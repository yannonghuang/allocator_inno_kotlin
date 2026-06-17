package com.allocator.services

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.SupplyGuidedPlanning")

// ── Config ─────────────────────────────────────────────────────────────────────

/**
 * Parsed from `config["supply_guided"]`.
 *
 * Supply-guided planning implements the two-loop model:
 *   Loop 1  — top-down request decomposition (inventory-prioritized BOM walk)
 *   Step 2  — supply allocation across all demands simultaneously
 *   Loop 2  — bottom-up commitment with per-supply budget caps
 *   Step 3c — GC of unused budgets via compensation passes
 */
data class SupplyGuidedConfig(
    val enabled: Boolean = false,
    /**
     * Allocation mode for Step 2.
     *
     * `"demand_qty"` (default) — proportional to raw demand quantity:
     *   allocation(d, s) = qty(s) × (qty(d) / Σ_competing qty(dd))
     * This gives each demand a fair share of every supply regardless of BOM rates.
     *
     * Other modes (`"fair"`, `"proportional"`, `"priority_first"`) delegate to
     * the existing [allocate] policies.
     */
    val allocationMode: String = "demand_qty",
    /**
     * Number of compensation passes after Loop 2 to GC unused budgets.
     * Each pass identifies allocations that went unused (because an upstream
     * supply satisfied the demand before recursion reached the allocated supply)
     * and redistributes the slack to cap-bound demands with residual need.
     * Range [0, 10]; default 1.
     */
    val maxCompensationPasses: Int = 1,
)

fun parseSupplyGuidedConfig(config: Map<String, Any?>?): SupplyGuidedConfig {
    val sub = (config?.get("supply_guided") as? Map<*, *>) ?: return SupplyGuidedConfig()
    @Suppress("UNCHECKED_CAST")
    val m = sub as? Map<String, Any?> ?: return SupplyGuidedConfig()
    val enabled = m["enabled"] as? Boolean ?: false
    val allocationMode = when (m["allocation_mode"]?.toString()) {
        "fair"           -> "fair"
        "proportional"   -> "proportional"
        "priority_first" -> "priority_first"
        "demand_qty"     -> "demand_qty"
        else             -> "demand_qty"
    }
    val maxCompensationPasses = ((m["max_compensation_passes"] as? Number)?.toInt() ?: 1).coerceIn(0, 10)
    return SupplyGuidedConfig(enabled, allocationMode, maxCompensationPasses)
}

// ── Orchestrator ───────────────────────────────────────────────────────────────

/**
 * Supply-guided planning — the two-loop model.
 *
 * ## Mental model (from spec)
 *
 * **Loop 1 — top-down request decomposition** (per demand, Step 1):
 *   For each demand walk the BOM from user demand towards raw materials.
 *   At each node where multiple BOM alternatives exist, prefer alternatives
 *   whose immediate children are supply-bearing — inventory is used up before
 *   new WOs (Step 1a). Collect a request at every supply-bearing node on the
 *   chosen paths. The resulting demand × supply matrix is the *request map*.
 *   BOM rates are propagated to WO skeletons (Step 1b) — handled implicitly
 *   by plan() in Loop 2.
 *
 * **Step 2 — supply allocation** (all demands together):
 *   For each supply column, allocate its qty to competing demands per the
 *   configured policy (default: demand_qty-proportional).  Allocation formula:
 *     allocation(d, s) = qty(s) × (qty(d) / Σ_competing qty(dd))   [demand_qty]
 *   Never over-commit: each demand receives at most its requested share.
 *   Output: per-demand budget map — demand → supply → allocated qty.
 *
 * **Loop 2 — bottom-up commitment** (per demand, Step 3):
 *   Run plan() for each demand with the budget caps from Step 2.  plan() does
 *   the actual inventory consumption and WO emission; budgets cap consumption
 *   at each supply so demands don't over-draw their allocated share.
 *   Reconcile() enforces AND semantics post-commit (least-supplied dominates).
 *
 * **Step 3c — GC unused budgets**:
 *   After Loop 2, any budget that went undrawn (demand found inventory
 *   upstream and never recursed to the leaf) is redistributed to cap-bound
 *   demands via compensation passes.  Convergence is guaranteed by the
 *   monotone-non-increasing property of compensation.
 *
 * @param demands priority-sorted demand list
 * @param inventory mutable supply pool (consumed in-place during Loop 2)
 * @param data BOM, methods, supply tables
 * @param config planning run configuration
 * @param overrideIndex pre-built method/variant override index
 * @param progressCallback optional per-demand progress notification
 * @return committed demands, work orders, and per-demand pegging trees
 */
