package com.allocator.api

import com.allocator.AgentMemory
import com.allocator.Boms
import com.allocator.Cases
import com.allocator.Demands
import com.allocator.ManualOverrides
import com.allocator.MethodBuys
import com.allocator.MethodMakes
import com.allocator.MethodMoves
import com.allocator.PlanRuns
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
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID

private val log = LoggerFactory.getLogger("com.allocator.PlanningAgentRoutes")

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
  1. Configure planning parameters from the user's natural-language goals.
  2. Run plans on the user's behalf.
  3. Explain the planner's decisions when asked.

Always classify the user's primary intent into one of three buckets (multiple may apply):

  PURCHASE intent — user wants to see how current supplies fulfill demand without buying more.
    Typical phrases: "analyze material bottlenecks", "what can we deliver today", "before we buy".
    Action: call update_config with { "purchase_allowed": false }, then run_plan_async, then
    summarize fill_rate_pct and the top short-supplied products from get_kpis.

  DEMAND intent — user wants fairness, no starvation, equal treatment across demands.
    Typical phrases: "treat all demands fairly", "no one starved", "公平对待".
    Action: call update_config with
      { "consolidation": { "enabled": true, "allocation_mode": "fair" },
        "method_selection": { "max_methods": 2, "mode": "preference" } }
    and explain why this combination delivers fairness.

  SUPPLY intent — user wants to maximize delivery / use up inventory / minimize purchase.
    Typical phrases: "maximize delivery", "earliest commit", "use existing stock", "least purchase".
    Action: call update_config with
      { "method_selection": { "mode": "elaborate", "score_weights": { ... } } }
    where score_weights matches the user's target axis (commit_time / inventory_consumed / purchase).

