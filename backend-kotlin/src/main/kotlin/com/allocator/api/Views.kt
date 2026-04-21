package com.allocator.api

import com.allocator.*
import com.allocator.services.CaseLoader
import com.allocator.services.TimeUtils
import com.allocator.services.SkuPatterns
import com.allocator.services.runAllocation
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val jViews = Json { ignoreUnknownKeys = true }

private fun parseStrListV(t: String?): List<String> {
    if (t.isNullOrBlank()) return emptyList()
    return try { jViews.parseToJsonElement(t).jsonArray.map { it.jsonPrimitive.content } } catch (_: Exception) { emptyList() }
}

private fun parseDoubleNullableList(t: String?): List<Double?>? {
    if (t.isNullOrBlank()) return null
    return try {
        jViews.parseToJsonElement(t).jsonArray.map { e ->
            if (e is JsonNull) null else e.jsonPrimitive.doubleOrNull
        }
    } catch (_: Exception) { null }
}

private fun normCompKey(ck: String): String {
    val parts = ck.split("|", limit = 2)
    val p0 = parts[0].trim()
    val p1 = if (parts.size > 1) parts[1].trim() else ""
    return "$p0|$p1"
}

private fun compKeyToAtV(ck: String): String =
    if ("|" in ck) ck.split("|", limit = 2).let { "${it[0]}@${it[1]}" } else ck

private fun invNodeDisplay(nodeId: String, sortedDates: List<String>): String {
    if (nodeId.startsWith("demand|")) return "demand ${nodeId.split("|", limit = 2).getOrElse(1) { "" }}"
    val lastPipe = nodeId.lastIndexOf("|")
    if (lastPipe > 0) {
        val comp = nodeId.substring(0, lastPipe)
        val periodStr = nodeId.substring(lastPipe + 1)
        val period = periodStr.toIntOrNull()
        if (period != null) {
            val dateStr = TimeUtils.periodToDate(period, sortedDates)
            return "${compKeyToAtV(comp)} (${dateStr ?: periodStr})"
        }
    }
    return if ("|" in nodeId) compKeyToAtV(nodeId) else nodeId
}

private fun compFromNode(nodeId: String): String {
    val i = nodeId.lastIndexOf("|")
    return if (i > 0) nodeId.substring(0, i) else nodeId
}

/** Consume up to need from component's FIFO supply nodes; returns amount taken. */
private fun consumeFromCompFifo(
    availByNode: MutableMap<String, Double>,
    consumedByNode: MutableMap<String, Double>,
    compKey: String,
    need: Double,
    compPeriods: Map<String, List<Int>>,
): Double {
    if (need <= 0) return 0.0
    val periods = compPeriods[compKey] ?: return 0.0
    var taken = 0.0
    for (p in periods.sorted()) {
        if (taken >= need) break
        val node = "$compKey|$p"
        val avail = availByNode[node] ?: 0.0
        if (avail <= 0) continue
        val take = minOf(avail, need - taken)
        if (take > 0) {
            availByNode[node] = avail - take
            consumedByNode[node] = (consumedByNode[node] ?: 0.0) + take
            taken += take
        }
    }
    return taken
}

/** Consume from basket FIFO and return list of (node, qty_taken). */
private fun consumeFromBasketFifoReturnDelta(
    basket: MutableMap<String, Double>,
    compKey: String,
    need: Double,
): List<Pair<String, Double>> {
    if (need <= 0) return emptyList()
    val prefix = "$compKey|"
    val nodes = basket.keys.filter { (it.startsWith(prefix)) && (basket[it] ?: 0.0) > 0 }
        .sortedWith(compareBy({ it.split("|").last().toIntOrNull() ?: 0 }, { it }))
    var remaining = need
    val taken = mutableListOf<Pair<String, Double>>()
    for (node in nodes) {
        if (remaining <= 0) break
        val avail = basket[node] ?: 0.0
        val take = minOf(avail, remaining)
        if (take > 0) {
            basket[node] = avail - take
            remaining -= take
            taken.add(node to take)
            if ((basket[node] ?: 0.0) <= 0) basket.remove(node)
        }
    }
    return taken
}

private data class BasketReplayResult(
    val initialItems: List<Map<String, Any?>>,
    val basketDeltas: List<Map<String, Any?>>,
    val fromInventoryIdToStep: Map<String, Int>,
    val demandIdToStep: Map<String, Int>,
    val basketFinal: List<Map<String, Any?>>,
)

private fun replayBasketSnapshots(
    supplies: List<ResultRow>,
    actionsSorted: List<ResultRow>,
    bomRate: Map<Pair<String, String>, Double>,
    leadTimeByVariant: Map<Pair<String, String>, Double>,
    moveTransit: Map<Triple<String, String, String>, Double>,
    dateToPeriod: Map<String, Int>,
    sortedDates: List<String>,
    supplyAdj: Map<String, Double> = emptyMap(),
    maxDeltasToKeep: Int? = null,
): BasketReplayResult {
    val basket = mutableMapOf<String, Double>()
    for (s in supplies) {
        val compKey = "${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}"
        val qty = s[Supplies.qty] + (supplyAdj[compKey] ?: 0.0)
        if (qty <= 0) continue
        val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
        val nodeId = "$compKey|$period"
        basket[nodeId] = (basket[nodeId] ?: 0.0) + qty
    }

    val compTotals = mutableMapOf<String, Double>()
    for ((nodeId, qty) in basket) compTotals[compFromNode(nodeId)] = (compTotals[compFromNode(nodeId)] ?: 0.0) + qty

    val initialItems = basket.entries
        .filter { it.value > 0 }
        .sortedWith(compareBy({ compTotals[compFromNode(it.key)] ?: 0.0 }, { it.key }))
        .map { (k, v) -> mapOf("key" to k, "display" to invNodeDisplay(k, sortedDates), "qty" to Math.round(v * 10000).toDouble() / 10000.0) }

    val basketDeltas = mutableListOf<Map<String, Any?>>()
    val fromInventoryIdToStep = mutableMapOf<String, Int>()
    val demandIdToStep = mutableMapOf<String, Int>()

    for ((stepIndex, a) in actionsSorted.withIndex()) {
        val reqKeys = parseStrListV(a[AllocationActions.reqComponentIds])
        val variantKey = a[AllocationActions.variantKey] ?: ""
        val targetPid = a[AllocationActions.targetProductId] ?: ""
        val targetLoc = a[AllocationActions.targetLocationId] ?: ""
        val qty = a[AllocationActions.qty]
        val edgeType = a[AllocationActions.edgeType] ?: "make"
        val outPer = a[AllocationActions.outputPeriod] ?: 0
        val reqRatesList = parseDoubleNullableList(a[AllocationActions.reqRates])

        // critical component: least available among req_keys
        var criticalKey: String? = null
        if (reqKeys.isNotEmpty()) {
            val byAvail = reqKeys.mapIndexed { i, ck ->
                val prefix = "$ck|"
                val avail = basket.entries.filter { it.key.startsWith(prefix) || it.key == ck }.sumOf { it.value }
                Triple(avail, i, ck)
            }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            criticalKey = byAvail.firstOrNull()?.third
        }

        val periodFrom = when {
            edgeType == "move" && reqKeys.isNotEmpty() && variantKey.isNotEmpty() -> {
                val fromLoc = reqKeys[0].split("|", limit = 2).getOrElse(1) { "" }
                val transit = moveTransit[Triple(targetPid, fromLoc, targetLoc)] ?: 0.0
                TimeUtils.periodMinusDays(outPer, transit, dateToPeriod, sortedDates)
            }
            edgeType == "make" -> {
                val lead = leadTimeByVariant[Pair(targetPid, targetLoc)] ?: 0.0
                TimeUtils.periodMinusDays(outPer, lead, dateToPeriod, sortedDates)
            }
            else -> outPer
        }
        val fromComp = criticalKey ?: reqKeys.firstOrNull() ?: ""
        val fromInventoryId = if (fromComp.isNotEmpty()) "$fromComp|$periodFrom" else ""

        val purged = mutableListOf<Map<String, Any?>>()
        for ((i, ck) in reqKeys.withIndex()) {
            val rate = if (reqRatesList != null && i < reqRatesList.size && (reqRatesList[i] ?: 0.0) > 0) {
                reqRatesList[i]!!
            } else {
                val compProduct = if ("|" in ck) ck.split("|", limit = 2)[0] else ck
                if (edgeType == "make") bomRate[Pair(targetPid, compProduct)] ?: 1.0 else 1.0
            }
            val need = if (rate > 0) qty / rate else 0.0
            for ((node, take) in consumeFromBasketFifoReturnDelta(basket, ck, need)) {
                purged.add(mapOf("key" to node, "display" to invNodeDisplay(node, sortedDates), "qty" to Math.round(take * 10000).toDouble() / 10000.0))
            }
        }
        val added = mutableListOf<Map<String, Any?>>()
        if (variantKey.isNotEmpty()) {
            val outNode = "$variantKey|$outPer"
            basket[outNode] = (basket[outNode] ?: 0.0) + qty
            added.add(mapOf("key" to outNode, "display" to invNodeDisplay(outNode, sortedDates), "qty" to Math.round(qty * 10000).toDouble() / 10000.0))
        }

        if (maxDeltasToKeep == null || stepIndex < maxDeltasToKeep) {
            basketDeltas.add(mapOf("purged" to purged, "added" to added))
        }
        if (fromInventoryId.isNotEmpty()) fromInventoryIdToStep[fromInventoryId] = stepIndex
        val demandId = a[AllocationActions.demandId]
        if (demandId != null) demandIdToStep[demandId] = stepIndex
    }

    val compTotalsFinal = mutableMapOf<String, Double>()
    for ((nodeId, qty) in basket) {
        if (qty > 0) compTotalsFinal[compFromNode(nodeId)] = (compTotalsFinal[compFromNode(nodeId)] ?: 0.0) + qty
    }
    val basketFinal = basket.entries.filter { it.value > 0 }
        .sortedWith(compareBy({ compTotalsFinal[compFromNode(it.key)] ?: 0.0 }, { it.key }))
        .map { (k, v) -> mapOf("key" to k, "display" to invNodeDisplay(k, sortedDates), "qty" to Math.round(v * 10000).toDouble() / 10000.0) }

    return BasketReplayResult(initialItems, basketDeltas, fromInventoryIdToStep, demandIdToStep, basketFinal)
}

