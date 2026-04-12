package com.allocator.api

import com.allocator.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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

private val log = LoggerFactory.getLogger("com.allocator.AssessmentRoute")

private val DEFAULT_CRITERIA = """
Evaluate the impact severity based on:
- Number of impacted demands and their priorities
- Total quantity at risk relative to the supply quantity
- Proximity of demand due dates to the supply change
- Magnitude of the delay or quantity reduction
""".trimIndent()

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class AssessmentRequest(
    // Mode A: supply change params → endpoint computes impact then assesses
    val supplyId: String? = null,
    val deliveryDelayDays: Int = 0,
    val quantityDecreasePct: Double = 0.0,
    val planRunId: Int? = null,
    // Mode B: pre-computed impact (agent passes this in; no recomputation)
    val impact: MaterialImpactResponse? = null,
    // Context: needed to load case criteria and persist result
    val caseId: Int? = null,
    // Optional override: if omitted, case's assessment_criteria is loaded; falls back to DEFAULT_CRITERIA
    val criteria: String? = null,
)

@Serializable
data class AssessmentSummary(
    val id: Int,
    val supplyId: String,
    val deliveryDelayDays: Int,
    val quantityDecreasePct: Double,
    val rating: String,
    val explanation: String,
    val criteria: String,
    val createdAt: String,
)

@Serializable
data class AssessmentResponse(
    val id: Int,
    val rating: String,
    val explanation: String,
    val criteria: String,
    val caseId: Int,
    val planRunId: Int?,
    val supply: MaterialSupplyDetail,
    val impactedDemandCount: Int,
    val impacts: List<MaterialImpactedDemand>,
    val createdAt: String,
)

// ── Anthropic client (lazily created, shared) ─────────────────────────────────

private val anthropicClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 90_000 }
    }
}

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessage>,
)

@Serializable
private data class AnthropicContentBlock(val type: String, val text: String? = null)

@Serializable
private data class AnthropicResponse(val content: List<AnthropicContentBlock>)

// ── LLM helpers ───────────────────────────────────────────────────────────────

private fun buildPrompt(impact: MaterialImpactResponse, criteria: String): String {
    val changeDesc = buildString {
        if (impact.deliveryDelayDays > 0) append("delayed by ${impact.deliveryDelayDays} days")
        if (impact.quantityDecreasePct > 0) {
            if (isNotEmpty()) append(", ")
            append("quantity reduced by ${impact.quantityDecreasePct}%")
        }
        if (isEmpty()) append("no change parameters specified")
    }
    val demandLines = impact.impacts.joinToString("\n") { d ->
        "- ${d.demandId}  customer=${d.customerId}  priority=${d.priority ?: "n/a"}  due=${d.requestDueTime ?: "n/a"}  requested=${d.requestedQty}  supply-consumed=${d.consumedSupplyQty}  status=${d.status}"
    }.ifEmpty { "  (none)" }

    return """
You are a supply chain risk analyst.

Assessment criteria:
$criteria

Supply change under evaluation:
- Supply ID: ${impact.supply.supplyId}
- Product: ${impact.supply.productId}  Location: ${impact.supply.locationId ?: "n/a"}  Vendor: ${impact.supply.vendorId ?: "n/a"}
- Initial quantity: ${impact.supply.qty}
- Change: $changeDesc

Impacted committed demands (${impact.impactedDemandCount} total):
$demandLines

Classify the overall impact severity as exactly one of: LOW, MEDIUM, or HIGH.

Respond in this exact format (no other text before or after):
RATING: <LOW|MEDIUM|HIGH>
EXPLANATION: <2–3 sentences; use the same language as the assessment criteria above>
""".trimIndent()
}

private suspend fun callLlm(prompt: String): Pair<String, String> {
    val apiKey = config.anthropicApiKey
        ?: throw IllegalStateException("ANTHROPIC_API_KEY is not configured")

    val reqBody = AnthropicRequest(
        model = config.assessmentModel,
        maxTokens = 512,
        messages = listOf(AnthropicMessage(role = "user", content = prompt)),
    )

    val resp = anthropicClient.post("https://api.anthropic.com/v1/messages") {
        header("x-api-key", apiKey)
        header("anthropic-version", "2023-06-01")
        contentType(ContentType.Application.Json)
        setBody(reqBody)
    }

    if (!resp.status.isSuccess()) {
        val body = resp.body<String>()
        throw IllegalStateException("Anthropic API error ${resp.status.value}: $body")
    }

    val anthropicResp = resp.body<AnthropicResponse>()
    val text = anthropicResp.content.firstOrNull { it.type == "text" }?.text
        ?: throw IllegalStateException("Anthropic response contained no text block")

    val ratingLine = text.lines().firstOrNull { it.startsWith("RATING:") }
        ?: throw IllegalStateException("LLM response missing RATING line. Raw: $text")
    val rating = ratingLine.removePrefix("RATING:").trim().uppercase()
    if (rating !in setOf("LOW", "MEDIUM", "HIGH")) {
        throw IllegalStateException("LLM returned unrecognised rating '$rating'. Raw: $text")
    }
    val explanation = text.substringAfter("EXPLANATION:").trim()

    log.info("LLM assessment: rating={} explanation_len={}", rating, explanation.length)
    return Pair(rating, explanation)
}

// ── Routes ────────────────────────────────────────────────────────────────────

