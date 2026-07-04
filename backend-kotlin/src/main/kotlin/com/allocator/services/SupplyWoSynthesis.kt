package com.allocator.services

/**
 * Phase E of supply-level consolidation — work-order synthesis.
 *
 * The per-demand commit (3a / 3c) emits one WO per demand per produced
 * BOM step. When multiple demands need the same produced component at the
 * same time, those WOs are conceptually one physical job split across
 * contributors. Phase E merges them into a single consolidated WO with
 * per-demand split-details, matching the leaf-engine's `consolidated:true`
 * output shape so downstream consumers (frontend, audit, persistence)
 * don't need to change.
 *
 * ## Grouping key
 *
 * WOs are merged when they share **all** of:
 *   - product_id
 *   - location_id
 *   - start_time
 *   - end_time
 *   - method
 *
 * Different times = different physical runs. Different methods (make vs
 * move) = different operations. Same tuple → one consolidated lot.
 *
 * ## Output shape
 *
 * Single-demand groups pass through unchanged (no consolidation marker).
 * Multi-demand groups produce one WO with:
 *   - `demand_id = null`
 *   - `quantity = Σ contributors' qty`
 *   - `consolidated = true`
 *   - `consolidation_split_mode` = the supply allocation policy
 *   - `consolidation_total_planned` = total qty
 *   - `consolidation_split_details` = list of per-demand
 *     `{demand_id, allocated_qty, requested_qty, priority, parent_product}`
 *
 * Matches the leaf-engine's [ConsolidationEngine.runConsolidation] output
 * format exactly so the frontend's existing consolidated-WO chip + slide-in
 * machinery works without changes.
 *
 * See docs/supply-level-consolidation.md §3 (mental-model diagram), §4.4
 * (WO emission), and §6 (what stays).
 */

/**
 * Merge per-demand WOs into consolidated WOs grouped by
 * (product_id, location_id, start_time, end_time, method).
 *
 * @param perDemandWOs the workOrders returned by [runInitialCommit]
 * @param demandPriorities per-demand priority for the split-details priority field
 * @param mode allocation policy in effect (carried in consolidation_split_mode)
 * @return a new list with single-demand WOs preserved as-is and multi-demand
 *         groups merged with split-details
 */
fun synthesizeConsolidatedWOs(
    perDemandWOs: List<Map<String, Any?>>,
    demandPriorities: Map<Any?, Int>,
    mode: String,
): List<Map<String, Any?>> {
    if (perDemandWOs.isEmpty()) return emptyList()

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

    val groups: Map<GroupKey, List<Map<String, Any?>>> = perDemandWOs.groupBy(::keyOf)
    val result = mutableListOf<Map<String, Any?>>()

    for ((_, members) in groups) {
        if (members.size == 1) {
            result.add(members[0])
            continue
        }

        val totalQty = members.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        // Per-demand split-details for the consolidated WO. Matches the leaf
        // engine's shape (parent_product/requested_qty fields included for UI
        // compatibility; they're best-effort here since supply-level
        // consolidation doesn't track parent_product directly).
        val splitDetails = members.map { wo ->
            val did = wo["demand_id"]
            val q = (wo["quantity"] as? Number)?.toDouble() ?: 0.0
            mapOf(
                "demand_id"     to did,
                "parent_product" to "",
                "requested_qty" to roundQty(q),
                "allocated_qty" to roundQty(q),
                "priority"      to (demandPriorities[did] ?: 0),
            )
        }

        // Carry over all non-demand-specific fields from the first member,
        // then overlay consolidation markers + summed quantity.
        val template = members[0]
        val consolidated = template.toMutableMap().apply {
            put("quantity", roundQty(totalQty))
            put("demand_id", null)
            put("consolidated", true)
            put("consolidation_split_mode", mode)
            put("consolidation_total_planned", roundQty(totalQty))
            put("consolidation_split_details", splitDetails)
        }
        result.add(consolidated)
    }

    return result
}
