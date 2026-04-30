package com.allocator.api

import com.allocator.AgentMemory
import com.allocator.Cases
import com.allocator.PlanRuns
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.max
import com.allocator.services.CaseLoader
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
  - consolidation.engine = "supply" (experimental, recommended) | "leaf-legacy".
  - consolidation.allocation_mode = "fair" (priority-first when ample, proportional
    under shortage) | "proportional" | "priority_first".
  - On case-171 the empirical sweet spot is mode=preference + max_methods=2.

Tactics:
  - Reach for tools when the user asks "what would happen if…", "why…", or "how much…".
    Don't guess KPIs — call get_kpis. Don't guess pegging — call get_demand_pegging.
  - Persist durable preferences via write_memory (e.g. user said "I never want purchase"
    → write_memory("purchase_default", false)). Memory is per-case.
  - Mirror the user's language (English / Chinese). Keep replies tight; be conversational.
  - When a plan finishes, end your reply with the plan_run_id and the headline KPIs.
  - When you make a config change, the user will see it applied to the form; you don't
    need to repeat it verbatim — just explain the *why* in their language.
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
        "Kick off a plan run with the working config. Returns job_id. " +
            "Pair with wait_for_plan to block until completion.",
        emptyParams(),
    ),
    tool(
        "wait_for_plan",
        "Block until a plan job completes (or times out at 10 minutes). " +
            "Returns plan_run_id, status, fill_rate_pct, total_committed, total_requested.",
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
            "rows with id, status, name, created_at, soundness_status, and (when " +
            "available) fill_rate_pct. Use to discover run_ids before calling " +
            "get_kpis or get_demand_pegging. Pass status='success' to skip " +
            "contingent / failed / running runs (contingent runs have no plan_kpis " +
            "stored — they're what-if simulations).",
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
            "was fulfilled across multiple work orders or hit partial fulfillment.",
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
        "get_supply_split_explanation",
        "For a consolidated supply, return how its produced quantity was split across competing " +
            "demands. Reads supply_level_allocations from the plan result.",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("run_id") { put("type", "integer") }
                putJsonObject("supply_id") { put("type", "string") }
            }
            put("required", buildJsonArray { add("run_id"); add("supply_id") })
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

private fun toolError(message: String): ToolResult =
    ToolResult(summary = "error: $message", payload = buildJsonObject { put("error", message) })

private fun toolReadCurrentConfig(workingConfig: JsonObject): ToolResult {
    val keys = workingConfig.keys.joinToString(", ").ifBlank { "(empty)" }
    return ToolResult(summary = "Read working config ($keys)", payload = workingConfig)
}

private fun toolUpdateConfig(
    workingConfig: JsonObject,
    args: JsonObject,
): Pair<ToolResult, JsonObject> {
    val partial = args["partial"] as? JsonObject ?: return Pair(
        toolError("`partial` must be a JSON object"),
        workingConfig,
    )
    val merged = mergeJsonObject(workingConfig, partial)
    val changedKeys = partial.keys.joinToString(", ")
    return Pair(
        ToolResult(summary = "Updated config: $changedKeys", payload = merged),
        merged,
    )
}

private suspend fun toolRunPlanAsync(
    caseId: Int,
    workingConfig: JsonObject,
): ToolResult {
    val configMap = jsonObjectToMap(workingConfig)
    val data = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: return@transaction null
        CaseLoader.load(caseId)
    } ?: return toolError("case $caseId not found")
    if (data["demand"].isNullOrEmpty() || data["supply"].isNullOrEmpty()) {
        return toolError("case $caseId has no demand or supply rows; import CSV first")
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
    engineScope.launch { runPlanBackground(jobId, caseId, data, configMap) }
    return ToolResult(
        summary = "Started plan job $jobId ($total demands)",
        payload = buildJsonObject {
            put("job_id", jobId)
            put("status", "running")
            put("total_demands", total)
        },
    )
}

