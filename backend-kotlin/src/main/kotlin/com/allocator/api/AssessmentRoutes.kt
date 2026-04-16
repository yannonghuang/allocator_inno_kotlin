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
the following are criteria for HIGH rating:
number of impacted demands is equal or greater than 5, or total quantity of impacted demands is equal or greater than 5000.

the following are criteria for LOW rating:
number of impacted demands is no more than 2, or total quantity of impacted demands is less than 500.

the following are criteria for MEDIUM rating:
otherwise.
""".trimIndent()

private val DEFAULT_CRITERIA_ZH = """
以下是HIGH评级的标准：
受影响需求数量大于等于5，或受影响需求的总数量大于等于5000。

以下是LOW评级的标准：
受影响需求数量不超过2，或受影响需求的总数量小于500。

以下是MEDIUM评级的标准：
其他情况。
""".trimIndent()

/**
 * Programmatic rating for the DEFAULT_CRITERIA thresholds.
 * Used whenever the active criteria matches the default to avoid LLM arithmetic/logic errors.
 */
private fun computeDefaultRating(demandCount: Int, totalShortfall: Double): String = when {
    demandCount >= 5 || totalShortfall >= 5000.0 -> "HIGH"
    demandCount <= 2 || totalShortfall < 500.0   -> "LOW"
    else                                          -> "MEDIUM"
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class AssessmentRequest(
    // Mode A: supply change params → endpoint computes impact then assesses
    val supplyId: String? = null,
    val deliveryDelayDays: Int = 0,
    val quantityDecreasePct: Double = 0.0,
    val quantityDecreaseAbs: Double? = null,
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
    val planRunId: Int? = null,
    val deliveryDelayDays: Int,
    val quantityDecreasePct: Double,
    val quantityDecreaseAbs: Double? = null,
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

// ── OpenAI client (lazily created, shared) ────────────────────────────────────

private val openAiClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 90_000 }
    }
}

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class OpenAiRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<OpenAiMessage>,
)

@Serializable
private data class OpenAiChoice(val message: OpenAiMessage)

@Serializable
private data class OpenAiResponse(val choices: List<OpenAiChoice>)

// ── LLM helpers ───────────────────────────────────────────────────────────────

private fun buildPrompt(impact: MaterialImpactResponse, criteria: String): String {
    val changeDesc = buildString {
        if (impact.deliveryDelayDays > 0) append("delayed by ${impact.deliveryDelayDays} days")
        if (impact.quantityDecreaseAbs != null && impact.quantityDecreaseAbs > 0) {
            if (isNotEmpty()) append(", ")
            append("quantity reduced by ${impact.quantityDecreaseAbs} units (absolute)")
        } else if (impact.quantityDecreasePct > 0) {
            if (isNotEmpty()) append(", ")
            append("quantity reduced by ${impact.quantityDecreasePct}%")
        }
        if (isEmpty()) append("no change parameters specified")
    }

    // Pre-compute aggregates so the LLM evaluates criteria against explicit numbers.
    // Fall back to consumedSupplyQty for the legacy sync path where re-plan fields are absent.
    val totalShortfall = impact.impacts.sumOf { d ->
        if (d.baselineCommittedQty > 0.0 || d.contingentCommittedQty > 0.0)
            d.baselineCommittedQty - d.contingentCommittedQty
        else
            d.consumedSupplyQty
    }
    val demandCount = impact.impactedDemandCount

    val demandLines = impact.impacts.joinToString("\n") { d ->
        val shortfall = d.baselineCommittedQty - d.contingentCommittedQty
        "- ${d.demandId}  customer=${d.customerId}  priority=${d.priority ?: "n/a"}  due=${d.requestDueTime ?: "n/a"}  requested=${d.requestedQty}  baseline-committed=${d.baselineCommittedQty}  contingent-committed=${d.contingentCommittedQty}  shortfall=${shortfall}  status=${d.status}"
    }.ifEmpty { "  (none)" }

    return """
You are a supply chain risk analyst.

Supply change under evaluation:
- Supply ID: ${impact.supply.supplyId}
- Product: ${impact.supply.productId}  Location: ${impact.supply.locationId ?: "n/a"}  Vendor: ${impact.supply.vendorId ?: "n/a"}
- Initial quantity: ${impact.supply.qty}
- Change: $changeDesc

PRE-COMPUTED SUMMARY (authoritative — do NOT recalculate from the detail rows below):
- Number of impacted demands: $demandCount
- Total shortfall quantity: $totalShortfall

Impacted demand details (for context only):
$demandLines

Assessment criteria:
$criteria

Instructions:
1. Use ONLY the pre-computed summary figures above (impacted demand count = $demandCount, total shortfall = $totalShortfall).
2. Evaluate EVERY condition in the criteria independently, including all OR branches.
3. If ANY condition for a rating is satisfied, that rating applies — do not stop at the first failing condition.

Classify the overall impact severity as exactly one of: LOW, MEDIUM, or HIGH.