private data class FlatViewResult(val invRows: List<Map<String, Any?>>, val demandRows: List<Map<String, Any?>>)

private fun buildAllocationViewFlat(
    actionsSorted: List<ResultRow>,
    supplies: List<ResultRow>,
    demands: List<ResultRow>,
    dateToPeriod: Map<String, Int>,
    sortedDates: List<String>,
    keyToSupplyId: Map<String, String>,
    leadTimeByVariant: Map<Pair<String, String>, Double>,
    moveTransit: Map<Triple<String, String, String>, Double>,
    productToDemands: Map<String, List<String>>,
): FlatViewResult {
    val available = mutableMapOf<String, Double>()
    for (s in supplies) {
        val key = normCompKey("${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}")
        available[key] = (available[key] ?: 0.0) + s[Supplies.qty]
    }

    val invRows = mutableListOf<Map<String, Any?>>()
    val demandRows = mutableListOf<Map<String, Any?>>()

    for (a in actionsSorted) {
        val qty = Math.round((a[AllocationActions.qty]) * 10000).toDouble() / 10000.0
        if (qty <= 0) continue
        val targetPid = a[AllocationActions.targetProductId] ?: ""
        val targetLoc = a[AllocationActions.targetLocationId] ?: ""
        val variantKey = a[AllocationActions.variantKey] ?: ""
        val demandIds = productToDemands[targetPid] ?: emptyList()
        val reqKeys = parseStrListV(a[AllocationActions.reqComponentIds])
        val reqKeysNorm = reqKeys.map { normCompKey(it) }
        val edgeType = a[AllocationActions.edgeType] ?: "make"
        val outPer = a[AllocationActions.outputPeriod] ?: 0
        val scarcityRank = a[AllocationActions.scarcityRank]
        val reqRatesList = parseDoubleNullableList(a[AllocationActions.reqRates])

        var criticalComponentKey: String? = null
        var criticalComponentIndex: Int? = null
        if (reqKeysNorm.isNotEmpty()) {
            val byAvail = reqKeysNorm.mapIndexed { i, ck -> Triple(available[ck] ?: 0.0, i, ck) }
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            criticalComponentKey = byAvail.firstOrNull()?.third
            criticalComponentIndex = byAvail.firstOrNull()?.second
        }

        for ((i, ck) in reqKeysNorm.withIndex()) {
            val rate = if (reqRatesList != null && i < reqRatesList.size && (reqRatesList[i] ?: 0.0) > 0) reqRatesList[i]!! else 1.0
            val need = qty / rate
            available[ck] = (available[ck] ?: 0.0) - need
        }
        if (variantKey.isNotEmpty()) available[variantKey] = (available[variantKey] ?: 0.0) + qty

        val periodFrom = when {
            edgeType == "move" && reqKeys.isNotEmpty() && variantKey.isNotEmpty() -> {
                val fromLoc = reqKeys[0].split("|", limit = 2).getOrElse(1) { "" }
                val transit = moveTransit[Triple(targetPid, fromLoc, targetLoc)] ?: 0.0
                TimeUtils.periodMinusDays(outPer, transit, dateToPeriod, sortedDates)
            }
            edgeType == "make" -> {
                val lead = leadTimeByVariant[Pair(targetPid, targetLoc)] ?: 0.0
                TimeUtils.periodMinusDays(outPer, lead, dateToPeriod, sortedDates)
            }
            else -> outPer
        }
        val fromComp = criticalComponentKey ?: reqKeysNorm.firstOrNull() ?: ""
        val fromInventoryId = if (fromComp.isNotEmpty()) "$fromComp|$periodFrom" else ""
        val toInventoryId = if (variantKey.isNotEmpty()) "$variantKey|$outPer" else ""

        invRows.add(mapOf(
            "edge_type" to edgeType,
            "scarcity_rank" to scarcityRank,
            "from_inventory_id" to fromInventoryId,
            "from_inventory_display" to invNodeDisplay(fromInventoryId, sortedDates),
            "to_inventory_id" to toInventoryId,
            "to_inventory_display" to invNodeDisplay(toInventoryId, sortedDates),
            "from_components" to reqKeys,
            "critical_component_key" to criticalComponentKey,
            "critical_component_index" to criticalComponentIndex,
            "to_variant_key" to variantKey,
            "to_variant_key_display" to (if (variantKey.isNotEmpty()) compKeyToAtV(variantKey) else ""),
            "output_period" to outPer,
            "output_date" to TimeUtils.periodToDate(outPer, sortedDates),
            "qty" to qty,
            "demand_ids" to demandIds,
            "supply_id" to (if (reqKeysNorm.isNotEmpty()) keyToSupplyId[reqKeysNorm[0]] ?: "" else ""),
        ))

        val demandId = a[AllocationActions.demandId]
        if (demandId != null && variantKey.isNotEmpty()) {
            demandRows.add(mapOf(
                "edge_type" to "make",
                "from_inventory_id" to toInventoryId,
                "from_inventory_display" to invNodeDisplay(toInventoryId, sortedDates),
                "to_inventory_id" to "demand|$demandId",
                "to_inventory_display" to invNodeDisplay("demand|$demandId", sortedDates),
                "to_variant_key" to variantKey,
                "output_period" to outPer,
                "output_date" to TimeUtils.periodToDate(outPer, sortedDates),
                "qty" to qty,
                "demand_ids" to listOf(demandId),
            ))
        }
    }
    return FlatViewResult(invRows, demandRows)
}

