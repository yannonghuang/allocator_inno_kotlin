package com.allocator.api

import com.allocator.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.MaterialImpactRoute")

@Serializable
data class MaterialImpactRequest(
    val supplyId: String,
    val deliveryDelayDays: Int = 0,
    val quantityDecreasePct: Double = 0.0,
)

@Serializable
data class MaterialSupplyDetail(
    val supplyId: String,
    val productId: String,
    val qty: Double,
    val supplyDate: String?,
    val locationId: String?,
    val vendorId: String?,
)

@Serializable
data class MaterialImpactedDemand(
    val demandId: String,
    val productId: String,
    val locationId: String?,
    val customerId: String,
    val description: String?,
    val priority: Int?,
    val requestDueTime: String?,
    val requestedQty: Double,
    val consumedSupplyQty: Double,   // units of the impacted supply consumed (BOM-scaled, not demand units)
    val status: String,
)

@Serializable
data class MaterialImpactResponse(
    val supply: MaterialSupplyDetail,
    val deliveryDelayDays: Int,
    val quantityDecreasePct: Double,
    val impactedDemandCount: Int,
    val impacts: List<MaterialImpactedDemand>,
    val note: String? = null,
)

// ── Tree-walking helpers ───────────────────────────────────────────────────────

/**
 * Returns true if any supply node in the pegging tree has a supply_id matching the given supplyId.
 */
private fun treeContainsSupply(node: JsonElement, supplyId: String): Boolean {
    val obj = node as? JsonObject ?: return false
    if (obj["type"]?.jsonPrimitive?.contentOrNull == "supply" &&
        obj["supply_id"]?.jsonPrimitive?.contentOrNull?.trim() == supplyId
    ) return true
    return (obj["children"] as? JsonArray ?: JsonArray(emptyList()))
        .any { treeContainsSupply(it, supplyId) }
}

/**
 * Sums the quantity consumed by supply nodes in the pegging tree whose supply_id matches the given supplyId.
 */
