package com.allocator.api

import com.allocator.*
import com.allocator.services.LlmMessage
import com.allocator.services.llmChat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.AssessmentRoute")

private val DEFAULT_CRITERIA = """
the following are criteria for HIGH rating:
number of impacted demands is equal or greater than 5, or total quantity of impacted demands is equal or greater than 5000.

the following are criteria for LOW rating:
number of impacted demands is no more than 2, and total quantity of impacted demands is less than 500.

the following are criteria for MEDIUM rating:
otherwise.
""".trimIndent()

private val DEFAULT_CRITERIA_ZH = """
以下是HIGH评级的标准：
受影响需求数量大于等于5，或受影响需求的总数量大于等于5000。

以下是LOW评级的标准：
受影响需求数量不超过2，并且受影响需求的总数量小于500。

以下是MEDIUM评级的标准：
其他情况。
""".trimIndent()

/**
 * Programmatic rating for the DEFAULT_CRITERIA thresholds.
 * Used whenever the active criteria matches the default to avoid LLM arithmetic/logic errors.
 */
private fun computeDefaultRating(demandCount: Int, totalShortfall: Double): String = when {
    demandCount >= 5 || totalShortfall >= 5000.0 -> "HIGH"
    demandCount <= 2 && totalShortfall < 500.0   -> "LOW"
    else                                          -> "MEDIUM"
}

/** Detect CJK Unified Ideographs — drives prompt language selection. */
private fun containsChinese(s: String): Boolean =
    s.any { it.code in 0x4E00..0x9FFF }

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
    // Locale for the LLM-generated explanation. "zh" → Chinese prompt; anything else → English.
    // Drives prompt selection even when the stored criteria is English default.
    val locale: String? = null,
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

private fun buildPromptZh(impact: MaterialImpactResponse, criteria: String): String {
    val changeDesc = buildString {
        if (impact.deliveryDelayDays > 0) append("延迟 ${impact.deliveryDelayDays} 天")
        if (impact.quantityDecreaseAbs != null && impact.quantityDecreaseAbs > 0) {
            if (isNotEmpty()) append("，")
            append("数量减少 ${impact.quantityDecreaseAbs} 单位（绝对值）")
        } else if (impact.quantityDecreasePct > 0) {
            if (isNotEmpty()) append("，")
            append("数量减少 ${impact.quantityDecreasePct}%")
        }
        if (isEmpty()) append("未指定变更参数")
    }

    val totalShortfall = impact.impacts.sumOf { d ->
        if (d.baselineCommittedQty > 0.0 || d.contingentCommittedQty > 0.0)
            d.baselineCommittedQty - d.contingentCommittedQty
        else
            d.consumedSupplyQty
    }
    val demandCount = impact.impactedDemandCount

    val demandLines = impact.impacts.joinToString("\n") { d ->
        val shortfall = d.baselineCommittedQty - d.contingentCommittedQty
        "- ${d.demandId}  客户=${d.customerId}  优先级=${d.priority ?: "n/a"}  需求时间=${d.requestDueTime ?: "n/a"}  需求量=${d.requestedQty}  基线承诺量=${d.baselineCommittedQty}  应急承诺量=${d.contingentCommittedQty}  缺口=${shortfall}  状态=${d.status}"
    }.ifEmpty { "  （无）" }

    return """
你是供应链风险分析师。

待评估的供应变更：
- 供应 ID：${impact.supply.supplyId}
- 产品：${impact.supply.productId}  地点：${impact.supply.locationId ?: "n/a"}  供应商：${impact.supply.vendorId ?: "n/a"}
- 初始数量：${impact.supply.qty}
- 变更：$changeDesc

预计算摘要（权威数据——请勿根据下方明细重新计算）：
- 受影响需求数量：$demandCount
- 总缺口数量：$totalShortfall

受影响需求明细（仅供参考）：
$demandLines

评估标准：
$criteria

说明：
1. 仅使用上方的预计算摘要数值（受影响需求数量=$demandCount，总缺口=$totalShortfall）。
2. 独立评估标准中的每一个条件，包括所有 OR 分支。
3. 如果任何一个条件满足某评级，则该评级适用——不要在第一个未满足的条件处停止。

请将整体影响严重程度分类为：LOW、MEDIUM、HIGH 之一。

请严格按照以下格式回答（前后不要有其他文字）：
RATING: <LOW|MEDIUM|HIGH>
EXPLANATION: <2-3 句中文说明；请列出触发该评级的具体预计算数值>
""".trimIndent()
}

