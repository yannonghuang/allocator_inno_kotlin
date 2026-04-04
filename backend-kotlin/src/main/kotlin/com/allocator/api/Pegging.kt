package com.allocator.api

import com.allocator.*
import com.allocator.services.TimeUtils
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val jPeg = Json { ignoreUnknownKeys = true }

private fun parseStrListP(t: String?): List<String> {
    if (t.isNullOrBlank()) return emptyList()
    return try { jPeg.parseToJsonElement(t).jsonArray.map { it.jsonPrimitive.content } } catch (_: Exception) { emptyList() }
}

private fun invNodeId(product: String, location: String, period: Int): String =
    "$product|${location}|$period"

// ── feasibleDemandsFromActions ─────────────────────────────────────────────────

/** Port of _feasible_demands_from_actions from allocate.py. */
internal fun feasibleDemandsFromActions(
    caseId: Int,
    actions: List<ResultRow>,
): List<Map<String, Any?>> = transaction {
    val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.toList()
    val demands = Demands.selectAll().where { Demands.caseId eq caseId }
        .orderBy(Demands.priority, SortOrder.ASC).toList()
    val customers = Customers.selectAll().where { Customers.caseId eq caseId }.toList()

    val supplyList = supplies.map { mapOf("supply_date" to it[Supplies.supplyDate]) }
    val demandList = demands.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
    val (dateToPeriod, sortedDates) = TimeUtils.buildPeriodIndex(supplyList, demandList)

    val custById = customers.associate { c -> c[Customers.customer] to (c[Customers.description] ?: c[Customers.customer]) }

    data class DemandInfo(
        val requested: Double, var allocated: Double, val productId: String,
        val duePeriod: Int, val requestDueTime: String?, var revisedPeriod: Int?,
        val customerId: String, val customer: String,
    )
    val byDemand = mutableMapOf<String, DemandInfo>()
    for (d in demands) {
        val duePer = TimeUtils.demandDuePeriod(d[Demands.requestDueTime], dateToPeriod)
        byDemand[d[Demands.demandId]] = DemandInfo(
            requested = d[Demands.quantity], allocated = 0.0, productId = d[Demands.productId],
            duePeriod = duePer, requestDueTime = d[Demands.requestDueTime], revisedPeriod = null,
            customerId = d[Demands.customerId], customer = custById[d[Demands.customerId]] ?: d[Demands.customerId],
        )
    }
    val demandsByProduct = mutableMapOf<String, MutableList<ResultRow>>()
    for (d in demands) demandsByProduct.getOrPut(d[Demands.productId]) { mutableListOf() }.add(d)

    val sortedActions = actions.sortedWith(compareBy(
        { it[AllocationActions.scarcityRank] ?: 999999 },
        { it[AllocationActions.id] }
    ))

    for (a in sortedActions) {
        val pid = a[AllocationActions.targetProductId] ?: continue
        val outPerRaw = a[AllocationActions.outputPeriod]
        val outPer = outPerRaw ?: 0
        val demandId = a[AllocationActions.demandId]
        val qty = a[AllocationActions.qty]
        if (demandId != null && outPerRaw != null && demandId in byDemand) {
            val di = byDemand[demandId]!!
            if (di.revisedPeriod == null || outPerRaw > di.revisedPeriod!!) di.revisedPeriod = outPerRaw
        }
        if (qty <= 0) continue
        val dmds = demandsByProduct[pid] ?: continue
        var remaining = qty
        for (d in dmds) {
            if (remaining <= 0) break
            val did = d[Demands.demandId]
            val di = byDemand[did] ?: continue
            val duePer = di.duePeriod
            if (duePer != 0 && outPer > duePer) continue
            val give = minOf(di.requested - di.allocated, remaining)
            if (give > 0) { di.allocated += give; remaining -= give }
        }
    }

    byDemand.map { (did, v) ->
        val req = v.requested; val alloc = v.allocated
        val status = if (alloc >= req) "fulfilled" else if (alloc > 0) "partial" else "unfulfilled"
        val suggestion = if (alloc >= req) "Fulfilled"
            else if (alloc > 0) "Reduce to $alloc"
            else "Unfulfilled (0 allocated)"
        val revisedTime = TimeUtils.periodToDate(v.revisedPeriod, sortedDates)
        val fulfillmentRate = if (req > 0) alloc / req else null
        mapOf(
            "demand_id" to did,
            "customer_id" to v.customerId,
            "customer" to v.customer,
            "product_id" to v.productId,
            "requested_qty" to req,
            "allocated_qty" to alloc,
            "fulfillment_rate" to if (fulfillmentRate != null) Math.round(fulfillmentRate * 10000).toDouble() / 10000.0 else null,
            "status" to status,
            "suggested_revision" to suggestion,
            "request_due_time" to v.requestDueTime,
            "revised_time" to revisedTime,
        )
    }
}

