package com.allocator.services

import java.time.LocalDate

/**
 * Phase 2 of supply-level consolidation — allocate each supply across
 * competing demands by the configured policy.
 *
 * Walks the columns of [NeedsMatrix] and, for each supply, produces a per-
 * demand cap derived from the same fair / proportional / priority_first
 * policies the leaf-engine uses today. The big difference is the *scope*:
 * here every inventory-bearing node is a separate column with its own
 * allocation; today's leaf-engine only allocates at merged-leaves and lets
 * deeper supplies compete via implicit FIFO. See
 * docs/supply-level-consolidation.md §3 (mental model) and §4.2 (column-wise
 * allocation).
 *
 * Output mirrors [NeedsMatrix]'s shape: forward (demandId → supplyKey → qty)
 * and inverted (supplyKey → demandId → qty) sparse maps. Phase 3 (commit)
 * iterates rows; Phase 3b (compensation) iterates columns to find unused
 * allocation.
 */

/**
 * One demand's candidacy at one supply. Built from a column of [NeedsMatrix]
 * plus a per-demand priority lookup.
 */
data class AllocationCandidate(
    val demandId: Any?,
    val neededQty: Double,
    val priority: Int,
    /**
     * Raw demand quantity (original user-facing quantity, not BOM-rate-adjusted).
     * Used by the [allocate] `"demand_qty"` mode, which allocates supply
     * proportionally to this value rather than to [neededQty].
     * Defaults to 0.0; when unset the `"demand_qty"` mode falls back to
     * proportional-by-[neededQty] (safe for callers that don't set it).
     */
    val demandQty: Double = 0.0,
)

/**
 * Per-supply allocation result.
 *
 * Both forward and inverted indexes are exposed together. Demands and
 * supplies that received no allocation (qty ≤ 1e-12) are omitted from both.
 */
data class SupplyAllocations(
    /** demandId → supplyKey → allocated qty. */
    val byRow: Map<Any?, Map<SupplyKey, Double>>,
    /** supplyKey → demandId → allocated qty. Transpose of [byRow]. */
    val byColumn: Map<SupplyKey, Map<Any?, Double>>,
) {
    fun isEmpty(): Boolean = byRow.isEmpty()
    fun cellCount(): Int = byRow.values.sumOf { it.size }
}

/**
 * Aggregate raw `data["supply"]` rows into per-(productId, locationId) totals.
 *
 * Multiple supply rows for the same (pid, lid) are summed. Rows with non-
 * positive qty are excluded.
 */
fun aggregateSupplies(supplies: List<Map<String, Any?>>): Map<SupplyKey, Double> {
    val totals = mutableMapOf<SupplyKey, Double>()
    for (row in supplies) {
        val pid = (row["product_id"] as? String)?.trim() ?: continue
        val lid = (row["location_id"] as? String)?.trim() ?: continue
        val qty = (row["qty"] as? Number)?.toDouble() ?: continue
        if (pid.isBlank() || lid.isBlank() || qty <= 0) continue
        val key = SupplyKey(pid, lid)
        totals[key] = (totals[key] ?: 0.0) + qty
    }
    return totals
}

/**
 * Allocate every supply column to its demand candidates by [mode].
 *
 * @param matrix output of Phase 1's [buildNeedsMatrix] or [buildInventoryAwareNeedsMatrix]
 * @param supplyTotals available qty per [SupplyKey] (typically built via [aggregateSupplies])
 * @param demandPriorities per-demand priority (lower = higher priority); demands absent default to 0
 * @param mode `"fair"` | `"proportional"` | `"priority_first"` | `"demand_qty"` (anything else → fair)
 * @param demandQuantities raw demand quantities by demand_id; only required for `"demand_qty"` mode
 */
