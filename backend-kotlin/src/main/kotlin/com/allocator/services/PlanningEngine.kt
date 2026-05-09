package com.allocator.services

import com.allocator.config
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
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
    val mode: String,        // "preference" | "elaborate"  — ranking source for waterfall
    val depth: Int,          // >= 1                        — gate for both elaborate and waterfall
    val multiple: Boolean,                                 //   legacy back-compat parse only
    /** Cap on how many methods waterfall may invoke per demand. >= 1; default 2. */
    val maxMethods: Int,
    val scoreWeights: Map<String, Any?>?,  // drives elaborate scoring (commit_time / inventory_consumed / purchase)
    /** Maximum real-make recursion depth admitted at the reactive make-fallback
     *  site in plan(). A make alternative whose precomputed [maxMakeDepth]
     *  exceeds this cap is skipped without recursing — too deep to attempt
     *  productively. Default 3 (covers typical case-171 patterns). */
    val maxBomDepth: Int = DEFAULT_MAX_BOM_DEPTH,
) {
    val elaborate: Boolean get() = mode == "elaborate"
}

internal const val DEFAULT_MAX_BOM_DEPTH = 3

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
 * Resolve the effective `max_methods` cap.
 *
 * Priority:
 *   1. explicit `max_methods` (clamped to ≥ 1; non-numeric warns and falls back to default)
 *   2. legacy `multiple: false` (no `max_methods`)         → 1
 *   3. legacy `multiple: true`  (no `max_methods`)         → 2 (behavior change documented)
 *   4. neither set                                          → 2
 *
 * Default tracks the UI default; the two are intentionally kept in sync.
 */
private const val DEFAULT_MAX_METHODS = 2

/** Below this residual (in demand-qty units), waterfall stops invoking further
 *  method slots. Prevents trivial 0.x-unit second WOs from lot-size or
 *  bottleneck-rounding leftovers. */
private const val MIN_WATERFALL_RESIDUAL = 0.5

/** Parse `method_selection.max_bom_depth`: integer ≥ 1, defaults to
 *  [DEFAULT_MAX_BOM_DEPTH] (3). Caps at 10 to bound recursion cost.
 *
 *  This is the maximum real-make recursion depth admitted at the reactive
 *  make-fallback site in plan(). A `make` alternative whose precomputed
 *  [maxMakeDepth] exceeds this cap is structurally too deep to attempt —
 *  the make would either recurse uselessly or compound. */
private const val MAX_BOM_DEPTH_HARD_CAP = 10
private fun parseMaxBomDepth(raw: Any?): Int {
    if (raw == null) return DEFAULT_MAX_BOM_DEPTH
    val n = (raw as? Number)?.toInt()
    if (n == null) {
        log.warn("Invalid method_selection.max_bom_depth={}; defaulting to {}", raw, DEFAULT_MAX_BOM_DEPTH)
        return DEFAULT_MAX_BOM_DEPTH
    }
    return n.coerceIn(1, MAX_BOM_DEPTH_HARD_CAP)
}

private fun parseMaxMethods(rawMax: Any?, multiple: Boolean, multiplePresent: Boolean): Int {
    if (rawMax != null) {
        val n = (rawMax as? Number)?.toInt()
        if (n == null) {
            log.warn("Invalid method_selection.max_methods={}; falling back to default {}", rawMax, DEFAULT_MAX_METHODS)
            return DEFAULT_MAX_METHODS
        }
        if (n < 1) {
            log.warn("method_selection.max_methods={} < 1; clamping to default {}", n, DEFAULT_MAX_METHODS)
            return DEFAULT_MAX_METHODS
        }
        return n
    }
    if (multiplePresent) return if (multiple) DEFAULT_MAX_METHODS else 1
    return DEFAULT_MAX_METHODS
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
    val multiplePresent = raw.containsKey("multiple") && raw["multiple"] is Boolean
    val maxMethods = parseMaxMethods(raw["max_methods"], multiple, multiplePresent)
    @Suppress("UNCHECKED_CAST")
    val methodWeights = raw["score_weights"] as? Map<String, Any?>
    val weights = methodWeights ?: run {
        val vs = (config?.get("variant_selection") as? Map<*, *>)?.let {
            @Suppress("UNCHECKED_CAST") it as? Map<String, Any?>
        }
        @Suppress("UNCHECKED_CAST")
        vs?.get("score_weights") as? Map<String, Any?>
    }
    val maxBomDepth = parseMaxBomDepth(raw["max_bom_depth"])
    return MethodSelectionConfig(
        mode = mode,
        depth = depth,
        multiple = multiple,
        maxMethods = maxMethods,
        scoreWeights = weights,
        maxBomDepth = maxBomDepth,
    )
}

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
internal fun isHardPlanningFailure(reason: String?): Boolean {
    if (reason.isNullOrBlank()) return false
    if (reason in BENIGN_REASONS) return false
    if (reason == "partial") return false
    return true  // no_methods, no_preferred_method, depth_limit, child_failed:*, etc.
}

/**
 * Classify a planning block reason as *structural* — i.e. dependent only on
 * `data` (BOM/methods topology), not on inventory state or path context.
 *
 * Used by the reactive-fallback site to memoize "this make is doomed" answers
 * across demands. Once a make-fallback for (pid, lid) blocks on a structural
 * cascade, no future demand can succeed at the same site for the same reason.
 *
 * Conservative inclusion: only `no_methods` and `no_preferred_method`
 * (raw + cascaded forms via `child_failed:*(...)`). NOT `cycle_stopped`
 * (path-dependent), NOT `no_inventory` / `partial` (state-dependent).
 */
internal fun isStructuralFailure(reason: String?): Boolean {
    if (reason.isNullOrBlank()) return false
    return reason.contains("no_methods") || reason.contains("no_preferred_method")
}

/** Holds the first-pass planning result for a single child material. */
private data class ChildPassResult(
    val child: Map<String, Any?>,
    val neededQty: Double,
    val effectiveQty: Double,        // committed qty from non-hard-failure rows
    val wos: List<Map<String, Any?>>,
    val pegging: Map<String, Any?>?,
    val cTimes: List<LocalDate>,
    val solvedList: List<Map<String, Any?>>,  // raw committedRow list — needed by the
                                              // bottleneck branch to surface the deepest
                                              // cause (vs the immediate failing child).
)

/**
 * Walk a cascading commit_reason and return the deepest non-cascade triple
 * (pid, loc, terminalCause). The planner's child_failed reasons nest like
 * matryoshka dolls — each level wraps the immediate failing child + its
 * own reason, which is itself often a child_failed cascade. Unwrapping all
 * the way to the terminal cause makes the demand row's commit_reason point
 * directly at the actual unfulfillable component instead of the outermost
 * propagated label.
 *
 *   "child_failed:A@1(no_inventory)"
 *     → (A, 1, no_inventory)
 *
 *   "child_failed:A@1(child_failed:B@2(no_methods))"
 *     → (B, 2, no_methods)        // B@2 is the actual root cause
 *
 *   "no_methods"                   // already terminal, no pid/loc
 *     → (?, ?, no_methods)
 *
 *   null                           // sensible default
 *     → (?, ?, no_inventory)
 *
 * Callers pass `?` through when their own bottleneck child's pid/loc is
 * available and more specific than the unwrap result.
 */
internal fun resolveDeepestCause(reason: String?): Triple<String, String, String> {
    if (reason.isNullOrBlank()) return Triple("?", "?", "no_inventory")
    val match = Regex("""^child_failed:([^@]+)@([^(]+)\((.+)\)$""").matchEntire(reason)
        ?: return Triple("?", "?", reason)
    val (pid, loc, cause) = match.destructured
    return if (cause.startsWith("child_failed:"))
        resolveDeepestCause(cause)
    else
        Triple(pid, loc, cause)
}

/**
 * Compute parent achievable qty from per-child first-pass results, given the
 * relation among children. AND (or unspecified): the worst-supplied child caps
 * the parent (classic min-bottleneck). OR: each variant contributes additively
 * — Σ (variant's fractional success × variant's share of demand). Returns
 * (rawAchievable, isOrSplit) so callers can decide whether a uniform second
 * pass is appropriate (AND) or whether to keep first-pass per-variant commits
 * as-is (OR — each variant already represents an independent attempt).
 */
/**
 * Compute the root-bottleneck child keys using iter-0 budget caps.
 *
 * The genuine origin of an AND-min cap is the child whose iter-0 fair-share
 * (cap / needed-qty ratio) is the smallest among siblings. Iter-N convergence
 * later smears all siblings down to align with this child, so the post-
 * convergence ratios all tie — losing the origin's identity. The iter-0
 * snapshot in [initialBudget] preserves it.
 *
 * Returns the key set (typically size 1, possibly more on ties within 1e-9).
 * Empty if [initialBudget] is null, no child has a finite cap, or the spread
 * between min and max iter-0 ratios is below 5% (no clear origin).
 *
 * Called from BOTH the AND-bottleneck branch (where current AND-min fires)
 * AND the no-shortage branch (where it doesn't, e.g. the post-convergence
 * pass that builds the displayed tree). The latter is critical: without
 * tagging there, the deeper level's signal is overwritten when the outer
 * level's second-pass plan() rebuilds children at the converged qty.
 */
private fun computeRootBottleneckKeys(
    childPassResults: List<ChildPassResult>,
    initialBudget: Map<String, Double>?,
): Set<Pair<String, String>> {
    if (initialBudget == null) return emptySet()
    val ratios = childPassResults.map { cr ->
        val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
        val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
        val cap = initialBudget["$pid|$lid"]
        if (cap != null && cr.neededQty > 1e-9) cap / cr.neededQty
        else Double.POSITIVE_INFINITY
    }
    val finite = ratios.filter { it.isFinite() }
    if (finite.isEmpty()) return emptySet()
    val minR = finite.min()
    val maxR = finite.max()
    if (maxR <= minR * 1.05 + 1e-6) return emptySet()
    return childPassResults.zip(ratios)
        .filter { (_, r) -> r.isFinite() && r <= minR + 1e-9 }
        .map { (cr, _) ->
            val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
            val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
            Pair(pid, lid)
        }
        .toSet()
}

private fun computeRawAchievable(
    childPassResults: List<ChildPassResult>,
    demandNetQty: Double,
    woChildrenRelation: String?,
): Pair<Double, Boolean> {
    val isOr = woChildrenRelation == "or" && childPassResults.size > 1
    val raw = if (isOr) {
        // Variants are equal-split shares of demand (see getPreferredVariants).
        // Each variant's parent contribution = success_fraction × variant_share.
        val perVariantShare = demandNetQty / childPassResults.size
        childPassResults.sumOf { cr ->
            if (cr.neededQty > 1e-9) {
                val frac = (cr.effectiveQty / cr.neededQty).coerceAtMost(1.0)
                frac * perVariantShare
            } else perVariantShare
        }
    } else {
        childPassResults.minOf { cr ->
            if (cr.neededQty > 1e-9) cr.effectiveQty * demandNetQty / cr.neededQty else demandNetQty
        }
    }
    return Pair(raw, isOr)
}

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

