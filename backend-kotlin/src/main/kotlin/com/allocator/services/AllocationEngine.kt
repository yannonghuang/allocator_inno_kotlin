package com.allocator.services

import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val log = LoggerFactory.getLogger("com.allocator.AllocationEngine")

/**
 * Scarcity-based allocation engine.
 * Port of services/allocation_engine.py.
 *
 * Core concepts:
 *   Comp    = (product_id, location_id) — a node in the inventory graph
 *   Variant = (product_id, location_id) — an output node produced by a make/move edge
 *   ReqWithRate = (comp, rate)          — how much of comp is consumed per unit of output
 *   BasketByTime = map of comp → sorted list of (qty, period), FIFO by period
 */

/** Inventory graph node = (product_id, location_id). */
data class Comp(val productId: String, val locationId: String)

/** Produced output node = (product_id, location_id). Same structure as Comp, typed separately for clarity. */
typealias Variant = Comp

/** (component, BOM rate): to produce 1 unit of output, need output_qty / rate units of component. */
typealias ReqWithRate = Pair<Comp, Double>

/** Basket entry list: (qty, period) sorted ascending by period. */
typealias BucketList = MutableList<Pair<Double, Int>>
typealias BasketByTime = MutableMap<Comp, BucketList>

// Minimum output qty to emit as an action; smaller shares are merged into the dominant share.
private const val MIN_ALLOCATION = 0.001
private const val MAX_ALLOCATION_STEPS = 100_000
private const val PROGRESS_INTERVAL = 200
private const val PERSIST_SLICE_INTERVAL = 1000

// ── Basket helpers ─────────────────────────────────────────────────────────────

private fun compKey(c: Comp) = "${c.productId}|${c.locationId}"
private fun variantKey(v: Variant) = "${v.productId}|${v.locationId}"
private fun normNodeKey(compKey: String, period: Int): String {
    val parts = compKey.split("|", limit = 2)
    val p0 = parts.getOrElse(0) { "" }.trim()
    val p1 = parts.getOrElse(1) { "" }.trim()
    return "$p0|$p1|$period"
}

private fun basketAdd(basket: BasketByTime, comp: Comp, qty: Double, period: Int) {
    if (qty <= 0) return
    val list = basket.getOrPut(comp) { mutableListOf() }
    val existing = list.indexOfFirst { it.second == period }
    if (existing >= 0) {
        list[existing] = Pair(list[existing].first + qty, period)
    } else {
        list.add(Pair(qty, period))
        list.sortBy { it.second }
    }
}

/**
 * Consume up to [need] from [comp] in FIFO (earliest period first).
 * Returns (totalTaken, maxPeriodConsumed).
 * Optionally records consumption into [recordConsumed] keyed by "productId|locationId|period".
 */
private fun basketConsume(
    basket: BasketByTime,
    comp: Comp,
    need: Double,
    recordConsumed: MutableMap<String, Double>? = null,
): Pair<Double, Int> {
    val list = basket[comp] ?: return Pair(0.0, 0)
    if (need <= 0) return Pair(0.0, 0)
    var taken = 0.0
    var maxPeriod = 0
    val ck = compKey(comp)
    var i = 0
    while (i < list.size && taken < need) {
        val (q, p) = list[i]
        val take = min(q, need - taken)
        if (take > 0) {
            taken += take
            maxPeriod = max(maxPeriod, p)
            recordConsumed?.let {
                val node = normNodeKey(ck, p)
                it[node] = (it[node] ?: 0.0) + take
            }
            if (take >= q) {
                list.removeAt(i)
                continue  // don't increment i
            } else {
                list[i] = Pair(q - take, p)
            }
        }
        i++
    }
    return Pair(taken, maxPeriod)
}

private fun basketTotal(basket: BasketByTime, comp: Comp): Double =
    basket[comp]?.sumOf { it.first } ?: 0.0

private fun basketTotalQty(basket: BasketByTime): Double =
    basket.keys.sumOf { basketTotal(basket, it) }

private fun deepCopyBasket(basket: BasketByTime): BasketByTime {
    val copy: BasketByTime = mutableMapOf()
    basket.forEach { (k, v) -> copy[k] = v.map { it.copy() }.toMutableList() }
    return copy
}

// ── Math helpers ───────────────────────────────────────────────────────────────

private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b
private fun lcmList(values: List<Int>): Int =
    values.fold(1L) { acc, v -> lcm(acc, v.toLong()) }.toInt()