// ── Graph builders ─────────────────────────────────────────────────────────────

private data class CaseGraph(
    val nodes: MutableMap<String, MutableMap<String, Any?>>,
    val edges: MutableList<MutableMap<String, Any?>>,
    val supplyByComp: Map<String, Double>,
)

private fun buildInventoryGraph(
    caseId: Int, runId: Int
): InvGraphTuple<MutableMap<String, MutableMap<String, Any?>>, MutableList<MutableMap<String, Any?>>, Map<String, Double>, List<String>> {
    return transaction {
        val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.toList()
        val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
        val supplyList = supplies.map { mapOf("supply_date" to it[Supplies.supplyDate]) }
        val demandList = demands.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
        val (dateToPeriod, sortedDates) = TimeUtils.buildPeriodIndex(supplyList, demandList)

        // inv_qty: nid -> qty accumulated from supplies + production
        val invQty = mutableMapOf<String, Double>()
        for (s in supplies) {
            val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
            val nid = invNodeId(s[Supplies.productId], s[Supplies.locationId] ?: "", period)
            invQty[nid] = (invQty[nid] ?: 0.0) + s[Supplies.qty]
        }

        val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }.toList()
        val makeLead = mutableMapOf<Pair<String, String>, Int>()
        MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }.forEach { m ->
            makeLead[m[MethodMakes.productId] to m[MethodMakes.locationId]] = m[MethodMakes.leadTime] ?: 0
        }
        val moveTransit = mutableMapOf<Triple<String, String, String>, Int>()
        MethodMoves.selectAll().where { MethodMoves.caseId eq caseId }.forEach { mv ->
            val fl = mv[MethodMoves.fromLocationId]; val tl = mv[MethodMoves.toLocationId]
            if (fl != tl) moveTransit[Triple(mv[MethodMoves.productId], fl, tl)] = mv[MethodMoves.transitTime]?.toInt() ?: 0
        }
        val boms = Boms.selectAll().where { Boms.caseId eq caseId }.toList()
        val bomRate = mutableMapOf<Pair<String, String>, Double>()
        for (b in boms) {
            val r = if (b[Boms.rate] != null && b[Boms.rate] != 0.0) b[Boms.rate]!! else 1.0
            bomRate[b[Boms.parentId] to b[Boms.childId]] = r
        }

        for (a in actions) {
            val vk = a[AllocationActions.variantKey] ?: ""
            val outPer = a[AllocationActions.outputPeriod] ?: 0
            val outputQty = a[AllocationActions.qty]
            val pid = if ("|" in vk) vk.split("|", limit = 2)[0] else vk
            val toLoc = if ("|" in vk) vk.split("|", limit = 2)[1] else ""
            val nid = invNodeId(pid, toLoc, outPer)
            invQty[nid] = (invQty[nid] ?: 0.0) + outputQty
        }

        // Build edges from allocation actions using FIFO matching
        val edgeRealQty = mutableMapOf<Pair<String, String>, Triple<Double, Int, Int>>() // (from,to) -> (qty, periodFrom, periodTo)
        val usedByNode = mutableMapOf<String, Double>()

        for (a in actions) {
            val vk = a[AllocationActions.variantKey] ?: ""
            val outPer = a[AllocationActions.outputPeriod] ?: 0
            val outputQty = a[AllocationActions.qty]
            val edgeType = (a[AllocationActions.edgeType] ?: "make").lowercase()
            val pid = if ("|" in vk) vk.split("|", limit = 2)[0] else vk
            val toLoc = if ("|" in vk) vk.split("|", limit = 2)[1] else ""
            val toNid = invNodeId(pid, toLoc, outPer)
            var reqComponentIds = parseStrListP(a[AllocationActions.reqComponentIds])
            if (reqComponentIds.isEmpty()) {
                // fallback: derive from BOM or MethodMove
                if (edgeType == "move" && "|" in vk) {
                    val mv = MethodMoves.selectAll().where {
                        (MethodMoves.caseId eq caseId) and (MethodMoves.productId eq pid) and (MethodMoves.toLocationId eq toLoc)
                    }.firstOrNull()
                    if (mv != null && mv[MethodMoves.fromLocationId] != mv[MethodMoves.toLocationId]) {
                        reqComponentIds = listOf("$pid|${mv[MethodMoves.fromLocationId]}")
                    }
                } else {
                    reqComponentIds = boms.filter { it[Boms.parentId] == pid }.map { b -> "${b[Boms.childId]}|$toLoc" }
                }
            }

            for (compKey in reqComponentIds) {
                val compParts = compKey.split("|", limit = 2)
                val compProduct = compParts[0]
                val consumption = if (edgeType == "move") outputQty
                    else { val r = bomRate[pid to compProduct] ?: 1.0; if (r > 0) outputQty / r else 0.0 }
                val prefix = "$compKey|"
                val matching = invQty.entries
                    .filter { (n, q) -> q > 0 && n.startsWith(prefix) }
                    .map { (n, q) ->
                        val parts = n.split("|")
                        val pf = parts.getOrNull(2)?.toIntOrNull() ?: 0
                        Triple(n, maxOf(0.0, q - (usedByNode[n] ?: 0.0)), pf)
                    }
                    .filter { it.second > 0 }
                    .sortedWith(compareBy({ it.third }, { it.first }))

                var remaining = consumption
                for ((fromNid, avail, pf) in matching) {
                    if (remaining <= 0) break
                    val take = minOf(avail, remaining)
                    if (take <= 0) continue
                    val key = fromNid to toNid
                    val existing = edgeRealQty[key]
                    edgeRealQty[key] = if (existing == null) Triple(take, pf, outPer)
                        else Triple(existing.first + take, existing.second, existing.third)
                    usedByNode[fromNid] = (usedByNode[fromNid] ?: 0.0) + take
                    remaining -= take
                }
            }
        }

        val edges = edgeRealQty.entries
            .filter { it.value.first > 0 }
            .map { (k, v) ->
                mutableMapOf<String, Any?>("from" to k.first, "to" to k.second, "qty" to Math.round(v.first * 10000).toDouble() / 10000.0,
                    "period_from" to v.second, "period_to" to v.third)
            }.toMutableList()

        val productToLocation = mutableMapOf<String, String>()
        MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }.forEach { m ->
            productToLocation[m[MethodMakes.productId]] = m[MethodMakes.locationId]
        }
        val demandByVariant = mutableMapOf<String, Double>()
        val demandCountByVariant = mutableMapOf<String, Int>()
        for (d in demands) {
            val loc = d[Demands.locationId] ?: productToLocation[d[Demands.productId]] ?: "VIRTUAL"
            val vk = "${d[Demands.productId]}|$loc"
            demandByVariant[vk] = (demandByVariant[vk] ?: 0.0) + d[Demands.quantity]
            demandCountByVariant[vk] = (demandCountByVariant[vk] ?: 0) + 1
        }

        // Build node dict
        val edgeFromSet = edges.map { it["from"] as String }.toSet()
        val nodes = mutableMapOf<String, MutableMap<String, Any?>>()
        for ((nid, qty) in invQty) {
            val parts = nid.split("|")
            val productId = parts.getOrElse(0) { "" }
            val locationId = parts.getOrElse(1) { "" }
            val period = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val dateStr = if (period > 0) TimeUtils.periodToDate(period, sortedDates) else "preexisting"
            val label = "$productId|$locationId ($dateStr)"
            val n = mutableMapOf<String, Any?>(
                "id" to nid, "label" to label, "product_id" to productId, "location_id" to locationId,
                "period" to period, "qty" to qty,
                "type" to if (nid in edgeFromSet) "component" else "variant",
            )
            val variantKey = "$productId|$locationId"
            if (variantKey in demandByVariant && demandByVariant[variantKey] != 0.0) {
                n["demand_qty"] = demandByVariant[variantKey]
                n["demand_count"] = demandCountByVariant[variantKey]
            }
            nodes[nid] = n
        }
        for (e in edges) {
            val toId = e["to"] as? String ?: ""
            if (toId in nodes && nodes[toId]?.get("type") != "component") nodes[toId]?.set("type", "variant")
        }

        // Raw allocated qty per (variant_key, demand_id)
        val totalAllocByVariant = mutableMapOf<String, Double>()
        for (a in actions) {
            val vk = a[AllocationActions.variantKey] ?: ""
            val did = a[AllocationActions.demandId]
            if (vk.isNotEmpty() && did != null) totalAllocByVariant[vk] = (totalAllocByVariant[vk] ?: 0.0) + a[AllocationActions.qty]
        }

        val feasible = feasibleDemandsFromActions(caseId, actions)
        val cappedAllocByDemand = feasible.associate { f -> f["demand_id"].toString() to ((f["allocated_qty"] as? Double) ?: 0.0) }

        val outFlowInvInv = mutableMapOf<String, Double>()
        for (e in edges) outFlowInvInv[e["from"] as String] = (outFlowInvInv[e["from"] as String] ?: 0.0) + ((e["qty"] as? Double) ?: 0.0)

        val usedByNodeDemand = mutableMapOf<String, Double>()

        // Add demand nodes and inv->demand edges
        for (d in demands) {
            val demandNid = "demand|${d[Demands.demandId]}"
            val loc = d[Demands.locationId] ?: productToLocation[d[Demands.productId]] ?: "VIRTUAL"
            val variantKey = "${d[Demands.productId]}|$loc"
            val demandQty = d[Demands.quantity]
            val alloc = cappedAllocByDemand[d[Demands.demandId].toString()] ?: 0.0
            val period = TimeUtils.demandDuePeriod(d[Demands.requestDueTime], dateToPeriod)
            val dateStr = when {
                period > 0 -> TimeUtils.periodToDate(period, sortedDates) ?: "–"
                period == 0 -> "preexisting"
                else -> "–"
            }
            nodes[demandNid] = mutableMapOf(
                "id" to demandNid, "label" to "Demand ${d[Demands.demandId]} | $loc ($dateStr)",
                "product_id" to d[Demands.productId], "location_id" to loc,
                "period" to period, "qty" to demandQty, "demand_qty" to demandQty,
                "type" to "demand", "demand_id" to d[Demands.demandId],
            )

            val prefix = "$variantKey|"
            val allInvNids = nodes.keys.filter { !it.startsWith("demand|") && it.startsWith(prefix) }
                .sortedWith(compareBy({
                    val p3 = it.split("|").getOrNull(2)?.toIntOrNull() ?: 0; p3
                }, { it }))

            var remaining = maxOf(0.0, alloc)
            for (nid in allInvNids) {
                if (remaining <= 0) break
                val nodeQty = invQty[nid] ?: 0.0
                val alreadyInv = outFlowInvInv[nid] ?: 0.0
                val alreadyDemand = usedByNodeDemand[nid] ?: 0.0
                val available = maxOf(0.0, nodeQty - alreadyInv - alreadyDemand)
                val take = Math.round(minOf(available, remaining) * 10000).toDouble() / 10000.0
                if (take > 0) {
                    edges.add(mutableMapOf("from" to nid, "to" to demandNid, "qty" to take, "period_from" to null, "period_to" to null))
                    usedByNodeDemand[nid] = (usedByNodeDemand[nid] ?: 0.0) + take
                    remaining -= take
                }
            }
        }

        // Guarantee: rescale inv->demand edges so sum <= node qty
        val outSum = mutableMapOf<String, Double>()
        val invToDemandEdges = mutableListOf<MutableMap<String, Any?>>()
        val otherEdges = mutableListOf<MutableMap<String, Any?>>()
        for (e in edges) {
            val toStr = e["to"] as? String ?: ""
            val fromStr = e["from"] as? String ?: ""
            if (toStr.startsWith("demand|") && !fromStr.startsWith("demand|")) {
                invToDemandEdges.add(e)
            } else {
                otherEdges.add(e)
                outSum[fromStr] = (outSum[fromStr] ?: 0.0) + ((e["qty"] as? Double) ?: 0.0)
            }
        }
        val demandSumByNid = mutableMapOf<String, Double>()
        for (e in invToDemandEdges) {
            val f = e["from"] as? String ?: ""
            outSum[f] = (outSum[f] ?: 0.0) + ((e["qty"] as? Double) ?: 0.0)
            demandSumByNid[f] = (demandSumByNid[f] ?: 0.0) + ((e["qty"] as? Double) ?: 0.0)
        }
        for (e in invToDemandEdges) {
            val nid = e["from"] as? String ?: ""
            val nodeQty = invQty[nid] ?: 0.0
            val total = outSum[nid] ?: 0.0
            val demandSum = demandSumByNid[nid] ?: 0.0
            val invInvSum = total - demandSum
            if (total > 0 && nodeQty >= 0 && demandSum > 0 && total > nodeQty) {
                val scale = if (demandSum > 0) maxOf(0.0, minOf(1.0, (nodeQty - invInvSum) / demandSum)) else 0.0
                e["qty"] = Math.round(((e["qty"] as? Double) ?: 0.0) * scale * 10000).toDouble() / 10000.0
            } else if (demandSum <= 0 || nodeQty < 0) {
                e["qty"] = 0.0
            }
        }

        val allEdges = (otherEdges + invToDemandEdges).toMutableList()
        val supplyQtyByNode = nodes.entries
            .filter { it.value["type"] != "demand" }
            .associate { (k, v) -> k to ((v["qty"] as? Double) ?: 0.0) }
            .toMutableMap()
        for (nid in nodes.keys) if (nodes[nid]?.get("type") == "demand") supplyQtyByNode[nid] = 0.0

        InvGraphTuple(nodes, allEdges, supplyQtyByNode as Map<String, Double>, sortedDates)
    }
}

