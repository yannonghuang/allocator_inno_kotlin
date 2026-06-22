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