internal fun parseDate(s: String?): LocalDate? {
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
    /**
     * Optional hard cap on total qty consumed at this (productId, locationId) regardless of
     * the demand's actual need. Used by Stage-3 budget-driven planning to enforce a per-demand
     * allocation share at the merged-leaf component without polluting [inventory] with tagged
     * synthetic buckets. `null` means unlimited (legacy behavior).
     */
    budgetCap: Double? = null,
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
    val effectiveNeed = if (budgetCap != null) min(need, budgetCap) else need
    if (effectiveNeed <= 0) return consumed
    var remaining = effectiveNeed

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
    // Demand-location-less fallback: if the demand carries no location_id at all,
    // we have no signal to filter by, so let any candidate method match. This is
    // the only case where loc-mismatch is acceptable.
    //
    // Note: VIRTUAL is NOT treated as a wildcard. It is a real location like 1000
    // or 2000. To deliver to VIRTUAL, the supply chain must include an explicit
    // move into VIRTUAL — `make@1000` produces inventory at 1000, not VIRTUAL,
    // and so does not directly satisfy a demand at VIRTUAL.
    val emptyLocFallback = loc.isEmpty()
    val result = mutableListOf<Map<String, Any?>>()

    (data["method_buy"] ?: emptyList()).forEach { m ->
        if ((m["product_id"] as? String)?.trim() == pid) {
            val mLoc = (m["location_id"] as? String)?.trim() ?: ""
            if (mLoc == loc || emptyLocFallback) result.add(mapOf("type" to "purchase") + m)
        }
    }
    (data["method_make"] ?: emptyList()).forEach { m ->
        if ((m["product_id"] as? String)?.trim() == pid) {
            val mLoc = (m["location_id"] as? String)?.trim() ?: ""
            if (mLoc == loc || emptyLocFallback) result.add(mapOf("type" to "make") + m)
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

/**
 * Compute the minimum real-make-recursion depth needed to source `productId`
 * at `locationId` via any structurally-feasible path through the location-
 * aware method graph. Used by the reactive-fallback site in plan() to gate
 * make alternatives — a make whose `maxMakeDepth` exceeds the budget cap is
 * skipped without recursing, preventing wasteful exploration of structurally
 * doomed paths.
 *
 * Edge semantics:
 *   • Direct supply at (pid, lid):                              depth = 0
 *   • Buy at (pid, lid) (purchase_allowed):                     depth = 0
 *   • Move from (pid, source_lid) to (pid, lid):                depth = maxMakeDepth(pid, source_lid)
 *       — moves traverse the location graph WITHOUT consuming budget
 *   • Make at (pid, lid):
 *       - Real parent (non-VirtualProduct):                     depth = 1 + max child depth
 *       - VirtualProduct parent:                                depth = max child depth (transparent)
 *       - Variants (alt_groups): pick the variant with min max child depth
 *
 * Cycle handling: an `inProgress` set marks nodes currently being explored.
 * Hitting an in-progress node returns `Int.MAX_VALUE` for that branch — the
 * cycle path is structurally unable to bottom out via supply/buy and is
 * useless as a make-fallback target.
 *
 * Date-aware supply NOT considered — the cache is a structural feasibility
 * filter; the runtime planner still does date-aware allocation and may
 * legitimately fail even when the cache says depth is small. The cache's
 * job is to skip *wasteful* recursion, not all failed recursion.
 *
 * Memoized via the `cache` parameter — first call populates, subsequent calls
 * are O(1) lookup. Caller owns the cache lifetime (typically reset per
 * planning run).
 */
internal fun maxMakeDepth(
    productId: String,
    locationId: String,
    data: Map<String, List<Map<String, Any?>>>,
    purchaseAllowed: Boolean,
    cache: MutableMap<Pair<String, String>, Int>,
    inProgress: MutableSet<Pair<String, String>> = mutableSetOf(),
): Int {
    val key = Pair(productId, locationId)
    cache[key]?.let { return it }
    if (key in inProgress) return Int.MAX_VALUE
    inProgress.add(key)
    try {
        var minDepth = Int.MAX_VALUE

        // Direct supply at (pid, lid)
        val hasSupply = (data["supply"] ?: emptyList()).any { s ->
            (s["product_id"] as? String)?.trim() == productId &&
                (s["location_id"] as? String)?.trim() == locationId &&
                ((s["qty"] as? Number)?.toDouble() ?: 0.0) > 0
        }
        if (hasSupply) minDepth = 0

        if (minDepth > 0) {
            val methods = getMethods(productId, locationId, data)
                .let { if (purchaseAllowed) it else it.filter { m -> m["type"] != "purchase" } }

            for (m in methods) {
                val depth = when (m["type"]) {
                    "purchase" -> 0
                    "move" -> {
                        val source = (m["from_location_id"] as? String)?.trim()
                        if (source.isNullOrBlank()) Int.MAX_VALUE
                        else maxMakeDepth(productId, source, data, purchaseAllowed, cache, inProgress)
                    }
                    "make" -> {
                        val variants = variantsForMake(productId, locationId, 1.0, m, data)
                        if (variants.isEmpty()) Int.MAX_VALUE
                        else {
                            // For each variant (alt_group), need max child depth.
                            // Across variants (OR semantics for alt-groups), pick min.
                            var minVariantDepth = Int.MAX_VALUE
                            for ((_, childList) in variants) {
                                var maxChildDepth = 0
                                var allReachable = true
                                for (c in childList) {
                                    val cPid = (c["product_id"] as? String)?.trim() ?: continue
                                    val cLid = (c["location_id"] as? String)?.trim() ?: locationId
                                    val cDepth = maxMakeDepth(cPid, cLid, data, purchaseAllowed, cache, inProgress)
                                    if (cDepth == Int.MAX_VALUE) { allReachable = false; break }
                                    if (cDepth > maxChildDepth) maxChildDepth = cDepth
                                }
                                if (allReachable && maxChildDepth < minVariantDepth) minVariantDepth = maxChildDepth
                            }
                            if (minVariantDepth == Int.MAX_VALUE) Int.MAX_VALUE
                            else if (productId.startsWith("VirtualProduct_")) minVariantDepth
                            else if (minVariantDepth >= Int.MAX_VALUE - 1) Int.MAX_VALUE
                            else 1 + minVariantDepth
                        }
                    }
                    else -> Int.MAX_VALUE
                }
                if (depth < minDepth) minDepth = depth
                if (minDepth == 0) break
            }
        }

        cache[key] = minDepth
        return minDepth
    } finally {
        inProgress.remove(key)
    }
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

/** Score a variant by simulating planning its child components. Returns (maxCommit, consumed, purchaseQty, anyFailed).
 *
 *  `config` MUST be forwarded to the recursive `plan()` call. Defaulting it to
 *  null is unsafe in this branch: with `parseMaxMethods` defaulting to 2, a
 *  null config flips the deeper plan into multi-method splitting at every
 *  multi-candidate site, which is exponential in BOM depth (case-171 hangs).
 *  Callers pass either the real planner config or a narrowed simConfig
 *  (max_methods=1, split=equal) when this is invoked from the elaborate scorer. */
private fun scoreVariant(
    altKey: String,
    childList: List<Map<String, Any?>>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    reqDt: LocalDate?,
    leadDays: Double,
    planningPath: Set<Pair<String, String>>,
    depth: Int,
    config: Map<String, Any?>?,
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
        val (solvedList, cWos, _) = plan(cDemand, invCopy, data, cReqDt, depth = depth - 1, planningPath = planningPath, config = config)
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
    config: Map<String, Any?>? = null,
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
                scoreVariant("feasibility", children, inventory, data, reqDt, leadDays, planningPath, depth - 1, config).fourth
            }
            "make" -> {
                val variants = variantsForMake(productId, productionLocation, quantity, m, data)
                variants.isEmpty() || variants.all { (altKey, childList) ->
                    scoreVariant(altKey, childList, inventory, data, reqDt, leadDays, planningPath, depth - 1, config).fourth
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
    val cfg = resolveMethodSelection(config)
    val levels = cfg.depth
    val pastScope = !shouldElaborateAtDepth(depth, levels)
    if (pastScope || methods.size <= 1) {
        val (m, msg) = getPreferredMethod(methods)
        // When elaborate mode is configured but we've recursed past the
        // method_selection.depth scope, the message would otherwise just say
        // "Chosen: <type> @ <loc> (preference X)" with no hint that elaborate
        // was bypassed. Annotate so the UI's Why panel makes it obvious why
        // a preference-shaped explanation appears in an elaborate-mode run.
        if (pastScope && cfg.elaborate && methods.size > 1) {
            return Pair(m, "$msg [past elaborate scope (method_selection.depth=$levels): preference fallback]")
        }
        return Pair(m, msg)
    }

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
                scoreVariant("cascade", children, inventory, data, reqDt, leadDays, planningPath, depth - 1, config).fourth
            }
            "make" -> {
                val variants = variantsForMake(productId, productionLocation, quantity, m, data)
                variants.isEmpty() || variants.all { (altKey, childList) ->
                    scoreVariant(altKey, childList, inventory, data, reqDt, leadDays, planningPath, depth - 1, config).fourth
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
    val (m, msg) = getPreferredMethod(methods)
    return Pair(m, "$msg [cascade probe exhausted: every method reported child failures, took lowest preference]")
}

/**
 * Elaborate method selection: simulate one planning level per method, score, and pick best.
 * Falls back to preference-only when we're past method_selection.depth levels from the
 * root, or when only one method exists.
 */
/**
 * Per-method elaborate score, used by the single-method picker
 * (`getPreferredMethodElaborate`) and as the rank source for waterfall
 * allocation when `mode = elaborate`.
 *
 * `score` is in `[0, 1]` for non-failed methods (composite of
 * commit_time / inventory_consumed / purchase normalized by their
 * cross-method spans, weighted by `score_weights`). Failed methods get
 * `-1e9` so callers can sort/filter cleanly.
 */
internal data class MethodScore(
    val method: Map<String, Any?>,
    val score: Double,
    val failed: Boolean,
    val ts: Double,
    val consumed: Double,
    val purchase: Double,
)

/**
 * Score every method by simulating each one's BOM children and measuring
 * commit_time / inventory_consumed / purchase. Returns one `MethodScore`
 * per input in the same order. Caller sorts.
 *
 * Extracted from the original inlined scoring inside `getPreferredMethodElaborate`
 * so the multi-method splitter can reuse it without code duplication.
 */
internal fun scoreMethodsForElaborate(
    methods: List<Map<String, Any?>>,
    demand: Map<String, Any?>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    requestTimeDt: LocalDate?,
    config: Map<String, Any?>?,
    depth: Int,
    planningPath: Set<Pair<String, String>>,
): List<MethodScore> {
    val methodCfg = resolveMethodSelection(config)
    val scoreWeights = methodCfg.scoreWeights
    val (wCommit, wInv, wPurchase) = normalizeScoreWeights(scoreWeights)

    // The recursive plan() calls below are pure simulations — used to measure
    // each candidate method's commit_time / inventory_consumed / purchase
    // metrics, not to commit. Force the simulation to single-method preference
    // selection so a deep multi-method site doesn't re-enter scoring (the
    // simulation is cost-only, multi-method semantics aren't useful here).
    val simConfig: Map<String, Any?> = (config ?: emptyMap()) + mapOf(
        "method_selection" to (
            ((config?.get("method_selection") as? Map<*, *>)
                ?.let { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }
                ?: emptyMap()) + mapOf(
                "max_methods" to 1,
            )
        ),
    )
    val productId = demand["product_id"] as? String ?: ""
    val locationId = demand["location_id"] as? String ?: ""
    val quantity = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
    val reqTimeStr = demand["request_due_time"] as? String ?: demand["request_time"] as? String

    data class Raw(val method: Map<String, Any?>, val ts: Double, val consumed: Double, val purchase: Double, val failed: Boolean)

    val raw = methods.map { m ->
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
                if (variants.isEmpty()) return@map Raw(m, LATE_DATE.toEpochDay().toDouble(), 0.0, 0.0, true)
                val (variantList, _) = getPreferredVariants(
                    variants, invCopy, data, reqDt, leadDays, planningPath, depth - 1, quantity,
                    multiple = false, scoreWeights = scoreWeights, topN = null,
                    config = simConfig,
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
            val (solvedList, cWos, _) = plan(cDemand, invCopy, data, cReqDt, depth = depth - 1, planningPath = planningPath, config = simConfig)
            for (s in solvedList) {
                if ((s["quantity"] as? Number)?.toDouble() ?: 0.0 <= 0) continue
                val ct = s["commit_time"] as? String
                val reason = s["commit_reason"] as? String ?: ""
                // cycle_stopped/cycle_detected rows are emitted at full residual qty
                // with a `commit_time` set to the demand's request_due_time — they
                // look like successful commits to a naive scoring loop, even though
                // no production happened. Treat them as inert: no `maxCommit`
                // contribution, no `anyFailed` flip. The method's *real* deliverable
                // qty stays captured via `consumed` (inventory taken before the
                // cycle stop) and via the non-cycle siblings in `solvedList`.
                if (reason == "cycle_stopped" || reason == "cycle_detected") continue
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
        Raw(m, ts, consumed, purchaseQty, anyFailed)
    }

    val validTs = raw.filter { !it.failed }.map { it.ts }
    val consList = raw.map { it.consumed }
    val purchList = raw.map { it.purchase }
    var spanTs = if (validTs.size >= 2) validTs.max() - validTs.min() else 1.0
    var spanC = if (consList.isNotEmpty()) consList.max() - consList.min() else 1.0
    var spanP = if (purchList.isNotEmpty()) purchList.max() - purchList.min() else 1.0
    if (spanTs <= 0) spanTs = 1.0
    if (spanC <= 0) spanC = 1.0
    if (spanP <= 0) spanP = 1.0
    val tsMax = validTs.maxOrNull() ?: 0.0

    return raw.map { r ->
        if (r.failed) MethodScore(r.method, -1e9, true, r.ts, r.consumed, r.purchase)
        else {
            val normCommit = max(0.0, min(1.0, (tsMax - r.ts) / spanTs))
            val normInv = max(0.0, min(1.0, (r.consumed - consList.min()) / spanC))
            val normP = max(0.0, min(1.0, (purchList.max() - r.purchase) / spanP))
            val s = wCommit * normCommit + wInv * normInv + wPurchase * normP
            MethodScore(r.method, s, false, r.ts, r.consumed, r.purchase)
        }
    }
}

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

    val scored = scoreMethodsForElaborate(
        methods, demand, inventory, data, requestTimeDt, config, depth, planningPath,
    ).sortedWith(
        // Tiebreaker by preference (asc): when sims are tied — typically when every
        // candidate hit the same downstream block (e.g. a deep cycle or a missing
        // raw material), all methods score 0 or -1e9. Stable sort then preserves
        // input order, which is `[buy, make, move]` from getMethods — making the
        // planner pick the first iteration order rather than the highest-priority
        // method. Falling back to preference asc here matches the cascade picker's
        // tiebreaker and keeps elaborate's "sometimes-tied" behaviour consistent
        // with operator expectations.
        compareByDescending<MethodScore> { it.score }
            .thenBy { (it.method["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
    )

    val best = scored.first()
    if (best.failed) return getPreferredMethod(methods)
    val loc = (best.method["location_id"] ?: best.method["to_location_id"] ?: "").toString()
    return Pair(best.method, "Chosen (elaborate score): ${best.method["type"]} @ $loc " +
        "(inventory_consumed=${best.consumed.toLong()}, purchase=${best.purchase.toLong()}).")
}

// ── Per-method slot planning ──────────────────────────────────────────────────

/**
 * Result of planning one method slot for a demand.
 *
 *   achievableQty       — qty this slot actually committed (≤ slotQty requested)
 *   wos                 — work orders emitted for this slot (parent + descendant)
 *   methodPeggingNode   — WO-pegging node to attach under the demand (always non-null;
 *                         a zero-qty placeholder when the slot was blocked)
 *   latestCommit        — latest commit_time across this slot's WO chain, if any
 *   anyChildShort       — first-pass found at least one child unable to fully commit
 *                         (drives the demand-level "partial" flag at the caller)
 *   blockedReason       — non-null only when the entire slot was blocked at qty 0;
 *                         carries the `child_failed:product@loc(no_inventory)` reason
 *                         so the caller can decide whether to keep trying (waterfall)
 *                         or report the failure (single-method)
 */
internal data class MethodSlotResult(
    val achievableQty: Double,
    val wos: List<Map<String, Any?>>,
    val methodPeggingNode: Map<String, Any?>,
    val latestCommit: LocalDate?,
    val anyChildShort: Boolean,
    val blockedReason: String?,
    /**
     * Set when the method blocked AND the immediate bottleneck child is
     * structurally dead at THIS level — its first-pass effectiveQty was 0
     * AND its own commit_reason includes a raw `no_methods` (not cascade),
     * meaning the BOM child literally has no methods at its location AND no
     * inventory. Distinguishes genuine structural deadness (safe to memoize
     * across demands) from capacity-driven cascades (the immediate child has
     * a working method that failed downstream — qty- and inventory-state-
     * dependent, must NOT be cached). Default false.
     */
    val immediateBottleneckTrulyStructural: Boolean = false,
)

/**
 * Plan one method slot for a demand: resolve variants, run the first-pass /
 * bottleneck / second-pass commit logic, emit a WO and pegging node.
 *
 * Caller is responsible for the demand-level emission (committedRow, demandNode)
 * and for accumulating across slots in waterfall mode. Inventory mutates in
 * place — callers running multiple slots see slot N's consumption when planning
 * slot N+1.
 */
internal fun planMethodSlot(
    m: Map<String, Any?>,
    slotQty: Double,
    productId: String,
    locationId: String,
    demand: Map<String, Any?>,
    demandId: Any?,
    requestTimeDt: LocalDate?,
    reqTimeStr: String?,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    depth: Int,
    path: Set<Pair<String, String>>,
    config: Map<String, Any?>?,
    preferDemandId: Any?,
    overrideIndex: Map<String, Map<String, Any?>>,
    budget: MutableMap<String, Double>?,
    useSingleVariant: Boolean,
    scoreWeights: Map<String, Any?>?,
    topN: Int?,
    variantOverride: Map<String, Any?>?,
    methodChoiceExplanation: String,
    overrideActive: Boolean,
    feasibilityCache: MutableMap<Pair<String, String>, Int>? = null,
    structuralFailedMakes: MutableMap<Pair<String, String>, String>? = null,
    initialBudget: Map<String, Double>? = null,
): MethodSlotResult {
    val productionLocation = (if (m["type"] == "move") m["to_location_id"] else m["location_id"])?.toString() ?: locationId
    val reqDt = parseDate(reqTimeStr) ?: requestTimeDt ?: LocalDate.now()
    val leadDays = leadDaysForMethod(m)

    // 3) Child materials
    var woChildrenRelation: String? = null
    val (childMaterials, variantExplanation) = when (m["type"]) {
        "make" -> {
            val rawVariants = variantsForMake(productId, productionLocation, slotQty, m, data)
            // Apply variant_selection override: force a specific alt_group
            val variants = if (variantOverride != null) {
                val forcedAltGroup = variantOverride["alt_group"]?.toString()
                rawVariants.filter { (altKey, _) -> forcedAltGroup == null || altKey == forcedAltGroup }
                    .ifEmpty {
                        log.warn("variant_selection override alt_group={} for {}@{} matched nothing; using all", forcedAltGroup, productId, productionLocation)
                        rawVariants
                    }
            } else rawVariants
            val (variantList, ve) = getPreferredVariants(variants, inventory, data, reqDt, leadDays, path, depth, slotQty,
                multiple = if (useSingleVariant) false else null, scoreWeights = scoreWeights, topN = topN, config = config)
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
        "move" -> Pair(childMaterialsForMove(m, slotQty), "")
        else -> Pair(emptyList<Map<String, Any?>>(), "")
    }

    // 4) Recursively plan children — with partial-fulfillment support.
    //    a) Snapshot inventory before any child planning.
    //    b) Run a first pass for all children at full slotQty.
    //    c) Compute the achievable parent qty as the bottleneck ratio.
    //    d) If partial: restore the snapshot and re-plan at the proportionally-
    //       scaled achievable qty (second pass).
    //    e) Emit the parent WO for achievableQty.
    val childWos = mutableListOf<Map<String, Any?>>()
    val commitTimes = mutableListOf<LocalDate>()
    val childPeggingNodes = mutableListOf<Map<String, Any?>>()

    val inventorySnap = copyInventory(inventory)
    val budgetSnap: Map<String, Double>? = budget?.toMap()

    // ── First pass: plan all children at full slotQty ─────────────────────────
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
        val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, overrideIndex = overrideIndex, budget = budget, feasibilityCache = feasibilityCache, structuralFailedMakes = structuralFailedMakes, initialBudget = initialBudget)
        val effectiveQty = solvedList.sumOf { s ->
            val r = s["commit_reason"] as? String
            if (r == "cycle_stopped" || r == "cycle_detected") 0.0
            else if (!isHardPlanningFailure(r)) (s["quantity"] as? Number)?.toDouble() ?: 0.0
            else 0.0
        }
        val cTimes = solvedList.mapNotNull { s -> parseDate(s["commit_time"] as? String) }
        childPassResults.add(ChildPassResult(c, neededQty, effectiveQty, cWos, cPegging, cTimes, solvedList))
    }

    val anyChildShort = childPassResults.any { cr -> cr.effectiveQty < cr.neededQty - 1e-9 }

    // ── Determine achievable parent qty ──────────────────────────────────────
    var achievableParentQty: Double
    if (!anyChildShort || childMaterials.isEmpty()) {
        achievableParentQty = slotQty
        // Root-bottleneck tagging on the no-shortage path. Required because
        // the OUTER planMethodSlot's second pass calls plan() at the
        // post-convergence achievable qty, where deeper children fit
        // exactly — landing here. Without tagging in this branch, the
        // iter-0 root-bottleneck signal computed by a DEEPER planMethodSlot
        // (during its own AND-min branch) is discarded when the outer
        // level rebuilds the tree from scratch. Same iter-0 cap analysis
        // as the AND branch below; see [ROOTBN] log there for details.
        val rootKeys = computeRootBottleneckKeys(childPassResults, initialBudget)
        if (rootKeys.isNotEmpty()) {
            log.info("[ROOTBN-fit] parent={}@{} did={} qty={} keys={}",
                productId, productionLocation, demandId, slotQty, rootKeys)
        }
        childPassResults.forEach { cr ->
            childWos.addAll(cr.wos)
            val peg = cr.pegging
            if (peg != null) {
                val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
                val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
                val key = Pair(pid, lid)
                val tagged: Map<String, Any?> = if (key in rootKeys) peg + ("is_root_bottleneck" to true) else peg
                childPeggingNodes.add(tagged)
            }
            commitTimes.addAll(cr.cTimes)
        }
    } else {
        val (rawAchievable, isOrSplit) = computeRawAchievable(childPassResults, slotQty, woChildrenRelation)
        val capped = if (rawAchievable >= slotQty - 1e-6) slotQty
                     else floor(rawAchievable).coerceIn(0.0, slotQty)

        if (capped <= 1e-9) {
            // Nothing achievable — emit a zero-qty placeholder pegging node so the UI
            // still shows WHICH method was attempted, and return blockedReason so the
            // caller can decide whether to keep trying (waterfall) or fail the demand.
            //
            // BEFORE returning, restore inventory + budget to the pre-first-pass
            // snapshot. The first pass recursively descended this method's BOM
            // and called consumeFromInventory at every supply leaf — mutating the
            // single global `inventory` list at every depth (raw materials,
            // intermediates, deeper sub-makes' second-pass commits). Without this
            // restore those takes persist into the live state even though the
            // parent committed 0, stranding raw materials and starving subsequent
            // demands. Symmetrical to the second-pass restore at lines 1024-1031
            // — same snapshots, same mechanism.
            //
            // Subtree-wide by construction: `inventory` is one global list passed
            // by reference through all recursive plan() calls; restoring at the
            // outer level reverts mutations at every depth below.
            inventory.clear()
            inventory.addAll(inventorySnap)
            if (budget != null && budgetSnap != null) {
                budget.clear()
                budget.putAll(budgetSnap)
            }
            // Keep the first-pass child pegging trees so the UI can show *why*
            // this method was blocked — under-allocated child branches, deeper
            // child_failed cascades, partial supply takes. The trees are stale
            // (the inventory takes they reference were rolled back above), but
            // they remain the most direct visual diagnosis of the bottleneck.
            //
            // To stop the soundness checker (R4 qty propagation, R7d orphan
            // leaves) from flagging the rollback-induced inconsistencies as
            // engine bugs, the blocked WO is marked `failed = true`. The
            // checker treats any failed-marked subtree as a debug snapshot
            // and skips it — the trees are accepted as expected-broken.
            val bottleneck = childPassResults.first { cr -> cr.effectiveQty < cr.neededQty - 1e-9 }
            val immediateChildPid = bottleneck.child["product_id"]?.toString() ?: "?"
            val immediateChildLoc = bottleneck.child["location_id"]?.toString() ?: "?"
            // Pick a representative failure row from the bottleneck child's commit rows to
            // unwrap deeper. Priority order:
            //  1. Cascade reason (`child_failed:...`) — carries the deeper subtree label.
            //  2. Hard-failure reason (`no_methods`, `depth_limit`) — terminal.
            //  3. cycle_stopped / cycle_detected — terminal cause too. Classified as
            //     "benign" by isHardPlanningFailure (because they don't count toward
            //     effective qty), but they ARE the real reason a cycled child failed.
            //     Without this branch the helper falls through to the (?, ?, "no_inventory")
            //     default — masking move-cycle blocks as fake inventory shortages.
            val bottleneckReason: String? = bottleneck.solvedList
                .map { it["commit_reason"] as? String }
                .firstOrNull { r -> r != null && r.startsWith("child_failed:") }
                ?: bottleneck.solvedList
                    .map { it["commit_reason"] as? String }
                    .firstOrNull { r -> r != null && isHardPlanningFailure(r) }
                ?: bottleneck.solvedList
                    .map { it["commit_reason"] as? String }
                    .firstOrNull { r -> r == "cycle_stopped" || r == "cycle_detected" }
            val (deepestPid, deepestLoc, terminalCause) = resolveDeepestCause(bottleneckReason)
            // If the cascade unwrap landed on "?" (broken/terminal pegging — expected when
            // the deepest child reported a non-cascade reason like "no_methods"), use the
            // immediate bottleneck child's own coordinates instead. Better an honest
            // shallow label than a fabricated "?@?".
            val deepPid = if (deepestPid == "?") immediateChildPid else deepestPid
            val deepLoc = if (deepestLoc == "?") immediateChildLoc else deepestLoc
            val reason = "child_failed:${deepPid}@${deepLoc}(${terminalCause})"
            // Diagnostic: emit one log line per blocked-path AND-min so the
            // consolidation/commit phase root cause is visible in run logs.
            // Grep `[ANDMIN]` to see the cascade chain — the smallest-scale
            // entry (deepest cascade level) names the genuine root leaf.
            log.info("[ANDMIN-block] parent={}@{} did={} qty={} achievable=0 immediate-bottleneck={}@{} (effective={}/needed={}) deepest-leaf={}@{} cause={}",
                productId, locationId, demandId, slotQty,
                immediateChildPid, immediateChildLoc, bottleneck.effectiveQty, bottleneck.neededQty,
                deepPid, deepLoc, terminalCause)
            val methodType = m["type"] as? String ?: ""
            // Tag bottleneck children with `is_bottleneck=true` so the UI surfaces
            // them on the failed branch too (mirrors the partial-success case).
            // Tied children at the min ratio are all flagged.
            val blockedRatios = childPassResults.map { cr ->
                if (cr.neededQty > 1e-9) cr.effectiveQty / cr.neededQty
                else Double.POSITIVE_INFINITY
            }
            val blockedMinRatio = blockedRatios.min()
            val blockedBottleneckKeys: Set<Pair<String, String>> = childPassResults
                .zip(blockedRatios)
                .filter { (_, r) -> r <= blockedMinRatio + 1e-9 }
                .map { (cr, _) ->
                    val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
                    val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
                    Pair(pid, lid)
                }
                .toSet()
            // Root-bottleneck on the blocked path — see partial-path branch
            // for rationale. Uses iter-0 budget caps + spread check to
            // identify origin leaf among siblings with meaningfully
            // different fair-shares.
            val blockedRootKeys = computeRootBottleneckKeys(childPassResults, initialBudget)
            val taggedChildPeggings: List<Map<String, Any?>> = childPassResults.mapNotNull { cr ->
                val peg = cr.pegging ?: return@mapNotNull null
                val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
                val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
                val key = Pair(pid, lid)
                var tagged = peg
                if (key in blockedBottleneckKeys) tagged = tagged + ("is_bottleneck" to true)
                if (key in blockedRootKeys) tagged = tagged + ("is_root_bottleneck" to true)
                tagged
            }
            val blockedWoNode = buildWoNode(
                productId, productionLocation, 0.0, methodType, m,
                reqDt, null, 0, 0.0,
                "$methodChoiceExplanation — blocked: deepest child $deepPid@$deepLoc ($terminalCause)",
                variantExplanation, woChildrenRelation,
                taggedChildPeggings,
                overrideActive,
                failed = true,
            )
            // Genuinely-structural classification: the AND-min bottleneck child
            // returned 0 AND its own solvedList carries a raw `no_methods` /
            // `no_preferred_method` row (not just a cascaded child_failed:...).
            // That means THIS child has zero methods at its product/location
            // AND had no inventory — true topological dead-end, safe to memo.
            // Cascade-only cases (the child has a method but it failed deeper)
            // are runtime-dependent and excluded.
            val immediateTrulyStructural = bottleneck.effectiveQty <= 1e-9 &&
                bottleneck.solvedList.any { row ->
                    val r = row["commit_reason"] as? String
                    r == "no_methods" || r == "no_preferred_method"
                }
            return MethodSlotResult(
                achievableQty = 0.0,
                wos = emptyList(),
                methodPeggingNode = blockedWoNode,
                latestCommit = null,
                anyChildShort = true,
                blockedReason = reason,
                immediateBottleneckTrulyStructural = immediateTrulyStructural,
            )
        }
        achievableParentQty = capped

        if (isOrSplit) {
            childPassResults.forEach { cr ->
                childWos.addAll(cr.wos)
                if (cr.pegging != null) childPeggingNodes.add(cr.pegging)
                commitTimes.addAll(cr.cTimes)
            }
        } else {
            // ── Identify AND-bottleneck child(ren). Only meaningful when the
            // parent was actually capped by an AND-min (achievableParentQty <
            // slotQty). The bottleneck is the child(ren) whose first-pass
            // ratio (effectiveQty / neededQty) equals the min over all
            // children. Tied children are all flagged. Empty when the parent
            // delivered its full request (no cap), or under OR-split (no
            // single bottleneck — supply is summed across variants).
            // First-pass pegging keyed by (pid, lid) for bottleneck children. The
            // first-pass exploration ran at full qty (slotQty) and recursively
            // identified bottlenecks at every deeper level — those flags are
            // already baked into `cr.pegging`. Second-pass operates at AND-min
            // qty where all descendants fit, so its tree carries no deeper
            // bottleneck info. By preserving first-pass pegging for the
            // bottleneck child(ren), the deeper bottleneck chain (e.g. "260-0385
            // @2000 was the limiter at 11607" → "supply cap at 260-0385_2000_111
            // was the leaf cause") survives into the displayed tree.
            val bottleneckPegging: Map<Pair<String, String>, Map<String, Any?>?> =
                if (achievableParentQty < slotQty - 1e-6) {
                    val ratios = childPassResults.map { cr ->
                        if (cr.neededQty > 1e-9) cr.effectiveQty / cr.neededQty
                        else Double.POSITIVE_INFINITY
                    }
                    val minRatio = ratios.min()
                    // Diagnostic: emit one log line per partial-success AND-min cap.
                    // Grep `[ANDMIN]` to trace the cascade chain — the smallest-
                    // scale entry (deepest cascade level) names the genuine root leaf.
                    val tied = childPassResults.zip(ratios)
                        .filter { (_, r) -> r <= minRatio + 1e-9 }
                        .map { (cr, _) ->
                            val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
                            val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
                            "$pid@$lid(eff=${cr.effectiveQty}/need=${cr.neededQty})"
                        }
                    log.info("[ANDMIN-partial] parent={}@{} did={} qty={} achievable={} tied-bottlenecks={}",
                        productId, productionLocation, demandId, slotQty, achievableParentQty, tied)
                    childPassResults.zip(ratios)
                        .filter { (_, r) -> r <= minRatio + 1e-9 }
                        .associate { (cr, _) ->
                            val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
                            val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
                            Pair(pid, lid) to cr.pegging
                        }
                } else emptyMap()

            // Root-bottleneck identification using iter-0 budget caps. The
            // child whose `initialBudget[pid|lid] / cr.neededQty` is the
            // smallest is the GENUINE constraint (origin) — distinct from
            // `bottleneckPegging` above which uses post-convergence first-
            // pass ratios where multiple siblings tie at the smeared
            // AND-feasible point. Tagged with `is_root_bottleneck=true`;
            // smearing-aligned siblings still carry `is_bottleneck=true`.
            //
            // Critically NOT gated on `achievableParentQty < slotQty`. The
            // displayed final-iteration tree calls plan() at the converged
            // slotQty (e.g. 198), where every sibling's *current* budget cap
            // fits exactly — so the current-iteration AND-min check sees
            // no constraint. But the iter-0 caps (e.g. 246 vs 11607) reveal
            // which sibling was the origin that *caused* the convergence.
            // We surface that origin even when the current call shows no
            // bottleneck, otherwise the badge never appears in the tree.
            //
            // Spread check: only flag when iter-0 caps differ meaningfully
            // between siblings. Uniform caps mean either no constraint or
            // pre-balanced shares — neither identifies a single origin.
            // Empty when initialBudget is null (run wasn't started by
            // runV2Iterated, or this demand had no consolidation entry) or
            // when no child has a finite-cap entry.
            val rootBottleneckKeys = computeRootBottleneckKeys(childPassResults, initialBudget)
            if (rootBottleneckKeys.isNotEmpty()) {
                log.info("[ROOTBN] parent={}@{} did={} qty={} achievable={} keys={}",
                    productId, productionLocation, demandId, slotQty, achievableParentQty, rootBottleneckKeys)
            }

            // ── Second pass: restore inventory + budget and re-plan at achievable qty
            inventory.clear()
            inventory.addAll(inventorySnap)
            if (budget != null && budgetSnap != null) {
                budget.clear()
                budget.putAll(budgetSnap)
            }
            val scale = achievableParentQty / slotQty
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
                val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, overrideIndex = overrideIndex, budget = budget, feasibilityCache = feasibilityCache, structuralFailedMakes = structuralFailedMakes, initialBudget = initialBudget)
                childWos.addAll(cWos)
                val cPid = (c["product_id"] as? String)?.trim() ?: ""
                val cLid = (c["location_id"] as? String)?.trim() ?: ""
                val key = Pair(cPid, cLid)
                if (cPegging != null) {
                    // Tag the second-pass pegging with `is_bottleneck=true` for
                    // children that were the AND-min limiter at first-pass, and
                    // `is_root_bottleneck=true` for the genuine origin per iter-0
                    // budget cap. The two flags differ: `is_bottleneck` covers
                    // ALL siblings tied at the post-convergence smeared cap;
                    // `is_root_bottleneck` covers only the leaf whose iter-0
                    // cap-to-need ratio was the smallest (the origin that
                    // dragged the others down via convergence).
                    var tagged = cPegging
                    if (key in bottleneckPegging) tagged = tagged + ("is_bottleneck" to true)
                    if (key in rootBottleneckKeys) tagged = tagged + ("is_root_bottleneck" to true)
                    childPeggingNodes.add(tagged)
                }
                solvedList.forEach { s -> parseDate(s["commit_time"] as? String)?.let { commitTimes.add(it) } }
            }

            // Move conservation: a move WO has exactly one source-side child whose
            // committed_qty must equal the parent qty.
            if (m["type"] == "move") {
                val childCommit = childPeggingNodes.firstOrNull()?.let {
                    (it["committed_qty"] as? Number)?.toDouble()
                }
                if (childCommit != null && childCommit < achievableParentQty - 1e-6) {
                    achievableParentQty = floor(childCommit).coerceAtLeast(0.0)
                }
            }
        }
    }

    // 5) Timing + work orders
    val startDt = computeStartDt(reqDt, leadDays, commitTimes)
    val woResult = buildWorkOrders(productId, productionLocation, achievableParentQty, leadDays, startDt, m, demandId, data, overrideActive)
    val wos = woResult.wos
    val lotCount = woResult.lotCount
    val lastEnd = woResult.lastEnd
    val lotSizeVal = woResult.lotSizeVal
    val methodType = m["type"] as? String ?: ""
    val woChildren = if (methodType == "purchase") listOf(mapOf("type" to "purchase", "product_id" to productId, "location_id" to productionLocation, "quantity" to roundQty(achievableParentQty), "children" to emptyList<Any>()))
                     else childPeggingNodes
    val methodPeggingNode = buildWoNode(productId, productionLocation, achievableParentQty, methodType, m, startDt, lastEnd, lotCount, lotSizeVal, methodChoiceExplanation, variantExplanation, woChildrenRelation, woChildren, overrideActive, woGroupId = woResult.woGroupId)

    return MethodSlotResult(
        achievableQty = achievableParentQty,
        wos = wos + childWos,
        methodPeggingNode = methodPeggingNode,
        latestCommit = lastEnd,
        anyChildShort = anyChildShort,
        blockedReason = null,
    )
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
    config: Map<String, Any?>? = null,
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
        val sc = scoreVariant(altKey, childList, inventory, data, reqDt, leadDays, planningPath, depth, config)
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
    /**
     * Optional per-component budget map keyed by `"$productId|$locationId"`. When supplied,
     * each call to [consumeFromInventory] caps the take at the remaining budget for the
     * (pid, lid) and the entry is decremented in-place. Threaded recursively through child
     * plan() calls so a single budget map governs the whole sub-tree of one demand.
     * Stage-3 alternative to demand-tagged synthetic supply buckets.
     */
    budget: MutableMap<String, Double>? = null,
    /**
     * Memoized make-feasibility cache: `(pid, lid) → maxMakeDepth`. When non-null,
     * the reactive-fallback site admits make-as-fallback for products whose depth
     * is bounded by `method_selection.max_bom_depth` (default 3). Caller-owned;
     * computed lazily on first query. Pass `mutableMapOf()` from the planning
     * entry to enable, or leave `null` to keep fallback move-only.
     */
    feasibilityCache: MutableMap<Pair<String, String>, Int>? = null,
    /**
     * Memoized structural-failure set for makes. When a make-fallback for
     * (pid, lid) hard-blocks on a structural cascade (no_methods /
     * no_preferred_method anywhere in its BOM tree), this set records (pid, lid)
     * so subsequent demands skip the make admission entirely — the failure
     * depends only on `data`, so the answer is the same for every caller.
     * Cuts the per-demand recursion cost on shared structurally-doomed subtrees.
     */
    structuralFailedMakes: MutableMap<Pair<String, String>, String>? = null,
    /**
     * Optional iter-0 snapshot of consolidation-engine per-leaf budget caps
     * for THIS demand. Used by planMethodSlot to identify the *origin* leaf
     * of the AND-bottleneck (smallest cap-to-need ratio among siblings) —
     * tagged with `is_root_bottleneck=true` to distinguish from the
     * post-convergence smearing-aligned siblings (`is_bottleneck=true`).
     * Read-only; immutable across the plan walk. Pass-through to recursive
     * plan() calls so deeper levels can also identify their own roots.
     */
    initialBudget: Map<String, Double>? = null,
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
    val componentKey = "$productId|$locationId"
    val budgetCap = budget?.get(componentKey)
    val consumedBuckets = consumeFromInventory(inventory, productId, locationId, quantity, preferDemandId, budgetCap)
    val taken = consumedBuckets.sumOf { it.qty }
    if (budget != null && budgetCap != null) {
        budget[componentKey] = (budgetCap - taken).coerceAtLeast(0.0)
    }
    val fulfillCommitTime = consumedBuckets.firstOrNull()?.commitTime
    val demandFulfilledList = mutableListOf<Map<String, Any?>>()
    val peggingChildren = mutableListOf<Map<String, Any?>>()

    if (taken > 0) {
        val commitForFulfilled = fulfillCommitTime ?: reqTimeStr
        demandFulfilledList.add(committedRow(taken, commitForFulfilled, "inventory"))
        // One supply node per consumed bucket so each node carries its exact supply_id.
        // Keep `quantity` UNROUNDED — fair-split allocators distribute supply.qty across
        // demands as fractional shares (e.g. 4 units / 6 demands = 0.667 each), and
        // rounding each leaf to 1 makes Σ leaves overstate consumption (R7b violation
        // on case 171's 500-6496_1000_4: 6 × roundQty(0.667)=6 against supply.qty=4).
        // extractSupplyAllocations + R7b checker both sum these qtys, so accuracy
        // matters more than display niceness.
        for (bucket in consumedBuckets) {
            peggingChildren.add(mapOf(
                "type" to "supply",
                "product_id" to productId,
                "location_id" to locationId,
                "supply_id" to bucket.supplyId,
                "quantity" to bucket.qty,
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
    // Sub-half residuals after partial inventory consumption are numerical noise from
    // proportional pda splits and partial-replan scaling (e.g. demand=2119 vs synthetic
    // bucket=2118.95 leaves a 0.05 residual). Recursing on those produces a roundQty(0.x)=0
    // WO in the ledger and a phantom pegging entry — absorb them as fulfilled instead.
    if (taken > 0 && demandNetQty < 0.5) {
        return Triple(demandFulfilledList, emptyList(), demandNode(peggingChildren, fulfillCommitTime, committedQty = quantity))
    }

    // 2) Get methods
    val purchaseAllowed = config?.get("purchase_allowed") != false  // default true
    val methodsRaw = getMethods(productId, locationId, data)
    val methods = if (purchaseAllowed) methodsRaw else methodsRaw.filter { m -> m["type"] != "purchase" }
    if (methods.isEmpty()) {
        // Diagnostic: surface WHY no methods were available so the pegging
        // tree carries enough context to answer "which component has no
        // supply?". Three flavours, in priority order:
        //   1. Buy filtered out by purchase_allowed=false — cheap fix.
        //   2. Methods exist at OTHER locations of this product — data issue
        //      (no move-to-here / make-at-here defined).
        //   3. Truly no methods anywhere — terminal data gap.
        // Attached to the demand node as `failure_explanation` so the UI can
        // render it inline in the failed-pegging area (no awkward synthetic
        // work_order child with a fake method label).
        val buyFiltered = !purchaseAllowed && methodsRaw.any { it["type"] == "purchase" }
        val otherLocsForMake = (data["method_make"] ?: emptyList())
            .filter { (it["product_id"] as? String)?.trim() == productId }
            .mapNotNull { (it["location_id"] as? String)?.trim() }
            .filter { it != locationId && it.isNotBlank() }
            .distinct()
        val otherLocsForMove = (data["method_move"] ?: emptyList())
            .filter { (it["product_id"] as? String)?.trim() == productId }
            .mapNotNull { (it["to_location_id"] as? String)?.trim() }
            .filter { it != locationId && it.isNotBlank() }
            .distinct()
        val otherLocs = (otherLocsForMake + otherLocsForMove).distinct()
        val explanation = buildString {
            append("No supply method for $productId @ $locationId.")
            if (buyFiltered) {
                append(" A buy method exists at this location but is excluded because " +
                    "purchase_allowed=false; enable purchase to admit it.")
            }
            if (otherLocs.isNotEmpty()) {
                append(" The product CAN be produced at: ${otherLocs.joinToString(", ")}, " +
                    "but no method (move-to-$locationId or make-at-$locationId) is defined to " +
                    "bring it here. Add a method_move row from one of those locations to " +
                    "$locationId, or define a make recipe at $locationId.")
            }
            if (!buyFiltered && otherLocs.isEmpty()) {
                append(" No method exists for this product at any location — " +
                    "data gap (missing method_make / method_move / method_buy rows).")
            }
        }
        demandFulfilledList.add(committedRow(demandNetQty, reqTimeStr, "no_methods"))
        val baseNode = demandNode(peggingChildren, reqTimeStr, "no_methods", committedQty = taken)
        return Triple(demandFulfilledList, emptyList(), baseNode + ("failure_explanation" to explanation))
    }

    if (methods.size > 1) {
        log.info("multi-method: demand_id={} product_id={} location_id={} count={} types={}",
            demandId, productId, locationId, methods.size, methods.map { it["type"] })
    }

    val methodCfg = resolveMethodSelection(config)
    val variantCfg = resolveVariantSelection(config)
    val useElaborateMethod = methodCfg.elaborate
    val useSingleVariant = variantCfg.multiple == false
    val scoreWeights = variantCfg.scoreWeights
    val topN = variantCfg.topN

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

    val elaborateAtThisLevel = shouldElaborateAtDepth(depth, methodCfg.depth)

    // ── Waterfall multi-method allocation ──────────────────────────────────
    // Restricted to root via `elaborateAtThisLevel` (gated by
    // `method_selection.depth=1`). Waterfall does both reactive fallback
    // and proactive split in one loop, but firing it at every BOM depth
    // explodes combinatorially even with `maxMethods=2` (observed: 22k
    // multi-method log lines / 30s on case 171). Reactive fallback at
    // deeper levels is handled below in the single-method path — cheaper
    // because it only fires on a hard block, not on every multi-method
    // site.
    val useWaterfall = methodCfg.maxMethods > 1 && elaborateAtThisLevel && effectiveMethods.size > 1
    if (useWaterfall) {
        // Rank methods once. Preference mode → ascending preference int (cascade
        // order). Elaborate mode → composite score descending. Failed elaborate
        // candidates carry score=-1e9 and naturally sink to the bottom.
        val ranked: List<Map<String, Any?>> = if (useElaborateMethod) {
            scoreMethodsForElaborate(effectiveMethods, demand, inventory, data, requestTimeDt, config, depth, path)
                .sortedWith(
                    // Tiebreaker by preference asc — see getPreferredMethodElaborate
                    // for the rationale (tied sims under deep blocks would otherwise
                    // pick whatever comes first in getMethods's iteration order).
                    compareByDescending<MethodScore> { it.score }
                        .thenBy { (it.method["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
                ).map { it.method }
        } else {
            effectiveMethods.sortedBy { (it["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
        }

        // Override-active determination for waterfall: only "did override narrow
        // the candidate set" applies, since waterfall doesn't pick a single
        // method that could "differ from auto-selection".
        val methodOverrideActiveW = methodOverride != null && effectiveMethods.size < methods.size
        val overrideActiveW = methodOverrideActiveW || variantOverride != null

        val cap = methodCfg.maxMethods.coerceAtMost(ranked.size)
        var residual = demandNetQty
        var latestCommit: LocalDate? = null
        val allWos = mutableListOf<Map<String, Any?>>()
        val slotPeggingNodes = mutableListOf<Map<String, Any?>>()
        var slotsUsed = 0
        var lastBlockedReason: String? = null

        for (method in ranked) {
            if (slotsUsed >= cap) break
            // MIN_RESIDUAL: skip trivial leftovers from lot-size rounding so we
            // don't burn a slot emitting a 0.x-unit second WO.
            if (residual <= MIN_WATERFALL_RESIDUAL) break
            // (Note: a stricter preflight using `firstFeasibleMethod` was tried
            // and rejected — that probe fails on any partial child commit, which
            // would skip slots that could legitimately commit some qty under
            // partial-fulfillment. planMethodSlot itself short-circuits on
            // capped<=0, and the UI suppresses zero-qty pegging subtrees, so the
            // remaining cost of a doomed slot is bounded and the noise is
            // already hidden at render time.)

            val mLoc = (method["location_id"] ?: method["to_location_id"] ?: "").toString()
            val slotLabel = "Waterfall slot ${slotsUsed + 1}/${cap}: ${method["type"]}@$mLoc " +
                "(planning ${roundQty(residual).toLong()} of ${demandNetQty.toLong()} residual)"
            val slot = planMethodSlot(
                m = method, slotQty = residual,
                productId = productId, locationId = locationId,
                demand = demand, demandId = demandId,
                requestTimeDt = requestTimeDt, reqTimeStr = reqTimeStr,
                inventory = inventory, data = data,
                depth = depth, path = path,
                config = config, preferDemandId = preferDemandId,
                overrideIndex = overrideIndex, budget = budget,
                useSingleVariant = useSingleVariant,
                scoreWeights = scoreWeights, topN = topN,
                variantOverride = variantOverride,
                methodChoiceExplanation = slotLabel,
                overrideActive = overrideActiveW,
                feasibilityCache = feasibilityCache,
                structuralFailedMakes = structuralFailedMakes,
                initialBudget = initialBudget,
            )
            // Always record the slot's pegging node so the UI shows every attempt
            // (including blocked ones with zero qty). Hard-failures still consume
            // a slot — a method that committed 0 is informative for the user.
            slotPeggingNodes.add(slot.methodPeggingNode)
            slotsUsed += 1
            if (slot.blockedReason != null) {
                lastBlockedReason = slot.blockedReason
                continue
            }
            residual -= slot.achievableQty
            allWos.addAll(slot.wos)
            slot.latestCommit?.let { c ->
                if (latestCommit == null || c > latestCommit) latestCommit = c
            }
        }

        val totalCommitted = demandNetQty - residual
        val partialReason = when {
            // Nothing committed at all — surface the last slot's hard-fail reason
            // so downstream KPIs / soundness see a real failure (not "partial").
            totalCommitted <= 1e-9 -> lastBlockedReason ?: "no_methods_succeeded"
            // Some committed but residual above the noise floor → partial fulfillment.
            residual > 1e-9 -> "partial"
            else -> null
        }
        val commitTimeStr = formatDate(latestCommit)
        demandFulfilledList.add(committedRow(totalCommitted, commitTimeStr ?: reqTimeStr, partialReason))
        val finalPegging = slotPeggingNodes + peggingChildren
        // Multi-slot waterfall: siblings under the demand are additive supply
        // paths (slot 1 + slot 2 each cover part of the demand). Mark OR so the
        // UI labels them as alternatives, matching the legend.
        val demandRelation = if (finalPegging.size > 1) "or" else null
        return Triple(
            demandFulfilledList,
            allWos,
            demandNode(finalPegging, commitTimeStr ?: reqTimeStr, partialReason, committedQty = taken + totalCommitted, childrenRelation = demandRelation),
        )
    }

    // ── Single method selection ────────────────────────────────────────────────
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

    // ── Single-method commit via the shared planMethodSlot helper ───────────
    // Reactive fallback: if the picked method blocks at 0 achievable, try
    // alternatives by preference. Two flavours of alternative are admitted:
    //
    //  1) Move-to-move (same product, different `from_location`). Catches the
    //     operator's primary case — tied-preference moves at the same site
    //     where one source's downstream chain dies but another succeeds
    //     (e.g. `move 1000→VIRTUAL` blocks via the @1000 chain;
    //     `move 2000→VIRTUAL` succeeds via @2000). Downstream BOM is the
    //     same — only the starting location differs, so cost is bounded.
    //
    //  2) Real make alternatives gated by [maxMakeDepth] feasibility cache.
    //     The cache pre-computes the minimum real-make-recursion depth needed
    //     to source `(productId, locationId)` via any structurally-feasible
    //     path (moves are free traversals, makes count). A make whose depth
    //     exceeds `methodCfg.maxBomDepth` (UI knob "Max BOM depth", default 3)
    //     is skipped without recursing — no wasteful exploration of
    //     structurally doomed BOM subtrees. Only fires when [feasibilityCache]
    //     is provided.
    //
    // VirtualProduct_* targets are exempt from the make filter (their only
    // method is make by data-model construction).
    val isMoveType = m["type"] == "move"
    val moveAlternatives: List<Map<String, Any?>> = if (isMoveType) {
        effectiveMethods
            .filter { it["type"] == "move" && it["from_location_id"] != m["from_location_id"] }
            .sortedBy { (it["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
    } else emptyList()

    val cachedMakeFailureReason = structuralFailedMakes?.get(Pair(productId, locationId))
    // Track makes that were excluded by the depth gate so we can surface a
    // diagnostic stub at the end. When the recipe is structurally infeasible
    // under the current `purchase_allowed` (e.g. transitive children are
    // buy-only with no inventory), maxMakeDepth returns Int.MAX_VALUE and the
    // make silently disappears from fallbackOrder — making "why didn't make
    // fire?" hard to answer from the pegging tree alone.
    var excludedMakeDepth: Int? = null   // computed depth (Int.MAX_VALUE => structural)
    val makeAlternatives: List<Map<String, Any?>> = if (feasibilityCache != null && cachedMakeFailureReason == null) {
        val purchaseAllowedForCache = config?.get("purchase_allowed") != false
        val rawMakes = effectiveMethods.filter { it["type"] == "make" && it !== m }
        rawMakes
            .filter {
                if (productId.startsWith("VirtualProduct_")) true
                else {
                    val mkDepth = maxMakeDepth(productId, locationId, data, purchaseAllowedForCache, feasibilityCache)
                    val admitted = mkDepth <= methodCfg.maxBomDepth
                    if (!admitted && excludedMakeDepth == null) excludedMakeDepth = mkDepth
                    admitted
                }
            }
            .sortedBy { (it["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
    } else emptyList()
    val fallbackOrder = listOf(m) + moveAlternatives + makeAlternatives
    val cap = methodCfg.maxMethods.coerceAtMost(fallbackOrder.size)

    // Mini-waterfall over `fallbackOrder`. Three flavours of advancement:
    //   • Reactive fallback — slot blocks (achievable=0). `continue` to next.
    //   • Proactive split into a MAKE — when the previous slot succeeded only
    //     partially AND the next slot is a make, run it for residual qty.
    //     Bounded by feasibilityCache having admitted the make in the first
    //     place (it gates by maxMakeDepth).
    //   • Otherwise (next slot is move and prev succeeded): break — splitting
    //     across moves at deep levels causes 2^N compounding (case 171's
    //     SUB_PCBA-style chains).
    val combinedWos = mutableListOf<Map<String, Any?>>()
    val combinedPegging = mutableListOf<Map<String, Any?>>()
    var residual = demandNetQty
    var totalAchievable = 0.0
    var anyChildShortAccum = false
    var latestCommit: LocalDate? = null
    var lastBlockedReason: String? = null
    for ((slotIdx, candidate) in fallbackOrder.take(cap).withIndex()) {
        if (slotIdx > 0 && residual <= MIN_WATERFALL_RESIDUAL) break
        val labelPrefix = if (slotIdx == 0) methodChoiceExplanation
            else "Fallback slot ${slotIdx + 1}/$cap: " +
                "${candidate["type"]}@${candidate["location_id"] ?: candidate["to_location_id"] ?: ""}" +
                " (residual=${roundQty(residual).toLong()})"
        val attempt = planMethodSlot(
            m = candidate, slotQty = if (slotIdx == 0) demandNetQty else residual,
            productId = productId, locationId = locationId,
            demand = demand, demandId = demandId,
            requestTimeDt = requestTimeDt, reqTimeStr = reqTimeStr,
            inventory = inventory, data = data,
            depth = depth, path = path,
            config = config, preferDemandId = preferDemandId,
            overrideIndex = overrideIndex, budget = budget,
            useSingleVariant = useSingleVariant,
            scoreWeights = scoreWeights, topN = topN,
            variantOverride = variantOverride,
            methodChoiceExplanation = labelPrefix,
            overrideActive = overrideActive,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
            initialBudget = initialBudget,
        )
        combinedPegging.add(attempt.methodPeggingNode)
        if (attempt.blockedReason != null) {
            // Reactive fallback: try the next method on hard block.
            lastBlockedReason = attempt.blockedReason
            // Memoize structural make failures so future demands skip the
            // doomed BOM walk. Only memo for actual make slots (slotIdx>0
            // gating into makeAlternatives), and only when the failure is
            // structural (no_methods cascade), not state-dependent.
            if (slotIdx > 0 && candidate["type"] == "make"
                && structuralFailedMakes != null
                && attempt.immediateBottleneckTrulyStructural
            ) {
                // Only memoize when the make's IMMEDIATE bottleneck child is
                // genuinely structurally dead — topological dead-end at this
                // level, no methods + no inventory at the child's own pid/lid.
                // Capacity-driven cascades where the immediate child has a
                // method that failed deeper are state-dependent and excluded
                // (their failure depends on runtime inventory state, not data
                // topology — caching them across demands is unsound).
                structuralFailedMakes[Pair(productId, locationId)] = attempt.blockedReason
            }
            continue
        }
        combinedWos.addAll(attempt.wos)
        totalAchievable += attempt.achievableQty
        residual -= attempt.achievableQty
        if (attempt.anyChildShort) anyChildShortAccum = true
        attempt.latestCommit?.let { c -> if (latestCommit == null || c > latestCommit) latestCommit = c }

        // Proactive split allowed only when the next admitted slot is a make.
        // Move-to-move split would compound at deep multi-move sites; make
        // alternatives are gated by feasibilityCache and naturally bounded.
        val nextIdx = slotIdx + 1
        if (nextIdx >= cap) break
        if (fallbackOrder[nextIdx]["type"] != "make") break
    }

    // Diagnostic stub: when structuralFailedMakes excluded the make from
    // admission, emit a synthetic blocked work_order pegging node so the user
    // sees the make was considered (and why it was skipped) without paying
    // for the recursion. The stub carries qty=0 and the cached failure reason
    // from the first demand that hit this dead end.
    if (cachedMakeFailureReason != null) {
        val stubMakeMethod = effectiveMethods.firstOrNull { it["type"] == "make" }
        if (stubMakeMethod != null) {
            // Unwrap the cascade reason to surface the deepest structural cause
            // (e.g. "child_failed:280-0511-03@2000(no_methods)" → child=280-0511-03,
            // loc=2000, cause=no_methods). Embed as a synthetic demand-leaf child
            // so the make stub becomes expandable and the user can see WHICH BOM
            // child caused the structural failure.
            val (deepPid, deepLoc, terminalCause) = resolveDeepestCause(cachedMakeFailureReason)
            val syntheticLeaf = mapOf<String, Any?>(
                "type" to "demand",
                "demand_id" to demandId,
                "product_id" to (if (deepPid == "?") productId else deepPid),
                "location_id" to (if (deepLoc == "?") locationId else deepLoc),
                "quantity" to 0,
                "committed_qty" to 0,
                "commit_reason" to terminalCause,
                "children" to emptyList<Any>(),
            )
            combinedPegging.add(mapOf(
                "type" to "work_order",
                "product_id" to productId,
                "location_id" to locationId,
                "quantity" to 0,
                "method" to "make",
                "method_choice_explanation" to
                    "Make skipped: cached structural failure ($cachedMakeFailureReason). " +
                    "A prior demand attempted make at this product/location and hit a " +
                    "no_methods cascade; reusing that result here to avoid redundant recursion.",
                "failed" to true,
                "children" to listOf(syntheticLeaf),
            ))
        }
    }

    // Diagnostic stub: when the make-fallback feasibility cache excluded the
    // make from admission (because maxMakeDepth exceeds method_selection.
    // max_bom_depth, often Int.MAX_VALUE — the recipe is structurally
    // infeasible under the current `purchase_allowed`), emit a stub so the
    // user sees the make was considered. Only fires when the structural-
    // failure stub above didn't already fire (mutually exclusive — the cache
    // gate runs BEFORE structuralFailedMakes was consulted, so if we got here
    // with cachedMakeFailureReason != null we already emitted that stub).
    if (cachedMakeFailureReason == null && excludedMakeDepth != null) {
        val stubMakeMethod = effectiveMethods.firstOrNull { it["type"] == "make" }
        if (stubMakeMethod != null) {
            val depthValue = excludedMakeDepth!!
            val isStructural = depthValue == Int.MAX_VALUE
            val purchaseAllowedFlag = config?.get("purchase_allowed") != false
            val explanation = if (isStructural) {
                "Make skipped: recipe is structurally infeasible (maxMakeDepth=∞). " +
                    "Some transitive BOM child has no terminal source under the current " +
                    "configuration (purchase_allowed=$purchaseAllowedFlag) — typically a " +
                    "buy-only leaf with no inventory at the needed location. Enable purchase " +
                    "or provision inventory upstream to admit this make."
            } else {
                "Make skipped: maxMakeDepth=$depthValue exceeds method_selection." +
                    "max_bom_depth=${methodCfg.maxBomDepth}. Raise max_bom_depth to admit " +
                    "this deeper recipe."
            }
            combinedPegging.add(mapOf(
                "type" to "work_order",
                "product_id" to productId,
                "location_id" to locationId,
                "quantity" to 0,
                "method" to "make",
                "method_choice_explanation" to explanation,
                "failed" to true,
                "children" to emptyList<Any>(),
            ))
        }
    }

    if (totalAchievable <= 1e-9) {
        // Every slot blocked. Surface the last failed reason and include all
        // attempted WOs so the UI shows the full fallback trail.
        demandFulfilledList.add(committedRow(0.0, reqTimeStr, lastBlockedReason ?: "no_methods_succeeded"))
        val failedPegging = combinedPegging + peggingChildren
        return Triple(demandFulfilledList, emptyList(), demandNode(failedPegging, reqTimeStr, lastBlockedReason, committedQty = taken))
    }

    // Some commit. Combine all attempted WOs (success + blocked) in pegging.
    peggingChildren.addAll(combinedPegging)
    val partialReason = if ((anyChildShortAccum || combinedPegging.size > 1) && residual > 1e-9) "partial" else null
    val commitTimeStr = formatDate(latestCommit)
    demandFulfilledList.add(committedRow(totalAchievable, commitTimeStr, partialReason))
    return Triple(demandFulfilledList, combinedWos, demandNode(peggingChildren, commitTimeStr ?: reqTimeStr, partialReason, committedQty = taken + totalAchievable))
}

// ── Lot-batching helper ────────────────────────────────────────────────────────

private data class WorkOrderResult(
    val wos: List<Map<String, Any?>>,
    val lotCount: Int,
    val lastEnd: LocalDate?,
    val lotSizeVal: Double,
    val woGroupId: String,
)

// Process-wide unique-id source for WO groups.  One id per `planMethodSlot`
// decision; shared by every lot row emitted by `buildWorkOrders` for that
// decision and stamped onto the matching pegging-tree WO node by `buildWoNode`.
// Used by `fixTimingFromPegging` to match pegging nodes to their lot rows
// without depending on (pid, lid, start_time) heuristics.
private val woGroupIdSeq = java.util.concurrent.atomic.AtomicLong(0)
private fun nextWoGroupId(): String = "wog${woGroupIdSeq.incrementAndGet()}"

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
    val woGroupId = nextWoGroupId()
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
            "wo_group_id" to woGroupId,
        ))
        lastEnd = lotEnd
        left -= lotQty
        lotCount++
        lotStart = if (left > 1e-9) lotEnd else null
    }
    return WorkOrderResult(wos, lotCount, lastEnd, lotSizeVal, woGroupId)
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
    failed: Boolean = false,
    woGroupId: String? = null,
): Map<String, Any?> = buildMap {
    put("type", "work_order")
    put("product_id", productId)
    put("location_id", productionLocation)
    put("quantity", roundQty(qty))
    put("start_time", formatDate(startDt))
    put("end_time", formatDate(lastEnd))
    put("method", methodType)
    put("location_source", if (methodType == "move") m["from_location_id"] else null)
    put("method_choice_explanation", methodChoiceExpl)
    put("variant_choice_explanation", variantExpl.ifBlank { null })
    put("children_relation", childrenRelation)
    put("lot_count", if (lotCount > 0) lotCount else null)
    put("max_lot_size", lotSizeVal)
    put("override_active", overrideActive)
    put("children", woChildren)
    if (woGroupId != null) put("wo_group_id", woGroupId)
    // Marker for the AND-bottleneck blocked branch: this WO is a debug snapshot
    // of "what would have happened" — its subtree shows first-pass takes that
    // were rolled back by inventory.clear()/inventory.addAll(snap) at the
    // outer level. Soundness skips the entire subtree under failed=true to
    // tolerate the broken/partial pegging it carries.
    if (failed) put("failed", true)
}

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
    insideFailedWo: Boolean = false,
): Map<String, Any?>? {
    val type = node["type"] as? String
    if (type == "supply" || type == "purchase") return node
    if (type == "demand" && node["commit_reason"] == "cycle_stopped") {
        // Inside a failed=true work_order's subtree, cycle_stopped demand
        // nodes ARE the diagnostic (showing where the BOM walk hit the
        // cycle). Keep them as leaf nodes so users can see the cause.
        // Outside failed subtrees, drop as "phantom move cycle" noise.
        if (insideFailedWo) {
            return node.toMutableMap().apply { put("children", emptyList<Any>()) }
        }
        return null
    }

    val isMoveWo = type == "work_order" &&
        (node["method"] as? String)?.lowercase() == "move"

    // Track failed-WO context for descendants. Once inside a failed branch,
    // every nested cycle_stopped demand is a diagnostic, not noise.
    val explicitlyFailed = node["failed"] == true
    val descendantInsideFailedWo = insideFailedWo || (type == "work_order" && explicitlyFailed)

    val prunedChildren = (node["children"] as? List<Map<String, Any?>>)
        ?.mapNotNull { prunePhantomLoops(it, isRoot = false, parentIsMoveWo = isMoveWo, insideFailedWo = descendantInsideFailedWo) } ?: emptyList()

    // Preserve explicitly-failed work_orders even when empty — they're
    // diagnostic markers (failed move with cycle_stopped child pruned out,
    // make stub injected when structuralFailedMakes memo'd, AND-bottleneck
    // blocked branch). Stripping them hides why the parent demand failed.
    if (type == "work_order" && prunedChildren.isEmpty() && !explicitlyFailed) return null
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

    // Walk variant 1: attribute every supply leaf to a single demand id (default behavior).
    fun walk(node: Map<String, Any?>, demandId: String?) {
        val type = node["type"] as? String
        // Skip subtrees rooted at failed=true work_orders — those carry the
        // AND-bottleneck blocked-branch diagnostic snapshot (first-pass child
        // peggings that were rolled back at the planner level via inventory
        // restore). Their supply-leaf qtys never actually drew from inventory,
        // so attributing them as `qty_consumed` over-counts and blows the
        // cap-enforcement budget downstream (one supply lot reports 100% util
        // while another reports 0%, and per-demand totals double-count first-
        // pass exploration). Mirrors the same `failed=true` filter applied
        // by prunePhantomLoops on the UI render path.
        if (type == "work_order" && node["failed"] == true) return
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

    // Walk variant 2: split every supply leaf across multiple demands by weights.
    // Used for consolidated entries (passthrough or multi-demand) so that raw materials
    // consumed inside the BOM chain are attributed to the demands that share the parent
    // consolidated supply, rather than to a synthetic null/unknown demand.
    fun walkSplit(node: Map<String, Any?>, weights: Map<String, Double>) {
        val type = node["type"] as? String
        // Skip failed=true subtrees — see walk() for rationale.
        if (type == "work_order" && node["failed"] == true) return
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
                val totalWeight = weights.values.sum()
                if (totalWeight > 1e-12) {
                    for ((did, w) in weights) {
                        val share = effectiveQty * (w / totalWeight)
                        if (share > 1e-9) {
                            result.add(mapOf(
                                "supply_id"    to supplyId,
                                "demand_id"    to did,
                                "qty_consumed" to share,
                            ))
                        }
                    }
                } else {
                    result.add(mapOf(
                        "supply_id"    to supplyId,
                        "demand_id"    to "",
                        "qty_consumed" to effectiveQty,
                    ))
                }
            }
        } else {
            (node["children"] as? List<Map<String, Any?>>)?.forEach { walkSplit(it, weights) }
        }
    }

    for (entry in pegging) {
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        val perDemand = entry["per_demand_allocations"] as? Map<String, Any?>
        if (perDemand != null && perDemand.isNotEmpty()) {
            val weights = perDemand
                .mapNotNull { (k, v) -> (v as? Number)?.toDouble()?.let { k to it } }
                .filter { it.second > 1e-12 }
                .toMap()
            if (weights.isNotEmpty()) {
                walkSplit(tree, weights)
                continue
            }
        }
        val demandId = entry["demand_id"] as? String
        walk(tree, demandId)
    }
    return result
}

// ── Phase functions (legacy single-pass flow) ────────────────────────────────
//
// runPlanning() orchestrates three logical phases. They are extracted here so
// the upcoming plan-then-consolidate refactor can plug in new implementations
// without touching the orchestrator. Behavior is identical to pre-refactor —
// these functions wrap the previously inlined regions of runPlanning().

internal data class LegacyCommitResult(
    val committedDemands: List<Map<String, Any?>>,
    val workOrders: List<Map<String, Any?>>,
    val planningPegging: List<Map<String, Any?>>,
)

/**
 * Maximum number of phase 2+3 iterations the v2 fixed-point controller
 * ([runV2Iterated]) will run before giving up and applying [reconcileOverProduction]
 * as a single-pass merged-leaf trim. In practice 1-2 iterations converge most
 * scenarios; the cap is a safety net for pathological alt-cascade BOMs that
 * would otherwise oscillate.
 *
 * Raised from 5 to 15 (2026-04-26) to give monotone-cap convergence room when
 * shared-RM diamond BOMs cause caps to take several rounds to settle.
 */
private const val MAX_PLANNING_ITERATIONS = 15

/**
 * Phases 2+3 result for one fixed-point iteration cycle. [iterations] reports
 * how many phase-2+3 passes the controller actually ran (1 means the first pass
 * already had no over-production). [converged] is false only when the controller
 * exhausted [MAX_PLANNING_ITERATIONS] and had to fall back to
 * [reconcileOverProduction].
 */
private data class V2IteratedResult(
    val consolidatedWOs: List<Map<String, Any?>>,
    val consolidatedPegging: List<Map<String, Any?>>,
    val commitResult: LegacyCommitResult,
    val iterations: Int,
    val converged: Boolean,
)

/**
 * Stage 4a: passive over-production trim (single pass, no feedback loop).
 *
 * After Phase-3 commit, scan the v2 budget map for unused capacity. Each leftover
 * unit at `(pid, lid)` represents qty produced at the merged leaf but never
 * consumed by the demand chain — typically because the chain found inventory at
 * an intermediate node higher up the BOM and never recursed to the leaf. Trim:
 *   - the consolidated WOs at that component (last-lot-first), and
 *   - the matching untagged supply bucket emitted in phase 2.
 *
 * Used by [runV2Iterated] as the last-iter fallback when the fixed-point loop
 * fails to converge. Note: only trims at the merged leaf — sub-component WOs
 * (BOM children of the merged leaf) are NOT cascade-trimmed by this routine;
 * fixed-point iteration is the mechanism that resizes those.
 *
 * Returns (componentsTrimmed, totalQtyTrimmed) for telemetry.
 */
private fun reconcileOverProduction(
    consolidatedWOs: MutableList<Map<String, Any?>>,
    inventory: MutableList<MutableMap<String, Any?>>,
    finalBudgets: Map<Any?, MutableMap<String, Double>>,
): Pair<Int, Double> {
    val overByComponent = mutableMapOf<String, Double>()
    for ((_, demandBudget) in finalBudgets) {
        for ((componentKey, remaining) in demandBudget) {
            if (remaining > 1e-9) {
                overByComponent.merge(componentKey, remaining, Double::plus)
            }
        }
    }
    if (overByComponent.isEmpty()) return Pair(0, 0.0)

    var componentsTrimmed = 0
    var totalQtyTrimmed = 0.0

    for ((componentKey, overQty) in overByComponent) {
        if (overQty <= 1e-9) continue
        val parts = componentKey.split("|", limit = 2)
        val pid = parts.getOrElse(0) { "" }
        val lid = parts.getOrElse(1) { "" }

        // Trim consolidated WOs at this component. Walk last lot first so the
        // earliest lots stay intact (they ran first and produced the qty that
        // was actually consumed). Drop fully-zeroed lots from the list.
        val matchingWoIndices = consolidatedWOs
            .withIndex()
            .filter { (_, wo) ->
                wo["product_id"]?.toString() == pid &&
                wo["location_id"]?.toString() == lid &&
                wo["consolidated"] == true
            }
            .map { it.index }
            .sortedByDescending { idx ->
                consolidatedWOs[idx]["start_time"] as? String ?: ""
            }
        var remainingToTrim = overQty
        val toRemoveIndices = mutableListOf<Int>()
        for (idx in matchingWoIndices) {
            if (remainingToTrim <= 1e-9) break
            val wo = consolidatedWOs[idx]
            val woQty = (wo["quantity"] as? Number)?.toDouble() ?: 0.0
            val take = min(woQty, remainingToTrim)
            val newQty = woQty - take
            remainingToTrim -= take
            totalQtyTrimmed += take
            if (newQty <= 1e-9) {
                toRemoveIndices.add(idx)
            } else {
                consolidatedWOs[idx] = wo.toMutableMap().apply {
                    put("quantity", roundQty(newQty))
                }
            }
        }
        // Remove zeroed lots in reverse order to preserve indices.
        for (idx in toRemoveIndices.sortedDescending()) consolidatedWOs.removeAt(idx)

        // Trim the untagged supply bucket at this component (FIFO across matching
        // buckets, though there should be at most one given v2's emission strategy).
        val supplyId = "consolidated_${pid}_${lid}"
        var supplyToRemove = overQty
        val matchingBuckets = inventory.filter {
            it["supply_id"]?.toString() == supplyId &&
            it["product_id"]?.toString() == pid &&
            it["location_id"]?.toString() == lid
        }
        for (b in matchingBuckets) {
            if (supplyToRemove <= 1e-9) break
            val avail = (b["qty"] as? Number)?.toDouble() ?: 0.0
            val take = min(avail, supplyToRemove)
            b["qty"] = avail - take
            supplyToRemove -= take
        }

        componentsTrimmed++
    }

    return Pair(componentsTrimmed, totalQtyTrimmed)
}

/**
 * v2 fixed-point iteration controller (Stages 1-4b).
 *
 * Drives convergence by minimizing **over-production** at merged leaves.
 * Over-production = `Σ_(d,c) max(0, initialBudgets[d][c] − consumed[d][c])`,
 * where `c` ranges over merged-leaf componentKeys and `d` ranges over demands;
 * `initialBudgets` is consolidation's per-demand allocation share at each leaf,
 * and `consumed` is what each demand actually drew from that share during
 * Phase 3. A positive residual at `(d, c)` means Phase 2 emitted synthetic
 * supply for demand `d` at leaf `c` that `d` never picked up — typically because
 * intermediate inventory above `c` satisfied `d`'s BOM walk before recursion
 * reached the leaf. See the "## Over-production" section below for the full
 * definition and contrast with what it is *not*.
 *
 * Runs phase 1 (resolution graph) once, builds merged groups once, then loops
 * phases 2+3 with progressively tighter member caps until production at the
 * merged leaf matches what the demand chain actually consumes:
 *
 *  1. Snapshot inventory.
 *  2. Apply per-demand caps from previous iter (no-op on iter 0).
 *  3. Phase 2: consolidate, emit one untagged supply per (pid, lid) at every
 *     produced component (merged leaf + its BOM children).
 *  4. Phase 3: commit each user demand against the post-phase-2 inventory.
 *  5. Measure over-production: any unspent budget at the merged leaf means
 *     the chain found inventory upstream and never recursed to the leaf —
 *     phase 2 over-sized the WO (and any BOM-child WOs cascaded from it).
 *  6. If zero over-production: converged, return.
 *     Else: revert inventory; new caps = `min(prevCap, this iter's consumption)`
 *     per (componentKey, demandId); continue.
 *
 * Why fixed-point and not single-pass: [reconcileOverProduction] (Stage 4a)
 * trims the merged-leaf WO but cannot resize WOs at BOM children of the merged
 * leaf — those were sized for the over-produced leaf qty. Iterating with
 * shrunken caps drives phase 2 to plan smaller leaf qty, which in turn shrinks
 * the BOM-child WOs naturally.
 *
 * ## Over-production (definition)
 *
 * Over-production is the **allocated-but-never-drawn** budget summed across
 * every (demand, merged-leaf-component) pair after Phase 3. It's a per-iter
 * accounting residual, not a physical excess.
 *
 *   initialBudgets[d][c]  -- qty consolidation allocated to demand d at leaf c
 *                            (set by Phase 2 from consResult.allocation)
 *   consumed[d][c]        -- qty d actually drew from c's synthetic supply
 *                            during Phase 3 (initialBudgets - remaining-budget)
 *   over[d][c]            -- max(0, initialBudgets[d][c] - consumed[d][c])
 *   totalOver             -- Σ_(d,c) over[d][c]   ← what runV2Iterated logs
 *
 * `c` is always a *merged leaf* (one entry per `(productId, locationId)`
 * group from `mergeGroups`), never a BOM intermediate. Phase 1 builds the
 * resolution graph **symbolically** (BOM rates × due-date arithmetic, no
 * inventory check). Phase 2 sizes consolidated WOs to satisfy that symbolic
 * need. Phase 3 commits each demand against actual inventory and picks one
 * alternative per alt_group; the leaf-level allocation goes unused when (a)
 * Phase 3 picks a different alt_group child than the path Phase 1 enumerated
 * for this leaf, or (b) post-Phase-2 synthetic supply at an intermediate level
 * (created by some other group's leaf production) satisfies the demand before
 * its BOM walk recurses to leaf `c`.
 *
 * If left uncorrected, `over[d][c] > 0` means the consolidated WO at leaf `c`
 * was sized for `d`'s share but produced units `d` never needed — i.e. qty
 * manufactured into the void. Driving `totalOver → 0` via the cap mechanism
 * shrinks both the merged-leaf WO and (transitively, via the leaf's internal
 * `plan()` call) every BOM-child WO cascaded from it.
 *
 * What over-production is **not**:
 *  - Not excess physical production from misconfigured WO sizing.
 *  - Not the planner exceeding demand quantity (Phase 3 is bounded by the row).
 *  - Not raw-material waste (tracked separately by inventory consumption).
 *  - Not measured at BOM children of merged leaves; children are corrected
 *    transitively via the cap mechanism, never measured directly. That's why
 *    [reconcileOverProduction]'s fallback log warns "sub-component WOs NOT
 *    cascade-trimmed" — it only fixes the leaf-level WO.
 *
 * ## Convergence design (why it terminates)
 *
 * The naïve cap rule `newCap = consumed` is **non-monotone**: it lets caps
 * rebound. When capping group A frees a shared raw material, group B can then
 * over-allocate against the freed supply, pushing some demand's "consumed"
 * (and therefore its next cap) back UP. We observed exactly this on case 162:
 * `302-0004|1000` oscillated 201k → 20k → 206k → 20k → 206k between iters,
 * never converging and exhausting [MAX_PLANNING_ITERATIONS].
 *
 * The fix has two parts that together guarantee termination:
 *
 *  (1) **Per-(component, demand) monotone clamp.** Each cap is updated as
 *      `newCap = min(prevCap, consumed)`. A cap can only decrease; once it
 *      shrinks it never grows back. Each cap forms a non-increasing sequence
 *      bounded below by 0, so by the monotone-decreasing-bounded-sequence
 *      theorem each cap converges to a limit.
 *
 *  (2) **Carry forward absent caps.** When a demand's cap drops to 0 it is
 *      excluded from the next iter's merged group, so it doesn't appear in
 *      that iter's `budgets` and the cap-update loop has no entry to write.
 *      Without (2), the next iter's `memberCaps` would be missing that demand,
 *      and [MergedGroup.withMemberCaps] would treat absent entries as
 *      "uncapped" (full member qty) — re-admitting the dropped demand. So
 *      `newCaps` is seeded from `prevCaps` verbatim before applying current
 *      consumption updates: a cap=0 dropout stays a cap=0 dropout.
 *
 * Aggregate (per-component) over-production can still fluctuate iter-to-iter
 * — different demands hit their caps in different iters and free supply that
 * other demands then over-allocate against. Only the per-(component, demand)
 * caps are guaranteed monotone. But because each cap is non-increasing and
 * bounded by 0, the system as a whole converges; aggregate-level oscillation
 * decays as more demand-level caps reach their fixed points.
 *
 * Empirical: case 162 (13 impacted demands, diamond BOMs sharing raw materials)
 * converges in 8 iters with this rule. Pre-fix it never converged in 5.
 *
 * If the loop still exhausts [MAX_PLANNING_ITERATIONS], fall back to a
 * single-pass [reconcileOverProduction] and log a warning.
 */
private fun runV2Iterated(
    demands: List<Map<String, Any?>>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    consolidationConfig: ConsolidationConfig,
    overrideIndex: Map<String, Map<String, Any?>>,
    progressCallback: ((Map<String, Any?>) -> Unit)?,
): V2IteratedResult {
    // Snapshot inventory once before any iteration mutates it. Each iter's
    // phase 2+3 consumes from / adds to inventory; we revert to this snapshot
    // between iters so caps drive convergence rather than compounding state.
    val initialInventory: List<Map<String, Any?>> = inventory.map { it.toMap() }

    val graph    = buildResolutionGraph(demands, data)
    val ancestry = BomAncestry(data["bom"] ?: emptyList())
    val baseMerged = mergeGroups(graph, ancestry, consolidationConfig.periodDays)

    var memberCaps: Map<Pair<String, String>, Map<Any?, Double>> = emptyMap()
    var lastConsolidatedWOs: List<Map<String, Any?>> = emptyList()
    var lastConsolidatedPegging: List<Map<String, Any?>> = emptyList()
    var lastCommit = LegacyCommitResult(emptyList(), emptyList(), emptyList())
    // Shared feasibility cache across consolidation + legacyCommit. Stable across
    // iters because it depends only on `data` and `purchase_allowed`, neither of
    // which change here. Memoizes maxMakeDepth(pid, lid) lookups; without it the
    // reactive-fallback site at plan() can't admit make alternatives (cache=null
    // short-circuits makeAlternatives to empty).
    val feasibilityCache: MutableMap<Pair<String, String>, Int> = mutableMapOf()
    // Structural-failure memo for makes — see plan()'s structuralFailedMakes
    // doc. Co-scoped with feasibilityCache. Value is the cached blocked reason
    // so the admission-skip site can emit a diagnostic stub pegging node.
    val structuralFailedMakes: MutableMap<Pair<String, String>, String> = mutableMapOf()

    // Iter-0 snapshot of consolidation-engine per-(demand, leaf) budget caps.
    // The compensate-equivalent loop below refines `memberCaps` across iters,
    // converging all of a demand's leaves to the AND-feasible production point —
    // which smears the bottleneck identity (every leaf reports the same final
    // cap). The iter-0 snapshot preserves the *origin*: the leaf with the
    // smallest cap-to-need ratio at iter-0 is the genuine root constraint.
    // Threaded into legacyCommit so the per-demand bottleneck-identification
    // logic can flag it with `is_root_bottleneck`, distinct from the post-
    // convergence `is_bottleneck` flag on smearing-aligned siblings.
    var iter0AllocationSnapshot: Map<Any?, Map<String, Double>>? = null

    for (iter in 0 until MAX_PLANNING_ITERATIONS) {
        // Revert inventory to snapshot at the start of every iteration (incl. iter 0,
        // for symmetry — the snapshot equals current state on iter 0 so it is a no-op).
        inventory.clear()
        for (b in initialInventory) inventory.add(b.toMutableMap())

        // Apply caps; drop fully-trimmed groups so consolidation doesn't see a zero-qty group.
        val merged = if (memberCaps.isEmpty()) baseMerged else baseMerged.mapNotNull { g ->
            val groupCaps = memberCaps[Pair(g.leafPid, g.leafLid)]
            val capped = if (groupCaps == null) g else g.withMemberCaps(groupCaps)
            if (capped.members.isEmpty() || capped.totalQty <= 1e-9) null else capped
        }

        val consGroups = merged.map { it.toConsolidationGroup() }
        val consResult = runConsolidation(
            consGroups, inventory, data, consolidationConfig, planConfig = config,
        ) { dem, inv, dat, reqDt, depth, path, cfg, prefId ->
            plan(dem, inv, dat, reqDt, depth, path, cfg, prefId,
                overrideIndex = overrideIndex,
                feasibilityCache = feasibilityCache,
                structuralFailedMakes = structuralFailedMakes)
        }

        // Emit one untagged supply per (pid, lid) for every produced component.
        // Allocation is keyed only at the merged-leaf level — BOM-child WOs
        // produced inside consolidation feed back through real inventory at
        // those (pid, lid)s, but we don't add explicit synthetic supplies for
        // them: they are consumed by the merged-leaf's own internal plan() call
        // and the resulting qty rolls up into the merged-leaf allocation.
        val producedByComponent = mutableMapOf<String, Double>()
        for ((_, componentAllocs) in consResult.allocation) {
            for ((componentKey, qty) in componentAllocs) {
                if (qty <= 1e-12) continue
                producedByComponent[componentKey] = (producedByComponent[componentKey] ?: 0.0) + qty
            }
        }
        for ((componentKey, totalQty) in producedByComponent) {
            if (totalQty <= 1e-12) continue
            val parts = componentKey.split("|", limit = 2)
            val pid = parts.getOrElse(0) { "" }
            val lid = parts.getOrElse(1) { "" }
            val supplyDate = consResult.consolidatedWOs
                .firstOrNull { it["product_id"] == pid && it["location_id"] == lid }
                ?.get("end_time") as? String
            inventory.add(mutableMapOf(
                "product_id"  to pid,
                "location_id" to lid,
                "qty"         to totalQty,
                "supply_date" to supplyDate,
                "supply_id"   to "consolidated_${pid}_${lid}",
            ))
        }

        // Snapshot initial budgets before phase 3 mutates them.
        val initialBudgets: Map<Any?, Map<String, Double>> = consResult.allocation
            .mapValues { (_, allocs) -> allocs.toMap() }
        // Capture iter-0 snapshot once, before any cap-refinement smearing.
        // See `iter0AllocationSnapshot` declaration for rationale.
        if (iter == 0) {
            iter0AllocationSnapshot = consResult.allocation
                .mapValues { (_, allocs) -> allocs.toMap() }
        }
        val budgets: Map<Any?, MutableMap<String, Double>> = consResult.allocation.mapValues { (_, allocs) ->
            allocs.mapValues { (_, q) -> q }.toMutableMap()
        }

        // Phase 3 — forward progressCallback live, tagged with iteration metadata
        // so the UI can show "iter k/N" alongside per-demand progress. The bar
        // restarts each iter (0→N) which is the honest signal that planning is
        // iterating; convergence in iter 0 shows a single smooth 0→100%.
        val isLastIter = iter == MAX_PLANNING_ITERATIONS - 1
        val iterCb: ((Map<String, Any?>) -> Unit)? = progressCallback?.let { cb ->
            { payload ->
                cb(payload + mapOf(
                    "iteration" to iter + 1,
                    "iterations_max" to MAX_PLANNING_ITERATIONS,
                ))
            }
        }
        val commit = legacyCommit(
            demands, inventory, data, config, overrideIndex,
            useTaggedLookup = false,
            progressCallback = iterCb,
            budgets = budgets,
            sharedFeasibilityCache = feasibilityCache,
            sharedStructuralFailedMakes = structuralFailedMakes,
            iter0Allocation = iter0AllocationSnapshot,
        )

        // Over-production: any leftover budget at the merged leaf.
        val totalOver = budgets.values.sumOf { db ->
            db.values.sumOf { q -> if (q > 1e-9) q else 0.0 }
        }

        lastConsolidatedWOs = consResult.consolidatedWOs
        lastConsolidatedPegging = consResult.consolidatedPegging
        lastCommit = commit

        if (totalOver <= 1e-9) {
            log.info("v2 iter {}: converged (no over-production)", iter + 1)
            return V2IteratedResult(consResult.consolidatedWOs, consResult.consolidatedPegging, commit, iter + 1, true)
        }

        if (isLastIter) {
            // Fixed-point not reached. Apply 4a single-pass trim as a safety net
            // — only fixes the merged leaf; sub-component WOs may stay over-sized.
            val finalWOs = consResult.consolidatedWOs.toMutableList()
            val (n, q) = reconcileOverProduction(finalWOs, inventory, budgets)
            log.warn(
                "v2 iter {} (max): {} qty over-production residual after fixed-point — " +
                "fallback trimmed {} component(s), {} qty (sub-component WOs NOT cascade-trimmed)",
                iter + 1, "%.2f".format(totalOver), n, "%.2f".format(q),
            )
            return V2IteratedResult(finalWOs.toList(), consResult.consolidatedPegging, commit, iter + 1, false)
        }

        // Compute next iter's caps from this iter's actual consumption. A demand's
        // cap at (pid, lid) becomes "what it actually drew from the merged leaf
        // budget this iter" — typically less than its allocation when intermediate
        // inventory satisfied the chain before recursion reached the leaf.
        //
        // MONOTONE: clamp to min(prevCap, consumed). Without this, caps can rebound
        // upward when capping group A frees shared raw-material supply that group B
        // then over-allocates against — producing the bistable 28k → 243k → 34k →
        // 246k oscillation we observed on case 162. Once a cap drops, it stays.
        //
        // Carry forward prevCaps verbatim before applying this iter's updates: a demand
        // dropped to cap=0 in a prior iter is excluded from this iter's merged group,
        // so it doesn't appear in `budgets` and the loop below would otherwise leave it
        // unwritten — which `withMemberCaps` treats as "no cap" and re-admits the
        // demand at full qty next iter. Seeding from prevCaps preserves the drop.
        val prevCaps = memberCaps
        val newCaps = mutableMapOf<Pair<String, String>, MutableMap<Any?, Double>>()
        for ((key, demandCaps) in prevCaps) {
            newCaps[key] = demandCaps.toMutableMap()
        }
        // Per-component aggregation for diagnostic top-offender logging.
        val overByComponent = mutableMapOf<String, Double>()
        for ((demandId, demandBudget) in budgets) {
            val initial = initialBudgets[demandId] ?: continue
            for ((componentKey, remaining) in demandBudget) {
                val initQty = initial[componentKey] ?: continue
                val consumed = (initQty - remaining).coerceAtLeast(0.0)
                val parts = componentKey.split("|", limit = 2)
                val pid = parts.getOrElse(0) { "" }
                val lid = parts.getOrElse(1) { "" }
                val key = Pair(pid, lid)
                val prevCap = prevCaps[key]?.get(demandId) ?: Double.POSITIVE_INFINITY
                newCaps.getOrPut(key) { mutableMapOf() }[demandId] = min(prevCap, consumed)
                if (remaining > 1e-9) {
                    overByComponent.merge(componentKey, remaining, Double::plus)
                }
            }
        }
        memberCaps = newCaps
        val top = overByComponent.entries.sortedByDescending { it.value }.take(5)
            .joinToString(", ") { (k, v) -> "$k=${"%.0f".format(v)}" }
        log.info(
            "v2 iter {}: over-production {} qty across {} component(s) (top: {}), capping members for next iter",
            iter + 1, "%.2f".format(totalOver), overByComponent.size, top,
        )
    }
    // Unreachable: the loop always returns from inside.
    return V2IteratedResult(lastConsolidatedWOs, lastConsolidatedPegging, lastCommit, MAX_PLANNING_ITERATIONS, false)
}

/**
 * Phase 3 (legacy): plan each user demand against the inventory left by
 * phase 2 (real supplies + tagged synthetic buckets). Emits committed rows,
 * work orders, and per-demand pegging trees.
 */
private fun legacyCommit(
    demands: List<Map<String, Any?>>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    overrideIndex: Map<String, Map<String, Any?>>,
    useTaggedLookup: Boolean,
    progressCallback: ((Map<String, Any?>) -> Unit)?,
    /**
     * Optional per-demand budget snapshots, keyed by `demand_id`. Each map is keyed by
     * `"$pid|$lid"` and caps consumption at the merged-leaf component during plan().
     * Used by Stage-3 v2 consolidation to enforce per-demand allocation shares without
     * polluting [inventory] with tagged synthetic buckets. Caller-owned; budgets are
     * consumed in-place during planning.
     */
    budgets: Map<Any?, MutableMap<String, Double>>? = null,
    /**
     * Optional shared feasibility cache. When passed in (e.g. from runV2Iterated),
     * memoization persists across the consolidation + commit phases of one iteration.
     * If null, an empty cache is created and discarded at the end of this call.
     */
    sharedFeasibilityCache: MutableMap<Pair<String, String>, Int>? = null,
    /** Optional shared structural-failure memo, see plan()'s docs. */
    sharedStructuralFailedMakes: MutableMap<Pair<String, String>, String>? = null,
    /**
     * Optional iter-0 snapshot of the consolidation engine's per-(demand, leaf)
     * allocation, captured BEFORE the cap-refinement loop in runV2Iterated
     * smears them. Used by plan()/planMethodSlot() to identify the *origin*
     * leaf (smallest cap-to-need ratio) and tag its pegging with
     * `is_root_bottleneck=true`, distinct from the post-convergence
     * `is_bottleneck` flag on smearing-aligned siblings.
     */
    iter0Allocation: Map<Any?, Map<String, Double>>? = null,
): LegacyCommitResult {
    val committedDemands = mutableListOf<Map<String, Any?>>()
    val workOrders = mutableListOf<Map<String, Any?>>()
    val planningPegging = mutableListOf<Map<String, Any?>>()
    val total = demands.size
    // One feasibility cache shared across all demands in this commit pass.
    // Lazily populated on first query at the reactive-fallback site;
    // unaffected demands incur no cost. Stable across the loop because
    // `data` and `purchase_allowed` don't change mid-commit.
    val feasibilityCache: MutableMap<Pair<String, String>, Int> = sharedFeasibilityCache ?: mutableMapOf()
    val structuralFailedMakes: MutableMap<Pair<String, String>, String> = sharedStructuralFailedMakes ?: mutableMapOf()
    demands.forEachIndexed { i, d ->
        val reqStr = d["request_due_time"] as? String ?: d["request_time"] as? String
        val reqDt = parseDate(reqStr)
        val demandId = d["demand_id"]
        val prefId = if (useTaggedLookup) demandId else null
        val demandBudget = budgets?.get(demandId)
        // Diagnostic: dump the budget map handed to this demand's plan walk.
        // Tells us whether per-(demand, leaf) caps from consolidation are
        // present (case 3 from the 246-mystery analysis) or only the
        // merged-leaf entry (cases 1/2 — leftover-state-driven). Filter to
        // the demand of interest to keep log volume bounded.
        if (demandId?.toString() == "10041744_10" && demandBudget != null) {
            val entries = demandBudget.entries.sortedBy { it.key }
                .joinToString(", ") { (k, v) -> "$k=${"%.1f".format(v)}" }
            log.info("[BUDGET] did={} entryCount={} entries={}",
                demandId, demandBudget.size, entries)
        }
        val (solvedList, wos, peggingNode) = plan(
            d, inventory, data, reqDt,
            config = config, preferDemandId = prefId, overrideIndex = overrideIndex,
            budget = demandBudget,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
            initialBudget = iter0Allocation?.get(demandId),
        )
        committedDemands.addAll(solvedList)
        workOrders.addAll(wos)
        if (peggingNode != null && demandId != null) {
            planningPegging.add(mapOf("demand_id" to demandId, "tree" to peggingNode))
        }
        progressCallback?.invoke(mapOf("current" to i + 1, "total" to total, "demand_id" to demandId))
    }
    return LegacyCommitResult(committedDemands, workOrders, planningPegging)
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

    // ── Phase 1 (resolve) + Phase 2 (consolidate) ────────────────────────────
    val consolidationConfig = parseConsolidationConfig(config)
    val consolidatedWOs = mutableListOf<Map<String, Any?>>()
    val consolidatedPegging = mutableListOf<Map<String, Any?>>()

    // Consolidation enabled → fixed-point iteration (phases 2+3+4 fused).
    // Disabled → run phase 3 directly against real inventory; useTaggedLookup
    // is needed only for supply-split overrides (real buckets split per demand).
    val commitResult: LegacyCommitResult
    // Empty list — retained for shape compatibility with historical consumers
    // that still read `supply_level_allocations` from enriched plan results.
    // The supply-level orchestrator (consolidation.scope="all") was retired
    // in 2026-05; only the leaf-level fixed-point pipeline remains.
    val supplyLevelAllocations: List<Map<String, Any?>> = emptyList()
    if (consolidationConfig.enabled) {
        val iterated = runV2Iterated(
            demands, inventory, data, config, consolidationConfig, overrideIndex, progressCallback,
        )
        consolidatedWOs.addAll(iterated.consolidatedWOs)
        consolidatedPegging.addAll(iterated.consolidatedPegging)
        commitResult = iterated.commitResult
    } else {
        commitResult = legacyCommit(
            demands, inventory, data, config, overrideIndex,
            useTaggedLookup = supplyCapMap.isNotEmpty(),
            progressCallback = progressCallback,
        )
    }
    committedDemands.addAll(commitResult.committedDemands)
    workOrders.addAll(commitResult.workOrders)
    planningPegging.addAll(commitResult.planningPegging)

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

    val timingFix = fixTimingFromPegging(consolidatedWOs + workOrders, allPegging, data)
    return mapOf(
        "committed_demands"      to committedDemands,
        "work_orders"            to timingFix.workOrders,
        "planning_pegging"       to timingFix.peggingTrees,
        "supply_allocations"     to supplyAllocations,
        "supply_cap_violations"  to supplyCapViolations,
        "override_warnings"      to overrideWarnings,
        "supply_level_allocations" to supplyLevelAllocations,
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

/**
 * Post-processing pass: enforce
 *   start_time(parent WO) >= max(end_time(direct-child WOs))
 * across the assembled work-order list.
 *
 * The forward planning pass schedules parents from the demand's requested
 * due-date (backward scheduling), which can place a parent WO earlier than
 * its children actually finish — especially in multi-slot waterfall and
 * consolidation paths.  This pass corrects that by reading the real
 * end_times back.
 *
 * Algorithm — leaf-driven push (not tree walk):
 *   1. Walk all pegging trees once to build the parent → children DAG over
 *      WO nodes, keyed by (product_id, location_id, original_start_time).
 *      A "child" relationship means: "parent WO has a demand child whose
 *      child is this WO" (i.e., direct WO predecessor in the BOM).
 *   2. Find leaves: WOs that are not anyone's parent in the DAG.
 *   3. For each leaf, push its end_time up to every ancestor.  When a
 *      parent shifts, the new end_time propagates further up immediately.
 *
 * A tree-walk variant (recurse from each demand root, fix the WO at each
 * node) is incorrect when a WO is shared across multiple demand trees:
 * the first tree's pass shifts it, but the parents of that WO sitting in
 * a separate demand tree never get re-checked unless we walk THEIR tree
 * later — and even then, the lookup by (pid, lid, original_start) breaks
 * once start_time has been mutated.  Leaf-driven push reaches every
 * ancestor regardless of which tree it sits in.
 */
private fun fixTimingFromPegging(
    workOrders: List<Map<String, Any?>>,
    peggingTrees: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): TimingFixResult {
    if (peggingTrees.isEmpty()) return TimingFixResult(workOrders, peggingTrees)

    // ── Step 1: Regenerate work-orders fresh from the pegging trees ───────────
    //
    // The planner emits both pegging trees and work_orders, but historically
    // these can drift out of lockstep: the AND-bottleneck blocked branch keeps
    // first-pass child sub-trees as diagnostic stubs while dropping their
    // wos; reconcileOverProduction trims lots without trimming nodes;
    // prunePhantomLoops trims nodes without trimming lots; etc.  Rather than
    // patch every mutation point, we treat the pegging trees as the canonical
    // record and rebuild the work_orders list from them.  After this step,
    // every pegging-tree WO node has a corresponding lot in the list, and
    // every lot has a matching tree node, by construction.
    //
    // OR-group treatment (Variant A): when a demand has > 1 non-failed WO
    // children (waterfall split), all alternatives share a single
    // wo_group_id minted at the OR-group level; alternatives are
    // distinguished within the group by `method_slot_index` (0, 1, …).
    // Sequencing then keys on wo_group_id directly (no canonOf side-map),
    // and within a shared group the per-alt shift logic uses
    // method_slot_index to keep each alternative on its own predecessor
    // alignment.
    //
    // Consolidation metadata (`consolidated`, `consolidation_split_*`)
    // isn't stored on tree nodes — we copy it onto regenerated lots from
    // the original pre-regen list, matched by the tree node's
    // pre-restamp wo_group_id.

    // Pre-build a lookup from the original WO list keyed by planning gid,
    // so we can copy per-WO consolidation metadata onto the regenerated lots.
    val originalLotByPlanningGid = mutableMapOf<String, Map<String, Any?>>()
    for (wo in workOrders) {
        val gid = wo["wo_group_id"] as? String ?: continue
        if (gid !in originalLotByPlanningGid) originalLotByPlanningGid[gid] = wo
    }
    val consolidationFieldKeys = listOf(
        "consolidated", "consolidation_split_mode", "consolidation_total_planned",
        "consolidation_split_details", "consolidation_override_active",
    )

    val regenLots = mutableListOf<MutableMap<String, Any?>>()
    // Dedup across the whole walk: each physical WO's planning gid (the gid
    // minted by buildWorkOrders) is emitted at most once.  Without this,
    // the same WO is emitted N times when its pegging node is reachable
    // from N positions in the trees — `cr.pegging` returned by recursive
    // plan() calls is shared by reference across waterfall slots and
    // taggedChildPeggings under blocked AND-bottleneck nodes, so a single
    // physical WO can have many tree-positions.
    val emittedPlanningGids = mutableSetOf<String>()
    var dedupSkipped = 0

    fun emitLotsForWo(
        woNode: Map<String, Any?>,
        finalGid: String,
        methodSlotIndex: Int?,
        ownerDemandId: Any?,
    ) {
        val planningGid = woNode["wo_group_id"] as? String
        if (planningGid != null) {
            if (!emittedPlanningGids.add(planningGid)) {
                dedupSkipped++
                return
            }
        }
        val pid = woNode["product_id"] as? String ?: return
        val lid = woNode["location_id"] as? String ?: return
        val method = woNode["method"] as? String ?: return
        val totalQty = (woNode["quantity"] as? Number)?.toDouble() ?: return
        if (totalQty <= 1e-9) return
        val startStr = woNode["start_time"] as? String ?: return
        val endStr = woNode["end_time"] as? String ?: return
        val startDt = parseDate(startStr) ?: return
        val endDt = parseDate(endStr) ?: return
        val lotCount = (woNode["lot_count"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val maxLotSize = (woNode["max_lot_size"] as? Number)?.toDouble()
            ?: maxLotSize(pid, lid, data) ?: totalQty
        val lotSize = max(1e-9, maxLotSize)
        val locationSource = woNode["location_source"] as? String
        val overrideActive = woNode["override_active"] as? Boolean ?: false
        val prodArea = getProdArea(pid, lid, data)
        val originalLot = planningGid?.let { originalLotByPlanningGid[it] }

        // Per-lot duration: derived from the WO's overall span and lotCount.
        // Matches buildWorkOrders's lot scheduling (each lot occupies
        // leadDays, sequential).
        val totalSpanDays = endDt.toEpochDay() - startDt.toEpochDay()
        val perLotDays = if (lotCount > 0) totalSpanDays / lotCount else 0L

        var lotStart: LocalDate = startDt
        var remaining = totalQty
        repeat(lotCount) {
            if (remaining <= 1e-9) return@repeat
            val lotQty = min(lotSize, remaining)
            val lotEnd = lotStart.plusDays(perLotDays)
            val lot = mutableMapOf<String, Any?>(
                "product_id" to pid,
                "location_id" to lid,
                "quantity" to roundQty(lotQty),
                "start_time" to formatDate(lotStart),
                "end_time" to formatDate(lotEnd),
                "method" to method,
                "location_source" to locationSource,
                "demand_id" to ownerDemandId,
                "prod_area" to prodArea,
                "override_active" to overrideActive,
                "wo_group_id" to finalGid,
            )
            if (methodSlotIndex != null) lot["method_slot_index"] = methodSlotIndex
            // Copy consolidation metadata from the original lot if present.
            if (originalLot != null) {
                for (key in consolidationFieldKeys) {
                    if (originalLot.containsKey(key)) lot[key] = originalLot[key]
                }
            }
            regenLots.add(lot)
            remaining -= lotQty
            lotStart = lotEnd
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun regenWalk(
        node: Map<String, Any?>,
        ownerDemandId: Any?,
        depth: Int,
    ): Map<String, Any?> {
        if (depth > 60) return node
        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        val type = node["type"] as? String

        if (type == "demand") {
            // Detect OR-group: > 1 non-failed WO children directly under this demand.
            val nonFailedWoChildren = children.withIndex().filter { (_, ch) ->
                ch["type"] == "work_order" && ch["failed"] != true
            }
            val isOrGroup = nonFailedWoChildren.size > 1
            val orGroupId = if (isOrGroup) nextWoGroupId() else null
            val altIndexByChild = if (isOrGroup) {
                nonFailedWoChildren.withIndex()
                    .associate { (altIndex, idxAndCh) -> idxAndCh.index to altIndex }
            } else emptyMap()

            val newChildren = children.mapIndexed { i, ch ->
                if (ch["type"] == "work_order" && ch["failed"] != true) {
                    val planningGid = ch["wo_group_id"] as? String
                    val finalGid = orGroupId ?: planningGid
                    val altIndex = altIndexByChild[i]
                    if (finalGid != null) {
                        emitLotsForWo(ch, finalGid, altIndex, ownerDemandId)
                    }
                    // Restamp the WO node's wo_group_id (and add slot index
                    // for OR-alts) so the tree and lots agree on the gid.
                    val restamped = ch.toMutableMap()
                    if (finalGid != null) restamped["wo_group_id"] = finalGid
                    if (altIndex != null) restamped["method_slot_index"] = altIndex
                    val grandchildren = (ch["children"] as? List<Map<String, Any?>>) ?: emptyList()
                    if (grandchildren.isNotEmpty()) {
                        restamped["children"] = grandchildren.map { regenWalk(it, ownerDemandId, depth + 1) }
                    }
                    restamped
                } else {
                    regenWalk(ch, ownerDemandId, depth + 1)
                }
            }
            return node.toMutableMap().apply { put("children", newChildren) }
        }

        // Other types: pass through, recurse into children.
        if (children.isEmpty()) return node
        val newChildren = children.map { regenWalk(it, ownerDemandId, depth + 1) }
        return node.toMutableMap().apply { put("children", newChildren) }
    }

    val regenTrees = peggingTrees.map { entry ->
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        val ownerDemandId = entry["demand_id"]
        val rebuilt = regenWalk(tree, ownerDemandId, 0)
        entry.toMutableMap().apply { put("tree", rebuilt) }
    }
    log.info("fixTimingFromPegging.regen: input_lots={} regen_lots={} pegging_trees={} dedup_skipped={}",
        workOrders.size, regenLots.size, peggingTrees.size, dedupSkipped)

    if (regenLots.isEmpty()) {
        // Nothing to sequence; just return regen output (likely an empty plan).
        return TimingFixResult(regenLots, regenTrees)
    }

    // ── Step 2: Sequencing on the regenerated lots ────────────────────────────
    //
    // Key on wo_group_id directly (alternatives in an OR-group already share
    // the same gid post-regen, distinguished by method_slot_index).  Per-alt
    // shifting partitions within a bucket using method_slot_index.

    // Index lots by wo_group_id (post-regen).
    val lotsByGroup = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
    for (wo in regenLots) {
        val gid = wo["wo_group_id"] as? String ?: continue
        lotsByGroup.getOrPut(gid) { mutableListOf() }.add(wo)
    }

    fun tailEnd(gid: String): LocalDate? =
        lotsByGroup[gid]?.mapNotNull { parseDate(it["end_time"] as? String) }?.maxOrNull()

    // Build the parent → children DAG by walking the (regen-stamped) trees.
    val parentsOf = mutableMapOf<String, MutableSet<String>>()
    val hasChild = mutableSetOf<String>()
    val allGroups = mutableSetOf<String>()

    @Suppress("UNCHECKED_CAST")
    fun walk(node: Map<String, Any?>, currentParent: String?, depth: Int) {
        if (depth > 60) return
        val nodeChildren = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        when (node["type"] as? String) {
            "work_order" -> {
                if (node["failed"] == true) return
                val gid = node["wo_group_id"] as? String ?: return
                allGroups.add(gid)
                if (currentParent != null && currentParent != gid) {
                    parentsOf.getOrPut(gid) { mutableSetOf() }.add(currentParent)
                    hasChild.add(currentParent)
                }
                for (c in nodeChildren) walk(c, currentParent = gid, depth + 1)
            }
            else -> {
                for (c in nodeChildren) walk(c, currentParent, depth + 1)
            }
        }
    }
    for (entry in regenTrees) {
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, currentParent = null, depth = 0)
    }
    val leaves = allGroups.filter { it !in hasChild }

    // Push child end_time up to every parent.  Within the parent's bucket,
    // partition by method_slot_index so each alternative shifts on its own
    // predecessor alignment (the shared gid lets the bucket see all child
    // pushes; method_slot_index keeps the per-alt chain intact).
    var shiftCount = 0
    fun pushUp(childGid: String, depth: Int) {
        if (depth > 200) return
        val childEnd = tailEnd(childGid) ?: return
        for (parentGid in parentsOf[childGid] ?: emptySet()) {
            val parentLots = lotsByGroup[parentGid] ?: continue
            // null method_slot_index → non-OR (single alt).  All non-OR lots
            // collapse into one alt-bucket; OR alts split into per-index
            // buckets.
            val byAlt = parentLots.groupBy { it["method_slot_index"] as? Int }
            var anyShift = false
            for ((altIndex, altLots) in byAlt) {
                val altHead = altLots.mapNotNull { parseDate(it["start_time"] as? String) }.minOrNull() ?: continue
                if (childEnd <= altHead) continue
                val shiftDays = childEnd.toEpochDay() - altHead.toEpochDay()
                for (lot in altLots) {
                    parseDate(lot["start_time"] as? String)?.let { lot["start_time"] = formatDate(it.plusDays(shiftDays)) }
                    parseDate(lot["end_time"] as? String)?.let { lot["end_time"] = formatDate(it.plusDays(shiftDays)) }
                }
                anyShift = true
                shiftCount++
                if (log.isDebugEnabled) {
                    val first = altLots.first()
                    log.debug("fixTiming: {}@{} group={} alt={} shifted +{} days (alt_head: {} → {}, lots={}) driven by child group={} end={}",
                        first["product_id"], first["location_id"], parentGid, altIndex, shiftDays,
                        formatDate(altHead), formatDate(altHead.plusDays(shiftDays)),
                        altLots.size, childGid, formatDate(childEnd))
                }
            }
            if (anyShift) pushUp(parentGid, depth + 1)
        }
    }
    // Iterate every group (not just leaves): an intermediate group's TAIL is
    // a binding constraint on its parents even when the group itself didn't
    // shift this iteration.
    for (gid in allGroups) pushUp(gid, depth = 0)

    log.info("fixTimingFromPegging: lot_groups={} pegging_trees={} dag_groups={} leaves={} shifts_applied={}",
        lotsByGroup.size, regenTrees.size, allGroups.size, leaves.size, shiftCount)

    // ── Step 3: Write corrected timings back into the pegging trees ───────────
    @Suppress("UNCHECKED_CAST")
    fun rewriteTree(node: Map<String, Any?>, depth: Int): Map<String, Any?> {
        if (depth > 60) return node
        val originalChildren = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        val newChildren = originalChildren.map { rewriteTree(it, depth + 1) }
        val updated = node.toMutableMap()
        if (originalChildren.isNotEmpty()) updated["children"] = newChildren
        when (node["type"] as? String) {
            "work_order" -> {
                if (node["failed"] != true) {
                    val gid = node["wo_group_id"] as? String
                    val altIndex = node["method_slot_index"] as? Int
                    if (gid != null) {
                        val ownLots = lotsByGroup[gid]?.filter {
                            (it["method_slot_index"] as? Int) == altIndex
                        } ?: emptyList()
                        if (ownLots.isNotEmpty()) {
                            ownLots.mapNotNull { parseDate(it["start_time"] as? String) }.minOrNull()
                                ?.let { updated["start_time"] = formatDate(it) }
                            ownLots.mapNotNull { parseDate(it["end_time"] as? String) }.maxOrNull()
                                ?.let { updated["end_time"] = formatDate(it) }
                        }
                    }
                }
            }
            "demand" -> {
                val newCommit = newChildren.mapNotNull { ch ->
                    val r = ch["commit_reason"] as? String
                    if (r == "cycle_stopped" || r == "cycle_detected") return@mapNotNull null
                    when (ch["type"] as? String) {
                        "work_order" -> parseDate(ch["end_time"] as? String)
                        "demand", "supply", "purchase" -> parseDate(ch["commit_time"] as? String)
                        else -> null
                    }
                }.maxOrNull()
                val current = parseDate(node["commit_time"] as? String)
                if (newCommit != null && (current == null || newCommit > current)) {
                    updated["commit_time"] = formatDate(newCommit)
                }
            }
        }
        return updated
    }
    val finalTrees = regenTrees.map { entry ->
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        entry.toMutableMap().apply { put("tree", rewriteTree(tree, 0)) }
    }

    return TimingFixResult(regenLots, finalTrees)
}

/** Result of [fixTimingFromPegging]: the corrected work-order list AND the
 *  pegging trees rewritten with the same canonical timings, so all downstream
 *  consumers (soundness, UI, KPIs) read a consistent view. */
private data class TimingFixResult(
    val workOrders: List<Map<String, Any?>>,
    val peggingTrees: List<Map<String, Any?>>,
)
