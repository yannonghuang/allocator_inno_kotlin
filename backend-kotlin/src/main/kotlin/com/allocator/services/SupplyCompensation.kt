package com.allocator.services

/**
 * Phase 3b of supply-level consolidation — compensation.
 *
 * After the initial commit (3a) reveals which demands left allocation unused,
 * compensation redistributes that unused capacity to other demands at the
 * same supply who *were* cap-bound and had more symbolic need.
 *
 * The redistribution uses the same allocation policy
 * (fair / proportional / priority_first) as Phase 2 — applied just to the
 * unused-capacity slice and the still-unmet-demand subset. Output is a
 * **new** [SupplyAllocations] with the incremental amounts added to the
 * existing per-(demand, supply) caps.
 *
 * See docs/supply-level-consolidation.md §3 (mental-model diagram, sub-phase
 * 3b) and §5 (compensation handles within-iter rebalancing; outer iteration
 * is reserved for production-cascade refinement).
 *
 * ## Convergence
 *
 * Each compensation pass can only ADD to caps (never reduce). Caps are bounded
 * above by `Σ matrix[D][S]` for each demand and bounded above by `supply.qty`
 * in aggregate, so the sequence is non-decreasing and bounded → converges in
 * finitely many passes.
 *
 * In practice 1-2 compensation passes are typical. The orchestrator loops:
 *   draws = runInitialCommit(allocations).actualDraws
 *   newAllocations = compensate(allocations, draws, matrix, priorities, mode)
 *   if newAllocations.unchanged → done
 *   else allocations = newAllocations; repeat
 */

/** Output of [compensate]. */
data class CompensationResult(
    /** Updated allocations after redistribution. Same shape as input. */
    val allocations: SupplyAllocations,
    /**
     * True if any cap changed. False = converged (no unused capacity, no
     * eligible candidates, or unused capacity has nowhere to go).
     */
    val redistributed: Boolean,
    /** Number of (supply) columns where redistribution happened — for telemetry. */
    val supplyCount: Int,
    /** Total qty redistributed across all supplies — for telemetry. */
    val qtyRedistributed: Double,
)

/**
 * One compensation pass.
 *
 * For each supply column where some demand left allocation unused:
 *   1. Compute `unused = totalAllocated − totalDrew`.
 *   2. Find candidate demands: those that drew their full allocation
 *      (cap was binding) AND have residual symbolic need beyond their
 *      current allocation.
 *   3. Redistribute `unused` across candidates by the policy.
 *   4. Add the incremental amount to each candidate's cap.
 *
 * @param allocations current per-(demand, supply) caps
 * @param actualDraws what each demand actually drew at each supply
 *                   (from runInitialCommit's output)
 * @param matrix Phase 1's symbolic needs matrix; used to identify residual
 *               unmet need beyond a demand's current allocation
 * @param demandPriorities per-demand priority (lower = higher priority)
 * @param mode allocation policy: "fair" | "proportional" | "priority_first"
 *
 * @return a new [SupplyAllocations] with the redistributed caps applied,
 *         plus telemetry on what changed.
 */
fun compensate(
    allocations: SupplyAllocations,
    actualDraws: Map<Any?, Map<SupplyKey, Double>>,
    matrix: NeedsMatrix,
    demandPriorities: Map<Any?, Int>,
    mode: String,
): CompensationResult {
    // Mutable copies so we can layer the increments.
    val newByRow: MutableMap<Any?, MutableMap<SupplyKey, Double>> = allocations.byRow
        .mapValues { (_, m) -> m.toMutableMap() }
        .toMutableMap()
    val newByColumn: MutableMap<SupplyKey, MutableMap<Any?, Double>> = allocations.byColumn
        .mapValues { (_, m) -> m.toMutableMap() }
        .toMutableMap()

    var supplyCount = 0
    var qtyRedistributed = 0.0

    // Iterate every supply that has a non-zero need column. (Includes supplies
    // that may have been allocated 0 to all demands — still candidates for
    // shut-out demands once unused capacity is freed.)
    for ((supplyKey, demandNeedsAtS) in matrix.byColumn) {
        val allocsAtS = allocations.byColumn[supplyKey] ?: continue
        if (allocsAtS.isEmpty()) continue

        val totalAllocated = allocsAtS.values.sum()
        val totalDrew = allocsAtS.keys.sumOf { d ->
            actualDraws[d]?.get(supplyKey) ?: 0.0
        }
        val unused = totalAllocated - totalDrew
        if (unused <= 1e-9) continue  // converged at this supply

        // Identify candidates: cap-bound demands with unmet symbolic need.
        // A demand is "cap-bound" if its actualDraw is at-or-near its allocation
        // (within float tolerance). It has "unmet need" if its symbolic need
        // exceeds its current allocation.
        val candidates = mutableListOf<AllocationCandidate>()
        for ((demandId, currentAlloc) in allocsAtS) {
            val drew = actualDraws[demandId]?.get(supplyKey) ?: 0.0
            val capBound = drew >= currentAlloc - 1e-9
            if (!capBound) continue  // demand didn't fully use its allocation; not a candidate

            val symbolicNeed = demandNeedsAtS[demandId] ?: 0.0
            val residualNeed = symbolicNeed - currentAlloc
            if (residualNeed <= 1e-9) continue  // demand has no headroom — already fully allocated

            candidates.add(AllocationCandidate(
                demandId = demandId,
                neededQty = residualNeed,
                priority = demandPriorities[demandId] ?: 0,
            ))
        }

        if (candidates.isEmpty()) continue  // unused capacity but nobody to give it to

        // Apply policy to the residual slice. Output: incremental qty per demand.
        val increment = allocate(candidates, unused, mode)
        var redistributedAtS = 0.0
        for ((demandId, addQty) in increment) {
            if (addQty <= 1e-9) continue
            val byRow = newByRow.getOrPut(demandId) { mutableMapOf() }
            byRow[supplyKey] = (byRow[supplyKey] ?: 0.0) + addQty
            val byCol = newByColumn.getOrPut(supplyKey) { mutableMapOf() }
            byCol[demandId] = (byCol[demandId] ?: 0.0) + addQty
            redistributedAtS += addQty
        }
        if (redistributedAtS > 1e-9) {
            supplyCount++
            qtyRedistributed += redistributedAtS
        }
    }

    return CompensationResult(
        allocations = SupplyAllocations(byRow = newByRow, byColumn = newByColumn),
        redistributed = qtyRedistributed > 1e-9,
        supplyCount = supplyCount,
        qtyRedistributed = qtyRedistributed,
    )
}
