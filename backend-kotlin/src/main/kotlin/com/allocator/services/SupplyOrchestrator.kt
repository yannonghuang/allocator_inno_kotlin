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
 * iters typical; 5 is safety headroom.
 */
private const val MAX_SUPPLY_ITERATIONS = 5

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

        log.info(
            "supply iter {}: redistributed {} qty across {} supply(ies); refining caps for next iter",
            iterations, "%.2f".format(comp.qtyRedistributed), comp.supplyCount,
        )
        allocations = comp.allocations
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

    return V2SupplyResult(
        workOrders = mergedWOs,
        commitResult = LegacyCommitResult(
            committedDemands = commit.committedDemands,
            workOrders = emptyList(),  // All WOs are in the outer workOrders list above.
            planningPegging = commit.planningPegging,
        ),
        iterations = iterations,
        converged = converged,
    )
}