// ── Prune helpers ─────────────────────────────────────────────────────────────

private fun pruneSupplyToDemandInv(
    caseId: Int,
    nodes: MutableMap<String, MutableMap<String, Any?>>,
    edges: MutableList<MutableMap<String, Any?>>,
    rootNid: String,
): Pair<MutableMap<String, MutableMap<String, Any?>>, MutableList<MutableMap<String, Any?>>> {
    return transaction {
        val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
        if (demands.isEmpty()) return@transaction nodes to edges

        val productToLocation = mutableMapOf<String, String>()
        MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }.forEach { m ->
            productToLocation[m[MethodMakes.productId]] = m[MethodMakes.locationId]
        }
        val demandVariantKeys = demands.map { d ->
            val loc = d[Demands.locationId] ?: productToLocation[d[Demands.productId]] ?: "VIRTUAL"
            "${d[Demands.productId]}|$loc"
        }.toSet()
        val demandNodes = nodes.keys.filter { nid ->
            nid.split("|").take(2).joinToString("|") in demandVariantKeys || nid.startsWith("demand|")
        }.toMutableSet()

        val inEdgesMap = mutableMapOf<String, MutableList<String>>()
        val outEdgesMap = mutableMapOf<String, MutableList<String>>()
        for (e in edges) {
            val f = e["from"] as? String ?: ""; val t = e["to"] as? String ?: ""
            inEdgesMap.getOrPut(t) { mutableListOf() }.add(f)
            outEdgesMap.getOrPut(f) { mutableListOf() }.add(t)
        }

        val canReachDemand = demandNodes.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            for (nid in canReachDemand.toList()) {
                for (pred in inEdgesMap[nid] ?: emptyList()) if (canReachDemand.add(pred)) changed = true
                for (succ in outEdgesMap[nid] ?: emptyList()) if (canReachDemand.add(succ)) changed = true
            }
        }
        val reachableFromRoot = mutableSetOf(rootNid)
        val frontier = mutableListOf(rootNid)
        while (frontier.isNotEmpty()) {
            val nid = frontier.removeLast()
            for (succ in outEdgesMap[nid] ?: emptyList()) if (reachableFromRoot.add(succ)) frontier.add(succ)
        }
        val visible = (reachableFromRoot intersect canReachDemand).ifEmpty { setOf(rootNid) }
        nodes.keys.retainAll(visible)
        edges.retainAll { e -> (e["from"] as? String) in visible && (e["to"] as? String) in visible }
        nodes to edges
    }
}