fun allocateSupplies(
    matrix: NeedsMatrix,
    supplyTotals: Map<SupplyKey, Double>,
    demandPriorities: Map<Any?, Int>,
    mode: String,
    demandQuantities: Map<Any?, Double> = emptyMap(),
): SupplyAllocations {
    val byRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()
    val byColumn = mutableMapOf<SupplyKey, MutableMap<Any?, Double>>()

    for ((supplyKey, demandNeeds) in matrix.byColumn) {
        val available = supplyTotals[supplyKey] ?: continue  // no supply → no allocation
        if (available <= 1e-12) continue

        val candidates = demandNeeds.map { (demandId, needed) ->
            AllocationCandidate(
                demandId = demandId,
                neededQty = needed,
                priority = demandPriorities[demandId] ?: 0,
                demandQty = demandQuantities[demandId] ?: 0.0,
            )
        }
        val allocation = allocate(candidates, available, mode)

        for ((demandId, qty) in allocation) {
            if (qty <= 1e-12) continue
            byRow.getOrPut(demandId) { mutableMapOf() }[supplyKey] = qty
            byColumn.getOrPut(supplyKey) { mutableMapOf() }[demandId] = qty
        }
    }

    return SupplyAllocations(byRow, byColumn)
}

/**
 * Apply the named allocation policy to distribute [availableQty] across
 * [candidates]. Returns demandId → allocated qty (omitting zero/near-zero).
 *
 * `fair` matches the leaf-engine's `splitFair` semantics exactly: behaves
 * like `priority_first` when supply ≥ total need (every demand gets its full
 * qty, no scarcity to split), falls back to `proportional` under shortage so
 * every demand gets a non-zero share.
 *
 * `demand_qty` splits supply proportionally to each candidate's raw demand
 * quantity ([AllocationCandidate.demandQty]) rather than to BOM-rate-adjusted
 * need. This gives each demand a fair share of every supply regardless of BOM
 * rates. Falls back to `proportional` when all [AllocationCandidate.demandQty]
 * are zero (e.g. callers that don't set the field).
 *
 * Algorithm parity with [splitFair] / [splitProportional] / [splitPriorityFirst]
 * in `ConsolidationEngine.kt` is intentional. The two engines diverge in
 * scope (per-supply here vs per-merged-leaf there), not in policy mechanics.
 */
fun allocate(
    candidates: List<AllocationCandidate>,
    availableQty: Double,
    mode: String,
): Map<Any?, Double> {
    if (candidates.isEmpty() || availableQty <= 1e-12) return emptyMap()
    val totalNeed = candidates.sumOf { it.neededQty }
    return when (mode) {
        "priority_first" -> allocPriorityFirst(candidates, availableQty)
        "proportional"   -> allocProportional(candidates, availableQty, totalNeed)
        "demand_qty"     -> allocProportionalByDemandQty(candidates, availableQty, totalNeed)
        else -> // "fair" + any unknown mode default to fair
            if (availableQty >= totalNeed - 1e-9) allocPriorityFirst(candidates, availableQty)
            else allocProportional(candidates, availableQty, totalNeed)
    }
}

/**
 * Allocate in ascending-priority order (lower number = higher priority).
 * Tie-break by demandId string for determinism. Each candidate gets up to
 * its needed qty from what's left.
 */
private fun allocPriorityFirst(
    candidates: List<AllocationCandidate>,
    availableQty: Double,
): Map<Any?, Double> {
    val sorted = candidates.sortedWith(
        compareBy({ it.priority }, { it.demandId?.toString() ?: "" })
    )
    var remaining = availableQty
    val result = mutableMapOf<Any?, Double>()
    for (c in sorted) {
        val give = minOf(c.neededQty, remaining)
        if (give > 0.0) result[c.demandId] = (result[c.demandId] ?: 0.0) + give
        remaining -= give
        if (remaining <= 1e-12) break
    }
    return result
}

/**
 * Allocate proportionally to needed qty share.
 *
 * No-shortage shortcut: when availableQty ≥ totalNeed, give each candidate
 * exactly its needed qty (skip the multiply-by-fraction arithmetic to avoid
 * floating-point under-allocation that would leave tiny residuals and cascade
 * into child_failed downstream — same trick as the leaf-engine).
 *
 * Empty-need fallback: if every candidate has zero need, split availableQty
 * equally so the column doesn't silently disappear.
 */
private fun allocProportional(
    candidates: List<AllocationCandidate>,
    availableQty: Double,
    totalNeed: Double,
): Map<Any?, Double> {
    if (totalNeed <= 1e-12) {
        val each = availableQty / candidates.size
        return candidates.associate { it.demandId to each }
    }
    if (availableQty >= totalNeed - 1e-9) {
        return candidates.associate { it.demandId to it.neededQty }
    }
    return candidates.associate { c ->
        c.demandId to availableQty * (c.neededQty / totalNeed)
    }
}

