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
    val periodDays: Int = 30,
    val allocationMode: String = "fair",  // "proportional" | "priority_first" | "fair"
    /**
     * Max Pass-1 allocation iterations. 1 (default) = single allocation pass: fastest,
     * but a demand can be capped above what it draws, orphaning the slack inventory
     * (fine when supply is ample). >1 reverts to the over-claim/compensate/converge
     * fixed-point loop that reclaims orphaned allocations each pass — tighter inventory
     * utilization (important when supply-constrained), at higher runtime. Clamped 1..15.
     */
    val maxIterations: Int = 1,
    /**
     * When true, Pass-1 inventory-consolidation re-supplies the depleted on-hand stock to per-demand
     * planning using the REAL `supply_id`s (distributed across the original lots) instead of a
     * synthetic `consolidated_<pid>_<lid>` bucket, and the `demand_id=null` production trees are
     * dropped. The fair split is unchanged (still enforced by `budgets`) — only the CARRIER changes,
     * so the per-demand pegging resolves to real supplies (no consolidation artifact in pegging).
     * Default false until soak-validated. See [[no_consolidated_in_pegging]].
     */
    val realPegging: Boolean = false,
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
    val periodDays = ((m["period_days"] as? Number)?.toInt() ?: 30).coerceIn(0, 365)
    val allocationMode = when (m["allocation_mode"]?.toString()) {
        "proportional"   -> "proportional"
        "priority_first" -> "priority_first"
        else             -> "fair"
    }
    // Pass-1 allocation iterations. Default 1 (single pass); >1 re-enables the
    // converge loop (clamped to the 15 hard ceiling). Accept both "max_iterations"
    // and the shorter "max_iter".
    val maxIterations = ((m["max_iterations"] ?: m["max_iter"]) as? Number)?.toInt()?.coerceIn(1, 15) ?: 1
    val realPegging = m["real_pegging"] as? Boolean ?: false
    // Note: legacy `scope=all` configs are silently coerced to leaf-only on
    // re-plan. The supply-level orchestrator was retired in 2026-05.
    return ConsolidationConfig(enabled, periodDays, allocationMode, maxIterations, realPegging)
}

// ── Time bucketing ────────────────────────────────────────────────────────────

/**
 * Floor a date to the nearest epoch-anchored period boundary.
 * Buckets are deterministic and period-aligned regardless of calendar weeks.
 *
 * periodDays == 0 is a sentinel meaning "single bucket": all dates collapse to
 * LocalDate.EPOCH so every demand lands in the same group regardless of its
 * due date. Use this when you want consolidation to merge across the entire
 * planning horizon without temporal fragmentation.
 */