Respond in this exact format (no other text before or after):
RATING: <LOW|MEDIUM|HIGH>
EXPLANATION: <2–3 sentences; state the specific pre-computed values that triggered the rating>
""".trimIndent()
}

/**
 * Prompt variant for when the rating has already been determined programmatically.
 * The LLM is asked only to write the explanation — no classification task.
 */
private fun buildExplanationPrompt(impact: MaterialImpactResponse, criteria: String, rating: String, totalShortfall: Double): String {
    val changeDesc = buildString {
        if (impact.deliveryDelayDays > 0) append("delayed by ${impact.deliveryDelayDays} days")
        if (impact.quantityDecreaseAbs != null && impact.quantityDecreaseAbs > 0) {
            if (isNotEmpty()) append(", ")
            append("quantity reduced by ${impact.quantityDecreaseAbs} units (absolute)")
        } else if (impact.quantityDecreasePct > 0) {
            if (isNotEmpty()) append(", ")
            append("quantity reduced by ${impact.quantityDecreasePct}%")
        }
        if (isEmpty()) append("no change parameters specified")
    }
    return """
You are a supply chain risk analyst writing an impact summary.

Supply change: ${impact.supply.supplyId} (${impact.supply.productId}), $changeDesc
Impacted demands: ${impact.impactedDemandCount}, total shortfall quantity: $totalShortfall

Assessment criteria used:
$criteria

The impact has been rated: $rating