/**
 * Allocate proportionally to raw demand quantity ([AllocationCandidate.demandQty]).
 *
 * This is the supply-guided allocation formula:
 *   allocation(d, s) = available × (demandQty(d) / Σ demandQty(competing_dd))
 *
 * Semantics: every demand gets the same *fractional* share of each supply,
 * weighted only by how much demand it represents — independent of BOM rates.
 * This differs from [allocProportional] which weights by BOM-rate-adjusted need.
 *
 * Fallback: when all [AllocationCandidate.demandQty] values are zero (the field
 * was not set by the caller), delegates to [allocProportional] so existing code
 * paths that don't populate [demandQty] continue to behave correctly.
 *
 * No-shortage shortcut: when available ≥ total BOM-need every demand gets exactly
 * its needed qty (no scarcity, no need to split by demand weights).
 */
private fun allocProportionalByDemandQty(
    candidates: List<AllocationCandidate>,
    availableQty: Double,
    totalNeed: Double,
): Map<Any?, Double> {
    // No-shortage: give each demand its full BOM-adjusted need.
    if (availableQty >= totalNeed - 1e-9) {
        return candidates.associate { it.demandId to it.neededQty }
    }
    val totalDemandQty = candidates.sumOf { it.demandQty }
    // Fallback when demandQty was not populated.
    if (totalDemandQty <= 1e-12) {
        return allocProportional(candidates, availableQty, totalNeed)
    }
    return candidates.associate { c ->
        c.demandId to availableQty * (c.demandQty / totalDemandQty)
    }
}

/**
 * Convenience: extract per-demand priority from raw demand list rows.
 * Demands without a `priority` field default to 0.
 */
fun extractDemandPriorities(demands: List<Map<String, Any?>>): Map<Any?, Int> =
    demands.associate { d ->
        d["demand_id"] to ((d["priority"] as? Number)?.toInt() ?: 0)
    }

/**
 * Convenience: extract raw demand quantities from demand list rows.
 * Used by [allocateSupplies] `"demand_qty"` mode to populate
 * [AllocationCandidate.demandQty].
 */
fun extractDemandQuantities(demands: List<Map<String, Any?>>): Map<Any?, Double> =
    demands.associate { d ->
        d["demand_id"] to ((d["quantity"] as? Number)?.toDouble() ?: 0.0)
    }

/**
 * Per-lot variant of [allocateSupplies].
 *
 * Treats each inventory lot (identified by supply_id) as a separate allocation
 * unit. Each demand is eligible for a lot only when its request_due_time ≥ the
 * lot's supply_date (if either date is absent, the demand is always eligible).
 *
 * Returns a flat budget map — demandId → String → qty — where budget keys are:
 *   - `"$pid|$lid|$supplyId"` — per-lot cap (consumed by [consumeFromInventory])
 *   - `"$pid|$lid"`           — aggregate cap = sum of lot allocations
 *
 * This matches the format expected by [legacyCommit] / [consumeFromInventory],
 * which already handles both key formats.
 */
