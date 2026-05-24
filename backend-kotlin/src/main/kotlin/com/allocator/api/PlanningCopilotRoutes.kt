package com.allocator.api

import com.allocator.Cases
import com.allocator.config
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

The plan config has three groups plus two post-plan UI toggles: **method_selection** (how make/move/buy methods are chosen), **purchase_allowed** (top-level boolean), **consolidation** (demand grouping / bucket settings), **analyze_criticality** (top-level boolean, post-plan auto-analysis), and **check_soundness** (top-level boolean, post-plan auto-validation). Any of these can appear in `config_update`. (BOM variants are modeled as distinct make methods, so there is no separate variant config.)

Intent → config mapping (interpret any phrasing that conveys the same intent):

1) **Set max methods per demand / how many methods to try / waterfall cap** (e.g. "max methods 2", "max method 3", "use up to 2 methods", "最多方法 2", "方法上限 3")
   → method_selection: { "max_methods": N } where N ≥ 1. Default 2. Waterfall semantics: slot 1 plans the full demand on the best-ranked method; slot 2 picks up whatever's left if the first hit capacity; etc. Inventory carries forward across slots. `max_methods=1` disables the fallback entirely. Older synonyms like "equal split across methods" / "use multiple methods" / "split across make/move/buy" map to `max_methods: 2` (intent: more than one method may be used).

2) **Use only one best method per demand / pick one method / by preference / no fallback**
   → method_selection: { "max_methods": 1, "mode": "preference" }. Engine picks one method by the preference number; no second slot.

3) **Elaborate method selection / score methods (make/move/buy) by criteria** (slower run)
   → method_selection: { "mode": "elaborate" }. Methods are scored rather than picked by preference.

4) **Set the elaborate search depth to N** (e.g. "method depth 3", "search depth 2", "方法深度 3", "深度 2")
   → method_selection: { "depth": N } (clamp N ≥ 1). Only meaningful when mode is elaborate.

4d) **Set max BOM depth for make-fallback admission to N** (e.g. "max BOM depth 4", "make-fallback depth 2", "最大BOM深度 3")
    → method_selection: { "max_bom_depth": N } (clamp 1..10). Default 3. Caps the recursion depth admitted at the reactive make-fallback site; deeper makes are skipped without recursing.

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
    method_selection: { "multiple": false, "mode": "preference", "depth": 1 }, purchase_allowed: false, consolidation: { "enabled": true, "period_days": 365, "allocation_mode": "fair" }, analyze_criticality: false, check_soundness: true.

14) **Enable criticality analysis after plan** (e.g. "analyze criticality", "criticality on", "open criticality", "启用关键度", "做关键度分析", "开启临界分析")
    → analyze_criticality: true. Auto-saves the run and runs criticality after each plan.

15) **Disable criticality analysis** (e.g. "no criticality", "skip criticality", "关闭关键度", "不做关键度", "停用临界分析")
    → analyze_criticality: false.

16) **Enable post-plan soundness check** (e.g. "check soundness", "validate plan", "soundness on", "开启完整性校验", "校验完整性", "做合理性检查")
    → check_soundness: true. Auto-runs the deep soundness check after each plan; result shown as a badge on the run row.

17) **Disable post-plan soundness check** (e.g. "no soundness check", "skip soundness", "soundness off", "关闭完整性校验", "不做合理性检查")
    → check_soundness: false.

