package com.allocator.services


/**
 * Phase 1 of supply-guided planning — build the bipartite (demand × supply)
 * needs matrix via BOM reachability.
 *
 * ## Design
 *
 * Budgeting is separate from planning. The request matrix answers only
 * "which demands compete for which supply?" Rate math (OR-splits, nVariants,
 * BOM quantities) belongs to the planning phase and must not appear here.
 *
 * A demand D competes for supply S if S is reachable from D's BOM root via
 * any path in the BOM DAG (union-all). The matrix value is the raw demand
 * quantity, used by the allocator to split supply proportionally:
 *   allocation(D, S) = available(S) × demandQty(D) / Σ demandQty(competing).
 *
 * ## Algorithm
 *
 * The BOM DAG is precomputed once ([buildBomGraph]):
 *   BFS from all demand roots + method nodes + supply nodes; collects every
 *   reachable (pid, lid) and its union-all outgoing edges.
 *
 * Per demand, BFS from the demand root through [BomGraph.edges] ([buildReachabilityMatrix]):
 *   All reachable supply nodes → emit (demandId, supplyKey, demandQty).
 */

/** A leaf or intermediate supply identified by (productId, locationId). */
data class SupplyKey(val productId: String, val locationId: String) {
    override fun toString() = "$productId|$locationId"
}

/**
 * Sparse bipartite (demand × supply) matrix of symbolic required qty.
 *
 * Both forward (`byRow`) and inverted (`byColumn`) maps are built once and
 * exposed together so callers don't pay for re-transposition.
 */
data class NeedsMatrix(
    /** demandId → supplyKey → symbolic required qty (rate-converted). */
    val byRow: Map<Any?, Map<SupplyKey, Double>>,
    /** supplyKey → demandId → symbolic required qty. Transpose of [byRow]. */
    val byColumn: Map<SupplyKey, Map<Any?, Double>>,
) {
    fun isEmpty(): Boolean = byRow.isEmpty()

    /** Number of non-zero (demand, supply) cells. Useful for diagnostics. */
    fun cellCount(): Int = byRow.values.sumOf { it.size }
}

/**
 * Build the needs matrix via BOM reachability (union-all).
 *
 * Every demand that can reach a supply via any BOM path competes for that
 * supply. Matrix value = raw demand quantity (used for proportional allocation).
 */
fun buildNeedsMatrix(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): NeedsMatrix {
    val graph = buildBomGraph(demands, data)
    return buildReachabilityMatrix(demands, graph)
}

/**
 * Alias for [buildNeedsMatrix]. Retained for call-site compatibility; the
 * inventory-aware distinction is removed — reachability-based budgeting uses
 * union-all paths for all demands.
 */
fun buildInventoryAwareNeedsMatrix(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): NeedsMatrix = buildNeedsMatrix(demands, data)

// ── Global BOM graph ─────────────────────────────────────────────────────────

internal data class BomGraph(
    /**
     * Union-all edges: (pid, lid) → all outgoing (child, rate) from all
     * methods. Used for allocation-phase rate propagation.
     */
    val edges: Map<Pair<String, String>, List<Pair<Pair<String, String>, Double>>>,
    /**
     * Inventory-preferred edges: non-null only at nodes where at least one
     * method has supply-bearing direct children. At those nodes, only the
     * edges from those methods are stored here. At all other nodes this map
     * is absent (caller falls back to [edges]).
     *
     * Used for budget-structure rate propagation (inventory-aware variant).
     */
    val inventoryEdges: Map<Pair<String, String>, List<Pair<Pair<String, String>, Double>>>,
    /** All nodes in topological order (based on full edge set). */
    val topoOrder: List<Pair<String, String>>,
    val supplyIndex: Set<SupplyKey>,
)

/**
 * Build the global BOM DAG from [data], seeded by [demands] as roots.
 *
 * Every (pid, lid) reachable from any demand root is visited once. All
 * locations — including "VIRTUAL" — are treated uniformly: their outgoing
 * edges come from whatever methods are defined for them in the BOM tables.
 */