fun allocateSuppliesPerLot(
    matrix: NeedsMatrix,
    supplies: List<Map<String, Any?>>,
    demandPriorities: Map<Any?, Int>,
    mode: String,
    demandDates: Map<Any?, LocalDate?>,
    demandQuantities: Map<Any?, Double> = emptyMap(),
): MutableMap<Any?, MutableMap<String, Double>> {
    val result = mutableMapOf<Any?, MutableMap<String, Double>>()

    // Group lots by SupplyKey; skip lots without supply_id or positive qty.
    val lotsByKey = mutableMapOf<SupplyKey, MutableList<Map<String, Any?>>>()
    for (row in supplies) {
        val pid = (row["product_id"] as? String)?.trim() ?: continue
        val lid = (row["location_id"] as? String)?.trim() ?: continue
        val qty = (row["qty"] as? Number)?.toDouble() ?: continue
        if (pid.isBlank() || lid.isBlank() || qty <= 0) continue
        (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
        lotsByKey.getOrPut(SupplyKey(pid, lid)) { mutableListOf() }.add(row)
    }

    for ((sk, demandNeeds) in matrix.byColumn) {
        val lots = lotsByKey[sk] ?: continue
        val aggKey = sk.toString()   // "$pid|$lid"

        for (lot in lots) {
            val supplyId = (lot["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val lotQty = (lot["qty"] as? Number)?.toDouble() ?: continue
            if (lotQty <= 1e-12) continue
            val lotDate = parseDate(lot["supply_date"] as? String)

            // Filter to demands eligible for this lot by date.
            // Period-bucket demands (request_due_time = 1st of month) extend to end-of-month
            // so they compete for any lot whose supply_date falls within their period, not just
            // lots that arrive before the first of the month.
            val eligibleNeeds = demandNeeds.filter { (demandId, _) ->
                val dd = demandDates[demandId]
                val eligDate = if (dd != null && dd.dayOfMonth == 1) dd.plusMonths(1).minusDays(1) else dd
                lotDate == null || eligDate == null || !eligDate.isBefore(lotDate)
            }
            if (eligibleNeeds.isEmpty()) continue

            val candidates = eligibleNeeds.entries.map { (demandId, needed) ->
                AllocationCandidate(
                    demandId = demandId,
                    neededQty = needed,
                    priority = demandPriorities[demandId] ?: 0,
                    demandQty = demandQuantities[demandId] ?: 0.0,
                )
            }

            for ((demandId, allocated) in allocate(candidates, lotQty, mode)) {
                if (allocated <= 1e-12) continue
                val budget = result.getOrPut(demandId) { mutableMapOf() }
                val lotKey = "$aggKey|$supplyId"
                budget[lotKey] = (budget[lotKey] ?: 0.0) + allocated
                budget[aggKey]  = (budget[aggKey]  ?: 0.0) + allocated
            }
        }
    }

    return result
}

/**
 * Critical-raw-material variant of [allocateSuppliesPerLot] — replaces it at
 * [buildSupplyAllocation]'s one call site (already scoped to `criticalMatrix`, the
 * pre-pruned-to-critical-only reachability matrix, so no separate critical/non-critical
 * partitioning is needed here). This table exists for users to review and manually edit
 * (Critical Raw Allocation UI, saved versions) — it expresses per-demand ELIGIBILITY and a
 * starting proportional split, not a hard sequencing of consumption; actual fair-share
 * arbitration among competing demands (AND-siblings, diamonds) happens later, during the real
 * commit, via the existing branchLotCap/intraBudget machinery.
 *
 * Two differences from the plain per-lot allocator, each a straightforward per-lot ELIGIBILITY
 * filter (not order-dependent — every lot is still processed independently, same as
 * [allocateSuppliesPerLot]):
 *  - TARGET: a lot whose `target` is set is only eligible for demands from that customer.
 *  - Tightened timing: only enforced on a lot that ALSO has TARGET set — untargeted lots keep
 *    today's plain "lot exists" eligibility, no deadline gate. When it does apply, eligibility
 *    uses the demand's cumulative-lead-time-adjusted deadline (via [aggregatePathToLeaf],
 *    AND-summed/OR-maxed across however many routes reach this specific material) rather than
 *    its flat top-level request date.
 *
 * Each demand's "legitimate quantity" (top-level qty × cumulative rate/yield at this material,
 * aggregated the same AND-sum/OR-max way) is computed ONCE and used as-is for every lot it's
 * eligible for — deliberately NOT reduced/deducted across lots (dropped the earlier
 * reverse-chronological running-deduction design: sequencing which lot "gets credit" for a
 * demand's need is exactly the kind of decision the real commit's own fairness system is already
 * built to make, and duplicating it here as a static pre-pass fought with it — see
 * consolidated_wo_pegging_redesign-era history for why. A demand can end up with more aggregate
 * per-lot budget than its own physical need when eligible for several lots of the same material;
 * that's fine — it's a CEILING per lot, not a consumption target, exactly like the non-critical
 * allocator already relies on).
 *
 * Output shape is identical to [allocateSuppliesPerLot]'s (demandId → `"$pid|$lid|$supplyId"` /
 * `"$pid|$lid"` → qty), so [consumeFromInventory]/`legacyCommit` need no changes.
 */
internal fun allocateCriticalSuppliesPerLot(
    matrix: NeedsMatrix,
    supplies: List<Map<String, Any?>>,
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): MutableMap<Any?, MutableMap<String, Double>> {
    val result = mutableMapOf<Any?, MutableMap<String, Double>>()
    val demandsById = demands.associateBy { it["demand_id"] }

    val lotsByKey = mutableMapOf<SupplyKey, MutableList<Map<String, Any?>>>()
    for (row in supplies) {
        val pid = (row["product_id"] as? String)?.trim() ?: continue
        val lid = (row["location_id"] as? String)?.trim() ?: continue
        val qty = (row["qty"] as? Number)?.toDouble() ?: continue
        if (pid.isBlank() || lid.isBlank() || qty <= 0) continue
        (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
        lotsByKey.getOrPut(SupplyKey(pid, lid)) { mutableListOf() }.add(row)
    }

    data class DemandContext(val customerId: String?, val eligDate: LocalDate?, val deadline: LocalDate?)
    for ((sk, demandNeeds) in matrix.byColumn) {
        val lots = lotsByKey[sk] ?: continue
        val aggKey = sk.toString()

        // Legitimate quantity + tightened deadline, once per demand at this material — same
        // AND-sum/OR-max aggregation as before, just no longer mutated across lots.
        val remaining = mutableMapOf<Any?, Double>()
        val context = mutableMapOf<Any?, DemandContext>()
        for (demandId in demandNeeds.keys) {
            val d = demandsById[demandId] ?: continue
            val dPid = (d["product_id"] as? String)?.trim() ?: continue
            val dLid = (d["location_id"] as? String)?.trim() ?: continue
            val dQty = (d["quantity"] as? Number)?.toDouble() ?: 0.0
            val path = aggregatePathToLeaf(dPid, dLid, sk.productId, sk.locationId, data) ?: continue
            val reqDate = parseDate(d["request_due_time"] as? String ?: d["request_time"] as? String)
            // Period-bucket demands (request_due_time = 1st of month) extend to end-of-month —
            // same convention allocateSuppliesPerLot uses for the untargeted (no lead-time
            // tightening) case, so an untargeted lot's eligibility here is byte-for-byte
            // identical to what it would be outside this TARGET-aware allocator.
            val eligDate = if (reqDate != null && reqDate.dayOfMonth == 1) reqDate.plusMonths(1).minusDays(1) else reqDate
            val deadline = reqDate?.let { dateAddDays(it, -path.cumulativeLeadTime) }
            remaining[demandId] = dQty * path.cumulativeRate
            context[demandId] = DemandContext(
                customerId = (d["customer_id"] as? String)?.trim(),
                eligDate = eligDate,
                deadline = deadline,
            )
        }
        if (remaining.isEmpty()) continue

        for (lot in lots) {
            val supplyId = (lot["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val lotQty = (lot["qty"] as? Number)?.toDouble() ?: continue
            if (lotQty <= 1e-12) continue
            val lotDate = parseDate(lot["supply_date"] as? String)
            val lotTarget = (lot["target"] as? String)?.trim()?.takeIf { it.isNotBlank() }

            val eligible = context.filter { (demandId, ctx) ->
                val rem = remaining[demandId] ?: 0.0
                if (rem <= 1e-12) return@filter false
                if (lotTarget != null) {
                    // TARGETed lot: customer match + tightened, lead-time-adjusted deadline —
                    // deliberately NOT the period-bucket-extended eligDate: a targeted lot's
                    // timing constraint is about real WO lead time against the demand's actual
                    // due date, not a monthly-aggregate window.
                    if (ctx.customerId != lotTarget) return@filter false
                    val deadline = ctx.deadline
                    if (lotDate != null && deadline != null && lotDate.isAfter(deadline)) return@filter false
                } else {
                    // Untargeted lot (the common case): same period-bucket-aware date check as
                    // allocateSuppliesPerLot, no lead-time subtraction.
                    val eligDate = ctx.eligDate
                    if (lotDate != null && eligDate != null && lotDate.isAfter(eligDate)) return@filter false
                }
                true
            }
            if (eligible.isEmpty()) continue

            val candidates = eligible.keys.map { demandId ->
                AllocationCandidate(demandId = demandId, neededQty = remaining.getValue(demandId), priority = 0)
            }
            val totalNeed = candidates.sumOf { it.neededQty }
            for ((demandId, allocated) in allocProportional(candidates, lotQty, totalNeed)) {
                if (allocated <= 1e-12) continue
                val budget = result.getOrPut(demandId) { mutableMapOf() }
                val lotKey = "$aggKey|$supplyId"
                budget[lotKey] = (budget[lotKey] ?: 0.0) + allocated
                budget[aggKey]  = (budget[aggKey]  ?: 0.0) + allocated
            }
        }
    }

    return result
}
