package com.allocator.services

import com.allocator.config
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val log = LoggerFactory.getLogger("com.allocator.PlanningEngine")

/** Port of services/planning_engine.py — demand-to-supply planning with pegging tree. */

private const val MAX_PLAN_DEPTH = 500
private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val DEFAULT_SCORE_WEIGHTS = Triple(0.4, 0.35, 0.25)
private val BENIGN_REASONS = setOf("cycle_stopped", "cycle_detected", "inventory")
private val LATE_DATE = LocalDate.of(9999, 12, 31)

// ── Selection config (method_selection / variant_selection) ───────────────────
//
// Method-level selection supports two modes: `preference` (cheap, preference-
// ordered cascade) and `elaborate` (scored against each candidate's simulated
// sub-plan). `depth` controls how many recursion levels elaborate applies at
// (≥ 1, default 1 = root only). Legacy shape `{ elaborate: true }` is accepted.
//
// Runtime mapping: engine `depth` counts *down* from `MAX_PLAN_DEPTH` at root,
// so level k has depth == MAX_PLAN_DEPTH - k. Elaborate applies when
// `depth > MAX_PLAN_DEPTH - methodCfg.depth` (N=1 → root only; N=2 → root +
// one level down; etc.). See `shouldElaborateAtDepth`.
//
// Variants are NOT rectified symmetrically: BOM-level variety is now modeled
// as distinct make methods, so there is no "variant mode" or "variant depth"
// concept. Variant config is just the equal-split / scoring knobs it has
// always been (`multiple`, `score_weights`, `top_n`).

/** Effective method_selection config. */
internal data class MethodSelectionConfig(
    val mode: String,        // "preference" | "elaborate"
    val depth: Int,          // >= 1
    val multiple: Boolean,
    val scoreWeights: Map<String, Any?>?,  // drives elaborate scoring (commit_time / inventory_consumed / purchase)
    val depthOptimal: Boolean = false,     // when true, caller iterates depth=1..N picking the first non-improving step
) {
    val elaborate: Boolean get() = mode == "elaborate"
}

/** Effective variant_selection config. */
internal data class VariantSelectionConfig(
    val multiple: Boolean?,                 // null → equal-split among feasible; false → single best
    val scoreWeights: Map<String, Any?>?,
    val topN: Int?,
)

/** Clamp method_selection.depth to int ≥ 1; warn and default to 1 on garbage input. */
private fun parseMethodDepth(raw: Any?): Int {
    if (raw == null) return 1
    val n = (raw as? Number)?.toInt()
    if (n == null || n < 1) {
        log.warn("Invalid method_selection.depth={}; clamping to 1", raw)
        return 1
    }
    return n
}

/**
 * Resolve the effective method_selection config.
 *
 * Accepts both the new shape (`mode`, `depth`, `score_weights`) and the legacy shape
 * (`elaborate: bool`). When both are present, `mode` wins. Score weights fall back
 * to `variant_selection.score_weights` when not set at the method level, since the
 * two groups used to share weights before the variant surface was removed.
 */
internal fun resolveMethodSelection(config: Map<String, Any?>?): MethodSelectionConfig {
    val raw = (config?.get("method_selection") as? Map<*, *>)?.let {
        @Suppress("UNCHECKED_CAST") it as? Map<String, Any?>
    } ?: emptyMap()
    val modeStr = (raw["mode"] as? String)?.trim()?.lowercase()
    val mode = when (modeStr) {
        "preference", "elaborate" -> modeStr
        null -> if (raw["elaborate"] == true) "elaborate" else "preference"
        else -> {
            log.warn("Invalid method_selection.mode='{}'; defaulting to preference", modeStr)
            "preference"
        }
    }
    val depth = parseMethodDepth(raw["depth"])
    val multiple = raw["multiple"] == true
    @Suppress("UNCHECKED_CAST")
    val methodWeights = raw["score_weights"] as? Map<String, Any?>
    val weights = methodWeights ?: run {
        val vs = (config?.get("variant_selection") as? Map<*, *>)?.let {
            @Suppress("UNCHECKED_CAST") it as? Map<String, Any?>
        }
        @Suppress("UNCHECKED_CAST")
        vs?.get("score_weights") as? Map<String, Any?>
    }
    val depthOptimal = raw["depth_optimal"] == true
    return MethodSelectionConfig(mode = mode, depth = depth, multiple = multiple, scoreWeights = weights, depthOptimal = depthOptimal)
}

/** Soft cap on how many depths the optimal-depth search will try. Each iteration costs a full plan. */
internal const val MAX_OPTIMAL_DEPTH = 10

/**
 * True when we are still within the top `levels` recursion levels and should
 * therefore run elaborate/cascade scoring rather than plain preference lookup.
 *
 * Engine `depth` counts down from `MAX_PLAN_DEPTH`; level 0 (root) has
 * depth == MAX_PLAN_DEPTH, level 1 has depth == MAX_PLAN_DEPTH - 1, etc.
 * For `levels = N` we want to cover levels 0..N-1, i.e. depth > MAX_PLAN_DEPTH - N.
 */
internal fun shouldElaborateAtDepth(depth: Int, levels: Int): Boolean =
    depth > MAX_PLAN_DEPTH - levels.coerceAtLeast(1)

/** Resolve the effective variant_selection config. */
internal fun resolveVariantSelection(config: Map<String, Any?>?): VariantSelectionConfig {
    val raw = (config?.get("variant_selection") as? Map<*, *>)?.let {
        @Suppress("UNCHECKED_CAST") it as? Map<String, Any?>
    } ?: emptyMap()
    val multiple = raw["multiple"] as? Boolean
    @Suppress("UNCHECKED_CAST")
    val scoreWeights = raw["score_weights"] as? Map<String, Any?>
    val topN = (raw["top_n"] as? Number)?.toInt()?.let { max(1, it) }
    return VariantSelectionConfig(multiple = multiple, scoreWeights = scoreWeights, topN = topN)
}

/** Round a quantity-like double to a whole-unit double for API emission.
 *  Used at the JSON boundary for fields like quantity, committed_qty, shortage, etc.
 *  Internal math stays in Double; only the value handed to the caller is rounded.
 *  Rate/percentage fields (rate, fulfillment_rate, *_pct) are not quantity-like and
 *  MUST NOT be passed through this helper. */
internal fun roundQty(x: Double): Double = Math.round(x).toDouble()

/** True for commit reasons that signal a genuine planning failure — the child contributed
 *  nothing (or nothing useful) to the parent's supply chain.  Benign cycle-detection
 *  reasons and the "partial" success reason are excluded: they still count toward the
 *  effective committed quantity when calculating the bottleneck. */
private fun isHardPlanningFailure(reason: String?): Boolean {
    if (reason.isNullOrBlank()) return false
    if (reason in BENIGN_REASONS) return false
    if (reason == "partial") return false
    return true  // no_methods, no_preferred_method, depth_limit, child_failed:*, etc.
}

/** Holds the first-pass planning result for a single child material. */
private data class ChildPassResult(
    val child: Map<String, Any?>,
    val neededQty: Double,
    val effectiveQty: Double,        // committed qty from non-hard-failure rows
    val wos: List<Map<String, Any?>>,
    val pegging: Map<String, Any?>?,
    val cTimes: List<LocalDate>,
)

// ── BOM real-pair cache ────────────────────────────────────────────────────────

/** Loads (parent_id, child_id) pairs where VIRTUAL <> 'Y' from bom.csv. */
private fun loadRealBomPairsFromCsv(): Set<Pair<String, String>> {
    val candidates = listOf(
        Paths.get("").toAbsolutePath().resolve("csv/bom.csv"),
        Paths.get("").toAbsolutePath().parent?.resolve("csv/bom.csv"),
        Paths.get("").toAbsolutePath().parent?.parent?.resolve("csv/bom.csv"),
    )
    val csvFile = candidates.firstOrNull { it != null && it.toFile().exists() }?.toFile() ?: return emptySet()
    val pairs = mutableSetOf<Pair<String, String>>()
    try {
        val lines = csvFile.readLines()
        if (lines.isEmpty()) return emptySet()
        val headers = lines[0].split(",").map { it.trim().uppercase() }
        val parentIdx = headers.indexOf("PARENT_ID")
        val childIdx = headers.indexOf("CHILD_ID")
        val virtualIdx = headers.indexOf("VIRTUAL")
        if (parentIdx < 0 || childIdx < 0) return emptySet()
        for (line in lines.drop(1)) {
            val cols = line.split(",")
            val parent = cols.getOrElse(parentIdx) { "" }.trim()
            val child = cols.getOrElse(childIdx) { "" }.trim()
            if (parent.isBlank() || child.isBlank()) continue
            if (virtualIdx >= 0 && cols.getOrElse(virtualIdx) { "" }.trim().uppercase() == "Y") continue
            pairs.add(Pair(parent, child))
        }
    } catch (e: Exception) {
        log.warn("Could not read bom.csv for real BOM pairs: ${e.message}")
    }
    return pairs
}

// Loaded once at startup
val REAL_BOM_PAIRS: Set<Pair<String, String>> by lazy { loadRealBomPairsFromCsv() }

// ── Date helpers ───────────────────────────────────────────────────────────────

private fun parseDate(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    val raw = s.trim().take(10)
    if (raw.length < 10) return null
    return try { LocalDate.parse(raw, DATE_FMT) } catch (e: Exception) { null }
}

private fun dateAddDays(d: LocalDate?, days: Double): LocalDate? =
    if (d == null) null else d.plusDays(days.toLong())

private fun formatDate(d: LocalDate?): String? = d?.format(DATE_FMT)

// ── Inventory helpers ──────────────────────────────────────────────────────────

/** FIFO consumption from mutable inventory. Returns (taken, commitTime).
 *  When preferDemandId is non-null, demand-tagged buckets for that demand are consumed first,
 *  then untagged buckets (demand-tagged buckets for OTHER demands are never consumed as fallback).
 *  When preferDemandId is null the behavior is identical to the original single-pass implementation.
 */
private data class ConsumedBucket(val supplyId: String?, val qty: Double, val commitTime: String?)