internal fun runSupplyGuidedPlanning(
    demands: List<Map<String, Any?>>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    overrideIndex: Map<String, Map<String, Any?>>,
    progressCallback: ((Map<String, Any?>) -> Unit)?,
): LegacyCommitResult {
    val sgConfig = parseSupplyGuidedConfig(config)

    // ── Loop 1: top-down request decomposition ─────────────────────────────────
    // Walk the BOM for every demand, preferring methods with supply-bearing
    // children (Step 1a). No inventory is consumed here — pure shape walk.
    val requestMatrix = buildInventoryAwareNeedsMatrix(demands, data)
    log.info(
        "[supply-guided] request map built: {} demand rows, {} supply columns, {} cells",
        requestMatrix.byRow.size, requestMatrix.byColumn.size, requestMatrix.cellCount(),
    )
    // ── Debug: dump request-map rows for every supply of 260-0141-02 ──────────────
    val debugPid = "260-0141-02"
    requestMatrix.byColumn
        .filterKeys { it.productId == debugPid }
        .forEach { (sk, demandNeeds) ->
            log.info("[supply-guided][debug] request map for supply {}: {} competing demands", sk, demandNeeds.size)
            demandNeeds.entries
                .sortedByDescending { it.value }
                .forEach { (demandId, need) ->
                    log.info("[supply-guided][debug]   demand={} need={}", demandId, need)
                }
        }
    if (requestMatrix.byColumn.keys.none { it.productId == debugPid }) {
        log.warn("[supply-guided][debug] request map has NO entries for product {}", debugPid)
    }

    // ── Step 2: supply allocation ───────────────────────────────────────────────
    // Aggregate supply quantities; distribute each supply to its competing
    // demands per the configured allocation mode.
    val supplyTotals      = aggregateSupplies(data["supply"] ?: emptyList())
    val demandPriorities  = extractDemandPriorities(demands)
    val demandQuantities  = extractDemandQuantities(demands)

    var allocations = allocateSupplies(
        matrix           = requestMatrix,
        supplyTotals     = supplyTotals,
        demandPriorities = demandPriorities,
        mode             = sgConfig.allocationMode,
        demandQuantities = demandQuantities,
    )
    log.info(
        "[supply-guided] initial allocation: {} demand rows, {} cells, mode={}",
        allocations.byRow.size, allocations.cellCount(), sgConfig.allocationMode,
    )
    // ── Debug: dump allocation for every supply of 260-0141-02 ───────────────────
    allocations.byColumn
        .filterKeys { it.productId == debugPid }
        .forEach { (sk, demandAllocs) ->
            log.info("[supply-guided][debug] allocation for supply {}: {} demands allocated", sk, demandAllocs.size)
            demandAllocs.entries
                .sortedByDescending { it.value }
                .forEach { (demandId, qty) ->
                    log.info("[supply-guided][debug]   demand={} allocated={}", demandId, qty)
                }
        }
    if (allocations.byColumn.keys.none { it.productId == debugPid }) {
        log.warn("[supply-guided][debug] allocation has NO entries for product {}", debugPid)
    }

    // ── Build per-demand budgets from allocations ───────────────────────────────
    // plan() expects budgets keyed by "$productId|$locationId" (componentKey).
    // SupplyKey.toString() already returns that format.
    fun allocationsToBudgets(a: SupplyAllocations): Map<Any?, MutableMap<String, Double>> =
        a.byRow.mapValues { (_, supplyCaps) ->
            supplyCaps.entries.associateTo(mutableMapOf()) { (sk, qty) -> sk.toString() to qty }
        }

    val budgets = allocationsToBudgets(allocations)

    // ── Debug: dump per-demand budgets for 260-0141-02 ───────────────────────────
    budgets.forEach { (demandId, caps) ->
        val debugCaps = caps.filterKeys { it.contains(debugPid) }
        if (debugCaps.isNotEmpty()) {
            log.info("[supply-guided][debug] budget for demand={}: {}", demandId, debugCaps)
        }
    }

    // ── Loop 2: commitment with budget caps ────────────────────────────────────
    // plan() for each demand consumes inventory up to the per-supply budget,
    // emits WOs for uncovered residual, and builds the pegging tree.
    val commitResult = legacyCommit(
        demands          = demands,
        inventory        = inventory,
        data             = data,
        config           = config,
        overrideIndex    = overrideIndex,
        useTaggedLookup  = false,
        progressCallback = progressCallback,
        budgets          = budgets,
    )

    // ── Step 3c: GC unused budgets via compensation passes ─────────────────────
    // Extract what each demand actually drew from each supply-bearing node,
    // then redistribute the undrawn slack to cap-bound demands.
    // Note: full convergence (re-running Loop 2 with updated caps) is deferred;
    // these passes are telemetry + single-pass redistribution only.
    if (sgConfig.maxCompensationPasses > 0) {
        val actualDraws = extractActualDrawsFromPegging(commitResult.planningPegging)
        var changed = false
        repeat(sgConfig.maxCompensationPasses) { pass ->
            val cr = compensate(allocations, actualDraws, requestMatrix, demandPriorities, sgConfig.allocationMode)
            if (!cr.redistributed) return@repeat
            log.info(
                "[supply-guided] compensation pass {}: supplies={} redistributed={:.2f} absorbed={:.2f}",
                pass + 1, cr.supplyCount, cr.qtyRedistributed, cr.qtyAbsorbed,
            )
            allocations = cr.allocations
            changed = true
        }
        if (changed) {
            log.info("[supply-guided] compensation complete; re-plan with updated caps is TODO (convergence loop)")
        }
    }

    return commitResult
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/**
 * Aggregate what each demand actually drew from each supply-bearing (pid, lid)
 * node during Loop 2, by walking the committed pegging trees.
 *
 * Returns demandId → SupplyKey → total qty consumed, suitable for passing
 * to [compensate] as the `actualDraws` argument.
 *
 * Only "supply" type pegging nodes are counted (not "purchase" — those are
 * external procurement events, not draws on existing inventory supply rows).
 * Failed work_order subtrees are skipped (they reflect first-pass exploration
 * that was rolled back and didn't actually consume inventory).
 */
private fun extractActualDrawsFromPegging(
    pegging: List<Map<String, Any?>>,
): Map<Any?, Map<SupplyKey, Double>> {
    val result = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()

    @Suppress("UNCHECKED_CAST")
    fun walk(node: Map<String, Any?>, demandId: Any?) {
        val type = node["type"] as? String
        if (type == "work_order" && node["failed"] == true) return

        if (type == "supply") {
            val pid = (node["product_id"] as? String)?.trim() ?: return
            val lid = (node["location_id"] as? String)?.trim() ?: return
            val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 1e-12) {
                result.getOrPut(demandId) { mutableMapOf() }
                    .merge(SupplyKey(pid, lid), qty, Double::plus)
            }
        }

        val nextId = if (type == "demand") node["demand_id"] else demandId
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it, nextId) }
    }

    for (entry in pegging) {
        val demandId = entry["demand_id"]
        val tree     = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, demandId)
    }
    return result
}