fun timeBucket(date: LocalDate?, periodDays: Int): LocalDate {
    if (date == null) return LocalDate.EPOCH
    if (periodDays <= 0) return LocalDate.EPOCH
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

/**
 * Hybrid split: behaves like [splitPriorityFirst] when supply is sufficient
 * (`availableQty >= totalQty`), but falls back to [splitProportional] when
 * there is a real shortage. This avoids the priority_first failure mode where
 * one demand is granted 100 % of a scarce component, can't actually produce
 * (e.g. because its BOM also needs the same component via another path or
 * another raw is exhausted), and all remaining demands get zero — producing a
 * plan that commits nothing at all.
 *
 * With `fair`, every demand whose need was > 0 is guaranteed a non-zero share
 * under shortage, so each per-demand plan can at least partial-commit the
 * producible fraction of its requested FG quantity.
 */
fun splitFair(
    group: ConsolidationGroup,
    availableQty: Double,
): Map<Any?, Double> =
    if (availableQty >= group.totalQty - 1e-9) splitPriorityFirst(group, availableQty)
    else splitProportional(group, availableQty)

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
    /** Starting inventory snapshot — used only at the root for cascade/elaborate probing. */
    inventory: List<Map<String, Any?>> = emptyList(),
    /** Planning config — carries method_selection (cascade vs elaborate) toggle. */
    planConfig: Map<String, Any?>? = null,
    /**
     * Output map: records the method chosen at the ROOT BOM node for each demand as
     * (productId, locationId, demandIdStr) → chosen method. Inner nodes are not recorded —
     * main plan uses simple getPreferredMethod there, which matches this walk's behavior.
     */
    methodChoices: MutableMap<Triple<String, String, String>, Map<String, Any?>>? = null,
    /** True for the outermost call for a demand — selects the method via cascade/elaborate. */
    isRoot: Boolean = false,
    /** Starting demand map — required when isRoot=true so cascade/elaborate can probe. */
    demand: Map<String, Any?>? = null,
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
    // Feasibility gate (root only): if every root method fails cascade's probe, skip registration.
    // Otherwise consolidation would pre-allocate components whose parent BOM can't roll up
    // (e.g. a sibling child has no supply and no methods) — producing phantom WOs that
    // consume real inventory without any finished-goods output. Main plan will still
    // independently commit qty=0 with child_failed for the demand.
    //
    // Guarded by inventory.isNotEmpty() because the probe uses plan() which consumes from
    // inventory — an empty-inventory probe would spuriously fail. In production runPlanning
    // always passes a populated inventory; older unit tests that call collectComponentNeeds
    // without inventory rely on the supplyIndex-based structural reachability only.
    if (isRoot && demand != null && methods.isNotEmpty() && inventory.isNotEmpty()) {
        if (firstFeasibleMethod(methods, demand, inventory, data, dueDate, 500, emptySet(), planConfig) == null) {
            return
        }
    }
    val method = if (isRoot && methods.size > 1 && methodChoices != null && demand != null) {
        // Align with PlanningEngine.plan()'s outermost method selection — the main plan uses
        // cascade (or elaborate) only at depth == MAX_PLAN_DEPTH. At inner recursion both
        // engines fall back to simple getPreferredMethod, so we only diverge at the root.
        val useElaborateMethod = resolveMethodSelection(planConfig).elaborate
        val chosen = if (useElaborateMethod)
            getPreferredMethodElaborate(methods, demand, inventory, data, dueDate, planConfig, 500, emptySet()).first
        else
            getPreferredMethodCascade(methods, demand, inventory, data, dueDate, planConfig, 500, emptySet()).first
        if (chosen != null && demandId != null) {
            val did = demandId.toString().trim()
            if (did.isNotBlank()) {
                methodChoices[Triple(productId, locationId, did)] = chosen
            }
        }
        chosen ?: methods.minByOrNull { (it["preference"] as? Number)?.toInt() ?: 0 }
    } else {
        methods.minByOrNull { (it["preference"] as? Number)?.toInt() ?: 0 }
    } ?: return
    val leadDays = leadDaysForMethod(method, productId, locationId, qty, data)
    val componentDueDate = if (dueDate != null) dueDate.minusDays(leadDays.toLong()) else null
    val nextVisited = visited + key

    when (method["type"]) {
        "make" -> {
            // Non-terminal: recurse through BOM children to reach inventory-level components.
            // Follow all children in every alt_group (including OR-alternatives), but propagate
            // viaOrAlt=true so that inventory items reached via OR groups are not over-procured.
            //
            // OR vs AND distinction: variantsForMake groups BOM rows by alt_group. A non-null
            // alt_group with multiple children is a real OR (alternative substitutes). The
            // synthetic "__null__" key is the AND bucket — every child in it is REQUIRED, not
            // alternative. Setting `isOrGroup = childList.size > 1` without the alt_group
            // check incorrectly marks all-AND BOMs (e.g. case 171's 502-1824-02 with 11 NULL
            // alt_group children) as OR, propagating viaOrAlt=true through the whole subtree.
            // Downstream that flag tells single-demand consolidation groups to skip
            // (line 574), leaving real inventory at the supply leaves untouched even though
            // the chain genuinely needs them.
            val productionLocation = (method["location_id"] as? String)?.trim() ?: locationId
            val variants = variantsForMake(productId, productionLocation, qty, method, data)
            for ((altKey, childList) in variants) {
                val isOrGroup = altKey != "__null__" && childList.size > 1
                for (child in childList) {
                    val cProductId  = (child["product_id"]  as? String)?.trim() ?: continue
                    val cLocationId = (child["location_id"] as? String)?.trim() ?: continue
                    val cQty        = (child["quantity"]    as? Number)?.toDouble() ?: continue
                    if (cQty <= 0) continue
                    collectDeepNeeds(cProductId, cLocationId, cQty, demandId, priority, componentDueDate,
                        productId, data, supplyIndex, nextVisited, result, addedKeys,
                        viaOrAlt = viaOrAlt || isOrGroup,
                        inventory = inventory, planConfig = planConfig,
                        methodChoices = methodChoices, isRoot = false, demand = null)
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
                    viaOrAlt = viaOrAlt,
                    inventory = inventory, planConfig = planConfig,
                    methodChoices = methodChoices, isRoot = false, demand = null)
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
    /** Starting inventory snapshot — used at the root call for cascade/elaborate probing. */
    inventory: List<Map<String, Any?>> = emptyList(),
    /** Planning config — determines cascade vs elaborate method selection. */
    planConfig: Map<String, Any?>? = null,
    /**
     * Output map: (productId, locationId, demandIdStr) → chosen root method. Caller merges
     * entries into the PlanningEngine overrideIndex as synthetic method_selection overrides
     * so the main plan's plan() picks the identical method at depth=MAX_PLAN_DEPTH.
     */
    methodChoices: MutableMap<Triple<String, String, String>, Map<String, Any?>>? = null,
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
            data, supplyIdx, emptySet(), result, addedKeys,
            inventory = inventory, planConfig = planConfig,
            methodChoices = methodChoices, isRoot = true, demand = d)
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

    val multi = groups.count { it.needs.size > 1 }
    log.info(
        "runConsolidation: mode={} period_days={} groups={} (multi-demand={}) sample={}",
        config.allocationMode, config.periodDays, groups.size, multi,
        groups.take(3).joinToString { "(${it.productId}@${it.locationId} qty=${it.totalQty} n=${it.needs.size})" }
    )

    for (group in groups) {
        val componentKey = "${group.productId}|${group.locationId}"

        // `timeBucket` is the grouping KEY — under single-bucket consolidation
        // (period_days<=0) it is LocalDate.EPOCH (1970-01-01) so every need collapses
        // into one group. That sentinel must NOT be used as the SCHEDULING date: a
        // purchase/make anchored at 1970 underflows to 1969 once lead time is
        // subtracted (and then spawns a wave per cycle from 1969 to the horizon).
        // Schedule against the earliest real due date among the consolidated needs
        // instead; fall back to the bucket only if no need carries a date.
        val scheduleBucket: LocalDate =
            if (group.timeBucket == LocalDate.EPOCH)
                (group.needs.mapNotNull { it.dueDate }.minOrNull() ?: group.timeBucket)
            else group.timeBucket

        // ── Inventory-only consolidation ─────────────────────────────────────────
        // Consolidation ALLOCATES existing on-hand inventory across the competing
        // demands; it never produces work orders. Cap the synthetic demand to the
        // stock actually on hand at this (product, location) — consuming stock is not
        // a work order, so the planFn call below emits zero WOs and only splits the
        // inventory. Groups with no on-hand stock here are skipped: per-demand
        // planning (legacyCommit) produces / moves whatever the stock doesn't cover
        // (including cross-location stock, handled per-demand for now).
        val availableInv = inventory
            .filter {
                (it["product_id"] as? String)?.trim() == group.productId &&
                    (it["location_id"] as? String)?.trim() == group.locationId
            }
            .sumOf { (it["qty"] as? Number)?.toDouble() ?: 0.0 }
        if (availableInv <= 1e-9) continue
        val allocatableQty = minOf(group.totalQty, availableInv)

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
                "quantity"         to allocatableQty,
                "request_due_time" to scheduleBucket.toString(),
                "request_time"     to scheduleBucket.toString(),
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
            val (committed, wos, pegging) = planFn(syntheticDemand, invCopy, data, scheduleBucket, 500, emptySet(), planConfig, null)
            // Exclude hard-failure rows (no_methods / no_preferred_method / depth_limit /
            // child_failed:*) from producedQty — those represent unmet demand, not real production.
            // Counting them as produced would inject phantom synthetic supply for products that
            // actually have nothing to offer (e.g. raw materials with no make method after their
            // parent's consolidation drained the inventory).
            val producedQty = committed
                .filterNot { isHardPlanningFailure(it["commit_reason"] as? String) }
                .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
            // Mark WOs and add
            wos.forEach { wo ->
                consolidatedWOs.add(wo + mapOf("consolidated" to false))
            }
            // Save pegging under the original demand_id with passthrough=true so the WO pegging
            // endpoint can locate the move/make nodes in this tree.  The passthrough flag tells
            // planKpis to exclude this entry from the supply-consumption sum, which prevents
            // double-counting (the main planning loop's tree already accounts for those units
            // via the tagged consolidated supply bucket that is injected below).
            // per_demand_allocations: weights used by PlanningEngine.extractSupplyAllocations and
            // by the frontend supplyPeggingMap to attribute supply consumption to demands directly,
            // instead of relying on the synthetic tagged-bucket inversion (which under-counts raw
            // materials consumed inside the BOM chain — they have no consolidated_<demandId> bucket).
            if (pegging != null) consolidatedPegging.add(mapOf(
                "demand_id" to need.demandId,
                "passthrough" to true,
                "per_demand_allocations" to mapOf(need.demandId to producedQty),
                "tree" to pegging,
            ))
            allocation.getOrPut(need.demandId) { mutableMapOf() }[componentKey] = producedQty
            // Consume from real inventory by walking the pegging tree so every supply leaf
            // (top-level component AND raw materials deep in the BOM) is depleted.  This is
            // essential: without it, subsequent groups see undepleted raw supply and over-peg.
            if (pegging != null) {
                applyPeggingConsumption(inventory, pegging)
            } else {
                consumeFromInventoryForConsolidation(inventory, group.productId, group.locationId, producedQty)
            }
        } else {
            // Multi-demand group — always consolidate, even when every need reached this
            // component via an OR-alternative path. With cap-propagation in place, a small
            // share yields a correspondingly small commit (not child_failed), so the fair
            // split is what prevents high-priority demands from starving the rest via FIFO.
            val syntheticDemand = mapOf(
                "demand_id"        to null,
                "product_id"       to group.productId,
                "location_id"      to group.locationId,
                "quantity"         to allocatableQty,
                "request_due_time" to scheduleBucket.toString(),
                "request_time"     to scheduleBucket.toString(),
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
            val (committed, wos, pegging) = planFn(syntheticDemand, invCopy, data, scheduleBucket, 500, emptySet(), planConfig, null)
            // Exclude hard-failure rows from producedQty — see passthrough branch for rationale.
            val producedQty = committed
                .filterNot { isHardPlanningFailure(it["commit_reason"] as? String) }
                .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

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
                    "fair"         -> splitFair(group, producedQty)
                    else           -> splitPriorityFirst(group, producedQty)
                }
            }
            for ((demandId, qty) in split) {
                if (qty <= 1e-12) continue
                allocation.getOrPut(demandId) { mutableMapOf() }[componentKey] = qty
            }

            // Visibility: log any shortage (produced < requested) so the actual split is auditable.
            if (producedQty < group.totalQty - 1e-6) {
                val preview = group.needs.take(8).joinToString {
                    "${it.demandId}:need=${"%.1f".format(it.qty)},got=${"%.1f".format(split[it.demandId] ?: 0.0)}"
                }
                val more = if (group.needs.size > 8) " …(+${group.needs.size - 8} more)" else ""
                log.info(
                    "consolidation shortage {}@{} bucket={} total={} produced={} mode={} → {}{}",
                    group.productId, group.locationId, group.timeBucket,
                    "%.1f".format(group.totalQty), "%.1f".format(producedQty),
                    config.allocationMode, preview, more,
                )
            }

            // Build split detail list for explanation (one entry per demand in the group)
            val splitDetails = group.needs.map { need ->
                mapOf(
                    "demand_id"     to need.demandId,
                    "parent_product" to need.parentProductId,
                    "requested_qty" to roundQty(need.qty),
                    "allocated_qty" to roundQty(split[need.demandId] ?: 0.0),
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
            // per_demand_allocations: actual allocated quantities per demand. Used by
            // PlanningEngine.extractSupplyAllocations to split each supply leaf qty proportionally
            // across demands, and by the frontend supplyPeggingMap to attribute consumed quantities.
            val perDemandAllocations: Map<String, Double> = split.entries
                .filter { (k, v) -> k != null && v > 1e-12 }
                .associate { (k, v) -> (k as String) to v }
            if (pegging != null) consolidatedPegging.add(mapOf(
                "demand_id" to null,
                "consolidated" to true,
                "consolidated_demand_ids" to consolidatedDemandIds,
                "per_demand_allocations" to perDemandAllocations,
                "tree" to pegging,
            ))

            // Consume from real inventory by walking the pegging tree — depletes the top-level
            // component AND all raw materials consumed in the BOM chain below it.
            if (pegging != null) {
                applyPeggingConsumption(inventory, pegging)
            } else {
                consumeFromInventoryForConsolidation(inventory, group.productId, group.locationId, producedQty)
            }
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

/**
 * Walk a pegging tree and decrement real inventory by each supply/purchase leaf's qty,
 * matched on supply_id. This is how consolidation propagates raw-material consumption back
 * to the shared inventory so that subsequent groups (and the main planner loop) see the
 * correct depletion. Without this, each consolidation group plans against invCopy (a fresh
 * snapshot), so raw materials deep in the BOM chain are never drained — leading to over-
 * pegging and FG qty not being capped by raw supply.
 *
 * Supply ids that do not match any inventory bucket (synthetic `consolidated_*` etc.) are
 * skipped silently: they have no physical cap.
 */
private fun applyPeggingConsumption(
    inventory: MutableList<MutableMap<String, Any?>>,
    peggingTree: Map<String, Any?>,
) {
    @Suppress("UNCHECKED_CAST")
    fun walk(node: Map<String, Any?>) {
        val type = node["type"] as? String
        val supplyId = node["supply_id"] as? String
        if ((type == "supply" || type == "purchase") && !supplyId.isNullOrBlank()) {
            val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 1e-9) {
                var remaining = qty
                for (b in inventory) {
                    if (remaining <= 1e-9) break
                    if (b["supply_id"]?.toString() != supplyId) continue
                    val avail = (b["qty"] as? Number)?.toDouble() ?: 0.0
                    if (avail <= 0) continue
                    val take = minOf(avail, remaining)
                    b["qty"] = avail - take
                    remaining -= take
                }
            }
        }
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it) }
    }
    walk(peggingTree)
}
