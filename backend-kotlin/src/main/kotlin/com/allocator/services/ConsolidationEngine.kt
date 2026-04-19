package com.allocator.services

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.LocalDate

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
 * Parse a component_split override payload into a demandId→qty map.
 * Clamps the total to producedQty if the override specifies more than was planned.
 */
@Suppress("UNCHECKED_CAST")
fun parseSplitOverride(
    payload: Map<String, Any?>,
    producedQty: Double,
): Map<Any?, Double> {
    val allocations = payload["allocations"] as? List<*> ?: return emptyMap()
    val raw = mutableMapOf<Any?, Double>()
    for (item in allocations) {
        val m = item as? Map<*, *> ?: continue
        val demandId = m["demand_id"]
        val qty = (m["qty"] as? Number)?.toDouble() ?: continue
        if (qty > 0) raw[demandId] = (raw[demandId] ?: 0.0) + qty
    }
    if (raw.isEmpty()) return emptyMap()
    val total = raw.values.sum()
    return if (total > producedQty + 1e-9 && total > 1e-12) {
        val scale = producedQty / total
        raw.mapValues { (_, v) -> v * scale }.also {
            log.warn("component_split override total {} exceeds producedQty {}; scaled down", total, producedQty)
        }
    } else raw
}

// ── Data classes ──────────────────────────────────────────────────────────────

/** One demand's requirement for one component, discovered during the pre-scan. */
data class ComponentNeed(
    val productId: String,
    val locationId: String,
    val dueDate: LocalDate?,
    val qty: Double,
    val demandId: Any?,
    val priority: Int,
    val parentProductId: String,
    /**
     * True when this need was reached by traversing an OR-alternative alt_group (childList.size > 1).
     * Used by runConsolidation to suppress procurement: if ALL needs in a group are via OR alternatives,
     * the synthetic demand is capped to available inventory (the component might not be needed at all if
     * the planner chooses a different BOM variant).
     */
    val viaOrAlternative: Boolean = false,
)

/** A group of component needs sharing the same (productId, locationId, timeBucket). */
data class ConsolidationGroup(
    val productId: String,
    val locationId: String,
    val timeBucket: LocalDate,
    val needs: List<ComponentNeed>,
    val totalQty: Double,
)

/** Parsed from the "consolidation" key in the run config map. */
data class ConsolidationConfig(
    val enabled: Boolean = false,
    val periodDays: Int = 7,
    val allocationMode: String = "proportional",  // "proportional" | "priority_first"
)

/** Output of runConsolidation(). */
data class ConsolidationResult(
    val consolidatedWOs: List<Map<String, Any?>>,
    // demandId → (componentKey "$productId|$locationId" → allocatedQty)
    val allocation: Map<Any?, Map<String, Double>>,
    val consolidatedPegging: List<Map<String, Any?>>,
)

// ── Config parsing ────────────────────────────────────────────────────────────

fun parseConsolidationConfig(config: Map<String, Any?>?): ConsolidationConfig {
    val sub = config?.get("consolidation") as? Map<*, *> ?: return ConsolidationConfig()
    @Suppress("UNCHECKED_CAST")
    val m = sub as? Map<String, Any?> ?: return ConsolidationConfig()
    val enabled = m["enabled"] as? Boolean ?: false
    val periodDays = ((m["period_days"] as? Number)?.toInt() ?: 7).coerceIn(1, 365)
    val allocationMode = when (m["allocation_mode"]?.toString()) {
        "proportional" -> "proportional"
        else -> "priority_first"
    }
    return ConsolidationConfig(enabled, periodDays, allocationMode)
}

// ── Time bucketing ────────────────────────────────────────────────────────────

/**
 * Floor a date to the nearest epoch-anchored period boundary.
 * Buckets are deterministic and period-aligned regardless of calendar weeks.
 */
fun timeBucket(date: LocalDate?, periodDays: Int): LocalDate {
    if (date == null) return LocalDate.EPOCH
    val epochDay = date.toEpochDay()
    val bucketEpochDay = (epochDay / periodDays) * periodDays
    return LocalDate.ofEpochDay(bucketEpochDay)
}

// ── Grouping ──────────────────────────────────────────────────────────────────

