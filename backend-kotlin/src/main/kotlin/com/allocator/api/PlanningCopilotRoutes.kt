package com.allocator.api

import com.allocator.Cases
import com.allocator.services.LlmMessage
import com.allocator.services.LlmNotConfiguredException
import com.allocator.services.llmChat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.PlanningCopilotRoutes")

// ── Request / response shapes ────────────────────────────────────────────────
//
// Frontend contract (mirrors the original Python allocator_inno):
//   POST /cases/{caseId}/planning-copilot
//   body: { "message": str, "current_config": object, "history": [{role, text}] }
//   reply: { "reply": str, "config_update": object | null }
// PlanningConfig is treated as opaque JSON here; the frontend owns the schema.

@Serializable
private data class CopilotHistoryItem(
    val role: String,
    val text: String? = null,
    val content: String? = null,
) {
    fun messageText(): String = text ?: content ?: ""
}

@Serializable
private data class PlanningCopilotRequest(
    val message: String? = null,
    @SerialName("current_config") val currentConfig: JsonObject? = null,
    val history: List<CopilotHistoryItem>? = null,
)

@Serializable
private data class PlanningCopilotResponse(
    val reply: String,
    @SerialName("config_update") val configUpdate: JsonObject? = null,
)

// ── LLM prompt ───────────────────────────────────────────────────────────────

private const val SYSTEM_PROMPT = """You are a friendly planning configuration assistant. Users express their requirements in many different ways, in English or Chinese. Your job is to infer their intent from whatever wording they use—do not expect or require specific phrases. Be conversational and natural. Mirror the user's language (reply in Chinese if they wrote in Chinese).

The plan config has three groups: **method_selection** (how make/move/buy methods are chosen), **purchase_allowed** (top-level boolean), and **consolidation** (demand grouping / bucket settings). Any of these can appear in `config_update`. (BOM variants are modeled as distinct make methods, so there is no separate variant config.)

Intent → config mapping (interpret any phrasing that conveys the same intent):

1) **Treat multiple methods equally / split demand across make/move/buy / distribute workload across all feasible methods**
   → method_selection: { "multiple": true }. Demand is split equally across all feasible methods (make/move/buy).

2) **Use only one best method per demand / pick one method / by preference**
   → method_selection: { "multiple": false, "mode": "preference" }. Engine picks one method by the preference number.

3) **Elaborate method selection / score methods (make/move/buy) by criteria** (slower run)
   → method_selection: { "mode": "elaborate" }. Methods are scored rather than picked by preference.

4) **Set the elaborate search depth to N** (e.g. "method depth 3", "search depth 2", "方法深度 3", "深度 2")
   → method_selection: { "depth": N } (clamp N ≥ 1). Only meaningful when mode is elaborate.

4d) **Optimal / auto depth — let the planner pick the best depth** (e.g. "optimal depth", "auto depth", "find best depth", "自动深度", "最优深度")
    → method_selection: { "mode": "elaborate", "depth_optimal": true }. Planner iterates depth=1,2,3… and stops at the first depth where the weighted plan score does not improve. Capped at 10. Re-run plan to apply.

4e) **Manual / fixed depth — disable auto-depth** (e.g. "fixed depth", "manual depth", "固定深度")
    → method_selection: { "depth_optimal": false }.

4a) **Earliest delivery / fastest commit** (weights-only intent; only meaningful when mode is elaborate)
    → method_selection: { "score_weights": { "commit_time": 1, "inventory_consumed": 0, "purchase": 0 } }.

4b) **Prioritize existing inventory / use what we have / consume more stock**
    → method_selection: { "score_weights": { "commit_time": 0, "inventory_consumed": 1, "purchase": 0 } }.

4c) **Minimize new purchases / least additional supply / avoid new buy**
    → method_selection: { "score_weights": { "commit_time": 0, "inventory_consumed": 0, "purchase": 1 } }.

5) **Allow / enable / use / permit purchase (buy)** (e.g. "allow purchase", "enable buy", "可以使用采购", "允许采购", "启用采购", "开启采购")
   → purchase_allowed: true.

6) **Disallow / disable / forbid purchase (buy)** (e.g. "no purchase", "disable purchase", "without buy", "禁用采购", "不采购", "不允许采购")
   → purchase_allowed: false.

7) **Enable demand consolidation / group demands / share work orders across demands** (e.g. "enable consolidation", "consolidate demand", "启用合并", "开启合并", "合并需求", "共享组件")
   → consolidation: { "enabled": true }.

8) **Disable consolidation / turn it off / do not group** (e.g. "禁用合并", "关闭合并", "不合并")
   → consolidation: { "enabled": false }.

9) **Set the consolidation bucket / period / window to N days** (e.g. "30 day bucket", "60天窗口", "period 90 days", "single bucket", "全部合并")
   → consolidation: { "period_days": N } (clamp 0..365). 0 = single bucket (collapse every demand into one bucket regardless of due date).

10) **Consolidation split policy — proportional / by share / by quantity** (e.g. "proportional split", "按比例", "按数量", "按份额")
    → consolidation: { "allocation_mode": "proportional" }.

11) **Consolidation split policy — priority-first / fill highest priority first** (e.g. "priority first", "by priority", "优先级优先", "按优先级")
    → consolidation: { "allocation_mode": "priority_first" }.

12) **Consolidation split policy — fair / hybrid / no one starved** (e.g. "fair split", "公平", "混合拆分")
    → consolidation: { "allocation_mode": "fair" }.

13) **Reset / clear / default** → full defaults:
    method_selection: { "multiple": false, "mode": "preference", "depth": 1 }, purchase_allowed: false, consolidation: { "enabled": true, "period_days": 365, "allocation_mode": "fair" }.

Valid config_update keys:
- method_selection: object with optional "multiple" (bool), "mode" ("preference" | "elaborate"), "depth" (int ≥ 1), "depth_optimal" (bool), "score_weights" ({ commit_time, inventory_consumed, purchase } — numeric, backend normalizes).
- purchase_allowed: boolean (top-level, not nested).
- consolidation: object with optional "enabled" (bool), "period_days" (int 0..365; 0 = single bucket), "allocation_mode" ("fair" | "proportional" | "priority_first").

Respond with valid JSON only, no markdown code fences:
- "reply": string (required). Acknowledge their intent in their words, say what you set, and mention re-run plan if you changed config. If the user asks to see the current config, describe all three groups (methods, purchase, consolidation) from the supplied current_config.
- "config_update": object (optional). Include when you inferred a clear intent. Merge by setting only the keys that change.

Interpret freely: e.g. "equally distribute across methods", "handle workload across make/move/buy", "split across options" → (1). "可以使用采购" / "use purchase" / "turn on buying" → (5). Never say you only handle specific phrases."""