private const val MAX_ACTIONS_ALLOCATION_VIEW = 5_000
private const val DEFAULT_MAX_ACTIONS_FIRST_LOAD = 300

// Simple LRU cache
private val allocationViewCache = object : LinkedHashMap<Pair<Int, Int>, List<Map<String, Any?>>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, List<Map<String, Any?>>>?) = size > 8
}

fun Routing.viewRoutes() {

    route("/cases/{case_id}/runs/{run_id}") {

        // ── GET /supply-view ──────────────────────────────────────────────────
        get("/supply-view") {
            val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid run_id")
            val debugComponentKey = call.request.queryParameters["debug_component_key"]
            val planRunId = call.request.queryParameters["plan_run_id"]?.toIntOrNull()

            // ── plan-based branch: derive consumption from plan_supply_allocation ──
            if (planRunId != null) {
                val rows = transaction {
                    PlanRuns.selectAll().where {
                        (PlanRuns.id eq planRunId) and (PlanRuns.caseId eq caseId)
                    }.singleOrNull() ?: throw NoSuchElementException("Plan run not found")

                    val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }
                        .orderBy(Supplies.supplyId, SortOrder.ASC).toList()

                    val allocs = PlanSupplyAllocations.selectAll().where {
                        (PlanSupplyAllocations.caseId eq caseId) and (PlanSupplyAllocations.planRunId eq planRunId)
                    }.toList()

                    val consumedBySupplyKey = mutableMapOf<String, Double>()
                    val demandsBySupplyKey = mutableMapOf<String, MutableSet<String>>()
                    for (r in allocs) {
                        val sid = r[PlanSupplyAllocations.supplyId]
                        consumedBySupplyKey[sid] = (consumedBySupplyKey[sid] ?: 0.0) + r[PlanSupplyAllocations.qtyConsumed]
                        val did = r[PlanSupplyAllocations.demandId]
                        if (!did.isNullOrBlank()) {
                            demandsBySupplyKey.getOrPut(sid) { mutableSetOf() }.add(did)
                        }
                    }

                    supplies.map { s ->
                        val sid = s[Supplies.supplyId]
                        val initRow = s[Supplies.qty]
                        val consumedRow = consumedBySupplyKey[sid] ?: 0.0
                        val residualRow = maxOf(0.0, initRow - consumedRow)
                        val utilRate = if (initRow > 0) consumedRow / initRow else null
                        val peggedDemands = demandsBySupplyKey[sid]?.size ?: 0
                        mapOf(
                            "id" to s[Supplies.id],
                            "component_key" to "${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}",
                            "supply_id" to sid,
                            "supply_date" to s[Supplies.supplyDate],
                            "product_id" to s[Supplies.productId],
                            "location_id" to (s[Supplies.locationId] ?: ""),
                            "initial_qty" to Math.round(initRow * 10000).toDouble() / 10000.0,
                            "consumed_qty" to Math.round(consumedRow * 10000).toDouble() / 10000.0,
                            "residual_qty" to Math.round(residualRow * 10000).toDouble() / 10000.0,
                            "utilization_rate" to if (utilRate != null) Math.round(utilRate * 10000).toDouble() / 10000.0 else null,
                            "pegged_demands" to peggedDemands,
                            "total_pegged_qty" to Math.round(consumedRow * 10000).toDouble() / 10000.0,
                        )
                    }
                }

                call.respond(buildJsonObject {
                    put("run_id", runId)
                    put("plan_run_id", planRunId)
                    put("source", "plan")
                    put("supply_view", buildJsonArray {
                        for (row in rows) add(buildJsonObject {
                            put("id", row["id"] as? Int ?: 0)
                            put("component_key", row["component_key"] as? String ?: "")
                            put("supply_id", row["supply_id"] as? String ?: "")
                            if (row["supply_date"] != null) put("supply_date", row["supply_date"] as String) else put("supply_date", JsonNull)
                            put("product_id", row["product_id"] as? String ?: "")
                            put("location_id", row["location_id"] as? String ?: "")
                            put("initial_qty", row["initial_qty"] as? Double ?: 0.0)
                            put("consumed_qty", row["consumed_qty"] as? Double ?: 0.0)
                            put("residual_qty", row["residual_qty"] as? Double ?: 0.0)
                            val ur = row["utilization_rate"] as? Double
                            if (ur != null) put("utilization_rate", ur) else put("utilization_rate", JsonNull)
                            put("pegged_demands", row["pegged_demands"] as? Int ?: 0)
                            put("total_pegged_qty", row["total_pegged_qty"] as? Double ?: 0.0)
                        })
                    })
                })
                return@get
            }

            transaction {
                AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")
            }

            val (result, debugOut) = transaction {
                val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }
                    .orderBy(Supplies.supplyId, SortOrder.ASC).toList()
                val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
                val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }
                    .sortedWith(compareBy({ it[AllocationActions.scarcityRank] ?: 999999 }, { it[AllocationActions.id] }))

                val supplyList = supplies.map { mapOf("supply_date" to it[Supplies.supplyDate]) }
                val demandList = demands.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
                val (dateToPeriod, _) = TimeUtils.buildPeriodIndex(supplyList, demandList)

                val initialByNode = mutableMapOf<String, Double>()
                val compPeriods = mutableMapOf<String, MutableList<Int>>()
                for (s in supplies) {
                    val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
                    val ck = normCompKey("${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}")
                    val node = "$ck|$period"
                    initialByNode[node] = (initialByNode[node] ?: 0.0) + s[Supplies.qty]
                    compPeriods.getOrPut(ck) { mutableListOf() }.let { if (period !in it) it.add(period) }
                }

                // node -> list of (idx, initialQty, period, supplyId)
                val nodeToSupplies = mutableMapOf<String, MutableList<SupplyNodeItem>>()
                for ((i, s) in supplies.withIndex()) {
                    val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
                    val ck = normCompKey("${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}")
                    val node = "$ck|$period"
                    nodeToSupplies.getOrPut(node) { mutableListOf() }.add(SupplyNodeItem(i, s[Supplies.qty], period, s[Supplies.supplyId]))
                }
                for ((_, list) in nodeToSupplies) list.sortWith(compareBy({ it.period }, { it.supplyId }))

                val boms = Boms.selectAll().where { Boms.caseId eq caseId }.toList()
                val bomRateSupply = mutableMapOf<Pair<String, String>, Double>()
                for (b in boms) {
                    val k = b[Boms.parentId] to b[Boms.childId]
                    bomRateSupply.putIfAbsent(k, b[Boms.rate] ?: 1.0)
                }

                val availByNode = initialByNode.toMutableMap()
                val consumedByNode = mutableMapOf<String, Double>()
                val debugActions = mutableListOf<Map<String, Any?>>()
                val debugCompNorm = debugComponentKey?.let { normCompKey(it.trim()) }

                for (a in actions) {
                    val outputQty = a[AllocationActions.qty]
                    val reqKeys = parseStrListV(a[AllocationActions.reqComponentIds])
                    val reqRates = parseDoubleNullableList(a[AllocationActions.reqRates])
                    val targetPid = a[AllocationActions.targetProductId] ?: ""
                    val edgeType = a[AllocationActions.edgeType] ?: "make"
                    for ((i, ckey) in reqKeys.withIndex()) {
                        val ckeyNorm = normCompKey(ckey)
                        val rate = if (reqRates != null && i < reqRates.size && (reqRates[i] ?: 0.0) > 0) {
                            reqRates[i]!!
                        } else {
                            val compProduct = if ("|" in ckeyNorm) ckeyNorm.split("|", limit = 2)[0] else ckeyNorm
                            if (edgeType == "make") bomRateSupply[targetPid to compProduct] ?: 1.0 else 1.0
                        }
                        val need = outputQty / rate
                        val taken = consumeFromCompFifo(availByNode, consumedByNode, ckeyNorm, need, compPeriods)
                        if (debugCompNorm != null && (debugCompNorm == ckeyNorm || ckey == debugComponentKey)) {
                            debugActions.add(mapOf(
                                "action_id" to a[AllocationActions.id],
                                "scarcity_rank" to a[AllocationActions.scarcityRank],
                                "variant_key" to a[AllocationActions.variantKey],
                                "target_product_id" to targetPid,
                                "edge_type" to edgeType,
                                "output_qty" to outputQty,
                                "rate_used" to rate,
                                "need_computed" to need,
                                "taken" to taken,
                                "req_rates_from_action" to (reqRates != null),
                            ))
                        }
                    }
                }

                // Distribute consumed_by_node to individual supply rows
                val consumedBySupplyIdx = mutableMapOf<Int, Double>()
                for ((node, consumed) in consumedByNode) {
                    if (consumed <= 0) continue
                    var remaining = consumed
                    for (q in nodeToSupplies[node] ?: emptyList()) {
                        val take = minOf(q.initQty, remaining)
                        if (take > 0) consumedBySupplyIdx[q.idx] = (consumedBySupplyIdx[q.idx] ?: 0.0) + take
                        remaining -= take
                        if (remaining <= 0) break
                    }
                }

                val result = supplies.mapIndexed { i, s ->
                    val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
                    val initRow = s[Supplies.qty]
                    val consumedRow = consumedBySupplyIdx[i] ?: 0.0
                    val residualRow = maxOf(0.0, initRow - consumedRow)
                    val utilRate = if (initRow > 0) consumedRow / initRow else null
                    mapOf(
                        "id" to s[Supplies.id],
                        "component_key" to "${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}",
                        "supply_id" to s[Supplies.supplyId],
                        "supply_date" to s[Supplies.supplyDate],
                        "product_id" to s[Supplies.productId],
                        "location_id" to (s[Supplies.locationId] ?: ""),
                        "initial_qty" to Math.round(initRow * 10000).toDouble() / 10000.0,
                        "consumed_qty" to Math.round(consumedRow * 10000).toDouble() / 10000.0,
                        "residual_qty" to Math.round(residualRow * 10000).toDouble() / 10000.0,
                        "utilization_rate" to if (utilRate != null) Math.round(utilRate * 10000).toDouble() / 10000.0 else null,
                    )
                }
                result to debugActions
            }

            call.respond(buildJsonObject {
                put("run_id", runId)
                put("supply_view", buildJsonArray {
                    for (row in result) add(buildJsonObject {
                        put("id", row["id"] as? Int ?: 0)
                        put("component_key", row["component_key"] as? String ?: "")
                        put("supply_id", row["supply_id"] as? String ?: "")
                        if (row["supply_date"] != null) put("supply_date", row["supply_date"] as String) else put("supply_date", JsonNull)
                        put("product_id", row["product_id"] as? String ?: "")
                        put("location_id", row["location_id"] as? String ?: "")
                        put("initial_qty", row["initial_qty"] as? Double ?: 0.0)
                        put("consumed_qty", row["consumed_qty"] as? Double ?: 0.0)
                        put("residual_qty", row["residual_qty"] as? Double ?: 0.0)
                        val ur = row["utilization_rate"] as? Double
                        if (ur != null) put("utilization_rate", ur) else put("utilization_rate", JsonNull)
                    })
                })
            })
        }

        // ── GET /production-trace ─────────────────────────────────────────────
        get("/production-trace") {
            val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid run_id")

            val out = transaction {
                AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")

                val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }
                    .orderBy(Supplies.supplyId, SortOrder.ASC).toList()
                val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
                val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }
                    .sortedWith(compareBy({ it[AllocationActions.scarcityRank] ?: 999999 }, { it[AllocationActions.id] }))

                val supplyList = supplies.map { mapOf("supply_date" to it[Supplies.supplyDate]) }
                val demandList = demands.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
                val (dateToPeriod, _) = TimeUtils.buildPeriodIndex(supplyList, demandList)

                val initialByNode = mutableMapOf<String, Double>()
                val compPeriods = mutableMapOf<String, MutableList<Int>>()
                for (s in supplies) {
                    val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
                    val ck = normCompKey("${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}")
                    val node = "$ck|$period"
                    initialByNode[node] = (initialByNode[node] ?: 0.0) + s[Supplies.qty]
                    compPeriods.getOrPut(ck) { mutableListOf() }.let { if (period !in it) it.add(period) }
                }

                val boms = Boms.selectAll().where { Boms.caseId eq caseId }.toList()
                val bomRateSupply = mutableMapOf<Pair<String, String>, Double>()
                for (b in boms) bomRateSupply.putIfAbsent(b[Boms.parentId] to b[Boms.childId], b[Boms.rate] ?: 1.0)

                val availByNode = initialByNode.toMutableMap()
                val consumedByNode = mutableMapOf<String, Double>()
                for (a in actions) {
                    val outputQty = a[AllocationActions.qty]
                    val reqKeys = parseStrListV(a[AllocationActions.reqComponentIds])
                    val reqRates = parseDoubleNullableList(a[AllocationActions.reqRates])
                    val targetPid = a[AllocationActions.targetProductId] ?: ""
                    val edgeType = a[AllocationActions.edgeType] ?: "make"
                    for ((i, ckey) in reqKeys.withIndex()) {
                        val ckeyNorm = normCompKey(ckey)
                        val rate = if (reqRates != null && i < reqRates.size && (reqRates[i] ?: 0.0) > 0) reqRates[i]!!
                        else {
                            val cp = if ("|" in ckeyNorm) ckeyNorm.split("|", limit = 2)[0] else ckeyNorm
                            if (edgeType == "make") bomRateSupply[targetPid to cp] ?: 1.0 else 1.0
                        }
                        val need = outputQty / rate
                        consumeFromCompFifo(availByNode, consumedByNode, ckeyNorm, need, compPeriods)
                    }
                }

                val byPattern = mutableMapOf<String, MutableMap<String, Any?>>()
                // Initialize known patterns
                listOf("1xx-xxxx", "2xx-xxxx", "3xx-xxxx").forEach { name ->
                    byPattern[name] = mutableMapOf("used" to false, "total_consumed_qty" to 0.0, "nodes_consumed" to mutableListOf<Map<String, Any?>>())
                }
                for ((node, qty) in consumedByNode) {
                    if (qty <= 0) continue
                    val productId = if ("|" in node) node.split("|", limit = 2)[0] else node
                    val pattern = SkuPatterns.rawMaterialPattern(productId) ?: continue
                    val entry = byPattern.getOrPut(pattern) { mutableMapOf("used" to false, "total_consumed_qty" to 0.0, "nodes_consumed" to mutableListOf<Map<String, Any?>>()) }
                    entry["used"] = true
                    entry["total_consumed_qty"] = (entry["total_consumed_qty"] as? Double ?: 0.0) + qty
                    @Suppress("UNCHECKED_CAST")
                    (entry["nodes_consumed"] as MutableList<Map<String, Any?>>).add(
                        mapOf("node" to node, "consumed_qty" to Math.round(qty * 10000).toDouble() / 10000.0)
                    )
                }
                for ((_, e) in byPattern) {
                    e["total_consumed_qty"] = Math.round((e["total_consumed_qty"] as? Double ?: 0.0) * 10000).toDouble() / 10000.0
                }
                byPattern
            }

            val supplyPatternsUsed = out.filter { it.value["used"] as? Boolean == true }.keys.toList()
            call.respond(buildJsonObject {
                put("run_id", runId)
                put("supply_patterns_used", buildJsonArray { supplyPatternsUsed.forEach { add(it) } })
                put("by_pattern", buildJsonObject {
                    for ((pattern, info) in out) put(pattern, buildJsonObject {
                        put("used", info["used"] as? Boolean ?: false)
                        put("total_consumed_qty", info["total_consumed_qty"] as? Double ?: 0.0)
                        put("nodes_consumed", buildJsonArray {
                            @Suppress("UNCHECKED_CAST")
                            ((info["nodes_consumed"] as? List<Map<String, Any?>>) ?: emptyList()).forEach { nc ->
                                add(buildJsonObject {
                                    put("node", nc["node"] as? String ?: "")
                                    put("consumed_qty", nc["consumed_qty"] as? Double ?: 0.0)
                                })
                            }
                        })
                    })
                })
            })
        }

        // ── GET /raw-material-usage ───────────────────────────────────────────
        get("/raw-material-usage") {
            val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid run_id")

            // Delegate: call production-trace internally via a sub-call isn't feasible; replicate the logic
            // We need production-trace data + raw_material_trace from run config
            val (prodTraceResult, rawMaterialTrace, suppliesCompKeys) = transaction {
                val run = AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")

                val configText = run[AllocationRuns.config] ?: "{}"
                val configJson = try { jViews.parseToJsonElement(configText).jsonObject } catch (_: Exception) { buildJsonObject {} }
                val rawMaterialTraceRaw = configJson["raw_material_trace"]?.jsonArray ?: buildJsonArray {}

                val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.toList()
                val supplyCompKeys = supplies.map { s ->
                    "${s[Supplies.productId].trim()}|${(s[Supplies.locationId] ?: "").trim()}"
                }.toSet()

                Triple(run, rawMaterialTraceRaw, supplyCompKeys)
            }

            // Build production trace summary map
            val ptByPattern = mutableMapOf<String, MutableMap<String, Any?>>()
            // Re-run production trace logic inline (avoid HTTP round-trip)
            transaction {
                val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }
                    .orderBy(Supplies.supplyId, SortOrder.ASC).toList()
                val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
                val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }
                    .sortedWith(compareBy({ it[AllocationActions.scarcityRank] ?: 999999 }, { it[AllocationActions.id] }))
                val supplyList = supplies.map { mapOf("supply_date" to it[Supplies.supplyDate]) }
                val demandList = demands.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
                val (dateToPeriod, _) = TimeUtils.buildPeriodIndex(supplyList, demandList)
                val initialByNode = mutableMapOf<String, Double>()
                val compPeriods = mutableMapOf<String, MutableList<Int>>()
                for (s in supplies) {
                    val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
                    val ck = normCompKey("${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}")
                    val node = "$ck|$period"
                    initialByNode[node] = (initialByNode[node] ?: 0.0) + s[Supplies.qty]
                    compPeriods.getOrPut(ck) { mutableListOf() }.let { if (period !in it) it.add(period) }
                }
                val boms = Boms.selectAll().where { Boms.caseId eq caseId }.toList()
                val bomRate = mutableMapOf<Pair<String, String>, Double>()
                for (b in boms) bomRate.putIfAbsent(b[Boms.parentId] to b[Boms.childId], b[Boms.rate] ?: 1.0)
                val availByNode = initialByNode.toMutableMap()
                val consumedByNode = mutableMapOf<String, Double>()
                for (a in actions) {
                    val outputQty = a[AllocationActions.qty]
                    val reqKeys = parseStrListV(a[AllocationActions.reqComponentIds])
                    val reqRates = parseDoubleNullableList(a[AllocationActions.reqRates])
                    val targetPid = a[AllocationActions.targetProductId] ?: ""
                    val edgeType = a[AllocationActions.edgeType] ?: "make"
                    for ((i, ckey) in reqKeys.withIndex()) {
                        val ckeyNorm = normCompKey(ckey)
                        val rate = if (reqRates != null && i < reqRates.size && (reqRates[i] ?: 0.0) > 0) reqRates[i]!!
                        else { val cp = if ("|" in ckeyNorm) ckeyNorm.split("|", limit = 2)[0] else ckeyNorm
                            if (edgeType == "make") bomRate[targetPid to cp] ?: 1.0 else 1.0 }
                        consumeFromCompFifo(availByNode, consumedByNode, ckeyNorm, outputQty / rate, compPeriods)
                    }
                }
                listOf("1xx-xxxx", "2xx-xxxx", "3xx-xxxx").forEach { name ->
                    ptByPattern[name] = mutableMapOf("used" to false, "total_consumed_qty" to 0.0, "nodes_consumed" to mutableListOf<Map<String, Any?>>())
                }
                for ((node, qty) in consumedByNode) {
                    if (qty <= 0) continue
                    val productId = if ("|" in node) node.split("|", limit = 2)[0] else node
                    val pattern = SkuPatterns.rawMaterialPattern(productId) ?: continue
                    val e = ptByPattern.getOrPut(pattern) { mutableMapOf("used" to false, "total_consumed_qty" to 0.0, "nodes_consumed" to mutableListOf<Map<String, Any?>>()) }
                    e["used"] = true
                    e["total_consumed_qty"] = (e["total_consumed_qty"] as? Double ?: 0.0) + qty
                    @Suppress("UNCHECKED_CAST")
                    (e["nodes_consumed"] as MutableList<Map<String, Any?>>).add(mapOf("node" to node, "consumed_qty" to Math.round(qty * 10000).toDouble() / 10000.0))
                }
            }

            val supplyPatternsUsed = ptByPattern.filter { it.value["used"] as? Boolean == true }.keys.toList()
            val summaryMap = mutableMapOf<String, Map<String, Any?>>()
            val details = mutableListOf<Map<String, Any?>>()
            for ((pattern, info) in ptByPattern) {
                val total = info["total_consumed_qty"] as? Double ?: 0.0
                @Suppress("UNCHECKED_CAST")
                val nodes = (info["nodes_consumed"] as? List<Map<String, Any?>>) ?: emptyList()
                summaryMap[pattern] = mapOf("total_consumed_qty" to Math.round(total * 10000).toDouble() / 10000.0, "node_count" to nodes.size)
                for (item in nodes) {
                    val node = item["node"] as? String ?: ""
                    val parts = node.split("|")
                    val pid2 = parts.getOrElse(0) { "" }
                    val lid2 = parts.getOrElse(1) { "" }
                    details.add(mapOf("pattern" to pattern, "product_id" to pid2, "location_id" to lid2, "node" to node, "consumed_qty" to (item["consumed_qty"] as? Double ?: 0.0)))
                }
            }
            details.sortWith(compareBy({ it["pattern"] as String }, { it["product_id"] as String }, { it["location_id"] as String }, { it["node"] as String }))

            val rawMaterialTraceWithInSupply = rawMaterialTrace.map { elem ->
                val obj = elem.jsonObject
                val compKey = obj["comp_key"]?.jsonPrimitive?.contentOrNull ?: ""
                val ck2 = normCompKey(compKey)
                buildJsonObject {
                    for ((k2, v2) in obj) put(k2, v2)
                    put("in_supply_view", JsonPrimitive(ck2 in suppliesCompKeys))
                }
            }

            call.respond(buildJsonObject {
                put("run_id", runId)
                put("supply_patterns_used", buildJsonArray { supplyPatternsUsed.forEach { add(it) } })
                put("summary", buildJsonObject {
                    for ((k, v) in summaryMap) put(k, buildJsonObject {
                        put("total_consumed_qty", v["total_consumed_qty"] as? Double ?: 0.0)
                        put("node_count", v["node_count"] as? Int ?: 0)
                    })
                })
                put("details", buildJsonArray {
                    details.forEach { d -> add(buildJsonObject {
                        put("pattern", d["pattern"] as? String ?: "")
                        put("product_id", d["product_id"] as? String ?: "")
                        put("location_id", d["location_id"] as? String ?: "")
                        put("node", d["node"] as? String ?: "")
                        put("consumed_qty", d["consumed_qty"] as? Double ?: 0.0)
                    }) }
                })
                put("involvement_trace", JsonArray(rawMaterialTraceWithInSupply))
            })
        }

        // ── GET /allocation-actions ───────────────────────────────────────────
        get("/allocation-actions") {
            val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid run_id")
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 2000)

            val (total, rows) = transaction {
                AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")
                val total = AllocationActions.selectAll().where { AllocationActions.runId eq runId }.count()
                val rows = AllocationActions.selectAll().where { AllocationActions.runId eq runId }
                    .orderBy(AllocationActions.scarcityRank, SortOrder.ASC_NULLS_LAST)
                    .orderBy(AllocationActions.id, SortOrder.ASC)
                    .limit(limit, offset.toLong())
                    .toList()
                total to rows
            }

            call.respond(buildJsonObject {
                put("actions", buildJsonArray {
                    rows.forEach { a -> add(buildJsonObject {
                        put("id", a[AllocationActions.id])
                        put("run_id", a[AllocationActions.runId])
                        put("variant_key", a[AllocationActions.variantKey])
                        put("req_component_ids", jViews.parseToJsonElement(a[AllocationActions.reqComponentIds]))
                        val rr = a[AllocationActions.reqRates]
                        if (rr != null) put("req_rates", jViews.parseToJsonElement(rr)) else put("req_rates", JsonNull)
                        put("qty", a[AllocationActions.qty])
                        val did = a[AllocationActions.demandId]
                        if (did != null) put("demand_id", did) else put("demand_id", JsonNull)
                        val tpid = a[AllocationActions.targetProductId]
                        if (tpid != null) put("target_product_id", tpid) else put("target_product_id", JsonNull)
                        val tloc = a[AllocationActions.targetLocationId]
                        if (tloc != null) put("target_location_id", tloc) else put("target_location_id", JsonNull)
                        val op = a[AllocationActions.outputPeriod]
                        if (op != null) put("output_period", op) else put("output_period", JsonNull)
                        val et = a[AllocationActions.edgeType]
                        if (et != null) put("edge_type", et) else put("edge_type", JsonNull)
                        val sr = a[AllocationActions.scarcityRank]
                        if (sr != null) put("scarcity_rank", sr) else put("scarcity_rank", JsonNull)
                    }) }
                })
                put("total_count", total)
                put("offset", offset)
                put("limit", limit)
            })
        }

        // ── GET /allocation-view ──────────────────────────────────────────────
        get("/allocation-view") {
            val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid run_id")
            val maxActions = call.request.queryParameters["max_actions"]?.toIntOrNull()
            val fromStep = call.request.queryParameters["from_step"]?.toIntOrNull()
            val toStep = call.request.queryParameters["to_step"]?.toIntOrNull()
            val skipBasket = call.request.queryParameters["skip_basket"]?.lowercase() == "true"

            val (fullResult, basketInitial, basketDeltas, basketFinal, basketPrunes, totalActionsCount, actionsLoadedCount) = transaction {
                val run = AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")

                val totalActions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }.count()
                val limit = minOf(
                    maxActions ?: DEFAULT_MAX_ACTIONS_FIRST_LOAD,
                    MAX_ACTIONS_ALLOCATION_VIEW
                )

                val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }
                    .orderBy(AllocationActions.scarcityRank, SortOrder.ASC_NULLS_LAST)
                    .orderBy(AllocationActions.id, SortOrder.ASC)
                    .limit(limit)
                    .toList()

                val actionsSorted = actions.sortedWith(compareBy({ it[AllocationActions.scarcityRank] ?: 999999 }, { it[AllocationActions.id] }))
                val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.toList()
                val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
                val (dateToPeriod, sortedDates) = TimeUtils.buildPeriodIndex(
                    supplies.map { mapOf("supply_date" to it[Supplies.supplyDate]) },
                    demands.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
                )

                val keyToSupplyId = mutableMapOf<String, String>()
                for (s in supplies) {
                    val key = normCompKey("${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}")
                    keyToSupplyId.putIfAbsent(key, s[Supplies.supplyId])
                }
                val leadTimeByVariant = mutableMapOf<Pair<String, String>, Double>()
                MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }.forEach { m ->
                    leadTimeByVariant[m[MethodMakes.productId] to m[MethodMakes.locationId]] = m[MethodMakes.leadTime]?.toDouble() ?: 0.0
                }
                val moveTransit = mutableMapOf<Triple<String, String, String>, Double>()
                MethodMoves.selectAll().where { MethodMoves.caseId eq caseId }.forEach { mv ->
                    val fl = mv[MethodMoves.fromLocationId]; val tl = mv[MethodMoves.toLocationId]
                    if (fl != tl) moveTransit[Triple(mv[MethodMoves.productId], fl, tl)] = mv[MethodMoves.transitTime] ?: 0.0
                }
                val productToDemands = mutableMapOf<String, MutableList<String>>()
                for (d in demands) productToDemands.getOrPut(d[Demands.productId]) { mutableListOf() }.add(d[Demands.demandId])

                val bomRate = mutableMapOf<Pair<String, String>, Double>()
                Boms.selectAll().where { Boms.caseId eq caseId }.forEach { b ->
                    bomRate.putIfAbsent(b[Boms.parentId] to b[Boms.childId], b[Boms.rate] ?: 1.0)
                }

                val supplyAdj = mutableMapOf<String, Double>()
                ManualOverrides.selectAll().where { ManualOverrides.caseId eq caseId }.forEach { o ->
                    if (o[ManualOverrides.entityType] == "supply") {
                        val payload = try { jViews.parseToJsonElement(o[ManualOverrides.payload]).jsonObject } catch (_: Exception) { return@forEach }
                        if (payload.containsKey("quantity")) supplyAdj[o[ManualOverrides.entityKey]] = payload["quantity"]!!.jsonPrimitive.double
                    }
                }

                var basketInitialOut: List<Map<String, Any?>> = emptyList()
                var basketDeltasOut: List<Map<String, Any?>> = emptyList()
                var basketFinalOut: List<Map<String, Any?>> = emptyList()
                var fromInvToStep: Map<String, Int> = emptyMap()
                var demandToStep: Map<String, Int> = emptyMap()

                if (!skipBasket && actionsSorted.isNotEmpty()) {
                    val rep = replayBasketSnapshots(
                        supplies, actionsSorted, bomRate, leadTimeByVariant, moveTransit,
                        dateToPeriod, sortedDates, supplyAdj, MAX_ACTIONS_ALLOCATION_VIEW
                    )
                    basketInitialOut = rep.initialItems
                    basketDeltasOut = rep.basketDeltas
                    basketFinalOut = rep.basketFinal
                    fromInvToStep = rep.fromInventoryIdToStep
                    demandToStep = rep.demandIdToStep
                }

                val (invRows, demandRows) = buildAllocationViewFlat(
                    actionsSorted, supplies, demands, dateToPeriod, sortedDates,
                    keyToSupplyId, leadTimeByVariant, moveTransit, productToDemands
                )

                // Group by from_inventory_id
                val groups = mutableMapOf<String, MutableList<Map<String, Any?>>>()
                for (r in invRows) {
                    val fid = r["from_inventory_id"] as? String ?: ""
                    if (fid.isNotEmpty()) groups.getOrPut(fid) { mutableListOf() }.add(r)
                }

                val componentRows = mutableListOf<MutableMap<String, Any?>>()
                for ((fromInventoryId, rows2) in groups) {
                    if (rows2.isEmpty()) continue
                    val r0 = rows2[0]
                    val scarcityRank = rows2.mapNotNull { it["scarcity_rank"] as? Int }.minOrNull() ?: 999999
                    val agg = mutableMapOf<Pair<String, String>, Double>()
                    for (r in rows2) {
                        val q = (r["qty"] as? Double) ?: 0.0; if (q <= 0) continue
                        val k = (r["to_inventory_id"] as? String ?: "") to (r["edge_type"] as? String ?: "")
                        agg[k] = (agg[k] ?: 0.0) + q
                    }
                    val candidates = agg.entries.filter { it.value > 0 }
                        .sortedWith(compareBy({ -it.value }, { it.key.first }))
                        .map { (k, qty) ->
                            val (toId, et) = k
                            mapOf("to_inventory_id" to toId, "to_inventory_display" to (if (toId.isNotEmpty()) invNodeDisplay(toId, sortedDates) else ""),
                                "qty" to Math.round(qty * 10000).toDouble() / 10000.0, "edge_type" to et,
                                "to_variant_key" to (if (toId.isNotEmpty() && "|" in toId) toId.substringBeforeLast("|") else toId))
                        }
                    if (candidates.isEmpty()) continue
                    val totalQty = candidates.sumOf { it["qty"] as? Double ?: 0.0 }
                    val pctParts = candidates.map { c ->
                        val pct = if (totalQty > 0) (c["qty"] as Double) / totalQty * 100 else 0.0
                        "${c["to_inventory_display"]}: ${c["qty"]} (${"%.0f".format(pct)}%)"
                    }
                    val splitSummary = pctParts.joinToString(" | ")
                    val splitExplanation = "Split by target weight (demand-priority): allocation = (target_weight / total_target_weight) × available. " +
                        "This critical component limited the step; quantities above are the resulting output per candidate. " +
                        "Details: $splitSummary. Click component for full formula."
                    val allDemandIds = rows2.flatMap { (it["demand_ids"] as? List<*>)?.map { d -> d.toString() } ?: emptyList() }
                    val basketStepIndex = fromInvToStep[fromInventoryId] ?: 0
                    componentRows.add(mutableMapOf(
                        "row_type" to "component",
                        "scarcity_rank" to scarcityRank,
                        "edge_type" to r0["edge_type"],
                        "from_inventory_id" to fromInventoryId,
                        "from_inventory_display" to (r0["from_inventory_display"] ?: ""),
                        "critical_component_key" to r0["critical_component_key"],
                        "to_inventory_id" to null,
                        "to_inventory_display" to null,
                        "candidates" to candidates,
                        "total_qty" to Math.round(totalQty * 10000).toDouble() / 10000.0,
                        "split_explanation" to splitExplanation,
                        "output_date" to r0["output_date"],
                        "output_period" to r0["output_period"],
                        "demand_ids" to allDemandIds.distinct(),
                        "supply_id" to r0["supply_id"],
                        "basket_step_index" to basketStepIndex,
                    ))
                }
                componentRows.sortWith(compareBy({ it["scarcity_rank"] as? Int ?: 999999 }, { it["from_inventory_id"] as? String ?: "" }))

                val demandRowsMut = demandRows.map { d ->
                    val dm = d.toMutableMap()
                    dm["row_type"] = "demand"
                    dm["candidates"] = emptyList<Any>()
                    dm["total_qty"] = dm["qty"] ?: 0.0
                    dm["split_explanation"] = null
                    dm["critical_component_key"] = null
                    dm["supply_id"] = ""
                    val did2 = (dm["demand_ids"] as? List<*>)?.firstOrNull()?.toString()
                    dm["basket_step_index"] = if (did2 != null) demandToStep[did2] ?: 0 else 0
                    dm
                }

                val fullResult2 = (componentRows + demandRowsMut).mapIndexed { i, r ->
                    val rm = r.toMutableMap(); rm["step"] = i + 1; rm
                }

                val configText = run[AllocationRuns.config] ?: "{}"
                val configJson = try { jViews.parseToJsonElement(configText).jsonObject } catch (_: Exception) { buildJsonObject {} }
                val prunes = configJson["prunes"]?.jsonArray ?: buildJsonArray {}

                data class R7(
                    val fr: List<Map<String, Any?>>, val bi: List<Map<String, Any?>>, val bd: List<Map<String, Any?>>,
                    val bf: List<Map<String, Any?>>, val bp: JsonArray, val ta: Long, val al: Int
                )
                R7(fullResult2, basketInitialOut, basketDeltasOut, basketFinalOut, prunes, totalActions, actions.size)
            }

            val totalSteps = fullResult.size
            val stepFrom = if (fromStep != null) (fromStep - 1).coerceAtLeast(0) else 0
            val stepTo = minOf(toStep ?: totalSteps, totalSteps)
            val resultSlice = fullResult.subList(stepFrom, stepTo)

            val biOut = if (skipBasket) emptyList() else basketInitial
            val bdOut = if (skipBasket) emptyList() else basketDeltas
            val bfOut: List<Map<String, Any?>>? = if (skipBasket) null else basketFinal
            val bpOut = if (skipBasket) buildJsonArray {} else basketPrunes

            call.respond(buildJsonObject {
                put("run_id", runId)
                put("allocation_view", buildJsonArray {
                    resultSlice.forEach { row -> add(buildMapAsJson(row, sortedDates = emptyList())) }
                })
                put("truncated", totalActionsCount > actionsLoadedCount)
                put("total_actions", totalActionsCount)
                put("limit", actionsLoadedCount)
                put("total_steps", totalSteps)
                if (resultSlice.isNotEmpty()) put("from_step", stepFrom + 1) else put("from_step", JsonNull)
                if (resultSlice.isNotEmpty()) put("to_step", stepTo) else put("to_step", JsonNull)
                put("basket_initial", buildJsonArray { biOut.forEach { add(buildBasketItemJson(it)) } })
                put("basket_deltas", buildJsonArray { bdOut.forEach { d -> add(buildDeltaJson(d)) } })
                if (bfOut != null) put("basket_final", buildJsonArray { bfOut.forEach { add(buildBasketItemJson(it)) } }) else put("basket_final", JsonNull)
                put("basket_prunes", bpOut)
            })
        }

        // ── GET /allocation-view-basket ───────────────────────────────────────
        get("/allocation-view-basket") {
            val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid run_id")
            val maxActionsParam = call.request.queryParameters["max_actions"]?.toIntOrNull()

            val (bi, bd, bf, prunes) = transaction {
                val run = AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")
                val limit = minOf(maxActionsParam ?: DEFAULT_MAX_ACTIONS_FIRST_LOAD, MAX_ACTIONS_ALLOCATION_VIEW)
                val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }
                    .orderBy(AllocationActions.scarcityRank, SortOrder.ASC_NULLS_LAST)
                    .orderBy(AllocationActions.id, SortOrder.ASC)
                    .limit(limit).toList()
                    .sortedWith(compareBy({ it[AllocationActions.scarcityRank] ?: 999999 }, { it[AllocationActions.id] }))
                val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.toList()
                val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
                val (dateToPeriod, sortedDates) = TimeUtils.buildPeriodIndex(
                    supplies.map { mapOf("supply_date" to it[Supplies.supplyDate]) },
                    demands.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
                )
                val leadTimeByVariant = mutableMapOf<Pair<String, String>, Double>()
                MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }.forEach { m ->
                    leadTimeByVariant[m[MethodMakes.productId] to m[MethodMakes.locationId]] = m[MethodMakes.leadTime]?.toDouble() ?: 0.0
                }
                val moveTransit = mutableMapOf<Triple<String, String, String>, Double>()
                MethodMoves.selectAll().where { MethodMoves.caseId eq caseId }.forEach { mv ->
                    val fl = mv[MethodMoves.fromLocationId]; val tl = mv[MethodMoves.toLocationId]
                    if (fl != tl) moveTransit[Triple(mv[MethodMoves.productId], fl, tl)] = mv[MethodMoves.transitTime] ?: 0.0
                }
                val bomRate = mutableMapOf<Pair<String, String>, Double>()
                Boms.selectAll().where { Boms.caseId eq caseId }.forEach { b ->
                    bomRate.putIfAbsent(b[Boms.parentId] to b[Boms.childId], b[Boms.rate] ?: 1.0)
                }
                val supplyAdj = mutableMapOf<String, Double>()
                ManualOverrides.selectAll().where { ManualOverrides.caseId eq caseId }.forEach { o ->
                    if (o[ManualOverrides.entityType] == "supply") {
                        val p = try { jViews.parseToJsonElement(o[ManualOverrides.payload]).jsonObject } catch (_: Exception) { return@forEach }
                        if (p.containsKey("quantity")) supplyAdj[o[ManualOverrides.entityKey]] = p["quantity"]!!.jsonPrimitive.double
                    }
                }
                val rep = if (actions.isNotEmpty()) replayBasketSnapshots(
                    supplies, actions, bomRate, leadTimeByVariant, moveTransit, dateToPeriod, sortedDates, supplyAdj, MAX_ACTIONS_ALLOCATION_VIEW
                ) else BasketReplayResult(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList())
                val configText = run[AllocationRuns.config] ?: "{}"
                val cj = try { jViews.parseToJsonElement(configText).jsonObject } catch (_: Exception) { buildJsonObject {} }
                data class R4(val a: List<Map<String, Any?>>, val b: List<Map<String, Any?>>, val c: List<Map<String, Any?>>, val d: JsonArray)
                R4(rep.initialItems, rep.basketDeltas, rep.basketFinal, cj["prunes"]?.jsonArray ?: buildJsonArray {})
            }

            call.respond(buildJsonObject {
                put("basket_initial", buildJsonArray { bi.forEach { add(buildBasketItemJson(it)) } })
                put("basket_deltas", buildJsonArray { bd.forEach { d -> add(buildDeltaJson(d)) } })
                put("basket_final", buildJsonArray { bf.forEach { add(buildBasketItemJson(it)) } })
                put("basket_prunes", prunes)
            })
        }
    }

    // ── GET /cases/{case_id}/trace-component ──────────────────────────────────
    get("/cases/{case_id}/trace-component") {
        val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
        val componentKey = call.request.queryParameters["component_key"]?.trim()
            ?: throw IllegalArgumentException("component_key required")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull() ?: throw NoSuchElementException("Case not found")
        }
        val data = CaseLoader.load(caseId)
        if (data["supply"].isNullOrEmpty() || data["demand"].isNullOrEmpty())
            throw IllegalArgumentException("Case has no supply or demand data")
        val result = runAllocation(data, traceComponentKey = componentKey)
        val trace = result.trace ?: emptyList()
        call.respond(buildJsonObject {
            put("component_key", componentKey)
            put("_trace", buildJsonArray { trace.forEach { t -> add(buildJsonObject { t.forEach { (k, v) -> put(k, v?.toString() ?: "") } }) } })
            put("trace_event_count", trace.size)
        })
    }
}

