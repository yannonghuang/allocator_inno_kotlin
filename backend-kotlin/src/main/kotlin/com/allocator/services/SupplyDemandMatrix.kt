package com.allocator.services

/**
 * Phase 1 of supply-level consolidation — build the bipartite (demand × supply)
 * needs matrix.
 *
 * For each demand, walks the BOM symbolically (no inventory awareness) with
 * union-alt at every alt_group, and records the rate-converted symbolic
 * required qty at every supply-bearing (pid, lid) along the way.
 *
 * ## How it differs from [buildResolutionGraph]
 *
 * The leaf-engine's resolution walker stops at the first inventory-bearing
 * node it reaches and emits one path per demand-leaf pair. That suits the
 * leaf-engine's mergeGroups + per-group consolidation pipeline.
 *
 * For supply-level consolidation we need every supply-bearing node along
 * every demand's path to be a column of the matrix — not just the first one.
 * So this walker:
 *   - records a need at every supply-bearing node it visits
 *   - **continues past** supply-bearing nodes (rather than stopping)
 *   - terminates at non-make/non-move nodes (purchase, no-method) and on cycles
 *
 * The walk is purely symbolic. Inventory levels are not consulted. Whether a
 * leaf has 0 or 1 million units, every demand transitively needing it gets
 * the same rate-converted symbolic entry. Consolidation/scarcity is for
 * Phase 2 (allocation), not for Phase 1.
 *
 * ## Output shape
 *
 * Sparse, with both forward and inverted indexes for O(1) lookup in either
 * direction. Phase 2 iterates columns (per-supply allocation); Phase 3
 * iterates rows (per-demand budget caps).
 *
 * See [docs/supply-level-consolidation.md] for the full design.
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
 * Build the needs matrix for [demands] over the BOM defined in [data].
 *
 * Pure function. No inventory consumption, no work-order emission, no allocation.
 *
 * Entries are summed across alt branches when a demand has multiple symbolic
 * paths to the same supply (union-alt). A supply that is on no demand's path
 * is absent from `byColumn`.
 */
fun buildNeedsMatrix(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): NeedsMatrix {
    val supplyIndex: Set<SupplyKey> = (data["supply"] ?: emptyList())
        .filter { ((it["qty"] as? Number)?.toDouble() ?: 0.0) > 0 }
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank()) null else SupplyKey(pid, lid)
        }
        .toSet()

    val byRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()

    for (demand in demands) {
        val productId = (demand["product_id"] as? String)?.trim() ?: continue
        val locationId = (demand["location_id"] as? String)?.trim() ?: continue
        val qty = (demand["quantity"] as? Number)?.toDouble() ?: continue
        if (qty <= 0) continue
        val demandId = demand["demand_id"]

        val needs = mutableMapOf<SupplyKey, Double>()
        walkBomSymbolic(
            productId = productId,
            locationId = locationId,
            cumulativeRate = 1.0,
            demandQty = qty,
            data = data,
            supplyIndex = supplyIndex,
            visited = emptySet(),
            out = needs,
        )
        if (needs.isNotEmpty()) {
            byRow[demandId] = needs
        }
    }

    // Build inverted index in one pass.
    val byColumn = mutableMapOf<SupplyKey, MutableMap<Any?, Double>>()
    for ((demandId, demandNeeds) in byRow) {
        for ((supplyKey, q) in demandNeeds) {
            byColumn.getOrPut(supplyKey) { mutableMapOf() }[demandId] = q
        }
    }

    return NeedsMatrix(byRow, byColumn)
}

/**
 * Symbolic BOM walker via DAG rate propagation. For each demand, computes
 * the symbolic required qty at every supply-bearing node reachable through
 * **any combination of methods and alts** (union-method × union-alt), in
 * O(V + E) time regardless of DAG path count.
 *
 * ## Why rate propagation, not recursion
 *
 * The naive recursive walker explores each path from root to leaf
 * independently. In real BOMs that are DAGs (a common raw material reachable
 * from many parents), the path count is exponential in graph size, and a
 * union-method / union-alt walk hangs Phase 1 (case 169 stuck > 10 minutes).
 *
 * Rate propagation collapses this: each (pid, lid) is visited once. Its
 * accumulated rate equals the sum of `demandQty × path-rate-product` over
 * every path from root to this node — exactly what we want for the matrix
 * entry, computed in linear time.
 *
 * ## Algorithm
 *
 *  1. **Edge discovery** (BFS): walk the reachable subgraph from the demand
 *     root. At each node, enumerate every method, every alt within make,
 *     and every move source. Record outgoing edges (cPid, cLid, edge_rate).
 *     Cycles are broken by the visited set; cycle-nodes are dropped.
 *
 *  2. **Topological sort** (Kahn's): order nodes so each node is processed
 *     after all its in-edges. Cycle members have non-zero in-degree forever
 *     and silently fall out — same coverage gap the leaf engine has.
 *
 *  3. **Rate propagation**: initialize rate[root] = demandQty. For each
 *     node in topo order, propagate `rate[node] × edge_rate` to each child.
 *     Where parent has multiple edges to the same child (e.g. two alts of
 *     the same make leading to the same component), they sum naturally —
 *     matching the recursive walker's union-alt semantics.
 *
 *  4. **Supply emission**: for any node whose key is in `supplyIndex`,
 *     emit `out[SupplyKey] += rate[node]`.
 *
 * ## Union-method semantics
 *
 * At each node we collect children from every method (purchase contributes
 * none; make contributes its alt children; move contributes its source).
 * plan() picks one method per node at runtime, but the matrix records the
 * union: any supply reachable through any method gets a cell. If the
 * resulting matrix over-allocates Phase 2 (almost always — typical iter-1
 * dropped is 95%+), compensation revokes the unused. Architectural promise:
 * by Phase 3c every demand-supply pair plan() touches is bounded by an
 * explicit cap.
 */
