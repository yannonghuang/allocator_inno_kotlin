package com.allocator.services

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.ConsolidationEngine")

// ── Override index ─────────────────────────────────────────────────────────────

/**
 * Recursively convert a JsonElement (or plain Kotlin type) to a plain Any? value.
 * Needed because CaseLoader stores override payloads as JsonObject, and a raw
 * `as? Map<String, Any?>` cast succeeds at runtime (type erasure) but leaves
 * JsonPrimitive values in the map — whose toString() includes surrounding quotes.
 */
private fun jsonToAny(v: Any?): Any? = when (v) {
    is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.doubleOrNull ?: v.content
    is JsonArray -> v.map { jsonToAny(it) }
    is JsonObject -> v.entries.associate { (k, el) -> k to jsonToAny(el) }
    else -> v
}

/**
 * Build a lookup map: "entityType|entityKey" → payload map (plain Kotlin types).
 * Used by both ConsolidationEngine and PlanningEngine to resolve user overrides.
 */
@Suppress("UNCHECKED_CAST")
fun buildOverrideIndex(overrides: List<Map<String, Any?>>): Map<String, Map<String, Any?>> {
    val result = mutableMapOf<String, Map<String, Any?>>()
    for (o in overrides) {
        val type = o["entity_type"]?.toString()?.trim() ?: continue
        val key  = o["entity_key"]?.toString()?.trim()  ?: continue
        val raw  = o["payload"]
        val payloadMap: Map<String, Any?> = when (raw) {
            is JsonObject -> raw.entries.associate { (k, v) -> k to jsonToAny(v) }
            is Map<*, *>  -> raw as Map<String, Any?>
            else          -> emptyMap()
        }
        result["$type|$key"] = payloadMap
    }
    return result
}

/**
 * Build per-supply demand caps from supply_split overrides.
 *
 * Returns Map<supplyId, Map<demandId, Double>>. For each supply with an override:
 *   - Allocations are summed per demand_id.
 *   - If the total exceeds the supply's qty, all caps are scaled down proportionally.
 *   - Demand ids that don't appear in the `demands` list are dropped (logged).
 *
 * Supplies without an override are absent from the returned map.
 */
@Suppress("UNCHECKED_CAST")
fun buildSupplyCapMap(
    overrideIndex: Map<String, Map<String, Any?>>,
    supplies: List<Map<String, Any?>>,
    demands: List<Map<String, Any?>>,
): Map<String, Map<String, Double>> {
    val supplyQty = mutableMapOf<String, Double>()
    for (s in supplies) {
        val sid = s["supply_id"]?.toString() ?: continue
        val q = (s["qty"] as? Number)?.toDouble() ?: 0.0
        supplyQty[sid] = (supplyQty[sid] ?: 0.0) + q
    }
    val knownDemandIds = demands.mapNotNull { it["demand_id"]?.toString() }.toHashSet()

    val out = mutableMapOf<String, Map<String, Double>>()
    for ((key, payload) in overrideIndex) {
        if (!key.startsWith("supply_split|")) continue
        val supplyId = key.removePrefix("supply_split|")
        val totalAvailable = supplyQty[supplyId]
        if (totalAvailable == null) {
            log.warn("supply_split override references unknown supply_id={}; ignoring", supplyId)
            continue
        }
        val allocations = payload["allocations"] as? List<*> ?: continue
        val raw = mutableMapOf<String, Double>()
        for (item in allocations) {
            val m = item as? Map<*, *> ?: continue
            val demandId = m["demand_id"]?.toString() ?: continue
            val qty = (m["qty"] as? Number)?.toDouble() ?: continue
            if (qty <= 0) continue
            if (demandId !in knownDemandIds) {
                log.warn("supply_split override for supply={} references unknown demand_id={}; dropping", supplyId, demandId)
                continue
            }
            raw[demandId] = (raw[demandId] ?: 0.0) + qty
        }
        if (raw.isEmpty()) continue
        val total = raw.values.sum()
        val clamped = if (total > totalAvailable + 1e-9 && total > 1e-12) {
            val scale = totalAvailable / total
            log.warn("supply_split override total {} exceeds supply.qty {} for {}; scaled down", total, totalAvailable, supplyId)
            raw.mapValues { (_, v) -> v * scale }
        } else raw
        out[supplyId] = clamped
    }
    return out
}


/** Parsed from the "consolidation" key in the run config map. */
data class ConsolidationConfig(
    val enabled: Boolean = false,
    val periodDays: Int = 30,
    val maxIterations: Int = 1,
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
    val maxIterations = ((m["max_iterations"] ?: m["max_iter"]) as? Number)?.toInt()?.coerceIn(1, 15) ?: 1
    val realPegging = m["real_pegging"] as? Boolean ?: true
    return ConsolidationConfig(enabled, periodDays, maxIterations, realPegging)
}

