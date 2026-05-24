package com.allocator.api

import com.allocator.NegotiationWaits
import com.allocator.config
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.NegotiationRoutes")

// No request timeout: a material-agent negotiation turn runs material_engine
// (async, ~10s), re-rates, and publishes the next waiting trace — frequently
// exceeding 30s. The endpoint dispatches this request fire-and-forget, so the
// client never waits for the body; we only need the connect to succeed.
// CIO's default engine-level requestTimeout is 15s, so disable it explicitly
// (0 = no timeout) — otherwise the background coroutine logs spurious errors
// even though OpenClaw received and processed the message fine.
private val openClawClient: HttpClient by lazy {
    HttpClient(CIO) {
        engine {
            requestTimeout = 0
        }
    }
}

private val openClawDispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Serializable
data class NegotiationReplyRequest(
    val sessionKey: String,
    val action: String, // "keep" | "abandon" | "counter" | "nl"
    val round: Int? = null,
    val delayDays: Int? = null,
    val qtyPct: Double? = null,
    val baselinePlanRunId: Int? = null,
    val contingentPlanRunId: Int? = null,
    val supplyId: String? = null,
    // For action=="nl": the raw natural-language reply. The material agent
    // classifies intent via its LLM rather than trusting the frontend.
    val text: String? = null,
)

@Serializable
data class NegotiationWaitRequest(
    val sessionKey: String,
    val round: Int,
    val rating: String,
    val explanation: String? = null,
    val currentDelayDays: Int,
    val currentQtyPct: Double,
    val baselinePlanRunId: Int,
    val contingentPlanRunId: Int? = null,
    val supplyId: String,
    val impactedDemandCount: Int = 0,
)

@Serializable
data class NegotiationWaitView(
    val id: Int,
    val caseId: Int,
    val sessionKey: String,
    val round: Int,
    val rating: String,
    val explanation: String?,
    val currentDelayDays: Int,
    val currentQtyPct: Double,
    val baselinePlanRunId: Int,
    val contingentPlanRunId: Int?,
    val supplyId: String,
    val impactedDemandCount: Int,
    val createdAt: String,
    val resolvedAt: String?,
    val resolvedAction: String?,
)

/**
 * POST /cases/{caseId}/negotiation-reply
 * Resumes a paused material-agent session via OpenClaw's chat-completion endpoint.
 * Fires the reply and returns immediately — the agent continues its ReAct loop
 * asynchronously, emitting further trace events when new state is reached.
 */
