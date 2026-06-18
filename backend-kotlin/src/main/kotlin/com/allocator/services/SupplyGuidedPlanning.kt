package com.allocator.services

import org.slf4j.LoggerFactory
import java.time.LocalDate

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
    val enabled: Boolean = true,
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
    val enabled = m["enabled"] as? Boolean ?: true
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
    // Build the BOM DAG once (global BFS + Kahn's). Two rate-propagation passes:
    //
    //   Union-all pass — every demand that can reach a supply via ANY path gets
    //   a request row. Used for allocation (Step 2) so that all competing demands
    //   participate and allocation amounts are fair. The allocation is pure
    //   arithmetic: allocation(D, S) = qty(S) × (demandQty(D) / Σ_competing qty).
    //   This budget is then passed directly to plan() (Loop 2) so that every
    //   demand is capped at its allocated amount for each supply regardless of
    //   which BOM path plan() takes at runtime — preventing unlimited draw when
    //   the initially-preferred path is exhausted by earlier demands.
    val graph         = buildBomGraph(demands, data)
    val requestMatrix = propagateRates(demands, graph, inventoryAware = false)
    log.info(
        "[supply-guided] request map built: {} demand rows, {} supply columns, {} cells",
        requestMatrix.byRow.size, requestMatrix.byColumn.size, requestMatrix.cellCount(),
    )
    // Demands absent from the request matrix reach no supply — they draw from inventory
    // uncapped in Loop 2. Log them so we can verify this is intentional (pure-WO paths)
    // rather than a BOM graph gap or zero-qty anomaly.
    val unmappedDemands = demands.filter { it["demand_id"] !in requestMatrix.byRow }
    if (unmappedDemands.isNotEmpty()) {
        log.warn(
            "[supply-guided] {} demand(s) not in request map (no reachable supply path or qty<=0): {}",
            unmappedDemands.size,
            unmappedDemands.joinToString { d ->
                "${d["demand_id"]}(qty=${d["quantity"]})"
            },
        )
    }
    // Per-demand dates for per-lot eligibility filtering.
    val demandDates: Map<Any?, LocalDate?> = demands.associate { d ->
        d["demand_id"] to parseDate(d["request_due_time"] as? String ?: d["request_time"] as? String)
    }

    // Critical-materials filter: budget caps apply only to non-purchasable materials.
    // Open-world semantics: absent from the allocation map ⇒ uncapped.
    val purchasable = effectivePurchasableSet(config, data)
    val criticalMatrix = if (purchasable == null) NeedsMatrix(emptyMap(), emptyMap())
                         else filterToCritical(requestMatrix, purchasable)
    log.info(
        "[supply-guided] critical matrix: {} supply columns (non-purchasable) of {} total",
        criticalMatrix.byColumn.size, requestMatrix.byColumn.size,
    )

    // Log contested critical supplies (>1 competing demand) sorted by total requested qty descending.
    criticalMatrix.byColumn.entries
        .filter { it.value.size > 1 }
        .sortedByDescending { e -> e.value.values.sum() }
        .forEach { (sk, demandNeeds) ->
            val total = demandNeeds.values.sum()
            val lots = (data["supply"] ?: emptyList())
                .filter { s -> s["product_id"]?.toString()?.trim() == sk.productId && s["location_id"]?.toString()?.trim() == sk.locationId }
            val supplyQty = lots.sumOf { s -> (s["qty"] as? Number)?.toDouble() ?: 0.0 }
            val sorted = demandNeeds.entries.sortedByDescending { it.value }
            // Full dump for critically scarce supplies (supply covers <1% of total request).
            val isCritical = supplyQty > 0 && supplyQty < total * 0.01
            log.info("[supply-guided][request] supply={}@{} supplyQty={} totalRequest={} demands={}  {}={}",
                sk.productId, sk.locationId, supplyQty.toLong(), total.toLong(), demandNeeds.size,
                if (isCritical) "all" else "top",
                (if (isCritical) sorted else sorted.take(8))
                    .joinToString { (d, q) -> "$d:${q.toLong()}" })
            // Per-lot request breakdown for critically scarce supplies.
            if (isCritical) {
                for (lot in lots.sortedByDescending { (it["qty"] as? Number)?.toDouble() ?: 0.0 }) {
                    val sid = (lot["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val lotQty = (lot["qty"] as? Number)?.toDouble() ?: continue
                    val lotDate = parseDate(lot["supply_date"] as? String)
                    val lotDateStr = (lot["supply_date"] as? String)?.take(10) ?: "?"
                    val eligible = demandNeeds.entries
                        .filter { (did, _) -> lotDate == null || demandDates[did] == null || !demandDates[did]!!.isBefore(lotDate) }
                        .sortedByDescending { it.value }
                    log.info("[supply-guided][request-lot] supply={}@{} lot={} date={} qty={} eligible_demands={}  top={}",
                        sk.productId, sk.locationId, sid, lotDateStr, lotQty.toLong(), eligible.size,
                        eligible.take(8).joinToString { (d, q) -> "$d:${q.toLong()}" })
                }
            }
        }

    // ── Step 2: supply allocation (per-lot) ────────────────────────────────────
    // Allocate each inventory lot separately. Demands are eligible for a lot only
    // when request_due_time ≥ lot supply_date, so early lots are reserved for
    // early demands and late lots for late demands (prevents date-blind pooling).
    val supplyTotals      = aggregateSupplies(data["supply"] ?: emptyList())
    val demandPriorities  = extractDemandPriorities(demands)
    val demandQuantities  = extractDemandQuantities(demands)

    // Aggregate allocation kept for the compensation pass.
    var allocations = allocateSupplies(
        matrix           = criticalMatrix,
        supplyTotals     = supplyTotals,
        demandPriorities = demandPriorities,
        mode             = sgConfig.allocationMode,
        demandQuantities = demandQuantities,
    )
    log.info(
        "[supply-guided] initial allocation: {} demand rows, {} cells, mode={}",
        allocations.byRow.size, allocations.cellCount(), sgConfig.allocationMode,
    )
    // Log aggregate allocation for contested supplies (backward-compat view).
    allocations.byColumn.entries
        .filter { it.value.size > 1 }
        .sortedByDescending { e -> e.value.values.sum() }
        .forEach { (sk, demandAllocs) ->
            val total = demandAllocs.values.sum()
            val sortedAllocs = demandAllocs.entries.sortedByDescending { it.value }
            val isCritical = sortedAllocs.lastOrNull()?.value?.let { it < 1.0 } ?: false
            log.info("[supply-guided][allocation] supply={}@{} totalAllocated={} demands={}  {}={}",
                sk.productId, sk.locationId, total.toLong(), demandAllocs.size,
                if (isCritical) "all" else "top",
                (if (isCritical) sortedAllocs else sortedAllocs.take(8))
                    .joinToString { (d, q) -> "$d:${q.toLong()}" })
        }

    // Per-lot budgets for Loop 2 — date-filtered so each lot is only consumable
    // by demands whose need date is on or after the lot's supply_date.
    val perLotBudgets = allocateSuppliesPerLot(
        matrix           = criticalMatrix,
        supplies         = data["supply"] ?: emptyList(),
        demandPriorities = demandPriorities,
        mode             = sgConfig.allocationMode,
        demandDates      = demandDates,
        demandQuantities = demandQuantities,
    )
    // Log per-lot allocation for critically scarce supplies.
    val aggKeySet = criticalMatrix.byColumn.keys.map { it.toString() }.toSet()
    // Invert: lotKey → demand → qty
    val lotAllocByLot = mutableMapOf<String, MutableMap<Any?, Double>>()
    for ((demandId, budgetMap) in perLotBudgets) {
        for ((key, qty) in budgetMap) {
            if (key in aggKeySet) continue  // skip aggregate "pid|lid" entries
            lotAllocByLot.getOrPut(key) { mutableMapOf() }[demandId] = qty
        }
    }
    for ((lotKey, demandAllocs) in lotAllocByLot.entries.sortedByDescending { it.value.values.sum() }) {
        val pipeIdx = lotKey.indexOf('|')
        val pipeIdx2 = if (pipeIdx >= 0) lotKey.indexOf('|', pipeIdx + 1) else -1
        if (pipeIdx < 0 || pipeIdx2 < 0) continue
        val pid = lotKey.substring(0, pipeIdx)
        val lid = lotKey.substring(pipeIdx + 1, pipeIdx2)
        val sid = lotKey.substring(pipeIdx2 + 1)
        val sk = SupplyKey(pid, lid)
        val totalRequest = criticalMatrix.byColumn[sk]?.values?.sum() ?: continue
        val totalSupply = supplyTotals[sk] ?: continue
        if (totalSupply <= 0 || totalSupply >= totalRequest * 0.01) continue  // only critical
        // Show all competing demands (including date-blocked ones that received 0) sorted by request qty.
        val allCompeting = criticalMatrix.byColumn[sk]?.entries?.sortedByDescending { it.value } ?: emptyList()
        log.info("[supply-guided][allocation-lot] supply={}@{} lot={} allocated={} eligible={} of {} demands  all={}",
            pid, lid, sid, demandAllocs.values.sum().toLong(), demandAllocs.size, allCompeting.size,
            allCompeting.joinToString { (d, _) -> "$d:${(demandAllocs[d] ?: 0.0).toLong()}" })
    }

    // ── Loop 2: commitment with per-lot budget caps ────────────────────────────
    // plan() consumes inventory up to per-lot caps, emits WOs for residual.
    val commitResult = legacyCommit(
        demands          = demands,
        inventory        = inventory,
        data             = data,
        config           = config,
        overrideIndex    = overrideIndex,
        useTaggedLookup  = false,
        progressCallback = progressCallback,
        budgets          = perLotBudgets,
    )

    // ── Trace: request → allocation → pegging per lot ──────────────────────────
    // For critically scarce supplies, show the full pipeline per lot: how much each
    // demand requested, was budgeted, and actually consumed after Loop 2.
    val lotPeggedByLot = extractLotDrawsFromPegging(commitResult.planningPegging)
    for ((lotKey, allocDemands) in lotAllocByLot.entries.sortedByDescending { it.value.values.sum() }) {
        val pipeIdx  = lotKey.indexOf('|')
        val pipeIdx2 = if (pipeIdx >= 0) lotKey.indexOf('|', pipeIdx + 1) else -1
        if (pipeIdx < 0 || pipeIdx2 < 0) continue
        val pid = lotKey.substring(0, pipeIdx)
        val lid = lotKey.substring(pipeIdx + 1, pipeIdx2)
        val sid = lotKey.substring(pipeIdx2 + 1)
        val sk  = SupplyKey(pid, lid)
        val totalRequest = criticalMatrix.byColumn[sk]?.values?.sum() ?: continue
        val totalSupply  = supplyTotals[sk] ?: continue
        if (totalSupply <= 0 || totalSupply >= totalRequest * 0.01) continue  // critical only
        val lotInfo   = (data["supply"] ?: emptyList()).firstOrNull {
            it["supply_id"]?.toString()?.trim() == sid && it["product_id"]?.toString()?.trim() == pid
        }
        val lotQty    = (lotInfo?.get("qty") as? Number)?.toDouble() ?: 0.0
        val lotDateStr = (lotInfo?.get("supply_date") as? String)?.take(10) ?: "?"
        val requestNeeds   = criticalMatrix.byColumn[sk] ?: emptyMap<Any?, Double>()
        val peggedByDemand = lotPeggedByLot[sk]?.get(sid) ?: emptyMap<Any?, Double>()
        // All competing demands for this supply (sorted by request qty, includes date-blocked ones).
        val sortedDemands = requestNeeds.entries.sortedByDescending { it.value }.map { it.key }
        val lines = sortedDemands.mapNotNull { did: Any? ->
            val req    = requestNeeds[did]   ?: 0.0
            val alloc  = allocDemands[did]   ?: 0.0
            val pegged = peggedByDemand[did] ?: 0.0
            if (req < 1e-9 && alloc < 1e-9 && pegged < 1e-9) null
            else "$did:${req.toLong()}->${alloc.toLong()}->${pegged.toLong()}"
        }
        log.info("[supply-guided][trace-lot] supply={}@{} lot={} date={} lotQty={}  all={}",
            pid, lid, sid, lotDateStr, lotQty.toLong(), lines.joinToString(", "))
    }

    // ── Step 3c: GC unused budgets via compensation passes ─────────────────────
    // Extract what each demand actually drew from each supply-bearing node,
    // then redistribute the undrawn slack to cap-bound demands.
    // Note: full convergence (re-running Loop 2 with updated caps) is deferred;
    // these passes are telemetry + single-pass redistribution only.
    if (sgConfig.maxCompensationPasses > 0) {
        val actualDraws = extractActualDrawsFromPegging(commitResult.planningPegging)
        var changed = false
        repeat(sgConfig.maxCompensationPasses) { pass ->
            val cr = compensate(allocations, actualDraws, criticalMatrix, demandPriorities, sgConfig.allocationMode)
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
                val m = result.getOrPut(demandId) { mutableMapOf() }
                val sk = SupplyKey(pid, lid)
                m[sk] = (m[sk] ?: 0.0) + qty
            }
        }

        val nextId = if (type == "demand") node["demand_id"] else demandId
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it, nextId) }
    }

    for (entry in pegging) {
        val demandId = entry["demand_id"]
        @Suppress("UNCHECKED_CAST")
        val tree     = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, demandId)
    }
    return result
}