private suspend fun llmParse(
    message: String,
    currentConfig: JsonObject,
    history: List<CopilotHistoryItem>,
): Pair<String, JsonObject?>? {
    val msgs = buildList<LlmMessage> {
        add(LlmMessage("user", "Current config: $currentConfig"))
        history.takeLast(10).forEach { h ->
            val t = h.messageText()
            if (t.isNotBlank()) add(LlmMessage(h.role, t))
        }
        add(LlmMessage("user", message))
    }

    val text = try {
        // Pin to OpenAI regardless of global LLM_PROVIDER — see ASSESSMENT_PROVIDER in
        // AssessmentRoutes.kt for the rationale.
        llmChat(
            systemPrompt = SYSTEM_PROMPT,
            messages = msgs,
            maxTokens = 500,
            provider = "openai",
        ).trim()
    } catch (e: LlmNotConfiguredException) {
        log.info("Planning copilot: {}, using rule-based fallback", e.message)
        return null
    } catch (e: Exception) {
        log.warn("Planning copilot: LLM call failed ({}), using rule-based fallback", e.toString())
        return null
    }
    if (text.isBlank()) return null

    // Strip ```json fences if the model returned them despite the instruction.
    val cleaned = text
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()

    val parsed = runCatching { Json.parseToJsonElement(cleaned).jsonObject }.getOrNull() ?: return null
    val reply = parsed["reply"]?.jsonPrimitive?.contentOrNull
        ?: "I didn't quite get that. Could you rephrase?"
    val configUpdate = parsed["config_update"] as? JsonObject
    val valid = configUpdate?.let { cu ->
        val ms = cu["method_selection"] as? JsonObject
        val cs = cu["consolidation"] as? JsonObject
        val pa = cu["purchase_allowed"]?.jsonPrimitive?.booleanOrNull
        val msOk = ms?.let { "multiple" in it || "elaborate" in it || "mode" in it || "depth" in it || "depth_optimal" in it || "score_weights" in it } == true
        val csOk = cs?.let { "enabled" in it || "period_days" in it || "allocation_mode" in it } == true
        val paOk = pa != null
        if (msOk || csOk || paOk) cu else null
    }
    return reply to valid
}