fun Routing.negotiationRoutes() {
    post("/cases/{caseId}/negotiation-reply") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val req = call.receive<NegotiationReplyRequest>()

        val action = req.action.lowercase()
        if (action !in setOf("keep", "abandon", "counter", "nl")) {
            throw IllegalArgumentException("action must be one of: keep | abandon | counter | nl")
        }
        if (action == "counter" && (req.delayDays == null || req.qtyPct == null)) {
            throw IllegalArgumentException("counter requires delayDays and qtyPct")
        }
        if (action == "nl" && req.text.isNullOrBlank()) {
            throw IllegalArgumentException("nl requires a non-empty text field")
        }

        val token = config.openClawToken
            ?: throw IllegalStateException("OPENCLAW_TOKEN is not configured")

        // For structured actions, wrap the reply in the legacy JSON envelope so
        // Step 1.7's fast-path still sees `{"type":"negotiation_reply",...}`.
        // For NL, forward the raw text and let the material agent classify intent.
        val messageContent: String = if (action == "nl") {
            req.text!!
        } else {
            buildJsonObject {
                put("type", "negotiation_reply")
                put("action", action)
                put("caseId", caseId)
                req.round?.let { put("round", it) }
                req.delayDays?.let { put("delay_days", it) }
                req.qtyPct?.let { put("qty_pct", it) }
                req.baselinePlanRunId?.let { put("baseline_plan_run_id", it) }
                req.contingentPlanRunId?.let { put("contingent_plan_run_id", it) }
                req.supplyId?.let { put("supply_id", it) }
            }.toString()
        }

        val body = buildJsonObject {
            put("model", config.openClawMaterialAgent)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", messageContent)
                }
            }
            put("stream", false)
        }

        log.info(
            "negotiation-reply: caseId={} sessionKey={} action={} round={}",
            caseId, req.sessionKey, action, req.round,
        )

        // Mark the most recent active wait for this session as resolved before dispatching
        // so the UI card disappears as soon as the reply is sent.
        transaction {
            val activeId = NegotiationWaits.selectAll()
                .where {
                    (NegotiationWaits.caseId eq caseId) and
                        (NegotiationWaits.sessionKey eq req.sessionKey) and
                        (NegotiationWaits.resolvedAt.isNull())
                }
                .orderBy(NegotiationWaits.id, SortOrder.DESC)
                .firstOrNull()
                ?.get(NegotiationWaits.id)
            if (activeId != null) {
                NegotiationWaits.update({ NegotiationWaits.id eq activeId }) {
                    it[NegotiationWaits.resolvedAt] = Clock.System.now()
                    it[NegotiationWaits.resolvedAction] = action
                }
            }
        }

        // Fire-and-forget. A material-agent turn re-runs material_engine and
        // re-rates before emitting the next trace, which can exceed any
        // reasonable HTTP timeout. The UI tracks progress via the trace
        // stream, not this response.
        val bodyString = body.toString()
        openClawDispatchScope.launch {
            try {
                val response = openClawClient.post("${config.openClawUrl}/v1/chat/completions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("x-openclaw-session-key", req.sessionKey)
                    contentType(ContentType.Application.Json)
                    setBody(bodyString)
                }
                if (!response.status.isSuccess()) {
                    log.error(
                        "openclaw gateway error: status={} body={}",
                        response.status, response.bodyAsText(),
                    )
                }
            } catch (e: Exception) {
                log.error("openclaw dispatch failed: sessionKey={} err={}", req.sessionKey, e.toString())
            }
        }

        call.respond(HttpStatusCode.Accepted, buildJsonObject {
            put("status", "dispatched")
            put("sessionKey", req.sessionKey)
            put("action", action)
        })
    }

    /**
     * POST /cases/{caseId}/negotiation-waits
     * Called by the material agent at Step 1.6 to register a pending negotiation
     * prompt for the given case. Any prior unresolved wait for the same
     * (case, sessionKey) is closed as "superseded" so only the latest shows.
     */
    post("/cases/{caseId}/negotiation-waits") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val req = call.receive<NegotiationWaitRequest>()

        val insertedId = transaction {
            NegotiationWaits.update({
                (NegotiationWaits.caseId eq caseId) and
                    (NegotiationWaits.sessionKey eq req.sessionKey) and
                    (NegotiationWaits.resolvedAt.isNull())
            }) {
                it[NegotiationWaits.resolvedAt] = Clock.System.now()
                it[NegotiationWaits.resolvedAction] = "superseded"
            }
            NegotiationWaits.insert {
                it[NegotiationWaits.caseId] = caseId
                it[sessionKey] = req.sessionKey
                it[round] = req.round
                it[rating] = req.rating.uppercase()
                it[explanation] = req.explanation
                it[currentDelayDays] = req.currentDelayDays
                it[currentQtyPct] = req.currentQtyPct
                it[baselinePlanRunId] = req.baselinePlanRunId
                it[contingentPlanRunId] = req.contingentPlanRunId
                it[supplyId] = req.supplyId
                it[impactedDemandCount] = req.impactedDemandCount
            } get NegotiationWaits.id
        }
        log.info(
            "negotiation-wait registered: caseId={} sessionKey={} round={} rating={}",
            caseId, req.sessionKey, req.round, req.rating,
        )
        call.respond(HttpStatusCode.Created, buildJsonObject { put("id", insertedId) })
    }

    /**
     * GET /cases/{caseId}/negotiation-waits/active
     * Returns the latest unresolved wait for the case (or null). Polled by the UI.
     * Optional filter: ?baselinePlanRunId=N to scope to a specific chain.
     */
    get("/cases/{caseId}/negotiation-waits/active") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val baseline = call.request.queryParameters["baselinePlanRunId"]?.toIntOrNull()

        val view = transaction {
            NegotiationWaits.selectAll()
                .where {
                    var cond: Op<Boolean> = (NegotiationWaits.caseId eq caseId) and
                        (NegotiationWaits.resolvedAt.isNull())
                    if (baseline != null) cond = cond and (NegotiationWaits.baselinePlanRunId eq baseline)
                    cond
                }
                .orderBy(NegotiationWaits.id, SortOrder.DESC)
                .firstOrNull()
                ?.toWaitView()
        }
        if (view == null) {
            call.respond(HttpStatusCode.OK, JsonNull)
            return@get
        }
        call.respond(view)
    }

    /**
     * GET /negotiation-waits/latest-unresolved
     * Returns the most recent unresolved wait across all cases (or null).
     * Used by the OpenClaw main agent to detect a paused subagent and forward
     * the user's free-text chat reply to /cases/{caseId}/negotiation-reply
     * instead of treating it as a fresh greeting — the negotiation prompt is
     * surfaced through the OpenClaw chat, not a dedicated UI dialog, so main
     * needs a way to discover the paused subagent without a case context.
     */
    get("/negotiation-waits/latest-unresolved") {
        val view = transaction {
            NegotiationWaits.selectAll()
                .where { NegotiationWaits.resolvedAt.isNull() }
                .orderBy(NegotiationWaits.id, SortOrder.DESC)
                .firstOrNull()
                ?.toWaitView()
        }
        if (view == null) {
            call.respond(HttpStatusCode.OK, JsonNull)
            return@get
        }
        call.respond(view)
    }

    /**
     * GET /cases/{caseId}/negotiation-waits
     * Returns the chronological history of waits for this case (for a chain timeline view).
     */
    get("/cases/{caseId}/negotiation-waits") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val baseline = call.request.queryParameters["baselinePlanRunId"]?.toIntOrNull()
        val rows = transaction {
            NegotiationWaits.selectAll()
                .where {
                    var cond: Op<Boolean> = NegotiationWaits.caseId eq caseId
                    if (baseline != null) cond = cond and (NegotiationWaits.baselinePlanRunId eq baseline)
                    cond
                }
                .orderBy(NegotiationWaits.id, SortOrder.ASC)
                .map { it.toWaitView() }
        }
        call.respond(rows)
    }
}

private fun ResultRow.toWaitView() = NegotiationWaitView(
    id = this[NegotiationWaits.id],
    caseId = this[NegotiationWaits.caseId],
    sessionKey = this[NegotiationWaits.sessionKey],
    round = this[NegotiationWaits.round],
    rating = this[NegotiationWaits.rating],
    explanation = this[NegotiationWaits.explanation],
    currentDelayDays = this[NegotiationWaits.currentDelayDays],
    currentQtyPct = this[NegotiationWaits.currentQtyPct],
    baselinePlanRunId = this[NegotiationWaits.baselinePlanRunId],
    contingentPlanRunId = this[NegotiationWaits.contingentPlanRunId],
    supplyId = this[NegotiationWaits.supplyId],
    impactedDemandCount = this[NegotiationWaits.impactedDemandCount],
    createdAt = this[NegotiationWaits.createdAt].toString(),
    resolvedAt = this[NegotiationWaits.resolvedAt]?.toString(),
    resolvedAction = this[NegotiationWaits.resolvedAction],
)