// ── JSON helpers ─────────────────────────────────────────────────────────────

private fun buildBasketItemJson(item: Map<String, Any?>): JsonObject = buildJsonObject {
    put("key", item["key"] as? String ?: "")
    put("display", item["display"] as? String ?: "")
    put("qty", item["qty"] as? Double ?: 0.0)
}

private fun buildDeltaJson(delta: Map<String, Any?>): JsonObject = buildJsonObject {
    @Suppress("UNCHECKED_CAST")
    val purged = delta["purged"] as? List<Map<String, Any?>> ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val added = delta["added"] as? List<Map<String, Any?>> ?: emptyList()
    put("purged", buildJsonArray { purged.forEach { add(buildBasketItemJson(it)) } })
    put("added", buildJsonArray { added.forEach { add(buildBasketItemJson(it)) } })
}

@Suppress("UNCHECKED_CAST")
private fun buildMapAsJson(row: Map<String, Any?>, sortedDates: List<String>): JsonObject = buildJsonObject {
    for ((k, v) in row) {
        when (v) {
            null -> put(k, JsonNull)
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Double -> put(k, v)
            is Float -> put(k, v.toDouble())
            is Boolean -> put(k, v)
            is List<*> -> put(k, buildJsonArray { v.forEach { item ->
                when (item) {
                    null -> add(JsonNull)
                    is String -> add(item)
                    is Int -> add(item)
                    is Double -> add(item)
                    is Map<*, *> -> add(buildJsonObject {
                        (item as Map<String, Any?>).forEach { (ik, iv) ->
                            when (iv) {
                                null -> put(ik, JsonNull)
                                is String -> put(ik, iv)
                                is Int -> put(ik, iv)
                                is Double -> put(ik, iv)
                                is Boolean -> put(ik, iv)
                                else -> put(ik, iv.toString())
                            }
                        }
                    })
                    else -> add(item.toString())
                }
            } })
            is Map<*, *> -> put(k, buildJsonObject {
                (v as Map<String, Any?>).forEach { (mk, mv) ->
                    when (mv) {
                        null -> put(mk, JsonNull)
                        is String -> put(mk, mv)
                        is Double -> put(mk, mv)
                        is Int -> put(mk, mv)
                        else -> put(mk, mv.toString())
                    }
                }
            })
            else -> put(k, v.toString())
        }
    }
}

// Local tuple-4 helper used in supply view only
private data class SupplyNodeItem(val idx: Int, val initQty: Double, val period: Int, val supplyId: String)