/**
 * Walk committed pegging trees and collect per-lot (supply_id) actual draws per demand.
 *
 * Returns SupplyKey → supplyId → demandId → qty consumed.
 * Failed work_order subtrees are skipped (they were rolled back, no inventory consumed).
 */
private fun extractLotDrawsFromPegging(
    pegging: List<Map<String, Any?>>,
): Map<SupplyKey, Map<String, Map<Any?, Double>>> {
    val result = mutableMapOf<SupplyKey, MutableMap<String, MutableMap<Any?, Double>>>()

    @Suppress("UNCHECKED_CAST")
    fun walk(node: Map<String, Any?>, demandId: Any?) {
        val type = node["type"] as? String
        if (type == "work_order" && node["failed"] == true) return

        if (type == "supply") {
            val pid = (node["product_id"] as? String)?.trim() ?: return
            val lid = (node["location_id"] as? String)?.trim() ?: return
            val sid = (node["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return
            val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 1e-12) {
                val sk = SupplyKey(pid, lid)
                result.getOrPut(sk) { mutableMapOf() }
                    .getOrPut(sid) { mutableMapOf() }
                    .merge(demandId, qty, Double::plus)
            }
        }

        val nextId = if (type == "demand") node["demand_id"] else demandId
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it, nextId) }
    }

    for (entry in pegging) {
        val demandId = entry["demand_id"]
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, demandId)
    }
    return result
}

private fun filterToCritical(matrix: NeedsMatrix, purchasable: Set<String>): NeedsMatrix {
    val newByColumn = matrix.byColumn.filterKeys { it.productId !in purchasable }
    if (newByColumn.isEmpty()) return NeedsMatrix(emptyMap(), emptyMap())
    val newByRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()
    for ((sk, demandNeeds) in newByColumn) {
        for ((demandId, qty) in demandNeeds) {
            newByRow.getOrPut(demandId) { mutableMapOf() }[sk] = qty
        }
    }
    return NeedsMatrix(newByRow, newByColumn)
}