private fun consumeFromInventory(
    inventory: MutableList<MutableMap<String, Any?>>,
    productId: String,
    locationId: String,
    need: Double,
    preferDemandId: Any? = null,
): List<ConsumedBucket> {
    val pid = productId.trim()
    val lid = locationId.trim()
    val sorter = compareBy<MutableMap<String, Any?>>(
        { parseDate(it["supply_date"] as? String) ?: LocalDate.MIN },
        { it["supply_id"]?.toString() ?: "" }
    )
    fun matches(b: MutableMap<String, Any?>) =
        b["product_id"]?.toString()?.trim() == pid &&
        b["location_id"]?.toString()?.trim() == lid &&
        (b["qty"] as? Number)?.toDouble() ?: 0.0 > 0

    val consumed = mutableListOf<ConsumedBucket>()
    var remaining = need

    fun consumeFrom(buckets: List<MutableMap<String, Any?>>) {
        for (b in buckets) {
            if (remaining <= 0) break
            val avail = (b["qty"] as? Number)?.toDouble() ?: 0.0
            if (avail <= 0) continue
            val take = min(avail, remaining)
            b["qty"] = avail - take
            remaining -= take
            val sd = b["supply_date"] as? String
            val commitTime = if (sd != null) formatDate(parseDate(sd)) else null
            consumed.add(ConsumedBucket(
                supplyId = b["supply_id"]?.toString(),
                qty = take,
                commitTime = commitTime,
            ))
        }
    }

    if (preferDemandId != null) {
        // Pass 1: tagged buckets for this demand only
        val tagged = inventory.filter { matches(it) && it["demand_tag"] == preferDemandId }
            .sortedWith(sorter)
        consumeFrom(tagged)
        // Pass 2: untagged buckets (no demand_tag key, or demand_tag == null)
        if (remaining > 0) {
            val untagged = inventory.filter { matches(it) && !it.containsKey("demand_tag") || (matches(it) && it["demand_tag"] == null) }
                .sortedWith(sorter)
            consumeFrom(untagged)
        }
    } else {
        // Original behavior: single pass over all matching buckets
        val buckets = inventory.filter { matches(it) }.sortedWith(sorter)
        consumeFrom(buckets)
    }

    return consumed
}

private fun copyInventory(inventory: List<Map<String, Any?>>): MutableList<MutableMap<String, Any?>> =
    inventory.map { b ->
        mutableMapOf(
            "product_id" to (b["product_id"] ?: ""),
            "location_id" to (b["location_id"] ?: ""),
            "supply_date" to b["supply_date"],
            "supply_id" to b["supply_id"],
            "qty" to ((b["qty"] as? Number)?.toDouble() ?: 0.0),
            "demand_tag" to b["demand_tag"],
        )
    }.toMutableList()

// ── BOM / variant helpers ──────────────────────────────────────────────────────

/**
 * Group BOM rows for (product_id, bom_id) by alt_group.
 * Returns list of (altKey, childMaterials). Falls back to any BOM rows matching parent if bom_id not found.
 */
internal fun variantsForMake(
    productId: String,
    locationId: String,
    quantity: Double,
    method: Map<String, Any?>,
    data: Map<String, List<Map<String, Any?>>>,
): List<Pair<String, List<Map<String, Any?>>>> {
    val bomId = (method["bom_id"] as? String)?.trim() ?: return emptyList()
    if (bomId.isBlank()) return emptyList()
    val pid = productId.trim()
    val bomList = data["bom"] ?: emptyList()

    fun buildVariants(rows: List<Map<String, Any?>>): List<Pair<String, List<Map<String, Any?>>>> {
        val byAlt = mutableMapOf<String, MutableList<Map<String, Any?>>>()
        for (b in rows) {
            val rate = (b["rate"] as? Number)?.toDouble() ?: 1.0
            if (rate <= 0) continue
            val ag = b["alt_group"]
            val altKey = if (ag != null && ag.toString().trim().isNotBlank()) ag.toString().trim() else "__null__"
            val childQty = quantity * rate
            byAlt.getOrPut(altKey) { mutableListOf() }.add(
                mapOf("product_id" to (b["child_id"] ?: ""), "location_id" to locationId, "quantity" to childQty)
            )
        }
        return byAlt.map { (k, v) -> Pair(k, v.toList()) }
    }

    // Primary: match bom_id + parent_id
    val primary = bomList.filter {
        (it["parent_id"] as? String)?.trim() == pid && (it["bom_id"] as? String)?.trim() == bomId
    }
    if (primary.isNotEmpty()) return buildVariants(primary)

    // Fallback: match parent_id only
    val fallback = bomList.filter { (it["parent_id"] as? String)?.trim() == pid }
    if (fallback.isNotEmpty()) {
        log.debug("variantsForMake: no BOM rows for bom_id={} parent={}; using fallback", bomId, pid)
        return buildVariants(fallback)
    }
    return emptyList()
}

private fun scaleChildMaterials(children: List<Map<String, Any?>>, scale: Double): List<Map<String, Any?>> {
    if (abs(scale - 1.0) < 1e-12) return children
    return children.map { c -> c + mapOf("quantity" to ((c["quantity"] as? Number)?.toDouble() ?: 0.0) * scale) }
}

internal fun childMaterialsForMove(method: Map<String, Any?>, quantity: Double): List<Map<String, Any?>> =
    listOf(mapOf(
        "product_id" to (method["product_id"] ?: ""),
        "location_id" to (method["from_location_id"] ?: ""),
        "quantity" to quantity,
    ))

// ── Method selection ───────────────────────────────────────────────────────────

/** Return all methods (buy/make/move) that can fulfill (product, location). */
fun getMethods(productId: String, locationId: String, data: Map<String, List<Map<String, Any?>>>): List<Map<String, Any?>> {
    val pid = productId.trim()
    val loc = locationId.trim()
    val virtualFallback = loc in setOf("", "VIRTUAL")
    val result = mutableListOf<Map<String, Any?>>()

    (data["method_buy"] ?: emptyList()).forEach { m ->
        if ((m["product_id"] as? String)?.trim() == pid) {
            val mLoc = (m["location_id"] as? String)?.trim() ?: ""
            if (mLoc == loc || virtualFallback) result.add(mapOf("type" to "purchase") + m)
        }
    }
    (data["method_make"] ?: emptyList()).forEach { m ->
        if ((m["product_id"] as? String)?.trim() == pid) {
            val mLoc = (m["location_id"] as? String)?.trim() ?: ""
            if (mLoc == loc || virtualFallback) result.add(mapOf("type" to "make") + m)
        }
    }
    (data["method_move"] ?: emptyList()).forEach { m ->
        if ((m["product_id"] as? String)?.trim() == pid &&
            (m["to_location_id"] as? String)?.trim() == loc) {
            result.add(mapOf("type" to "move") + m)
        }
    }
    return result
}

/** Pick best method by preference (lowest number). */
internal fun getPreferredMethod(methods: List<Map<String, Any?>>): Pair<Map<String, Any?>?, String> {
    if (methods.isEmpty()) return Pair(null, "No methods available.")
    val chosen = methods.minBy { (it["preference"] as? Number)?.toInt() ?: 0 }
    val pref = (chosen["preference"] as? Number)?.toInt() ?: 0
    val loc = (chosen["location_id"] ?: chosen["to_location_id"] ?: "").toString()
    if (methods.size <= 1) return Pair(chosen, "Only option: ${chosen["type"]} @ $loc (preference $pref).")
    val alternatives = methods.filter { it !== chosen }
        .joinToString("; ") { m -> "${m["type"]} @ ${m["location_id"] ?: m["to_location_id"] ?: ""} (preference ${(m["preference"] as? Number)?.toInt() ?: 0})" }
    return Pair(chosen, "Chosen: ${chosen["type"]} @ $loc (preference $pref). Alternatives: $alternatives.")
}

/** Normalize score weights to (commit_time, inventory_consumed, purchase) summing to 1. */
private fun normalizeScoreWeights(weights: Map<String, Any?>?): Triple<Double, Double, Double> {
    if (weights == null) return DEFAULT_SCORE_WEIGHTS
    val wC = (weights["commit_time"] as? Number)?.toDouble() ?: 0.0
    val wI = (weights["inventory_consumed"] as? Number)?.toDouble() ?: 0.0
    val wP = (weights["purchase"] as? Number)?.toDouble() ?: 0.0
    val total = wC + wI + wP
    if (total <= 0) return DEFAULT_SCORE_WEIGHTS
    return Triple(wC / total, wI / total, wP / total)
}

/** Score a variant by simulating planning its child components. Returns (maxCommit, consumed, purchaseQty, anyFailed). */
private fun scoreVariant(
    altKey: String,
    childList: List<Map<String, Any?>>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    reqDt: LocalDate?,
    leadDays: Double,
    planningPath: Set<Pair<String, String>>,
    depth: Int,
): Quadruple<LocalDate?, Double, Double, Boolean> {
    val invCopy = copyInventory(inventory)
    val beforeQty = invCopy.sumOf { (it["qty"] as? Number)?.toDouble() ?: 0.0 }
    var maxCommit: LocalDate? = null
    var purchaseQty = 0.0
    var anyFailed = false
    for (c in childList) {
        val cReqDt = dateAddDays(reqDt, -leadDays)
        val cDemand = mapOf(
            "demand_id" to null,
            "product_id" to c["product_id"],
            "location_id" to c["location_id"],
            "quantity" to c["quantity"],
            "request_due_time" to formatDate(cReqDt),
            "request_time" to formatDate(cReqDt),
        )
        val (solvedList, cWos, _) = plan(cDemand, invCopy, data, cReqDt, depth = depth - 1, planningPath = planningPath)
        for (s in solvedList) {
            val qty = (s["quantity"] as? Number)?.toDouble() ?: 0.0
            val ct = s["commit_time"] as? String
            val reason = s["commit_reason"] as? String ?: ""
            val hardFailed = ct == null || (reason.isNotBlank() && reason !in BENIGN_REASONS)
            if (hardFailed) anyFailed = true
            if (qty <= 0) continue
            if (ct != null) {
                val dt = parseDate(ct)
                if (dt != null && (maxCommit == null || dt > maxCommit)) maxCommit = dt
            }
        }
        for (wo in cWos) {
            if (wo["method"] == "purchase") purchaseQty += (wo["quantity"] as? Number)?.toDouble() ?: 0.0
        }
    }
    val afterQty = invCopy.sumOf { (it["qty"] as? Number)?.toDouble() ?: 0.0 }
    val consumed = beforeQty - afterQty
    if (anyFailed) maxCommit = null
    return Quadruple(maxCommit, consumed, purchaseQty, anyFailed)
}

/** Tuple-4 helper (Kotlin lacks built-in quadruple). */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * Return the first method (in ascending preference order) whose root BOM passes cascade's
 * feasibility probe, or null if every method fails.  Mirrors the probe inside
 * [getPreferredMethodCascade]; exposed so consolidation can skip demands whose parent can
 * never roll up (avoids phantom WOs for reachable siblings of an unreachable component).
 */