// ── Critical path helpers ─────────────────────────────────────────────────────

private fun criticalPathDemandToSupplyInv(
    nodes: Map<String, Map<String, Any?>>,
    edges: List<Map<String, Any?>>,
    supplyQty: Map<String, Double>,
    demandId: String,
    caseId: Int,
): Map<String, Any?> {
    return transaction {
        val d = Demands.selectAll().where {
            (Demands.caseId eq caseId) and (Demands.demandId eq demandId)
        }.firstOrNull() ?: return@transaction mapOf("path" to emptyList<String>(), "cost" to 0, "demand_id" to demandId)

        val demandNid = "demand|${d[Demands.demandId]}"
        val startNodes = listOf(demandNid).filter { it in nodes }
        if (startNodes.isEmpty()) return@transaction mapOf("path" to emptyList<String>(), "cost" to 0, "demand_id" to demandId)

        val inEdges = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        for (e in edges) inEdges.getOrPut(e["to"] as String) { mutableListOf() }.add((e["from"] as String) to ((e["qty"] as? Double) ?: 0.0))

        var bestPath = emptyList<String>()
        var bestCost = Double.MAX_VALUE

        fun pathCost(path: List<String>) = path.sumOf { supplyQty[it] ?: 0.0 }
        fun dfs(node: String, path: MutableList<String>, visited: MutableSet<String>) {
            if (!visited.add(node)) return
            path.add(node)
            val preds = inEdges[node] ?: emptyList()
            if (preds.isEmpty()) {
                val cost = pathCost(path)
                if (cost < bestCost) { bestCost = cost; bestPath = path.toList() }
            } else for ((pred, _) in preds) dfs(pred, path, visited)
            path.removeLast(); visited.remove(node)
        }
        for (start in startNodes) dfs(start, mutableListOf(), mutableSetOf())
        mapOf("path" to bestPath, "cost" to if (bestCost == Double.MAX_VALUE) 0.0 else bestCost, "demand_id" to demandId)
    }
}

