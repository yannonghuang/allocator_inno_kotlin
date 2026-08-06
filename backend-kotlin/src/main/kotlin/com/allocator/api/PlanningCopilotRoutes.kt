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

The plan config has three groups plus two post-plan UI toggles: **method_selection** (how make/move/buy methods are chosen), **purchase_allowed** (top-level boolean — purchasing on/off as a whole), **consolidation** (WO batch window — groups same-component work orders that start within N days into fewer larger orders), **analyze_criticality** (top-level boolean, post-plan auto-analysis), and **check_soundness** (top-level boolean, post-plan auto-validation). Any of these can appear in `config_update`. (BOM variants are modeled as distinct make methods, so there is no separate variant config.) Beyond these, a case also has 5 versioned external config objects (Critical Material Allocation, Supply Preferences, Demand Ordering, Purchasable Materials, Constraints) — see (18)-(20) below for how the copilot deals with those: version-picking for all five, plus regeneration for the two that are computed (Supply Preferences via parameter tuning, Critical Material Allocation on request). **Purchasable Materials CONTENT is never edited from this chat** — see (6s).

Intent → config mapping (interpret any phrasing that conveys the same intent):

1) **Set max methods per demand / how many methods to try / waterfall cap** (e.g. "max methods 2", "max method 3", "use up to 2 methods", "最多方法 2", "方法上限 3")
   → method_selection: { "max_methods": N } where N ≥ 1. Default 2. Waterfall semantics: slot 1 plans the full demand on the best-ranked method; slot 2 picks up whatever's left if the first hit capacity; etc. Inventory carries forward across slots. `max_methods=1` disables the fallback entirely. Older synonyms like "equal split across methods" / "use multiple methods" / "split across make/move/buy" map to `max_methods: 2` (intent: more than one method may be used).

2) **Use only one best method per demand / pick one method / by preference / no fallback**
   → method_selection: { "max_methods": 1, "mode": "preference" }. Engine picks one method by the preference number; no second slot.

Note: "elaborate"/scored method selection, method-selection "depth", "max_bom_depth", and "score_weights"
have been removed — the planner is preference-ranked waterfall only, at every BOM depth (no separate
scoring/probing step, no depth restriction). If a user asks for scored/elaborate method selection or
score weights, explain this was retired in favor of the simpler preference waterfall and do not set
these keys.

5) **Allow / enable / use / permit purchase (buy)** (e.g. "allow purchase", "enable buy", "可以使用采购", "允许采购", "启用采购", "开启采购")
   → purchase_allowed: true.

6) **Disallow / disable / forbid purchase (buy)** (e.g. "no purchase", "disable purchase", "without buy", "禁用采购", "不采购", "不允许采购")
   → purchase_allowed: false.

6s) **Selective purchase (WHICH raw materials may be bought) is not edited here.** The
   purchasable-materials whitelist is versioned case master data, maintained deliberately on
   the Purchasable Materials page — a chat message must never rewrite it. When the user asks to
   change which materials may be bought ("only buy the 1xx series", "不采购某供应商的材料",
   "all except aluminum"), emit NO config keys for it: explain in `reply` that the whitelist is
   edited on the Purchasable Materials page, and that a saved version can then be selected for
   a run here ("use purchasable materials version N" — see (18)). Turning purchasing on/off as
   a whole remains (5)/(6).

7) **Enable WO batching (consolidation)** (e.g. "enable consolidation", "batch work orders", "启用合并", "开启合并", "批量合并工单")
   → consolidation: { "make_batch_scale": "weekly", "move_batch_scale": "weekly", "purchase_batch_scale": "weekly", "period_days": 7 }.
   Consolidation itself always runs — there is no on/off flag; "enable" means setting all three types to a real (non-"none") scale.

8) **Disable WO batching (consolidation)** (e.g. "disable consolidation", "no batching", "禁用合并", "关闭合并", "不合并")
   → consolidation: { "make_batch_scale": "none", "move_batch_scale": "none", "purchase_batch_scale": "none" }.
   "Disable" means setting all three types to "none" — that is the real, per-type off-switch.

9) **Set WO batch window scale** — make/move/buy can each have independent scales.
   To set all at once: "weekly batches", "bi-weekly", "monthly window", "no batching", "all together", "每周批次", "每两周", "每月窗口", "不合并工单", "全部合并"
   → consolidation: { "make_batch_scale": "...", "move_batch_scale": "...", "purchase_batch_scale": "..." }
   To set per type: "set make monthly", "move orders weekly", "don't batch purchases", "制造每月", "调拨每周", "采购不合并"
   → consolidation: { "<type>_batch_scale": "none"|"weekly"|"biweekly"|"monthly"|"all" }
   Always also set "period_days" to the matching value (0/7/14/30/0) for supply-side consistency.
   Numeric requests (e.g. "30 day window") map to the nearest named scale: ≤7 → weekly, ≤14 → biweekly, else → monthly.

10) **Reset / clear / default** → full defaults:
    method_selection: { "multiple": false, "mode": "preference" }, purchase_allowed: false, consolidation: { "make_batch_scale": "weekly", "move_batch_scale": "weekly", "purchase_batch_scale": "weekly", "period_days": 7 }, analyze_criticality: false, check_soundness: true.