internal fun firstFeasibleMethod(
    methods: List<Map<String, Any?>>,
    demand: Map<String, Any?>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    requestTimeDt: LocalDate?,
    depth: Int,
    planningPath: Set<Pair<String, String>>,
): Map<String, Any?>? {
    if (methods.isEmpty()) return null
    val productId = demand["product_id"] as? String ?: ""
    val locationId = demand["location_id"] as? String ?: ""
    val quantity = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
    val reqTimeStr = demand["request_due_time"] as? String ?: demand["request_time"] as? String
    val reqDt = parseDate(reqTimeStr) ?: requestTimeDt
    val sorted = methods.sortedBy { (it["preference"] as? Number)?.toInt() ?: 0 }
    for (m in sorted) {
        val productionLocation = (if (m["type"] == "move") m["to_location_id"] else m["location_id"])?.toString() ?: locationId
        val leadDays = leadDaysForMethod(m)
        val failed = when (m["type"]) {
            "purchase" -> false
            "move" -> {
                val children = childMaterialsForMove(m, quantity)
                scoreVariant("feasibility", children, inventory, data, reqDt, leadDays, planningPath, depth - 1).fourth
            }
            "make" -> {
                val variants = variantsForMake(productId, productionLocation, quantity, m, data)
                variants.isEmpty() || variants.all { (altKey, childList) ->
                    scoreVariant(altKey, childList, inventory, data, reqDt, leadDays, planningPath, depth - 1).fourth
                }
            }
            else -> false
        }
        if (!failed) return m
    }
    return null
}

/**
 * Cascade method selection: try each method in ascending preference order.
 * Returns the first method whose children can be successfully planned.
 * Falls back to preference-only when we're past method_selection.depth levels
 * from the root, or when only one method exists.
 */
internal fun getPreferredMethodCascade(
    methods: List<Map<String, Any?>>,
    demand: Map<String, Any?>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    requestTimeDt: LocalDate?,
    config: Map<String, Any?>?,
    depth: Int,
    planningPath: Set<Pair<String, String>>,
): Pair<Map<String, Any?>?, String> {
    if (methods.isEmpty()) return Pair(null, "No methods available.")
    val levels = resolveMethodSelection(config).depth
    if (!shouldElaborateAtDepth(depth, levels) || methods.size <= 1) return getPreferredMethod(methods)

    val productId = demand["product_id"] as? String ?: ""
    val locationId = demand["location_id"] as? String ?: ""
    val quantity = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
    val reqTimeStr = demand["request_due_time"] as? String ?: demand["request_time"] as? String
    val reqDt = parseDate(reqTimeStr) ?: requestTimeDt

    val sorted = methods.sortedBy { (it["preference"] as? Number)?.toInt() ?: 0 }

    for (m in sorted) {
        val productionLocation = (if (m["type"] == "move") m["to_location_id"] else m["location_id"])?.toString() ?: locationId
        val leadDays = leadDaysForMethod(m)

        val failed = when (m["type"]) {
            "purchase" -> false
            "move" -> {
                val children = childMaterialsForMove(m, quantity)
                scoreVariant("cascade", children, inventory, data, reqDt, leadDays, planningPath, depth - 1).fourth
            }
            "make" -> {
                val variants = variantsForMake(productId, productionLocation, quantity, m, data)
                variants.isEmpty() || variants.all { (altKey, childList) ->
                    scoreVariant(altKey, childList, inventory, data, reqDt, leadDays, planningPath, depth - 1).fourth
                }
            }
            else -> false
        }

        if (!failed) {
            val loc = (m["location_id"] ?: m["to_location_id"] ?: "").toString()
            val pref = (m["preference"] as? Number)?.toInt() ?: 0
            return Pair(m, "Cascade (by preference): ${m["type"]} @ $loc (pref $pref) — first feasible.")
        }
    }

    log.debug("cascade: all methods failed for {}@{}, falling back to lowest-preference", productId, locationId)
    return getPreferredMethod(methods)
}

/**
 * Elaborate method selection: simulate one planning level per method, score, and pick best.
 * Falls back to preference-only when we're past method_selection.depth levels from the
 * root, or when only one method exists.
 */
internal fun getPreferredMethodElaborate(
    methods: List<Map<String, Any?>>,
    demand: Map<String, Any?>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    requestTimeDt: LocalDate?,
    config: Map<String, Any?>?,
    depth: Int,
    planningPath: Set<Pair<String, String>>,
): Pair<Map<String, Any?>?, String> {
    if (methods.isEmpty()) return Pair(null, "No methods available.")
    val methodCfg = resolveMethodSelection(config)
    if (!shouldElaborateAtDepth(depth, methodCfg.depth) || methods.size <= 1) return getPreferredMethod(methods)

    val scoreWeights = methodCfg.scoreWeights
    val (wCommit, wInv, wPurchase) = normalizeScoreWeights(scoreWeights)
    val productId = demand["product_id"] as? String ?: ""
    val locationId = demand["location_id"] as? String ?: ""
    val quantity = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
    val reqTimeStr = demand["request_due_time"] as? String ?: demand["request_time"] as? String

    data class Scored(val score: Double, val method: Map<String, Any?>, val ts: Double, val consumed: Double, val purchase: Double, val failed: Boolean)

    val scored = methods.map { m ->
        val invCopy = copyInventory(inventory)
        val productionLocation = (m["location_id"] ?: m["to_location_id"] ?: locationId) as? String ?: locationId
        val reqDt = parseDate(reqTimeStr) ?: requestTimeDt
        val leadDays = when (m["type"]) {
            "make" -> (m["lead_time"] as? Number)?.toDouble() ?: 0.0
            "move" -> (m["transit_time"] as? Number)?.toDouble() ?: 0.0
            "purchase" -> (m["lead_days_supply"] as? Number)?.toDouble() ?: 0.0
            else -> 0.0
        }
        val cReqDt = dateAddDays(reqDt, -leadDays)
        var maxCommit: LocalDate? = null
        var purchaseQty = 0.0
        var anyFailed = false

        val childMaterials = when (m["type"]) {
            "make" -> {
                val variants = variantsForMake(productId, productionLocation, quantity, m, data)
                if (variants.isEmpty()) return@map Scored(-1e9, m, LATE_DATE.toEpochDay().toDouble(), 0.0, 0.0, true)
                val (variantList, _) = getPreferredVariants(
                    variants, invCopy, data, reqDt, leadDays, planningPath, depth - 1, quantity,
                    multiple = false, scoreWeights = scoreWeights, topN = null
                )
                variantList.flatMap { (cm, _, _) -> cm }
            }
            "move" -> childMaterialsForMove(m, quantity)
            else -> emptyList()
        }

        val beforeQty = invCopy.sumOf { (it["qty"] as? Number)?.toDouble() ?: 0.0 }
        for (c in childMaterials) {
            val cDemand = mapOf(
                "demand_id" to null,
                "product_id" to c["product_id"],
                "location_id" to c["location_id"],
                "quantity" to c["quantity"],
                "request_due_time" to formatDate(cReqDt),
                "request_time" to formatDate(cReqDt),
            )
            val (solvedList, cWos, _) = plan(cDemand, invCopy, data, cReqDt, depth = depth - 1, planningPath = planningPath, config = config)
            for (s in solvedList) {
                if ((s["quantity"] as? Number)?.toDouble() ?: 0.0 <= 0) continue
                val ct = s["commit_time"] as? String
                val reason = s["commit_reason"] as? String ?: ""
                if (ct == null || (reason.isNotBlank() && reason !in BENIGN_REASONS)) anyFailed = true
                if (ct != null) parseDate(ct)?.let { dt -> if (maxCommit == null || dt > maxCommit) maxCommit = dt }
            }
            for (wo in cWos) {
                if (wo["method"] == "purchase") purchaseQty += (wo["quantity"] as? Number)?.toDouble() ?: 0.0
            }
        }
        val afterQty = invCopy.sumOf { (it["qty"] as? Number)?.toDouble() ?: 0.0 }
        val consumed = beforeQty - afterQty
        if (anyFailed) maxCommit = null
        val ts = maxCommit?.toEpochDay()?.toDouble() ?: LATE_DATE.toEpochDay().toDouble()
        Scored(0.0, m, ts, consumed, purchaseQty, anyFailed)
    }.toMutableList()

    // Normalize scores
    val validTs = scored.filter { !it.failed }.map { it.ts }
    val consList = scored.map { it.consumed }
    val purchList = scored.map { it.purchase }
    var spanTs = if (validTs.size >= 2) validTs.max() - validTs.min() else 1.0
    var spanC = if (consList.isNotEmpty()) consList.max() - consList.min() else 1.0
    var spanP = if (purchList.isNotEmpty()) purchList.max() - purchList.min() else 1.0
    if (spanTs <= 0) spanTs = 1.0
    if (spanC <= 0) spanC = 1.0
    if (spanP <= 0) spanP = 1.0
    val tsMax = validTs.maxOrNull() ?: 0.0

    val finalScored = scored.map { s ->
        if (s.failed) s.copy(score = -1e9)
        else {
            val normCommit = max(0.0, min(1.0, (tsMax - s.ts) / spanTs))
            val normInv = max(0.0, min(1.0, (s.consumed - consList.min()) / spanC))
            val normP = max(0.0, min(1.0, (purchList.max() - s.purchase) / spanP))
            s.copy(score = wCommit * normCommit + wInv * normInv + wPurchase * normP)
        }
    }.sortedByDescending { it.score }

    val best = finalScored.first()
    if (best.failed) return getPreferredMethod(methods)
    val loc = (best.method["location_id"] ?: best.method["to_location_id"] ?: "").toString()
    return Pair(best.method, "Chosen (elaborate score): ${best.method["type"]} @ $loc " +
        "(inventory_consumed=${best.consumed.toLong()}, purchase=${best.purchase.toLong()}).")
}

// ── Variant selection ──────────────────────────────────────────────────────────

/** Result item: (child_materials, alt_key, quantity). */
typealias VariantResultItem = Triple<List<Map<String, Any?>>, String, Double>

/**
 * Score and select variants.
 * multiple=false → single best. multiple=null → all feasible split equally. topN limits how many.
 */