fun groupByTimeBucket(
    needs: List<ComponentNeed>,
    periodDays: Int,
): List<ConsolidationGroup> =
    needs.groupBy { Triple(it.productId, it.locationId, timeBucket(it.dueDate, periodDays)) }
        .entries
        .sortedWith(compareBy({ it.key.third }, { it.key.first }, { it.key.second }))
        .map { (key, groupNeeds) ->
            ConsolidationGroup(
                productId  = key.first,
                locationId = key.second,
                timeBucket = key.third,
                needs      = groupNeeds,
                totalQty   = groupNeeds.sumOf { it.qty },
            )
        }

// ── Allocation splits ─────────────────────────────────────────────────────────

/**
 * Allocates availableQty to demands in ascending priority order.
 * Lower priority number = higher priority. Secondary sort by demandId string for determinism.
 */
fun splitPriorityFirst(
    group: ConsolidationGroup,
    availableQty: Double,
): Map<Any?, Double> {
    val sorted = group.needs.sortedWith(
        compareBy({ it.priority }, { it.demandId?.toString() ?: "" })
    )
    var remaining = availableQty
    val result = mutableMapOf<Any?, Double>()
    for (need in sorted) {
        val give = minOf(need.qty, remaining)
        result[need.demandId] = (result[need.demandId] ?: 0.0) + give
        remaining -= give
        if (remaining <= 1e-12) break
    }
    return result
}

/**
 * Allocates availableQty proportionally by each demand's qty share.
 *
 * When availableQty >= totalQty (no actual shortage), each demand receives exactly its full
 * requirement to avoid floating-point under-allocation (e.g. 690 * (345/690) = 344.999...).
 * That under-allocation would leave a tiny residual in individual planning which, if the
 * component has no production method, cascades into child_failed for the parent demand.
 */
fun splitProportional(
    group: ConsolidationGroup,
    availableQty: Double,
): Map<Any?, Double> {
    if (group.totalQty <= 1e-12) {
        // Equal split fallback
        val each = if (group.needs.isNotEmpty()) availableQty / group.needs.size else 0.0
        return group.needs.associate { it.demandId to each }
    }
    // No shortage: give each demand exactly what it needs (skip floating-point arithmetic)
    if (availableQty >= group.totalQty - 1e-9) {
        return group.needs.associate { need -> need.demandId to need.qty }
    }
    return group.needs.associate { need ->
        need.demandId to availableQty * (need.qty / group.totalQty)
    }
}

// ── Pre-scan: collect component needs ────────────────────────────────────────

/**
 * Build a set of (product_id, location_id) pairs that have available supply in inventory.
 * Used by the deep-scan to know when to stop recursing and register a consolidation claim.
 */
private fun buildSupplyIndex(data: Map<String, List<Map<String, Any?>>>): Set<Pair<String, String>> =
    (data["supply"] ?: emptyList())
        .filter { s -> ((s["qty"] as? Number)?.toDouble() ?: 0.0) > 0 }
        .mapNotNull { s ->
            val pid = s["product_id"]?.toString()?.trim() ?: return@mapNotNull null
            val lid = s["location_id"]?.toString()?.trim() ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank()) null else Pair(pid, lid)
        }
        .toSet()

/**
 * Recursively walk the BOM/method tree for (productId, locationId) and register a
 * ComponentNeed for every inventory-bearing node reachable via unambiguous BOM paths.
 *
 * Stopping rule: if the current (product, location) has existing supply in [supplyIndex],
 * register it and stop — the planner will consume it from inventory directly.  Recurse when
 * the product must be made (make method), following ALL children in every alt_group, including
 * OR-alternative groups (multiple children).  Over-claiming across OR-variant paths is harmless
 * because the proportional split self-corrects to the actual produced qty, and unused tagged
 * buckets left by unchosen variants are never consumed.
 *
 * Move methods: follow the source location, since that is where supply will be consumed.
 * Purchase methods: terminal — no inventory to pre-claim.
 *
 * [addedKeys] prevents the same demand from registering duplicate ComponentNeeds for the
 * same (product, location) via different BOM paths.
 */
