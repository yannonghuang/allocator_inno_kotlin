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
    /** Enable post-planning lot-draw trace + compensation telemetry. Off by default — can OOM on large runs. */
    val traceLots: Boolean = false,
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
    val traceLots = m["trace_lots"] as? Boolean ?: false
    return SupplyGuidedConfig(enabled, allocationMode, maxCompensationPasses, traceLots)
}

// ── Allocation result ──────────────────────────────────────────────────────────

/**
 * Output of [buildSupplyAllocation] — the pre-computed per-lot budget caps to
 * hand to [legacyCommit], plus diagnostic context for post-planning tracing.
 */
internal data class SupplyAllocationResult(
    /** Per-demand, per-lot budget caps: demandId → lotKey → qty.  Passed directly to legacyCommit. */
    val perLotBudgets: Map<Any?, MutableMap<String, Double>>,
    // ── BOM graph (reused by computeAchievableQtyMaps to avoid rebuilding) ────
    val graph: BomGraph,
    // ── diagnostic context (needed by logSupplyGuidedTrace) ──────────────────
    val criticalMatrix: NeedsMatrix,
    val allocations: SupplyAllocations,
    val supplyTotals: Map<SupplyKey, Double>,
    val lotAllocByLot: Map<String, Map<Any?, Double>>,
    val demandPriorities: Map<Any?, Int>,
    val sgConfig: SupplyGuidedConfig,
)

// ── Allocation ─────────────────────────────────────────────────────────────────

/**
 * Pure allocation step: BOM reachability walk + proportional supply split.
 * Does NOT touch inventory — no side effects on the supply pool.
 *
 * Produces the per-lot budget caps ([SupplyAllocationResult.perLotBudgets]) that
 * [legacyCommit] uses to enforce each demand's entitlement during planning.
 *
 * @param demands all demands competing for shared supply
 * @param data    BOM, methods, supply tables (read-only)
 * @param config  planning config (supply_guided sub-key, purchasable_materials, …)
 */