private fun buildExplanationPromptZh(impact: MaterialImpactResponse, criteria: String, rating: String, totalShortfall: Double): String {
    val changeDesc = buildString {
        if (impact.deliveryDelayDays > 0) append("延迟 ${impact.deliveryDelayDays} 天")
        if (impact.quantityDecreaseAbs != null && impact.quantityDecreaseAbs > 0) {
            if (isNotEmpty()) append("，")
            append("数量减少 ${impact.quantityDecreaseAbs} 单位（绝对值）")
        } else if (impact.quantityDecreasePct > 0) {
            if (isNotEmpty()) append("，")
            append("数量减少 ${impact.quantityDecreasePct}%")
        }
        if (isEmpty()) append("未指定变更参数")
    }
    return """
你是供应链风险分析师，负责撰写影响摘要。

供应变更：${impact.supply.supplyId}（${impact.supply.productId}），$changeDesc
受影响需求数：${impact.impactedDemandCount}，总缺口数量：$totalShortfall

所用评估标准：
$criteria

该影响已被评定为：$rating

请用 2-3 句中文说明该评级与上述标准的关系，
并引用决定该结果的具体数值（需求数量与总缺口）。
仅输出说明文字——不要添加标签或前言。
""".trimIndent()
}

// Pin assessment LLM calls to OpenAI regardless of the global LLM_PROVIDER. OpenClaw's
// /v1/chat/completions and the Claude Pro sub token both have caps/quirks that surface as
// opaque 5xx; OpenAI's API is the most reliable for short structured prompts like RATING.
private const val ASSESSMENT_PROVIDER = "openai"

