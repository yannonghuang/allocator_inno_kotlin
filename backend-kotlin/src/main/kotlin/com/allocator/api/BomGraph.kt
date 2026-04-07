package com.allocator.api

import com.allocator.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

fun Routing.bomGraphRoutes() {
    get("/cases/{case_id}/bom-graph") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case $caseId not found")
        }
        val graph = buildBomGraph(caseId)
        call.respond(graph)
    }
}

private fun nodeId(productId: String, locationId: String) = "$productId|$locationId"

private fun buildBomGraph(caseId: Int): BomGraphResponse = transaction {
    // Load all relevant rows upfront
    val demandRows = Demands.selectAll().where { Demands.caseId eq caseId }
        .map { Pair(it[Demands.productId], it[Demands.locationId] ?: "") }
        .filter { it.second.isNotEmpty() }
        .toSet()

    val buySet = MethodBuys.selectAll().where { MethodBuys.caseId eq caseId }
        .map { Pair(it[MethodBuys.productId], it[MethodBuys.locationId]) }
        .toSet()

    data class MoveRow(
        val productId: String,
        val fromLocationId: String,
        val toLocationId: String,
        val transitTime: Double?,
        val preference: Int?,
    )
    val moveRows = MethodMoves.selectAll().where { MethodMoves.caseId eq caseId }
        .map { MoveRow(
            it[MethodMoves.productId],
            it[MethodMoves.fromLocationId],
            it[MethodMoves.toLocationId],
            it[MethodMoves.transitTime],
            it[MethodMoves.preference],
        )}

    data class MakeRow(
        val bomId: String,
        val productId: String,
        val locationId: String,
        val preference: Int?,
        val leadTime: Int?,
    )
    val makeRows = MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }
        .map { MakeRow(
            it[MethodMakes.bomId],
            it[MethodMakes.productId],
            it[MethodMakes.locationId],
            it[MethodMakes.preference],
            it[MethodMakes.leadTime],
        )}

    // BOM pre-index: (bomId, parentId) -> altKey -> [(childId, rate)]
    // null altGroup children each get a unique key so they form independent groups of size 1
    val bomByMakeKey = mutableMapOf<Pair<String, String>, MutableMap<String, MutableList<Pair<String, Double>>>>()
    Boms.selectAll().where { Boms.caseId eq caseId }.forEach { row ->
        val key = Pair(row[Boms.bomId], row[Boms.parentId])
        val childId = row[Boms.childId]
        val altKey = row[Boms.altGroup] ?: "__null__$childId"
        val rate = row[Boms.rate] ?: 1.0
        bomByMakeKey.getOrPut(key) { mutableMapOf() }
            .getOrPut(altKey) { mutableListOf() }
            .add(Pair(childId, rate))
    }

    // Move index: (productId, toLocationId) -> list of MoveRow
    val moveByTarget = mutableMapOf<Pair<String, String>, MutableList<MoveRow>>()
    for (mv in moveRows) {
        moveByTarget.getOrPut(Pair(mv.productId, mv.toLocationId)) { mutableListOf() }.add(mv)
    }

    // Make index: (productId, locationId) -> list of MakeRow
    val makeByTarget = mutableMapOf<Pair<String, String>, MutableList<MakeRow>>()
    for (mk in makeRows) {
        makeByTarget.getOrPut(Pair(mk.productId, mk.locationId)) { mutableListOf() }.add(mk)
    }

    // Product/location descriptions
    val productDesc = Products.selectAll().where { Products.caseId eq caseId }
        .associate { it[Products.productId] to it[Products.description] }
    val locationDesc = Locations.selectAll().where { Locations.caseId eq caseId }
        .associate { it[Locations.locationId] to it[Locations.locationDescription] }

    // DFS from demand nodes
    val visited = mutableSetOf<Pair<String, String>>()
    val nodeEstablishedBy = mutableMapOf<Pair<String, String>, MutableSet<String>>()
    val edges = mutableListOf<BomGraphEdge>()
    val edgeIds = mutableSetOf<String>()

    val queue: ArrayDeque<Pair<String, String>> = ArrayDeque(demandRows)
    // Ensure demand nodes are in establishedBy map even if no methods exist
    for (d in demandRows) {
        nodeEstablishedBy.getOrPut(d) { mutableSetOf() }
    }

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (!visited.add(node)) continue
        val (productId, locationId) = node

        // Check buy (terminal — no further expansion)
        if (buySet.contains(node)) {
            nodeEstablishedBy.getOrPut(node) { mutableSetOf() }.add("buy")
        }

        // Check move: find moves where this node is the target (toLocation)
        moveByTarget[node]?.forEach { mv ->
            val src = Pair(mv.productId, mv.fromLocationId)
            nodeEstablishedBy.getOrPut(node) { mutableSetOf() }.add("move")
            val edgeId = "move|${nodeId(src.first, src.second)}|${nodeId(productId, locationId)}"
            if (edgeIds.add(edgeId)) {
                edges.add(BomGraphEdge(
                    id = edgeId,
                    source = nodeId(src.first, src.second),
                    target = nodeId(productId, locationId),
                    edgeType = "move",
                    bomId = null,
                    altGroup = null,
                    rate = null,
                    preference = mv.preference,
                    leadDays = mv.transitTime,
                ))
            }
            if (src !in visited) queue.add(src)
            nodeEstablishedBy.getOrPut(src) { mutableSetOf() }
        }

        // Check make: find all make methods for this (product, location)
        makeByTarget[node]?.forEach { mk ->
            val bomGroups = bomByMakeKey[Pair(mk.bomId, mk.productId)] ?: return@forEach
            nodeEstablishedBy.getOrPut(node) { mutableSetOf() }.add("make")
            // Exhaust ALL alt_groups
            bomGroups.forEach { (altKey, children) ->
                val altGroupDisplay = if (altKey.startsWith("__null__")) null else altKey
                children.forEach { (childId, rate) ->
                    val child = Pair(childId, locationId)
                    val edgeId = "make|${nodeId(childId, locationId)}|${nodeId(productId, locationId)}|${mk.bomId}|$altKey"
                    if (edgeIds.add(edgeId)) {
                        edges.add(BomGraphEdge(
                            id = edgeId,
                            source = nodeId(childId, locationId),
                            target = nodeId(productId, locationId),
                            edgeType = "make",
                            bomId = mk.bomId,
                            altGroup = altGroupDisplay,
                            rate = rate,
                            preference = mk.preference,
                            leadDays = mk.leadTime?.toDouble(),
                        ))
                    }
                    if (child !in visited) queue.add(child)
                    nodeEstablishedBy.getOrPut(child) { mutableSetOf() }
                }
            }
        }
    }

    val nodes = nodeEstablishedBy.map { (pair, methods) ->
        val (productId, locationId) = pair
        BomGraphNode(
            id = nodeId(productId, locationId),
            productId = productId,
            locationId = locationId,
            productDescription = productDesc[productId],
            locationDescription = locationDesc[locationId],
            establishedBy = methods.toList(),
            isDemand = pair in demandRows,
        )
    }

    BomGraphResponse(
        nodes = nodes,
        edges = edges,
        nodeCount = nodes.size,
        edgeCount = edges.size,
    )
}