private fun criticalPathSupplyToDemandInv(
    nodes: Map<String, Map<String, Any?>>,
    edges: List<Map<String, Any?>>,
    supplyQty: Map<String, Double>,
    rootNid: String,
    caseId: Int,
): Map<String, Any?> {
    return transaction {
        val demands = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
        val outEdges = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        for (e in edges) outEdges.getOrPut(e["from"] as String) { mutableListOf() }.add((e["to"] as String) to ((e["qty"] as? Double) ?: 0.0))

        val results = demands.map { d ->
            val demandNid = "demand|${d[Demands.demandId]}"
            val endNodes = listOf(demandNid).filter { it in nodes }
            var bestPath = emptyList<String>()
            var bestCost = Double.MAX_VALUE

            fun pathCost(path: List<String>) = path.sumOf { supplyQty[it] ?: 0.0 }
            fun dfs(node: String, path: MutableList<String>, visited: MutableSet<String>) {
                if (!visited.add(node)) return
                path.add(node)
                if (node in endNodes) {
                    val cost = pathCost(path); if (cost < bestCost) { bestCost = cost; bestPath = path.toList() }
                }
                for ((succ, _) in outEdges[node] ?: emptyList()) dfs(succ, path, visited)
                path.removeLast(); visited.remove(node)
            }
            dfs(rootNid, mutableListOf(), mutableSetOf())
            mapOf("demand_id" to d[Demands.demandId], "path" to bestPath, "cost" to if (bestCost == Double.MAX_VALUE) 0.0 else bestCost)
        }
        mapOf("component_key" to rootNid, "paths_by_demand" to results)
    }
}