private suspend fun toolWaitForPlan(args: JsonObject): ToolResult {
    val jobId = args["job_id"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`job_id` is required")
    // 60s chat-friendly cap. The frontend's HTTP request would otherwise sit
    // open for the full plan wall-time (case-171 ranges 90s–6min); intermediate
    // proxies and the user's patience both run out long before that. When this
    // expires we return a structured "still_running" payload + the job_id so
    // the agent can reply "plan started, check back in a couple minutes" and
    // the user can poll later by asking again.
    val deadline = System.currentTimeMillis() + 60 * 1000
    while (System.currentTimeMillis() < deadline) {
        val job = planJobs[jobId] ?: return toolError("job $jobId not found (server restart?)")
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
                    summary = "Plan run $runId completed: fill ${fillPct ?: "?"}% " +
                        "(${totalCommitted ?: "?"} / ${totalRequested ?: "?"})",
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
                    summary = "Plan failed: $err",
                    payload = buildJsonObject {
                        put("status", "failed"); put("error", err)
                    },
                )
            }
            else -> delay(2_000)  // poll every 2s
        }
    }
    // 60s elapsed and plan still running. Return a "still_running" tool result
    // (NOT an error) so the agent can craft a useful reply with the job_id +
    // current progress instead of treating this as a failure.
    val job = planJobs[jobId]
    @Suppress("UNCHECKED_CAST")
    val progress = job?.get("progress") as? Map<String, Any?>
    val current = (progress?.get("current") as? Number)?.toInt() ?: 0
    val total = (progress?.get("total") as? Number)?.toInt() ?: 0
    return ToolResult(
        summary = "Plan still running ($current/$total demands after 60s) — agent should reply early",
        payload = buildJsonObject {
            put("status", "still_running")
            put("job_id", jobId)
            put("progress_current", current)
            put("progress_total", total)
            put(
                "note",
                "60-second wait window expired but plan is still in flight. " +
                    "Reply to the user immediately with the job_id and total progress; " +
                    "tell them to come back in a couple minutes and ask for the result. " +
                    "Don't loop on wait_for_plan — the chat timeout will kick in.",
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
    val fillPct: Double?,
)

private fun toolListPlanRuns(caseId: Int, args: JsonObject): ToolResult {
    val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
    val statusFilter = args["status"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    // Materialize all row data INSIDE the transaction. Exposed lazy-evaluates
    // some column reads (especially nullable text + timestamp), so accessing
    // ResultRow columns outside the transaction throws "No transaction in
    // context".
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
                val fillPct: Double? = if (resultJson.isNullOrBlank()) null else runCatching {
                    val root = jsonParser.parseToJsonElement(resultJson).jsonObject
                    ((root["plan_kpis"] as? JsonObject)
                        ?.get("delivery") as? JsonObject)
                        ?.get("fill_rate_pct")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                }.getOrNull()
                PlanRunSummary(
                    id = r[PlanRuns.id],
                    status = r[PlanRuns.status],
                    name = r[PlanRuns.name] ?: "",
                    createdAt = r[PlanRuns.createdAt].toString(),
                    soundnessStatus = r[PlanRuns.soundnessStatus],
                    fillPct = fillPct,
                )
            }
    }
    val summary = if (items.isEmpty()) "no plan runs for case $caseId"
        else "${items.size} runs, latest=${items.first().id}"
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
                    })
                }
            })
        },
    )
}

private fun toolGetKpis(caseId: Int, args: JsonObject): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required")
    val result = loadPlanResultFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId")
    @Suppress("UNCHECKED_CAST")
    val kpis = (result["plan_kpis"] as? Map<String, Any?>)
    if (kpis.isNullOrEmpty()) {
        // Contingent / what-if runs skip the KPI compute step — they store
        // committed_demands + pegging but not plan_kpis. Tell the model
        // explicitly so it doesn't say "KPIs not available" without context.
        return ToolResult(
            summary = "Run $runId has no plan_kpis (likely a contingent run)",
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
        summary = "Run $runId KPIs (fill ${fillPct ?: "?"}%)",
        payload = anyToJson(kpis),
    )
}

private fun toolGetDemandPegging(caseId: Int, args: JsonObject): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required")
    val demandId = args["demand_id"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`demand_id` is required")
    val result = loadPlanResultFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId")
    @Suppress("UNCHECKED_CAST")
    val pegging = result["planning_pegging"] as? List<Map<String, Any?>> ?: emptyList()
    val entry = pegging.firstOrNull { it["demand_id"]?.toString()?.trim() == demandId.trim() }
        ?: return toolError("demand $demandId not in run $runId pegging")
    return ToolResult(
        summary = "Pegging tree for demand $demandId",
        payload = anyToJson(entry),
    )
}

