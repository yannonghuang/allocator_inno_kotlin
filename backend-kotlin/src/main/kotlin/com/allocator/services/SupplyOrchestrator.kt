package com.allocator.services

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.SupplyOrchestrator")

/**
 * Phase F of supply-level consolidation — top-level orchestrator that wires
 * Phases A–E into the pipeline:
 *
 * ```
 *   Phase 1 (PLAN)        buildNeedsMatrix
 *   Phase 2 (ALLOCATE)    allocateSupplies
 *   loop:
 *     Phase 3a (COMMIT)   runInitialCommit
 *     Phase 3b (COMP)     compensate
 *     until !redistributed (or MAX_SUPPLY_ITERATIONS)
 *   Phase 3c (FINAL)      runInitialCommit with final caps
 *   Phase E (WO MERGE)    synthesizeConsolidatedWOs
 * ```
 *
 * Returned in a [V2SupplyResult] that runPlanning unwraps the same way it
 * unwraps [runV2Iterated]'s output.
 *
 * Activated by `consolidation.engine = "supply"` in the run config. Default
 * is `"leaf-legacy"` so existing behaviour is unchanged until callers opt
 * in.
 *
 * Per-iter telemetry emits one INFO line per compensation pass, matching
 * the leaf engine's `v2 iter k` log style — useful for soak-test
 * diagnostics + side-by-side validation against the leaf engine.
 */

/**
 * Maximum compensation iterations the supply engine will run before giving
 * up and using the last allocations as-is. Each iter is one full
 * runInitialCommit + compensate pass. In the design (§5) we expect 1-2
 * iters typical; 10 is safety headroom for cases with deep BOMs where the
 * tail of the geometric decay needs a few more passes to settle.
 */
private const val MAX_SUPPLY_ITERATIONS = 10

/**
 * Practical-convergence threshold. If consecutive iters' redistributed qty
 * differ by less than this fraction (1%), we treat the loop as converged
 * even though `compensate.redistributed` is still true. The residual is
 * floating-point/rounding shuffle between cap-bound demands at boundary
 * supplies — running more iters won't change the allocation in any way the
 * downstream commit can observe, so we stop early.
 */
private const val CONVERGENCE_PROGRESS_THRESHOLD = 0.01

/** Output of [runV2Supply]; mirrors the shape runPlanning expects. */
internal data class V2SupplyResult(
    /** All work orders, post-Phase-E synthesis (per-supply consolidated where applicable). */
    val workOrders: List<Map<String, Any?>>,
    /**
     * Committed rows + per-demand pegging trees. The `workOrders` field of
     * this nested result is intentionally empty — all WOs are at the outer
     * [V2SupplyResult.workOrders] field after Phase E synthesis.
     */
    val commitResult: LegacyCommitResult,
    /** Number of compensation iterations actually run (1-based). */
    val iterations: Int,
    /** True if the loop stopped because compensation found nothing to redistribute. */
    val converged: Boolean,
    /**
     * Per-(supply_id) allocation records emitted into the runPlanning result
     * map under the `supply_level_allocations` key. Frontend consumers (the
     * supply view chip + slide-in) populate `splitInfos` from this field
     * when the supply engine ran. Empty under the leaf engine — its
     * splitInfos come from the consolidated pegging entries instead.
     */
    val supplyLevelAllocations: List<Map<String, Any?>>,
)

/**
 * Run the supply-level consolidation pipeline end-to-end.
 *
 * `inventory` is mutated by Phase 3a's per-demand `plan()` walks; the orchestrator
 * snapshots it once before the loop and reverts between iterations so each pass
 * sees the same starting state. After the final commit the function leaves
 * `inventory` in its mutated post-final-commit state — callers that want the
 * pre-commit snapshot can capture it themselves.
 */