private fun supplyQtyInTree(node: JsonElement, supplyId: String): Double {
    val obj = node as? JsonObject ?: return 0.0
    if (obj["type"]?.jsonPrimitive?.contentOrNull == "supply" &&
        obj["supply_id"]?.jsonPrimitive?.contentOrNull?.trim() == supplyId
    ) return obj["quantity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    return (obj["children"] as? JsonArray ?: JsonArray(emptyList()))
        .sumOf { supplyQtyInTree(it, supplyId) }
}

// ── Route ─────────────────────────────────────────────────────────────────────

/**
 * POST /material-impact
 *
 * Given a supply ID and the nature of the change (delay days, quantity decrease),
 * finds all committed demands impacted by that supply using the latest successful
 * planning run for the supply's case.
 *
 * Impact is determined by walking the planning_pegging trees stored in the plan run
 * result and finding demands whose supply tree references this supply's product+location.
 */
fun Routing.materialImpactRoutes() {
    route("/material-impact") {
        post {
            val rawBody = call.receiveText()
            log.info("material-impact raw body: {}", rawBody)
            val req = try {
                Json.decodeFromString<MaterialImpactRequest>(rawBody)
            } catch (e: Exception) {
                log.error("material-impact body parse failed: {}", e.message)
                throw e
            }
            log.info(
                "material-impact request: supplyId={} delayDays={} qtyDecreasePct={}",
                req.supplyId, req.deliveryDelayDays, req.quantityDecreasePct,
            )

            val result = transaction {
                // 1. Look up supply
                val supplyRow = Supplies.selectAll()
                    .where { Supplies.supplyId eq req.supplyId }
                    .firstOrNull()
                    ?: throw IllegalArgumentException("Supply '${req.supplyId}' not found")

                val caseId = supplyRow[Supplies.caseId]
                val productId = supplyRow[Supplies.productId].trim()
                val locationId = (supplyRow[Supplies.locationId] ?: "").trim()

                val supply = MaterialSupplyDetail(
                    supplyId = supplyRow[Supplies.supplyId],
                    productId = productId,
                    qty = supplyRow[Supplies.qty],
                    supplyDate = supplyRow[Supplies.supplyDate],
                    locationId = supplyRow[Supplies.locationId],
                    vendorId = supplyRow[Supplies.vendorId],
                )

                // 2. Latest successful plan run for this case
                val latestPlanRun = PlanRuns.selectAll()
                    .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                    .orderBy(PlanRuns.id, SortOrder.DESC)
                    .firstOrNull()

                if (latestPlanRun == null) {
                    return@transaction MaterialImpactResponse(
                        supply = supply,
                        deliveryDelayDays = req.deliveryDelayDays,
                        quantityDecreasePct = req.quantityDecreasePct,
                        impactedDemandCount = 0,
                        impacts = emptyList(),
                        note = "No successful plan run found for this case.",
                    )
                }

                val resultJson = latestPlanRun[PlanRuns.result]
                    ?: return@transaction MaterialImpactResponse(
                        supply = supply,
                        deliveryDelayDays = req.deliveryDelayDays,
                        quantityDecreasePct = req.quantityDecreasePct,
                        impactedDemandCount = 0,
                        impacts = emptyList(),
                        note = "Plan run has no result data.",
                    )

                // 3. Parse result and walk planning_pegging trees
                val planResult = Json.parseToJsonElement(resultJson)
                val pegging = (planResult as? JsonObject)?.get("planning_pegging") as? JsonArray
                    ?: JsonArray(emptyList())

                // demand_id → allocated qty from this supply
                val demandAllocated = mutableMapOf<String, Double>()
                for (entry in pegging) {
                    val obj = entry as? JsonObject ?: continue
                    val demandId = obj["demand_id"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?: continue
                    if (demandId.isBlank()) continue
                    val tree = obj["tree"] ?: continue
                    if (treeContainsSupply(tree, req.supplyId)) {
                        val qty = supplyQtyInTree(tree, req.supplyId)
                        demandAllocated[demandId] = (demandAllocated[demandId] ?: 0.0) + qty
                    }
                }

                if (demandAllocated.isEmpty()) {
                    return@transaction MaterialImpactResponse(
                        supply = supply,
                        deliveryDelayDays = req.deliveryDelayDays,
                        quantityDecreasePct = req.quantityDecreasePct,
                        impactedDemandCount = 0,
                        impacts = emptyList(),
                    )
                }

                // 4. Fetch demand details
                val demandRows = Demands.selectAll()
                    .where { (Demands.caseId eq caseId) and (Demands.demandId inList demandAllocated.keys.toList()) }
                    .toList()

                val impactStatus = if (req.deliveryDelayDays > 0) "delayed" else "at_risk"

                val impacts = demandRows.map { d ->
                    val did = d[Demands.demandId]
                    MaterialImpactedDemand(
                        demandId = did,
                        productId = d[Demands.productId],
                        locationId = d[Demands.locationId],
                        customerId = d[Demands.customerId],
                        description = d[Demands.description],
                        priority = d[Demands.priority],
                        requestDueTime = d[Demands.requestDueTime],
                        requestedQty = d[Demands.quantity],
                        consumedSupplyQty = demandAllocated[did] ?: 0.0,
                        status = impactStatus,
                    )
                }

                log.info(
                    "material-impact result: supplyId={} product={} location={} impactedDemands={}",
                    supply.supplyId, supply.productId, supply.locationId, impacts.size,
                )
                impacts.forEach { d ->
                    log.info(
                        "  impacted demand: demandId={} customer={} product={} location={} " +
                        "requestedQty={} consumedSupplyQty={} dueTime={} priority={} status={}",
                        d.demandId, d.customerId, d.productId, d.locationId,
                        d.requestedQty, d.consumedSupplyQty, d.requestDueTime, d.priority, d.status,
                    )
                }

                MaterialImpactResponse(
                    supply = supply,
                    deliveryDelayDays = req.deliveryDelayDays,
                    quantityDecreasePct = req.quantityDecreasePct,
                    impactedDemandCount = impacts.size,
                    impacts = impacts,
                )
            }
            call.respond(result)
        }
    }
}
