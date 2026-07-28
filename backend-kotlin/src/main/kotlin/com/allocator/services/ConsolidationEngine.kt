package com.allocator.services

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.ConsolidationEngine")

/** Parsed from the "consolidation" key in the run config map.
 *
 *  A `real_pegging` flag used to live here (whether Pass-1 inventory-consolidation
 *  re-supplies depleted stock via real supply_ids vs. a synthetic consolidated
 *  bucket) — confirmed dead (never read as a live gate anywhere; that architecture
 *  is now permanently baked in) and removed. An `enabled` on/off flag used to live
 *  here too — removed: consolidation always runs now; the per-type batch scales
 *  (make/move/purchase_batch_scale, "none" vs a real bucket width) are the only
 *  real on/off control, per type. */
data class ConsolidationConfig(
    val periodDays: Int = 30,
)

// ── Config parsing ────────────────────────────────────────────────────────────

fun parseConsolidationConfig(config: Map<String, Any?>?): ConsolidationConfig {
    val sub = config?.get("consolidation") as? Map<*, *> ?: return ConsolidationConfig()
    @Suppress("UNCHECKED_CAST")
    val m = sub as? Map<String, Any?> ?: return ConsolidationConfig()
    val periodDays = ((m["period_days"] as? Number)?.toInt() ?: 30).coerceIn(0, 365)
    return ConsolidationConfig(periodDays)
}