internal fun runV2Supply(
    demands: List<Map<String, Any?>>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    consolidationConfig: ConsolidationConfig,
    overrideIndex: Map<String, Map<String, Any?>>,
    progressCallback: ((Map<String, Any?>) -> Unit)?,
): V2SupplyResult {
    val mode = consolidationConfig.allocationMode

    // Phase 1 — symbolic needs matrix. One-shot.
    val matrix = buildNeedsMatrix(demands, data)
    log.info(
        "supply Phase 1 (plan): matrix has {} demand row(s), {} supply column(s), {} cell(s)",
        matrix.byRow.size, matrix.byColumn.size, matrix.cellCount(),
    )

    // Phase 2 — initial per-supply allocation.
    val supplyTotals = aggregateSupplies(data["supply"] ?: emptyList())
    val priorities = extractDemandPriorities(demands)
    var allocations = allocateSupplies(matrix, supplyTotals, priorities, mode)
    log.info(
        "supply Phase 2 (allocate): mode={} supplies={} initial cells={}",
        mode, supplyTotals.size, allocations.cellCount(),
    )

    // Snapshot inventory once. Phase 3a's plan() calls mutate it; we revert
    // between iters so each iter sees the same starting state.
    val initialInventory: List<Map<String, Any?>> = inventory.map { it.toMap() }

    // Compensation loop (Phase 3a + 3b).
    var commit: InitialCommitResult? = null
    var iterations = 0
    var converged = false
    var prevRedistributed = Double.POSITIVE_INFINITY

    for (iter in 0 until MAX_SUPPLY_ITERATIONS) {
        iterations = iter + 1

        // Revert inventory to snapshot.
        inventory.clear()
        for (b in initialInventory) inventory.add(b.toMutableMap())

        // 3a — initial / re-commit with current caps.
        commit = runInitialCommit(
            demands, inventory, data, config, overrideIndex, allocations, progressCallback,
        )

        // 3b — compensation.
        val comp = compensate(allocations, commit.actualDraws, matrix, priorities, mode)

        if (!comp.redistributed) {
            log.info("supply iter {}: converged (no redistribution)", iterations)
            converged = true
            break
        }

        // Practical-convergence break: if the redistribution qty has shrunk
        // less than CONVERGENCE_PROGRESS_THRESHOLD relative to last iter, the
        // tail is just rounding shuffle between boundary candidates — running
        // more iters won't materially change the allocation. Apply the new
        // caps from this iter (it's still a strict improvement) before exit.
        val progressFraction = if (prevRedistributed.isFinite() && prevRedistributed > 0.0) {
            (prevRedistributed - comp.qtyRedistributed) / prevRedistributed
        } else 1.0
        val dropped = comp.qtyRedistributed - comp.qtyAbsorbed
        if (iter > 0 && progressFraction < CONVERGENCE_PROGRESS_THRESHOLD) {
            log.info(
                "supply iter {}: converged (progress {}% below {}% threshold; over-production {} qty across {} supply(ies); {} absorbed by candidates, {} dropped)",
                iterations,
                "%.2f".format(progressFraction * 100),
                "%.0f".format(CONVERGENCE_PROGRESS_THRESHOLD * 100),
                "%.2f".format(comp.qtyRedistributed),
                comp.supplyCount,
                "%.2f".format(comp.qtyAbsorbed),
                "%.2f".format(dropped),
            )
            allocations = comp.allocations
            converged = true
            break
        }

        log.info(
            "supply iter {}: over-production {} qty across {} supply(ies) ({} absorbed by candidates, {} dropped); refining caps for next iter",
            iterations,
            "%.2f".format(comp.qtyRedistributed),
            comp.supplyCount,
            "%.2f".format(comp.qtyAbsorbed),
            "%.2f".format(dropped),
        )
        allocations = comp.allocations
        prevRedistributed = comp.qtyRedistributed
    }

    if (!converged) {
        log.warn(
            "supply iter {} (max): compensation did not converge — remaining redistribution will be left as residual",
            iterations,
        )
    }

    // Phase E — merge per-demand WOs into consolidated WOs.
    val mergedWOs = synthesizeConsolidatedWOs(commit!!.workOrders, priorities, mode)
    log.info(
        "supply Phase E (WO synthesis): {} per-demand WOs merged into {} (consolidated + passthrough) WO(s)",
        commit.workOrders.size, mergedWOs.size,
    )

    // Build per-(supply_id) allocation records for the frontend's splitInfos.
    val supplyLevelAllocations = buildSupplyLevelAllocations(
        allocations = allocations,
        actualDraws = commit.actualDraws,
        supplies = data["supply"] ?: emptyList(),
        mode = mode,
    )

    return V2SupplyResult(
        workOrders = mergedWOs,
        commitResult = LegacyCommitResult(
            committedDemands = commit.committedDemands,
            workOrders = emptyList(),  // All WOs are in the outer workOrders list above.
            planningPegging = commit.planningPegging,
        ),
        iterations = iterations,
        converged = converged,
        supplyLevelAllocations = supplyLevelAllocations,
    )
}

/**
 * Build per-(supply_id) allocation records for the runPlanning result map.
 *
 * Frontend consumers (chip + slide-in) populate splitInfos from these
 * records when present, replacing the consolidated-pegging-entry walk
 * the leaf engine uses. One record per real supply_id; supply rows that
 * share a SupplyKey share the same group_total_need / group_total_produced
 * / per_demand_allocations (since allocation is per (pid, lid), not per
 * supply row).
 *
 * Output shape mirrors what the frontend's SupplySplitInfo type expects:
 *   - supply_id, group_product_id, group_location_id, mode
 *   - group_total_need (Σ allocated)
 *   - group_total_produced (Σ actually drawn)
 *   - candidate_count (number of demands with non-zero allocation)
 *   - per_demand_allocations: Map<demandId, allocatedQty>
 *
 * Empty list is returned when no supplies have multi-demand allocations
 * (a fully degenerate run).
 */
private fun buildSupplyLevelAllocations(
    allocations: SupplyAllocations,
    actualDraws: Map<Any?, Map<SupplyKey, Double>>,
    supplies: List<Map<String, Any?>>,
    mode: String,
): List<Map<String, Any?>> {
    // Map SupplyKey → list of supply_ids (multiple rows can share a key).
    val supplyIdsByKey: Map<SupplyKey, List<String>> = supplies
        .filter { ((it["qty"] as? Number)?.toDouble() ?: 0.0) > 0 }
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            val sid = row["supply_id"] as? String ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank() || sid.isBlank()) null
            else Triple(SupplyKey(pid, lid), sid, row)
        }
        .groupBy({ it.first }, { it.second })

    val result = mutableListOf<Map<String, Any?>>()
    for ((supplyKey, demandsAtKey) in allocations.byColumn) {
        val supplyIds = supplyIdsByKey[supplyKey] ?: continue
        val perDemand = demandsAtKey.entries
            .filter { it.value > 1e-9 }
            .associate { (demandId, qty) -> (demandId?.toString() ?: "") to qty }
        if (perDemand.isEmpty()) continue

        val totalAllocated = demandsAtKey.values.sum()
        val totalDrawn = demandsAtKey.keys.sumOf { d ->
            actualDraws[d]?.get(supplyKey) ?: 0.0
        }

        for (sid in supplyIds) {
            result.add(mapOf(
                "supply_id"             to sid,
                "group_product_id"      to supplyKey.productId,
                "group_location_id"     to supplyKey.locationId,
                "mode"                  to mode,
                "group_total_need"      to totalAllocated,
                "group_total_produced"  to totalDrawn,
                "candidate_count"       to perDemand.size,
                "per_demand_allocations" to perDemand,
            ))
        }
    }
    return result
}
