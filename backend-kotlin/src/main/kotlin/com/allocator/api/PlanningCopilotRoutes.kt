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

The plan config has four groups: **variant_selection**, **method_selection**, **purchase_allowed** (top-level boolean), and **consolidation** (demand grouping / bucket settings). Any of these can appear in `config_update`.

Intent → config mapping (interpret any phrasing that conveys the same intent):

1) **Treat multiple alternatives equally / split demand across options / distribute workload / use all options when multiple exist** (for both variants and methods)
   → variant_selection: { "multiple": true }, method_selection: { "multiple": true }. (Variants: demand split equally across all feasible variants; integer qty when demand is integer. Methods: demand split equally across all feasible methods make/move/buy.)
   In your reply, say you're applying equal split to **both** variants and methods.

2) **Use only one best option per demand** (e.g. single best variant, pick one, prefer one)
   → variant_selection: { "multiple": false }. (Engine picks one by: earliest commit, most inventory used, least purchase.)

3) **Prioritize existing inventory / use what we have / consume more stock** (even if delivery is later)
   → variant_selection: { "multiple": false, "score_weights": { "commit_time": 0, "inventory_consumed": 1, "purchase": 0 } }.

4) **Minimize new purchases / least additional supply / avoid new buy**
   → variant_selection: { "multiple": false, "score_weights": { "commit_time": 0, "inventory_consumed": 0, "purchase": 1 } }.

5) **Earliest delivery / fastest commit**
   → variant_selection: { "multiple": false, "score_weights": { "commit_time": 1, "inventory_consumed": 0, "purchase": 0 } }.

6) **Split among top N variants** (e.g. top 2, top 3), optionally **by least supply / by inventory**
   → variant_selection: { "multiple": true, "top_n": N }. Add "score_weights" only if they specify a criterion (e.g. least supply => purchase: 1).

7) **Score methods (make/move/buy) by same criteria as variants** (slower run)
   → method_selection: { "elaborate": true }. **Use preference only for methods** → method_selection: { "elaborate": false }.

8) **Allow / enable / use / permit purchase (buy)** (e.g. "allow purchase", "enable buy", "可以使用采购", "允许采购", "启用采购", "开启采购")
   → purchase_allowed: true.

9) **Disallow / disable / forbid purchase (buy)** (e.g. "no purchase", "disable purchase", "without buy", "禁用采购", "不采购", "不允许采购")
   → purchase_allowed: false.

10) **Enable demand consolidation / group demands / share work orders across demands** (e.g. "enable consolidation", "consolidate demand", "启用合并", "开启合并", "合并需求", "共享组件")
    → consolidation: { "enabled": true }.

11) **Disable consolidation / turn it off / do not group** (e.g. "禁用合并", "关闭合并", "不合并")
    → consolidation: { "enabled": false }.

12) **Set the consolidation bucket / period / window to N days** (e.g. "30 day bucket", "60天窗口", "period 90 days")
    → consolidation: { "period_days": N } (clamp 1..365).

13) **Consolidation split policy — proportional / by share / by quantity** (e.g. "proportional split", "按比例", "按数量", "按份额")
    → consolidation: { "allocation_mode": "proportional" }.

14) **Consolidation split policy — priority-first / fill highest priority first** (e.g. "priority first", "by priority", "优先级优先", "按优先级")
    → consolidation: { "allocation_mode": "priority_first" }.

15) **Consolidation split policy — fair / hybrid / no one starved** (e.g. "fair split", "公平", "混合拆分")
    → consolidation: { "allocation_mode": "fair" }.

16) **Reset / clear / default** → full defaults:
    variant_selection: { "multiple": true }, method_selection: { "multiple": false, "elaborate": false }, purchase_allowed: false, consolidation: { "enabled": true, "period_days": 365, "allocation_mode": "fair" }.

Valid config_update keys:
- variant_selection: object with optional "multiple" (bool), "top_n" (int), "score_weights" ({ commit_time, inventory_consumed, purchase } — numeric, normalized to sum 1).
- method_selection: object with optional "multiple" (bool), "elaborate" (bool).
- purchase_allowed: boolean (top-level, not nested).
- consolidation: object with optional "enabled" (bool), "period_days" (int 1..365), "allocation_mode" ("fair" | "proportional" | "priority_first").

