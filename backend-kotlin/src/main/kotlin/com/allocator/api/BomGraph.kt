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

internal data class MoveRow(
    val productId: String,
    val fromLocationId: String,
    val toLocationId: String,
    val transitTime: Double?,
    val preference: Int?,
)

internal data class MakeRow(
    val bomId: String,
    val productId: String,
    val locationId: String,
    val preference: Int?,
    val leadTime: Int?,
)

internal fun nodeId(productId: String, locationId: String) = "$productId|$locationId"

private fun buildBomGraph(caseId: Int): BomGraphResponse = transaction {
    val demandRows = Demands.selectAll().where { Demands.caseId eq caseId }
        .map { Pair(it[Demands.productId], it[Demands.locationId] ?: "") }
        .filter { it.second.isNotEmpty() }
        .toSet()

    val buySet = MethodBuys.selectAll().where { MethodBuys.caseId eq caseId }
        .map { Pair(it[MethodBuys.productId], it[MethodBuys.locationId]) }
        .toSet()

    val moveRows = MethodMoves.selectAll().where { MethodMoves.caseId eq caseId }
        .map { MoveRow(
            it[MethodMoves.productId],
            it[MethodMoves.fromLocationId],
            it[MethodMoves.toLocationId],
            it[MethodMoves.transitTime],
            it[MethodMoves.preference],
        )}

    val makeRows = MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }
        .map { MakeRow(
            it[MethodMakes.bomId],
            it[MethodMakes.productId],
            it[MethodMakes.locationId],
            it[MethodMakes.preference],
            it[MethodMakes.leadTime],
        )}

    // BOM pre-index: (bomId, parentId) -> altKey -> [(childId, rate)]
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

    val productDesc = Products.selectAll().where { Products.caseId eq caseId }
        .associate { it[Products.productId] to it[Products.description] }
    val locationDesc = Locations.selectAll().where { Locations.caseId eq caseId }
        .associate { it[Locations.locationId] to it[Locations.locationDescription] }

    buildBomGraphPure(demandRows, buySet, moveRows, makeRows, bomByMakeKey, productDesc, locationDesc)
}

/**
 * Pure DFS BOM graph builder — no DB access, fully unit-testable.
 *
 * @param demandRows        seed nodes: set of (productId, locationId)
 * @param buySet            terminal buy nodes: set of (productId, locationId)
 * @param moveRows          all move method rows for this case
 * @param makeRows          all make method rows for this case
 * @param bomByMakeKey      (bomId, parentProductId) → altKey → [(childProductId, rate)]
 * @param productDesc       productId → description (optional)
 * @param locationDesc      locationId → description (optional)
 */
internal fun buildBomGraphPure(
    demandRows:    Set<Pair<String, String>>,
    buySet:        Set<Pair<String, String>>,
    moveRows:      List<MoveRow>,
    makeRows:      List<MakeRow>,
    bomByMakeKey:  Map<Pair<String, String>, Map<String, List<Pair<String, Double>>>>,
    productDesc:   Map<String, String?> = emptyMap(),
    locationDesc:  Map<String, String?> = emptyMap(),
): BomGraphResponse {
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

    val visited = mutableSetOf<Pair<String, String>>()
    val nodeEstablishedBy = mutableMapOf<Pair<String, String>, MutableSet<String>>()
    val edges = mutableListOf<BomGraphEdge>()
    val edgeIds = mutableSetOf<String>()

    val queue: ArrayDeque<Pair<String, String>> = ArrayDeque(demandRows)
    for (d in demandRows) {
        nodeEstablishedBy.getOrPut(d) { mutableSetOf() }
    }

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (!visited.add(node)) continue
        val (productId, locationId) = node

        if (buySet.contains(node)) {
            nodeEstablishedBy.getOrPut(node) { mutableSetOf() }.add("buy")
        }

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

        makeByTarget[node]?.forEach { mk ->
            val bomGroups = bomByMakeKey[Pair(mk.bomId, mk.productId)] ?: return@forEach
            nodeEstablishedBy.getOrPut(node) { mutableSetOf() }.add("make")
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
        val (pid, lid) = pair
        BomGraphNode(
            id = nodeId(pid, lid),
            productId = pid,
            locationId = lid,
            productDescription = productDesc[pid],
            locationDescription = locationDesc[lid],
            establishedBy = methods.toList(),
            isDemand = pair in demandRows,
        )
    }

    return BomGraphResponse(
        nodes = nodes,
        edges = edges,
        nodeCount = nodes.size,
        edgeCount = edges.size,
    )
}
