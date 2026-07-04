package com.allocator.services

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.ConsolidationEngine")

/** Parsed from the "consolidation" key in the run config map. */
data class ConsolidationConfig(
    val enabled: Boolean = false,
    val periodDays: Int = 30,
    /**
     * When true, Pass-1 inventory-consolidation re-supplies the depleted on-hand stock to per-demand
     * planning using the REAL `supply_id`s (distributed across the original lots) instead of a
     * synthetic `consolidated_<pid>_<lid>` bucket, and the `demand_id=null` production trees are
     * dropped. The fair split is unchanged (still enforced by `budgets`) — only the CARRIER changes,
     * so the per-demand pegging resolves to real supplies (no consolidation artifact in pegging).
     * Default true: real lots are the carriers, pegging resolves cleanly. See [[no_consolidated_in_pegging]].
     */
    val realPegging: Boolean = true,
)

// ── Config parsing ────────────────────────────────────────────────────────────

fun parseConsolidationConfig(config: Map<String, Any?>?): ConsolidationConfig {
    val sub = config?.get("consolidation") as? Map<*, *> ?: return ConsolidationConfig()
    @Suppress("UNCHECKED_CAST")
    val m = sub as? Map<String, Any?> ?: return ConsolidationConfig()
    val enabled = m["enabled"] as? Boolean ?: false
    val periodDays = ((m["period_days"] as? Number)?.toInt() ?: 30).coerceIn(0, 365)
    val realPegging = m["real_pegging"] as? Boolean ?: true
    return ConsolidationConfig(enabled, periodDays, realPegging)
}