private fun resolveSupplyToInventoryNode(
    caseId: Int, supplyId: String, nodes: Map<String, *>, sortedDates: List<String>
): String? = transaction {
    if (supplyId.count { it == '|' } >= 2) return@transaction supplyId
    var s = Supplies.selectAll().where {
        (Supplies.caseId eq caseId) and (Supplies.supplyId eq supplyId)
    }.firstOrNull()
    if (s == null && "|" in supplyId) {
        val parts = supplyId.split("|", limit = 2)
        s = Supplies.selectAll().where {
            (Supplies.caseId eq caseId) and (Supplies.productId eq parts[0]) and
                (Supplies.locationId eq (parts.getOrElse(1) { "" }))
        }.firstOrNull()
    }
    if (s == null) return@transaction null
    val supplies2 = Supplies.selectAll().where { Supplies.caseId eq caseId }.toList()
    val demands2 = Demands.selectAll().where { Demands.caseId eq caseId }.toList()
    val (dateToPeriod, _) = TimeUtils.buildPeriodIndex(
        supplies2.map { mapOf("supply_date" to it[Supplies.supplyDate]) },
        demands2.map { mapOf("request_due_time" to it[Demands.requestDueTime]) }
    )
    val period = TimeUtils.supplyPeriod(s[Supplies.supplyDate], dateToPeriod)
    invNodeId(s[Supplies.productId], s[Supplies.locationId] ?: "", period)
}