fun Routing.assessmentRoutes() {

    // ── GET  /cases/{caseId}/assessment-criteria ──────────────────────────────
    get("/cases/{caseId}/assessment-criteria") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val criteria = transaction {
            Cases.selectAll().where { Cases.id eq caseId }
                .firstOrNull()?.get(Cases.assessmentCriteria)
        }
        call.respond(mapOf("criteria" to criteria))
    }

    // ── PUT  /cases/{caseId}/assessment-criteria ──────────────────────────────
    put("/cases/{caseId}/assessment-criteria") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val body = call.receive<Map<String, String>>()
        val criteria = body["criteria"] ?: throw IllegalArgumentException("criteria field required")
        transaction {
            Cases.update({ Cases.id eq caseId }) { it[Cases.assessmentCriteria] = criteria }
        }
        call.respond(mapOf("criteria" to criteria))
    }

    // ── GET  /cases/{caseId}/material-impact-assessments ─────────────────────
    get("/cases/{caseId}/material-impact-assessments") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        val supplyId = call.request.queryParameters["supplyId"]
        val rows = transaction {
            var q = MaterialImpactAssessments.selectAll()
                .where { MaterialImpactAssessments.caseId eq caseId }
            if (supplyId != null) q = q.andWhere { MaterialImpactAssessments.supplyId eq supplyId }
            q.orderBy(MaterialImpactAssessments.id, SortOrder.DESC).toList()
        }
        val summaries = rows.map { r ->
            AssessmentSummary(
                id = r[MaterialImpactAssessments.id],
                supplyId = r[MaterialImpactAssessments.supplyId],
                deliveryDelayDays = r[MaterialImpactAssessments.deliveryDelayDays],
                quantityDecreasePct = r[MaterialImpactAssessments.quantityDecreasePct],
                rating = r[MaterialImpactAssessments.rating],
                explanation = r[MaterialImpactAssessments.explanation],
                criteria = r[MaterialImpactAssessments.criteria],
                createdAt = r[MaterialImpactAssessments.createdAt].toString(),
            )
        }
        call.respond(summaries)
    }

    // ── POST /material-impact-assessment  (top-level — agent + UI) ────────────
    post("/material-impact-assessment") {
        handleAssessment(call, caseIdOverride = null)
    }

    // ── POST /cases/{caseId}/material-impact-assessment  (UI convenience) ─────
    post("/cases/{caseId}/material-impact-assessment") {
        val caseId = call.parameters["caseId"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid caseId")
        handleAssessment(call, caseIdOverride = caseId)
    }
}

private suspend fun handleAssessment(
    call: ApplicationCall,
    caseIdOverride: Int?,
) {
    val rawBody = call.receiveText()
    val req = try {
        Json { ignoreUnknownKeys = true }.decodeFromString<AssessmentRequest>(rawBody)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid request body: ${e.message}")
    }

    // Resolve effective caseId (path wins over body)
    val effectiveCaseId = caseIdOverride
        ?: req.caseId
        ?: req.impact?.caseId
        ?: throw IllegalArgumentException("caseId required (path param, body.caseId, or body.impact.caseId)")

    // Resolve impact — Mode B preferred, Mode A if only supply params given
    val impact: MaterialImpactResponse = when {
        req.impact != null -> req.impact
        req.supplyId != null -> {
            val impactReq = MaterialImpactRequest(
                supplyId = req.supplyId,
                deliveryDelayDays = req.deliveryDelayDays,
                quantityDecreasePct = req.quantityDecreasePct,
                planRunId = req.planRunId,
            )
            computeMaterialImpact(impactReq)
        }
        else -> throw IllegalArgumentException("Provide either 'impact' (Mode B) or 'supplyId' (Mode A)")
    }

    // Resolve criteria: request > case > default
    val criteria = req.criteria?.takeIf { it.isNotBlank() }
        ?: transaction {
            Cases.selectAll().where { Cases.id eq effectiveCaseId }
                .firstOrNull()?.get(Cases.assessmentCriteria)
        }?.takeIf { it.isNotBlank() }
        ?: DEFAULT_CRITERIA

    log.info(
        "assessment request: caseId={} supplyId={} delayDays={} impactedDemands={}",
        effectiveCaseId, impact.supply.supplyId, impact.deliveryDelayDays, impact.impactedDemandCount,
    )

    // Call LLM
    val prompt = buildPrompt(impact, criteria)
    val (rating, explanation) = callLlm(prompt)

    // Persist result
    val assessmentId = transaction {
        val stmt = MaterialImpactAssessments.insert {
            it[MaterialImpactAssessments.caseId]              = effectiveCaseId
            it[MaterialImpactAssessments.planRunId]           = impact.planRunId
            it[MaterialImpactAssessments.supplyId]            = impact.supply.supplyId
            it[MaterialImpactAssessments.deliveryDelayDays]   = impact.deliveryDelayDays
            it[MaterialImpactAssessments.quantityDecreasePct] = impact.quantityDecreasePct
            it[MaterialImpactAssessments.criteria]            = criteria
            it[MaterialImpactAssessments.rating]              = rating
            it[MaterialImpactAssessments.explanation]         = explanation
        }
        stmt[MaterialImpactAssessments.id]
    }

    log.info("assessment persisted: id={} rating={}", assessmentId, rating)

    call.respond(
        AssessmentResponse(
            id = assessmentId,
            rating = rating,
            explanation = explanation,
            criteria = criteria,
            caseId = effectiveCaseId,
            planRunId = impact.planRunId,
            supply = impact.supply,
            impactedDemandCount = impact.impactedDemandCount,
            impacts = impact.impacts,
            createdAt = java.time.Instant.now().toString(),
        )
    )
}