private fun walkBomSymbolic(
    productId: String,
    locationId: String,
    @Suppress("UNUSED_PARAMETER") cumulativeRate: Double,
    demandQty: Double,
    data: Map<String, List<Map<String, Any?>>>,
    supplyIndex: Set<SupplyKey>,
    @Suppress("UNUSED_PARAMETER") visited: Set<Pair<String, String>>,
    out: MutableMap<SupplyKey, Double>,
) {
    val rootKey = productId to locationId

    // ── Step 1: edge discovery via BFS. Each (pid, lid) is visited once;
    //            its outgoing edges are collected from every method.
    val edges = mutableMapOf<Pair<String, String>, MutableList<Pair<Pair<String, String>, Double>>>()
    val discovered = mutableSetOf<Pair<String, String>>()
    val toVisit = ArrayDeque<Pair<String, String>>()
    toVisit.addLast(rootKey)
    discovered.add(rootKey)

    while (toVisit.isNotEmpty()) {
        val key = toVisit.removeFirst()
        val (pid, lid) = key

        val methods = getMethods(pid, lid, data)
        if (methods.isEmpty()) continue

        val outEdges = edges.getOrPut(key) { mutableListOf() }
        for (method in methods) {
            when (method["type"]) {
                "purchase" -> {
                    // Purchase materializes here; no children.
                }
                "make" -> {
                    val productionLocation = (method["location_id"] as? String)?.trim() ?: lid
                    val variants = variantsForMake(pid, productionLocation, 1.0, method, data)
                    for ((_, childList) in variants) {
                        for (alt in childList) {
                            val cPid = (alt["product_id"] as? String)?.trim() ?: continue
                            val cLid = (alt["location_id"] as? String)?.trim() ?: continue
                            val cRate = (alt["quantity"] as? Number)?.toDouble() ?: continue
                            if (cRate <= 0) continue
                            val childKey = cPid to cLid
                            outEdges.add(childKey to cRate)
                            if (discovered.add(childKey)) toVisit.addLast(childKey)
                        }
                    }
                }
                "move" -> {
                    for (child in childMaterialsForMove(method, 1.0)) {
                        val cPid = (child["product_id"] as? String)?.trim() ?: continue
                        val cLid = (child["location_id"] as? String)?.trim() ?: continue
                        val cRate = (child["quantity"] as? Number)?.toDouble() ?: continue
                        if (cRate <= 0) continue
                        val childKey = cPid to cLid
                        outEdges.add(childKey to cRate)
                        if (discovered.add(childKey)) toVisit.addLast(childKey)
                    }
                }
            }
        }
    }

    // ── Step 2: topological sort via Kahn's algorithm. Cycle members never
    //            reach in-degree 0 and are dropped — same as the leaf
    //            engine's cycle-handling.
    val inDegree = mutableMapOf<Pair<String, String>, Int>()
    for ((_, edgeList) in edges) {
        for ((child, _) in edgeList) {
            inDegree[child] = (inDegree[child] ?: 0) + 1
        }
    }
    // Root is always a source. If a cycle (e.g. A → B → A) gives the root
    // an in-edge, Kahn's natural calculation would never put it in the ready
    // queue and the entire walk would emit nothing. Force the root in.
    val ready = ArrayDeque<Pair<String, String>>()
    ready.addLast(rootKey)
    for (node in discovered) {
        if (node != rootKey && (inDegree[node] ?: 0) == 0) ready.addLast(node)
    }
    val topoOrder = mutableListOf<Pair<String, String>>()
    val processed = mutableSetOf<Pair<String, String>>()
    while (ready.isNotEmpty()) {
        val node = ready.removeFirst()
        if (!processed.add(node)) continue  // already processed (forced root + cycle revived it)
        topoOrder.add(node)
        for ((child, _) in edges[node] ?: emptyList()) {
            val newIn = (inDegree[child] ?: 0) - 1
            inDegree[child] = newIn
            if (newIn == 0 && child !in processed) ready.addLast(child)
        }
    }

    // ── Step 3: rate propagation in topo order. ── Step 4: emit supply needs.
    val rate = mutableMapOf<Pair<String, String>, Double>()
    rate[rootKey] = demandQty
    for (node in topoOrder) {
        val accumQty = rate[node] ?: continue
        if (accumQty <= 1e-12) continue
        val supplyKey = SupplyKey(node.first, node.second)
        if (supplyKey in supplyIndex) {
            out.merge(supplyKey, accumQty, Double::plus)
        }
        for ((child, edgeRate) in edges[node] ?: emptyList()) {
            rate[child] = (rate[child] ?: 0.0) + accumQty * edgeRate
        }
    }
}