private fun toolGetSupplySplitExplanation(caseId: Int, args: JsonObject): ToolResult {
    val runId = args["run_id"]?.jsonPrimitive?.intOrNull
        ?: return toolError("`run_id` is required")
    val supplyId = args["supply_id"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`supply_id` is required")
    val result = loadPlanResultFromDb(caseId, runId)
        ?: return toolError("plan run $runId not found for case $caseId")
    @Suppress("UNCHECKED_CAST")
    val sla = result["supply_level_allocations"] as? List<Map<String, Any?>> ?: emptyList()
    val match = sla.firstOrNull { it["supply_id"]?.toString() == supplyId }
        ?: return toolError(
            "supply $supplyId not in supply_level_allocations for run $runId — " +
                "either run was leaf-legacy engine or supply wasn't consolidated",
        )
    @Suppress("UNCHECKED_CAST")
    val perDemand = match["per_demand_allocations"] as? Map<String, Any?> ?: emptyMap()
    return ToolResult(
        summary = "Supply $supplyId split across ${perDemand.size} demands",
        payload = anyToJson(match),
    )
}

private fun toolReadMemory(caseId: Int): ToolResult {
    val mem = loadMemory(caseId)
    return ToolResult(
        summary = "Read ${mem.size} memory entries",
        payload = mem,
    )
}

private fun toolWriteMemory(caseId: Int, args: JsonObject): ToolResult {
    val key = args["key"]?.jsonPrimitive?.contentOrNull
        ?: return toolError("`key` is required")
    if (key.length > 128) return toolError("`key` must be ≤ 128 characters")
    val value = args["value"] ?: return toolError("`value` is required")
    upsertMemory(caseId, key, value)
    return ToolResult(
        summary = "Wrote memory[$key]",
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
    val steps = mutableListOf<AgentStep>()

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
            )
        }

        // Append the assistant's tool-request message exactly as received, so
        // the next round-trip carries the tool_call_id<->tool_result linkage.
        convo.add(LlmAgentMessage(role = "assistant", content = resp.text, toolCalls = resp.toolCalls))

        // Execute every tool call sequentially, append results.
        for (call in resp.toolCalls) {
            val args = runCatching { jsonParser.parseToJsonElement(call.arguments).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            val (result, configAfter) = dispatchTool(caseId, call, args, workingConfig)
            workingConfig = configAfter
            // wait_for_plan succeeded → capture run id for the response envelope.
            if (call.name == "wait_for_plan") {
                val rid = (result.payload as? JsonObject)?.get("plan_run_id")?.jsonPrimitive?.intOrNull
                if (rid != null) freshRunId = rid
            }
            steps.add(AgentStep(tool = call.name, args = args, resultSummary = result.summary))
            convo.add(LlmAgentMessage(role = "tool", toolCallId = call.id, content = result.payload.toString()))
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
    )
}

private suspend fun dispatchTool(
    caseId: Int,
    call: LlmToolCall,
    args: JsonObject,
    workingConfig: JsonObject,
): Pair<ToolResult, JsonObject> {
    return when (call.name) {
        "read_current_config" -> Pair(toolReadCurrentConfig(workingConfig), workingConfig)
        "update_config" -> toolUpdateConfig(workingConfig, args)
        "run_plan_async" -> Pair(toolRunPlanAsync(caseId, workingConfig), workingConfig)
        "wait_for_plan" -> Pair(toolWaitForPlan(args), workingConfig)
        "list_plan_runs" -> Pair(toolListPlanRuns(caseId, args), workingConfig)
        "get_kpis" -> Pair(toolGetKpis(caseId, args), workingConfig)
        "get_demand_pegging" -> Pair(toolGetDemandPegging(caseId, args), workingConfig)
        "get_supply_split_explanation" -> Pair(toolGetSupplySplitExplanation(caseId, args), workingConfig)
        "read_memory" -> Pair(toolReadMemory(caseId), workingConfig)
        "write_memory" -> Pair(toolWriteMemory(caseId, args), workingConfig)
        else -> Pair(toolError("unknown tool: ${call.name}"), workingConfig)
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