internal fun buildBomGraph(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): BomGraph {
    // Supply index: marks which (pid, lid) nodes are supply-bearing in the BOM topology.
    // qty filter is intentionally ABSENT — the graph must be inventory-blind so that
    // every supply node is reachable regardless of current inventory levels. Demands
    // whose only path reaches a currently-zero or negative supply (e.g. Negative_Inventory_*
    // pseudo-demands) still participate in the request matrix. Step 2 (aggregateSupplies)
    // handles the zero-available case and gives them 0 budget, which is correct.
    val supplyIndex: Set<SupplyKey> = (data["supply"] ?: emptyList())
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank()) null else SupplyKey(pid, lid)
        }
        .toSet()

    val edges         = mutableMapOf<Pair<String, String>, MutableList<Pair<Pair<String, String>, Double>>>()
    val inventoryEdges = mutableMapOf<Pair<String, String>, MutableList<Pair<Pair<String, String>, Double>>>()
    val discovered    = mutableSetOf<Pair<String, String>>()
    val toVisit       = ArrayDeque<Pair<String, String>>()

    fun enqueue(node: Pair<String, String>) {
        if (discovered.add(node)) toVisit.addLast(node)
    }

    for (demand in demands) {
        val pid = (demand["product_id"] as? String)?.trim() ?: continue
        val lid = (demand["location_id"] as? String)?.trim() ?: continue
        enqueue(pid to lid)
    }
    for (table in listOf("method_make", "method_move")) {
        for (method in data[table] ?: emptyList()) {
            val pid = (method["product_id"] as? String)?.trim() ?: continue
            val lid = (method["location_id"] as? String)?.trim() ?: continue
            enqueue(pid to lid)
        }
    }
    for (sk in supplyIndex) enqueue(sk.productId to sk.locationId)

    while (toVisit.isNotEmpty()) {
        val key = toVisit.removeFirst()
        val (pid, lid) = key
        val outAll = edges.getOrPut(key) { mutableListOf() }
        val outInv = mutableListOf<Pair<Pair<String, String>, Double>>()

        for (method in getMethods(pid, lid, data)) {
            val methodEdges = mutableListOf<Pair<Pair<String, String>, Double>>()
            when (method["type"]) {
                "make" -> {
                    val pLid = (method["location_id"] as? String)?.trim() ?: lid
                    val variantsMap = variantsForMake(pid, pLid, 1.0, method, data)
                    // OR-split: planner equal-splits demand across n variants, so each
                    // variant is responsible for 1/n of the parent qty. Scale the BOM rate
                    // accordingly so the request matrix claims demand_qty × rate/n per
                    // variant's supply — not the full demand_qty × rate that a union-all
                    // walk would produce. Reachability is unaffected: all variant paths
                    // are still included (test = demand can reach supply via ANY route).
                    val nVariants = variantsMap.size.coerceAtLeast(1).toDouble()
                    for ((_, childList) in variantsMap) {
                        for (alt in childList) {
                            val cPid  = (alt["product_id"] as? String)?.trim() ?: continue
                            val cLid  = (alt["location_id"] as? String)?.trim() ?: continue
                            val cRate = (alt["quantity"] as? Number)?.toDouble() ?: continue
                            if (cRate <= 0) continue
                            methodEdges.add((cPid to cLid) to cRate / nVariants)
                        }
                    }
                }
                "move" -> {
                    for (child in childMaterialsForMove(method, 1.0)) {
                        val cPid  = (child["product_id"] as? String)?.trim() ?: continue
                        val cLid  = (child["location_id"] as? String)?.trim() ?: continue
                        val cRate = (child["quantity"] as? Number)?.toDouble() ?: continue
                        if (cRate <= 0) continue
                        methodEdges.add((cPid to cLid) to cRate)
                    }
                }
            }
            outAll.addAll(methodEdges)
            // Inventory-preferred: include this method's edges only if any
            // direct child is supply-bearing.
            if (methodEdges.any { (ck, _) -> SupplyKey(ck.first, ck.second) in supplyIndex }) {
                outInv.addAll(methodEdges)
            }
            for ((childKey, _) in methodEdges) enqueue(childKey)
        }

        if (outInv.isNotEmpty()) inventoryEdges[key] = outInv
    }

    // Kahn's topological sort on the full edge set.
    val inDegree = mutableMapOf<Pair<String, String>, Int>()
    for ((_, edgeList) in edges) {
        for ((child, _) in edgeList) {
            inDegree[child] = (inDegree[child] ?: 0) + 1
        }
    }
    val ready = ArrayDeque<Pair<String, String>>()
    for (node in discovered) {
        if ((inDegree[node] ?: 0) == 0) ready.addLast(node)
    }
    val topoOrder = mutableListOf<Pair<String, String>>()
    val processed = mutableSetOf<Pair<String, String>>()
    while (ready.isNotEmpty()) {
        val node = ready.removeFirst()
        if (!processed.add(node)) continue
        topoOrder.add(node)
        for ((child, _) in edges[node] ?: emptyList()) {
            val newIn = (inDegree[child] ?: 0) - 1
            inDegree[child] = newIn
            if (newIn == 0 && child !in processed) ready.addLast(child)
        }
    }
    for (node in discovered) {
        if (processed.add(node)) topoOrder.add(node)
    }

    return BomGraph(edges, inventoryEdges, topoOrder, supplyIndex)
}