private fun invNodeFromId(nid: String, sortedDates: List<String>, qty: Double): MutableMap<String, Any?> {
    val parts = nid.split("|")
    val productId = parts.getOrElse(0) { "" }
    val locationId = parts.getOrElse(1) { "" }
    val period = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val dateStr = if (period > 0) TimeUtils.periodToDate(period, sortedDates) else "preexisting"
    return mutableMapOf(
        "id" to nid, "label" to "$productId|$locationId ($dateStr)",
        "product_id" to productId, "location_id" to locationId,
        "period" to period, "qty" to qty, "type" to "component",
    )
}

// ── JSON serialization helpers ────────────────────────────────────────────────

private fun nodeToJson(n: Map<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in n) when (v) {
        null -> put(k, JsonNull)
        is String -> put(k, v)
        is Int -> put(k, v)
        is Long -> put(k, v)
        is Double -> put(k, v)
        is Float -> put(k, v.toDouble())
        is Boolean -> put(k, v)
        else -> put(k, v.toString())
    }
}

private fun edgeToJson(e: Map<String, Any?>): JsonObject = buildJsonObject {
    put("from", e["from"] as? String ?: "")
    put("to", e["to"] as? String ?: "")
    put("qty", e["qty"] as? Double ?: 0.0)
    val pf = e["period_from"]; if (pf != null) put("period_from", pf.toString().toIntOrNull() ?: 0) else put("period_from", JsonNull)
    val pt = e["period_to"]; if (pt != null) put("period_to", pt.toString().toIntOrNull() ?: 0) else put("period_to", JsonNull)
}

private fun critPathToJson(cp: Map<String, Any?>): JsonObject = buildJsonObject {
    put("demand_id", cp["demand_id"]?.toString() ?: "")
    put("cost", (cp["cost"] as? Double) ?: 0.0)
    put("path", buildJsonArray { @Suppress("UNCHECKED_CAST") (cp["path"] as? List<String> ?: emptyList()).forEach { add(it) } })
}

// ── Route ─────────────────────────────────────────────────────────────────────

