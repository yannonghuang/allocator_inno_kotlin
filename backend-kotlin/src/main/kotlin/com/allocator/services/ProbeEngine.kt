package com.allocator.services

import java.time.LocalDate

/**
 * Read-only inventory probe — computes achievable quantity and scoring metrics
 * without copying or mutating inventory.
 *
 * Replaces the `plan(invCopy)` simulation pattern used in variant and method
 * scoring.  The old pattern ran a full recursive plan on a mutable inventory
 * copy for every candidate (O(candidates × depth × BOM size)); this probe is
 * O(children × inventory buckets) per candidate, with no copies or commits.
 *
 * ## BOM semantics
 *
 * - **AND node** (variant's required children): all children must be satisfied;
 *   achievable at the parent = min fraction across children.
 * - **OR node** (alternative methods or variants): contributions accumulate;
 *   achievable = sum of per-alternative contributions, capped at demand qty.
 *
 * The probe looks only at *direct* inventory (not sub-BOM recursion).  For
 * scoring purposes this is sufficient: variants differ primarily at their
 * immediate children, and any deeper sub-BOM will be handled correctly in
 * the real commit pass that follows.  `anyFailed` is set conservatively: a
 * child is only marked failed when it has zero inventory AND no method (make /
 * buy / move) exists at its (pid, lid).
 */

data class ProbeResult(
    /** Total inventory units that can be drawn across all AND children. */
    val consumed: Double,
    /** Units that must come from purchase (buy method) rather than inventory. */
    val purchaseQty: Double,
    /** Latest supply_date drawn from inventory (for commit-time scoring). */
    val maxCommitDate: LocalDate?,
    /** True iff any required child is fully unreachable (no inventory, no method). */
    val anyFailed: Boolean,
)

/**
 * Read-only FIFO scan of inventory at (productId, locationId).
 *
 * Mirrors [consumeFromInventory]'s date-sorted bucket walk but makes no
 * mutations.  Returns the quantity that *would* be consumed and the latest
 * supply date touched.
 *
 * @param budgetCap  optional hard cap (supply-guided budget); null = uncapped.
 */
internal fun peekInventory(
    inventory: List<Map<String, Any?>>,
    productId: String,
    locationId: String,
    need: Double,
    budgetCap: Double? = null,
): Pair<Double, LocalDate?> {
    val pid = productId.trim()
    val lid = locationId.trim()
    val effectiveNeed = if (budgetCap != null) minOf(need, budgetCap) else need
    if (effectiveNeed <= 1e-12) return Pair(0.0, null)

    val sorter = compareBy<Map<String, Any?>>(
        { parseDate(it["supply_date"] as? String) ?: LocalDate.MIN },
        { it["supply_id"]?.toString() ?: "" },
    )
    // Fast path: use (pid,lid) index when available (IndexedInventory), avoiding O(N) scan.
    val candidates: List<Map<String, Any?>> =
        (inventory as? IndexedInventory)?.idx?.get(Pair(pid, lid))
            ?: inventory.filter { b -> b["product_id"]?.toString()?.trim() == pid && b["location_id"]?.toString()?.trim() == lid }
    val buckets = candidates
        .filter { (it["qty"] as? Number)?.toDouble() ?: 0.0 > 0 }
        .sortedWith(sorter)

    var remaining = effectiveNeed
    var maxDate: LocalDate? = null
    for (b in buckets) {
        if (remaining <= 1e-12) break
        val avail = (b["qty"] as? Number)?.toDouble() ?: 0.0
        val take = minOf(avail, remaining)
        remaining -= take
        val sd = parseDate(b["supply_date"] as? String)
        if (sd != null && (maxDate == null || sd > maxDate)) maxDate = sd
    }

    return Pair(effectiveNeed - remaining, maxDate)
}

/**
 * Probe an AND-group of child materials against current inventory.
 *
 * For each child, [peekInventory] returns how much is immediately available.
 * `anyFailed` is raised only when a child has zero inventory *and* no method
 * exists at its (pid, lid) — i.e. it is genuinely unreachable.
 *
 * @param childList   the AND children (each entry has product_id, location_id, quantity)
 * @param inventory   current inventory pool (read-only)
 * @param data        BOM / method tables (for `anyFailed` method check)
 * @param planningPath cycle guard — a child whose key is in this set is cycle-stopped
 */
internal fun probeChildren(
    childList: List<Map<String, Any?>>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    planningPath: Set<Pair<String, String>>,
): ProbeResult {
    var totalConsumed = 0.0
    var totalPurchase = 0.0
    var maxCommit: LocalDate? = null
    var anyFailed = false

    for (child in childList) {
        val pid = (child["product_id"] as? String)?.trim() ?: continue
        val lid = (child["location_id"] as? String)?.trim() ?: continue
        val qty = (child["quantity"] as? Number)?.toDouble() ?: continue
        if (qty <= 1e-12) continue

        val (available, commitDate) = peekInventory(inventory, pid, lid, qty)
        totalConsumed += available
        if (commitDate != null && (maxCommit == null || commitDate > maxCommit)) maxCommit = commitDate

        val remainder = qty - available
        if (remainder > 1e-9) {
            if (Pair(pid, lid) in planningPath) {
                anyFailed = true
            } else {
                val methods = getMethods(pid, lid, data)
                when {
                    methods.isEmpty() -> anyFailed = true
                    methods.all { it["type"] == "purchase" } -> totalPurchase += remainder
                    // make/move available — optimistically feasible; real commit will handle it
                }
            }
        }
    }

    return ProbeResult(totalConsumed, totalPurchase, maxCommit, anyFailed)
}
