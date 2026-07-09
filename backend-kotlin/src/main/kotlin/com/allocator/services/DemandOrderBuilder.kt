package com.allocator.services

import java.time.LocalDate

/**
 * Demand Ordering build engine — a precomputed, persisted processing order for demands,
 * replacing today's raw `(priority, demand_id)` sort in [runPlanning] with an explicit,
 * user-editable ranking.
 *
 * Unlike the Preferences KB (a per-node BOM-tree walk), this is a single flat sort over
 * `data["demand"]`: by [request_due_time] ascending (earliest due first), tie-broken by
 * [priority] ascending (lower = higher priority, same meaning as today's sort), tie-broken
 * finally by `demand_id` for full determinism. No configurable weights — the rule is fixed.
 *
 * This file is intentionally DB-free (pure function over `data`); persistence lives in
 * `api/DemandOrdering.kt`, mirroring how [buildPreferenceKb] (pure) is split from
 * `Preferences.kt`'s `generateAndSeedCasePreferences` (DB read/write).
 */

internal data class DemandOrderRow(val demandId: String, val order: Int)

/**
 * Sorts every demand in [data] by (request_due_time asc, priority asc, demand_id asc) and
 * assigns canonical order 10, 20, 30, ... in that sequence. Null/unparseable due times sort
 * last (treated as "no deadline", lowest urgency) — mirrors the existing inline pattern used
 * for the same field in resource-contention arbitration (see [ResourceScheduler]).
 */
internal fun buildDemandOrder(data: Map<String, List<Map<String, Any?>>>): List<DemandOrderRow> {
    val demands = data["demand"] ?: emptyList()
    val sorted = demands.sortedWith(
        compareBy(
            { d: Map<String, Any?> -> (d["request_due_time"] as? String)?.let { parseDate(it) } ?: LocalDate.MAX },
            { d: Map<String, Any?> -> (d["priority"] as? Number)?.toInt() ?: 0 },
            { d: Map<String, Any?> -> d["demand_id"]?.toString() ?: "" },
        )
    )
    return sorted.mapIndexed { i, d -> DemandOrderRow(d["demand_id"]?.toString() ?: "", (i + 1) * 10) }
}