Planner knowledge (from docs/waterfall-allocation.md):
  - max_methods controls waterfall fan-out: 1 = single best method per demand;
    2-4 = exhaust the best method, then fall back to the next ranked method only
    if the first hit capacity. Inventory carries forward across slots.
  - mode = "preference" (lowest preference int wins) | "elaborate" (composite scoring
    of commit_time / inventory_consumed / purchase; ~3-4× slower wall-time).
  - method_selection.depth gates elaborate to top N BOM levels (default 1 = root only).
  - method_selection.max_bom_depth (default 3) caps the recursion depth admitted
    at the reactive make-fallback site. A make alternative whose precomputed
    maxMakeDepth exceeds this cap is skipped without recursing — clamps cost
    on deep BOMs while still admitting structurally feasible fallbacks.
  - consolidation.allocation_mode = "fair" (priority-first when ample, proportional
    under shortage) | "proportional" | "priority_first". Split policy applies at
    supply-bearing nodes (raw inventory, leftover stock, carry-over WOs).
  - On case-171 the empirical sweet spot is mode=preference + max_methods=2.

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
      consolidation period, manual_override.component_split.

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
    or from the KB. If you self-construct a signature, round-trip it through is_signature_in_kb
    before mentioning it. (b) Any rationale for "why this config" MUST cite either a
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

  5. **Decide the response shape based on N vs max_feasible_days**:
     - N ≤ max_feasible_days  → Safe. Optionally offer create_wo_schedule_event
       to record the scenario; no impact run needed.
     - N > max_feasible_days  → Run analyze_wo_schedule_impact(delay_days=N,
       persist=true). Surface impacted_demand_count, top 3-5 impacted demands
       (demandId, customer, daysDelta), and the contingent_plan_run_id.
       **Capture the contingent_plan_run_id explicitly** — you'll need it in
       step 7 if the user accepts.

  6. **Suggest options** when N exceeds max-safe — ALWAYS present exactly these
     three, NUMBERED 1/2/3 (so user replies of "3" / "三" / "我选择 3" are
     unambiguously interpretable):
       1. "Reduce shutdown to max_feasible_days days" — pure safe envelope.
       2. "Try a different start date" — shift bucketStart later (re-run availability).
       3. "Accept the impact and amend the plan" — promote the contingent.

  7. **On user acceptance of option (c)** — directly call promote_plan_run with
     the **literal `contingent_plan_run_id` value that step 5's
     analyze_wo_schedule_impact returned in its summary line**
     ("…contingent_plan_run_id=<N>"). NOT a number from this prompt's example —
     pull the real id from your most recent tool-result trace. DO NOT re-run
     find_wos / analyze_wo_availability / analyze_wo_schedule_impact — the
     contingent run is already saved with the correct WO set; re-running could
     pick a different WO set and a different contingent.

  8. **If promote_plan_run fails** ("not_found", "wrong_status", "superseded"):
     STOP. Do NOT recover by re-running the impact analysis. Report the failure
     to the user verbatim and ask them how to proceed (likely you used the
     wrong id — re-read step 5's summary and find the correct
     contingent_plan_run_id).

  9. **Confirmation gate / option-3 pattern matching**: NEVER call promote_plan_run
     without an explicit user acceptance. After you've offered the three
     numbered options in step 6 and you're awaiting the choice, treat ANY of
     these as "user picked option 3 (accept the impact)" and respond by calling
     promote_plan_run with the captured contingent_plan_run_id from step 5:
       - English: "3" / "three" / "option 3" / "(c)" / "C" / "yes" / "yes, promote" /
         "go ahead" / "amend" / "accept the impact" / "promote".
       - Chinese: "3" / "三" / "③" / "选项 3" / "第三个" / "我选择 3" / "选择 3" /
         "我选 3" / "接受" / "确认" / "采用" / "升格".
     Number "1" / "一" / "第一个" → option 1 (reduce shutdown). Number "2" /
     "二" / "第二个" → option 2 (different start date). DO NOT re-run any
     analysis to "verify" — the option choice is unambiguous.

  10. **HARD STOP — never call analyze_wo_schedule_impact twice in one turn.**
      If you've already called analyze_wo_schedule_impact in this conversation
      turn AND the user's most recent message is a numeric option choice
      (1/2/3, 一/二/三, A/B/C, etc.), DO NOT call analyze_wo_schedule_impact
      again. The legal next action depends on the option:
        Option 1 → analyze_wo_availability is fine (re-check the safe envelope
                   they accepted), or just acknowledge in prose.
        Option 2 → analyze_wo_availability with the new bucketStart (re-check
                   if the new date is safe). Single call, then reply.
        Option 3 → promote_plan_run(plan_run_id=<captured contingent_plan_run_id>).
                   That's it. No availability, no impact.
      If you find yourself "wanting to verify" — STOP. The contingent plan run
      from step 5 is already saved and authoritative.

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
    "go ahead" — IMMEDIATELY call promote_plan_run(plan_run_id=«CONTINGENT_ID»)
    using the actual integer you captured, NOT a literal from this example.
    DO NOT re-run find_wos or analyze_wo_*. The contingent is already saved.
  If promote fails: report the error to the user; do not recover by
    re-running analysis.
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
        "Merge a partial PlanningConfig into the working config. Only set fields you want to change. " +
            "Returns the merged config. The agent's response will surface this to the form so the user " +
            "sees toggles flip in sync.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("partial") {
                    put("type", "object")
                    put("description", "Partial PlanningConfig. Top-level keys: method_selection, " +
                        "consolidation, purchase_allowed, analyze_criticality, check_soundness.")
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
                        "'mode', 'max_methods', 'depth', 'max_bom_depth', 'score_weights', " +
                        "'allocation_mode', 'period_days', 'purchase_allowed', " +
                        "'consolidation_enabled', 'elaborate'. Only runs that bootstrap-" +
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
            "(e.g. 'm=preference|max=2|d=1|bom=3|...|alloc=fair|cons=true|p=0|purch=false'). " +
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
        "Read the case's supply table for one product: every initial-inventory row " +
            "(supply_id, location, qty, supply_date). Pair with get_product_methods to " +
            "diagnose 'why did this demand fail?' — the typical answer is either zero " +
            "supply at the needed (product, location) AND no make/move/buy method to " +
            "produce it there. Returns [{supply_id, location, qty, supply_date}], sorted " +
            "by location then supply_date.",
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
            "    array names the four override paths available (manual_override.component_split, " +
            "    change allocation_mode, change period_days, change demand.priority).\n" +
            "Returns { product_id, location_id, total_initial_supply, competitor_count, " +
            "competitors: [...], member_count, zero_share_count, members: [...], " +
            "consolidation: { enabled, allocation_mode, period_days }, override_levers }.\n" +
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
        "recommend_config",
        "**L3 / advising new runs.** Multi-objective router: given a target " +
            "objective and an optional constraint, recommend a config from KB (or " +
            "propose a novel one). Decision tree:\n" +
            "  • `novel_only=true` → delegates to suggest_next_batch with the " +
            "    objective mapped to a criterion. Returns up to 3 novel proposals.\n" +
            "  • `hard_constraint` set → query KB with the constraint as filter, " +
            "    sort by objective. Returns top-3 from the filtered set.\n" +
            "  • `soft_constraint` set → Pareto frontier on objective × " +
            "    soft_constraint.kpi, then knee detection (max distance from " +
            "    utopia line). Returns the knee + 1-2 nearby points.\n" +
            "  • Pure objective (no constraint) → Pareto with a sensible default " +
            "    secondary axis (e.g. best_fill defaults to gini).\n" +
            "Allowed objectives: best_fill, best_fairness, least_purchase, " +
            "most_inventory_use, earliest_commit, fewest_starvation. Returns " +
            "{ headline, alternates, rationale, frontier_summary, total_in_kb, " +
            "source } where `source` ∈ {kb_pareto, kb_query, novel, ...}.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("objective") {
                    put("type", "string")
                    put("description", "best_fill | best_fairness | least_purchase | most_inventory_use | earliest_commit | fewest_starvation")
                }
                putJsonObject("soft_constraint") {
                    put("type", "object")
                    put("description", "{ kpi, qualifier (\"reasonable\"|\"strict\") } — anchors the secondary axis for Pareto knee detection.")
                }
                putJsonObject("hard_constraint") {
                    put("type", "object")
                    put("description", "{ kpi, op (\"<\"|\"<=\"|\">\"|\">=\"), value } — hard threshold applied as a pre-filter.")
                }
                putJsonObject("novel_only") {
                    put("type", "boolean")
                    put("description", "If true, restrict to novel single-axis variations not yet in KB.")
                }
            }
            put("required", buildJsonArray { add("objective") })
        },
    ),
    tool(
        "narrate_tradeoff",
        "**L3 / tradeoff narration.** Compare two configurations by signature and return " +
            "structured tradeoff analysis: which knobs differ, which KPIs delta, and a " +
            "one-line axis-of-tradeoff label. Use this whenever you need to narrate why " +
            "recommend_config.alternates exist — never use freehand comparison. Returns " +
            "{ differing_knobs: [{ knob, a, b }], kpi_deltas: [{ kpi, a, b, delta }], " +
            "axis_label: string, summary: string }.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("sig_a") {
                    put("type", "string")
                    put("description", "First signature (canonical form, e.g. from recommend_config or KB).")
                }
                putJsonObject("sig_b") {
                    put("type", "string")
                    put("description", "Second signature (canonical form).")
                }
            }
            put("required", buildJsonArray { add("sig_a"); add("sig_b") })
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
            "  • `failed_cascade_probe` — tried but BOM probe blocked deeper (failed=true)\n" +
            "  • `score_lower` — elaborate-mode catch-all for losing alternatives\n" +
            "Plus an `override_levers` array naming the seven supply-side override paths " +
            "(manual_override.method_selection / change max_methods / change max_bom_depth / " +
            "change mode / change score_weights / flip purchase_allowed / re-rank " +
            "preference). Symmetric to get_leaf_competition's `members` enrichment. When " +
            "`demand_id` is provided, scope to one demand's tree; otherwise return all " +
            "matches across the run.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("product_id") { put("type", "string") }
                putJsonObject("location_id") { put("type", "string") }
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
        "Return the planning config and override snapshot that produced a specific plan run. " +
            "Use this BEFORE comparing two runs' KPIs — to confirm they share the same config (so " +
            "any KPI delta is attributable to a single load-bearing knob, not a confound). Returns " +
            "{config, override_snapshot}.",
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
        "find_wos",
        "List work orders matching the given filters, aggregated one row per wo_group_id. Use to " +
            "identify candidates for a maintenance/downtime scenario before calling " +
            "analyze_wo_availability / analyze_wo_schedule_impact. Filters AND together; omit any to " +
            "match all. Returns up to `limit` rows (default 50, max 500).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("prod_area")  { put("type", "string"); put("description", "Exact prod_area match.") }
                putJsonObject("location_id"){ put("type", "string"); put("description", "Exact location_id match.") }
                putJsonObject("product_id") { put("type", "string"); put("description", "Exact product_id match.") }
                putJsonObject("method")     { put("type", "string"); put("description", "make / move / buy.") }
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
                putJsonObject("note") { put("type", "string") }
            }
            put("required", buildJsonArray { add("selectors") })
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
        "promote_plan_run",
        "Promote a contingent plan run to status='success' — making it the active baseline. ALWAYS " +
            "confirm with the user before calling this; never auto-promote. Idempotent on already-promoted runs.",
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
private fun loc(en: String, zh: String, locale: String): String =
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

