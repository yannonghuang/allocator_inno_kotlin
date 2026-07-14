package com.allocator.services

import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.math.min

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

    // Critical-materials identification (done before BOM walk so the walk can prune early):
    //   Step A — restrict to the "Purchasable raw materials" candidate set: products that have
    //            method_buy AND prod_area='raw' in productlocation.  This is exactly the set
    //            that appears in the UI selection list.  Make-only, WIP, and OB items are
    //            excluded — they are not purchasable candidates.
    //   Step B — from those candidates, keep only the UNSELECTED (unchecked) ones: products
    //            NOT in purchasable_materials config.  These are the materials the user has
    //            explicitly NOT allowed purchasing → existing supply is the only source →
    //            proportional allocation is required.
    //   Open-world semantics: absent from allocation map ⇒ uncapped in planning.
    val rawBuyableIds = buildRawBuyableSet(data)
    val purchasable   = effectivePurchasableSet(config, data)
    // criticalPids: raw-buyable products NOT in purchasable_materials → must be allocated.
    // null purchasable means no whitelist → all raw-buyable are critical.
    val criticalPids: Set<String>? = if (purchasable == null) null else rawBuyableIds - purchasable

    // Step 1 — BOM reachability: build full graph (for unmapped-demand check), then build
    // the critical-only matrix in one pass by pruning non-critical supply leaves during walk.
    val graph         = buildBomGraph(demands, data)
    val requestMatrix = buildReachabilityMatrix(demands, graph)   // full — used only for unmapped check
    val unmappedDemands = demands.filter { it["demand_id"] !in requestMatrix.byRow }
    if (unmappedDemands.isNotEmpty()) {
        log.warn(
            "[supply-guided] {} demand(s) not in request map (no reachable supply path or qty<=0): {}",
            unmappedDemands.size,
            unmappedDemands.joinToString { d -> "${d["demand_id"]}(qty=${d["quantity"]})" },
        )
    }
    val criticalMatrix = buildReachabilityMatrix(demands, graph, criticalPids = criticalPids)
    log.info(
        "[supply-guided] critical matrix: {} supply columns (raw-buyable unselected) of {} raw-buyable of {} total",
        criticalMatrix.byColumn.size, rawBuyableIds.size, requestMatrix.byColumn.size,
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
 * phase can bypass the unified waterfall's candidate expansion/ranking entirely —
 * reducing per-node overhead to O(1) at every BOM depth during planning.
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
    /**
     * Root-cause pointer(s) for why [achievable] < what was asked of this node, computed
     * INLINE alongside the same min (AND)/waterfall (OR) arithmetic that produces [achievable]
     * itself — not a separate reconstruction pass. Empty when this node was fully satisfied.
     * See [nodeSketchInto] / [methodAchievableForSketch] for exactly where each entry comes from.
     */
    val quantityDominator: List<DominatorRef> = emptyList(),
)

typealias DemandBlueprint = Map<Pair<String, String>, NodeBlueprint>
typealias PlanBlueprint   = Map<Any?, DemandBlueprint>

/**
 * Sketch phase: one read-only BOM walk per demand that:
 *  1. Computes achievable quantity at every node (same logic as [computeAchievableQtyMaps]).
 *  2. Records the FIRST feasible BOM method by preference so the commit phase can
 *     skip the unified waterfall's candidate expansion/ranking entirely.
 *
 * Supersedes [computeAchievableQtyMaps] in the supply-guided pipeline.
 */