// ── Rule-based fallback (mirrors planning_copilot.py::_rule_based_parse) ─────

private fun mergeMethodSelection(current: JsonObject, patch: Map<String, JsonElement>): JsonObject {
    val existing = (current["method_selection"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    existing.putAll(patch)
    return JsonObject(mapOf("method_selection" to JsonObject(existing)))
}

private fun mergeConsolidation(current: JsonObject, patch: Map<String, JsonElement>): JsonObject {
    val existing = (current["consolidation"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    existing.putAll(patch)
    return JsonObject(mapOf("consolidation" to JsonObject(existing)))
}

private fun ruleBasedParse(message: String, current: JsonObject): Pair<String, JsonObject?> {
    val raw = message.trim()
    val t = raw.lowercase()
    val ms = (current["method_selection"] as? JsonObject) ?: JsonObject(emptyMap())
    val cs = (current["consolidation"] as? JsonObject) ?: JsonObject(emptyMap())
    val purchaseAllowed = current["purchase_allowed"]?.jsonPrimitive?.booleanOrNull

    if (t.isBlank()) {
        return (
            "You can tell me how you'd like planning to behave—for example use elaborate method selection, " +
                "split demand across all feasible methods, or allow purchase. You can also ask to see the current settings."
            ) to null
    }

    if (Regex("show|current|what('s| is)? (my )?config|settings|config|显示配置|当前配置|查看配置").containsMatchIn(t) ||
        Regex("显示配置|当前配置|查看配置").containsMatchIn(raw)
    ) {
        val parts = mutableListOf<String>()
        val msElaborate = ms["elaborate"]?.jsonPrimitive?.booleanOrNull == true ||
            ms["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() == "elaborate"
        val depth = ms["depth"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
        when {
            ms["multiple"]?.jsonPrimitive?.booleanOrNull == true ->
                parts.add("method equal split (demand divided across all feasible methods)")
            msElaborate -> {
                val sw = ms["score_weights"] as? JsonObject
                val wc = sw?.get("commit_time")?.jsonPrimitive?.doubleOrNull
                val wi = sw?.get("inventory_consumed")?.jsonPrimitive?.doubleOrNull
                val wp = sw?.get("purchase")?.jsonPrimitive?.doubleOrNull
                val depthOptimal = ms["depth_optimal"]?.jsonPrimitive?.booleanOrNull == true
                val depthLabel = if (depthOptimal) "auto-depth (optimal search)" else "depth $depth"
                val label = when {
                    wc != null && wi != null && wp != null -> {
                        val weightDesc = when {
                            wc > 0 && wi == 0.0 && wp == 0.0 -> ", earliest delivery"
                            wi > 0 && wc == 0.0 && wp == 0.0 -> ", most inventory"
                            wp > 0 && wc == 0.0 && wi == 0.0 -> ", least purchase"
                            else -> ", weights commit=${"%.2f".format(wc)} inv=${"%.2f".format(wi)} purch=${"%.2f".format(wp)}"
                        }
                        "elaborate method selection ($depthLabel$weightDesc)"
                    }
                    else -> "elaborate method selection ($depthLabel)"
                }
                parts.add(label)
            }
            else -> parts.add("method by preference (single best)")
        }
        parts.add(if (purchaseAllowed == false) "purchase disabled" else "purchase allowed")
        val csEnabled = cs["enabled"]?.jsonPrimitive?.booleanOrNull
        if (csEnabled == false) {
            parts.add("consolidation off")
        } else {
            val days = cs["period_days"]?.jsonPrimitive?.intOrNull ?: 365
            val mode = cs["allocation_mode"]?.jsonPrimitive?.contentOrNull ?: "fair"
            val modeLabel = when (mode) {
                "proportional" -> "proportional split"
                "priority_first" -> "priority-first split"
                else -> "fair split"
            }
            val bucketLabel = if (days == 0) "single bucket" else "$days-day bucket"
            parts.add("consolidation on ($bucketLabel, $modeLabel)")
        }
        val desc = if (parts.isEmpty()) "default" else parts.joinToString(", ")
        return (
            "Right now we're using $desc. If you'd like to switch, just say so—e.g. \"elaborate methods\", \"method depth 3\", \"split across all methods\", \"allow purchase\", or \"disable consolidation\"."
            ) to null
    }

    // ── Optimal depth search (auto-pick depth) ──
    if (Regex("optimal depth|auto depth|auto-depth|best depth|find depth|search depth").containsMatchIn(t) ||
        Regex("自动深度|最优深度|最佳深度").containsMatchIn(raw)
    ) {
        return "Enabling optimal depth search: planner will iterate depth=1, 2, 3 … and stop at the first depth where the weighted plan score does not improve. Capped at 10. Re-run plan to apply." to
            mergeMethodSelection(current, mapOf("mode" to JsonPrimitive("elaborate"), "depth_optimal" to JsonPrimitive(true)))
    }
    if (Regex("manual depth|fixed depth|disable optimal depth|disable auto[- ]depth|turn off auto[- ]depth").containsMatchIn(t) ||
        Regex("固定深度|手动深度|关闭自动深度|关闭最优深度").containsMatchIn(raw)
    ) {
        return "Disabling optimal depth search. Planner will use the fixed depth value." to
            mergeMethodSelection(current, mapOf("depth_optimal" to JsonPrimitive(false)))
    }

    // ── Method depth (e.g. "method depth 3", "depth to 2", "方法深度 3", "深度 2") ──
    val depthMatch = Regex("(?:method\\s+)?depth\\s*(?:=|:|to)?\\s*(\\d+)").find(t)
    val zhDepthMatch = Regex("方法深度\\s*[:=]?\\s*(\\d+)|深度\\s*[:=]?\\s*(\\d+)").find(raw)
    if (depthMatch != null || zhDepthMatch != null) {
        val n = (depthMatch?.groupValues?.get(1)
            ?: zhDepthMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: "1").toIntOrNull() ?: 1
        val d = n.coerceAtLeast(1)
        return "Setting elaborate method depth to $d. Re-run plan to apply (depth only takes effect when method mode is elaborate)." to
            mergeMethodSelection(current, mapOf("depth" to JsonPrimitive(d), "depth_optimal" to JsonPrimitive(false)))
    }

    // ── Score weights (only applicable when elaborate mode is on) ──
    fun scoreWeightsPatch(commit: Double, inv: Double, purchase: Double): JsonElement = JsonObject(mapOf(
        "commit_time" to JsonPrimitive(commit),
        "inventory_consumed" to JsonPrimitive(inv),
        "purchase" to JsonPrimitive(purchase),
    ))
    if (Regex("earliest commit|earliest (delivery|fulfillment|time)|fastest|prefer.*earliest|commit time.*(earliest|first)|minim(ize|ise) (commit )?time").containsMatchIn(t) ||
        Regex("最早交付|最快交付|最早提交").containsMatchIn(raw)
    ) {
        return "Weighting elaborate scoring toward earliest commit time (commit=1, inventory=0, purchase=0). Re-run plan to apply (takes effect when method mode is elaborate)." to
            mergeMethodSelection(current, mapOf("score_weights" to scoreWeightsPatch(1.0, 0.0, 0.0)))
    }
    if (Regex("inventor(y|ies)|existing (stock|inventory|supply)|use (what we have|existing|current)|most (existing |current )?inventory|consume (more |existing )?inventory|prefer (existing |current )?stock").containsMatchIn(t) ||
        Regex("优先(使用)?库存|消耗库存|现有库存").containsMatchIn(raw)
    ) {
        return "Weighting elaborate scoring toward most inventory consumed (commit=0, inventory=1, purchase=0). Re-run plan to apply (takes effect when method mode is elaborate)." to
            mergeMethodSelection(current, mapOf("score_weights" to scoreWeightsPatch(0.0, 1.0, 0.0)))
    }
    if (Regex("minimum additional (supply|supplies)|minim(ize|ise) (additional |new )?(supply|supplies|purchase|buy)|least (additional |new )?(supply|supplies|purchase)|avoid (new )?purchase|avoid (new )?buy").containsMatchIn(t) ||
        Regex("最少采购|减少采购|避免采购").containsMatchIn(raw)
    ) {
        return "Weighting elaborate scoring toward least purchase (commit=0, inventory=0, purchase=1). Re-run plan to apply (takes effect when method mode is elaborate)." to
            mergeMethodSelection(current, mapOf("score_weights" to scoreWeightsPatch(0.0, 0.0, 1.0)))
    }

    // ── Equal split across methods ──
    if (Regex("equal(ly)? (split|distribute)|split across (all )?methods?|divide (across|among) methods?|all methods?|every method|distribute.*methods?|workload across methods?").containsMatchIn(t) ||
        Regex("方法.*平均|方法.*等分|方法均分|按方法平均分配").containsMatchIn(raw)
    ) {
        return "Splitting demand equally across all feasible methods (make/move/buy). Re-run plan to apply." to
            mergeMethodSelection(current, mapOf("multiple" to JsonPrimitive(true)))
    }

    if (Regex("elaborate method|score methods?|method selection.*score|methods? by (commit|inventory|purchase)|turn on elaborate|use elaborate method|enable elaborate|elaborate mode").containsMatchIn(t) ||
        Regex("精细方法|方法评分|按评分选方法|精细模式").containsMatchIn(raw)
    ) {
        return "Turning on elaborate method selection. Methods (make/move/buy) will be scored rather than picked by preference. Re-run plan to apply (this mode is slower)." to
            mergeMethodSelection(current, mapOf("mode" to JsonPrimitive("elaborate")))
    }

    if (Regex("simple method|preference only|methods? by preference|turn off elaborate|disable elaborate|use simple method|prefer methods? by preference|preference mode").containsMatchIn(t) ||
        Regex("按偏好|偏好方法|偏好模式|级联方法").containsMatchIn(raw)
    ) {
        return "Using method selection by preference (single best). The planner will pick make/move/buy by the preference number only. Re-run plan to apply." to
            mergeMethodSelection(current, mapOf("mode" to JsonPrimitive("preference"), "multiple" to JsonPrimitive(false)))
    }

    // ── Purchase toggle ──
    if (Regex("no purchase|disable purchase|disallow purchase|no buy|exclude buy|without purchase|forbid purchase").containsMatchIn(t) ||
        Regex("禁用采购|不采购|不允许采购|关闭采购").containsMatchIn(raw)
    ) {
        return "Disabling purchase: the planner will not use buy as a fulfillment method. Re-run plan to apply." to
            JsonObject(mapOf("purchase_allowed" to JsonPrimitive(false)))
    }
    if (Regex("allow purchase|enable purchase|purchase allowed|include buy|with purchase|use purchase|permit purchase|turn on (buy|purchase)").containsMatchIn(t) ||
        Regex("允许采购|启用采购|开启采购|可以(使用)?采购|使用采购").containsMatchIn(raw)
    ) {
        return "Enabling purchase: buy is now an allowed fulfillment method. Re-run plan to apply." to
            JsonObject(mapOf("purchase_allowed" to JsonPrimitive(true)))
    }

    // ── Consolidation enable/disable ──
    if (Regex("disable consolidat|turn off consolidat|no consolidat|without consolidat").containsMatchIn(t) ||
        Regex("禁用合并|关闭合并|不合并").containsMatchIn(raw)
    ) {
        return "Disabling consolidation: each demand will get its own work orders again. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("enabled" to JsonPrimitive(false)))
    }
    if (Regex("enable consolidat|turn on consolidat|consolidate demand|group demand|shared.?component").containsMatchIn(t) ||
        Regex("启用合并|开启合并|合并需求|共享组件").containsMatchIn(raw)
    ) {
        val days = cs["period_days"]?.jsonPrimitive?.intOrNull ?: 365
        val mode = cs["allocation_mode"]?.jsonPrimitive?.contentOrNull ?: "fair"
        val bucketLabel = if (days == 0) "single-bucket" else "$days-day bucket"
        return "Enabling consolidation ($bucketLabel, $mode split). Demands for the same component in the same bucket will share work orders. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("enabled" to JsonPrimitive(true)))
    }

    // ── Consolidation single-bucket / period_days = 0 ──
    if (Regex("single (bucket|period)|one (bucket|period)|no (bucket|period)|merge all|consolidate all").containsMatchIn(t) ||
        Regex("单桶|单一桶|不分桶|全部合并|合并所有").containsMatchIn(raw)
    ) {
        return "Setting consolidation to single-bucket (period_days=0): every demand collapses into one bucket regardless of due date. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("period_days" to JsonPrimitive(0)))
    }

    // ── Consolidation bucket / period_days ──
    val periodMatch = Regex("(\\d+)\\s*(?:-\\s*)?days?\\s*(?:bucket|period|window)").find(t)
    val zhPeriodMatch = Regex("(\\d+)\\s*天(?:桶|窗口|周期)?").find(raw)
    val bucketMatch = Regex("(?:bucket|period)\\D*(\\d+)|时间桶\\D*(\\d+)").find(t + " " + raw)
    if (periodMatch != null || zhPeriodMatch != null || bucketMatch != null) {
        val n = (periodMatch?.groupValues?.get(1)
            ?: zhPeriodMatch?.groupValues?.get(1)
            ?: bucketMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: "0").toIntOrNull() ?: 0
        val days = n.coerceIn(0, 365)
        val msg = if (days == 0) "single-bucket" else "$days day${if (days == 1) "" else "s"}"
        return "Setting consolidation bucket to $msg. Demands within that window may share work orders. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("period_days" to JsonPrimitive(days)))
    }

    // ── Consolidation allocation_mode ──
    if (Regex("proportional|split by (qty|quantity|share)|by share").containsMatchIn(t) ||
        Regex("按比例|按数量|按份额").containsMatchIn(raw)
    ) {
        return "Using proportional split: shared work orders are divided across demands in proportion to their requested quantity. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("allocation_mode" to JsonPrimitive("proportional")))
    }
    if (Regex("priority.?first|fill highest priority|by priority|priority order").containsMatchIn(t) ||
        Regex("优先级优先|按优先级|优先级顺序").containsMatchIn(raw)
    ) {
        return "Using priority-first split: highest-priority demands get filled first from shared work orders. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("allocation_mode" to JsonPrimitive("priority_first")))
    }
    if (Regex("\\bfair\\b|hybrid split|no one starved").containsMatchIn(t) ||
        Regex("公平|混合拆分").containsMatchIn(raw)
    ) {
        return "Using fair split: priority guides the order but every demand gets some share—no one is starved. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("allocation_mode" to JsonPrimitive("fair")))
    }

    // ── Reset ──
    if (Regex("reset|default|clear").containsMatchIn(t) ||
        Regex("重置|默认|清除").containsMatchIn(raw)
    ) {
        val patch = buildJsonObject {
            put("method_selection", buildJsonObject {
                put("multiple", false)
                put("mode", "preference")
                put("depth", 1)
                put("score_weights", buildJsonObject {
                    put("commit_time", 0.4)
                    put("inventory_consumed", 0.35)
                    put("purchase", 0.25)
                })
            })
            put("purchase_allowed", false)
            put("consolidation", buildJsonObject {
                put("enabled", true)
                put("period_days", 365)
                put("allocation_mode", "fair")
            })
        }
        return "Reset to defaults: method by preference, purchase disabled, consolidation on (365-day bucket, fair split). Re-run plan to apply." to patch
    }

    return (
        "I'm not sure I caught that. I can configure three things: method selection (preference / elaborate / equal-split, plus elaborate depth), purchase allowed, and consolidation (on/off, bucket days, split mode). What would you like?"
        ) to null
}

// ── Route ────────────────────────────────────────────────────────────────────

fun Routing.planningCopilotRoutes() {
    post("/cases/{caseId}/planning-copilot") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")

        val exists = transaction {
            Cases.selectAll().where { Cases.id eq caseId }.any()
        }
        if (!exists) {
            call.respond(HttpStatusCode.NotFound, mapOf("detail" to "Case not found"))
            return@post
        }

        val req = call.receive<PlanningCopilotRequest>()
        val message = req.message?.trim().orEmpty()
        if (message.isEmpty()) {
            throw IllegalArgumentException("message is required")
        }
        val currentConfig = req.currentConfig ?: JsonObject(emptyMap())
        val history = req.history ?: emptyList()

        val (reply, configUpdate) = llmParse(message, currentConfig, history)
            ?: ruleBasedParse(message, currentConfig)

        call.respond(PlanningCopilotResponse(reply = reply, configUpdate = configUpdate))
    }
}