internal fun buildSupplyAllocation(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): SupplyAllocationResult {
    val sgConfig = parseSupplyGuidedConfig(config)

    // Step 1 — BOM reachability: for each demand find every supply it can reach.
    val graph         = buildBomGraph(demands, data)
    val requestMatrix = buildReachabilityMatrix(demands, graph)
    log.info(
        "[supply-guided] request map built: {} demand rows, {} supply columns, {} cells",
        requestMatrix.byRow.size, requestMatrix.byColumn.size, requestMatrix.cellCount(),
    )
    val unmappedDemands = demands.filter { it["demand_id"] !in requestMatrix.byRow }
    if (unmappedDemands.isNotEmpty()) {
        log.warn(
            "[supply-guided] {} demand(s) not in request map (no reachable supply path or qty<=0): {}",
            unmappedDemands.size,
            unmappedDemands.joinToString { d -> "${d["demand_id"]}(qty=${d["quantity"]})" },
        )
    }

    // Critical-materials filter:
    //   Step A — restrict to the "Purchasable raw materials" candidate set: products that have
    //            method_buy AND prod_area='raw' in productlocation.  This is exactly the set
    //            that appears in the UI selection list.  Make-only, WIP, and OB items are
    //            excluded — they are not purchasable candidates.
    //   Step B — from those candidates, keep only the UNSELECTED (unchecked) ones: products
    //            NOT in purchasable_materials config.  These are the materials the user has
    //            explicitly NOT allowed purchasing → existing supply is the only source →
    //            proportional allocation is required.
    //   Open-world semantics: absent from allocation map ⇒ uncapped in planning.
    val rawBuyableIds  = buildRawBuyableSet(data)
    val candidateMatrix = filterToProductIds(requestMatrix, rawBuyableIds)
    val purchasable    = effectivePurchasableSet(config, data)
    val criticalMatrix = if (purchasable == null) candidateMatrix
                         else filterToCritical(candidateMatrix, purchasable)
    log.info(
        "[supply-guided] critical matrix: {} supply columns (raw-buyable unselected) of {} raw-buyable of {} total",
        criticalMatrix.byColumn.size, candidateMatrix.byColumn.size, requestMatrix.byColumn.size,
    )

    // Step 2 — supply allocation: split each lot proportionally among competing demands.
    val supplyTotals     = aggregateSupplies(data["supply"] ?: emptyList())
    val demandPriorities = extractDemandPriorities(demands)
    val demandQuantities = extractDemandQuantities(demands)
    val demandDates: Map<Any?, LocalDate?> = demands.associate { d ->
        d["demand_id"] to parseDate(d["request_due_time"] as? String ?: d["request_time"] as? String)
    }

    // Aggregate allocation (kept for compensation-pass diagnostics).
    val allocations = allocateSupplies(
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

    // Log contested critical supplies.
    criticalMatrix.byColumn.entries
        .filter { it.value.size > 1 }
        .sortedByDescending { e -> e.value.values.sum() }
        .forEach { (sk, demandNeeds) ->
            val total     = demandNeeds.values.sum()
            val lots      = (data["supply"] ?: emptyList()).filter { s ->
                s["product_id"]?.toString()?.trim() == sk.productId &&
                s["location_id"]?.toString()?.trim() == sk.locationId
            }
            val supplyQty = lots.sumOf { s -> (s["qty"] as? Number)?.toDouble() ?: 0.0 }
            val sorted    = demandNeeds.entries.sortedByDescending { it.value }
            val isCritical = supplyQty > 0 && supplyQty < total * 0.01
            log.info("[supply-guided][request] supply={}@{} supplyQty={} totalRequest={} demands={}  {}={}",
                sk.productId, sk.locationId, supplyQty.toLong(), total.toLong(), demandNeeds.size,
                if (isCritical) "all" else "top",
                (if (isCritical) sorted else sorted.take(8)).joinToString { (d, q) -> "$d:${q.toLong()}" })
            if (isCritical) {
                for (lot in lots.sortedByDescending { (it["qty"] as? Number)?.toDouble() ?: 0.0 }) {
                    val sid        = (lot["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val lotQty     = (lot["qty"] as? Number)?.toDouble() ?: continue
                    val lotDate    = parseDate(lot["supply_date"] as? String)
                    val lotDateStr = (lot["supply_date"] as? String)?.take(10) ?: "?"
                    val eligible   = demandNeeds.entries
                        .filter { (did, _) ->
                            val dd       = demandDates[did]
                            val eligDate = if (dd != null && dd.dayOfMonth == 1) dd.plusMonths(1).minusDays(1) else dd
                            lotDate == null || eligDate == null || !eligDate.isBefore(lotDate)
                        }
                        .sortedByDescending { it.value }
                    log.info("[supply-guided][request-lot] supply={}@{} lot={} date={} qty={} eligible_demands={}  top={}",
                        sk.productId, sk.locationId, sid, lotDateStr, lotQty.toLong(), eligible.size,
                        eligible.take(8).joinToString { (d, q) -> "$d:${q.toLong()}" })
                }
            }
            // Log aggregate allocation for this supply.
            val demandAllocs  = allocations.byColumn[sk] ?: emptyMap()
            val sortedAllocs  = demandAllocs.entries.sortedByDescending { it.value }
            val allocCritical = sortedAllocs.lastOrNull()?.value?.let { it < 1.0 } ?: false
            log.info("[supply-guided][allocation] supply={}@{} totalAllocated={} demands={}  {}={}",
                sk.productId, sk.locationId, demandAllocs.values.sum().toLong(), demandAllocs.size,
                if (allocCritical) "all" else "top",
                (if (allocCritical) sortedAllocs else sortedAllocs.take(8)).joinToString { (d, q) -> "$d:${q.toLong()}" })
        }

    // Per-lot budgets — date-filtered so each lot is only consumable by demands
    // whose need date is on or after the lot's supply_date.
    val perLotBudgets = allocateSuppliesPerLot(
        matrix           = criticalMatrix,
        supplies         = data["supply"] ?: emptyList(),
        demandPriorities = demandPriorities,
        mode             = sgConfig.allocationMode,
        demandDates      = demandDates,
        demandQuantities = demandQuantities,
    )

    // Build lotKey → demand → qty map for post-planning trace.
    val aggKeySet     = criticalMatrix.byColumn.keys.map { it.toString() }.toSet()
    val lotAllocByLot = mutableMapOf<String, MutableMap<Any?, Double>>()
    for ((demandId, budgetMap) in perLotBudgets) {
        for ((key, qty) in budgetMap) {
            if (key in aggKeySet) continue
            lotAllocByLot.getOrPut(key) { mutableMapOf() }[demandId] = qty
        }
    }
    for ((lotKey, demandAllocs) in lotAllocByLot.entries.sortedByDescending { it.value.values.sum() }) {
        val pipeIdx  = lotKey.indexOf('|')
        val pipeIdx2 = if (pipeIdx >= 0) lotKey.indexOf('|', pipeIdx + 1) else -1
        if (pipeIdx < 0 || pipeIdx2 < 0) continue
        val pid          = lotKey.substring(0, pipeIdx)
        val lid          = lotKey.substring(pipeIdx + 1, pipeIdx2)
        val sid          = lotKey.substring(pipeIdx2 + 1)
        val sk           = SupplyKey(pid, lid)
        val totalRequest = criticalMatrix.byColumn[sk]?.values?.sum() ?: continue
        val totalSupply  = supplyTotals[sk] ?: continue
        if (totalSupply <= 0 || totalSupply >= totalRequest * 0.01) continue
        val allCompeting = criticalMatrix.byColumn[sk]?.entries?.sortedByDescending { it.value } ?: emptyList()
        log.info("[supply-guided][allocation-lot] supply={}@{} lot={} allocated={} eligible={} of {} demands  all={}",
            pid, lid, sid, demandAllocs.values.sum().toLong(), demandAllocs.size, allCompeting.size,
            allCompeting.joinToString { (d, _) -> "$d:${(demandAllocs[d] ?: 0.0).toLong()}" })
    }

    // Diagnostic: per-lot budgets for 160-1153@1000 lots.
    val diagLots = lotAllocByLot.entries.filter { (k, _) -> k.startsWith("160-1153|1000|") }.sortedBy { it.key }
    if (diagLots.isEmpty()) {
        log.info("[supply-guided][diag-160-1153-lots] no lots found in perLotBudgets for 160-1153@1000")
    } else {
        for ((lotKey, demandAllocs) in diagLots) {
            log.info("[supply-guided][diag-160-1153-lots] lot={} demands={} total_alloc={}  allocs={}",
                lotKey.substringAfterLast('|'), demandAllocs.size, demandAllocs.values.sum().toLong(),
                demandAllocs.entries.sortedByDescending { it.value }.joinToString { (d, q) -> "$d:${q.toLong()}" })
        }
    }

    return SupplyAllocationResult(
        perLotBudgets    = perLotBudgets,
        graph            = graph,
        criticalMatrix   = criticalMatrix,
        allocations      = allocations,
        supplyTotals     = supplyTotals,
        lotAllocByLot    = lotAllocByLot,
        demandPriorities = demandPriorities,
        sgConfig         = sgConfig,
    )
}

/**
 * Flatten perLotBudgets to (supplyId, demandId?, qty) triples for DB persistence.
 * Only emits lot-level keys ("pid|lid|sid"); aggregate ("pid|lid") keys are skipped.
 * perLotBudgets is produced from criticalMatrix (non-purchasable materials only),
 * so the result is already restricted to critical materials by construction.
 */
internal fun buildAllocationBudgetRows(perLotBudgets: Map<Any?, MutableMap<String, Double>>): List<Triple<String, String?, Double>> {
    val rows = mutableListOf<Triple<String, String?, Double>>()
    for ((demandId, budgetMap) in perLotBudgets) {
        for ((key, qty) in budgetMap) {
            if (key.count { it == '|' } < 2) continue
            val supplyId = key.substringAfterLast('|')
            if (supplyId.isBlank() || qty <= 1e-12) continue
            rows.add(Triple(supplyId, demandId?.toString(), qty))
        }
    }
    return rows
}

// ── Achievable-quantity pre-computation ────────────────────────────────────────

/**
 * For each demand, compute the maximum fillable quantity at EVERY BOM node given
 * the per-lot budget caps from [allocation].  Returns a per-demand map of
 * (pid to lid) → achievable qty so that [plan] can cap each node before drawing
 * supply — eliminating the WO cascade that fires for quantities the budget can
 * never cover.
 *
 * Traversal rules (per-demand BOM walk):
 *   - Supply leaf: budget-capped if the supply is critical (has lot entries in
 *     perLotBudgets for this demand); uncapped otherwise.
 *   - Make method (variants = OR split, equal share): each variant handles
 *     needed/nVariants; its components are AND-constrained (min over children).
 *     Child qty = (needed/nVariants) * rate (per-variant, not the full-qty
 *     value that variantsForMake returns for the aggregate demand).
 *   - Move method: delegate to the source (pid, from_lid).
 *   - Buy method: unlimited.
 *   - Multiple methods at a node: OR — take the best.
 *
 * Returns demandId → Map<(pid to lid), achievable qty>.
 */
internal fun computeAchievableQtyMaps(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
): Map<Any?, Map<Pair<String, String>, Double>> {
    val result = mutableMapOf<Any?, Map<Pair<String, String>, Double>>()
    for (demand in demands) {
        val demandId = demand["demand_id"] ?: continue
        val pid  = (demand["product_id"] as? String)?.trim() ?: continue
        val lid  = (demand["location_id"] as? String)?.trim() ?: continue
        val qty  = (demand["quantity"]    as? Number)?.toDouble() ?: continue
        if (qty <= 0.0) { result[demandId] = emptyMap(); continue }
        val nodeMap = mutableMapOf<Pair<String, String>, Double>()
        nodeAchievableInto(pid, lid, qty, demandId, allocation, data, mutableSetOf(), nodeMap)
        result[demandId] = nodeMap
        val rootAq = nodeMap[pid to lid] ?: qty
        if (rootAq < qty - 1e-9) {
            log.info("[supply-guided][cap] demand={} qty={} achievable={} ({}) nodes={}",
                demandId, qty.toLong(), rootAq.toLong(), "${"%.1f".format(rootAq * 100.0 / qty)}%", nodeMap.size)
        }
    }
    return result
}

private fun nodeAchievableInto(
    pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, Double>,
): Double {
    if (needed <= 1e-9) return 0.0
    val key = pid to lid
    if (!visited.add(key)) return needed  // cycle guard: assume uncapped

    try {
        val sk            = SupplyKey(pid, lid)
        val demandBudgets = allocation.perLotBudgets[demandId] ?: emptyMap<String, Double>()
        val budgetPrefix  = "$pid|$lid|"

        // Supply at this node: sum lot budgets if critical; treat as uncapped otherwise.
        val supplyContrib: Double = when {
            sk !in allocation.graph.supplyIndex -> 0.0
            demandBudgets.keys.any { it.startsWith(budgetPrefix) } ->
                demandBudgets.entries.filter { (k, _) -> k.startsWith(budgetPrefix) }.sumOf { (_, q) -> q }
            else -> needed  // supply exists but not in critical matrix for this demand → uncapped
        }
        if (supplyContrib >= needed) {
            into[key] = needed
            return needed
        }

        // Methods at this node: OR group — best method wins.
        var methodContrib = 0.0
        for (method in getMethods(pid, lid, data)) {
            val ma = methodAchievableInto(method, pid, lid, needed, demandId, allocation, data, visited, into)
            if (ma > methodContrib) methodContrib = ma
            if (methodContrib >= needed) break
        }

        val aq = minOf(needed, supplyContrib + methodContrib)
        into[key] = aq
        return aq
    } finally {
        visited.remove(key)
    }
}

private fun methodAchievableInto(
    method: Map<String, Any?>, pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, Double>,
): Double {
    return when (method["type"] as? String) {
        "purchase" -> needed  // unlimited
        "move" -> {
            val fromLid = (method["from_location_id"] as? String)?.trim() ?: return 0.0
            nodeAchievableInto(pid, fromLid, needed, demandId, allocation, data, visited, into)
        }
        "make" -> {
            // Variants are OR alternatives with equal-split: each handles needed/nVariants.
            // variantsForMake returns children with qty = needed * rate (full-demand scale).
            // Per-variant child qty = (needed/nVariants) * rate = cNeededFull / nVariants.
            val variants = variantsForMake(pid, lid, needed, method, data)
            if (variants.isEmpty()) return 0.0
            val nVariants = variants.size.toDouble()
            var total = 0.0
            for ((_, children) in variants) {
                val perVariant = needed / nVariants
                if (children.isEmpty()) { total += perVariant; continue }
                // AND: achievable limited by the most-constrained component.
                var variantAchievable = perVariant
                for (child in children) {
                    val cPid        = (child["product_id"] as? String)?.trim() ?: continue
                    val cLid        = (child["location_id"] as? String)?.trim() ?: continue
                    val cNeededFull = (child["quantity"]    as? Number)?.toDouble() ?: continue
                    // Divide by nVariants: variantsForMake scales qty by full `needed`, but
                    // each variant only handles needed/nVariants of the parent demand.
                    val cNeeded = cNeededFull / nVariants  // = perVariant * rate
                    if (cNeeded <= 1e-9) continue
                    val cAch      = nodeAchievableInto(cPid, cLid, cNeeded, demandId, allocation, data, visited, into)
                    // Convert child achievable back to parent units: cAch / rate = cAch / (cNeeded/perVariant)
                    val fromChild = cAch / (cNeeded / perVariant)
                    if (fromChild < variantAchievable) variantAchievable = fromChild
                }
                total += variantAchievable
            }
            total
        }
        else -> 0.0
    }
}

// ── Plan blueprint (sketch-based two-phase planning) ───────────────────────────

/**
 * Per-node result of the sketch phase.
 * Captures the achievable quantity AND the pre-selected BOM method so the commit
 * phase can bypass [getPreferredMethodCascade] — reducing per-node overhead from
 * O(methods × probeChildren) to O(1) at every BOM depth during planning.
 */
data class NodeBlueprint(
    /** Maximum qty this node can satisfy given its per-lot budget caps. */
    val achievable: Double,
    /** Supply portion of achievable (drawn from this node's inventory budget). */
    val supplyQty: Double = 0.0,
    /**
     * First feasible BOM method (by ascending preference int) for the residual.
     * null = supply fully covers demand (no method needed).
     */
    val method: Map<String, Any?>? = null,
)

typealias DemandBlueprint = Map<Pair<String, String>, NodeBlueprint>
typealias PlanBlueprint   = Map<Any?, DemandBlueprint>

/**
 * Sketch phase: one read-only BOM walk per demand that:
 *  1. Computes achievable quantity at every node (same logic as [computeAchievableQtyMaps]).
 *  2. Records the FIRST feasible BOM method by preference so the commit phase can
 *     skip [getPreferredMethodCascade] entirely (no probeChildren calls per node).
 *
 * Supersedes [computeAchievableQtyMaps] in the supply-guided pipeline.
 */
internal fun computePlanBlueprint(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
): PlanBlueprint {
    val result = mutableMapOf<Any?, DemandBlueprint>()
    for (demand in demands) {
        val demandId = demand["demand_id"] ?: continue
        val pid  = (demand["product_id"] as? String)?.trim() ?: continue
        val lid  = (demand["location_id"] as? String)?.trim() ?: continue
        val qty  = (demand["quantity"]    as? Number)?.toDouble() ?: continue
        if (qty <= 0.0) { result[demandId] = emptyMap(); continue }
        val nodeMap = mutableMapOf<Pair<String, String>, NodeBlueprint>()
        nodeSketchInto(pid, lid, qty, demandId, allocation, data, mutableSetOf(), nodeMap)
        result[demandId] = nodeMap
        val rootAq = nodeMap[pid to lid]?.achievable ?: qty
        if (rootAq < qty - 1e-9) {
            log.info("[supply-guided][blueprint-cap] demand={} qty={} achievable={} ({}) nodes={}",
                demandId, qty.toLong(), rootAq.toLong(),
                "${"%.1f".format(rootAq * 100.0 / qty)}%", nodeMap.size)
        }
    }
    return result
}

private fun nodeSketchInto(
    pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, NodeBlueprint>,
): Double {
    if (needed <= 1e-9) return 0.0
    val key = pid to lid
    if (!visited.add(key)) return needed  // cycle guard: assume uncapped
    try {
        val sk            = SupplyKey(pid, lid)
        val demandBudgets = allocation.perLotBudgets[demandId] ?: emptyMap<String, Double>()
        val budgetPrefix  = "$pid|$lid|"

        val supplyQty: Double = when {
            sk !in allocation.graph.supplyIndex -> 0.0
            demandBudgets.keys.any { it.startsWith(budgetPrefix) } ->
                demandBudgets.entries.filter { (k, _) -> k.startsWith(budgetPrefix) }.sumOf { (_, q) -> q }
            else -> needed
        }
        if (supplyQty >= needed) {
            into[key] = NodeBlueprint(achievable = needed, supplyQty = needed)
            return needed
        }

        val residual = needed - supplyQty

        // First feasible method by preference (ascending int = higher priority).
        // Takes the first method with achievable > 0 — no backtracking in commit phase.
        val methods = getMethods(pid, lid, data)
            .sortedBy { (it["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }

        var selectedMethod: Map<String, Any?>? = null
        var selectedAchievable = 0.0

        for (method in methods) {
            val ma = methodAchievableForSketch(method, pid, lid, residual, demandId, allocation, data, visited, into)
            if (ma > 1e-9) {
                selectedMethod = method
                selectedAchievable = ma
                break
            }
        }

        val aq = supplyQty + selectedAchievable
        into[key] = NodeBlueprint(achievable = aq, supplyQty = supplyQty, method = selectedMethod)
        return aq
    } finally {
        visited.remove(key)
    }
}

// Same arithmetic as methodAchievableInto but recurses via nodeSketchInto to populate blueprint entries.
private fun methodAchievableForSketch(
    method: Map<String, Any?>, pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, NodeBlueprint>,
): Double {
    return when (method["type"] as? String) {
        "purchase" -> needed
        "move" -> {
            val fromLid = (method["from_location_id"] as? String)?.trim() ?: return 0.0
            nodeSketchInto(pid, fromLid, needed, demandId, allocation, data, visited, into)
        }
        "make" -> {
            val variants = variantsForMake(pid, lid, needed, method, data)
            if (variants.isEmpty()) return 0.0
            val nVariants = variants.size.toDouble()
            var total = 0.0
            for ((_, children) in variants) {
                val perVariant = needed / nVariants
                if (children.isEmpty()) { total += perVariant; continue }
                var variantAchievable = perVariant
                for (child in children) {
                    val cPid        = (child["product_id"] as? String)?.trim() ?: continue
                    val cLid        = (child["location_id"] as? String)?.trim() ?: continue
                    val cNeededFull = (child["quantity"]    as? Number)?.toDouble() ?: continue
                    val cNeeded = cNeededFull / nVariants
                    if (cNeeded <= 1e-9) continue
                    val cAch      = nodeSketchInto(cPid, cLid, cNeeded, demandId, allocation, data, visited, into)
                    val fromChild = cAch / (cNeeded / perVariant)
                    if (fromChild < variantAchievable) variantAchievable = fromChild
                }
                total += variantAchievable
            }
            total
        }
        else -> 0.0
    }
}

// ── Post-planning trace ─────────────────────────────────────────────────────────

/**
 * Logs the request → allocation → pegging pipeline for critically scarce supplies,
 * and runs the compensation-pass telemetry (budget GC diagnostics).
 * Called after [legacyCommit] completes, with the committed pegging trees.
 */
internal fun logSupplyGuidedTrace(
    allocation: SupplyAllocationResult,
    pegging: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
) {
    val lotPeggedByLot = extractLotDrawsFromPegging(pegging)
    for ((lotKey, allocDemands) in allocation.lotAllocByLot.entries.sortedByDescending { it.value.values.sum() }) {
        val pipeIdx  = lotKey.indexOf('|')
        val pipeIdx2 = if (pipeIdx >= 0) lotKey.indexOf('|', pipeIdx + 1) else -1
        if (pipeIdx < 0 || pipeIdx2 < 0) continue
        val pid          = lotKey.substring(0, pipeIdx)
        val lid          = lotKey.substring(pipeIdx + 1, pipeIdx2)
        val sid          = lotKey.substring(pipeIdx2 + 1)
        val sk           = SupplyKey(pid, lid)
        val totalRequest = allocation.criticalMatrix.byColumn[sk]?.values?.sum() ?: continue
        val totalSupply  = allocation.supplyTotals[sk] ?: continue
        if (totalSupply <= 0 || totalSupply >= totalRequest * 0.01) continue
        val lotInfo        = (data["supply"] ?: emptyList()).firstOrNull {
            it["supply_id"]?.toString()?.trim() == sid && it["product_id"]?.toString()?.trim() == pid
        }
        val lotQty         = (lotInfo?.get("qty") as? Number)?.toDouble() ?: 0.0
        val lotDateStr     = (lotInfo?.get("supply_date") as? String)?.take(10) ?: "?"
        val requestNeeds   = allocation.criticalMatrix.byColumn[sk] ?: emptyMap()
        val peggedByDemand = lotPeggedByLot[sk]?.get(sid) ?: emptyMap()
        val lines = requestNeeds.entries.sortedByDescending { it.value }.mapNotNull { (did, _) ->
            val req    = requestNeeds[did]      ?: 0.0
            val alloc  = allocDemands[did]      ?: 0.0
            val pegged = peggedByDemand[did]    ?: 0.0
            if (req < 1e-9 && alloc < 1e-9 && pegged < 1e-9) null
            else "$did:${req.toLong()}->${alloc.toLong()}->${pegged.toLong()}"
        }
        log.info("[supply-guided][trace-lot] supply={}@{} lot={} date={} lotQty={}  all={}",
            pid, lid, sid, lotDateStr, lotQty.toLong(), lines.joinToString(", "))
    }

    // Compensation-pass telemetry (re-planning with updated caps is deferred; this is diagnostics only).
    if (allocation.sgConfig.maxCompensationPasses > 0) {
        val actualDraws = extractActualDrawsFromPegging(pegging)
        var allocations = allocation.allocations
        var changed = false
        repeat(allocation.sgConfig.maxCompensationPasses) { pass ->
            val cr = compensate(allocations, actualDraws, allocation.criticalMatrix,
                                allocation.demandPriorities, allocation.sgConfig.allocationMode)
            if (!cr.redistributed) return@repeat
            log.info("[supply-guided] compensation pass {}: supplies={} redistributed={:.2f} absorbed={:.2f}",
                pass + 1, cr.supplyCount, cr.qtyRedistributed, cr.qtyAbsorbed)
            allocations = cr.allocations
            changed = true
        }
        if (changed) log.info("[supply-guided] compensation complete; re-plan with updated caps is TODO (convergence loop)")
    }
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
                    .merge(demandId, qty) { a, b -> a + b }
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

/** Returns the "Purchasable raw materials" candidate set: products with method_buy AND
 *  prod_area='raw' in productlocation — the exact set shown in the UI selection list.
 *  Delegates to [partitionBuyables] — same classification used by [effectivePurchasableSet]. */
private fun buildRawBuyableSet(data: Map<String, List<Map<String, Any?>>>): Set<String> =
    partitionBuyables(data).first

/** Keeps only supply columns whose productId is in [productIds]; rebuilds byRow accordingly. */
private fun filterToProductIds(matrix: NeedsMatrix, productIds: Set<String>): NeedsMatrix {
    val newByColumn = matrix.byColumn.filterKeys { it.productId in productIds }
    if (newByColumn.isEmpty()) return NeedsMatrix(emptyMap(), emptyMap())
    val newByRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()
    for ((sk, demandNeeds) in newByColumn) {
        for ((demandId, qty) in demandNeeds) {
            newByRow.getOrPut(demandId) { mutableMapOf() }[sk] = qty
        }
    }
    return NeedsMatrix(newByRow, newByColumn)
}