fun getPreferredVariants(
    variants: List<Pair<String, List<Map<String, Any?>>>>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    reqDt: LocalDate?,
    leadDays: Double,
    planningPath: Set<Pair<String, String>>,
    depth: Int,
    demandNetQty: Double,
    multiple: Boolean? = null,
    scoreWeights: Map<String, Any?>? = null,
    topN: Int? = null,
): Pair<List<VariantResultItem>, String> {
    if (variants.isEmpty()) return Pair(emptyList(), "No variants.")
    if (variants.size == 1) {
        val (altKey, childList) = variants[0]
        return Pair(listOf(Triple(childList, altKey, demandNetQty)),
            "Single variant (ALT_GROUP=$altKey); ${childList.size} component(s).")
    }
    log.info("multi-variant: {} variants alt_groups={}", variants.size, variants.map { it.first })

    val (wCommit, wInv, wPurchase) = normalizeScoreWeights(scoreWeights)

    data class ScoredVariant(
        val sc: Quadruple<LocalDate?, Double, Double, Boolean>,
        val altKey: String,
        val childList: List<Map<String, Any?>>,
        val failed: Boolean,
    )

    val needRanking = multiple == false || (topN != null && topN >= 1)
    val scored = variants.map { (altKey, childList) ->
        val sc = scoreVariant(altKey, childList, inventory, data, reqDt, leadDays, planningPath, depth)
        ScoredVariant(sc, altKey, childList, sc.fourth)
    }

    val rankedScored = if (needRanking) {
        val commits = scored.map { it.sc.first }
        val consumedList = scored.map { it.sc.second }
        val purchaseList = scored.map { it.sc.third }
        val validTs = commits.filterNotNull().filter { it != LATE_DATE }.map { it.toEpochDay().toDouble() }
        var spanTs = if (validTs.size >= 2) validTs.max() - validTs.min() else 1.0
        var spanC = if (consumedList.isNotEmpty()) consumedList.max() - consumedList.min() else 1.0
        var spanP = if (purchaseList.isNotEmpty()) purchaseList.max() - purchaseList.min() else 1.0
        if (spanTs <= 0) spanTs = 1.0
        if (spanC <= 0) spanC = 1.0
        if (spanP <= 0) spanP = 1.0
        val tsMaxVal = validTs.maxOrNull() ?: 0.0

        val withScore = scored.mapIndexed { i, sv ->
            val commitTs = commits[i]?.toEpochDay()?.toDouble() ?: LATE_DATE.toEpochDay().toDouble()
            val consumed = consumedList[i]
            val purchase = purchaseList[i]
            if (sv.failed) Pair(-1e9, sv)
            else {
                val normCommit = if (spanTs > 0 && commitTs != LATE_DATE.toEpochDay().toDouble()) max(0.0, min(1.0, (tsMaxVal - commitTs) / spanTs)) else 1.0
                val normInv = max(0.0, min(1.0, (consumed - consumedList.min()) / spanC))
                val normPurchase = max(0.0, min(1.0, (purchaseList.max() - purchase) / spanP))
                Pair(wCommit * normCommit + wInv * normInv + wPurchase * normPurchase, sv)
            }
        }.sortedByDescending { it.first }
        withScore.map { it.second }
    } else scored

    if (multiple == false) {
        val best = rankedScored.first()
        val others = rankedScored.drop(1).map { it.altKey }
        val (mc, negConsumed, purchase, _) = best.sc
        return Pair(
            listOf(Triple(best.childList, best.altKey, demandNetQty)),
            "Chosen variant ALT_GROUP=${best.altKey} (${best.childList.size} component(s)). " +
            "Score: commit_time=${if (mc == null || mc == LATE_DATE) "—" else mc.format(DATE_FMT)}, " +
            "inventory_consumed=${negConsumed.toLong()}, purchase_qty=${purchase.toLong()}. " +
            "Alternatives: ${others.joinToString(", ")}."
        )
    }

    // multiple=null: equal split among feasible, optionally limited to topN
    var feasible = rankedScored.filter { !it.failed }.map { Pair(it.altKey, it.childList) }
    if (feasible.isEmpty()) feasible = listOf(Pair(rankedScored[0].altKey, rankedScored[0].childList))
    if (topN != null && topN >= 1) feasible = feasible.take(topN)
    val n = feasible.size
    val demandInt = demandNetQty.toLong()
    val useIntegerSplit = n > 0 && abs(demandNetQty - demandInt.toDouble()) < 1e-9
    val qtyPerVariant = if (useIntegerSplit && n > 0) {
        val base = demandInt / n
        val remainder = (demandInt % n).toInt()
        List(remainder) { (base + 1).toDouble() } + List(n - remainder) { base.toDouble() }
    } else {
        val qtyEach = if (n > 0) demandNetQty / n else demandNetQty
        List(n) { qtyEach }
    }
    val result = feasible.mapIndexed { i, (altKey, childList) ->
        val qtyI = qtyPerVariant.getOrElse(i) { if (n > 0) demandNetQty / n else demandNetQty }
        val scale = if (demandNetQty > 1e-12) qtyI / demandNetQty else 1.0
        Triple(scaleChildMaterials(childList, scale), altKey, qtyI)
    }
    val altKeys = result.map { it.second }
    val qtyStr = qtyPerVariant.joinToString(", ") { roundQty(it).toLong().toString() }
    val totalStr = roundQty(demandNetQty).toLong().toString()
    val expl = if (topN != null && topN >= 1)
        "Top $n variant(s) (by score): ALT_GROUP=${altKeys.joinToString(", ")}; quantities: $qtyStr (total $totalStr)."
    else
        "Multiple variants ($n): ALT_GROUP=${altKeys.joinToString(", ")}; quantities: $qtyStr (total $totalStr)."
    return Pair(result, expl)
}

// ── Product location helpers ───────────────────────────────────────────────────

private fun maxLotSize(productId: String, locationId: String, data: Map<String, List<Map<String, Any?>>>): Double? {
    for (pl in data["productlocation"] ?: emptyList()) {
        if ((pl["product_id"] as? String)?.trim() == productId && (pl["location_id"] as? String)?.trim() == locationId) {
            val v = pl["max_lot_size"]
            if (v != null) return try { (v as? Number)?.toDouble() ?: v.toString().toDouble() } catch (e: Exception) { null }
        }
    }
    return null
}

private fun getProdArea(productId: String, locationId: String, data: Map<String, List<Map<String, Any?>>>): String? {
    for (pl in data["productlocation"] ?: emptyList()) {
        if ((pl["product_id"] as? String)?.trim() == productId && (pl["location_id"] as? String)?.trim() == locationId) {
            val v = pl["prod_area"]?.toString()?.trim()
            if (!v.isNullOrBlank()) return v
        }
    }
    return null
}

// ── Core recursive planning function ──────────────────────────────────────────

/**
 * Plan one demand. Returns (committedDemands, workOrders, peggingTreeNode).
 * Port of planning_engine.plan().
 */
