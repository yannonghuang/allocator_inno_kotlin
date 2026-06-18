package com.allocator.services

/**
 * Phase 1 of supply-level consolidation — build the bipartite (demand × supply)
 * needs matrix.
 *
 * ## Two variants
 *
 * **Union-all** ([buildNeedsMatrix]): follows every method at every BOM node.
 * Every demand that can reach a supply via any path gets a request row for
 * that supply. Used for allocation — every competing demand participates so
 * the allocation amounts are fair.
 *
 * **Inventory-aware** ([buildInventoryAwareNeedsMatrix]): at each BOM node,
 * if any method has supply-bearing direct children, only those methods are
 * followed (others are pruned). Falls back to union-all when no method has
 * supply-bearing direct children. Used for budget structure — each demand's
 * budget only covers the supplies plan() will naturally reach on its
 * inventory-preferred path, avoiding budget caps at wrong BOM levels.
 *
 * ## Algorithm
 *
 * The BOM DAG is precomputed once ([buildBomGraph]):
 *   1. BFS from all demand roots + method nodes + supply nodes: collects every
 *      reachable (pid, lid) and its outgoing edges. For each BOM node, both
 *      the full edge set (union-all) and the inventory-only edge set
 *      (methods with supply-bearing direct children) are stored.
 *   2. Kahn's topological sort on the full edge set. The resulting order is
 *      also valid for the inventory-only subgraph (a subset of edges can only
 *      reduce in-degrees, never create ordering violations).
 *
 * Per demand, only rate propagation is needed ([propagateRates]):
 *   - Seed rate[demandRoot] = demandQty.
 *   - Iterate the precomputed topo order; for each node with non-null rate,
 *     emit a supply need if supply-bearing, then propagate rate × edgeRate.
 *   - Union-all uses `graph.edges`; inventory-aware uses `graph.inventoryEdges`
 *     at nodes where it is non-empty, otherwise falls back to `graph.edges`.
 *
 * This reduces cost from O(N × per-demand BFS + getMethods calls) to
 * O(V+E) graph setup + O(N × V_global) rate propagation with map lookups.
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
 * Build the needs matrix using the **union-all** BOM walk.
 *
 * Every demand that can reach a supply via any BOM path gets a request row
 * for that supply. Use this for allocation — all competing demands participate
 * so allocation amounts are fair.
 */
fun buildNeedsMatrix(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): NeedsMatrix {
    val graph = buildBomGraph(demands, data)
    return propagateRates(demands, graph, inventoryAware = false)
}

/**
 * Build the needs matrix using the **inventory-aware** BOM walk.
 *
 * At each BOM node, if any method has supply-bearing direct children, only
 * those methods are followed. Falls back to union-all when no such method
 * exists. Use this for budget structure — each demand's budget covers only
 * the supplies plan() will naturally reach on its inventory-preferred path.
 */
fun buildInventoryAwareNeedsMatrix(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): NeedsMatrix {
    val graph = buildBomGraph(demands, data)
    return propagateRates(demands, graph, inventoryAware = true)
}

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

// ── Rate propagation ─────────────────────────────────────────────────────────

/**
 * Propagate demand quantities through the precomputed [graph] and collect
 * supply requests per demand.
 *
 * @param inventoryAware when true, uses [BomGraph.inventoryEdges] at nodes
 *   where it is defined; falls back to [BomGraph.edges] elsewhere. When
 *   false, always uses [BomGraph.edges] (union-all).
 */
internal fun propagateRates(
    demands: List<Map<String, Any?>>,
    graph: BomGraph,
    inventoryAware: Boolean,
): NeedsMatrix {
    val byRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()

    for (demand in demands) {
        val productId  = (demand["product_id"] as? String)?.trim() ?: continue
        val locationId = (demand["location_id"] as? String)?.trim() ?: continue
        val qty        = (demand["quantity"] as? Number)?.toDouble() ?: continue
        if (qty <= 0) continue
        val demandId = demand["demand_id"]

        val rate  = mutableMapOf<Pair<String, String>, Double>()
        rate[productId to locationId] = qty

        val needs = mutableMapOf<SupplyKey, Double>()
        for (node in graph.topoOrder) {
            val accumQty = rate[node] ?: continue
            if (accumQty <= 1e-12) continue
            val sk = SupplyKey(node.first, node.second)
            if (sk in graph.supplyIndex) {
                needs[sk] = (needs[sk] ?: 0.0) + accumQty
            }
            val outEdges = if (inventoryAware)
                graph.inventoryEdges[node] ?: graph.edges[node] ?: emptyList()
            else
                graph.edges[node] ?: emptyList()
            for ((child, edgeRate) in outEdges) {
                rate[child] = (rate[child] ?: 0.0) + accumQty * edgeRate
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