// ── Override helper ────────────────────────────────────────────────────────────

private fun applyOverrides(overrides: List<Map<String, Any?>>): Pair<Map<String, Double>, Map<String, Double>> {
    val supplyAdj = mutableMapOf<String, Double>()
    val demandAdj = mutableMapOf<String, Double>()
    for (o in overrides) {
        val et = o["entity_type"] as? String ?: continue
        val key = o["entity_key"] as? String ?: continue
        @Suppress("UNCHECKED_CAST")
        val payload = o["payload"] as? Map<String, Any?> ?: continue
        if (et == "supply" && payload.containsKey("quantity")) {
            supplyAdj[key] = (payload["quantity"] as? Number)?.toDouble() ?: 0.0
        }
        if (et == "demand" && payload.containsKey("quantity")) {
            demandAdj["demand|$key"] = (payload["quantity"] as? Number)?.toDouble() ?: 0.0
        }
    }
    return Pair(supplyAdj, demandAdj)
}

// ── Allocation result types ────────────────────────────────────────────────────

data class AllocationResult(
    val allocation: List<Map<String, Any?>>,
    val feasibleDemands: List<Map<String, Any?>>,
    val basketAlloc: Map<String, Double>,
    val basketProd: Map<String, Double>,
    val rawMaterialTrace: List<Map<String, Any?>>,
    val consumedByNode: Map<String, Double>,
    val trace: List<Map<String, Any?>>? = null,
)

// ── File-level private data classes ───────────────────────────────────────────

private data class SplitItem(
    val variant: Variant,
    val reqSet: List<ReqWithRate>,
    val leadDays: Double,
    val edgeType: String,
    var outputQty: Double,
)

private data class ProductionKey(val variant: Variant, val outputPeriod: Int)

// ── Main engine ────────────────────────────────────────────────────────────────

/**
 * Run scarcity-based allocation over the inventory graph DAG.
 *
 * @param data          Output of CaseLoader.load() — maps for supply, demand, bom, method_make, method_move, overrides.
 * @param customerWeights Optional map of customer_id → weight (default 1.0).
 * @param progressCallback Invoked every PROGRESS_INTERVAL steps with a status dict.
 * @param traceComponentKey Optional "product_id|location_id" to enable step-level trace for that component.
 */