internal fun computePlanBlueprint(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    /** Optional Preferences KB override, see [plan]'s `preferenceKb` param. `null` preserves
     *  today's exact raw-preference behavior. */
    preferenceKb: PreferenceKb? = null,
): PlanBlueprint {
    val result = mutableMapOf<Any?, DemandBlueprint>()
    for (demand in demands) {
        val demandId = demand["demand_id"] ?: continue
        val pid  = (demand["product_id"] as? String)?.trim() ?: continue
        val lid  = (demand["location_id"] as? String)?.trim() ?: continue
        val qty  = (demand["quantity"]    as? Number)?.toDouble() ?: continue
        if (qty <= 0.0) { result[demandId] = emptyMap(); continue }
        val nodeMap = mutableMapOf<Pair<String, String>, NodeBlueprint>()
        nodeSketchInto(pid, lid, qty, demandId, allocation, data, mutableSetOf(), nodeMap, preferenceKb)
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

/** Every physical lot THIS DEMAND actually has entitlement to at (pid, lid), per its own
 *  [demandBudgets] slice of perLotBudgets — NOT every physical lot that exists for the
 *  product@location, which perLotBudgets deliberately fragments across ALL demands sharing a
 *  critical material (a demand's own entitlement is only ever a subset). Used only as the
 *  terminal cause when this exact (pid, lid) has no method able to relieve it — a genuine
 *  raw-supply bottleneck, not a (product, location) proxy, and not a listing of lots this demand
 *  was never entitled to draw from in the first place. When this demand has no entitlement here
 *  at all, self-identifies as the sole terminal cause with no supply_id — mirrors
 *  `rawDominatorRefs`'s "no supply method" terminal case in PlanningEngine.kt for consistency
 *  across the sketch-phase / live-commit boundary. */
private fun rawSupplyLotRefs(pid: String, lid: String, demandBudgets: Map<String, Double>): List<DominatorRef> {
    val prefix = "$pid|$lid|"
    val ownLots = demandBudgets.keys.filter { it.startsWith(prefix) }
    if (ownLots.isEmpty()) {
        return listOf(DominatorRef(kind = "bom_child", productId = pid, locationId = lid, label = "$pid@$lid (no supply)"))
    }
    return ownLots.mapNotNull { k ->
        val sid = k.removePrefix(prefix).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DominatorRef(kind = "bom_child", productId = pid, locationId = lid, supplyId = sid, label = "$pid@$lid ($sid)")
    }
}

private fun nodeSketchInto(
    pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, NodeBlueprint>,
    preferenceKb: PreferenceKb? = null,
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

        // First feasible method by preference (ascending int = higher priority) — the
        // Preferences KB overrides this ranking per-alternative when present (see
        // kbPreferenceForMethod), falling back to raw CSV preference otherwise.
        // Takes the first method with achievable > 0 — no backtracking in commit phase.
        val methods = getMethods(pid, lid, data)
            .sortedBy { kbPreferenceForMethod(pid, lid, it, preferenceKb, data) }

        var selectedMethod: Map<String, Any?>? = null
        var selectedAchievable = 0.0
        var selectedDominator: List<DominatorRef> = emptyList()

        for (method in methods) {
            val ms = methodAchievableForSketch(method, pid, lid, residual, demandId, allocation, data, visited, into, preferenceKb)
            if (ms.achievable > 1e-9) {
                selectedMethod = method
                selectedAchievable = ms.achievable
                selectedDominator = ms.dominator
                break
            }
        }

        val aq = supplyQty + selectedAchievable
        // Genuine shortfall at THIS node: either the chosen method itself fell short of the
        // residual it was asked for (inherit its own dominator — already the correct AND/OR
        // result from the recursion above, computed alongside its own achievable qty), or no
        // method exists/contributed anything at all, in which case the terminal cause IS this
        // (pid, lid)'s own raw supply — point at its actual physical lot(s) directly.
        val quantityDominator = if (aq < needed - 1e-9) {
            if (selectedMethod != null && selectedDominator.isNotEmpty()) selectedDominator
            else rawSupplyLotRefs(pid, lid, demandBudgets)
        } else emptyList()
        into[key] = NodeBlueprint(achievable = aq, supplyQty = supplyQty, method = selectedMethod, quantityDominator = quantityDominator)
        return aq
    } finally {
        visited.remove(key)
    }
}

/**
 * Preferences KB lookup for a "raw" (unsplit-by-alt_group) method as seen by the sketch
 * phase: for `move`/`purchase`, a direct [kbPreference] lookup (no alt_group concept); for
 * `make` with multiple BOM alt_groups, the MIN KB preference across that method's alt_groups
 * (its best-case rank) — so this method-level pre-selection stays consistent with the
 * finer-grained alt_group waterfall the commit phase runs afterward. Falls back to raw CSV
 * `preference` exactly like today when [preferenceKb] is null or has no entries for this node.
 */
internal fun kbPreferenceForMethod(
    pid: String, lid: String,
    method: Map<String, Any?>,
    preferenceKb: PreferenceKb?,
    data: Map<String, List<Map<String, Any?>>>,
): Int {
    if (preferenceKb == null) return (method["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE
    if (method["type"] == "make") {
        val variants = variantsForMake(pid, lid, 1.0, method, data)
        if (variants.size > 1) {
            return variants.minOf { (altKey, _) -> kbPreference(pid, lid, method, altKey, preferenceKb) }
        }
    }
    return kbPreference(pid, lid, method, null, preferenceKb)
}

/** Paired with [NodeBlueprint.quantityDominator]: the achievable qty AND, computed in the
 *  same breath, who's to blame if it fell short of what was asked of this method. */
private data class MethodSketchResult(val achievable: Double, val dominator: List<DominatorRef> = emptyList())

// Same arithmetic as methodAchievableInto but recurses via nodeSketchInto to populate blueprint entries.
private fun methodAchievableForSketch(
    method: Map<String, Any?>, pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, NodeBlueprint>,
    preferenceKb: PreferenceKb? = null,
): MethodSketchResult {
    return when (method["type"] as? String) {
        // Elastic on both quantity and time (can always order more / sooner) — never itself
        // a dominator, matching the "any purchase is a consequence, never a cause" rule.
        "purchase" -> MethodSketchResult(needed)
        "move" -> {
            val fromLid = (method["from_location_id"] as? String)?.trim() ?: return MethodSketchResult(0.0)
            val ach = nodeSketchInto(pid, fromLid, needed, demandId, allocation, data, visited, into, preferenceKb)
            // Transparent 1:1 pass-through — move adds no constraint of its own, so it inherits
            // the source location's own already-computed dominator verbatim (whatever that is).
            MethodSketchResult(ach, into[pid to fromLid]?.quantityDominator ?: emptyList())
        }
        "make" -> {
            val variants = variantsForMake(pid, lid, needed, method, data)
            if (variants.isEmpty()) return MethodSketchResult(0.0)
            // Waterfall across alt_group variants — mirrors the commit phase's unified
            // waterfall (best variant by preference/KB ranking gets the full residual; only
            // spill to the next if it can't fully cover). Replaces the old equal-split-
            // across-all-variants estimate, which diluted `needed` by 1/nVariants regardless
            // of which variant was actually best — systematically under-predicting
            // achievable qty for a KB/preference-favored variant and, since this feeds
            // nodeQtyCaps, artificially capping the commit phase below what its own
            // alt_group-aware waterfall could otherwise draw.
            val ranked = variants.sortedBy { (altKey, _) -> kbPreference(pid, lid, method, altKey, preferenceKb) }
            var residual = needed
            var total = 0.0
            // OR-group: at most one dominator per alt_group variant actually tried, unioned
            // (deduped) only from variants that fell short of what THEY were asked — mirrors
            // exactly which variants the waterfall below actually spilled past.
            var dominatorUnion: List<DominatorRef> = emptyList()
            for ((_, children) in ranked) {
                if (residual <= 1e-9) break
                val askedOfThisVariant = residual
                if (children.isEmpty()) { total += residual; residual = 0.0; continue }
                var variantAchievable = residual
                // AND: exactly one child — whichever pulls variantAchievable down the most —
                // dominates this variant. Ties keep whichever was found first (min tracking
                // itself only ever holds one winner at a time).
                var variantDominator: List<DominatorRef> = emptyList()
                for (child in children) {
                    val cPid        = (child["product_id"] as? String)?.trim() ?: continue
                    val cLid        = (child["location_id"] as? String)?.trim() ?: continue
                    // variantsForMake scaled each child's "quantity" by the full `needed` qty
                    // (rate * needed) — rescale to this iteration's residual share.
                    val cNeededFull = (child["quantity"]    as? Number)?.toDouble() ?: continue
                    val cNeeded = if (needed > 1e-9) cNeededFull * (residual / needed) else 0.0
                    if (cNeeded <= 1e-9) continue
                    val cAch      = nodeSketchInto(cPid, cLid, cNeeded, demandId, allocation, data, visited, into, preferenceKb)
                    val fromChild = cAch / (cNeeded / residual)
                    if (fromChild < variantAchievable) {
                        variantAchievable = fromChild
                        // Inherit the child's own already-computed dominator (recursively
                        // resolved — may itself be an OR-group from further below) rather than
                        // re-deriving it; fall back to a fresh self-reference only if the child
                        // (unexpectedly) didn't carry one despite falling short.
                        variantDominator = into[cPid to cLid]?.quantityDominator?.takeIf { it.isNotEmpty() }
                            ?: listOf(DominatorRef(kind = "bom_child", productId = cPid, locationId = cLid, label = "$cPid@$cLid"))
                    }
                }
                total += variantAchievable
                residual -= variantAchievable
                if (variantAchievable < askedOfThisVariant - 1e-9 && variantDominator.isNotEmpty()) {
                    dominatorUnion = dominatorUnion + variantDominator
                }
            }
            MethodSketchResult(total, dominatorUnion.dedupBySupply())
        }
        else -> MethodSketchResult(0.0)
    }
}

// ── Intra-demand sibling contention ("diamond problem") ─────────────────────────
//
// A demand's own tree can fan out into several SIMULTANEOUSLY-active branches that
// independently reach the same scarce, critical material: AND-required BOM siblings
// under one make method, and root-level candidates.take(cap) (rootSplitWeights,
// PlanningEngine.kt) proportionally splitting the demand's own quantity up front.
// Both share the property that makes gather-then-allocate tractable: the participant
// set is fixed and known BEFORE any of them runs (unlike the ordinary sequential
// waterfall, where whether a second candidate is even tried depends on the first
// one's outcome). The live commit phase (planMethodSlot/plan) explores these
// branches sequentially and greedily, so an early branch can exhaust a shared lot
// before a later, equally-entitled sibling ever gets a look — even when the lot
// would comfortably cover a FAIR split across all of them. This section computes
// that fair split up front, in a separate top-down gather pass over the same
// (already-deterministic, KB-ranked) topology the live commit phase itself walks,
// so the live phase can enforce it as an additional per-branch cap.

/** Identifies one contending branch. AND-siblings are identified by their own
 *  (productId, locationId) alone (distinct BOM children of one AND-parent always have
 *  distinct (pid, lid) in practice). Root-split candidates all share the demand's own
 *  (productId, locationId) — [slot] disambiguates which top-level candidate/method a
 *  branch represents. */
data class BranchKey(
    val productId: String,
    val locationId: String,
    val slot: String? = null,
)

/** One (contended node, branch, critical supply) request discovered by [gatherAndSiblingRequests]. */
internal data class AndSiblingRequest(
    /** The AND-parent's (or root demand's) own (productId, locationId) — groups branches
     *  that are simultaneously active competitors for the same fanout. */
    val cohort: Pair<String, String>,
    val branch: BranchKey,
    val supplyKey: SupplyKey,
    val requestedQty: Double,
)

internal fun slotIdFor(candidate: WaterfallCandidate): String =
    "${candidate.method["type"]}:${candidate.altKey
        ?: (candidate.method["location_id"] ?: candidate.method["to_location_id"] ?: "").toString()}"

/**
 * Lineage: the chain of enclosing branch identities ("pid@lid" segments joined by ">")
 * leading to a fanout. Shared verbatim between [gatherAndSiblingRequests] (which builds
 * it while discovering branches) and PlanningEngine.kt's `plan()`/`planMethodSlot` (which
 * must reconstruct the identical value while walking the live commit, so a branch created
 * mid-tree by two DIFFERENT outer AND-siblings that happen to route through the same
 * shared sub-assembly doesn't collapse to one indistinguishable BranchKey — see
 * [gatherAndSiblingRequests]'s own doc for the full rationale. Both sides MUST use these
 * exact same two functions, or a lookup mismatch silently falls back to "uncapped".
 */
internal fun extendLineage(lineage: String, pid: String, lid: String): String =
    if (lineage.isEmpty()) "$pid@$lid" else "$lineage>$pid@$lid"

internal fun combineSlot(lineage: String, localSlot: String?): String? =
    listOfNotNull(lineage.ifEmpty { null }, localSlot).joinToString("|").ifEmpty { null }

/**
 * Phase 1 — pure top-down request gathering for one demand: assumes infinite upstream
 * supply and deterministic top-choice (KB-ranked) routing at every non-fanout point (the
 * same simplification [nodeSketchInto] and [computeAchievableQtyMaps] already lean on —
 * with a precomputed Preferences KB and a preference-ordered-waterfall/root-split
 * paradigm everywhere else, a pegging tree's shape is already close to deterministic).
 * No availability checks, no capping — just discovers who would ask for what, IF every
 * fanout branch got everything it asked for.
 *
 * Returns every (cohort, branch, criticalSupply, requestedQty) tuple this demand's tree
 * would generate. Cheap to call for demands with no critical reach at all — bails via
 * [SupplyAllocationResult.criticalMatrix]'s already-computed byRow index.
 */
internal fun gatherAndSiblingRequests(
    demand: Map<String, Any?>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    preferenceKb: PreferenceKb?,
    /** This demand's own slice of [computePlanBlueprint]'s sketch-phase output — reused here
     *  (not re-derived) to discount a branch's requested share of a shared critical material by
     *  what the SAME sketch pass already knows that branch can achieve, independent of the
     *  shared-material fight (nodeSketchInto doesn't track cross-branch consumption of a
     *  contended material, so its achievable prediction for any OTHER, non-shared constraint
     *  along the path is exactly the "ignoring this fight" ceiling step (b)'s fair-split is
     *  missing). Already accounts for max_methods/preferences via the same config/preferenceKb
     *  this function's own routing (rankedCandidates/kbPreference) already uses — reusing the
     *  sketch's own achievable value here means this doesn't need its own separate notion of
     *  achievability. `null` (the default) applies no discount, matching prior behavior. */
    demandBlueprint: DemandBlueprint? = null,
): List<AndSiblingRequest> {
    val demandId = demand["demand_id"]
    if (allocation.criticalMatrix.byRow[demandId].isNullOrEmpty()) return emptyList()
    val rootPid = (demand["product_id"] as? String)?.trim() ?: return emptyList()
    val rootLid = (demand["location_id"] as? String)?.trim() ?: return emptyList()
    val rootQty = (demand["quantity"] as? Number)?.toDouble() ?: return emptyList()
    if (rootQty <= 1e-9) return emptyList()

    val methodCfg = resolveMethodSelection(config)
    val out = mutableListOf<AndSiblingRequest>()

    fun rankedCandidates(pid: String, lid: String): List<WaterfallCandidate> {
        val methods = getMethods(pid, lid, data)
        if (methods.isEmpty()) return emptyList()
        return expandWaterfallCandidates(methods, pid, demand, config, data)
            .sortedBy { kbPreference(pid, lid, it.method, it.altKey, preferenceKb) }
    }

    // The top-ranked candidate is sometimes a structural dead end regardless of supply — most
    // commonly a move whose source is the very (pid, lid) frame currently open one level up
    // (e.g. 260-0141-02@2000 move-from-1000 vs 260-0141-02@1000 move-from-2000, a two-location
    // cycle). Under "infinite supply" that candidate would still never terminate, so picking it
    // and stopping (relying on the cycle guard) makes this whole branch silently vanish from the
    // gather pass instead of falling through to the next candidate — exactly what the live
    // commit phase's own cycle-aware waterfall does (a blocked candidate never ends the search).
    // Only a one-hop lookahead: deeper/indirect cycles still terminate via the recursive
    // visited-guard inside accumulate/discover, same as before.
    fun firstFeasibleCandidate(pid: String, lid: String, visited: Set<Pair<String, String>>): WaterfallCandidate? =
        rankedCandidates(pid, lid).firstOrNull { cand ->
            if (cand.method["type"] != "move") return@firstOrNull true
            val fromLid = (cand.method["from_location_id"] as? String)?.trim() ?: return@firstOrNull true
            (pid to fromLid) !in visited
        }

    // Full recursive tally of everything ONE branch's own subtree needs — AND children
    // summed (they're all mandatory, no alternative to choose among), the single
    // deterministic top-choice OR path elsewhere. Terminates at critical leaves (a
    // critical material's existing supply is its only source — nothing to recurse into).
    fun accumulate(pid: String, lid: String, needed: Double, branch: BranchKey, cohort: Pair<String, String>, visited: MutableSet<Pair<String, String>>) {
        if (needed <= 1e-9) return
        val key = pid to lid
        if (!visited.add(key)) return
        try {
            val sk = SupplyKey(pid, lid)
            if (sk in allocation.criticalMatrix.byColumn) {
                out.add(AndSiblingRequest(cohort, branch, sk, needed))
                return
            }
            // Discount by this position's own sketch-computed achievable ceiling — everything
            // else already known to constrain this path, independent of the shared-material
            // fight (see this function's own demandBlueprint param doc). Applied AFTER the
            // critical-leaf check above: capping the leaf's OWN registration by its OWN
            // sketch-achievable would be circular (that achievable is itself derived from this
            // demand's fair share of the very material step (b) is about to compute).
            val cappedNeeded = demandBlueprint?.get(key)?.achievable?.let { min(needed, it) } ?: needed
            if (cappedNeeded <= 1e-9) return
            val best = firstFeasibleCandidate(pid, lid, visited) ?: return
            when (best.method["type"]) {
                "move" -> {
                    val fromLid = (best.method["from_location_id"] as? String)?.trim() ?: return
                    accumulate(pid, fromLid, cappedNeeded, branch, cohort, visited)
                }
                "make" -> {
                    val mLoc = (best.method["location_id"] as? String)?.trim() ?: lid
                    val variants = variantsForMake(pid, mLoc, cappedNeeded, best.method, data)
                    val chosen = if (best.altKey != null) variants.filter { it.first == best.altKey } else variants
                    for ((_, children) in chosen) {
                        // A nested AND-fanout (>1 child) partway down this branch's own
                        // subtree is exactly what `discover` independently finds and handles
                        // at its own, finer-grained cohort (it's invoked on this same (pid,
                        // lid) alongside every accumulate call — see both call sites below).
                        // Flattening through it here too would register a SECOND, coarser
                        // request for the same underlying critical-material touch — under
                        // this outer branch's identity rather than the nested branch's own —
                        // diluting the fair split with a phantom contender that never
                        // actually draws anything in the live commit (nothing looks up this
                        // outer branch's cap for a leaf that's really reached through the
                        // nested fanout's own, more specific branch key). Stop here; the
                        // nested discover call covers this subtree completely on its own.
                        if (children.size > 1) return
                        for (child in children) {
                            val cPid = (child["product_id"] as? String)?.trim() ?: continue
                            val cLid = (child["location_id"] as? String)?.trim() ?: continue
                            val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                            if (cQty <= 1e-9) continue
                            accumulate(cPid, cLid, cQty, branch, cohort, visited)
                        }
                    }
                }
                // "purchase": elastic (can always order more/sooner) — never itself a
                // contention source, matches the sketch phase's own treatment.
            }
        } finally {
            visited.remove(key)
        }
    }

    // Walks the same topology looking for fanout points (AND-parent with >1 child, or the
    // root's own rootSplitWeights split). At each one found: computes each branch's own
    // target qty (rate-based for AND, KB-weighted for root-split — mirroring
    // PlanningEngine.kt's rootSplitWeights formula exactly), fires one fresh [accumulate]
    // per branch, and keeps discovering deeper, independent fanouts nested within each
    // branch's own subtree — each nested fanout gets its own, separate cohort. Two
    // "cousin" branches from unrelated cohorts that both happen to reach the same scarce
    // critical material ARE fairly pooled together — [computeAndSiblingCaps] groups by
    // supplyKey alone, not (cohort, supplyKey), specifically so cousin contention isn't
    // invisible to the split. What's still left uncomposed: whether an OUTER branch's own
    // (possibly partial) achievability should further discount an INNER nested fanout's
    // share — e.g. a branch capped to 30% elsewhere in its own subtree still competes for
    // an unrelated inner-fanout material as if it will fully succeed. A real but separate,
    // lower-severity refinement (efficiency, not fair-share correctness) left for later.
    //
    // Lineage disambiguates two structurally identical subtrees reached via different
    // outer AND-siblings — see [extendLineage]/[combineSlot]'s own doc for the full
    // rationale (shared verbatim with PlanningEngine.kt's live-commit lookup).
    //
    // `discover` and `routeChildrenForDiscovery` mutually recurse, so both are declared as
    // lateinit lambdas (plain local `fun`s only see declarations lexically before them —
    // no forward reference — so genuine two-way local-function recursion needs this).
    lateinit var discover: (String, String, Double, Boolean, MutableSet<Pair<String, String>>, String) -> Unit
    lateinit var routeChildrenForDiscovery: (WaterfallCandidate, String, String, Double, String) -> Unit

    discover = discover@{ pid: String, lid: String, needed: Double, isRoot: Boolean, visited: MutableSet<Pair<String, String>>, lineage: String ->
        if (needed <= 1e-9) return@discover
        val key = pid to lid
        if (!visited.add(key)) return@discover
        try {
            val sk = SupplyKey(pid, lid)
            if (sk in allocation.criticalMatrix.byColumn) return@discover  // terminal — no fanout beneath a raw critical leaf

            val candidates = rankedCandidates(pid, lid)
            if (candidates.isEmpty()) return@discover
            val cap = methodCfg.maxMethods.coerceAtMost(candidates.size)

            if (isRoot && cap > 1) {
                val cohort = key
                val top = candidates.take(cap)
                val scored = preferenceKb?.let { reconstructNodeScores(pid, lid, top, it) }
                    ?.let { s -> val sum = s.sum(); if (sum > 1e-9) s.map { it / sum } else null }
                val weights = scored ?: List(top.size) { 1.0 / top.size }
                for ((idx, cand) in top.withIndex()) {
                    val target = weights[idx] * needed
                    if (target <= 1e-9) continue
                    val branch = BranchKey(pid, lid, combineSlot(lineage, slotIdFor(cand)))
                    accumulate(pid, lid, target, branch, cohort, mutableSetOf())
                    // Step directly into this candidate's own children for nested-fanout
                    // discovery — cannot re-enter discover(pid, lid, ...) here, that would
                    // just re-trigger this same root-split fanout again.
                    //
                    // Root-split candidates all share the SAME (pid, lid) — they're OR
                    // alternatives for building the identical product, not distinct BOM
                    // children — so extending lineage with plain "pid@lid" would produce the
                    // SAME segment for every candidate, collapsing a nested fanout shared by
                    // two DIFFERENT root-split candidates exactly like an unqualified
                    // BranchKey would. Fold the candidate's own slot in too, matching
                    // `branch`'s own identity, so each candidate's descent stays distinct.
                    routeChildrenForDiscovery(cand, pid, lid, target, extendLineage(lineage, pid, "$lid#${slotIdFor(cand)}"))
                }
                return@discover
            }

            // Not a fanout here: follow the single deterministic top choice, but keep
            // looking for fanouts further below. Skips a top choice that would immediately
            // cycle back to an already-open frame (see firstFeasibleCandidate) — otherwise
            // a cyclic top preference silently truncates discovery right here, same bug as
            // in accumulate.
            val best = firstFeasibleCandidate(pid, lid, visited) ?: return@discover
            when (best.method["type"]) {
                "move" -> {
                    val fromLid = (best.method["from_location_id"] as? String)?.trim() ?: return@discover
                    discover(pid, fromLid, needed, false, visited, lineage)
                }
                "make" -> {
                    val mLoc = (best.method["location_id"] as? String)?.trim() ?: lid
                    val variants = variantsForMake(pid, mLoc, needed, best.method, data)
                    val chosen = if (best.altKey != null) variants.filter { it.first == best.altKey } else variants
                    for ((_, children) in chosen) {
                        val isAndGroup = children.size > 1
                        if (isAndGroup) {
                            val cohort = key
                            for (child in children) {
                                val cPid = (child["product_id"] as? String)?.trim() ?: continue
                                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                                val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                                if (cQty <= 1e-9) continue
                                val branch = BranchKey(cPid, cLid, combineSlot(lineage, null))
                                accumulate(cPid, cLid, cQty, branch, cohort, mutableSetOf())
                                discover(cPid, cLid, cQty, false, mutableSetOf(), extendLineage(lineage, cPid, cLid))
                            }
                        } else {
                            for (child in children) {
                                val cPid = (child["product_id"] as? String)?.trim() ?: continue
                                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                                val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                                if (cQty <= 1e-9) continue
                                discover(cPid, cLid, cQty, false, visited, lineage)
                            }
                        }
                    }
                }
            }
        } finally {
            visited.remove(key)
        }
    }

    routeChildrenForDiscovery = routeChildrenForDiscovery@{ candidate: WaterfallCandidate, pid: String, lid: String, needed: Double, lineage: String ->
        when (candidate.method["type"]) {
            "move" -> {
                val fromLid = (candidate.method["from_location_id"] as? String)?.trim() ?: return@routeChildrenForDiscovery
                discover(pid, fromLid, needed, false, mutableSetOf(), lineage)
            }
            "make" -> {
                val mLoc = (candidate.method["location_id"] as? String)?.trim() ?: lid
                val variants = variantsForMake(pid, mLoc, needed, candidate.method, data)
                val chosen = if (candidate.altKey != null) variants.filter { it.first == candidate.altKey } else variants
                for ((_, children) in chosen) {
                    for (child in children) {
                        val cPid = (child["product_id"] as? String)?.trim() ?: continue
                        val cLid = (child["location_id"] as? String)?.trim() ?: continue
                        val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                        if (cQty <= 1e-9) continue
                        discover(cPid, cLid, cQty, false, mutableSetOf(), lineage)
                    }
                }
            }
        }
    }

    discover(rootPid, rootLid, rootQty, true, mutableSetOf(), "")
    return out
}

/** Bundles [computeAndSiblingCaps]'s two outputs: per-branch numeric caps (step b) and, for
 *  branches whose group was genuinely constrained, the dominator each should be tagged with
 *  (step c). Kept as one return type (rather than two separately-invoked top-level functions)
 *  because both are computed from the exact same per-group fair-split call — see
 *  [allocateSiblingGroup]. */
internal data class AndSiblingCapsResult(
    val caps: Map<Any?, Map<BranchKey, Map<String, Double>>>,
    val dominators: Map<Any?, Map<BranchKey, List<DominatorRef>>>,
)

private fun BranchKey.label(): String = "$productId@$locationId" + (slot?.let { " [$it]" } ?: "")

/** One contended-group's fair-split result: per-branch [shares] of [availableAgg] (step b), and
 *  — when the group's combined need exceeded what was available (the common case: proportional
 *  scaling across every contending branch, not one clear "worst" one) — the dominator(s) each
 *  constrained branch should be tagged with (step c).
 *
 *  Every branch in the group is drawing on the exact same material `sk` — so, mirroring the
 *  AND-min rule ("the wo with the least quantity dominates; copy its dominator to every
 *  sibling") one level up: rather than each branch computing its own reduced-quantity dominator
 *  independently, [dominatorByBranch] is ONE already-resolved [rawSupplyLotRefs] result for `sk`
 *  — the same bottom-up resolution every branch would already get from the sketch phase for
 *  this shared material — copied onto every constrained branch, not recomputed per branch. A
 *  dominator is always a real, specific supply lot (`kind = "bom_child"`, `supplyId` set), never
 *  something composed on-the-fly at the propagation site. Which sibling branches were also
 *  drawing on it rides along on `competingDemandIds` purely for UI-tooltip use — attached to the
 *  copy, never baked into its identity or label — and the copy still has to survive
 *  `isLotExhausted` at reconcile() time like any other `bom_child` ref: this is a candidate, not
 *  a final verdict on which lot actually bound. Empty [dominatorByBranch] when the group wasn't
 *  actually constrained (available >= total ask). */
private data class SiblingGroupAllocation(
    val shares: Map<BranchKey, Double>,
    val dominatorByBranch: Map<BranchKey, List<DominatorRef>>,
)

/**
 * Step (b): fair-split [availableAgg] of [sk] across [byBranch]'s contending branches — reuses
 * [allocate], the same fair-split primitive already used cross-demand, one level deeper.
 *
 * Step (c): when the group's total ask exceeds [availableAgg] — the common case, proportional
 * scaling across ALL branches rather than a single identifiable "worst" one — every constrained
 * branch is tagged with the SAME [rawSupplyLotRefs] resolution for `sk`, copied rather than
 * independently recomputed (see [SiblingGroupAllocation]'s own doc for why copy, not compose).
 */
private fun allocateSiblingGroup(
    sk: SupplyKey,
    byBranch: Map<BranchKey, List<AndSiblingRequest>>,
    availableAgg: Double,
    allocationMode: String,
    demandBudgets: Map<String, Double>,
): SiblingGroupAllocation {
    val candidates = byBranch.map { (branch, reqs) ->
        AllocationCandidate(demandId = branch, neededQty = reqs.sumOf { it.requestedQty }, priority = 0)
    }
    // Mode "demand_qty" (the default) gracefully falls back to proportional-by-neededQty here —
    // AllocationCandidate.demandQty is deliberately left unset (0.0) since these candidates are
    // branches of ONE demand, not competing demands; splitting by each branch's own need is
    // exactly the right semantics.
    // allocate() returns Map<Any?, Double> (AllocationCandidate.demandId is Any? so it can hold
    // a BranchKey here) — safe per-entry cast, same as the pre-split code's own
    // `branch as? BranchKey ?: continue`, not a blanket cast.
    val shares = allocate(candidates, availableAgg, allocationMode)
        .mapNotNull { (k, v) -> (k as? BranchKey)?.let { it to v } }.toMap()

    val totalRequested = candidates.sumOf { it.neededQty }
    val dominatorByBranch = if (totalRequested > availableAgg + 1e-9) {
        val sharedDominator = rawSupplyLotRefs(sk.productId, sk.locationId, demandBudgets)
        byBranch.keys.associateWith { branch ->
            val siblingLabels = byBranch.keys.filter { it != branch }.map { it.label() }
            sharedDominator.map { it.copy(competingDemandIds = siblingLabels) }
        }
    } else emptyMap()
    return SiblingGroupAllocation(shares, dominatorByBranch)
}

/**
 * Phase 2 — per-(demand, critical supply) fair allocation among contending branches
 * discovered by [gatherAndSiblingRequests] (step a), split into fair-share allocation (step
 * b, [allocateSiblingGroup]) and horizontal dominator propagation (step c, same function) —
 * see [allocateSiblingGroup]'s own doc for why b and c are one call, not two.
 *
 * Grouped by supplyKey alone, deliberately NOT by (cohort, supplyKey): branches from
 * different AND-parents ("cousins") competing for the same scarce material draw from the
 * exact same demand-wide budget and must be split together, not one cohort at a time —
 * see the grouping loop below for the full rationale.
 *
 * Returns, per demand: demandId → branch → lotKey → capped qty (`caps`, ready to slice
 * per-demand and thread into [legacyCommit] as `andSiblingCaps`), and demandId → branch →
 * dominator refs (`dominators`, threaded the same way as `andSiblingDominators`).
 */
internal fun computeAndSiblingCaps(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    preferenceKb: PreferenceKb?,
    /** Optional per-demand progress callback (index 1-based, total demand count) — this is the
     *  single most expensive step in a large plan's pre-processing (a full BOM gather walk per
     *  demand), so it gets its own sub-progress rather than reporting only once at entry/exit. */
    onDemandProcessed: ((Int, Int) -> Unit)? = null,
    /** [computePlanBlueprint]'s sketch-phase output — sliced per-demand and passed to
     *  [gatherAndSiblingRequests] as its own `demandBlueprint` param. See that param's own doc
     *  for why step (a)'s gather reuses it instead of assuming infinite achievability. */
    planBlueprint: PlanBlueprint? = null,
): AndSiblingCapsResult {
    val capsResult = mutableMapOf<Any?, Map<BranchKey, Map<String, Double>>>()
    val dominatorResult = mutableMapOf<Any?, Map<BranchKey, List<DominatorRef>>>()
    for ((idx, demand) in demands.withIndex()) {
        val demandId = demand["demand_id"] ?: continue
        val requests = gatherAndSiblingRequests(demand, allocation, data, config, preferenceKb, planBlueprint?.get(demandId))  // (a)
        onDemandProcessed?.invoke(idx + 1, demands.size)
        if (requests.isEmpty()) continue
        val demandBudgets = allocation.perLotBudgets[demandId] ?: emptyMap()
        val branchCaps = mutableMapOf<BranchKey, MutableMap<String, Double>>()
        val branchDominators = mutableMapOf<BranchKey, MutableList<DominatorRef>>()

        // Group by supplyKey ALONE — not (cohort, supplyKey). Two branches hanging off
        // different AND-parents ("cousins") that both reach the same scarce critical
        // material are just as much in contention as two direct AND-siblings: they draw
        // from the exact same demand-wide budget. Grouping per-cohort would let each
        // cohort's fair-split run in isolation, blind to the other's claim on the same
        // pool — worse, a branch with no peer *within its own cohort* would never trip
        // the size<2 check below and would go uncapped, free to drain the whole
        // demand-wide budget if evaluated first, starving its cousin(s) down to zero.
        // For demands where every critical material is reached from a single cohort
        // (the common case), this produces the exact same groups as before.
        for ((sk, group) in requests.groupBy { it.supplyKey }) {
            val byBranch = group.groupBy { it.branch }
            if (byBranch.size < 2) continue  // no contention: only one branch reaches this supply

            val prefix = "${sk.productId}|${sk.locationId}|"
            val lotEntries = demandBudgets.entries.filter { it.key.startsWith(prefix) }.map { it.key to it.value }
            val availableAgg = lotEntries.sumOf { it.second }
            if (availableAgg <= 1e-9) continue

            val (shares, dominatorByBranch) =
                allocateSiblingGroup(sk, byBranch, availableAgg, allocation.sgConfig.allocationMode, demandBudgets)  // (b) + (c)

            // Known v1 limitation: projects one flat ratio onto every one of the demand's
            // existing per-lot entries for this supply key — doesn't re-check per-lot date
            // eligibility per branch.
            for ((branch, share) in shares) {
                if (share <= 1e-9) continue
                val ratio = share / availableAgg
                val m = branchCaps.getOrPut(branch) { mutableMapOf() }
                for ((lotKey, lotQty) in lotEntries) {
                    val cap = lotQty * ratio
                    // A branch key can legitimately recur across independent cohorts (e.g. a
                    // shared component appearing under two different AND-parents); take the
                    // tighter of the two rather than summing — summing could let a branch's
                    // effective cap exceed what any single cohort's fair split actually granted.
                    m[lotKey] = m[lotKey]?.let { min(it, cap) } ?: cap
                }
            }
            for ((branch, refs) in dominatorByBranch) {
                branchDominators.getOrPut(branch) { mutableListOf() }.addAll(refs)
            }
        }
        if (branchCaps.isNotEmpty()) capsResult[demandId] = branchCaps
        if (branchDominators.isNotEmpty()) dominatorResult[demandId] = branchDominators
    }
    return AndSiblingCapsResult(capsResult, dominatorResult)
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