fun plan(
    demand: Map<String, Any?>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    requestTimeDt: LocalDate?,
    depth: Int = MAX_PLAN_DEPTH,
    planningPath: Set<Pair<String, String>> = emptySet(),
    config: Map<String, Any?>? = null,
    preferDemandId: Any? = null,
    overrideIndex: Map<String, Map<String, Any?>> = emptyMap(),
): Triple<List<Map<String, Any?>>, List<Map<String, Any?>>, Map<String, Any?>?> {

    val productId = (demand["product_id"] as? String)?.trim() ?: ""
    val locationId = (demand["location_id"] as? String)?.trim() ?: ""
    val quantity = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
    val demandId = demand["demand_id"]
    val reqTimeStr = demand["request_due_time"] as? String ?: demand["request_time"] as? String
    val customerId = demand["customer_id"]
    val customer = demand["customer"]

    fun demandNode(
        children: List<Map<String, Any?>>,
        commitTime: String? = null,
        commitReason: String? = null,
        committedQty: Double = quantity,
        childrenRelation: String? = null,
    ): Map<String, Any?> = buildMap<String, Any?> {
        put("type", "demand")
        put("demand_id", demandId)
        put("product_id", productId)
        put("location_id", locationId)
        put("quantity", roundQty(quantity))
        put("committed_qty", roundQty(committedQty))
        put("request_time", reqTimeStr)
        put("commit_time", commitTime)
        put("commit_reason", commitReason)
        put("children", children)
        if (childrenRelation != null) put("children_relation", childrenRelation)
    }

    fun committedRow(qty: Double, commitTime: String?, commitReason: String? = null) = buildMap<String, Any?> {
        put("demand_id", demandId)
        put("customer_id", customerId)
        put("customer", customer)
        put("product_id", productId)
        put("location_id", locationId)
        put("quantity", roundQty(qty))
        put("request_time", reqTimeStr)
        put("commit_time", commitTime)
        if (commitReason != null) put("commit_reason", commitReason)
    }

    if (quantity <= 0) return Triple(emptyList(), emptyList(), null)

    if (depth <= 0) {
        return Triple(listOf(committedRow(quantity, reqTimeStr, "depth_limit")), emptyList(), demandNode(emptyList(), reqTimeStr, "depth_limit", committedQty = 0.0))
    }

    val key = Pair(productId, locationId)
    if (key in planningPath) {
        return Triple(listOf(committedRow(quantity, reqTimeStr, "cycle_stopped")), emptyList(), demandNode(emptyList(), reqTimeStr, "cycle_stopped", committedQty = 0.0))
    }
    val path = planningPath + key

    // 1) Fulfill from inventory (FIFO)
    val consumedBuckets = consumeFromInventory(inventory, productId, locationId, quantity, preferDemandId)
    val taken = consumedBuckets.sumOf { it.qty }
    val fulfillCommitTime = consumedBuckets.firstOrNull()?.commitTime
    val demandFulfilledList = mutableListOf<Map<String, Any?>>()
    val peggingChildren = mutableListOf<Map<String, Any?>>()

    if (taken > 0) {
        val commitForFulfilled = fulfillCommitTime ?: reqTimeStr
        demandFulfilledList.add(committedRow(taken, commitForFulfilled, "inventory"))
        // One supply node per consumed bucket so each node carries its exact supply_id
        for (bucket in consumedBuckets) {
            peggingChildren.add(mapOf(
                "type" to "supply",
                "product_id" to productId,
                "location_id" to locationId,
                "supply_id" to bucket.supplyId,
                "quantity" to roundQty(bucket.qty),
                "commit_time" to (bucket.commitTime ?: reqTimeStr),
                "children" to emptyList<Any>(),
            ))
        }
    }

    val demandNetQty = quantity - taken
    if (demandNetQty <= 1e-9) {
        // Treat sub-epsilon residuals as fully satisfied (prevents floating-point drift from
        // cascading into child_failed when a consolidation proportional share rounds down by ε)
        return Triple(demandFulfilledList, emptyList(), demandNode(peggingChildren, fulfillCommitTime, committedQty = taken))
    }

    // 2) Get methods
    val purchaseAllowed = config?.get("purchase_allowed") != false  // default true
    val methods = getMethods(productId, locationId, data)
        .let { if (purchaseAllowed) it else it.filter { m -> m["type"] != "purchase" } }
    if (methods.isEmpty()) {
        demandFulfilledList.add(committedRow(demandNetQty, reqTimeStr, "no_methods"))
        return Triple(demandFulfilledList, emptyList(), demandNode(peggingChildren, reqTimeStr, "no_methods", committedQty = taken))
    }

    if (methods.size > 1) {
        log.info("multi-method: demand_id={} product_id={} location_id={} count={} types={}",
            demandId, productId, locationId, methods.size, methods.map { it["type"] })
    }

    val methodCfg = resolveMethodSelection(config)
    val variantCfg = resolveVariantSelection(config)
    val useElaborateMethod = methodCfg.elaborate
    val useMultipleMethods = methodCfg.multiple && methods.size > 1
    val useSingleVariant = variantCfg.multiple == false
    val scoreWeights = variantCfg.scoreWeights
    val topN = variantCfg.topN

    // Shortage tolerance: tiny partial-fulfillment gaps are collapsed to "no bottleneck".
    // Tolerance = max(absolute, relative * qty). Absolute floor kills sub-unit drift;
    // relative floor kills cascade-ratio artifacts on large demands. Configurable via
    //   shortage_tolerance: { absolute: 1.0, relative: 0.01 }
    val shortageToleranceCfg = (config?.get("shortage_tolerance") as? Map<*, *>)?.let {
        @Suppress("UNCHECKED_CAST") it as? Map<String, Any?>
    }
    val shortageAbs = (shortageToleranceCfg?.get("absolute") as? Number)?.toDouble() ?: 1.0
    val shortageRel = (shortageToleranceCfg?.get("relative") as? Number)?.toDouble() ?: 0.01
    fun shortageTolerance(qty: Double) = maxOf(shortageAbs, shortageRel * qty)

    // ── Multiple methods: equal split (with per-method two-pass probe) ─────────
    //
    // For each method slot:
    //   a) Compute child materials at the slot's methodQty.
    //   b) Snapshot inventory; first pass → plan each child at its full needed qty.
    //   c) If any child is short, cap the slot at the bottleneck ratio
    //      (min effective/needed × methodQty), restore inventory, rescale children,
    //      and re-plan (second pass).
    //   d) Build the WO at methodAchievable (not methodQty).
    //   e) Aggregate: totalAchievable = Σ methodAchievable across slots.
    //      Emit ONE committed row with commit_reason="partial" if any slot shorted.
    if (useMultipleMethods) {
        val n = methods.size
        val demandInt = demandNetQty.toLong()
        val useIntSplit = n > 0 && abs(demandNetQty - demandInt.toDouble()) < 1e-9
        val qtyPerMethod = if (useIntSplit) {
            val base = demandInt / n; val rem = (demandInt % n).toInt()
            List(rem) { (base + 1).toDouble() } + List(n - rem) { base.toDouble() }
        } else {
            val ea = if (n > 0) demandNetQty / n else demandNetQty; List(n) { ea }
        }

        val allWos = mutableListOf<Map<String, Any?>>()
        val allPeggingWoNodes = mutableListOf<Map<String, Any?>>()
        var latestCommit: LocalDate? = null
        val reqDt = parseDate(reqTimeStr) ?: requestTimeDt ?: LocalDate.now()
        var totalMethodAchievable = 0.0
        var anyMethodShort = false

        for ((idx, m) in methods.withIndex()) {
            val methodQty = qtyPerMethod.getOrElse(idx) { 0.0 }
            if (methodQty <= 1e-9) continue
            val productionLocation = (if (m["type"] == "move") m["to_location_id"] else m["location_id"])?.toString() ?: locationId
            val leadDays = leadDaysForMethod(m)

            var woChildrenRelation: String? = null
            val (childMaterials, variantExplanation) = when (m["type"]) {
                "make" -> {
                    val variants = variantsForMake(productId, productionLocation, methodQty, m, data)
                    val (variantList, ve) = getPreferredVariants(variants, inventory, data, reqDt, leadDays, path, depth, methodQty,
                        multiple = if (useSingleVariant) false else null, scoreWeights = scoreWeights, topN = topN)
                    val cm = variantList.flatMap { (childList, _, _) -> childList }
                    if (variantList.size > 1) {
                        woChildrenRelation = if (variantList.all { (childList, _, _) -> childList.size == 1 }) "or" else "and"
                    } else if (variantList.size == 1 && (variantList[0].first.size) > 1) {
                        woChildrenRelation = "and"
                    }
                    Pair(cm, ve)
                }
                "move" -> Pair(childMaterialsForMove(m, methodQty), "")
                else -> Pair(emptyList(), "")
            }

            // Snapshot inventory so we can restore before the second pass if a deeper cap is found.
            val methodInvSnap = copyInventory(inventory)

            // ── First pass: plan each child at its full needed qty ───────────
            val methodChildResults = mutableListOf<ChildPassResult>()
            for (c in childMaterials) {
                if (m["type"] == "make") {
                    val parentKey = productId.trim()
                    val childKey = (c["product_id"] as? String)?.trim() ?: ""
                    if (Pair(parentKey, childKey) in REAL_BOM_PAIRS) {
                        log.info("planning: real BOM (VIRTUAL<>Y) parent={} child={} demand={} qty={} (equal-split)", parentKey, childKey, demandId, c["quantity"])
                    }
                }
                val cReqDt = dateAddDays(reqDt, -leadDays)
                val neededQty = (c["quantity"] as? Number)?.toDouble() ?: 0.0
                val cDemand = mapOf("demand_id" to demandId, "product_id" to c["product_id"], "location_id" to c["location_id"],
                    "quantity" to neededQty, "request_due_time" to formatDate(cReqDt), "request_time" to formatDate(cReqDt))
                val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, overrideIndex = overrideIndex)
                // effectiveQty: sum of qty committed by non-hard-failure rows, excluding cycle
                // terminators (which return no physical supply). Matches L991-996 in single-method path.
                val effectiveQty = solvedList.sumOf { s ->
                    val r = s["commit_reason"] as? String
                    if (r == "cycle_stopped" || r == "cycle_detected") 0.0
                    else if (!isHardPlanningFailure(r)) (s["quantity"] as? Number)?.toDouble() ?: 0.0
                    else 0.0
                }
                val cTimes = solvedList.mapNotNull { s -> parseDate(s["commit_time"] as? String) }
                methodChildResults.add(ChildPassResult(c, neededQty, effectiveQty, cWos, cPegging, cTimes))
            }

            val methodChildShort = methodChildResults.any { cr -> cr.effectiveQty < cr.neededQty - 1e-9 }
            val methodAchievable: Double
            val childWos = mutableListOf<Map<String, Any?>>()
            val commitTimes = mutableListOf<LocalDate>()
            val childPeggingNodes = mutableListOf<Map<String, Any?>>()

            if (!methodChildShort || childMaterials.isEmpty()) {
                methodAchievable = methodQty
                methodChildResults.forEach { cr ->
                    childWos.addAll(cr.wos)
                    if (cr.pegging != null) childPeggingNodes.add(cr.pegging)
                    commitTimes.addAll(cr.cTimes)
                }
            } else {
                val rawAchievable = methodChildResults.minOf { cr ->
                    if (cr.neededQty > 1e-9) cr.effectiveQty * methodQty / cr.neededQty else methodQty
                }
                // Close-enough collapse: tiny shortages are numerical noise, not real bottlenecks.
                // Threshold sourced from config.shortage_tolerance (see top of plan()).
                val capped = if (methodQty - rawAchievable < shortageTolerance(methodQty)) methodQty
                             else rawAchievable.coerceIn(0.0, methodQty)
                if (capped <= 1e-9) {
                    // This method is completely blocked — deep raw material is exhausted.
                    // Contribute 0 to the split and preserve the first-pass pegging so the UI
                    // still shows WHICH child hit zero. No WO is emitted for this slot.
                    methodChildResults.forEach { cr ->
                        if (cr.pegging != null) childPeggingNodes.add(cr.pegging)
                    }
                    val methodType = m["type"] as? String ?: ""
                    allPeggingWoNodes.add(buildWoNode(productId, productionLocation, 0.0, methodType, m,
                        reqDt, null, 0, 0.0,
                        "Equal split: ${methodType}@${productionLocation} blocked (deep child exhausted; 0 of ${roundQty(methodQty).toLong()})",
                        variantExplanation, woChildrenRelation, childPeggingNodes))
                    anyMethodShort = true
                    continue
                }
                methodAchievable = capped
                anyMethodShort = true

                // ── Second pass: restore inventory and re-plan at scaled qty ──
                inventory.clear()
                inventory.addAll(methodInvSnap)
                val scale = methodAchievable / methodQty
                val scaledChildren = scaleChildMaterials(childMaterials, scale)
                for (c in scaledChildren) {
                    val cReqDt = dateAddDays(reqDt, -leadDays)
                    val cDemand = mapOf("demand_id" to demandId, "product_id" to c["product_id"], "location_id" to c["location_id"],
                        "quantity" to c["quantity"], "request_due_time" to formatDate(cReqDt), "request_time" to formatDate(cReqDt))
                    val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, overrideIndex = overrideIndex)
                    childWos.addAll(cWos)
                    if (cPegging != null) childPeggingNodes.add(cPegging)
                    solvedList.forEach { s -> parseDate(s["commit_time"] as? String)?.let { commitTimes.add(it) } }
                }
            }

            val startDt = computeStartDt(reqDt, leadDays, commitTimes)
            val (wos, lotCount, lastEnd, lotSizeVal) = buildWorkOrders(productId, productionLocation, methodAchievable, leadDays, startDt, m, demandId, data)
            allWos.addAll(wos); allWos.addAll(childWos)
            if (lastEnd != null && (latestCommit == null || lastEnd > latestCommit)) latestCommit = lastEnd

            val methodType = m["type"] as? String ?: ""
            val methodLabel = "$methodType@$productionLocation"
            val woChildren = if (methodType == "purchase") listOf(mapOf("type" to "purchase", "product_id" to productId, "location_id" to productionLocation, "quantity" to roundQty(methodAchievable), "children" to emptyList<Any>()))
                             else childPeggingNodes
            val methodLabelExpl = "Equal split: $methodLabel (qty ${roundQty(methodAchievable).toLong()} of ${roundQty(methodQty).toLong()})"
            allPeggingWoNodes.add(buildWoNode(productId, productionLocation, methodAchievable, methodType, m, startDt, lastEnd, lotCount, lotSizeVal, methodLabelExpl, variantExplanation, woChildrenRelation, woChildren))
            totalMethodAchievable += methodAchievable
        }

        val partialReason = if (anyMethodShort && totalMethodAchievable < demandNetQty - 1e-9) "partial" else null
        demandFulfilledList.add(committedRow(totalMethodAchievable, formatDate(latestCommit), partialReason))
        return Triple(demandFulfilledList, allWos, demandNode(allPeggingWoNodes, formatDate(latestCommit), partialReason, committedQty = taken + totalMethodAchievable))
    }

    // ── Override lookup for this (product, location, demand) ──────────────────
    // Exact match only: demand-specific WOs match demand-scoped keys; null-demand WOs match
    // location-level keys. No fallback — prevents a location-level override (saved for a
    // consolidated/null-demand WO) from leaking to unrelated demand-specific WOs.
    val demandIdStr = demandId?.toString()?.trim() ?: ""
    val methodOverride = if (demandIdStr.isNotBlank())
        overrideIndex["method_selection|$productId|$locationId|$demandIdStr"]
    else
        overrideIndex["method_selection|$productId|$locationId"]
    val variantOverride = if (demandIdStr.isNotBlank())
        overrideIndex["variant_selection|$productId|$locationId|$demandIdStr"]
    else
        overrideIndex["variant_selection|$productId|$locationId"]
    // Filter to the override-specified method if one is configured
    val effectiveMethods = if (methodOverride != null) {
        val forcedType = (methodOverride["method"] ?: methodOverride["method_type"])?.toString()
        val forcedPref = (methodOverride["preference"] as? Number)?.toInt()
        methods.filter { m ->
            (forcedType == null || m["type"]?.toString() == forcedType) &&
            (forcedPref == null || (m["preference"] as? Number)?.toInt() == forcedPref)
        }.ifEmpty {
            log.warn("method_selection override for {}@{} matched no methods; using all", productId, locationId)
            methods
        }
    } else methods

    // ── Single method selection ────────────────────────────────────────────────
    val elaborateAtThisLevel = shouldElaborateAtDepth(depth, methodCfg.depth)
    val (m, methodChoiceExplanation) = when {
        effectiveMethods.size == 1 -> {
            val m = effectiveMethods[0]; val loc = (m["location_id"] ?: m["to_location_id"] ?: "").toString()
            val base = "Only option: ${m["type"]} @ $loc."
            Pair(m, if (methodOverride != null) "$base [method override active]" else base)
        }
        useElaborateMethod && elaborateAtThisLevel ->
            getPreferredMethodElaborate(effectiveMethods, demand, inventory, data, requestTimeDt, config, depth, path)
        else -> getPreferredMethodCascade(effectiveMethods, demand, inventory, data, requestTimeDt, config, depth, path)
    }

    // Override is active only when it actually changed the selected method vs auto-selection.
    // For elaborate mode (expensive), fall back to checking whether choices were restricted.
    val methodOverrideActive = methodOverride != null && when {
        useElaborateMethod && elaborateAtThisLevel -> effectiveMethods.size < methods.size
        else -> getPreferredMethod(methods).first?.get("type")?.toString() != m?.get("type")?.toString()
    }
    val overrideActive = methodOverrideActive || variantOverride != null

    if (m == null) {
        demandFulfilledList.add(committedRow(demandNetQty, reqTimeStr, "no_preferred_method"))
        return Triple(demandFulfilledList, emptyList(), demandNode(peggingChildren, reqTimeStr, "no_preferred_method", committedQty = taken))
    }

    val productionLocation = (if (m["type"] == "move") m["to_location_id"] else m["location_id"])?.toString() ?: locationId
    val reqDt = parseDate(reqTimeStr) ?: requestTimeDt ?: LocalDate.now()
    val leadDays = leadDaysForMethod(m)

    // 3) Child materials
    var woChildrenRelation: String? = null
    val (childMaterials, variantExplanation) = when (m["type"]) {
        "make" -> {
            val rawVariants = variantsForMake(productId, productionLocation, demandNetQty, m, data)
            // Apply variant_selection override: force a specific alt_group
            val variants = if (variantOverride != null) {
                val forcedAltGroup = variantOverride["alt_group"]?.toString()
                rawVariants.filter { (altKey, _) -> forcedAltGroup == null || altKey == forcedAltGroup }
                    .ifEmpty {
                        log.warn("variant_selection override alt_group={} for {}@{} matched nothing; using all", forcedAltGroup, productId, productionLocation)
                        rawVariants
                    }
            } else rawVariants
            val (variantList, ve) = getPreferredVariants(variants, inventory, data, reqDt, leadDays, path, depth, demandNetQty,
                multiple = if (useSingleVariant) false else null, scoreWeights = scoreWeights, topN = topN)
            val cm = variantList.flatMap { (childList, _, _) -> childList }
            if (variantList.size > 1) {
                woChildrenRelation = if (variantList.all { (childList, _, _) -> childList.size == 1 }) "or" else "and"
            } else if (variantList.size == 1 && (variantList[0].first.size) > 1) {
                // Single variant with multiple BOM children → AND group (all required).
                woChildrenRelation = "and"
            }
            val veAnnotated = if (variantOverride != null) "$ve [variant override active]" else ve
            Pair(cm, veAnnotated)
        }
        "move" -> Pair(childMaterialsForMove(m, demandNetQty), "")
        else -> Pair(emptyList<Map<String, Any?>>(), "")
    }

    // 4) Recursively plan children — with partial-fulfillment support.
    //
    //    Instead of aborting when a child can only supply a fraction of what is
    //    needed, we:
    //      a) Snapshot inventory before any child planning.
    //      b) Run a first pass for all children at the full demandNetQty.
    //      c) Compute the achievable parent qty as the bottleneck ratio:
    //             achievable = min over children of (effectiveCommitted / needed) * demandNetQty
    //      d) If partial: restore the inventory snapshot and re-plan all children
    //         at the proportionally-scaled achievable qty (second pass).  Because the
    //         scale factor is derived from what each child actually committed in the
    //         first pass, the second pass is guaranteed to succeed.
    //      e) Emit the parent WO for achievableQty; use commit_reason="partial" (not
    //         a failure reason) so the demand shows a non-zero shortage in the UI.
    val childWos = mutableListOf<Map<String, Any?>>()
    val commitTimes = mutableListOf<LocalDate>()
    val childPeggingNodes = mutableListOf<Map<String, Any?>>()

    // Snapshot inventory before any child planning.
    val inventorySnap = copyInventory(inventory)

    // ── First pass: plan all children at full demandNetQty ────────────────────
    val childPassResults = mutableListOf<ChildPassResult>()
    for (c in childMaterials) {
        if (m["type"] == "make") {
            val parentKey = productId.trim()
            val childKey = (c["product_id"] as? String)?.trim() ?: ""
            if (Pair(parentKey, childKey) in REAL_BOM_PAIRS) {
                log.info("planning: real BOM (VIRTUAL<>Y) parent={} child={} demand={} qty={}", parentKey, childKey, demandId, c["quantity"])
            }
        }
        val cReqDt = dateAddDays(reqDt, -leadDays)
        val neededQty = (c["quantity"] as? Number)?.toDouble() ?: 0.0
        val cDemand = mapOf("demand_id" to demandId, "product_id" to c["product_id"], "location_id" to c["location_id"],
            "quantity" to neededQty, "request_due_time" to formatDate(cReqDt), "request_time" to formatDate(cReqDt))
        val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, overrideIndex = overrideIndex)
        // Sum rows that represent a real physical commitment:
        //   - "inventory"  → pulled from live inventory (real supply consumed)
        //   - "partial"    → child committed a truncated qty after its own cap
        //   - null/blank   → fully successful recursive plan (all descendants produced real WOs)
        // Explicitly EXCLUDE cycle_stopped / cycle_detected: those terminate a move/make
        // loop with no actual supply behind them. If they were counted as committed, a
        // bottleneck child in a cycle (e.g. 260-0152-02 with 471 supply answering a 1500
        // demand via a return move) would inflate effectiveQty to the full requested qty,
        // causing the parent to build a WO larger than physical supply can back.
        val effectiveQty = solvedList.sumOf { s ->
            val r = s["commit_reason"] as? String
            if (r == "cycle_stopped" || r == "cycle_detected") 0.0
            else if (!isHardPlanningFailure(r)) (s["quantity"] as? Number)?.toDouble() ?: 0.0
            else 0.0
        }
        val cTimes = solvedList.mapNotNull { s -> parseDate(s["commit_time"] as? String) }
        childPassResults.add(ChildPassResult(c, neededQty, effectiveQty, cWos, cPegging, cTimes))
    }

    val anyChildShort = childPassResults.any { cr -> cr.effectiveQty < cr.neededQty - 1e-9 }

    // ── Determine achievable parent qty ────────────────────────────────────────
    val achievableParentQty: Double
    if (!anyChildShort || childMaterials.isEmpty()) {
        // All children fully committed — first-pass results are final.
        achievableParentQty = demandNetQty
        childPassResults.forEach { cr ->
            childWos.addAll(cr.wos)
            if (cr.pegging != null) childPeggingNodes.add(cr.pegging)
            commitTimes.addAll(cr.cTimes)
        }
    } else {
        // Bottleneck: child with the worst committed/needed ratio limits the parent.
        val rawAchievable = childPassResults.minOf { cr ->
            if (cr.neededQty > 1e-9) cr.effectiveQty * demandNetQty / cr.neededQty else demandNetQty
        }
        // Close-enough collapse: tiny shortages are numerical noise, not real bottlenecks.
        // Threshold sourced from config.shortage_tolerance (see top of plan()).
        val capped = if (demandNetQty - rawAchievable < shortageTolerance(demandNetQty)) demandNetQty
                     else rawAchievable.coerceIn(0.0, demandNetQty)

        if (capped <= 1e-9) {
            // Nothing achievable at all — no raw capacity after deeper chain depletion.
            val bottleneck = childPassResults.first { cr -> cr.effectiveQty < cr.neededQty - 1e-9 }
            val childProduct = bottleneck.child["product_id"]?.toString() ?: "?"
            val childLoc     = bottleneck.child["location_id"]?.toString() ?: "?"
            val reason = "child_failed:${childProduct}@${childLoc}(no_inventory)"
            // Commit 0 (not demandNetQty) so downstream KPIs / UI reflect that nothing
            // was actually produced.  The reason string still tells callers why.
            demandFulfilledList.add(committedRow(0.0, reqTimeStr, reason))
            // Wrap the failed-child pegging in a zero-qty WO placeholder so the tree still
            // shows WHICH method was attempted (make / move / purchase) and carries its
            // variant relation (OR alt_groups vs AND same-variant children). Without this
            // wrapper the UI sees a demand → bare-child-demands shape that hides the method
            // context and mislabels the group.
            val failedChildPegging = childPassResults.mapNotNull { it.pegging }
            val methodType = m["type"] as? String ?: ""
            val blockedWoChildren = if (methodType == "purchase")
                listOf(mapOf("type" to "purchase", "product_id" to productId, "location_id" to productionLocation, "quantity" to 0.0, "children" to emptyList<Any>()))
            else failedChildPegging
            val blockedWoNode = buildWoNode(
                productId, productionLocation, 0.0, methodType, m,
                reqDt, null, 0, 0.0,
                "$methodChoiceExplanation — blocked: deep child $childProduct@$childLoc has no supply",
                variantExplanation, woChildrenRelation, blockedWoChildren, overrideActive
            )
            val failedPegging = listOf(blockedWoNode) + peggingChildren
            return Triple(demandFulfilledList, emptyList(), demandNode(failedPegging, reqTimeStr, reason, committedQty = taken))
        }
        achievableParentQty = capped

        // ── Second pass: restore inventory and re-plan at achievable qty ──────
        // Because scale = capped/demandNetQty, each child is asked for exactly
        // what it committed in the first pass (minus epsilon), so this pass succeeds.
        inventory.clear()
        inventory.addAll(inventorySnap)
        val scale = achievableParentQty / demandNetQty
        val scaledChildren = scaleChildMaterials(childMaterials, scale)
        for (c in scaledChildren) {
            if (m["type"] == "make") {
                val parentKey = productId.trim()
                val childKey = (c["product_id"] as? String)?.trim() ?: ""
                if (Pair(parentKey, childKey) in REAL_BOM_PAIRS) {
                    log.info("planning: real BOM partial re-plan parent={} child={} demand={} achievable={}", parentKey, childKey, demandId, achievableParentQty)
                }
            }
            val cReqDt = dateAddDays(reqDt, -leadDays)
            val cDemand = mapOf("demand_id" to demandId, "product_id" to c["product_id"], "location_id" to c["location_id"],
                "quantity" to c["quantity"], "request_due_time" to formatDate(cReqDt), "request_time" to formatDate(cReqDt))
            val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, overrideIndex = overrideIndex)
            childWos.addAll(cWos)
            if (cPegging != null) childPeggingNodes.add(cPegging)
            solvedList.forEach { s -> parseDate(s["commit_time"] as? String)?.let { commitTimes.add(it) } }
        }
    }

    // 5) Timing + work orders (using achievableParentQty; equals demandNetQty when not partial)
    val startDt = computeStartDt(reqDt, leadDays, commitTimes)
    val (wos, lotCount, lastEnd, lotSizeVal) = buildWorkOrders(productId, productionLocation, achievableParentQty, leadDays, startDt, m, demandId, data, overrideActive)
    val methodType = m["type"] as? String ?: ""
    val woChildren = if (methodType == "purchase") listOf(mapOf("type" to "purchase", "product_id" to productId, "location_id" to productionLocation, "quantity" to roundQty(achievableParentQty), "children" to emptyList<Any>()))
                     else childPeggingNodes
    peggingChildren.add(buildWoNode(productId, productionLocation, achievableParentQty, methodType, m, startDt, lastEnd, lotCount, lotSizeVal, methodChoiceExplanation, variantExplanation, woChildrenRelation, woChildren, overrideActive))

    // "partial" is NOT a failure reason — it keeps is_failed=false so enrichCommittedDemands
    // counts achievableParentQty toward effectiveCommitted and computes shortage correctly.
    val partialReason = if (anyChildShort && achievableParentQty < demandNetQty - 1e-9) "partial" else null
    demandFulfilledList.add(committedRow(achievableParentQty, formatDate(lastEnd), partialReason))
    return Triple(demandFulfilledList, wos + childWos, demandNode(peggingChildren, formatDate(lastEnd), partialReason, committedQty = taken + achievableParentQty))
}

