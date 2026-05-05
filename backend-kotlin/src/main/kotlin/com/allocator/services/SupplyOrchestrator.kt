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
    /**
     * Consolidated-WO pegging entries (`demand_id = null`) so the WO-pegging
     * endpoint can locate trees for merged work orders. One entry per
     * multi-demand group from Phase E. The tree is a representative
     * per-demand tree from one of the contributing demands; the frontend's
     * findWoNode walks it by (pid, lid, method) and ignores root-level
     * demand_id, so any contributor's tree suffices for lookup.
     */
    val consolidatedPegging: List<Map<String, Any?>>,
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

    // Shared feasibility cache across all Phase 3a/3c plan() calls. Stable for
    // the run since `data` and `purchase_allowed` don't change. Without it, the
    // reactive-fallback site at plan() can't admit make alternatives.
    val feasibilityCache: MutableMap<Pair<String, String>, Int> = mutableMapOf()
    // Structural-failure memo for makes — once a make-fallback for (pid, lid)
    // hard-blocks on a no_methods cascade, future demands skip the doomed walk.
    // Value is the cached reason for diagnostic stub pegging nodes.
    val structuralFailedMakes: MutableMap<Pair<String, String>, String> = mutableMapOf()

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

        // Wrap the progress callback to inject iter K/MAX so the UI's
        // demand-progress bar carries an "iter K/N" suffix the same way the
        // leaf engine does. Phase 3c (final commit) emits no iter info — the
        // bar will render plain "X/N demands" for that pass, which the user
        // can read as "post-convergence final commit."
        val iterCb: ((Map<String, Any?>) -> Unit)? = progressCallback?.let { cb ->
            { payload ->
                cb(payload + mapOf(
                    "iteration" to iter + 1,
                    "iterations_max" to MAX_SUPPLY_ITERATIONS,
                ))
            }
        }

        // 3a — initial / re-commit with current caps.
        commit = runInitialCommit(
            demands, inventory, data, config, overrideIndex, allocations, iterCb,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
        )

        // Capture matrix misses. runInitialCommit now reports actualDraws via
        // inventory delta, so any (D, S) pair where D drew from S without an
        // allocation is visible. Patch those into `allocations` at the drawn
        // qty, so subsequent iters' commits and Phase 3c run with proper caps
        // and the demand participates in supply-level allocation instead of
        // racing through plan() unconstrained.
        //
        // The patch is at-or-above the supply.qty bound: total per-S
        // allocation post-patch ≤ total inventory drained from S in this iter
        // ≤ supply.qty. compensate's revoke step still bounds the loop.
        val (patchedAllocs, missCount, missQty) = patchMatrixMisses(allocations, commit.actualDraws)
        if (missCount > 0) {
            log.warn(
                "supply iter {}: matrix-miss caught — {} (demand, supply) pair(s) totaling {} qty bypassed Phase 2; patching caps for next iter",
                iterations, missCount, "%.2f".format(missQty),
            )
            allocations = patchedAllocs
        }

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
                "supply iter {}: converged (progress {}% below {}% threshold; over-allocation {} qty across {} supply(ies); {} absorbed by candidates, {} dropped)",
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
            "supply iter {}: over-allocation {} qty across {} supply(ies) ({} absorbed by candidates, {} dropped); refining caps for next iter",
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

    // Phase 3c — final commit with converged caps.
    //
    // The in-loop `commit` reflects iter K's pre-compensation caps. When the
    // loop exits via the relative-progress threshold, comp.allocations differs
    // from the caps that produced `commit.workOrders` by up to qtyRedistributed
    // (e.g. ~179k qty on case 169). Without this pass, WOs would encode iter
    // K's input caps while supplyLevelAllocations encodes iter K's output caps.
    //
    // For the "no redistribution" exit path this is a no-op (allocations
    // unchanged → identical commit). For the threshold-break path it
    // re-resolves WOs against the converged caps so all downstream artifacts
    // describe the same state.
    inventory.clear()
    for (b in initialInventory) inventory.add(b.toMutableMap())
    commit = runInitialCommit(
        demands, inventory, data, config, overrideIndex, allocations, progressCallback,
        feasibilityCache = feasibilityCache,
        structuralFailedMakes = structuralFailedMakes,
    )
    log.info(
        "supply Phase 3c (final commit): produced {} WOs from {} demand(s) under converged caps",
        commit.workOrders.size, demands.size,
    )

    // Phase E — merge per-demand WOs into consolidated WOs.
    val mergedWOs = synthesizeConsolidatedWOs(commit.workOrders, priorities, mode)
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

    // Build consolidated-WO pegging entries so the WO-pegging endpoint can
    // locate trees for the merged WOs Phase E produced.
    val consolidatedPegging = buildConsolidatedPegging(
        perDemandWOs = commit.workOrders,
        perDemandPegging = commit.planningPegging,
        priorities = priorities,
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
        consolidatedPegging = consolidatedPegging,
    )
}

/**
 * For each multi-demand WO group from Phase E, emit a consolidatedPegging
 * entry shaped like the leaf-engine's: `{demand_id: null, consolidated: true,
 * consolidated_demand_ids: [...], per_demand_allocations: {...}, tree: ...}`.
 *
 * The tree is the per-demand pegging tree of the largest contributor — the
 * frontend's findWoNode walks by (pid, lid, method) and doesn't care which
 * contributor owns the tree, so any tree containing the WO works.
 *
 * Single-demand groups don't need consolidated entries; the frontend looks
 * those up in planningPegging by their real demand_id.
 */