private suspend fun callLlm(prompt: String): Pair<String, String> {
    val text = llmChat(
        messages = listOf(LlmMessage("user", prompt)),
        maxTokens = 512,
        provider = ASSESSMENT_PROVIDER,
    )

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
private suspend fun callLlmForText(prompt: String): String =
    llmChat(
        messages = listOf(LlmMessage("user", prompt)),
        maxTokens = 256,
        provider = ASSESSMENT_PROVIDER,
    ).trim()

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

    // Assessments must be tied to the active (saved) plan — never a contingent re-plan.
    // Validate both the caller-supplied planRunId and (Mode B) the one carried in the impact payload.
    fun requireActivePlanRun(planRunId: Int, source: String) {
        val status = transaction {
            PlanRuns.selectAll()
                .where { (PlanRuns.id eq planRunId) and (PlanRuns.caseId eq effectiveCaseId) }
                .firstOrNull()?.get(PlanRuns.status)
        } ?: throw IllegalArgumentException("$source planRunId=$planRunId not found for caseId=$effectiveCaseId")
        if (status != "success") {
            throw IllegalArgumentException(
                "$source planRunId=$planRunId has status='$status'; assessment requires an active (success) plan run"
            )
        }
    }
    req.planRunId?.let { requireActivePlanRun(it, "request") }
    req.impact?.planRunId?.let { requireActivePlanRun(it, "impact") }

    // Idempotency: reuse an existing assessment with identical parameters to prevent duplicate rows
    // when the agent pipeline traverses both order_engine (Mode B) and planning_engine (Mode A)
    // for the same material event.
    // Skip the cache when the caller's criteria language differs from the cached explanation's language,
    // so a locale switch (zh ↔ en) regenerates rather than returning a stale translation.
    val idempotencySupplyId = req.supplyId ?: req.impact?.supply?.supplyId
    val idempotencyPlanRunId = req.planRunId ?: req.impact?.planRunId
    if (idempotencySupplyId != null) {
        data class CachedAssessment(
            val id: Int,
            val rating: String,
            val explanation: String,
            val criteria: String,
            val impactedDemandCount: Int,
            val impactsJson: String?,
            val createdAt: String,
        )
        val cached: CachedAssessment? = transaction {
            var q = MaterialImpactAssessments.selectAll()
                .where { MaterialImpactAssessments.caseId eq effectiveCaseId }
                .andWhere { MaterialImpactAssessments.supplyId eq idempotencySupplyId }
                .andWhere { MaterialImpactAssessments.deliveryDelayDays eq req.deliveryDelayDays }
                .andWhere { MaterialImpactAssessments.quantityDecreasePct eq req.quantityDecreasePct }
            if (idempotencyPlanRunId != null)
                q = q.andWhere { MaterialImpactAssessments.planRunId eq idempotencyPlanRunId }
            q.orderBy(MaterialImpactAssessments.id, SortOrder.DESC).firstOrNull()?.let {
                CachedAssessment(
                    id = it[MaterialImpactAssessments.id],
                    rating = it[MaterialImpactAssessments.rating],
                    explanation = it[MaterialImpactAssessments.explanation],
                    criteria = it[MaterialImpactAssessments.criteria],
                    impactedDemandCount = it[MaterialImpactAssessments.impactedDemandCount],
                    impactsJson = it[MaterialImpactAssessments.impactsJson],
                    createdAt = it[MaterialImpactAssessments.createdAt].toString(),
                )
            }
        }
        val requestedZh: Boolean? = when {
            req.locale?.startsWith("zh", ignoreCase = true) == true -> true
            req.locale != null -> false
            req.criteria != null -> containsChinese(req.criteria)
            else -> null
        }
        val cachedExplanationZh = cached?.explanation?.let { containsChinese(it) }
        val languageMatches = requestedZh == null || cachedExplanationZh == null || requestedZh == cachedExplanationZh
        if (cached != null && languageMatches) {
            // Mode A (computeMaterialImpact, pegging-walk) and Mode B (full re-plan
            // diff passed in via req.impact) produce *different* impacts lists for
            // the same supply change. We must not recompute via Mode A on cache hit:
            // the cached rating + explanation refer to a specific impacts list, and
            // returning Mode A's numbers next to Mode B's cached explanation made
            // the user see "13 demands" in one email and "27 demands" in the next.
            //
            // Prefer in order: caller-supplied impact (Mode B, freshest) → the
            // impacts list snapshotted at rate-time → an empty list (legacy rows
            // that pre-date this column). Re-rate only when no cached impacts and
            // the caller didn't supply one either.
            val cachedImpacts: List<MaterialImpactedDemand>? = cached.impactsJson?.let { raw ->
                runCatching { Json.decodeFromString<List<MaterialImpactedDemand>>(raw) }.getOrNull()
            }
            val canServe = req.impact != null || cachedImpacts != null
            if (canServe) {
                val supply: MaterialSupplyDetail
                val planRunIdOut: Int?
                val impacts: List<MaterialImpactedDemand>
                val count: Int
                if (req.impact != null) {
                    supply = req.impact.supply
                    planRunIdOut = req.impact.planRunId
                    impacts = req.impact.impacts
                    count = req.impact.impactedDemandCount
                } else {
                    // Need supply detail for the response; cheap DB lookup.
                    val supplyRow = transaction {
                        Supplies.selectAll()
                            .where { (Supplies.caseId eq effectiveCaseId) and (Supplies.supplyId eq idempotencySupplyId) }
                            .firstOrNull()
                    }
                    supply = supplyRow?.let {
                        MaterialSupplyDetail(
                            supplyId = it[Supplies.supplyId],
                            productId = it[Supplies.productId],
                            qty = it[Supplies.qty],
                            supplyDate = it[Supplies.supplyDate],
                            locationId = it[Supplies.locationId],
                            vendorId = it[Supplies.vendorId],
                        )
                    } ?: MaterialSupplyDetail(idempotencySupplyId, "", 0.0, null, null, null)
                    planRunIdOut = idempotencyPlanRunId
                    impacts = cachedImpacts!!
                    count = cached.impactedDemandCount
                }
                log.info("assessment cache hit: id={} supplyId={} planRunId={} rating={} count={}",
                    cached.id, idempotencySupplyId, idempotencyPlanRunId, cached.rating, count)
                call.respond(
                    AssessmentResponse(
                        id = cached.id,
                        rating = cached.rating,
                        explanation = cached.explanation,
                        criteria = cached.criteria,
                        caseId = effectiveCaseId,
                        planRunId = planRunIdOut,
                        supply = supply,
                        impactedDemandCount = count,
                        impacts = impacts,
                        createdAt = cached.createdAt,
                    )
                )
                return
            }
            log.info(
                "assessment cache row {} has no impacts snapshot and caller didn't supply impact — re-rating",
                cached.id,
            )
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
    val useZh = req.locale?.startsWith("zh", ignoreCase = true) == true || containsChinese(criteria)
    val (rating, explanation) = if (isDefaultCriteria) {
        val computedRating = computeDefaultRating(impact.impactedDemandCount, totalShortfall)
        log.info("assessment: using programmatic rating={} (demandCount={} totalShortfall={})",
            computedRating, impact.impactedDemandCount, totalShortfall)
        val explanationPrompt = if (useZh)
            buildExplanationPromptZh(impact, criteria, computedRating, totalShortfall)
        else
            buildExplanationPrompt(impact, criteria, computedRating, totalShortfall)
        val explanationText = callLlmForText(explanationPrompt)
        Pair(computedRating, explanationText)
    } else {
        val prompt = if (useZh) buildPromptZh(impact, criteria) else buildPrompt(impact, criteria)
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
            it[MaterialImpactAssessments.impactedDemandCount] = impact.impactedDemandCount
            it[MaterialImpactAssessments.impactsJson]         = Json.encodeToString(ListSerializer(MaterialImpactedDemand.serializer()), impact.impacts)
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