// ── Lot-batching helper ────────────────────────────────────────────────────────

private data class WorkOrderResult(
    val wos: List<Map<String, Any?>>,
    val lotCount: Int,
    val lastEnd: LocalDate?,
    val lotSizeVal: Double,
)

internal fun leadDaysForMethod(m: Map<String, Any?>): Double = when (m["type"]) {
    "make" -> (m["lead_time"] as? Number)?.toDouble() ?: 0.0
    "move" -> (m["transit_time"] as? Number)?.toDouble() ?: 0.0
    "purchase" -> (m["lead_days_supply"] as? Number)?.toDouble() ?: 0.0
    else -> 0.0
}

private fun computeStartDt(reqDt: LocalDate, leadDays: Double, commitTimes: List<LocalDate>): LocalDate {
    var startDt = if (leadDays > 0) dateAddDays(reqDt, -leadDays) ?: reqDt else reqDt
    if (commitTimes.isNotEmpty()) {
        val latestChild = commitTimes.max()
        if (latestChild > startDt) startDt = latestChild
    }
    return startDt
}

private fun buildWorkOrders(
    productId: String,
    productionLocation: String,
    qty: Double,
    leadDays: Double,
    startDt: LocalDate,
    m: Map<String, Any?>,
    demandId: Any?,
    data: Map<String, List<Map<String, Any?>>>,
    overrideActive: Boolean = false,
): WorkOrderResult {
    val lotSizeVal = maxLotSize(productId, productionLocation, data)?.takeIf { it > 0 } ?: qty
    val lotSize = max(1e-9, lotSizeVal)
    val prodArea = getProdArea(productId, productionLocation, data)
    val methodType = m["type"] as? String ?: ""
    val wos = mutableListOf<Map<String, Any?>>()
    var left = qty
    var lotStart: LocalDate? = startDt
    var lastEnd: LocalDate? = null
    var lotCount = 0
    while (left > 1e-9 && lotStart != null) {
        val lotQty = min(lotSize, left)
        val lotEnd = dateAddDays(lotStart, leadDays)
        wos.add(mapOf(
            "product_id" to productId,
            "location_id" to productionLocation,
            "quantity" to roundQty(lotQty),
            "start_time" to formatDate(lotStart),
            "end_time" to formatDate(lotEnd),
            "method" to methodType,
            "location_source" to (if (methodType == "move") m["from_location_id"] else null),
            "demand_id" to demandId,
            "prod_area" to prodArea,
            "override_active" to overrideActive,
        ))
        lastEnd = lotEnd
        left -= lotQty
        lotCount++
        lotStart = if (left > 1e-9) lotEnd else null
    }
    return WorkOrderResult(wos, lotCount, lastEnd, lotSizeVal)
}