14) **Enable criticality analysis after plan** (e.g. "analyze criticality", "criticality on", "open criticality", "启用关键度", "做关键度分析", "开启临界分析")
    → analyze_criticality: true. Auto-saves the run and runs criticality after each plan.

15) **Disable criticality analysis** (e.g. "no criticality", "skip criticality", "关闭关键度", "不做关键度", "停用临界分析")
    → analyze_criticality: false.

16) **Enable post-plan soundness check** (e.g. "check soundness", "validate plan", "soundness on", "开启完整性校验", "校验完整性", "做合理性检查")
    → check_soundness: true. Auto-runs the deep soundness check after each plan; result shown as a badge on the run row.

17) **Disable post-plan soundness check** (e.g. "no soundness check", "skip soundness", "soundness off", "关闭完整性校验", "不做合理性检查")
    → check_soundness: false.

18) **Pick a version of an external config object.** Each case has 5 versioned external config
    objects — Critical Material Allocation, Supply Preferences, Demand Ordering, Purchasable
    Materials, Constraints — and a run can pin a specific version of each (e.g. "use allocation
    version 2", "plan with constraints version 6", "用偏好版本 3", "切换到需求排序版本 5"):
    → the matching key with the integer id the user named:
      case_alloc_version_id | pref_version_id | demand_order_version_id |
      purchasable_material_version_id | constraint_version_id
    Only when the user explicitly names a numeric version. The backend rejects ids that don't
    exist for that object. (To CHANGE purchasable materials content, use (6s); to TUNE
    Supply Preferences, use (19) — not this.)

19) **Tune Supply Preferences.** Preferences are a GENERATED ranking, tuned via weights on three
    scoring axes (delivery lead time / inventory coverage / critical-material usage) plus a BOM
    walk depth — the user tunes parameters, never edits ranking rows (e.g. "favor delivery
    speed", "weight inventory coverage higher", "rebuild preferences with bom depth 4",
    "偏好更看重交期", "重新生成偏好，库存权重 0.5"):
    → preference_tuning: {
        "delivery_weight": D,           // optional, 0..1
        "inventory_weight": I,          // optional, 0..1
        "critical_material_weight": C,  // optional, 0..1
        "max_bom_depth": N              // optional, 1..10
      }
    Emit only the parameters the user wants changed — the backend starts from the currently
    selected version's stored values, applies your changes, renormalizes the three weights to
    sum 1, regenerates the ranking as a NEW preferences version, and selects it for the next
    run. For qualitative asks ("favor X", "care more about Y") set that weight to a clearly
    dominant value (e.g. 0.6) and let renormalization handle the rest.