private fun collectDeepNeeds(
    productId: String,
    locationId: String,
    qty: Double,
    demandId: Any?,
    priority: Int,
    dueDate: LocalDate?,
    parentProductId: String,
    data: Map<String, List<Map<String, Any?>>>,
    supplyIndex: Set<Pair<String, String>>,
    visited: Set<Pair<String, String>>,
    result: MutableList<ComponentNeed>,
    addedKeys: MutableSet<Triple<Any?, String, String>>,
    viaOrAlt: Boolean = false,
) {
    val key = Pair(productId, locationId)
    if (key in visited) return

    // Inventory exists here → this is a supply-level item worth pre-allocating.
    if (key in supplyIndex) {
        val needKey = Triple(demandId, productId, locationId)
        if (needKey !in addedKeys) {
            addedKeys.add(needKey)
            result.add(ComponentNeed(
                productId        = productId,
                locationId       = locationId,
                dueDate          = dueDate,
                qty              = qty,
                demandId         = demandId,
                priority         = priority,
                parentProductId  = parentProductId,
                viaOrAlternative = viaOrAlt,
            ))
        }
        return
    }

    val methods = getMethods(productId, locationId, data)
    val method = methods.minByOrNull { (it["preference"] as? Number)?.toInt() ?: 0 } ?: return
    val leadDays = leadDaysForMethod(method)
    val componentDueDate = if (dueDate != null) dueDate.minusDays(leadDays.toLong()) else null
    val nextVisited = visited + key

    when (method["type"]) {
        "make" -> {
            // Non-terminal: recurse through BOM children to reach inventory-level components.
            // Follow all children in every alt_group (including OR-alternatives), but propagate
            // viaOrAlt=true so that inventory items reached via OR groups are not over-procured.
            val productionLocation = (method["location_id"] as? String)?.trim() ?: locationId
            val variants = variantsForMake(productId, productionLocation, qty, method, data)
            for ((_, childList) in variants) {
                val isOrGroup = childList.size > 1
                for (child in childList) {
                    val cProductId  = (child["product_id"]  as? String)?.trim() ?: continue
                    val cLocationId = (child["location_id"] as? String)?.trim() ?: continue
                    val cQty        = (child["quantity"]    as? Number)?.toDouble() ?: continue
                    if (cQty <= 0) continue
                    collectDeepNeeds(cProductId, cLocationId, cQty, demandId, priority, componentDueDate,
                        productId, data, supplyIndex, nextVisited, result, addedKeys,
                        viaOrAlt = viaOrAlt || isOrGroup)
                }
            }
        }
        "move" -> {
            // Follow the move to its source location — that is where inventory will be consumed.
            for (child in childMaterialsForMove(method, qty)) {
                val cProductId  = (child["product_id"]  as? String)?.trim() ?: continue
                val cLocationId = (child["location_id"] as? String)?.trim() ?: continue
                val cQty        = (child["quantity"]    as? Number)?.toDouble() ?: continue
                if (cQty <= 0) continue
                collectDeepNeeds(cProductId, cLocationId, cQty, demandId, priority, componentDueDate,
                    productId, data, supplyIndex, nextVisited, result, addedKeys,
                    viaOrAlt = viaOrAlt)
            }
        }
        // "purchase" — purchasable on demand, no inventory to pre-allocate
    }
}

/**
 * Multi-level BOM scan: for every demand, walk its BOM tree via ALL paths (including
 * OR-alternative alt_groups) and register a ComponentNeed for each inventory-bearing node
 * reached.  Only nodes that have existing supply in [data["supply"]] are registered —
 * intermediate make-chain products (e.g. sub-assemblies) are passed through without
 * claiming, so the main planner's recursive make logic is not short-circuited by
 * intermediate tagged buckets.
 *
 * Result feeds groupByTimeBucket → runConsolidation → proportional split, ensuring that
 * deep-BOM consumers (e.g. 858_M51 needing 260-0152-02 via a 4-level make chain) compete
 * fairly with direct consumers for the same inventory.
 */
fun collectComponentNeeds(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    @Suppress("UNUSED_PARAMETER") consolidationConfig: ConsolidationConfig,
): List<ComponentNeed> {
    val result    = mutableListOf<ComponentNeed>()
    val supplyIdx = buildSupplyIndex(data)

    for (d in demands) {
        val productId  = (d["product_id"]  as? String)?.trim() ?: continue
        val locationId = (d["location_id"] as? String)?.trim() ?: continue
        val qty        = (d["quantity"]    as? Number)?.toDouble() ?: continue
        if (qty <= 0) continue
        val demandId = d["demand_id"]
        val priority = (d["priority"] as? Number)?.toInt() ?: 0
        val reqStr   = d["request_due_time"] as? String ?: d["request_time"] as? String
        val reqDt    = if (reqStr != null) try { LocalDate.parse(reqStr.trim().take(10)) } catch (_: Exception) { null } else null

        val addedKeys = mutableSetOf<Triple<Any?, String, String>>()
        collectDeepNeeds(productId, locationId, qty, demandId, priority, reqDt, productId,
            data, supplyIdx, emptySet(), result, addedKeys)
    }
    return result
}