private fun buildWoNode(
    productId: String,
    productionLocation: String,
    qty: Double,
    methodType: String,
    m: Map<String, Any?>,
    startDt: LocalDate?,
    lastEnd: LocalDate?,
    lotCount: Int,
    lotSizeVal: Double,
    methodChoiceExpl: String,
    variantExpl: String,
    childrenRelation: String?,
    woChildren: List<Map<String, Any?>>,
    overrideActive: Boolean = false,
): Map<String, Any?> = mapOf(
    "type" to "work_order",
    "product_id" to productId,
    "location_id" to productionLocation,
    "quantity" to roundQty(qty),
    "start_time" to formatDate(startDt),
    "end_time" to formatDate(lastEnd),
    "method" to methodType,
    "location_source" to (if (methodType == "move") m["from_location_id"] else null),
    "method_choice_explanation" to methodChoiceExpl,
    "variant_choice_explanation" to variantExpl.ifBlank { null },
    "children_relation" to childrenRelation,
    "lot_count" to (if (lotCount > 0) lotCount else null),
    "max_lot_size" to lotSizeVal,
    "override_active" to overrideActive,
    "children" to woChildren,
)

// ── Phantom-loop pruning ───────────────────────────────────────────────────────

/**
 * Remove cycle-stopped demand nodes and any work_order nodes that become childless
 * after that pruning. This eliminates phantom move cycles (e.g. A@loc1 → move → A@loc2 → move →
 * A@loc1 [cycle_stopped]) from the pegging tree without touching real demand structure.
 *
 * Rules:
 *   - supply / purchase leaf → always kept
 *   - demand with commit_reason == "cycle_stopped" → dropped
 *   - transit demand with no children after recursive pruning → dropped
 *     A "transit demand" is a demand node that is a direct child of a move WO — it represents
 *     an intermediate routing stop, not a real component need. Pruning it makes the parent move
 *     WO childless, which is then also pruned. Real component demands (children of make WOs)
 *     are kept even when childless.
 *   - work_order with no children after recursive pruning → dropped
 *   - everything else → kept with its children recursively pruned
 */
@Suppress("UNCHECKED_CAST")
private fun prunePhantomLoops(
    node: Map<String, Any?>,
    isRoot: Boolean = false,
    parentIsMoveWo: Boolean = false,
): Map<String, Any?>? {
    val type = node["type"] as? String
    if (type == "supply" || type == "purchase") return node
    if (type == "demand" && node["commit_reason"] == "cycle_stopped") return null

    val isMoveWo = type == "work_order" &&
        (node["method"] as? String)?.lowercase() == "move"

    val prunedChildren = (node["children"] as? List<Map<String, Any?>>)
        ?.mapNotNull { prunePhantomLoops(it, isRoot = false, parentIsMoveWo = isMoveWo) } ?: emptyList()

    if (type == "work_order" && prunedChildren.isEmpty()) return null
    // Only prune childless demand nodes that are transit stops inside a move chain,
    // not real component demands (children of make WOs or the root).
    if (type == "demand" && prunedChildren.isEmpty() && !isRoot && parentIsMoveWo) return null

    return node.toMutableMap().apply { put("children", prunedChildren) }
}

// ── Supply allocation extraction ───────────────────────────────────────────────

/**
 * Walk the assembled pegging tree and collect every supply/purchase leaf node,
 * recording which demand they backed and how much was consumed.
 * Used to persist supply consumption to plan_supply_allocation on plan save.
 *
 * Cap enforcement: each binding is capped so cumulative pegging against any
 * real supply_id cannot exceed its initial qty. Bindings are processed in
 * emission order (consolidation trees first, then demand trees in priority
 * order), so earlier entries get first claim on scarce supply. A binding that
 * would exceed the cap is truncated; a binding beyond full exhaustion is dropped.
 *
 * Synthetic supply_ids (e.g. "consolidated_<demand>_<pid>" injected by
 * runConsolidation) are passed through uncapped — they represent planner
 * bookkeeping against runtime-created inventory buckets, not physical supply.
 */
@Suppress("UNCHECKED_CAST")
internal fun extractSupplyAllocations(
    pegging: List<Map<String, Any?>>,
    supplies: List<Map<String, Any?>>,
): List<Map<String, Any?>> {
    val remainingBySupply = mutableMapOf<String, Double>()
    for (s in supplies) {
        val sid = s["supply_id"] as? String ?: continue
        val qty = (s["qty"] as? Number)?.toDouble() ?: 0.0
        remainingBySupply[sid] = (remainingBySupply[sid] ?: 0.0) + qty
    }

    val result = mutableListOf<Map<String, Any?>>()
    fun walk(node: Map<String, Any?>, demandId: String?) {
        val type = node["type"] as? String
        val supplyId = node["supply_id"] as? String
        if ((type == "supply" || type == "purchase") && !supplyId.isNullOrBlank()) {
            val rawQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            val effectiveQty = if (supplyId in remainingBySupply) {
                val remaining = remainingBySupply[supplyId] ?: 0.0
                val take = minOf(rawQty, remaining)
                remainingBySupply[supplyId] = remaining - take
                take
            } else {
                rawQty  // synthetic/non-physical bucket — no cap
            }
            if (effectiveQty > 1e-9) {
                result.add(mapOf(
                    "supply_id"    to supplyId,
                    "demand_id"    to (demandId ?: ""),
                    "qty_consumed" to effectiveQty,
                ))
            }
        } else {
            val nextDemand = if (type == "demand") (node["demand_id"] as? String ?: demandId) else demandId
            (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it, nextDemand) }
        }
    }
    for (entry in pegging) {
        val demandId = entry["demand_id"] as? String
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, demandId)
    }
    return result
}

// ── Main entry point ───────────────────────────────────────────────────────────

