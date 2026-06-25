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
     * Optional hard cap on total qty consumed at this (productId, locationId).
     * Takes precedence over [perLotBudget] for the overall limit.
     * `null` means unlimited (legacy behavior).
     */
    budgetCap: Double? = null,
    /**
     * Optional per-lot budget caps: supplyId → remaining qty allowed for this demand.
     * When set, each lot is additionally capped by its own entry. The map is mutated
     * in-place to deduct consumed amounts so the caller can track remaining budgets.
     * Lots whose supplyId is absent from the map are uncapped at the lot level.
     */
    perLotBudget: MutableMap<String, Double>? = null,
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
            val sid = b["supply_id"]?.toString()
            // perLotBudget acts as a whitelist: a lot absent from the map gets cap 0.0 (not unlimited).
            val lotCap = if (perLotBudget != null && sid != null) (perLotBudget[sid] ?: 0.0) else null
            val take = min(avail, if (lotCap != null) min(remaining, lotCap) else remaining)
            if (take <= 0) continue
            b["qty"] = avail - take
            remaining -= take
            if (perLotBudget != null && sid != null && lotCap != null) {
                perLotBudget[sid] = (lotCap - take).coerceAtLeast(0.0)
            }
            val sd = b["supply_date"] as? String
            val commitTime = if (sd != null) formatDate(parseDate(sd)) else null
            consumed.add(ConsumedBucket(
                supplyId = sid,
                qty = take,
                commitTime = commitTime,
            ))
        }
    }

    // Fast path: use (pid,lid) index when available (IndexedInventory), avoiding O(N) scan.
    val candidateBuckets: List<MutableMap<String, Any?>> =
        (inventory as? IndexedInventory)?.idx?.get(Pair(pid, lid))
            ?: inventory.filter { b -> b["product_id"]?.toString()?.trim() == pid && b["location_id"]?.toString()?.trim() == lid }

    if (preferDemandId != null) {
        // Pass 1: tagged buckets for this demand only
        val tagged = candidateBuckets
            .filter { (it["qty"] as? Number)?.toDouble() ?: 0.0 > 0 && it["demand_tag"] == preferDemandId }
            .sortedWith(sorter)
        consumeFrom(tagged)
        // Pass 2: untagged buckets (no demand_tag key, or demand_tag == null)
        if (remaining > 0) {
            val untagged = candidateBuckets
                .filter { (it["qty"] as? Number)?.toDouble() ?: 0.0 > 0 && (!it.containsKey("demand_tag") || it["demand_tag"] == null) }
                .sortedWith(sorter)
            consumeFrom(untagged)
        }
    } else {
        // Original behavior: single pass over all matching buckets
        val buckets = candidateBuckets
            .filter { (it["qty"] as? Number)?.toDouble() ?: 0.0 > 0 }
            .sortedWith(sorter)
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

// ── Hot-path indexes: avoid O(N) list scans on every plan() call ──────────────
//
// With 4738 supply rows, 7450 method_make rows, 3622 method_move rows, and
// 14592 BOM rows, linear scans in consumeFromInventory / getMethods /
// variantsForMake dominate runtime on large plans (150+ min for 208 demands).
// Indexes are built once in legacyCommit and threaded via JVM subtyping —
// no function signature changes needed: callers detect the wrappers via `as?`.

/** Method + BOM lookup index built once per legacyCommit call. */
internal data class DataIndex(
    val makeByPidLid: Map<Pair<String, String>, List<Map<String, Any?>>>,
    val moveByPidToLid: Map<Pair<String, String>, List<Map<String, Any?>>>,
    val buyByPidLid: Map<Pair<String, String>, List<Map<String, Any?>>>,
    /** BOM rows keyed by bom_id; secondary filter by parent_id applied at use site. */
    val bomByBomId: Map<String, List<Map<String, Any?>>>,
    /** BOM rows keyed by parent_id for the fallback (no bom_id match) case. */
    val bomByParentId: Map<String, List<Map<String, Any?>>>,
)

/** Wraps the data map with a [DataIndex]; detected via `data as? PlanData`. */
internal class PlanData(
    private val raw: Map<String, List<Map<String, Any?>>>,
    val idx: DataIndex,
) : Map<String, List<Map<String, Any?>>> by raw

/** Wraps the inventory list with a (pid,lid)→rows index; detected via `inventory as? IndexedInventory`. */
internal class IndexedInventory(
    private val list: MutableList<MutableMap<String, Any?>>,
    val idx: Map<Pair<String, String>, List<MutableMap<String, Any?>>>,
) : MutableList<MutableMap<String, Any?>> by list

private fun buildDataIndex(data: Map<String, List<Map<String, Any?>>>): DataIndex = DataIndex(
    makeByPidLid = (data["method_make"] ?: emptyList()).groupBy {
        Pair(it["product_id"]?.toString()?.trim() ?: "", it["location_id"]?.toString()?.trim() ?: "")
    },
    moveByPidToLid = (data["method_move"] ?: emptyList()).groupBy {
        Pair(it["product_id"]?.toString()?.trim() ?: "", it["to_location_id"]?.toString()?.trim() ?: "")
    },
    buyByPidLid = (data["method_buy"] ?: emptyList()).groupBy {
        Pair(it["product_id"]?.toString()?.trim() ?: "", it["location_id"]?.toString()?.trim() ?: "")
    },
    bomByBomId = (data["bom"] ?: emptyList()).groupBy {
        it["bom_id"]?.toString()?.trim() ?: ""
    },
    bomByParentId = (data["bom"] ?: emptyList()).groupBy {
        it["parent_id"]?.toString()?.trim() ?: ""
    },
)

private fun buildInventoryIndex(
    inventory: List<MutableMap<String, Any?>>,
): Map<Pair<String, String>, List<MutableMap<String, Any?>>> =
    inventory.groupBy { row ->
        Pair(row["product_id"]?.toString()?.trim() ?: "", row["location_id"]?.toString()?.trim() ?: "")
    }

/** Cheap qty-only snapshot: captures the current qty of every bucket in order. */
private fun snapshotQtys(inventory: List<MutableMap<String, Any?>>): DoubleArray =
    DoubleArray(inventory.size) { i -> (inventory[i]["qty"] as? Number)?.toDouble() ?: 0.0 }

/** Restores qty values in-place from a [snapshotQtys] snapshot.
 *  In-place restore (vs clear+addAll) keeps [IndexedInventory.idx] pointers valid
 *  across rollbacks, so the index remains usable after partial-plan rollback. */
private fun restoreQtys(inventory: List<MutableMap<String, Any?>>, snap: DoubleArray) {
    for (i in snap.indices) inventory[i]["qty"] = snap[i]
}

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

    // Fast path: O(1) bom_id lookup when PlanData index is available.
    val idx = (data as? PlanData)?.idx
    if (idx != null) {
        val primary = (idx.bomByBomId[bomId] ?: emptyList())
            .filter { (it["parent_id"] as? String)?.trim() == pid }
        if (primary.isNotEmpty()) return buildVariants(primary)
        val fallback = (idx.bomByParentId[pid] ?: emptyList())
        if (fallback.isNotEmpty()) {
            log.debug("variantsForMake: no BOM rows for bom_id={} parent={}; using fallback", bomId, pid)
            return buildVariants(fallback)
        }
        return emptyList()
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

/**
 * Selective-purchase whitelist: the set of buyable `product_id`s the user permits
 * purchasing this run. Returns null when there is no restriction — config absent,
 * not a list, or an empty/whitespace-only list — meaning *all* buy methods are
 * admitted (the default, and the historical all-or-nothing behavior when
 * `purchase_allowed=true`). A non-null set is a strict whitelist: any buy method
 * whose product is not in the set is dropped.
 */
internal fun purchasableSet(config: Map<String, Any?>?): Set<String>? {
    val raw = config?.get("purchasable_materials") as? List<*> ?: return null
    val s = raw.mapNotNull { (it as? String)?.trim() }.filter { it.isNotEmpty() }.toSet()
    return s.ifEmpty { null }
}

/**
 * The effective buy-whitelist actually applied by the planner. The user-facing
 * `purchasable_materials` list controls RAW-material purchases ONLY — the picker enumerates
 * raw materials (productlocation.prod_area='raw' with a method_buy). Some buyable products
 * are NOT raw (e.g. purchased sub-assemblies); those are out of scope and must stay
 * admitted. So we augment the user's set with every non-raw buyable product. Consequence:
 * selecting EVERY listed (raw) material is equivalent to an empty list — both admit all raw
 * + all non-raw buys (i.e. "all selected ≡ none selected ≡ no restriction"). Returns null
 * (no restriction) when the user list is empty.
 */
internal fun effectivePurchasableSet(
    config: Map<String, Any?>?,
    data: Map<String, List<Map<String, Any?>>>,
): Set<String>? {
    val base = purchasableSet(config) ?: return null
    val rawIds = (data["productlocation"] ?: emptyList())
        .filter { (it["prod_area"] as? String)?.trim() == "raw" }
        .mapNotNull { (it["product_id"] as? String)?.trim() }
        .toHashSet()
    val nonRawBuyables = (data["method_buy"] ?: emptyList())
        .mapNotNull { (it["product_id"] as? String)?.trim() }
        .filter { it.isNotEmpty() && it !in rawIds }
    return base + nonRawBuyables
}

/**
 * A customer-specific BOM-alternative constraint. When a demand from [customerId] resolves
 * [parent] as a make at a matching location, the planner is forced to pick the alternative
 * (alt_group variant) whose children include [child] — overriding the automatic
 * best-score/equal-split variant selection. [location] blank or "*" matches any location.
 */
internal data class Constraint(
    val customerId: String,
    val parent: String,
    val location: String,
    val child: String,
)

/**
 * Parse `config.constraints` (a list of {customer, parent, location, child} rules). Tolerant:
 * accepts `customer`/`customer_id`, `parent`/`parent_product`, `child`/`child_product`; drops
 * rules missing customer/parent/child; returns empty when the key is absent or malformed.
 */
internal fun parseConstraints(config: Map<String, Any?>?): List<Constraint> {
    val raw = config?.get("constraints") as? List<*> ?: return emptyList()
    return raw.mapNotNull { e ->
        val m = e as? Map<*, *> ?: return@mapNotNull null
        fun s(vararg keys: String): String =
            keys.firstNotNullOfOrNull { (m[it] as? String)?.trim()?.takeIf { v -> v.isNotEmpty() } } ?: ""
        val customer = s("customer", "customer_id")
        val parent = s("parent", "parent_product")
        val child = s("child", "child_product")
        val location = (m["location"] as? String)?.trim() ?: ""
        if (customer.isEmpty() || parent.isEmpty() || child.isEmpty()) null
        else Constraint(customer, parent, location, child)
    }
}

/**
 * True if the make [method] (identified by its bom_id) produces [child] for [parent] — i.e.
 * a BOM row exists with that bom_id, parent, and child. Used to apply a customer constraint at
 * the METHOD level: alternatives are often distinct make methods (one bom_id per child), so the
 * planner picks among them by preference unless a constraint pins which child to produce.
 */
internal fun makeMethodProducesChild(
    parent: String,
    method: Map<String, Any?>,
    child: String,
    data: Map<String, List<Map<String, Any?>>>,
): Boolean {
    val bomId = (method["bom_id"] as? String)?.trim() ?: return false
    val p = parent.trim(); val ch = child.trim()
    return (data["bom"] ?: emptyList()).any { b ->
        (b["bom_id"] as? String)?.trim() == bomId &&
            (b["parent_id"] as? String)?.trim() == p &&
            (b["child_id"] as? String)?.trim() == ch
    }
}

/** True if a purchase method for [productId] is admitted under the current gate. */
private fun buyAdmitted(productId: String, purchaseAllowed: Boolean, purchasable: Set<String>?): Boolean =
    purchaseAllowed && (purchasable == null || productId.trim() in purchasable)

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

    // Fast path: O(1) index lookup when PlanData is available, avoiding O(N) scans.
    val idx = (data as? PlanData)?.idx
    if (idx != null && !emptyLocFallback) {
        val result = mutableListOf<Map<String, Any?>>()
        val key = Pair(pid, loc)
        idx.buyByPidLid[key]?.forEach { m -> result.add(mapOf("type" to "purchase") + m) }
        idx.makeByPidLid[key]?.forEach { m -> result.add(mapOf("type" to "make") + m) }
        idx.moveByPidToLid[key]?.forEach { m -> result.add(mapOf("type" to "move") + m) }
        return result
    }

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
    // Selective-purchase whitelist (null ⇒ no restriction). A buy edge bottoms out
    // a branch at depth 0 only if the product is actually buyable under the gate —
    // otherwise the structural cache would admit a make-fallback path that the
    // runtime planner then rejects, defeating the "skip wasteful recursion" purpose.
    purchasable: Set<String>? = null,
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
                .filter { m ->
                    m["type"] != "purchase" ||
                        buyAdmitted((m["product_id"] as? String) ?: productId, purchaseAllowed, purchasable)
                }

            for (m in methods) {
                val depth = methodStructuralDepth(productId, locationId, m, data, purchaseAllowed, cache, inProgress, purchasable)
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

/**
 * Structural depth of fulfilling `(productId, locationId)` VIA the single method [m],
 * sharing [maxMakeDepth]'s memoized child feasibility. `Int.MAX_VALUE` ⇒ this method's
 * chain can't bottom out — a self-cycle (make/move X ultimately needs X again with no
 * inventory/buy base case) or a dead-end. [inProgress] MUST already contain
 * `(productId, locationId)` so a child that references back is detected as a cycle.
 *
 * Used by [maxMakeDepth] (to take the min over methods) and by `plan()`'s cycle-aware
 * pruning, which drops infeasible methods from selection when a feasible one exists so a
 * single-method pick (max_methods=1) won't commit to a self-cycling method and dead-end.
 */
internal fun methodStructuralDepth(
    productId: String,
    locationId: String,
    m: Map<String, Any?>,
    data: Map<String, List<Map<String, Any?>>>,
    purchaseAllowed: Boolean,
    cache: MutableMap<Pair<String, String>, Int>,
    inProgress: MutableSet<Pair<String, String>>,
    purchasable: Set<String>? = null,
): Int = when (m["type"]) {
    "purchase" -> if (buyAdmitted((m["product_id"] as? String) ?: productId, purchaseAllowed, purchasable)) 0 else Int.MAX_VALUE
    "move" -> {
        val source = (m["from_location_id"] as? String)?.trim()
        if (source.isNullOrBlank()) Int.MAX_VALUE
        else maxMakeDepth(productId, source, data, purchaseAllowed, cache, inProgress, purchasable)
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
                    val cDepth = maxMakeDepth(cPid, cLid, data, purchaseAllowed, cache, inProgress, purchasable)
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
 * Probe-based variant scorer — O(children × inventory buckets), no copies.
 *
 * Returns the same Quadruple shape as [scoreVariant] so callers can swap
 * without structural changes.  `anyFailed` is optimistic (see [probeChildren]
 * for the trade-off).  Use only for *ranking* / *cascade* paths where a
 * wrong pick produces a suboptimal but still-correct commit result.
 * [firstFeasibleMethod] must keep [scoreVariant] for strict consolidation
 * correctness.
 */
private fun probeVariant(
    childList: List<Map<String, Any?>>,
    inventory: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    planningPath: Set<Pair<String, String>>,
): Quadruple<LocalDate?, Double, Double, Boolean> {
    val r = probeChildren(childList, inventory, data, planningPath)
    return Quadruple(if (r.anyFailed) null else r.maxCommitDate, r.consumed, r.purchaseQty, r.anyFailed)
}

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
        val leadDays = leadDaysForMethod(m, productId, productionLocation, quantity, data)
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
        val leadDays = leadDaysForMethod(m, productId, productionLocation, quantity, data)

        val failed = when (m["type"]) {
            "purchase" -> false
            "move" -> {
                val children = childMaterialsForMove(m, quantity)
                probeChildren(children, inventory, data, planningPath).anyFailed
            }
            "make" -> {
                val variants = variantsForMake(productId, productionLocation, quantity, m, data)
                variants.isEmpty() || variants.all { (_, childList) ->
                    probeChildren(childList, inventory, data, planningPath).anyFailed
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
        val productionLocation = (m["location_id"] ?: m["to_location_id"] ?: locationId) as? String ?: locationId
        val reqDt = parseDate(reqTimeStr) ?: requestTimeDt
        val leadDays = when (m["type"]) {
            "make" -> (m["lead_time"] as? Number)?.toDouble() ?: 0.0
            "move" -> (m["transit_time"] as? Number)?.toDouble() ?: 0.0
            "purchase" -> (m["lead_days_supply"] as? Number)?.toDouble() ?: 0.0
            else -> 0.0
        }

        val childMaterials = when (m["type"]) {
            "make" -> {
                val variants = variantsForMake(productId, productionLocation, quantity, m, data)
                if (variants.isEmpty()) return@map Raw(m, LATE_DATE.toEpochDay().toDouble(), 0.0, 0.0, true)
                val (variantList, _) = getPreferredVariants(
                    variants, inventory, data, reqDt, leadDays, planningPath, depth - 1, quantity,
                    multiple = false, scoreWeights = scoreWeights, topN = null,
                    config = simConfig,
                )
                variantList.flatMap { (cm, _, _) -> cm }
            }
            "move" -> childMaterialsForMove(m, quantity)
            else -> emptyList()
        }

        val probe = probeChildren(childMaterials, inventory, data, planningPath)
        val ts = (if (probe.anyFailed) null else probe.maxCommitDate)
            ?.toEpochDay()?.toDouble() ?: LATE_DATE.toEpochDay().toDouble()
        Raw(m, ts, probe.consumed, probe.purchaseQty, probe.anyFailed)
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
    nodeQtyCaps: Map<Pair<String, String>, Double>? = null,
    demandBlueprint: DemandBlueprint? = null,
): MethodSlotResult {
    val productionLocation = (if (m["type"] == "move") m["to_location_id"] else m["location_id"])?.toString() ?: locationId
    val reqDt = parseDate(reqTimeStr) ?: requestTimeDt ?: LocalDate.now()
    val leadDays = leadDaysForMethod(m, productId, productionLocation, slotQty, data)

    // 3) Child materials
    var woChildrenRelation: String? = null
    val (childMaterials, variantExplanation) = when (m["type"]) {
        "make" -> {
            val rawVariants = variantsForMake(productId, productionLocation, slotQty, m, data)
            // Variant selection, in precedence order:
            //  1) explicit saved per-WO variant_selection override (force a specific alt_group);
            //  2) else a customer-specific Constraint (force the alt whose children include the
            //     constrained child) — config.constraints matched on demand.customer_id + parent
            //     + (location blank/"*" or this productionLocation);
            //  3) else all variants (automatic best-score / equal-split downstream).
            var constraintChild: String? = null
            val variants = if (variantOverride != null) {
                val forcedAltGroup = variantOverride["alt_group"]?.toString()
                rawVariants.filter { (altKey, _) -> forcedAltGroup == null || altKey == forcedAltGroup }
                    .ifEmpty {
                        log.warn("variant_selection override alt_group={} for {}@{} matched nothing; using all", forcedAltGroup, productId, productionLocation)
                        rawVariants
                    }
            } else {
                val cust = demand["customer_id"]?.toString()?.trim()
                val match = if (cust.isNullOrEmpty()) null else parseConstraints(config).firstOrNull { c ->
                    c.customerId == cust && c.parent == productId.trim() &&
                        (c.location.isBlank() || c.location == "*" || c.location == productionLocation.trim())
                }
                if (match != null) {
                    constraintChild = match.child
                    rawVariants.filter { (_, childList) ->
                        childList.any { (it["product_id"] as? String)?.trim() == match.child }
                    }.ifEmpty {
                        log.warn("constraint customer={} parent={}@{} child={} matched no variant; using all", cust, productId, productionLocation, match.child)
                        rawVariants
                    }
                } else rawVariants
            }
            val (variantList, ve) = getPreferredVariants(variants, inventory, data, reqDt, leadDays, path, depth, slotQty,
                multiple = if (useSingleVariant) false else null, scoreWeights = scoreWeights, topN = topN, config = config)
            val cm = variantList.flatMap { (childList, _, _) -> childList }
            if (variantList.size > 1) {
                woChildrenRelation = if (variantList.all { (childList, _, _) -> childList.size == 1 }) "or" else "and"
            } else if (variantList.size == 1 && (variantList[0].first.size) > 1) {
                // Single variant with multiple BOM children → AND group (all required).
                woChildrenRelation = "and"
            }
            val veAnnotated = when {
                variantOverride != null -> "$ve [variant override active]"
                constraintChild != null -> "$ve [constraint child=$constraintChild]"
                else -> ve
            }
            Pair(cm, veAnnotated)
        }
        "move" -> Pair(childMaterialsForMove(m, slotQty), "")
        else -> Pair(emptyList<Map<String, Any?>>(), "")
    }

    // ── Pre-scale to achievable using nodeQtyCaps (single-pass guard) ─────────
    // When the probing step has pre-computed per-node achievable quantities, derive
    // the minimum scale factor from capped children and reduce slotQty + child
    // quantities before the first-pass loop. This makes anyChildShort = false for
    // all cap-bounded cases, eliminating the second-pass BOM retrace.
    var activeSlotQty = slotQty
    val activeChildren: List<Map<String, Any?>>
    if (nodeQtyCaps != null && childMaterials.isNotEmpty() && slotQty > 1e-9) {
        var minScale = 1.0
        for (c in childMaterials) {
            val cPid = (c["product_id"] as? String)?.trim() ?: continue
            val cLid = (c["location_id"] as? String)?.trim() ?: continue
            val cNeeded = (c["quantity"] as? Number)?.toDouble() ?: continue
            if (cNeeded <= 1e-9) continue
            val cap = nodeQtyCaps[cPid to cLid] ?: continue
            if (cap >= cNeeded - 1e-9) continue
            val s = cap / cNeeded
            if (s < minScale) minScale = s
        }
        if (minScale < 1.0 - 1e-9) {
            activeSlotQty = floor(slotQty * minScale).coerceAtLeast(0.0)
            val finalScale = if (slotQty > 1e-9) activeSlotQty / slotQty else 1.0
            activeChildren = childMaterials.map { c ->
                val q = (c["quantity"] as? Number)?.toDouble() ?: return@map c
                c + mapOf("quantity" to q * finalScale)
            }
            log.info("[nodeqtycap-prescale] parent={}@{} did={} requested={} effective={} scale={}",
                productId, productionLocation, demandId, slotQty, activeSlotQty, "%.4f".format(minScale))
        } else {
            activeChildren = childMaterials
        }
    } else {
        activeChildren = childMaterials
    }

    // 4) Recursively plan children — with partial-fulfillment support.
    //    a) Snapshot inventory before any child planning.
    //    b) Run a first pass for all children at full activeSlotQty.
    //    c) Compute the achievable parent qty as the bottleneck ratio.
    //    d) If partial: restore the snapshot and re-plan at the proportionally-
    //       scaled achievable qty (second pass).
    //    e) Emit the parent WO for achievableQty.
    val childWos = mutableListOf<Map<String, Any?>>()
    val commitTimes = mutableListOf<LocalDate>()
    val childPeggingNodes = mutableListOf<Map<String, Any?>>()

    // Qty-only snapshot: captures just the qty values in position order.
    // In-place restore (restoreQtys) keeps IndexedInventory.idx pointers valid
    // across rollbacks — no object replacement, same MutableMaps throughout.
    val inventorySnap = snapshotQtys(inventory)
    val budgetSnap: Map<String, Double>? = budget?.toMap()

    // ── First pass: plan all children at activeSlotQty ───────────────────────
    val childPassResults = mutableListOf<ChildPassResult>()
    for (c in activeChildren) {
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
            "quantity" to neededQty, "request_due_time" to formatDate(cReqDt), "request_time" to formatDate(cReqDt),
            // Inherit the originating demand's customer so customer-specific constraints
            // apply to sub-components, not just the finished good.
            "customer_id" to demand["customer_id"], "customer" to demand["customer"])
        val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, overrideIndex = overrideIndex, budget = budget, feasibilityCache = feasibilityCache, structuralFailedMakes = structuralFailedMakes, initialBudget = initialBudget, nodeQtyCaps = nodeQtyCaps, demandBlueprint = demandBlueprint)
        val effectiveQty = solvedList.sumOf { s ->
            val r = s["commit_reason"] as? String
            if (r == "cycle_stopped" || r == "cycle_detected") 0.0
            else if (!isHardPlanningFailure(r)) (s["quantity"] as? Number)?.toDouble() ?: 0.0
            else 0.0
        }
        val cTimes = solvedList.mapNotNull { s -> parseDate(s["commit_time"] as? String) }
        // Tag gc_bom_rate on the demand node so GCEngine can scale into AND-children
        // recursively without needing the original BOM table. Rate = child_need / parent_slotQty.
        val bomRate = if (activeSlotQty > 1e-9) neededQty / activeSlotQty else 0.0
        val taggedPegging = cPegging?.plus("gc_bom_rate" to bomRate)
        childPassResults.add(ChildPassResult(c, neededQty, effectiveQty, cWos, taggedPegging, cTimes, solvedList))
    }

    // Blueprint mode: BOM rates produce fractional child quantities (e.g. 452.0012 from
    // demand_qty × bom_rate). nodeQtyCaps round to integer achievable (e.g. 452.0), so the
    // child commits 452.0 against needed 452.0012 — a 0.0012-unit "shortfall" that is purely
    // floating-point noise. With 1e-9 tolerance this triggers the second-pass BOM re-traversal
    // for every single BOM node, making it the dominant planning cost. With 0.5 tolerance in
    // blueprint mode, sub-unit noise is absorbed; genuine shortfalls (typically many units) still
    // trigger the second pass. Non-blueprint runs keep the strict 1e-9 tolerance.
    val anyChildShortTolerance = if (demandBlueprint != null) 0.5 else 1e-9
    val anyChildShort = childPassResults.any { cr -> cr.effectiveQty < cr.neededQty - anyChildShortTolerance }

    // ── Determine achievable parent qty ──────────────────────────────────────
    var achievableParentQty: Double
    if (!anyChildShort || activeChildren.isEmpty()) {
        achievableParentQty = activeSlotQty
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
        val (rawAchievable, isOrSplit) = computeRawAchievable(childPassResults, activeSlotQty, woChildrenRelation)
        val capped = if (rawAchievable >= activeSlotQty - 1e-6) activeSlotQty
                     else floor(rawAchievable).coerceIn(0.0, activeSlotQty)

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
            restoreQtys(inventory, inventorySnap)
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
                data = data,
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
            // When blueprint is active, nodeQtyCaps values come from the sketch phase
            // which uses integer-unit supply counts. BOM rates (e.g. 452.0012848...)
            // produce fractional slotQty values that differ from nodeQtyCaps by <0.002
            // units due to floating-point arithmetic. Using 1e-6 causes these sub-unit
            // gaps to trigger a full second-pass BOM re-traversal on every node —
            // the dominant planning cost. With 0.5 tolerance, sub-unit noise is ignored;
            // genuine shortfalls (typically tens or hundreds of units short) still trigger.
            val andMinTolerance = if (demandBlueprint != null) 0.5 else 1e-6
            val bottleneckPegging: Map<Pair<String, String>, Map<String, Any?>?> =
                if (achievableParentQty < activeSlotQty - andMinTolerance) {
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

            // ── GC: trim over-committed first-pass children, return excess to inventory ──────
            // Replaces snapshot-restore + second-pass re-plan. For each AND-child that
            // committed more than its achievable share (achievableParentQty * bomRate),
            // garbageCollectPegging walks the child's pegging subtree and returns the
            // excess inventory (and budget) in-place. Only over-committed children are
            // touched; children that delivered at or below their share are kept as-is.
            // Bottleneck / root-bottleneck tags from the first pass are preserved on
            // the (now-trimmed) pegging nodes so the UI attribution is unchanged.
            val gcScale = achievableParentQty / activeSlotQty
            for (cr in childPassResults) {
                val targetQty = cr.neededQty * gcScale
                val woScale   = if (cr.effectiveQty > 1e-9) targetQty / cr.effectiveQty else 0.0
                childWos.addAll(scaleWos(cr.wos, woScale))
                commitTimes.addAll(cr.cTimes)
                val peg = cr.pegging ?: continue
                val trimmed: Map<String, Any?> = if (cr.effectiveQty > targetQty + 1e-9)
                    garbageCollectPegging(peg, targetQty, inventory, budget)
                else peg
                // reconcile() derives BOM rate as child.quantity / wo.quantity. In the old second-pass
                // world the child node was replanned at targetQty so its quantity == targetQty. In the
                // GC world the node comes from the first pass where quantity == neededQty (the full
                // first-pass request). Without this fix, rateOf = neededQty / achievableParentQty
                // (e.g. 40/10 = 4.0) instead of the true BOM rate (neededQty / activeSlotQty = 1.0),
                // causing reconcile to cut the parent commit to effectiveQty / inflatedRate = 2.5.
                val scaled = trimmed + ("quantity" to targetQty)
                val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
                val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
                val key = pid to lid
                var tagged: Map<String, Any?> = scaled
                if (key in bottleneckPegging) tagged = tagged + ("is_bottleneck" to true)
                if (key in rootBottleneckKeys) tagged = tagged + ("is_root_bottleneck" to true)
                childPeggingNodes.add(tagged)
            }

            // Move conservation safety: GC targets exactly achievableParentQty for the
            // move-source child (bomRate=1). Correct parent if GC could not fully return
            // (e.g. missing inventory bucket) so the WO qty never exceeds the child commit.
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
    // Purchase leaves have no source supply record (they are new procurement),
    // so surface the vendor as their identifier in the supply-leaf table.
    val woChildren = if (methodType == "purchase") listOf(mapOf("type" to "purchase", "product_id" to productId, "location_id" to productionLocation, "quantity" to roundQty(achievableParentQty), "vendor_id" to m["vendor_id"], "start_time" to formatDate(startDt), "end_time" to formatDate(lastEnd), "children" to emptyList<Any>()))
                     else childPeggingNodes
    val methodPeggingNode = buildWoNode(productId, productionLocation, achievableParentQty, methodType, m, startDt, lastEnd, lotCount, lotSizeVal, methodChoiceExplanation, variantExplanation, woChildrenRelation, woChildren, overrideActive, woGroupId = woResult.woGroupId, data = data)

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
        val sc = probeVariant(childList, inventory, data, planningPath)
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
    /**
     * Optional per-node achievable quantity caps from the supply-guided probing
     * step ([computeAchievableQtyMaps]).  When the probed achievable at
     * (productId to locationId) is less than [demand]["quantity"], plan() caps
     * the effective demand and re-enters itself — so downstream supply draws
     * and WO emission never exceed what the budget tree can cover.
     * Threaded through all recursive plan() / planMethodSlot() calls.
     */
    nodeQtyCaps: Map<Pair<String, String>, Double>? = null,
    /**
     * Optional per-demand BOM blueprint from [computePlanBlueprint].
     * When present and the current (productId, locationId) has a blueprint entry whose
     * method is still in effectiveMethods, the commit phase uses it directly — skipping
     * [getPreferredMethodCascade] and its probeChildren overhead.
     * Threaded through all recursive plan() / planMethodSlot() calls.
     */
    demandBlueprint: DemandBlueprint? = null,
): Triple<List<Map<String, Any?>>, List<Map<String, Any?>>, Map<String, Any?>?> {

    val productId = (demand["product_id"] as? String)?.trim() ?: ""
    val locationId = (demand["location_id"] as? String)?.trim() ?: ""
    val quantity = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
    val demandId = demand["demand_id"]

    // Apply per-node achievable cap from the supply-guided probing step.
    // If the probed achievable at this node is less than the requested qty,
    // cap and re-enter with a modified demand so all downstream draws and WO
    // emission work on the achievable quantity — no try-and-error required.
    val nodeCap = nodeQtyCaps?.get(productId to locationId)
    if (nodeCap != null && nodeCap < quantity - 1e-9) {
        return plan(
            demand           = demand + mapOf("quantity" to nodeCap),
            inventory        = inventory,
            data             = data,
            requestTimeDt    = requestTimeDt,
            depth            = depth,
            planningPath     = planningPath,
            config           = config,
            preferDemandId   = preferDemandId,
            overrideIndex    = overrideIndex,
            budget           = budget,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
            initialBudget    = initialBudget,
            nodeQtyCaps      = nodeQtyCaps,
            demandBlueprint  = demandBlueprint,
        )
    }
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
    // Per-lot caps: collect all budget entries whose key starts with "$pid|$lid|".
    val perLotBudget: MutableMap<String, Double>? = budget?.let { b ->
        val prefix = "$componentKey|"
        val lotEntries = b.entries.filter { it.key.startsWith(prefix) }
        if (lotEntries.isEmpty()) null
        else lotEntries.associateTo(mutableMapOf()) { (k, v) ->
            k.removePrefix(prefix) to v   // supplyId → remaining
        }
    }
    // Aggregate cap: explicit key, or sum of per-lot entries.
    // In non-realPegging mode all synthetic supplies share supply_id "consolidated_pid_lid" so
    // no per-lot key ever matches inside consumeFromInventory — the aggregate cap is the only
    // guard; perLotBudget values are reduced proportionally on write-back.
    val totalLotBudgetBefore = perLotBudget?.values?.sum() ?: 0.0
    val budgetCap = budget?.get(componentKey)
        ?: perLotBudget?.values?.sum()?.takeIf { it > 1e-9 }
    val consumedBuckets = consumeFromInventory(
        inventory, productId, locationId, quantity, preferDemandId, budgetCap, perLotBudget,
    )
    val taken = consumedBuckets.sumOf { it.qty }
    // Write back per-lot budget remainders.
    if (budget != null && perLotBudget != null) {
        val lotCapWasConsumed = (totalLotBudgetBefore - (perLotBudget.values.sum())) > 1e-9
        if (lotCapWasConsumed) {
            // realPegging: per-lot caps were enforced in-place by consumeFromInventory; write back.
            for ((sid, remaining) in perLotBudget) {
                budget["$componentKey|$sid"] = remaining
            }
        } else if (taken > 1e-9 && totalLotBudgetBefore > 1e-9) {
            // non-realPegging: synthetic supply_id didn't match any lot key, so lot entries
            // were not mutated. Proportionally reduce all lot entries to track the aggregate.
            val consumedFraction = taken.coerceAtMost(totalLotBudgetBefore) / totalLotBudgetBefore
            for ((sid, cap) in perLotBudget) {
                budget["$componentKey|$sid"] = (cap * (1.0 - consumedFraction)).coerceAtLeast(0.0)
            }
        }
    }
    if (budget != null && budgetCap != null && budget.containsKey(componentKey)) {
        budget[componentKey] = (budgetCap - taken).coerceAtLeast(0.0)
    }
    // ── [MAP] Log draws on 260-0141-02 per lot ───────────────────────────────────
    if (productId == "260-0141-02") {
        consumedBuckets.forEach { cb ->
            val lotKey = if (cb.supplyId != null) "$componentKey|${cb.supplyId}" else componentKey
            val lotCap = budget?.get(lotKey) // remaining after deduction
            log.info("[map][draw] demand={} lot={} taken={} budgetRemaining={}",
                demandId, cb.supplyId ?: "(agg)", cb.qty.toLong(), lotCap?.toLong() ?: "N/A")
        }
        if (consumedBuckets.isEmpty()) {
            log.info("[map][draw] demand={} supply={} need={} taken=0 (no inventory or capped)",
                demandId, componentKey, quantity.toLong())
        }
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
    val purchasable = effectivePurchasableSet(config, data)  // raw whitelist + non-raw buyables; null ⇒ no restriction
    val methodsRaw = getMethods(productId, locationId, data)
    // Selective purchase: keep every non-buy method; admit a buy only when
    // purchase is allowed AND (no whitelist, or this product is whitelisted).
    val methods = methodsRaw.filter { m ->
        m["type"] != "purchase" ||
            buyAdmitted((m["product_id"] as? String) ?: productId, purchaseAllowed, purchasable)
    }
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
        // A buy method exists at this location but was filtered out — either by
        // purchase_allowed=false (global) or by this product being absent from a
        // non-empty purchasable-materials whitelist (selective).
        val hasBuyHere = methodsRaw.any { it["type"] == "purchase" }
        val buyFiltered = !purchaseAllowed && hasBuyHere
        val buyNotWhitelisted = purchaseAllowed && purchasable != null && hasBuyHere &&
            productId.trim() !in purchasable
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
            if (buyNotWhitelisted) {
                append(" A buy method exists at this location but $productId is not in the " +
                    "purchasable-materials whitelist; add it (or clear the list to allow all " +
                    "raw materials) to admit the buy.")
            }
            if (otherLocs.isNotEmpty()) {
                append(" The product CAN be produced at: ${otherLocs.joinToString(", ")}, " +
                    "but no method (move-to-$locationId or make-at-$locationId) is defined to " +
                    "bring it here. Add a method_move row from one of those locations to " +
                    "$locationId, or define a make recipe at $locationId.")
            }
            if (!buyFiltered && !buyNotWhitelisted && otherLocs.isEmpty()) {
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
    // ── Customer constraint at the METHOD level ──────────────────────────────
    // BOM alternatives are frequently distinct make methods (one bom_id per child),
    // chosen by preference. When a constraint matches (customer, parent, and the make
    // method's location — blank/"*" = any), drop the make methods that DON'T produce the
    // constrained child so the planner is forced onto the pinned route. Non-make methods
    // are untouched; if no make method produces the child, fall back to all (warn). An
    // explicit method_selection override takes precedence (constraint only applies below).
    // (The within-method alt_group case is additionally narrowed in planMethodSlot.)
    val cust = demand["customer_id"]?.toString()?.trim()
    val constraintRules = if (methodOverride != null || cust.isNullOrEmpty()) emptyList()
        else parseConstraints(config).filter { it.customerId == cust && it.parent == productId.trim() }
    val constrainedMethods = if (constraintRules.isEmpty()) methods else {
        val filtered = methods.filter { m ->
            if (m["type"] != "make") return@filter true
            val mLoc = (m["location_id"] as? String)?.trim() ?: locationId.trim()
            val rule = constraintRules.firstOrNull { it.location.isBlank() || it.location == "*" || it.location == mLoc }
            rule == null || makeMethodProducesChild(productId, m, rule.child, data)
        }
        if (filtered.any { it["type"] == "make" } || methods.none { it["type"] == "make" }) filtered
        else {
            log.warn("constraint customer={} parent={}: no make method produces the constrained child; using all", cust, productId)
            methods
        }
    }

    val overrideFilteredMethods = if (methodOverride != null) {
        val forcedType = (methodOverride["method"] ?: methodOverride["method_type"])?.toString()
        val forcedPref = (methodOverride["preference"] as? Number)?.toInt()
        methods.filter { m ->
            (forcedType == null || m["type"]?.toString() == forcedType) &&
            (forcedPref == null || (m["preference"] as? Number)?.toInt() == forcedPref)
        }.ifEmpty {
            log.warn("method_selection override for {}@{} matched no methods; using all", productId, locationId)
            methods
        }
    } else constrainedMethods

    // ── Cycle-aware method pruning ───────────────────────────────────────────
    // Drop methods whose chain can't structurally bottom out (a self-cycle — e.g.
    // make/move X ultimately needs X again, with no inventory/buy base case) WHEN at
    // least one feasible method remains. This makes single-method mode (max_methods=1)
    // robust: without it the lowest-preference method is picked blindly, so a demand on
    // a part that ping-pongs between two locations commits to the cyclic make/move and
    // dead-ends (cause=cycle_stopped) instead of choosing the feasible buy/move-from-
    // stock path. If NO method is feasible, keep them all so the genuine failure (and its
    // diagnostic) still surfaces. Only runs when the feasibility cache is available and
    // there's an actual choice to make.
    val effectiveMethods = if (feasibilityCache != null && overrideFilteredMethods.size > 1) {
        val inProgress = mutableSetOf(Pair(productId, locationId))
        val feasible = overrideFilteredMethods.filter { mm ->
            methodStructuralDepth(productId, locationId, mm, data, purchaseAllowed, feasibilityCache, inProgress, purchasable) < Int.MAX_VALUE
        }
        if (feasible.isNotEmpty() && feasible.size < overrideFilteredMethods.size) {
            log.info("cycle-aware: {}@{} pruned {} self-cycling/dead-end method(s); {} feasible remain",
                productId, locationId, overrideFilteredMethods.size - feasible.size, feasible.size)
            feasible
        } else overrideFilteredMethods
    } else overrideFilteredMethods

    val elaborateAtThisLevel = shouldElaborateAtDepth(depth, methodCfg.depth)

    // ── Blueprint shortcut (preference mode only) ──────────────────────────
    // Pre-selected in the sketch phase. Applied to BOTH the waterfall path
    // (collapses to 1 slot) and the cascade/single-method path (bypasses
    // getPreferredMethodCascade). Not used in elaborate mode.
    val blueprintEntry = if (!useElaborateMethod) {
        demandBlueprint?.get(productId to locationId)
    } else null
    val blueprintMethod = blueprintEntry?.method?.takeIf { bm ->
        effectiveMethods.any { it === bm || it == bm }
    }

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
        // Blueprint: if a method was pre-selected in the sketch phase, collapse the
        // waterfall to a single slot — eliminating the second-slot re-traversal.
        val ranked: List<Map<String, Any?>> = when {
            blueprintMethod != null -> listOf(blueprintMethod)
            useElaborateMethod -> {
                scoreMethodsForElaborate(effectiveMethods, demand, inventory, data, requestTimeDt, config, depth, path)
                    .sortedWith(
                        // Tiebreaker by preference asc — see getPreferredMethodElaborate
                        // for the rationale (tied sims under deep blocks would otherwise
                        // pick whatever comes first in getMethods's iteration order).
                        compareByDescending<MethodScore> { it.score }
                            .thenBy { (it.method["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
                    ).map { it.method }
            }
            else -> effectiveMethods.sortedBy { (it["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE }
        }

        // Override-active determination for waterfall: only "did override narrow
        // the candidate set" applies, since waterfall doesn't pick a single
        // method that could "differ from auto-selection".
        val methodOverrideActiveW = methodOverride != null && overrideFilteredMethods.size < methods.size
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
                nodeQtyCaps = nodeQtyCaps,
                demandBlueprint = demandBlueprint,
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
        blueprintMethod != null -> {
            val loc  = (blueprintMethod["location_id"] ?: blueprintMethod["to_location_id"] ?: "").toString()
            val pref = (blueprintMethod["preference"] as? Number)?.toInt() ?: 0
            Pair(blueprintMethod, "Blueprint: ${blueprintMethod["type"]} @ $loc (pref $pref)")
        }
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
        blueprintMethod != null        -> overrideFilteredMethods.size < methods.size
        useElaborateMethod && elaborateAtThisLevel -> overrideFilteredMethods.size < methods.size
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
        val purchasableForCache = effectivePurchasableSet(config, data)
        val rawMakes = effectiveMethods.filter { it["type"] == "make" && it !== m }
        rawMakes
            .filter {
                if (productId.startsWith("VirtualProduct_")) true
                else {
                    val mkDepth = maxMakeDepth(productId, locationId, data, purchaseAllowedForCache, feasibilityCache, purchasable = purchasableForCache)
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
    // A slot fully blocked by a self-cycle is structurally UNUSABLE (it contributes 0),
    // not merely capacity-limited — so it does not count against max_methods. Tracking
    // these as "cycle escapes" lets single-method mode (cap=1) fall through from a cyclic
    // make/move to the alternative that actually breaks the loop, instead of dead-ending
    // on cause=cycle_stopped. The total is still bounded by fallbackOrder.size.
    var cycleEscapes = 0
    for ((slotIdx, candidate) in fallbackOrder.withIndex()) {
        if (slotIdx - cycleEscapes >= cap) break
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
            nodeQtyCaps = nodeQtyCaps,
            demandBlueprint = demandBlueprint,
        )
        combinedPegging.add(attempt.methodPeggingNode)
        if (attempt.blockedReason != null) {
            // Reactive fallback: try the next method on hard block.
            lastBlockedReason = attempt.blockedReason
            // Cycle-escape: a self-cycle makes this method structurally unusable (achieved
            // 0), so grant an extra slot to try the alternative even under a tight cap.
            if (attempt.achievableQty <= 1e-9 && attempt.blockedReason?.contains("cycle") == true) {
                cycleEscapes++
            }
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
        if (nextIdx >= fallbackOrder.size) break
        if (nextIdx - cycleEscapes >= cap) break
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

internal fun leadDaysForMethod(
    m: Map<String, Any?>,
    productId: String? = null,
    locationId: String? = null,
    qty: Double = 0.0,
    data: Map<String, List<Map<String, Any?>>>? = null,
): Double = when (m["type"]) {
    "make" -> {
        val staticLead = (m["lead_time"] as? Number)?.toDouble() ?: 0.0
        if (productId != null && locationId != null && data != null && qty > 0.0) {
            OperationLookup.effectiveLeadDays(productId, locationId, qty, staticLead, data).days
        } else {
            staticLead
        }
    }
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

    // Concurrency cap: how many lots may share a wave (same start/end) at this
    // location. Subsequent waves start at the previous wave's end, so the cap
    // controls how far the lot series marches forward in time.
    //   • make     → resource-limited: parallelismCap lots per wave, the rest
    //                cascade forward one lead-time per wave (capacity is real).
    //   • purchase → no modeled cadence (cycle_days_supply is 0 across the data),
    //                so all POs are placeable at once. A single wave keeps every
    //                lot at (due − lead); without this a large consolidated
    //                quantity split by max_lot_size marches decades into the
    //                future (one lead-time per lot). [cycle_days_supply > 0 spacing
    //                is a future enhancement.]
    //   • move     → no modeled transport-capacity limit, so likewise concurrent.
    val cap = if (methodType == "make")
        OperationLookup.parallelismCap(productId, productionLocation, data).coerceAtLeast(1)
    else Int.MAX_VALUE

    val wos = mutableListOf<Map<String, Any?>>()
    var left = qty
    var waveStart: LocalDate? = startDt
    var lastEnd: LocalDate? = null
    var lotCount = 0
    var waveIndex = 0
    while (left > 1e-9 && waveStart != null) {
        val waveEnd = dateAddDays(waveStart, leadDays)
        // Each wave runs up to `cap` lots in parallel — all share waveStart /
        // waveEnd. Subsequent waves start at the previous wave's end (today's
        // sequential lots become cap=1, identical to old behavior).
        var lotsThisWave = 0
        while (lotsThisWave < cap && left > 1e-9) {
            val lotQty = min(lotSize, left)
            wos.add(mapOf(
                "product_id" to productId,
                "location_id" to productionLocation,
                "quantity" to roundQty(lotQty),
                "start_time" to formatDate(waveStart),
                "end_time" to formatDate(waveEnd),
                "method" to methodType,
                "location_source" to (if (methodType == "move") m["from_location_id"] else null),
                "demand_id" to demandId,
                "prod_area" to prodArea,
                "override_active" to overrideActive,
                "wo_group_id" to woGroupId,
                "wave_index" to waveIndex,
            ))
            left -= lotQty
            lotsThisWave++
            lotCount++
        }
        lastEnd = waveEnd
        waveIndex++
        waveStart = if (left > 1e-9) waveEnd else null
    }
    return WorkOrderResult(wos, lotCount, lastEnd, lotSizeVal, woGroupId)
}

/**
 * Phase 3 — bottom-up COMMITMENT aggregate over a finalized pegging tree (the quantity-focused
 * pass's closing step in the mental model: top-down requests, inventory consolidation, then
 * bottom-up commitment where "least supplied child dominates"). Top-down planning commits
 * greedily; cross-demand inventory contention can later zero a child a parent already committed
 * against, leaving a make committed ABOVE what its children actually supply (the R4/R8 break).
 *
 * `reconcile(node, target)` flows the parent's `target` down, lets each subtree report what it can
 * actually supply, takes the AND-min up ("least dominates"), and re-trims siblings to that figure
 * so the whole subtree is conservation-consistent — make.quantity = committed = min(child/rate).
 * It is a NO-OP for an already-consistent tree (every ask returns full, supply == target). Only
 * trimming of existing committed flows happens — no inventory re-planning — so it can't oscillate.
 * Returns the (rebuilt) node and the quantity it commits. A 0.5 band ignores fractional rounding.
 */
/** Proportionally trim a reconciled subtree by `factor` (≤1): scales quantity/committed_qty and
 *  every descendant the same way (releasing leaf allocations). Used to re-trim a make's
 *  over-supplied siblings down to the bottleneck — O(subtree), no re-reconcile. */
private fun scaleSubtree(node: Map<String, Any?>, factor: Double): Map<String, Any?> {
    if (factor >= 1.0 - 1e-9) return node
    val f = factor.coerceIn(0.0, 1.0)
    // EXACT scaling (no roundQty) — rounding a parent and child separately makes them disagree by
    // up to 1 (R4_move) and can round a leaf above its supply (R7a/R7b). Conservation must be exact.
    val nn = node.toMutableMap()
    (node["quantity"] as? Number)?.let { nn["quantity"] = it.toDouble() * f }
    (node["committed_qty"] as? Number)?.let { nn["committed_qty"] = it.toDouble() * f }
    (node["children"] as? List<*>)?.let { ch ->
        nn["children"] = ch.map { c -> (c as? Map<String, Any?>)?.let { scaleSubtree(it, f) } ?: c }
    }
    if (node["type"] == "work_order") {
        val newQ = ((node["quantity"] as? Number)?.toDouble() ?: 0.0) * f
        val hasDemandChild = (node["children"] as? List<*>)?.any { (it as? Map<*, *>)?.get("type") == "demand" } == true
        if (newQ <= 1e-6 && hasDemandChild) nn["failed"] = true
    }
    return nn
}

private fun reconcile(
    node: Map<String, Any?>,
    target: Double,
    data: Map<String, List<Map<String, Any?>>>,
): Pair<Map<String, Any?>, Double> {
    if (node["failed"] == true) return node to 0.0
    val allChildren = node["children"] as? List<*> ?: emptyList<Any?>()
    when (node["type"]) {
        "supply", "purchase" -> {
            val q = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            val supplied = minOf(target, q).coerceAtLeast(0.0)
            return (node + ("quantity" to supplied)) to supplied
        }
        "demand" -> {
            // Fulfilled by children (inventory/supply leaves + production WOs) in plan order,
            // each contributing up to the remaining shortfall. committed = what they together give.
            var remaining = target
            val newChildren = allChildren.map { ch ->
                val cm = ch as? Map<String, Any?> ?: return@map ch
                val (nc, g) = reconcile(cm, remaining.coerceAtLeast(0.0), data)
                remaining -= g
                nc
            }
            val committed = (target - remaining).coerceAtLeast(0.0)
            // Collapse the request to the commitment so the reconciled tree is fully consistent
            // (quantity == committed_qty for every internal node). The caller restores the root's
            // original request for the requested-vs-committed display.
            return (node + mapOf("children" to newChildren, "quantity" to committed, "committed_qty" to committed)) to committed
        }
        "work_order" -> {
            val curQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            val want = minOf(target, curQty).coerceAtLeast(0.0)
            val method = node["method"]
            val demandChildren = allChildren.mapNotNull { it as? Map<String, Any?> }.filter { it["type"] == "demand" }
            if (method == "purchase" || demandChildren.isEmpty()) {
                // Procurement / leaf-backed WO delivers `want`; trim leaf children to it.
                val newChildren = allChildren.map { ch -> (ch as? Map<String, Any?>)?.let { reconcile(it, want, data).first } ?: ch }
                return (node + mapOf("quantity" to want, "children" to newChildren)) to want
            }
            fun rateOf(cd: Map<String, Any?>) = if (curQty > 1e-9) ((cd["quantity"] as? Number)?.toDouble() ?: 0.0) / curQty else 0.0
            val rel = node["children_relation"] as? String
            // First (and only) ask: each component for want × rate. Capture the trimmed node AND
            // how many parents it can back (committed / rate). Reconcile each child ONCE here.
            val firstAsk = demandChildren.map { cd ->
                // A move is 1:1 (rate 1), so ask the source for exactly `want`. Using the stored
                // source.quantity/curQty would be ≈1 but not exactly 1 when the two drifted apart
                // upstream, leaving the source committed above the move (R4_move). Asking for `want`
                // caps the source to it.
                val r = if (method == "move") 1.0 else rateOf(cd)
                val (nc, c) = reconcile(cd, want * r, data)
                Triple(nc, r, if (r > 1e-9) c / r else Double.POSITIVE_INFINITY)
            }
            val rawSupply = when {
                method == "move" -> firstAsk.firstOrNull()?.third ?: want   // single source side
                rel == "or"      -> {
                    // OR-split: each variant independently contributes to the parent.
                    // The correct parent qty = Σ(c_i / BOM_rate_actual_i) — the same formula R4
                    // soundness uses. We look up actual BOM rates from the BOM table rather than
                    // inferring them from cd["quantity"] / curQty, because the latter encodes
                    // BOM_rate × variantShare / achievedParentQty and is wrong when the OR WO
                    // achieved less than slotQty (achievedQty < slotQty → rateOf is inflated →
                    // Σ third/n undershoots by slotQty/achievedQty, causing R4 actual>>expected).
                    val parentPid = (node["product_id"] as? String)?.trim() ?: ""
                    val bomRows = data["bom"] ?: emptyList()
                    val parentBomRows = bomRows.filter { (it["parent_id"] as? String)?.trim() == parentPid }
                    val sum = firstAsk.zip(demandChildren).sumOf { (ask, cd) ->
                        val (_, r, third) = ask
                        val c = third * r  // committed_qty for this variant: c = (c/r) × r
                        val childPid = (cd["product_id"] as? String)?.trim() ?: ""
                        val actualRate = parentBomRows
                            .firstOrNull { (it["child_id"] as? String)?.trim() == childPid }
                            ?.let { (it["rate"] as? Number)?.toDouble() }
                        if (actualRate != null && actualRate > 1e-9) c / actualRate else third
                    }
                    sum.coerceAtMost(want)
                }
                else             -> firstAsk.minOfOrNull { it.third } ?: want // AND make: least dominates
            }
            // EXACT — commit exactly the least-supplied child's contribution. (No slack band: it
            // would leave parent=want while the child commits want−ε, breaking R4/R4_move. A
            // genuinely-consistent tree returns rawSupply == want, so this is still a no-op there;
            // float-noise shortfalls scale by ≈1, which scaleSubtree treats as a no-op.)
            val supply = rawSupply.coerceIn(0.0, want)
            // Re-trim over-supplied components down to supply × rate by PROPORTIONAL scaling of the
            // already-reconciled subtree (no second reconcile — that would be exponential).
            // For OR WOs: skip trimming — each variant already holds its independently-reconciled
            // commitment; re-scaling them to `supply × rateOf` would over-correct (when supply < want
            // due to budget-cap shortfalls, targetT = supply × rateOf_i < c_i, forcing a second
            // scale-down that under-commits what the child actually delivered).
            var askIdx = 0
            val newChildren = allChildren.map { ch ->
                val cm = ch as? Map<String, Any?> ?: return@map ch
                if (cm["type"] != "demand") return@map cm
                val (asked, r, _) = firstAsk[askIdx]; askIdx++
                if (rel == "or") {
                    asked  // keep each variant's reconciled commitment as-is
                } else {
                    val askedCommitted = (asked["committed_qty"] as? Number)?.toDouble() ?: (want * r)
                    val targetT = supply * r
                    if (askedCommitted > 1e-9 && targetT < askedCommitted - 1e-9) scaleSubtree(asked, targetT / askedCommitted) else asked
                }
            }
            val failed = supply <= 1e-6 && demandChildren.isNotEmpty()
            val nn = node + mapOf("quantity" to supply, "children" to newChildren) +
                (if (failed) mapOf("failed" to true) else emptyMap())
            return nn to supply
        }
        else -> return node to ((node["quantity"] as? Number)?.toDouble() ?: 0.0)
    }
}

/**
 * Derive the output work-order list directly from the final per-demand pegging trees —
 * one flat work order per pegging WO node, carrying the node's TOTAL quantity plus
 * `lot_count` (the per-node lot-explosion is metadata, not separate rows).
 *
 * This is the WO-consolidation half of the two-pass model expressed faithfully to the
 * mental model: Pass 1 builds the per-demand pegging SKELETON (BOM explosion + alternative
 * selection + quantities); the work orders are then the skeleton's WO nodes 1:1. Because
 * each emitted WO carries the node's own (demand_id, product, location, method, start_time)
 * and wo_group_id, it resolves back to exactly that pegging node — so the pegging panel,
 * supplies expansion, and predecessor/successor drill-down all work BY CONSTRUCTION. (The
 * old lot-level expansion produced ~5× more rows that didn't each map to a node; the
 * cross-demand batch (consolidateWorkOrdersByTiming) collapses further but severs that
 * per-demand linkage, so it is opt-in only.)
 */
private fun flattenPeggingToWorkOrders(
    peggingTrees: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): List<Map<String, Any?>> {
    val out = mutableListOf<Map<String, Any?>>()
    fun walk(node: Any?, demandId: Any?, members: List<String>?) {
        val n = node as? Map<*, *> ?: return
        // Skip failed=true subtrees entirely: these are debug snapshots of an AND-bottleneck
        // blocked branch whose first-pass inventory takes were rolled back — the make emits ~0
        // and the purchases/supplies beneath it never actually happen. Walking into them leaks
        // phantom orphan purchases into work_orders (inflating Committed qty and the WO count).
        // The pegging tree keeps these nodes for diagnostics; the WO list must not. (Soundness
        // likewise skips failed=true subtrees.)
        if (n["failed"] == true) return
        // Also skip a make that produced ~0: it built nothing, so anything beneath it is an
        // orphan (the under-consumption the soundness checker flags as R7d). Not real output.
        if (n["type"] == "work_order" && n["method"] == "make" &&
            ((n["quantity"] as? Number)?.toDouble() ?: 0.0) < 1e-6) return
        if (n["type"] == "work_order") {
            val pid = (n["product_id"] as? String)?.trim() ?: ""
            val lid = (n["location_id"] as? String)?.trim() ?: ""
            out.add(buildMap {
                put("product_id", pid)
                put("location_id", lid)
                put("quantity", n["quantity"])
                put("start_time", n["start_time"])
                put("end_time", n["end_time"])
                put("method", n["method"])
                put("location_source", n["location_source"])
                put("demand_id", demandId)
                put("prod_area", getProdArea(pid, lid, data))
                put("override_active", n["override_active"] ?: false)
                put("wo_group_id", n["wo_group_id"])
                put("lot_count", n["lot_count"])
                put("max_lot_size", n["max_lot_size"])
                put("wave_index", 0)
                if (members != null) put("consolidated_demand_ids", members)
            })
        }
        (n["children"] as? List<*>)?.forEach { walk(it, demandId, members) }
    }
    for (entry in peggingTrees) {
        @Suppress("UNCHECKED_CAST")
        val members = entry["consolidated_demand_ids"] as? List<String>
        walk(entry["tree"], entry["demand_id"], members)
    }
    return out
}

/**
 * Pass 2 — work-order timing/scheduling consolidation.
 *
 * Batches per-demand work orders that produce/buy/move the SAME
 * (product, location, method, source) and start within the same scheduling window
 * into fewer, larger orders (re-lotted by max_lot_size), cutting the WO count.
 * This is the scheduling counterpart to Pass-1 inventory allocation: Pass 1 decides
 * WHO gets scarce stock; Pass 2 decides HOW to batch the resulting production.
 *
 * Operates on the final work-order list only; per-demand pegging trees are left
 * intact for traceability. A merged order carries `consolidated=true`,
 * `demand_id=null`, and `wo_competing_demands` (the demands it serves), reusing the
 * existing consolidated-WO rendering. Singleton groups pass through unchanged, as do
 * failed-WO stubs and any WO without a parseable start.
 *
 * Merged timing: the batch is ready by the EARLIEST member end (so it serves the
 * soonest demand in the window), started one full lead earlier.
 *
 * Size: each group collapses to ONE batch order carrying the group's TOTAL quantity
 * plus `lot_count` = ceil(total / max_lot_size) — the max-lot-size detail is metadata,
 * not separate work orders. (Re-emitting per-lot WOs would not shrink the count, since
 * the inputs are already one lot each: the WO count is driven by total_qty/max_lot_size.)
 *
 * @param windowDays scheduling bucket width; <=0 collapses the whole horizon into one
 *   window. Conservative default is a small window (e.g. 7) so batching stays local.
 */
internal data class WoConsolidation(
    /** Merged work-order summary: consolidated batches + un-mergeable singletons + pass-throughs. */
    val consolidated: List<Map<String, Any?>>,
    /** The input per-demand WOs, each tagged with `consolidated_group_id` linking it to the ONE
     *  consolidated WO it belongs to. `consolidated` and `native` form a strict partition. */
    val native: List<Map<String, Any?>>,
)

internal data class ReadjustResult(
    val peggingTrees: List<Map<String, Any?>>,
    val consolidatedWos: List<Map<String, Any?>>,
)

internal fun consolidateWorkOrdersByTiming(
    workOrders: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    windowDays: Int,
): WoConsolidation {
    val consolidatedOut = mutableListOf<Map<String, Any?>>()
    val nativeOut = mutableListOf<Map<String, Any?>>()

    // INVARIANT: every native (input) WO is included in EXACTLY ONE consolidated WO. Each output WO
    // and its constituent native(s) share a `consolidated_group_id`, so the two lists form a strict
    // partition — no native is lost, and none is double-counted across the native/consolidated tabs.
    fun emitSingleton(wo: Map<String, Any?>) {
        val cgid = nextWoGroupId()
        consolidatedOut.add(wo + ("consolidated_group_id" to cgid))
        nativeOut.add(wo + ("consolidated_group_id" to cgid))
    }

    if (workOrders.size < 2) {
        workOrders.forEach { emitSingleton(it) }
        return WoConsolidation(consolidatedOut, nativeOut)
    }

    fun bucketOf(start: String?): Long {
        val d = parseDate(start) ?: return Long.MIN_VALUE
        return if (windowDays <= 0) 0L else Math.floorDiv(d.toEpochDay(), windowDays.toLong())
    }

    val passthrough = mutableListOf<Map<String, Any?>>()
    val groupable = mutableListOf<Map<String, Any?>>()
    for (wo in workOrders) {
        if (wo["failed"] == true || parseDate(wo["start_time"] as? String) == null) passthrough.add(wo)
        else groupable.add(wo)
    }
    passthrough.forEach { emitSingleton(it) }

    // Eligibility (WORK-ORDER-LIST summary only — never touches the per-demand pegging, so
    // constituents need NOT have identical sub-trees). qty = sum, start = min, end = max.
    //   • make / buy → keyed by (product, location, method, source, window): one batch per
    //     component, since each order produces/procures a single product.
    //   • MOVE → keyed by (source, target, prod_area, window): a physical move from one
    //     location to another in a window is a single shipment, but only components that
    //     share the same prod_area are consolidated together — this preserves prod_area
    //     attribution on the merged WO so pivot views align with the native breakdown.
    //     The merged move WO still carries a `move_components` manifest.
    val groups = groupable.groupBy {
        if (it["method"] == "move") listOf(
            "move",
            (it["location_source"] as? String)?.trim(),  // source
            (it["location_id"] as? String)?.trim(),       // target
            (it["prod_area"] as? String)?.trim(),          // same prod_area only
            bucketOf(it["start_time"] as? String),
        ) else listOf(
            (it["product_id"] as? String)?.trim(),
            (it["location_id"] as? String)?.trim(),
            it["method"] as? String,
            (it["location_source"] as? String)?.trim(),
            bucketOf(it["start_time"] as? String),
        )
    }

    for ((_, wos) in groups) {
        if (wos.size == 1) { emitSingleton(wos[0]); continue }
        val first = wos[0]
        val pid = (first["product_id"] as? String) ?: ""
        val lid = (first["location_id"] as? String) ?: ""
        val method = (first["method"] as? String) ?: ""
        val totalQty = wos.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        if (totalQty <= 1e-9) { wos.forEach { emitSingleton(it) }; continue }
        // This batch's id — shared with every constituent native via `consolidated_group_id`.
        val cgid = nextWoGroupId()

        // Merged span = [min(start), max(end)] across the constituents.
        val mergedStart = wos.mapNotNull { parseDate(it["start_time"] as? String) }.minOrNull()
        val mergedEnd = wos.mapNotNull { parseDate(it["end_time"] as? String) }.maxOrNull()
        val demands = wos.mapNotNull { it["demand_id"] as? String }.filter { it.isNotBlank() }.distinct()
        // Per-demand split: drives the union predecessor/successor (↓/↑) and the Requested column
        // in the UI — woRowDemandIds reads wo_consolidation_split_details[].demand_id.
        val splitDetails = wos.filter { (it["demand_id"] as? String)?.isNotBlank() == true }
            .groupBy { it["demand_id"] as String }
            .map { (d, ws) -> mapOf("demand_id" to d, "allocated_qty" to ws.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }) }
        val overrideActive = wos.any { it["override_active"] == true }

        if (method == "move") {
            // Same-prod_area mixed-product shipment: source → target in this window, carrying
            // DIFFERENT components together, but all belonging to the same prod_area. There is
            // no single product_id — the cargo is the `move_components` manifest (one entry per
            // product). lot_count = 1 (one shipment; a move models reachability, with no
            // per-product lotting). Pegging is untouched; per-product move nodes stay in each
            // demand's tree, so soundness/drill-down by product still resolve there.
            val moveComponents = wos.groupBy { (it["product_id"] as? String)?.trim() ?: "" }
                .map { (p, ws) ->
                    mapOf(
                        "product_id" to p,
                        "quantity" to ws.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 },
                        "demand_ids" to ws.mapNotNull { it["demand_id"] as? String }.filter { it.isNotBlank() }.distinct(),
                    )
                }
                .sortedBy { (it["product_id"] as? String) ?: "" }
            consolidatedOut.add(mapOf(
                "product_id" to null,                          // mixed cargo within same prod_area
                "location_id" to lid,                          // target
                "quantity" to totalQty,                        // exact sum of constituents → conserved
                "start_time" to formatDate(mergedStart),
                "end_time" to formatDate(mergedEnd),
                "method" to "move",
                "location_source" to first["location_source"], // source
                "demand_id" to null,
                "prod_area" to first["prod_area"],             // shared prod_area of all constituents
                "override_active" to overrideActive,
                "wo_group_id" to cgid,
                "consolidated_group_id" to cgid,
                "wave_index" to 0,
                "lot_count" to 1,
                "consolidated" to true,
                "wo_competing_demands" to demands,
                "consolidation_split_details" to splitDetails,
                "consolidated_demand_ids" to demands,
                "move_components" to moveComponents,
                "wo_window_start" to formatDate(mergedStart),
                "wo_window_end" to formatDate(wos.mapNotNull { parseDate(it["start_time"] as? String) }.maxOrNull()),
                "wo_consolidation_total_planned" to totalQty,
            ))
            wos.forEach { nativeOut.add(it + ("consolidated_group_id" to cgid)) }
            continue
        }

        val lotSize = (maxLotSize(pid, lid, data)?.takeIf { it > 0 } ?: totalQty).coerceAtLeast(1e-9)
        val lotCount = Math.ceil(totalQty / lotSize).toInt().coerceAtLeast(1)

        // One batch order for the whole group. The max-lot-size detail is carried as
        // `lot_count` metadata rather than exploding back into per-lot work orders —
        // that is what actually shrinks the work-order count (inputs are already one
        // lot each, so re-lotting would reproduce them).
        consolidatedOut.add(mapOf(
            "product_id" to pid,
            "location_id" to lid,
            "quantity" to totalQty,                            // exact sum of constituents → conserved
            "start_time" to formatDate(mergedStart),
            "end_time" to formatDate(mergedEnd),
            "method" to method,
            "location_source" to first["location_source"],
            "demand_id" to null,
            "prod_area" to first["prod_area"],
            "override_active" to overrideActive,
            "wo_group_id" to cgid,
            "consolidated_group_id" to cgid,
            "wave_index" to 0,
            "lot_count" to lotCount,
            "max_lot_size" to lotSize,
            "consolidated" to true,
            "wo_competing_demands" to demands,
            // Per-demand split breakdown (INTERNAL key name — the API enrichment in Allocate.kt
            // maps consolidation_split_details → wo_consolidation_split_details). The UI reads it
            // to derive the union predecessor/successor (each demand's pegging keys feed the ↓/↑
            // relation BFS) and to set Requested = Committed. This keeps the merged order linked
            // to the (intact) per-demand pegging instead of becoming a dead-end.
            "consolidation_split_details" to splitDetails,
            // The constituent demands, so a batched order remains traceable: the pegging
            // endpoint and the supplies/Requested maps resolve a consolidated WO back to each
            // of these demands' per-demand pegging nodes for (product, location, method).
            "consolidated_demand_ids" to demands,
            // The original start-window the constituents fell in. The pegging endpoint filters
            // the per-demand nodes to this range so the resolved/aggregated node matches THIS
            // batch (not every window of those demands for the same component).
            "wo_window_start" to formatDate(wos.mapNotNull { parseDate(it["start_time"] as? String) }.minOrNull()),
            "wo_window_end" to formatDate(wos.mapNotNull { parseDate(it["start_time"] as? String) }.maxOrNull()),
            "wo_consolidation_total_planned" to totalQty,
        ))
        wos.forEach { nativeOut.add(it + ("consolidated_group_id" to cgid)) }
    }
    return WoConsolidation(consolidatedOut, nativeOut)
}

private data class ConsolidatedWoState(var startTime: LocalDate, val leadTime: Long) {
    val endTime: LocalDate get() = startTime.plusDays(leadTime)
}

/**
 * Pass 2b — iterative timing readjustment.
 *
 * After cross-demand batching, a consolidated WO's start_time = min(native starts). If any
 * constituent demand's WO actually starts LATER (e.g. because its upstream BOM is delayed),
 * the batch cannot begin until that later date. This function propagates those constraints:
 *
 * invariant: lead_time = consolidated_wo.end_time − consolidated_wo.start_time is preserved.
 * When start_time is pushed, end_time = new_start + lead_time.
 *
 * Traversal: bottom-up within each demand's pegging tree so child WO delays cascade to
 * parent WOs in the same pass. The outer do-while iterates until no consolidated WO
 * changes (convergence guaranteed by BOM depth).
 */
internal fun readjustConsolidatedWoTiming(
    peggingTrees: List<Map<String, Any?>>,
    woConsolidation: WoConsolidation,
): ReadjustResult {
    val consolidatedState = mutableMapOf<String, ConsolidatedWoState>()
    for (c in woConsolidation.consolidated) {
        val cgid  = c["consolidated_group_id"] as? String ?: continue
        val start = parseDate(c["start_time"] as? String) ?: continue
        val end   = parseDate(c["end_time"]   as? String) ?: continue
        consolidatedState[cgid] = ConsolidatedWoState(start, end.toEpochDay() - start.toEpochDay())
    }
    val woGidToCgid = mutableMapOf<String, String>()
    for (n in woConsolidation.native) {
        val woGid = n["wo_group_id"] as? String ?: continue
        val cgid  = n["consolidated_group_id"] as? String ?: continue
        woGidToCgid[woGid] = cgid
    }

    var workingTrees = peggingTrees
    var changed = false
    val updatedEnd = mutableMapOf<String, LocalDate>()

    @Suppress("UNCHECKED_CAST")
    fun walkNode(node: Map<String, Any?>): Map<String, Any?> {
        if (node["type"] == "work_order" && node["failed"] == true) return node

        val origChildren = node["children"] as? List<Map<String, Any?>>
        val newChildren  = origChildren?.map { walkNode(it) }
        val origChildrenNN = origChildren ?: emptyList()
        val childrenChanged = newChildren != null &&
            newChildren.indices.any { i -> newChildren[i] !== origChildrenNN[i] }

        if (node["type"] != "work_order") {
            return if (childrenChanged) node.toMutableMap().also { it["children"] = newChildren }
                   else node
        }

        val woGid = node["wo_group_id"] as? String
        val cgid  = if (woGid != null) woGidToCgid[woGid] else null
        val state = if (cgid != null) consolidatedState[cgid] else null

        val directWoChildren = newChildren?.filter { it["type"] == "work_order" && it["failed"] != true }
        val effectiveStart: LocalDate? = if (!directWoChildren.isNullOrEmpty()) {
            directWoChildren.mapNotNull { child ->
                val cGid = child["wo_group_id"] as? String
                if (cGid != null) updatedEnd[cGid] else parseDate(child["end_time"] as? String)
            }.maxOrNull()
        } else {
            parseDate(node["start_time"] as? String)
        }

        if (state != null && effectiveStart != null && effectiveStart > state.startTime) {
            state.startTime = effectiveStart   // endTime auto-updates: startTime + leadTime
            changed = true
        }

        val newStart = state?.startTime
        val newEnd   = state?.endTime
        if (woGid != null && newEnd != null) updatedEnd[woGid] = newEnd

        val startChanged = newStart != null && formatDate(newStart) != node["start_time"]
        val endChanged   = newEnd   != null && formatDate(newEnd)   != node["end_time"]

        return if (startChanged || endChanged || childrenChanged) {
            node.toMutableMap().also { m ->
                if (startChanged)    m["start_time"] = formatDate(newStart)
                if (endChanged)      m["end_time"]   = formatDate(newEnd)
                if (childrenChanged) m["children"]   = newChildren
            }
        } else node
    }

    var iter = 0
    do {
        changed = false
        updatedEnd.clear()
        iter++
        @Suppress("UNCHECKED_CAST")
        workingTrees = workingTrees.map { entry ->
            val tree    = entry["tree"] as? Map<String, Any?> ?: return@map entry
            val newTree = walkNode(tree)
            if (newTree === tree) entry
            else entry.toMutableMap().also { it["tree"] = newTree }
        }
        log.info("readjustConsolidatedWoTiming iter={} changed={} trees={}", iter, changed, workingTrees.size)
    } while (changed)

    val adjustedConsolidated = woConsolidation.consolidated.map { c ->
        val cgid     = c["consolidated_group_id"] as? String ?: return@map c
        val state    = consolidatedState[cgid] ?: return@map c
        val newStart = formatDate(state.startTime)
        val newEnd   = formatDate(state.endTime)
        if (newStart == c["start_time"] && newEnd == c["end_time"]) c
        else c.toMutableMap().also { it["start_time"] = newStart; it["end_time"] = newEnd }
    }
    return ReadjustResult(workingTrees, adjustedConsolidated)
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
    data: Map<String, List<Map<String, Any?>>>? = null,
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

    // Wave structure for make WOs under the operation override. cap >= 1 means
    // the WO compresses its lots into ceil(lot_count / cap) sequential waves;
    // outside the override cap=1 and wave_count == lot_count (today's behavior).
    val cap = if (methodType == "make" && data != null)
        OperationLookup.parallelismCap(productId, productionLocation, data).coerceAtLeast(1)
    else 1
    if (cap > 1) put("parallelism_cap", cap)
    if (lotCount > 0) {
        val waveCount = kotlin.math.ceil(lotCount.toDouble() / cap.toDouble()).toInt()
        if (waveCount > 0) put("wave_count", waveCount)
    }

    // For make WOs with an applicable operation, prepend an operation node
    // (with resource children) so pegging trees expose the granular UPH/BOR
    // model alongside the BOM children. Falls through silently when no
    // operation matches — applicability gate is identical to leadDaysForMethod.
    val opChildren = if (methodType == "make" && data != null)
        buildOperationChildren(productId, productionLocation, qty, data)
    else emptyList()
    put("children", opChildren + woChildren)

    if (woGroupId != null) put("wo_group_id", woGroupId)
    // Marker for the AND-bottleneck blocked branch: this WO is a debug snapshot
    // of "what would have happened" — its subtree shows first-pass takes that
    // were rolled back by inventory.clear()/inventory.addAll(snap) at the
    // outer level. Soundness skips the entire subtree under failed=true to
    // tolerate the broken/partial pegging it carries.
    //
    // Also mark failed when the WO produced ~0 yet still carries BOM children: it built
    // nothing, so everything beneath it is a rolled-back orphan (the under-consumption the
    // soundness checker flags as R7d). Most blocked branches are tagged at the AND-min site,
    // but some 0-qty WOs (e.g. a blocked move) slip past it; this is the central catch-all so
    // the failed contract is uniform (soundness + the WO flatten both skip these).
    if (failed || (qty <= 1e-6 && woChildren.isNotEmpty())) put("failed", true)
}

/**
 * For a make WO at (product, location, qty), look up the matching operation
 * (via productlocation.prod_area) and emit a single operation node whose
 * children are the resource nodes from the operation's BOR. Returns an empty
 * list when the override doesn't apply (no productlocation row, no operation,
 * empty BOR, or any required resource missing at this location) — same gate
 * as OperationLookup.effectiveLeadDays.
 */
private fun buildOperationChildren(
    productId: String,
    locationId: String,
    qty: Double,
    data: Map<String, List<Map<String, Any?>>>,
): List<Map<String, Any?>> {
    val prodArea = (data["productlocation"] ?: return emptyList())
        .firstOrNull {
            (it["product_id"] as? String)?.trim() == productId &&
            (it["location_id"] as? String)?.trim() == locationId
        }
        ?.get("prod_area")?.toString()?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return emptyList()

    val op = (data["operation"] ?: return emptyList())
        .firstOrNull { (it["prod_area"] as? String)?.trim() == prodArea }
        ?: return emptyList()

    val borId = (op["bor_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()

    val borRows = (data["bor"] ?: emptyList())
        .filter { (it["bor_id"] as? String)?.trim() == borId }
    if (borRows.isEmpty()) return emptyList()

    val resourceRows = data["resource"] ?: emptyList()
    val resourceChildren = borRows.mapNotNull { br ->
        val rid = (br["resource_id"] as? String)?.trim() ?: return@mapNotNull null
        val resRow = resourceRows.firstOrNull {
            (it["resource_id"] as? String)?.trim() == rid &&
            (it["location_id"] as? String)?.trim() == locationId
        } ?: return@mapNotNull null  // applicability fails if any resource missing
        mapOf(
            "type" to "resource",
            "resource_id" to rid,
            "location_id" to locationId,
            "resource_rate" to ((br["resource_rate"] as? Number)?.toDouble() ?: 0.0),
            "size" to ((resRow["size"] as? Number)?.toDouble() ?: 0.0),
            "children" to emptyList<Map<String, Any?>>(),
        )
    }
    if (resourceChildren.size != borRows.size) return emptyList()

    // Concurrency cap = min(floor(size/rate)) across BOR. Pegged onto the
    // operation node so the UI can render "up to N parallel lots" and the
    // user can reason about how the WO span was compressed.
    val parallelismCap = OperationLookup.parallelismCap(productId, locationId, data)

    return listOf(mapOf(
        "type" to "operation",
        "operation_id" to (op["operation_id"] as? String)?.trim(),
        "prod_area" to prodArea,
        "product_id" to productId,
        "location_id" to locationId,
        "quantity" to roundQty(qty),
        "uph" to (op["uph"] as? Number)?.toDouble(),
        "yield_factor" to (op["yield_factor"] as? Number)?.toDouble(),
        "process_time" to (op["process_time"] as? Number)?.toInt(),
        "pre_process_time" to (op["pre_process_time"] as? Number)?.toInt(),
        "post_process_time" to (op["post_process_time"] as? Number)?.toInt(),
        "parallelism_cap" to parallelismCap,
        "children" to resourceChildren,
    ))
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
    demands: List<Map<String, Any?>> = emptyList(),
): List<Map<String, Any?>> {
    val remainingBySupply = mutableMapOf<String, Double>()
    for (s in supplies) {
        val sid = s["supply_id"] as? String ?: continue
        val qty = (s["qty"] as? Number)?.toDouble() ?: 0.0
        remainingBySupply[sid] = (remainingBySupply[sid] ?: 0.0) + qty
    }

    val result = mutableListOf<MutableMap<String, Any?>>()

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
                result.add(mutableMapOf(
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
                            result.add(mutableMapOf(
                                "supply_id"    to supplyId,
                                "demand_id"    to did,
                                "qty_consumed" to share,
                            ))
                        }
                    }
                } else {
                    result.add(mutableMapOf(
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

    // Post-process: compute qty_allocated = demand's proportional entitlement from each lot.
    // Each physical lot distributes its full initial qty to competing demands proportionally by
    // demand quantity — allocation is lot-local and independent of whether the plan over-supplies
    // globally. qty_consumed records what was actually drawn; qty_allocated records the entitlement.
    // Demands that consumed 0 from a lot (because other lots served them) are excluded here
    // (they appear in no pegging tree), so allocations are normalised among consuming demands only.
    // Mutates result records in-place (MutableMap) to avoid allocating a new map per record.
    val demandQtyMap: Map<String, Double> = demands
        .mapNotNull { d -> (d["demand_id"] as? String)?.let { id -> id to ((d["quantity"] as? Number)?.toDouble() ?: 0.0) } }
        .toMap()
    // lot initial qty lookup (physical lots only — synthetic/consolidated buckets have no entry)
    val lotInitialQty: Map<String, Double> = supplies
        .mapNotNull { s -> (s["supply_id"] as? String)?.let { id -> id to ((s["qty"] as? Number)?.toDouble() ?: 0.0) } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, qtys) -> qtys.sum() }

    // Build per-lot weight totals in one pass so each lot's totalWeight is computed once, not per-record
    data class LotStats(val lotQty: Double, var totalWeight: Double = 0.0)
    val lotStats = mutableMapOf<String, LotStats>()
    for (rec in result) {
        val sid = rec["supply_id"] as? String ?: continue
        val lotQty = lotInitialQty[sid] ?: continue  // skip synthetic buckets
        val stats = lotStats.getOrPut(sid) { LotStats(lotQty) }
        val did = rec["demand_id"] as? String
        val w = (if (did != null) demandQtyMap[did] else null)
            ?: (rec["qty_consumed"] as? Number)?.toDouble()
            ?: 0.0
        stats.totalWeight += w
    }
    // Second pass: write qty_allocated in-place
    for (rec in result) {
        val sid = rec["supply_id"] as? String
        val stats = if (sid != null) lotStats[sid] else null
        val qtyAllocated: Double = if (stats != null && stats.totalWeight > 1e-12) {
            val did = rec["demand_id"] as? String
            val w = (if (did != null) demandQtyMap[did] else null)
                ?: (rec["qty_consumed"] as? Number)?.toDouble()
                ?: 0.0
            stats.lotQty * w / stats.totalWeight
        } else {
            (rec["qty_consumed"] as? Number)?.toDouble() ?: 0.0
        }
        rec["qty_allocated"] = qtyAllocated
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
    val producedByComponent: Map<String, Double> = emptyMap(),
    val releasedByComponent: Map<String, Double> = emptyMap(),
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
    // ── [MAP] Request map: raw paths per supply column ───────────────────────────
    // Groups every resolution path by (supplyPid, supplyLid); each entry shows which
    // demands reach that supply and at what quantity — this is the request map.
    run {
        val byColumn = graph.paths.groupBy { Triple(it.leaf.productId, it.leaf.locationId, it.leaf.supplyId) }
        val contested = byColumn.filter { (_, ps) -> ps.map { it.demandId }.toSet().size > 1 }
        log.info("[map][request] {} supply lots total, {} contested (>1 demand)",
            byColumn.size, contested.size)
        contested.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }, { it.key.third ?: "" }))
            .forEach { (col, paths) ->
                val byDemand = paths.groupBy { it.demandId }
                log.info("[map][request] supply={}@{} lot={} demands={}  qty-by-demand={}",
                    col.first, col.second, col.third ?: "?", byDemand.size,
                    byDemand.entries.sortedByDescending { e -> e.value.sumOf { it.leafQuantity() } }
                        .take(10)
                        .joinToString { (d, ps) -> "$d:${ps.sumOf { it.leafQuantity() }.toLong()}" })
            }
    }

    // Pass 1 (inventory allocation) ALWAYS pools universally — bucket 0 — so scarce
    // on-hand stock is shared fairly across every competing demand regardless of due
    // date. (The UI "Bucket (days)" controls Pass 2 / WO batching, a separate schedule.)
    val baseMerged = mergeGroups(graph, periodDays = 0)

    // ── [MAP] Demand map: per-supply groups used by consolidation ────────────────
    // Identical to request map — each group is the set of demands competing for that supply.
    run {
        val contested = baseMerged.filter { it.members.map { m -> m.demandId }.toSet().size > 1 }
        log.info("[map][demand] {} supply lots total, {} contested (>1 demand)",
            baseMerged.size, contested.size)
        contested
            .sortedWith(compareBy({ it.leafPid }, { it.leafLid }, { it.supplyId ?: "" }))
            .forEach { g ->
                log.info("[map][demand] supply={}@{} lot={} totalQty={} demands={}  top-demands={}",
                    g.leafPid, g.leafLid, g.supplyId ?: "?", g.totalQty.toLong(),
                    g.members.map { it.demandId }.toSet().size,
                    g.members.sortedByDescending { it.qty }.take(10)
                        .joinToString { m -> "${m.demandId}:${m.qty.toLong()}" })
            }
    }

    var memberCaps: Map<Pair<String, String>, Map<Any?, Double>> = emptyMap()
    var lastConsolidatedWOs: List<Map<String, Any?>> = emptyList()
    var lastConsolidatedPegging: List<Map<String, Any?>> = emptyList()
    var lastCommit = LegacyCommitResult(emptyList(), emptyList(), emptyList())
    var lastProducedByComponent: Map<String, Double> = emptyMap()
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

    // Tracks over-production across iterations for stall detection. Caps are
    // monotone non-increasing, so the residual either keeps shrinking or hits a
    // stable floor; when it stops shrinking the controller is at a fixed point and
    // further iterations only reproduce the same residual — stop early then.
    var prevTotalOver = Double.POSITIVE_INFINITY

    // Pass-1 allocation passes. Default 1 (single allocation pass) — fastest, but a
    // demand can be capped above what it draws, orphaning the slack inventory (fine
    // when supply is ample). Set consolidation.max_iterations > 1 to re-enable the
    // over-claim/compensate/converge loop, which reclaims orphaned allocations each
    // pass for tighter inventory utilization (important when supply-constrained) at
    // higher runtime. Hard-ceiling at MAX_PLANNING_ITERATIONS.
    val maxIters = consolidationConfig.maxIterations.coerceIn(1, MAX_PLANNING_ITERATIONS)

    for (iter in 0 until maxIters) {
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

        // ── [MAP] Allocation map: per-demand per-supply allocations ─────────────────
        // For iter=0 only — shows what each demand was promised from each supply.
        if (iter == 0) {
            // Group by supply column → list of (demand, qty)
            val bySupply = mutableMapOf<String, MutableList<Pair<Any?, Double>>>()
            for ((demandId, allocs) in consResult.allocation) {
                for ((componentKey, qty) in allocs) {
                    if (qty > 1e-9) bySupply.getOrPut(componentKey) { mutableListOf() }.add(demandId to qty)
                }
            }
            val contested = bySupply.filter { it.value.size > 1 }
            log.info("[map][allocation] iter=0: {} supply columns allocated, {} contested (>1 demand)",
                bySupply.size, contested.size)
            contested.entries
                .sortedBy { it.key }
                .forEach { (col, entries) ->
                    val total = entries.sumOf { it.second }
                    log.info("[map][allocation]   supply={} total={} demands={}  breakdown={}",
                        col, total.toLong(), entries.size,
                        entries.sortedByDescending { it.second }.take(10)
                            .joinToString { (d, q) -> "$d:${q.toLong()}" })
                }
        }

        // The output components Pass-1 allocated (merged-leaf level). Deep BOM-child supplies it
        // consumed inside its own plan() stay depleted in both paths — only these outputs are
        // re-supplied to per-demand planning.
        val producedByComponent = mutableMapOf<String, Double>()
        for ((_, componentAllocs) in consResult.allocation) {
            for ((componentKey, qty) in componentAllocs) {
                if (qty <= 1e-12) continue
                producedByComponent[componentKey] = (producedByComponent[componentKey] ?: 0.0) + qty
            }
        }
        lastProducedByComponent = producedByComponent
        if (consolidationConfig.realPegging) {
            // CARRIER = REAL supplies. Mirror the bucket path EXACTLY — re-supply only the allocated
            // OUTPUT (pid,lid)s (deep raws stay depleted) — but restore their REAL pre-consolidation
            // lots (real supply_id) instead of a synthetic bucket. Per-demand planning then consumes
            // real lots capped by `budgets` → same allocation, real pegging. (Restoring the full
            // inventory instead would let demands re-consume deep raws and drift the allocation.)
            val allocatedPLs = producedByComponent.keys.map {
                val p = it.split("|")
                (p.getOrElse(0) { "" }.trim()) to (p.getOrElse(1) { "" }.trim())
            }.toHashSet()
            fun plOf(m: Map<String, Any?>) =
                ((m["product_id"] as? String)?.trim() ?: "") to ((m["location_id"] as? String)?.trim() ?: "")
            inventory.removeAll { plOf(it) in allocatedPLs }
            for (b in initialInventory) if (plOf(b) in allocatedPLs) inventory.add(b.toMutableMap())
        } else {
            // Build a lookup of supply_date by supply_id from the pre-consolidation inventory so
            // synthetic buckets preserve the original lot's availability date, maintaining
            // FIFO ordering (on-hand inventory consumed before future work orders).
            // supply_id uses "consolidated_pid_lid" (NOT the real lot id) so that demand pegging
            // in plan() references a distinct key and doesn't double-count with the real lot's
            // consolidation pegging tree.
            val initialSupplyDates: Map<String, String?> = initialInventory
                .mapNotNull { b -> (b["supply_id"] as? String)?.let { sid -> sid to b["supply_date"] as? String } }
                .toMap()
            for ((componentKey, totalQty) in producedByComponent) {
                if (totalQty <= 1e-12) continue
                val parts = componentKey.split("|")
                val pid = parts.getOrElse(0) { "" }
                val lid = parts.getOrElse(1) { "" }
                val originalSupplyId = parts.getOrElse(2) { "" }.ifBlank { null }
                val supplyDate = if (originalSupplyId != null) {
                    initialSupplyDates[originalSupplyId]
                } else {
                    consResult.consolidatedWOs
                        .firstOrNull { it["product_id"] == pid && it["location_id"] == lid }
                        ?.get("end_time") as? String
                }
                inventory.add(mutableMapOf(
                    "product_id"  to pid,
                    "location_id" to lid,
                    "qty"         to totalQty,
                    "supply_date" to supplyDate,
                    "supply_id"   to "consolidated_${pid}_${lid}",
                ))
            }
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

        // ── [MAP] Budget map: per-demand caps entering plan() ────────────────────
        // For iter=0 only. Each entry = what plan() is allowed to consume from that supply.
        if (iter == 0) {
            val bySupply = mutableMapOf<String, MutableList<Pair<Any?, Double>>>()
            for ((demandId, caps) in budgets) {
                for ((supplyKey, cap) in caps) {
                    if (cap > 1e-9) bySupply.getOrPut(supplyKey) { mutableListOf() }.add(demandId to cap)
                }
            }
            val contested = bySupply.filter { it.value.size > 1 }
            log.info("[map][budget] iter=0: {} supplies with caps, {} contested",
                bySupply.size, contested.size)
            contested.entries.sortedBy { it.key }.forEach { (supplyKey, entries) ->
                log.info("[map][budget]   supply={} capped-demands={}  caps={}",
                    supplyKey, entries.size,
                    entries.sortedByDescending { it.second }.take(10)
                        .joinToString { (d, q) -> "$d:${q.toLong()}" })
            }
        }

        // Phase 3 — forward progressCallback live, tagged with iteration metadata
        // so the UI can show "iter k/N" alongside per-demand progress. The bar
        // restarts each iter (0→N) which is the honest signal that planning is
        // iterating; convergence in iter 0 shows a single smooth 0→100%.
        val isLastIter = iter == maxIters - 1
        val iterCb: ((Map<String, Any?>) -> Unit)? = progressCallback?.let { cb ->
            { payload ->
                cb(payload + mapOf(
                    "iteration" to iter + 1,
                    "iterations_max" to maxIters,
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

        // ── [MAP] Budget consumption: drawn vs cap after plan() for each demand ──
        if (iter == 0) {
            val bySupply = mutableMapOf<String, MutableList<Triple<Any?, Double, Double>>>() // demand, cap, drawn
            for ((demandId, caps) in initialBudgets) {
                val remaining = budgets[demandId] ?: emptyMap()
                for ((supplyKey, cap) in caps) {
                    if (cap < 1e-9) continue
                    val drawn = cap - (remaining[supplyKey] ?: 0.0)
                    bySupply.getOrPut(supplyKey) { mutableListOf() }.add(Triple(demandId, cap, drawn.coerceAtLeast(0.0)))
                }
            }
            bySupply.filter { it.value.size > 1 }.entries.sortedBy { it.key }.forEach { (supplyKey, entries) ->
                val totalCap   = entries.sumOf { it.second }
                val totalDrawn = entries.sumOf { it.third }
                log.info("[map][consumed] supply={} cap={} drawn={} unused={}  breakdown={}",
                    supplyKey, totalCap.toLong(), totalDrawn.toLong(), (totalCap - totalDrawn).toLong(),
                    entries.sortedByDescending { it.third }.take(10)
                        .joinToString { (d, c, w) -> "$d:drawn=${w.toLong()}/cap=${c.toLong()}" })
            }
        }

        // Over-production: any leftover budget at the merged leaf.
        val totalOver = budgets.values.sumOf { db ->
            db.values.sumOf { q -> if (q > 1e-9) q else 0.0 }
        }

        lastConsolidatedWOs = consResult.consolidatedWOs
        lastConsolidatedPegging = consResult.consolidatedPegging
        lastCommit = commit

        // Component release: budget residuals of fully-unserved demands at this iteration.
        // These units were allocated in Phase 1 Step 2 but never drawn in Step 3 (the demand
        // failed for a non-C reason — AND-bottleneck, missing raw material, etc. — and plan()
        // restored its inventory + budget via invCopy rollback). Surfaced as a hint to the
        // checker and in the plan output; with max_iterations > 1 the convergence loop would
        // reclaim this slack naturally.
        val releasedByComponent = mutableMapOf<String, Double>()
        val iterCommittedQtyByDemand = mutableMapOf<Any?, Double>()
        for (row in commit.committedDemands) {
            if (isHardPlanningFailure(row["commit_reason"] as? String)) continue
            val did = row["demand_id"] ?: continue
            iterCommittedQtyByDemand[did] = (iterCommittedQtyByDemand[did] ?: 0.0) +
                ((row["quantity"] as? Number)?.toDouble() ?: 0.0)
        }
        for (d in demands) {
            val did = d["demand_id"] ?: continue
            if ((iterCommittedQtyByDemand[did] ?: 0.0) > 1e-9) continue  // served
            val demBudget = budgets[did] ?: continue
            for ((componentKey, residual) in demBudget) {
                if (residual <= 1e-9) continue
                releasedByComponent[componentKey] =
                    (releasedByComponent[componentKey] ?: 0.0) + residual
            }
        }
        if (releasedByComponent.isNotEmpty()) {
            log.warn(
                "v2 iter {}: {} component(s) with orphaned allocations ({} qty) — " +
                "unserved demand(s) held budget they never drew; consider max_iterations > 1",
                iter + 1, releasedByComponent.size,
                "%.2f".format(releasedByComponent.values.sum()),
            )
        }

        if (totalOver <= 1e-9) {
            log.info("v2 iter {}: converged (no over-production)", iter + 1)
            return V2IteratedResult(
                consResult.consolidatedWOs, consResult.consolidatedPegging, commit,
                iter + 1, true, producedByComponent, releasedByComponent,
            )
        }

        if (isLastIter) {
            // Single allocation pass complete. Apply the 4a one-shot trim as a safety net.
            val finalWOs = consResult.consolidatedWOs.toMutableList()
            val (n, q) = reconcileOverProduction(finalWOs, inventory, budgets)
            // `totalOver` is leftover allocation BUDGET, not real over-produced WOs: under
            // inventory-only consolidation there are no consolidated production WOs to trim
            // (q≈0), so a large residual here is phantom and does not affect the plan — it's
            // the merge's variant-path provisioning that the per-demand commit didn't draw on.
            // Only warn if the trim actually removed quantity (a genuine over-size).
            val msg = "v2 single allocation pass: {} qty leftover allocation budget — " +
                "reconcile trimmed {} component(s), {} qty"
            if (q > 1e-6) log.warn(msg, "%.2f".format(totalOver), n, "%.2f".format(q))
            else log.info(msg + " (phantom budget; no production over-size)", "%.2f".format(totalOver), n, "%.2f".format(q))
            return V2IteratedResult(
                finalWOs.toList(), consResult.consolidatedPegging, commit,
                iter + 1, false, producedByComponent, releasedByComponent,
            )
        }

        // Stall detection: the residual is monotone non-increasing (caps only drop),
        // so if it didn't shrink at all this iteration the controller has reached a
        // stable fixed point — the leftover (typically a sub-lot-size remnant at a
        // stocked component) can't be trimmed by iterating further; every remaining
        // pass would reproduce it. Stop now and apply the single-pass safety trim
        // instead of burning the rest of the iteration budget (and the minutes it
        // costs on large runs).
        if (totalOver >= prevTotalOver - 1e-6) {
            val finalWOs = consResult.consolidatedWOs.toMutableList()
            val (n, q) = reconcileOverProduction(finalWOs, inventory, budgets)
            log.info(
                "v2 iter {}: over-production stalled at {} qty (no improvement vs prev {}) — " +
                "stable fixed point; stopping early, fallback trimmed {} component(s), {} qty",
                iter + 1, "%.2f".format(totalOver), "%.2f".format(prevTotalOver), n, "%.2f".format(q),
            )
            return V2IteratedResult(
                finalWOs.toList(), consResult.consolidatedPegging, commit,
                iter + 1, false, producedByComponent, releasedByComponent,
            )
        }
        prevTotalOver = totalOver

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
            val consumedByPidLid = mutableMapOf<Pair<String, String>, Double>()
            for ((componentKey, remaining) in demandBudget) {
                val initQty = initial[componentKey] ?: continue
                val consumed = (initQty - remaining).coerceAtLeast(0.0)
                val parts = componentKey.split("|")
                val pid = parts.getOrElse(0) { "" }
                val lid = parts.getOrElse(1) { "" }
                consumedByPidLid.merge(Pair(pid, lid), consumed, Double::plus)
                if (remaining > 1e-9) {
                    overByComponent.merge(componentKey, remaining, Double::plus)
                }
            }
            for ((key, totalConsumed) in consumedByPidLid) {
                val prevCap = prevCaps[key]?.get(demandId) ?: Double.POSITIVE_INFINITY
                newCaps.getOrPut(key) { mutableMapOf() }[demandId] = min(prevCap, totalConsumed)
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
    return V2IteratedResult(lastConsolidatedWOs, lastConsolidatedPegging, lastCommit, MAX_PLANNING_ITERATIONS, false, lastProducedByComponent)
}

/**
 * Phase 3 (legacy): plan each user demand against the inventory left by
 * phase 2 (real supplies + tagged synthetic buckets). Emits committed rows,
 * work orders, and per-demand pegging trees.
 */
internal fun legacyCommit(
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
    /**
     * Optional per-demand per-node achievable quantity caps from
     * [computeAchievableQtyMaps].  Keyed by demand_id → (pid to lid) → achievable qty.
     * When present, the per-demand slice is threaded into [plan] as [nodeQtyCaps]
     * so every node in the BOM tree is capped before supply draws are attempted,
     * preventing WO emission for quantities the budget tree can never cover.
     */
    achievableQtyMaps: Map<Any?, Map<Pair<String, String>, Double>>? = null,
    /**
     * Optional per-demand BOM blueprint from [computePlanBlueprint].
     * When present, the per-demand blueprint slice is passed to [plan] so method
     * selection is bypassed for nodes that have a pre-selected method in the sketch.
     */
    planBlueprint: PlanBlueprint? = null,
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

    // Build hot-path indexes once for all demands. These eliminate the O(N) linear
    // scans in getMethods (11K rows), variantsForMake (14K rows), and
    // consumeFromInventory (4K+ rows) that dominate runtime on large plans.
    // Passed via JVM subtyping — no signature changes to plan() or helpers.
    val planData: Map<String, List<Map<String, Any?>>> =
        if (data !is PlanData) PlanData(data, buildDataIndex(data)) else data
    val indexedInventory: MutableList<MutableMap<String, Any?>> =
        if (inventory !is IndexedInventory) IndexedInventory(inventory, buildInventoryIndex(inventory)) else inventory
    log.info("legacyCommit: built indexes (supply={} make={} move={} bom={})",
        inventory.size,
        (data["method_make"] ?: emptyList()).size,
        (data["method_move"] ?: emptyList()).size,
        (data["bom"] ?: emptyList()).size,
    )

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
            d, indexedInventory, planData, reqDt,
            config = config, preferDemandId = prefId, overrideIndex = overrideIndex,
            budget = demandBudget,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
            initialBudget = iter0Allocation?.get(demandId),
            nodeQtyCaps = achievableQtyMaps?.get(demandId),
            demandBlueprint = planBlueprint?.get(demandId),
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
 * Richer return type for [runPlanning]. Carries the serialisable plan output plus
 * the two internal inventory snapshots that the soundness checker needs for the
 * conservation-of-mass check (R7e) but that are too large / too internal to
 * include in the API response.
 *
 * Callers that only need the API payload use [output]. Callers that also run an
 * inline soundness check thread [inventoryEffectiveInitial] and [inventoryLeftover]
 * into [checkRunSoundness].
 */
data class RunPlanningResult(
    /** The serialisable plan output (committed_demands, work_orders, pegging, …). */
    val output: Map<String, Any>,
    /** Inventory state AFTER supply-split overrides, BEFORE any planning pass. */
    val inventoryEffectiveInitial: List<Map<String, Any?>>,
    /** Inventory state AFTER all planning passes (physical supply leftover). */
    val inventoryLeftover: List<Map<String, Any?>>,
    /**
     * Total qty produced per merged-leaf component (pid|lid) in Phase 1 Step 2.
     * Used by [checkRunSoundness] for the component-conservation cross-check (R7f).
     * Empty when consolidation is disabled.
     */
    val producedByComponent: Map<String, Double> = emptyMap(),
    /** Flattened (supply_id, demand_id?, qty_allocated) rows from the SupplyAllocator step. Empty when supply-guided is disabled. Used to seed case_allocation after a plan run. */
    val allocationBudgetRows: List<Triple<String, String?, Double>> = emptyList(),
)

/**
 * Plan all demands. Returns (committedDemands, workOrders, planningPegging).
 * Port of planning_engine.run_planning().
 */
fun runPlanning(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>? = null,
    progressCallback: ((Map<String, Any?>) -> Unit)? = null,
    /** Pre-computed case-level allocation budgets (demandId → lotKey → qty). When non-null, overrides the internal SupplyAllocator step while keeping the BOM graph computed fresh. */
    precomputedBudgets: Map<Any?, MutableMap<String, Double>>? = null,
): RunPlanningResult {
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
            val originalQty = buckets.sumOf { (it["qty"] as? Number)?.toDouble() ?: 0.0 }
            val totalCaps = caps.values.sumOf { if (it > 1e-12) it else 0.0 }
            if (totalCaps > originalQty + 1e-6) {
                log.warn(
                    "supply-split override: supply_id {} Σcaps {:.4f} > original_qty {:.4f} — " +
                    "demands will compete for more than the physical supply",
                    supplyId, totalCaps, originalQty,
                )
            }
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
    // Conservation baseline: after supply-split overrides so that demand-tagged sub-buckets
    // (Σcaps ≤ original_qty) are the reference, not the raw input buckets.
    val inventoryEffectiveInitial: List<Map<String, Any?>> = inventory.map { it.toMap() }

    // ── Phase 1 (resolve) + Phase 2 (consolidate) ────────────────────────────
    val consolidationConfig = parseConsolidationConfig(config)
    val consolidatedWOs = mutableListOf<Map<String, Any?>>()
    val consolidatedPegging = mutableListOf<Map<String, Any?>>()

    // Consolidation enabled → fixed-point iteration (phases 2+3+4 fused).
    // Supply-guided enabled → two-loop model (request map → allocation → budget-commit).
    // Disabled → run phase 3 directly against real inventory; useTaggedLookup
    // is needed only for supply-split overrides (real buckets split per demand).
    var commitResult: LegacyCommitResult
    var producedByComponent: Map<String, Double> = emptyMap()
    var releasedByComponent: Map<String, Double> = emptyMap()
    // Empty list — retained for shape compatibility with historical consumers
    // that still read `supply_level_allocations` from enriched plan results.
    // The supply-level orchestrator (consolidation.scope="all") was retired
    // in 2026-05; only the leaf-level fixed-point pipeline remains.
    val supplyLevelAllocations: List<Map<String, Any?>> = emptyList()
    var allocationBudgetRows: List<Triple<String, String?, Double>> = emptyList()
    val supplyGuidedConfig = parseSupplyGuidedConfig(config)
    // Supply-guided takes priority over legacy consolidation when enabled.
    if (supplyGuidedConfig.enabled) {
        // Step 1+2: pure allocation — BOM reachability walk + proportional supply split.
        val sgAllocationBase = buildSupplyAllocation(demands, data, config)
        val sgAllocation = if (precomputedBudgets != null) {
            log.info("[supply-guided] using case_allocation override: {} demand budget entries", precomputedBudgets.size)
            sgAllocationBase.copy(perLotBudgets = precomputedBudgets)
        } else {
            // Capture computed budgets to seed case_allocation after the run
            allocationBudgetRows = buildAllocationBudgetRows(sgAllocationBase.perLotBudgets)
            sgAllocationBase
        }
        // Step 2b: sketch phase — one read-only BOM walk that both computes achievable caps
        // AND pre-selects the first-feasible BOM method per node per demand.
        // Replaces computeAchievableQtyMaps; eliminates getPreferredMethodCascade overhead
        // (probeChildren per node) from the commit phase entirely.
        val planBlueprint = computePlanBlueprint(demands, sgAllocation, data)
        val achievableQtyMaps = planBlueprint.mapValues { (_, db) ->
            db.mapValues { (_, nb) -> nb.achievable }
        }
        // Step 3: commit with pre-selected methods, per-node caps, and per-lot budget guards.
        commitResult = legacyCommit(
            demands           = demands,
            inventory         = inventory,
            data              = data,
            config            = config,
            overrideIndex     = overrideIndex,
            useTaggedLookup   = false,
            progressCallback  = progressCallback,
            budgets           = sgAllocation.perLotBudgets,
            achievableQtyMaps = achievableQtyMaps,
            planBlueprint     = planBlueprint,
        )
        // Post-planning trace + compensation-pass telemetry (opt-in: trace_lots=true).
        if (sgAllocation.sgConfig.traceLots)
            logSupplyGuidedTrace(sgAllocation, commitResult.planningPegging, data)
    } else if (consolidationConfig.enabled) {
        val iterated = runV2Iterated(
            demands, inventory, data, config, consolidationConfig, overrideIndex, progressCallback,
        )
        consolidatedWOs.addAll(iterated.consolidatedWOs)
        consolidatedPegging.addAll(iterated.consolidatedPegging)
        commitResult = iterated.commitResult
        producedByComponent = iterated.producedByComponent
        releasedByComponent = iterated.releasedByComponent
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
    // Release commitResult's pegging reference — originals are now only in planningPegging,
    // which is cleared after allPegging is built, allowing GC to reclaim the trees.
    commitResult = commitResult.copy(planningPegging = emptyList())

    // With realPegging on, Pass-1 re-supplied the real lots (real supply_id) and the per-demand
    // trees consume them directly — so the `demand_id=null` production trees would DOUBLE-claim
    // those lots in soundness/aggregation. Drop them; per-demand trees carry the real consumption.
    // Prune cycle_stopped phantom loop nodes. Scope `base` inside a run{} so it goes out of scope
    // immediately after pruning, allowing the GC to reclaim original trees before verification passes.
    @Suppress("UNCHECKED_CAST")
    var allPegging: List<Map<String, Any?>> = run {
        val base = if (consolidationConfig.realPegging) planningPegging else (consolidatedPegging + planningPegging)
        base.mapNotNull { entry ->
            val tree = entry["tree"] as? Map<String, Any?> ?: return@mapNotNull null
            val pruned = prunePhantomLoops(tree, isRoot = true) ?: return@mapNotNull null
            entry.toMutableMap().apply { put("tree", pruned) }
        }
    }
    // Release original pegging lists so GC can reclaim them before the verification + timing passes.
    planningPegging.clear()
    consolidatedPegging.clear()
    val suppliesForCap = data["supply"] ?: emptyList()
    val supplyAllocations = extractSupplyAllocations(allPegging, suppliesForCap, demands)
    val supplyCapViolations = verifySupplyCap(suppliesForCap, supplyAllocations)
    // R7e: physical conservation — only count pegging from served demands (committed > 0).
    // Unserved demands have their inventory restored by plan()'s invCopy rollback; excluding
    // their pegging avoids false violations from imperfect `failed=true` tagging.
    val servedDemandIds: Set<String> = committedDemands
        .filter { !isHardPlanningFailure(it["commit_reason"] as? String) }
        .filter { ((it["quantity"] as? Number)?.toDouble() ?: 0.0) > 1e-9 }
        .mapNotNull { it["demand_id"]?.toString() }
        .toSet()
    val conservationViolations = verifyInventoryConservation(
        inventoryEffectiveInitial, inventory, supplyAllocations, servedDemandIds = servedDemandIds,
    )
    // R7f: component conservation — produced qty (Phase 1 Step 2) vs consumed by served demands.
    val componentConservationViolations = if (producedByComponent.isNotEmpty())
        verifyComponentConservation(producedByComponent, allPegging, servedDemandIds = servedDemandIds)
    else emptyList<String>()
    // R7g computed after adjustedConsolidated is available (below the timing-readjust block).

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
    // Release pruned trees; timingFix holds the timing-adjusted copies.
    allPegging = emptyList()

    // Phase-1 cross-WO arbitration. Opt-in via planning config flag — the
    // feature shifts WO start times when shared resources are contended, so
    // existing baselines and KB snapshots stay unchanged until the user
    // explicitly enables it. When pushedCount > 0 we re-run the cascade
    // step (resequenceFromPegging) so the pushed lots propagate up the DAG
    // and the pegging tree's WO/demand timings re-sync.
    // Default ON — flipped from the opt-in default after Phase A validation.
    // Explicit false (saved configs from before the flip) still disables it.
    val enableGlobalScheduling = config?.get("enable_global_scheduling") != false
    var resourceContentionPushed = 0
    val finalTimings = if (enableGlobalScheduling) {
        val mutableLots: List<MutableMap<String, Any?>> = timingFix.workOrders.map {
            (it as? MutableMap<String, Any?>) ?: it.toMutableMap()
        }
        val priorityMap = (data["demand"] ?: emptyList()).mapNotNull { d ->
            val id = (d["demand_id"] as? String)?.trim() ?: return@mapNotNull null
            val pri = (d["priority"] as? Number)?.toInt() ?: 0
            id to pri
        }.toMap()
        val dueMap = (data["demand"] ?: emptyList()).mapNotNull { d ->
            val id = (d["demand_id"] as? String)?.trim() ?: return@mapNotNull null
            val due = (d["request_due_time"] as? String)?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }
            id to due
        }.toMap()
        resourceContentionPushed = ResourceScheduler.arbitrate(mutableLots, data, priorityMap, dueMap)
        if (resourceContentionPushed > 0) {
            resequenceFromPegging(mutableLots, timingFix.peggingTrees)
        } else {
            timingFix
        }
    } else {
        timingFix
    }

    // ── Phase 3 — bottom-up COMMITMENT aggregate over the FINAL pegging (after the timing fix, so
    // nothing downstream re-derives it). The closing step of the quantity pass in the mental model:
    // a parent commits min over children of (child_commit / bom_rate) — "least supplied dominates".
    // Top-down planning commits greedily, so cross-demand inventory contention can leave a make
    // committed ABOVE what its children actually supply (the R4/R8 break). `reconcile` trims those
    // (a no-op for already-consistent trees); the work orders flatten from the trimmed trees.
    val reconciledByDemand = mutableMapOf<String, Double>()
    val reconciledTrees = finalTimings.peggingTrees.map { entry ->
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        val rootReq = (tree["quantity"] as? Number)?.toDouble() ?: 0.0
        val (reconciled, after) = reconcile(tree, rootReq, data)
        val did = entry["demand_id"]?.toString()?.takeIf { it.isNotBlank() }
        // OVERWRITE (not sum): R0 compares committed_demands to the LAST pegging tree per demand
        // (treeByDemand = peggingByDemand.mapValues { it.last() }), so when a demand has several
        // trees we target the last one's committed_qty to match the checker exactly.
        if (did != null) reconciledByDemand[did] = after
        // Restore the root's original request (reconcile collapsed it to committed) so the UI keeps
        // requested-vs-committed; committed_qty already reflects the reconciled commitment.
        entry.toMutableMap().apply { put("tree", reconciled + ("quantity" to rootReq)) }
    }
    // Sync committed_demands to the reconciled roots EXACTLY (R0_committed_consistency): each
    // demand's non-failure committed total must equal its pegging root.committed_qty. Must be
    // exact (no roundQty) and applied for ANY delta — the reconciled root carries the precise
    // fractional commitment (e.g. 999.7057855812216), so a rounded committed_demands would not
    // match. Single-row demands are set directly; multi-row are scaled to the reconciled total.
    if (reconciledByDemand.isNotEmpty()) {
        val nonFailTotal = mutableMapOf<String, Double>()
        val nonFailCount = mutableMapOf<String, Int>()
        for (row in committedDemands) {
            val did = row["demand_id"]?.toString() ?: continue
            if (isHardPlanningFailure(row["commit_reason"] as? String)) continue
            nonFailTotal[did] = (nonFailTotal[did] ?: 0.0) + ((row["quantity"] as? Number)?.toDouble() ?: 0.0)
            nonFailCount[did] = (nonFailCount[did] ?: 0) + 1
        }
        val synced = committedDemands.map { row ->
            val did = row["demand_id"]?.toString() ?: return@map row
            if (isHardPlanningFailure(row["commit_reason"] as? String)) return@map row
            val target = reconciledByDemand[did] ?: return@map row
            if ((nonFailCount[did] ?: 0) == 1) return@map row + ("quantity" to target)  // exact
            val cur = nonFailTotal[did] ?: 0.0
            if (cur <= 1e-9) return@map row
            row + ("quantity" to ((row["quantity"] as? Number)?.toDouble() ?: 0.0) * (target / cur))
        }
        committedDemands.clear(); committedDemands.addAll(synced)
    }

    // ── Pass 2 — WO consolidation + timing ────────────────────────────────────
    // Mental model: Pass 1 builds the per-demand pegging SKELETON (BOM explosion +
    // alternative selection + quantities); Pass 2 derives the work orders FROM it. The
    // default WO-consolidation collapses the per-node lot-explosion into one WO per
    // skeleton node (flattenPeggingToWorkOrders), so the output stays in sync with the
    // per-demand pegging BY CONSTRUCTION — every WO resolves back to its node (pegging /
    // supplies / predecessor-successor drill-down all work).
    //
    // ON by default when consolidation is enabled (opt out with consolidate_wos=false): it
    // batches ACROSS demands (same product/location/method/window) into fewer, larger orders —
    // e.g. one purchase order per raw material instead of one per sub-assembly per demand. The
    // cost is per-demand linkage: batched orders are demand_id=null and no longer map to a
    // single pegging node, so per-WO drill-down degrades (the order still lists its competing
    // demands). Window = UI "Bucket (days)" (period_days); explicit wo_window_days overrides;
    // 0 ⇒ one batch per component across the horizon.
    @Suppress("UNCHECKED_CAST")
    val consolidationCfg = config?.get("consolidation") as? Map<String, Any?>
    val nodeLevelWos = flattenPeggingToWorkOrders(reconciledTrees, data)
    val consolidateWos = consolidationConfig.enabled && consolidationCfg?.get("consolidate_wos") != false
    // consolidated.consolidated = the merged procurement view; consolidated.native = the same input
    // per-demand WOs, each tagged with `consolidated_group_id` so every native belongs to EXACTLY ONE
    // consolidated WO (a strict partition — no native lost, no double-count across the two tabs).
    val consolidation = if (consolidateWos) {
        val windowDays = (consolidationCfg?.get("wo_window_days") as? Number)?.toInt() ?: consolidationConfig.periodDays
        val merged = consolidateWorkOrdersByTiming(nodeLevelWos, data, windowDays)
        log.info("Pass 2 cross-demand WO batch (window={}d): {} native → {} consolidated work orders", windowDays, nodeLevelWos.size, merged.consolidated.size)
        merged
    } else {
        log.info("Pass 2 WO-consolidation OFF: {} node-level work orders (1:1 with per-demand pegging)", nodeLevelWos.size)
        WoConsolidation(nodeLevelWos, nodeLevelWos)
    }

    // Pass 2b — iterative timing readjustment: if any constituent demand's WO starts later
    // than the consolidated batch's start_time, push the batch forward (preserving lead_time)
    // and cascade up the BOM until convergence.
    val (adjustedTrees, adjustedConsolidated) = if (consolidateWos)
        readjustConsolidatedWoTiming(reconciledTrees, consolidation)
    else ReadjustResult(reconciledTrees, consolidation.consolidated)

    // Patch native WO timings to match the adjusted consolidated WO for each group.
    // Preserve original per-demand start and lead before overwriting with consolidated timing so
    // the frontend can show per-demand vs. batch-timing deltas.
    val adjustedTimingByCgid = adjustedConsolidated.associate { c ->
        (c["consolidated_group_id"] as? String ?: "") to
        Pair(c["start_time"] as? String, c["end_time"] as? String)
    }
    val adjustedNative = consolidation.native.map { n ->
        val cgid = n["consolidated_group_id"] as? String ?: return@map n
        val (newStart, newEnd) = adjustedTimingByCgid[cgid] ?: return@map n
        val origStart = n["start_time"] as? String
        val origEnd   = n["end_time"]   as? String
        val origLeadDays = parseDate(origStart)?.let { s -> parseDate(origEnd)?.let { e -> e.toEpochDay() - s.toEpochDay() } }
        n + mapOf(
            "start_time"         to newStart,
            "end_time"           to newEnd,
            "original_start_time" to origStart,
            "original_lead_days"  to origLeadDays,
        )
    }
    log.info("[plan] adjustedNative built: native={}", adjustedNative.size)

    // R7g: WO conservation — post-trim consolidated WO qty vs served-demand consumption.
    // Uses adjustedTrees (final pegging) and adjustedConsolidated (final WO list).
    val woConservationViolations = if (producedByComponent.isNotEmpty())
        verifyWoConservation(producedByComponent, adjustedTrees, adjustedConsolidated, servedDemandIds = servedDemandIds)
    else emptyList<String>()
    log.info("[plan] verifyWoConservation done: violations={}", woConservationViolations.size)

    val output: Map<String, Any> = mapOf(
        "committed_demands"      to committedDemands,
        "work_orders"            to adjustedConsolidated,
        // The NATIVE per-demand work orders (pre-consolidation, 1:1 with the pegging nodes), each
        // tagged with `consolidated_group_id` → the ONE consolidated WO it rolls into. Kept separate
        // from `work_orders` (the consolidated procurement view) so the UI shows each in its own tab —
        // combining them would double-count any aggregate. When consolidation is off these are
        // identical to `work_orders`.
        "work_orders_native"     to adjustedNative,
        "planning_pegging"       to adjustedTrees,
        "supply_allocations"     to supplyAllocations,
        "supply_cap_violations"  to supplyCapViolations,
        "conservation_violations" to conservationViolations,
        // R7f component conservation: produced vs consumed per merged-leaf component.
        // Non-empty when unserved demands left their Phase 1 allocation unused. Surfaced
        // here so callers can detect budget leakage without running the full soundness checker.
        "component_conservation_violations" to componentConservationViolations,
        // R7g: WO qty > served-demand consumption at a consolidated component. Non-empty when
        // reconcileOverProduction did not fully release unserved-demand capacity from WOs.
        "wo_conservation_violations" to woConservationViolations,
        // Budget released from unserved demands; keyed by "pid|lid". Non-empty with
        // max_iterations=1 when a demand failed AND-bottleneck after Phase 1 allocated capacity.
        "budget_released_by_component" to releasedByComponent,
        "override_warnings"      to overrideWarnings,
        "supply_level_allocations" to supplyLevelAllocations,
        // Number of WO groups whose start was pushed by ResourceScheduler
        // to wait for contended resources. Zero when the feature flag is
        // off; > 0 when global scheduling actually moved at least one WO.
        "resource_contention_pushed_wos" to resourceContentionPushed,
    )
    return RunPlanningResult(
        output = output,
        inventoryEffectiveInitial = inventoryEffectiveInitial,
        inventoryLeftover = inventory.map { it.toMap() },
        producedByComponent = producedByComponent,
        allocationBudgetRows = allocationBudgetRows,
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
 * Conservation-of-mass check: for every physical supply bucket,
 *   initial_qty == leftover_qty + pegged_qty
 * Synthetic buckets (supply_id starts with "consolidated_") are excluded — they
 * have no physical cap and are zero-sum within each planning pass by construction.
 *
 * A non-empty result indicates a ghost depletion or phantom allocation bug:
 * inventory was consumed without a matching pegging tree entry, or vice-versa.
 *
 * @param effectiveInitial  Snapshot of inventory AFTER supply-split overrides, BEFORE planning.
 * @param postPlanningInventory  Inventory state AFTER all planning passes.
 * @param supplyAllocations  Output of [extractSupplyAllocations] — pegged qty per supply_id.
 */
internal fun verifyInventoryConservation(
    effectiveInitial: List<Map<String, Any?>>,
    postPlanningInventory: List<Map<String, Any?>>,
    supplyAllocations: List<Map<String, Any?>>,
    tolerance: Double = 1e-6,
    /**
     * When provided, only count supply allocations from these demand IDs.
     * Unserved demands (committed=0) have their inventory restored by plan()'s
     * invCopy rollback, so including their pegging would over-count and produce
     * false conservation violations. Implements the formula:
     *   initial = leftover + pegged_to_served_demands
     */
    servedDemandIds: Set<String>? = null,
): List<String> {
    val initialBySupply = mutableMapOf<String, Double>()
    for (s in effectiveInitial) {
        val sid = s["supply_id"] as? String ?: continue
        if (sid.startsWith("consolidated_")) continue
        val qty = (s["qty"] as? Number)?.toDouble() ?: 0.0
        initialBySupply[sid] = (initialBySupply[sid] ?: 0.0) + qty
    }
    val leftoverBySupply = mutableMapOf<String, Double>()
    for (s in postPlanningInventory) {
        val sid = s["supply_id"] as? String ?: continue
        if (sid.startsWith("consolidated_")) continue
        val qty = (s["qty"] as? Number)?.toDouble() ?: 0.0
        leftoverBySupply[sid] = (leftoverBySupply[sid] ?: 0.0) + qty
    }
    val peggedBySupply = mutableMapOf<String, Double>()
    val effectiveAllocations = if (servedDemandIds != null)
        supplyAllocations.filter { (it["demand_id"] as? String ?: "") in servedDemandIds }
    else supplyAllocations
    for (a in effectiveAllocations) {
        val sid = a["supply_id"] as? String ?: continue
        if (sid.startsWith("consolidated_")) continue
        val qty = (a["qty_consumed"] as? Number)?.toDouble() ?: 0.0
        peggedBySupply[sid] = (peggedBySupply[sid] ?: 0.0) + qty
    }
    val violations = mutableListOf<String>()
    for ((sid, initial) in initialBySupply) {
        val leftover = leftoverBySupply[sid] ?: 0.0
        val pegged   = peggedBySupply[sid] ?: 0.0
        val discrepancy = kotlin.math.abs(initial - leftover - pegged)
        if (discrepancy > maxOf(tolerance, 1e-9 * initial)) {
            val msg = "supply_id %s: initial %.4f ≠ leftover %.4f + pegged %.4f (Δ=%.6f)".format(
                sid, initial, leftover, pegged, discrepancy)
            violations.add(msg)
            log.warn("inventory conservation violation — {}", msg)
        }
    }
    // Orphaned leftover: physical supply present post-planning but not in effective initial.
    for ((sid, leftover) in leftoverBySupply) {
        if (sid !in initialBySupply && leftover > tolerance) {
            val msg = "supply_id %s: orphaned leftover %.4f (absent from effective initial)".format(sid, leftover)
            violations.add(msg)
            log.warn("inventory conservation violation — {}", msg)
        }
    }
    return violations
}

/**
 * R7f: component conservation check. For each merged-leaf component (pid|lid) that
 * Phase 1 Step 2 (consolidation) produced, the total qty consumed from its synthetic
 * `consolidated_<pid>_<lid>` supply bucket by served demands must equal the produced qty.
 *
 * A non-empty result means unserved demands left their Phase 1 allocation unused —
 * that capacity was never drawn in Step 3. With max_iterations=1 this leaks; with
 * max_iterations > 1 the convergence loop reclaims it automatically.
 *
 * @param producedByComponent  From [RunPlanningResult.producedByComponent] — qty allocated per
 *        merged-leaf component in Phase 1 Step 2 (keyed by "pid|lid").
 * @param planningPegging      All pegging entries from the plan output (consolidated + per-demand).
 * @param servedDemandIds      When provided, only count consumption from these demand IDs.
 *        Pass the same set used in [verifyInventoryConservation] for consistency.
 */
internal fun verifyComponentConservation(
    producedByComponent: Map<String, Double>,
    planningPegging: List<Map<String, Any?>>,
    servedDemandIds: Set<String>? = null,
    tolerance: Double = 1e-6,
): List<String> {
    if (producedByComponent.isEmpty()) return emptyList()

    // Build reverse index: consolidated supply_id → component key (pid|lid)
    val componentBySupplyId: Map<String, String> = producedByComponent.keys.associateBy { key ->
        val parts = key.split("|", limit = 2)
        "consolidated_${parts.getOrElse(0) { "" }}_${parts.getOrElse(1) { "" }}"
    }

    val consumedByComponent = mutableMapOf<String, Double>()

    fun walk(node: Map<String, Any?>) {
        val type = node["type"] as? String
        if (type == "work_order" && node["failed"] == true) return
        val supplyId = node["supply_id"] as? String
        if ((type == "supply" || type == "purchase") && supplyId != null) {
            val componentKey = componentBySupplyId[supplyId]
            if (componentKey != null) {
                val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
                consumedByComponent[componentKey] = (consumedByComponent[componentKey] ?: 0.0) + qty
            }
        }
        @Suppress("UNCHECKED_CAST")
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it) }
    }

    for (entry in planningPegging) {
        if (entry["consolidated"] == true || entry["passthrough"] == true) continue
        val demandId = entry["demand_id"]?.toString() ?: continue
        if (servedDemandIds != null && demandId !in servedDemandIds) continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree)
    }

    val violations = mutableListOf<String>()
    for ((key, produced) in producedByComponent) {
        val consumed = consumedByComponent[key] ?: 0.0
        val orphaned = produced - consumed
        if (orphaned > maxOf(tolerance, 1e-9 * produced)) {
            val parts = key.split("|", limit = 2)
            val pid = parts.getOrElse(0) { "" }
            val lid = parts.getOrElse(1) { "" }
            val msg = "component %s @ %s: produced %.4f but only %.4f consumed by served demands (orphaned %.4f)".format(
                pid, lid, produced, consumed, orphaned)
            violations.add(msg)
            log.warn("component conservation violation — {}", msg)
        }
    }
    return violations
}

/**
 * R7g: WO conservation check. For each merged-leaf component (pid|lid) that Phase 1
 * Step 2 produced a consolidated WO for, the total WO qty at that component must not
 * exceed what served demands actually consumed from the synthetic supply bucket.
 *
 * Unlike R7f which uses the pre-[reconcileOverProduction] allocation, this check uses
 * the post-trim WO qty and verifies the trim landed correctly. A non-empty result means
 * a consolidated WO remains over-sized after trimming — typically because
 * [reconcileOverProduction]'s Stage-4a fallback only trims the merged leaf and does not
 * cascade to BOM-child WOs.
 *
 * @param producedByComponent  From [RunPlanningResult.producedByComponent] — identifies
 *        which (pid|lid) pairs had consolidated WOs. Only WOs at those components are checked.
 * @param planningPegging      All pegging entries from the plan output.
 * @param workOrders           The consolidated `work_orders` from the plan output.
 * @param servedDemandIds      When provided, only count consumption from these demand IDs.
 */
internal fun verifyWoConservation(
    producedByComponent: Map<String, Double>,
    planningPegging: List<Map<String, Any?>>,
    workOrders: List<Map<String, Any?>>,
    servedDemandIds: Set<String>? = null,
    tolerance: Double = 1e-6,
): List<String> {
    if (producedByComponent.isEmpty() || workOrders.isEmpty()) return emptyList()

    // Reverse index: consolidated supply_id → component key (pid|lid)
    val componentBySupplyId: Map<String, String> = producedByComponent.keys.associateBy { key ->
        val parts = key.split("|", limit = 2)
        "consolidated_${parts.getOrElse(0) { "" }}_${parts.getOrElse(1) { "" }}"
    }

    // Sum synthetic supply consumption per component from served demand pegging trees.
    val consumedByComponent = mutableMapOf<String, Double>()

    fun walk(node: Map<String, Any?>) {
        val type = node["type"] as? String
        if (type == "work_order" && node["failed"] == true) return
        val supplyId = node["supply_id"] as? String
        if ((type == "supply" || type == "purchase") && supplyId != null) {
            componentBySupplyId[supplyId]?.let { key ->
                consumedByComponent[key] = (consumedByComponent[key] ?: 0.0) +
                    ((node["quantity"] as? Number)?.toDouble() ?: 0.0)
            }
        }
        @Suppress("UNCHECKED_CAST")
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it) }
    }

    for (entry in planningPegging) {
        if (entry["consolidated"] == true || entry["passthrough"] == true) continue
        val demandId = entry["demand_id"]?.toString() ?: continue
        if (servedDemandIds != null && demandId !in servedDemandIds) continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree)
    }

    // Sum consolidated WO qty per component (only components that had consolidation).
    val woQtyByComponent = mutableMapOf<String, Double>()
    for (wo in workOrders) {
        val pid = (wo["product_id"] as? String)?.trim() ?: continue
        val lid = (wo["location_id"] as? String)?.trim() ?: continue
        val key = "$pid|$lid"
        if (key !in producedByComponent) continue
        val qty = (wo["quantity"] as? Number)?.toDouble() ?: 0.0
        woQtyByComponent[key] = (woQtyByComponent[key] ?: 0.0) + qty
    }

    val violations = mutableListOf<String>()
    for ((key, woQty) in woQtyByComponent) {
        val consumed = consumedByComponent[key] ?: 0.0
        val orphaned = woQty - consumed
        if (orphaned > maxOf(tolerance, 1e-9 * woQty)) {
            val parts = key.split("|", limit = 2)
            val pid = parts.getOrElse(0) { "" }
            val lid = parts.getOrElse(1) { "" }
            val msg = "WO %s @ %s: qty %.4f > served-demand consumption %.4f (orphaned %.4f — unserved demand WO not released)".format(
                pid, lid, woQty, consumed, orphaned)
            violations.add(msg)
            log.warn("WO conservation violation — {}", msg)
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

        // Per-wave duration: lots run in `cap`-sized waves; each wave occupies
        // one per-lot lead. Total span = wave_count × per-lot. Falls back to
        // sequential (cap=1, wave_count=lot_count) when parallelism_cap isn't
        // on the node (legacy WOs or non-override methods).
        val cap = (woNode["parallelism_cap"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val waveCount = (woNode["wave_count"] as? Number)?.toInt()
            ?: kotlin.math.ceil(lotCount.toDouble() / cap.toDouble()).toInt().coerceAtLeast(1)
        val totalSpanDays = endDt.toEpochDay() - startDt.toEpochDay()
        // Per-wave duration: divide span by wave count (NOT lot count).
        // Sequential WOs collapse to the old behavior since wave_count ==
        // lot_count when cap == 1.
        val perWaveDays = if (waveCount > 0) totalSpanDays / waveCount else 0L
        // Purchase/move lots have no inherent serialization (no cycle/transport
        // cadence is modeled), so every lot runs concurrently across the node's
        // full [start,end] window — each carries the full procurement/transit lead.
        // Only make WOs cascade across waves. Without this, dividing the (single-
        // wave) span by lot_count corrupts the per-lot lead to near-zero.
        val concurrent = method != "make"

        var remaining = totalQty
        for (i in 0 until lotCount) {
            if (remaining <= 1e-9) break
            val lotQty = min(lotSize, remaining)
            val waveIdx = if (concurrent) 0L else (i / cap).toLong()
            val lotStart = if (concurrent) startDt else startDt.plusDays(waveIdx * perWaveDays)
            val lotEnd = if (concurrent) endDt else lotStart.plusDays(perWaveDays)
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
                "wave_index" to waveIdx.toInt(),
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
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun regenWalk(
        node: Map<String, Any?>,
        ownerDemandId: Any?,
        depth: Int,
    ): Map<String, Any?> {
        if (depth > 60) return node
        val type = node["type"] as? String

        // Failed=true WOs (AND-bottleneck blocked branches) carry first-pass
        // taggedChildPeggings as diagnostic stubs.  Those embedded subtrees
        // contain WO nodes whose lots were never propagated to the workOrders
        // list (the slot returned wos=emptyList).  Walking into them would
        // emit lots for those orphan first-pass WOs — keep the subtree
        // visible in the tree but skip recursion entirely.
        if (type == "work_order" && node["failed"] == true) return node

        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()

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

    return resequenceFromPegging(regenLots, regenTrees)
}

/**
 * Sequencing pipeline (Step 2 + Step 3 of [fixTimingFromPegging]) reusable for
 * "what-if" schedule-change scenarios that bypass Step 1 (regen).
 *
 * Inputs are a coherent (work_orders, pegging trees) pair where every WO has a
 * stable `wo_group_id` and OR-alternatives share a gid distinguished by
 * `method_slot_index` — i.e. the output of a baseline plan run that already
 * went through `fixTimingFromPegging` once.
 *
 * Step 2 builds the parent→children DAG, applies leaf-time pre-shifts (supply
 * /purchase commit_time constraints), and runs the pushUp loop so any
 * downstream WO with a later end_time pushes its parents forward.
 *
 * Step 3 rewrites pegging-tree node start/end (and demand commit_time) from
 * the post-shift lot timings.
 */
internal fun resequenceFromPegging(
    workOrders: List<Map<String, Any?>>,
    peggingTrees: List<Map<String, Any?>>,
): TimingFixResult {
    if (workOrders.isEmpty() || peggingTrees.isEmpty()) {
        return TimingFixResult(workOrders, peggingTrees)
    }

    // ── Step 2: Sequencing on the regenerated lots ────────────────────────────
    //
    // Key on wo_group_id directly (alternatives in an OR-group already share
    // the same gid post-regen, distinguished by method_slot_index).  Per-alt
    // shifting partitions within a bucket using method_slot_index.

    val dag = buildPeggingDag(workOrders, peggingTrees)
    val mutableLots = dag.mutableLots
    val lotsByGroup = dag.lotsByGroup
    val parentsOf = dag.parentsOf
    val allGroups = dag.allGroups
    val leafConstraintByGid = dag.leafConstraintByGid
    val leaves = allGroups.filter { it !in dag.hasChild }

    fun tailEnd(gid: String): LocalDate? =
        lotsByGroup[gid]?.mapNotNull { parseDate(it["end_time"] as? String) }?.maxOrNull()

    // Apply leaf-time pre-shifts: ensure each WO group's lots start no
    // earlier than max(supply / purchase commit_time of any leaf in its
    // demand-children sub-tree).  Standard pushUp will propagate any
    // resulting tail-shift further up the DAG.
    var leafConstraintShifts = 0
    for ((gid, minStart) in leafConstraintByGid) {
        val parentLots = lotsByGroup[gid] ?: continue
        val byAlt = parentLots.groupBy { it["method_slot_index"] as? Int }
        for ((_, altLots) in byAlt) {
            val altHead = altLots.mapNotNull { parseDate(it["start_time"] as? String) }.minOrNull() ?: continue
            if (minStart <= altHead) continue
            val shiftDays = minStart.toEpochDay() - altHead.toEpochDay()
            for (lot in altLots) {
                parseDate(lot["start_time"] as? String)?.let { lot["start_time"] = formatDate(it.plusDays(shiftDays)) }
                parseDate(lot["end_time"] as? String)?.let { lot["end_time"] = formatDate(it.plusDays(shiftDays)) }
            }
            leafConstraintShifts++
        }
    }

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

    log.info("resequenceFromPegging: lot_groups={} pegging_trees={} dag_groups={} leaves={} leaf_constraint_shifts={} shifts_applied={}",
        lotsByGroup.size, peggingTrees.size, allGroups.size, leaves.size, leafConstraintShifts, shiftCount)

    // ── Step 3: Write corrected timings back into the pegging trees ───────────
    // Same skip-failed discipline as regenWalk: failed=true WO nodes carry
    // first-pass diagnostic stubs whose gids were never emitted as lots.
    // Recursing into them and trying to update timing produces ~20k spurious
    // "no lots" hits and (worse) leaves the tree in an inconsistent state.
    var rewriteWoUpdated = 0
    var rewriteWoNoLots = 0
    var rewriteWoNoGid = 0
    val rewriteMissSamples = mutableListOf<String>()
    @Suppress("UNCHECKED_CAST")
    fun rewriteTree(node: Map<String, Any?>, depth: Int, path: String): Map<String, Any?> {
        if (depth > 60) return node
        // Skip the entire subtree of any failed=true WO node.
        if (node["type"] == "work_order" && node["failed"] == true) return node
        val originalChildren = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        val newChildren = originalChildren.mapIndexed { i, ch -> rewriteTree(ch, depth + 1, "$path-$i") }
        val updated = node.toMutableMap()
        if (originalChildren.isNotEmpty()) updated["children"] = newChildren
        when (node["type"] as? String) {
            "work_order" -> {
                val gid = node["wo_group_id"] as? String
                val altIndex = node["method_slot_index"] as? Int
                if (gid == null) {
                    rewriteWoNoGid++
                    if (rewriteMissSamples.size < 10) {
                        rewriteMissSamples.add("no_gid: ${node["product_id"]}@${node["location_id"]}/${node["method"]} path=$path start=${node["start_time"]}")
                    }
                } else {
                    val ownLots = lotsByGroup[gid]?.filter {
                        (it["method_slot_index"] as? Int) == altIndex
                    } ?: emptyList()
                    if (ownLots.isNotEmpty()) {
                        ownLots.mapNotNull { parseDate(it["start_time"] as? String) }.minOrNull()
                            ?.let { updated["start_time"] = formatDate(it) }
                        ownLots.mapNotNull { parseDate(it["end_time"] as? String) }.maxOrNull()
                            ?.let { updated["end_time"] = formatDate(it) }
                        rewriteWoUpdated++
                    } else {
                        rewriteWoNoLots++
                        if (rewriteMissSamples.size < 10) {
                            val bucketSize = lotsByGroup[gid]?.size ?: 0
                            rewriteMissSamples.add("no_lots: ${node["product_id"]}@${node["location_id"]}/${node["method"]} gid=$gid altIdx=$altIndex bucket_size=$bucketSize path=$path start=${node["start_time"]}")
                        }
                    }
                }
            }
            "demand" -> {
                // Recompute commit_time from children that ACTUALLY produced
                // something — exclude hard-planning-failure children whose
                // commit_time is the wishful request_time (no real
                // fulfillment, so they don't constrain the parent's start).
                val newCommit = newChildren.mapNotNull { ch ->
                    val r = ch["commit_reason"] as? String
                    if (r == "cycle_stopped" || r == "cycle_detected") return@mapNotNull null
                    if (isHardPlanningFailure(r)) return@mapNotNull null
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
    val finalTrees = peggingTrees.map { entry ->
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        entry.toMutableMap().apply { put("tree", rewriteTree(tree, 0, "0")) }
    }
    log.info("resequenceFromPegging.rewriteTree: wo_updated={} wo_no_lots={} wo_no_gid={}",
        rewriteWoUpdated, rewriteWoNoLots, rewriteWoNoGid)
    if (rewriteMissSamples.isNotEmpty()) {
        log.warn("resequenceFromPegging.rewriteTree miss samples (first {}):", rewriteMissSamples.size)
        for (sample in rewriteMissSamples) log.warn("  rewrite-miss: {}", sample)
    }

    return TimingFixResult(mutableLots, finalTrees)
}

/** Result of [fixTimingFromPegging] / [resequenceFromPegging]: the corrected
 *  work-order list AND the pegging trees rewritten with the same canonical
 *  timings, so all downstream consumers (soundness, UI, KPIs) read a
 *  consistent view. */
internal data class TimingFixResult(
    val workOrders: List<Map<String, Any?>>,
    val peggingTrees: List<Map<String, Any?>>,
)

/** DAG view of a (work_orders, pegging_trees) pair, derived once and reused
 *  by [resequenceFromPegging] and the WO-availability analysis. */
internal data class PeggingDag(
    /** Lots wrapped as MutableMap so sequencing can mutate start/end in place. */
    val mutableLots: List<MutableMap<String, Any?>>,
    /** Lots indexed by `wo_group_id`. */
    val lotsByGroup: Map<String, List<MutableMap<String, Any?>>>,
    /** Per-gid set of *parent* gids in the DAG (multi-parent supported). */
    val parentsOf: Map<String, Set<String>>,
    /** Set of gids that appear as a parent of at least one other gid. */
    val hasChild: Set<String>,
    /** Every gid encountered in the trees (excludes failed=true subtree gids). */
    val allGroups: Set<String>,
    /** Per-gid max supply/purchase commit_time for any leaf in the demand-children
     *  subtree of that gid — used by sequencing as a pre-shift constraint. */
    val leafConstraintByGid: Map<String, LocalDate>,
    /** Per-demand_id, the gids of WOs that are direct (level-1) WO children of
     *  the demand-root tree. Used by availability analysis for the demand-root
     *  commit-stability check. */
    val demandRootChildren: Map<String, Set<String>>,
)

/** Walk the (regen-stamped) pegging trees once and produce the DAG view used
 *  by both [resequenceFromPegging] and the availability analysis. The walk
 *  collects parent-child WO edges, leaf-time constraints (supply/purchase
 *  commit_time per parent gid), and per-demand-root direct WO children. */
internal fun buildPeggingDag(
    workOrders: List<Map<String, Any?>>,
    peggingTrees: List<Map<String, Any?>>,
): PeggingDag {
    val mutableLots: List<MutableMap<String, Any?>> = workOrders.map { wo ->
        if (wo is MutableMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            wo as MutableMap<String, Any?>
        } else wo.toMutableMap()
    }
    val lotsByGroup = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
    for (wo in mutableLots) {
        val gid = wo["wo_group_id"] as? String ?: continue
        lotsByGroup.getOrPut(gid) { mutableListOf() }.add(wo)
    }

    val parentsOf = mutableMapOf<String, MutableSet<String>>()
    val hasChild = mutableSetOf<String>()
    val allGroups = mutableSetOf<String>()
    val leafConstraintByGid = mutableMapOf<String, LocalDate>()
    val demandRootChildren = mutableMapOf<String, MutableSet<String>>()

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
            "supply", "purchase" -> {
                if (currentParent != null) {
                    parseDate(node["commit_time"] as? String)?.let { commit ->
                        leafConstraintByGid.merge(currentParent, commit) { a, b -> if (b > a) b else a }
                    }
                }
            }
            else -> {
                for (c in nodeChildren) walk(c, currentParent, depth + 1)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    for (entry in peggingTrees) {
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, currentParent = null, depth = 0)

        // Demand-root direct WO children: only the level-1 WOs under the root
        // demand node feed the demand commit_time (deeper WOs feed via pushUp).
        val demandId = entry["demand_id"]?.toString()?.trim() ?: continue
        if (demandId.isBlank()) continue
        if (tree["type"] != "demand") continue
        val rootChildren = tree["children"] as? List<Map<String, Any?>> ?: continue
        for (ch in rootChildren) {
            if (ch["type"] != "work_order") continue
            if (ch["failed"] == true) continue
            val gid = ch["wo_group_id"] as? String ?: continue
            demandRootChildren.getOrPut(demandId) { mutableSetOf() }.add(gid)
        }
    }

    return PeggingDag(
        mutableLots = mutableLots,
        lotsByGroup = lotsByGroup,
        parentsOf = parentsOf,
        hasChild = hasChild,
        allGroups = allGroups,
        leafConstraintByGid = leafConstraintByGid,
        demandRootChildren = demandRootChildren,
    )
}