Valid config_update keys:
- method_selection: object with optional "multiple" (bool), "mode" ("preference" | "elaborate"), "depth" (int ≥ 1), "max_methods" (int ≥ 1; default 2; waterfall cap), "max_bom_depth" (int 1..10; default 3; make-fallback admission cap), "score_weights" ({ commit_time, inventory_consumed, purchase } — numeric, backend normalizes).
- purchase_allowed: boolean (top-level, not nested).
- consolidation: object with optional "enabled" (bool), "period_days" (int 0..365; 0 = single bucket), "allocation_mode" ("fair" | "proportional" | "priority_first").
- analyze_criticality: boolean (top-level). Post-plan UI toggle, does not affect the planner itself.
- check_soundness: boolean (top-level). Post-plan UI toggle, does not affect the planner itself.

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
        // Route through the shared allocator LLM pin (default nanogpt; controlled
        // by ALLOCATOR_LLM_PROVIDER). Kept distinct from the global LLM_PROVIDER
        // so the generic llmChat default path can target a different provider.
        llmChat(
            systemPrompt = SYSTEM_PROMPT,
            messages = msgs,
            maxTokens = 500,
            provider = config.allocatorLlmProvider,
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
        val ac = cu["analyze_criticality"]?.jsonPrimitive?.booleanOrNull
        val sc = cu["check_soundness"]?.jsonPrimitive?.booleanOrNull
        val msOk = ms?.let { "multiple" in it || "elaborate" in it || "mode" in it || "depth" in it || "max_methods" in it || "max_bom_depth" in it || "score_weights" in it } == true
        val csOk = cs?.let { "enabled" in it || "period_days" in it || "allocation_mode" in it } == true
        val paOk = pa != null
        val acOk = ac != null
        val scOk = sc != null
        if (msOk || csOk || paOk || acOk || scOk) cu else null
    }
    return reply to valid
}

// ── Rule-based fallback (mirrors planning_copilot.py::_rule_based_parse) ─────
//
// Replies are bilingual: when the user's message contains any CJK Unified
// Ideographs, the Chinese variant is returned; otherwise English. This matches
// the LLM path's "mirror the user's language" behavior so the experience is
// consistent whether or not LLM_PROVIDER is configured.

private fun isChineseInput(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF }

