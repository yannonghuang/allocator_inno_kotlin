package com.allocator.api

import com.allocator.AgentMemory
import com.allocator.Boms
import com.allocator.Cases
import com.allocator.config
import com.allocator.Demands
import com.allocator.MethodBuys
import com.allocator.MethodMakes
import com.allocator.MethodMoves
import com.allocator.PlanRuns
import com.allocator.ProductLocations
import com.allocator.Products
import com.allocator.Supplies
import com.allocator.WoScheduleEvents
import com.allocator.services.emitPlanRunEvent
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.max
import com.allocator.services.CaseBootstrap
import com.allocator.services.CaseLoader
import com.allocator.services.KbStore
import com.allocator.services.LlmAgentMessage
import com.allocator.services.LlmNotConfiguredException
import com.allocator.services.LlmTool
import com.allocator.services.LlmToolCall
import com.allocator.services.llmChatWithTools
import com.allocator.services.Constraint
import com.allocator.services.parseConstraints
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("com.allocator.PlanningAgentRoutes")

// ── Pending maintenance-decision cache (prompt-context memory) ───────────────
//
// Background: when analyze_wo_schedule_impact persists a contingent plan run
// and the user accepts on the next turn, the LLM was emitting promote_plan_run
// with the wrong plan_run_id — it pulled the integer from <active_run_id> in
// the system prompt instead of the contingent_plan_run_id from the prior
// tool-result trace. Two valid integers in context, no clear pointer to which
// one to use → wrong-args tool emission.
//
// Fix: after a contingent is created we stash its id here (per-case, TTL'd),
// and the next agent-loop invocation injects a <pending_maintenance_decision>
// block into the system prompt that names the id explicitly. The workflow
// rules already say "use the contingent_plan_run_id from step 5"; this makes
// that id structurally available alongside <active_run_id> with a distinct
// label so the model can't conflate them.
//
// The LLM is still in charge of choosing tools and emitting calls — this
// cache is pure prompt-context memory, not a deterministic short-circuit.
//
// Cache is cleared when promote_plan_run is called with the matching id (so
// the pending offer doesn't linger after it's accepted). Auto-expires after
// PENDING_MAINTENANCE_TTL_MIN minutes if the user walks away.
internal data class PendingMaintenanceDecision(
    /** Option C's contingent — the original-window, accept-impact plan run.
     *  Kept named `contingentPlanRunId` for backwards compatibility with
     *  earlier callers; semantically this is the option-3 CPR id. */
    val contingentPlanRunId: Int,
    val maxFeasibleDays: Int?,
    val capturedAt: java.time.Instant,
    val prodArea: String? = null,
    val bucketStart: String? = null,
    val originalDelayDays: Int? = null,
    val woGroupIds: List<String>? = null,
    val alternateStartDate: String? = null,
    /** Option A's contingent — shortened to maxFeasibleDays, no demand impact.
     *  Populated when the assessment flow generates it; null otherwise. */
    val optionACprId: Int? = null,
    /** Option B's contingent — deferred to alternateStartDate, no demand impact. */
    val optionBCprId: Int? = null,
)
private val pendingMaintenance = java.util.concurrent.ConcurrentHashMap<Int, PendingMaintenanceDecision>()
private const val PENDING_MAINTENANCE_TTL_MIN = 30L

internal fun rememberPendingMaintenance(
    caseId: Int,
    contingentPlanRunId: Int,
    maxFeasibleDays: Int?,
    prodArea: String? = null,
    bucketStart: String? = null,
    originalDelayDays: Int? = null,
    woGroupIds: List<String>? = null,
    alternateStartDate: String? = null,
    optionACprId: Int? = null,
    optionBCprId: Int? = null,
) {
    // If a prior entry exists for this case, preserve fields the new caller
    // didn't supply — lets find_earliest_safe_start enrich the cache later
    // without losing context populated by analyze_wo_schedule_impact.
    val prior = pendingMaintenance[caseId]
    pendingMaintenance[caseId] = PendingMaintenanceDecision(
        contingentPlanRunId = contingentPlanRunId,
        maxFeasibleDays = maxFeasibleDays ?: prior?.maxFeasibleDays,
        capturedAt = java.time.Instant.now(),
        prodArea = prodArea ?: prior?.prodArea,
        bucketStart = bucketStart ?: prior?.bucketStart,
        originalDelayDays = originalDelayDays ?: prior?.originalDelayDays,
        woGroupIds = woGroupIds ?: prior?.woGroupIds,
        alternateStartDate = alternateStartDate ?: prior?.alternateStartDate,
        optionACprId = optionACprId ?: prior?.optionACprId,
        optionBCprId = optionBCprId ?: prior?.optionBCprId,
    )
    sweepPendingMaintenance()
}

internal fun loadPendingMaintenance(caseId: Int): PendingMaintenanceDecision? {
    val p = pendingMaintenance[caseId] ?: return null
    val ageMin = java.time.temporal.ChronoUnit.MINUTES.between(p.capturedAt, java.time.Instant.now())
    if (ageMin > PENDING_MAINTENANCE_TTL_MIN) {
        pendingMaintenance.remove(caseId)
        return null
    }
    return p
}

internal fun clearPendingMaintenance(caseId: Int) {
    pendingMaintenance.remove(caseId)
}

private fun sweepPendingMaintenance() {
    val now = java.time.Instant.now()
    pendingMaintenance.entries.removeIf { (_, p) ->
        java.time.temporal.ChronoUnit.MINUTES.between(p.capturedAt, now) > PENDING_MAINTENANCE_TTL_MIN
    }
}

// Heuristic: does the user message clearly pick option 1 or option 2 of a
// just-presented maintenance offer? Option 3 (accept impact) is intentionally
// NOT matched — auto-deleting a contingent the user wanted to promote would be
// destructive. False negatives are fine (the cache TTL cleans up eventually);
// the goal is a tight match for the "1/2/一/二/方案1/option 2" reply shapes.
private val OPTION_1_OR_2_PATTERN = Regex(
    "(?iu)" +
    """(?:^|[^\d])(?:option\s*[12]\b|opt\s*[12]\b|\([ab]\)|方案\s*[12一二]|""" +
    """选项\s*[12一二]|第[一二]个|我选\s*[12一二]|选\s*[12一二]|""" +
    """^[12一二①②]\s*$|[①②])"""
)
private val OPTION_3_PATTERN = Regex(
    "(?iu)" +
    """(?:option\s*3\b|opt\s*3\b|\(c\)|方案\s*[3三]|选项\s*[3三]|第三个|我选\s*[3三]|""" +
    """选\s*[3三]|^[3三③]\s*$|③|accept|promote|approve|接受|确认|采用|升格)"""
)

internal fun looksLikeOption1Or2Pick(message: String): Boolean {
    if (message.isBlank()) return false
    if (OPTION_3_PATTERN.containsMatchIn(message)) return false
    return OPTION_1_OR_2_PATTERN.containsMatchIn(message)
}

// ── Wire contract ────────────────────────────────────────────────────────────
//
//   POST /cases/{caseId}/planning-agent
//   body:  { message: str, current_config?: object, history?: [{role, content}] }
//   reply: {
//     reply: str,                      // final assistant text
//     steps: [{tool, args, result_summary}]  // every tool call + its outcome
//     config_update: object | null,    // working config after the loop
//     fresh_run_id: int | null         // if the agent ran a plan
//   }

@Serializable
private data class AgentRequest(
    val message: String? = null,
    @SerialName("current_config") val currentConfig: JsonObject? = null,
    val history: List<AgentHistoryItem>? = null,
    /** The plan_run id the user is currently viewing on the page (e.g. selected
     *  in the run-history dropdown). Lets the agent default to this run when
     *  the user asks a run-scoped question without naming a number. Null when
     *  the user hasn't selected a run (e.g. a fresh case before the first plan). */
    @SerialName("viewing_run_id") val viewingRunId: Int? = null,
)

@Serializable
private data class AgentHistoryItem(
    val role: String,
    val content: String? = null,
)

@Serializable
private data class AgentStep(
    val tool: String,
    val args: JsonObject,
    @SerialName("result_summary") val resultSummary: String,
)

@Serializable
private data class AgentResponse(
    val reply: String,
    val steps: List<AgentStep>,
    @SerialName("config_update") val configUpdate: JsonObject? = null,
    @SerialName("fresh_run_id") val freshRunId: Int? = null,
    // When run_plan_async returned status='still_running' (plan exceeded the
    // 25s blocking window), this carries the job_id so the frontend can keep
    // polling /plan/status/{job_id} and post a completion message itself.
    @SerialName("pending_job_id") val pendingJobId: String? = null,
)

// ── Knowledge primer (curated value props + design + ops + glossary) ────────
//
// Loaded once at JVM startup from src/main/resources/agent-knowledge.md
// and prepended to the system prompt on every conversation turn. The file
// is the single source of truth for what the agent "knows" about the
// system at a high level — when a major design decision or new feature
// ships, that file gets updated in the same PR. See its header for the
// maintenance contract.

private val AGENT_KNOWLEDGE: String by lazy {
    val res = ::AGENT_KNOWLEDGE.javaClass.classLoader.getResource("agent-knowledge.md")
    res?.readText() ?: run {
        log.warn("agent-knowledge.md not found on classpath; agent will run with system prompt only.")
        ""
    }
}

// ── Design-doc retrieval (L3 cross-cutting) ─────────────────────────────────
//
// The agent's `query_design_docs` tool retrieves source-of-truth quotes from
// docs/*.md when the prompt-baked digest in agent-knowledge.md isn't enough.
// We index paragraphs (split on blank lines) once at JVM startup so per-call
// search is O(N paragraphs × topic-length) — trivial at the current scale of
// ~9 docs and ~hundreds of paragraphs.
//
// Source files are bundled into the JAR via build.gradle.kts processResources
// task (copies docs/*.md into resources/docs/). When the docs/ resource path
// is missing (e.g. fresh CI checkout), the agent tool returns empty results —
// graceful degradation, no crash.

private data class DesignDocParagraph(
    val file: String,        // e.g. "waterfall-allocation.md"
    val lineStart: Int,      // 1-based line in source file
    val text: String,        // the paragraph body (preserves markdown)
)

private val DESIGN_DOC_FILES: List<String> = listOf(
    "DESIGN.md",
    "waterfall-allocation.md",
    "supply-level-consolidation.md",
    "planner-conservation-fixes.md",
    "planner-orphan-consumption.md",
    "planner-soundness-followups.md",
    "soundness-checker-gaps.md",
    "planning-agent.md",
)

private val DESIGN_DOC_INDEX: List<DesignDocParagraph> by lazy {
    val out = mutableListOf<DesignDocParagraph>()
    val cl = ::DESIGN_DOC_INDEX.javaClass.classLoader
    for (filename in DESIGN_DOC_FILES) {
        val res = cl.getResource("docs/$filename") ?: continue
        val text = runCatching { res.readText() }.getOrNull() ?: continue
        // Split on blank lines (one or more). Track line numbers for citation.
        val lines = text.lines()
        var paraStart = 1
        val buf = StringBuilder()
        var firstLineInPara = 1
        for ((idx, line) in lines.withIndex()) {
            val lineNo = idx + 1
            if (line.isBlank()) {
                if (buf.isNotBlank()) {
                    out.add(DesignDocParagraph(filename, firstLineInPara, buf.toString().trim()))
                }
                buf.clear()
                paraStart = lineNo + 1
                firstLineInPara = paraStart
            } else {
                if (buf.isEmpty()) firstLineInPara = lineNo
                buf.appendLine(line)
            }
        }
        if (buf.isNotBlank()) out.add(DesignDocParagraph(filename, firstLineInPara, buf.toString().trim()))
    }
    if (out.isEmpty()) {
        log.warn("design-doc index is empty — docs/*.md not found on classpath. Run gradle build to bundle them, or check the processResources task.")
    } else {
        log.info("design-doc index built: ${out.size} paragraphs across ${out.map { it.file }.distinct().size} file(s).")
    }
    out
}

// ── System prompt ────────────────────────────────────────────────────────────

private const val SYSTEM_PROMPT_INTRO = """You are the Planning Agent — a domain expert for this supply-chain planning system.

Your jobs:
  1. Run plans on the user's behalf (run_plan_async).
  2. Explain the planner's decisions and surface KPIs, pegging, soundness.
  3. Handle shutdown / maintenance window scheduling (WO impact analysis).
  4. Answer demand / supply / BOM lookup questions.

You do NOT modify planning config (consolidation, method_selection, score_weights,
etc.). Config changes go through the plan-form UI, not through you. If the user
asks to change those, redirect them to the form rather than attempting the change.

**HONESTY HARD STOP — the single most-violated rule in this system, read before
answering ANY "why" question:** Every fact you state about this case's data — a
BOM alt_group, a constraint row, a config version number, a tool's returned
fields — MUST come from a tool result you actually received THIS turn. This is
not a style preference; it has caused repeated, confirmed incidents where the
agent invented a plausible-sounding schema/constraint/product-variant that does
not exist anywhere in the case's data, and a user caught it against the raw
CSVs. Concretely:
  - **An empty / zero-match tool result (`match_count: 0`, `[]`, "no WO found",
    `0 unique gids`) is a complete, valid answer.** It means exactly what it
    says — nothing more. Report it as-is. Do NOT follow an empty result with an
    invented mechanism, mock payload, or narrative that fills the gap with
    something plausible-sounding.
  - **Never invent an identifier.** Product/BOM-variant ids, alt_group names,
    constraint version numbers, demand ids — if it isn't a literal string or
    number copied from a tool payload this turn, don't write it. "This looks
    like it would be named X" is not grounding.
  - **Don't conflate two different id spaces.** location_id and prod_area are
    different dimensions (see explain_method_choice's doc); customer_id and
    product_id can collide as the SAME string across different cases (see
    list_customers' doc). A tool call using the wrong dimension will look
    "empty" or "wrong" — that's a signal to re-check which dimension you meant,
    not to guess past it.
  - **Don't contradict yourself across turns without saying so.** If a claim
    you're about to make conflicts with something you displayed earlier THIS
    conversation (e.g. a number in an earlier table), that's a red flag —
    re-run the grounding tool call and quote the fresh result explicitly,
    noting the discrepancy, rather than silently overwriting the earlier claim.
  - If you genuinely don't know the mechanism after calling the relevant
    tools, say so plainly ("the available tools don't show a reason for this")
    instead of constructing one. A correct "I don't know" is always better than
    a fabricated explanation.

**INTENT-ROUTING HARD STOP — read BEFORE doing anything else:**
If the user's message contains ANY of these phrases (or their close paraphrases),
the intent is **WO shutdown/maintenance scheduling**. Skip the three-bucket
classification below and jump directly to the "Downtime / maintenance window
scheduling" workflow further down. Specifically NEVER call `update_config`
for these — `update_config` changes planning-engine parameters
(consolidation/merge-bucket/method-selection), NOT maintenance schedules.

  English: "shut down X for N days", "shutdown X N days", "take X offline",
           "maintenance window", "outage", "down for N days", "production hold",
           "stop production X for N days".
  中文:    "关闭 X N 天", "停产 X N 天", "停机 X N 天", "X 维护 N 天",
           "X 检修 N 天", "X 停线 N 天", "X 下线 N 天", "停掉 X 一周".

Example — "从2024年7月15日关闭OE生产区7天" → "关闭 X N 天" pattern matches →
WO scheduling workflow → list_prod_areas → find_wos → analyze_wo_availability
→ analyze_wo_schedule_impact → find_earliest_safe_start. Do NOT call
update_config; "7天" here is the maintenance window length, NOT a consolidation
bucket size.

Otherwise, the user's intent is one of: WO maintenance scheduling (covered above),
plan analysis / explanation (KPIs, pegging, soundness, comparisons), or demand /
supply lookup. Pick the tool that matches the question's data layer (see L1 / L2 /
L3 below). This agent does NOT modify planning config — config changes go through
the plan-form UI, not the agent.

Planner knowledge (from docs/waterfall-allocation.md):
  - max_methods controls waterfall fan-out: 1 = single best method per demand;
    2-4 = exhaust the best-ranked alternative, then fall back to the next by
    preference only if the first left a residual. Ranking is a single flat list
    across method type (make/move/buy) AND BOM alt_group/variant boundaries —
    there is no separate "variant selection" step. Inventory carries forward
    across slots. This runs at every BOM depth, not just the root.
  - mode = "preference" (lowest preference int wins) is the only supported mode.
    "elaborate" (composite scoring of commit_time / inventory_consumed / purchase)
    was retired — a stored config with mode="elaborate" is silently treated as
    "preference". method_selection.depth, max_bom_depth, and score_weights are
    legacy no-op fields with no runtime effect.
  - consolidation.allocation_mode = "fair" (priority-first when ample, proportional
    under shortage) | "proportional" | "priority_first". Split policy applies at
    supply-bearing nodes (raw inventory, leftover stock, carry-over WOs).
  - On case-171 the empirical sweet spot is mode=preference + max_methods=2.
  - method_selection.root_waterfall (bool, default true) — true = the demand's ROOT node uses
    the same ordinary sequential 100%-then-spillover waterfall as every other node. false =
    the root instead divides its quantity UP-FRONT across its alternatives (root-only
    proportional/equal split), before waterfall applies below. Only the root is affected;
    every non-root BOM node always waterfalls regardless of this flag. Distinct from
    raw_material_sourcing="equal_split" (below) — that one applies at ANY depth to
    purchasable-raw-material siblings specifically, this one applies only at the demand root
    and to any alternatives there.
  - method_selection.raw_material_sourcing = "equal_split" (vs default "waterfall") — when
    "equal_split", PURCHASABLE-RAW-MATERIAL alternatives filling the same BOM slot (no
    method_make, admitted as buy) get an equal fixed share of that slot's quantity instead of
    sequential waterfall. Manufacturable alternatives (have a method_make) are never grouped
    this way even if structurally identical. See agent-knowledge.md's "Equal-split raw-material
    sourcing" section and compare_alternatives' tool doc for the "equal DEMAND ≠ equal
    PURCHASE" consequence this has on purchase-quantity questions.
  - reallocate_critical_leftover (bool, default false, EXPERIMENTAL, top-level config key — NOT
    under method_selection) — when true, after the normal critical-material allocation pass,
    any leftover budget on a (critical-material, supply-lot) that its originally-allocated
    demand didn't fully use gets reallocated to OTHER demands that are still short of that same
    material, instead of sitting unused. When explaining a demand that unexpectedly DID or did
    NOT receive extra critical material beyond its own Targeted Supply Allocation rows, check this
    flag via get_run_config before concluding the allocation is wrong — with it on, a short
    demand legitimately drawing beyond its own pre-allocated rows is expected behavior, not a
    bug.

**Critical materials** (canonical test: `isRawCriticalPosition` in PlanningEngine.kt — the
SAME function used for live dominator labeling and for building Targeted Supply Allocation's
budgets, so this is the one true definition, not an approximation you should re-derive):
  - A product@location is critical iff: it has NO `make` method anywhere for that product
    (make always wins — if it can be made anywhere, the whole product is elastic, never
    critical, regardless of buy status), AND EITHER (a) it also has no admitted `buy` method
    (only existing supply can ever satisfy it), OR (b) it has a `buy` method that raw data
    offers but the current run's config excludes (purchase_allowed=false, or the product is
    absent from a non-empty purchasable_materials whitelist).
  - `move` plays no role in either criterion — relocating stock never creates more of it
    system-wide.
  - A PRODUCT (not just one location) is critical only if EVERY location it appears at
    passes the test above — one elastic location (e.g. a sub-assembly made at a single
    plant) makes the whole product system-wide elastic, even if its other locations
    individually have no method of their own.
  - Do NOT guess which product_ids are critical from memory or by pattern-matching product
    codes — call get_critical_materials(run_id) to get the actual, run-specific set (it's
    version-dependent: purchasable_materials/purchase_allowed can change which materials are
    critical between runs).
  - **A `no_methods` / `commit_reason: "no_methods"` node for a critical material does NOT
    mean zero was consumed — do not report it as "not used."** Verified live case: a demand's
    pegging showed a `no_methods` failure node for a critical material, and the agent claimed
    "did not consume any quantity of it" — the demand had actually drawn 300+ units of it from
    real supply lots (confirmed in `plan_supply_allocation`); the failure node was the
    UNMET RESIDUAL after that draw, not a record of zero draw. This is the STRUCTURALLY
    EXPECTED failure mode for a criterion-1 critical material (no make, no buy — see above):
    it can ONLY be satisfied from finite existing stock, so once stock at that leaf runs out,
    the unmet remainder legitimately has no method to fall back to and shows `no_methods` —
    that says nothing about whatever portion WAS successfully drawn before the shortfall. A
    pegging tree can carry MULTIPLE nodes for the same (product, location) under one demand:
    some showing real consumption, another showing the leftover gap as a failure. Before
    stating a demand's consumption of ANY material (critical or not) as zero, confirm it with
    a real consumption number — get_component_allocation_by_demand or get_leaf_competition's
    per-demand draws — never infer "not consumed" from a failure/bottleneck node's mere
    presence.

External config objects (5 total, from Tables.kt's CaseConfigVersions doc — DO NOT
confuse any of these with `manual_override`, a mechanism that was fully retired
— its DB table was dropped and no `manual_override` structure exists anywhere in
the codebase; never invent an `override_id`/`component_split`/`method_selection`
schema for it):
  - **Critical materials ARE effectively pre-allocated, per (supply lot, demand)
    — that IS the normal/default state, even though nothing demand-level is
    actually STORED.** Targeted Supply Allocation (`case_allocation` table) is
    now an INPUT table — one row per critical-material lot, carrying only
    `qty_cap` (a cap override) and `target` (a customer earmark), editable on
    the Targeted Supply Allocation page. `get_critical_raw_allocation` does
    NOT return those input rows — it RECOMPUTES and returns the resulting
    per-(lot, demand) qty budget by running the same buildSupplyAllocation
    pass a real plan run makes, so its numbers always match what planning
    actually used (there is no separate "planner bypasses its own logic with
    a stored budget" mechanism anymore). Under this normal/default path EVERY
    row it returns is tagged to a real demand_id, by construction. Expect
    demand-tagged rows; do NOT default to assuming or reporting rows as
    unassigned. If you query for a specific demand and don't immediately see
    it, the far more likely explanation (confirmed root cause, repeatedly
    observed) is that you're matching against a STRIPPED id missing its
    `_VIRTUAL` suffix, not that the row doesn't exist or lacks a demand — see
    the demand_id-stripping note elsewhere in this primer. ALWAYS re-call
    get_critical_raw_allocation with `demand_id` set for a demand-specific
    question (the resolver there tolerates the missing suffix) rather than
    eyeballing an earlier, broader (unfiltered) result for a text match —
    manually scanning for an exact string is exactly how this class of bug
    keeps recurring.
    A row's `demand_id` CAN come back null in the recomputed grid — that
    means an untargeted lot with no demand currently drawing against it at
    that (product, location); it does NOT mean a user cleared anything (there
    is no more user-editable demand-level state to clear — see above; there
    is no "pool mode" or similar concept anywhere in this codebase, never
    invent one). A demand gets a cap for a (product, location) only if it has
    its OWN tagged row(s) there (any of ITS lots missing from ITS OWN rows
    are then forbidden — 0, not uncapped); a demand with ZERO rows of its own
    draws that material fully uncapped, via the normal waterfall.
    `get_critical_raw_allocation`'s `demand_specific_caps` field and `note`
    tell you plainly which situation you're in — trust and relay that, don't
    guess. TSA input rows (qty_cap/target) only ever cover genuinely RAW
    critical materials — critical STOCK (on-hand inventory of an otherwise-
    elastic, non-raw product that merely inherits targeting from its
    mandatory raw material) is never directly editable; its share of the
    recomputed grid is entirely derived.
  - **Supply Preferences** (`case_preference` table, a.k.a. "Preferences KB") —
    precomputed method/BOM-variant preference ranking, consulted in place of
    the raw CSV `preference` column when a (product, location, method) row
    exists here.
  - **Demand Ordering** (`case_demand_order` table) — precomputed demand
    processing order (by due_time, tie-broken by priority), consulted in
    place of the raw `(priority, demand_id)` sort when a row exists.
  - **Purchasable Materials** (`case_purchasable_material` table) — case-level
    whitelist of which raw materials may be bought.
  - **Constraints** (`case_constraint` table) — customer-specific
    BOM-alternative pins: (customer_id, parent, location) -> child. Forces
    which BOM-alternative child a customer's demand resolves to, at
    method+variant level (`location="*"` means any location).
  Each object is independently versioned (`CaseConfigVersions`, kind =
  casealloc/pref/ord/purchmat/constr); a case has one version marked default,
  but each plan_run/kb_record records the SPECIFIC version_id it actually
  used — which is not necessarily the case's current default. You only have a
  read tool for Targeted Supply Allocation's content. If asked about the
  CONTENT of Supply Preferences, Demand Ordering, Purchasable Materials, or
  Constraints (not just "which version did this run use", answerable from
  get_run_config where present) — say plainly you have no tool for that and
  point the user to the case's own page for that object, rather than
  guessing or fabricating a schema.

Tactics:
  - **Decide which knowledge layer the question lives in BEFORE picking a tool.**
    Three layers, three sources of truth (full table in agent-knowledge.md):
      • L1 — input dataset (CSVs: bom, method_*, supply, demand). Tools:
        get_bom_tree, find_move_path, trace_demand_to_supply,
        get_product_methods, get_product_supply.
        Use for: "is A in B's BOM?", "can A move L1→L2?",
        "does demand D require supply S?".
      • L2 — plan-run results (pegging, kpis, soundness). Tools:
        get_demand_pegging, compare_runs, explain_method_choice,
        get_kpis, get_run_config, get_soundness_summary,
        get_leaf_competition.
        Use for: "why did X fail?", "compare A vs B", "why was method 1
        chosen over method 2 at node N?" (call explain_method_choice —
        it bypasses the pegging-tree pruner so successful WOs deep in
        the tree stay visible).
      • L3 — advising new runs (KB + design rationale). Tools:
        recommend_config, pareto_kb_runs, query_kb_runs, suggest_next_batch.
        Use for: "best X with reasonable Y?", "what should I try next?".
    Cross-cutting: query_design_docs(topic) for source-of-truth quotes from
    docs/*.md when this primer's digest isn't specific enough.
    DON'T bring L2 tools to a L1 question (no run carries that info).
    DON'T ask L3 to answer a L1 feasibility question.

  - **Identifier formats are opaque.** product_id and location_id are arbitrary
    strings — product codes commonly contain hyphens (e.g. `502-2991`,
    `260-0312-02`, `M51__688`). Pass them VERBATIM from the user. Don't split
    on `-`, `_`, `@`, or `/` to invent separate fields. If the user says
    "502-2991 at location 2000", call with product_id=`502-2991` and
    location_id=`2000`, not product_id=`502` and location_id=`2991`.

  - **Defaulting `run_id` when the user omits it.** The system prompt carries
    `<viewing_run_id>` (the run currently shown on the user's page) and
    `<active_run_id>` (the case's designated active run, or the latest
    success). When a tool needs `run_id` and the user didn't name one:
      1. If `<viewing_run_id>` is a number, use it. The user is almost
         certainly asking about the run they're looking at.
      2. Else if `<active_run_id>` is a number, use it. State your assumption
         in the reply ("Using the active run #N — let me know if you meant a
         different one.").
      3. Else (both `(none)`) — ask the user which run.
    Don't invent a run number. If a prior turn in this same chat named a run
    and the topic clearly continued, that takes precedence over both anchors.

  - Reach for tools when the user asks "what would happen if…", "why…", or "how much…".
    Don't guess KPIs — call get_kpis. Don't guess pegging — call get_demand_pegging.
  - When a demand fails or commits short ("why didn't X commit?", "supply chain loop",
    "no supply method", "为什么 X 没满", "哪个物料是瓶颈", "what's the bottleneck",
    "what's the root cause", "shortage origin"), the unifying concept is:

    **SHORTAGE ORIGIN** = any pegging node with `is_bottleneck=true` OR
    `is_root_bottleneck=true`. Both flags identify ORIGINS of shortage. Once
    shortage crosses an origin, propagation up the tree is identical — the
    distinction matters only for the FIX, not for the DIAGNOSIS. Report all
    origins under one heading; never say "no bottleneck" when a node carries
    `is_root_bottleneck=true` (or vice versa).

    The two kinds (used to pick the right lever, NOT to bifurcate the diagnosis):

    **Kind=supply (瓶颈 / orange `is_bottleneck`)** — the BOM/inventory chain
    couldn't deliver at this leaf. Cascade reasons:
      1. `get_demand_pegging(run_id, demand_id)` — root demand carries
         `failure_explanation` for `no_methods`. Quote verbatim if present.
      2. Walk failed nodes; find the deepest leaf with the smallest first-pass
         effective/needed ratio. If pushback, call `get_product_methods` and
         `get_product_supply` on that leaf:
            • `make` exists at L1+L2 but no `move-to-needed-loc` → data gap.
            • `buy` exists but `purchase_allowed=false` → config gap.
            • `move` source has zero supply at the source → upstream provisioning gap.
      Levers: add a method, raise supply, allow purchase, fix transit edge.

    **Kind=demand (根因 / red `is_root_bottleneck`)** — consolidation's fair-share
    split left this demand with the tightest share-vs-need ratio at the flagged
    child (independent of supply).
      1. `get_leaf_competition(run_id, product_id, location_id)` — actual
         draws for every demand at the leaf + total_initial_supply.
      2. Articulate: "demand X got C/T (≈C%) because demand(s) [Y, Z] consumed
         K/T (≈K%) under allocation_mode=…".
      Levers: priority change for X, switch allocation_mode, change
      consolidation period, or a Targeted Supply Allocation adjustment (the
      case's pre-computed per-lot-per-demand budget — see
      get_critical_raw_allocation; edited on its own page, not from chat).

    **Mandatory workflow for any bottleneck/origin question:**
      1. Call `get_demand_pegging(run_id, demand_id)` and read `critical_path`.
         It is the ONLY field you need for this question. Each entry is
         `{pid, lid, kind, depth}` covering every dominator in the critical-
         path SUB-TREE. AND junctions contribute one child (the AND-min); OR
         junctions contribute every contributing child (so the path BRANCHES).
         THE CRITICAL PATH IS A TREE, NOT A CHAIN. Children with 0
         contribution (and their subtrees) are excluded.
      2. If `critical_path` is empty → "没有瓶颈物料 / no bottleneck."
      3. Optional drill-in: for terminals (entries where the next entry's
         depth ≤ current's depth) you may follow up with
         `get_product_methods` / `get_product_supply` (kind=supply) or
         `get_leaf_competition` (kind=demand) — but only if the user asks
         for the FIX, not for the bottleneck itself.

      **Required reply shape — quote `critical_path_pretty` verbatim
      inside a fenced code block.** It's a pre-formatted multi-line
      string with strict per-depth indentation (2 spaces per level)
      and language-localized kind labels. Wrap it in triple backticks
      so the indentation renders as-is — markdown collapses leading
      whitespace outside of code fences. Don't reformat the lines,
      don't re-derive them from `critical_path`, don't add "起源
      (terminals)" lines, don't add fix recommendations unless asked.
      Other flagged nodes in the tree are NOT origins for THIS demand —
      they may be flagged for a different demand or live on a
      non-contributing OR-branch. Never list them.

         需求 <D> 的关键路径上的物料：
         ```
         <critical_path_pretty here, verbatim>
         ```

    **CRITICAL — never fabricate a bottleneck story.** Before narrating
    `get_leaf_competition` as a competition/bottleneck cause:
      • Confirm the response carries `is_origin: true`. If `false` (or if
        `headroom > 0`), this leaf is NOT a shortage origin — it has slack,
        and the small `leaf_draw_qty` for a demand reflects small NEED, not
        exhausted supply. State that plainly: "311-0436@2000 isn't a
        bottleneck — supply 41,958 > total drawn 10,656; the small allocation
        reflects how much 10041744_10 needed at this leaf, not competition."
      • The right leaves to query are the ones in `origins` from
        get_demand_pegging (terminals of the critical-path sub-tree). Don't
        guess; don't pick arbitrary leaves.
      • A leaf with multiple drawers is competition, but competition is only
        a BOTTLENECK when supply was exhausted. Always quote total_supply,
        total_drawn, and headroom in your reasoning.

    **Anti-pattern (observed)**: calling `get_leaf_competition` on one
    arbitrary leaf, seeing `is_origin: false`, and concluding "no bottleneck
    material" — while other terminals in `origins` ARE origins. Walk every
    entry in `origins` before concluding.

    **Anti-pattern**: replying "no bottleneck material" when a kind=demand
    entry exists in `critical_path`, or vice versa. Both kinds are origins.
    Also: dumping the pegging tree as bullets — the user saw it in the UI.
    Your job is to NAME the origins from `critical_path`.

    **Per-demand allocation table — call `get_component_allocation_by_demand`**:
    when the user asks "how much P@L did each demand get?" / "list 物料 P@L
    在这些需求中的分配", that tool is the canonical answer. It returns one
    row per demand with explicit `status` (drew_at_leaf /
    walks_leaf_drew_zero / walks_other_location / doesnt_walk_product) so a
    `consumed_qty=0` row is never ambiguous. Do NOT assemble such a table
    from `get_leaf_competition.members[*].leaf_draw_qty` (or
    `competitors[*].leaf_draw_qty`) — those are leaf-side and produce null
    for `walk_at_other_location` / `walk_avoids_product`, which the agent
    historically misreported as "0 allocated overall" when in fact the
    demand consumed the product elsewhere in its pegging tree. If the
    user's question is genuinely "did demand D draw at THIS leaf?", say
    so — quote leaf_draw_qty alongside share_status so the scope is
    unambiguous. Cross-check: if you previously stated a non-zero P
    consumption for demand D from `get_demand_pegging`, do not later report
    0/null at this leaf without reconciling the two metrics explicitly
    (the new tool's `walks_other_location` status surfaces this directly).
  - Persist durable preferences via write_memory (e.g. user said "I never want purchase"
    → write_memory("purchase_default", false)). Memory is per-case.
  - Mirror the user's language (English / Chinese). Keep replies tight; be conversational.
  - When a plan finishes, end your reply with the plan_run_id and the headline KPIs.
  - When you make a config change, the user will see it applied to the form; you don't
    need to repeat it verbatim — just explain the *why* in their language.

EXECUTION RULES:
  - `run_plan_async` is non-blocking — it returns in under a second with a
    job_id and status='started'. The plan runs in the background and the
    chat panel posts the completion KPIs itself. Your reply should be ONE
    short sentence: "Plan started — I'll show the result here when it
    lands." Do NOT call `wait_for_plan`, `get_kpis`, or any other follow-up
    tool to fetch the result; the user will see the completion message
    automatically. Do NOT fabricate KPIs; the result is posted by the
    chat panel, not by you.
  - **Chaining a config change with "run/generate a plan".** When the user's message asks for
    BOTH a config change AND to run/generate a new plan (e.g. "set max methods to 3 and run a
    plan", "increase delivery weight then generate a new plan"), you MUST call `run_plan_async`
    as well as `update_config` before your final reply — either as a second tool call in the
    SAME response, or in the very next turn once you see `update_config`'s result. Do NOT stop
    after only `update_config` and then describe a plan as running; that violates the HONESTY
    RULES below.
  - When the user asks to run a candidate from `suggest_next_batch`, you
    MUST pass the candidate's `config` object to `update_config` VERBATIM
    — every top-level key AND every nested field, even ones unchanged from
    the current working config. Reason: `update_config` is a deep MERGE,
    so any field you omit silently keeps the prior value, which will
    diverge from the candidate's signature (e.g. period_days,
    allocation_mode often drop out and the actual run uses the wrong
    config). Treat the candidate.config as a recipe you must transcribe
    completely, not paraphrase.

HONESTY RULES (these override "be helpful"):
  - NEVER claim to have done something you didn't do. If the user asks you to "expand
    the KB", you have NO tool to expand it from chat — say so plainly: "I can't expand
    the KB from chat — click the 'Expand KB' button on the planning page, then ask me
    again." Do NOT pretend the expansion happened. Do NOT silently fall through to
    query_kb_runs and present existing rows as 'newly added'.
  - Same pattern for the 4 external config objects you have no read tool for (Supply
    Preferences / Demand Ordering / Purchasable Materials / Constraints — see "External
    config objects" above): if asked for their CONTENT, say "I don't have a tool to read
    [object]'s content — check its page in the case UI" rather than inventing rows,
    inventing a config schema (e.g. a `manual_override`-shaped JSON blob — that mechanism
    was fully retired and has no trace in the current codebase), or reasoning from
    unrelated tool output (e.g. get_run_config's plan_run.config) as if it were that
    object's data.
  - Specifically: NEVER say "plan started" / "running the plan now" / any phrasing implying a
    plan is executing unless `run_plan_async` actually appears in THIS turn's tool calls —
    check what you actually called, not what you intended to call. If the user asked for a
    config change AND a plan run but you only called `update_config` so far, either call
    `run_plan_async` now before replying, or say plainly "I updated the config but haven't
    started a plan yet — want me to run it?" Do not conflate "I changed the config that will
    be used for the next plan" with "a plan is running".
  - When the user asks for "configs to try next" / "what should I explore?" /
    "recommend new configurations": you MUST call suggest_next_batch. That tool is the
    ONLY source of NOVEL proposals (single-axis variations off the current best,
    dedup'd against every existing KB row + plan_run). DO NOT fall back to
    query_kb_runs and present existing KB rows as proposals — those are NOT novel,
    and the user can already see them. If suggest_next_batch returns an empty list,
    say so honestly: "the curated single-axis library is exhausted for this case —
    you've already explored every variation around the current best".
  - Before recommending a specific signature in any context, if you constructed it
    yourself (rather than reading it from suggest_next_batch), call is_signature_in_kb
    on it first. If exists=true, the proposal is NOT novel — pick something else or
    say so.
  - **Self-consistency on numeric facts.** Before reporting a numeric claim about a
    (demand_id, product_id) or (demand_id, product_id, location_id), scan prior turns
    in this conversation for any earlier number you stated about the same tuple. If
    your new number contradicts the earlier one, do NOT pick the latest blindly —
    reconcile explicitly. Two reconciliation paths: (a) explain why both numbers are
    correct under different metrics (e.g. "156 was leaf-side draw at @2000; 312 is
    the demand's whole-tree consumption of 300-0312") and present both, or (b)
    re-derive from a primary source — `get_demand_pegging` for whole-tree consumption,
    `get_component_allocation_by_demand` for per-demand-at-leaf — and quote the
    method explicitly. Never silently overwrite a prior claim with a new number.

L3 WORKFLOW RULES (these govern recommend_config and suggest_next_batch chains):
  - **Symptom-to-Objective Inference** (before recommending): When the user describes a
    planning concern, first classify it: is it a stated objective (clean, e.g. "best fill",
    "minimize purchase", "fair allocation") OR a symptom (ambiguous, e.g. "missing on
    customer X", "this run is worse than last", "buying too much", "everyone gets a partial
    fill")? For symptoms, infer the implied KPI lens and surface it in one sentence before
    recommending. Symptom patterns:
      • Distribution complaints ("unfair", "some demands starved") → fairness (gini, p10_fill, starvation)
      • Shipment shortfalls ("missing", "short", "not enough") → fill (fill_rate_pct)
      • Cost concerns ("buying too much", "cost high") → least_purchase
      • Delivery urgency ("late", "overdue") → earliest_commit or on_time_count
    For clean objectives: skip inference. When ambiguous or mixed (e.g. "fair but deliver more"):
    ASK before recommending which takes priority.

  - **Constraint Propagation**: Every recommend_config / suggest_next_batch / pareto_kb_runs
    call MUST include hard_constraint and soft_constraint args derived from the memory keys
    (hard_constraints, objective_soft_constraints) EVEN IF the user didn't restate them this
    turn. Current turn's explicit constraints override memory; otherwise memory is the default.
    Reason: multi-turn consistency — a user who said "never purchase" shouldn't see
    purchase-enabled configs in later replies just because they asked a follow-up without
    restating the constraint.

  - **Reuse Before Novel**: Before calling suggest_next_batch to propose new configs, ALWAYS
    first try to satisfy the user's stated or remembered objective + constraints with existing
    KB rows. Call query_kb_runs or pareto_kb_runs with the user's constraint set. If the top
    result satisfies the soft threshold, recommend IT (with cited_plan_run_id) instead of
    generating a novel proposal. Only after exhausting reuse (KB search returns 0 satisfying
    rows, or the user explicitly asks for novelty) call suggest_next_batch. Reason: reuse is
    faster, lower risk, and citable; novel is best as a second resort.

  - **Tradeoff Axis is Mandatory**: Every recommendation reply MUST include at least one
    alternate from recommend_config.alternates[], and the COMPARISON between them MUST be
    narrated by calling narrate_tradeoff (NOT freehand rationale). If the headline and
    alternates are identical or if recommend_config returns a single-point frontier, say so
    explicitly: "this is a single frontier point — no tradeoff axis to surface." Never omit
    the tradeoff narration; never respond with a single config without either an alternate or
    an explanation of why none exists.

  - **Sourced Configs and Rationales**: (a) Any signature in your reply MUST appear in the
    immediate tool output — either from recommend_config / suggest_next_batch / query_kb_runs,
    or from the KB. NEVER self-construct a signature — the format includes per-case content
    fingerprints (critical raw allocation overrides, supply preferences, demand ordering) you
    have no tool visibility into, so a self-assembled signature could never match what a real
    submission would actually get; always obtain one from a tool's returned output instead.
    (b) Any rationale for "why this config" MUST cite either a
    plan_run_id from KB evidence or a query_design_docs quote — never both, never neither.
    No self-authored mechanism stories ("this will help because…"); always ground in evidence.

──────────────────────────────────────────────────────────────────────
WORKFLOW — Downtime / maintenance window scheduling
──────────────────────────────────────────────────────────────────────

Trigger phrases (English):  "shut down X for N days",  "machine outage",  "line maintenance",
"prod area shutdown",  "take down line/area …",  "can I take L1 offline for a week",
"schedule a maintenance window".

Trigger phrases (Chinese — also recognise these):  "关闭/停机/停产 X N 天",  "X 维护 N 天",
"线 / 生产区 / 机台 / 设备 + 检修 / 停机 / 维护 / 保养",  "把 L1 停掉一周",  "OE 区停产 7 天",
"我想停机 X 维护"  — same workflow, same tools. Reply in Chinese.

Steps (DO NOT skip any):

  1. **Ground the user's term**. Never trust free-form labels verbatim.
     - For prod_area phrases ("OE", "assy line", "FAB"): call list_prod_areas.
       If exactly one match, use it. If multiple plausible matches (substring,
       prefix), ask the user which one. If none match, ask for clarification —
       don't guess.
     - For locations ("L1", "building 2", "plant A"): call list_locations.
     - For products: call get_product_methods or query find_wos with product_id.

  2. **Resolve the date**. Use <current_date> in the system prompt to convert
     relative phrases into ISO yyyy-MM-dd:
       "starting Monday"      → next Monday after today.
       "in 2 weeks"           → today + 14 days.
       "starting mid-July"    → 2026-07-15.
       "next quarter"         → first day of next calendar quarter.
     If date is fully omitted ("can I shut down OE for 7 days"), default
     bucketStart = today; mention this assumption in your reply.

  3. **Identify the WO set** with find_wos(prod_area=…, start_after=bucketStart,
     limit=500). DO NOT pass `start_before` — the impact pipeline shifts every
     lot of the selected gids whose start_time ≥ bucketStart with no upper
     bound, so capping start_before in find_wos would undercount what actually
     gets shifted (the preview wouldn't match the real result). Pull the
     wo_group_id values from the result and **remember them for the rest of
     this conversation turn** — every subsequent analyze_* call must use the
     SAME gid list. Do not re-call find_wos with different filters mid-analysis.

  4. **Get the safety envelope**: analyze_wo_availability(selectors=[{
     bucketStart, woGroupIds=[…all matched gids…]}]). Read max_feasible_days +
     bottlenecks.

  5. **Run the assessment via a single bundled call.**

     `assess_maintenance_options(prod_area, bucket_start, delay_days)` —
     this server-side tool does the entire pipeline atomically:
       (a) collects WO gids in prod_area starting on/after bucket_start
       (b) computes max_feasible_days from the availability DAG
       (c) generates the Option C contingent (original window, persist=true)
       (d) finds earliest_safe_start for Option B
       (e) generates the Option A contingent (shortened, persist=true)
       (f) generates the Option B contingent (deferred, persist=true; skipped
           only when no safe start exists within the horizon)

     All three contingent ids come back in the response payload as
     `option_a_cpr_id`, `option_b_cpr_id`, `option_c_cpr_id`. They are also
     cached server-side; the next turn's `<pending_maintenance_decision>`
     block exposes them by the same names.

     **Use this tool — do NOT chain find_wos / analyze_wo_availability /
     analyze_wo_schedule_impact x3 / find_earliest_safe_start manually.** That
     chain has a long tail of "one call got dropped under iteration pressure"
     failure modes. The bundled tool is atomic and guarantees every option
     has a contingent.

     The response payload also carries `rendered_impacts` (pre-formatted
     markdown table for Option 3's impacted-demand display), `max_feasible_days`,
     `alternate_start_date`, and `bottleneck_gid` / `bottleneck_end` —
     splice these into step 6's reply verbatim where they appear.

     Branch on the payload's `impacted_demand_count`:
       - 0 (everything fits within safe envelope) → Safe path, no option
         message; reply that the original window has zero impact, surface
         option_c_cpr_id, and ask the user to confirm (then promote_plan_run).
       - >0 → present all three options (step 6).

  6. **Suggest options** when N exceeds max-safe. The bundled tool returns a
     server-rendered `terminalReply` markdown that's already correctly framed
     (canonical labels Option A/B/C, A/B/C wording, deterministic Option B
     handling for all three states) — the user sees that string directly, so
     you don't need to re-render it. This step is just for understanding the
     tool's payload structure so you can act on the user's pick in step 7+.

     Option labels are CANONICAL — Option A = shorten (option_a_cpr_id),
     Option B = defer (option_b_cpr_id), Option C = accept impact
     (option_c_cpr_id). Labels never renumber/relabel, even if one option is
     unavailable. Letters only — A/B/C, not 1/2/3.

     Option B has THREE possible states the tool encodes via two fields:

       - `option_b_cpr_id` non-null + `alternate_start_date` non-null →
         "ok" state. A real contingent run was generated for the deferred
         window. User-pick B → call promote_plan_run with option_b_cpr_id.

       - `option_b_cpr_id` null + `alternate_start_date` non-null →
         "no_op_at_alt_start". ESS found a valid deferred date BUT no WO
         actually needs to shift there — the existing plan already
         accommodates the maintenance at that date. No CPR was generated
         because none is needed; the baseline plan is already valid. From
         the user's perspective Option B IS still a viable pick. User-pick B
         in this state → DO NOT promote anything; delete option_a_cpr_id and
         option_c_cpr_id (the other two contingents) and confirm to the
         user. There is nothing to promote; the maintenance window is
         already supported.

       - `option_b_cpr_id` null + `alternate_start_date` null →
         "no_safe_start_within_horizon". ESS couldn't find any later safe
         date. Option B is genuinely absent from the rendered reply. If the
         user somehow picks B, tell them that option wasn't available and
         ask them to pick A or C.

     `impacted_demand_count > 0` always means Option C exists with a CPR;
     `option_a_cpr_id` is present whenever `max_feasible_days > 0`.

  7. **On user acceptance of option (c)** — directly call promote_plan_run.
     The `plan_run_id` argument MUST come from the
     `<pending_maintenance_decision>` block in the system prompt context (the
     `contingent_plan_run_id=N` line). Do **NOT** use `<active_run_id>` or
     `<viewing_run_id>` — those are baseline ids unrelated to the contingent
     and using them is a no-op (active run is already at status="success") that
     silently fails. Do **NOT** copy a number from this prompt's example.
     If `<pending_maintenance_decision>` is absent, the offer has expired or
     was already accepted — ask the user instead of guessing. DO NOT re-run
     find_wos / analyze_wo_availability / analyze_wo_schedule_impact — the
     contingent run is already saved with the correct WO set; re-running could
     pick a different WO set and a different contingent.

  8. **If promote_plan_run fails** ("not_found", "wrong_status", "superseded"):
     STOP. Do NOT recover by re-running the impact analysis. Report the failure
     to the user verbatim and ask them how to proceed (likely you used the
     wrong id — re-read step 5's summary and find the correct
     contingent_plan_run_id).

  9. **Confirmation gate / option-C pattern matching**: NEVER call promote_plan_run
     without an explicit user acceptance. After the assessment's options were
     presented in step 6 and you're awaiting the user's choice, parse the
     reply as one of A/B/C (input is permissive — accept letters OR legacy
     numbers; output is always A/B/C):
       - **A** triggers: English: "a" / "A" / "1" / "one" / "first" / "option A" /
         "shorten" / "reduce". Chinese: "A" / "1" / "一" / "①" / "选项 A" /
         "选项 1" / "缩短" / "第一个".
       - **B** triggers: English: "b" / "B" / "2" / "two" / "option B" /
         "defer" / "shift start". Chinese: "B" / "2" / "二" / "②" /
         "选项 B" / "选项 2" / "推迟" / "第二个".
       - **C** triggers: English: "c" / "C" / "3" / "three" / "option C" /
         "(c)" / "yes" / "yes, promote" / "go ahead" / "amend" / "accept
         the impact" / "promote". Chinese: "C" / "3" / "三" / "③" /
         "选项 C" / "选项 3" / "第三个" / "接受" / "确认" / "采用" / "升格".
     DO NOT re-run any analysis to "verify" — the option choice is unambiguous.

  10. **On the option-pick turn, call commit_option_pick(option_letter).** This
      is the ONLY tool call on the resume turn. The server reads
      `<pending_maintenance_decision>` from cache, dispatches the correct
      action for the user's pick (A/B/C), promotes the right CPR (or, for
      Option B in no_op_at_alt_start state, promotes nothing), deletes the
      unchosen contingents, and returns a deterministic confirmation
      message — all atomically. Do NOT call promote_plan_run or
      delete_plan_run by hand on this turn — they are intentionally not
      part of the resume flow because the LLM kept mis-picking the wrong
      CPR id (e.g. promoting option_c_cpr_id when the user picked B).

      Tool call shape:
      ```json
      {"option_letter": "A"}   // or "B" or "C"
      ```

      The result includes `picked_option`, `promoted_plan_run_id` (null when
      the no_op_at_alt_start branch took the no-promote path), and
      `deleted_unchosen`. The server's `terminalReply` is the user-facing
      confirmation — emit it verbatim; do not add commentary like
      "the original plan has been amended" unless the tool result actually
      says so.

      Never re-run analyze_wo_availability / assess_maintenance_options /
      any other analysis on this turn — the cached CPRs from step 5 are
      authoritative.

  10b. **Picking from a parsed user reply** — once you've matched the user's
       message to A/B/C via step 9's triggers, call commit_option_pick with
       that letter. The server handles every case:

       - **A** → promotes option_a_cpr_id (shorten window); deletes B+C.
       - **B with option_b_cpr_id non-null** → promotes option_b_cpr_id
         (deferred plan); deletes A+C.
       - **B with option_b_cpr_id null + alternateStartDate non-null**
         (no_op_at_alt_start) → no promotion; existing plan already
         accommodates the deferred window; deletes A+C; confirms.
       - **B with both null** (no_safe_start_within_horizon) → returns
         `option_b_not_available`; relay verbatim and ask the user to pick
         A or C.
       - **C** → promotes option_c_cpr_id (accept impact); deletes A+B.

       **Hard rule**: if commit_option_pick returns an error
       (`plan_run_not_found`, `option_X_not_available`, etc.), STOP and
       report it verbatim. NEVER fabricate a "promoted" outcome that didn't
       come from the tool result, never invent new commit times, never
       claim the plan was amended when the tool's `promoted_plan_run_id`
       was null. The tool's `terminalReply` is the trustworthy
       user-facing message; everything else you add is at your own risk.

  11. **Contingent lifecycle.** commit_option_pick handles cleanup — the two
      unchosen contingents are deleted as part of the same call. Don't
      call delete_plan_run yourself on the maintenance flow.
      `delete_plan_run` remains available for unrelated cleanup the user
      explicitly asks for; it has no role in the option-pick resume turn.

Example A — within safe window:
  User: "Can I take down line L1 for a week without breaking anything?"
  Steps: list_locations → confirm L1 exists → find_wos(location_id=L1,
  start_after=<today>) → analyze_wo_availability(selectors=[{bucketStart=<today>,
  woGroupIds=[…]}]) → if max_feasible_days ≥ 7, reply "Yes — max safe is 14d,
  your 7d is well inside. Want me to record the scenario?"

Example B — exceeds safe window (use «PLACEHOLDER» tokens — never copy literal
numbers from this example into real tool calls; pull the real values from
your tool-result trace):

  User: "I want to shut down the OE prod area for about 7 days, please analyze
  impacts, suggest options, and possibly amend the plan."
  Steps:
    list_prod_areas → resolve "OE" (might be "OE_ASSY"; ask if ambiguous).
    find_wos(prod_area=«resolved», start_after=«today», limit=500) →
       REMEMBER the gid list as «GIDS».
    analyze_wo_availability(selectors=[{bucketStart=«today», woGroupIds=«GIDS»}])
       → max_feasible_days = «MAX_SAFE».
    if «MAX_SAFE» < 7:
        analyze_wo_schedule_impact(selectors=[{bucketStart=«today»,
            woGroupIds=«GIDS»}], delay_days=7, persist=true) →
            the tool's summary line ends with
            "…contingent_plan_run_id=«CONTINGENT_ID»". CAPTURE «CONTINGENT_ID».
    Reply with the three NUMBERED options (1, 2, 3) listing «MAX_SAFE» and
    the impacted demands; tell the user option 3 is "accept the impact and
    promote contingent plan run #«CONTINGENT_ID» as the new baseline".
  When user picks option 3 — by ANY form, e.g. "3" / "三" / "我选择 3" /
    "选择3" / "我选3" / "我选择C" / "yes, promote" / "接受" / "确认" /
    "go ahead" — IMMEDIATELY call
    promote_plan_run(plan_run_id=<contingent_plan_run_id from
    <pending_maintenance_decision>>). The id comes from the
    <pending_maintenance_decision> block in the system prompt — NOT from
    <active_run_id>, NOT from <viewing_run_id>, NOT from this example.
    DO NOT re-run find_wos or analyze_wo_*. The contingent is already saved.
  If <pending_maintenance_decision> is missing in the prompt, the offer has
    expired or was already accepted — ask the user.
  If promote fails: report the error to the user; do not recover by
    re-running analysis.

──────────────────────────────────────────────────────────────────────
WORKFLOW — Scenario Q&A playbooks (purchase/WO comparison, customer schedules)
──────────────────────────────────────────────────────────────────────

These three question shapes recur constantly across cases — recognize them and
go straight to the recipe instead of orchestrating tools from scratch.

  **Playbook 1 — weekly/daily purchase (or WO) comparison across products.**
  Trigger phrases (English): "weekly purchase plan for X and Y", "compare
  purchases of X vs Y", "side by side", "pull out weekly/daily purchase plans".
  中文: "X 和 Y 的每周采购计划", "对比 X 和 Y 的采购", "并排比较", "逐周/逐日采购计划".
    → call compare_alternatives(product_ids=[X, Y], group_by="week" (or "day")).
      Render `purchase_history[pid]` (already bucketed by period+location) as
      one table with a row per period, one column per product. Do NOT hand-bucket
      raw per-WO rows yourself when group_by is available — that's what it's for.

  **Playbook 2 — "why does X have more purchases/WOs than Y".**
  Trigger phrases (English): "why does X have more purchases than Y", "why is
  X's total higher", "explain the discrepancy between X and Y".
  中文: "为什么 X 的采购比 Y 多", "X 和 Y 的差异是什么原因", "解释一下差距".
    → call compare_alternatives(product_ids=[X, Y]) (group_by optional). Check,
      in order: (a) `opening_stock_total` — equal-split (raw_material_sourcing=
      "equal_split") guarantees equal DEMAND, not equal PURCHASE; the side with
      less starting stock buys sooner (see `first_purchase_date`) and more,
      even under an identical split; (b) `pinning_constraints` — a Constraints
      row can route one customer's ENTIRE demand to only one side, bypassing
      equal-split for it; (c) if root_waterfall=false (get_run_config), the
      root-level split itself may be uneven — check the config value rather
      than assuming a 50/50 split. Cite whichever of (a)/(b)/(c) the data
      actually shows; never narrate a mechanism the tool result doesn't support.

  **Playbook 3 — daily work orders for a customer, optionally across 2+ prod_areas.**
  Trigger phrases (English): "daily work orders for customer X", "WOs for
  customer X in CB and COC", "X and Y's respective CB, and shared COC".
  中文: "客户 X 的每日工单", "客户 X 在 CB 和 COC 的工单", "X 和 Y 各自的 CB，以及共用的 COC".
    → ground BOTH free-form terms first: list_customers for X (same grounding
      rule list_prod_areas/list_locations already use), list_prod_areas for
      the area names. If X isn't in list_customers' result, STOP before
      calling find_wos — see the "zero-match diagnosis" rule below, do not
      just retry find_wos with the same id. Once grounded, ONE call to
      find_wos(customer_id=X, prod_areas=[…], start_after=…, limit=500). Each
      returned row already carries its own `prod_area`, so a single call
      covers multiple areas — do not call find_wos once per area. Render as a
      daily table (one row per wo_group_id, sorted by start_time). Note in
      your reply that customer_id attribution assumes WO consolidation is off
      for this case (each native WO maps to exactly one demand_id → customer);
      say so explicitly if the run's config has any *_batch_scale other than
      "none".

    **"Shared" prod_area/product across two customers — query it directly, don't
    infer it.** When a user says one prod_area is "shared" between two
    customers (e.g. each has their own CB, sharing a common COC), that means a
    specific COMPONENT product is a BOM child of both customers' top-level
    products — confirm with get_bom_tree(each customer's own product_id) and
    look for the SAME child product_id under both. Once confirmed, get the
    shared rows with ONE call to find_wos(product_id=<shared child>,
    prod_area=…) — WITHOUT a customer_id filter, since the whole point is the
    combined production regardless of which demand triggered which lot. Do NOT
    approximate "shared" by calling find_wos separately per customer and then
    manually intersecting dates / summing quantities — that's an invented
    proxy for something find_wos can answer directly and exactly.

  **Zero-match diagnosis — a term not found in THIS case is not the same as
  "doesn't exist".** Every case is an independent dataset; the SAME id string
  can mean different things in different cases (a real, observed case: "Q4R"
  is a product_id in one case's data and a customer_id in an entirely
  different case). Never guess at spelling variants or retry the identical
  failing call. Instead, the moment ANY grounding/lookup tool (list_customers,
  list_prod_areas, list_locations, find_wos, get_product_methods, …) comes
  back empty for a user-supplied id:
    1. State which case you're currently scoped to — `<case_id>`/`<case_name>`
       from the system prompt context — plainly in your reply. This is the
       single most common actual cause and the one thing you can't infer from
       the tool result alone.
    2. Check whether the SAME string resolves under a DIFFERENT role in this
       same case (e.g. list_customers had no match → try
       find_wos(product_id=X) or get_product_methods(X); or vice versa).
    3. If neither resolves, say plainly that the id isn't in this case's data
       and ask the user to confirm the case/scenario they mean — do NOT
       silently keep retrying the same failing filter across multiple turns,
       and do NOT invent alternate id spellings ("maybe it's X__666?") without
       evidence from an actual tool result.

  **Playbook 3b — "why is there no WO for customer X on date Y" (absence, not lookup).**
  Trigger phrases (English): "why does X have no CB workorder on Aug 14",
  "why is there nothing scheduled for X that day".
  中文: "为什么 X 在 8 月 14 日没有 CB 工单", "为什么那天没有安排".
    This is a multi-tool synthesis, not a single lookup — no tool answers
    "why not" directly:
      1. find_wos(customer_id=X, prod_area=…, start_after=<day-3>,
         start_before=<day+3>) to confirm the gap and see what IS scheduled
         immediately around it. If a table earlier in THIS conversation
         already showed a value for that exact (customer, prod_area, date),
         a fresh call returning empty is a direct contradiction — say so
         explicitly and re-verify rather than silently going with whichever
         answer you produce last.
      2. get_critical_raw_allocation(product_id=…, demand_id=…) /
         get_critical_materials(run_id) to check whether a critical-material
         lead-time or targeting constraint (see the "Targeted Supply Allocation"
         primer above) is binding on that date — a demand can't get a WO
         before its critical inputs are available.
      3. Cross-check the demand's own request_due_time (get_demand_pegging or
         find_demands_for_product) — a WO legitimately absent on day Y but
         present on day Y±k is normal waterfall/lead-time scheduling, not a
         bug.
      4. Only if you need method-selection detail, call
         explain_method_choice/get_bom_tree — with a REAL location_id (ground
         via list_locations first; a real, observed failure was passing a
         prod_area string like "COC" as location_id, which matches nothing).
         If either returns empty/no alt_group, that is the complete answer —
         report "no alternate method/variant found," do not invent one.
    Cite whichever cause the data actually shows (lead-time constraint vs.
    inventory/critical-material exhaustion vs. simply no demand due that day)
    — never assert a mechanism you haven't confirmed against a tool result.
"""

// ── Tool registry ────────────────────────────────────────────────────────────
//
// JSON-Schema parameter shapes are inlined here; keep them tight (the model gets
// the description + parameters as token-cost overhead on every loop iteration).

private fun tool(name: String, description: String, parameters: JsonObject): LlmTool =
    LlmTool(name, description, parameters)

private fun emptyParams(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {})
    put("required", buildJsonArray { })
}

internal val TOOLS: List<LlmTool> = listOf(
    tool(
        "read_current_config",
        "Returns the working planning config for this conversation (most-recent saved or in-flight).",
        emptyParams(),
    ),
    tool(
        "update_config",
        "Apply a partial patch to the working planning config for this conversation. Deep-merges " +
            "`partial` onto the current working config — set only the keys that change (method_selection, " +
            "purchase_allowed, purchasable_materials, consolidation, analyze_criticality, check_soundness). " +
            "Does NOT run a plan by itself — call run_plan_async afterward if the user wants the change " +
            "applied. Returns the merged config as confirmation.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("partial") {
                    put("type", "object")
                    put("description", "Config keys to merge in, e.g. {\"method_selection\": {\"max_methods\": 3}}")
                }
            }
            put("required", buildJsonArray { add("partial") })
        },
    ),
    tool(
        "run_plan_async",
        "Start a plan run with the working config — NON-BLOCKING. Returns in <1 second with " +
            "{job_id, status='started', total_demands}. The chat panel polls in the background " +
            "and posts the completion KPIs automatically when the plan finishes; you do NOT " +
            "wait, do NOT call wait_for_plan, do NOT call get_kpis after this. Your reply " +
            "should be a single short sentence telling the user the plan is running and that " +
            "the result will appear shortly. The completion message is appended by the " +
            "frontend, NOT by you.",
        emptyParams(),
    ),
    tool(
        "wait_for_plan",
        "Re-check a plan job started in a PRIOR turn (rare). Use only when the user comes back " +
            "later asking 'is my plan done?' and you have the job_id from a previous turn — " +
            "but normally the chat panel auto-posts completion, so this is almost never " +
            "needed. Returns the same shape as run_plan_async. Do NOT call this in the same " +
            "turn as run_plan_async.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("job_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("job_id") })
        },
    ),
    tool(
        "list_plan_runs",
        "List recent plan runs for this case, newest first. Returns up to `limit` " +
            "rows. Each row includes id, status, name, created_at, soundness_status " +
            "PLUS the headline KPIs INLINE (fill_rate_pct, gini, p10_fill_ratio, " +
            "median_fill_ratio, starvation_pct, on_time_count, total_committed, " +
            "total_requested, manufacturing_total_quantity, inventory_consumed_total). " +
            "This is the bulk-query channel — for any 'compare across runs' / " +
            "'find the best on metric X' / multi-objective question, prefer ONE " +
            "list_plan_runs call (with limit 50) over N get_kpis calls. KPI fields " +
            "are omitted when null (contingent runs / older rows without computed " +
            "KPIs). Pass status='success' to skip contingent / failed / running.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max rows to return; defaults to 10, hard-capped at 50.")
                }
                putJsonObject("status") {
                    put("type", "string")
                    put("description",
                        "Optional status filter: 'success' | 'contingent' | 'failed' | 'running'. " +
                            "Omit to include all statuses.")
                }
            }
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "query_kb_runs",
        "Parametric filter+sort over the case's Knowledge Base of plan runs (kb_records). " +
            "Use this for KB-grounded questions when the KB has more rows than fit in " +
            "list_plan_runs(50): 'top N by metric X', 'best fill under constraint Y', " +
            "'all runs that varied axis Z'. Returns one row per (case, config-signature) — " +
            "no duplicates from re-runs. Each row carries a kpis snapshot inline " +
            "(fill_rate_pct, gini, p10_fill_ratio, median_fill_ratio, starvation_pct, " +
            "on_time_count, total_committed, total_requested, manufacturing_total_quantity, " +
            "inventory_consumed_total) plus plan_run_id (use this for downstream " +
            "get_run_config / get_kpis calls — NOT kb_record_id, which is internal), " +
            "signature, preset_id, primary_axis, soundness_status. Includes total_in_kb " +
            "so you can detect a sparse KB (suggest /expand-kb when <10).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("soundness_status") {
                    put("type", "string")
                    put("description", "Filter to one of: sound | unsound | unchecked. Omit for any.")
                }
                putJsonObject("min_fill_rate") { put("type", "number"); put("description", "fill_rate_pct ≥ this.") }
                putJsonObject("max_gini")      { put("type", "number"); put("description", "gini ≤ this.") }
                putJsonObject("min_p10_fill")  { put("type", "number"); put("description", "p10_fill_ratio ≥ this.") }
                putJsonObject("primary_axis") {
                    put("type", "string")
                    put("description", "Use one of these short axis names — NOT dotted paths: " +
                        "'max_methods', 'depth', 'allocation_mode', 'period_days', " +
                        "'purchase_allowed', 'consolidation_enabled'. Only runs that bootstrap-" +
                        "varied this axis off baseline.")
                }
                putJsonObject("preset_id") { put("type", "string"); put("description", "Filter to a single bootstrap preset id.") }
                putJsonObject("sort_by") {
                    put("type", "string")
                    put("description",
                        "fill_rate_desc | gini_asc | p10_fill_desc | starvation_asc | newest_first. " +
                            "Default: fill_rate_desc.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max rows; default 20, hard-capped at 100.")
                }
            }
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "pareto_kb_runs",
        "Compute the Pareto frontier of the case's KB over user-chosen KPI objectives. " +
            "Use for multi-objective questions like 'best fill rate with reasonable fairness' " +
            "or 'least purchase without hurting on-time': returns ONLY non-dominated runs, " +
            "saving you from computing the frontier in-prompt. Pre-filter via min_fill_rate / " +
            "max_gini to constrain the input set. KPI names must come from: fill_rate_pct, " +
            "gini, p10_fill_ratio, median_fill_ratio, starvation_pct, on_time_count, " +
            "total_committed, total_requested, manufacturing_total_quantity, " +
            "inventory_consumed_total. Returns frontier rows sorted by the first maximize " +
            "axis desc, plus a frontier_summary one-liner (e.g. '5 frontier points; " +
            "fill_rate spans 11–25, gini spans 0.28–0.42').",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("maximize") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "KPI names to maximize, e.g. ['fill_rate_pct'].")
                }
                putJsonObject("minimize") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "KPI names to minimize, e.g. ['gini'].")
                }
                putJsonObject("soundness_status") {
                    put("type", "string")
                    put("description", "Default 'sound'. Pass 'any' to include unsound/unchecked.")
                }
                putJsonObject("min_fill_rate") { put("type", "number") }
                putJsonObject("max_gini")      { put("type", "number") }
            }
            put("required", buildJsonArray { add("maximize"); add("minimize") })
        },
    ),
    tool(
        "is_signature_in_kb",
        "Check whether a given config-signature already has a KB record for this case. " +
            "Use this BEFORE recommending a 'config to try next' to confirm the proposal is " +
            "actually novel — never propose a signature that returns exists=true. The signature " +
            "format is the canonical pipe-delimited string emitted by the planner " +
            "(e.g. 'm=preference|max=2|d=1|bom=3|w=0.4,0.35,0.25|cons=true|p=0|purch=false|" +
            "casealloc=none|pref=none|ord=none|purchmat=none|constr=none'). NEVER self-construct " +
            "one — the casealloc/pref/ord/purchmat/constr segments are per-case content " +
            "fingerprints (critical raw allocation overrides / supply preferences / demand " +
            "ordering / purchasable materials / customer constraints) with no tool exposing " +
            "their underlying table contents, so a guessed signature could never match a real " +
            "submission. Always obtain a signature from another tool's returned output " +
            "(suggest_next_batch / recommend_config / query_kb_runs) and pass it here verbatim. " +
            "Returns {exists: bool, total_in_kb: int}.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("signature") {
                    put("type", "string")
                    put("description", "The exact canonical signature to check.")
                }
            }
            put("required", buildJsonArray { add("signature") })
        },
    ),
    tool(
        "suggest_next_batch",
        "Generate up to N candidate configs to run NEXT — single-axis variations off the case's " +
            "current best run, dedup'd against EVERY signature already in the KB or in any " +
            "non-failed plan_run. This is the only tool that produces NOVEL configs (never " +
            "rehashes). Each candidate is a draft — use update_config + run_plan_async to " +
            "actually queue it. Empty result means the curated single-axis library is exhausted " +
            "for this case (the user has already explored every variation around the current " +
            "best). When the user asks 'what should I try next?' / 'recommend configs to " +
            "explore' / 'next steps', call this — DO NOT fall back to query_kb_runs and " +
            "present existing rows as proposals.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("criterion") {
                    put("type", "string")
                    put("description", "Which 'best run' to seed variations from: " +
                        "'fill_rate' (default — best by fill_rate_pct desc), " +
                        "'fairness' (best by gini asc), " +
                        "'pareto' (Pareto-balanced over fill+fairness).")
                }
                putJsonObject("batch_size") {
                    put("type", "integer")
                    put("description", "Max candidates to return (default 3, hard-capped at 10).")
                }
            }
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "get_kpis",
        "Fetch full KPI dashboard (delivery / fairness / inventory / procurement / " +
            "manufacturing / logistics) for a plan run.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("run_id") })
        },
    ),
    tool(
        "get_demand_pegging",
        "Fetch the pegging tree for one demand in a plan run. Used to explain why a demand " +
            "was fulfilled across multiple work orders or hit partial fulfillment. Failed " +
            "demands carry a `failure_explanation` field on the root demand node when the " +
            "commit_reason is `no_methods` — surface it verbatim instead of paraphrasing. " +
            "**Always read `origins` and `critical_path` first** for the " +
            "'where's the bottleneck?' answer. The critical path is a SUB-TREE of " +
            "the pegging tree composed of dominators (AND junctions: single AND-min " +
            "child; OR junctions: every contributing child). It can branch — multiple " +
            "origins are normal when alternative supply paths each carry a shortage. " +
            "`critical_path` = depth-first flat list of `{pid, lid, kind, depth}` for " +
            "every node in the sub-tree (depth=1 is a child of the demand). " +
            "`origins` = list of terminal nodes (leaves of the sub-tree) — these are " +
            "the actual originating sources to fix. `critical_path_pretty` = the same " +
            "info as `critical_path`, pre-formatted as an indented multi-line string " +
            "with localized kind labels — quote it verbatim inside a fenced code block " +
            "for the answer. Empty origins+critical_path means the demand has no " +
            "shortage origins (fully fulfilled, or failed for non-shortage reasons — " +
            "check failure_explanation).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("demand_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("run_id"); add("demand_id") })
        },
    ),
    tool(
        "get_product_methods",
        "Read the case's static method registry for one product: every method_make, " +
            "method_move, and method_buy row defined for it. Use this when a demand fails " +
            "with `no_methods` / `no supply method` and the user asks why — combine with " +
            "get_product_supply to confirm whether the failure is a data gap (no method " +
            "row at the needed location) or a configuration gap (purchase disabled, etc.). " +
            "Returns { make: [{location, preference, lead_time}], move: [{from, to, " +
            "transit_time, preference}], buy: [{location, preference}] } — empty arrays " +
            "for method types with no rows.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("product_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("product_id") })
        },
    ),
    tool(
        "get_product_supply",
        "Read the case's supply table for one product: every initial-inventory row across ALL its " +
            "locations, with CONSUMPTION STATUS — use this to answer 'what's the consumption status " +
            "on product X's existing stock?' in a single call (no run_id/location_id dance needed " +
            "per location). Each row also carries `consumed_qty`/`residual_qty` (from the most recent " +
            "successful run, or `run_id` if given) once a successful plan run exists for the case; " +
            "response also carries `total_consumed`/`total_residual`, PLUS `by_location` — the same " +
            "totals already rolled up per location, so comparing one product's starting inventory " +
            "across locations (or against another product via two calls) never requires summing the " +
            "raw `rows` yourself. Pair with get_product_methods to diagnose 'why did this demand " +
            "fail?' — the typical answer is either zero supply at the needed (product, location) AND " +
            "no make/move/buy method to produce it there. Returns [{supply_id, location, qty, " +
            "supply_date, consumed_qty?, residual_qty?}] plus by_location, sorted by location then " +
            "supply_date.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("run_id") {
                    put("type", "integer")
                    put("description", "Which run's consumption to report. Defaults to the case's latest success run.")
                }
            }
            put("required", buildJsonArray { add("product_id") })
        },
    ),
    tool(
        "compare_alternatives",
        "Compare 2+ products side by side — supply on hand per location, purchase WO history over " +
            "time, and any customer-BOM constraint pinning one of them as the FORCED resolution of a " +
            "shared BOM slot. Purpose-built for 'these are equal-split BOM alternatives, why do their " +
            "purchase quantities differ' — pass both alternatives' product_ids (find them via " +
            "find_bom_siblings if you only have one) and this returns everything needed to explain BOTH " +
            "known causes in one call: (a) different starting on-hand inventory (equal-split guarantees " +
            "equal DEMAND, not equal PURCHASE — each side draws its own stock first, so purchase " +
            "quantities only converge once both are depleted — see per-product `opening_stock_total` " +
            "and `first_purchase_date`, the date each side's stock ran out and buying started), and " +
            "(b) `pinning_constraints` — a customer constraint routing that customer's ENTIRE demand " +
            "to one side, bypassing equal-split for it entirely. See agent-knowledge.md's 'Equal-split " +
            "raw-material sourcing' section for the full mechanism before answering. Also THE tool for " +
            "'weekly/daily purchase plan for product X and Y side by side' — pass `group_by` to get " +
            "`purchase_history` pre-bucketed into periods instead of one row per WO, so you don't have " +
            "to bucket dozens of individual purchase lots by hand.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("product_ids") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "2 or more product ids to compare (e.g. two BOM alt_group siblings).")
                }
                putJsonObject("location_id") {
                    put("type", "string")
                    put("description", "Optional — restrict supply/purchase comparison to one location.")
                }
                putJsonObject("plan_run_id") {
                    put("type", "integer")
                    put("description", "Which run's purchase history + config to use. Defaults to the case's latest success run.")
                }
                putJsonObject("group_by") {
                    put("type", "string")
                    put("description", "Optional — \"day\" or \"week\" to pre-bucket `purchase_history` by period " +
                        "(summed quantity per period per location) instead of one row per native WO. Omit for the " +
                        "raw per-WO rows (unchanged default behavior).")
                }
            }
            put("required", buildJsonArray { add("product_ids") })
        },
    ),
    tool(
        "find_bom_siblings",
        "Reverse BOM lookup: given ONE product id, find every BOM slot it fills (parent_id + " +
            "alt_group) and every OTHER product sharing that exact slot — its alternatives. Use this " +
            "when a user names only one product and you need to discover what it's an alternative " +
            "to/for, without already knowing the parent (get_bom_tree(parent_id) requires the parent; " +
            "this doesn't). Each sibling is flagged `purchasable_raw_material`; each slot carries " +
            "`equal_split_eligible` (2+ siblings, all purchasable raw materials — the same scope " +
            "PlanningEngine.kt's equal-split logic uses) as a shortcut. Feed the sibling ids straight " +
            "into compare_alternatives.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("product_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("product_id") })
        },
    ),
    tool(
        "get_leaf_competition",
        "**Demand-side root-cause story + zero-share members.** Two views over a (product, " +
            "location) leaf in a plan run. **`leaf_draw_qty` is the demand's draw at THIS " +
            "leaf only — NOT the demand's overall allocation of the product.** A demand may " +
            "consume the same product at another location (status=walk_at_other_location) " +
            "or take a different recipe (status=walk_avoids_product); for those the field " +
            "is null, not 0.0. For per-demand totals across all leaves, call " +
            "`get_demand_pegging` and sum the tree.\n" +
            "  • `competitors` — demands that drew > 0 with leaf_draw_qty + share_pct. " +
            "    Use for the 根因 (red badge / `is_root_bottleneck`) story: 'demand X got " +
            "    C/T (≈C%) because demands [Y, Z] together consumed K/T (≈K%) under " +
            "    allocation_mode=...'.\n" +
            "  • `members` — all demands whose BOM contains this product, drawers AND " +
            "    zero-share candidates, each tagged with `share_status` and " +
            "    `presumed_reason`. Use for 'why was demand D eliminated and how do I " +
            "    re-assign shares to it?'. Status values: drew_full / drew_partial / " +
            "    walk_at_other_location / walk_avoids_product (both = BOM contains pid " +
            "    but planner walk took a different branch — leaf_draw_qty=null, NOT actual " +
            "    elimination) / priority_filtered / share_starved_under_shortage / " +
            "    outside_bucket / override_blocked / zero_share. The `override_levers` " +
            "    array names the available override paths (a Targeted Supply Allocation " +
            "    adjustment — see get_critical_raw_allocation — change allocation_mode, " +
            "    change period_days, change demand.priority).\n" +
            "Returns { product_id, location_id, total_initial_supply, competitor_count, " +
            "competitors: [...], member_count, zero_share_count, members: [...], " +
            "consolidation: { allocation_mode, period_days }, override_levers }.\n" +
            "**Empty-leaf branch:** when (pid, lid) has no supply rows (e.g. the product is " +
            "made or bought rather than inventoried), the response collapses to " +
            "{ ..., note, supply_locations_for_product: [...], usages: [{ demand_id, " +
            "locations: [...] }], usage_demand_count }. `usages` answers the " +
            "demand-side question 'which demands USE this product?' — distinct from " +
            "the supply-side 'who drew from this leaf?'. Don't conflate empty supply " +
            "with empty usage.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("location_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("run_id"); add("product_id"); add("location_id") })
        },
    ),
    tool(
        "get_component_allocation_by_demand",
        "**Per-demand allocation table for a (product, location) leaf.** Answers " +
            "'how much P@L did each demand get?' / '物料 P@L 在这些需求中的分配情况'. " +
            "Returns one row per demand with `consumed_qty` (draw at THIS leaf only), " +
            "`requested_qty` (the demand's total request), `share_of_total_consumed_pct`, " +
            "and an explicit `status`:\n" +
            "  • drew_at_leaf            consumed > 0 from this leaf's supply\n" +
            "  • walks_leaf_drew_zero    walk reaches (pid, lid) but drew 0 here " +
            "(consolidation share / priority / override — call get_leaf_competition.members " +
            "for the precise cause)\n" +
            "  • walks_other_location    walk visits product at OTHER location(s) — the " +
            "demand consumed P, just NOT at this lid (`walks_locations` lists where)\n" +
            "  • doesnt_walk_product     walk doesn't visit pid anywhere — different recipe\n" +
            "  • no_pegging_entry        only when caller passed `demand_ids` and a listed " +
            "demand has no pegging entry in this run\n" +
            "**Use this tool — not get_leaf_competition — when the user asks for an " +
            "allocation TABLE across demands.** It avoids the leaf_draw_qty=0 ambiguity that " +
            "get_leaf_competition.members produces for walks_other_location / walks_avoids_product " +
            "demands (which DO consume the product, just elsewhere). Optional `demand_ids` " +
            "filter restricts the rows; without it, every demand the planner processed is " +
            "included (sorted by consumed_qty desc). For a demand's TOTAL consumption of " +
            "the product across all leaves, call get_demand_pegging on that demand and sum.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("location_id") { put("type", "string") }
                putJsonObject("demand_ids") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            put("required", buildJsonArray { add("run_id"); add("product_id"); add("location_id") })
        },
    ),
    tool(
        "get_critical_materials",
        "Return the actual, run-specific set of 'critical materials' (see the 'Critical materials' " +
            "primer in the system prompt for the definition — no make method anywhere, and either " +
            "no admitted buy or buy excluded by this run's config). Call this BEFORE reasoning " +
            "about 'critical material' anything — do not guess or pattern-match product_ids from " +
            "memory; whether a material is critical is version-dependent (purchase_allowed / " +
            "purchasable_materials can change it between runs). Derived from the SAME Critical Raw " +
            "Allocation budget rows get_critical_raw_allocation reads (so it can never disagree with " +
            "that tool about which materials are critical), aggregated per product_id across the " +
            "whole run: total qty_allocated, which locations it appears critical at, and how many " +
            "distinct demands drew against it — use those to pick candidates worth a deeper " +
            "get_critical_raw_allocation / get_leaf_competition call, not as a final per-demand-unit " +
            "ranking (this tool does not compute that ratio itself).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("run_id") })
        },
    ),
    tool(
        "get_critical_raw_allocation",
        "**Targeted Supply Allocation (TSA)** — one of the case's 5 versioned external config " +
            "objects (Targeted Supply Allocation, Supply Preferences, Demand Ordering, Purchasable " +
            "Materials, Constraints — see the system prompt's 'External config objects' primer). " +
            "The case's PERSISTED state for this object is INPUT-level (qty_cap/target per " +
            "critical-material lot, editable on the Targeted Supply Allocation page) — this tool " +
            "does NOT return that raw input. It recomputes and returns the resulting per-demand " +
            "OUTPUT grid (the same buildSupplyAllocation pass a real plan run makes, given that " +
            "version's TSA input), so the numbers you get always reflect what planning actually " +
            "used — there is no separate 'planner bypasses its own logic with a stored budget' " +
            "mechanism anymore. Critical materials ARE effectively pre-allocated per demand by " +
            "default — expect demand-tagged rows. For a demand-specific question, ALWAYS call this " +
            "tool again with `demand_id` set (its resolver tolerates a missing `_VIRTUAL` suffix) " +
            "rather than eyeballing an earlier, broader result for a text match — a demand_id that " +
            "looks 'missing' from a prior unfiltered table is almost always the `_VIRTUAL`-stripping " +
            "quirk, not evidence the row doesn't exist or lacks a demand; do not report rows as " +
            "unassigned/null without this tool's own `demand_specific_caps`/`note` telling you so. " +
            "Use this when the user asks for the 'critical raw allocation', 'critical material " +
            "allocation', 'targeted supply allocation', or 'pre-allocation' MAP for a product — this " +
            "is the recomputed budget table, not the plan's own committed consumption (for that, " +
            "use get_component_allocation_by_demand instead). " +
            "There is NO other tool that reads this table — do not confuse it with " +
            "`manual_override` (a DIFFERENT, retired mechanism — the DB table backing it was " +
            "dropped; do not reference `manual_override.component_split` or " +
            "`manual_override.method_selection`, they no longer exist and setting them does " +
            "nothing). If the user asks about Supply Preferences, Demand Ordering, or Constraints " +
            "content directly (not just which version a run used), say plainly you have no tool " +
            "for reading those and point them to the case's own pages instead of guessing.\n" +
            "Returns one row per (supply_id, demand_id) with `qty_allocated`, resolved against the " +
            "run's own `case_alloc_version_id` (each run pins a specific version — this is NOT the " +
            "case's current default unless the run used it). Empty `rows` + a plain-language note " +
            "means either the material has no TSA input rows in that version, or the run predates " +
            "versioning (no case_alloc_version_id recorded) — say so honestly, don't guess.\n" +
            "`demand_specific_caps` (bool) + `note` tell you plainly whether any row here actually " +
            "caps a demand's draws, per the 'External config objects' primer's Targeted Supply " +
            "Allocation entry — relay that `note` verbatim rather than inventing your own " +
            "explanation (e.g. there is NO 'pool mode' or similar named concept in this system).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("location_id") { put("type", "string") }
                putJsonObject("demand_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("run_id"); add("product_id") })
        },
    ),
    tool(
        "get_bom_tree",
        "**L1 / dataset-feasibility.** Expand the BOM recipe tree for a product or a " +
            "demand from the `bom` + `method_make` tables. Use to answer 'is A in B's " +
            "BOM?' / 'is A needed transitively for B?' / 'what does demand D require " +
            "at the leaves?'. Pass either `product_id` directly OR `demand_id` (looks " +
            "up the demand's product). Walks the tree (cycle-aware) and returns each " +
            "node with its children plus flags: `terminal_supply` (any supply rows for " +
            "this product), `makeable` (has a method_make), `buyable` (has a " +
            "method_buy), `alt_group` (when multiple recipes exist at this level), " +
            "`child_qty` (BOM rate from parent). Cycles render as `cycle_to: \"P\"`. " +
            "Cap depth via `max_depth` (default 4, max 8) — most real BOMs are 2-5 deep.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("demand_id") {
                    put("type", "string")
                    put("description", "Alternative to product_id; looks up the demand's product.")
                }
                putJsonObject("max_depth") {
                    put("type", "integer")
                    put("description", "Recursion depth limit, clamped to [1, 8]. Default 4.")
                }
            }
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "find_move_path",
        "**L1 / dataset-feasibility.** BFS shortest path over `method_move` edges for a " +
            "single product from `from_location` to `to_location`. Use to answer 'can " +
            "material A move from L1 to L2 (directly or via intermediate hops)?'. When " +
            "reachable: returns the path with each hop's transit_time + preference, plus " +
            "total_transit_time and hop count. When unreachable: returns " +
            "{ reachable: false, explored: [...] } so you can name the missing CSV row " +
            "(e.g. 'add method_move <product> from <last-explored-loc> to <to_location>'). " +
            "Caps at 6 hops; same-location returns hops=0.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("from_location") { put("type", "string") }
                putJsonObject("to_location") { put("type", "string") }
                putJsonObject("max_hops") {
                    put("type", "integer")
                    put("description", "Hop limit, clamped to [1, 10]. Default 6.")
                }
            }
            put("required", buildJsonArray { add("product_id"); add("from_location"); add("to_location") })
        },
    ),
    tool(
        "query_design_docs",
        "**Cross-cutting / source-of-truth retrieval.** Substring search over the " +
            "project's design docs (DESIGN.md, waterfall-allocation.md, " +
            "supply-level-consolidation.md, planner-conservation-fixes.md, etc.). " +
            "Returns the top-3 matching paragraphs with file + line citation, capped " +
            "at `max_chars` total response size. Use when the prompt-baked digest in " +
            "agent-knowledge.md isn't sufficient and you need a precise quote " +
            "(e.g. 'why does mode=elaborate hurt fairness?', 'what does R7d catch?'). " +
            "Returns { topic, hits: [{ file, line, text, score }], total_paragraphs, " +
            "truncated: bool }.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("topic") {
                    put("type", "string")
                    put("description", "Keyword(s) to search for. Case-insensitive substring; multiple words ranked by per-word match count.")
                }
                putJsonObject("max_chars") {
                    put("type", "integer")
                    put("description", "Max total response chars (sum of paragraph text). Default 2000, hard cap 4000.")
                }
            }
            put("required", buildJsonArray { add("topic") })
        },
    ),
    tool(
        "explain_method_choice",
        "**L2 / per-WO method-selection rationale + non-chosen-alternative classification.** " +
            "Answers 'why method X over Y at node N (product P @ location L)?' AND 'how do " +
            "I admit method Y?'. Walks the FULL pegging tree (bypasses get_demand_pegging's " +
            "pruner) to find the WO at (product_id, location_id), returns its " +
            "method_choice_explanation + parent demand context, AND lists every method at " +
            "the site classified by `status` + `presumed_reason` + `would_admit_if` hint:\n" +
            "  • `chosen` — selected by the planner\n" +
            "  • `lower_preference` — preference > chosen waterfall's max\n" +
            "  • `beyond_max_methods` — preference rank > methodCfg.max_methods\n" +
            "  • `purchase_disabled` — type=buy but purchase_allowed=false\n" +
            "  • `purchase_not_whitelisted` — type=buy but product absent from purchasable_materials whitelist\n" +
            "  • `failed_cascade_probe` — tried but BOM probe blocked deeper (failed=true)\n" +
            "  • `score_lower` — elaborate-mode catch-all for losing alternatives\n" +
            "Plus an `override_levers` array naming the available override paths " +
            "(a Constraints pin — see the 'External config objects' primer; forces which BOM-alternative " +
            "child a customer's demand resolves to, at method+variant level — / change " +
            "max_methods / change max_bom_depth / change mode / change score_weights / " +
            "flip purchase_allowed / re-rank preference). Symmetric to " +
            "get_leaf_competition's `members` enrichment. When " +
            "`demand_id` is provided, scope to one demand's tree; otherwise return all " +
            "matches across the run.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("location_id") {
                    put("type", "string")
                    put("description", "A real location_id (ground via list_locations, or reuse one already seen in a find_wos/ " +
                        "get_bom_tree result this turn) — NOT a prod_area value. location_id and prod_area are different " +
                        "dimensions (e.g. one real case has a single location_id \"1000\" but multiple prod_area values like " +
                        "\"CB\"/\"COC\"); passing a prod_area string here will simply match zero WOs and return an honest empty " +
                        "result — that empty result means no match was found, NOT that you should guess what it would show.")
                }
                putJsonObject("demand_id") {
                    put("type", "string")
                    put("description", "Optional — restrict matches to one demand's pegging tree.")
                }
            }
            put("required", buildJsonArray { add("run_id"); add("product_id"); add("location_id") })
        },
    ),
    tool(
        "compare_runs",
        "**L2 / plan-run comparison.** Side-by-side data for two plan runs: per-axis " +
            "config diff (only the paths that differ), KPI delta on the standard set " +
            "(fill_rate_pct, gini, p10_fill_ratio, median_fill_ratio, starvation_pct, " +
            "on_time_count, manufacturing_total_quantity, inventory_consumed_total), " +
            "and soundness-status delta. Also returns a `signature_match` boolean — " +
            "when true, both runs use the canonical-equivalent config and any KPI " +
            "delta is environmental noise (inventory state, etc.), not a config " +
            "effect. Pure data; the agent articulates the mechanism story (e.g. 'mode " +
            "= elaborate trades 3 min wall-time for ...') using agent-knowledge.md " +
            "tactics. Returns { a: {id, signature, soundness_status, kpis}, b: {...}, " +
            "signature_match, config_diff: [{path, a, b}], kpi_delta: {kpi: ±value}, " +
            "soundness_delta: 'a→b' }.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_a_id") { put("type", "integer") }
                putJsonObject("run_b_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("run_a_id"); add("run_b_id") })
        },
    ),
    tool(
        "trace_demand_to_supply",
        "**L1 / dataset-feasibility.** Joint reachability: does supply S have a " +
            "structural path to feed demand D, given the BOM + method graph? Combines " +
            "BOM containment (S's product must appear in D's recipe tree, transitively) " +
            "with location feasibility (S's location must reach the recipe step's " +
            "location via method_move chain, OR be the same location). Static — does " +
            "NOT consult plan-run pegging. Returns { reachable: bool, demand: {...}, " +
            "supply: {...}, bom_path: [...] (chain from D's product down to S's), " +
            "move_path: [...] (S's location to the consuming recipe location), " +
            "blocker: string? }. When unreachable, `blocker` names the failing edge " +
            "(e.g. 'S's product not in D's recipe' or 'no method_move from S's location " +
            "to the consuming recipe location').",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("demand_id") { put("type", "string") }
                putJsonObject("supply_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("demand_id"); add("supply_id") })
        },
    ),
    tool(
        "get_run_config",
        "Return the raw plan_run.config JSON that produced a specific plan run — exactly as " +
            "submitted (method_selection, consolidation, purchase_allowed, purchasable_materials, " +
            "variant_selection, constraints, etc.). This does NOT include the content of the 5 " +
            "versioned external config objects (see 'External config objects' in the system " +
            "prompt) — for Targeted Supply Allocation's content use get_critical_raw_allocation; for " +
            "the other 4 you have no read tool. Use this BEFORE comparing two runs' KPIs — to " +
            "confirm they share the same config (so any KPI delta is attributable to a single " +
            "load-bearing knob, not a confound). Returns {config}.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("run_id") })
        },
    ),
    tool(
        "recheck_soundness",
        "Re-run the soundness checker against a persisted plan run with the *current* rule set. " +
            "Use this when a new soundness rule has shipped (e.g. R7d) and you need to retroactively " +
            "apply it to an older run whose stored soundness_status predates the rule. Updates the " +
            "run's soundness_status / soundness_report and returns the full report.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("deep_check") {
                    put("type", "boolean")
                    put("description", "Whether to run the deep R8 conservation walk. Default true.")
                }
            }
            put("required", buildJsonArray { add("run_id") })
        },
    ),
    tool(
        "get_soundness_summary",
        "Aggregate a plan run's soundness_report into rule-level rollups. Returns " +
            "{overall_sound, demand_count, sound_count, violations_by_rule: [{rule, demand_count, " +
            "violation_count, total_actual}]}. Use INSTEAD of walking 200+ demands one at a time when " +
            "you just need the headline (\"how many demands violate which rules, by how much\").",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("run_id") })
        },
    ),
    tool(
        "read_memory",
        "Read all per-case memory entries (key/value JSON) the agent has stored for this case. " +
            "Use to recall durable user preferences from prior sessions.",
        emptyParams(),
    ),
    tool(
        "write_memory",
        "Persist a key/value into per-case memory. Value is any JSON. Overwrites any existing " +
            "entry with the same key.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("key") {
                    put("type", "string")
                    put("description", "Short stable identifier (≤128 chars), e.g. 'purchase_default'.")
                }
                putJsonObject("value") { put("description", "Any JSON value.") }
            }
            put("required", buildJsonArray { add("key"); add("value") })
        },
    ),

    // ── WO schedule-change / maintenance-window tools ────────────────────────
    tool(
        "list_prod_areas",
        "Return distinct prod_area values present in the case's baseline plan. Use BEFORE find_wos / " +
            "analyze_wo_schedule_impact when the user uses a free-form term (e.g. 'OE', 'assy line') " +
            "to ground it against the actual values. Returns count of WOs and a few sample products per area.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("plan_run_id") {
                    put("type", "integer")
                    put("description", "Plan run id; defaults to the latest success run for the case.")
                }
            }
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "list_locations",
        "Return distinct location_id values present in the case's baseline plan, with WO counts. " +
            "Use to ground free-form location terms (e.g. 'L1', 'building 2') against actual values.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("plan_run_id") {
                    put("type", "integer")
                    put("description", "Plan run id; defaults to the latest success run for the case.")
                }
            }
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "list_customers",
        "Return distinct customer_id values present in the case's demand data (with demand counts and " +
            "sample products), straight from the demand table — no plan run required. Use BEFORE " +
            "find_wos(customer_id=…) whenever the term is free-form / unconfirmed, the SAME grounding " +
            "rule as list_prod_areas/list_locations. Critically useful when a lookup by a given id " +
            "comes back empty: the SAME string can be a customer_id in one case and a product_id in " +
            "another (cases are independent datasets) — call this first (and check the id against " +
            "find_wos(product_id=…) too) rather than guessing at spelling variants, and always state " +
            "which case (<case_id>/<case_name>) you're scoped to when reporting a not-found result, since " +
            "that's the most common actual cause.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "find_wos",
        "THE general lookup for actual, planner-generated work orders (make, move, AND purchase) " +
            "— use this for questions like 'what purchase requests/orders exist for product X', 'list " +
            "buy work orders at location Y', 'what move orders are scheduled next month', 'daily work " +
            "orders for customer Q4R in CB and COC', etc. Pass method=\"purchase\" for purchase requests " +
            "specifically (\"buy\" is also accepted as an alias — CSV/table name is method_buy, but WOs " +
            "are stamped method=\"purchase\" at runtime). Also the entry point before calling " +
            "analyze_wo_availability / analyze_wo_schedule_impact for a maintenance/downtime scenario. " +
            "One row per wo_group_id (product_id, location_id, prod_area, method, start_time, end_time, " +
            "quantity, demand_id). Filters AND together (prod_areas is OR'd against itself, then AND'd " +
            "with the rest); omit any to match all. Returns up to `limit` rows (default 50, max 500).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prod_area")  { put("type", "string"); put("description", "Exact prod_area match. For 2+ areas in one call, use prod_areas instead.") }
                putJsonObject("prod_areas") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Match ANY of these prod_area values (e.g. [\"CB\", \"COC\"] for \"in CB and COC\"). Combine with prod_area for a single extra value.")
                }
                putJsonObject("location_id"){ put("type", "string"); put("description", "Exact location_id match.") }
                putJsonObject("product_id") { put("type", "string"); put("description", "Exact product_id match.") }
                putJsonObject("customer_id") {
                    put("type", "string")
                    put("description", "Exact customer_id match — resolved via each WO's demand_id (Demand.customer_id). Ground free-form " +
                        "customer terms via list_customers FIRST, same rule as prod_area/list_prod_areas — the same id string can mean a " +
                        "different real-world entity in a different case (e.g. a product_id in one case, a customer_id in another). Only " +
                        "meaningful when WO consolidation is off (one native WO per demand); with consolidation on, a consolidated WO can " +
                        "serve multiple customers and won't be attributable to just one.")
                }
                putJsonObject("method")     { put("type", "string"); put("description", "make / move / purchase (\"buy\" also accepted as an alias for purchase).") }
                putJsonObject("start_after") {
                    put("type", "string")
                    put("description", "ISO yyyy-MM-dd. Include WOs whose start_time ≥ this date.")
                }
                putJsonObject("start_before") {
                    put("type", "string")
                    put("description", "ISO yyyy-MM-dd. Include WOs whose start_time ≤ this date.")
                }
                putJsonObject("plan_run_id") { put("type", "integer") }
                putJsonObject("limit")       { put("type", "integer"); put("description", "Default 50, max 500.") }
            }
            put("required", buildJsonArray { })
        },
    ),
    tool(
        "analyze_wo_availability",
        "Closed-form max-safe-delay probe. For the given selectors, returns the largest N such that " +
            "displacing the front of the bucket by N days leaves every demand commit_time unchanged. " +
            "STRICT criterion (any commit shift = unsafe). Sub-millisecond. Returns max_feasible_days, " +
            "binding bottlenecks, and bottleneck demand details.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("selectors") {
                    put("type", "array")
                    put("description", "List of {bucketStart: ISO yyyy-MM-dd, woGroupIds: [string]}. Typically one entry.")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("bucketStart") { put("type", "string") }
                            putJsonObject("woGroupIds") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                        put("required", buildJsonArray { add("bucketStart"); add("woGroupIds") })
                    }
                }
                putJsonObject("plan_run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("selectors") })
        },
    ),
    tool(
        "analyze_wo_schedule_impact",
        "Run the full impact analysis: shift the matched WOs by delay_days (or to delay_to_date) and " +
            "report which demand commit_times move. Synchronous (~ sub-second to a few seconds). When " +
            "persist=true, creates a contingent plan run and returns its id (use promote_plan_run later " +
            "if the user accepts). Response also includes max_feasible_days for context.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("selectors") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("bucketStart") { put("type", "string") }
                            putJsonObject("woGroupIds") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                        put("required", buildJsonArray { add("bucketStart"); add("woGroupIds") })
                    }
                }
                putJsonObject("delay_days") { put("type", "integer"); put("description", "Mutually exclusive with delay_to_date.") }
                putJsonObject("delay_to_date") { put("type", "string"); put("description", "ISO yyyy-MM-dd.") }
                putJsonObject("plan_run_id") { put("type", "integer") }
                putJsonObject("persist") {
                    put("type", "boolean")
                    put("description", "Default true. When true, the contingent run is saved (promotable).")
                }
                putJsonObject("option_label") {
                    put("type", "string")
                    put("description",
                        "Optional. Set to 'a' when this call corresponds to option 1 (shortened to max_feasible_days), " +
                            "'b' when option 2 (deferred to alternate_start_date), or 'c' when option 3 (original window). " +
                            "Used by the server to cache the resulting contingent_plan_run_id under the right slot so the " +
                            "user's option pick can be honored with a single promote_plan_run call.")
                }
                putJsonObject("note") { put("type", "string") }
            }
            put("required", buildJsonArray { add("selectors") })
        },
    ),
    tool(
        "assess_maintenance_options",
        "Server-bundled end-to-end maintenance-window assessment. Given prod_area, bucket_start, and " +
            "delay_days, the allocator runs the FULL option pipeline in one call: (1) collect WO gids " +
            "in the prod_area starting on/after bucket_start; (2) compute max_feasible_days from the " +
            "availability DAG; (3) generate Option C contingent (original window, persist=true); " +
            "(4) find earliest_safe_start for Option B; (5) generate Option A contingent (shortened to " +
            "max_feasible_days, persist=true); (6) generate Option B contingent (deferred to " +
            "earliest_safe_start, persist=true; skipped if no safe start within horizon). All three " +
            "contingent ids are cached server-side so the user's option pick can be honored with a " +
            "single promote_plan_run. PREFER THIS over manually chaining find_wos / analyze_wo_availability / " +
            "analyze_wo_schedule_impact x3 / find_earliest_safe_start — the bundled tool is atomic and " +
            "guarantees all three contingents exist.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prod_area") {
                    put("type", "string")
                    put("description", "Prod area canonical id (use list_prod_areas to disambiguate first if unsure).")
                }
                putJsonObject("bucket_start") {
                    put("type", "string")
                    put("description", "ISO yyyy-MM-dd. The user's originally requested maintenance start date.")
                }
                putJsonObject("delay_days") {
                    put("type", "integer")
                    put("description", "Maintenance window length in days (the N the user asked for).")
                }
                putJsonObject("plan_run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("prod_area"); add("bucket_start"); add("delay_days") })
        },
    ),
    tool(
        "find_earliest_safe_start",
        "Find the earliest bucketStart >= after_date at which a delay_days maintenance window on " +
            "prod_area has ZERO impact (verified by simulation). Use this for Option 2 'try a different " +
            "start date' when the user's requested delay exceeds max_feasible_days. Allocator iterates " +
            "server-side over wo.start_time boundary dates with per-candidate simulation. Returns " +
            "earliestSafeStart, bottleneckGid, bottleneckEnd, maxFeasibleDaysAtStart, iterations on " +
            "success; or { error: 'no_safe_start_within_horizon', ... } if exhausted.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prod_area") {
                    put("type", "string")
                    put("description", "Prod area canonical id (use list_prod_areas to disambiguate).")
                }
                putJsonObject("delay_days") {
                    put("type", "integer")
                    put("description", "Maintenance window length in days.")
                }
                putJsonObject("after_date") {
                    put("type", "string")
                    put("description", "ISO yyyy-MM-dd. The earliest date the search may suggest — typically the user's requested bucketStart.")
                }
                putJsonObject("plan_run_id") { put("type", "integer") }
                putJsonObject("max_iterations") { put("type", "integer"); put("description", "Default 50, cap 200.") }
            }
            put("required", buildJsonArray { add("prod_area"); add("delay_days"); add("after_date") })
        },
    ),
    tool(
        "create_wo_schedule_event",
        "Persist a WO schedule-change event so it shows up on the WO Schedule Impact page for later " +
            "review/replay. Selectors + delay match analyze_wo_schedule_impact. Returns the event id.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("selectors") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("bucketStart") { put("type", "string") }
                            putJsonObject("woGroupIds") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                        put("required", buildJsonArray { add("bucketStart"); add("woGroupIds") })
                    }
                }
                putJsonObject("delay_days")    { put("type", "integer") }
                putJsonObject("delay_to_date") { put("type", "string") }
                putJsonObject("note")          { put("type", "string") }
            }
            put("required", buildJsonArray { add("selectors") })
        },
    ),
    tool(
        "commit_option_pick",
        "PREFERRED tool for the maintenance-window resume turn. Given the user's pick (A/B/C), the " +
            "server reads the pending_maintenance_decision cache and dispatches the right action: " +
            "promotes the correct option's contingent plan run, deletes the two unchosen contingents, " +
            "and renders a deterministic confirmation message — all in one call. Handles the " +
            "no_op_at_alt_start branch (Option B when the existing plan already accommodates the " +
            "deferred window) correctly by deleting unchosen contingents WITHOUT promoting anything. " +
            "Use this instead of calling promote_plan_run + delete_plan_run by hand on the resume turn — " +
            "the prior hand-chained path mis-promoted Option C's CPR as if it were Option B's deferred " +
            "plan and silently corrupted the active schedule.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("option_letter") {
                    put("type", "string")
                    put("enum", buildJsonArray { add("A"); add("B"); add("C") })
                    put("description", "The user's pick. Must be one of A, B, or C (uppercase).")
                }
            }
            put("required", buildJsonArray { add("option_letter") })
        },
    ),
    tool(
        "promote_plan_run",
        "Promote a contingent plan run to status='success' — making it the active baseline. ALWAYS " +
            "confirm with the user before calling this; never auto-promote. Idempotent on already-promoted runs. " +
            "For the maintenance-window option-pick resume turn, PREFER commit_option_pick(option_letter) — " +
            "it dispatches the correct CPR and handles the no_op_at_alt_start case that promote_plan_run can't.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("plan_run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("plan_run_id") })
        },
    ),
    tool(
        "find_demands_for_product",
        "Given a product/material id, return the list of demands whose plan chain includes work orders " +
            "for that product. Covers BOTH cases: (a) the product IS the demand's finished good, and " +
            "(b) the product is consumed somewhere downstream in the demand's BOM/pegging tree. Use this " +
            "to answer 'which demands use material X' / '请列出所有使用物料X的用户需求' — a single call " +
            "returns the answer instead of looping through trace_demand_to_supply per demand.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("product_id") {
                    put("type", "string")
                    put("description", "The material or finished-good id to look up.")
                }
                putJsonObject("plan_run_id") { put("type", "integer") }
                putJsonObject("limit") { put("type", "integer"); put("description", "Default 50, max 500.") }
            }
            put("required", buildJsonArray { add("product_id") })
        },
    ),
    tool(
        "delete_plan_run",
        "Delete an orphan plan run (status must be 'contingent', 'ready', or 'failed' — the tool " +
            "refuses 'success' to protect the active baseline, and 'running' to avoid races). Use this " +
            "to clean up the contingent_plan_run_id created in step 5 when the user picks Option 1 " +
            "(reduce shutdown) or Option 2 (different date), since the contingent is no longer needed. " +
            "Every generated contingent must end either promoted or deleted — never abandoned.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("plan_run_id") { put("type", "integer") }
            }
            put("required", buildJsonArray { add("plan_run_id") })
        },
    ),
)

// ── Memory helpers ───────────────────────────────────────────────────────────

private fun loadMemory(caseId: Int): JsonObject = transaction {
    val rows = AgentMemory.selectAll()
        .where { (AgentMemory.caseId eq caseId) and (AgentMemory.scope eq "case") }
        .toList()
    buildJsonObject {
        rows.forEach { r ->
            val k = r[AgentMemory.key]
            val raw = r[AgentMemory.value]
            val parsed = runCatching { jsonParser.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
            put(k, parsed)
        }
    }
}

private fun upsertMemory(caseId: Int, key: String, value: JsonElement) {
    transaction {
        val existing = AgentMemory.selectAll()
            .where {
                (AgentMemory.caseId eq caseId) and
                    (AgentMemory.scope eq "case") and
                    (AgentMemory.key eq key)
            }
            .firstOrNull()
        if (existing == null) {
            AgentMemory.insert {
                it[AgentMemory.caseId] = caseId
                it[AgentMemory.scope] = "case"
                it[AgentMemory.key] = key
                it[AgentMemory.value] = value.toString()
            }
        } else {
            AgentMemory.update({
                (AgentMemory.caseId eq caseId) and
                    (AgentMemory.scope eq "case") and
                    (AgentMemory.key eq key)
            }) {
                it[AgentMemory.value] = value.toString()
                it[AgentMemory.updatedAt] = CurrentTimestamp
            }
        }
    }
}

private val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

// ── Locale detection for tool result summaries ──────────────────────────────
//
// The reply itself is rendered by the LLM in whatever language the user's
// message is in (driven by the system-prompt tactic "Mirror the user's
// language"). Tool result summaries are different — they're built by Kotlin
// code and shown in the UI's step trace right under the reply. If the user
// is talking Chinese and the trace is English, the UX has a visible seam.
//
// Detect from the latest user message: any CJK-Unified-Ideograph code point
// → locale="zh", otherwise "en". Cheap, no API change, no frontend work.

private fun detectLocale(s: String): String =
    if (s.any { it in '一'..'鿿' || it in '㐀'..'䶿' }) "zh" else "en"

/** Pick a localized string. Defaults to English when the locale isn't recognized. */
internal fun loc(en: String, zh: String, locale: String): String =
    if (locale == "zh") zh else en

// ── Config merge (deep merge of JsonObject) ──────────────────────────────────

/** Recursive deep-merge: scalar / array values in `patch` replace those in `base`;
 *  nested JsonObject keys are merged key-by-key. Exposed as `internal` for tests. */
internal fun mergeJsonObject(base: JsonObject?, patch: JsonObject): JsonObject {
    val merged = (base?.toMutableMap() ?: mutableMapOf())
    for ((k, v) in patch) {
        val existing = merged[k]
        merged[k] = if (existing is JsonObject && v is JsonObject) {
            mergeJsonObject(existing, v)
        } else v
    }
    return JsonObject(merged)
}

// ── Tool implementations ─────────────────────────────────────────────────────
//
// Each tool returns (resultSummary, jsonResultForLLM). The summary is shown
// to the user as a chat-step row; the JSON is the body the LLM sees.

internal data class ToolResult(
    val summary: String,
    val payload: JsonElement,
    /** When set, the agent loop bypasses the final LLM rendering pass and uses
     *  this string verbatim as the assistant reply. Use for pure-lookup tools
     *  whose output is a definitive list/table — sending it through the LLM
     *  just burns tokens, risks truncation, and adds no value. */
    val terminalReply: String? = null,
)

private fun toolError(message: String, locale: String = "en"): ToolResult =
    ToolResult(
        summary = loc("error: $message", "出错：$message", locale),
        payload = buildJsonObject { put("error", message) },
    )

private fun toolReadCurrentConfig(workingConfig: JsonObject, locale: String): ToolResult {
    val keys = workingConfig.keys.joinToString(", ").ifBlank { loc("(empty)", "(空)", locale) }
    return ToolResult(
        summary = loc("Read working config ($keys)", "已读取当前配置（$keys）", locale),
        payload = workingConfig,
    )
}

private fun toolUpdateConfig(
    workingConfig: JsonObject,
    args: JsonObject,
    locale: String,
): Pair<ToolResult, JsonObject> {
    // OpenAI's function-calling sometimes sends the partial as a stringified
    // JSON, sometimes flattens the keys directly into args. Accept any of:
    //   1. {"partial": { ... }}                  ← schema-correct form
    //   2. {"partial": "{...}" }                 ← stringified
    //   3. {"max_methods": 2, "consolidation": ... }  ← flattened (no wrapper)
    // Step 1: pull the candidate from `partial` if present.
    val rawPartial: JsonElement? = args["partial"]
    val partial: JsonObject? = when (rawPartial) {
        is JsonObject -> rawPartial
        is JsonPrimitive -> if (rawPartial.isString) {
            runCatching { jsonParser.parseToJsonElement(rawPartial.content).jsonObject }.getOrNull()
        } else null
        else -> null
    } ?: run {
        // Step 2: no `partial` wrapper — treat the whole args as the partial,
        // but only if it has at least one recognized config top-level key.
        val configKeys = setOf(
            "method_selection", "consolidation", "purchase_allowed", "purchasable_materials", "constraints",
            "analyze_criticality", "check_soundness",
        )
        if (args.keys.any { it in configKeys }) args else null
    }
    if (partial == null || partial.isEmpty()) {
        return Pair(
            toolError(
                "Could not extract a config patch from your tool call. " +
                    "Pass `partial` as a JSON object, e.g. " +
                    """{"partial": {"method_selection": {"max_methods": 3}}}""",
                locale,
            ),
            workingConfig,
        )
    }
    val merged = mergeJsonObject(workingConfig, partial)
    val changedKeys = partial.keys.joinToString(", ")
    return Pair(
        ToolResult(
            summary = loc("Updated config: $changedKeys", "已更新配置：$changedKeys", locale),
            payload = merged,
        ),
        merged,
    )
}

private suspend fun toolRunPlanAsync(
    caseId: Int,
    workingConfig: JsonObject,
    locale: String,
): ToolResult {
    val configMap = jsonObjectToMap(workingConfig)
    val data = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: return@transaction null
        CaseLoader.load(caseId)
    } ?: return toolError("case $caseId not found", locale)
    if (data["demand"].isNullOrEmpty() || data["supply"].isNullOrEmpty()) {
        return toolError("case $caseId has no demand or supply rows; import CSV first", locale)
    }
    val jobId = UUID.randomUUID().toString()
    val total = data["demand"]?.size ?: 0
    planJobs[jobId] = mutableMapOf(
        "case_id" to caseId,
        "status" to "running",
        "progress" to mapOf("current" to 0, "total" to total),
        "result" to null,
        "error" to null,
    )
    // autoSave=true: chat-driven plans always commit to history + KB. There's
    // no equivalent "decide whether to keep this run" UX in chat (the user
    // already opted in by asking the agent to run it). The page-driven path
    // (POST /cases/{id}/plan?async=true) keeps the explicit save flow.
    engineScope.launch { runPlanBackground(jobId, caseId, data, configMap, autoSave = true) }
    // Truly non-blocking. Earlier we tried chaining wait_for_plan up to 60s
    // (then 25s) here so the agent could report KPIs in the same turn —
    // but gpt-4o-mini frequently squeezes in extra tool calls after the
    // wait returns, and the cumulative chat HTTP request blew past the
    // Next.js dev-server proxy's 60s rewrite timeout, dropping users into
    // the planningCopilot fallback. The job_id is returned immediately;
    // the chat panel's mode-2 polling on /plan/status/{job_id} reports
    // completion (and posts a synthetic "✓ Plan run N completed" message)
    // entirely outside the chat HTTP request.
    return ToolResult(
        summary = loc(
            "Plan job $jobId started ($total demands) — chat will post the result when it lands",
            "计划任务 $jobId 已启动（$total 个需求）— 完成后聊天会自动展示结果",
            locale,
        ),
        payload = buildJsonObject {
            put("job_id", jobId)
            put("status", "started")
            put("total_demands", total)
            put(
                "note",
                "The plan is running in the background. The chat panel polls and will append a " +
                    "completion message when it finishes — you do NOT need to call wait_for_plan " +
                    "or get_kpis. Just tell the user the plan started and that the result will " +
                    "appear automatically.",
            )
        },
    )
}

private suspend fun toolWaitForPlan(args: JsonObject, locale: String): ToolResult {
    val jobId = args["job_id"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`job_id` is required", locale)
    // 25-second window — must finish well below the Next.js dev-server proxy
    // timeout (60s) AND leave headroom for the surrounding LLM round trips.
    // When the wait expires with status=still_running, the frontend takes
    // over polling /plan/status/{job_id} and posts a completion message
    // when the plan finally lands.
    val deadline = System.currentTimeMillis() + 25 * 1000
    while (System.currentTimeMillis() < deadline) {
        val job = planJobs[jobId] ?: return toolError("job $jobId not found (server restart?)", locale)
        when (job["status"]) {
            "completed" -> {
                val runId = planJobRunIds[jobId]
                @Suppress("UNCHECKED_CAST")
                val result = job["result"] as? Map<String, Any?>
                val kpis = result?.get("plan_kpis") as? Map<String, Any?>
                val delivery = kpis?.get("delivery") as? Map<String, Any?>
                val fillPct = (delivery?.get("fill_rate_pct") as? Number)?.toDouble()
                val totalCommitted = (delivery?.get("total_committed") as? Number)?.toDouble()
                val totalRequested = (delivery?.get("total_requested") as? Number)?.toDouble()
                return ToolResult(
                    summary = loc(
                        "Plan run $runId completed: fill ${fillPct ?: "?"}% " +
                            "(${totalCommitted ?: "?"} / ${totalRequested ?: "?"})",
                        "计划运行 $runId 已完成：填充率 ${fillPct ?: "?"}% " +
                            "（${totalCommitted ?: "?"} / ${totalRequested ?: "?"}）",
                        locale,
                    ),
                    payload = buildJsonObject {
                        put("plan_run_id", runId)
                        put("status", "completed")
                        put("fill_rate_pct", fillPct ?: 0.0)
                        put("total_committed", totalCommitted ?: 0.0)
                        put("total_requested", totalRequested ?: 0.0)
                    },
                )
            }
            "failed" -> {
                val err = job["error"]?.toString() ?: "(unknown)"
                return ToolResult(
                    summary = loc("Plan failed: $err", "计划失败：$err", locale),
                    payload = buildJsonObject {
                        put("status", "failed"); put("error", err)
                    },
                )
            }
            else -> delay(2_000)  // poll every 2s
        }
    }
    val job = planJobs[jobId]
    @Suppress("UNCHECKED_CAST")
    val progress = job?.get("progress") as? Map<String, Any?>
    val current = (progress?.get("current") as? Number)?.toInt() ?: 0
    val total = (progress?.get("total") as? Number)?.toInt() ?: 0
    return ToolResult(
        summary = loc(
            "Plan still running ($current/$total demands after 60s) — agent should reply early",
            "计划仍在运行中（60 秒后 $current/$total 个需求）— 应尽快回复用户",
            locale,
        ),
        payload = buildJsonObject {
            put("status", "still_running")
            put("job_id", jobId)
            put("progress_current", current)
            put("progress_total", total)
            put(
                "note",
                "60-second wait window expired; plan is still in flight. End your turn now: " +
                    "tell the user the progress (e.g. \"$current of $total demands processed\"), " +
                    "include the job_id, and ask them to come back in a minute or two for the " +
                    "result. Do NOT call wait_for_plan again — the chat HTTP request will time " +
                    "out before the plan finishes.",
            )
        },
    )
}

private data class PlanRunSummary(
    val id: Int,
    val status: String,
    val name: String,
    val createdAt: String,
    val soundnessStatus: String,
    // Inline KPI snapshot (Phase A of agent KB scaling) — all the
    // headline metrics the comparative-diagnosis / multi-objective tactics
    // need, returned in one round-trip so the agent can reason across many
    // runs without N+1 get_kpis calls. Each is null when the run isn't
    // success-status or KPIs weren't computed (contingent runs).
    val fillPct: Double?,
    val gini: Double?,
    val p10FillRatio: Double?,
    val medianFillRatio: Double?,
    val starvationPct: Double?,
    val onTimeCount: Int?,
    val totalCommitted: Double?,
    val totalRequested: Double?,
    val mfgTotalQty: Double?,
    val invConsumedTotal: Double?,
)

private fun toolListPlanRuns(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
    val statusFilter = args["status"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    val items: List<PlanRunSummary> = transaction {
        PlanRuns.selectAll()
            .where {
                if (statusFilter != null)
                    (PlanRuns.caseId eq caseId) and (PlanRuns.status eq statusFilter)
                else
                    PlanRuns.caseId eq caseId
            }
            .orderBy(PlanRuns.createdAt to SortOrder.DESC)
            .limit(limit)
            .map { r ->
                val resultJson = r[PlanRuns.result]
                // Parse the plan_kpis subtree once and extract all headline
                // metrics; nullable lookups so older / contingent runs that
                // are missing fields just produce nulls rather than blow up.
                val parsedRoot: JsonObject? = if (resultJson.isNullOrBlank()) null
                    else runCatching { jsonParser.parseToJsonElement(resultJson).jsonObject }.getOrNull()
                val kpis: JsonObject? = parsedRoot?.get("plan_kpis") as? JsonObject
                val delivery: JsonObject? = kpis?.get("delivery") as? JsonObject
                val fairness: JsonObject? = kpis?.get("fairness") as? JsonObject
                val mfg: JsonObject? = kpis?.get("manufacturing") as? JsonObject
                val inv: JsonObject? = kpis?.get("inventory") as? JsonObject
                fun JsonObject?.numberAt(key: String): Double? = this?.get(key)
                    ?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                fun JsonObject?.intAt(key: String): Int? = this?.get(key)
                    ?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                PlanRunSummary(
                    id = r[PlanRuns.id],
                    status = r[PlanRuns.status],
                    name = r[PlanRuns.name] ?: "",
                    createdAt = r[PlanRuns.createdAt].toString(),
                    soundnessStatus = r[PlanRuns.soundnessStatus],
                    fillPct = delivery.numberAt("fill_rate_pct"),
                    gini = fairness.numberAt("gini"),
                    p10FillRatio = fairness.numberAt("p10_fill_ratio"),
                    medianFillRatio = fairness.numberAt("median_fill_ratio"),
                    starvationPct = fairness.numberAt("starvation_pct"),
                    onTimeCount = delivery.intAt("on_time_count"),
                    totalCommitted = delivery.numberAt("total_committed"),
                    totalRequested = delivery.numberAt("total_requested"),
                    mfgTotalQty = mfg.numberAt("total_quantity"),
                    invConsumedTotal = inv.numberAt("consumed_total"),
                )
            }
    }
    val summary = if (items.isEmpty())
        loc("no plan runs for case $caseId", "案例 $caseId 没有计划运行记录", locale)
    else
        loc("${items.size} runs, latest=${items.first().id}",
            "${items.size} 条记录，最新=${items.first().id}", locale)
    return ToolResult(
        summary = summary,
        payload = buildJsonObject {
            put("count", items.size)
            put("runs", buildJsonArray {
                items.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id)
                        put("status", s.status)
                        put("name", s.name)
                        put("created_at", s.createdAt)
                        put("soundness_status", s.soundnessStatus)
                        if (s.fillPct != null) put("fill_rate_pct", s.fillPct)
                        if (s.gini != null) put("gini", s.gini)
                        if (s.p10FillRatio != null) put("p10_fill_ratio", s.p10FillRatio)
                        if (s.medianFillRatio != null) put("median_fill_ratio", s.medianFillRatio)
                        if (s.starvationPct != null) put("starvation_pct", s.starvationPct)
                        if (s.onTimeCount != null) put("on_time_count", s.onTimeCount)
                        if (s.totalCommitted != null) put("total_committed", s.totalCommitted)
                        if (s.totalRequested != null) put("total_requested", s.totalRequested)
                        if (s.mfgTotalQty != null) put("manufacturing_total_quantity", s.mfgTotalQty)
                        if (s.invConsumedTotal != null) put("inventory_consumed_total", s.invConsumedTotal)
                    })
                }
            })
        },
    )
}

// ── KB-aware tools (query / Pareto frontier over kb_records) ────────────────

private fun kbRecordToJson(r: KbStore.KbRecord): JsonObject {
    val kpis = runCatching { jsonParser.parseToJsonElement(r.kpisSnapshotJson).jsonObject }
        .getOrElse { JsonObject(emptyMap()) }
    return buildJsonObject {
        // The headline `plan_run_id` is what get_run_config / get_kpis /
        // get_demand_pegging consume. `kb_record_id` is an internal book-
        // keeping id (one row per (case, signature) — re-runs upsert in
        // place). When the source plan_run was deleted, plan_run_id is null.
        if (r.sourcePlanRunId != null) put("plan_run_id", r.sourcePlanRunId)
        put("kb_record_id", r.id)
        put("signature", r.signature)
        if (r.presetId != null) put("preset_id", r.presetId)
        if (r.presetLabel != null) put("preset_label", r.presetLabel)
        if (r.primaryAxis != null) put("primary_axis", r.primaryAxis)
        put("soundness_status", r.soundnessStatus)
        put("source_plan_run_deleted", r.sourcePlanRunDeleted)
        put("kpis", kpis)
    }
}

private fun parseSortBy(s: String?): KbStore.KbSortBy = when (s?.trim()?.lowercase()) {
    "fill_rate_desc", null, "" -> KbStore.KbSortBy.FILL_RATE_DESC
    "gini_asc"        -> KbStore.KbSortBy.GINI_ASC
    "p10_fill_desc"   -> KbStore.KbSortBy.P10_FILL_DESC
    "starvation_asc"  -> KbStore.KbSortBy.STARVATION_ASC
    "newest_first", "created_desc" -> KbStore.KbSortBy.NEWEST_FIRST
    else -> KbStore.KbSortBy.FILL_RATE_DESC
}

private fun jsonArrayOfStrings(el: JsonElement?): List<String> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

private fun toolQueryKbRuns(caseId: Int, args: JsonObject, locale: String): ToolResult {
    // Lazy backfill — pulls any sound + successful plan_run that didn't get
    // upserted into kb_records (defense in depth against auto-save misses,
    // legacy rows, etc). Idempotent and bounded by case size.
    runCatching { KbStore.backfillForCase(caseId) }
    val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
    val sortBy = parseSortBy(args["sort_by"]?.jsonPrimitive?.contentOrNull)
    val filter = KbStore.KbQueryFilter(
        soundnessStatus = args["soundness_status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "any" },
        minFillRate = args["min_fill_rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        maxGini = args["max_gini"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        minP10Fill = args["min_p10_fill"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        primaryAxis = args["primary_axis"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        presetId = args["preset_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    )
    val result = KbStore.query(caseId, filter, sortBy, limit)
    val summary =
        if (result.totalInKb == 0)
            loc("KB empty for case $caseId — suggest /expand-kb",
                "案例 $caseId 的 KB 为空 — 建议运行 /expand-kb", locale)
        else
            loc("${result.rows.size} of ${result.totalInKb} KB rows match",
                "${result.rows.size}/${result.totalInKb} 条 KB 记录匹配", locale)
    return ToolResult(
        summary = summary,
        payload = buildJsonObject {
            put("count", result.rows.size)
            put("total_in_kb", result.totalInKb)
            put("runs", buildJsonArray {
                result.rows.forEach { add(kbRecordToJson(it)) }
            })
        },
    )
}

private fun toolParetoKbRuns(caseId: Int, args: JsonObject, locale: String): ToolResult {
    runCatching { KbStore.backfillForCase(caseId) }
    val maximize = jsonArrayOfStrings(args["maximize"])
    val minimize = jsonArrayOfStrings(args["minimize"])
    val rawSoundness = args["soundness_status"]?.jsonPrimitive?.contentOrNull?.trim()
    val soundness = when {
        rawSoundness.isNullOrBlank() -> "sound"   // default: only sound runs
        rawSoundness == "any"        -> null
        else                         -> rawSoundness
    }
    val prefilter = KbStore.KbQueryFilter(
        soundnessStatus = soundness,
        minFillRate = args["min_fill_rate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        maxGini = args["max_gini"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
    )
    val result = KbStore.paretoFrontier(caseId, maximize, minimize, prefilter)
    if (result.errors.isNotEmpty()) {
        return ToolResult(
            summary = loc(
                "pareto: ${result.errors.joinToString("; ")}",
                "pareto 错误：${result.errors.joinToString("; ")}",
                locale,
            ),
            payload = buildJsonObject {
                put("error", result.errors.joinToString("; "))
                put("allowed_kpi_names", buildJsonArray { KbStore.KPI_ALLOWLIST.forEach { add(it) } })
            },
        )
    }
    // Build a one-line frontier summary scanning the parsed KPIs of the frontier rows.
    val frontierKpis = result.frontier.map { KbStore.parseKpiSnapshot(it.kpisSnapshotJson) }
    val spans = (maximize + minimize).joinToString("; ") { kpi ->
        val vals = frontierKpis.mapNotNull { it[kpi] }
        if (vals.isEmpty()) "$kpi n/a"
        else "$kpi spans ${"%.2f".format(vals.min())}–${"%.2f".format(vals.max())}"
    }
    val frontierSummary = "${result.frontier.size} frontier point(s); $spans"
    val summary =
        if (result.totalInKb == 0)
            loc("KB empty for case $caseId — suggest /expand-kb",
                "案例 $caseId 的 KB 为空 — 建议运行 /expand-kb", locale)
        else
            loc("${result.frontier.size} frontier of ${result.totalInKb} KB rows",
                "在 ${result.totalInKb} 条 KB 记录中找到 ${result.frontier.size} 个帕累托前沿点", locale)
    return ToolResult(
        summary = summary,
        payload = buildJsonObject {
            put("count", result.frontier.size)
            put("total_in_kb", result.totalInKb)
            put("frontier_summary", frontierSummary)
            put("runs", buildJsonArray {
                result.frontier.forEach { add(kbRecordToJson(it)) }
            })
        },
    )
}

private fun toolIsSignatureInKb(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val sig = args["signature"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`signature` is required", locale)
    val (exists, total) = transaction {
        // Authoritative: kb_records covers KB-promoted runs; plan_run.config
        // covers user-driven runs that haven't been promoted yet (e.g. unsound).
        // Either match means the signature is "already tried".
        val kbHit = com.allocator.KbRecords.selectAll()
            .where { (com.allocator.KbRecords.caseId eq caseId) and (com.allocator.KbRecords.signature eq sig) }
            .firstOrNull() != null
        if (kbHit) {
            return@transaction true to com.allocator.KbRecords.selectAll()
                .where { com.allocator.KbRecords.caseId eq caseId }.count().toInt()
        }
        // Fall back: scan non-failed plan_runs and recompute signature.
        val planRunHit = com.allocator.PlanRuns.selectAll()
            .where { (com.allocator.PlanRuns.caseId eq caseId) and (com.allocator.PlanRuns.status neq "failed") }
            .toList().any { row ->
                val raw = row[com.allocator.PlanRuns.config] ?: return@any false
                val cfg = runCatching { jsonParser.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return@any false
                CaseBootstrap.signatureFor(cfg) == sig
            }
        planRunHit to com.allocator.KbRecords.selectAll()
            .where { com.allocator.KbRecords.caseId eq caseId }.count().toInt()
    }
    return ToolResult(
        summary = if (exists)
            loc("signature already in KB / plan_run history",
                "签名已存在于 KB / plan_run 历史中", locale)
        else
            loc("signature is NOVEL — safe to propose",
                "签名是新的 — 可以推荐", locale),
        payload = buildJsonObject {
            put("exists", exists)
            put("total_in_kb", total)
            put("signature", sig)
        },
    )
}

private fun toolSuggestNextBatch(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val batchSize = (args["batch_size"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 10)
    val criterionRaw = args["criterion"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    val criterion = when (criterionRaw) {
        null, "fill_rate" -> CaseBootstrap.CRITERION_FILL_RATE
        "fairness"        -> CaseBootstrap.CRITERION_FAIRNESS
        "pareto"          -> CaseBootstrap.CRITERION_PARETO
        else -> return toolError(
            "criterion must be 'fill_rate', 'fairness', or 'pareto' (got '$criterionRaw')",
            locale,
        )
    }
    val candidates = CaseBootstrap.selectNextBatch(caseId, batchSize, criterion)
    if (candidates.isEmpty()) {
        return ToolResult(
            summary = loc(
                "single-axis library exhausted for case $caseId — no novel proposals",
                "案例 $caseId 的单轴变体已穷尽 — 无新建议",
                locale,
            ),
            payload = buildJsonObject {
                put("count", 0)
                put("criterion", criterion)
                put(
                    "note",
                    "All single-axis variations off the current best are already in the KB or " +
                        "plan_run history. Suggest the user combine multiple knobs manually via " +
                        "update_config, or revisit older configs with new soundness rules.",
                )
            },
        )
    }
    return ToolResult(
        summary = loc(
            "${candidates.size} candidate config(s) (criterion=$criterion)",
            "${candidates.size} 个候选配置（criterion=$criterion）",
            locale,
        ),
        payload = buildJsonObject {
            put("count", candidates.size)
            put("criterion", criterion)
            put("candidates", buildJsonArray {
                candidates.forEach { p ->
                    add(buildJsonObject {
                        put("preset_id", p.presetId)
                        put("label", p.label)
                        put("primary_axis", p.primaryAxis)
                        put("signature", CaseBootstrap.signatureForBootstrapCandidate(p.config, caseId))
                        put("config", p.config)
                    })
                }
            })
            put(
                "note",
                "Each candidate is a single-knob variation off the current best, dedup'd against " +
                    "every signature in this case's KB + plan_run history. To run one, call " +
                    "update_config with `partial` set to the EXACT candidate.config object — " +
                    "every top-level key (purchase_allowed, purchasable_materials, constraints, method_selection, variant_selection, " +
                    "consolidation, analyze_criticality, check_soundness) and every nested field " +
                    "(consolidation.period_days, consolidation.allocation_mode, method_selection.max_bom_depth, …) MUST be present. " +
                    "update_config is a deep MERGE — anything you omit silently keeps the prior " +
                    "working-config value, which will diverge from the candidate's signature. " +
                    "Then call run_plan_async (it auto-saves and the chat panel posts the result).",
            )
        },
    )
}

/**
 * Multi-objective config recommendation. Wraps [KbStore.recommendConfig] with
 * arg parsing + JSON output shaping. The router internally chooses among
 * Pareto-knee, hard-constraint query, and novel-suggest branches based on
 * which arguments are populated.
 */
private fun toolRecommendConfig(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val objective = args["objective"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`objective` is required", locale)

    val softConstraint: com.allocator.services.KbStore.SoftConstraint? = run {
        val obj = args["soft_constraint"] as? JsonObject ?: return@run null
        val kpi = obj["kpi"]?.jsonPrimitive?.contentOrNull?.trim()
        val qual = obj["qualifier"]?.jsonPrimitive?.contentOrNull?.trim() ?: "reasonable"
        if (kpi.isNullOrBlank()) null
        else com.allocator.services.KbStore.SoftConstraint(kpi, qual)
    }
    val hardConstraint: com.allocator.services.KbStore.HardConstraint? = run {
        val obj = args["hard_constraint"] as? JsonObject ?: return@run null
        val kpi = obj["kpi"]?.jsonPrimitive?.contentOrNull?.trim()
        val op = obj["op"]?.jsonPrimitive?.contentOrNull?.trim()
        val value = obj["value"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        if (kpi.isNullOrBlank() || op.isNullOrBlank() || value == null) null
        else com.allocator.services.KbStore.HardConstraint(kpi, op, value)
    }
    val novelOnly = args["novel_only"]?.jsonPrimitive?.booleanOrNull == true

    val rec = com.allocator.services.KbStore.recommendConfig(
        caseId = caseId,
        objective = objective,
        softConstraint = softConstraint,
        hardConstraint = hardConstraint,
        novelOnly = novelOnly,
    )

    if (rec.errors.isNotEmpty()) {
        return toolError(rec.errors.joinToString("; "), locale)
    }

    fun renderRow(rec: com.allocator.services.KbStore.KbRecord): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(rec.id))
        put("signature", JsonPrimitive(rec.signature))
        put("preset_id", JsonPrimitive(rec.presetId))
        put("preset_label", JsonPrimitive(rec.presetLabel))
        put("primary_axis", JsonPrimitive(rec.primaryAxis))
        put("soundness_status", JsonPrimitive(rec.soundnessStatus))
        put("source_plan_run_id", rec.sourcePlanRunId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("kpis", runCatching { jsonParser.parseToJsonElement(rec.kpisSnapshotJson) }.getOrElse { JsonObject(emptyMap()) })
        put("config", runCatching { jsonParser.parseToJsonElement(rec.configJson) }.getOrElse { JsonObject(emptyMap()) })
    }

    val payload = buildJsonObject {
        put("objective", JsonPrimitive(objective))
        put("source", JsonPrimitive(rec.source))
        put("total_in_kb", JsonPrimitive(rec.totalInKb))
        if (rec.frontierSummary != null) put("frontier_summary", JsonPrimitive(rec.frontierSummary))
        put("rationale", JsonPrimitive(rec.rationale))
        put("headline", rec.headline?.let { renderRow(it) } ?: JsonNull)
        put("alternates", JsonArray(rec.alternates.map { renderRow(it) }))
    }

    val sumEn = rec.headline?.let { "Recommended: ${it.presetLabel ?: it.signature.take(40)} (source=${rec.source})" }
        ?: "No recommendation (${rec.source})"
    val sumZh = rec.headline?.let { "推荐：${it.presetLabel ?: it.signature.take(40)} (来源=${rec.source})" }
        ?: "无推荐 (${rec.source})"
    return ToolResult(
        summary = loc(sumEn, sumZh, locale),
        payload = payload,
    )
}

/**
 * Compare two configurations by signature and narrate the tradeoff.
 * Returns structured diff of knobs, KPI deltas, and a one-line axis label.
 */
private fun toolNarrateTradeoff(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val sigA = args["sig_a"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`sig_a` is required", locale)
    val sigB = args["sig_b"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`sig_b` is required", locale)

    val result = com.allocator.services.KbStore.narrateTradeoff(caseId, sigA, sigB)

    // Check for error.
    if (result["error"] != null) {
        return toolError(result["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error", locale)
    }

    val sumEn = result["summary"]?.jsonPrimitive?.contentOrNull ?: "configs compared"
    val sumZh = "配置对比：${result["axis_label"]?.jsonPrimitive?.contentOrNull ?: "不同"}"
    return ToolResult(
        summary = loc(sumEn, sumZh, locale),
        payload = result,
    )
}

/**
 * Substring-search the design-doc paragraph index. Returns the top-3 hits
 * ranked by per-word match count, capped at [max_chars] total response size.
 *
 * Doesn't take caseId — design docs are global (shared across cases).
 */
private fun toolQueryDesignDocs(args: JsonObject, locale: String): ToolResult {
    val topicRaw = args["topic"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`topic` is required", locale)
    if (topicRaw.isBlank()) return toolError("`topic` cannot be blank", locale)
    val maxChars = (args["max_chars"]?.jsonPrimitive?.intOrNull ?: 2000).coerceIn(200, 4000)

    val index = DESIGN_DOC_INDEX
    if (index.isEmpty()) {
        return ToolResult(
            summary = loc(
                "Design-doc index empty (docs/*.md not bundled into JAR). Build via gradle.",
                "设计文档索引为空（docs/*.md 未打包进 JAR）。请通过 gradle 重新构建。",
                locale,
            ),
            payload = buildJsonObject {
                put("topic", JsonPrimitive(topicRaw))
                put("hits", JsonArray(emptyList()))
                put("total_paragraphs", JsonPrimitive(0))
                put("truncated", JsonPrimitive(false))
            },
        )
    }

    // Score each paragraph by the count of word matches (case-insensitive).
    // Multi-word topics rank paragraphs that hit MORE of the words higher.
    val words = topicRaw.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
    if (words.isEmpty()) return toolError("`topic` has no searchable words", locale)

    data class Scored(val para: DesignDocParagraph, val score: Int)
    val ranked = index
        .map { p ->
            val lowered = p.text.lowercase()
            val score = words.count { w -> lowered.contains(w) }
            Scored(p, score)
        }
        .filter { it.score > 0 }
        .sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { it.para.file }
                .thenBy { it.para.lineStart },
        )

    if (ranked.isEmpty()) {
        return ToolResult(
            summary = loc(
                "No design-doc match for `$topicRaw`",
                "未找到与「$topicRaw」匹配的设计文档段落",
                locale,
            ),
            payload = buildJsonObject {
                put("topic", JsonPrimitive(topicRaw))
                put("hits", JsonArray(emptyList()))
                put("total_paragraphs", JsonPrimitive(index.size))
                put("truncated", JsonPrimitive(false))
            },
        )
    }

    // Take top-3, then truncate the tail when total response exceeds maxChars.
    val take = ranked.take(3)
    var running = 0
    val included = mutableListOf<Scored>()
    var truncated = false
    for (s in take) {
        if (running + s.para.text.length > maxChars) {
            // Include a partial last paragraph if room.
            val remaining = maxChars - running
            if (remaining > 100) {
                val truncatedPara = s.para.copy(text = s.para.text.take(remaining) + "…")
                included.add(s.copy(para = truncatedPara))
                running += remaining
            }
            truncated = true
            break
        }
        included.add(s)
        running += s.para.text.length
    }

    return ToolResult(
        summary = loc(
            "Design-doc hits for `$topicRaw`: ${included.size} paragraph(s) across ${included.map { it.para.file }.distinct().size} file(s)",
            "「$topicRaw」匹配 ${included.size} 个段落，覆盖 ${included.map { it.para.file }.distinct().size} 个文件",
            locale,
        ),
        payload = buildJsonObject {
            put("topic", JsonPrimitive(topicRaw))
            put("total_paragraphs", JsonPrimitive(index.size))
            put("truncated", JsonPrimitive(truncated))
            put("hits", JsonArray(included.map { s ->
                buildJsonObject {
                    put("file", JsonPrimitive(s.para.file))
                    put("line", JsonPrimitive(s.para.lineStart))
                    put("score", JsonPrimitive(s.score))
                    put("text", JsonPrimitive(s.para.text))
                }
            }))
        },
    )
}

private fun toolGetKpis(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val result = loadPlanResultFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId", locale)
    @Suppress("UNCHECKED_CAST")
    val kpis = (result["plan_kpis"] as? Map<String, Any?>)
    if (kpis.isNullOrEmpty()) {
        return ToolResult(
            summary = loc(
                "Run $runId has no plan_kpis (likely a contingent run)",
                "运行 $runId 没有 plan_kpis（可能是 contingent 模拟运行）",
                locale,
            ),
            payload = buildJsonObject {
                put("error", "no_plan_kpis")
                put("run_id", runId)
                put(
                    "note",
                    "This run has no precomputed KPIs. Common cause: status=contingent " +
                        "(what-if simulation from /material-impact). To see fairness/delivery " +
                        "KPIs, look at the baseline run this contingent was forked from " +
                        "(see plan_run.metadata.baselinePlanRunId), or use list_plan_runs " +
                        "with status=success to find a real run.",
                )
            },
        )
    }
    val delivery = kpis["delivery"] as? Map<String, Any?>
    val fillPct = (delivery?.get("fill_rate_pct") as? Number)?.toDouble()
    return ToolResult(
        summary = loc(
            "Run $runId KPIs (fill ${fillPct ?: "?"}%)",
            "运行 $runId 的 KPI（填充率 ${fillPct ?: "?"}%）",
            locale,
        ),
        payload = anyToJson(kpis),
    )
}

/** Resolves a possibly `_VIRTUAL`-stripped demand_id. Verified quirk (LLM-side, not a tool
 *  bug): the model routinely drops a consolidation-bucket demand's trailing `_VIRTUAL` suffix
 *  when writing it into a chat reply table, the user then copies that stripped id back into
 *  their next message, and the model re-uses it verbatim in a tool call — which genuinely
 *  fails, since `_VIRTUAL`-suffixed ids are the real, first-class `demand` rows. Tries
 *  [demandId] exactly first; if not found and it doesn't already end with `_VIRTUAL`, retries
 *  with the suffix appended. Returns the id that actually exists in `demand` for [caseId], or
 *  null if neither form does. */
private fun resolveDemandId(caseId: Int, demandId: String): String? = transaction {
    fun exists(id: String) = Demands.selectAll().where { (Demands.caseId eq caseId) and (Demands.demandId eq id) }.any()
    val trimmed = demandId.trim()
    when {
        exists(trimmed) -> trimmed
        !trimmed.endsWith("_VIRTUAL") && exists("${trimmed}_VIRTUAL") -> "${trimmed}_VIRTUAL"
        else -> null
    }
}

private fun toolGetDemandPegging(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val demandIdArg = args["demand_id"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`demand_id` is required", locale)
    val demandId = resolveDemandId(caseId, demandIdArg) ?: demandIdArg.trim()
    val resolvedNote = if (demandId != demandIdArg.trim())
        " (resolved from '${demandIdArg.trim()}' — likely a stripped `_VIRTUAL` suffix)" else ""
    val result = loadPlanResultWithPeggingFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId", locale)
    @Suppress("UNCHECKED_CAST")
    val pegging = result["planning_pegging"] as? List<Map<String, Any?>> ?: emptyList()
    val matchingEntries = pegging.filter { it["demand_id"]?.toString()?.trim() == demandId.trim() }
    if (matchingEntries.isEmpty()) {
        return toolError("demand $demandId not in run $runId pegging$resolvedNote", locale)
    }
    // The pegging list can carry MULTIPLE entries for the same demand_id —
    // typically one root demand tree (rooted at the demand's actual pid+lid)
    // plus one or more make-WO sub-trees that the planner emits as its own
    // pegging entries (for component-level work orders). Picking the first
    // match silently grabs a sub-tree, missing all flagged origin nodes which
    // are computed at the root-demand level. To pick the ROOT entry:
    //   1. Look up the demand's actual product_id + location_id from the
    //      Demands table.
    //   2. Pick the entry whose tree root matches that pid+lid.
    //   3. If lookup or match fails, fall back to the entry without
    //      `passthrough`/`per_demand_allocations` keys (sub-tree markers),
    //      then to the entry whose tree has the most children.
    val (demandPid, demandLid) = transaction {
        Demands.selectAll()
            .where { (Demands.caseId eq caseId) and (Demands.demandId eq demandId.trim()) }
            .firstOrNull()
            ?.let { it[Demands.productId] to it[Demands.locationId] }
            ?: (null to null)
    }
    fun treeRootMatches(entry: Map<String, Any?>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val t = entry["tree"] as? Map<String, Any?> ?: return false
        val tpid = (t["product_id"] as? String)?.trim()
        val tlid = (t["location_id"] as? String)?.trim()
        return tpid == demandPid && tlid == demandLid
    }
    fun isLikelySubtree(entry: Map<String, Any?>): Boolean =
        entry.containsKey("passthrough") || entry.containsKey("per_demand_allocations")
    val entry = matchingEntries.firstOrNull(::treeRootMatches)
        ?: matchingEntries.firstOrNull { !isLikelySubtree(it) }
        ?: matchingEntries.maxByOrNull { e ->
            @Suppress("UNCHECKED_CAST")
            ((e["tree"] as? Map<String, Any?>)?.get("children") as? List<*>)?.size ?: 0
        }
        ?: matchingEntries.first()

    // Cap the payload to keep the LLM context tractable — a deep BOM with
    // 200+ AND-children + virtual products easily blows past 128k tokens
    // (~133k observed on case-172 888_F11). Walk the tree, keep only nodes
    // along the failure path: failed=true work_orders, is_bottleneck /
    // is_root_bottleneck demand nodes, leaves with non-trivial commit_reason,
    // and the immediate children of the root for context. Fulfilled subtrees
    // collapse to a "{n_children} fulfilled children" summary leaf.
    @Suppress("UNCHECKED_CAST")
    val tree = entry["tree"] as? Map<String, Any?>
    val prunedTree = tree?.let { pruneTreeForAgent(it, depthFromRoot = 0) }

    // Pre-compute a flat list of shortage origins from the FULL tree (before
    // pruning, so we don't miss anything). Each origin = a node with
    // `is_bottleneck=true` or `is_root_bottleneck=true`. The agent is bad at
    // walking deep nested JSON to extract flagged nodes — it tends to pick
    // an arbitrary AND-child and stop. Surfacing this flat list ensures the
    // agent always knows WHICH leaves are origins without needing to inspect
    // the tree itself.
    val origins = mutableListOf<JsonObject>()
    if (tree != null) collectShortageOrigins(tree, origins)
    // De-duplicate by (pid, lid, kinds) — a leaf can appear multiple times
    // in the tree (different AND-paths) but is the same origin logically.
    val originsKeyed = origins
        .map { o ->
            val key = "${o["product_id"]?.jsonPrimitive?.contentOrNull}|" +
                "${o["location_id"]?.jsonPrimitive?.contentOrNull}|" +
                "${o["kind"]?.jsonPrimitive?.contentOrNull}"
            key to o
        }
        .associate { it }
    val originsJson = JsonArray(originsKeyed.values.toList())

    // Critical path: the dominator SUB-TREE from root demand to its
    // originating sources. AND junctions stay single-child; OR junctions
    // branch (every contributing child is a dominator). Result is a depth-
    // first flat list of {pid, lid, kind, depth}; terminals (leaves of the
    // sub-tree) are the actual origins — usually multiple when OR junctions
    // fan out.
    val criticalPathSteps = if (tree != null) traceCriticalPath(tree) else emptyList()
    // Terminals: a step is a leaf of the sub-tree iff the next step's depth
    // is ≤ this step's depth (or there is no next step). Skip the root.
    val terminalIndices = criticalPathSteps.indices.filter { i ->
        if (i == 0) return@filter false  // root
        val nextDepth = criticalPathSteps.getOrNull(i + 1)?.second
        nextDepth == null || nextDepth <= criticalPathSteps[i].second
    }.toSet()
    // Emit critical_path with depth, skipping the root (depth=0) since it's
    // the demand itself (already named in the question).
    val criticalPathJson = JsonArray(criticalPathSteps.drop(1).map { (node, depth) ->
        buildJsonObject {
            put("product_id", JsonPrimitive((node["product_id"] as? String).orEmpty()))
            put("location_id", JsonPrimitive((node["location_id"] as? String).orEmpty()))
            put("kind", JsonPrimitive(criticalPathKind(node)))
            put("depth", JsonPrimitive(depth))
        }
    })
    val terminalSteps = terminalIndices.sorted().map { criticalPathSteps[it] }
    val originsListJson = JsonArray(terminalSteps.map { (node, depth) ->
        buildJsonObject {
            put("product_id", JsonPrimitive((node["product_id"] as? String).orEmpty()))
            put("location_id", JsonPrimitive((node["location_id"] as? String).orEmpty()))
            put("kind", JsonPrimitive(criticalPathKind(node)))
            put("depth", JsonPrimitive(depth))
        }
    })

    // Pre-rendered indented form of `critical_path` for the agent to quote
    // verbatim. The agent was prone to flattening the indentation when
    // transcribing entries itself; building the lines here makes the visual
    // structure deterministic. Kind labels are localized to the user's
    // language so the agent doesn't translate them inconsistently.
    val criticalPathPretty = criticalPathSteps.drop(1).joinToString("\n") { (node, depth) ->
        val pid = (node["product_id"] as? String).orEmpty()
        val lid = (node["location_id"] as? String).orEmpty()
        val kindLabel = when (criticalPathKind(node)) {
            "supply" -> if (locale == "zh") "瓶颈" else "supply"
            "demand" -> if (locale == "zh") "根因" else "demand"
            else     -> if (locale == "zh") "关键路径" else "transit"
        }
        "${"  ".repeat(depth - 1)}depth $depth: $pid@$lid ($kindLabel)"
    }

    val prunedEntry = LinkedHashMap<String, Any?>().apply {
        put("origins", originsListJson)
        put("critical_path", criticalPathJson)
        put("critical_path_pretty", JsonPrimitive(criticalPathPretty))
        entry.forEach { (k, v) -> if (k != "tree") put(k, v) }
        if (prunedTree != null) put("tree", prunedTree)
    }

    val payload = anyToJson(prunedEntry)
    val payloadStr = payload.toString()
    val originalSize = anyToJson(entry).toString().length
    val summarySuffix = if (originalSize > payloadStr.length + 1024) {
        " (pruned ${originalSize}→${payloadStr.length} chars; fulfilled subtrees collapsed)"
    } else ""
    val originSummary = if (originsListJson.isNotEmpty()) {
        val rendered = originsListJson.take(5).joinToString(", ") { o ->
            val obj = o as JsonObject
            "${obj["product_id"]?.jsonPrimitive?.contentOrNull}@${obj["location_id"]?.jsonPrimitive?.contentOrNull}(${obj["kind"]?.jsonPrimitive?.contentOrNull})"
        }
        val ellipsis = if (originsListJson.size > 5) ", ..." else ""
        " · ${originsListJson.size} origin(s): $rendered$ellipsis; critical path of ${criticalPathJson.size} step(s)"
    } else if (originsJson.isEmpty()) {
        " · no shortage origins"
    } else {
        " · ${originsJson.size} shortage origin(s) (no critical-path tree built)"
    }

    return ToolResult(
        summary = loc(
            "Pegging tree for demand $demandId$originSummary$summarySuffix$resolvedNote",
            "需求 $demandId 的支撑链$originSummary$summarySuffix$resolvedNote",
            locale,
        ),
        payload = payload,
    )
}

/** Trace the critical path — a SUB-TREE of the pegging tree containing every
 *  dominator from root demand down to its originating sources. Reflects the
 *  AND/OR distinction:
 *   - **AND junction** (e.g., a method WO requiring all BOM components):
 *     pick the single AND-min child. The planner pre-marks it via
 *     `is_bottleneck` / `is_root_bottleneck`. Tie-breaker: smallest
 *     committed_qty/quantity ratio, then tree order. If no direct child is
 *     flagged but a descendant is, descend through the transit child with
 *     the smallest ratio (method WO between BOM levels carries no flag).
 *   - **OR junction** (e.g., a demand with alternative supply paths):
 *     EVERY contributing child (quantity > 0 OR committed_qty > 0) is a
 *     dominator. Recurse into all of them. The path branches.
 *  Returns the sub-tree as a depth-first flat list of (node, depth) pairs.
 *  Caller post-processes terminals (origins) by checking depth-vs-next. */
@Suppress("UNCHECKED_CAST")
private fun traceCriticalPath(root: Map<String, Any?>): List<Pair<Map<String, Any?>, Int>> {
    fun hasFlaggedDescendant(n: Map<String, Any?>): Boolean {
        if (n["is_bottleneck"] == true || n["is_root_bottleneck"] == true) return true
        val kids = n["children"] as? List<Map<String, Any?>> ?: return false
        return kids.any(::hasFlaggedDescendant)
    }
    fun ratio(c: Map<String, Any?>): Double {
        val q = (c["quantity"] as? Number)?.toDouble() ?: 0.0
        val cq = (c["committed_qty"] as? Number)?.toDouble() ?: q
        return if (q < 1e-9) 0.0 else cq / q
    }
    fun contributed(c: Map<String, Any?>): Boolean {
        val q = (c["quantity"] as? Number)?.toDouble() ?: 0.0
        val cq = (c["committed_qty"] as? Number)?.toDouble() ?: q
        return q > 1e-9 || cq > 1e-9
    }
    fun relationOf(n: Map<String, Any?>): String {
        val explicit = (n["children_relation"] as? String)?.lowercase()
        if (explicit == "or" || explicit == "and") return explicit
        // Fallback: work_order children are BOM components (AND); demand
        // children are alternative paths (OR); supply/purchase have no
        // meaningful children. Default OR keeps the walker open at unknown
        // node types so we don't miss flagged descendants.
        return if ((n["type"] as? String) == "work_order") "and" else "or"
    }

    val result = mutableListOf<Pair<Map<String, Any?>, Int>>()
    fun walk(node: Map<String, Any?>, depth: Int) {
        result.add(node to depth)
        val children = node["children"] as? List<Map<String, Any?>> ?: return
        if (children.isEmpty()) return
        // Universal rule: a child (and its subtree) with 0 contribution is NOT
        // on the critical path. The path traces actual flow.
        val contributing = children.filter(::contributed)
        if (contributing.isEmpty()) return
        if (relationOf(node) == "or") {
            // OR: every contributing child is a dominator.
            for (c in contributing) walk(c, depth + 1)
        } else {
            // AND: single dominator — the AND-min among contributing children.
            val flagged = contributing.filter { c ->
                c["is_bottleneck"] == true || c["is_root_bottleneck"] == true
            }
            val next = if (flagged.isNotEmpty()) {
                flagged.minByOrNull(::ratio)
            } else {
                // Transit through a contributing child with a flagged descendant
                // (e.g. method WO node between demand and BOM components).
                val transit = contributing.filter(::hasFlaggedDescendant)
                if (transit.isEmpty()) null else transit.minByOrNull(::ratio)
            }
            if (next != null) walk(next, depth + 1)
        }
    }
    walk(root, 0)
    return result
}

/** Classify a critical-path node's kind. Root demand isn't flagged; everything
 *  else falls into supply or demand kind (root_bottleneck preferred when both
 *  fire — demand-side is the more specific verdict). */
private fun criticalPathKind(node: Map<String, Any?>): String = when {
    node["is_root_bottleneck"] == true -> "demand"
    node["is_bottleneck"] == true -> "supply"
    else -> "root"
}

/** Walk a pegging tree and collect every node carrying `is_bottleneck=true`
 *  or `is_root_bottleneck=true`. Each emitted entry: `{product_id, location_id,
 *  kind: "supply" | "demand"}`. Allocator's two flags can both fire on the same
 *  node (rare); when so, two entries emit (one per kind). */
@Suppress("UNCHECKED_CAST")
private fun collectShortageOrigins(node: Map<String, Any?>, out: MutableList<JsonObject>) {
    val pid = (node["product_id"] as? String)?.trim()
    val lid = (node["location_id"] as? String)?.trim()
    if (!pid.isNullOrBlank() && !lid.isNullOrBlank()) {
        if (node["is_bottleneck"] == true) {
            out.add(buildJsonObject {
                put("product_id", JsonPrimitive(pid))
                put("location_id", JsonPrimitive(lid))
                put("kind", JsonPrimitive("supply"))
            })
        }
        if (node["is_root_bottleneck"] == true) {
            out.add(buildJsonObject {
                put("product_id", JsonPrimitive(pid))
                put("location_id", JsonPrimitive(lid))
                put("kind", JsonPrimitive("demand"))
            })
        }
    }
    val children = node["children"] as? List<Map<String, Any?>> ?: return
    for (c in children) collectShortageOrigins(c, out)
}

/**
 * Walk the pegging tree and keep only nodes that explain the failure.
 *
 *   - Always keep: root + immediate children (give the LLM the demand's top-level shape).
 *   - Keep on the failure path: nodes with `failed=true`, `is_bottleneck`,
 *     `is_root_bottleneck`, or with a non-success `commit_reason`.
 *   - Drop fully-fulfilled subtrees deeper than 2 levels — substitute a
 *     leaf node `{type: "summary", note: "…N fulfilled descendants"}`.
 *   - Always preserve `failure_explanation` on the root.
 *
 * Result: typical full pegging shrinks 5-50x (we've measured 130k → 5k chars
 * on case-172 888_F11) while keeping the load-bearing diagnosis intact.
 */
@Suppress("UNCHECKED_CAST")
private fun pruneTreeForAgent(node: Map<String, Any?>, depthFromRoot: Int): Map<String, Any?> {
    val children = node["children"] as? List<Map<String, Any?>> ?: emptyList()

    fun isFailureRelevant(n: Map<String, Any?>): Boolean {
        if (n["failed"] == true) return true
        if (n["is_bottleneck"] == true) return true
        if (n["is_root_bottleneck"] == true) return true
        val reason = n["commit_reason"] as? String
        if (!reason.isNullOrBlank() && reason !in BENIGN_COMMIT_REASONS) return true
        // Recursively check: if any descendant is failure-relevant, keep this node.
        val grandKids = n["children"] as? List<Map<String, Any?>> ?: return false
        return grandKids.any { isFailureRelevant(it) }
    }

    val keptChildren = mutableListOf<Map<String, Any?>>()
    var collapsedCount = 0
    var collapsedTotalQty = 0.0
    for (c in children) {
        val keep = depthFromRoot < 2 || isFailureRelevant(c)
        if (keep) {
            keptChildren.add(pruneTreeForAgent(c, depthFromRoot + 1))
        } else {
            collapsedCount++
            collapsedTotalQty += (c["quantity"] as? Number)?.toDouble() ?: 0.0
        }
    }
    if (collapsedCount > 0) {
        keptChildren.add(mapOf(
            "type" to "summary",
            "note" to "…$collapsedCount fulfilled subtree(s) collapsed (total qty ${collapsedTotalQty}). Call get_demand_pegging again with a different demand_id, or get_product_methods/supply for a specific component, if you need to inspect them.",
            "children" to emptyList<Any>(),
        ))
    }
    return node.toMutableMap().apply { put("children", keptChildren) }
}

/** Commit reasons that indicate normal fulfillment (not a failure path). */
private val BENIGN_COMMIT_REASONS = setOf("inventory", "partial", "")

private fun toolGetProductMethods(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`product_id` is required", locale)
    if (productId.isBlank()) return toolError("`product_id` cannot be blank", locale)

    val (makes, moves, buys) = transaction {
        val makeRows = MethodMakes.selectAll()
            .where { (MethodMakes.caseId eq caseId) and (MethodMakes.productId eq productId) }
            .map { row ->
                buildJsonObject {
                    put("location", JsonPrimitive(row[MethodMakes.locationId]))
                    put("preference", JsonPrimitive(row[MethodMakes.preference]))
                    put("lead_time", JsonPrimitive(row[MethodMakes.leadTime]))
                    put("bom_id", JsonPrimitive(row[MethodMakes.bomId]))
                }
            }
            .sortedBy { (it["location"] as? JsonPrimitive)?.contentOrNull ?: "" }
        val moveRows = MethodMoves.selectAll()
            .where { (MethodMoves.caseId eq caseId) and (MethodMoves.productId eq productId) }
            .map { row ->
                buildJsonObject {
                    put("from", JsonPrimitive(row[MethodMoves.fromLocationId]))
                    put("to", JsonPrimitive(row[MethodMoves.toLocationId]))
                    put("transit_time", JsonPrimitive(row[MethodMoves.transitTime]))
                    put("preference", JsonPrimitive(row[MethodMoves.preference]))
                }
            }
            .sortedWith(compareBy(
                { (it["from"] as? JsonPrimitive)?.contentOrNull ?: "" },
                { (it["to"] as? JsonPrimitive)?.contentOrNull ?: "" },
            ))
        val buyRows = MethodBuys.selectAll()
            .where { (MethodBuys.caseId eq caseId) and (MethodBuys.productId eq productId) }
            .map { row ->
                buildJsonObject {
                    put("location", JsonPrimitive(row[MethodBuys.locationId]))
                    put("preference", JsonPrimitive(row[MethodBuys.preference]))
                    put("lead_days_supply", JsonPrimitive(row[MethodBuys.leadDaysSupply]))
                }
            }
            .sortedBy { (it["location"] as? JsonPrimitive)?.contentOrNull ?: "" }
        Triple(makeRows, moveRows, buyRows)
    }

    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        put("make", JsonArray(makes))
        put("move", JsonArray(moves))
        put("buy", JsonArray(buys))
    }
    return ToolResult(
        summary = loc(
            "Methods for $productId — make:${makes.size} move:${moves.size} buy:${buys.size}",
            "$productId 的方法定义 — make:${makes.size} move:${moves.size} buy:${buys.size}",
            locale,
        ),
        payload = payload,
    )
}

private fun toolGetProductSupply(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`product_id` is required", locale)
    if (productId.isBlank()) return toolError("`product_id` cannot be blank", locale)
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull

    // Consumption status (optional): aggregate qty_consumed per supply_id from the run's
    // supply_allocations — the SAME field extractSupplyAllocations populates for every plan run
    // (no separate pegging load needed). Silently omitted (rows carry qty only) when no
    // successful run exists yet for this case — a fresh case has real supply but nothing to
    // report consumption against.
    val consumedBySupplyId: Map<String, Double> = run {
        val result = loadPlanResultFromDb(caseId, runId) ?: return@run emptyMap()
        @Suppress("UNCHECKED_CAST")
        val allocations = (result["supply_allocations"] as? List<Map<String, Any?>>) ?: emptyList()
        allocations
            .groupBy { it["supply_id"] as? String ?: "" }
            .mapValues { (_, entries) -> entries.sumOf { (it["qty_consumed"] as? Number)?.toDouble() ?: 0.0 } }
    }

    val rows = transaction {
        Supplies.selectAll()
            .where { (Supplies.caseId eq caseId) and (Supplies.productId eq productId) }
            .map { row ->
                val qty = row[Supplies.qty]
                val consumed = consumedBySupplyId[row[Supplies.supplyId]]
                buildJsonObject {
                    put("supply_id", JsonPrimitive(row[Supplies.supplyId]))
                    put("location", JsonPrimitive(row[Supplies.locationId]))
                    put("qty", JsonPrimitive(qty))
                    put("supply_date", JsonPrimitive(row[Supplies.supplyDate]))
                    if (consumedBySupplyId.isNotEmpty()) {
                        put("consumed_qty", JsonPrimitive(consumed ?: 0.0))
                        put("residual_qty", JsonPrimitive(qty - (consumed ?: 0.0)))
                    }
                }
            }
            .sortedWith(compareBy(
                { (it["location"] as? JsonPrimitive)?.contentOrNull ?: "" },
                { (it["supply_date"] as? JsonPrimitive)?.contentOrNull ?: "" },
            ))
    }
    val totalQty = rows.sumOf { (it["qty"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }
    val totalConsumed = rows.sumOf { (it["consumed_qty"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }
    // Per-location rollup — callers comparing two products' starting inventory at a specific
    // location (e.g. two equal-split BOM alternatives) would otherwise have to filter+sum the
    // raw `rows` themselves; this is the same aggregation, done once, per location.
    val byLocation = rows.groupBy { (it["location"] as? JsonPrimitive)?.contentOrNull ?: "" }
        .entries.sortedBy { it.key }
        .map { (locName, locRows) ->
            val locQty = locRows.sumOf { (it["qty"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }
            val locConsumed = locRows.sumOf { (it["consumed_qty"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }
            buildJsonObject {
                put("location", JsonPrimitive(locName))
                put("total_qty", JsonPrimitive(locQty))
                if (consumedBySupplyId.isNotEmpty()) {
                    put("total_consumed", JsonPrimitive(locConsumed))
                    put("total_residual", JsonPrimitive(locQty - locConsumed))
                }
            }
        }
    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        put("total_qty", JsonPrimitive(totalQty))
        if (consumedBySupplyId.isNotEmpty()) {
            put("total_consumed", JsonPrimitive(totalConsumed))
            put("total_residual", JsonPrimitive(totalQty - totalConsumed))
        }
        put("by_location", JsonArray(byLocation))
        put("rows", JsonArray(rows))
    }
    val summary = if (consumedBySupplyId.isNotEmpty()) {
        loc(
            "Supply for $productId — ${rows.size} rows, total qty $totalQty, consumed $totalConsumed",
            "$productId 的供应 — ${rows.size} 行，总量 $totalQty，已消耗 $totalConsumed",
            locale,
        )
    } else {
        loc(
            "Supply for $productId — ${rows.size} rows, total qty ${totalQty}",
            "$productId 的供应 — ${rows.size} 行，总量 ${totalQty}",
            locale,
        )
    }
    return ToolResult(summary = summary, payload = payload)
}

/** Resolve a run's config as a native Map the same way loadPlanResultRowFromDb (Allocate.kt)
 *  resolves the run itself: [planRunId] if given, else the case's latest successful run. Null
 *  when no matching run/config exists. */
private fun resolveRunConfig(caseId: Int, planRunId: Int?): Map<String, Any?>? {
    val configText = transaction {
        val query = if (planRunId != null) {
            PlanRuns.selectAll().where { (PlanRuns.id eq planRunId) and (PlanRuns.caseId eq caseId) }
        } else {
            PlanRuns.selectAll().where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                .orderBy(PlanRuns.id, SortOrder.DESC)
        }
        query.firstOrNull()?.get(PlanRuns.config)
    } ?: return null
    @Suppress("UNCHECKED_CAST")
    return runCatching { jsonElementToNative(jsonParser.parseToJsonElement(configText)) as? Map<String, Any?> }
        .getOrNull()
}

/** Bucket a WO start date into a "day" (the date itself) or "week" (Monday of that ISO
 *  calendar week) label — used by `compare_alternatives`'s optional `group_by`. Deliberately
 *  Monday-aligned rather than reusing PlanningEngine's epoch-day/7 consolidation buckets
 *  (calendarBucket): those are anchored to the Unix epoch (a Thursday) purely for internal
 *  WO-merge grouping and were never meant for display; a calendar week start is what a human
 *  reads as "the week of". */
internal fun periodLabel(date: java.time.LocalDate, scale: String): String =
    if (scale == "week") date.minusDays((date.dayOfWeek.value - 1).toLong()).toString() else date.toString()

/**
 * Compare two or more products side by side — supply on hand per location, purchase WO history
 * over time, and any customer-BOM constraint that pins one of them as the FORCED resolution of a
 * shared BOM slot. Built specifically for the "these are supposed to be equal-split BOM
 * alternatives, why do their purchase quantities differ" class of question: purely comparing
 * purchase history (find_wos twice) explains WHEN they diverge but not WHY; this tool also
 * surfaces the two actual root causes in one call — (a) different starting on-hand inventory
 * (each side draws its own stock first, so equal DEMAND ≠ equal PURCHASE until both are
 * depleted), and (b) a customer constraint that removes some demand from the equal-split pool
 * entirely for one side (see agent-knowledge.md's "Equal-split raw-material sourcing" section).
 * Pure data — the LLM still articulates the mechanism story, this just removes the need to
 * orchestrate 3+ separate tool calls and manually diff their output by hand.
 */
private fun toolCompareAlternatives(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val productIds = (args["product_ids"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
        ?.filter { it.isNotBlank() }
        ?.distinct()
        ?: return toolError("`product_ids` (array of 2+ product ids) is required", locale)
    if (productIds.size < 2) return toolError("`product_ids` needs at least 2 distinct product ids to compare", locale)
    val locationFilter = args["location_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val groupBy = args["group_by"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        ?.takeIf { it == "day" || it == "week" }

    // Supply on hand, per product per location — also rolled up to a per-product total below
    // (opening_stock_total), the "how much was already on hand before any purchase" figure.
    val supplyRows = transaction {
        Supplies.selectAll().where { (Supplies.caseId eq caseId) and (Supplies.productId inList productIds) }.toList()
    }
    val stockByProductLoc = productIds.associateWith { pid ->
        supplyRows
            .filter { it[Supplies.productId] == pid && (locationFilter == null || it[Supplies.locationId] == locationFilter) }
            .groupBy { it[Supplies.locationId] ?: "" }
            .mapValues { (_, lotRows) -> lotRows.sumOf { it[Supplies.qty] } }
    }
    val supplyByProduct = buildJsonObject {
        for (pid in productIds) {
            putJsonObject(pid) {
                stockByProductLoc.getValue(pid).entries.sortedBy { it.key }.forEach { (locId, qty) -> put(locId, qty) }
            }
        }
    }

    // Purchase WO history, per product — same wo_group_id aggregation find_wos uses, so results
    // are directly comparable to a separate find_wos call. When `group_by` is set, further
    // bucket into day/week periods (summed per location) instead of one row per native WO —
    // built for "weekly purchase plan" comparisons where dozens/hundreds of individual purchase
    // lots would otherwise have to be bucketed by hand.
    val wos = loadBaselineWorkOrders(caseId, planRunId) ?: emptyList()
    data class Agg(var location: String, var minStart: String?, var maxEnd: String?, var qty: Double)
    val firstPurchaseDateByProduct = mutableMapOf<String, String?>()
    val purchaseByProduct = buildJsonObject {
        for (pid in productIds) {
            val matches = wos.asSequence()
                .filter { it["product_id"] == pid && it["method"] == "purchase" && it["wo_group_id"] != null }
                .filter { locationFilter == null || it["location_id"] == locationFilter }
            val byGid = LinkedHashMap<String, Agg>()
            for (w in matches) {
                val gid = w["wo_group_id"] as? String ?: continue
                val start = w["start_time"] as? String
                val end = w["end_time"] as? String
                val q = (w["quantity"] as? Number)?.toDouble() ?: 0.0
                val ex = byGid[gid]
                if (ex == null) {
                    byGid[gid] = Agg(w["location_id"] as? String ?: "", start, end, q)
                } else {
                    if (start != null && (ex.minStart == null || start < ex.minStart!!)) ex.minStart = start
                    if (end != null && (ex.maxEnd == null || end > ex.maxEnd!!)) ex.maxEnd = end
                    ex.qty += q
                }
            }
            val sortedAggs = byGid.values.sortedBy { it.minStart ?: "" }
            firstPurchaseDateByProduct[pid] = sortedAggs.firstOrNull { it.minStart != null }?.minStart

            if (groupBy == null) {
                putJsonArray(pid) {
                    sortedAggs.forEach { a ->
                        addJsonObject {
                            put("location_id", a.location)
                            put("start_time", a.minStart)
                            put("end_time", a.maxEnd)
                            put("quantity", a.qty)
                        }
                    }
                }
            } else {
                data class PeriodAgg(var qty: Double = 0.0, var lotCount: Int = 0)
                val byPeriod = LinkedHashMap<Pair<String, String>, PeriodAgg>() // (period, location) -> agg
                for (a in sortedAggs) {
                    val start = a.minStart?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }
                        ?: continue
                    val key = periodLabel(start, groupBy) to a.location
                    val bucket = byPeriod.getOrPut(key) { PeriodAgg() }
                    bucket.qty += a.qty
                    bucket.lotCount += 1
                }
                putJsonArray(pid) {
                    byPeriod.entries.sortedBy { it.key.first }.forEach { (key, agg) ->
                        addJsonObject {
                            put("period", key.first)
                            put("location_id", key.second)
                            put("quantity", agg.qty)
                            put("lot_count", agg.lotCount)
                        }
                    }
                }
            }
        }
    }

    // Customer-BOM constraints pinning ANY of the compared products as the forced child of some
    // (customer, parent, location) slot — that customer's demand never enters equal-split for
    // that slot; its entire requirement routes to the pinned product alone.
    val config = resolveRunConfig(caseId, planRunId)
    val pinningRules = parseConstraints(config).filter { it.child in productIds }
    val constraintsJson = buildJsonArray {
        pinningRules.forEach { rule ->
            add(buildJsonObject {
                put("customer_id", rule.customerId)
                put("parent", rule.parent)
                put("location", rule.location)
                put("pinned_child", rule.child)
            })
        }
    }

    val payload = buildJsonObject {
        put("product_ids", JsonArray(productIds.map { JsonPrimitive(it) }))
        put("supply_on_hand_by_location", supplyByProduct)
        put("opening_stock_total", buildJsonObject {
            for (pid in productIds) put(pid, stockByProductLoc.getValue(pid).values.sum())
        })
        put("first_purchase_date", buildJsonObject {
            for (pid in productIds) firstPurchaseDateByProduct[pid]?.let { put(pid, it) }
        })
        groupBy?.let { put("group_by", it) }
        put("purchase_history", purchaseByProduct)
        put("pinning_constraints", constraintsJson)
    }
    val summary = if (pinningRules.isEmpty()) {
        loc(
            "Compared ${productIds.joinToString(", ")} — supply on hand + purchase history; no pinning constraints found",
            "已对比 ${productIds.joinToString(", ")} — 现有库存与采购历史；未发现锁定约束",
            locale,
        )
    } else {
        loc(
            "Compared ${productIds.joinToString(", ")} — supply on hand + purchase history; " +
                "${pinningRules.size} customer constraint(s) pin one side exclusively (see pinning_constraints)",
            "已对比 ${productIds.joinToString(", ")} — 现有库存与采购历史；发现 ${pinningRules.size} 条客户约束单独锁定其中一方（见 pinning_constraints）",
            locale,
        )
    }
    return ToolResult(summary = summary, payload = payload)
}

/**
 * Reverse BOM lookup: given a leaf/child product id, find every BOM slot (parent_id + alt_group)
 * it fills, and every OTHER product sharing that exact slot (its alternatives). Lets the agent
 * discover "these two products are alternatives of the same slot" starting from just a product
 * id, without the user having to name the parent — the gap get_bom_tree(parent_id) alone doesn't
 * close, since that walks top-down and requires already knowing the parent. Each sibling is
 * flagged `purchasable_raw_material` (no method_make, has method_buy) and each slot carries
 * `equal_split_eligible` (2+ siblings, ALL purchasable raw materials — same scope
 * PlanningEngine.kt's equal-split logic uses, see agent-knowledge.md) as a shortcut so the agent
 * doesn't have to re-derive that eligibility rule by hand.
 */
private fun toolFindBomSiblings(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`product_id` is required", locale)
    if (productId.isBlank()) return toolError("`product_id` cannot be blank", locale)

    val (slotRows, makeProducts, buyProducts) = transaction {
        val ownRows = Boms.selectAll().where { (Boms.caseId eq caseId) and (Boms.childId eq productId) }.toList()
        val parentIds = ownRows.map { it[Boms.parentId] }.distinct()
        val allRows = if (parentIds.isEmpty()) emptyList() else
            Boms.selectAll().where { (Boms.caseId eq caseId) and (Boms.parentId inList parentIds) }.toList()
        val makes = MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }.map { it[MethodMakes.productId] }.toSet()
        val buys = MethodBuys.selectAll().where { MethodBuys.caseId eq caseId }.map { it[MethodBuys.productId] }.toSet()
        Triple(allRows, makes, buys)
    }
    if (slotRows.isEmpty()) {
        return ToolResult(
            summary = loc("$productId is not a BOM child of anything in this case", "$productId 在本案例中不是任何 BOM 的子项", locale),
            payload = buildJsonObject { put("product_id", productId); put("slots", JsonArray(emptyList())) },
        )
    }

    data class SlotKey(val parentId: String, val altGroup: String?)
    val bySlot = slotRows.groupBy { SlotKey(it[Boms.parentId], it[Boms.altGroup]) }
        .filterValues { rows -> rows.any { it[Boms.childId] == productId } }

    val slotsJson = buildJsonArray {
        bySlot.forEach { (slot, rows) ->
            val siblingIds = rows.map { it[Boms.childId] }.distinct()
            add(buildJsonObject {
                put("parent_id", slot.parentId)
                put("alt_group", slot.altGroup)
                put("siblings", buildJsonArray {
                    siblingIds.forEach { sid ->
                        add(buildJsonObject {
                            put("product_id", sid)
                            put("is_self", sid == productId)
                            put("makeable", sid in makeProducts)
                            put("buyable", sid in buyProducts)
                            put("purchasable_raw_material", sid in buyProducts && sid !in makeProducts)
                        })
                    }
                })
                put("equal_split_eligible", siblingIds.size > 1 && siblingIds.all { it in buyProducts && it !in makeProducts })
            })
        }
    }
    val totalSiblings = slotRows.map { it[Boms.childId] }.distinct().size - 1
    return ToolResult(
        summary = loc(
            "$productId fills ${bySlot.size} BOM slot(s), $totalSiblings distinct sibling(s) total",
            "$productId 属于 ${bySlot.size} 个 BOM 槽位，共有 $totalSiblings 个不同的兄弟节点",
            locale,
        ),
        payload = buildJsonObject {
            put("product_id", productId)
            put("slots", slotsJson)
        },
    )
}

/** Whether [productId] is referenced ANYWHERE in this case's data — not just the `Products`
 *  master-data CSV (PRODUCT.csv), which can be incomplete relative to the transactional CSVs.
 *  A product with real Supplies/BOM/method rows but missing from PRODUCT.csv is a real, in-case
 *  product; treating it as "not found" produces a confidently wrong error. Confirmed live:
 *  285-0612/285-0647 have real Supplies + method_buy rows but aren't in Products, so
 *  [toolGetLeafCompetition]/[toolGetComponentAllocationByDemand]'s hyphen-split defensive check
 *  rejected them as unknown even though [toolGetProductSupply] — which queries Supplies
 *  directly, no Products gate — succeeded for the exact same ids. Used by both tools' checks so
 *  a fix here covers both. */
private fun productReferencedInCase(caseId: Int, productId: String): Boolean = transaction {
    Products.selectAll().where { (Products.caseId eq caseId) and (Products.productId eq productId) }.limit(1).count() > 0L ||
        Supplies.selectAll().where { (Supplies.caseId eq caseId) and (Supplies.productId eq productId) }.limit(1).count() > 0L ||
        Demands.selectAll().where { (Demands.caseId eq caseId) and (Demands.productId eq productId) }.limit(1).count() > 0L ||
        MethodBuys.selectAll().where { (MethodBuys.caseId eq caseId) and (MethodBuys.productId eq productId) }.limit(1).count() > 0L ||
        MethodMakes.selectAll().where { (MethodMakes.caseId eq caseId) and (MethodMakes.productId eq productId) }.limit(1).count() > 0L ||
        MethodMoves.selectAll().where { (MethodMoves.caseId eq caseId) and (MethodMoves.productId eq productId) }.limit(1).count() > 0L ||
        Boms.selectAll().where { (Boms.caseId eq caseId) and ((Boms.parentId eq productId) or (Boms.childId eq productId)) }.limit(1).count() > 0L ||
        ProductLocations.selectAll().where { (ProductLocations.caseId eq caseId) and (ProductLocations.productId eq productId) }.limit(1).count() > 0L
}

private fun toolGetLeafCompetition(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`product_id` is required", locale)
    val locationId = args["location_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`location_id` is required", locale)
    if (productId.isBlank() || locationId.isBlank()) {
        return toolError("`product_id` and `location_id` cannot be blank", locale)
    }

    val result = loadPlanResultWithPeggingFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId", locale)

    // Defensive: catch the common LLM input-parsing error where a hyphenated
    // product code (e.g. "502-2991") was split as product="502", location="2991".
    // If `productId` isn't a real product in this case AND `${productId}-${locationId}`
    // IS a real product, return an early error pointing at the corrected identifier
    // so the agent retries with the right args instead of producing a confidently
    // wrong "no usage" answer.
    val productExists = productReferencedInCase(caseId, productId)
    if (!productExists) {
        val joined = "$productId-$locationId"
        val joinedExists = productReferencedInCase(caseId, joined)
        if (joinedExists) {
            return toolError(
                "product_id `$productId` not found in case $caseId, but `$joined` IS a known product. " +
                    "Did you split a hyphenated product code? Re-call with product_id=`$joined` and " +
                    "the actual location_id (product codes are opaque strings and may contain hyphens).",
                locale,
            )
        }
        // Real unknown — return a clear error so the agent doesn't hallucinate.
        return toolError(
            "product_id `$productId` not found in case $caseId. Verify the product code; " +
                "case product_ids are opaque strings (often hyphenated, e.g. `502-2991`).",
            locale,
        )
    }

    // Total initial supply at the leaf (case-scoped).
    val totalSupply = transaction {
        Supplies.selectAll()
            .where {
                (Supplies.caseId eq caseId) and (Supplies.productId eq productId) and (Supplies.locationId eq locationId)
            }
            .sumOf { it[Supplies.qty] }
    }

    @Suppress("UNCHECKED_CAST")
    val planningPegging = (result["planning_pegging"] as? List<Map<String, Any?>>) ?: emptyList()

    // Empty-leaf early return: when this (pid, lid) has no supply at all, the
    // queried location isn't a real consolidation leaf for this product.
    // Returning a full members[] classification in that case produces an
    // all-walk_skipped table that mostly confuses the agent ("did consolidation
    // strip everyone?"). Instead, surface:
    //   1. The product's actual supply locations (so caller can re-query a real leaf).
    //   2. The demands whose pegging walks visit this product (made/bought via
    //      work orders, not drawn from inventory) — answers "which demands
    //      USE this product?" without the caller needing a different tool.
    if (totalSupply <= 1e-9) {
        val otherLocs: List<String> = transaction {
            Supplies.selectAll()
                .where { (Supplies.caseId eq caseId) and (Supplies.productId eq productId) }
                .mapNotNull { it[Supplies.locationId] }
                .distinct()
        }
        // Walk pegging trees to find demands whose walks include this product
        // at any location (work_order or demand nodes). Distinguishes "no
        // supply" (the supply-side answer) from "no usage" (the demand-side
        // answer) — those are very different.
        val usageByDemand = mutableMapOf<String, Set<String>>()
        for (entry in planningPegging) {
            val demandId = entry["demand_id"]?.toString() ?: continue
            @Suppress("UNCHECKED_CAST")
            val tree = entry["tree"] as? Map<String, Any?> ?: continue
            val locs = mutableSetOf<String>()
            peggingProductLocations(tree, productId, locs)
            if (locs.isNotEmpty()) usageByDemand[demandId] = locs
        }
        val hint = when {
            otherLocs.isEmpty() && usageByDemand.isEmpty() ->
                "no supply rows for $productId, and no demand pegging walk visits it — this product is unused in run #$runId"
            otherLocs.isEmpty() ->
                "no supply rows for $productId — this product is made/bought via work orders (not inventoried). " +
                    "${usageByDemand.size} demand(s) use it; see `usages` for the list"
            usageByDemand.isEmpty() ->
                "no supply at $productId@$locationId; supply exists at: ${otherLocs.joinToString(", ")} — " +
                    "re-query with one of those location_ids. No demand walk visits this product in run #$runId."
            else ->
                "no supply at $productId@$locationId; supply exists at: ${otherLocs.joinToString(", ")}. " +
                    "${usageByDemand.size} demand(s) use this product (see `usages`); for supply-side competition, re-query at a real supply location."
        }
        val usagesJson = JsonArray(
            usageByDemand.entries
                .sortedBy { it.key }
                .map { (did, locs) ->
                    buildJsonObject {
                        put("demand_id", JsonPrimitive(did))
                        put("locations", JsonArray(locs.sorted().map { JsonPrimitive(it) }))
                    }
                }
        )
        val payload = buildJsonObject {
            put("product_id", JsonPrimitive(productId))
            put("location_id", JsonPrimitive(locationId))
            put("total_initial_supply", JsonPrimitive(0.0))
            put("competitor_count", JsonPrimitive(0))
            put("competitors", JsonArray(emptyList()))
            put("note", JsonPrimitive(hint))
            put("supply_locations_for_product", JsonArray(otherLocs.map { JsonPrimitive(it) }))
            put("usages", usagesJson)
            put("usage_demand_count", JsonPrimitive(usageByDemand.size))
        }
        val summaryEn = "No supply at $productId @ $locationId — " +
            (if (otherLocs.isNotEmpty()) "supply exists at ${otherLocs.joinToString(",")}; " else "") +
            "${usageByDemand.size} demand(s) use this product"
        val summaryZh = "$productId @ $locationId 无供应 — " +
            (if (otherLocs.isNotEmpty()) "供应位于 ${otherLocs.joinToString(",")}；" else "") +
            "${usageByDemand.size} 个需求使用该产品"
        return ToolResult(
            summary = loc(summaryEn, summaryZh, locale),
            payload = payload,
        )
    }

    // Actual draws: walk planning_pegging supply leaves and aggregate qty by demand_id.
    // Each pegging entry is keyed by demand_id; supply leaves under it carry the qty
    // that demand actually pulled from physical inventory at this (pid, lid).
    // (Note: iter-0 consolidation fair-shares were considered for persistence but
    // dropped — they bloated plan_run.result by hundreds of KB per demand and
    // OOM'd the listing endpoint. Actual draws cover the demand-side competition
    // story well enough on their own.)
    val actualByDemand = mutableMapOf<String, Double>()
    // Cross-reference the pegging-tree origin flags: which demands (if any)
    // carry `is_bottleneck=true` or `is_root_bottleneck=true` at THIS exact
    // (pid, lid)? A leaf that isn't an origin for any demand is NOT a bottleneck
    // — even if multiple demands draw from it. Surfacing this prevents the agent
    // from fabricating bottleneck narratives around healthy leaves with slack.
    val supplyOriginDemands = mutableListOf<String>()
    val demandOriginDemands = mutableListOf<String>()
    for (entry in planningPegging) {
        val demandId = entry["demand_id"]?.toString() ?: continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        accumulateLeafDraws(tree, productId, locationId, demandId, actualByDemand)
        val (supplyFlag, demandFlag) = peggingOriginFlagsAt(tree, productId, locationId)
        if (supplyFlag) supplyOriginDemands.add(demandId)
        if (demandFlag) demandOriginDemands.add(demandId)
    }
    val isOrigin = supplyOriginDemands.isNotEmpty() || demandOriginDemands.isNotEmpty()

    val rows = actualByDemand.entries
        .filter { it.value > 1e-9 }
        .sortedByDescending { it.value }
        .map { (did, qty) -> did to qty }

    val totalDrawn = rows.sumOf { it.second }
    val headroom = totalSupply - totalDrawn

    val competitorsJson = JsonArray(rows.map { (did, qty) ->
        buildJsonObject {
            put("demand_id", JsonPrimitive(did))
            put("leaf_draw_qty", JsonPrimitive(qty))
            put("share_pct", JsonPrimitive(if (totalSupply > 1e-9) qty * 100.0 / totalSupply else 0.0))
        }
    })

    // ── Members + reason classification (consolidation override use-case) ──
    //
    // The above `competitors` array lists demands that drew > 0 from this
    // leaf. For the "re-assign shares to a system-eliminated demand" workflow,
    // the user also needs visibility into ZERO-SHARE candidates — demands that
    // could have competed at this leaf but were eliminated by consolidation
    // policy (priority_filtered, share_starved_under_shortage, outside_bucket,
    // override_blocked) or never actually walked through this leaf
    // (walk_skipped — false positive of BOM containment).
    //
    // Approach: BOM-containment closure (cheap recompute) intersected with
    // each demand's planner-walk reach, classified against the run's
    // consolidation policy + priority data + Targeted Supply Allocation budget
    // (manual_override was the old mechanism for this — retired, DB table
    // dropped). No persistence required (plan_run.result stays compact).

    // Build reverse-BOM closure: products whose recipe transitively contains
    // the leaf's product. A demand at FG product F is a "candidate" iff
    // F is in this closure (or F == leaf.pid for direct demands).
    val candidateProducts: Set<String> = transaction {
        val rows = Boms.selectAll().where { Boms.caseId eq caseId }.toList()
        val parentsByChild = rows.groupBy({ it[Boms.childId] }, { it[Boms.parentId] })
            .mapValues { it.value.toSet() }
        val out = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(productId to 0)
        val visited = mutableSetOf(productId)
        while (queue.isNotEmpty()) {
            val (cur, depth) = queue.removeFirst()
            if (depth >= 12) continue   // generous cap; deeper BOMs are unusual
            for (p in parentsByChild[cur] ?: emptySet()) {
                if (visited.add(p)) { out.add(p); queue.add(p to depth + 1) }
            }
        }
        out + productId
    }

    // Pull demand metadata for the candidate set.
    data class DemandRow(
        val demandId: String,
        val productId: String,
        val locationId: String?,
        val priority: Int?,
        val requestDueTime: String?,
        val quantity: Double,
    )
    val candidates: List<DemandRow> = transaction {
        Demands.selectAll()
            .where { (Demands.caseId eq caseId) and (Demands.productId inList candidateProducts) }
            .map {
                DemandRow(
                    demandId = it[Demands.demandId],
                    productId = it[Demands.productId],
                    locationId = it[Demands.locationId],
                    priority = it[Demands.priority],
                    requestDueTime = it[Demands.requestDueTime],
                    quantity = it[Demands.quantity],
                )
            }
    }

    // Track which candidate demands' planner walks actually reached this exact
    // (pid, lid) — touching a work_order or demand node. Distinguishes
    // "eliminated at consolidation" from "different BOM branch / skipped".
    // Also collect the full set of locations each demand visits this product
    // at, so a "walk_skipped" verdict can distinguish "walked at a different
    // location" from "walk doesn't touch this product at all".
    val reachedThisLeaf = mutableSetOf<String>()
    val productLocsByDemand = mutableMapOf<String, Set<String>>()
    for (entry in planningPegging) {
        val demandId = entry["demand_id"]?.toString() ?: continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        if (peggingReachesLeaf(tree, productId, locationId)) reachedThisLeaf.add(demandId)
        val locs = mutableSetOf<String>()
        peggingProductLocations(tree, productId, locs)
        if (locs.isNotEmpty()) productLocsByDemand[demandId] = locs
    }

    // Run config: consolidation policy + period.
    val runConfigJson: JsonObject? = transaction {
        PlanRuns.selectAll().where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }
            .firstOrNull()?.get(PlanRuns.config)
    }?.let { raw -> runCatching { jsonParser.parseToJsonElement(raw).jsonObject }.getOrNull() }
    val consolidationConfig = (runConfigJson?.get("consolidation") as? JsonObject) ?: JsonObject(emptyMap())
    val allocationMode = consolidationConfig["allocation_mode"]?.jsonPrimitive?.contentOrNull ?: "fair"
    val periodDays = consolidationConfig["period_days"]?.jsonPrimitive?.intOrNull ?: 30

    // Classify each candidate: drawer (full / partial), zero-share (with
    // presumed reason), or walk_skipped (false positive of BOM containment).
    val drawerPriorities = candidates
        .filter { it.demandId in actualByDemand && (actualByDemand[it.demandId] ?: 0.0) > 1e-9 }
        .mapNotNull { it.priority }
    val drawerMinPriority = drawerPriorities.minOrNull()
    val drawerTotalRequested = candidates
        .filter { (actualByDemand[it.demandId] ?: 0.0) > 1e-9 }
        .sumOf { it.quantity }
    val isUnderShortage = drawerTotalRequested > totalSupply + 1e-6

    // Approximate the consolidation bucket window from drawers' request_due_time
    // range. A candidate whose due_time falls outside [min−1d, max+period_days]
    // is presumed `outside_bucket`. Imprecise but useful as a heuristic.
    val drawerDueTimes = candidates
        .filter { (actualByDemand[it.demandId] ?: 0.0) > 1e-9 }
        .mapNotNull { it.requestDueTime }
        .sorted()

    fun classify(d: DemandRow): Pair<String, String> {
        val actual = actualByDemand[d.demandId] ?: 0.0
        if (actual >= d.quantity - 1e-6) return "drew_full" to "fulfilled in full"
        if (actual > 1e-9) return "drew_partial" to "drew $actual of ${d.quantity}"
        // Zero-share path. First check if walk reached this leaf at all.
        if (d.demandId !in reachedThisLeaf) {
            val productLocs = productLocsByDemand[d.demandId].orEmpty()
            return if (productLocs.isNotEmpty()) {
                // Demand's walk visits this product, but at other location(s).
                // Not an elimination at this leaf — caller likely queried the
                // wrong location for this demand.
                "walk_at_other_location" to
                    "demand walks $productId at ${productLocs.joinToString(",")}, not at $locationId — no elimination happened at this leaf for this demand"
            } else {
                // Demand never visits this product — different recipe
                // alternative chosen upstream (e.g. method_make picked a sibling
                // BOM branch that skips $productId entirely).
                "walk_avoids_product" to
                    "demand's walk doesn't visit $productId at any location — likely a different recipe alternative was chosen upstream"
            }
        }
        // Walk reached, drew zero. Classify by policy + priority + shortage.
        if (allocationMode == "priority_first" && drawerMinPriority != null && d.priority != null && d.priority > drawerMinPriority) {
            return "priority_filtered" to "policy=priority_first; this demand's priority ${d.priority} > top drawers' priority $drawerMinPriority"
        }
        if (drawerDueTimes.isNotEmpty() && d.requestDueTime != null) {
            val minDue = drawerDueTimes.first()
            val maxDue = drawerDueTimes.last()
            // String comparison works for ISO-format dates. Period_days widens the upper bound conceptually.
            if (d.requestDueTime < minDue || d.requestDueTime > maxDue) {
                return "outside_bucket" to "request_due_time ${d.requestDueTime} falls outside drawers' window [$minDue..$maxDue]; period_days=$periodDays may be too tight"
            }
        }
        if (isUnderShortage) {
            return "share_starved_under_shortage" to "policy=$allocationMode under shortage (drawers' total req ${drawerTotalRequested.toLong()} > supply ${totalSupply.toLong()})"
        }
        return "zero_share" to "presumed eliminated by consolidation but specific reason not derivable from policy=$allocationMode + priority + shortage signals; inspect Targeted Supply Allocation (get_critical_raw_allocation) or upstream walks"
    }

    val membersJson = JsonArray(candidates.map { d ->
        val (status, reason) = classify(d)
        // Null leaf_draw_qty when the demand didn't actually compete at this
        // leaf — `walk_at_other_location` (consumes the product elsewhere) and
        // `walk_avoids_product` (different recipe). A literal 0.0 here misleads
        // the agent into reporting "0 allocation" when the demand may consume
        // this material at another (pid, lid) row in its pegging tree. For
        // those statuses, force the agent to read share_status / presumed_reason.
        val leafDrawQty: JsonElement = if (status == "walk_at_other_location" || status == "walk_avoids_product") {
            JsonNull
        } else {
            JsonPrimitive(actualByDemand[d.demandId] ?: 0.0)
        }
        buildJsonObject {
            put("demand_id", JsonPrimitive(d.demandId))
            put("product_id", JsonPrimitive(d.productId))
            put("location_id", JsonPrimitive(d.locationId))
            put("priority", d.priority?.let { JsonPrimitive(it) } ?: JsonNull)
            put("request_due_time", JsonPrimitive(d.requestDueTime))
            put("requested_qty", JsonPrimitive(d.quantity))
            put("leaf_draw_qty", leafDrawQty)
            put("share_status", JsonPrimitive(status))
            put("presumed_reason", JsonPrimitive(reason))
        }
    })

    val zeroShareCount = candidates.count {
        val a = actualByDemand[it.demandId] ?: 0.0
        a <= 1e-9 && it.demandId in reachedThisLeaf
    }

    // Build a guidance note when this leaf is NOT a shortage origin. Without
    // this, the LLM tends to fabricate a bottleneck story whenever it sees
    // "demand X drew C, demand Y drew K" — even if total_supply >> sum(draws).
    // We make the non-origin verdict structurally hard to ignore: explicit
    // boolean + plain-language note + headroom number.
    val originNote: String? = when {
        !isOrigin && headroom > 1e-6 ->
            "This leaf is NOT a shortage origin for any demand in run #$runId. " +
                "Total supply ${totalSupply.toLong()} > total drawn ${totalDrawn.toLong()} " +
                "(headroom ${headroom.toLong()}). Do NOT narrate a bottleneck/competition " +
                "story for this leaf — the small leaf_draw_qty for any demand reflects " +
                "small NEED, not exhausted supply. Look elsewhere in the pegging tree for " +
                "nodes flagged is_bottleneck=true or is_root_bottleneck=true."
        !isOrigin ->
            "This leaf is NOT flagged as a shortage origin in any demand's pegging tree. " +
                "Don't infer a bottleneck from the competitor list alone — confirm against " +
                "is_bottleneck / is_root_bottleneck flags from get_demand_pegging."
        else -> null
    }

    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        put("location_id", JsonPrimitive(locationId))
        put("total_initial_supply", JsonPrimitive(totalSupply))
        put("total_drawn", JsonPrimitive(totalDrawn))
        put("headroom", JsonPrimitive(headroom))
        put("is_origin", JsonPrimitive(isOrigin))
        put("supply_origin_for_demands", JsonArray(supplyOriginDemands.distinct().map { JsonPrimitive(it) }))
        put("demand_origin_for_demands", JsonArray(demandOriginDemands.distinct().map { JsonPrimitive(it) }))
        if (originNote != null) put("origin_note", JsonPrimitive(originNote))
        put("competitor_count", JsonPrimitive(rows.size))
        put("competitors", competitorsJson)
        put("member_count", JsonPrimitive(candidates.size))
        put("zero_share_count", JsonPrimitive(zeroShareCount))
        put("members", membersJson)
        put("consolidation", buildJsonObject {
            put("allocation_mode", JsonPrimitive(allocationMode))
            put("period_days", JsonPrimitive(periodDays))
        })
        put("override_levers", buildJsonArray {
            // Surface the override paths the agent can recommend. Caller
            // (LLM) picks the appropriate one based on share_status.
            add(JsonPrimitive("adjust Targeted Supply Allocation — re-assign lot shares at the supply level (its own page, not from chat; see get_critical_raw_allocation to inspect the current budget)"))
            add(JsonPrimitive("change consolidation.allocation_mode (fair / proportional / priority_first)"))
            add(JsonPrimitive("change consolidation.period_days — widen / narrow the merge bucket"))
            add(JsonPrimitive("change demand.priority — affects priority_first ordering"))
        })
    }
    val originTag = if (isOrigin) {
        val kinds = buildList {
            if (supplyOriginDemands.isNotEmpty()) add("supply")
            if (demandOriginDemands.isNotEmpty()) add("demand")
        }.joinToString("+")
        "origin($kinds)"
    } else "NOT an origin"
    return ToolResult(
        summary = loc(
            "Competition at $productId @ $locationId — $originTag, ${rows.size} drawer(s), " +
                "$zeroShareCount zero-share, supply ${totalSupply.toLong()}, drawn ${totalDrawn.toLong()}, " +
                "headroom ${headroom.toLong()}",
            "$productId @ $locationId — $originTag, ${rows.size} 抽取者, $zeroShareCount 零份额, " +
                "供应 ${totalSupply.toLong()}, 已取 ${totalDrawn.toLong()}, 余量 ${headroom.toLong()}",
            locale,
        ),
        payload = payload,
    )
}

/** True iff the pegging tree contains a work_order or demand node at the given
 *  (pid, lid). Used to detect whether a demand's planner walk actually reached
 *  this leaf — distinguishes consolidation "eliminated" from BOM-branch
 *  "skipped". Skips `failed=true` subtrees (rolled-back diagnostic snapshots). */
@Suppress("UNCHECKED_CAST")
private fun peggingReachesLeaf(node: Map<String, Any?>, targetPid: String, targetLid: String): Boolean {
    if (node["failed"] == true) return false
    val pid = (node["product_id"] as? String)?.trim()
    val lid = (node["location_id"] as? String)?.trim()
    val type = node["type"] as? String
    if ((type == "demand" || type == "work_order") && pid == targetPid && lid == targetLid) {
        return true
    }
    val children = node["children"] as? List<Map<String, Any?>> ?: return false
    return children.any { peggingReachesLeaf(it, targetPid, targetLid) }
}

/** Walk a demand's pegging tree and check whether (targetPid, targetLid)
 *  appears as a node carrying `is_bottleneck=true` and/or
 *  `is_root_bottleneck=true`. Returns a Pair<supplyOrigin, demandOrigin>; both
 *  default to false if the leaf isn't flagged. Used by [toolGetLeafCompetition]
 *  to refuse to look like a bottleneck story when the queried leaf isn't
 *  actually a shortage origin for any demand. */
private fun peggingOriginFlagsAt(
    node: Map<String, Any?>,
    targetPid: String,
    targetLid: String,
): Pair<Boolean, Boolean> {
    val pid = (node["product_id"] as? String)?.trim()
    val lid = (node["location_id"] as? String)?.trim()
    var supply = false
    var demand = false
    if (pid == targetPid && lid == targetLid) {
        if (node["is_bottleneck"] == true) supply = true
        if (node["is_root_bottleneck"] == true) demand = true
    }
    @Suppress("UNCHECKED_CAST")
    val children = node["children"] as? List<Map<String, Any?>>
    if (children != null) {
        for (c in children) {
            val (s, d) = peggingOriginFlagsAt(c, targetPid, targetLid)
            if (s) supply = true
            if (d) demand = true
            if (supply && demand) break
        }
    }
    return supply to demand
}

/** Collect all locations where a demand's pegging walk visits `targetPid`
 *  (work_order or demand nodes). Used to refine `walk_skipped` into either
 *  "walked at a different location" or "walk doesn't touch this product at all". */
private fun peggingProductLocations(node: Map<String, Any?>, targetPid: String, out: MutableSet<String>) {
    if (node["failed"] == true) return
    val pid = (node["product_id"] as? String)?.trim()
    val lid = (node["location_id"] as? String)?.trim()
    val type = node["type"] as? String
    if ((type == "demand" || type == "work_order") && pid == targetPid && !lid.isNullOrBlank()) {
        out.add(lid)
    }
    @Suppress("UNCHECKED_CAST")
    val children = node["children"] as? List<Map<String, Any?>> ?: return
    for (c in children) peggingProductLocations(c, targetPid, out)
}

/** True iff `targetPid` appears anywhere in this pegging tree on a real
 *  node (work_order / supply / purchase / demand). Skips failed=true subtrees
 *  (rolled back at the planner level) and synthetic `consolidated_*` supply
 *  ids (intermediate accounting, not real consumption). Used by
 *  `find_demands_for_product` to catch both make-chain and leaf-component
 *  cases — previously only WO production was checked, which missed
 *  inventory/buy leaf consumption. */
private fun peggingTreeContainsProduct(node: Map<String, Any?>, targetPid: String): Boolean {
    if (node["failed"] == true) return false
    val type = node["type"] as? String
    val supplyId = node["supply_id"] as? String
    val isSyntheticBucket = supplyId != null && supplyId.startsWith("consolidated_")
    if (!isSyntheticBucket) {
        val pid = (node["product_id"] as? String)?.trim()
        if (pid == targetPid && type != null) return true
    }
    @Suppress("UNCHECKED_CAST")
    val children = node["children"] as? List<Map<String, Any?>> ?: return false
    for (c in children) if (peggingTreeContainsProduct(c, targetPid)) return true
    return false
}

/**
 * Walk a pegging tree and accumulate qty pulled from supply leaves at the
 * given (pid, lid). Skips `failed=true` subtrees (rolled back at the planner
 * level — those qtys never landed in real consumption). Skips synthetic
 * consolidated supply ids (`consolidated_*`) — those are intermediate
 * accounting, not real inventory draws.
 */
@Suppress("UNCHECKED_CAST")
private fun accumulateLeafDraws(
    node: Map<String, Any?>,
    targetPid: String,
    targetLid: String,
    demandId: String,
    accumulator: MutableMap<String, Double>,
) {
    if (node["failed"] == true) return
    val type = node["type"] as? String
    if (type == "supply" || type == "purchase") {
        val pid = (node["product_id"] as? String)?.trim()
        val lid = (node["location_id"] as? String)?.trim()
        val supplyId = node["supply_id"]?.toString()
        val isConsolidated = supplyId?.startsWith("consolidated_") == true
        if (!isConsolidated && pid == targetPid && lid == targetLid) {
            val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 1e-9) {
                accumulator.merge(demandId, qty, Double::plus)
            }
        }
    }
    val children = node["children"] as? List<Map<String, Any?>> ?: return
    for (c in children) accumulateLeafDraws(c, targetPid, targetLid, demandId, accumulator)
}

/**
 * Per-demand allocation of a (product, location) leaf in a plan run. Answers
 * the user's question shape "how much P@L did each demand get?" with an
 * unambiguous one-row-per-demand table.
 *
 * Distinct from [toolGetLeafCompetition] in framing: that tool centers on the
 * leaf (competition + zero-share members for a 根因 story), this one centers
 * on the per-demand table (clean rows, one per demand, with an explicit
 * `status` per row that disambiguates the four "consumed=0" cases). No
 * `competitors`/`members` split — a single `by_demand` array sorted by
 * consumed_qty desc.
 *
 * Statuses (so the agent never reads a 0 as overall elimination):
 *   • drew_at_leaf            consumed > 0 from this leaf's supply rows
 *   • walks_leaf_drew_zero    walk reaches (pid, lid) but consumed 0 here
 *                             (consolidation share / priority / override)
 *   • walks_other_location    walk visits product at OTHER location(s) — the
 *                             demand consumed P, just not at THIS lid
 *   • doesnt_walk_product     walk doesn't visit pid anywhere — different
 *                             recipe or branch chosen upstream; demand may
 *                             still be fulfilled via a different material
 *   • no_pegging_entry        only when caller passed `demand_ids` and a
 *                             listed demand has no pegging entry in this run
 */
private fun toolGetComponentAllocationByDemand(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`product_id` is required", locale)
    val locationId = args["location_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`location_id` is required", locale)
    if (productId.isBlank() || locationId.isBlank()) {
        return toolError("`product_id` and `location_id` cannot be blank", locale)
    }
    val demandIdFilter: Set<String> = args["demand_ids"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
        ?.map { resolveDemandId(caseId, it) ?: it }
        ?.toSet().orEmpty()

    // Defensive: catch the hyphen-split LLM error (e.g. product=`502-2991` mis-passed
    // as product=`502`, location=`2991`). Mirrors [toolGetLeafCompetition].
    val productExists = productReferencedInCase(caseId, productId)
    if (!productExists) {
        val joined = "$productId-$locationId"
        val joinedExists = productReferencedInCase(caseId, joined)
        if (joinedExists) {
            return toolError(
                "product_id `$productId` not found in case $caseId, but `$joined` IS a known product. " +
                    "Did you split a hyphenated product code? Re-call with product_id=`$joined` and " +
                    "the actual location_id (product codes are opaque strings and may contain hyphens).",
                locale,
            )
        }
        return toolError(
            "product_id `$productId` not found in case $caseId. Verify the product code; " +
                "case product_ids are opaque strings (often hyphenated, e.g. `502-2991`).",
            locale,
        )
    }

    val result = loadPlanResultWithPeggingFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId", locale)

    @Suppress("UNCHECKED_CAST")
    val planningPegging = (result["planning_pegging"] as? List<Map<String, Any?>>) ?: emptyList()

    // Iterate every pegging entry (mirrors [toolGetLeafCompetition]). A demand
    // can have multiple entries (root tree + per-WO sub-trees); accumulating
    // across all of them is consistent with leaf_competition's draw counting.
    val consumedByDemand = mutableMapOf<String, Double>()
    val visitedByDemand = mutableMapOf<String, MutableSet<String>>()
    val seenDemands = mutableSetOf<String>()
    for (entry in planningPegging) {
        val did = entry["demand_id"]?.toString()?.trim() ?: continue
        if (demandIdFilter.isNotEmpty() && did !in demandIdFilter) continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        seenDemands.add(did)
        accumulateLeafDraws(tree, productId, locationId, did, consumedByDemand)
        peggingProductLocations(tree, productId, visitedByDemand.getOrPut(did) { mutableSetOf() })
    }

    // Resolve requested_qty per demand from the Demands table. When demand_ids
    // is given, restrict the query; otherwise fetch only for demands we saw.
    val demandsToLookUp = if (demandIdFilter.isNotEmpty()) demandIdFilter else seenDemands
    val requestedByDemand: Map<String, Double> = if (demandsToLookUp.isEmpty()) emptyMap() else transaction {
        Demands.selectAll()
            .where { (Demands.caseId eq caseId) and (Demands.demandId inList demandsToLookUp.toList()) }
            .associate { it[Demands.demandId] to it[Demands.quantity] }
    }

    // Effective demand set: with a filter, include even demands not in pegging
    // (status will be `no_pegging_entry`). Without a filter, only demands the
    // planner processed.
    val effectiveDemands: List<String> = (if (demandIdFilter.isNotEmpty()) demandIdFilter else seenDemands)
        .sortedByDescending { consumedByDemand[it] ?: 0.0 }

    val totalConsumed = effectiveDemands.sumOf { consumedByDemand[it] ?: 0.0 }
    val drewCount = effectiveDemands.count { (consumedByDemand[it] ?: 0.0) > 1e-9 }

    fun classify(did: String): Pair<String, String> {
        val consumed = consumedByDemand[did] ?: 0.0
        if (consumed > 1e-9) {
            return "drew_at_leaf" to "consumed $consumed of $productId@$locationId at this leaf"
        }
        if (did !in seenDemands) {
            return "no_pegging_entry" to "demand has no pegging entry in run #$runId (planner did not process it — verify demand_id and run)"
        }
        val locs = visitedByDemand[did].orEmpty()
        if (locationId in locs) {
            return "walks_leaf_drew_zero" to
                "walk reaches $productId@$locationId but drew 0 — share filtered by consolidation/priority/override; check get_leaf_competition.members for the precise cause"
        }
        if (locs.isNotEmpty()) {
            return "walks_other_location" to
                "demand walks $productId at ${locs.sorted().joinToString(",")} (not at $locationId) — consumed elsewhere; per-demand total of $productId requires get_demand_pegging on this demand"
        }
        return "doesnt_walk_product" to
            "demand's walk does not visit $productId at any location — different recipe alternative was chosen upstream"
    }

    val rowsJson = JsonArray(effectiveDemands.map { did ->
        val consumed = consumedByDemand[did] ?: 0.0
        val (status, reason) = classify(did)
        val visited = visitedByDemand[did].orEmpty()
        buildJsonObject {
            put("demand_id", JsonPrimitive(did))
            put("requested_qty", JsonPrimitive(requestedByDemand[did] ?: 0.0))
            put("consumed_qty", JsonPrimitive(consumed))
            put("share_of_total_consumed_pct", JsonPrimitive(
                if (totalConsumed > 1e-9) consumed * 100.0 / totalConsumed else 0.0
            ))
            put("status", JsonPrimitive(status))
            put("reason", JsonPrimitive(reason))
            if (visited.isNotEmpty()) {
                put("walks_locations", JsonArray(visited.sorted().map { JsonPrimitive(it) }))
            }
        }
    })

    val payload = buildJsonObject {
        put("run_id", JsonPrimitive(runId))
        put("product_id", JsonPrimitive(productId))
        put("location_id", JsonPrimitive(locationId))
        put("total_consumed_at_leaf", JsonPrimitive(totalConsumed))
        put("demand_count", JsonPrimitive(effectiveDemands.size))
        put("drew_count", JsonPrimitive(drewCount))
        put("by_demand", rowsJson)
        put("note", JsonPrimitive(
            "consumed_qty is the demand's draw at THIS (pid, lid) leaf only. For demands " +
                "with status=walks_other_location the demand consumed $productId at a different " +
                "location; for status=doesnt_walk_product the demand uses a different material " +
                "branch entirely. For per-demand totals of $productId across the whole pegging " +
                "tree, call get_demand_pegging."
        ))
    }

    val summaryEn = "$productId@$locationId across ${effectiveDemands.size} demand(s) " +
        "in run #$runId — $drewCount drew (total ${totalConsumed.toLong()})"
    val summaryZh = "$productId@$locationId 在 ${effectiveDemands.size} 个需求中（运行 #$runId）" +
        " — $drewCount 个抽取（共 ${totalConsumed.toLong()}）"
    return ToolResult(
        summary = loc(summaryEn, summaryZh, locale),
        payload = payload,
    )
}

/** Targeted Supply Allocation — the RECOMPUTED (never stored) (supply_lot, demand) ->
 *  qty_allocated budget, one of the case's 5 versioned external config objects. `case_allocation`
 *  (`CaseAllocations`) now stores TSA INPUT rows (qty_cap/target per raw critical-material lot);
 *  this is derived on demand by running the same buildSupplyAllocation pass a real plan run makes
 *  — see `computeAllocationPreview` in Allocation.kt. Resolves the run's OWN
 *  `case_alloc_version_id` (each run pins the version it actually used — NOT necessarily the
 *  case's current default) rather than trusting workingConfig, mirroring why the copilot/agent
 *  config-mutation code resolves versions server-side instead of trusting caller-submitted ids
 *  (see resolveEffectiveConfig's own doc in Allocate.kt). Distinct from
 *  get_component_allocation_by_demand, which reads the plan's OUTPUT consumption — this reads the
 *  INPUT budget the planner was given, if the case has one. */

/** Loads plan run [runId]'s own resolved Targeted Supply Allocation version + persisted config
 *  (the point-in-time state that run actually used — see `resolveEffectiveConfig`'s own doc), and
 *  recomputes the (supply_lot, demand) -> qty_allocated preview grid for it via
 *  [computeAllocationPreview] — the same on-demand recomputation the Allocation page's read-only
 *  matrix view uses, since `case_allocation` no longer stores this grid directly (it stores TSA
 *  INPUT rows — qty_cap/target per lot — see `CaseAllocations`' own doc). Returns null if the run
 *  doesn't exist for this case, or predates TSA versioning (no case_alloc_version_id recorded). */
private fun computeRunAllocationPreview(caseId: Int, runId: Int): Pair<Int, List<AllocPreviewRow>>? {
    val row = transaction {
        PlanRuns.selectAll().where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }.firstOrNull()
    } ?: return null
    val versionId = row[PlanRuns.caseAllocVersionId] ?: return null
    val config = row[PlanRuns.config]?.let { cfg ->
        @Suppress("UNCHECKED_CAST")
        runCatching { jsonElementToNative(Json.parseToJsonElement(cfg)) as? Map<String, Any?> }.getOrNull()
    }
    val data = transaction { CaseLoader.load(caseId) }
    val tsaOverrides = buildTsaOverridesFromCaseAlloc(loadCaseAllocRows(versionId).orEmpty())
    return versionId to computeAllocationPreview(data, config, tsaOverrides)
}

/** The run-specific critical-material set, derived by aggregating [computeRunAllocationPreview]'s
 *  recomputed grid per product_id instead of by re-deriving isRawCriticalPosition here — a
 *  material only ever appears in that grid if buildSupplyAllocation's criticalPids/criticalMatrix
 *  included it, so the grid IS the ground truth for "which materials were critical for this run,"
 *  with no risk of the agent tool layer drifting from the planner's own canonical test. */
private fun toolGetCriticalMaterials(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required — the critical-material set is versioned per run.", locale)

    val (versionId, rows) = computeRunAllocationPreview(caseId, runId) ?: return toolError(
        "plan run $runId not found for case $caseId, or it predates Targeted Supply Allocation " +
            "versioning (no case_alloc_version_id recorded) — cannot derive the critical-material set.",
        locale,
    )

    val data = transaction { CaseLoader.load(caseId) }
    val supplyMeta = (data["supply"] ?: emptyList()).associate { s ->
        ((s["supply_id"] as? String)?.trim() ?: "") to
            Pair((s["product_id"] as? String)?.trim() ?: "", (s["location_id"] as? String)?.trim() ?: "")
    }

    data class Agg(var totalQty: Double = 0.0, val locations: MutableSet<String> = mutableSetOf(), val demands: MutableSet<String> = mutableSetOf())
    val byProduct = mutableMapOf<String, Agg>()
    for (row in rows) {
        val (pid, lid) = supplyMeta[row.supplyId] ?: continue
        if (pid.isEmpty()) continue
        val agg = byProduct.getOrPut(pid) { Agg() }
        agg.totalQty += row.qtyAllocated
        if (lid.isNotEmpty()) agg.locations.add(lid)
        row.demandId?.let { agg.demands.add(it) }
    }

    if (byProduct.isEmpty()) {
        return ToolResult(
            summary = loc(
                "No critical materials recorded for run $runId's allocation version ($versionId) — " +
                    "either this case has none (every raw/buy position is elastic via a make method " +
                    "or an admitted purchase), or Targeted Supply Allocation was never generated for this version.",
                "运行 $runId 的分配版本（$versionId）没有关键材料记录 — 该案例没有关键材料，或该版本从未生成关键原材料分配。",
                locale,
            ),
            payload = buildJsonObject { put("run_id", runId); put("case_alloc_version_id", versionId); put("materials", buildJsonArray {}) },
        )
    }

    val materials = byProduct.entries.sortedByDescending { it.value.totalQty }.map { (pid, agg) ->
        buildJsonObject {
            put("product_id", pid)
            put("locations", JsonArray(agg.locations.sorted().map { JsonPrimitive(it) }))
            put("total_qty_allocated", agg.totalQty)
            put("demand_count", agg.demands.size)
        }
    }

    return ToolResult(
        summary = loc(
            "Run $runId (allocation version $versionId): ${materials.size} critical material(s) — ${byProduct.keys.sorted().joinToString(", ")}.",
            "运行 $runId（分配版本 $versionId）：${materials.size} 个关键材料 — ${byProduct.keys.sorted().joinToString("、")}。",
            locale,
        ),
        payload = buildJsonObject {
            put("run_id", runId)
            put("case_alloc_version_id", versionId)
            put("materials", JsonArray(materials))
        },
    )
}

private fun toolGetCriticalRawAllocation(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required — Targeted Supply Allocation is versioned per run.", locale)
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        ?: return toolError("`product_id` is required", locale)
    val locationIdFilter = args["location_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    val demandIdFilter = args["demand_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        ?.let { resolveDemandId(caseId, it) ?: it }

    val (versionId, previewRows) = computeRunAllocationPreview(caseId, runId) ?: return toolError(
        "plan run $runId not found for case $caseId, or it predates Targeted Supply Allocation " +
            "versioning (no case_alloc_version_id recorded) — this run has no allocation budget to read.",
        locale,
    )

    val data = transaction { CaseLoader.load(caseId) }
    val supplyMeta = (data["supply"] ?: emptyList()).associate { s ->
        ((s["supply_id"] as? String)?.trim() ?: "") to
            Pair((s["product_id"] as? String)?.trim() ?: "", (s["location_id"] as? String)?.trim() ?: "")
    }

    val matching = previewRows.filter { row ->
        val (pid, lid) = supplyMeta[row.supplyId] ?: return@filter false
        pid == productId &&
            (locationIdFilter == null || lid == locationIdFilter) &&
            (demandIdFilter == null || row.demandId == demandIdFilter)
    }

    val locSuffix = locationIdFilter?.let { "@$it" } ?: ""
    if (matching.isEmpty()) {
        return ToolResult(
            summary = loc(
                "No Targeted Supply Allocation rows for '$productId'$locSuffix in run $runId's allocation version ($versionId) — no pre-allocated budget for this material/version.",
                "运行 $runId 的关键原材料分配版本（$versionId）中没有 '$productId'$locSuffix 的条目 — 该材料/版本没有预分配额度。",
                locale,
            ),
            payload = buildJsonObject {
                put("product_id", productId)
                put("case_alloc_version_id", versionId)
                put("rows", buildJsonArray {})
            },
        )
    }

    val byDemand = matching.groupBy { it.demandId ?: "(unassigned)" }
    val total = matching.sumOf { it.qtyAllocated }
    val taggedCount = matching.count { it.demandId != null }
    val demandSpecificCaps = taggedCount > 0
    // Ground truth: consumeFromInventory (PlanningEngine.kt) + computeAllocationPreview
    // (Allocation.kt). A demand gets a cap for a (product, location) ONLY if it has at
    // least one of its OWN demand_id-tagged rows there; with a cap in place, its OWN
    // untagged lots are forbidden (0). A demand with zero rows of its own — true for
    // every demand when ALL rows here are demand_id=null — draws fully uncapped, as if
    // this material had no Targeted Supply Allocation entry at all. There is no "pool mode"
    // or similar named concept; untagged rows are just a record of known lot quantities.
    // When the caller scoped this query to one demand (demandIdFilter set), every matching row
    // ALREADY belongs to that demand — name it explicitly instead of talking about "any OTHER
    // demand" in the abstract, which is easy to misapply back onto the very demand just queried.
    val note = if (demandIdFilter != null) {
        "Demand '$demandIdFilter' has ${matching.size} of its OWN tagged row(s) here for " +
            "'$productId'$locSuffix, totaling $total — this IS a real, existing per-demand budget " +
            "(any of ITS OWN untagged lots would be forbidden at 0, not uncapped). Do not report " +
            "this demand's rows as null/unassigned/uncapped — they are none of those."
    } else when {
        taggedCount == 0 ->
            "All $taggedCount/${matching.size} row(s) here are untagged (demand_id=null): NO demand " +
                "has a budget cap for '$productId'$locSuffix in this version — every demand draws it " +
                "via the normal, uncapped waterfall, exactly as if there were no Critical Raw " +
                "Allocation entry at all. These rows are only a record of known critical-lot quantities."
        taggedCount == matching.size ->
            "All ${matching.size} row(s) are tagged to a specific demand — this material IS " +
                "budget-capped per-demand in this version. A DIFFERENT demand not appearing among " +
                "these rows has zero rows of its own for '$productId'$locSuffix and therefore draws " +
                "it uncapped — but every demand actually listed here (see `rows`) DOES have a real, " +
                "existing budget; do not describe a listed demand's own rows as null/unassigned."
        else ->
            "$taggedCount/${matching.size} row(s) are tagged to a specific demand; the rest are " +
                "untagged (demand_id=null, granting no one a budget). A demand WITH its own tagged " +
                "row(s) here is capped (untagged lots of ITS OWN become forbidden, not uncapped); a " +
                "demand with NO row of its own for '$productId'$locSuffix draws it fully uncapped."
    }
    return ToolResult(
        summary = loc(
            "Targeted Supply Allocation for '$productId'$locSuffix in run $runId (version $versionId): ${matching.size} lot-demand row(s) across ${byDemand.size} demand(s), total ${total}.",
            "运行 $runId（版本 $versionId）中 '$productId'$locSuffix 的关键原材料分配：${byDemand.size} 个需求共 ${matching.size} 行，总量 ${total}。",
            locale,
        ),
        payload = buildJsonObject {
            put("product_id", productId)
            put("case_alloc_version_id", versionId)
            put("total_qty_allocated", total)
            put("demand_specific_caps", demandSpecificCaps)
            put("note", note)
            put("rows", buildJsonArray {
                matching.forEach { row ->
                    add(buildJsonObject {
                        put("supply_id", row.supplyId)
                        row.demandId?.let { put("demand_id", it) } ?: put("demand_id", JsonNull)
                        put("qty_allocated", row.qtyAllocated)
                    })
                }
            })
        },
    )
}

// ── L1 dataset-feasibility tools ────────────────────────────────────────────
//
// Static, plan-run-independent answers about the case's CSV-derived data:
// BOM containment, location-graph reachability, and the joint demand↔supply
// "is there a structural path?" question. Answer "is A in B's BOM?", "can A
// move L1→L2?", and "does demand D require supply S?" without consulting any
// pegging tree. Pair with [toolGetProductMethods] / [toolGetProductSupply]
// for the per-leaf method + inventory views.

/** Hard cap on BOM-tree recursion depth surfaced via the agent tool. Most
 *  real BOMs are 2-5 levels; bumping higher trades response size for
 *  completeness on unusually deep recipes. */
private const val BOM_TREE_MAX_DEPTH_CAP = 8
private const val MOVE_PATH_MAX_HOPS_CAP = 10

private fun toolGetBomTree(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val productIdArg = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
    val demandIdArg = args["demand_id"]?.jsonPrimitive?.contentOrNull?.trim()
    val maxDepth = (args["max_depth"]?.jsonPrimitive?.intOrNull ?: 4)
        .coerceIn(1, BOM_TREE_MAX_DEPTH_CAP)

    // Resolve productId — caller can pass either directly or via demand_id shortcut.
    val productId: String = when {
        !productIdArg.isNullOrBlank() -> productIdArg
        !demandIdArg.isNullOrBlank() -> {
            val resolvedDemandId = resolveDemandId(caseId, demandIdArg) ?: demandIdArg
            val demandRow = transaction {
                Demands.selectAll()
                    .where { (Demands.caseId eq caseId) and (Demands.demandId eq resolvedDemandId) }
                    .firstOrNull()
            } ?: return toolError("demand_id `$demandIdArg` not found in case $caseId", locale)
            demandRow[Demands.productId]
        }
        else -> return toolError("either `product_id` or `demand_id` is required", locale)
    }

    // Pull the case's BOM rows + method/supply existence indicators in one pass.
    // For ~hundreds of BOM rows + dozens of methods this fits easily in memory;
    // the alternative (per-node DB query) hits N×depth round-trips.
    val (bomByParent, makeProducts, buyProducts, supplyProducts) = transaction {
        val bomRows = Boms.selectAll().where { Boms.caseId eq caseId }.toList()
        val byParent = bomRows.groupBy { it[Boms.parentId] }
        val makes = MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }
            .map { it[MethodMakes.productId] }.toSet()
        val buys = MethodBuys.selectAll().where { MethodBuys.caseId eq caseId }
            .map { it[MethodBuys.productId] }.toSet()
        val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }
            .map { it[Supplies.productId] }.toSet()
        Tuple4(byParent, makes, buys, supplies)
    }

    fun build(pid: String, depth: Int, parentRate: Double?, altGroup: String?, inProgress: MutableSet<String>): Map<String, Any?> {
        // Cycle: render the back-edge instead of recursing.
        if (pid in inProgress) {
            return mapOf(
                "product_id" to pid,
                "level" to depth,
                "cycle_to" to pid,
            )
        }
        val terminalSupply = pid in supplyProducts
        val makeable = pid in makeProducts
        val buyable = pid in buyProducts
        val node = mutableMapOf<String, Any?>(
            "product_id" to pid,
            "level" to depth,
            "terminal_supply" to terminalSupply,
            "makeable" to makeable,
            "buyable" to buyable,
        )
        if (parentRate != null) node["child_qty"] = parentRate
        if (altGroup != null) node["alt_group"] = altGroup

        if (depth >= maxDepth) {
            // Don't expand further — surface as a leaf with truncated flag.
            node["children"] = emptyList<Any>()
            node["truncated"] = true
            return node
        }
        // Walk this product's BOM children. Note: `bom` rows can repeat
        // (per-location duplicates); de-dup by (child_id, alt_group) and
        // pick the first observed rate.
        val rows = bomByParent[pid] ?: emptyList()
        if (rows.isEmpty()) {
            node["children"] = emptyList<Any>()
            return node
        }
        val seen = mutableSetOf<Pair<String, String?>>()
        val children = mutableListOf<Map<String, Any?>>()
        inProgress.add(pid)
        for (row in rows) {
            val cid = row[Boms.childId]
            val ag = row[Boms.altGroup]
            val key = cid to ag
            if (!seen.add(key)) continue
            val rate = row[Boms.rate]
            children.add(build(cid, depth + 1, rate, ag, inProgress))
        }
        inProgress.remove(pid)
        node["children"] = children
        return node
    }

    val tree = build(productId, 0, null, null, mutableSetOf())
    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        if (!demandIdArg.isNullOrBlank()) put("demand_id", JsonPrimitive(demandIdArg))
        put("max_depth", JsonPrimitive(maxDepth))
        put("tree", anyToJson(tree))
    }
    return ToolResult(
        summary = loc(
            "BOM tree for $productId (depth $maxDepth)",
            "$productId 的BOM树（深度 $maxDepth）",
            locale,
        ),
        payload = payload,
    )
}

/** Tiny tuple holder — Kotlin std only ships up to Triple. */
private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun toolFindMovePath(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`product_id` is required", locale)
    val from = args["from_location"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`from_location` is required", locale)
    val to = args["to_location"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`to_location` is required", locale)
    val maxHops = (args["max_hops"]?.jsonPrimitive?.intOrNull ?: 6)
        .coerceIn(1, MOVE_PATH_MAX_HOPS_CAP)

    if (productId.isBlank() || from.isBlank() || to.isBlank()) {
        return toolError("`product_id`, `from_location`, `to_location` cannot be blank", locale)
    }

    if (from == to) {
        return ToolResult(
            summary = loc(
                "Same location ($from) — no move needed for $productId",
                "$productId 同位置 ($from) 无需调拨",
                locale,
            ),
            payload = buildJsonObject {
                put("product_id", JsonPrimitive(productId))
                put("from_location", JsonPrimitive(from))
                put("to_location", JsonPrimitive(to))
                put("reachable", JsonPrimitive(true))
                put("hops", JsonPrimitive(0))
                put("path", JsonArray(emptyList()))
                put("total_transit_time", JsonPrimitive(0.0))
            },
        )
    }

    // Load all move edges for this product, scoped to case.
    data class Edge(val from: String, val to: String, val transitTime: Double, val preference: Int?)
    val edges: List<Edge> = transaction {
        MethodMoves.selectAll()
            .where { (MethodMoves.caseId eq caseId) and (MethodMoves.productId eq productId) }
            .map {
                Edge(
                    from = it[MethodMoves.fromLocationId],
                    to = it[MethodMoves.toLocationId],
                    transitTime = it[MethodMoves.transitTime] ?: 0.0,
                    preference = it[MethodMoves.preference],
                )
            }
    }
    val edgesByFrom: Map<String, List<Edge>> = edges.groupBy { it.from }

    // BFS for shortest hop path (preference / transit time used as tiebreaker
    // among equal-hop alternatives — pick lowest preference, then shortest
    // transit). For a typical 3-5 location graph this is trivial.
    data class Visit(val loc: String, val pathEdges: List<Edge>)
    val queue: ArrayDeque<Visit> = ArrayDeque()
    queue.add(Visit(from, emptyList()))
    val visited = mutableSetOf(from)
    val explored = mutableListOf<String>()
    var found: List<Edge>? = null

    while (queue.isNotEmpty() && found == null) {
        val v = queue.removeFirst()
        explored.add(v.loc)
        if (v.pathEdges.size >= maxHops) continue
        val outgoing = edgesByFrom[v.loc] ?: continue
        // Sort by preference for tiebreaker stability — BFS first hits via
        // shortest hop count, but among same-hop alternatives we prefer the
        // lower-preference edge.
        for (e in outgoing.sortedWith(compareBy({ it.preference ?: Int.MAX_VALUE }, { it.transitTime }))) {
            if (e.to in visited) continue
            visited.add(e.to)
            val newPath = v.pathEdges + e
            if (e.to == to) {
                found = newPath
                break
            }
            queue.add(Visit(e.to, newPath))
        }
    }

    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        put("from_location", JsonPrimitive(from))
        put("to_location", JsonPrimitive(to))
        if (found != null) {
            put("reachable", JsonPrimitive(true))
            put("hops", JsonPrimitive(found.size))
            put("total_transit_time", JsonPrimitive(found.sumOf { it.transitTime }))
            put("path", JsonArray(found.map { e ->
                buildJsonObject {
                    put("from", JsonPrimitive(e.from))
                    put("to", JsonPrimitive(e.to))
                    put("transit_time", JsonPrimitive(e.transitTime))
                    put("preference", JsonPrimitive(e.preference))
                }
            }))
        } else {
            put("reachable", JsonPrimitive(false))
            put("explored", JsonArray(explored.distinct().map { JsonPrimitive(it) }))
            // Hint: name the locations we reached but couldn't advance from.
            // The agent can convert that into a CSV-row recommendation.
            val frontier = explored.distinct().filter { l -> edgesByFrom[l].isNullOrEmpty() }
            if (frontier.isNotEmpty()) {
                put("frontier_dead_ends", JsonArray(frontier.map { JsonPrimitive(it) }))
            }
        }
    }
    return ToolResult(
        summary = loc(
            if (found != null) "Move path $from→$to for $productId — ${found.size} hop(s)"
            else "No move path $from→$to for $productId (explored ${explored.distinct().size} loc(s))",
            if (found != null) "$productId 调拨路径 $from→$to — ${found.size} 跳"
            else "$productId 无调拨路径 $from→$to (探索了 ${explored.distinct().size} 个位置)",
            locale,
        ),
        payload = payload,
    )
}

private fun toolTraceDemandToSupply(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val demandIdArg = args["demand_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`demand_id` is required", locale)
    val supplyId = args["supply_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`supply_id` is required", locale)
    val demandId = resolveDemandId(caseId, demandIdArg) ?: demandIdArg

    // Resolve demand + supply rows.
    data class Demand(val pid: String, val lid: String?, val qty: Double, val priority: Int?)
    data class Supply(val pid: String, val lid: String, val qty: Double)
    val demand: Demand
    val supplies: List<Supply>
    try {
        val (d, s) = transaction {
            val dRow = Demands.selectAll()
                .where { (Demands.caseId eq caseId) and (Demands.demandId eq demandId) }
                .firstOrNull() ?: return@transaction null to emptyList<Supply>()
            val sRows = Supplies.selectAll()
                .where { (Supplies.caseId eq caseId) and (Supplies.supplyId eq supplyId) }
                .map { Supply(it[Supplies.productId], it[Supplies.locationId] ?: "", (it[Supplies.qty])) }
            Demand(dRow[Demands.productId], dRow[Demands.locationId], dRow[Demands.quantity], dRow[Demands.priority]) to sRows
        }
        if (d == null) return toolError("demand `$demandIdArg` not found in case $caseId", locale)
        if (s.isEmpty()) return toolError("supply `$supplyId` not found in case $caseId", locale)
        demand = d
        supplies = s
    } catch (e: Exception) {
        return toolError("lookup failed: ${e.message}", locale)
    }

    // Each supply_id can have multiple rows (one per location). Try each in turn —
    // a successful path through ANY of them counts as reachable.
    val supplyProductId = supplies.first().pid
    if (supplies.any { it.pid != supplyProductId }) {
        return toolError(
            "supply_id `$supplyId` resolves to multiple product_ids (${supplies.map { it.pid }.distinct()}); ambiguous",
            locale,
        )
    }

    // BOM containment: walk demand product's recipe tree, look for supply's product.
    // Reuses the same BOM map pattern as toolGetBomTree but stops as soon as we
    // find the target — and captures the path.
    val bomByParent: Map<String, List<org.jetbrains.exposed.sql.ResultRow>> = transaction {
        Boms.selectAll().where { Boms.caseId eq caseId }.toList().groupBy { it[Boms.parentId] }
    }

    data class BomStep(val from: String, val to: String, val rate: Double?, val altGroup: String?)
    val bomPath: List<BomStep>? = run {
        if (supplyProductId == demand.pid) return@run emptyList()
        val parents = mutableMapOf<String, BomStep>()
        val queue = ArrayDeque<String>()
        queue.add(demand.pid)
        val visited = mutableSetOf(demand.pid)
        var found = false
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val children = bomByParent[cur] ?: continue
            for (row in children) {
                val child = row[Boms.childId]
                if (child in visited) continue
                visited.add(child)
                parents[child] = BomStep(cur, child, row[Boms.rate], row[Boms.altGroup])
                if (child == supplyProductId) {
                    found = true
                    break
                }
                queue.add(child)
            }
            if (found) break
        }
        if (!found) null
        else {
            // Reconstruct from supplyProductId back to demand.pid.
            val path = mutableListOf<BomStep>()
            var cursor: String? = supplyProductId
            while (cursor != null) {
                val step = parents[cursor] ?: break
                path.add(0, step)
                cursor = step.from
                if (cursor == demand.pid) break
            }
            path
        }
    }

    val reachable: Boolean
    val movePath: List<Map<String, Any?>>
    val blocker: String?
    if (bomPath == null) {
        reachable = false
        movePath = emptyList()
        blocker = "supply's product `$supplyProductId` does not appear in demand's BOM (no transitive containment under `${demand.pid}`)"
    } else {
        // The supply's product needs to reach the consuming recipe's location.
        // For a transitive BOM hit, the consuming location is the demand's
        // top-level location_id (BOMs in this codebase are location-agnostic;
        // method_make rows define which locations can produce a given product,
        // and method_move shifts inventory between locations). The simplest
        // heuristic: can the supply at its location reach the demand's location
        // for the supply's own product? If they share a location, it's free.
        val demandLocation = demand.lid ?: ""
        val anyReachable = supplies.any { s ->
            if (s.lid == demandLocation || demandLocation.isBlank()) true
            else {
                // Re-use BFS logic over method_move scoped to the supply's product.
                val edges = transaction {
                    MethodMoves.selectAll()
                        .where { (MethodMoves.caseId eq caseId) and (MethodMoves.productId eq supplyProductId) }
                        .map { Triple(it[MethodMoves.fromLocationId], it[MethodMoves.toLocationId], it[MethodMoves.transitTime] ?: 0.0) }
                }
                val edgesByFrom = edges.groupBy { it.first }
                val q = ArrayDeque<String>()
                q.add(s.lid)
                val visited = mutableSetOf(s.lid)
                var hit = false
                while (q.isNotEmpty()) {
                    val cur = q.removeFirst()
                    if (cur == demandLocation) { hit = true; break }
                    for (e in edgesByFrom[cur] ?: emptyList()) {
                        if (e.second !in visited) { visited.add(e.second); q.add(e.second) }
                    }
                }
                hit
            }
        }
        reachable = anyReachable
        movePath = if (anyReachable) {
            // Compute the path for one matching supply (the first that reached).
            // For simplicity, capture the source locations we have, and let the
            // agent call find_move_path explicitly for the precise path if needed.
            supplies.map { s ->
                mapOf<String, Any?>(
                    "supply_location" to s.lid,
                    "supply_qty" to s.qty,
                    "needs_move_to" to demandLocation,
                )
            }
        } else emptyList()
        blocker = if (anyReachable) null
            else "supply at location(s) ${supplies.map { it.lid }.distinct()} cannot reach demand's location `$demandLocation` for product `$supplyProductId` via method_move chain"
    }

    val payload = buildJsonObject {
        put("demand", buildJsonObject {
            put("demand_id", JsonPrimitive(demandId))
            put("product_id", JsonPrimitive(demand.pid))
            put("location_id", JsonPrimitive(demand.lid))
            put("quantity", JsonPrimitive(demand.qty))
            if (demand.priority != null) put("priority", JsonPrimitive(demand.priority))
        })
        put("supply", buildJsonObject {
            put("supply_id", JsonPrimitive(supplyId))
            put("product_id", JsonPrimitive(supplyProductId))
            put("locations", JsonArray(supplies.map { s ->
                buildJsonObject {
                    put("location_id", JsonPrimitive(s.lid))
                    put("qty", JsonPrimitive(s.qty))
                }
            }))
        })
        put("reachable", JsonPrimitive(reachable))
        put("bom_path", JsonArray((bomPath ?: emptyList()).map { step ->
            buildJsonObject {
                put("parent", JsonPrimitive(step.from))
                put("child", JsonPrimitive(step.to))
                put("rate", JsonPrimitive(step.rate))
                if (step.altGroup != null) put("alt_group", JsonPrimitive(step.altGroup))
            }
        }))
        put("move_path", JsonArray(movePath.map { anyToJson(it) }))
        if (blocker != null) put("blocker", JsonPrimitive(blocker))
    }
    return ToolResult(
        summary = loc(
            if (reachable) "Demand $demandId CAN be fed by supply $supplyId (BOM depth ${bomPath?.size ?: 0})"
            else "Demand $demandId CANNOT be fed by supply $supplyId — ${blocker?.take(80)}",
            if (reachable) "需求 $demandId 可由供应 $supplyId 喂入（BOM 深度 ${bomPath?.size ?: 0}）"
            else "需求 $demandId 无法由供应 $supplyId 喂入 — ${blocker?.take(80)}",
            locale,
        ),
        payload = payload,
    )
}

private fun toolGetRunConfig(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val config = transaction {
        val row = PlanRuns.selectAll()
            .where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }
            .singleOrNull() ?: return@transaction null
        row[PlanRuns.config]
    } ?: return toolError("plan run $runId not found for case $caseId", locale)
    val configJson: JsonElement = config.let { raw ->
        runCatching { jsonParser.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
    }
    val configKeys = (configJson as? JsonObject)?.keys?.joinToString(", ").orEmpty()
    val keysDisplay = configKeys.ifBlank { loc("no top-level keys", "无顶层键", locale) }
    return ToolResult(
        summary = loc(
            "Run $runId config ($keysDisplay)",
            "运行 $runId 的配置（$keysDisplay）",
            locale,
        ),
        payload = buildJsonObject {
            put("run_id", runId)
            put("config", configJson)
        },
    )
}

/** Compare two plan runs side-by-side: config diff + KPI delta + soundness
 *  delta. Pure data — the LLM articulates the mechanism story. Mirrors
 *  [toolGetDemandPegging]'s data-tool pattern (no narrative bundling).
 *
 *  Performance: lazily loads `plan_run.result` only when KB has no snapshot
 *  for the run's signature (matches the listing-endpoint optimization in
 *  [Allocate.kt]).
 */
private fun toolCompareRuns(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val aId = args["run_a_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_a_id` is required", locale)
    val bId = args["run_b_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_b_id` is required", locale)

    data class RunSlot(
        val id: Int,
        val configJson: JsonObject,
        val signature: String,
        val kpis: JsonObject,
        val soundnessStatus: String,
    )

    fun loadRun(runId: Int): RunSlot? = transaction {
        val row = PlanRuns.selectAll()
            .where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }
            .firstOrNull() ?: return@transaction null
        val cfgRaw = row[PlanRuns.config] ?: "{}"
        val cfg = runCatching { jsonParser.parseToJsonElement(cfgRaw).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        val sig = runCatching { CaseBootstrap.signatureFor(cfg) }.getOrElse { "" }
        // KPI lookup: prefer KB row by signature; fall back to lazy result parse.
        val kbBySig: Map<String, com.allocator.services.KbStore.KbRecord> =
            com.allocator.services.KbStore.listForCase(caseId)
        val kpis: JsonObject = kbBySig[sig]?.let { kb ->
            runCatching { jsonParser.parseToJsonElement(kb.kpisSnapshotJson).jsonObject }.getOrNull()
        } ?: com.allocator.services.KbStore.extractKpisFromResult(row[PlanRuns.result])
        RunSlot(
            id = runId,
            configJson = cfg,
            signature = sig,
            kpis = kpis,
            soundnessStatus = row[PlanRuns.soundnessStatus],
        )
    }

    val a = loadRun(aId) ?: return toolError("plan run $aId not found for case $caseId", locale)
    val b = loadRun(bId) ?: return toolError("plan run $bId not found for case $caseId", locale)

    // Structural JSON diff — flatten paths, only emit entries that differ.
    val configDiff: List<Map<String, Any?>> = diffJsonObjects(a.configJson, b.configJson, "")

    // KPI delta — only over the standard set, b - a.
    val kpiNames = listOf(
        "fill_rate_pct", "gini", "p10_fill_ratio", "median_fill_ratio",
        "starvation_pct", "on_time_count", "manufacturing_total_quantity",
        "inventory_consumed_total", "total_committed", "total_requested",
    )
    val kpiDelta: Map<String, Double?> = kpiNames.associateWith { name ->
        val av = (a.kpis[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val bv = (b.kpis[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        if (av == null || bv == null) null else (bv - av)
    }

    val signatureMatch = a.signature == b.signature && a.signature.isNotBlank()
    val payload = buildJsonObject {
        put("a", buildJsonObject {
            put("id", JsonPrimitive(a.id))
            put("signature", JsonPrimitive(a.signature))
            put("soundness_status", JsonPrimitive(a.soundnessStatus))
            put("kpis", a.kpis)
        })
        put("b", buildJsonObject {
            put("id", JsonPrimitive(b.id))
            put("signature", JsonPrimitive(b.signature))
            put("soundness_status", JsonPrimitive(b.soundnessStatus))
            put("kpis", b.kpis)
        })
        put("signature_match", JsonPrimitive(signatureMatch))
        put("config_diff", JsonArray(configDiff.map { entry -> anyToJson(entry) }))
        put("kpi_delta", buildJsonObject {
            for ((k, v) in kpiDelta) {
                put(k, if (v == null) JsonNull else JsonPrimitive(v))
            }
        })
        put("soundness_delta", JsonPrimitive("${a.soundnessStatus} → ${b.soundnessStatus}"))
    }

    val diffSummary = if (signatureMatch) "configs identical (signature match)"
        else "${configDiff.size} config path(s) differ"
    return ToolResult(
        summary = loc(
            "Compare runs $aId vs $bId — $diffSummary",
            "对比运行 $aId 与 $bId — $diffSummary",
            locale,
        ),
        payload = payload,
    )
}

/** Recursive structural diff. For each leaf path that differs between [a] and
 *  [b], emit `{path, a, b}`. Treats nested JsonObject as a sub-tree to diff;
 *  arrays + primitives compared by structural equality. Order-agnostic via
 *  the union-of-keys traversal. */
private fun diffJsonObjects(a: JsonObject, b: JsonObject, prefix: String): List<Map<String, Any?>> {
    val keys = (a.keys + b.keys).toSortedSet()
    val result = mutableListOf<Map<String, Any?>>()
    for (k in keys) {
        val path = if (prefix.isEmpty()) k else "$prefix.$k"
        val av = a[k]
        val bv = b[k]
        when {
            av == null && bv == null -> {}  // unreachable but defensive
            av == null -> result.add(mapOf("path" to path, "a" to null, "b" to jsonElementToAny(bv!!)))
            bv == null -> result.add(mapOf("path" to path, "a" to jsonElementToAny(av), "b" to null))
            av is JsonObject && bv is JsonObject -> result.addAll(diffJsonObjects(av, bv, path))
            av != bv -> result.add(mapOf("path" to path, "a" to jsonElementToAny(av), "b" to jsonElementToAny(bv)))
        }
    }
    return result
}

/** Unwrap a JsonElement into a Kotlin primitive/list/map for serialization
 *  via [anyToJson]. Used by the diff to emit comparable values without
 *  requiring the LLM to parse JsonElement strings. */
private fun jsonElementToAny(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is JsonPrimitive -> e.booleanOrNull ?: e.intOrNull ?: e.doubleOrNull ?: e.contentOrNull
    is JsonArray -> e.map { jsonElementToAny(it) }
    is JsonObject -> e.mapValues { jsonElementToAny(it.value) }
}

/**
 * Explain a per-WO method choice — "why was method X picked over Y at node N?".
 *
 * Walks the FULL planning_pegging tree (not pruned for context-window safety
 * like get_demand_pegging does) to find every work_order at the requested
 * (product_id, location_id) and returns each with its method_choice_explanation
 * + parent demand context. Also bundles the static alternatives at the site
 * (from the method_* tables) and the run's method_selection config so the
 * LLM can explain the selection criterion (preference int comparison vs
 * elaborate composite scoring).
 *
 * The pegging-tree pruner I added earlier collapses fulfilled subtrees deeper
 * than 2 levels, which means questions about successful WOs deep in the tree
 * may not be answerable from get_demand_pegging alone. This tool bypasses the
 * pruner by reading the un-pruned planning_pegging entry directly.
 */
private fun toolExplainMethodChoice(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`product_id` is required", locale)
    val locationId = args["location_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`location_id` is required", locale)
    if (productId.isBlank() || locationId.isBlank()) {
        return toolError("`product_id` / `location_id` cannot be blank", locale)
    }
    val demandFilter = args["demand_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        ?.let { resolveDemandId(caseId, it) ?: it }

    val result = loadPlanResultWithPeggingFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId", locale)

    @Suppress("UNCHECKED_CAST")
    val planningPegging = (result["planning_pegging"] as? List<Map<String, Any?>>) ?: emptyList()

    // Each match is one WO instance at the (pid, lid) site, with its parent
    // demand context for disambiguation. The same site can appear under
    // multiple parents (e.g., consolidated component shared by multiple FGs).
    data class WoMatch(
        val demandId: String?,
        val parentPid: String?, val parentLid: String?, val parentQty: Double?,
        val woMethod: String,
        val woQty: Double,
        val woFailed: Boolean,
        val methodExplanation: String?,
        val variantExplanation: String?,
        val locationSource: String?,
    )

    val matches = mutableListOf<WoMatch>()

    @Suppress("UNCHECKED_CAST")
    fun walk(node: Map<String, Any?>, parentPid: String?, parentLid: String?, parentQty: Double?, demandId: String?) {
        val type = node["type"] as? String
        when (type) {
            "demand" -> {
                val nextPid = node["product_id"] as? String
                val nextLid = node["location_id"] as? String
                val nextQty = (node["quantity"] as? Number)?.toDouble()
                val children = node["children"] as? List<Map<String, Any?>> ?: return
                // Parent of a WO is the demand directly above it; recurse with
                // this demand as the parent context.
                for (c in children) walk(c, nextPid, nextLid, nextQty, demandId)
            }
            "work_order" -> {
                val pid = (node["product_id"] as? String)?.trim()
                val lid = (node["location_id"] as? String)?.trim()
                if (pid == productId && lid == locationId) {
                    matches.add(WoMatch(
                        demandId = demandId,
                        parentPid = parentPid, parentLid = parentLid, parentQty = parentQty,
                        woMethod = (node["method"] as? String) ?: "",
                        woQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0,
                        woFailed = node["failed"] == true,
                        methodExplanation = node["method_choice_explanation"] as? String,
                        variantExplanation = node["variant_choice_explanation"] as? String,
                        locationSource = node["location_source"] as? String,
                    ))
                }
                val children = node["children"] as? List<Map<String, Any?>> ?: return
                for (c in children) walk(c, parentPid, parentLid, parentQty, demandId)
            }
            else -> {
                val children = node["children"] as? List<Map<String, Any?>> ?: return
                for (c in children) walk(c, parentPid, parentLid, parentQty, demandId)
            }
        }
    }

    for (entry in planningPegging) {
        val entryDemandId = entry["demand_id"]?.toString()
        if (demandFilter != null && entryDemandId != demandFilter) continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, null, null, null, entryDemandId)
    }

    // Static alternatives at the site (regardless of which one the planner
    // picked). Caller can compare the chosen method to these to articulate
    // "elaborate scored make@2000 higher than move-from-1000@2000 because…".
    val availableMethods: List<Map<String, Any?>> = transaction {
        val makes = MethodMakes.selectAll()
            .where { (MethodMakes.caseId eq caseId) and (MethodMakes.productId eq productId) and (MethodMakes.locationId eq locationId) }
            .map { mapOf("type" to "make", "location" to it[MethodMakes.locationId], "preference" to it[MethodMakes.preference], "lead_time" to it[MethodMakes.leadTime]) }
        val moves = MethodMoves.selectAll()
            .where { (MethodMoves.caseId eq caseId) and (MethodMoves.productId eq productId) and (MethodMoves.toLocationId eq locationId) }
            .map { mapOf("type" to "move", "from" to it[MethodMoves.fromLocationId], "to" to it[MethodMoves.toLocationId], "preference" to it[MethodMoves.preference], "transit_time" to it[MethodMoves.transitTime]) }
        val buys = MethodBuys.selectAll()
            .where { (MethodBuys.caseId eq caseId) and (MethodBuys.productId eq productId) and (MethodBuys.locationId eq locationId) }
            .map { mapOf("type" to "buy", "location" to it[MethodBuys.locationId], "preference" to it[MethodBuys.preference]) }
        (makes + moves + buys).sortedBy { (it["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
    }

    // Run config so the LLM knows the selection criterion (preference vs elaborate)
    // and the score weights / depth caps in effect.
    val configRaw = transaction {
        PlanRuns.selectAll().where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }
            .firstOrNull()?.get(PlanRuns.config)
    }
    val configJsonFull: JsonObject = configRaw?.let { raw ->
        runCatching { jsonParser.parseToJsonElement(raw).jsonObject }.getOrNull()
    } ?: JsonObject(emptyMap())
    val msConfig: JsonObject = (configJsonFull["method_selection"] as? JsonObject) ?: JsonObject(emptyMap())
    val purchaseAllowed = configJsonFull["purchase_allowed"]?.jsonPrimitive?.booleanOrNull != false
    // Selective-purchase whitelist (empty ⇒ all raw materials buyable).
    val purchasableMaterials: Set<String> = (configJsonFull["purchasable_materials"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        ?.toSet() ?: emptySet()
    val buyNotWhitelisted = purchaseAllowed && purchasableMaterials.isNotEmpty() &&
        productId.trim() !in purchasableMaterials
    val mode = msConfig["mode"]?.jsonPrimitive?.contentOrNull ?: "preference"
    val maxMethods = msConfig["max_methods"]?.jsonPrimitive?.intOrNull ?: 2

    // ── Per-alternative status classification (supply-side override use-case) ──
    //
    // Symmetric to the demand-side `members` enrichment in get_leaf_competition.
    // For each method in `available_methods_at_site`, classify against the
    // chosen method(s) + selection criteria + cascade-probe failures captured
    // in pegging. Yields actionable "would_admit_if" hints so the agent can
    // narrate "method 2 was eliminated by max_methods cap of 2 — bump to 3
    // to admit it" (or "manually override method_selection at this site").
    //
    // Status values (mirrors the demand-side taxonomy):
    //   • chosen                   — selected by the planner; in matches
    //   • lower_preference         — preference > chosen waterfall's max
    //                                (cascade mode picked a higher-ranked one)
    //   • beyond_max_methods       — preference rank exceeds methodCfg.maxMethods
    //   • purchase_disabled        — type=buy with purchase_allowed=false
    //   • failed_cascade_probe     — appears in pegging with failed=true
    //                                (cascade picker tried, BOM probe blocked)
    //   • score_lower              — elaborate mode catch-all for losing alts
    //   • unknown_not_chosen       — neither chosen nor matched a known reason

    data class MethodKey(val type: String, val fromLoc: String?)
    fun keyOf(m: WoMatch): MethodKey =
        MethodKey(m.woMethod, m.locationSource?.takeIf { it.isNotBlank() })
    fun keyOfAvail(am: Map<String, Any?>): MethodKey =
        MethodKey((am["type"] as? String) ?: "", am["from"] as? String)

    val chosenSucceededKeys: Set<MethodKey> = matches
        .filter { !it.woFailed }
        .map { keyOf(it) }.toSet()
    val failedCascadeKeys: Set<MethodKey> = matches
        .filter { it.woFailed }
        .map { keyOf(it) }.toSet()
    // Among admitted (non-failed) chosen methods, the highest preference is
    // the cascade's "last waterfall slot" — alternatives beyond this lose by
    // ranking. Used to classify lower_preference vs beyond_max_methods.
    val chosenSucceededPrefs = availableMethods
        .filter { keyOfAvail(it) in chosenSucceededKeys }
        .mapNotNull { (it["preference"] as? Number)?.toInt() }
    val chosenMaxPref = chosenSucceededPrefs.maxOrNull()

    val classifiedMethods: List<Map<String, Any?>> = availableMethods.mapIndexed { rank, am ->
        val key = keyOfAvail(am)
        val amType = key.type
        val amPref = (am["preference"] as? Number)?.toInt()
        val (status, reason, hint) = when {
            key in chosenSucceededKeys ->
                Triple("chosen", "selected by the planner at this site", null)
            amType == "buy" && !purchaseAllowed ->
                Triple("purchase_disabled", "method type=buy but purchase_allowed=false on this run", "set purchase_allowed=true (or add a Constraints pin at this site)")
            amType == "buy" && buyNotWhitelisted ->
                Triple("purchase_not_whitelisted", "method type=buy but $productId is not in the purchasable_materials whitelist on this run", "add $productId to purchasable_materials (or clear the list to allow all raw materials)")
            key in failedCascadeKeys ->
                Triple("failed_cascade_probe", "cascade picker tried this method but its BOM probe blocked deeper (failed=true in pegging)", "non-trivial — fix upstream inventory or methods at the deeper bottleneck (call get_demand_pegging on a relevant demand to trace)")
            // beyond_max_methods: this alt's rank (1-based) > maxMethods AND
            // its preference is worse than chosen's last slot. Only meaningful
            // when waterfall is on (maxMethods > 1).
            amPref != null && chosenMaxPref != null && amPref > chosenMaxPref && (rank + 1) > maxMethods ->
                Triple("beyond_max_methods", "preference $amPref ranks position ${rank + 1} which exceeds max_methods=$maxMethods", "set method_selection.max_methods >= ${rank + 1} (or add a Constraints pin at this site)")
            // lower_preference: preference is worse than chosen, but within
            // the max_methods cap — meaning the cascade simply preferred the
            // higher-ranked one and the residual didn't reach this slot.
            amPref != null && chosenMaxPref != null && amPref > chosenMaxPref ->
                Triple("lower_preference", "preference $amPref > chosen waterfall's max preference $chosenMaxPref; cascade ordering elected the higher-ranked method", "a Constraints pin OR re-rank this method's preference in method_${amType} CSV")
            else ->
                Triple("unknown_not_chosen", "not chosen for an unidentified reason (no preference comparison applies); inspect the run config or the chosen WO's method_choice_explanation", "a Constraints pin at this site")
        }
        am + mapOf<String, Any?>(
            "status" to status,
            "presumed_reason" to reason,
            "would_admit_if" to hint,
        )
    }

    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        put("location_id", JsonPrimitive(locationId))
        if (demandFilter != null) put("demand_id_filter", JsonPrimitive(demandFilter))
        put("match_count", JsonPrimitive(matches.size))
        put("matches", JsonArray(matches.map { m ->
            buildJsonObject {
                put("demand_id", m.demandId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("parent_pid", m.parentPid?.let { JsonPrimitive(it) } ?: JsonNull)
                put("parent_lid", m.parentLid?.let { JsonPrimitive(it) } ?: JsonNull)
                put("parent_qty", m.parentQty?.let { JsonPrimitive(it) } ?: JsonNull)
                put("chosen_method", JsonPrimitive(m.woMethod))
                put("wo_qty", JsonPrimitive(m.woQty))
                put("failed", JsonPrimitive(m.woFailed))
                put("method_choice_explanation", m.methodExplanation?.let { JsonPrimitive(it) } ?: JsonNull)
                if (m.variantExplanation != null) put("variant_choice_explanation", JsonPrimitive(m.variantExplanation))
                if (m.locationSource != null) put("location_source", JsonPrimitive(m.locationSource))
            }
        }))
        put("available_methods_at_site", anyToJson(classifiedMethods))
        put("selection_mode", JsonPrimitive(mode))
        put("max_methods", JsonPrimitive(maxMethods))
        put("purchase_allowed", JsonPrimitive(purchaseAllowed))
        put("purchasable_materials", JsonArray(purchasableMaterials.sorted().map { JsonPrimitive(it) }))
        put("override_levers", buildJsonArray {
            // Symmetric to get_leaf_competition's override_levers — names the
            // supply-side override paths so the agent can recommend the right
            // one based on the alternative's `status`.
            add(JsonPrimitive("a Constraints pin — pin a specific method/BOM-alternative at this (pid, lid) site (see 'External config objects' in the system prompt)"))
            add(JsonPrimitive("change method_selection.max_methods — admit more waterfall slots (across method type AND BOM variant)"))
            add(JsonPrimitive("change purchase_allowed — admit/exclude method_buy"))
            add(JsonPrimitive("change purchasable_materials — selectively whitelist which raw materials may be bought (empty = all)"))
            add(JsonPrimitive("change preference values in method_make/move/buy CSV — re-rank globally"))
        })
    }

    val sumEn = if (matches.isEmpty()) "No WO at $productId@$locationId in run $runId"
        else "${matches.size} WO match(es) for $productId@$locationId — chosen: ${matches.first().woMethod} (${availableMethods.size} method(s) available at site)"
    val sumZh = if (matches.isEmpty()) "运行 $runId 未找到 $productId@$locationId 的 WO"
        else "$productId@$locationId 匹配 ${matches.size} 个 WO — 选用：${matches.first().woMethod}（站点可用方法 ${availableMethods.size} 个）"
    return ToolResult(
        summary = loc(sumEn, sumZh, locale),
        payload = payload,
    )
}

private fun toolRecheckSoundness(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val deepCheck = args["deep_check"]?.jsonPrimitive?.booleanOrNull ?: true
    return try {
        val report = runSoundnessCheckForRun(caseId, runId, deepCheck)
        val overall = report["overall_sound"]?.jsonPrimitive?.booleanOrNull
        val sound = report["sound_count"]?.jsonPrimitive?.intOrNull
        val total = report["demand_count"]?.jsonPrimitive?.intOrNull
        val verdictEn = if (overall == true) "sound" else "unsound"
        val verdictZh = if (overall == true) "通过" else "未通过"
        ToolResult(
            summary = loc(
                "Re-checked run $runId: $verdictEn ($sound/$total demands)",
                "已重新校验运行 $runId：$verdictZh（$sound/$total 个需求）",
                locale,
            ),
            payload = report,
        )
    } catch (e: NoSuchElementException) {
        toolError(e.message ?: "plan run $runId not found", locale)
    } catch (e: IllegalStateException) {
        toolError(e.message ?: "soundness check failed", locale)
    } catch (e: Exception) {
        toolError("soundness check raised: ${e.message ?: e::class.simpleName ?: "unknown"}", locale)
    }
}

private fun toolGetSoundnessSummary(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val reportRaw = transaction {
        PlanRuns.selectAll()
            .where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }
            .singleOrNull()?.get(PlanRuns.soundnessReport)
    } ?: return toolError(
        "plan run $runId has no soundness_report — call recheck_soundness first to generate one",
        locale,
    )
    val report = runCatching { jsonParser.parseToJsonElement(reportRaw).jsonObject }
        .getOrElse { return toolError("plan run $runId soundness_report is not parseable JSON", locale) }

    // Aggregate per-demand violations by rule.
    data class Bucket(val demandIds: MutableSet<String> = mutableSetOf(), var count: Int = 0, var totalActual: Double = 0.0)
    val buckets = mutableMapOf<String, Bucket>()
    val demands = report["demands"]?.jsonArray ?: JsonArray(emptyList())
    for (d in demands) {
        val obj = d.jsonObject
        val demandId = obj["demand_id"]?.jsonPrimitive?.contentOrNull ?: continue
        val violations = obj["violations"]?.jsonArray ?: continue
        for (v in violations) {
            val vObj = v.jsonObject
            val rule = vObj["rule"]?.jsonPrimitive?.contentOrNull ?: continue
            val actualNum = vObj["actual"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val b = buckets.getOrPut(rule) { Bucket() }
            b.demandIds.add(demandId)
            b.count++
            b.totalActual += actualNum
        }
    }
    // Cross-demand violations (separate from per-demand) — emit under their own rule key
    // with the demand_count meaningless (set to null in the output for those rows).
    val crossViolations = report["cross_demand_violations"]?.jsonArray ?: JsonArray(emptyList())
    val crossBuckets = mutableMapOf<String, Bucket>()
    for (v in crossViolations) {
        val vObj = v.jsonObject
        val rule = vObj["rule"]?.jsonPrimitive?.contentOrNull ?: continue
        val actualNum = vObj["actual"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val b = crossBuckets.getOrPut(rule) { Bucket() }
        b.count++
        b.totalActual += actualNum
    }

    val nRules = buckets.size + crossBuckets.size
    return ToolResult(
        summary = if (nRules == 0)
            loc("Soundness summary for run $runId: no violations",
                "运行 $runId 的合理性汇总：无违规", locale)
        else
            loc("Soundness summary for run $runId: $nRules rules",
                "运行 $runId 的合理性汇总：$nRules 条规则违规", locale),
        payload = buildJsonObject {
            put("run_id", runId)
            put("overall_sound", report["overall_sound"] ?: JsonPrimitive(true))
            put("demand_count", report["demand_count"] ?: JsonPrimitive(0))
            put("sound_count", report["sound_count"] ?: JsonPrimitive(0))
            put("deep_check", report["deep_check"] ?: JsonPrimitive(false))
            put("violations_by_rule", buildJsonArray {
                buckets.entries.sortedByDescending { it.value.totalActual }.forEach { (rule, b) ->
                    add(buildJsonObject {
                        put("rule", rule)
                        put("demand_count", b.demandIds.size)
                        put("violation_count", b.count)
                        put("total_actual", b.totalActual)
                    })
                }
            })
            put("cross_demand_violations_by_rule", buildJsonArray {
                crossBuckets.entries.sortedByDescending { it.value.totalActual }.forEach { (rule, b) ->
                    add(buildJsonObject {
                        put("rule", rule)
                        put("violation_count", b.count)
                        put("total_actual", b.totalActual)
                    })
                }
            })
        },
    )
}

private fun toolReadMemory(caseId: Int, locale: String): ToolResult {
    val mem = loadMemory(caseId)
    return ToolResult(
        summary = loc(
            "Read ${mem.size} memory entries",
            "已读取 ${mem.size} 条记忆",
            locale,
        ),
        payload = mem,
    )
}

private fun toolWriteMemory(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val key = args["key"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`key` is required", locale)
    if (key.length > 128) return toolError("`key` must be ≤ 128 characters", locale)
    val value = args["value"] ?: return toolError("`value` is required", locale)
    upsertMemory(caseId, key, value)
    return ToolResult(
        summary = loc("Wrote memory[$key]", "已写入记忆[$key]", locale),
        payload = buildJsonObject { put("ok", true); put("key", key) },
    )
}

// ── Generic helpers ──────────────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun jsonObjectToMap(obj: JsonObject): Map<String, Any?> {
    fun convert(el: JsonElement): Any? = when (el) {
        is JsonObject -> el.mapValues { convert(it.value) }
        is JsonArray -> el.map { convert(it) }
        is JsonPrimitive -> when {
            el.contentOrNull == null -> null
            el.isString -> el.content
            else -> el.intOrNull ?: el.content.toDoubleOrNull() ?: el.content.toBooleanStrictOrNull() ?: el.content
        }
        else -> null
    }
    return convert(obj) as Map<String, Any?>
}

private fun anyToJson(v: Any?): JsonElement {
    return when (v) {
        null -> JsonPrimitive(null as String?)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Long -> JsonPrimitive(v)
        is Double -> JsonPrimitive(v)
        is Float -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v.toDouble())
        is String -> JsonPrimitive(v)
        is Map<*, *> -> buildJsonObject {
            v.forEach { (k, vv) -> put(k.toString(), anyToJson(vv)) }
        }
        is Iterable<*> -> buildJsonArray { v.forEach { add(anyToJson(it)) } }
        else -> JsonPrimitive(v.toString())
    }
}

// ── Agent loop ───────────────────────────────────────────────────────────────

private const val MAX_TOOL_ITERATIONS = 8

private suspend fun runAgentLoop(
    caseId: Int,
    userMessage: String,
    initialConfig: JsonObject,
    history: List<AgentHistoryItem>,
    viewingRunId: Int? = null,
): AgentResponse {
    var workingConfig = initialConfig
    var freshRunId: Int? = null
    var pendingJobId: String? = null
    val steps = mutableListOf<AgentStep>()
    // Locale drives only the tool result summaries shown in the chat-step trace
    // — the LLM-rendered reply already mirrors the user's language via the
    // system prompt's "Mirror the user's language" tactic. We detect from the
    // current user message; conversation-mid switches are handled per-turn.
    val locale = detectLocale(userMessage)

    // Bootstrap memory into the system prompt so the model sees prior context
    // without needing to call read_memory first (saves a round-trip).
    // Sequence:
    //   1. SYSTEM_PROMPT_INTRO — persona / intent classification / tactics
    //   2. AGENT_KNOWLEDGE     — curated digest of value props + design
    //                            decisions + tool ops + glossary, loaded
    //                            from src/main/resources/agent-knowledge.md.
    //                            Update that file when shipping major
    //                            features so the agent's knowledge stays
    //                            current.
    //   3. <memory>            — per-case agent_memory entries
    //   4. <current_config>    — the working PlanningConfig
    //   5. <viewing_run_id>    — the run the user is currently viewing on the
    //                            page (from the request); the default anchor
    //                            for run-scoped questions without a number.
    //   6. <active_run_id>     — the case's designated active run (or latest
    //                            successful), via resolveActiveRunId; the
    //                            secondary fallback when nothing is being
    //                            viewed.
    val memory = loadMemory(caseId)
    // Resolve the case's active run id with the same logic the rest of the
    // app uses (designated → falls back to latest success). When the case has
    // no successful runs yet this is null and the agent must ask the user.
    // caseName is surfaced as <case_name> below — the chat has no other way to tell the agent
    // (or, via its replies, the user) which case/scenario it's currently scoped to. Distinct
    // cases can reuse the same free-form id for different real-world entities (e.g. "Q4R" is a
    // product in one case's data and a customer in another's) — without this, a zero-match
    // lookup looks like "not found" instead of "wrong case", and the agent has no way to say so.
    var caseName: String? = null
    val activeRunId: Int? = transaction {
        val caseRow = Cases.selectAll().where { Cases.id eq caseId }.firstOrNull()
        caseName = caseRow?.get(Cases.name)
        val designatedId = caseRow?.get(Cases.designatedActivePlanRunId)
        val successIds = PlanRuns.selectAll()
            .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
            .map { it[PlanRuns.id] }
        com.allocator.services.resolveActiveRunId(designatedId, successIds)
    }
    val systemWithMemory = buildString {
        append(SYSTEM_PROMPT_INTRO)
        if (AGENT_KNOWLEDGE.isNotBlank()) {
            append("\n\n────── KNOWLEDGE PRIMER ──────\n\n")
            append(AGENT_KNOWLEDGE)
        }
        append("\n\n<memory>\n")
        if (memory.isEmpty()) append("(empty)") else append(memory.toString())
        append("\n</memory>")
        append("\n\n<current_config>\n")
        append(workingConfig.toString())
        append("\n</current_config>")
        append("\n\n<case_id>").append(caseId.toString()).append("</case_id>")
        append("\n<case_name>").append(caseName ?: "(unknown)").append("</case_name>")
        append("\n\n<viewing_run_id>")
        append(viewingRunId?.toString() ?: "(none)")
        append("</viewing_run_id>")
        append("\n<active_run_id>")
        append(activeRunId?.toString() ?: "(none)")
        append("</active_run_id>")
        // Today's date — used to resolve relative phrases ("next Monday",
        // "in 2 weeks", "starting mid-July") into the ISO yyyy-MM-dd form
        // the schedule-change tools require.
        append("\n<current_date>")
        append(java.time.LocalDate.now().toString())
        append("</current_date>")
        // Pending maintenance-decision context: when a prior turn ran
        // analyze_wo_schedule_impact and produced a contingent plan run that
        // hasn't been promoted yet, the contingent_plan_run_id is named here
        // so the LLM uses THIS id (not <active_run_id>) for promote_plan_run
        // when the user accepts. Cleared on successful promote of the matching
        // id; otherwise auto-expires after PENDING_MAINTENANCE_TTL_MIN minutes.
        val pendingForPrompt = loadPendingMaintenance(caseId)
        if (pendingForPrompt != null) {
            append("\n\n<pending_maintenance_decision>")
            append("\n  option_c_cpr_id=").append(pendingForPrompt.contingentPlanRunId)
            pendingForPrompt.optionACprId?.let { append("\n  option_a_cpr_id=").append(it) }
            pendingForPrompt.optionBCprId?.let { append("\n  option_b_cpr_id=").append(it) }
            pendingForPrompt.maxFeasibleDays?.let {
                append("\n  max_feasible_days=").append(it)
            }
            pendingForPrompt.prodArea?.let { append("\n  prod_area=").append(it) }
            pendingForPrompt.bucketStart?.let { append("\n  original_bucket_start=").append(it) }
            pendingForPrompt.originalDelayDays?.let { append("\n  original_delay_days=").append(it) }
            pendingForPrompt.alternateStartDate?.let { append("\n  alternate_start_date=").append(it) }
            pendingForPrompt.woGroupIds?.takeIf { it.isNotEmpty() }?.let { gids ->
                append("\n  wo_group_ids=[").append(gids.joinToString(",") { "\"$it\"" }).append("]")
            }
            append("\n  captured_at=").append(pendingForPrompt.capturedAt.toString())
            append("\n  hint=On the user's option pick, call promote_plan_run with the cached id matching their choice:")
            append(" option 1 → option_a_cpr_id, option 2 → option_b_cpr_id, option 3 → option_c_cpr_id.")
            append(" Do NOT re-run analyze_wo_schedule_impact on the pick turn — the contingents were generated during the assessment.")
            append(" Do NOT delete any of the unchosen contingents — they stay in history as a record of the assessed alternatives.")
            append(" Do NOT use <active_run_id> or <viewing_run_id> as the contingent id — they are unrelated baseline plan ids.")
            append("\n</pending_maintenance_decision>")
        }
    }

    val convo = mutableListOf<LlmAgentMessage>()
    history.forEach { h ->
        if (h.role == "user" || h.role == "assistant") {
            convo.add(LlmAgentMessage(role = h.role, content = h.content ?: ""))
        }
    }
    convo.add(LlmAgentMessage(role = "user", content = userMessage))

    repeat(MAX_TOOL_ITERATIONS) { iter ->
        val resp = llmChatWithTools(
            systemPrompt = systemWithMemory,
            messages = convo,
            tools = TOOLS,
            // 4096 lets the model render long list-style replies (e.g.
            // find_demands_for_product returning 20+ demands with full
            // fields each). 1024 truncated those mid-row.
            maxTokens = 4096,
            temperature = 0.2,
            // Route through the shared allocator LLM pin (default nanogpt;
            // controlled by ALLOCATOR_LLM_PROVIDER). Defaulting to NanoGPT
            // direct is deliberate: the OpenClaw gateway DOES forward the
            // caller's `tools` array (current OpenClaw build, verified 2026-05-15),
            // but it also auto-injects ~47 built-in tools (read/write/exec,
            // memory_*, all globally-configured MCP servers including
            // scheduling-engine and planning_engine) — those directly overlap
            // the planning agent's domain and confuse tool selection. NanoGPT
            // is a thin OpenAI-shape passthrough to the same upstream models
            // OpenClaw uses (default minimax/minimax-m2.7), so the model sees
            // only this agent's 35 tools.
            provider = config.allocatorLlmProvider,
        )

        // No tool calls → final reply.
        if (resp.toolCalls.isEmpty()) {
            val rawReply = resp.text ?: "(empty reply)"
            val (safetyNetReply, finalSteps) = applyOption12SafetyNet(
                caseId, userMessage, rawReply, steps, locale,
            )
            val finalReply = applyPlanStartedHonestyCheck(safetyNetReply, finalSteps, locale)
            return AgentResponse(
                reply = finalReply,
                steps = finalSteps,
                configUpdate = workingConfig.takeIf { it != initialConfig },
                freshRunId = freshRunId,
                pendingJobId = pendingJobId,
            )
        }

        // Append the assistant's tool-request message exactly as received, so
        // the next round-trip carries the tool_call_id<->tool_result linkage.
        convo.add(LlmAgentMessage(role = "assistant", content = resp.text, toolCalls = resp.toolCalls))

        // If any tool returned a terminalReply, we'll skip the next LLM
        // rendering pass and use it directly. Picks the LAST terminalReply
        // produced in this iteration (concatenating multiples would be
        // ambiguous; in practice only one such tool fires per turn).
        var earlyTerminalReply: String? = null

        // Execute every tool call sequentially, append results.
        for (call in resp.toolCalls) {
            val args = runCatching { jsonParser.parseToJsonElement(call.arguments).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            val (result, configAfter) = dispatchTool(caseId, call, args, workingConfig, locale)
            workingConfig = configAfter
            // run_plan_async always returns immediately with status='started';
            // wait_for_plan (rarely called now) may return 'completed',
            // 'still_running', or 'failed'. In any in-flight case the
            // frontend takes over via pending_job_id polling.
            if (call.name == "run_plan_async" || call.name == "wait_for_plan") {
                val payload = result.payload as? JsonObject
                val rid = payload?.get("plan_run_id")?.jsonPrimitive?.intOrNull
                val status = payload?.get("status")?.jsonPrimitive?.contentOrNull
                if (rid != null) {
                    freshRunId = rid
                    pendingJobId = null
                } else if (status == "started" || status == "running" || status == "still_running") {
                    val jid = payload?.get("job_id")?.jsonPrimitive?.contentOrNull
                    if (jid != null) pendingJobId = jid
                }
            }
            val payloadStr = result.payload.toString()
            log.info("planning-agent tool={} payload_chars={} summary={}",
                call.name, payloadStr.length, result.summary)
            steps.add(AgentStep(tool = call.name, args = args, resultSummary = result.summary))
            convo.add(LlmAgentMessage(role = "tool", toolCallId = call.id, content = payloadStr))
            if (result.terminalReply != null) earlyTerminalReply = result.terminalReply
        }
        log.info("planning-agent iter={} tool_calls={} steps_total={}", iter + 1, resp.toolCalls.size, steps.size)

        // Pure-lookup short-circuit: when a tool says "my output IS the
        // reply" (terminalReply set), skip the next LLM rendering call. The
        // tool result row is already deterministic, complete, and free of
        // token-budget truncation — sending it back through the LLM would
        // just re-narrate it more expensively.
        if (earlyTerminalReply != null) {
            val (safetyNetReply, finalSteps) = applyOption12SafetyNet(
                caseId, userMessage, earlyTerminalReply!!, steps, locale,
            )
            val finalReply = applyPlanStartedHonestyCheck(safetyNetReply, finalSteps, locale)
            return AgentResponse(
                reply = finalReply,
                steps = finalSteps,
                configUpdate = workingConfig.takeIf { it != initialConfig },
                freshRunId = freshRunId,
                pendingJobId = pendingJobId,
            )
        }
    }

    // Hit iteration cap — return whatever we have plus a guard message.
    log.warn("planning-agent hit MAX_TOOL_ITERATIONS={}", MAX_TOOL_ITERATIONS)
    val capReply = "(I ran out of reasoning steps after $MAX_TOOL_ITERATIONS tool calls. " +
        "Try a more specific request.)"
    val (safetyNetCapReply, finalCapSteps) = applyOption12SafetyNet(
        caseId, userMessage, capReply, steps, locale,
    )
    val finalCapReply = applyPlanStartedHonestyCheck(safetyNetCapReply, finalCapSteps, locale)
    return AgentResponse(
        reply = finalCapReply,
        steps = finalCapSteps,
        configUpdate = workingConfig.takeIf { it != initialConfig },
        freshRunId = freshRunId,
        pendingJobId = pendingJobId,
    )
}

// Post-loop honesty guard: the system prompt instructs the model to never claim a plan is
// running unless it actually called run_plan_async, but smaller local models don't reliably
// follow that instruction (observed on qwen2.5:7b: narrates "Plan started" after calling only
// update_config). Whether run_plan_async fired this turn is a plain fact the backend already
// has in `steps` — enforcing the claim deterministically here is more reliable than depending
// solely on the model to police itself, consistent with keeping deterministic checks in
// Kotlin rather than the prompt (mirrors the Allocation<->Purchasable coupling handling).
private val PLAN_STARTED_CLAIM_RE = Regex(
    "plan (has )?(started|running|been started)|running (the|a) (new )?plan|" +
        "started (the|a) (new )?plan|计划(已|正在)(开始|运行|启动)|正在(运行|生成)计划",
    RegexOption.IGNORE_CASE,
)

private fun applyPlanStartedHonestyCheck(
    reply: String,
    steps: List<AgentStep>,
    locale: String,
): String {
    if (!PLAN_STARTED_CLAIM_RE.containsMatchIn(reply)) return reply
    val actuallyRanPlan = steps.any { it.tool == "run_plan_async" || it.tool == "wait_for_plan" }
    if (actuallyRanPlan) return reply
    return reply + "\n\n" + loc(
        "(Correction: I described running a plan but didn't actually call the plan tool this " +
            "turn — no plan is running. Say \"run it\" / \"generate a plan\" if you'd like me to " +
            "start one now.)",
        "（更正：我描述了正在运行计划，但本轮实际上并未调用计划工具 — 目前没有计划在运行。如需现在" +
            "启动，请告诉我「运行」或「生成计划」。）",
        locale,
    )
}

// Post-loop hook for option-1/2 picks. Earlier revisions deterministically
// deleted the orphan contingent here when the LLM failed to act. That
// conflicts with the current policy ("every contingent stays as history;
// the chosen option's contingent is promoted, others remain as the assessed
// alternatives"). We now leave the cache and contingents in place — the LLM
// is responsible for calling analyze_wo_schedule_impact with the new option
// params and promoting the resulting CPR per step 10b. Kept as a stub so
// callers don't need to change; logs a warning so we can spot if the LLM
// returns without acting.
private fun applyOption12SafetyNet(
    caseId: Int,
    userMessage: String,
    reply: String,
    steps: MutableList<AgentStep>,
    @Suppress("UNUSED_PARAMETER") locale: String,
): Pair<String, List<AgentStep>> {
    val pending = loadPendingMaintenance(caseId) ?: return Pair(reply, steps)
    if (!looksLikeOption1Or2Pick(userMessage)) return Pair(reply, steps)
    log.warn(
        "planning-agent option1/2 pick detected but pending contingent #{} still cached after loop — " +
            "LLM may not have run the new impact + promote. caseId={}",
        pending.contingentPlanRunId, caseId,
    )
    return Pair(reply, steps)
}

private suspend fun dispatchTool(
    caseId: Int,
    call: LlmToolCall,
    args: JsonObject,
    workingConfig: JsonObject,
    locale: String,
): Pair<ToolResult, JsonObject> {
    return when (call.name) {
        "read_current_config" -> Pair(toolReadCurrentConfig(workingConfig, locale), workingConfig)
        "update_config" -> toolUpdateConfig(workingConfig, args, locale)
        "run_plan_async" -> Pair(toolRunPlanAsync(caseId, workingConfig, locale), workingConfig)
        "wait_for_plan" -> Pair(toolWaitForPlan(args, locale), workingConfig)
        "list_plan_runs" -> Pair(toolListPlanRuns(caseId, args, locale), workingConfig)
        "query_kb_runs" -> Pair(toolQueryKbRuns(caseId, args, locale), workingConfig)
        "pareto_kb_runs" -> Pair(toolParetoKbRuns(caseId, args, locale), workingConfig)
        "is_signature_in_kb" -> Pair(toolIsSignatureInKb(caseId, args, locale), workingConfig)
        "suggest_next_batch" -> Pair(toolSuggestNextBatch(caseId, args, locale), workingConfig)
        "get_kpis" -> Pair(toolGetKpis(caseId, args, locale), workingConfig)
        "get_demand_pegging" -> Pair(toolGetDemandPegging(caseId, args, locale), workingConfig)
        "get_product_methods" -> Pair(toolGetProductMethods(caseId, args, locale), workingConfig)
        "get_product_supply" -> Pair(toolGetProductSupply(caseId, args, locale), workingConfig)
        "compare_alternatives" -> Pair(toolCompareAlternatives(caseId, args, locale), workingConfig)
        "find_bom_siblings" -> Pair(toolFindBomSiblings(caseId, args, locale), workingConfig)
        "get_leaf_competition" -> Pair(toolGetLeafCompetition(caseId, args, locale), workingConfig)
        "get_component_allocation_by_demand" -> Pair(toolGetComponentAllocationByDemand(caseId, args, locale), workingConfig)
        "get_critical_materials" -> Pair(toolGetCriticalMaterials(caseId, args, locale), workingConfig)
        "get_critical_raw_allocation" -> Pair(toolGetCriticalRawAllocation(caseId, args, locale), workingConfig)
        "get_bom_tree" -> Pair(toolGetBomTree(caseId, args, locale), workingConfig)
        "find_move_path" -> Pair(toolFindMovePath(caseId, args, locale), workingConfig)
        "trace_demand_to_supply" -> Pair(toolTraceDemandToSupply(caseId, args, locale), workingConfig)
        "compare_runs" -> Pair(toolCompareRuns(caseId, args, locale), workingConfig)
        "explain_method_choice" -> Pair(toolExplainMethodChoice(caseId, args, locale), workingConfig)
        "query_design_docs" -> Pair(toolQueryDesignDocs(args, locale), workingConfig)
        "get_run_config" -> Pair(toolGetRunConfig(caseId, args, locale), workingConfig)
        "recheck_soundness" -> Pair(toolRecheckSoundness(caseId, args, locale), workingConfig)
        "get_soundness_summary" -> Pair(toolGetSoundnessSummary(caseId, args, locale), workingConfig)
        "read_memory" -> Pair(toolReadMemory(caseId, locale), workingConfig)
        "write_memory" -> Pair(toolWriteMemory(caseId, args, locale), workingConfig)
        "list_prod_areas" -> Pair(toolListProdAreas(caseId, args, locale), workingConfig)
        "list_locations" -> Pair(toolListLocations(caseId, args, locale), workingConfig)
        "list_customers" -> Pair(toolListCustomers(caseId, locale), workingConfig)
        "find_wos" -> Pair(toolFindWos(caseId, args, locale), workingConfig)
        "analyze_wo_availability" -> Pair(toolAnalyzeWoAvailability(caseId, args, locale), workingConfig)
        "analyze_wo_schedule_impact" -> Pair(toolAnalyzeWoScheduleImpact(caseId, args, locale), workingConfig)
        "find_earliest_safe_start" -> Pair(toolFindEarliestSafeStart(caseId, args, locale), workingConfig)
        "assess_maintenance_options" -> Pair(toolAssessMaintenanceOptions(caseId, args, locale), workingConfig)
        "commit_option_pick" -> Pair(toolCommitOptionPick(caseId, args, locale), workingConfig)
        "create_wo_schedule_event" -> Pair(toolCreateWoScheduleEvent(caseId, args, locale), workingConfig)
        "promote_plan_run" -> Pair(toolPromotePlanRun(caseId, args, locale), workingConfig)
        "delete_plan_run" -> Pair(toolDeletePlanRun(caseId, args, locale), workingConfig)
        "find_demands_for_product" -> Pair(toolFindDemandsForProduct(caseId, args, locale), workingConfig)
        else -> Pair(toolError("unknown tool: ${call.name}", locale), workingConfig)
    }
}

// ── WO schedule-change / maintenance-window tool handlers ───────────────────

/** Pull the work-orders list out of a baseline plan-run result. Returns null
 *  if no baseline exists or the result is empty. */
@Suppress("UNCHECKED_CAST")
internal fun loadBaselineWorkOrders(caseId: Int, planRunId: Int?): List<Map<String, Any?>>? {
    val result = loadPlanResultFromDb(caseId, planRunId) ?: return null
    return (result["work_orders"] as? List<Map<String, Any?>>)
}

/** True if the WO is a synthetic VirtualProduct_* placeholder (planner-internal,
 *  not an actual shop-floor work order — same filter the UI applies). */
internal fun isVirtualProductWo(wo: Map<String, Any?>): Boolean {
    val pid = wo["product_id"] as? String ?: return false
    return pid.startsWith("VirtualProduct_")
}

internal fun toolListProdAreas(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val wos = loadBaselineWorkOrders(caseId, planRunId)
        ?: return ToolResult(
            summary = loc("no baseline plan run; run plan first", "尚无基线计划，请先生成计划", locale),
            payload = buildJsonObject { put("error", "no_baseline") },
        )
    val byArea = wos.asSequence()
        .filter { !isVirtualProductWo(it) }
        .filter { it["wo_group_id"] != null }
        .groupBy { (it["prod_area"] as? String) ?: "" }
    val rows = byArea.entries
        .filter { it.key.isNotBlank() }
        .map { (area, list) ->
            val gids = list.mapNotNull { it["wo_group_id"] as? String }.toSet()
            val sampleProducts = list.mapNotNull { it["product_id"] as? String }
                .toSet().sorted().take(5)
            Triple(area, gids.size, sampleProducts)
        }
        .sortedByDescending { it.second }
    return ToolResult(
        summary = loc("${rows.size} prod_area(s)", "${rows.size} 个生产区", locale),
        payload = buildJsonObject {
            put("count", rows.size)
            put("prod_areas", buildJsonArray {
                rows.forEach { (area, woCount, samples) ->
                    add(buildJsonObject {
                        put("prod_area", area)
                        put("wo_count", woCount)
                        put("sample_products", buildJsonArray { samples.forEach { add(it) } })
                    })
                }
            })
        },
    )
}

internal fun toolListLocations(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val wos = loadBaselineWorkOrders(caseId, planRunId)
        ?: return ToolResult(
            summary = loc("no baseline plan run; run plan first", "尚无基线计划，请先生成计划", locale),
            payload = buildJsonObject { put("error", "no_baseline") },
        )
    val byLoc = wos.asSequence()
        .filter { !isVirtualProductWo(it) }
        .filter { it["wo_group_id"] != null }
        .groupBy { (it["location_id"] as? String) ?: "" }
    val rows = byLoc.entries
        .filter { it.key.isNotBlank() }
        .map { (loc, list) ->
            val gids = list.mapNotNull { it["wo_group_id"] as? String }.toSet()
            loc to gids.size
        }
        .sortedByDescending { it.second }
    return ToolResult(
        summary = loc("${rows.size} location(s)", "${rows.size} 个地点", locale),
        payload = buildJsonObject {
            put("count", rows.size)
            put("locations", buildJsonArray {
                rows.forEach { (locId, woCount) ->
                    add(buildJsonObject {
                        put("location_id", locId)
                        put("wo_count", woCount)
                    })
                }
            })
        },
    )
}

/** Ground free-form customer_id terms against the case's actual demand data, straight from
 *  Demands (no plan run needed) — mirrors list_prod_areas/list_locations' grounding role for
 *  find_wos(customer_id=…). Cases are independent datasets that can reuse the same id string for
 *  different real-world entities (e.g. a product code in one case, a customer id in another);
 *  this lets the agent confirm which one it's looking at instead of guessing after a 0-match. */
internal fun toolListCustomers(caseId: Int, locale: String): ToolResult {
    val rows = transaction { Demands.selectAll().where { Demands.caseId eq caseId }.toList() }
    val entries = rows.groupBy { it[Demands.customerId] }
        .filter { it.key.isNotBlank() }
        .map { (cid, list) ->
            val sampleProducts = list.map { it[Demands.productId] }.toSet().sorted().take(5)
            Triple(cid, list.size, sampleProducts)
        }
        .sortedByDescending { it.second }
    return ToolResult(
        summary = loc("${entries.size} customer(s)", "${entries.size} 个客户", locale),
        payload = buildJsonObject {
            put("count", entries.size)
            put("customers", buildJsonArray {
                entries.forEach { (cid, demandCount, samples) ->
                    add(buildJsonObject {
                        put("customer_id", cid)
                        put("demand_count", demandCount)
                        put("sample_products", buildJsonArray { samples.forEach { add(it) } })
                    })
                }
            })
        },
    )
}

internal fun toolFindWos(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    // prod_area (single) and prod_areas (array) are OR'd together into one match set — lets
    // "daily WOs for customer Q4R in CB and COC" resolve in one call instead of two.
    val prodAreas: Set<String>? = buildSet {
        args["prod_area"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(it) }
        (args["prod_areas"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?.let { addAll(it) }
    }.takeIf { it.isNotEmpty() }
    val locationId = args["location_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    // customer_id resolves via each WO's demand_id (Demand.customer_id) — WOs have no direct
    // customer column. Reliable 1:1 only when WO consolidation is off (see tool doc string).
    val customerId = args["customer_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val customerDemandIds: Set<String>? = customerId?.let { cid ->
        transaction {
            Demands.selectAll()
                .where { (Demands.caseId eq caseId) and (Demands.customerId eq cid) }
                .map { it[Demands.demandId] }
                .toSet()
        }
    }
    // WOs are stamped with method type "make"/"move"/"purchase" at runtime — never "buy" (that's
    // only the CSV/table name, method_buy.csv/MethodBuys). Normalize so an LLM call using the
    // CSV-derived term still matches real data instead of silently returning zero rows.
    val method = args["method"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?.let { if (it.equals("buy", ignoreCase = true)) "purchase" else it }
    val startAfter = args["start_after"]?.jsonPrimitive?.contentOrNull?.let {
        runCatching { java.time.LocalDate.parse(it) }.getOrNull()
    }
    val startBefore = args["start_before"]?.jsonPrimitive?.contentOrNull?.let {
        runCatching { java.time.LocalDate.parse(it) }.getOrNull()
    }
    val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)

    val wos = loadBaselineWorkOrders(caseId, planRunId)
        ?: return ToolResult(
            summary = loc("no baseline plan run; run plan first", "尚无基线计划，请先生成计划", locale),
            payload = buildJsonObject { put("error", "no_baseline") },
        )

    fun parseStart(wo: Map<String, Any?>): java.time.LocalDate? {
        val s = (wo["start_time"] as? String)?.take(10) ?: return null
        return runCatching { java.time.LocalDate.parse(s) }.getOrNull()
    }

    val filtered = wos.asSequence()
        .filter { !isVirtualProductWo(it) && it["wo_group_id"] != null }
        .filter { prodAreas == null || it["prod_area"] in prodAreas }
        .filter { locationId == null || it["location_id"] == locationId }
        .filter { productId == null || it["product_id"] == productId }
        .filter { customerDemandIds == null || it["demand_id"] in customerDemandIds }
        .filter { method == null || it["method"] == method }
        .filter {
            val s = parseStart(it) ?: return@filter false
            (startAfter == null || s >= startAfter) && (startBefore == null || s <= startBefore)
        }
        .toList()

    // Aggregate one row per wo_group_id: min(start), max(end), sum(qty), lot_count.
    data class Agg(
        var product: String, var location: String, var method: String, var prodArea: String?,
        var demandId: String?, var minStart: String?, var maxEnd: String?, var qty: Double, var lotCount: Int,
    )
    val byGid = LinkedHashMap<String, Agg>()
    for (w in filtered) {
        val gid = w["wo_group_id"] as? String ?: continue
        val ex = byGid[gid]
        val start = w["start_time"] as? String
        val end = w["end_time"] as? String
        val q = (w["quantity"] as? Number)?.toDouble() ?: 0.0
        if (ex == null) {
            byGid[gid] = Agg(
                product = (w["product_id"] as? String) ?: "",
                location = (w["location_id"] as? String) ?: "",
                method = (w["method"] as? String) ?: "",
                prodArea = w["prod_area"] as? String,
                demandId = w["demand_id"] as? String,
                minStart = start,
                maxEnd = end,
                qty = q,
                lotCount = 1,
            )
        } else {
            if (start != null && (ex.minStart == null || start < ex.minStart!!)) ex.minStart = start
            if (end != null && (ex.maxEnd == null || end > ex.maxEnd!!)) ex.maxEnd = end
            ex.qty += q
            ex.lotCount += 1
        }
    }
    val ordered = byGid.entries.sortedBy { it.value.minStart ?: "" }.take(limit)

    return ToolResult(
        summary = loc(
            "${ordered.size} matching WO(s) (${byGid.size} unique gids; ${filtered.size} lots)",
            "匹配 ${ordered.size} 个工单 (${byGid.size} 个唯一 gid，${filtered.size} 条 lot)",
            locale,
        ),
        payload = buildJsonObject {
            put("count", ordered.size)
            put("total_unique_gids", byGid.size)
            put("total_lots", filtered.size)
            put("truncated", byGid.size > ordered.size)
            put("wos", buildJsonArray {
                ordered.forEach { (gid, a) ->
                    add(buildJsonObject {
                        put("wo_group_id", gid)
                        put("product_id", a.product)
                        put("location_id", a.location)
                        put("method", a.method)
                        a.prodArea?.let { put("prod_area", it) }
                        a.demandId?.let { put("demand_id", it) }
                        a.minStart?.let { put("start_time", it) }
                        a.maxEnd?.let { put("end_time", it) }
                        put("quantity", a.qty)
                        put("lot_count", a.lotCount)
                    })
                }
            })
        },
    )
}

/** Parse a JSON list of {bucketStart, woGroupIds} into the kotlinx-serializable
 *  WoScheduleSelector list. Returns null if the shape is wrong. */
internal fun parseSelectorsArg(args: JsonObject): List<WoScheduleSelector>? {
    val arr = args["selectors"] as? JsonArray ?: return null
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val bucketStart = obj["bucketStart"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val gids = (obj["woGroupIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return@mapNotNull null
        WoScheduleSelector(bucketStart = bucketStart, woGroupIds = gids)
    }
}

private fun toolAnalyzeWoAvailability(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val selectors = parseSelectorsArg(args)
    if (selectors.isNullOrEmpty()) return ToolResult(
        summary = loc("invalid selectors", "选择器格式错误", locale),
        payload = buildJsonObject { put("error", "invalid_selectors") },
    )
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val req = WoScheduleImpactRequest(
        selectors = selectors, delayDays = 1, planRunId = planRunId, caseId = caseId, persist = false,
    )
    val baseline = loadBaseline(req) ?: return ToolResult(
        summary = loc("no baseline plan run", "尚无基线计划", locale),
        payload = buildJsonObject { put("error", "no_baseline") },
    )
    val avail = computeAvailability(baseline.workOrders, baseline.peggingTrees, selectors)
    val baselineCommits = extractCommitTimes(baseline.peggingTrees)
    val bottleneckDemands = enrichBottleneckDemands(baseline.caseId, avail.bottlenecks, baselineCommits)
    val bottlenecksJson = Json.encodeToJsonElement(
        kotlinx.serialization.builtins.ListSerializer(WoAvailabilityBottleneck.serializer()),
        avail.bottlenecks,
    )
    val bottleneckDemandsJson = Json.encodeToJsonElement(
        kotlinx.serialization.builtins.ListSerializer(WoImpactedDemand.serializer()),
        bottleneckDemands,
    )
    return ToolResult(
        summary = loc(
            "max safe ${avail.maxFeasibleDays}d (matched ${avail.matchedWoCount} WO, " +
                "${bottleneckDemands.size} bottleneck demand(s))",
            "最大安全延迟 ${avail.maxFeasibleDays} 天 (匹配 ${avail.matchedWoCount} 个工单，" +
                "${bottleneckDemands.size} 个瓶颈需求)",
            locale,
        ),
        payload = buildJsonObject {
            put("max_feasible_days", avail.maxFeasibleDays)
            put("matched_wo_count", avail.matchedWoCount)
            put("bottlenecks", bottlenecksJson)
            put("bottleneck_demands", bottleneckDemandsJson)
            put("plan_run_id", baseline.planRunId)
        },
    )
}

/** Strip the trailing `_VIRTUAL` suffix from synthetic virtual-demand ids for
 *  display — the suffix is planner-internal noise. */
private fun trimVirtualSuffix(id: String): String =
    if (id.endsWith("_VIRTUAL")) id.removeSuffix("_VIRTUAL") else id

/** Render impacted demands as a 6-column markdown table — the same shape the
 *  scheduling-agent uses for its Option C "受影响需求" display. Returns empty
 *  string when impacts is empty, so callers can render "(zero impact)" themselves. */
private fun renderImpactedDemandsBullets(
    impacts: List<WoImpactedDemand>,
    locale: String,
    maxShown: Int = 10,
): String {
    if (impacts.isEmpty()) return ""
    // Sort by daysDelta desc (worst impact first), then priority asc.
    val sorted = impacts.sortedWith(compareByDescending<WoImpactedDemand> { it.daysDelta }
        .thenBy { it.priority ?: Int.MAX_VALUE })
    val shown = sorted.take(maxShown)
    val truncated = sorted.size > maxShown
    return buildString {
        append(loc(
            "| Demand | Product | Customer | Baseline | New | Delay |\n" +
                "|---|---|---|---|---|---:|\n",
            "| 需求 | 产品 | 客户 | 原承诺 | 新承诺 | 延迟 |\n" +
                "|---|---|---|---|---|---:|\n",
            locale,
        ))
        shown.forEach { d ->
            val did = trimVirtualSuffix(d.demandId).replace("|", "\\|")
            val product = (d.description?.takeIf { it.isNotBlank() } ?: d.productId).replace("|", "\\|")
            val cust = d.customerId.replace("|", "\\|")
            val base = (d.baselineCommitTime ?: "—")
            val new = (d.contingentCommitTime ?: "—")
            val delay = if (d.daysDelta > 0) loc("+${d.daysDelta}d", "+${d.daysDelta} 天", locale) else "—"
            append("| $did | $product | $cust | $base | $new | $delay |\n")
        }
        if (truncated) {
            append(loc(
                "\n_…and ${sorted.size - maxShown} more._\n",
                "\n_…还有 ${sorted.size - maxShown} 条。_\n",
                locale,
            ))
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun toolAnalyzeWoScheduleImpact(caseId: Int, args: JsonObject, locale: String): ToolResult {
    // Guard: refuse direct LLM calls to analyze_wo_schedule_impact. Both the
    // Branch A safe path and Branch B option assessment must go through
    // assess_maintenance_options, which generates Option-A/B/C contingents
    // atomically and renders the user-facing options markdown server-side.
    // The internal Kotlin call from toolAssessMaintenanceOptions invokes
    // runWoScheduleImpactInline directly, bypassing this dispatcher — so
    // this guard only blocks agent-loop callers without breaking the bundled
    // path. The "promoted wrong CPR" incident was caused by the agent
    // calling this with persist=true (creating a single CPR for the
    // original window with impact) and then promoting that CPR as if it
    // were the deferred-Option-B plan.
    return ToolResult(
        summary = loc(
            "analyze_wo_schedule_impact disallowed — use assess_maintenance_options instead",
            "已禁用 analyze_wo_schedule_impact — 请改用 assess_maintenance_options",
            locale,
        ),
        payload = buildJsonObject {
            put("error", "use_assess_maintenance_options_instead")
            put("hint", loc(
                "analyze_wo_schedule_impact is not available to the agent loop. For BOTH Branch A (delay ≤ max_feasible_days) AND Branch B (delay > max_feasible_days, multi-option), call assess_maintenance_options instead — it atomically generates Option A/B/C contingent plan runs, renders the user-facing option markdown, and caches the three CPR ids for the next-turn promote. Calling analyze_wo_schedule_impact directly typically produces a single CPR for the original window with impact and then mislabels it as a different option on promote.",
                "analyze_wo_schedule_impact 已不向 agent loop 开放。无论是 Branch A（delay ≤ max_feasible_days）还是 Branch B（delay > max_feasible_days，多选项），都请改用 assess_maintenance_options — 它会原子生成 Option A/B/C 的 contingent plan run，渲染用户可见的方案选择消息，并把三个 CPR ID 缓存好供下一回合 promote。直接调用 analyze_wo_schedule_impact 通常只会生成一个对应原始窗口（含影响）的 CPR，再被错误地当作其他选项 promote。",
                locale,
            ))
            put("redirect_tool", "assess_maintenance_options")
        },
    )
}

@Suppress("UNUSED_PARAMETER")
private fun toolFindEarliestSafeStart(caseId: Int, args: JsonObject, locale: String): ToolResult {
    // Guard: standalone use of find_earliest_safe_start by the planning agent
    // is the antipattern that produced the "promoted wrong CPR" incident.
    // The returned date is not actionable on its own — picking Option B
    // requires a corresponding contingent plan run at the deferred bucket,
    // which only assess_maintenance_options generates atomically. Without
    // that CPR the agent typically falls back to promoting whatever other
    // CPR is in scope (e.g. the original-window-with-impact run) and labels
    // it as the deferred plan, silently corrupting the active schedule.
    // Refuse and redirect to the bundled tool.
    return ToolResult(
        summary = loc(
            "use assess_maintenance_options instead — find_earliest_safe_start is not standalone here",
            "请改用 assess_maintenance_options — 此处不支持单独调用 find_earliest_safe_start",
            locale,
        ),
        payload = buildJsonObject {
            put("error", "use_assess_maintenance_options_instead")
            put("hint", loc(
                "find_earliest_safe_start is not available as a standalone tool to the planning agent. Call assess_maintenance_options with prod_area, bucket_start, and delay_days — it computes alternateStartDate internally, persists Option B's contingent at that date (or marks it as no_op_at_alt_start when the existing plan already accommodates), and returns the CPR ids for all three options atomically. A standalone earliest-safe-start date with no corresponding contingent plan run cannot be promoted.",
                "find_earliest_safe_start 不能由 planning agent 单独调用。请用 prod_area、bucket_start、delay_days 调用 assess_maintenance_options — 它会在内部计算 alternateStartDate，原子地为选项 B 持久化对应的 contingent plan run（或在现有计划已可容纳时标记为 no_op_at_alt_start），并一次性返回所有三个选项的 CPR ID。脱离了对应 contingent plan run 的 earliest-safe-start 日期无法被升格。",
                locale,
            ))
            put("redirect_tool", "assess_maintenance_options")
        },
    )
}

/** Bundled maintenance-options assessment. Runs the entire option pipeline
 *  in one server-side call so the LLM doesn't have to chain 5-7 tools
 *  reliably (gpt-4o-mini drops calls under iteration pressure, which left
 *  the user with only 2 of 3 contingents). All three persist=true
 *  contingents are guaranteed to exist after this returns (modulo
 *  no_safe_start_within_horizon, which legitimately drops Option B). */
private suspend fun toolAssessMaintenanceOptions(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val prodArea = args["prod_area"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            summary = loc("prod_area required", "缺少 prod_area 参数", locale),
            payload = buildJsonObject { put("error", "prod_area required") },
        )
    val bucketStartStr = args["bucket_start"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            summary = loc("bucket_start required (ISO yyyy-MM-dd)", "缺少 bucket_start 参数（ISO 日期）", locale),
            payload = buildJsonObject { put("error", "bucket_start required") },
        )
    val delayDays = args["delay_days"]?.jsonPrimitive?.intOrNull
        ?: return ToolResult(
            summary = loc("delay_days required (int)", "缺少 delay_days 参数（整数）", locale),
            payload = buildJsonObject { put("error", "delay_days required" ) },
        )
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val bucketStartDate = runCatching { java.time.LocalDate.parse(bucketStartStr) }.getOrNull()
        ?: return ToolResult(
            summary = loc("bad bucket_start: $bucketStartStr", "bucket_start 日期格式错误：$bucketStartStr", locale),
            payload = buildJsonObject { put("error", "bad_bucket_start") },
        )

    val wos = loadBaselineWorkOrders(caseId, planRunId)
        ?: return ToolResult(
            summary = loc("no baseline plan run; run plan first", "尚无基线计划，请先生成计划", locale),
            payload = buildJsonObject { put("error", "no_baseline") },
        )

    // Step 1 — collect gids in this prod_area starting on/after bucket_start.
    // Mirrors find_wos's filter without start_before (the impact pipeline shifts
    // every later lot of these gids — no upper bound).
    fun gidsForBucket(bucket: java.time.LocalDate): List<String> =
        wos.asSequence()
            .filter { !isVirtualProductWo(it) && it["wo_group_id"] != null }
            .filter { it["prod_area"] == prodArea }
            .filter {
                val s = (it["start_time"] as? String)?.take(10) ?: return@filter false
                val d = runCatching { java.time.LocalDate.parse(s) }.getOrNull() ?: return@filter false
                d >= bucket
            }
            .mapNotNull { it["wo_group_id"] as? String }
            .toSet()
            .toList()

    val gids = gidsForBucket(bucketStartDate)

    if (gids.isEmpty()) return ToolResult(
        summary = loc(
            "no WOs match prod_area=$prodArea starting on/after $bucketStartStr",
            "未找到 $prodArea 在 $bucketStartStr 及之后的工单",
            locale,
        ),
        payload = buildJsonObject { put("error", "no_matching_wos") },
    )

    val selectors = listOf(WoScheduleSelector(bucketStart = bucketStartStr, woGroupIds = gids))

    // Run impact on a worker thread so multiple calls can be dispatched in
    // parallel via coroutineScope { async { … } }.
    suspend fun runImpact(sels: List<WoScheduleSelector>, dd: Int, label: String):
        Pair<WoScheduleImpactResponse?, String?> = withContext(Dispatchers.IO) {
        val req = WoScheduleImpactRequest(
            selectors = sels, delayDays = dd, planRunId = planRunId, caseId = caseId,
            persist = true, note = "assess_maintenance_options option $label",
        )
        when (val r = runWoScheduleImpactInline(req)) {
            is WoImpactResult.Ok -> Pair<WoScheduleImpactResponse?, String?>(r.response, null)
            is WoImpactResult.Failed -> Pair<WoScheduleImpactResponse?, String?>(null, r.reason)
        }
    }

    // Compute the safety envelope cheaply (sub-millisecond DAG walk) so we
    // know max_feasible_days WITHOUT waiting on a full Option-C planner run.
    val availReq = WoScheduleImpactRequest(
        selectors = selectors, delayDays = delayDays,
        planRunId = planRunId, caseId = caseId, persist = false,
    )
    val baseline = loadBaseline(availReq)
        ?: return ToolResult(
            summary = loc("no baseline plan run; run plan first", "尚无基线计划，请先生成计划", locale),
            payload = buildJsonObject { put("error", "no_baseline") },
        )
    val avail = computeAvailability(baseline.workOrders, baseline.peggingTrees, selectors)
    val maxFeasibleDays = avail.maxFeasibleDays

    // Run Option A + Option C + earliest_safe_start IN PARALLEL. Each impact
    // call is a full planner run (~30 s) — sequentialising 3 of them blew past
    // nginx's gateway timeout. Single coroutineScope so all asyncs run
    // concurrently (a per-async coroutineScope would block until each finishes,
    // serialising the whole pipeline — the bug that caused the earlier 504s).
    // Option B is launched inside the scope once earliest_safe_start resolves.
    data class Assessed(
        val optionA: WoScheduleImpactResponse?,
        val errA: String?,
        val optionB: WoScheduleImpactResponse?,
        val errB: String?,
        val optionC: WoScheduleImpactResponse?,
        val errC: String?,
        val safeResult: EarliestSafeStartResult,
    )
    val assessed = coroutineScope {
        val optionADef = if (maxFeasibleDays > 0) {
            async(Dispatchers.IO) { runImpact(selectors, maxFeasibleDays, "a") }
        } else null
        val optionCDef = async(Dispatchers.IO) { runImpact(selectors, delayDays, "c") }
        val safeDef = async(Dispatchers.IO) {
            val safeReq = EarliestSafeStartRequest(
                caseId = caseId, planRunId = planRunId, prodArea = prodArea,
                delayDays = delayDays, afterDate = bucketStartStr,
            )
            findEarliestSafeStartInline(safeReq)
        }
        val safeResult = safeDef.await()
        val altStart: String? = (safeResult as? EarliestSafeStartResult.Ok)?.response?.earliestSafeStart
        // Re-collect gids relevant to the DEFERRED bucket start. The original
        // gids (collected at the user's bucket_start) may have all finished
        // by the alternate date, in which case the deferred maintenance has
        // nothing to shift in that gid set. The "maintenance window" semantic
        // is "OE production unavailable during this period"; the impact should
        // be evaluated against whichever WOs exist in the prod_area at the
        // deferred date.
        val deferredBucket = altStart?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val deferredGids = deferredBucket?.let { gidsForBucket(it) } ?: emptyList()
        val optionBDef = if (altStart != null && deferredGids.isNotEmpty()) {
            val deferredSelectors = listOf(WoScheduleSelector(bucketStart = altStart, woGroupIds = deferredGids))
            async(Dispatchers.IO) { runImpact(deferredSelectors, delayDays, "b") }
        } else null
        if (altStart != null && deferredGids.isEmpty()) {
            log.info(
                "assess_maintenance_options: alt_start={} has no matching gids in {} — Option B truly N/A",
                altStart, prodArea,
            )
        }
        val (a, eA) = optionADef?.await() ?: Pair(null, "max_feasible_days=0")
        val (c, eC) = optionCDef.await()
        val (b, eB) = optionBDef?.await() ?: Pair(null, "no_safe_start_within_horizon")
        Assessed(a, eA, b, eB, c, eC, safeResult)
    }

    val optionA = assessed.optionA
    val errA = assessed.errA
    val optionB = assessed.optionB
    val errB = assessed.errB
    val optionC = assessed.optionC
    val errC = assessed.errC
    val safeResult = assessed.safeResult

    if (optionC == null) return ToolResult(
        summary = loc(
            "option C impact failed: ${errC ?: "unknown"}",
            "Option C 影响分析失败：${errC ?: "unknown"}",
            locale,
        ),
        payload = buildJsonObject { put("error", "option_c_failed"); put("reason", errC ?: "") },
    )
    if (optionA == null && maxFeasibleDays > 0) {
        log.warn("planning-agent assess_maintenance_options: option A impact failed: {}", errA)
    }
    val alternateStart: String? = (safeResult as? EarliestSafeStartResult.Ok)?.response?.earliestSafeStart
    val bottleneckGid: String? = (safeResult as? EarliestSafeStartResult.Ok)?.response?.bottleneckGid
    val bottleneckEnd: String? = (safeResult as? EarliestSafeStartResult.Ok)?.response?.bottleneckEnd
    log.info(
        "assess_maintenance_options: caseId={} max_feasible={} alt_start={} safeResult={}",
        caseId, maxFeasibleDays, alternateStart,
        safeResult::class.simpleName,
    )
    if (optionB == null && alternateStart != null) {
        log.warn("planning-agent assess_maintenance_options: option B impact failed: {}", errB)
    }

    // Persist the full assessment state in the cache so the option-pick turn
    // can promote the right CPR with the server's auto-cleanup of the other two.
    rememberPendingMaintenance(
        caseId = caseId,
        contingentPlanRunId = optionC.contingentPlanRunId
            ?: 0, // unreachable when persist=true succeeded
        maxFeasibleDays = maxFeasibleDays,
        prodArea = prodArea,
        bucketStart = bucketStartStr,
        originalDelayDays = delayDays,
        woGroupIds = gids,
        alternateStartDate = alternateStart,
        optionACprId = optionA?.contingentPlanRunId,
        optionBCprId = optionB?.contingentPlanRunId,
    )

    val renderedImpacts = renderImpactedDemandsBullets(optionC.impacts, locale)
    val renderedMessage = renderAssessmentMessage(
        prodArea = prodArea,
        bucketStart = bucketStartStr,
        delayDays = delayDays,
        maxFeasibleDays = maxFeasibleDays,
        alternateStart = alternateStart,
        bottleneckGid = bottleneckGid,
        bottleneckEnd = bottleneckEnd,
        optionACprId = optionA?.contingentPlanRunId,
        optionBCprId = optionB?.contingentPlanRunId,
        optionCCprId = optionC.contingentPlanRunId,
        impactedDemandCount = optionC.impactedDemandCount,
        renderedImpacts = renderedImpacts,
        locale = locale,
    )

    val summary = loc(
        "assessed: max_safe=${maxFeasibleDays}d, " +
            "option_a_cpr=${optionA?.contingentPlanRunId ?: "skipped"}, " +
            "option_b_cpr=${optionB?.contingentPlanRunId ?: "n/a"}, " +
            "option_c_cpr=${optionC.contingentPlanRunId}, " +
            "impacted_demands=${optionC.impactedDemandCount}",
        "评估完成：安全边界 ${maxFeasibleDays} 天，" +
            "Option A=${optionA?.contingentPlanRunId ?: "跳过"}，" +
            "Option B=${optionB?.contingentPlanRunId ?: "无"}，" +
            "Option C=${optionC.contingentPlanRunId}，" +
            "影响需求 ${optionC.impactedDemandCount} 条",
        locale,
    )

    return ToolResult(
        summary = summary,
        payload = buildJsonObject {
            put("prod_area", prodArea)
            put("bucket_start", bucketStartStr)
            put("delay_days", delayDays)
            put("max_feasible_days", maxFeasibleDays)
            put("matched_wo_count", optionC.matchedWoCount)
            put("wo_group_ids", buildJsonArray { gids.forEach { add(it) } })
            put("alternate_start_date", alternateStart)
            bottleneckGid?.let { put("bottleneck_gid", it) }
            bottleneckEnd?.let { put("bottleneck_end", it) }
            put("option_a_cpr_id", optionA?.contingentPlanRunId)
            put("option_b_cpr_id", optionB?.contingentPlanRunId)
            put("option_c_cpr_id", optionC.contingentPlanRunId)
            put("impacted_demand_count", optionC.impactedDemandCount)
            put("rendered_impacts", renderedImpacts)
        },
        // Short-circuit the next LLM pass entirely. The options-presentation
        // message has been bitten too many times by the LLM fabricating
        // unavailable options ("Option 2 不适用", "推迟开始至 2024-08-23"
        // when alt_start was null, etc). Render it deterministically here
        // so the user sees exactly what the tool result says.
        terminalReply = renderedMessage,
    )
}

/** Render the assessment message as Markdown — same format the LLM was
 *  meant to produce, but deterministic. Skips entire Option sections when
 *  the corresponding CPR id is null (vs prior LLM-driven version which kept
 *  hallucinating "Option 2 不适用"). */
private fun renderAssessmentMessage(
    prodArea: String,
    bucketStart: String,
    delayDays: Int,
    maxFeasibleDays: Int,
    alternateStart: String?,
    bottleneckGid: String?,
    bottleneckEnd: String?,
    optionACprId: Int?,
    optionBCprId: Int?,
    optionCCprId: Int?,
    impactedDemandCount: Int,
    renderedImpacts: String,
    locale: String,
): String = buildString {
    // Header
    append(loc(
        "## Maintenance window assessment — $prodArea from $bucketStart for $delayDays days\n\n",
        "## 维护窗口评估 — $prodArea 自 $bucketStart 起 $delayDays 天\n\n",
        locale,
    ))
    append(loc(
        "Maximum safe shutdown is **$maxFeasibleDays days**; your requested " +
            "$delayDays-day window exceeds it and would shift **$impactedDemandCount " +
            "demand(s)**.\n\n",
        "最大安全关闭天数为 **$maxFeasibleDays 天**；您请求的 $delayDays 天" +
            "超出安全边界，会推迟 **$impactedDemandCount 条需求**的承诺时间。\n\n",
        locale,
    ))
    // Impacted demands table (just the rendered_impacts block — already a markdown table)
    if (renderedImpacts.isNotBlank()) {
        append(loc("### Impacted demands\n\n", "### 受影响需求\n\n", locale))
        append(renderedImpacts.trim())
        append("\n\n")
    }
    // Options — labels are A/B/C (letters only, no numbers). Skip omitted
    // options; do NOT relabel C as B. Option B has three states:
    //   - ok: optionBCprId != null AND alternateStart != null → standard
    //         "deferred + CPR" wording.
    //   - no_op_at_alt_start: optionBCprId == null AND alternateStart != null
    //         → ESS found a date but no WO actually shifts there. Render
    //         positively: existing plan already accommodates; no CPR needed.
    //         User can still pick it (cleanup deletes A+C, no promote).
    //   - no_safe_start_within_horizon: alternateStart == null → omit
    //         Option B entirely.
    append(loc("### Options\n", "### 备选方案\n", locale))
    if (optionACprId != null) {
        append(loc(
            "\n**Option A** — Shorten the window to $maxFeasibleDays days. " +
                "Demand commits unchanged; WO schedule amended within slack. " +
                "New plan run = **#$optionACprId**.\n",
            "\n**选项 A** — 缩短关闭时间至 $maxFeasibleDays 天。需求承诺保持不变，" +
                "WO 排程在可用余量内调整。生成的新计划运行 = **#$optionACprId**。\n",
            locale,
        ))
    }
    if (optionBCprId != null && alternateStart != null) {
        // "ok" — deferred Option B with a real CPR
        val bottleneckHint = if (bottleneckGid != null && bottleneckEnd != null) {
            loc(
                " (gated by bottleneck WO `$bottleneckGid` finishing on $bottleneckEnd)",
                "（瓶颈工单 `$bottleneckGid` 在 $bottleneckEnd 完成）",
                locale,
            )
        } else ""
        append(loc(
            "\n**Option B** — Defer the start to $alternateStart$bottleneckHint. " +
                "Demand commits unchanged; WO schedule amended for the deferred window. " +
                "New plan run = **#$optionBCprId**.\n",
            "\n**选项 B** — 推迟开始日期至 $alternateStart$bottleneckHint。" +
                "需求承诺保持不变，WO 排程相应调整。生成的新计划运行 = **#$optionBCprId**。\n",
            locale,
        ))
    } else if (optionBCprId == null && alternateStart != null) {
        // "no_op_at_alt_start" — alt date exists, existing plan accommodates
        // the maintenance there. Frame positively. No CPR to mention.
        append(loc(
            "\n**Option B** — Defer the start to $alternateStart " +
                "($delayDays days, current plan already accommodates). " +
                "$prodArea has no scheduled production in this window, so the " +
                "maintenance can run as-is. The existing plan supports it; no " +
                "adjustment needed.\n" +
                "> To pick this: reply *\"Shut down $prodArea for $delayDays days from $alternateStart\"*\n",
            "\n**选项 B** — 推迟开始日期至 $alternateStart（原 $delayDays 天，" +
                "现行计划已可容纳）。此时段 $prodArea 区已无计划生产，" +
                "维护事件可直接在此窗口进行，无需调整计划。\n" +
                "> 如选此项：回复 *\"在 $alternateStart 停 $prodArea $delayDays 天\"*\n",
            locale,
        ))
    }
    // alternateStart == null → Option B omitted entirely
    if (optionCCprId != null) {
        append(loc(
            "\n**Option C** — Accept the impact and amend the plan. Demand commits " +
                "shift as listed in the impacted demands table above. " +
                "New plan run = **#$optionCCprId**.\n",
            "\n**选项 C** — 接受影响并修改计划。上述受影响需求按表中所示的天数推迟。" +
                "生成的新计划运行 = **#$optionCCprId**。\n",
            locale,
        ))
    }
    val available = buildList {
        if (optionACprId != null) add("A")
        if (optionBCprId != null || alternateStart != null) add("B")
        if (optionCCprId != null) add("C")
    }
    val availableEn = when (available.size) {
        1 -> "**${available[0]}**"
        2 -> "**${available[0]}** or **${available[1]}**"
        3 -> "**${available[0]}**, **${available[1]}**, or **${available[2]}**"
        else -> ""
    }
    val availableZh = available.joinToString("、") { "**$it**" }
    append(loc(
        "\nReply with $availableEn to confirm which option to promote.\n",
        "\n请回复 $availableZh 以确认要升格的方案。\n",
        locale,
    ))
}

private fun toolCreateWoScheduleEvent(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val selectors = parseSelectorsArg(args)
    if (selectors.isNullOrEmpty()) return ToolResult(
        summary = loc("invalid selectors", "选择器格式错误", locale),
        payload = buildJsonObject { put("error", "invalid_selectors") },
    )
    val delayDays = args["delay_days"]?.jsonPrimitive?.intOrNull
    val delayToDate = args["delay_to_date"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val note = args["note"]?.jsonPrimitive?.contentOrNull?.trim()
    if (delayDays == null && delayToDate == null) return ToolResult(
        summary = loc("delay_days or delay_to_date required", "需指定 delay_days 或 delay_to_date", locale),
        payload = buildJsonObject { put("error", "no_delay") },
    )
    val selectorsJsonStr = Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(WoScheduleSelector.serializer()),
        selectors,
    )
    val newId = transaction {
        WoScheduleEvents.insert {
            it[WoScheduleEvents.caseId] = caseId
            it[WoScheduleEvents.selectorsJson] = selectorsJsonStr
            it[WoScheduleEvents.delayDays] = delayDays
            it[WoScheduleEvents.delayToDate] = delayToDate
            it[WoScheduleEvents.note] = note
        }[WoScheduleEvents.id]
    }
    return ToolResult(
        summary = loc("event #$newId created", "已创建事件 #$newId", locale),
        payload = buildJsonObject {
            put("event_id", newId)
            put("case_id", caseId)
        },
    )
}

/** Deterministic handler for the resume turn of the maintenance-window flow.
 *  Reads pending_maintenance_decision from cache, dispatches the right action
 *  for the user's option pick (A/B/C), and returns a localized confirmation.
 *
 *  Why this exists: the LLM kept conflating CPR ids on the resume turn
 *  (e.g. promoting option_c_cpr_id when the user picked B in
 *  no_op_at_alt_start state), silently corrupting the active schedule. Moving
 *  the dispatch server-side makes the resume turn idempotent and impossible
 *  to mis-promote regardless of what the LLM tries to say.
 *
 *  Dispatch by option_letter:
 *    A → promote option_a_cpr_id, delete option_b_cpr_id (if non-null) and
 *        option_c_cpr_id.
 *    B + option_b_cpr_id non-null (ok) → promote option_b_cpr_id, delete
 *        option_a_cpr_id and option_c_cpr_id.
 *    B + option_b_cpr_id null + alternateStartDate non-null (no_op_at_alt_start)
 *        → delete option_a_cpr_id and option_c_cpr_id; do NOT promote
 *        (existing plan already accommodates the deferred maintenance).
 *    B + both null (no_safe_start_within_horizon) → return error "Option B
 *        was not available".
 *    C → promote option_c_cpr_id, delete option_a_cpr_id and option_b_cpr_id
 *        (if non-null).
 */
private fun toolCommitOptionPick(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val rawLetter = args["option_letter"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
        ?: return ToolResult(
            summary = loc("option_letter required (A/B/C)", "需指定 option_letter (A/B/C)", locale),
            payload = buildJsonObject { put("error", "missing_option_letter") },
        )
    if (rawLetter !in setOf("A", "B", "C")) return ToolResult(
        summary = loc("option_letter must be A, B, or C", "option_letter 必须为 A、B 或 C", locale),
        payload = buildJsonObject { put("error", "invalid_option_letter") },
    )
    val pending = loadPendingMaintenance(caseId) ?: return ToolResult(
        summary = loc(
            "no pending maintenance decision — ask user to re-run assess_maintenance_options",
            "无待处理的维护决策 — 请用户重新调用 assess_maintenance_options",
            locale,
        ),
        payload = buildJsonObject { put("error", "no_pending_maintenance") },
    )

    val optionACpr = pending.optionACprId
    val optionBCpr = pending.optionBCprId
    val optionCCpr = pending.contingentPlanRunId  // legacy name; this is option C
    val altStart = pending.alternateStartDate
    val prodArea = pending.prodArea ?: "?"
    val originalDelay = pending.originalDelayDays

    // Helper: promote + delete-unchosen for a single picked CPR.
    fun promoteAndCleanup(pickedCpr: Int, unchosen: List<Int>, picked: String): ToolResult {
        // Promote the picked CPR
        val outcome: String = transaction {
            val row = PlanRuns.selectAll()
                .where { (PlanRuns.id eq pickedCpr) and (PlanRuns.caseId eq caseId) }
                .firstOrNull() ?: return@transaction "not_found"
            val status = row[PlanRuns.status]
            when {
                status == "success" -> "already_promoted"
                status == "contingent" -> {
                    PlanRuns.update({ PlanRuns.id eq pickedCpr }) { it[PlanRuns.status] = "success" }
                    emitPlanRunEvent(caseId, pickedCpr, "promoted", buildJsonObject {
                        put("from_status", JsonPrimitive("contingent"))
                        put("to_status", JsonPrimitive("success"))
                        put("source", JsonPrimitive("planning_agent.commit_option_pick"))
                        put("option", JsonPrimitive(picked))
                    })
                    "promoted"
                }
                else -> "wrong_status:$status"
            }
        }
        if (outcome == "not_found") return ToolResult(
            summary = loc(
                "Option $picked's plan run #$pickedCpr was not found — cache may be stale",
                "找不到选项 $picked 对应的计划运行 #$pickedCpr — 缓存可能已过期",
                locale,
            ),
            payload = buildJsonObject { put("error", "plan_run_not_found"); put("plan_run_id", pickedCpr) },
        )
        if (outcome.startsWith("wrong_status:")) return ToolResult(
            summary = loc(
                "Option $picked's plan run #$pickedCpr has unexpected status (${outcome.removePrefix("wrong_status:")}) — cannot promote",
                "选项 $picked 的计划运行 #$pickedCpr 状态异常 (${outcome.removePrefix("wrong_status:")}) — 无法升格",
                locale,
            ),
            payload = buildJsonObject { put("error", outcome) },
        )
        // Delete unchosen
        val deleted = mutableListOf<Int>()
        for (other in unchosen) {
            val delResult = toolDeletePlanRun(caseId, buildJsonObject { put("plan_run_id", other) }, locale)
            val payloadObj = delResult.payload as? JsonObject
            if (payloadObj?.get("deleted")?.jsonPrimitive?.booleanOrNull == true) deleted.add(other)
            log.info(
                "commit_option_pick: caseId={} picked={} promoted={} deleted_other={} result={}",
                caseId, picked, pickedCpr, other, delResult.summary,
            )
        }
        clearPendingMaintenance(caseId)
        val cleanupSuffix = if (deleted.isNotEmpty())
            loc(
                "; deleted unchosen contingents " + deleted.joinToString(", ") { "#$it" },
                "；已删除未选用的临时计划 " + deleted.joinToString("、") { "#$it" },
                locale,
            )
        else ""
        return ToolResult(
            summary = loc(
                "Option $picked committed: plan run #$pickedCpr promoted$cleanupSuffix",
                "选项 $picked 已确认：计划运行 #$pickedCpr 已升格$cleanupSuffix",
                locale,
            ),
            payload = buildJsonObject {
                put("picked_option", rawLetter)
                put("promoted_plan_run_id", pickedCpr)
                put("deleted_unchosen", buildJsonArray { deleted.forEach { add(it) } })
                if (outcome == "already_promoted") put("noop", true)
            },
            // Render the confirmation deterministically so the LLM can't
            // rephrase the wrong narrative around it.
            terminalReply = loc(
                "✓ Option $picked committed — plan run #$pickedCpr is now the active plan.",
                "✓ 选项 $picked 已确认 — 计划运行 #$pickedCpr 现为正式计划。",
                locale,
            ),
        )
    }

    return when (rawLetter) {
        "A" -> {
            if (optionACpr == null) return ToolResult(
                summary = loc("Option A was not available", "选项 A 不可用", locale),
                payload = buildJsonObject { put("error", "option_a_not_available") },
            )
            val unchosen = listOfNotNull(optionBCpr, optionCCpr).filter { it != optionACpr }.distinct()
            promoteAndCleanup(optionACpr, unchosen, "A")
        }
        "B" -> {
            when {
                optionBCpr != null -> {
                    val unchosen = listOfNotNull(optionACpr, optionCCpr).filter { it != optionBCpr }.distinct()
                    promoteAndCleanup(optionBCpr, unchosen, "B")
                }
                altStart != null -> {
                    // no_op_at_alt_start: existing plan already accommodates.
                    // Delete the two unchosen contingents and confirm — DO NOT
                    // promote anything; the baseline plan is unchanged.
                    val toDelete = listOfNotNull(optionACpr, optionCCpr).distinct()
                    val deleted = mutableListOf<Int>()
                    for (other in toDelete) {
                        val delResult = toolDeletePlanRun(caseId, buildJsonObject { put("plan_run_id", other) }, locale)
                        val payloadObj = delResult.payload as? JsonObject
                        if (payloadObj?.get("deleted")?.jsonPrimitive?.booleanOrNull == true) deleted.add(other)
                    }
                    clearPendingMaintenance(caseId)
                    val days = originalDelay?.toString() ?: "?"
                    ToolResult(
                        summary = loc(
                            "Option B confirmed (no_op_at_alt_start): no plan change needed; deleted unchosen contingents " +
                                deleted.joinToString(", ") { "#$it" },
                            "选项 B 已确认 (no_op_at_alt_start): 无需变更计划；已删除未选用的临时计划 " +
                                deleted.joinToString("、") { "#$it" },
                            locale,
                        ),
                        payload = buildJsonObject {
                            put("picked_option", "B")
                            put("status", "no_op_at_alt_start")
                            put("promoted_plan_run_id", JsonNull)
                            put("deleted_unchosen", buildJsonArray { deleted.forEach { add(it) } })
                        },
                        terminalReply = loc(
                            "✓ Option B confirmed — maintenance window scheduled for **$prodArea** from **$altStart** for **$days days**. The existing plan already accommodates this window; no plan change is needed and no new plan run was generated.",
                            "✓ 选项 B 已确认 — **$prodArea** 维护窗口安排于 **$altStart** 起 **$days 天**。现行计划已可容纳此窗口，无需变更计划，也未生成新的计划运行。",
                            locale,
                        ),
                    )
                }
                else -> ToolResult(
                    summary = loc(
                        "Option B was not available (no safe alternate start within horizon)",
                        "选项 B 不可用（视野内无安全的替代开始日期）",
                        locale,
                    ),
                    payload = buildJsonObject { put("error", "option_b_not_available") },
                )
            }
        }
        "C" -> {
            // contingentPlanRunId (= optionCCpr) is non-nullable in
            // PendingMaintenanceDecision — there's always a C contingent
            // whenever there's a pending decision at all.
            val unchosen = listOfNotNull(optionACpr, optionBCpr).filter { it != optionCCpr }.distinct()
            promoteAndCleanup(optionCCpr, unchosen, "C")
        }
        else -> ToolResult(
            summary = loc("unreachable", "不可达", locale),
            payload = buildJsonObject { put("error", "unreachable") },
        )
    }
}

private fun toolPromotePlanRun(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
        ?: return ToolResult(
            summary = loc("plan_run_id required", "需指定 plan_run_id", locale),
            payload = buildJsonObject { put("error", "missing_plan_run_id") },
        )
    // If the agent passed the id of the pending maintenance contingent (or
    // promotes any run after a maintenance offer), clear the cache so the
    // <pending_maintenance_decision> block disappears from the next turn's
    // prompt and we don't re-offer / re-trigger a promotion. We only clear
    // when the id matches the cached one — promoting an unrelated run shouldn't
    // wipe a still-pending maintenance offer.
    val pending = loadPendingMaintenance(caseId)
    val outcome: String = transaction {
        val row = PlanRuns.selectAll()
            .where { (PlanRuns.id eq planRunId) and (PlanRuns.caseId eq caseId) }
            .firstOrNull() ?: return@transaction "not_found"
        val status = row[PlanRuns.status]
        val supersededBy = row[PlanRuns.supersededByPlanRunId]
        when {
            status == "success" -> "already_promoted"
            status == "contingent" && supersededBy != null -> "superseded:$supersededBy"
            status == "contingent" -> {
                PlanRuns.update({ PlanRuns.id eq planRunId }) { it[PlanRuns.status] = "success" }
                emitPlanRunEvent(caseId, planRunId, "promoted", buildJsonObject {
                    put("from_status", JsonPrimitive("contingent"))
                    put("to_status", JsonPrimitive("success"))
                    put("source", JsonPrimitive("planning_agent"))
                })
                "promoted"
            }
            else -> "wrong_status:$status"
        }
    }
    // Auto-cleanup of the unchosen maintenance contingents. When the promoted
    // id matches ANY of the cached option CPRs (A/B/C), delete the other two
    // cached ones — they were assessed alternatives the user didn't pick, and
    // leaving them as 'contingent' status clutters the run-history list.
    val autoDeleted = mutableListOf<Int>()
    if (outcome == "promoted" && pending != null) {
        val cachedCprs = listOfNotNull(
            pending.optionACprId,
            pending.optionBCprId,
            pending.contingentPlanRunId,
        ).distinct()
        if (cachedCprs.contains(planRunId)) {
            val others = cachedCprs.filter { it != planRunId }
            for (other in others) {
                val args2 = buildJsonObject { put("plan_run_id", other) }
                val delResult = toolDeletePlanRun(caseId, args2, locale)
                val payloadObj = delResult.payload as? JsonObject
                if (payloadObj?.get("deleted")?.jsonPrimitive?.booleanOrNull == true) {
                    autoDeleted.add(other)
                }
                log.info(
                    "planning-agent auto-delete unchosen option contingent: caseId={} promoted={} other={} result={}",
                    caseId, planRunId, other, delResult.summary,
                )
            }
            clearPendingMaintenance(caseId)
        }
    }
    if (outcome == "promoted" && pending?.contingentPlanRunId == planRunId && autoDeleted.isEmpty()) {
        // Legacy path: no option CPR cluster (single contingent case).
        clearPendingMaintenance(caseId)
    }
    return when {
        outcome == "promoted" -> {
            val cleanupSuffix = if (autoDeleted.isNotEmpty())
                loc(
                    "; deleted unchosen contingents " + autoDeleted.joinToString(", ") { "#$it" },
                    "；已删除未选用的临时计划 " + autoDeleted.joinToString("、") { "#$it" },
                    locale,
                )
            else ""
            ToolResult(
                summary = loc(
                    "plan run #$planRunId promoted to success" + cleanupSuffix,
                    "计划运行 #$planRunId 已升格为正式" + cleanupSuffix,
                    locale,
                ),
                payload = buildJsonObject {
                    put("plan_run_id", planRunId)
                    put("new_status", "success")
                    if (autoDeleted.isNotEmpty()) {
                        put("deleted_unchosen", buildJsonArray { autoDeleted.forEach { add(it) } })
                    }
                },
            )
        }
        outcome == "already_promoted" -> ToolResult(
            summary = loc("plan run #$planRunId is already at success (no-op)", "计划运行 #$planRunId 已是正式状态 (无需操作)", locale),
            payload = buildJsonObject {
                put("plan_run_id", planRunId)
                put("new_status", "success")
                put("noop", true)
            },
        )
        outcome.startsWith("superseded:") -> {
            val later = outcome.removePrefix("superseded:")
            ToolResult(
                summary = loc(
                    "plan run #$planRunId is superseded by #$later — promote that one instead",
                    "计划运行 #$planRunId 已被 #$later 取代，请升格后者",
                    locale,
                ),
                payload = buildJsonObject {
                    put("error", "superseded")
                    put("superseded_by_plan_run_id", later.toIntOrNull())
                },
            )
        }
        outcome == "not_found" -> ToolResult(
            summary = loc("plan run #$planRunId not found for case $caseId", "案例 $caseId 找不到计划运行 #$planRunId", locale),
            payload = buildJsonObject { put("error", "not_found") },
        )
        outcome.startsWith("wrong_status:") -> {
            val st = outcome.removePrefix("wrong_status:")
            ToolResult(
                summary = loc(
                    "plan run #$planRunId has status='$st' — only contingent runs can be promoted",
                    "计划运行 #$planRunId 状态为 '$st'，仅 contingent 状态可升格",
                    locale,
                ),
                payload = buildJsonObject {
                    put("error", "wrong_status")
                    put("status", st)
                },
            )
        }
        else -> ToolResult(
            summary = loc("unexpected outcome: $outcome", "意外结果：$outcome", locale),
            payload = buildJsonObject { put("error", outcome) },
        )
    }
}

private fun toolDeletePlanRun(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
        ?: return ToolResult(
            summary = loc("plan_run_id required", "需指定 plan_run_id", locale),
            payload = buildJsonObject { put("error", "missing_plan_run_id") },
        )
    val outcome: String = transaction {
        val row = PlanRuns.selectAll()
            .where { (PlanRuns.id eq planRunId) and (PlanRuns.caseId eq caseId) }
            .firstOrNull() ?: return@transaction "not_found"
        val status = row[PlanRuns.status]
        // Whitelist deletable statuses. Refuse "success" (active baseline) and
        // "running" (job in flight) outright.
        when (status) {
            "contingent", "ready", "failed" -> {
                // Don't emit plan_run_event(deleted) — that table has a CASCADE
                // FK to plan_run, so any audit row would be wiped along with
                // the plan_run row anyway. Application log is the audit trail.
                com.allocator.services.KbStore.markPlanRunDeleted(planRunId)
                PlanRuns.deleteWhere {
                    (PlanRuns.id eq planRunId) and (PlanRuns.caseId eq caseId)
                }
                log.info(
                    "plan-run deleted via planning_agent: caseId={} planRunId={} prior_status={}",
                    caseId, planRunId, status,
                )
                "deleted:$status"
            }
            else -> "wrong_status:$status"
        }
    }
    // Clear pending-maintenance cache if this contingent was the one offered.
    val pending = loadPendingMaintenance(caseId)
    if (outcome.startsWith("deleted:") && pending?.contingentPlanRunId == planRunId) {
        clearPendingMaintenance(caseId)
    }
    return when {
        outcome.startsWith("deleted:") -> {
            val priorStatus = outcome.removePrefix("deleted:")
            ToolResult(
                summary = loc(
                    "plan run #$planRunId (status=$priorStatus) deleted",
                    "计划运行 #$planRunId（状态 $priorStatus）已删除",
                    locale,
                ),
                payload = buildJsonObject {
                    put("plan_run_id", planRunId)
                    put("prior_status", priorStatus)
                    put("deleted", true)
                },
            )
        }
        outcome == "not_found" -> ToolResult(
            summary = loc("plan run #$planRunId not found for case $caseId", "案例 $caseId 找不到计划运行 #$planRunId", locale),
            payload = buildJsonObject { put("error", "not_found") },
        )
        outcome.startsWith("wrong_status:") -> {
            val st = outcome.removePrefix("wrong_status:")
            ToolResult(
                summary = loc(
                    "plan run #$planRunId has status='$st' — refused (only contingent/ready/failed can be deleted)",
                    "计划运行 #$planRunId 状态为 '$st'，无法删除（仅 contingent/ready/failed 可删）",
                    locale,
                ),
                payload = buildJsonObject {
                    put("error", "wrong_status")
                    put("status", st)
                },
            )
        }
        else -> ToolResult(
            summary = loc("unexpected outcome: $outcome", "意外结果：$outcome", locale),
            payload = buildJsonObject { put("error", outcome) },
        )
    }
}

private fun toolFindDemandsForProduct(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return ToolResult(
            summary = loc("product_id is required", "缺少 product_id 参数", locale),
            payload = buildJsonObject { put("error", "product_id is required") },
        )
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 50).coerceIn(1, 500)

    val result = loadPlanResultWithPeggingFromDb(caseId, planRunId)
        ?: return ToolResult(
            summary = loc("no baseline plan run; run plan first", "尚无基线计划，请先生成计划", locale),
            payload = buildJsonObject { put("error", "no_baseline") },
        )

    // Walk planning_pegging — the demand-centric pegging trees. This catches
    // BOTH (a) `productId` appearing as a make/sub-make WO inside the chain
    // AND (b) leaf-component consumption (supply/purchase nodes carrying
    // `product_id == productId` directly). The old work-orders-only filter
    // missed case (b) — leaf materials that the demand consumed from
    // inventory or via purchase had no make-WO produced for them.
    @Suppress("UNCHECKED_CAST")
    val planningPegging = (result["planning_pegging"] as? List<Map<String, Any?>>) ?: emptyList()
    val matchingDemandIds = mutableSetOf<String>()
    for (entry in planningPegging) {
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        if (!peggingTreeContainsProduct(tree, productId)) continue
        val entryDemandId = entry["demand_id"] as? String
        if (!entryDemandId.isNullOrBlank()) {
            matchingDemandIds.add(entryDemandId)
        } else {
            // Multi-demand consolidated entry (no single demand_id) — credit
            // every demand that shares this consolidation group. Matches the
            // Supply View's `peggedDemandCount` behavior.
            @Suppress("UNCHECKED_CAST")
            val members = entry["consolidated_demand_ids"] as? List<String> ?: emptyList()
            for (m in members) if (m.isNotBlank()) matchingDemandIds.add(m)
        }
    }

    if (matchingDemandIds.isEmpty()) {
        return ToolResult(
            summary = loc(
                "no demands found whose chain uses '$productId'",
                "未找到使用物料 '$productId' 的用户需求",
                locale,
            ),
            payload = buildJsonObject {
                put("product_id", productId)
                put("count", 0)
                put("demands", buildJsonArray { })
            },
        )
    }

    // Look up demand details from the Demands table.
    val rows = transaction {
        Demands.selectAll()
            .where { (Demands.caseId eq caseId) and (Demands.demandId inList matchingDemandIds) }
            .map { row ->
                mapOf(
                    "demand_id" to row[Demands.demandId],
                    "product_id" to row[Demands.productId],
                    "customer_id" to row[Demands.customerId],
                    "location_id" to row[Demands.locationId],
                    "request_due_time" to row[Demands.requestDueTime],
                    "quantity" to row[Demands.quantity],
                    "priority" to row[Demands.priority],
                    "description" to row[Demands.description],
                )
            }
            .sortedWith(compareBy(
                { (it["priority"] as? Int) ?: Int.MAX_VALUE },
                { it["request_due_time"] as? String ?: "" },
            ))
    }
    val truncated = rows.size > limit
    val ordered = if (truncated) rows.take(limit) else rows

    // Pre-render the reply in Kotlin and return it as the terminal reply, so
    // the agent loop can skip the final LLM rendering pass. Style matches the
    // scheduling-agent's option-C "受影响需求" table — a clean 6-column
    // markdown table the chat renderer aligns nicely on screen.
    val rendered = buildString {
        append(loc(
            "**${rows.size} demand(s) use material `$productId`**",
            "**${rows.size} 条用户需求使用物料 `$productId`**",
            locale,
        ))
        if (truncated) {
            append(loc(" — showing first $limit:\n\n", " — 显示前 $limit 条：\n\n", locale))
        } else {
            append(loc(":\n\n", "：\n\n", locale))
        }
        append(loc(
            "| Demand | Product | Customer | Due | Qty | Prio |\n" +
                "|---|---|---|---|---:|---:|\n",
            "| 需求 | 产品 | 客户 | 到期 | 数量 | 优先级 |\n" +
                "|---|---|---|---|---:|---:|\n",
            locale,
        ))
        ordered.forEach { d ->
            val did = trimVirtualSuffix((d["demand_id"] as? String).orEmpty()).replace("|", "\\|")
            val pid = (d["product_id"] as? String).orEmpty()
            val desc = (d["description"] as? String).orEmpty()
            val product = (desc.takeIf { it.isNotBlank() } ?: pid).replace("|", "\\|")
            val cid = (d["customer_id"] as? String).orEmpty().replace("|", "\\|")
            val due = (d["request_due_time"] as? String).orEmpty()
            val qty = ((d["quantity"] as? Number)?.toDouble() ?: 0.0)
            val qtyStr = if (qty == qty.toLong().toDouble()) qty.toLong().toString()
                else String.format("%,.2f", qty)
            val prio = (d["priority"] as? Int)?.toString() ?: ""
            append("| $did | $product | $cid | $due | $qtyStr | $prio |\n")
        }
    }

    return ToolResult(
        summary = loc(
            "${rows.size} demand(s) use product '$productId'" + if (truncated) " (showing first $limit)" else "",
            "${rows.size} 条用户需求使用物料 '$productId'" + if (truncated) "（显示前 $limit 条）" else "",
            locale,
        ),
        payload = buildJsonObject {
            put("product_id", productId)
            put("count", rows.size)
            put("truncated", truncated)
            put("demands", buildJsonArray {
                ordered.forEach { d ->
                    add(buildJsonObject {
                        put("demand_id", d["demand_id"] as? String)
                        put("product_id", d["product_id"] as? String)
                        put("customer_id", d["customer_id"] as? String)
                        (d["location_id"] as? String)?.let { put("location_id", it) }
                        (d["request_due_time"] as? String)?.let { put("request_due_time", it) }
                        put("quantity", (d["quantity"] as? Number)?.toDouble() ?: 0.0)
                        (d["priority"] as? Int)?.let { put("priority", it) }
                        (d["description"] as? String)?.let { put("description", it) }
                    })
                }
            })
        },
        terminalReply = rendered,
    )
}

// ── Route ────────────────────────────────────────────────────────────────────

fun Routing.planningAgentRoutes() {
    post("/cases/{caseId}/planning-agent") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")

        val exists = transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
        }
        if (exists == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "case not found"))
            return@post
        }

        val req = call.receive<AgentRequest>()
        val userMessage = req.message?.takeIf { it.isNotBlank() }
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "message is required"))

        val initialConfig = req.currentConfig ?: JsonObject(emptyMap())
        val history = req.history ?: emptyList()

        val response = try {
            runAgentLoop(caseId, userMessage, initialConfig, history, req.viewingRunId)
        } catch (e: LlmNotConfiguredException) {
            log.warn("planning-agent: LLM not configured: {}", e.message)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "Planning agent requires NANOGPT_API_KEY to be configured.")
            )
            return@post
        } catch (e: Exception) {
            log.warn("planning-agent: loop failed", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.message ?: "agent loop failed"))
            )
            return@post
        }

        call.respond(response)
    }
}