20) **Regenerate Critical Material Allocation** (e.g. "regenerate allocation", "refresh the critical raw allocation", "重新生成分配", "刷新关键原材料分配") — also the right move whenever the user just changed which materials are purchasable, since which supply positions are "critical" (and so get an allocation budget) depends on that. Acknowledge in `reply`; emit NO config_update key for this yourself — the backend detects the request from your wording directly and regenerates. (Do not confuse with (18)'s case_alloc_version_id, which only PICKS an existing version.)

Valid config_update keys:
- method_selection: object with optional "multiple" (bool, legacy), "mode" ("preference" — the only supported mode), "max_methods" (int ≥ 1; default 2; waterfall cap — how many ranked alternatives, across method type and BOM variant, to try before giving up).
- purchase_allowed: boolean (top-level, not nested).
- case_alloc_version_id / pref_version_id / demand_order_version_id / purchasable_material_version_id / constraint_version_id: integer (top-level) — see (18); explicit version picks only. Never emit a purchasable_materials array or per-material keys — whitelist content is not editable from this chat (see (6s)).
- preference_tuning: object (top-level) — see (19). The ONLY way to change Supply Preferences; never emit preference rows.
- consolidation: object with optional "make_batch_scale", "move_batch_scale", "purchase_batch_scale" (each "none"|"weekly"|"biweekly"|"monthly"|"all"; per-type scales — always runs, "none" is the real per-type off-switch), "wo_batch_scale" (legacy global fallback), "period_days" (int 0..365; supply-side bucket width; 0 = single bucket).
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
    // No catalog in the prompt: whitelist content is not editable from the copilot
    // at all (prompt §6s) — the model never sees product ids, so it can neither
    // hallucinate them nor be tempted to emit lists. Also keeps the prompt small.
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
    val valid = configUpdate?.let { cuRaw ->
        // Whitelist content is not editable from the copilot (prompt §6s): a raw
        // purchasable_materials list — and the retired purchasable_filter predicate —
        // are contract violations, dropped outright so a hallucinated id can never
        // touch the whitelist. Whitelist changes happen on the Purchasable Materials
        // page; runs pin them via purchasable_material_version_id (§18).
        val cu = JsonObject(cuRaw.toMutableMap().apply {
            remove("purchasable_materials")
            remove("purchasable_filter")
        })
        val ms = cu["method_selection"] as? JsonObject
        val cs = cu["consolidation"] as? JsonObject
        val pa = cu["purchase_allowed"]?.jsonPrimitive?.booleanOrNull
        val ac = cu["analyze_criticality"]?.jsonPrimitive?.booleanOrNull
        val sc = cu["check_soundness"]?.jsonPrimitive?.booleanOrNull
        val msOk = ms?.let { "multiple" in it || "elaborate" in it || "mode" in it || "depth" in it || "max_methods" in it || "max_bom_depth" in it || "score_weights" in it } == true
        val csOk = cs?.let { "period_days" in it || "wo_batch_scale" in it || "make_batch_scale" in it || "move_batch_scale" in it || "purchase_batch_scale" in it } == true
        val ptOk = cu["preference_tuning"] is JsonObject
        val verOk = EXTERNAL_VERSION_KEYS.keys.any { (cu[it] as? JsonPrimitive)?.intOrNull != null }
        val paOk = pa != null
        val acOk = ac != null
        val scOk = sc != null
        if (msOk || csOk || paOk || acOk || scOk || ptOk || verOk) cu else null
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

private fun ruleBasedParse(
    message: String,
    current: JsonObject,
): Pair<String, JsonObject?> {
    val raw = message.trim()
    val t = raw.lowercase()
    val ms = (current["method_selection"] as? JsonObject) ?: JsonObject(emptyMap())
    val cs = (current["consolidation"] as? JsonObject) ?: JsonObject(emptyMap())
    val purchaseAllowed = current["purchase_allowed"]?.jsonPrimitive?.booleanOrNull

    // ── Selective purchase — deliberately NOT editable from the copilot ──────
    // The whitelist is versioned case master data (PurchasableMaterials.kt); chat
    // only redirects to its page. Patterns stay material-specific so plain
    // "no purchase"/"不采购" still reaches the purchase-toggle branches below.
    if (Regex("purchasable|only buy|whitelist|all raw materials|no restriction|采购所有原材料|不限制采购|只采购|限制采购").containsMatchIn(t) ||
        Regex("只采购|限制采购|采购所有原材料|不限制采购").containsMatchIn(raw)
    ) {
        return bi(
            "Which raw materials may be bought is managed on the Purchasable Materials page (it's versioned case data, not a per-run setting). Save your whitelist there, then tell me e.g. \"use purchasable materials version 5\" to pin it for the next run.",
            "可采购的原材料在「可采购材料」页面维护（它是有版本的案例数据，不是单次运行参数）。请先在该页面保存白名单版本，然后告诉我例如「使用可采购材料版本 5」以用于下次运行。",
            raw,
        ) to null
    }

    if (t.isBlank()) {
        return bi(
            "You can tell me how you'd like planning to behave—for example \"max methods 2\", " +
                "\"check soundness off\", or \"allow purchase\". You can also ask to see the current settings.",
            "您可以告诉我希望规划如何运行 — 例如「最多方法 2」、" +
                "「关闭完整性校验」、或「允许采购」。您也可以让我显示当前配置。",
            raw,
        ) to null
    }

    if (Regex("show|current|what('s| is)? (my )?config|settings|config|显示配置|当前配置|查看配置").containsMatchIn(t) ||
        Regex("显示配置|当前配置|查看配置").containsMatchIn(raw)
    ) {
        val zh = isChineseInput(raw)
        val parts = mutableListOf<String>()
        val maxMethods = ms["max_methods"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            ?: if (ms["multiple"]?.jsonPrimitive?.booleanOrNull == false) 1 else 2
        val legacyElaborate = ms["elaborate"]?.jsonPrimitive?.booleanOrNull == true ||
            ms["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() == "elaborate"
        parts.add(if (zh) "按偏好瀑布式选择方法/BOM变体" else "preference-ranked waterfall (method + BOM variant)")
        if (legacyElaborate) {
            parts.add(if (zh) "（配置中残留的「精细」模式已废弃，不再生效）"
                      else "(a legacy 'elaborate' mode setting in this config is deprecated and has no effect)")
        }
        parts.add(if (zh) "最多方法数 $maxMethods" else "max methods $maxMethods")
        val whitelist = (current["purchasable_materials"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?: emptyList()
        parts.add(when {
            purchaseAllowed == false -> if (zh) "禁用采购" else "purchase disabled"
            whitelist.isNotEmpty() -> if (zh) "仅采购 ${whitelist.size} 种原材料（${whitelist.joinToString("、")}）"
                                      else "purchase only ${whitelist.size} materials (${whitelist.joinToString(", ")})"
            else -> if (zh) "允许采购（所有原材料）" else "purchase allowed (all raw materials)"
        })
        val globalScale = cs["wo_batch_scale"]?.jsonPrimitive?.contentOrNull
        val days = cs["period_days"]?.jsonPrimitive?.intOrNull ?: 7
        fun scaleLabel(key: String): String {
            val scale = cs[key]?.jsonPrimitive?.contentOrNull ?: globalScale
            return when (scale) {
                "none"     -> if (zh) "不合并" else "none"
                "weekly"   -> if (zh) "每周" else "weekly"
                "biweekly" -> if (zh) "每两周" else "bi-weekly"
                "monthly"  -> if (zh) "每月" else "monthly"
                "all"      -> if (zh) "全部" else "all"
                else       -> if (zh) { if (days == 0) "单桶" else "${days}天" } else { if (days == 0) "single bucket" else "${days}d" }
            }
        }
        val bucketLabel = "make:${scaleLabel("make_batch_scale")} move:${scaleLabel("move_batch_scale")} buy:${scaleLabel("purchase_batch_scale")}"
        parts.add(if (zh) "工单批量合并（$bucketLabel）" else "WO batching ($bucketLabel)")
        val analyzeCriticality = current["analyze_criticality"]?.jsonPrimitive?.booleanOrNull == true
        val checkSoundness = current["check_soundness"]?.jsonPrimitive?.booleanOrNull != false  // default true
        parts.add(if (zh) (if (analyzeCriticality) "关键度分析开启" else "关键度分析关闭")
                  else  (if (analyzeCriticality) "criticality analysis on" else "criticality analysis off"))
        parts.add(if (zh) (if (checkSoundness) "完整性校验开启" else "完整性校验关闭")
                  else  (if (checkSoundness) "soundness check on" else "soundness check off"))
        val sep = if (zh) "、" else ", "
        val desc = if (parts.isEmpty()) (if (zh) "默认" else "default") else parts.joinToString(sep)
        return bi(
            "Right now we're using $desc. If you'd like to switch, just say so—e.g. \"max methods 2\", \"allow purchase\", \"set 14-day batch window\", \"check soundness off\", or \"disable WO batching\".",
            "当前配置：$desc。如需切换，告诉我即可 — 例如「最多方法 2」、「允许采购」、「设置 14 天批量窗口」、「关闭完整性校验」或「禁用工单批量合并」。",
            raw,
        ) to null
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
            "Elaborate/scored method selection was retired — the planner now always uses a preference-ranked waterfall (across method type and BOM variant, at every BOM depth), no scoring/probing step. Nothing to change here.",
            "「精细/评分」方法选择已被移除 — 规划器现在始终采用按偏好排序的瀑布式选择（跨方法类型与 BOM 变体，作用于每一层 BOM），不再有评分/试探步骤。此项无需更改。",
            raw,
        ) to null
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

    // ── Consolidation enable/disable — consolidation always runs; "disable" and "enable" map
    // to the real per-type off-switch: setting all three batch scales to "none", or to a real
    // scale, respectively. ──
    if (Regex("disable consolidat|turn off consolidat|no consolidat|without consolidat").containsMatchIn(t) ||
        Regex("禁用合并|关闭合并|不合并").containsMatchIn(raw)
    ) {
        return bi(
            "Disabling WO batching: same-component work orders will no longer be merged (make/move/purchase batch scale set to none). Re-run plan to apply.",
            "已禁用工单批量合并：同一组件的工单将不再合并（制造/调拨/采购批量窗口设为不合并）。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf(
            "make_batch_scale" to JsonPrimitive("none"),
            "move_batch_scale" to JsonPrimitive("none"),
            "purchase_batch_scale" to JsonPrimitive("none"),
        ))
    }
    if (Regex("enable consolidat|turn on consolidat|batch work order|enable.*batch|wo batch").containsMatchIn(t) ||
        Regex("启用合并|开启合并|批量合并工单|开启批量").containsMatchIn(raw)
    ) {
        val scale = cs["wo_batch_scale"]?.jsonPrimitive?.contentOrNull ?: "weekly"
        val days = cs["period_days"]?.jsonPrimitive?.intOrNull ?: 7
        val bucketLabel = when (scale) {
            "weekly" -> "weekly"; "biweekly" -> "bi-weekly"; "monthly" -> "monthly"
            "all" -> "all (single bucket)"; else -> if (days == 0) "single-bucket" else "$days-day window"
        }
        val zhBucketLabel = when (scale) {
            "weekly" -> "每周"; "biweekly" -> "每两周"; "monthly" -> "每月"
            "all" -> "全部（单桶）"; else -> if (days == 0) "单桶" else "${days}天窗口"
        }
        return bi(
            "Enabling WO batching ($bucketLabel). Same-component work orders within the window will be merged into fewer larger orders. Re-run plan to apply.",
            "已启用工单批量合并（$zhBucketLabel）。同一时间窗口内同一组件的工单将合并为更少的大订单。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf(
            "make_batch_scale" to JsonPrimitive(scale),
            "move_batch_scale" to JsonPrimitive(scale),
            "purchase_batch_scale" to JsonPrimitive(scale),
            "period_days" to JsonPrimitive(days),
        ))
    }

    // ── WO batch scale — per-type patterns ──
    data class TypeMatch(val type: String, val enPattern: String, val zhPattern: String, val configKey: String, val zhLabel: String)
    val perTypeMatches = listOf(
        TypeMatch("make",     "make|manufactur|production", "制造|生产",   "make_batch_scale",     "制造"),
        TypeMatch("move",     "move|transfer|transit",      "调拨|移库",   "move_batch_scale",     "调拨"),
        TypeMatch("purchase", "purchas|buy|procurement",    "采购|外购",   "purchase_batch_scale", "采购"),
    )
    val scaleLower = t.lowercase()
    val combined = scaleLower + " " + raw
    for (tm in perTypeMatches) {
        if (!Regex(tm.enPattern).containsMatchIn(scaleLower) && !Regex(tm.zhPattern).containsMatchIn(raw)) continue
        val typeScale: String? = when {
            Regex("\\bnone\\b|no.?batch|不合并").containsMatchIn(combined) -> "none"
            Regex("\\ball\\b|全部").containsMatchIn(combined)              -> "all"
            Regex("bi.?weekly|every\\s+two\\s+weeks|每\\s*两\\s*周|双周").containsMatchIn(combined) -> "biweekly"
            Regex("\\bweekly\\b|每\\s*周").containsMatchIn(combined)      -> "weekly"
            Regex("\\bmonthly\\b|每\\s*月").containsMatchIn(combined)     -> "monthly"
            else -> null
        } ?: continue
        val days = when (typeScale) { "weekly" -> 7; "biweekly" -> 14; "monthly" -> 30; else -> 0 }
        val labelEn = mapOf("none" to "none", "weekly" to "weekly", "biweekly" to "bi-weekly", "monthly" to "monthly", "all" to "all")[typeScale]!!
        val labelZh = mapOf("none" to "不合并", "weekly" to "每周", "biweekly" to "每两周", "monthly" to "每月", "all" to "全部")[typeScale]!!
        return bi(
            "Setting ${tm.type} WO batch window to $labelEn. Re-run plan to apply.",
            "已将${tm.zhLabel}工单批量窗口设为${labelZh}。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf(
            tm.configKey to JsonPrimitive(typeScale),
            "period_days" to JsonPrimitive(days),
        ))
    }

    // ── WO batch scale — named calendar scale (sets all types at once) ──
    val woBatchScale: String? = when {
        Regex("\\bnone\\b|no.?batch|不合并工单").containsMatchIn(combined) -> "none"
        Regex("\\ball\\b|全部合并|合并所有|单桶|单一桶|不分桶").containsMatchIn(combined) -> "all"
        Regex("bi.?weekly|every\\s+two\\s+weeks|每\\s*两\\s*周|双周").containsMatchIn(combined) -> "biweekly"
        Regex("\\bweekly\\b|每\\s*周").containsMatchIn(combined) -> "weekly"
        Regex("\\bmonthly\\b|每\\s*月").containsMatchIn(combined) -> "monthly"
        else -> null
    }
    if (woBatchScale != null) {
        val days = when (woBatchScale) { "weekly" -> 7; "biweekly" -> 14; "monthly" -> 30; else -> 0 }
        val labelEn = mapOf("none" to "none", "weekly" to "weekly", "biweekly" to "bi-weekly", "monthly" to "monthly", "all" to "all (single bucket)")[woBatchScale]!!
        val labelZh = mapOf("none" to "不合并", "weekly" to "每周", "biweekly" to "每两周", "monthly" to "每月", "all" to "全部（单桶）")[woBatchScale]!!
        return bi(
            "Setting all WO batch windows to $labelEn (make + move + buy). Re-run plan to apply.",
            "已将所有工单批量窗口（制造/调拨/采购）设为${labelZh}。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf(
            "make_batch_scale"     to JsonPrimitive(woBatchScale),
            "move_batch_scale"     to JsonPrimitive(woBatchScale),
            "purchase_batch_scale" to JsonPrimitive(woBatchScale),
            "period_days"          to JsonPrimitive(days),
        ))
    }

    // ── Consolidation single-bucket / period_days = 0 (legacy pattern — now handled by "all" above) ──
    if (Regex("single (bucket|period)|one (bucket|period)|no (bucket|period)|merge all|consolidate all").containsMatchIn(t)) {
        return bi(
            "Setting consolidation to single-bucket (all WOs in one batch). Re-run plan to apply.",
            "已将合并设为单桶（全部工单合并为一批）。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf(
            "make_batch_scale" to JsonPrimitive("all"), "move_batch_scale" to JsonPrimitive("all"),
            "purchase_batch_scale" to JsonPrimitive("all"), "period_days" to JsonPrimitive(0),
        ))
    }

    // ── Consolidation bucket / period_days (numeric, legacy) ──
    val periodMatch = Regex("(\\d+)\\s*(?:-\\s*)?days?\\s*(?:bucket|period|window)").find(t)
    val zhPeriodMatch = Regex("(\\d+)\\s*天(?:桶|窗口|周期)?").find(raw)
    val bucketMatch = Regex("(?:bucket|period)\\D*(\\d+)|时间桶\\D*(\\d+)").find(t + " " + raw)
    if (periodMatch != null || zhPeriodMatch != null || bucketMatch != null) {
        val n = (periodMatch?.groupValues?.get(1)
            ?: zhPeriodMatch?.groupValues?.get(1)
            ?: bucketMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
            ?: "0").toIntOrNull() ?: 0
        val days = n.coerceIn(0, 365)
        val snappedScale = if (days == 0) "all" else if (days <= 7) "weekly" else if (days <= 14) "biweekly" else "monthly"
        val msg = if (days == 0) "single-bucket" else "$days day${if (days == 1) "" else "s"}"
        val zhMsg = if (days == 0) "单桶" else "${days}天"
        return bi(
            "Setting consolidation bucket to $msg (→ $snappedScale, all WO types). Demands within that window may share work orders. Re-run plan to apply.",
            "已将合并时间桶设为 $zhMsg（→ $snappedScale，所有工单类型）。同一时间窗口内的需求可共用生产订单。请重新运行计划以生效。",
            raw,
        ) to mergeConsolidation(current, mapOf(
            "make_batch_scale" to JsonPrimitive(snappedScale),
            "move_batch_scale" to JsonPrimitive(snappedScale),
            "purchase_batch_scale" to JsonPrimitive(snappedScale),
            "period_days" to JsonPrimitive(days),
        ))
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

    // ── Regenerate allocation — acknowledge only; maybeRegenerateAllocation (which runs
    // regardless of what this function returns, per the same regex) does the actual work and
    // appends its own outcome. Without this branch the request falls through to the "I'm not
    // sure" catch-all below, which would read oddly concatenated with the real success note. ──
    if (Regex("regenerate.*allocation|refresh.*allocation").containsMatchIn(t) ||
        Regex("重新生成.*分配|刷新.*分配").containsMatchIn(raw)
    ) {
        return bi(
            "On it — regenerating Critical Material Allocation.",
            "好的 — 正在重新生成关键原材料分配。",
            raw,
        ) to null
    }

    // ── Reset ──
    if (Regex("reset|default|clear").containsMatchIn(t) ||
        Regex("重置|默认|清除").containsMatchIn(raw)
    ) {
        val patch = buildJsonObject {
            put("method_selection", buildJsonObject {
                put("multiple", false)
                put("mode", "preference")
            })
            put("purchase_allowed", false)
            put("consolidation", buildJsonObject {
                put("make_batch_scale", "weekly")
                put("move_batch_scale", "weekly")
                put("purchase_batch_scale", "weekly")
                put("period_days", 7)
            })
            put("analyze_criticality", false)
            put("check_soundness", true)
        }
        return bi(
            "Reset to defaults: method by preference (waterfall), max methods 2, purchase disabled, WO batching on (weekly), criticality off, soundness check on.",
            "已重置为默认：按偏好方法（瀑布式）、最多 2 个方法、禁用采购、工单批量合并开启（每周）、关键度关闭、完整性校验开启。",
            raw,
        ) to patch
    }

    return bi(
        "I'm not sure I caught that. I can configure: method selection (preference waterfall, max methods), purchase allowed, WO batching (per-type scale: make/move/purchase, bucket days), and the post-plan toggles (analyze criticality, check soundness). What would you like?",
        "我不太理解。我可以配置：方法选择（按偏好瀑布式，最多方法数）、是否允许采购、工单批量合并（按类型：制造/调拨/采购，桶天数），以及计划后开关（关键度分析、完整性校验）。您想做什么？",
        raw,
    ) to null
}

// ── External-config version picks ────────────────────────────────────────────

/** config_update key → which of the 5 versioned external config objects it pins (see prompt §18). */
private val EXTERNAL_VERSION_KEYS: Map<String, com.allocator.services.ConfigVersionKind> = mapOf(
    "case_alloc_version_id" to com.allocator.services.ConfigVersionKind.CASEALLOC,
    "pref_version_id" to com.allocator.services.ConfigVersionKind.PREF,
    "demand_order_version_id" to com.allocator.services.ConfigVersionKind.ORD,
    "purchasable_material_version_id" to com.allocator.services.ConfigVersionKind.PURCHMAT,
    "constraint_version_id" to com.allocator.services.ConfigVersionKind.CONSTR,
)

/** Strip any LLM-picked *_version_id that doesn't exist for this case (a hallucinated or
 *  misheard id must never silently fall back to the default at submit time and look applied),
 *  appending an explanation to the reply. Valid picks pass through untouched. */
private fun validateVersionPicks(
    caseId: Int,
    userMessage: String,
    reply: String,
    configUpdate: JsonObject?,
): Pair<String, JsonObject?> {
    val cuIn: JsonObject = configUpdate ?: return reply to null
    val picked = EXTERNAL_VERSION_KEYS.filterKeys { (cuIn[it] as? JsonPrimitive)?.intOrNull != null }
    if (picked.isEmpty()) return reply to cuIn
    var cu: JsonObject = cuIn
    var out = reply
    for ((key, kind) in picked) {
        val id = (cuIn[key] as? JsonPrimitive)?.intOrNull ?: continue
        val exists = com.allocator.services.CaseConfigVersioning.listVersions(caseId, kind).any { it.id == id }
        if (!exists) {
            cu = JsonObject(cu.toMutableMap().apply { remove(key) })
            out += "\n" + bi(
                "(Version $id doesn't exist for ${kind.name.lowercase()} on this case — that pick was skipped.)",
                "（此案例的 ${kind.name.lowercase()} 不存在版本 $id — 已跳过该选择。）",
                userMessage,
            )
        }
    }
    return out to cu.takeIf { it.isNotEmpty() }
}

// ── Preference tuning ────────────────────────────────────────────────────────
//
// Supply Preferences are a GENERATED artifact (PreferenceBuilder), so the copilot
// operates at its config-parameter level — never on ranking rows and never by
// asking the LLM for a version number: the model emits a preference_tuning
// object with just the parameters the user wants changed; the backend overlays
// them on the currently-selected version's stored config, renormalizes the three
// axis weights, regenerates into a freshly minted PREF version (a referenced
// version is never mutated — same discipline as the generate route's 409), and
// selects it via pref_version_id in config_update.

/** If [configUpdate] carries preference_tuning, regenerate preferences into a new version. */
private fun resolvePreferenceTuning(
    caseId: Int,
    userMessage: String,
    currentConfig: JsonObject,
    reply: String,
    configUpdate: JsonObject?,
): Pair<String, JsonObject?> {
    val ptJson = configUpdate?.get("preference_tuning") as? JsonObject ?: return reply to configUpdate
    val rest = JsonObject(configUpdate.toMutableMap().apply { remove("preference_tuning") })
    fun restOrNull() = rest.takeIf { it.isNotEmpty() }
    fun note(en: String, zh: String) = "$reply\n${bi(en, zh, userMessage)}"

    fun dbl(k: String) = (ptJson[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..1.0 }
    val dW = dbl("delivery_weight")
    val iW = dbl("inventory_weight")
    val cW = dbl("critical_material_weight")
    val depth = (ptJson["max_bom_depth"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 10)
    if (dW == null && iW == null && cW == null && depth == null) {
        return note(
            "(I couldn't tune preferences — no valid parameter in the request. Weights are 0..1; depth is 1..10.)",
            "（无法调整偏好 — 请求中没有有效参数。权重取值 0..1，深度取值 1..10。）",
        ) to restOrNull()
    }

    // Baseline = the currently-selected version's stored generation config.
    val effective = transaction { resolveEffectiveConfig(currentConfig, caseId, com.allocator.services.CaseLoader.load(caseId)) }
    val base = loadCasePreferenceConfig(effective.prefVersionId)
    var d = dW ?: base?.deliveryWeight ?: 0.3
    var i = iW ?: base?.inventoryWeight ?: 0.3
    var c = cW ?: base?.criticalMaterialWeight ?: 0.4
    val sum = d + i + c
    if (sum <= 0.0) {
        return note(
            "(All three preference weights came out zero — nothing to rank by. Preferences unchanged.)",
            "（三个偏好权重全为零 — 无法排序。偏好未更改。）",
        ) to restOrNull()
    }
    fun r4(v: Double) = Math.round(v / sum * 10000.0) / 10000.0
    d = r4(d); i = r4(i); c = r4(c)
    val newDepth = depth ?: base?.maxBomDepth ?: 3

    val data = transaction { com.allocator.services.CaseLoader.load(caseId) }
    if ((data["demand"] ?: emptyList()).isEmpty()) {
        return note(
            "(This case has no demand data — preferences can't be generated.)",
            "（此案例没有需求数据 — 无法生成偏好。）",
        ) to restOrNull()
    }
    // Critical-material axis needs purchase_allowed/purchasable_materials/constraints — resolved
    // for real from the effective versions (planningConfig), NOT the frontend's currentConfig:
    // planningConfig.purchasable_materials there is a dead display field (see (6s)/§18), so
    // trusting it here would silently reintroduce the same "unrestricted purchase" class of bug
    // fixed in runPlanning earlier — just for the critical-material scoring axis instead of the
    // planner itself.
    @Suppress("UNCHECKED_CAST")
    val currentConfigMap = runCatching { jsonElementToNative(currentConfig) as? Map<String, Any?> }.getOrNull()
    val cfgMap = planningConfig(currentConfigMap, effective)

    val label = "copilot: prefs w=${d}/${i}/${c} d=$newDepth".take(60)
    val newVersionId = transaction {
        com.allocator.services.CaseConfigVersioning.createVersion(
            caseId, com.allocator.services.ConfigVersionKind.PREF, label,
            "Created by planning copilot from: \"${userMessage.take(200)}\"",
        )
    }
    val rows = generateAndSeedCasePreferences(
        caseId, newVersionId, data, cfgMap,
        maxBomDepth = newDepth, deliveryWeight = d, inventoryWeight = i, criticalMaterialWeight = c,
    )

    val outCu = JsonObject(rest.toMutableMap().apply {
        put("pref_version_id", JsonPrimitive(newVersionId))
    })
    return note(
        "Regenerated Supply Preferences (${rows.size} rows) with weights delivery=$d / inventory=$i / critical=$c, bom depth $newDepth — saved as version $newVersionId and selected it for the next run.",
        "已按权重 交期=$d / 库存=$i / 关键材料=$c、BOM深度 $newDepth 重新生成偏好（${rows.size} 行）— 已保存为版本 $newVersionId 并选作下次运行。",
    ) to outCu
}

// ── Allocation ↔ Purchasable coupling ────────────────────────────────────────
//
// Critical Material Allocation and Purchasable Materials are NOT independent: a supply
// position is "critical" (gets an allocation budget row at all) only when it has
// no make method AND is not an admitted buy — see isRawCriticalPosition in
// PlanningEngine.kt, criterion 2. So switching purchasable_material_version_id
// changes exactly which positions the Allocation's budget rows cover; an
// Allocation generated under the OLD selection can now be silently wrong (over-
// or under-covering) for the new one. There's no stored link recording which
// purchmat content an Allocation version was built from (case_allocation_config's
// content_hash covers only the allocation's own rows), so this can't be detected
// automatically — every purchasable_material_version_id pick gets a standing
// invitation to regenerate.

/** After a purchasable_material_version_id pick, invite (not force) an Allocation refresh —
 *  offers the explicit copilot command as well as the page, since regenerating is one call.
 *  [justRegenerated] must come from [maybeRegenerateAllocation]'s own return, NOT be inferred
 *  from `case_alloc_version_id` merely being present in configUpdate: an ordinary explicit pick
 *  of an (unrelated, possibly stale) allocation version in the SAME message — e.g. "use
 *  allocation version 2 and purchasable materials version 4" — would also leave that key set,
 *  and picking an old version is exactly the case that still needs the invitation. */
private fun invitePurchasableCoupledAllocationRefresh(
    userMessage: String,
    reply: String,
    configUpdate: JsonObject?,
    justRegenerated: Boolean,
): String {
    if (configUpdate?.get("purchasable_material_version_id") == null) return reply
    if (justRegenerated) return reply
    return "$reply\n" + bi(
        "Heads up: Critical Material Allocation depends on which materials are purchasable — a " +
            "budget built under the old selection may no longer be accurate. Regenerate it on " +
            "the Critical Material Allocation page, or tell me \"regenerate allocation\".",
        "提示：关键原材料分配取决于哪些材料可采购 — 基于旧选择生成的分配可能已不准确。" +
            "请在「关键原材料分配」页面重新生成，或告诉我「重新生成分配」。",
        userMessage,
    )
}

/** Explicit "regenerate allocation" request — regenerates into a NEW Critical Material Allocation
 *  version using the currently-effective purchasable/constraints selection (never mutates a
 *  referenced version, matching the Generate route's own discipline) and selects it. Third
 *  return value is true only when THIS call actually minted a new version — the signal
 *  [invitePurchasableCoupledAllocationRefresh] needs to avoid a redundant nag; deliberately not
 *  inferred from `case_alloc_version_id`'s mere presence, which an unrelated explicit pick in
 *  the same message would also leave set. */
private fun maybeRegenerateAllocation(
    caseId: Int,
    userMessage: String,
    currentConfig: JsonObject,
    reply: String,
    configUpdate: JsonObject?,
): Triple<String, JsonObject?, Boolean> {
    val t = userMessage.trim().lowercase()
    val wantsRegen = Regex("regenerate.*allocation|refresh.*allocation|重新生成.*分配|刷新.*分配").containsMatchIn(t) ||
        Regex("重新生成.*分配|刷新.*分配").containsMatchIn(userMessage)
    if (!wantsRegen) return Triple(reply, configUpdate, false)

    val data = transaction { com.allocator.services.CaseLoader.load(caseId) }
    val effective = transaction { resolveEffectiveConfig(currentConfig, caseId, data) }
    if ((data["demand"] ?: emptyList()).isEmpty() || (data["supply"] ?: emptyList()).isEmpty()) {
        return Triple(
            "$reply\n" + bi(
                "(Can't regenerate allocation — this case has no demand/supply data.)",
                "（无法重新生成分配 — 此案例没有需求/供应数据。）",
                userMessage,
            ),
            configUpdate, false,
        )
    }
    @Suppress("UNCHECKED_CAST")
    val currentConfigMap = runCatching { jsonElementToNative(currentConfig) as? Map<String, Any?> }.getOrNull()
    val cfgMap = planningConfig(currentConfigMap, effective)

    val label = "copilot: allocation refresh".take(60)
    val newVersionId = transaction {
        com.allocator.services.CaseConfigVersioning.createVersion(
            caseId, com.allocator.services.ConfigVersionKind.CASEALLOC, label,
            "Created by planning copilot from: \"${userMessage.take(200)}\"",
        )
    }
    val rows = generateAndSeedCaseAllocation(caseId, newVersionId, data, cfgMap)

    val outCu = JsonObject((configUpdate ?: JsonObject(emptyMap())).toMutableMap().apply {
        put("case_alloc_version_id", JsonPrimitive(newVersionId))
    })
    return Triple(
        "$reply\n" + bi(
            "Regenerated Critical Material Allocation (${rows.size} rows) against the currently selected purchasable materials — saved as version $newVersionId and selected it for the next run.",
            "已根据当前选定的可采购材料重新生成关键原材料分配（${rows.size} 行）— 已保存为版本 $newVersionId 并选作下次运行。",
            userMessage,
        ),
        outCu, true,
    )
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
        val (rawReply, rawUpdate) = llmParse(message, currentConfig, history)
            ?: ruleBasedParse(message, currentConfig)
        // Validate explicit version picks first, then preference tuning and an explicit
        // allocation-regenerate request — the version ids they mint are valid by construction.
        val (pickedReply, pickedUpdate) = validateVersionPicks(caseId, message, rawReply, rawUpdate)
        val (tunedReply, tunedUpdate) =
            resolvePreferenceTuning(caseId, message, currentConfig, pickedReply, pickedUpdate)
        val (regenReply, regenUpdate, justRegenerated) =
            maybeRegenerateAllocation(caseId, message, currentConfig, tunedReply, tunedUpdate)
        // Last: if a purchasable_material_version_id pick is going out this turn (and the user
        // didn't just regenerate allocation to match it), invite an Allocation refresh — see
        // "Allocation ↔ Purchasable coupling" above.
        val reply = invitePurchasableCoupledAllocationRefresh(message, regenReply, regenUpdate, justRegenerated)

        call.respond(PlanningCopilotResponse(reply = reply, configUpdate = regenUpdate))
    }
}