fun runAllocation(
    data: Map<String, List<Map<String, Any?>>>,
    customerWeights: Map<String, Double>? = null,
    progressCallback: ((Map<String, Any>) -> Unit)? = null,
    traceComponentKey: String? = null,
): AllocationResult {

    // ── Trace setup ────────────────────────────────────────────────────────────
    var traceComp: Comp? = null
    val traceEvents = mutableListOf<Map<String, Any?>>()
    val rawMaterialTrace = mutableListOf<Map<String, Any?>>()
    if (traceComponentKey != null && "|" in traceComponentKey) {
        val (a, b) = traceComponentKey.trim().split("|", limit = 2)
        traceComp = Comp(a, b)
    }

    // ── Overrides ──────────────────────────────────────────────────────────────
    val (supplyAdj, demandAdj) = applyOverrides(data["overrides"] ?: emptyList())

    val supplyList = data["supply"] ?: emptyList()
    val demandListRaw = data["demand"] ?: emptyList()

    // ── Period index ───────────────────────────────────────────────────────────
    val (dateToPeriod, sortedDates) = TimeUtils.buildPeriodIndex(supplyList, demandListRaw)

    // ── Init basket from supplies ──────────────────────────────────────────────
    val basketAlloc: BasketByTime = mutableMapOf()
    for (s in supplyList) {
        val pid = s["product_id"] as? String ?: continue
        val loc = s["location_id"] as? String ?: ""
        val key = Comp(pid, loc)
        val baseQty = (s["qty"] as? Number)?.toDouble() ?: 0.0
        val adj = supplyAdj[compKey(key)] ?: 0.0
        val qty = baseQty + adj
        if (qty > 0) {
            val period = TimeUtils.supplyPeriod(s["supply_date"] as? String, dateToPeriod)
            basketAdd(basketAlloc, key, qty, period)
        }
    }
    val initialBasketTotalQty = basketTotalQty(basketAlloc)
    val initialBasketKeys = basketAlloc.count { basketTotal(basketAlloc, it.key) > 0 }
    val basketProd: BasketByTime = deepCopyBasket(basketAlloc)

    // ── Enrich demands with due_period ─────────────────────────────────────────
    val demandList = demandListRaw.map { d ->
        val due = TimeUtils.demandDuePeriod(d["request_due_time"] as? String, dateToPeriod)
        d + mapOf("due_period" to due)
    }

    // ── Build variants from method_make + method_move ──────────────────────────
    val bomList = data["bom"] ?: emptyList()
    val methodMakeList = data["method_make"] ?: emptyList()
    val methodMoveList = data["method_move"] ?: emptyList()

    // parent_id → altKey → [(child_id, rate)]
    // AND within same alt_key, OR across alt_keys
    val bomByParentAlt = mutableMapOf<String, MutableMap<String, MutableList<Pair<String, Double>>>>()
    for (b in bomList) {
        val pid = b["parent_id"] as? String ?: continue
        val childId = b["child_id"] as? String ?: continue
        val rate = (b["rate"] as? Number)?.toDouble() ?: 1.0
        val altKey = (b["alt_group"] as? String)?.takeIf { it.isNotBlank() } ?: "__null__"
        bomByParentAlt.getOrPut(pid) { mutableMapOf() }
            .getOrPut(altKey) { mutableListOf() }
            .add(Pair(childId, rate))
    }

    // (variant, alternatives, leadDays, edgeType)
    // alternatives = list of requirement sets (AND within set, OR across sets)
    data class VariantEntry(
        val variant: Variant,
        val alternatives: List<List<ReqWithRate>>,
        val leadDays: Double,
        val edgeType: String,
    )

    val variantsList = mutableListOf<VariantEntry>()

    for (m in methodMakeList) {
        val pid = m["product_id"] as? String ?: continue
        val loc = m["location_id"] as? String ?: continue
        val v = Variant(pid, loc)
        val altGroups = bomByParentAlt[pid] ?: continue
        val alternatives = altGroups.values.map { reqSet ->
            reqSet.map { (childId, rate) -> Pair(Comp(childId, loc), rate) }
        }
        if (alternatives.isEmpty()) continue
        val leadDays = (m["lead_time"] as? Number)?.toDouble() ?: 0.0
        variantsList.add(VariantEntry(v, alternatives, leadDays, "make"))
    }

    for (mv in methodMoveList) {
        val pid = mv["product_id"] as? String ?: continue
        val fromLoc = mv["from_location_id"] as? String ?: ""
        val toLoc = mv["to_location_id"] as? String ?: ""
        if (fromLoc == toLoc) continue
        val v = Variant(pid, toLoc)
        val req: List<ReqWithRate> = listOf(Pair(Comp(pid, fromLoc), 1.0))
        val transitDays = (mv["transit_time"] as? Number)?.toDouble() ?: 0.0
        variantsList.add(VariantEntry(v, listOf(req), transitDays, "move"))
    }

    // ── Union of components per variant (for downstream graph + scarcity) ──────
    val variantsReq = mutableMapOf<Variant, List<Comp>>()
    for (entry in variantsList) {
        val comps = mutableSetOf<Comp>()
        entry.alternatives.forEach { reqSet -> reqSet.forEach { (c, _) -> comps.add(c) } }
        variantsReq[entry.variant] = comps.toList()
    }
    val allTargets = variantsReq.keys.toSet()

    // ── Trace: which variants use trace_comp? ──────────────────────────────────
    if (traceComp != null) {
        val variantsUsingTrace = variantsList.mapNotNull { entry ->
            entry.alternatives.forEachIndexed { altIx, reqSet ->
                if (reqSet.any { (c, _) -> c == traceComp }) {
                    return@mapNotNull mapOf(
                        "variant_key" to variantKey(entry.variant),
                        "edge_type" to entry.edgeType,
                        "alt_index" to altIx,
                        "req_component_keys" to reqSet.map { (c, _) -> compKey(c) },
                        "rates" to reqSet.map { (_, r) -> r },
                    )
                }
            }
            null
        }
        traceEvents.add(mapOf(
            "event" to "variants_using_trace_component",
            "trace_component_key" to compKey(traceComp),
            "variants" to variantsUsingTrace,
        ))
    }

    // ── demand_product_to_location (first make method per product) ─────────────
    val demandProductToLocation = mutableMapOf<String, String>()
    for (m in methodMakeList) {
        val pid = m["product_id"] as? String ?: continue
        val loc = m["location_id"] as? String ?: continue
        demandProductToLocation.putIfAbsent(pid, loc)
    }

    // ── Unmet demand per (variant, customer) and target_weight ────────────────
    val unmetMap = mutableMapOf<Pair<Variant, String>, Double>()
    for (d in demandList) {
        val pid = d["product_id"] as? String ?: continue
        val cust = d["customer_id"] as? String ?: ""
        val qty = (d["quantity"] as? Number)?.toDouble() ?: 0.0
        val loc = d["location_id"] as? String ?: demandProductToLocation[pid] ?: continue
        val adj = demandAdj["demand|${d["demand_id"]}"] ?: 0.0
        val effectiveQty = max(0.0, qty + adj)
        val v = Variant(pid, loc)
        unmetMap[Pair(v, cust)] = (unmetMap[Pair(v, cust)] ?: 0.0) + effectiveQty
    }

    val demandedTargets = unmetMap.filter { it.value > 0 }.keys.map { it.first }.toSet()
    val wC = (customerWeights ?: emptyMap()).toMutableMap()
    for (d in demandList) {
        val cid = d["customer_id"] as? String ?: continue
        wC.putIfAbsent(cid, 1.0)
    }

    val targetWeight = mutableMapOf<Variant, Double>()
    for ((vCust, q) in unmetMap) {
        if (q > 0) {
            val (v, cust) = vCust
            targetWeight[v] = (targetWeight[v] ?: 0.0) + q * (wC[cust] ?: 1.0)
        }
    }

    // ── Downstream graph (variant → variants that consume it) ─────────────────
    val downstream = mutableMapOf<Variant, MutableSet<Variant>>()
    for ((v, comps) in variantsReq) {
        for (c in comps) {
            if (c in allTargets) {
                downstream.getOrPut(c as Variant) { mutableSetOf() }.add(v)
            }
        }
    }

    // Topological order for propagating target_weight to intermediate nodes
    val order = mutableListOf<Variant>()
    val seen = mutableSetOf<Variant>()
    fun visit(t: Variant) {
        if (t in seen) return
        seen.add(t)
        downstream[t]?.forEach { visit(it) }
        order.add(t)
    }
    allTargets.forEach { visit(it) }
    order.reverse()

    for (t in order) {
        if (t in targetWeight) continue
        targetWeight[t] = downstream[t]?.sumOf { targetWeight[it] ?: 0.0 } ?: 0.0
    }

    // ── Scarcity comparator ────────────────────────────────────────────────────
    fun scarcity(c: Comp): Pair<Double, String> = Pair(basketTotal(basketAlloc, c), compKey(c))

    // ── Allocation helper: which (variant, reqSet, leadDays, edgeType) use comp? ─
    val targetsToBeAllocated = variantsReq.keys.toMutableSet()

    fun allocatableRecipes(comp: Comp): List<VariantEntry> {
        val result = mutableListOf<VariantEntry>()
        for (entry in variantsList) {
            if (entry.variant !in targetsToBeAllocated) continue
            for (reqSet in entry.alternatives) {
                if (reqSet.any { (c, _) -> c == comp }) {
                    // Return a VariantEntry with only the matching alternative selected
                    result.add(VariantEntry(entry.variant, listOf(reqSet), entry.leadDays, entry.edgeType))
                    break
                }
            }
        }
        return result
    }

    // ── Main allocation loop ───────────────────────────────────────────────────
    val allocation = mutableListOf<MutableMap<String, Any?>>()
    val variantOutputPeriod = mutableMapOf<Variant, Int>()
    val reservedForVariant = mutableMapOf<Variant, MutableMap<Comp, Double>>()
    val consumedByNode = mutableMapOf<String, Double>()
    var stepCounter = 0
    var lastPersistBoundary = 0

    progressCallback?.invoke(mapOf(
        "steps" to 0,
        "max_steps" to MAX_ALLOCATION_STEPS,
        "basket_total_qty" to roundQty(initialBasketTotalQty),
        "basket_keys" to initialBasketKeys,
        "initial_basket_total_qty" to roundQty(initialBasketTotalQty),
        "initial_basket_keys" to initialBasketKeys,
    ))

    outer@ while (true) {
        if (stepCounter >= MAX_ALLOCATION_STEPS) break
        val componentsInBasket = basketAlloc.keys.filter { basketTotal(basketAlloc, it) > 0 }
        val scarcityOrder = componentsInBasket.sortedWith(compareBy({ scarcity(it).first }, { scarcity(it).second }))
        if (scarcityOrder.isEmpty()) break

        var allocatedThisPass = false

        for (component in scarcityOrder) {
            if (stepCounter >= MAX_ALLOCATION_STEPS) break@outer
            val avail = basketTotal(basketAlloc, component)
            if (avail <= 0) continue
            val recipes = allocatableRecipes(component)
            if (recipes.isEmpty()) continue
            val totalW = recipes.sumOf { targetWeight[it.variant] ?: 0.0 }
            if (totalW <= 0) continue

            // ── Trace: critical step ───────────────────────────────────────────
            if (traceComp != null && component == traceComp) {
                traceEvents.add(mapOf(
                    "event" to "trace_component_as_critical",
                    "step_before" to stepCounter,
                    "critical_avail" to avail,
                    "recipes_count" to recipes.size,
                    "variant_keys" to recipes.map { variantKey(it.variant) },
                ))
            }
            if (traceComp != null && component != traceComp) {
                val companionRecipes = recipes.filter { entry ->
                    entry.alternatives.first().any { (c, _) -> c == traceComp }
                }
                if (companionRecipes.isNotEmpty()) {
                    traceEvents.add(mapOf(
                        "event" to "companion_opportunity",
                        "step_before" to stepCounter,
                        "critical_component" to compKey(component),
                        "critical_avail" to avail,
                        "trace_component_avail" to basketTotal(basketAlloc, traceComp),
                        "variants_with_trace_as_companion" to companionRecipes.map { variantKey(it.variant) },
                    ))
                }
            }

            // ── Proportional split by target_weight ────────────────────────────
            var splitQtys = recipes.map { entry ->
                val reqSet = entry.alternatives.first()
                val shareComponent = (targetWeight[entry.variant] ?: 0.0) / totalW * avail
                val rateC = reqSet.firstOrNull { (c, _) -> c == component }?.second ?: 1.0
                val outputQty = if (rateC > 0) shareComponent / rateC else 0.0
                SplitItem(entry.variant, reqSet, entry.leadDays, entry.edgeType, outputQty)
            }

            // Merge crumbs into dominant share
            val crumbTotal = splitQtys.filter { it.outputQty in 0.0..<MIN_ALLOCATION }.sumOf { it.outputQty }
            if (crumbTotal > 0) {
                val dominantIdx = splitQtys.indices.maxBy { splitQtys[it].outputQty }
                splitQtys = buildList {
                    splitQtys.forEachIndexed { i, item ->
                        when {
                            i == dominantIdx -> add(item.copy(outputQty = item.outputQty + crumbTotal))
                            item.outputQty >= MIN_ALLOCATION -> add(item)
                            // else: crumb merged into dominant, skip
                        }
                    }
                }
            }

            // ── Raw material trace: critical component ─────────────────────────
            val rawPattern = SkuPatterns.rawMaterialPattern(component.productId)
            if (rawPattern != null) {
                val breakdownByVariant = mutableMapOf<String, Double>()
                for (item in splitQtys) {
                    if (item.outputQty > 0) {
                        val vk = variantKey(item.variant)
                        breakdownByVariant[vk] = (breakdownByVariant[vk] ?: 0.0) + item.outputQty
                    }
                }
                log.info(
                    "allocation step={} critical_raw_material comp={} pattern={} avail={} → {}",
                    stepCounter, compKey(component), rawPattern, avail,
                    breakdownByVariant.entries.joinToString("; ") { "${it.key}=${roundQty(it.value).toLong()}" }.ifEmpty { "(none)" }
                )
                rawMaterialTrace.add(mapOf(
                    "role" to "critical",
                    "step" to stepCounter,
                    "comp_key" to compKey(component),
                    "pattern" to rawPattern,
                    "avail" to roundQty(avail),
                    "allocated" to true,
                    "breakdown" to breakdownByVariant.map { (vk, q) ->
                        mapOf("variant_key" to vk, "output_qty" to roundQty(q))
                    },
                ))
            }

            // ── Per-recipe consume + emit action ──────────────────────────────
            for (item in splitQtys) {
                if (item.outputQty <= 0) continue
                val reqSet = item.reqSet

                // Cap output by component availability
                val outputCap = reqSet
                    .filter { (_, r) -> r > 0 }
                    .minOfOrNull { (c, r) -> basketTotal(basketAlloc, c) * r }
                    ?: 0.0
                var outputQtyActual = min(item.outputQty, outputCap)

                // Discrete BOM: output must be multiple of LCM(integer rates)
                val intRates = reqSet.mapNotNull { (_, r) ->
                    if (r > 0 && abs(r - r.toLong()) < 1e-9) r.toInt() else null
                }
                if (intRates.isNotEmpty()) {
                    val lcmVal = lcmList(intRates)
                    outputQtyActual = (outputQtyActual / lcmVal).toLong() * lcmVal.toDouble()
                }

                if (outputQtyActual <= 0 || outputQtyActual < 1e-9) {
                    // Trace: skip
                    if (traceComp != null && reqSet.any { (c, _) -> c == traceComp }) {
                        traceEvents.add(mapOf(
                            "event" to "skip_action_using_trace_component",
                            "step" to stepCounter,
                            "variant_key" to variantKey(item.variant),
                            "reason" to "output_qty_actual <= 0 or < 1e-9",
                            "output_qty" to item.outputQty,
                            "output_cap" to outputCap,
                            "output_qty_actual" to outputQtyActual,
                            "req_component_keys" to reqSet.map { (c, _) -> compKey(c) },
                        ))
                    }
                    // Raw material trace: companion skipped
                    for ((c, _) in reqSet) {
                        if (c != component) {
                            val cPattern = SkuPatterns.rawMaterialPattern(c.productId)
                            if (cPattern != null) {
                                rawMaterialTrace.add(mapOf(
                                    "role" to "companion",
                                    "step" to stepCounter,
                                    "comp_key" to compKey(c),
                                    "pattern" to cPattern,
                                    "considered_but_skipped" to true,
                                    "variant_key" to variantKey(item.variant),
                                    "critical_component" to compKey(component),
                                    "reason" to "output_qty_actual <= 0 (cap or integer BOM)",
                                ))
                            }
                        }
                    }
                    continue
                }

                // Need per component: output_qty_actual / rate_i
                val needsActual = reqSet.map { (_, r) -> if (r > 0) outputQtyActual / r else 0.0 }
                var maxPeriodConsumed = 0

                for ((idx, cr) in reqSet.withIndex()) {
                    val (c, rate) = cr
                    val need = needsActual[idx]
                    val (taken, maxP) = basketConsume(basketAlloc, c, need, consumedByNode)
                    maxPeriodConsumed = max(maxPeriodConsumed, maxP)

                    // Raw material trace: companion
                    if (c != component) {
                        val cPattern = SkuPatterns.rawMaterialPattern(c.productId)
                        if (cPattern != null) {
                            val needTarget = if (rate > 0) item.outputQty / rate else 0.0
                            log.info(
                                "allocation step={} companion_raw_material comp={} pattern={} allocated={} " +
                                "because critical={} making variant={} output_qty={}",
                                stepCounter, compKey(c), cPattern, roundQty(taken).toLong(),
                                compKey(component), variantKey(item.variant), outputQtyActual
                            )
                            rawMaterialTrace.add(mapOf(
                                "role" to "companion",
                                "step" to stepCounter,
                                "comp_key" to compKey(c),
                                "pattern" to cPattern,
                                "allocated" to (taken > 0),
                                "taken" to roundQty(taken),
                                "need_actual" to roundQty(need),
                                "critical_component" to compKey(component),
                                "variant_key" to variantKey(item.variant),
                                "output_qty_actual" to roundQty(outputQtyActual),
                            ))
                        }
                    }

                    // Reserve remainder
                    val needTarget = if (rate > 0) item.outputQty / rate else 0.0
                    val short = needTarget - need
                    if (short > 0) {
                        reservedForVariant.getOrPut(item.variant) { mutableMapOf() }
                            .merge(c, short, Double::plus)
                    }
                }

                // Compute output_period
                val outputPeriod = if (maxPeriodConsumed > 0 || item.leadDays > 0) {
                    TimeUtils.periodPlusDays(maxPeriodConsumed, item.leadDays, dateToPeriod, sortedDates)
                } else maxPeriodConsumed
                variantOutputPeriod[item.variant] = max(variantOutputPeriod[item.variant] ?: 0, outputPeriod)

                val reqCompList = reqSet.map { (c, _) -> c }
                val reqRates = reqSet.map { (_, r) -> r }

                val action = mutableMapOf<String, Any?>(
                    "variant_key" to variantKey(item.variant),
                    "target_product_id" to item.variant.productId,
                    "target_location_id" to item.variant.locationId,
                    "req_component_ids" to reqCompList.map { compKey(it) },
                    "req_rates" to reqRates,
                    "qty" to roundQty(outputQtyActual),
                    "demand_id" to null,
                    "output_period" to outputPeriod,
                    "edge_type" to item.edgeType,
                    "scarcity_rank" to stepCounter,
                )
                allocation.add(action)

                // Trace: action emitted
                if (traceComp != null && traceComp in reqCompList) {
                    traceEvents.add(mapOf(
                        "event" to "action_emitted_with_trace_component",
                        "step" to stepCounter,
                        "variant_key" to variantKey(item.variant),
                        "qty" to roundQty(outputQtyActual),
                        "req_component_ids" to reqCompList.map { compKey(it) },
                        "as_critical" to (component == traceComp),
                        "critical_component" to compKey(component),
                    ))
                }

                stepCounter++
                allocatedThisPass = true

                // Progress callback
                if (progressCallback != null && stepCounter % PROGRESS_INTERVAL == 0) {
                    val bTotalQty = basketTotalQty(basketAlloc)
                    val bKeys = basketAlloc.count { basketTotal(basketAlloc, it.key) > 0 }
                    val payload = mutableMapOf<String, Any>(
                        "steps" to stepCounter,
                        "max_steps" to MAX_ALLOCATION_STEPS,
                        "basket_total_qty" to roundQty(bTotalQty),
                        "basket_keys" to bKeys,
                        "initial_basket_total_qty" to roundQty(initialBasketTotalQty),
                        "initial_basket_keys" to initialBasketKeys,
                    )
                    if (stepCounter % PERSIST_SLICE_INTERVAL == 0) {
                        payload["allocation_slice"] = allocation.subList(lastPersistBoundary, stepCounter)
                        lastPersistBoundary = stepCounter
                    }
                    progressCallback(payload)
                }

                // Add produced qty to basket for downstream processing
                basketAdd(basketAlloc, item.variant, outputQtyActual, outputPeriod)

                // Release reserved quantities for this variant
                val reserved = reservedForVariant[item.variant]
                if (reserved != null) {
                    for (c in reqCompList) {
                        val rv = reserved[c] ?: 0.0
                        if (rv > 0) reserved[c] = 0.0
                    }
                }
            }
        }

        // ── Prune basket of components unrelated to remaining demanded targets ─
        val relatedComponents = mutableSetOf<Comp>()
        val queue = ArrayDeque<Comp>(demandedTargets)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst() as Comp
            if (v in relatedComponents) continue
            relatedComponents.add(v)
            for (c in variantsReq[v as Variant] ?: emptyList()) {
                relatedComponents.add(c)
                if (c in allTargets) queue.addLast(c)
            }
        }
        val compsRemoved = basketAlloc.keys.filter { it !in relatedComponents }
        compsRemoved.forEach { basketAlloc.remove(it) }
        if (compsRemoved.isNotEmpty()) {
            progressCallback?.invoke(mapOf(
                "prune_after_step" to stepCounter,
                "prune_components" to compsRemoved.map { compKey(it) },
            ))
        }

        if (!allocatedThisPass) break
    }

    // ── Demand-fulfillment: assign produced qty to demands by priority ─────────
    val produced = mutableMapOf<ProductionKey, Double>()
    val templateByKey = mutableMapOf<ProductionKey, MutableMap<String, Any?>>()
    for (a in allocation) {
        val v = Variant(a["target_product_id"] as? String ?: "", a["target_location_id"] as? String ?: "")
        val outPer = (a["output_period"] as? Int) ?: 0
        val qty = (a["qty"] as? Double) ?: 0.0
        if (qty <= 0) continue
        val pk = ProductionKey(v, outPer)
        produced[pk] = (produced[pk] ?: 0.0) + qty
        templateByKey.putIfAbsent(pk, a)
    }

    val expanded = mutableListOf<Map<String, Any?>>()
    for ((pk, totalQty) in produced) {
        val (variant, outPer) = pk
        val matching = demandList.filter { d ->
            d["product_id"] == variant.productId &&
            (d["location_id"] ?: demandProductToLocation[d["product_id"]]) == variant.locationId
        }
        var remaining = totalQty
        val unmet = matching.associate { it["demand_id"] as String to ((it["quantity"] as? Number)?.toDouble() ?: 0.0) }
            .toMutableMap()
        for (d in matching) {
            if (remaining <= 0) break
            val did = d["demand_id"] as? String ?: continue
            val give = min(unmet[did] ?: 0.0, remaining)
            if (give <= 0) continue
            remaining -= give
            unmet[did] = (unmet[did] ?: 0.0) - give
            val t = templateByKey[pk] ?: mutableMapOf()
            expanded.add(mapOf(
                "variant_key" to t["variant_key"],
                "target_product_id" to variant.productId,
                "target_location_id" to variant.locationId,
                "req_component_ids" to (t["req_component_ids"] ?: emptyList<String>()),
                "req_rates" to (t["req_rates"] ?: emptyList<Double>()),
                "qty" to roundQty(give),
                "demand_id" to did,
                "output_period" to outPer,
                "edge_type" to t["edge_type"],
                "scarcity_rank" to t["scarcity_rank"],
            ))
        }
        // Emit unallocated remainder so production pass fires correctly
        if (remaining > 0) {
            val t = templateByKey[pk] ?: mutableMapOf()
            expanded.add(mapOf(
                "variant_key" to t["variant_key"],
                "target_product_id" to variant.productId,
                "target_location_id" to variant.locationId,
                "req_component_ids" to (t["req_component_ids"] ?: emptyList<String>()),
                "req_rates" to (t["req_rates"] ?: emptyList<Double>()),
                "qty" to roundQty(remaining),
                "demand_id" to null,
                "output_period" to outPer,
                "edge_type" to t["edge_type"],
                "scarcity_rank" to t["scarcity_rank"],
            ))
        }
    }
    val finalAllocation: List<Map<String, Any?>> = expanded

    // ── Production pass (timing): replay allocation through basket_prod ───────
    for (a in finalAllocation) {
        val v = Variant(a["target_product_id"] as? String ?: "", a["target_location_id"] as? String ?: "")
        val reqKeys = (a["req_component_ids"] as? List<*>)?.filterIsInstance<String>() ?: continue
        val reqRatesList = (a["req_rates"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 1.0 } ?: List(reqKeys.size) { 1.0 }
        val reqComps = reqKeys.map { k -> k.split("|", limit = 2).let { Comp(it[0], it.getOrElse(1) { "" }) } }
        val outputQty = (a["qty"] as? Double) ?: 0.0
        val outPer = (a["output_period"] as? Int) ?: 0
        if (outputQty <= 0 || reqComps.isEmpty()) continue

        val needs = reqComps.indices.map { i ->
            val rate = if (i < reqRatesList.size) reqRatesList[i] else 1.0
            if (rate > 0) outputQty / rate else 0.0
        }
        val canFire = reqComps.indices.all { i -> basketTotal(basketProd, reqComps[i]) >= needs[i] }
        if (!canFire) continue
        reqComps.forEachIndexed { i, c -> basketConsume(basketProd, c, needs[i]) }
        basketAdd(basketProd, v, outputQty, outPer)
    }

    // ── Feasible demands ──────────────────────────────────────────────────────
    val demandAllocated = mutableMapOf<String, Double>()
    for (a in finalAllocation) {
        val did = a["demand_id"] as? String ?: continue
        demandAllocated[did] = (demandAllocated[did] ?: 0.0) + ((a["qty"] as? Double) ?: 0.0)
    }

    val feasible = demandList.map { d ->
        val did = d["demand_id"] as? String ?: ""
        val pid = d["product_id"] as? String ?: ""
        val reqQty = (d["quantity"] as? Number)?.toDouble() ?: 0.0
        val allocQty = min(demandAllocated[did] ?: 0.0, reqQty)
        val status = when {
            allocQty >= reqQty -> "fulfilled"
            allocQty > 0 -> "partial"
            else -> "unfulfilled"
        }
        mapOf("demand_id" to did, "product_id" to pid, "requested_qty" to roundQty(reqQty), "allocated_qty" to roundQty(allocQty), "status" to status)
    }

    // ── Flatten baskets ───────────────────────────────────────────────────────
    fun basketTotalsFlat(b: BasketByTime): Map<String, Double> =
        b.mapNotNull { (comp, buckets) ->
            val total = buckets.sumOf { it.first }
            if (total > 0) compKey(comp) to roundQty(total) else null
        }.toMap()

    return AllocationResult(
        allocation = finalAllocation,
        feasibleDemands = feasible,
        basketAlloc = basketTotalsFlat(basketAlloc),
        basketProd = basketTotalsFlat(basketProd),
        rawMaterialTrace = rawMaterialTrace,
        consumedByNode = consumedByNode.mapValues { roundQty(it.value) },
        trace = if (traceComp != null) traceEvents else null,
    )
}