private fun buildConsolidatedPegging(
    perDemandWOs: List<Map<String, Any?>>,
    perDemandPegging: List<Map<String, Any?>>,
    priorities: Map<Any?, Int>,
): List<Map<String, Any?>> {
    if (perDemandWOs.isEmpty()) return emptyList()

    // Group per-demand WOs by the same key Phase E uses.
    data class GroupKey(
        val productId: String?,
        val locationId: String?,
        val startTime: String?,
        val endTime: String?,
        val method: String?,
    )

    fun keyOf(wo: Map<String, Any?>) = GroupKey(
        productId = wo["product_id"] as? String,
        locationId = wo["location_id"] as? String,
        startTime = wo["start_time"] as? String,
        endTime = wo["end_time"] as? String,
        method = wo["method"] as? String,
    )

    val groups = perDemandWOs.groupBy(::keyOf)
    val treeByDemand: Map<Any?, Map<String, Any?>> = perDemandPegging
        .mapNotNull { entry ->
            val did = entry["demand_id"]
            @Suppress("UNCHECKED_CAST")
            val tree = entry["tree"] as? Map<String, Any?>
            if (did != null && tree != null) did to tree else null
        }
        .toMap()

    val result = mutableListOf<Map<String, Any?>>()
    for ((_, members) in groups) {
        if (members.size < 2) continue  // single-demand passthroughs go through planningPegging by demand_id
        val perDemandQty: Map<Any?, Double> = members
            .filter { it["demand_id"] != null }
            .groupingBy { it["demand_id"] }
            .fold(0.0) { acc, wo -> acc + ((wo["quantity"] as? Number)?.toDouble() ?: 0.0) }
        if (perDemandQty.isEmpty()) continue
        // Largest contributor — guaranteed to have a per-demand tree containing this WO.
        val largest = perDemandQty.maxByOrNull { it.value }?.key ?: continue
        val tree = treeByDemand[largest] ?: continue
        result.add(mapOf(
            "demand_id" to null,
            "consolidated" to true,
            "consolidated_demand_ids" to perDemandQty.keys.map { it?.toString() ?: "" },
            "per_demand_allocations" to perDemandQty.entries.associate { (k, v) -> (k?.toString() ?: "") to v },
            "tree" to tree,
            // Carry priority of the chosen tree's demand for downstream sorting (best-effort).
            "_priority" to (priorities[largest] ?: 0),
        ))
    }
    return result
}

/**
 * Detect (demand, supply) pairs that drew from a supply without a Phase 2
 * allocation — i.e., the matrix walker missed them, so plan() ran with no
 * cap for that pair and FIFO-style competition resumed.
 *
 * Patches `allocations` with the discovered draw qty for each missed pair.
 * Returns the patched allocations plus a (count, qty) summary for telemetry.
 *
 * After patching, every demand that physically drew from a supply has an
 * explicit cap. The next iter's commit (and ultimately Phase 3c) will run
 * plan() with that cap in budget — no more unconstrained draws.
 */
private fun patchMatrixMisses(
    allocations: SupplyAllocations,
    actualDraws: Map<Any?, Map<SupplyKey, Double>>,
): Triple<SupplyAllocations, Int, Double> {
    var missCount = 0
    var missQty = 0.0
    val newByRow = allocations.byRow.mapValues { (_, m) -> m.toMutableMap() }.toMutableMap()
    val newByColumn = allocations.byColumn.mapValues { (_, m) -> m.toMutableMap() }.toMutableMap()
    val sample = mutableListOf<Triple<Any?, SupplyKey, Double>>()  // for diagnostic logging

    for ((demandId, draws) in actualDraws) {
        val rowAllocs = newByRow[demandId]
        for ((sk, drew) in draws) {
            if (drew <= 1e-9) continue
            val currentAlloc = rowAllocs?.get(sk) ?: 0.0
            // Matrix miss = drew > current cap. Either the demand wasn't in
            // the matrix (rowAllocs == null) or the matrix had no entry for
            // this supply (currentAlloc == 0) but plan() drew via inventory
            // anyway. Either way, the cap needs to rise to at least drew so
            // the next iter recognizes the demand as a participant at S.
            if (drew > currentAlloc + 1e-9) {
                val byRow = newByRow.getOrPut(demandId) { mutableMapOf() }
                byRow[sk] = drew
                val byCol = newByColumn.getOrPut(sk) { mutableMapOf() }
                byCol[demandId] = drew
                missQty += drew - currentAlloc
                missCount++
                if (sample.size < 10) sample.add(Triple(demandId, sk, drew))
            }
        }
    }

    if (sample.isNotEmpty()) {
        val rendered = sample.joinToString("; ") { (d, sk, q) -> "$d→$sk:${"%.2f".format(q)}" }
        log.warn("supply matrix-miss sample (first ${sample.size} of $missCount): $rendered")
    }

    return Triple(SupplyAllocations(byRow = newByRow, byColumn = newByColumn), missCount, missQty)
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