/** Pick reply by input language. `raw` is the original user message (pre-lowercased). */
private fun bi(en: String, zh: String, raw: String): String = if (isChineseInput(raw)) zh else en

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
        return bi(
            "You can tell me how you'd like planning to behave—for example \"max methods 2\", " +
                "\"max BOM depth 4\", \"use elaborate method selection\", \"check soundness off\", " +
                "or \"allow purchase\". You can also ask to see the current settings.",
            "您可以告诉我希望规划如何运行 — 例如「最多方法 2」、「最大 BOM 深度 4」、" +
                "「使用精细方法选择」、「关闭完整性校验」、或「允许采购」。您也可以让我显示当前配置。",
            raw,
        ) to null
    }

    if (Regex("show|current|what('s| is)? (my )?config|settings|config|显示配置|当前配置|查看配置").containsMatchIn(t) ||
        Regex("显示配置|当前配置|查看配置").containsMatchIn(raw)
    ) {
        val zh = isChineseInput(raw)
        val parts = mutableListOf<String>()
        val msElaborate = ms["elaborate"]?.jsonPrimitive?.booleanOrNull == true ||
            ms["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() == "elaborate"
        val depth = ms["depth"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
        val maxMethods = ms["max_methods"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            ?: if (ms["multiple"]?.jsonPrimitive?.booleanOrNull == false) 1 else 2
        when {
            msElaborate -> {
                val sw = ms["score_weights"] as? JsonObject
                val wc = sw?.get("commit_time")?.jsonPrimitive?.doubleOrNull
                val wi = sw?.get("inventory_consumed")?.jsonPrimitive?.doubleOrNull
                val wp = sw?.get("purchase")?.jsonPrimitive?.doubleOrNull
                val depthLabel = if (zh) "深度 $depth" else "depth $depth"
                val label = when {
                    wc != null && wi != null && wp != null -> {
                        val weightDesc = if (zh) when {
                            wc > 0 && wi == 0.0 && wp == 0.0 -> "，最早交付"
                            wi > 0 && wc == 0.0 && wp == 0.0 -> "，最多库存"
                            wp > 0 && wc == 0.0 && wi == 0.0 -> "，最少采购"
                            else -> "，权重 交付=${"%.2f".format(wc)} 库存=${"%.2f".format(wi)} 采购=${"%.2f".format(wp)}"
                        } else when {
                            wc > 0 && wi == 0.0 && wp == 0.0 -> ", earliest delivery"
                            wi > 0 && wc == 0.0 && wp == 0.0 -> ", most inventory"
                            wp > 0 && wc == 0.0 && wi == 0.0 -> ", least purchase"
                            else -> ", weights commit=${"%.2f".format(wc)} inv=${"%.2f".format(wi)} purch=${"%.2f".format(wp)}"
                        }
                        if (zh) "精细方法选择（$depthLabel$weightDesc）" else "elaborate method selection ($depthLabel$weightDesc)"
                    }
                    else -> if (zh) "精细方法选择（$depthLabel）" else "elaborate method selection ($depthLabel)"
                }
                parts.add(label)
            }
            else -> parts.add(if (zh) "按偏好选择方法（单一最优）" else "method by preference (single best)")
        }
        parts.add(if (zh) "最多方法数 $maxMethods" else "max methods $maxMethods")
        val maxBomDepth = ms["max_bom_depth"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 3
        parts.add(if (zh) "最大 BOM 深度 $maxBomDepth" else "max BOM depth $maxBomDepth")
        parts.add(if (purchaseAllowed == false) (if (zh) "禁用采购" else "purchase disabled") else (if (zh) "允许采购" else "purchase allowed"))
        val csEnabled = cs["enabled"]?.jsonPrimitive?.booleanOrNull
        if (csEnabled == false) {
            parts.add(if (zh) "合并关闭" else "consolidation off")
        } else {
            val days = cs["period_days"]?.jsonPrimitive?.intOrNull ?: 365
            val mode = cs["allocation_mode"]?.jsonPrimitive?.contentOrNull ?: "fair"
            val modeLabel = if (zh) when (mode) {
                "proportional" -> "按比例拆分"
                "priority_first" -> "优先级优先拆分"
                else -> "公平拆分"
            } else when (mode) {
                "proportional" -> "proportional split"
                "priority_first" -> "priority-first split"
                else -> "fair split"
            }
            val bucketLabel = if (zh) {
                if (days == 0) "单桶" else "${days}天桶"
            } else {
                if (days == 0) "single bucket" else "$days-day bucket"
            }
            parts.add(if (zh) "合并开启（$bucketLabel，$modeLabel）" else "consolidation on ($bucketLabel, $modeLabel)")
        }
        val analyzeCriticality = current["analyze_criticality"]?.jsonPrimitive?.booleanOrNull == true
        val checkSoundness = current["check_soundness"]?.jsonPrimitive?.booleanOrNull != false  // default true
        parts.add(if (zh) (if (analyzeCriticality) "关键度分析开启" else "关键度分析关闭")
                  else  (if (analyzeCriticality) "criticality analysis on" else "criticality analysis off"))
        parts.add(if (zh) (if (checkSoundness) "完整性校验开启" else "完整性校验关闭")
                  else  (if (checkSoundness) "soundness check on" else "soundness check off"))
        val sep = if (zh) "、" else ", "
        val desc = if (parts.isEmpty()) (if (zh) "默认" else "default") else parts.joinToString(sep)
        return bi(
            "Right now we're using $desc. If you'd like to switch, just say so—e.g. \"max methods 2\", \"elaborate methods\", \"method depth 3\", \"max BOM depth 4\", \"allow purchase\", \"check soundness off\", or \"disable consolidation\".",
            "当前配置：$desc。如需切换，告诉我即可 — 例如「最多方法 2」、「精细方法」、「方法深度 3」、「最大 BOM 深度 4」、「允许采购」、「关闭完整性校验」或「禁用合并」。",
            raw,
        ) to null
    }

    // ── Max BOM depth (e.g. "max bom depth 4", "make-fallback depth 2", "最大BOM深度 3") ──
    val bomDepthMatch = Regex("max(?:imum)?\\s*bom\\s*depth\\s*(?:=|:|to)?\\s*(\\d+)|make[- ]fallback\\s*depth\\s*(?:=|:|to)?\\s*(\\d+)").find(t)
    val zhBomDepthMatch = Regex("最大\\s*BOM\\s*深度\\s*[:=]?\\s*(\\d+)|BOM\\s*深度\\s*[:=]?\\s*(\\d+)").find(raw)
    if (bomDepthMatch != null || zhBomDepthMatch != null) {
        val n = (bomDepthMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: zhBomDepthMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: "3").toIntOrNull() ?: 3
        val d = n.coerceIn(1, 10)
        return bi(
            "Setting max BOM depth to $d. Caps the recursion depth admitted at the make-fallback site; deeper makes are skipped. Re-run plan to apply.",
            "已将最大 BOM 深度设为 $d。该值约束 make-fallback 的递归深度，更深的 make 将被跳过。请重新运行计划以生效。",
            raw,
        ) to mergeMethodSelection(current, mapOf("max_bom_depth" to JsonPrimitive(d)))
    }

    // ── Method depth (e.g. "method depth 3", "depth to 2", "方法深度 3", "深度 2") ──
    val depthMatch = Regex("(?:method\\s+)?depth\\s*(?:=|:|to)?\\s*(\\d+)").find(t)
    val zhDepthMatch = Regex("方法深度\\s*[:=]?\\s*(\\d+)|深度\\s*[:=]?\\s*(\\d+)").find(raw)
    if (depthMatch != null || zhDepthMatch != null) {
        val n = (depthMatch?.groupValues?.get(1)
            ?: zhDepthMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: "1").toIntOrNull() ?: 1
        val d = n.coerceAtLeast(1)
        return bi(
            "Setting elaborate method depth to $d. Re-run plan to apply (depth only takes effect when method mode is elaborate).",
            "已将精细方法深度设为 $d。请重新运行计划以生效（仅在方法模式为「精细」时生效）。",
            raw,
        ) to mergeMethodSelection(current, mapOf("depth" to JsonPrimitive(d)))
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
        return bi(
            "Weighting elaborate scoring toward earliest commit time (commit=1, inventory=0, purchase=0). Re-run plan to apply (takes effect when method mode is elaborate).",
            "精细评分权重已设为优先「最早交付」（交付=1，库存=0，采购=0）。请重新运行计划以生效（仅在方法模式为「精细」时生效）。",
            raw,
        ) to mergeMethodSelection(current, mapOf("score_weights" to scoreWeightsPatch(1.0, 0.0, 0.0)))
    }
    if (Regex("inventor(y|ies)|existing (stock|inventory|supply)|use (what we have|existing|current)|most (existing |current )?inventory|consume (more |existing )?inventory|prefer (existing |current )?stock").containsMatchIn(t) ||
        Regex("优先(使用)?库存|消耗库存|现有库存").containsMatchIn(raw)
    ) {
        return bi(
            "Weighting elaborate scoring toward most inventory consumed (commit=0, inventory=1, purchase=0). Re-run plan to apply (takes effect when method mode is elaborate).",
            "精细评分权重已设为优先「使用现有库存」（交付=0，库存=1，采购=0）。请重新运行计划以生效（仅在方法模式为「精细」时生效）。",
            raw,
        ) to mergeMethodSelection(current, mapOf("score_weights" to scoreWeightsPatch(0.0, 1.0, 0.0)))
    }
    if (Regex("minimum additional (supply|supplies)|minim(ize|ise) (additional |new )?(supply|supplies|purchase|buy)|least (additional |new )?(supply|supplies|purchase)|avoid (new )?purchase|avoid (new )?buy").containsMatchIn(t) ||
        Regex("最少采购|减少采购|避免采购").containsMatchIn(raw)
    ) {
        return bi(
            "Weighting elaborate scoring toward least purchase (commit=0, inventory=0, purchase=1). Re-run plan to apply (takes effect when method mode is elaborate).",
            "精细评分权重已设为优先「最少采购」（交付=0，库存=0，采购=1）。请重新运行计划以生效（仅在方法模式为「精细」时生效）。",
            raw,
        ) to mergeMethodSelection(current, mapOf("score_weights" to scoreWeightsPatch(0.0, 0.0, 1.0)))
    }

    // ── Max methods (waterfall cap) ──
    val maxMethodsMatch = Regex("max\\s*methods?\\s*(?:=|:|to)?\\s*(\\d+)").find(t)
    val zhMaxMethodsMatch = Regex("最多方法\\s*[:=]?\\s*(\\d+)|方法上限\\s*[:=]?\\s*(\\d+)").find(raw)
    if (maxMethodsMatch != null || zhMaxMethodsMatch != null) {
        val n = (maxMethodsMatch?.groupValues?.get(1)
            ?: zhMaxMethodsMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: "2").toIntOrNull() ?: 2
        val capped = n.coerceIn(1, 4)
        return bi(
            "Setting max methods to $capped. Each demand may use up to $capped ranked methods (waterfall: slot 1 plans the full demand, slot 2 picks up whatever's left if slot 1 hit capacity). Inventory carries forward. Re-run plan to apply.",
            "已将「最多方法数」设为 $capped。每个需求最多尝试 $capped 个排序后的方法（瀑布式：第 1 个先承担全部需求，第 2 个接管剩余部分，依此类推）。库存按顺序结转。请重新运行计划以生效。",
            raw,
        ) to mergeMethodSelection(current, mapOf("max_methods" to JsonPrimitive(capped)))
    }

    // ── Legacy "equal split" / "split across methods" → mapped to max_methods=2 ──
    if (Regex("equal(ly)? (split|distribute)|split across (all )?methods?|divide (across|among) methods?|all methods?|every method|distribute.*methods?|workload across methods?").containsMatchIn(t) ||
        Regex("方法.*平均|方法.*等分|方法均分|按方法平均分配").containsMatchIn(raw)
    ) {
        return bi(
            "Setting max methods to 2 (waterfall): slot 1 plans the full demand on the best-ranked method, slot 2 picks up whatever's left if the first hit capacity. The old proportional 'equal split' was removed because it was exponentially expensive on real BOMs. Re-run plan to apply.",
            "已将「最多方法数」设为 2（瀑布式）：第 1 个方法先承担全部需求，第 2 个接管剩余部分。原先的按比例「等量拆分」因在真实 BOM 上代价过大已移除。请重新运行计划以生效。",
            raw,
        ) to mergeMethodSelection(current, mapOf("max_methods" to JsonPrimitive(2)))
    }

    if (Regex("elaborate method|score methods?|method selection.*score|methods? by (commit|inventory|purchase)|turn on elaborate|use elaborate method|enable elaborate|elaborate mode").containsMatchIn(t) ||
        Regex("精细方法|方法评分|按评分选方法|精细模式").containsMatchIn(raw)
    ) {
        return bi(
            "Turning on elaborate method selection. Methods (make/move/buy) will be scored rather than picked by preference. Re-run plan to apply (this mode is slower).",
            "已开启精细方法选择。方法（生产/转移/采购）将按评分排序，而非按偏好挑选。请重新运行计划以生效（该模式较慢）。",
            raw,
        ) to mergeMethodSelection(current, mapOf("mode" to JsonPrimitive("elaborate")))
    }

    if (Regex("simple method|preference only|methods? by preference|turn off elaborate|disable elaborate|use simple method|prefer methods? by preference|preference mode").containsMatchIn(t) ||
        Regex("按偏好|偏好方法|偏好模式|级联方法").containsMatchIn(raw)
    ) {
        return bi(
            "Using method selection by preference (single best). The planner will pick make/move/buy by the preference number only. Re-run plan to apply.",
            "已切换为按偏好选择方法（单一最优）。规划器将仅按偏好编号挑选生产/转移/采购方法。请重新运行计划以生效。",
            raw,
        ) to mergeMethodSelection(current, mapOf("mode" to JsonPrimitive("preference"), "multiple" to JsonPrimitive(false)))
    }

    // ── Purchase toggle ──
    if (Regex("no purchase|disable purchase|disallow purchase|no buy|exclude buy|without purchase|forbid purchase").containsMatchIn(t) ||
        Regex("禁用采购|不采购|不允许采购|关闭采购").containsMatchIn(raw)
    ) {
        return bi(
            "Disabling purchase: the planner will not use buy as a fulfillment method. Re-run plan to apply.",
            "已禁用采购：规划器不会使用采购作为满足方法。请重新运行计划以生效。",
            raw,
        ) to JsonObject(mapOf("purchase_allowed" to JsonPrimitive(false)))
    }
    if (Regex("allow purchase|enable purchase|purchase allowed|include buy|with purchase|use purchase|permit purchase|turn on (buy|purchase)").containsMatchIn(t) ||
        Regex("允许采购|启用采购|开启采购|可以(使用)?采购|使用采购").containsMatchIn(raw)
    ) {
        return bi(
            "Enabling purchase: buy is now an allowed fulfillment method. Re-run plan to apply.",
            "已启用采购：采购现在是允许的满足方法。请重新运行计划以生效。",
            raw,
        ) to JsonObject(mapOf("purchase_allowed" to JsonPrimitive(true)))
    }

    // ── Consolidation enable/disable ──
    if (Regex("disable consolidat|turn off consolidat|no consolidat|without consolidat").containsMatchIn(t) ||
        Regex("禁用合并|关闭合并|不合并").containsMatchIn(raw)
    ) {
        return bi(
            "Disabling consolidation: each demand will get its own work orders again. Re-run plan to apply.",
            "已禁用合并：每个需求将再次获得各自的生产订单。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf("enabled" to JsonPrimitive(false)))
    }
    if (Regex("enable consolidat|turn on consolidat|consolidate demand|group demand|shared.?component").containsMatchIn(t) ||
        Regex("启用合并|开启合并|合并需求|共享组件").containsMatchIn(raw)
    ) {
        val days = cs["period_days"]?.jsonPrimitive?.intOrNull ?: 365
        val mode = cs["allocation_mode"]?.jsonPrimitive?.contentOrNull ?: "fair"
        val bucketLabel = if (days == 0) "single-bucket" else "$days-day bucket"
        val zhBucketLabel = if (days == 0) "单桶" else "${days}天桶"
        val zhMode = when (mode) { "proportional" -> "按比例"; "priority_first" -> "优先级优先"; else -> "公平" }
        return bi(
            "Enabling consolidation ($bucketLabel, $mode split). Demands for the same component in the same bucket will share work orders. Re-run plan to apply.",
            "已启用合并（$zhBucketLabel，$zhMode 拆分）。同一桶内共享同一组件的需求将共用生产订单。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf("enabled" to JsonPrimitive(true)))
    }

    // ── Consolidation single-bucket / period_days = 0 ──
    if (Regex("single (bucket|period)|one (bucket|period)|no (bucket|period)|merge all|consolidate all").containsMatchIn(t) ||
        Regex("单桶|单一桶|不分桶|全部合并|合并所有").containsMatchIn(raw)
    ) {
        return bi(
            "Setting consolidation to single-bucket (period_days=0): every demand collapses into one bucket regardless of due date. Re-run plan to apply.",
            "已将合并设为单桶（period_days=0）：所有需求合并为同一桶，不区分到期日。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf("period_days" to JsonPrimitive(0)))
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
        val zhMsg = if (days == 0) "单桶" else "${days}天"
        return bi(
            "Setting consolidation bucket to $msg. Demands within that window may share work orders. Re-run plan to apply.",
            "已将合并时间桶设为 $zhMsg。同一时间窗口内的需求可共用生产订单。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf("period_days" to JsonPrimitive(days)))
    }

    // ── Consolidation allocation_mode ──
    if (Regex("proportional|split by (qty|quantity|share)|by share").containsMatchIn(t) ||
        Regex("按比例|按数量|按份额").containsMatchIn(raw)
    ) {
        return bi(
            "Using proportional split: shared work orders are divided across demands in proportion to their requested quantity. Re-run plan to apply.",
            "采用按比例拆分：共享生产订单按各需求请求数量的比例分配。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf("allocation_mode" to JsonPrimitive("proportional")))
    }
    if (Regex("priority.?first|fill highest priority|by priority|priority order").containsMatchIn(t) ||
        Regex("优先级优先|按优先级|优先级顺序").containsMatchIn(raw)
    ) {
        return bi(
            "Using priority-first split: highest-priority demands get filled first from shared work orders. Re-run plan to apply.",
            "采用优先级优先拆分：共享生产订单先满足优先级最高的需求。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf("allocation_mode" to JsonPrimitive("priority_first")))
    }
    if (Regex("\\bfair\\b|hybrid split|no one starved").containsMatchIn(t) ||
        Regex("公平|混合拆分").containsMatchIn(raw)
    ) {
        return bi(
            "Using fair split: priority guides the order but every demand gets some share—no one is starved. Re-run plan to apply.",
            "采用公平拆分：优先级决定顺序，但每个需求都能得到一份 — 没人被饿死。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf("allocation_mode" to JsonPrimitive("fair")))
    }

    // ── Post-plan UI toggles: analyze_criticality ──
    if (Regex("(?:analyz|analys)e?\\s*criticality|criticality\\s*(on|enable|analysis)|enable\\s*criticality").containsMatchIn(t) ||
        Regex("启用关键度|做关键度|开启关键度|开启临界|做临界").containsMatchIn(raw)
    ) {
        return bi(
            "Enabling criticality analysis: every successful plan will be saved and analyzed automatically.",
            "已启用关键度分析：每次成功的规划都会自动保存并分析。",
            raw,
        ) to JsonObject(mapOf("analyze_criticality" to JsonPrimitive(true)))
    }
    if (Regex("(no|skip|disable|turn off)\\s*criticality|criticality\\s*off").containsMatchIn(t) ||
        Regex("关闭关键度|不做关键度|停用关键度|不分析关键度|关闭临界").containsMatchIn(raw)
    ) {
        return bi(
            "Disabling criticality analysis.",
            "已关闭关键度分析。",
            raw,
        ) to JsonObject(mapOf("analyze_criticality" to JsonPrimitive(false)))
    }

    // ── Post-plan UI toggles: check_soundness ──
    if (Regex("(check|validate|verify|run)\\s*soundness|soundness\\s*(check\\s*)?(on|enable)|enable\\s*soundness").containsMatchIn(t) ||
        Regex("开启完整性|校验完整性|做合理性|检查合理性|开启校验").containsMatchIn(raw)
    ) {
        return bi(
            "Enabling post-plan soundness check: every successful plan will be auto-validated (deep check). Result shown as a badge on the run row.",
            "已启用计划后完整性校验：每次成功的规划都会自动深度校验，结果以徽章形式显示在该运行旁。",
            raw,
        ) to JsonObject(mapOf("check_soundness" to JsonPrimitive(true)))
    }
    if (Regex("(no|skip|disable|turn off)\\s*soundness|soundness\\s*off").containsMatchIn(t) ||
        Regex("关闭完整性|不做合理性|跳过校验|不校验").containsMatchIn(raw)
    ) {
        return bi(
            "Disabling post-plan soundness check.",
            "已关闭计划后完整性校验。",
            raw,
        ) to JsonObject(mapOf("check_soundness" to JsonPrimitive(false)))
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
            put("analyze_criticality", false)
            put("check_soundness", true)
        }
        return bi(
            "Reset to defaults: method by preference, purchase disabled, consolidation on (365-day bucket, fair split), criticality off, soundness check on.",
            "已重置为默认：按偏好方法、禁用采购、合并开启（365 天桶，公平拆分）、关键度关闭、完整性校验开启。",
            raw,
        ) to patch
    }

    return bi(
        "I'm not sure I caught that. I can configure: method selection (preference / elaborate, depth, max methods), purchase allowed, consolidation (on/off, bucket days, split mode), and the post-plan toggles (analyze criticality, check soundness). What would you like?",
        "我不太理解。我可以配置：方法选择（偏好 / 精细，深度，最多方法数）、是否允许采购、合并（开/关、桶天数、拆分模式），以及计划后开关（关键度分析、完整性校验）。您想做什么？",
        raw,
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