/**
 * Plan all demands. Returns (committedDemands, workOrders, planningPegging).
 * Port of planning_engine.run_planning().
 */
fun runPlanning(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>? = null,
    progressCallback: ((Map<String, Any?>) -> Unit)? = null,
): Map<String, Any> {
    val inventory: MutableList<MutableMap<String, Any?>> = (data["supply"] ?: emptyList()).map { s ->
        mutableMapOf(
            "product_id" to (s["product_id"] ?: ""),
            "location_id" to (s["location_id"] ?: ""),
            "supply_date" to s["supply_date"],
            "supply_id" to s["supply_id"],
            "qty" to ((s["qty"] as? Number)?.toDouble() ?: 0.0),
        )
    }.toMutableList()

    val demands = (data["demand"] ?: emptyList()).sortedWith(
        compareBy({ (it["priority"] as? Number)?.toInt() ?: 0 }, { it["demand_id"]?.toString() ?: "" })
    )

    val committedDemands = mutableListOf<Map<String, Any?>>()
    val workOrders = mutableListOf<Map<String, Any?>>()
    val planningPegging = mutableListOf<Map<String, Any?>>()
    val total = demands.size

    // Build override index once — passed through to all plan() calls. Mutable so consolidation
    // can merge synthetic method_selection entries (computed from collectDeepNeeds' cascade walk)
    // before the main planning loop runs, ensuring consolidation's pre-allocation path matches
    // main plan's actual path.
    @Suppress("UNCHECKED_CAST")
    val overrideIndex: MutableMap<String, Map<String, Any?>> =
        buildOverrideIndex((data["overrides"] ?: emptyList()) as List<Map<String, Any?>>).toMutableMap()

    // Supply-split overrides: split affected inventory buckets into demand-tagged
    // sub-buckets so the existing preferDemandId/demand_tag two-pass consumption
    // logic enforces per-(supply,demand) caps during planning.
    val supplyCapMap = buildSupplyCapMap(overrideIndex, data["supply"] ?: emptyList(), demands)
    if (supplyCapMap.isNotEmpty()) {
        for ((supplyId, caps) in supplyCapMap) {
            val buckets = inventory.filter { it["supply_id"]?.toString() == supplyId }
            if (buckets.isEmpty()) continue
            val template = buckets.first()
            val productId = template["product_id"]
            val locationId = template["location_id"]
            val supplyDate = template["supply_date"]
            inventory.removeAll(buckets.toSet())
            for ((demandId, cap) in caps) {
                if (cap <= 1e-12) continue
                inventory.add(mutableMapOf(
                    "product_id"  to productId,
                    "location_id" to locationId,
                    "supply_date" to supplyDate,
                    "supply_id"   to supplyId,
                    "qty"         to cap,
                    "demand_tag"  to demandId,
                ))
            }
        }
    }

    // ── Consolidation phase ───────────────────────────────────────────────────
    val consolidationConfig = parseConsolidationConfig(config)
    val consolidatedWOs = mutableListOf<Map<String, Any?>>()
    val consolidatedPegging = mutableListOf<Map<String, Any?>>()

    if (consolidationConfig.enabled) {
        // Record the root-level method each demand's BOM walk chose, then inject them as
        // synthetic method_selection overrides so the main plan picks the identical method
        // at depth=MAX_PLAN_DEPTH (and plan() line ~941 filters effectiveMethods to this one).
        // User-authored overrides already in overrideIndex win: we only insert when absent.
        val methodChoices = mutableMapOf<Triple<String, String, String>, Map<String, Any?>>()
        val needs  = collectComponentNeeds(demands, data, consolidationConfig,
            inventory = inventory, planConfig = config, methodChoices = methodChoices)
        for ((triple, chosen) in methodChoices) {
            val (pid, lid, did) = triple
            val key = "method_selection|$pid|$lid|$did"
            if (overrideIndex.containsKey(key)) continue
            val entry = mutableMapOf<String, Any?>()
            chosen["type"]?.let { entry["method_type"] = it }
            chosen["preference"]?.let { entry["preference"] = it }
            if (entry.isNotEmpty()) overrideIndex[key] = entry
        }
        val groups = groupByTimeBucket(needs, consolidationConfig.periodDays)
        val result = runConsolidation(groups, inventory, data, consolidationConfig, planConfig = config) { dem, inv, dat, reqDt, depth, path, cfg, prefId ->
            plan(dem, inv, dat, reqDt, depth, path, cfg, prefId, overrideIndex = overrideIndex)
        }
        consolidatedWOs.addAll(result.consolidatedWOs)
        consolidatedPegging.addAll(result.consolidatedPegging)

        // Inject per-demand tagged supply buckets so each demand's plan() finds its pre-allocated share
        for ((demandId, componentAllocs) in result.allocation) {
            for ((componentKey, qty) in componentAllocs) {
                if (qty <= 1e-12) continue
                val parts = componentKey.split("|", limit = 2)
                val pid = parts.getOrElse(0) { "" }
                val lid = parts.getOrElse(1) { "" }
                // Use the end_time of the first matching consolidated WO as supply_date
                val supplyDate = result.consolidatedWOs
                    .firstOrNull { it["product_id"] == pid && it["location_id"] == lid }
                    ?.get("end_time") as? String
                inventory.add(mutableMapOf(
                    "product_id"  to pid,
                    "location_id" to lid,
                    "qty"         to qty,
                    "supply_date" to supplyDate,
                    "supply_id"   to "consolidated_${demandId}_${pid}",
                    "demand_tag"  to demandId,
                ))
            }
        }
    }
    // ── Main planning loop ────────────────────────────────────────────────────

    val useTaggedLookup = consolidationConfig.enabled || supplyCapMap.isNotEmpty()
    demands.forEachIndexed { i, d ->
        val reqStr = d["request_due_time"] as? String ?: d["request_time"] as? String
        val reqDt = parseDate(reqStr)
        val demandId = d["demand_id"]
        val prefId = if (useTaggedLookup) demandId else null
        val (solvedList, wos, peggingNode) = plan(d, inventory, data, reqDt, config = config, preferDemandId = prefId, overrideIndex = overrideIndex)
        committedDemands.addAll(solvedList)
        workOrders.addAll(wos)
        if (peggingNode != null && demandId != null) {
            planningPegging.add(mapOf("demand_id" to demandId, "tree" to peggingNode))
        }
        progressCallback?.invoke(mapOf("current" to i + 1, "total" to total, "demand_id" to demandId))
    }

    // Prune cycle_stopped phantom loop nodes from every pegging tree before returning.
    @Suppress("UNCHECKED_CAST")
    val allPegging = (consolidatedPegging + planningPegging).mapNotNull { entry ->
        val tree = entry["tree"] as? Map<String, Any?> ?: return@mapNotNull null
        val pruned = prunePhantomLoops(tree, isRoot = true) ?: return@mapNotNull null
        entry.toMutableMap().apply { put("tree", pruned) }
    }
    val suppliesForCap = data["supply"] ?: emptyList()
    val supplyAllocations = extractSupplyAllocations(allPegging, suppliesForCap)
    val supplyCapViolations = verifySupplyCap(suppliesForCap, supplyAllocations)

    // Supply-split override soft-warn: any demand in an override that commits short
    // of its request is flagged as potentially impacted by the override.
    // Commit qty is measured from real supply/purchase allocations — committed_demands
    // rows with hard-failure reasons (no_methods, etc.) record shortfall, not commit.
    val overrideWarnings = if (supplyCapMap.isEmpty()) emptyList() else {
        val requestedByDemand = mutableMapOf<String, Double>()
        for (d in demands) {
            val did = d["demand_id"]?.toString() ?: continue
            val q = (d["quantity"] as? Number)?.toDouble() ?: 0.0
            requestedByDemand[did] = (requestedByDemand[did] ?: 0.0) + q
        }
        val committedByDemand = mutableMapOf<String, Double>()
        for (row in committedDemands) {
            val reason = row["commit_reason"] as? String
            if (isHardPlanningFailure(reason)) continue
            val did = row["demand_id"]?.toString() ?: continue
            val q = (row["quantity"] as? Number)?.toDouble() ?: 0.0
            committedByDemand[did] = (committedByDemand[did] ?: 0.0) + q
        }
        val warnings = mutableListOf<Map<String, Any?>>()
        for ((supplyId, caps) in supplyCapMap) {
            val affected = mutableListOf<Map<String, Any?>>()
            for ((did, _) in caps) {
                val req = requestedByDemand[did] ?: continue
                val com = committedByDemand[did] ?: 0.0
                val shortfall = req - com
                if (shortfall > 1e-6) {
                    affected.add(mapOf(
                        "demand_id" to did,
                        "shortfall" to roundQty(shortfall),
                        "requested" to roundQty(req),
                        "committed" to roundQty(com),
                    ))
                }
            }
            if (affected.isNotEmpty()) {
                warnings.add(mapOf(
                    "supply_id"        to supplyId,
                    "affected_demands" to affected,
                ))
            }
        }
        warnings
    }

    return mapOf(
        "committed_demands"     to committedDemands,
        "work_orders"           to consolidatedWOs + workOrders,
        "planning_pegging"      to allPegging,
        "supply_allocations"    to supplyAllocations,
        "supply_cap_violations" to supplyCapViolations,
        "override_warnings"     to overrideWarnings,
    )
}

/**
 * Cross-check: the total pegged quantity for any supply_id must not exceed the
 * supply's initial quantity. A non-empty result indicates a planning bug — the
 * "limited supply constrains producible FG qty" invariant was violated.
 */
internal fun verifySupplyCap(
    supplies: List<Map<String, Any?>>,
    allocations: List<Map<String, Any?>>,
): List<String> {
    val initialBySupply = mutableMapOf<String, Double>()
    for (s in supplies) {
        val sid = s["supply_id"] as? String ?: continue
        val qty = (s["qty"] as? Number)?.toDouble() ?: 0.0
        initialBySupply[sid] = (initialBySupply[sid] ?: 0.0) + qty
    }
    val consumedBySupply = mutableMapOf<String, Double>()
    for (a in allocations) {
        val sid = a["supply_id"] as? String ?: continue
        val qty = (a["qty_consumed"] as? Number)?.toDouble() ?: 0.0
        consumedBySupply[sid] = (consumedBySupply[sid] ?: 0.0) + qty
    }
    val violations = mutableListOf<String>()
    for ((sid, consumed) in consumedBySupply) {
        // Skip synthetic / runtime-injected supply buckets (e.g. "consolidated_*") —
        // they have no physical cap and must not be flagged.
        val initial = initialBySupply[sid] ?: continue
        if (consumed > initial + 1e-6) {
            val msg = "supply_id %s: pegged %.4f > available %.4f".format(sid, consumed, initial)
            violations.add(msg)
            log.warn("supply cap violation — {}", msg)
        }
    }
    return violations
}