// ── Consolidation runner ──────────────────────────────────────────────────────

/**
 * Plans each consolidation group once, splits output by priority, returns consolidated WOs + allocation.
 *
 * @param planFn  injected reference to plan() — enables unit testing without a real PlanningEngine
 */
fun runConsolidation(
    groups: List<ConsolidationGroup>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: ConsolidationConfig,
    planConfig: Map<String, Any?>? = null,
    planFn: (
        demand: Map<String, Any?>,
        inventory: MutableList<MutableMap<String, Any?>>,
        data: Map<String, List<Map<String, Any?>>>,
        requestTimeDt: LocalDate?,
        depth: Int,
        planningPath: Set<Pair<String, String>>,
        config: Map<String, Any?>?,
        preferDemandId: Any?,
    ) -> Triple<List<Map<String, Any?>>, List<Map<String, Any?>>, Map<String, Any?>?>,
): ConsolidationResult {
    val consolidatedWOs     = mutableListOf<Map<String, Any?>>()
    val allocation          = mutableMapOf<Any?, MutableMap<String, Double>>()
    val consolidatedPegging = mutableListOf<Map<String, Any?>>()

    // Build override index once — shared across all groups
    val overrideIndex = buildOverrideIndex(data["overrides"] ?: emptyList())

    for (group in groups) {
        val componentKey = "${group.productId}|${group.locationId}"

        if (group.needs.size == 1) {
            // Single-demand group — pass through with original demandId, no change in behavior.
            // If the only claimant reached this component via an OR-alternative path, leave the
            // supply untouched: let the main planner consume it via FIFO.  Injecting a small
            // tagged bucket that is insufficient to complete the BOM chain causes child_failed
            // with consumed-but-wasted supply.  The OR-alternative traversal still serves its
            // purpose when a non-OR consumer is also present (allViaOr=false, multi-demand branch).
            val need = group.needs[0]
            if (need.viaOrAlternative) continue
            val syntheticDemand = mapOf(
                "demand_id"        to need.demandId,
                "product_id"       to group.productId,
                "location_id"      to group.locationId,
                "quantity"         to group.totalQty,
                "request_due_time" to group.timeBucket.toString(),
                "request_time"     to group.timeBucket.toString(),
                "priority"         to need.priority,
            )
            val invCopy = inventory.map { b ->
                mutableMapOf(
                    "product_id"  to b["product_id"],
                    "location_id" to b["location_id"],
                    "supply_date" to b["supply_date"],
                    "supply_id"   to b["supply_id"],
                    "qty"         to b["qty"],
                    "demand_tag"  to b["demand_tag"],
                )
            }.toMutableList()
            val (committed, wos, pegging) = planFn(syntheticDemand, invCopy, data, group.timeBucket, 500, emptySet(), planConfig, null)
            val producedQty = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
            // Mark WOs and add
            wos.forEach { wo ->
                consolidatedWOs.add(wo + mapOf("consolidated" to false))
            }
            // Save pegging under the original demand_id with passthrough=true so the WO pegging
            // endpoint can locate the move/make nodes in this tree.  The passthrough flag tells
            // planKpis to exclude this entry from the supply-consumption sum, which prevents
            // double-counting (the main planning loop's tree already accounts for those units
            // via the tagged consolidated supply bucket that is injected below).
            if (pegging != null) consolidatedPegging.add(mapOf("demand_id" to need.demandId, "passthrough" to true, "tree" to pegging))
            allocation.getOrPut(need.demandId) { mutableMapOf() }[componentKey] = producedQty
            // Consume from real inventory (claim the supply)
            consumeFromInventoryForConsolidation(inventory, group.productId, group.locationId, producedQty)
        } else {
            // Multi-demand group — consolidate.
            // If ALL needs are via OR-alternative paths, skip: leave supply in inventory for FIFO.
            // Splitting a tiny fraction among many demands causes each to fail with child_failed
            // while the supply is consumed and wasted.  When at least one demand is a direct
            // (non-OR) consumer, allViaOr=false and we consolidate normally so OR-path demands
            // get a fair proportional share alongside the direct consumer.
            val allViaOr = group.needs.all { it.viaOrAlternative }
            if (allViaOr) continue
            val syntheticDemand = mapOf(
                "demand_id"        to null,
                "product_id"       to group.productId,
                "location_id"      to group.locationId,
                "quantity"         to group.totalQty,
                "request_due_time" to group.timeBucket.toString(),
                "request_time"     to group.timeBucket.toString(),
                "priority"         to 0,
            )
            val invCopy = inventory.map { b ->
                mutableMapOf(
                    "product_id"  to b["product_id"],
                    "location_id" to b["location_id"],
                    "supply_date" to b["supply_date"],
                    "supply_id"   to b["supply_id"],
                    "qty"         to b["qty"],
                    "demand_tag"  to b["demand_tag"],
                )
            }.toMutableList()
            val (committed, wos, pegging) = planFn(syntheticDemand, invCopy, data, group.timeBucket, 500, emptySet(), planConfig, null)
            val producedQty = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

            // Check for a component_split override before falling back to engine split policy
            val splitKey = "${group.productId}|${group.locationId}|${group.timeBucket}"
            val splitOverridePayload = overrideIndex["component_split|$splitKey"]
            val splitOverrideActive = splitOverridePayload != null
            val split: Map<Any?, Double> = if (splitOverridePayload != null) {
                parseSplitOverride(splitOverridePayload, producedQty).also {
                    log.info("component_split override applied for {} — {} demands", splitKey, it.size)
                }
            } else {
                when (config.allocationMode) {
                    "proportional" -> splitProportional(group, producedQty)
                    else           -> splitPriorityFirst(group, producedQty)
                }
            }
            for ((demandId, qty) in split) {
                if (qty <= 1e-12) continue
                allocation.getOrPut(demandId) { mutableMapOf() }[componentKey] = qty
            }

            // Build split detail list for explanation (one entry per demand in the group)
            val splitDetails = group.needs.map { need ->
                mapOf(
                    "demand_id"     to need.demandId,
                    "parent_product" to need.parentProductId,
                    "requested_qty" to need.qty,
                    "allocated_qty" to (split[need.demandId] ?: 0.0),
                    "priority"      to need.priority,
                )
            }

            // Mark all WOs as consolidated and carry split details
            wos.forEach { wo ->
                consolidatedWOs.add(wo + mapOf(
                    "consolidated" to true,
                    "demand_id" to null,
                    "consolidation_split_mode" to config.allocationMode,
                    "consolidation_total_planned" to producedQty,
                    "consolidation_split_details" to splitDetails,
                    "consolidation_override_active" to splitOverrideActive,
                ))
            }
            // Include the demand_ids that share this consolidated supply so the frontend
            // can attribute supply pegging to specific demands (supply view "Pegged Demands" column).
            val consolidatedDemandIds = split.filter { (_, qty) -> qty > 1e-12 }.keys.filterNotNull().toList()
            if (pegging != null) consolidatedPegging.add(mapOf(
                "demand_id" to null,
                "consolidated" to true,
                "consolidated_demand_ids" to consolidatedDemandIds,
                "tree" to pegging,
            ))

            // Consume from real inventory (claim the consolidated supply upfront)
            consumeFromInventoryForConsolidation(inventory, group.productId, group.locationId, producedQty)
        }
    }

    return ConsolidationResult(consolidatedWOs, allocation, consolidatedPegging)
}

/** Simple inventory consumption used by consolidation — no demand tag preference needed. */
private fun consumeFromInventoryForConsolidation(
    inventory: MutableList<MutableMap<String, Any?>>,
    productId: String,
    locationId: String,
    qty: Double,
) {
    var remaining = qty
    for (b in inventory) {
        if (remaining <= 1e-12) break
        if (b["product_id"]?.toString()?.trim() != productId.trim()) continue
        if (b["location_id"]?.toString()?.trim() != locationId.trim()) continue
        if (b.containsKey("demand_tag") && b["demand_tag"] != null) continue  // skip already-tagged
        val avail = (b["qty"] as? Number)?.toDouble() ?: 0.0
        if (avail <= 0) continue
        val take = minOf(avail, remaining)
        b["qty"] = avail - take
        remaining -= take
    }
}