// ── Reachability-based request matrix ────────────────────────────────────────

/**
 * Build the needs matrix via BFS reachability through the precomputed [graph].
 *
 * For each demand, BFS from the demand root through union-all edges. Every
 * reachable supply node → this demand competes for that supply. The matrix
 * value is the raw demand quantity (not BOM-rate-adjusted), so the downstream
 * allocator splits supply proportionally to demand size.
 */
internal fun buildReachabilityMatrix(
    demands: List<Map<String, Any?>>,
    graph: BomGraph,
    /** When non-null, only supply nodes whose product_id is in this set are recorded.
     *  Non-critical supply leaves are skipped, avoiding allocation work for purchasable
     *  materials whose lots are uncapped in planning anyway. Product-scoped (matches
     *  computeCriticalPids's own all-locations-must-qualify semantics) — see
     *  [criticalStockKeys] for the location-aware counterpart. */
    criticalPids: Set<String>? = null,
    /** When non-null, ALSO record supply nodes whose exact (productId, locationId) is in this
     *  set — deliberately location-aware, unlike [criticalPids]: a product that's critical stock
     *  at one location but not another must not pull the OTHER location's ordinary lots into the
     *  TARGET-aware allocator too. See `computeCriticalStockPositions`'s own doc. */
    criticalStockKeys: Set<SupplyKey>? = null,
): NeedsMatrix {
    val byRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()

    for (demand in demands) {
        val productId  = (demand["product_id"] as? String)?.trim() ?: continue
        val locationId = (demand["location_id"] as? String)?.trim() ?: continue
        val qty        = (demand["quantity"] as? Number)?.toDouble() ?: continue
        if (qty <= 0) continue
        val demandId = demand["demand_id"]

        val visited = mutableSetOf<Pair<String, String>>()
        val queue   = ArrayDeque<Pair<String, String>>()
        val root    = productId to locationId
        visited.add(root)
        queue.addLast(root)

        val needs = mutableMapOf<SupplyKey, Double>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val sk   = SupplyKey(node.first, node.second)
            if (sk in graph.supplyIndex &&
                ((criticalPids == null || sk.productId in criticalPids) ||
                    (criticalStockKeys != null && sk in criticalStockKeys)))
                needs[sk] = qty
            for ((child, _) in graph.edges[node] ?: emptyList()) {
                if (visited.add(child)) queue.addLast(child)
            }
        }

        if (needs.isNotEmpty()) byRow[demandId] = needs
    }

    val byColumn = mutableMapOf<SupplyKey, MutableMap<Any?, Double>>()
    for ((demandId, demandNeeds) in byRow) {
        for ((supplyKey, q) in demandNeeds) {
            byColumn.getOrPut(supplyKey) { mutableMapOf() }[demandId] = q
        }
    }
    return NeedsMatrix(byRow, byColumn)
}
