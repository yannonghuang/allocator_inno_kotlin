package com.allocator.api

import com.allocator.AgentMemory
import com.allocator.Cases
import com.allocator.MethodBuys
import com.allocator.MethodMakes
import com.allocator.MethodMoves
import com.allocator.PlanRuns
import com.allocator.Supplies
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
  - Reach for tools when the user asks "what would happen if…", "why…", or "how much…".
    Don't guess KPIs — call get_kpis. Don't guess pegging — call get_demand_pegging.
  - When a demand fails or commits short ("why didn't X commit?", "supply chain loop",
    "no supply method", "为什么 X 没满"), report TWO ORTHOGONAL AXES — never collapse them:

    **Supply-side (瓶颈 / orange `is_bottleneck`)** — the BOM/inventory chain
    couldn't deliver. Trace the cascade reason in pegging:
      1. `get_demand_pegging(run_id, demand_id)` — root demand carries
         `failure_explanation` for `no_methods`. Quote verbatim if present.
      2. Walk the failed nodes; find the deepest leaf with the smallest first-pass
         effective/needed ratio. If the user pushes back, call `get_product_methods`
         and `get_product_supply` on that leaf to confirm:
            • `make` exists at L1+L2 but no `move-to-needed-loc` → data gap.
            • `buy` exists but `purchase_allowed=false` → config gap.
            • `move` source has zero supply at the source → upstream provisioning gap.

    **Demand-side (根因 / red `is_root_bottleneck`)** — consolidation's fair-share
    split with competing demands left this demand with the tightest share-vs-need
    ratio at the flagged child. Independent of supply.
      1. From the pegging, find children with `is_root_bottleneck=true`.
      2. `get_leaf_competition(run_id, product_id, location_id)` — returns actual
         draws for every demand that consumed at that leaf, plus
         total_initial_supply. Use this to articulate the demand-side story:
         "demand X got C/T (≈C%) because demand(s) [Y, Z] together consumed
         K/T (≈K%) under allocation_mode=…".
      3. Suggest demand-side levers: priority change for X, switch
         allocation_mode, change consolidation period, etc.

    **Synthesize**: report both axes when both fire. Template —
       Supply: <single-line cause + concrete fix>.
       Demand allocation: <competition story + concrete lever>.
       The two are independent — applying one fix without the other still leaves
       the demand short. Pick whichever is cheaper for the user.

    **Anti-pattern**: dumping the pegging tree as a markdown bullet list. The user
    saw it in the UI. Your job is to NAME root causes (one per axis when both fire).
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

private val TOOLS: List<LlmTool> = listOf(
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
            "commit_reason is `no_methods` — surface it verbatim instead of paraphrasing.",
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
        "**Demand-side root-cause story.** For one (product, location) leaf in a plan run, " +
            "list all demands that drew from it — with actual qty consumed and share-of-" +
            "total-supply percentage. Use this to articulate the 根因 (red badge / " +
            "`is_root_bottleneck`) story on a pegging tree: 'demand X got C/T (≈C%) of " +
            "supply because demands [Y, Z] together consumed K/T (≈K%) under " +
            "allocation_mode=…'. Pairs with `get_demand_pegging`'s 瓶颈 (supply-side) " +
            "signal to give the user both fix-it levers. Returns { product_id, " +
            "location_id, total_initial_supply, competitor_count, competitors: " +
            "[{ demand_id, actual_draw_qty, share_pct }] } sorted by actual_draw_qty desc.",
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
    val entry = pegging.firstOrNull { it["demand_id"]?.toString()?.trim() == demandId.trim() }
        ?: return toolError("demand $demandId not in run $runId pegging", locale)

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
    val prunedEntry = entry.toMutableMap().apply { if (prunedTree != null) put("tree", prunedTree) }

    val payload = anyToJson(prunedEntry)
    val payloadStr = payload.toString()
    val originalSize = anyToJson(entry).toString().length
    val summarySuffix = if (originalSize > payloadStr.length + 1024) {
        " (pruned ${originalSize}→${payloadStr.length} chars; fulfilled subtrees collapsed)"
    } else ""

    return ToolResult(
        summary = loc(
            "Pegging tree for demand $demandId$summarySuffix",
            "需求 $demandId 的支撑链$summarySuffix",
            locale,
        ),
        payload = payload,
    )
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

    // Total initial supply at the leaf (case-scoped).
    val totalSupply = transaction {
        Supplies.selectAll()
            .where {
                (Supplies.caseId eq caseId) and (Supplies.productId eq productId) and (Supplies.locationId eq locationId)
            }
            .sumOf { it[Supplies.qty] }
    }

    // Actual draws: walk planning_pegging supply leaves and aggregate qty by demand_id.
    // Each pegging entry is keyed by demand_id; supply leaves under it carry the qty
    // that demand actually pulled from physical inventory at this (pid, lid).
    // (Note: iter-0 consolidation fair-shares were considered for persistence but
    // dropped — they bloated plan_run.result by hundreds of KB per demand and
    // OOM'd the listing endpoint. Actual draws cover the demand-side competition
    // story well enough on their own.)
    @Suppress("UNCHECKED_CAST")
    val planningPegging = (result["planning_pegging"] as? List<Map<String, Any?>>) ?: emptyList()
    val actualByDemand = mutableMapOf<String, Double>()
    for (entry in planningPegging) {
        val demandId = entry["demand_id"]?.toString() ?: continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        accumulateLeafDraws(tree, productId, locationId, demandId, actualByDemand)
    }

    val rows = actualByDemand.entries
        .filter { it.value > 1e-9 }
        .sortedByDescending { it.value }
        .map { (did, qty) -> did to qty }

    val competitorsJson = JsonArray(rows.map { (did, qty) ->
        buildJsonObject {
            put("demand_id", JsonPrimitive(did))
            put("actual_draw_qty", JsonPrimitive(qty))
            put("share_pct", JsonPrimitive(if (totalSupply > 1e-9) qty * 100.0 / totalSupply else 0.0))
        }
    })

    val payload = buildJsonObject {
        put("product_id", JsonPrimitive(productId))
        put("location_id", JsonPrimitive(locationId))
        put("total_initial_supply", JsonPrimitive(totalSupply))
        put("competitor_count", JsonPrimitive(rows.size))
        put("competitors", competitorsJson)
    }
    return ToolResult(
        summary = loc(
            "Competition at $productId @ $locationId — ${rows.size} demand(s), total supply ${totalSupply}",
            "$productId @ $locationId 的竞争 — ${rows.size} 个需求，总供应 ${totalSupply}",
            locale,
        ),
        payload = payload,
    )
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
    val memory = loadMemory(caseId)
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
        "get_run_config" -> Pair(toolGetRunConfig(caseId, args, locale), workingConfig)
        "recheck_soundness" -> Pair(toolRecheckSoundness(caseId, args, locale), workingConfig)
        "get_soundness_summary" -> Pair(toolGetSoundnessSummary(caseId, args, locale), workingConfig)
        "read_memory" -> Pair(toolReadMemory(caseId, locale), workingConfig)
        "write_memory" -> Pair(toolWriteMemory(caseId, args, locale), workingConfig)
        else -> Pair(toolError("unknown tool: ${call.name}", locale), workingConfig)
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
            runAgentLoop(caseId, userMessage, initialConfig, history)
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