Write 2–3 sentences explaining this rating in terms of the criteria above.
Cite the specific numbers (demand count and total shortfall) that determined the outcome.
Output ONLY the explanation text — no labels, no preamble.
""".trimIndent()
}

private suspend fun callLlm(prompt: String): Pair<String, String> {
    val apiKey = config.openAiApiKey
        ?: throw IllegalStateException("OPENAI_API_KEY is not configured")

    val reqBody = OpenAiRequest(
        model = config.assessmentModel,
        maxTokens = 512,
        messages = listOf(OpenAiMessage(role = "user", content = prompt)),
    )

    val resp = openAiClient.post("https://api.openai.com/v1/chat/completions") {
        header("Authorization", "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(reqBody)
    }

    if (!resp.status.isSuccess()) {
        val body = resp.body<String>()
        throw IllegalStateException("OpenAI API error ${resp.status.value}: $body")
    }

    val openAiResp = resp.body<OpenAiResponse>()
    val text = openAiResp.choices.firstOrNull()?.message?.content
        ?: throw IllegalStateException("OpenAI response contained no content")

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

/** Call LLM and return the raw response text (no RATING: parsing — for explanation-only prompts). */
private suspend fun callLlmForText(prompt: String): String {
    val apiKey = config.openAiApiKey
        ?: throw IllegalStateException("OPENAI_API_KEY is not configured")

    val reqBody = OpenAiRequest(
        model = config.assessmentModel,
        maxTokens = 256,
        messages = listOf(OpenAiMessage(role = "user", content = prompt)),
    )

    val resp = openAiClient.post("https://api.openai.com/v1/chat/completions") {
        header("Authorization", "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(reqBody)
    }

    if (!resp.status.isSuccess()) {
        val body = resp.body<String>()
        throw IllegalStateException("OpenAI API error ${resp.status.value}: $body")
    }

    return resp.body<OpenAiResponse>().choices.firstOrNull()?.message?.content?.trim()
        ?: throw IllegalStateException("OpenAI response contained no content")
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
        val planRunId = call.request.queryParameters["planRunId"]?.toIntOrNull()
        val deliveryDelayDays = call.request.queryParameters["deliveryDelayDays"]?.toIntOrNull()
        val quantityDecreasePct = call.request.queryParameters["quantityDecreasePct"]?.toDoubleOrNull()
        val summaries = transaction {
            var q = MaterialImpactAssessments.selectAll()
                .where { MaterialImpactAssessments.caseId eq caseId }
            if (supplyId != null) q = q.andWhere { MaterialImpactAssessments.supplyId eq supplyId }
            if (planRunId != null) q = q.andWhere { MaterialImpactAssessments.planRunId eq planRunId }
            if (deliveryDelayDays != null) q = q.andWhere { MaterialImpactAssessments.deliveryDelayDays eq deliveryDelayDays }
            if (quantityDecreasePct != null) q = q.andWhere { MaterialImpactAssessments.quantityDecreasePct eq quantityDecreasePct }
            q.orderBy(MaterialImpactAssessments.id, SortOrder.DESC).map { r ->
                AssessmentSummary(
                    id = r[MaterialImpactAssessments.id],
                    supplyId = r[MaterialImpactAssessments.supplyId],
                    planRunId = r[MaterialImpactAssessments.planRunId],
                    deliveryDelayDays = r[MaterialImpactAssessments.deliveryDelayDays],
                    quantityDecreasePct = r[MaterialImpactAssessments.quantityDecreasePct],
                    quantityDecreaseAbs = r[MaterialImpactAssessments.quantityDecreaseAbs],
                    rating = r[MaterialImpactAssessments.rating],
                    explanation = r[MaterialImpactAssessments.explanation],
                    criteria = r[MaterialImpactAssessments.criteria],
                    createdAt = r[MaterialImpactAssessments.createdAt].toString(),
                )
            }
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

    // Idempotency: reuse an existing assessment with identical parameters (same agent may be called twice).
    // Only applies to Mode A (supplyId path) — Mode B always has a fresh impact object.
    if (req.impact == null && req.supplyId != null) {
        val existing = transaction {
            var q = MaterialImpactAssessments.selectAll()
                .where { MaterialImpactAssessments.caseId eq effectiveCaseId }
                .andWhere { MaterialImpactAssessments.supplyId eq req.supplyId }
                .andWhere { MaterialImpactAssessments.deliveryDelayDays eq req.deliveryDelayDays }
                .andWhere { MaterialImpactAssessments.quantityDecreasePct eq req.quantityDecreasePct }
            if (req.planRunId != null)
                q = q.andWhere { MaterialImpactAssessments.planRunId eq req.planRunId }
            q.orderBy(MaterialImpactAssessments.id, SortOrder.DESC).firstOrNull()
        }
        if (existing != null) {
            log.info("assessment cache hit: id={} supplyId={} planRunId={} rating={}",
                existing[MaterialImpactAssessments.id], req.supplyId, req.planRunId, existing[MaterialImpactAssessments.rating])
            // Re-run impact analysis to get current demand details (cheap — no LLM).
            val impact = computeMaterialImpact(MaterialImpactRequest(
                supplyId = req.supplyId,
                deliveryDelayDays = req.deliveryDelayDays,
                quantityDecreasePct = req.quantityDecreasePct,
                quantityDecreaseAbs = req.quantityDecreaseAbs,
                planRunId = req.planRunId,
            ))
            call.respond(
                AssessmentResponse(
                    id = existing[MaterialImpactAssessments.id],
                    rating = existing[MaterialImpactAssessments.rating],
                    explanation = existing[MaterialImpactAssessments.explanation],
                    criteria = existing[MaterialImpactAssessments.criteria],
                    caseId = effectiveCaseId,
                    planRunId = impact.planRunId,
                    supply = impact.supply,
                    impactedDemandCount = impact.impactedDemandCount,
                    impacts = impact.impacts,
                    createdAt = existing[MaterialImpactAssessments.createdAt].toString(),
                )
            )
            return
        }
    }

    // Resolve impact — Mode B preferred, Mode A if only supply params given
    val impact: MaterialImpactResponse = when {
        req.impact != null -> req.impact
        req.supplyId != null -> {
            val impactReq = MaterialImpactRequest(
                supplyId = req.supplyId,
                deliveryDelayDays = req.deliveryDelayDays,
                quantityDecreasePct = req.quantityDecreasePct,
                quantityDecreaseAbs = req.quantityDecreaseAbs,
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

    // Determine rating — programmatically for default criteria; LLM for custom criteria
    // totalShortfall: prefer re-plan diff fields; fall back to consumedSupplyQty for the
    // legacy sync path (computeMaterialImpact) which doesn't run a contingent plan.
    val totalShortfall = impact.impacts.sumOf { d ->
        if (d.baselineCommittedQty > 0.0 || d.contingentCommittedQty > 0.0)
            d.baselineCommittedQty - d.contingentCommittedQty
        else
            d.consumedSupplyQty
    }
    val isDefaultCriteria = criteria.trim() == DEFAULT_CRITERIA.trim() || criteria.trim() == DEFAULT_CRITERIA_ZH.trim()
    val (rating, explanation) = if (isDefaultCriteria) {
        val computedRating = computeDefaultRating(impact.impactedDemandCount, totalShortfall)
        log.info("assessment: using programmatic rating={} (demandCount={} totalShortfall={})",
            computedRating, impact.impactedDemandCount, totalShortfall)
        val explanationPrompt = buildExplanationPrompt(impact, criteria, computedRating, totalShortfall)
        val explanationText = callLlmForText(explanationPrompt)
        Pair(computedRating, explanationText)
    } else {
        val prompt = buildPrompt(impact, criteria)
        callLlm(prompt)
    }

    // Persist result
    val assessmentId = transaction {
        val stmt = MaterialImpactAssessments.insert {
            it[MaterialImpactAssessments.caseId]              = effectiveCaseId
            it[MaterialImpactAssessments.planRunId]           = impact.planRunId
            it[MaterialImpactAssessments.supplyId]            = impact.supply.supplyId
            it[MaterialImpactAssessments.deliveryDelayDays]   = impact.deliveryDelayDays
            it[MaterialImpactAssessments.quantityDecreasePct] = impact.quantityDecreasePct
            it[MaterialImpactAssessments.quantityDecreaseAbs] = impact.quantityDecreaseAbs
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