private data class ToolResult(val summary: String, val payload: JsonElement)

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
            "method_selection", "consolidation", "purchase_allowed",
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
                        put("signature", CaseBootstrap.signatureFor(p.config))
                        put("config", p.config)
                    })
                }
            })
            put(
                "note",
                "Each candidate is a single-knob variation off the current best, dedup'd against " +
                    "every signature in this case's KB + plan_run history. To run one, call " +
                    "update_config with `partial` set to the EXACT candidate.config object — " +
                    "every top-level key (purchase_allowed, method_selection, variant_selection, " +
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

private fun toolGetDemandPegging(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required", locale)
    val demandId = args["demand_id"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`demand_id` is required", locale)
    val result = loadPlanResultFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId", locale)
    @Suppress("UNCHECKED_CAST")
    val pegging = result["planning_pegging"] as? List<Map<String, Any?>> ?: emptyList()
    val matchingEntries = pegging.filter { it["demand_id"]?.toString()?.trim() == demandId.trim() }
    if (matchingEntries.isEmpty()) {
        return toolError("demand $demandId not in run $runId pegging", locale)
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
            "Pegging tree for demand $demandId$originSummary$summarySuffix",
            "需求 $demandId 的支撑链$originSummary$summarySuffix",
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

    val rows = transaction {
        Supplies.selectAll()
            .where { (Supplies.caseId eq caseId) and (Supplies.productId eq productId) }
            .map { row ->
                buildJsonObject {
                    put("supply_id", JsonPrimitive(row[Supplies.supplyId]))
                    put("location", JsonPrimitive(row[Supplies.locationId]))
                    put("qty", JsonPrimitive(row[Supplies.qty]))
                    put("supply_date", JsonPrimitive(row[Supplies.supplyDate]))
                }
            }
            .sortedWith(compareBy(
                { (it["location"] as? JsonPrimitive)?.contentOrNull ?: "" },
                { (it["supply_date"] as? JsonPrimitive)?.contentOrNull ?: "" },
            ))
    }
    val totalQty = rows.sumOf { (it["qty"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }
    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        put("total_qty", JsonPrimitive(totalQty))
        put("rows", JsonArray(rows))
    }
    return ToolResult(
        summary = loc(
            "Supply for $productId — ${rows.size} rows, total qty ${totalQty}",
            "$productId 的供应 — ${rows.size} 行，总量 ${totalQty}",
            locale,
        ),
        payload = payload,
    )
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

    val result = loadPlanResultFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId", locale)

    // Defensive: catch the common LLM input-parsing error where a hyphenated
    // product code (e.g. "502-2991") was split as product="502", location="2991".
    // If `productId` isn't a real product in this case AND `${productId}-${locationId}`
    // IS a real product, return an early error pointing at the corrected identifier
    // so the agent retries with the right args instead of producing a confidently
    // wrong "no usage" answer.
    val productExists = transaction {
        Products.selectAll()
            .where { (Products.caseId eq caseId) and (Products.productId eq productId) }
            .limit(1).count() > 0L
    }
    if (!productExists) {
        val joined = "$productId-$locationId"
        val joinedExists = transaction {
            Products.selectAll()
                .where { (Products.caseId eq caseId) and (Products.productId eq joined) }
                .limit(1).count() > 0L
        }
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
    // consolidation policy + priority data + manual_override state. No
    // persistence required (plan_run.result stays compact).

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
    val periodDays = consolidationConfig["period_days"]?.jsonPrimitive?.intOrNull ?: 0
    val consolidationEnabled = consolidationConfig["enabled"]?.jsonPrimitive?.booleanOrNull ?: true

    // Manual overrides on supplies at this (pid, lid) — surfaces when a
    // component_split override has zeroed a demand's share at a specific
    // supply_id under this leaf.
    val activeOverrides: List<Pair<String, JsonObject>> = transaction {
        ManualOverrides.selectAll()
            .where { (ManualOverrides.caseId eq caseId) and (ManualOverrides.entityType eq "supply") }
            .mapNotNull { row ->
                val key = row[ManualOverrides.entityKey]
                val payload = runCatching {
                    jsonParser.parseToJsonElement(row[ManualOverrides.payload]).jsonObject
                }.getOrNull() ?: return@mapNotNull null
                key to payload
            }
    }

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
        if (!consolidationEnabled) {
            return "zero_share" to "consolidation disabled — share elimination didn't happen at this leaf; check upstream methods"
        }
        // Manual override check: any active override on a supply at this
        // (pid, lid) that names this demand_id explicitly with qty=0?
        val overrideHit = activeOverrides.any { (_, payload) ->
            val allocs = payload["allocations"] as? JsonArray ?: return@any false
            allocs.any { el ->
                val obj = el as? JsonObject ?: return@any false
                val did = obj["demand_id"]?.jsonPrimitive?.contentOrNull
                val qty = obj["qty"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                did == d.demandId && (qty ?: 0.0) <= 1e-9
            }
        }
        if (overrideHit) return "override_blocked" to "manual_override component_split set qty=0 for this demand at this supply"

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
        return "zero_share" to "presumed eliminated by consolidation but specific reason not derivable from policy=$allocationMode + priority + shortage signals; inspect manual_override or upstream walks"
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
            put("enabled", JsonPrimitive(consolidationEnabled))
            put("allocation_mode", JsonPrimitive(allocationMode))
            put("period_days", JsonPrimitive(periodDays))
        })
        put("override_levers", buildJsonArray {
            // Surface the override paths the agent can recommend. Caller
            // (LLM) picks the appropriate one based on share_status.
            add(JsonPrimitive("manual_override.component_split — re-assign shares at the supply level"))
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
        ?.toSet().orEmpty()

    // Defensive: catch the hyphen-split LLM error (e.g. product=`502-2991` mis-passed
    // as product=`502`, location=`2991`). Mirrors [toolGetLeafCompetition].
    val productExists = transaction {
        Products.selectAll()
            .where { (Products.caseId eq caseId) and (Products.productId eq productId) }
            .limit(1).count() > 0L
    }
    if (!productExists) {
        val joined = "$productId-$locationId"
        val joinedExists = transaction {
            Products.selectAll()
                .where { (Products.caseId eq caseId) and (Products.productId eq joined) }
                .limit(1).count() > 0L
        }
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

    val result = loadPlanResultFromDb(caseId, runId)
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
            val demandRow = transaction {
                Demands.selectAll()
                    .where { (Demands.caseId eq caseId) and (Demands.demandId eq demandIdArg) }
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
    val demandId = args["demand_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`demand_id` is required", locale)
    val supplyId = args["supply_id"]?.jsonPrimitive?.contentOrNull?.trim()
        ?: return toolError("`supply_id` is required", locale)

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
        if (d == null) return toolError("demand `$demandId` not found in case $caseId", locale)
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
    val (config, override) = transaction {
        val row = PlanRuns.selectAll()
            .where { (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }
            .singleOrNull() ?: return@transaction null
        Pair(row[PlanRuns.config], row[PlanRuns.overrideSnapshot])
    } ?: return toolError("plan run $runId not found for case $caseId", locale)
    val configJson: JsonElement = config?.let { raw ->
        runCatching { jsonParser.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
    } ?: JsonObject(emptyMap())
    val overrideJson: JsonElement = override?.let { raw ->
        runCatching { jsonParser.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
    } ?: JsonObject(emptyMap())
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
            put("override_snapshot", overrideJson)
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

    val result = loadPlanResultFromDb(caseId, runId)
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
                Triple("purchase_disabled", "method type=buy but purchase_allowed=false on this run", "set purchase_allowed=true (or manually override method_selection)")
            key in failedCascadeKeys ->
                Triple("failed_cascade_probe", "cascade picker tried this method but its BOM probe blocked deeper (failed=true in pegging)", "non-trivial — fix upstream inventory or methods at the deeper bottleneck (call get_demand_pegging on a relevant demand to trace)")
            // beyond_max_methods: this alt's rank (1-based) > maxMethods AND
            // its preference is worse than chosen's last slot. Only meaningful
            // when waterfall is on (maxMethods > 1).
            amPref != null && chosenMaxPref != null && amPref > chosenMaxPref && (rank + 1) > maxMethods ->
                Triple("beyond_max_methods", "preference $amPref ranks position ${rank + 1} which exceeds max_methods=$maxMethods", "set method_selection.max_methods >= ${rank + 1} (or manually override method_selection)")
            // lower_preference: preference is worse than chosen, but within
            // the max_methods cap — meaning the cascade simply preferred the
            // higher-ranked one and the residual didn't reach this slot.
            amPref != null && chosenMaxPref != null && amPref > chosenMaxPref ->
                Triple("lower_preference", "preference $amPref > chosen waterfall's max preference $chosenMaxPref; cascade ordering elected the higher-ranked method", "manual method_selection override OR re-rank this method's preference in method_${amType} CSV")
            // elaborate-mode catch-all for losing alternatives.
            mode == "elaborate" ->
                Triple("score_lower", "elaborate composite scoring placed this method below the chosen one (exact rejected score not persisted)", "manual method_selection override OR change method_selection.score_weights to favor the dimension this method is strong on")
            else ->
                Triple("unknown_not_chosen", "not chosen for an unidentified reason (no preference comparison applies); inspect the run config or the chosen WO's method_choice_explanation", "manual method_selection override at this site")
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
        put("max_bom_depth", JsonPrimitive(msConfig["max_bom_depth"]?.jsonPrimitive?.intOrNull ?: 3))
        put("purchase_allowed", JsonPrimitive(purchaseAllowed))
        msConfig["score_weights"]?.let { put("score_weights", it) }
        put("override_levers", buildJsonArray {
            // Symmetric to get_leaf_competition's override_levers — names the
            // supply-side override paths so the agent can recommend the right
            // one based on the alternative's `status`.
            add(JsonPrimitive("manual_override.method_selection — pin a specific method at this (pid, lid) site"))
            add(JsonPrimitive("change method_selection.max_methods — admit more waterfall slots"))
            add(JsonPrimitive("change method_selection.max_bom_depth — admit make alternatives with deeper recipes"))
            add(JsonPrimitive("change method_selection.mode (preference / elaborate) — switch the selection criterion"))
            add(JsonPrimitive("change method_selection.score_weights — re-weight commit_time / inventory_consumed / purchase (elaborate only)"))
            add(JsonPrimitive("change purchase_allowed — admit/exclude method_buy"))
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
    val activeRunId: Int? = transaction {
        val designatedId = Cases.selectAll().where { Cases.id eq caseId }
            .firstOrNull()?.get(Cases.designatedActivePlanRunId)
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
            maxTokens = 1024,
            temperature = 0.2,
            provider = "openai",
        )

        // No tool calls → final reply.
        if (resp.toolCalls.isEmpty()) {
            val reply = resp.text ?: "(empty reply)"
            return AgentResponse(
                reply = reply,
                steps = steps,
                configUpdate = workingConfig.takeIf { it != initialConfig },
                freshRunId = freshRunId,
                pendingJobId = pendingJobId,
            )
        }

        // Append the assistant's tool-request message exactly as received, so
        // the next round-trip carries the tool_call_id<->tool_result linkage.
        convo.add(LlmAgentMessage(role = "assistant", content = resp.text, toolCalls = resp.toolCalls))

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
        }
        log.info("planning-agent iter={} tool_calls={} steps_total={}", iter + 1, resp.toolCalls.size, steps.size)
    }

    // Hit iteration cap — return whatever we have plus a guard message.
    log.warn("planning-agent hit MAX_TOOL_ITERATIONS={}", MAX_TOOL_ITERATIONS)
    return AgentResponse(
        reply = "(I ran out of reasoning steps after $MAX_TOOL_ITERATIONS tool calls. " +
            "Try a more specific request.)",
        steps = steps,
        configUpdate = workingConfig.takeIf { it != initialConfig },
        freshRunId = freshRunId,
        pendingJobId = pendingJobId,
    )
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
        "get_leaf_competition" -> Pair(toolGetLeafCompetition(caseId, args, locale), workingConfig)
        "get_component_allocation_by_demand" -> Pair(toolGetComponentAllocationByDemand(caseId, args, locale), workingConfig)
        "get_bom_tree" -> Pair(toolGetBomTree(caseId, args, locale), workingConfig)
        "find_move_path" -> Pair(toolFindMovePath(caseId, args, locale), workingConfig)
        "trace_demand_to_supply" -> Pair(toolTraceDemandToSupply(caseId, args, locale), workingConfig)
        "compare_runs" -> Pair(toolCompareRuns(caseId, args, locale), workingConfig)
        "explain_method_choice" -> Pair(toolExplainMethodChoice(caseId, args, locale), workingConfig)
        "recommend_config" -> Pair(toolRecommendConfig(caseId, args, locale), workingConfig)
        "narrate_tradeoff" -> Pair(toolNarrateTradeoff(caseId, args, locale), workingConfig)
        "query_design_docs" -> Pair(toolQueryDesignDocs(args, locale), workingConfig)
        "get_run_config" -> Pair(toolGetRunConfig(caseId, args, locale), workingConfig)
        "recheck_soundness" -> Pair(toolRecheckSoundness(caseId, args, locale), workingConfig)
        "get_soundness_summary" -> Pair(toolGetSoundnessSummary(caseId, args, locale), workingConfig)
        "read_memory" -> Pair(toolReadMemory(caseId, locale), workingConfig)
        "write_memory" -> Pair(toolWriteMemory(caseId, args, locale), workingConfig)
        "list_prod_areas" -> Pair(toolListProdAreas(caseId, args, locale), workingConfig)
        "list_locations" -> Pair(toolListLocations(caseId, args, locale), workingConfig)
        "find_wos" -> Pair(toolFindWos(caseId, args, locale), workingConfig)
        "analyze_wo_availability" -> Pair(toolAnalyzeWoAvailability(caseId, args, locale), workingConfig)
        "analyze_wo_schedule_impact" -> Pair(toolAnalyzeWoScheduleImpact(caseId, args, locale), workingConfig)
        "create_wo_schedule_event" -> Pair(toolCreateWoScheduleEvent(caseId, args, locale), workingConfig)
        "promote_plan_run" -> Pair(toolPromotePlanRun(caseId, args, locale), workingConfig)
        else -> Pair(toolError("unknown tool: ${call.name}", locale), workingConfig)
    }
}

// ── WO schedule-change / maintenance-window tool handlers ───────────────────

/** Pull the work-orders list out of a baseline plan-run result. Returns null
 *  if no baseline exists or the result is empty. */
@Suppress("UNCHECKED_CAST")
private fun loadBaselineWorkOrders(caseId: Int, planRunId: Int?): List<Map<String, Any?>>? {
    val result = loadPlanResultFromDb(caseId, planRunId) ?: return null
    return (result["work_orders"] as? List<Map<String, Any?>>)
}

/** True if the WO is a synthetic VirtualProduct_* placeholder (planner-internal,
 *  not an actual shop-floor work order — same filter the UI applies). */
private fun isVirtualProductWo(wo: Map<String, Any?>): Boolean {
    val pid = wo["product_id"] as? String ?: return false
    return pid.startsWith("VirtualProduct_")
}

private fun toolListProdAreas(caseId: Int, args: JsonObject, locale: String): ToolResult {
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

private fun toolListLocations(caseId: Int, args: JsonObject, locale: String): ToolResult {
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

private fun toolFindWos(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val prodArea = args["prod_area"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val locationId = args["location_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val productId = args["product_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val method = args["method"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
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
        .filter { prodArea == null || it["prod_area"] == prodArea }
        .filter { locationId == null || it["location_id"] == locationId }
        .filter { productId == null || it["product_id"] == productId }
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

private fun toolAnalyzeWoScheduleImpact(caseId: Int, args: JsonObject, locale: String): ToolResult {
    val selectors = parseSelectorsArg(args)
    if (selectors.isNullOrEmpty()) return ToolResult(
        summary = loc("invalid selectors", "选择器格式错误", locale),
        payload = buildJsonObject { put("error", "invalid_selectors") },
    )
    val delayDays = args["delay_days"]?.jsonPrimitive?.intOrNull
    val delayToDate = args["delay_to_date"]?.jsonPrimitive?.contentOrNull
    if (delayDays == null && delayToDate.isNullOrBlank()) return ToolResult(
        summary = loc("delay_days or delay_to_date required", "需指定 delay_days 或 delay_to_date", locale),
        payload = buildJsonObject { put("error", "no_delay") },
    )
    val planRunId = args["plan_run_id"]?.jsonPrimitive?.intOrNull
    val persist = args["persist"]?.jsonPrimitive?.booleanOrNull ?: true
    val note = args["note"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val req = WoScheduleImpactRequest(
        selectors = selectors,
        delayDays = delayDays,
        delayToDate = delayToDate,
        planRunId = planRunId,
        caseId = caseId,
        persist = persist,
        note = note,
    )
    return when (val r = runWoScheduleImpactInline(req)) {
        is WoImpactResult.Failed -> ToolResult(
            summary = loc("impact analysis failed: ${r.reason}", "影响分析失败：${r.reason}", locale),
            payload = buildJsonObject { put("error", r.reason) },
        )
        is WoImpactResult.Ok -> {
            val resp = r.response
            val withinSafe = (resp.delayDays != null && resp.maxFeasibleDays != null &&
                resp.delayDays <= resp.maxFeasibleDays)
            // Surface contingent_plan_run_id in the summary so the LLM sees the
            // actual id (and won't fall back to a literal from the few-shot
            // example in the system prompt) when it later calls promote_plan_run.
            val contingentSuffix = resp.contingentPlanRunId?.let { ", contingent_plan_run_id=$it" } ?: ""
            ToolResult(
                summary = loc(
                    "impact: ${resp.matchedWoCount} WO shifted, ${resp.impactedDemandCount} demand(s) delayed; " +
                        "max safe ${resp.maxFeasibleDays}d (${if (withinSafe) "within" else "exceeds"})" +
                        contingentSuffix,
                    "影响：${resp.matchedWoCount} 个工单后移，${resp.impactedDemandCount} 个需求延迟；" +
                        "最大安全 ${resp.maxFeasibleDays} 天 (${if (withinSafe) "安全范围内" else "超出"})" +
                        contingentSuffix,
                    locale,
                ),
                payload = Json.encodeToJsonElement(WoScheduleImpactResponse.serializer(), resp),
            )
        }
    }
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

private fun toolPromotePlanRun(caseId: Int, args: JsonObject, locale: String): ToolResult {
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
    return when {
        outcome == "promoted" -> ToolResult(
            summary = loc("plan run #$planRunId promoted to success", "计划运行 #$planRunId 已升格为正式", locale),
            payload = buildJsonObject {
                put("plan_run_id", planRunId)
                put("new_status", "success")
            },
        )
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
                mapOf("error" to "Planning agent requires OPENAI_API_KEY to be configured.")
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

