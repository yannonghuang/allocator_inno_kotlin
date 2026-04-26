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
 * Recursive symbolic BOM walker. Records needs at every supply-bearing node
 * encountered, and continues past inventory-bearing nodes (unlike the
 * leaf-engine's [walkResolution] which stops at the first one).
 *
 * Termination:
 *   - cycle (already in `visited`): stop
 *   - no methods at this node: stop
 *   - method type `purchase`: terminal (purchase materializes here)
 *   - method type `make`: recurse into every alt_group child (union-alt)
 *   - method type `move`: recurse into the source location
 *
 * `visited` is the chain of (pid, lid) pairs from this demand's root down
 * to the current node — used for cycle detection only. It does **not**
 * accumulate across demands; each demand starts with `emptySet()`.
 */
private fun walkBomSymbolic(
    productId: String,
    locationId: String,
    cumulativeRate: Double,
    demandQty: Double,
    data: Map<String, List<Map<String, Any?>>>,
    supplyIndex: Set<SupplyKey>,
    visited: Set<Pair<String, String>>,
    out: MutableMap<SupplyKey, Double>,
) {
    val key = productId to locationId
    if (key in visited) return

    val supplyKey = SupplyKey(productId, locationId)
    if (supplyKey in supplyIndex) {
        // Record symbolic need: demandQty × cumulative rate from demand to here.
        // Sum across multiple paths (union-alt may reach the same supply twice).
        out.merge(supplyKey, demandQty * cumulativeRate, Double::plus)
        // KEY: do NOT stop here. Keep walking past supply-bearing nodes.
    }

    val methods = getMethods(productId, locationId, data)
    val (method, _) = getPreferredMethod(methods)
    if (method == null) return  // terminal — no way to make/buy/move

    val nextVisited = visited + key

    when (method["type"]) {
        "purchase" -> {
            // Purchase materializes inventory here; nothing to recurse into.
        }
        "make" -> {
            val productionLocation = (method["location_id"] as? String)?.trim() ?: locationId
            val variants = variantsForMake(productId, productionLocation, 1.0, method, data)
            for ((_, childList) in variants) {
                // Union-alt: recurse into every alt child, not just the first.
                for (alt in childList) {
                    val cPid = (alt["product_id"] as? String)?.trim() ?: continue
                    val cLid = (alt["location_id"] as? String)?.trim() ?: continue
                    val cRate = (alt["quantity"] as? Number)?.toDouble() ?: continue
                    if (cRate <= 0) continue
                    walkBomSymbolic(
                        productId = cPid,
                        locationId = cLid,
                        cumulativeRate = cumulativeRate * cRate,
                        demandQty = demandQty,
                        data = data,
                        supplyIndex = supplyIndex,
                        visited = nextVisited,
                        out = out,
                    )
                }
            }
        }
        "move" -> {
            for (child in childMaterialsForMove(method, 1.0)) {
                val cPid = (child["product_id"] as? String)?.trim() ?: continue
                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                val cRate = (child["quantity"] as? Number)?.toDouble() ?: continue
                if (cRate <= 0) continue
                walkBomSymbolic(
                    productId = cPid,
                    locationId = cLid,
                    cumulativeRate = cumulativeRate * cRate,
                    demandQty = demandQty,
                    data = data,
                    supplyIndex = supplyIndex,
                    visited = nextVisited,
                    out = out,
                )
            }
        }
    }
}
