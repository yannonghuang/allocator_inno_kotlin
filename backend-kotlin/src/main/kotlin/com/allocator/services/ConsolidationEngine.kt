package com.allocator.services

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.ConsolidationEngine")

/** Parsed from the "consolidation" key in the run config map.
 *
 *  A `real_pegging` flag used to live here (whether Pass-1 inventory-consolidation
 *  re-supplies depleted stock via real supply_ids vs. a synthetic consolidated
 *  bucket) — confirmed dead (never read as a live gate anywhere; that architecture
 *  is now permanently baked in) and removed. */
data class ConsolidationConfig(
    val enabled: Boolean = false,
    val periodDays: Int = 30,
)

// ── Config parsing ────────────────────────────────────────────────────────────

fun parseConsolidationConfig(config: Map<String, Any?>?): ConsolidationConfig {
    val sub = config?.get("consolidation") as? Map<*, *> ?: return ConsolidationConfig()
    @Suppress("UNCHECKED_CAST")
    val m = sub as? Map<String, Any?> ?: return ConsolidationConfig()
    val enabled = m["enabled"] as? Boolean ?: false
    val periodDays = ((m["period_days"] as? Number)?.toInt() ?: 30).coerceIn(0, 365)
    return ConsolidationConfig(enabled, periodDays)
}