fun Routing.peggingRoutes() {

    get("/cases/{case_id}/runs/{run_id}/pegging") {
        val caseId = call.parameters["case_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull() ?: throw IllegalArgumentException("Invalid run_id")
        val direction = call.request.queryParameters["direction"]
            ?: throw IllegalArgumentException("direction required")
        val demandId = call.request.queryParameters["demand_id"]
        val supplyId = call.request.queryParameters["supply_id"]
        val verify = call.request.queryParameters["verify"]?.lowercase() == "true"

        transaction {
            AllocationRuns.selectAll().where {
                (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
            }.singleOrNull() ?: throw NoSuchElementException("Run not found")
        }

        val (nodes, edges, supplyQty, sortedDates) = buildInventoryGraph(caseId, runId)

        when (direction) {
            "demand-to-supply" -> {
                val did = demandId ?: throw IllegalArgumentException("demand_id required")
                val critical = criticalPathDemandToSupplyInv(nodes, edges, supplyQty, did, caseId)

                val (allocatedQty, requestedQty) = transaction {
                    val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }.toList()
                    val feasible = feasibleDemandsFromActions(caseId, actions)
                    val fd = feasible.find { it["demand_id"].toString() == did }
                    val alloc = (fd?.get("allocated_qty") as? Double) ?: 0.0
                    var requested = (fd?.get("requested_qty") as? Double) ?: 0.0
                    if (requested == 0.0) {
                        val dr = Demands.selectAll().where { (Demands.caseId eq caseId) and (Demands.demandId eq did) }.firstOrNull()
                        requested = dr?.get(Demands.quantity) ?: 0.0
                    }
                    alloc to requested
                }

                val demandRootId = "demand|$did"
                // Rescale inv->demand edges for this demand
                val invToThisDemand = edges.filter { e ->
                    val t = e["to"] as? String ?: ""
                    t == demandRootId || (t.startsWith("demand|") && t.split("|", limit = 2).getOrElse(1) { "" }.trim() == did.trim())
                }
                val currentSum = invToThisDemand.sumOf { (it["qty"] as? Double) ?: 0.0 }
                if (currentSum > 0 && allocatedQty >= 0) {
                    val scale = allocatedQty / currentSum
                    for (e in invToThisDemand) e["qty"] = Math.round(((e["qty"] as? Double) ?: 0.0) * scale * 10000).toDouble() / 10000.0
                }

                call.respond(buildJsonObject {
                    put("direction", direction)
                    put("nodes", buildJsonArray { nodes.values.forEach { add(nodeToJson(it)) } })
                    put("edges", buildJsonArray { edges.forEach { add(edgeToJson(it)) } })
                    put("critical_path", buildJsonObject {
                        put("demand_id", critical["demand_id"]?.toString() ?: "")
                        put("cost", (critical["cost"] as? Double) ?: 0.0)
                        put("path", buildJsonArray { @Suppress("UNCHECKED_CAST") (critical["path"] as? List<String> ?: emptyList()).forEach { add(it) } })
                    })
                    put("sorted_dates", buildJsonArray { sortedDates.forEach { add(it) } })
                    put("demand_id", did)
                    put("demand_root_id", demandRootId)
                    put("demand_allocated_qty", Math.round(allocatedQty * 10000).toDouble() / 10000.0)
                    put("demand_requested_qty", Math.round(requestedQty * 10000).toDouble() / 10000.0)
                })
            }

            "supply-to-demand" -> {
                val sid = supplyId ?: throw IllegalArgumentException("supply_id required")
                var rootNid = resolveSupplyToInventoryNode(caseId, sid, nodes, sortedDates)
                    ?: throw NoSuchElementException("Supply not found or no inventory node for this supply")
                if (rootNid !in nodes) nodes[rootNid] = invNodeFromId(rootNid, sortedDates, supplyQty[rootNid] ?: 0.0)
                pruneSupplyToDemandInv(caseId, nodes, edges, rootNid)
                val critical = criticalPathSupplyToDemandInv(nodes, edges, supplyQty, rootNid, caseId)

                call.respond(buildJsonObject {
                    put("direction", direction)
                    put("nodes", buildJsonArray { nodes.values.forEach { add(nodeToJson(it)) } })
                    put("edges", buildJsonArray { edges.forEach { add(edgeToJson(it)) } })
                    put("critical_paths_by_demand", buildJsonObject {
                        put("component_key", critical["component_key"]?.toString() ?: "")
                        put("paths_by_demand", buildJsonArray {
                            @Suppress("UNCHECKED_CAST")
                            (critical["paths_by_demand"] as? List<Map<String, Any?>> ?: emptyList()).forEach { add(critPathToJson(it)) }
                        })
                    })
                    put("sorted_dates", buildJsonArray { sortedDates.forEach { add(it) } })
                    if (verify) {
                        put("_verify", buildJsonArray {
                            val outSum = mutableMapOf<String, Double>()
                            for (e in edges) {
                                val t = e["to"] as? String ?: ""
                                val f = e["from"] as? String ?: ""
                                if (t.startsWith("demand|") && !f.startsWith("demand|"))
                                    outSum[f] = (outSum[f] ?: 0.0) + ((e["qty"] as? Double) ?: 0.0)
                            }
                            for (nid in outSum.keys.sorted()) {
                                val nodeQty = (nodes[nid]?.get("qty") as? Double) ?: 0.0
                                val total = outSum[nid] ?: 0.0
                                add(buildJsonObject {
                                    put("node_id", nid)
                                    put("node_qty", Math.round(nodeQty * 10000).toDouble() / 10000.0)
                                    put("demand_edge_sum", Math.round(total * 10000).toDouble() / 10000.0)
                                    put("ok", total <= nodeQty + 1e-6)
                                })
                            }
                        })
                    }
                })
            }

            else -> throw IllegalArgumentException("direction must be demand-to-supply or supply-to-demand")
        }
    }
}

// Local 4-tuple for this file only (named differently to avoid package-level clash)
private data class InvGraphTuple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private operator fun <A, B, C, D> InvGraphTuple<A, B, C, D>.component1() = a
private operator fun <A, B, C, D> InvGraphTuple<A, B, C, D>.component2() = b
private operator fun <A, B, C, D> InvGraphTuple<A, B, C, D>.component3() = c
private operator fun <A, B, C, D> InvGraphTuple<A, B, C, D>.component4() = d