Respond with valid JSON only, no markdown code fences:
- "reply": string (required). Acknowledge their intent in their words, say what you set, and mention re-run plan if you changed config. If the user asks to see the current config, describe all four groups (variants, methods, purchase, consolidation) from the supplied current_config.
- "config_update": object (optional). Include when you inferred a clear intent. Merge by setting only the keys that change.

Interpret freely: e.g. "equally distribute among alternatives if present", "treat multiple alternatives equally", "handle workload", "when there are multiple ways do X", "split across options", "use all options" → all map to (1). "可以使用采购" / "use purchase" / "turn on buying" → (8). Always mention both variants and methods in your reply for (1). Never say you only handle specific phrases."""

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
        llmChat(systemPrompt = SYSTEM_PROMPT, messages = msgs, maxTokens = 500).trim()
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
        val vs = cu["variant_selection"] as? JsonObject
        val ms = cu["method_selection"] as? JsonObject
        val cs = cu["consolidation"] as? JsonObject
        val pa = cu["purchase_allowed"]?.jsonPrimitive?.booleanOrNull
        val vsOk = vs?.let { "multiple" in it || "score_weights" in it || "top_n" in it } == true
        val msOk = ms?.let { "multiple" in it || "elaborate" in it } == true
        val csOk = cs?.let { "enabled" in it || "period_days" in it || "allocation_mode" in it } == true
        val paOk = pa != null
        if (vsOk || msOk || csOk || paOk) cu else null
    }
    return reply to valid
}

// ── Rule-based fallback (mirrors planning_copilot.py::_rule_based_parse) ─────

private fun mergeVariantSelection(current: JsonObject, patch: Map<String, JsonElement>): JsonObject {
    val existing = (current["variant_selection"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    existing.putAll(patch)
    return JsonObject(mapOf("variant_selection" to JsonObject(existing)))
}

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

private fun scoreWeights(commit: Int, inventory: Int, purchase: Int): JsonObject = JsonObject(
    mapOf(
        "commit_time" to JsonPrimitive(commit),
        "inventory_consumed" to JsonPrimitive(inventory),
        "purchase" to JsonPrimitive(purchase),
    )
)

private fun ruleBasedParse(message: String, current: JsonObject): Pair<String, JsonObject?> {
    val raw = message.trim()
    val t = raw.lowercase()
    val vs = (current["variant_selection"] as? JsonObject) ?: JsonObject(emptyMap())
    val ms = (current["method_selection"] as? JsonObject) ?: JsonObject(emptyMap())
    val cs = (current["consolidation"] as? JsonObject) ?: JsonObject(emptyMap())
    val purchaseAllowed = current["purchase_allowed"]?.jsonPrimitive?.booleanOrNull

    if (t.isBlank()) {
        return (
            "You can tell me how you'd like planning to behave—for example prefer a single best variant per demand, " +
                "or split demand across all feasible variants. You can also ask to see the current settings."
            ) to null
    }

    if (Regex("show|current|what('s| is)? (my )?config|settings|config|显示配置|当前配置|查看配置").containsMatchIn(t) ||
        Regex("显示配置|当前配置|查看配置").containsMatchIn(raw)
    ) {
        val parts = mutableListOf<String>()
        val multi = vs["multiple"]?.jsonPrimitive?.booleanOrNull
        if (multi == false) {
            parts.add("single best variant")
        } else {
            val topN = vs["top_n"]?.jsonPrimitive?.intOrNull
            if (topN != null && topN >= 1) parts.add("top $topN variants (split demand among them)")
            else parts.add("all feasible variants (equal split)")
        }
        val sw = vs["score_weights"] as? JsonObject
        if (sw != null) {
            val p = sw["purchase"]?.jsonPrimitive?.intOrNull ?: 0
            val i = sw["inventory_consumed"]?.jsonPrimitive?.intOrNull ?: 0
            val c = sw["commit_time"]?.jsonPrimitive?.intOrNull ?: 0
            when {
                p > 0 && i == 0 && c == 0 -> parts.add("ranked by least additional supply")
                i > 0 && p == 0 && c == 0 -> parts.add("ranked by most existing inventory used")
                c > 0 && i == 0 && p == 0 -> parts.add("ranked by earliest commit time")
                p > 0 || i > 0 || c > 0 -> parts.add("with custom score weights")
            }
        }
        when {
            ms["multiple"]?.jsonPrimitive?.booleanOrNull == true ->
                parts.add("method equal split (demand divided across all feasible methods)")
            ms["elaborate"]?.jsonPrimitive?.booleanOrNull == true ->
                parts.add("elaborate method selection (methods scored by commit/inventory/purchase, same weights as variants)")
            else -> parts.add("simple method selection (methods by preference only)")
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
            parts.add("consolidation on ($days-day bucket, $modeLabel)")
        }
        val desc = if (parts.isEmpty()) "default" else parts.joinToString(", ")
        return (
            "Right now we're using $desc. If you'd like to switch, just say so—e.g. \"use single variant\", \"split across all\", \"allow purchase\", or \"disable consolidation\"."
            ) to null
    }

    // Split among top N variants
    val topNMatch = Regex("top\\s+(\\d+)\\s+variants?").find(t)
    if (Regex("split|among|divide").containsMatchIn(t) && topNMatch != null) {
        val n = topNMatch.groupValues[1].toInt().coerceIn(1, 99)
        return if (Regex("least (additional |new )?(supply|supplies|purchase)|minimum additional|minim(ize|ise) (additional |new )?(supply|supplies|purchase)").containsMatchIn(t)) {
            "Splitting demand among the top $n variants ranked by least additional supply—the $n variants that need the least new purchase will share the demand equally. Re-run plan to apply." to
                mergeVariantSelection(current, mapOf(
                    "multiple" to JsonPrimitive(true),
                    "top_n" to JsonPrimitive(n),
                    "score_weights" to scoreWeights(0, 0, 1),
                ))
        } else {
            "Splitting demand among the top $n variants (by current score). Each will get an equal share. Re-run plan to apply." to
                mergeVariantSelection(current, mapOf(
                    "multiple" to JsonPrimitive(true),
                    "top_n" to JsonPrimitive(n),
                ))
        }
    }

    if (Regex("minimum additional (supply|supplies)|minim(ize|ise) (additional |new )?(supply|supplies|purchase|buy)|least (additional |new )?(supply|supplies|purchase)|use minimum additional").containsMatchIn(t)) {
        return "Changing the score weight distribution so that minimizing new purchase gets all the weight—we'll prefer variants that need the least additional supply, at the expense of fulfillment time and inventory use. Re-run plan to apply." to
            mergeVariantSelection(current, mapOf(
                "multiple" to JsonPrimitive(false),
                "score_weights" to scoreWeights(0, 0, 1),
            ))
    }

    if (Regex("inventor(y|ies)|existing (stock|inventory|supply)|use (what we have|existing|current)|most (existing |current )?inventory|minim(ize|ise) (buy|purchase)|least (buy|purchase)|consume (more |existing )?inventory|prefer (existing |current )?stock").containsMatchIn(t)) {
        return "Changing the score weight distribution so that inventory consumption gets all the weight—at the expense of fulfillment time. The planner will prefer variants that use the most existing inventory; commit times may be later. Re-run plan to apply." to
            mergeVariantSelection(current, mapOf(
                "multiple" to JsonPrimitive(false),
                "score_weights" to scoreWeights(0, 1, 0),
            ))
    }

    if (Regex("earliest commit|earliest (delivery|fulfillment|time)|fastest|select.*(alternative|option|variant).*earliest|prefer.*earliest|commit time.*(earliest|first)|minim(ize|ise) (commit )?time").containsMatchIn(t)) {
        return "Using single best with earliest commit time as the only criterion—the planner will pick the alternative (variant or method when scored) that commits soonest. Re-run plan to apply." to
            mergeVariantSelection(current, mapOf(
                "multiple" to JsonPrimitive(false),
                "score_weights" to scoreWeights(1, 0, 0),
            ))
    }

    if (Regex("single|one variant|only one|best variant|use one").containsMatchIn(t)) {
        return "Got it—I'll use single best variant. The planner will pick one option per demand using earliest commit, most inventory consumed, and least new purchase. Re-run plan to apply." to
            mergeVariantSelection(current, mapOf("multiple" to JsonPrimitive(false)))
    }

    if (Regex("all variants|split|multiple variants|every variant|equal split|divide (across|among)|equally distribute|distribute equally|(demand )?among multiple alternatives|multiple alternatives.*(equal|distribute)|equal(ly)? (split|distribute)|alternatives if present|treat multiple alternatives equally|workload").containsMatchIn(t)) {
        val patch = JsonObject(mapOf(
            "variant_selection" to JsonObject((vs.toMutableMap()).also { it["multiple"] = JsonPrimitive(true) }),
            "method_selection" to JsonObject((ms.toMutableMap()).also { it["multiple"] = JsonPrimitive(true) }),
        ))
        return "Treating multiple alternatives equally for variants and methods: variants use an equal split across all feasible options (integer quantities when demand is integer); methods use an equal split across all feasible methods (make/move/buy) when multiple can fulfill a demand. Re-run plan to apply." to patch
    }

    if (Regex("elaborate method|score methods?|method selection.*score|methods? by (commit|inventory|purchase)|turn on elaborate|use elaborate method|enable elaborate").containsMatchIn(t) ||
        Regex("精细方法|方法评分|按评分选方法").containsMatchIn(raw)
    ) {
        return "Turning on elaborate method selection. Methods (make/move/buy) will be scored by the same criteria as variants: earliest commit time, most inventory consumed, least new purchase—using the same score weights you set for variant selection. Re-run plan to apply (this mode is slower)." to
            mergeMethodSelection(current, mapOf("elaborate" to JsonPrimitive(true)))
    }

    if (Regex("simple method|preference only|methods? by preference|turn off elaborate|disable elaborate|use simple method|prefer methods? by preference").containsMatchIn(t) ||
        Regex("按偏好|偏好方法|级联方法").containsMatchIn(raw)
    ) {
        return "Using simple method selection (preference only). The planner will pick make/move/buy by the preference number only, not by scoring. Re-run plan to apply." to
            mergeMethodSelection(current, mapOf("elaborate" to JsonPrimitive(false)))
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
        return "Enabling consolidation ($days-day bucket, $mode split). Demands for the same component in the same bucket will share work orders. Re-run plan to apply." to
            mergeConsolidation(current, mapOf("enabled" to JsonPrimitive(true)))
    }

    // ── Consolidation bucket / period_days ──
    val periodMatch = Regex("(\\d+)\\s*(?:-\\s*)?days?\\s*(?:bucket|period|window)").find(t)
    val zhPeriodMatch = Regex("(\\d+)\\s*天(?:桶|窗口|周期)?").find(raw)
    val bucketMatch = Regex("(?:bucket|period)\\D*(\\d+)|时间桶\\D*(\\d+)").find(t + " " + raw)
    if (periodMatch != null || zhPeriodMatch != null || bucketMatch != null) {
        val n = (periodMatch?.groupValues?.get(1)
            ?: zhPeriodMatch?.groupValues?.get(1)
            ?: bucketMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: "365").toIntOrNull() ?: 365
        val days = n.coerceIn(1, 365)
        return "Setting consolidation bucket to $days day${if (days == 1) "" else "s"}. Demands within that window may share work orders. Re-run plan to apply." to
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
            put("variant_selection", buildJsonObject { put("multiple", true) })
            put("method_selection", buildJsonObject { put("multiple", false); put("elaborate", false) })
            put("purchase_allowed", false)
            put("consolidation", buildJsonObject {
                put("enabled", true)
                put("period_days", 365)
                put("allocation_mode", "fair")
            })
        }
        return "Reset to defaults: all feasible variants (equal split), simple method selection, purchase disabled, consolidation on (365-day bucket, fair split). Re-run plan to apply." to patch
    }

    return (
        "I'm not sure I caught that. I can configure four things: variant selection (single best / split / top-N), method selection (preference / elaborate / equal-split), purchase allowed, and consolidation (on/off, bucket days, split mode). What would you like?"
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
