package com.allocator.services

import com.allocator.config
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private val log = LoggerFactory.getLogger("com.allocator.PlanningEngine")

/** Port of services/planning_engine.py — demand-to-supply planning with pegging tree. */

private const val MAX_PLAN_DEPTH = 500
private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
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
        "preference" -> modeStr
        "elaborate" -> { log.warn("method_selection.mode='elaborate' is deprecated; using preference"); "preference" }
        null -> "preference"
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

/** Universal "is this WO effectively zero" check — the single definition of "zero" a WO
 *  quantity must clear everywhere in the pipeline to count as a real, fully-fledged work
 *  order: whether it gets a row in work_orders/work_orders_native (flattenPeggingToWorkOrders,
 *  consolidateByWaves), whether its wo_group_id is expected to resolve to a lot (R11 orphan
 *  check, SoundnessChecker.verifyWoGidOrphans/checkRunSoundnessStreaming), and whether it's
 *  scrutinized as a real WO subject to full lead-time/duration scheduling (SoundnessChecker's
 *  R4/R5/R6 make/move validation). A WO below this threshold is uniformly treated as zero
 *  everywhere — no lot, no orphan expectation, no scheduling scrutiny; a WO at or above it is
 *  a fully-fledged WO everywhere, including taking its full lead time.
 *
 *  Threshold is 0.5, not a tiny epsilon: the frontend's qtyFmt() rounds to the nearest
 *  integer, so any quantity below 0.5 already displays as a bare "0" to the user — making it
 *  indistinguishable from a genuine zero regardless of what it's stored as internally. */
internal fun isZeroQty(quantity: Any?): Boolean = ((quantity as? Number)?.toDouble() ?: 0.0) < 0.5

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

/**
 * A pointer from a constrained pegging node to the OTHER node currently determining its
 * committed quantity or committed time — "least quantity dominates, latest time dominates."
 * Captured inline at the existing points in the planner where a min (quantity) or max (time)
 * collapse already happens, rather than computed by a separate pass. Attached to tree nodes as
 * `quantity_dominator`/`time_dominator` (a list — ties are common, already tolerated elsewhere
 * in this file at 1e-9/5% bands).
 */
data class DominatorRef(
    val kind: String,   // "bom_child" | "sibling_wo" | "method_alternative" | "wave_peer" |
                         // "resource_contention" (not yet constructed anywhere — see the
                         // resource-arbitration follow-up)
    val productId: String? = null,
    val locationId: String? = null,
    val demandId: String? = null,
    val woGroupId: String? = null,
    val supplyId: String? = null,
    val competingDemandIds: List<String>? = null,
    val label: String,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("kind", kind)
        if (productId != null) put("product_id", productId)
        if (locationId != null) put("location_id", locationId)
        if (demandId != null) put("demand_id", demandId)
        if (woGroupId != null) put("wo_group_id", woGroupId)
        if (supplyId != null) put("supply_id", supplyId)
        if (competingDemandIds != null) put("competing_demand_ids", competingDemandIds)
        put("label", label)
    }
}

private fun List<DominatorRef>.toJsonList(): List<Map<String, Any?>> = map { it.toMap() }

/**
 * Dedup key is supply identity, not full structural equality: the SAME underlying supply lot is
 * frequently discovered independently by more than one collapse site (e.g. a demand-level
 * rollup AND a sibling WO's own AND-branch both bottoming out at the same lot), each stamping a
 * different `kind`/`label` on the way — plain `.distinct()` treats those as different entries
 * and leaves visible near-duplicates ("Qty limited by: X" appearing several times). Falls back
 * to (kind, product, location, demand, wo group) only for the rare ref with no supplyId at all
 * (e.g. the terminal "no supply method" `bom_child` case).
 */
internal fun List<DominatorRef>.dedupBySupply(): List<DominatorRef> {
    val deduped = distinctBy { it.supplyId ?: "${it.kind}|${it.productId}|${it.locationId}|${it.demandId}|${it.woGroupId}" }
    // A dominator is always a real, specific supply lot — never a synthesized placeholder. The
    // "no supply method" terminal bom_child (supply_id null — nowhere real was ever reachable
    // down THAT particular branch) is only the right answer when nothing real exists anywhere
    // in this aggregated group; if another branch in the SAME group DID find a real lot, that's
    // the more informative, physically-grounded cause, and the terminal placeholder — which
    // isn't itself a supply lot — should drop out rather than sit alongside it. Scoped to
    // bom_child specifically: other kinds (wave_peer, method_alternative, ...) legitimately
    // have no supply_id by design and aren't "missing a real lot" in the same sense.
    val hasRealLot = deduped.any { it.kind == "bom_child" && it.supplyId != null }
    return if (hasRealLot) deduped.filterNot { it.kind == "bom_child" && it.supplyId == null } else deduped
}

/**
 * Builds the [DominatorRef] for an AND-group's parent WO from [keys] — the tied-min sibling
 * set already computed by the caller (same set used for `is_bottleneck` tagging). An AND-group
 * has exactly ONE dominator: [keys] is frequently NOT a genuine multi-way tie but an artifact of
 * blueprint mode pre-equalizing every AND-sibling to the same achievable value (so comparing
 * ratios again here finds almost everything "tied" without that meaning they're all equally
 * responsible) — so only the FIRST matching child is used, and its own dominator (already
 * resolved recursively during its own [plan] call — see the sketch phase's
 * `NodeBlueprint.quantityDominator`) propagates verbatim, never re-derived or unioned.
 */
private fun bomChildDominatorRefs(
    childPassResults: List<ChildPassResult>,
    keys: Set<Pair<String, String>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<DominatorRef> {
    val tiedCandidates = childPassResults.filter { cr ->
        val pid = (cr.child["product_id"] as? String)?.trim() ?: ""
        val lid = (cr.child["location_id"] as? String)?.trim() ?: ""
        Pair(pid, lid) in keys
    }
    // Prefer whichever tied candidate already carries its OWN genuine dominator (set during its
    // own recursive plan() call — the sketch phase's per-node achievable computation is scoped
    // exactly to what THAT child was actually asked for, so it correctly leaves siblings that
    // merely LOOK tied here empty). Falls back to the first tied candidate only when none of
    // them carry one yet (e.g. non-blueprint plan() calls, where rawDominatorRefs's own
    // raw-leaf/child search below is the sole source).
    val winner = tiedCandidates.firstOrNull { cr ->
        (cr.pegging?.get("quantity_dominator") as? List<*>)?.isNotEmpty() == true
    } ?: tiedCandidates.firstOrNull()
    return rawDominatorRefs(winner?.pegging, "quantity_dominator", "bom_child", data, config)
}

/**
 * Resolves a dominator candidate down to a genuine raw supply leaf, instead of pointing at
 * an intermediate demand/work_order node the caller would then have to click through again to
 * find the real answer — any work order (make/move/purchase) is always a CONSEQUENCE of some
 * upstream constraint, never itself a root cause. [node] is some other node ALREADY fully built at
 * this point (a child recursed into earlier in the same bottom-up planning pass, or a
 * sibling already rewritten by an earlier step in the same tree walk) — so if it already
 * carries its own [key] ("quantity_dominator" or "time_dominator"), that's the deeper cause
 * and gets propagated verbatim (transitively chaining all the way down, since each of those
 * nodes was itself resolved the same way when it was built). [fallbackKind] labels a
 * freshly-discovered leaf when no richer kind was already recorded.
 *
 * "Raw" means genuinely fixed and unchangeable — a `supply` lot has both a fixed quantity and
 * a fixed availability date, neither adjustable by the planner. A `purchase` is NOT raw on
 * either axis: its quantity is elastic (you can always order more) and its timing is also a
 * planner decision (you can always order sooner) rather than a given fact — so a subtree that
 * bottoms out at nothing but purchase leaves has no genuine raw constraint on either axis; this
 * returns empty rather than misattributing a shortage or delay to an elastic purchase.
 *
 * Deliberately does NOT recurse past its own direct children when neither of the above apply.
 * A node with no existing dominator and no direct raw-leaf child means planning found it fully
 * satisfied at its own level — there is nothing to chase. Digging deeper (as an earlier version
 * of this function did, walking every descendant and unioning whatever raw materials happened
 * to be reachable) mistook "planning didn't tag this" for "go search the whole subtree," which
 * is how an unrelated, roughly-constrained sibling's entire multi-level BOM could leak dozens of
 * unrelated raw materials into one node's dominator list. Any genuine constraint belongs
 * embedded inline at the exact planning step that decided it (see [NodeBlueprint.quantityDominator]
 * and the AND/OR call sites below) — not reconstructed here after the fact.
 */
/**
 * True if [productId]@[locationId] is "critical": (1) no make method and no admitted purchase
 * — existing supply is the only possible source, system-wide — or (2) it has no make method and
 * a buy method that raw data offers but the current config explicitly excludes from purchase.
 * A `make` method always wins: if the material can be made, it is elastic and never critical,
 * regardless of whether it also has a buy method and regardless of that buy method's purchase
 * status — make availability alone is what determines "the total system-wide quantity is fixed."
 * `move` plays no role in either criterion: relocating stock between locations never creates
 * more of it system-wide, so a move-only position (no make, no buy) is still critical here — even
 * though the live commit's own "no_methods" trigger in [plan]/[planMethodSlot] would NOT fail on
 * such a position (the move itself succeeds). This function answers a different, broader question
 * than "will this draw fail right now": whether the TOTAL system-wide quantity of the material
 * is fixed (make/buy-elastic vs. not) — the property `criticalMatrix`/`perLotBudgets`/diamond
 * allocation actually need, to stop one demand from hoarding a shared, non-replenishable total.
 *
 * This is the single, canonical critical-material test — used identically here (dominator
 * labeling) and by [buildSupplyAllocation]'s `criticalPids` construction (which feeds
 * `criticalMatrix`), so the two systems can never classify the same material differently.
 */
internal fun isRawCriticalPosition(
    productId: String?,
    locationId: String?,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): Boolean {
    val pid = productId ?: return false
    val lid = locationId ?: return false
    val methods = getMethods(pid, lid, data)
    val hasMake = methods.any { it["type"] == "make" }
    val hasBuy  = methods.any { it["type"] == "purchase" }
    // Move plays no role in either criterion: moving stock between locations never creates
    // more of it system-wide, so a move-only position is still critical.
    if (hasMake) return false                       // make available: always elastic, never critical
    if (!hasBuy) return true                          // no make, no buy: criterion 1
    val purchaseAllowed = config?.get("purchase_allowed") != false
    val purchasable = effectivePurchasableSet(config, data)
    return !buyAdmitted(pid, purchaseAllowed, purchasable)  // criterion 2: no make, buy exists but excluded
}

internal fun rawDominatorRefs(
    node: Map<String, Any?>?,
    key: String,
    fallbackKind: String,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<DominatorRef> {
    if (node == null) return emptyList()
    // Quantity and time are genuinely different axes here: "raw+critical" is about whether MORE
    // quantity could ever be obtained — irrelevant to time, where a physical supply lot's own
    // arrival date is fixed regardless of whether the underlying product ALSO has some other,
    // elastic sourcing path. So for time_dominator this is the simple rule (any "supply" leaf is
    // raw); quantity_dominator additionally requires raw+critical.
    val isQty = key == "quantity_dominator"
    fun isRawLeaf(n: Map<String, Any?>): Boolean {
        if (n["type"] != "supply") return false
        if (!isQty) return true
        return isRawCriticalPosition(n["product_id"] as? String, n["location_id"] as? String, data, config)
    }

    @Suppress("UNCHECKED_CAST")
    val existing = node[key] as? List<Map<String, Any?>>
    if (!existing.isNullOrEmpty()) {
        return existing.map { m ->
            @Suppress("UNCHECKED_CAST")
            val competing = m["competing_demand_ids"] as? List<String>
            DominatorRef(
                kind = m["kind"] as? String ?: fallbackKind,
                productId = m["product_id"] as? String, locationId = m["location_id"] as? String,
                demandId = m["demand_id"] as? String, woGroupId = m["wo_group_id"] as? String,
                supplyId = m["supply_id"] as? String,
                competingDemandIds = competing,
                label = m["label"] as? String ?: "",
            )
        }
    }
    if (isRawLeaf(node)) {
        val pid = node["product_id"] as? String
        val lid = node["location_id"] as? String
        val sid = node["supply_id"] as? String
        return listOf(DominatorRef(
            kind = fallbackKind, productId = pid, locationId = lid, supplyId = sid,
            label = if (sid != null) "$pid@$lid ($sid)" else "$pid@$lid",
        ))
    }
    // A "demand" node with no children is a dead end planning gave up on entirely (e.g. the
    // "no_methods" terminal state) — never a leaf child collection to search. When that dead
    // end is itself raw+critical, IT is the real root cause for a QUANTITY shortfall (there's
    // nowhere left to chase: no supply lot exists here, and no method could ever produce one) —
    // report it directly, same shape as a raw supply leaf but with no supply_id (no lot was ever
    // reachable). Doesn't apply to time: nothing was ever committed at this dead end, so there
    // is no arrival date here to dominate anything.
    if (isQty && node["type"] == "demand" && (node["children"] as? List<*>).isNullOrEmpty()) {
        val pid = node["product_id"] as? String
        val lid = node["location_id"] as? String
        if (isRawCriticalPosition(pid, lid, data, config)) {
            return listOf(DominatorRef(
                kind = fallbackKind, productId = pid, locationId = lid,
                label = "$pid@$lid (no supply method)",
            ))
        }
    }
    @Suppress("UNCHECKED_CAST")
    val kids = node["children"] as? List<Map<String, Any?>> ?: emptyList()
    val contributingLeaves = kids.filter { isRawLeaf(it) && ((it["quantity"] as? Number)?.toDouble() ?: 0.0) > 1e-9 }
    if (contributingLeaves.isNotEmpty()) {
        // Quantity: several physical lots of the same material genuinely ARE an OR-group (each
        // partially covers the ask) — list them all. Time has exactly one dominator (only the
        // latest matters), so pick the single latest-dated lot rather than listing every lot's
        // own date.
        val selected = if (key == "time_dominator")
            listOfNotNull(contributingLeaves.maxByOrNull { parseDate(it["supply_date"] as? String) ?: LocalDate.MIN })
        else contributingLeaves
        return selected.map { l ->
            val sid = l["supply_id"] as? String
            val pidLid = "${l["product_id"]}@${l["location_id"]}"
            DominatorRef(
                kind = fallbackKind, productId = l["product_id"] as? String, locationId = l["location_id"] as? String,
                supplyId = sid, label = if (sid != null) "$pidLid ($sid)" else pidLid,
            )
        }
    }
    // Nothing raw+critical+exhausted among direct children of THIS node. Deliberately does not
    // search further — reconcile()'s own existing bottom-up recursion already visits every
    // work_order/demand child before its parent, and the "demand" case's own dominator
    // computation already calls rawDominatorRefs on EVERY child (not just supply leaves) — so a
    // work_order child that itself resolved a deeper cause during ITS OWN reconcile step is
    // already picked up there, via the ordinary existing-dominator propagation above, with no
    // extra traversal needed here.
    return emptyList()
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

/** AND-min: a make's children are ALL required together (one variant's childList — never a
 *  flattened multi-variant set now that variant selection is a waterfall candidate, not a
 *  same-call fan-out — see [planMethodSlot]'s "make" branch). */
private fun computeRawAchievable(
    childPassResults: List<ChildPassResult>,
    demandNetQty: Double,
): Double {
    return childPassResults.minOf { cr ->
        if (cr.neededQty > 1e-9) cr.effectiveQty * demandNetQty / cr.neededQty else demandNetQty
    }
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
            // perLotBudget caps individual lots present in the map. A demand only ever gets a
            // perLotBudget for a (product, location) it's under critical allocation for — so if
            // this specific lot's supplyId is missing, that's not "this material is uncontrolled,
            // draw freely" (that's what perLotBudget == null means), it's "this demand was never
            // granted this specific lot" — most commonly because allocateSuppliesPerLot's own
            // date-eligibility filter excluded it (e.g. a July-dated demand isn't eligible for
            // stock that arrives in August). Treating an absent-but-controlled lot as uncapped let
            // date-ineligible demands fall through to later lots with no limit at all once their
            // own eligible supply ran out. Forbid it instead — 0, not null.
            val lotCap = when {
                perLotBudget == null -> null
                sid == null -> null
                perLotBudget.containsKey(sid) -> perLotBudget[sid]
                else -> 0.0
            }
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

internal fun childMaterialsForMove(method: Map<String, Any?>, quantity: Double): List<Map<String, Any?>> =
    listOf(mapOf(
        "product_id" to (method["product_id"] ?: ""),
        "location_id" to (method["from_location_id"] ?: ""),
        "quantity" to quantity,
    ))

/** One candidate in the unified waterfall: a method (make/move/purchase), and — for a "make"
 *  with multiple BOM alt_groups — which alt_group this candidate represents. Null altKey for
 *  move/purchase (no variant concept) and single-alt_group makes. */
internal data class WaterfallCandidate(val method: Map<String, Any?>, val altKey: String?)

/**
 * Expand [methods] into the unified waterfall's flat candidate list: a "make" method with
 * multiple BOM alt_groups becomes one candidate per alt_group (each inheriting the method's own
 * `preference` — ranking happens at the call site); move/purchase pass through as a single
 * candidate each. Customer BOM-alternative constraints (`config.constraints`) are applied here
 * at alt_group granularity — narrower than the method-level constraint filter already applied to
 * [methods] upstream, which only drops entire make methods that can't produce the pinned child
 * anywhere in their BOM.
 */
internal fun expandWaterfallCandidates(
    methods: List<Map<String, Any?>>,
    productId: String,
    demand: Map<String, Any?>,
    config: Map<String, Any?>?,
    data: Map<String, List<Map<String, Any?>>>,
): List<WaterfallCandidate> {
    val cust = demand["customer_id"]?.toString()?.trim()
    val constraintRules = if (cust.isNullOrEmpty()) emptyList()
        else parseConstraints(config).filter { it.customerId == cust && it.parent == productId.trim() }
    return methods.flatMap { m ->
        if (m["type"] != "make") return@flatMap listOf(WaterfallCandidate(m, null))
        val mLoc = (m["location_id"] as? String)?.trim() ?: ""
        // Only the alt_group structure is needed here — quantities are recomputed at the
        // real residual qty when a candidate is actually attempted (planMethodSlot's "make"
        // branch calls variantsForMake again with the live slotQty), so a placeholder qty
        // is fine for discovering which alt_groups exist.
        val variants = variantsForMake(productId, mLoc, 1.0, m, data)
        if (variants.size <= 1) return@flatMap listOf(WaterfallCandidate(m, variants.singleOrNull()?.first))
        val rule = constraintRules.firstOrNull { it.location.isBlank() || it.location == "*" || it.location == mLoc }
        val admitted = if (rule == null) variants else {
            val pinned = variants.filter { (_, childList) -> childList.any { c -> (c["product_id"] as? String)?.trim() == rule.child } }
            pinned.ifEmpty { variants }
        }
        admitted.map { (altKey, _) -> WaterfallCandidate(m, altKey) }
    }
}

/**
 * Preferences KB lookup for one waterfall candidate: consults [preferenceKb] (built by
 * [buildPreferenceKb] in PreferenceBuilder.kt, keyed the same way via [preferenceMethodKey])
 * before falling back to the raw CSV `preference` column — the per-alternative fallback the
 * Preferences feature is built around, so partial KB coverage degrades gracefully rather than
 * all-or-nothing per case. `preferenceKb == null` (no KB for this case) preserves today's
 * exact raw-preference behavior.
 */
internal fun kbPreference(
    productId: String,
    locationId: String,
    method: Map<String, Any?>,
    altKey: String?,
    preferenceKb: PreferenceKb?,
): Int {
    val fallback = (method["preference"] as? Number)?.toInt() ?: Int.MAX_VALUE
    if (preferenceKb == null) return fallback
    return preferenceKb.entries[Triple(productId, locationId, preferenceMethodKey(method, altKey))]?.preference ?: fallback
}

// ── Diamond allocation: OR-group grand-parent recipient caps, recomputed live per attempt ──────

/**
 * Downward counterpart to [findOrGroupRecipients]'s upward, structural walk: from a resolved
 * method (a specific waterfall candidate already chosen by the caller — [computeDiamondCapsForAttempt]
 * is what chooses it), finds which of [candidateRecipients] are reachable through THIS method's
 * own children. AND-mandatory children (a "make" variant's own multiple children) are all
 * explored, matching their simultaneous, deterministic visitation in the live AND-loop; nested
 * OR-choices further down explore EVERY candidate via [reachableRecipients] (union-all, not a
 * single guessed top choice) — a known recipient several unrelated OR-hops below this method
 * must never be missed just because some intermediate level's top-ranked pick differs from
 * whatever the live commit eventually resolves there. Confirmed necessary in practice: an
 * earlier single-top-choice version of this walk missed 280-1159's own AND-mandatory A1-D1
 * children entirely whenever an unrelated intermediate OR-choice several levels up picked
 * differently, silently falling back to the unreliable general `andSiblingCaps` mechanism this
 * whole function exists to avoid. Stops descending at a matched recipient (it IS the collapsing
 * point) but keeps exploring sibling AND-branches for other recipients.
 */
private fun reachableRecipientsForMethod(
    pid: String,
    lid: String,
    method: Map<String, Any?>,
    altKey: String?,
    candidateRecipients: Set<String>,
    demand: Map<String, Any?>,
    config: Map<String, Any?>?,
    data: Map<String, List<Map<String, Any?>>>,
    preferenceKb: PreferenceKb?,
    visited: MutableSet<Pair<String, String>>,
): Set<String> {
    if (pid in candidateRecipients) return setOf(pid)
    val found = mutableSetOf<String>()
    when (method["type"]) {
        "move" -> {
            val fromLid = (method["from_location_id"] as? String)?.trim()
            if (fromLid != null) {
                found += reachableRecipients(pid, fromLid, candidateRecipients, demand, config, data, preferenceKb, visited)
            }
        }
        "make" -> {
            val mLoc = (method["location_id"] as? String)?.trim() ?: lid
            val variants = variantsForMake(pid, mLoc, 1.0, method, data)
            val chosen = if (altKey != null) variants.filter { it.first == altKey } else variants
            for ((_, children) in chosen) {
                for (child in children) {
                    val cPid = (child["product_id"] as? String)?.trim() ?: continue
                    val cLid = (child["location_id"] as? String)?.trim() ?: continue
                    found += reachableRecipients(cPid, cLid, candidateRecipients, demand, config, data, preferenceKb, visited)
                }
            }
        }
        // "purchase": elastic, no children — never itself a recipient path.
    }
    return found
}

/**
 * Explores EVERY candidate at (pid, lid) — union-all reachability, not "guess the live
 * commit's single top choice" — and delegates each to [reachableRecipientsForMethod]. This is
 * deliberately more permissive than the live commit's own actual resolution: a KNOWN recipient
 * (e.g. an AND-mandatory child several intermediate OR-hops down, like 280-1159's own A1-D1)
 * must never be missed just because the top-ranked choice at some UNRELATED intermediate level
 * happens to differ from the live commit's own eventual pick — under-reaching here silently
 * falls back to the general (and, for this exact shape, unreliable — see
 * [computeDiamondCapsForAttempt]'s own doc) `andSiblingCaps` mechanism, which is far worse than
 * the comparatively harmless cost of over-including a candidate the live commit ends up not
 * visiting (that candidate's computed cap simply goes unused). Cycle-guarded via [visited],
 * shared with the caller's own walk so re-entering an already-open frame (e.g. a two-location
 * move cycle) safely yields no match instead of recursing forever.
 */
private fun reachableRecipients(
    pid: String,
    lid: String,
    candidateRecipients: Set<String>,
    demand: Map<String, Any?>,
    config: Map<String, Any?>?,
    data: Map<String, List<Map<String, Any?>>>,
    preferenceKb: PreferenceKb?,
    visited: MutableSet<Pair<String, String>>,
): Set<String> {
    if (candidateRecipients.isEmpty()) return emptySet()
    if (pid in candidateRecipients) return setOf(pid)
    val key = pid to lid
    // Path-scoped cycle guard, NOT a global "ever visited" memo: this BOM has heavily shared
    // sub-assemblies (e.g. 280-1159 is reached from several unrelated higher-level components),
    // so the SAME (pid, lid) can legitimately appear on many different, non-cyclic paths within
    // one top-level search. Must un-mark on the way back out (try/finally, matching
    // gatherAndSiblingRequests's own accumulate/discover pattern) — leaving it marked for the
    // rest of the search (as an earlier version of this function did) causes the first branch
    // explored to permanently block every other legitimate path through the same shared node,
    // silently losing real recipients.
    if (!visited.add(key)) return emptySet()
    try {
        val methods = getMethods(pid, lid, data)
        if (methods.isEmpty()) return emptySet()
        val found = mutableSetOf<String>()
        for (candidate in expandWaterfallCandidates(methods, pid, demand, config, data)) {
            found += reachableRecipientsForMethod(pid, lid, candidate.method, candidate.altKey, candidateRecipients, demand, config, data, preferenceKb, visited)
        }
        return found
    } finally {
        visited.remove(key)
    }
}

/**
 * Live, per-waterfall-candidate-attempt counterpart to [findOrGroupRecipients]'s structural
 * discovery — this is what makes diamond allocation "embedded" in the planner's own loop rather
 * than a separate, static pre-pass (this session's explicit design mandate). Called once by
 * [plan]'s root-split/waterfall candidate loop, immediately before EACH candidate is attempted:
 * given that candidate's own method/altKey (already chosen by the caller) and the quantity it's
 * about to be asked for, finds which of the structurally-known recipients for each critical
 * material are reachable through THIS SPECIFIC candidate's own subtree, then splits whatever
 * entitlement remains (the demand's total [diamondCriticalEntitlement] for that material, MINUS
 * whatever ANY of that material's recipients have already actually consumed so far this demand —
 * read live from [diamondRecipientConsumed]) evenly across however many recipients are found.
 *
 * This single mechanism handles both shapes from this session's toy example automatically:
 * - Recipients simultaneously reachable from ONE candidate (e.g. A1 and A3, both AND-mandatory
 *   children of the same "make" choice) are genuinely split evenly in ONE call — correct, since
 *   both WILL be visited together in this one pass. Behaviorally identical to the old, static,
 *   hardcoded 50/50 [findOrGroupRecipients] shape for 160-1153.
 * - Recipients that only appear behind DIFFERENT, mutually exclusive waterfall candidates (e.g.
 *   P vs P') each get their OWN separate call — once per candidate the outer loop tries, each
 *   time reading whatever [diamondRecipientConsumed] currently shows. A candidate that falls
 *   short of its target simply leaves more of the pool for the NEXT candidate's own, later call
 *   — the waterfall loop's own iteration over candidates with shrinking `slotQty`/`residual` IS
 *   the "rerun with leftover quantity," with no separate rerun mechanism needed.
 */
internal fun computeDiamondCapsForAttempt(
    productId: String,
    locationId: String,
    method: Map<String, Any?>,
    altKey: String?,
    demand: Map<String, Any?>,
    config: Map<String, Any?>?,
    data: Map<String, List<Map<String, Any?>>>,
    preferenceKb: PreferenceKb?,
    diamondRecipients: Map<String, Set<String>>,
    diamondCriticalEntitlement: Map<String, Map<String, Double>>,
    diamondRecipientConsumed: Map<String, MutableMap<String, Double>>,
): Map<String, Map<String, Double>> {
    if (diamondRecipients.isEmpty() || diamondCriticalEntitlement.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, MutableMap<String, Double>>()
    for ((criticalPid, recipients) in diamondRecipients) {
        val totalEntitlement = diamondCriticalEntitlement[criticalPid] ?: continue
        if (totalEntitlement.isEmpty()) continue
        val found = reachableRecipientsForMethod(
            productId, locationId, method, altKey, recipients, demand, config, data, preferenceKb, mutableSetOf(),
        )
        if (found.isEmpty()) continue
        val n = found.size
        for ((lotKey, totalQty) in totalEntitlement) {
            // Remaining pool = total entitlement minus what's ALREADY been consumed by ANY of
            // this critical material's known recipients so far (not just the ones found in
            // THIS attempt) — so re-descending into the same diamond later in the same demand
            // still respects the original total, not a re-inflated one.
            val consumedSoFar = recipients.sumOf { r -> diamondRecipientConsumed[r]?.get(lotKey) ?: 0.0 }
            val remaining = (totalQty - consumedSoFar).coerceAtLeast(0.0)
            val share = remaining / n
            for (recipient in found) {
                result.getOrPut(recipient) { mutableMapOf() }[lotKey] = share
            }
        }
    }
    return result
}

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
 * Partitions all method_buy products into (rawBuyables, nonRawBuyables) by checking
 * productlocation.prod_area.  Used by [effectivePurchasableSet] so the rawIds scan over
 * productlocation is done exactly once per callsite.
 *
 * @return Pair(rawBuyables, nonRawBuyables)
 *   rawBuyables    — products with method_buy AND prod_area='raw'  (the "Purchase allowed" candidates)
 *   nonRawBuyables — products with method_buy AND prod_area≠'raw'  (purchased sub-assemblies, etc.)
 */
internal fun partitionBuyables(data: Map<String, List<Map<String, Any?>>>): Pair<Set<String>, Set<String>> {
    val rawIds = (data["productlocation"] ?: emptyList())
        .filter { (it["prod_area"] as? String)?.trim() == "raw" }
        .mapNotNull { (it["product_id"] as? String)?.trim() }
        .toHashSet()
    val rawBuyables    = mutableSetOf<String>()
    val nonRawBuyables = mutableSetOf<String>()
    for (row in (data["method_buy"] ?: emptyList())) {
        val pid = (row["product_id"] as? String)?.trim() ?: continue
        if (pid.isEmpty()) continue
        if (pid in rawIds) rawBuyables.add(pid) else nonRawBuyables.add(pid)
    }
    return rawBuyables to nonRawBuyables
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
    val (_, nonRawBuyables) = partitionBuyables(data)
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
    budget: MutableMap<String, Double>?,
    /** For a "make" method with multiple BOM alt_groups: which one this call plans — chosen
     *  upstream by the unified waterfall candidate list, one candidate per alt_group. Null
     *  for move/purchase (no variant concept) and single-alt_group make methods. */
    selectedAltKey: String?,
    methodChoiceExplanation: String,
    feasibilityCache: MutableMap<Pair<String, String>, Int>? = null,
    structuralFailedMakes: MutableMap<Pair<String, String>, String>? = null,
    initialBudget: Map<String, Double>? = null,
    /** See [plan]'s doc — same cumulative, never-restored, demand-wide consumption tracker. */
    demandConsumed: MutableMap<String, Double>? = null,
    nodeQtyCaps: Map<Pair<String, String>, Double>? = null,
    demandBlueprint: DemandBlueprint? = null,
    preferenceKb: PreferenceKb? = null,
    /** See [plan]'s doc — the whole per-demand intra-demand sibling-contention table. */
    andSiblingCaps: Map<BranchKey, Map<String, Double>>? = null,
    /** See [plan]'s doc — active cap for the branch this call is currently inside, if any. */
    branchLotCap: Map<String, Double>? = null,
    /** See [plan]'s doc — paired, never-restored, per-branch consumption tally. */
    branchConsumed: MutableMap<String, Double>? = null,
    /** See [plan]'s doc — lineage of enclosing branch identities, for matching
     *  [andSiblingCaps] lookups against branches nested under a specific outer sibling. */
    branchLineage: String = "",
    /** See [plan]'s doc — the whole per-demand intra-demand sibling-contention DOMINATOR table. */
    andSiblingDominators: Map<BranchKey, List<DominatorRef>>? = null,
    /** See [plan]'s doc — freshly resolved dominator for the branch this call is currently
     *  inside, if any. Never inherited from an enclosing call — see [plan]'s doc for why. */
    branchDominator: List<DominatorRef>? = null,
    /** See [plan]'s doc — live, per-waterfall-candidate-attempt caps for whichever OR-group
     *  recipients this SPECIFIC candidate's own subtree reaches, keyed by product_id, not
     *  lineage. Recomputed fresh by [plan]'s waterfall loop before each candidate attempt — see
     *  [computeDiamondCapsForAttempt]'s own doc. */
    diamondRecipientCaps: Map<String, Map<String, Double>>? = null,
    /** See [plan]'s doc — demand-wide-persistent consumption pool for the diamond recipients,
     *  keyed the same fixed way, so every occurrence of a recipient within one demand shares it. */
    diamondRecipientConsumed: MutableMap<String, MutableMap<String, Double>>? = null,
    /** See [plan]'s doc — structural (BOM-topology-only) OR-group recipient sets per critical
     *  material, from [findOrGroupRecipients]. Global — identical for every demand. */
    diamondRecipients: Map<String, Set<String>>? = null,
    /** See [plan]'s doc — this demand's raw (unsplit) entitlement per critical material that
     *  has known [diamondRecipients], from [buildDiamondCriticalEntitlement]. */
    diamondCriticalEntitlement: Map<String, Map<String, Double>>? = null,
): MethodSlotResult {
    val productionLocation = (if (m["type"] == "move") m["to_location_id"] else m["location_id"])?.toString() ?: locationId
    val reqDt = parseDate(reqTimeStr) ?: requestTimeDt ?: LocalDate.now()
    val leadDays = leadDaysForMethod(m, productId, productionLocation, slotQty, data)

    // 3) Child materials
    var woChildrenRelation: String? = null
    val (childMaterials, variantExplanation) = when (m["type"]) {
        "make" -> {
            // The unified waterfall candidate list (built once per demand, in plan()) already
            // expanded each BOM alt_group into its own ranked candidate — customer-constraint
            // filtering included — so this is a direct pick, not a scoring/equal-split step.
            // selectedAltKey is null only for a single-alt_group make, where there's nothing to
            // pick among.
            val rawVariants = variantsForMake(productId, productionLocation, slotQty, m, data)
            val chosen = if (selectedAltKey != null) rawVariants.filter { (altKey, _) -> altKey == selectedAltKey } else rawVariants
            val cm = chosen.flatMap { (_, childList) -> childList }
            // Single variant with multiple BOM children → AND group (all required together).
            if (chosen.size == 1 && chosen[0].second.size > 1) woChildrenRelation = "and"
            val ve = chosen.singleOrNull()?.let { (altKey, childList) ->
                "Variant ALT_GROUP=$altKey (${childList.size} component(s))."
            } ?: "No matching variant for ALT_GROUP=$selectedAltKey."
            Pair(cm, ve)
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
    // Parallel to commitTimes — retains which child produced each date, so computeStartDt can
    // report "latest time dominates" (which BOM child pushed this WO's own start out).
    val commitTimesWithSource = mutableListOf<Pair<LocalDate, ChildPassResult>>()
    val childPeggingNodes = mutableListOf<Map<String, Any?>>()

    // Qty-only snapshot: captures just the qty values in position order.
    // In-place restore (restoreQtys) keeps IndexedInventory.idx pointers valid
    // across rollbacks — no object replacement, same MutableMaps throughout.
    val inventorySnap = snapshotQtys(inventory)
    val budgetSnap: Map<String, Double>? = budget?.toMap()
    // Paired with demandConsumed/branchConsumed's restore below: those trackers are
    // deliberately NOT touched by the inventory/budget restore they sit next to (see that
    // restore's own comment) — permanent, cross-sibling accounting is the whole point of
    // demandConsumed. But when THIS candidate method is abandoned wholesale (capped <= 1e-9
    // below), every draw made while exploring its children — at any depth — must unwind
    // together, consumed-trackers included, or a later, unrelated candidate/retry at this
    // same node inherits a phantom demand-wide charge for units that were never actually
    // committed anywhere. Restoring only inventory+budget while leaving demandConsumed
    // permanently charged can zero out an entire demand even when a genuinely achievable
    // draw exists — the abandoned candidate's exploratory consumption silently exhausts the
    // shared cap before the real, kept candidate ever gets a turn.
    val demandConsumedSnap: Map<String, Double>? = demandConsumed?.toMap()
    val branchConsumedSnap: Map<String, Double>? = branchConsumed?.toMap()

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
        // Intra-demand sibling contention (the "diamond" fix): if this child is one of an
        // AND-parent's simultaneously-active siblings that Phase 2 (computeAndSiblingCaps)
        // found contending for a shared critical material, start a fresh per-branch cap and
        // consumption tally for it. Otherwise inherit whatever branch cap this call is
        // already inside (an ordinary, non-contending pass-through).
        val cPid = (c["product_id"] as? String)?.trim() ?: ""
        val cLid = (c["location_id"] as? String)?.trim() ?: ""
        // Lookup key always embeds the CURRENT (not-yet-extended) lineage — matches exactly
        // what gatherAndSiblingRequests used when it created this child's own branch, if any
        // (see BranchKey/combineSlot's doc). Whether lineage gets EXTENDED for the recursive
        // call below depends on isAndGroupChild — see that flag's own comment.
        // OR-group grand-parent diamond recipients (findOrGroupRecipients/
        // computeDiamondCapsForAttempt) take precedence over the general andSiblingCaps lookup —
        // identified by product identity, not lineage, so every occurrence within this demand
        // shares the SAME pool instead of each getting an independent, lineage-derived one. See
        // computeDiamondCapsForAttempt's own doc for why the general andSiblingCaps mechanism is
        // unreliable for this shape.
        val diamondCap = diamondRecipientCaps?.get(cPid)
        val childAndCap = diamondCap ?: andSiblingCaps?.get(BranchKey(cPid, cLid, combineSlot(branchLineage, null)))
        val cBranchLotCap = childAndCap ?: branchLotCap
        val cBranchConsumed = if (diamondCap != null) {
            diamondRecipientConsumed?.getOrPut(cPid) { mutableMapOf() } ?: mutableMapOf()
        } else if (childAndCap != null) mutableMapOf<String, Double>() else branchConsumed
        // Freshly resolved for THIS child only — never inherited from the enclosing branchDominator
        // (see plan()'s own doc for why: a dominator is an explanatory tag scoped to the exact node
        // where the constraint was detected, not a composable budget).
        val cBranchDominator = andSiblingDominators?.get(BranchKey(cPid, cLid, combineSlot(branchLineage, null)))
        // Only extend lineage descending into a genuine AND-group (>1 children):
        // gatherAndSiblingRequests's own isAndGroup check gates branch creation
        // identically, so a single-child pass-through (a "move" method's sole source, or a
        // "make" with just one BOM child) never got its own branch/lineage segment on the
        // gather side either — extending lineage here too would desync from what any REAL
        // nested fanout further down was actually tagged with.
        val isAndGroupChild = activeChildren.size > 1
        val cBranchLineage = if (isAndGroupChild) extendLineage(branchLineage, cPid, cLid) else branchLineage
        val (solvedList, cWos, cPegging) = plan(cDemand, inventory, data, cReqDt, depth = depth - 1, planningPath = path, config = config, preferDemandId = preferDemandId, budget = budget, feasibilityCache = feasibilityCache, structuralFailedMakes = structuralFailedMakes, initialBudget = initialBudget, demandConsumed = demandConsumed, nodeQtyCaps = nodeQtyCaps, demandBlueprint = demandBlueprint, preferenceKb = preferenceKb, andSiblingCaps = andSiblingCaps, branchLotCap = cBranchLotCap, branchConsumed = cBranchConsumed, branchLineage = cBranchLineage, andSiblingDominators = andSiblingDominators, branchDominator = cBranchDominator, diamondRecipientCaps = diamondRecipientCaps, diamondRecipientConsumed = diamondRecipientConsumed, diamondRecipients = diamondRecipients, diamondCriticalEntitlement = diamondCriticalEntitlement)
        // Prefer the unrounded "quantity_precise" (see committedRow's doc) over the rounded
        // "quantity" — this feeds the AND-min ratio (computeRawAchievable) below, and reading
        // the rounded value here would let a genuinely-achieved fractional child (e.g. 0.4999
        // out of 0.5 needed) register as a hard 0, collapsing the whole AND-group even though
        // the child came within a rounding hair of fully succeeding.
        val effectiveQty = solvedList.sumOf { s ->
            val r = s["commit_reason"] as? String
            if (r == "cycle_stopped" || r == "cycle_detected") 0.0
            else if (!isHardPlanningFailure(r)) ((s["quantity_precise"] as? Number) ?: (s["quantity"] as? Number))?.toDouble() ?: 0.0
            else 0.0
        }
        val cTimes = solvedList.mapNotNull { s -> parseDate(s["commit_time"] as? String) }
        // Tag gc_bom_rate on the demand node so GCEngine can scale into AND-children
        // recursively without needing the original BOM table. Rate = child_need / parent_slotQty.
        val bomRate = if (activeSlotQty > 1e-9) neededQty / activeSlotQty else 0.0
        // Intra-demand sibling contention (step c), applied HERE rather than relying solely on
        // plan()'s own nodeCap-gated tagging site: that site only fires when the SKETCH phase's
        // independent nodeQtyCaps prediction also flags a shortfall at this exact node, but
        // computeAndSiblingCaps exists precisely BECAUSE the sketch phase doesn't track
        // cross-branch consumption — in the canonical diamond-fairness case, nodeQtyCaps predicts
        // every AND-sibling "fully achievable" on its own (each independently sees the full,
        // unreduced entitlement), so that gate never fires at all for the very shape this fix
        // targets. Here, the shortfall is verified directly (effectiveQty < neededQty) at the
        // exact point Phase 2's fair-split cap was applied to this child, so no such gap exists.
        val cHadShortfall = effectiveQty < neededQty - 1e-9
        // Only fill in an axis cPegging doesn't already carry its own answer for — never
        // overwrite. cPegging may already carry a MORE SPECIFIC, correctly-computed dominator
        // from its own deeper resolution (e.g. planMethodSlot's own ratio-based
        // bomChildDominatorRefs comparison one level down, which correctly identifies the true
        // least-achieving AND-sibling by comparing effectiveQty/neededQty ratios across ALL of
        // ITS OWN children — not just whichever one happens to touch this shared material).
        // Blindly overwriting that with cBranchDominator — a per-branch guess computed once,
        // up front, before any live commit happens — would replace a verified, specific cause
        // with a coarser one, and that mistake then survives verbatim through every AND-min
        // collapse above it (reconcile() trusts an already-set dominator and never re-compares
        // it against sibling candidates). Traced live on 858_F35_2024_07_VIRTUAL: this exact
        // clobber replaced 260-0141-02@1000's own correct dominator (280-1459/280-1460, the
        // true minimum-ratio AND-sibling) with 160-1153, which happened to be a merely-tied,
        // much-less-constrained sibling elsewhere in the same group.
        @Suppress("UNCHECKED_CAST")
        val cPeggingHasOwnQtyDominator = !(cPegging?.get("quantity_dominator") as? List<*>).isNullOrEmpty()
        @Suppress("UNCHECKED_CAST")
        val cPeggingHasOwnTimeDominator = !(cPegging?.get("time_dominator") as? List<*>).isNullOrEmpty()
        val dominatorOverride: Map<String, Any?> = if (!cBranchDominator.isNullOrEmpty() && cHadShortfall) {
            buildMap {
                if (!cPeggingHasOwnQtyDominator) put("quantity_dominator", cBranchDominator.toJsonList())
                if (!cPeggingHasOwnTimeDominator) put("time_dominator", cBranchDominator.toJsonList())
            }
        } else emptyMap()
        val taggedPegging = cPegging?.plus("gc_bom_rate" to bomRate)?.plus(dominatorOverride)
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
    // "Least quantity dominates": the tied-min sibling(s) that capped this WO's achievable
    // qty below what it asked for — populated only in the shortage/GC branch below (the
    // no-shortage branch leaves this empty since nothing constrained the parent here).
    var qtyDominatorForWoNode: List<DominatorRef> = emptyList()
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
            commitTimesWithSource.addAll(cr.cTimes.map { it to cr })
        }
    } else {
        val rawAchievable = computeRawAchievable(childPassResults, activeSlotQty)
        // No floor() here: this AND-min collapse runs once per intermediate BOM level, and a
        // deep tree (7-8 levels) compounds each level's fractional loss — flooring at every
        // level can silently zero out a genuinely-fillable branch (e.g. eight levels each
        // losing <1 unit) even when nothing is actually out of stock. The physical
        // lot-quantization (whole-unit WOs) happens exactly once, downstream, in
        // buildWorkOrders()'s roundQty(lotQty) — that's the only place fractional asks need to
        // become real, ship-able quantities.
        val capped = if (rawAchievable >= activeSlotQty - 1e-6) activeSlotQty
                     else rawAchievable.coerceIn(0.0, activeSlotQty)

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
                // Budget (allocation cap) is static; a restore must never resurrect more
                // remaining allowance than this demand was ever granted for a given lot.
                // Without this clamp, a blanket snapshot/restore across independent sibling
                // branches can "un-spend" budget that a different, already-concluded branch
                // legitimately consumed — letting this demand overdraw a shared, scarce
                // material well past its fair share.
                if (initialBudget != null) {
                    for (key in budget.keys) {
                        val cap = initialBudget[key] ?: continue
                        val v = budget[key]
                        if (v != null && v > cap) budget[key] = cap
                    }
                }
                // Same defensive clamp, one level narrower: this branch's own fair-share cap.
                if (branchLotCap != null) {
                    for (key in budget.keys) {
                        val cap = branchLotCap[key] ?: continue
                        val v = budget[key]
                        if (v != null && v > cap) budget[key] = cap
                    }
                }
            }
            // Undo the permanent demand-wide/branch-wide consumption charges this abandoned
            // candidate's children racked up — see demandConsumedSnap's doc above. Without
            // this, an earlier candidate/retry at this same node that happened to draw real
            // budget from a scarce shared material (before failing for an unrelated reason
            // elsewhere in its own AND-list) permanently poisons that material's remaining
            // allowance for every later candidate, even the one that ultimately gets kept.
            if (demandConsumed != null && demandConsumedSnap != null) {
                demandConsumed.clear()
                demandConsumed.putAll(demandConsumedSnap)
            }
            if (branchConsumed != null && branchConsumedSnap != null) {
                branchConsumed.clear()
                branchConsumed.putAll(branchConsumedSnap)
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
                failed = true,
                data = data,
                // "Least quantity dominates": the tied-min sibling(s) that blocked this WO
                // entirely — same set already used for is_bottleneck, just captured as a
                // pointer on the constrained PARENT instead of a flag on the constraining child.
                quantityDominator = bomChildDominatorRefs(childPassResults, blockedBottleneckKeys, data, config),
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

        run {
            // ── Identify AND-bottleneck child(ren). Only meaningful when the
            // parent was actually capped by an AND-min (achievableParentQty <
            // slotQty). The bottleneck is the child(ren) whose first-pass
            // ratio (effectiveQty / neededQty) equals the min over all
            // children. Tied children are all flagged. Empty when the parent
            // delivered its full request (no cap).
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
            qtyDominatorForWoNode = bomChildDominatorRefs(childPassResults, bottleneckPegging.keys, data, config)

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
                commitTimesWithSource.addAll(cr.cTimes.map { it to cr })
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
    val (startDt, startDtDominator) = computeStartDt(reqDt, leadDays, commitTimesWithSource, data, config)
    val woResult = buildWorkOrders(productId, productionLocation, achievableParentQty, leadDays, startDt, m, demandId, data)
    val wos = woResult.wos
    val lotCount = woResult.lotCount
    val lastEnd = woResult.lastEnd
    val lotSizeVal = woResult.lotSizeVal
    val methodType = m["type"] as? String ?: ""
    // Purchase leaves have no source supply record (they are new procurement),
    // so surface the vendor as their identifier in the supply-leaf table.
    val woChildren = if (methodType == "purchase") listOf(mapOf("type" to "purchase", "product_id" to productId, "location_id" to productionLocation, "quantity" to roundQty(achievableParentQty), "quantity_precise" to achievableParentQty, "vendor_id" to m["vendor_id"], "start_time" to formatDate(startDt), "end_time" to formatDate(lastEnd), "children" to emptyList<Any>()))
                     else childPeggingNodes
    val methodPeggingNode = buildWoNode(productId, productionLocation, achievableParentQty, methodType, m, startDt, lastEnd, lotCount, lotSizeVal, methodChoiceExplanation, variantExplanation, woChildrenRelation, woChildren, woGroupId = woResult.woGroupId, data = data, quantityDominator = qtyDominatorForWoNode, timeDominator = startDtDominator)

    return MethodSlotResult(
        achievableQty = achievableParentQty,
        wos = wos + childWos,
        methodPeggingNode = methodPeggingNode,
        latestCommit = lastEnd,
        anyChildShort = anyChildShort,
        blockedReason = null,
    )
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
     * Cumulative, PERMANENT record of how much of [initialBudget] this demand has actually
     * consumed from each lot, across its ENTIRE exploration — deliberately created once per
     * top-level demand and never included in [planMethodSlot]'s snapshot/restore. `budget`
     * (the mutable per-branch tracker) is correctly restored on a failed branch's own local
     * rollback, but when multiple structurally-independent parts of the SAME demand's tree
     * (e.g. two different sub-assemblies that each happen to route through the same scarce,
     * non-purchasable material) each succeed on their own, `budget` alone has no way to see
     * that a sibling branch already spent part of the shared cap — each one's own entry
     * snapshot legitimately shows the lot at full strength, so each draws independently, and
     * the sum can exceed the demand's true, single per-lot allocation. This map is the fix:
     * `initialBudget[lot] - demandConsumed[lot]` is the true, demand-wide remaining allowance,
     * immune to any branch-local restore. Threaded through unchanged to every recursive call.
     */
    demandConsumed: MutableMap<String, Double>? = null,
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
     * the unified waterfall's own candidate expansion/ranking.
     * Threaded through all recursive plan() / planMethodSlot() calls.
     */
    demandBlueprint: DemandBlueprint? = null,
    /**
     * Optional Preferences KB override: (productId, locationId, methodKey) → canonical
     * preference (10, 20, 30, ...), built once via the Preferences page's Generate action
     * (see [buildPreferenceKb] in PreferenceBuilder.kt). When non-null, [kbPreference]
     * consults it per-alternative before falling back to the raw CSV `preference` column —
     * partial coverage degrades gracefully, alternative by alternative. `null` (the default)
     * preserves today's exact raw-preference behavior. Threaded through all recursive
     * plan() calls.
     */
    preferenceKb: PreferenceKb? = null,
    /**
     * Optional intra-demand sibling-contention table from [computeAndSiblingCaps] (the
     * "diamond problem" fix) — the WHOLE per-demand table, already sliced to this one
     * demand's own branches by [legacyCommit]. Keyed by [BranchKey]: an AND-parent's own
     * (productId, locationId) child, or a root-split candidate. When a recursive call
     * enters one of these branches (see [planMethodSlot]'s AND-loop, and this function's
     * own root-split waterfall loop), it starts a fresh [branchLotCap]/[branchConsumed]
     * pair sourced from the matching entry here. Threaded through unchanged to every
     * recursive plan() / planMethodSlot() call — contention can occur at any depth.
     */
    andSiblingCaps: Map<BranchKey, Map<String, Double>>? = null,
    /**
     * Active per-lot cap for the branch THIS call is currently inside, if any — composes
     * with [initialBudget]/[demandConsumed] (a third, tighter `min()` term) so a branch that
     * Phase 2 fair-split found contending for a shared critical material can't draw past its
     * own share of the demand-wide cap, even though [initialBudget] alone would allow it
     * (that cap governs the whole demand, not one branch of it). `null` when this call isn't
     * inside any capped branch.
     */
    branchLotCap: Map<String, Double>? = null,
    /**
     * Paired with [branchLotCap]: cumulative, PERMANENT (never snapshot/restored) record of
     * how much of it this branch has actually consumed so far — same rationale as
     * [demandConsumed], one level narrower in scope. Freshly created whenever a call enters
     * a NEW capped branch; inherited unchanged while staying inside the same one.
     */
    branchConsumed: MutableMap<String, Double>? = null,
    /**
     * Chain of enclosing branch identities ("pid@lid" segments joined by ">"), built by
     * [extendLineage] exactly as [gatherAndSiblingRequests] built it on the gather side.
     * Needed because [BranchKey] alone (productId, locationId, slot) collapses two
     * structurally identical subtrees reached via DIFFERENT outer AND-siblings — e.g. two
     * siblings that both happen to route through the same shared sub-assembly — into one
     * indistinguishable key. `andSiblingCaps` lookups (in [planMethodSlot]'s AND-loop and
     * this function's own root-split loop) embed the current lineage into the key via
     * [combineSlot], and extend it via [extendLineage] for whichever child/candidate they're
     * about to recurse into — mirroring precisely which points the gather phase treats as
     * "a new branch was created". `""` (the default) at the top of a fresh demand.
     */
    branchLineage: String = "",
    /** [computeAndSiblingCaps]'s step (c) — the dominator-side twin of [andSiblingCaps],
     *  keyed and threaded the same way (whole per-demand table, unchanged through every
     *  recursive call). */
    andSiblingDominators: Map<BranchKey, List<DominatorRef>>? = null,
    /**
     * Freshly resolved (NOT inherited) dominator for the branch THIS call is currently
     * inside, if step (c) flagged it as constrained — see the two lookup sites in
     * [planMethodSlot]'s AND-loop and this function's own root-split loop. Unlike
     * [branchLotCap] (a composable numeric budget that legitimately narrows an entire
     * subtree and is correct to fall back to at every depth), a dominator is an
     * EXPLANATORY TAG scoped to the exact node where the constraint was detected —
     * inheriting it into unrelated deeper descendants would misattribute their own,
     * possibly unrelated shortfalls to this branch's cause. So this is `null` unless the
     * IMMEDIATE child/candidate being entered is itself one of step (c)'s flagged
     * branches — never a fallback to the enclosing call's own value.
     */
    branchDominator: List<DominatorRef>? = null,
    /**
     * OR-group grand-parent recipient caps for whichever critical materials the CURRENT
     * waterfall candidate attempt actually reaches — generalizes the former hardcoded 160-1153
     * shape ([findOrGroupRecipients]) to any critical material reached through an OR-group.
     * Recomputed FRESH by this function's own root-split/waterfall candidate loop before each
     * candidate is tried (see [computeDiamondCapsForAttempt]'s own doc for why this must be
     * live, per-attempt, rather than a static per-demand map): a candidate that only partially
     * succeeds leaves more of the demand's own entitlement for the NEXT candidate's own, later,
     * fresh computation — the waterfall loop's own iteration IS the "rerun with leftover
     * quantity." Keyed by product_id (not lineage) — inherited unchanged by this candidate's
     * OWN recursive descent (AND-loop, deeper waterfall levels), since they're all still
     * inside the SAME one candidate attempt.
     */
    diamondRecipientCaps: Map<String, Map<String, Double>>? = null,
    /**
     * Demand-wide-persistent pool of consumption, keyed the same fixed way as
     * [diamondRecipientCaps] — created once per demand in [legacyCommit], alongside
     * [demandConsumed], so every occurrence of a given recipient within one demand's tree
     * shares the SAME tally instead of each getting a fresh, disconnected one (the failure
     * mode of the general [andSiblingCaps] mechanism for this shape), and so
     * [computeDiamondCapsForAttempt]'s live "remaining entitlement" computation at a later
     * candidate attempt correctly sees what an earlier one already drew.
     */
    diamondRecipientConsumed: MutableMap<String, MutableMap<String, Double>>? = null,
    /** Structural (BOM-topology-only) OR-group recipient sets per critical material, from
     *  [findOrGroupRecipients]. Global — identical for every demand, computed once in
     *  [runPlanning]. `null`/empty when no critical material has an OR-group ancestor. */
    diamondRecipients: Map<String, Set<String>>? = null,
    /** This demand's raw (unsplit) entitlement per critical material that has known
     *  [diamondRecipients], from [buildDiamondCriticalEntitlement] — the pool
     *  [computeDiamondCapsForAttempt] splits live, per candidate attempt. Deliberately NOT
     *  pre-split (unlike the old [diamondRecipientCaps]'s predecessor): recipients gated
     *  behind a shared ancestor OR-choice are mutually exclusive, so pre-splitting between them
     *  would be wrong — only the live, per-attempt computation knows which is actually being
     *  visited right now. */
    diamondCriticalEntitlement: Map<String, Map<String, Double>>? = null,
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
        val (fulfilled, wos, peggingNode) = plan(
            demand           = demand + mapOf("quantity" to nodeCap),
            inventory        = inventory,
            data             = data,
            requestTimeDt    = requestTimeDt,
            depth            = depth,
            planningPath     = planningPath,
            config           = config,
            preferDemandId   = preferDemandId,
            budget           = budget,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
            initialBudget    = initialBudget,
            demandConsumed   = demandConsumed,
            nodeQtyCaps      = nodeQtyCaps,
            demandBlueprint  = demandBlueprint,
            andSiblingCaps   = andSiblingCaps,
            branchLotCap     = branchLotCap,
            branchConsumed   = branchConsumed,
            branchLineage    = branchLineage,
            andSiblingDominators = andSiblingDominators,
            branchDominator  = branchDominator,
            diamondRecipientCaps = diamondRecipientCaps,
            diamondRecipientConsumed = diamondRecipientConsumed,
            diamondRecipients = diamondRecipients,
            diamondCriticalEntitlement = diamondCriticalEntitlement,
        )
        // No cross-demand contention tag for quantity: critical materials are fully resolved by
        // the supply-guided pre-processor (buildSupplyAllocation's perLotBudgets) before this
        // call ever runs, and non-critical materials are FIFO — so a demand's own bottom-up
        // dominator (ownDominator, below) is already the complete, self-contained answer for
        // "which of MY entitled lots did I use up." Naming other demands adds nothing quantity
        // didn't already settle upstream (unlike time — see timeRefs below, which travels
        // cross-demand through wave consolidation, a different mechanism entirely).
        //
        // This node's own cap came from the sketch phase's achievable-qty computation
        // (nodeQtyCaps IS built directly from NodeBlueprint.achievable) — its quantityDominator
        // was computed inline, alongside that same min (AND) / waterfall (OR) arithmetic, so
        // read it directly rather than re-deriving anything here. This is the ONLY reliable
        // source once nodeQtyCaps has already pre-equalized every AND-sibling to the same
        // achievable value — by the time the live commit phase looks, the original local
        // disparity that would otherwise reveal "who's the bottleneck" is gone.
        val ownDominator = demandBlueprint?.get(productId to locationId)?.quantityDominator
        // Which quantity_dominator wins: peggingNode's OWN dominator (from the recursive plan()
        // call just above, itself potentially chasing an even deeper, more specific cause) is
        // ALWAYS preferred when present — same "don't overwrite a more specific existing
        // dominator" rule as the AND-loop's and root-split loop's identical overrides. Only
        // fall back to branchDominator (step c's per-branch guess) or ownDominator (the
        // sketch-phase catch-all) when peggingNode didn't already resolve something of its own.
        @Suppress("UNCHECKED_CAST")
        val peggingHasOwnQtyDominator = !(peggingNode?.get("quantity_dominator") as? List<*>).isNullOrEmpty()
        @Suppress("UNCHECKED_CAST")
        val peggingHasOwnTimeDominator = !(peggingNode?.get("time_dominator") as? List<*>).isNullOrEmpty()
        val qtyRefs: List<Map<String, Any?>>? = if (peggingHasOwnQtyDominator) {
            null
        } else if (!branchDominator.isNullOrEmpty()) {
            branchDominator.toJsonList()
        } else if (!ownDominator.isNullOrEmpty()) {
            ownDominator.toJsonList()
        } else null
        // time_dominator has no cross-demand tier here: unlike quantity, time genuinely does
        // travel across demand boundaries — but through wave consolidation (consolidateByWaves /
        // gidDominatorRef), a separate, already-correct mechanism operating on consolidated WO
        // groups downstream of this function, not through buildSupplyAllocation's
        // criticalMatrix (a quantity-oriented signal that has no bearing on arrival timing). No
        // ownDominator equivalent either: the sketch phase (NodeBlueprint) has no time counterpart.
        val timeRefs: List<Map<String, Any?>>? = if (peggingHasOwnTimeDominator) {
            null
        } else if (!branchDominator.isNullOrEmpty()) {
            branchDominator.toJsonList()
        } else null
        val tagged = if (peggingNode == null) peggingNode else {
            peggingNode +
                (if (qtyRefs != null) mapOf("quantity_dominator" to qtyRefs) else emptyMap()) +
                (if (timeRefs != null) mapOf("time_dominator" to timeRefs) else emptyMap())
        }
        return Triple(fulfilled, wos, tagged)
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
        quantityDominator: List<DominatorRef> = emptyList(),
        timeDominator: List<DominatorRef> = emptyList(),
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
        if (quantityDominator.isNotEmpty()) put("quantity_dominator", quantityDominator.toJsonList())
        if (timeDominator.isNotEmpty()) put("time_dominator", timeDominator.toJsonList())
    }

    fun committedRow(qty: Double, commitTime: String?, commitReason: String? = null) = buildMap<String, Any?> {
        put("demand_id", demandId)
        put("customer_id", customerId)
        put("customer", customer)
        put("product_id", productId)
        put("location_id", locationId)
        put("quantity", roundQty(qty))
        // Unrounded companion to "quantity" — internal-only, read by the AND-min collapse one
        // level up (see planMethodSlot's effectiveQty) so a genuinely-achieved fractional amount
        // (e.g. 0.4999 units from a compounding low BOM rate) doesn't get misread as "this child
        // achieved 0" just because roundQty() rounds it down for physical/display purposes here.
        // "quantity" itself must stay rounded — it's what final committed_demands/pegging show.
        put("quantity_precise", qty)
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
    // Paired with perLotBudget: each lot's demand-wide-correct remaining BEFORE branch
    // narrowing — the write-back below must use THIS, not perLotBudget's own (possibly
    // branch-narrowed) post-draw value, or one contending branch's private cap would leak
    // into the shared `budget` map and wrongly starve sibling branches that haven't drawn
    // anything yet (see write-back comment below).
    val demandWideRemainingBySid = mutableMapOf<String, Double>()
    val perLotBudget: MutableMap<String, Double>? = budget?.let { b ->
        val prefix = "$componentKey|"
        val lotEntries = b.entries.filter { it.key.startsWith(prefix) }
        if (lotEntries.isEmpty()) null
        else lotEntries.associateTo(mutableMapOf()) { (k, v) ->
            // True remaining allowance for this lot, demand-wide: the static cap minus
            // whatever this demand has PERMANENTLY consumed so far from it, across every
            // independent branch of its own tree — not just `v` (budget's own local,
            // rollback-scoped view, which legitimately resets to the full cap for every
            // structurally-separate branch that reaches this lot, letting each one draw
            // independently past the demand's true, single per-lot allocation).
            val cap = initialBudget?.get(k)
            val consumedSoFar = demandConsumed?.get(k) ?: 0.0
            val trueRemaining = if (cap != null) (cap - consumedSoFar).coerceAtLeast(0.0) else v
            val bounded = min(v, trueRemaining)
            val sid = k.removePrefix(prefix)
            demandWideRemainingBySid[sid] = bounded
            // Third, tighter cap: this branch's own fair share (Phase 2's
            // computeAndSiblingCaps split), if this call is inside a contending branch.
            val branchCap = branchLotCap?.get(k)
            val branchConsumedSoFar = branchConsumed?.get(k) ?: 0.0
            val boundedForBranch = if (branchCap != null)
                min(bounded, (branchCap - branchConsumedSoFar).coerceAtLeast(0.0)) else bounded
            sid to boundedForBranch   // supplyId → remaining
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
        inventory, productId, locationId, quantity, preferDemandId, null, perLotBudget,
    )
    val taken = consumedBuckets.sumOf { it.qty }
    // Permanently record this draw against the demand-wide cap — deliberately NOT part of
    // any snapshot/restore scope, so a later, structurally-independent branch of this same
    // demand's tree can't get a fresh, un-depleted view of a lot this demand already spent
    // part of its allocation on elsewhere. If the enclosing branch is later abandoned and
    // `inventory` rolls back, this stays charged — conservative (this demand may end up
    // slightly under its true allowance in that edge case) rather than allowing the
    // cross-branch over-draw this is fixing.
    if (demandConsumed != null && perLotBudget != null) {
        for (cb in consumedBuckets) {
            val sid = cb.supplyId ?: continue
            if (sid !in perLotBudget) continue
            val lotKey = "$componentKey|$sid"
            demandConsumed[lotKey] = (demandConsumed[lotKey] ?: 0.0) + cb.qty
        }
    }
    // Mirror of the above, one level narrower: permanently charge this branch's own cap too.
    if (branchConsumed != null && perLotBudget != null) {
        for (cb in consumedBuckets) {
            val sid = cb.supplyId ?: continue
            if (sid !in perLotBudget) continue
            val lotKey = "$componentKey|$sid"
            branchConsumed[lotKey] = (branchConsumed[lotKey] ?: 0.0) + cb.qty
        }
    }
    // Write back per-lot budget remainders. Sourced from demandWideRemainingBySid (the
    // pre-branch-narrowing figure), NOT perLotBudget's own post-draw value: when a branch cap
    // is active, perLotBudget holds the tighter of the demand-wide and branch-local caps, so
    // its post-draw remaining no longer equals "how much of the WHOLE demand's budget is
    // left" — naively writing that back would let one contending branch's own narrower
    // allowance wrongly zero out the shared `budget` map for sibling branches that haven't
    // drawn anything yet, even though the demand-wide cap still has plenty left for them.
    if (budget != null && perLotBudget != null) {
        val lotCapWasConsumed = (totalLotBudgetBefore - (perLotBudget.values.sum())) > 1e-9
        if (lotCapWasConsumed) {
            // realPegging: per-lot caps were enforced in-place by consumeFromInventory —
            // recover the ACTUAL qty taken per lot and subtract it from the demand-wide
            // (unnarrowed) remaining, rather than trusting perLotBudget's own remaining.
            val takenBySid = consumedBuckets.groupBy { it.supplyId ?: "" }.mapValues { (_, cbs) -> cbs.sumOf { it.qty } }
            for (sid in perLotBudget.keys) {
                val demandWideRemaining = demandWideRemainingBySid[sid] ?: continue
                val takenHere = takenBySid[sid] ?: 0.0
                budget["$componentKey|$sid"] = (demandWideRemaining - takenHere).coerceAtLeast(0.0)
            }
        } else if (taken > 1e-9 && totalLotBudgetBefore > 1e-9) {
            // non-realPegging: synthetic supply_id didn't match any lot key, so lot entries
            // were not mutated. Proportionally reduce all lot entries (by the demand-wide
            // remaining, not the branch-narrowed cap) to track the aggregate.
            val consumedFraction = taken.coerceAtMost(totalLotBudgetBefore) / totalLotBudgetBefore
            for (sid in perLotBudget.keys) {
                val demandWideRemaining = demandWideRemainingBySid[sid] ?: continue
                budget["$componentKey|$sid"] = (demandWideRemaining * (1.0 - consumedFraction)).coerceAtLeast(0.0)
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

    // ── Customer constraint at the METHOD level ──────────────────────────────
    // BOM alternatives are frequently distinct make methods (one bom_id per child),
    // chosen by preference. When a constraint matches (customer, parent, and the make
    // method's location — blank/"*" = any), drop the make methods that DON'T produce the
    // constrained child so the planner is forced onto the pinned route. Non-make methods
    // are untouched; if no make method produces the child, fall back to all (warn).
    // (The within-method alt_group case is additionally narrowed in planMethodSlot.)
    val cust = demand["customer_id"]?.toString()?.trim()
    val constraintRules = if (cust.isNullOrEmpty()) emptyList()
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
    val effectiveMethods = if (feasibilityCache != null && constrainedMethods.size > 1) {
        val inProgress = mutableSetOf(Pair(productId, locationId))
        val feasible = constrainedMethods.filter { mm ->
            methodStructuralDepth(productId, locationId, mm, data, purchaseAllowed, feasibilityCache, inProgress, purchasable) < Int.MAX_VALUE
        }
        if (feasible.isNotEmpty() && feasible.size < constrainedMethods.size) {
            log.info("cycle-aware: {}@{} pruned {} self-cycling/dead-end method(s); {} feasible remain",
                productId, locationId, constrainedMethods.size - feasible.size, feasible.size)
            feasible
        } else constrainedMethods
    } else constrainedMethods

    // ── Blueprint shortcut ──────────────────────────────────────────────────
    // Pre-selected in the sketch phase: collapse straight to that one method,
    // skipping candidate expansion/ranking entirely.
    val blueprintEntry = demandBlueprint?.get(productId to locationId)
    val blueprintMethod = blueprintEntry?.method?.takeIf { bm ->
        effectiveMethods.any { it === bm || it == bm }
    }

    // ── Unified waterfall: one flat list of alternatives — across method-type
    // (make/move/purchase) AND BOM alt_group boundaries — ranked purely by
    // `preference` (ties keep BOM/method declaration order), tried
    // sequentially at every BOM depth (no root-only restriction): the best
    // fully, then only the residual to the next. This replaces three
    // formerly-separate mechanisms (a root-only proactive multi-method
    // waterfall, a non-root reactive-fallback mini-waterfall, and BOM
    // alt_group equal-split) with one.
    //
    // Why this is safe to run at every depth (the old proactive waterfall
    // wasn't — see history: 22k multi-method log lines / 30s on case 171):
    // it's inherently lazy. A candidate is only ever attempted (a real,
    // possibly-expensive recursive planMethodSlot call) when the prior one
    // left a residual or blocked — never speculatively in parallel. Cost is
    // paid once per candidate actually needed, same profile as the old
    // reactive-fallback (which already ran safely at every depth).
    val cachedMakeFailureReason = structuralFailedMakes?.get(Pair(productId, locationId))
    val candidates: List<WaterfallCandidate> = if (blueprintMethod != null) {
        // Still route through expandWaterfallCandidates for just this one method: a "make"
        // whose bom_id has multiple alt_groups must pick ONE (or waterfall across them), never
        // merge all alt_groups' children into a single AND-list. For the common case (≤1
        // alt_group) this yields exactly the same single WaterfallCandidate(blueprintMethod,
        // null) as before — zero behavior change there.
        expandWaterfallCandidates(listOf(blueprintMethod), productId, demand, config, data)
            .sortedBy { kbPreference(productId, locationId, it.method, it.altKey, preferenceKb) }
    } else {
        expandWaterfallCandidates(effectiveMethods, productId, demand, config, data)
            // A make candidate already proven structurally dead at this (pid, lid) by a
            // prior demand this run: skip re-attempting it (cheap memoization), matching
            // structuralFailedMakes's role below. A diagnostic stub covers it instead.
            .let { if (cachedMakeFailureReason != null) it.filterNot { c -> c.method["type"] == "make" } else it }
            .sortedBy { kbPreference(productId, locationId, it.method, it.altKey, preferenceKb) }
    }

    if (candidates.isEmpty()) {
        demandFulfilledList.add(committedRow(demandNetQty, reqTimeStr, "no_preferred_method"))
        return Triple(demandFulfilledList, emptyList(), demandNode(peggingChildren, reqTimeStr, "no_preferred_method", committedQty = taken))
    }

    val cap = methodCfg.maxMethods.coerceAtMost(candidates.size)
    val combinedWos = mutableListOf<Map<String, Any?>>()
    val combinedPegging = mutableListOf<Map<String, Any?>>()
    var residual = demandNetQty
    var totalAchievable = 0.0
    var latestCommit: LocalDate? = null
    // "Latest time dominates": which method-alternative candidate's own commit pushed this
    // demand's overall commit time out furthest, across the OR-waterfall of candidates below.
    var latestCommitDominator: List<DominatorRef> = emptyList()
    // OR-group: each method-alternative candidate below (purchase/make/move, or a root-split
    // slot) that fell short of what IT was asked (slotQty) contributes its own already-computed
    // dominator — bounded by the number of candidates actually tried, deduped. Attached to the
    // demand only when the OVERALL waterfall genuinely fell short (gated below at both return
    // sites) — a candidate that failed but was fully compensated by a later one must not leave
    // a stale dominator on an otherwise fully-satisfied demand.
    var qtyDominatorForDemand: List<DominatorRef> = emptyList()
    var lastBlockedReason: String? = null
    // A slot fully blocked by a self-cycle is structurally UNUSABLE (it contributes 0),
    // not merely capacity-limited — so it does not count against max_methods. Tracking
    // these as "cycle escapes" lets single-method mode (cap=1) fall through from a cyclic
    // make/move to the alternative that actually breaks the loop, instead of dead-ending
    // on cause=cycle_stopped. The total is still bounded by candidates.size.
    var cycleEscapes = 0
    // Whether the PRIOR attempt was a full block (0 achieved). Continuing reactively past
    // a full block is always safe (cost is bounded — nothing was proactively split). But
    // continuing past a PARTIAL success into another candidate below the root is only safe
    // when that next candidate is a "make" — move-to-move (or purchase) proactive splitting
    // at every BOM depth causes 2^N compounding (case 171's SUB_PCBA-style chains). At the
    // root (depth == MAX_PLAN_DEPTH) a single fan-out never compounds (there's no deeper
    // level for it to multiply across), so root may proactively split into ANY candidate
    // type after a partial success — matching the historical root-only waterfall's behavior,
    // which had no such restriction.
    val isRoot = depth == MAX_PLAN_DEPTH
    // Root-only proportional/equal split among the top `cap` candidates: rather than the
    // ordinary sequential 100%-then-spillover waterfall, a root demand with cap > 1 always
    // gets its quantity divided up-front across its top-ranked alternatives — weighted by
    // each candidate's reconstructed Preferences-KB score when one is fully available for
    // every candidate in play, otherwise an even split. Never applied below the root (that's
    // exactly the 2^N fan-out risk the historical proactive waterfall was root-only to avoid).
    val rootSplitWeights: List<Double>? = if (isRoot && cap > 1) {
        val scored = preferenceKb?.let { reconstructNodeScores(productId, locationId, candidates, it) }
            ?.take(cap)?.let { topScores ->
                val sum = topScores.sum()
                if (sum > 1e-9) topScores.map { it / sum } else null
            }
        scored ?: List(cap) { 1.0 / cap }
    } else null
    // Shortfall from a candidate that couldn't reach its rootSplitWeights-derived target rolls
    // forward onto the next candidate's target — the same residual-cascade idea the ordinary
    // waterfall already uses, just starting from a split target instead of a 100% target.
    var carryForward = 0.0
    var priorAttemptWasBlocked = true
    for ((slotIdx, candidate) in candidates.withIndex()) {
        if (slotIdx - cycleEscapes >= cap) break
        if (slotIdx > 0 && residual <= MIN_WATERFALL_RESIDUAL) break
        if (slotIdx > 0 && !priorAttemptWasBlocked && !isRoot && candidate.method["type"] != "make") break
        // Past the precomputed split (can happen after a cycle escape lets more than `cap`
        // candidates be tried), degrade to the ordinary sequential residual for the tail.
        val target = rootSplitWeights?.getOrNull(slotIdx)?.let { it * demandNetQty + carryForward }
        val slotQty = (target ?: residual).coerceAtMost(residual)
        val mLoc = (candidate.method["location_id"] ?: candidate.method["to_location_id"] ?: "").toString()
        val label = if (slotIdx == 0) "Preference ${candidate.method["preference"] ?: "—"}: ${candidate.method["type"]}@$mLoc" +
            (candidate.altKey?.let { " variant=$it" } ?: "")
            else "Fallback slot ${slotIdx + 1}/$cap: ${candidate.method["type"]}@$mLoc" +
                (candidate.altKey?.let { " variant=$it" } ?: "") + " (residual=${roundQty(residual).toLong()})"
        // Intra-demand sibling contention (the "diamond" fix), root-split side: when this
        // slot is one of rootSplitWeights' simultaneously-active candidates, look up the
        // matching branch Phase 2 (computeAndSiblingCaps) computed for it — keyed the same
        // way gatherAndSiblingRequests built it: BranchKey(productId, locationId,
        // combineSlot(lineage, slotIdFor(candidate))), embedding the current branchLineage
        // exactly as the gather side did at its own root-split creation point. A miss
        // (candidate-set drift between the gather pass and this live waterfall) degrades
        // safely to the outer branch cap, if any.
        val rootSplitBranchKey = if (rootSplitWeights != null)
            BranchKey(productId, locationId, combineSlot(branchLineage, slotIdFor(candidate))) else null
        val slotAndCap = rootSplitBranchKey?.let { andSiblingCaps?.get(it) }
        val slotBranchLotCap = slotAndCap ?: branchLotCap
        val slotBranchConsumed = if (slotAndCap != null) mutableMapOf<String, Double>() else branchConsumed
        // Freshly resolved for THIS candidate only — never inherited, same rationale as the
        // AND-loop's cBranchDominator (see plan()'s own param doc).
        val slotBranchDominator = rootSplitBranchKey?.let { andSiblingDominators?.get(it) }
        // Only extend lineage when this candidate is genuinely one of rootSplitWeights'
        // simultaneously-active branches — mirrors gatherAndSiblingRequests's own
        // `isRoot && cap > 1` gate for when it extends lineage descending into a root-split
        // candidate's own children (routeChildrenForDiscovery). Root-split candidates all
        // share the SAME (productId, locationId) — plain "pid@lid" would produce an
        // identical segment for every candidate, so fold slotIdFor(candidate) in too,
        // matching gather's own fix at its mirror-image call site exactly.
        val slotBranchLineage = if (rootSplitWeights != null)
            extendLineage(branchLineage, productId, "$locationId#${slotIdFor(candidate)}") else branchLineage
        // OR-group grand-parent diamond allocation, recomputed FRESH for THIS candidate attempt
        // — "embedding" diamond allocation into the live commit loop (per this session's
        // explicit design mandate): rather than a static per-demand split computed once before
        // any commit happens, each waterfall candidate gets its own fresh cap, split only
        // across whichever known recipients ITS OWN subtree actually reaches, using whatever
        // entitlement remains after any earlier candidate attempt's real consumption (read live
        // from diamondRecipientConsumed). A candidate that falls short simply leaves more for
        // the next candidate's own later call here — the waterfall loop's own iteration IS the
        // "rerun with leftover quantity"; no separate rerun trigger needed. See
        // computeDiamondCapsForAttempt's own doc.
        val slotDiamondCaps = if (diamondRecipients.isNullOrEmpty() || diamondCriticalEntitlement.isNullOrEmpty()) {
            diamondRecipientCaps
        } else {
            computeDiamondCapsForAttempt(
                productId = productId, locationId = locationId,
                method = candidate.method, altKey = candidate.altKey,
                demand = demand, config = config, data = data,
                preferenceKb = preferenceKb,
                diamondRecipients = diamondRecipients,
                diamondCriticalEntitlement = diamondCriticalEntitlement,
                diamondRecipientConsumed = diamondRecipientConsumed ?: emptyMap(),
            )
        }
        val attempt = planMethodSlot(
            m = candidate.method, slotQty = slotQty,
            productId = productId, locationId = locationId,
            demand = demand, demandId = demandId,
            requestTimeDt = requestTimeDt, reqTimeStr = reqTimeStr,
            inventory = inventory, data = data,
            depth = depth, path = path,
            config = config, preferDemandId = preferDemandId,
            budget = budget,
            selectedAltKey = candidate.altKey,
            methodChoiceExplanation = label,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
            initialBudget = initialBudget,
            demandConsumed = demandConsumed,
            nodeQtyCaps = nodeQtyCaps,
            demandBlueprint = demandBlueprint,
            preferenceKb = preferenceKb,
            andSiblingCaps = andSiblingCaps,
            branchLotCap = slotBranchLotCap,
            branchConsumed = slotBranchConsumed,
            branchLineage = slotBranchLineage,
            andSiblingDominators = andSiblingDominators,
            branchDominator = slotBranchDominator,
            diamondRecipientCaps = slotDiamondCaps,
            diamondRecipientConsumed = diamondRecipientConsumed,
            diamondRecipients = diamondRecipients,
            diamondCriticalEntitlement = diamondCriticalEntitlement,
        )
        // Intra-demand sibling contention (step c), applied directly here rather than relying
        // solely on plan()'s nodeCap-gated tagging site — same rationale as the AND-loop's
        // identical override (see its own comment): the sketch phase's nodeQtyCaps prediction
        // and Phase 2's fair-split cap are independently computed and don't always coincide, so
        // tagging at the exact point the shortfall is verified (achievableQty < slotQty) avoids
        // depending on the former having also predicted it.
        val slotHadShortfall = attempt.achievableQty < slotQty - 1e-9
        // Same "don't overwrite a more specific existing dominator" rule as the AND-loop's
        // identical override — attempt.methodPeggingNode may already carry its own, correctly-
        // computed dominator from deeper in its own subtree (e.g. its own AND-min's ratio-based
        // bomChildDominatorRefs comparison); blindly overwriting it with slotBranchDominator (a
        // per-branch guess computed once, up front) would replace a verified, specific cause
        // with a coarser one that then survives verbatim through every collapse above it.
        @Suppress("UNCHECKED_CAST")
        val slotHasOwnQtyDominator = !(attempt.methodPeggingNode?.get("quantity_dominator") as? List<*>).isNullOrEmpty()
        @Suppress("UNCHECKED_CAST")
        val slotHasOwnTimeDominator = !(attempt.methodPeggingNode?.get("time_dominator") as? List<*>).isNullOrEmpty()
        val slotDominatorOverride: Map<String, Any?> = if (!slotBranchDominator.isNullOrEmpty() && slotHadShortfall && attempt.methodPeggingNode != null) {
            buildMap {
                if (!slotHasOwnQtyDominator) put("quantity_dominator", slotBranchDominator.toJsonList())
                if (!slotHasOwnTimeDominator) put("time_dominator", slotBranchDominator.toJsonList())
            }
        } else emptyMap()
        val slotPeggingNode = attempt.methodPeggingNode?.plus(slotDominatorOverride) ?: attempt.methodPeggingNode
        // Always record the attempt's pegging node so the UI shows every candidate tried
        // (including blocked ones with zero qty) — informative for the user even when a
        // candidate committed nothing.
        combinedPegging.add(slotPeggingNode)
        if (attempt.blockedReason != null) {
            priorAttemptWasBlocked = true
            lastBlockedReason = attempt.blockedReason
            if (target != null) carryForward = target
            // Blocked = contributed nothing toward what this slot was asked for — its own
            // dominator (already tagged by planMethodSlot's blocked-path handling) explains why.
            if (slotQty > 1e-9) {
                qtyDominatorForDemand = qtyDominatorForDemand +
                    rawDominatorRefs(slotPeggingNode, "quantity_dominator", "method_alternative", data, config)
            }
            if (attempt.achievableQty <= 1e-9 && attempt.blockedReason?.contains("cycle") == true) {
                cycleEscapes++
            }
            // Memoize structural make failures so future demands skip the doomed BOM walk.
            // Only for genuinely structural dead-ends (topological, not capacity/state
            // dependent) — see MethodSlotResult.immediateBottleneckTrulyStructural's doc.
            if (candidate.method["type"] == "make" && structuralFailedMakes != null && attempt.immediateBottleneckTrulyStructural) {
                structuralFailedMakes[Pair(productId, locationId)] = attempt.blockedReason
            }
            continue
        }
        priorAttemptWasBlocked = false
        if (target != null) carryForward = (target - attempt.achievableQty).coerceAtLeast(0.0)
        combinedWos.addAll(attempt.wos)
        totalAchievable += attempt.achievableQty
        residual -= attempt.achievableQty
        // This candidate delivered less than what it was asked (slotQty — either its
        // rootSplitWeights share or the ordinary sequential residual, whichever this loop
        // actually computed above) — one of the (possibly several) alternatives behind the
        // demand's overall shortfall.
        if (attempt.achievableQty < slotQty - 1e-6) {
            qtyDominatorForDemand = qtyDominatorForDemand +
                rawDominatorRefs(slotPeggingNode, "quantity_dominator", "method_alternative", data, config)
        }
        attempt.latestCommit?.let { c ->
            if (latestCommit == null || c > latestCommit) {
                latestCommit = c
                latestCommitDominator = rawDominatorRefs(attempt.methodPeggingNode, "time_dominator", "method_alternative", data, config)
            }
        }
    }

    // Diagnostic stub: when structuralFailedMakes excluded make candidates from this
    // waterfall, emit a synthetic blocked work_order pegging node so the user sees make
    // was considered (and why it was skipped) without paying for the recursion again.
    if (cachedMakeFailureReason != null && effectiveMethods.any { it["type"] == "make" }) {
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

    if (totalAchievable <= 1e-9) {
        // Every candidate blocked. Surface the last failed reason and include all
        // attempted WOs so the UI shows the full waterfall trail.
        demandFulfilledList.add(committedRow(0.0, reqTimeStr, lastBlockedReason ?: "no_methods_succeeded"))
        val failedPegging = combinedPegging + peggingChildren
        val rel = if (failedPegging.size > 1) "or" else null
        return Triple(demandFulfilledList, emptyList(), demandNode(failedPegging, reqTimeStr, lastBlockedReason, committedQty = taken, childrenRelation = rel, quantityDominator = qtyDominatorForDemand.dedupBySupply()))
    }

    // Some commit. Combine all attempted WOs (success + blocked) in pegging. Siblings
    // under the demand are additive supply paths (candidate 1 + candidate 2 each cover
    // part of the demand) — mark "or" so the UI labels them as alternatives.
    peggingChildren.addAll(combinedPegging)
    val partialReason = if (residual > 1e-9) "partial" else null
    val commitTimeStr = formatDate(latestCommit)
    demandFulfilledList.add(committedRow(totalAchievable, commitTimeStr, partialReason))
    val rel = if (peggingChildren.size > 1) "or" else null
    return Triple(demandFulfilledList, combinedWos, demandNode(
        peggingChildren, commitTimeStr ?: reqTimeStr, partialReason, committedQty = taken + totalAchievable, childrenRelation = rel,
        timeDominator = latestCommitDominator,
        // Only when the OVERALL waterfall genuinely fell short — a candidate that failed but
        // was fully compensated by a later one must not leave a stale dominator behind on an
        // otherwise fully-satisfied demand.
        quantityDominator = if (residual > 1e-9) qtyDominatorForDemand.dedupBySupply() else emptyList(),
    ))
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

private fun computeStartDt(
    reqDt: LocalDate,
    leadDays: Double,
    commitTimesWithSource: List<Pair<LocalDate, ChildPassResult>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): Pair<LocalDate, List<DominatorRef>> {
    var startDt = if (leadDays > 0) dateAddDays(reqDt, -leadDays) ?: reqDt else reqDt
    var dominator: List<DominatorRef> = emptyList()
    if (commitTimesWithSource.isNotEmpty()) {
        val latestChild = commitTimesWithSource.maxOf { it.first }
        if (latestChild > startDt) {
            startDt = latestChild
            // "Latest time dominates": unlike quantity (an OR-group can have several genuine
            // contributing alternatives), time has exactly ONE dominator — whichever single
            // child's commit time is the actual latest. Several dates can tie exactly (a
            // multi-lot child contributes several commit_times, or a pre-equalized wave), but
            // that's not evidence of several equally-responsible causes — pick the first tied
            // child and propagate its own (already-resolved) dominator verbatim.
            val winner = commitTimesWithSource.firstOrNull { it.first == latestChild }?.second
            dominator = rawDominatorRefs(winner?.pegging, "time_dominator", "bom_child", data, config)
        }
    }
    return startDt to dominator
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
): WorkOrderResult {
    val lotSizeVal = maxLotSize(productId, productionLocation, data)?.takeIf { it > 0 } ?: qty
    val lotSize = max(1e-9, lotSizeVal)
    val prodArea = getProdArea(productId, productionLocation, data)
    val methodType = m["type"] as? String ?: ""
    val woGroupId = nextWoGroupId()

    // All lots share the same calendar window [startDt, startDt+leadDays] regardless of
    // resource capacity. Resource-capacity wave sequencing (waveCount × lead_time) is
    // ResourceScheduler's exclusive responsibility and runs after consolidation. The native
    // pegging tree built from these lots therefore reflects only the calendar constraint,
    // not resource-sequential boundaries.
    val waveEnd = dateAddDays(startDt, leadDays)

    val wos = mutableListOf<Map<String, Any?>>()
    var left = qty
    var lotCount = 0
    while (left > 1e-9) {
        val lotQty = min(lotSize, left)
        wos.add(mapOf(
            "product_id" to productId,
            "location_id" to productionLocation,
            "quantity" to roundQty(lotQty),
            "start_time" to formatDate(startDt),
            "end_time" to formatDate(waveEnd),
            "method" to methodType,
            "location_source" to (if (methodType == "move") m["from_location_id"] else null),
            "demand_id" to demandId,
            "prod_area" to prodArea,
            "wo_group_id" to woGroupId,
            "wave_index" to 0,
        ))
        left -= lotQty
        lotCount++
    }
    return WorkOrderResult(wos, lotCount, waveEnd, lotSizeVal, woGroupId)
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
    config: Map<String, Any?>?,
): Pair<Map<String, Any?>, Double> {
    if (node["failed"] == true) return node to 0.0
    val allChildren = node["children"] as? List<*> ?: emptyList<Any?>()
    when (node["type"]) {
        "supply", "purchase" -> {
            // Prefer the unrounded "quantity_precise" when present (see committedRow's doc).
            // "supply" nodes are already unrounded in "quantity" itself (see the comment where
            // they're built); "purchase" nodes carry quantity_precise separately since their own
            // "quantity" is rounded for display.
            val q = ((node["quantity_precise"] as? Number) ?: (node["quantity"] as? Number))?.toDouble() ?: 0.0
            val supplied = minOf(target, q).coerceAtLeast(0.0)
            return (node + ("quantity" to supplied)) to supplied
        }
        "demand" -> {
            // Fulfilled by children (inventory/supply leaves + production WOs, OR several
            // candidates each carrying their own pre-assigned share under a root-level
            // proportional split) — committed = what they together give. This node's
            // quantity_dominator was, in the common case, ALREADY computed inline during
            // planning (the sketch phase — see NodeBlueprint.quantityDominator) or during the
            // live commit (planMethodSlot's AND/OR branches) — preserve it verbatim; it was
            // resolved at the exact point the constraint was decided, with the real, local
            // budget actually enforced there, so there is nothing more authoritative to
            // re-derive after the fact.
            @Suppress("UNCHECKED_CAST")
            val existingDominator = (node["quantity_dominator"] as? List<Map<String, Any?>>)?.takeIf { it.isNotEmpty() }
            var remaining = target
            val newChildren = allChildren.map { ch ->
                val cm = ch as? Map<String, Any?> ?: return@map ch
                val askedForThisChild = remaining.coerceAtLeast(0.0)
                val (nc, g) = reconcile(cm, askedForThisChild, data, config)
                remaining -= g
                nc
            }
            val committed = (target - remaining).coerceAtLeast(0.0)
            // "The contributing WO's dominator is the dominator": on a genuine overall
            // shortfall, collect from whichever children already carry their own dominator —
            // set inline, during planning, at the exact point THEY were constrained — rather
            // than trying to detect "which child fell short" via any local ask/delivered delta
            // at this level. That per-child comparison is unreliable here: a root-split
            // demand's several candidates (see rootSplitWeights, cap>1) each get their own
            // pre-assigned share up front, not "the entire remaining amount," so a child that
            // fully delivered its own assignment carries no dominator of its own and
            // contributes nothing — exactly right, since it wasn't the cause.
            val qtyDominatorRefs = if (existingDominator == null && committed < target - 1e-6) {
                newChildren.flatMap { ch ->
                    (ch as? Map<String, Any?>)?.let { rawDominatorRefs(it, "quantity_dominator", "bom_child", data, config) } ?: emptyList()
                }.dedupBySupply()
            } else emptyList()
            // Collapse the request to the commitment so the reconciled tree is fully consistent
            // (quantity == committed_qty for every internal node). The caller restores the root's
            // original request for the requested-vs-committed display.
            val nn = node + mapOf("children" to newChildren, "quantity" to committed, "committed_qty" to committed) +
                (if (existingDominator != null) mapOf("quantity_dominator" to existingDominator)
                 else if (qtyDominatorRefs.isNotEmpty()) mapOf("quantity_dominator" to qtyDominatorRefs.toJsonList())
                 else emptyMap())
            return nn to committed
        }
        "work_order" -> {
            // Prefer the unrounded "quantity_precise" (see buildWoNode's doc) — reading the
            // rounded "quantity" here would let a genuinely-achieved fractional WO (e.g. 0.4999
            // out of 0.5) register as a hard 0 one level up, the same rounding-cascade class of
            // bug fixed in planMethodSlot's effectiveQty.
            val curQty = ((node["quantity_precise"] as? Number) ?: (node["quantity"] as? Number))?.toDouble() ?: 0.0
            val want = minOf(target, curQty).coerceAtLeast(0.0)
            val method = node["method"]
            val demandChildren = allChildren.mapNotNull { it as? Map<String, Any?> }.filter { it["type"] == "demand" }
            if (method == "purchase" || demandChildren.isEmpty()) {
                // Procurement / leaf-backed WO delivers `want`; trim leaf children to it.
                val newChildren = allChildren.map { ch -> (ch as? Map<String, Any?>)?.let { reconcile(it, want, data, config).first } ?: ch }
                return (node + mapOf("quantity" to want, "children" to newChildren)) to want
            }
            // Prefer the TRUE BOM rate over inferring it from cd["quantity"]/curQty — the latter
            // encodes BOM_rate × (whatever upstream achieved) and drifts from the real rate whenever
            // an ancestor was already trimmed/lot-rounded inconsistently (the same class of bug
            // Mechanism C fixed for the OR aggregate below, but the AND branch's ask still used the
            // naive ratio — propagating, not correcting, upstream drift through nested AND levels;
            // root cause of the R4_qty_propagation/R8_deep_conservation violation class). Falls back
            // to the ratio only when no real BOM row exists (synthetic/virtual nodes).
            val parentPid = (node["product_id"] as? String)?.trim() ?: ""
            val bomRowsForRate = data["bom"] ?: emptyList()
            val parentBomRowsForRate = bomRowsForRate.filter { (it["parent_id"] as? String)?.trim() == parentPid }
            fun rateOf(cd: Map<String, Any?>): Double {
                val childPid = (cd["product_id"] as? String)?.trim() ?: ""
                val actualRate = parentBomRowsForRate
                    .firstOrNull { (it["child_id"] as? String)?.trim() == childPid }
                    ?.let { (it["rate"] as? Number)?.toDouble() }
                if (actualRate != null && actualRate > 1e-9) return actualRate
                return if (curQty > 1e-9) ((cd["quantity"] as? Number)?.toDouble() ?: 0.0) / curQty else 0.0
            }
            val rel = node["children_relation"] as? String
            // First (and only) ask: each component for want × rate. Capture the trimmed node AND
            // how many parents it can back (committed / rate). Reconcile each child ONCE here.
            val firstAsk = demandChildren.map { cd ->
                // A move is 1:1 (rate 1), so ask the source for exactly `want`. Using the stored
                // source.quantity/curQty would be ≈1 but not exactly 1 when the two drifted apart
                // upstream, leaving the source committed above the move (R4_move). Asking for `want`
                // caps the source to it.
                val r = if (method == "move") 1.0 else rateOf(cd)
                val (nc, c) = reconcile(cd, want * r, data, config)
                Triple(nc, r, if (r > 1e-9) c / r else Double.POSITIVE_INFINITY)
            }
            // "Least quantity dominates": captured only when this AND-node is genuinely trimmed
            // below `want` — reconcile() is a no-op for an already-consistent tree (see its own
            // docstring). This WO's quantity_dominator was, in the common case, ALREADY computed
            // inline during planning (the sketch phase mirrors this exact min-over-children
            // arithmetic before nodeQtyCaps pre-equalizes every AND-sibling to the same value,
            // which is the only point the true local disparity is visible at all) — preserve it
            // verbatim. Only fall back to a fresh computation for the rarer case where the live
            // commit diverged from the sketch's prediction and nothing was set yet.
            @Suppress("UNCHECKED_CAST")
            val existingDominator = (node["quantity_dominator"] as? List<Map<String, Any?>>)?.takeIf { it.isNotEmpty() }
            var qtyDominatorRefs: List<DominatorRef> = emptyList()
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
                else             -> {
                    // AND make: least dominates. An AND-group has exactly ONE dominator — the
                    // single worst child — never a union across every tied sibling (ties are the
                    // norm here, not the exception: nodeQtyCaps pre-equalizes every AND-sibling to
                    // the same achievable value, so comparing `third` again post-hoc finds almost
                    // everything "tied" without that meaning they're all equally responsible).
                    // Whichever child is picked, its dominator propagates verbatim (recursively —
                    // may itself be an OR-group from further below), not re-derived here.
                    val minVal = firstAsk.minOfOrNull { it.third }
                    if (existingDominator == null && minVal != null && minVal < want - 1e-6) {
                        val winner = firstAsk.firstOrNull { it.third <= minVal + 1e-9 }
                        if (winner != null) {
                            qtyDominatorRefs = rawDominatorRefs(winner.first, "quantity_dominator", "bom_child", data, config)
                        }
                    }
                    minVal ?: want
                }
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
            // "Least dominates, and that's the dominator for the WHOLE AND-group": the winning
            // (least-achieving) sibling's dominator isn't just this WO's own — it's WHY every
            // other sibling got scaled down too, even the ones that fully achieved their own
            // ask. Copy it onto them directly (unless a sibling already carries its own, more
            // specific dominator from being independently constrained) so a sibling scaled down
            // by scaleSubtree below isn't left looking unexplained.
            val groupDominatorJson: List<Map<String, Any?>>? =
                existingDominator ?: qtyDominatorRefs.takeIf { it.isNotEmpty() }?.toJsonList()
            var askIdx = 0
            val newChildren = allChildren.map { ch ->
                val cm = ch as? Map<String, Any?> ?: return@map ch
                if (cm["type"] != "demand") return@map cm
                val (asked, r, _) = firstAsk[askIdx]; askIdx++
                val trimmed = if (rel == "or") {
                    asked  // keep each variant's reconciled commitment as-is
                } else {
                    val askedCommitted = (asked["committed_qty"] as? Number)?.toDouble() ?: (want * r)
                    val targetT = supply * r
                    if (askedCommitted > 1e-9 && targetT < askedCommitted - 1e-9) scaleSubtree(asked, targetT / askedCommitted) else asked
                }
                if (rel != "or" && groupDominatorJson != null && trimmed["quantity_dominator"] == null)
                    trimmed + ("quantity_dominator" to groupDominatorJson)
                else trimmed
            }
            val failed = supply <= 1e-6 && demandChildren.isNotEmpty()
            val nn = node + mapOf("quantity" to supply, "children" to newChildren) +
                (if (failed) mapOf("failed" to true) else emptyMap()) +
                (if (groupDominatorJson != null) mapOf("quantity_dominator" to groupDominatorJson) else emptyMap())
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
        // Also skip a WO that isZeroQty(): it built/moved/bought nothing meaningful, so anything
        // beneath it is an orphan (the under-consumption the soundness checker flags as R7d).
        // Not real output — applies to purchase/move just as much as make.
        if (n["type"] == "work_order" && isZeroQty(n["quantity"])) return
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
                put("wo_group_id", n["wo_group_id"])
                put("lot_count", 1)            // capacity/lot-size splitting deferred to WO consolidation
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
 * (product, location, method, source) into fewer, larger orders (re-lotted by
 * max_lot_size), cutting the WO count.
 * This is the scheduling counterpart to Pass-1 inventory allocation: Pass 1 decides
 * WHO gets scarce stock; Pass 2 decides HOW to batch the resulting production.
 *
 * Operates on the final work-order list only; per-demand pegging trees are left
 * intact for traceability. A merged order carries `consolidated=true`,
 * `demand_id=null`, and `wo_competing_demands` (the demands it serves), reusing the
 * existing consolidated-WO rendering. Singleton groups pass through unchanged, as do
 * failed-WO stubs and any WO without a parseable start.
 *
 * Lot-count strategy — sum first, then divide to lots:
 *   • make / buy → keyed by (product, location, method, source, window): WOs in the same
 *     window are merged; the merged total is divided by max_lot_size to derive `lot_count`
 *     (NOT summing the constituent native lot_counts). Resource capacity (lot sizing) is
 *     applied ONLY here, not at the per-demand native-WO level (native WOs carry lot_count=1).
 *     Singletons also get lot_count recomputed from qty/lotSize.
 *   • MOVE → same windowing; merged into a single mixed-cargo shipment per window.
 *
 * Size: each group collapses to ONE batch order carrying the group's TOTAL quantity
 * plus `lot_count` = ceil(total / max_lot_size).
 *
 * @param windowDays scheduling bucket width; <=0 collapses the whole horizon into one window.
 */
internal data class WoConsolidation(
    /** Merged work-order summary: consolidated batches + un-mergeable singletons + pass-throughs. */
    val consolidated: List<Map<String, Any?>>,
    /** The input per-demand WOs, each tagged with `consolidated_group_id` linking it to the ONE
     *  consolidated WO it belongs to. `consolidated` and `native` form a strict partition. */
    val native: List<Map<String, Any?>>,
)

/**
 * Test bridge: wraps a flat list of native WOs in synthetic single-node pegging trees and
 * delegates to [consolidateByWaves]. Preserves the original test API so unit tests don't need to
 * construct full pegging trees. Each WO is given its own synthetic node (no nesting), so the
 * dependency graph is trivially empty and every group resolves in wave 0 — identical behavior to
 * the old single-pass bucketing.
 */
internal fun consolidateWorkOrdersByTiming(
    workOrders: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    windowDays: Int,
): WoConsolidation {
    val trees = workOrders.map { wo ->
        mapOf(
            "demand_id" to wo["demand_id"],
            "tree" to (wo + mapOf("type" to "work_order", "children" to emptyList<Any>())),
        )
    }
    return consolidateByWaves(trees, data, windowDays).consolidation
}

/** Result of [consolidateByWaves]: the rewritten pegging trees (final, pushed timings) plus the
 *  consolidated/native WO lists. */
internal data class WaveConsolidationResult(
    val peggingTrees: List<Map<String, Any?>>,
    val consolidation: WoConsolidation,
)

internal data class WoBatchConfig(
    val make: String?,
    val move: String?,
    val purchase: String?,
    val legacyWindowDays: Int = 0,
) {
    fun scaleFor(method: String?): String? = when (method) {
        "make"     -> make
        "move"     -> move
        "purchase" -> purchase
        else       -> make
    }
}

private fun calendarBucket(start: LocalDate, scale: String?, windowDays: Int): Long = when {
    scale == "monthly"  -> start.year * 12L + start.monthValue
    scale == "all"      -> 0L
    scale == "weekly"   -> start.toEpochDay() / 7
    scale == "biweekly" -> start.toEpochDay() / 14
    windowDays > 0      -> start.toEpochDay() / windowDays
    else                -> 0L
}

/**
 * Post-wave display pass: merge consolidated WOs that share the same calendar bucket but arrived
 * in different Kahn waves (and were therefore never in the same frontier).
 *
 * This is a pure presentation transformation — it only rewrites [WoConsolidation.consolidated]
 * and remaps [WoConsolidation.native] `consolidated_group_id` pointers.  Pegging trees are
 * intentionally left untouched; native WO timing was already correctly committed during the wave
 * loop.
 *
 * The merge key mirrors the wave loop's base key plus a calendar bucket dimension:
 *   make/buy:  (product_id, location_id, method, location_source, bucket)
 *   move:      ("__move__", location_source, location_id, prod_area, bucket)
 *
 * Because bucket boundaries are epoch-rooted and fixed per WO (derived solely from that WO's own
 * `start_time`), grouping is deterministic and cannot cascade.
 */
internal fun crossWaveCalendarMerge(
    consolidation: WoConsolidation,
    windowDays: Int,
    data: Map<String, List<Map<String, Any?>>>,
    woBatchConfig: WoBatchConfig? = null,
): WoConsolidation {
    val groups = linkedMapOf<List<Any?>, MutableList<Map<String, Any?>>>()
    val nonePassthrough = mutableListOf<Map<String, Any?>>()
    for (row in consolidation.consolidated) {
        val method = row["method"] as? String ?: continue
        val lid    = row["location_id"] as? String ?: continue
        val locSrc = row["location_source"] as? String
        val start  = parseDate(row["start_time"] as? String) ?: continue
        val methodScale = woBatchConfig?.scaleFor(method)
        if (methodScale == "none") { nonePassthrough.add(row); continue }
        val wd = woBatchConfig?.legacyWindowDays ?: windowDays
        val bucket = calendarBucket(start, methodScale, wd)
        val key: List<Any?> = if (method == "move")
            listOf("__move__", locSrc, lid, row["prod_area"], bucket)
        else
            listOf(row["product_id"], lid, method, locSrc, bucket)
        groups.getOrPut(key) { mutableListOf() }.add(row)
    }

    val mergedConsolidated = mutableListOf<Map<String, Any?>>()
    val cgidRemap = mutableMapOf<String, String>()
    for (row in nonePassthrough) {
        mergedConsolidated.add(row)
        val cgid = row["consolidated_group_id"] as? String
        if (cgid != null) cgidRemap[cgid] = cgid
    }

    for ((_, rows) in groups) {
        // Fast path: single row — keep as-is.
        if (rows.size == 1) {
            mergedConsolidated.add(rows[0])
            val cgid = rows[0]["consolidated_group_id"] as? String
            if (cgid != null) cgidRemap[cgid] = cgid
            continue
        }

        val first  = rows[0]
        val method = first["method"] as? String ?: continue
        val lid    = first["location_id"] as? String ?: continue
        val pid    = first["product_id"] as? String           // null for move
        val locSrc = first["location_source"] as? String

        val totalQty   = rows.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        val mergedStart = rows.mapNotNull { parseDate(it["start_time"] as? String) }.maxOrNull()

        if (mergedStart == null || totalQty <= 1e-9) {
            mergedConsolidated.addAll(rows)
            for (r in rows) { val c = r["consolidated_group_id"] as? String; if (c != null) cgidRemap[c] = c }
            continue
        }

        // Lead-time recomputation from merged quantity, matching the wave loop's formula.
        val leadDays: Double = when {
            method == "move" -> {
                // Transit time is route-fixed; derive from any existing row's span.
                rows.mapNotNull { r ->
                    val s = parseDate(r["start_time"] as? String)
                    val e = parseDate(r["end_time"] as? String)
                    if (s != null && e != null) (e.toEpochDay() - s.toEpochDay()).toDouble() else null
                }.maxOrNull() ?: 0.0
            }
            pid != null -> {
                val methodRow = getMethods(pid, lid, data).firstOrNull { it["type"] == method }
                // Span = calendar lead_time only. Resource-capacity wave sequencing is
                // ResourceScheduler's job, which runs after consolidation.
                (methodRow?.get("lead_time") as? Number)?.toDouble()
                    ?: rows.mapNotNull { r ->
                        val s = parseDate(r["start_time"] as? String)
                        val e = parseDate(r["end_time"]   as? String)
                        if (s != null && e != null) (e.toEpochDay() - s.toEpochDay()).toDouble() else null
                    }.maxOrNull() ?: 0.0
            }
            else -> 0.0
        }
        val mergedEnd = dateAddDays(mergedStart, leadDays) ?: mergedStart

        // Merge competing demands + split details from all constituent rows. Both
        // singletonRow() and mergedRow() always set wo_competing_demands /
        // consolidation_split_details now, but fall back to demand_id / (demand_id, quantity)
        // defensively in case some other row-producer ever omits them — same shape of fallback
        // for both, so the two lists can't silently diverge the way they used to when only
        // wo_competing_demands had a fallback and consolidation_split_details didn't.
        @Suppress("UNCHECKED_CAST")
        val allDemands = rows.flatMap { row ->
            val fromCompeting = (row["wo_competing_demands"] as? List<String>) ?: emptyList()
            val fromDemandId  = (row["demand_id"] as? String)?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            fromCompeting.ifEmpty { fromDemandId }
        }.distinct()
        @Suppress("UNCHECKED_CAST")
        val mergedSplit = rows.flatMap { row ->
            val fromSplit = (row["consolidation_split_details"] as? List<Map<String, Any?>>) ?: emptyList()
            val fromDemandId = (row["demand_id"] as? String)?.takeIf { it.isNotBlank() }?.let { d ->
                listOf(mapOf("demand_id" to d, "allocated_qty" to ((row["quantity"] as? Number)?.toDouble() ?: 0.0)))
            } ?: emptyList()
            fromSplit.ifEmpty { fromDemandId }
        }
            .groupBy { it["demand_id"] as? String }
            .map { (d, es) -> mapOf("demand_id" to d, "allocated_qty" to es.sumOf { (it["allocated_qty"] as? Number)?.toDouble() ?: 0.0 }) }
        // Regression guard: every demand counted in allDemands should have a matching split entry
        // (and the splits should sum to totalQty) — should never fire after the fallback above.
        val splitIds = mergedSplit.mapNotNull { it["demand_id"] as? String }.toSet()
        val missingFromSplit = allDemands.filterNot { it in splitIds }
        if (missingFromSplit.isNotEmpty()) {
            log.warn("[wo-consolidation] {} demand(s) in wo_competing_demands but missing from consolidation_split_details for product={} location={} method={}: {}",
                missingFromSplit.size, pid, lid, method, missingFromSplit)
        }
        val newCgid = rows[0]["consolidated_group_id"] as? String ?: nextWoGroupId()
        for (r in rows) { val c = r["consolidated_group_id"] as? String; if (c != null) cgidRemap[c] = newCgid }

        val lotCount = if (method != "move" && pid != null) {
            val lotSize = (maxLotSize(pid, lid, data)?.takeIf { it > 0 } ?: totalQty).coerceAtLeast(1e-9)
            Math.ceil(totalQty / lotSize).toInt().coerceAtLeast(1)
        } else rows.sumOf { (it["lot_count"] as? Number)?.toInt() ?: 1 }

        val mergedRow = buildMap<String, Any?> {
            put("product_id", pid)
            put("location_id", lid)
            put("quantity", totalQty)
            put("start_time", formatDate(mergedStart))
            put("end_time", formatDate(mergedEnd))
            put("method", method)
            put("location_source", locSrc)
            put("demand_id", null)
            put("prod_area", first["prod_area"])
            put("wo_group_id", newCgid)
            put("consolidated_group_id", newCgid)
            put("wave_index", 0)
            put("lot_count", lotCount)
            if (pid != null) maxLotSize(pid, lid, data)?.let { put("max_lot_size", it) }
            put("consolidated", true)
            // Calendar lead_time for one wave — stored once so ResourceScheduler can use it
            // as the stable per-wave floor across both arbitration passes (Pass 2 would
            // otherwise read the already-extended totalSpan and double the duration).
            put("per_wave_days", leadDays.toLong())
            put("wo_competing_demands", allDemands)
            put("consolidation_split_details", mergedSplit)
            put("consolidated_demand_ids", allDemands)
            put("wo_window_start", formatDate(mergedStart))
            put("wo_window_end", formatDate(mergedStart))
            put("wo_consolidation_total_planned", totalQty)
            if (method == "move") {
                @Suppress("UNCHECKED_CAST")
                val moveComponents = rows.flatMap { (it["move_components"] as? List<Map<String, Any?>>) ?: emptyList() }
                    .groupBy { it["product_id"] as? String }
                    .map { (p, comps) ->
                        @Suppress("UNCHECKED_CAST")
                        val splitMap = comps.flatMap { c ->
                            (c["demand_splits"] as? List<Map<String, Any?>>) ?: emptyList()
                        }.groupBy { it["demand_id"] as? String }
                         .mapValues { (_, es) -> es.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } }
                        val demandSplits = splitMap.entries
                            .sortedBy { it.key ?: "" }
                            .map { (d, qty) -> mapOf("demand_id" to d, "quantity" to qty) }
                        mapOf(
                            "product_id"    to p,
                            "quantity"      to comps.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 },
                            "demand_ids"    to demandSplits.map { it["demand_id"] as String },
                            "demand_splits" to demandSplits,
                        )
                    }.sortedBy { it["product_id"] as? String ?: "" }
                put("move_components", moveComponents)
                // Singleton rows entering crossWaveCalendarMerge carry their own product_id.
                // After merging, if the shipment spans multiple products, null it out so the
                // WO table renders "N components" instead of showing the first product's id.
                val distinctMovePids = moveComponents.mapNotNull { it["product_id"] as? String }.distinct()
                if (distinctMovePids.size != 1) put("product_id", null)
            }
        }
        mergedConsolidated.add(mergedRow)
    }

    // Remap native rows' consolidated_group_id to their new canonical cgid.
    val remappedNative = consolidation.native.map { row ->
        val cgid = row["consolidated_group_id"] as? String
        val newCgid = cgid?.let { cgidRemap[it] } ?: cgid
        if (newCgid == cgid) row else row + mapOf("consolidated_group_id" to newCgid)
    }

    return WoConsolidation(mergedConsolidated, remappedNative)
}

/**
 * Bottom-up, level-synchronous WO consolidation + timing propagation. Replaces the old
 * consolidate-once-then-patch design (`consolidateFromPegging` + `readjustConsolidatedWoTiming`):
 * that design bucketed every WO once using its native, pre-correction start_time, then pushed
 * timing in a second pass that never re-bucketed — a WO pushed across a window boundary stayed
 * merged with whatever else landed in its ORIGINAL bucket, and lead_time was preserved as the
 * original interval instead of being recomputed from the post-merge lot_count.
 *
 * This processes WO groups (`wo_group_id`) in bottom-up topological waves, across ALL pegging
 * trees at once (so a wo_group_id shared by more than one tree — e.g. a VIRTUAL consolidation
 * demand sharing a physical WO with a real demand — resolves once, correctly). A group is only
 * bucketed/timed once every group it depends on (BOM/alternative-method children, and producing
 * WOs reached through a produced-supply sub-tree) has already been finalized. Each wave re-buckets
 * using the just-computed (pushed) start time and recomputes duration from the post-merge
 * lot_count via the same wave-packing formula used to build native WOs in [buildWorkOrders] — so a
 * parent is never bucketed/timed before its dependencies are known, by construction, and "parent
 * starts before child finishes" cannot occur.
 *
 * Same output contract as before: `consolidated` and `native` form a strict partition keyed by
 * `consolidated_group_id`. Failed subtrees and zero-qty make WOs are skipped (mirroring
 * flattenPeggingToWorkOrders so the R11 soundness check stays in sync).
 */
internal fun consolidateByWaves(
    peggingTrees: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    windowDays: Int,
    woBatchConfig: WoBatchConfig? = null,
    config: Map<String, Any?>? = null,
): WaveConsolidationResult {
    data class Occurrence(
        val gid: String,                 // internal identity (real wo_group_id, or synthetic) — bucketing/dependency graph only
        val originalGid: String?,        // raw wo_group_id on the node (possibly null) — preserved on native output
        val pid: String,
        val lid: String,
        val method: String?,
        val locationSource: String?,
        val qty: Double,
        val nativeStart: LocalDate?,
        val nativeEnd: LocalDate?,
        val demandId: String?,
        val nativeMaxLotSize: Any?,
        val members: List<String>?,
    )

    class GidInfo {
        var pid: String = ""
        var lid: String = ""
        var method: String? = null
        var locationSource: String? = null
        var totalQty: Double = 0.0
        var occurrenceCount: Int = 0
        var nativeStart: LocalDate? = null
        var nativeDuration: Double? = null
        val demandQty: LinkedHashMap<String, Double> = LinkedHashMap()
        var sampleMembers: List<String>? = null
    }

    // ── Phase A: walk every pegging tree once, collecting WO occurrences and the GLOBAL
    // dependency graph (parentsOf: gid -> set of gids that depend on it). Mirrors buildPeggingDag's
    // walk: skip failed subtrees, re-root through work_order nodes, recurse THROUGH supply/purchase
    // children without changing the current parent (so a producing WO nested under a supply node is
    // attributed as a dependency of the ORIGINAL consuming WO), and record leafConstraintByGid (the
    // latest supply/purchase commit_time reachable under a gid) as a lower bound on that gid's start.
    val occurrences = mutableListOf<Occurrence>()
    val parentsOf = mutableMapOf<String, MutableSet<String>>()
    val leafConstraintByGid = mutableMapOf<String, LocalDate>()
    var syntheticSeq = 0

    @Suppress("UNCHECKED_CAST")
    fun walk(node: Any?, demandId: Any?, members: List<String>?, currentParent: String?, depth: Int) {
        if (depth > 80) return
        val n = node as? Map<String, Any?> ?: return
        if (n["failed"] == true) return
        val nodeChildren = (n["children"] as? List<*>) ?: emptyList<Any?>()
        when (n["type"] as? String) {
            "work_order" -> {
                // Skip a WO that isZeroQty(), regardless of method — a purchase/move trimmed to
                // nothing by an upstream AND-bottleneck is just as much a non-real occurrence as
                // a zero-qty make.
                if (isZeroQty(n["quantity"])) return
                val pid = (n["product_id"] as? String)?.trim() ?: ""
                val lid = (n["location_id"] as? String)?.trim() ?: ""
                val realGid = (n["wo_group_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                val gid = realGid ?: "__occ${syntheticSeq++}"
                val nativeStart = parseDate(n["start_time"] as? String)
                val nativeEnd = parseDate(n["end_time"] as? String)
                occurrences.add(Occurrence(
                    gid = gid,
                    originalGid = realGid,
                    pid = pid, lid = lid,
                    method = n["method"] as? String,
                    locationSource = (n["location_source"] as? String)?.trim(),
                    qty = (n["quantity"] as? Number)?.toDouble() ?: 0.0,
                    nativeStart = nativeStart,
                    nativeEnd = nativeEnd,
                    demandId = demandId?.toString()?.takeIf { it.isNotBlank() },
                    nativeMaxLotSize = n["max_lot_size"],
                    members = members,
                ))
                if (currentParent != null && currentParent != gid) {
                    parentsOf.getOrPut(gid) { mutableSetOf() }.add(currentParent)
                }
                val nextParent = realGid ?: currentParent
                for (c in nodeChildren) walk(c, demandId, members, nextParent, depth + 1)
            }
            "supply", "purchase" -> {
                if (currentParent != null) {
                    parseDate(n["commit_time"] as? String)?.let { commit ->
                        leafConstraintByGid.merge(currentParent, commit) { a, b -> if (b > a) b else a }
                    }
                }
                for (c in nodeChildren) walk(c, demandId, members, currentParent, depth + 1)
            }
            else -> for (c in nodeChildren) walk(c, demandId, members, currentParent, depth + 1)
        }
    }

    for (entry in peggingTrees) {
        @Suppress("UNCHECKED_CAST")
        val members = entry["consolidated_demand_ids"] as? List<String>
        // Skip supply-only pegging entries: demand_id = null AND no consolidated_demand_ids.
        // These are pure supply-graph entries with no real demand context; walking them produces
        // demand-orphan WO groups (empty demandQty + empty sampleMembers) in the consolidation output.
        val entryDemandId = entry["demand_id"]?.toString()?.takeIf { it.isNotBlank() }
        if (entryDemandId == null && members.isNullOrEmpty()) continue
        walk(entry["tree"], entryDemandId, members, null, 0)
    }

    // ── Phase B: aggregate occurrences by gid (sums ALL occurrences of a shared gid, e.g. the same
    // physical WO referenced from a VIRTUAL tree and a real-demand tree, BEFORE bucketing). ───────
    val gidInfo = linkedMapOf<String, GidInfo>()
    for (occ in occurrences) {
        val info = gidInfo.getOrPut(occ.gid) { GidInfo() }
        if (info.occurrenceCount == 0) {
            info.pid = occ.pid; info.lid = occ.lid; info.method = occ.method; info.locationSource = occ.locationSource
        }
        info.totalQty += occ.qty
        info.occurrenceCount++
        if (occ.nativeStart != null && (info.nativeStart == null || occ.nativeStart < info.nativeStart!!)) info.nativeStart = occ.nativeStart
        if (occ.nativeStart != null && occ.nativeEnd != null) {
            val dur = (occ.nativeEnd.toEpochDay() - occ.nativeStart.toEpochDay()).toDouble()
            if (info.nativeDuration == null || dur > info.nativeDuration!!) info.nativeDuration = dur
        }
        if (occ.demandId != null) info.demandQty[occ.demandId] = (info.demandQty[occ.demandId] ?: 0.0) + occ.qty
        if (occ.members != null) info.sampleMembers = occ.members
    }

    // ── Phase C: invert parentsOf (child -> parents) into directDeps (parent -> children) to seed
    // Kahn's-algorithm in-degree counts. ──────────────────────────────────────────────────────────
    val directDeps = mutableMapOf<String, MutableSet<String>>()
    for ((child, parents) in parentsOf) {
        for (p in parents) directDeps.getOrPut(p) { mutableSetOf() }.add(child)
    }
    val remaining = mutableMapOf<String, Int>()
    for (gid in gidInfo.keys) remaining[gid] = directDeps[gid]?.size ?: 0

    fun methodRowFor(pid: String, lid: String, method: String?, locationSource: String?): Map<String, Any?>? {
        if (method == null) return null
        val candidates = getMethods(pid, lid, data).filter { it["type"] == method }
        return if (method == "move") candidates.firstOrNull { (it["from_location_id"] as? String)?.trim() == locationSource } ?: candidates.firstOrNull()
        else candidates.firstOrNull()
    }

    val consolidatedOut = mutableListOf<Map<String, Any?>>()
    val consolidatedGidByGid = mutableMapOf<String, String>()
    val consolidatedStartByGid = mutableMapOf<String, LocalDate>()
    val consolidatedEndByGid = mutableMapOf<String, LocalDate>()
    // lot_count/wave_count as recomputed for this gid's FINAL (post-merge) bucket — propagated back
    // onto the pegging tree's WO node alongside start/end so R5_lead_time's expected-duration formula
    // (perLotLead × waveCount) reads the SAME wave count that produced the actual start/end span.
    // Without this, a gid trimmed by reconcile() across a lot-size boundary keeps its PRE-trim
    // lot_count/wave_count on the tree node while start/end reflect the post-trim (shorter) span.
    val consolidatedLotCountByGid = mutableMapOf<String, Int>()
    val consolidatedWaveCountByGid = mutableMapOf<String, Int>()

    fun singletonRow(gid: String, info: GidInfo, start: LocalDate?, end: LocalDate?, cgid: String): Map<String, Any?> = buildMap {
        put("product_id", info.pid)
        put("location_id", info.lid)
        put("quantity", info.totalQty)
        put("start_time", formatDate(start))
        put("end_time", formatDate(end))
        put("method", info.method)
        put("location_source", info.locationSource)
        put("demand_id", info.demandQty.keys.firstOrNull())
        put("prod_area", getProdArea(info.pid, info.lid, data))
        put("wo_group_id", gid)
        put("consolidated_group_id", cgid)
        put("wave_index", 0)
        if (info.method == "move") {
            put("lot_count", 1)
            val demandSplits = info.demandQty.entries.map { (d, qty) ->
                mapOf("demand_id" to d, "quantity" to qty)
            }
            put("move_components", listOf(mapOf(
                "product_id"   to info.pid,
                "quantity"     to info.totalQty,
                "demand_ids"   to info.demandQty.keys.toList(),
                "demand_splits" to demandSplits,
            )))
        } else {
            val lotSize = (maxLotSize(info.pid, info.lid, data)?.takeIf { it > 0 } ?: info.totalQty).coerceAtLeast(1e-9)
            put("lot_count", Math.ceil(info.totalQty / lotSize).toInt().coerceAtLeast(1))
            put("max_lot_size", lotSize)
        }
        // crossWaveCalendarMerge aggregates allDemands from wo_competing_demands (falling back to
        // demand_id only when this list is absent) and separately sums consolidation_split_details
        // by demand_id — always emit both consistently here, matching mergedRow()'s shape exactly,
        // so a singleton row that later cross-wave-merges with other demands' rows doesn't silently
        // drop its own demand's quantity from the merged split (it would still count toward the
        // merged row's total quantity, so the visible per-demand splits would stop summing to it).
        put("wo_competing_demands", info.demandQty.keys.toList())
        put("consolidated_demand_ids", info.sampleMembers ?: info.demandQty.keys.toList())
        if (info.demandQty.isNotEmpty()) {
            put("consolidation_split_details", info.demandQty.entries.map { (d, q) -> mapOf("demand_id" to d, "allocated_qty" to q) })
        }
    }

    fun mergedRow(gids: List<String>, mergedStart: LocalDate, mergedEnd: LocalDate, cgid: String): Map<String, Any?> {
        val first = gidInfo.getValue(gids[0])
        val totalQty = gids.sumOf { gidInfo.getValue(it).totalQty }
        val demands = gids.flatMap { gidInfo.getValue(it).demandQty.keys }.distinct()
        val splitDetails = gids.flatMap { g -> gidInfo.getValue(g).demandQty.entries.map { it.key to it.value } }
            .groupBy({ it.first }, { it.second })
            .map { (d, qtys) -> mapOf("demand_id" to d, "allocated_qty" to qtys.sum()) }
        // When constituent WOs came from virtual/null-demand pegging entries their demandId is null,
        // so demandQty is empty and demands=[]. Fall back to sampleMembers (real demand IDs carried
        // on the virtual entry's consolidated_demand_ids) so the frontend can still show the accordion.
        val effectiveDemands = demands.ifEmpty {
            gids.flatMap { gidInfo.getValue(it).sampleMembers ?: emptyList() }.distinct()
        }
        if (first.method == "move") {
            val moveComponents = gids.groupBy { gidInfo.getValue(it).pid }
                .map { (p, gs) ->
                    val splitMap = gs.flatMap { g ->
                        gidInfo.getValue(g).demandQty.entries.map { (d, qty) -> d to qty }
                    }.groupBy({ it.first }, { it.second })
                     .mapValues { it.value.sum() }
                    val demandSplits = splitMap.entries
                        .sortedBy { it.key }
                        .map { (d, qty) -> mapOf("demand_id" to d, "quantity" to qty) }
                    mapOf(
                        "product_id"    to p,
                        "quantity"      to gs.sumOf { gidInfo.getValue(it).totalQty },
                        "demand_ids"    to demandSplits.map { it["demand_id"] as String },
                        "demand_splits" to demandSplits,
                    )
                }
                .sortedBy { (it["product_id"] as? String) ?: "" }
            return mapOf(
                "product_id" to null,
                "location_id" to first.lid,
                "quantity" to totalQty,
                "start_time" to formatDate(mergedStart),
                "end_time" to formatDate(mergedEnd),
                "method" to "move",
                "location_source" to first.locationSource,
                "demand_id" to null,
                "prod_area" to getProdArea(first.pid, first.lid, data),
                "wo_group_id" to cgid,
                "consolidated_group_id" to cgid,
                "wave_index" to 0,
                "lot_count" to 1,
                "consolidated" to true,
                "wo_competing_demands" to effectiveDemands,
                "consolidation_split_details" to splitDetails,
                "consolidated_demand_ids" to effectiveDemands,
                "move_components" to moveComponents,
                "wo_window_start" to formatDate(mergedStart),
                "wo_window_end" to formatDate(mergedStart),
                "wo_consolidation_total_planned" to totalQty,
            )
        }
        val lotSize = (maxLotSize(first.pid, first.lid, data)?.takeIf { it > 0 } ?: totalQty).coerceAtLeast(1e-9)
        val lotCount = Math.ceil(totalQty / lotSize).toInt().coerceAtLeast(1)
        return mapOf(
            "product_id" to first.pid,
            "location_id" to first.lid,
            "quantity" to totalQty,
            "start_time" to formatDate(mergedStart),
            "end_time" to formatDate(mergedEnd),
            "method" to first.method,
            "location_source" to first.locationSource,
            "demand_id" to null,
            "prod_area" to getProdArea(first.pid, first.lid, data),
            "wo_group_id" to cgid,
            "consolidated_group_id" to cgid,
            "wave_index" to 0,
            "lot_count" to lotCount,
            "max_lot_size" to lotSize,
            "consolidated" to true,
            "wo_competing_demands" to effectiveDemands,
            "consolidation_split_details" to splitDetails,
            "consolidated_demand_ids" to effectiveDemands,
            "wo_window_start" to formatDate(mergedStart),
            "wo_window_end" to formatDate(mergedStart),
            "wo_consolidation_total_planned" to totalQty,
        )
    }

    // ── Phase D/E: Kahn's-algorithm wave loop. Each wave buckets/merges/recomputes-duration for
    // every gid whose dependencies are all already resolved, then propagates the new end_time up to
    // direct parents (pendingStart bump + in-degree decrement) before the next wave starts. ────────
    val pendingStart = mutableMapOf<String, LocalDate>()
    for ((gid, info) in gidInfo) info.nativeStart?.let { pendingStart[gid] = it }
    // "Latest time dominates," captured for both wave-consolidation flavors: horizontal (same
    // calendar-bucket contention, below) and bottom-up (a resolving child pushes its parent's
    // earliest start, further below). Keyed by ORIGINAL gid — same key space as
    // consolidatedStartByGid/consolidatedEndByGid — so it feeds rewritePeggingTimings directly.
    val timeDominatorByGid = mutableMapOf<String, DominatorRef>()
    fun gidDominatorRef(gid: String): DominatorRef {
        val info = gidInfo[gid]
        // Which demand(s) this wave-peer WO group actually belongs to — cross-demand WO
        // consolidation can merge another demand's own work order into the same wave bucket,
        // pushing THIS WO's timing even though the peer's natives come from a different demand
        // entirely. Carried forward (see resolveWavePeer) onto the final resolved dominator so
        // the UI can tell "delayed by my own WO" from "delayed by a consolidated peer WO
        // belonging to a different demand."
        val peerDemandIds = info?.demandQty?.keys?.toList().orEmpty()
        return DominatorRef(
            kind = "wave_peer", woGroupId = gid, productId = info?.pid, locationId = info?.lid,
            demandId = peerDemandIds.firstOrNull(),
            competingDemandIds = if (peerDemandIds.size > 1) peerDemandIds else null,
            label = "${info?.method ?: "wo"} ${info?.pid}@${info?.lid}",
        )
    }

    var frontier: List<String> = gidInfo.keys.filter { (remaining[it] ?: 0) == 0 }
    val visited = mutableSetOf<String>()
    var guard = 0
    while (frontier.isNotEmpty() && guard < 10000) {
        guard++
        for (gid in frontier) {
            leafConstraintByGid[gid]?.let { lc ->
                val cur = pendingStart[gid]
                if (cur == null || lc > cur) pendingStart[gid] = lc
            }
        }

        // Group frontier WOs by base identity (pid, lid, method, locationSource) first,
        // then merge overlapping production intervals within each group.
        // Fixed-window bucketing (epoch-rooted or calendar-month) splits WOs whose
        // production intervals physically overlap just because their start dates straddle a
        // bucket boundary — even when the shorter WO is entirely subsumed by the longer one.
        // Interval-overlap is the correct semantic: two WOs should consolidate iff their
        // [pendingStart, pendingStart+duration] intervals intersect.
        // windowDays <= 0 retains the original "collapse all" behaviour.
        val buckets = linkedMapOf<List<Any?>, MutableList<String>>()
        val passthroughGids = mutableListOf<String>()
        val baseGroups = linkedMapOf<List<Any?>, MutableList<String>>()
        for (gid in frontier) {
            val info = gidInfo.getValue(gid)
            val start = pendingStart[gid]
            if (start == null) { passthroughGids.add(gid); continue }
            val baseKey: List<Any?> = if (info.method == "move") listOf(
                "move", info.locationSource, info.lid, getProdArea(info.pid, info.lid, data)
            ) else listOf(info.pid, info.lid, info.method, info.locationSource)
            baseGroups.getOrPut(baseKey) { mutableListOf() }.add(gid)
        }
        for ((baseKey, gids) in baseGroups) {
            for (gid in gids) {
                val info = gidInfo.getValue(gid)
                val start = info.nativeStart ?: pendingStart[gid]!!
                val methodScale = woBatchConfig?.scaleFor(info.method)
                val wd = woBatchConfig?.legacyWindowDays ?: windowDays
                val effectiveBucketKey: List<Any?> = if (methodScale == "none")
                    baseKey + listOf("__none__", gid)  // singleton — never merges with any peer
                else
                    baseKey + listOf(calendarBucket(start, methodScale, wd))
                buckets.getOrPut(effectiveBucketKey) { mutableListOf() }.add(gid)
            }
        }

        for (gid in passthroughGids) {
            val info = gidInfo.getValue(gid)
            val cgid = nextWoGroupId()
            consolidatedGidByGid[gid] = cgid
            val row = singletonRow(gid, info, null, null, cgid)
            consolidatedOut.add(row)
            consolidatedLotCountByGid[gid] = (row["lot_count"] as? Number)?.toInt() ?: 1
            consolidatedWaveCountByGid[gid] = 1
            // No parseable date → nothing to propagate; matches old passthrough semantics.
        }

        for ((_, gids) in buckets) {
            val totalOccCount = gids.sumOf { gidInfo.getValue(it).occurrenceCount }
            val totalQty = gids.sumOf { gidInfo.getValue(it).totalQty }
            val mergedStart = gids.mapNotNull { pendingStart[it] }.maxOrNull()
            // Horizontal (same calendar-bucket contention): the gid(s) whose OWN pendingStart
            // already equalled mergedStart are the "winners" — everyone else in this bucket got
            // pushed out to match them. Only the pushed-out gids get a new dominator; a winner's
            // own dominator (e.g. from its own child pushing it, below) is left untouched.
            if (mergedStart != null && gids.size > 1) {
                val winners = gids.filter { pendingStart[it] == mergedStart }
                if (winners.isNotEmpty()) {
                    val winnerRef = gidDominatorRef(winners.first())
                    for (gid in gids) if (gid !in winners) timeDominatorByGid[gid] = winnerRef
                }
            }
            val cgid = nextWoGroupId()
            if (mergedStart == null || totalQty <= 1e-9) {
                // Degenerate: emit each gid as its own singleton (mirrors old zero-qty handling).
                for (gid in gids) {
                    val info = gidInfo.getValue(gid)
                    val gcgid = if (gid == gids[0]) cgid else nextWoGroupId()
                    consolidatedGidByGid[gid] = gcgid
                    val st = pendingStart[gid]
                    val row = singletonRow(gid, info, st, st, gcgid)
                    consolidatedOut.add(row)
                    consolidatedLotCountByGid[gid] = (row["lot_count"] as? Number)?.toInt() ?: 1
                    consolidatedWaveCountByGid[gid] = 1
                    if (st != null) { consolidatedStartByGid[gid] = st; consolidatedEndByGid[gid] = st }
                }
                continue
            }
            val first = gidInfo.getValue(gids[0])
            val mRow = methodRowFor(first.pid, first.lid, first.method, first.locationSource)
            var bucketLotCount = 1
            var bucketWaveCount = 1
            val leadDays = if (first.method == "move") {
                // Move bucketing (baseKey above) groups by (locationSource, lid, prodArea) —
                // deliberately WITHOUT product_id, so different products moving the same route can
                // share one consolidated batch. Using only gids[0]'s own transit_time as the
                // batch's duration would silently compress every OTHER product in the bucket onto
                // a window shorter than what it physically needs whenever gids[0]'s own
                // requirement happens to be the smallest (R5_transit_time). Consolidated transit
                // time = latest native end − earliest native start across every member in the
                // bucket — this measures the span each member's own already-correct native window
                // (established at commit time) actually needs, without re-deriving transit_time
                // from method tables again (and risking a re-match mismatch).
                val earliestStart = gids.mapNotNull { gidInfo.getValue(it).nativeStart }.minOrNull()
                val latestEnd = gids.mapNotNull { g ->
                    val gi = gidInfo.getValue(g)
                    gi.nativeStart?.let { s -> gi.nativeDuration?.let { d -> s.plusDays(d.toLong()) } }
                }.maxOrNull()
                if (earliestStart != null && latestEnd != null)
                    (latestEnd.toEpochDay() - earliestStart.toEpochDay()).toDouble().coerceAtLeast(0.0)
                else gids.mapNotNull { gidInfo.getValue(it).nativeDuration }.maxOrNull() ?: 0.0
            } else {
                val lotSize = (maxLotSize(first.pid, first.lid, data)?.takeIf { it > 0 } ?: totalQty).coerceAtLeast(1e-9)
                val lotCount = Math.ceil(totalQty / lotSize).toInt().coerceAtLeast(1)
                // Span = calendar lead_time only. Resource-capacity wave sequencing (waveCount × perWave)
                // belongs exclusively to ResourceScheduler, which runs after consolidation.
                val span = (mRow?.get("lead_time") as? Number)?.toDouble()
                    ?: gids.mapNotNull { gidInfo.getValue(it).nativeDuration }.maxOrNull() ?: 0.0
                bucketLotCount = lotCount
                // bucketWaveCount stays 1 — ResourceScheduler computes waveCount from lot_count and cap
                span
            }
            val mergedEnd = dateAddDays(mergedStart, leadDays) ?: mergedStart

            if (totalOccCount <= 1) {
                consolidatedOut.add(singletonRow(gids[0], first, mergedStart, mergedEnd, cgid))
            } else {
                consolidatedOut.add(mergedRow(gids, mergedStart, mergedEnd, cgid))
            }
            for (gid in gids) {
                consolidatedGidByGid[gid] = cgid
                consolidatedStartByGid[gid] = mergedStart
                consolidatedEndByGid[gid] = mergedEnd
                consolidatedLotCountByGid[gid] = bucketLotCount
                consolidatedWaveCountByGid[gid] = bucketWaveCount
            }
        }

        val nextCandidates = LinkedHashSet<String>()
        for (gid in frontier) {
            visited.add(gid)
            val end = consolidatedEndByGid[gid] ?: continue
            for (parent in (parentsOf[gid] ?: emptySet())) {
                if (parent in visited) continue
                val cur = pendingStart[parent]
                if (cur == null || end > cur) {
                    pendingStart[parent] = end
                    // Bottom-up (commit phase): this child's own finish is what currently sets
                    // its parent's earliest start — "latest time dominates."
                    timeDominatorByGid[parent] = gidDominatorRef(gid)
                }
                remaining[parent] = (remaining[parent] ?: 0) - 1
                if ((remaining[parent] ?: 0) <= 0) nextCandidates.add(parent)
            }
        }
        frontier = nextCandidates.filterNot { it in visited }
    }

    // Defensive: any gid never resolved (should not happen on a true DAG) still gets emitted so no
    // WO is silently dropped.
    for (gid in gidInfo.keys) {
        if (gid in consolidatedGidByGid) continue
        val info = gidInfo.getValue(gid)
        val cgid = nextWoGroupId()
        consolidatedGidByGid[gid] = cgid
        val st = pendingStart[gid] ?: info.nativeStart
        val row = singletonRow(gid, info, st, st, cgid)
        consolidatedOut.add(row)
        consolidatedLotCountByGid[gid] = (row["lot_count"] as? Number)?.toInt() ?: 1
        consolidatedWaveCountByGid[gid] = 1
        if (st != null) { consolidatedStartByGid[gid] = st; consolidatedEndByGid[gid] = st }
    }

    // ── Phase F: native output — one row per ORIGINAL occurrence, carrying its own native timing
    // (the orchestrator patches native timing to match the FINAL consolidated/arbitrated timing in
    // a later step), tagged with the consolidated_group_id it rolled into. ────────────────────────
    val nativeOut = occurrences.map { occ ->
        val cgid = consolidatedGidByGid[occ.gid]
        buildMap<String, Any?> {
            put("product_id", occ.pid)
            put("location_id", occ.lid)
            put("quantity", occ.qty)
            put("start_time", formatDate(occ.nativeStart))
            put("end_time", formatDate(occ.nativeEnd))
            put("method", occ.method)
            put("location_source", occ.locationSource)
            put("demand_id", occ.demandId)
            put("prod_area", getProdArea(occ.pid, occ.lid, data))
            put("wo_group_id", occ.originalGid)
            put("lot_count", 1)
            put("max_lot_size", occ.nativeMaxLotSize)
            put("wave_index", 0)
            if (occ.members != null) put("consolidated_demand_ids", occ.members)
            put("consolidated_group_id", cgid)
        }
    }

    // ── Phase G: rewrite the pegging trees with final (wave-pushed) timings, and roll the result up
    // into each demand/supply/purchase node's commit_time bottom-up — single pass, no fixed-point
    // iteration needed since the wave loop above already established globally-consistent timings.
    val rewrittenTrees = rewritePeggingTimings(
        peggingTrees, consolidatedStartByGid, consolidatedEndByGid,
        consolidatedLotCountByGid, consolidatedWaveCountByGid,
        timeDominatorByGid, data, config,
    )

    return WaveConsolidationResult(rewrittenTrees, WoConsolidation(consolidatedOut, nativeOut))
}

/**
 * Rewrite pegging-tree WO node timings from per-`wo_group_id` final start/end maps, rolling the
 * result up into each demand/supply/purchase node's `commit_time` bottom-up. Shared by
 * [consolidateByWaves] (wave-computed timings) and the main orchestrator (post-arbitration
 * capacity-shifted timings) so both timing-correction passes write back into the trees the same
 * way.
 *
 * `lotCountByGid`/`waveCountByGid` are optional (only [consolidateByWaves] supplies them) — they
 * keep the tree node's `lot_count`/`wave_count` in sync with whatever lot/wave packing actually
 * produced `startByGid`/`endByGid`'s span. Without this, a WO trimmed by `reconcile()` across a
 * lot-size boundary keeps its PRE-trim lot_count/wave_count on the tree while start/end reflect
 * the POST-trim (shorter) span, so the soundness checker's expected-duration formula
 * (perLotLead × waveCount, read from the same node) disagrees with the actual span it itself wrote.
 * The post-arbitration call (orchestrator) omits them deliberately — arbitration/resequence only
 * shift a WO's start+end by the same delta (`pushUp`), preserving its already-correct lot/wave count.
 */
internal fun rewritePeggingTimings(
    peggingTrees: List<Map<String, Any?>>,
    startByGid: Map<String, LocalDate>,
    endByGid: Map<String, LocalDate>,
    lotCountByGid: Map<String, Int> = emptyMap(),
    waveCountByGid: Map<String, Int> = emptyMap(),
    // "Latest time dominates": populated by consolidateByWaves's wave loop (horizontal
    // same-bucket contention + bottom-up child-pushes-parent) — see its own doc for how this
    // map is built. Empty by default so callers that don't run wave consolidation see no change.
    timeDominatorByGid: Map<String, DominatorRef> = emptyMap(),
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>? = null,
): List<Map<String, Any?>> {
    // Index every (non-failed) work_order node by its wo_group_id, from the SAME trees being
    // rewritten — these already carry whatever quantity_dominator/time_dominator the commit
    // phase (Sites 1/2/5/6) resolved for them, since legacyCommit runs before wave
    // consolidation. Lets a Site-4 wave_peer pointer (which only knows the OTHER wo_group_id,
    // not that WO's own resolved dominator) be resolved one hop further via rawDominatorRefs,
    // instead of landing on the caller's screen as an unresolved "delayed by move X" pointer —
    // per the mental model, any work order is a consequence, never itself the root cause.
    val woNodeByGid = mutableMapOf<String, Map<String, Any?>>()
    fun indexWoNodes(node: Map<String, Any?>, depth: Int) {
        if (depth > 80) return
        if (node["type"] == "work_order" && node["failed"] != true) {
            (node["wo_group_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.let { woNodeByGid[it] = node }
        }
        @Suppress("UNCHECKED_CAST")
        val kids = node["children"] as? List<Map<String, Any?>> ?: emptyList()
        kids.forEach { indexWoNodes(it, depth + 1) }
    }
    peggingTrees.forEach { entry ->
        @Suppress("UNCHECKED_CAST")
        (entry["tree"] as? Map<String, Any?>)?.let { indexWoNodes(it, 0) }
    }
    // Never falls back to the unresolved wave_peer ref itself — per the mental model, a work
    // order is always a consequence, never a root cause, so if the peer WO has no raw supply
    // beneath it (e.g. purely purchase-sourced), the correct answer is "nothing to show," not
    // an unresolved WO-to-WO pointer.
    fun resolveWavePeer(ref: DominatorRef): List<DominatorRef> {
        val target = ref.woGroupId?.let { woNodeByGid[it] } ?: return emptyList()
        val resolved = rawDominatorRefs(target, "time_dominator", ref.kind, data, config)
        // Tag with which demand(s) the wave-peer WO that caused this push actually belongs to —
        // cross-demand WO consolidation can merge another demand's own work order into the same
        // wave bucket, so the true cause here may not be the demand currently being viewed at
        // all. Only fills this in when the resolution didn't already carry a more specific one
        // from further down its own chain (a deeper, already-resolved dominator wins).
        return resolved.map { r ->
            if (r.demandId != null) r else r.copy(demandId = ref.demandId, competingDemandIds = ref.competingDemandIds)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun rewriteNode(node: Map<String, Any?>, depth: Int): Map<String, Any?> {
        if (depth > 80) return node
        if (node["type"] == "work_order" && node["failed"] == true) return node
        val origChildren = node["children"] as? List<Map<String, Any?>>
        val newChildren = origChildren?.map { rewriteNode(it, depth + 1) }
        val childrenChanged = newChildren != null &&
            newChildren.indices.any { i -> newChildren[i] !== origChildren!![i] }

        if (node["type"] == "work_order") {
            val gid = (node["wo_group_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            val newStart = gid?.let { startByGid[it] }
            val newEnd = gid?.let { endByGid[it] }
            val newLotCount = gid?.let { lotCountByGid[it] }
            val newWaveCount = gid?.let { waveCountByGid[it] }
            val newTimeDominator = gid?.let { timeDominatorByGid[it] }
            val startChanged = newStart != null && formatDate(newStart) != node["start_time"]
            val endChanged = newEnd != null && formatDate(newEnd) != node["end_time"]
            val lotCountChanged = newLotCount != null && newLotCount != (node["lot_count"] as? Number)?.toInt()
            val waveCountChanged = newWaveCount != null && newWaveCount != (node["wave_count"] as? Number)?.toInt()
            val result = if (startChanged || endChanged || lotCountChanged || waveCountChanged || childrenChanged) {
                node.toMutableMap().also { m ->
                    if (startChanged) m["start_time"] = formatDate(newStart)
                    if (endChanged) m["end_time"] = formatDate(newEnd)
                    if (lotCountChanged) m["lot_count"] = newLotCount
                    if (waveCountChanged) m["wave_count"] = newWaveCount
                    if (childrenChanged) m["children"] = newChildren
                    if ((startChanged || endChanged) && newTimeDominator != null) {
                        val resolved = resolveWavePeer(newTimeDominator)
                        if (resolved.isNotEmpty()) m["time_dominator"] = resolved.toJsonList()
                    }
                }
            } else node
            // Keep the cross-reference index current as rewriting proceeds bottom-up, so a
            // PARENT's own wave_peer resolution (processed immediately after, in the same
            // recursive call — children are always rewritten before their parent above) sees
            // this node's freshly-resolved time_dominator instead of a stale pre-rewrite
            // snapshot with none. This is what lets a two-hop chain (a WO pushed by its OWN
            // child WO, which was itself pushed by a horizontal wave peer belonging to another
            // demand) resolve all the way through to the raw supply in one pass.
            if (gid != null) woNodeByGid[gid] = result
            return result
        }

        if (node["type"] == "demand" || node["type"] == "supply" || node["type"] == "purchase") {
            val commitCandidates = (newChildren ?: emptyList()).mapNotNull { ch ->
                val r = ch["commit_reason"] as? String
                if (r == "cycle_stopped" || r == "cycle_detected" || isHardPlanningFailure(r)) return@mapNotNull null
                val d = when (ch["type"] as? String) {
                    "work_order" -> parseDate(ch["end_time"] as? String)
                    "demand", "supply", "purchase" -> parseDate(ch["commit_time"] as? String)
                    else -> null
                } ?: return@mapNotNull null
                d to ch
            }
            val newCommit = commitCandidates.maxOfOrNull { it.first }
            val current = parseDate(node["commit_time"] as? String)
            val commitChanged = newCommit != null && newCommit != current
            return if (commitChanged || childrenChanged) {
                node.toMutableMap().also { m ->
                    if (childrenChanged) m["children"] = newChildren
                    if (commitChanged) {
                        m["commit_time"] = formatDate(newCommit)
                        // "Latest time dominates": exactly ONE dominator — the single child whose
                        // own commit/end time IS the new rollup value — resolved down to the raw
                        // supply leaf that's genuinely responsible (ch is itself already fully
                        // rewritten by this same bottom-up pass — newChildren are recursed before
                        // the parent, so if ch already carries its own time_dominator from a
                        // deeper level, rawDominatorRefs propagates that instead of stopping at
                        // this intermediate work_order/demand/purchase node). Several candidates
                        // can tie at the exact same date; that's not several equally-responsible
                        // causes, so only the first tied one is used.
                        val winner = commitCandidates.firstOrNull { it.first == newCommit }?.second
                        val winners = rawDominatorRefs(winner, "time_dominator", "bom_child", data, config)
                        if (winners.isNotEmpty()) m["time_dominator"] = winners.toJsonList()
                    }
                }
            } else node
        }

        return if (childrenChanged) node.toMutableMap().also { it["children"] = newChildren } else node
    }

    return peggingTrees.map { entry ->
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        val newTree = rewriteNode(tree, 0)
        if (newTree === tree) entry else entry.toMutableMap().also { it["tree"] = newTree }
    }
}

/**
 * Clone pegging trees with each WO node's `wo_group_id` swapped to its consolidated group id, so
 * [buildPeggingDag]/[resequenceFromPegging] can compute cross-batch capacity-cascade dependencies
 * at the CONSOLIDATED granularity (matching a flat lots list keyed by `consolidated_group_id`).
 * Used only as scratch input for that DAG walk — never returned as final output.
 */
internal fun relabelTreesToConsolidatedGids(
    peggingTrees: List<Map<String, Any?>>,
    gidToCgid: Map<String, String>,
): List<Map<String, Any?>> {
    @Suppress("UNCHECKED_CAST")
    // Returns original reference when nothing in the subtree needs to change —
    // avoids cloning every ancestor node on the path to each work_order, which
    // previously caused OOM on large consolidations (200 trees × 62K lots).
    fun rewrite(node: Map<String, Any?>): Map<String, Any?> {
        val children = node["children"] as? List<Map<String, Any?>>
        val newChildren = children?.map { rewrite(it) }
        val childrenChanged = newChildren != null &&
            newChildren.indices.any { newChildren[it] !== children!![it] }

        val cgid = if (node["type"] == "work_order") {
            (node["wo_group_id"] as? String)?.let { gidToCgid[it] }
        } else null

        if (!childrenChanged && cgid == null) return node  // nothing changed — reuse reference

        val copy = node.toMutableMap()
        if (childrenChanged) copy["children"] = newChildren
        if (cgid != null) copy["wo_group_id"] = cgid
        return copy
    }
    return peggingTrees.map { entry ->
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        val newTree = rewrite(tree)
        if (newTree === tree) entry else entry.toMutableMap().also { it["tree"] = newTree }
    }
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
    failed: Boolean = false,
    woGroupId: String? = null,
    data: Map<String, List<Map<String, Any?>>>? = null,
    quantityDominator: List<DominatorRef> = emptyList(),
    timeDominator: List<DominatorRef> = emptyList(),
): Map<String, Any?> = buildMap {
    put("type", "work_order")
    put("product_id", productId)
    put("location_id", productionLocation)
    put("quantity", roundQty(qty))
    // Unrounded companion — see committedRow's doc. reconcile() (the bottom-up Phase 3
    // commitment aggregate) reads this back in preference to "quantity" so a genuinely-achieved
    // fractional amount doesn't get misread as "delivered 0" one level up.
    put("quantity_precise", qty)
    put("start_time", formatDate(startDt))
    put("end_time", formatDate(lastEnd))
    put("method", methodType)
    put("location_source", if (methodType == "move") m["from_location_id"] else null)
    put("method_choice_explanation", methodChoiceExpl)
    put("variant_choice_explanation", variantExpl.ifBlank { null })
    if (quantityDominator.isNotEmpty()) put("quantity_dominator", quantityDominator.toJsonList())
    if (timeDominator.isNotEmpty()) put("time_dominator", timeDominator.toJsonList())
    put("children_relation", childrenRelation)
    put("lot_count", if (lotCount > 0) lotCount else null)
    put("max_lot_size", lotSizeVal)

    // Wave structure for make WOs. cap > 0 (BOR present) → resource-limited;
    // cap == 0 (no BOR data, e.g. subcon) → unconstrained parallel (wave_count=1),
    // consistent with buildWorkOrders which also treats cap=0 as Int.MAX_VALUE.
    val rawCapNode = if (methodType == "make" && data != null) OperationLookup.parallelismCap(productId, productionLocation, data) else 0
    val cap = if (rawCapNode > 0) rawCapNode else Int.MAX_VALUE
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

    val originalChildren = node["children"] as? List<Map<String, Any?>> ?: emptyList()
    val prunedChildren = originalChildren
        .mapNotNull { prunePhantomLoops(it, isRoot = false, parentIsMoveWo = isMoveWo, insideFailedWo = descendantInsideFailedWo) }

    // Preserve explicitly-failed work_orders even when empty — they're
    // diagnostic markers (failed move with cycle_stopped child pruned out,
    // make stub injected when structuralFailedMakes memo'd, AND-bottleneck
    // blocked branch). Stripping them hides why the parent demand failed.
    if (type == "work_order" && prunedChildren.isEmpty() && !explicitlyFailed) return null
    // Only prune childless demand nodes that are transit stops inside a move chain,
    // not real component demands (children of make WOs or the root).
    if (type == "demand" && prunedChildren.isEmpty() && !isRoot && parentIsMoveWo) return null

    // Only copy the node when children actually changed — some nodes are backed by
    // immutable mapOf() and cannot be mutated in place (UnsupportedOperationException).
    if (prunedChildren.size == originalChildren.size) return node
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
    /** Supply IDs that belong to critical (non-purchasable) lots. When non-null, only these
     *  lots receive a qty_allocated; all other lots are non-critical (FIFO consumption,
     *  uncapped) and emit qty_allocated = null so the UI shows "–". */
    criticalSupplyIds: Set<String>? = null,
    /** Per-demand, per-lot entitlement actually enforced during planning — [SupplyAllocationResult.perLotBudgets],
     *  keyed `demandId -> "productId|locationId|supplyId" -> qty`. This is the SAME map that caps
     *  `consumeFromInventory`'s draws (via `initialBudget`/`branchLotCap`), so surfacing it here — rather than
     *  re-deriving a proportional estimate after the fact — makes the displayed "qty_allocated" the actual
     *  enforced cap instead of an independently-computed number that can silently diverge from it per lot. */
    perLotBudgets: Map<Any?, Map<String, Double>>? = null,
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

    // Post-process: qty_allocated = the demand's REAL, planning-time entitlement for this
    // specific lot — perLotBudgets[demandId]["productId|locationId|supplyId"], the exact
    // per-lot cap that governed consumeFromInventory during commit (via initialBudget/
    // branchLotCap). Not re-derived here — just surfaced, so the UI can never show a
    // "qty_allocated" that silently diverges from what was actually enforced. Only critical
    // lots (those in criticalSupplyIds) carry an entitlement at all; non-critical lots are
    // consumed FIFO with no allocation concept, so their qty_allocated is null (UI shows "-").
    // Mutates result records in-place (MutableMap) to avoid allocating a new map per record.
    // supply_id -> (productId, locationId) lookup, needed to reconstruct each lot's budget key
    // (physical lots only — synthetic/consolidated buckets have no entry and no entitlement).
    val lotPidLid: Map<String, Pair<String, String>> = supplies.mapNotNull { s ->
        val sid = (s["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val pid = (s["product_id"] as? String)?.trim() ?: return@mapNotNull null
        val lid = (s["location_id"] as? String)?.trim() ?: return@mapNotNull null
        sid to (pid to lid)
    }.toMap()
    for (rec in result) {
        val sid = rec["supply_id"] as? String
        val isCritical = criticalSupplyIds == null || (sid != null && sid in criticalSupplyIds)
        val did = rec["demand_id"] as? String
        val pidLid = sid?.let { lotPidLid[it] }
        val qtyAllocated: Double? = if (!isCritical) {
            null  // non-critical (purchasable): FIFO consumption, no allocation concept
        } else if (pidLid != null && did != null && perLotBudgets != null) {
            val lotKey = "${pidLid.first}|${pidLid.second}|$sid"
            perLotBudgets[did]?.get(lotKey) ?: 0.0
        } else {
            0.0
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
 * Phase 3 (legacy): plan each user demand against the inventory left by
 * phase 2 (real supplies + tagged synthetic buckets). Emits committed rows,
 * work orders, and per-demand pegging trees.
 */
internal fun legacyCommit(
    demands: List<Map<String, Any?>>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
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
    /** Optional Preferences KB override, see [plan]'s `preferenceKb` param. Passed through
     *  unchanged to every demand's [plan] call. */
    preferenceKb: PreferenceKb? = null,
    /** Optional intra-demand sibling-contention table from [computeAndSiblingCaps] (the
     *  "diamond problem" fix), keyed by demand_id. Sliced per-demand and passed to [plan]
     *  as `andSiblingCaps`. */
    andSiblingCaps: Map<Any?, Map<BranchKey, Map<String, Double>>>? = null,
    /** Optional intra-demand sibling-contention DOMINATOR table from
     *  [computeAndSiblingCaps]'s step (c) — the dominator-side twin of [andSiblingCaps],
     *  keyed the same way (demand_id). Sliced per-demand and passed to [plan] as
     *  `andSiblingDominators`. */
    andSiblingDominators: Map<Any?, Map<BranchKey, List<DominatorRef>>>? = null,
    /** Structural OR-group recipient sets per critical material from [findOrGroupRecipients] —
     *  global, identical for every demand, passed straight through to [plan] as
     *  `diamondRecipients` (unsliced, unlike the other per-demand tables above). */
    diamondRecipients: Map<String, Set<String>>? = null,
    /** Per-demand raw (unsplit) entitlement per critical material with known
     *  [diamondRecipients], from [buildDiamondCriticalEntitlement]. Sliced per-demand and
     *  passed to [plan] as `diamondCriticalEntitlement` — [plan]'s own waterfall loop computes
     *  the actual per-recipient caps live, per candidate attempt; `legacyCommit` no longer
     *  precomputes a static, pre-split `diamondRecipientCaps` map at all. */
    diamondCriticalEntitlement: Map<Any?, Map<String, Map<String, Double>>>? = null,
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
        // Fresh per demand: the demand-wide, never-restored record of how much of its own
        // static per-lot cap it has actually spent so far, across every independent branch
        // of its own tree — see plan()'s doc for why this can't just be derived from `budget`.
        val demandConsumed = mutableMapOf<String, Double>()
        // Fresh per demand: pooled consumption for whichever diamond recipients this demand's
        // tree actually visits, keyed by product identity — see diamondRecipientCaps's own doc.
        val diamondRecipientConsumed = mutableMapOf<String, MutableMap<String, Double>>()
        val (solvedList, wos, peggingNode) = plan(
            d, indexedInventory, planData, reqDt,
            config = config, preferDemandId = prefId,
            budget = demandBudget,
            feasibilityCache = feasibilityCache,
            structuralFailedMakes = structuralFailedMakes,
            initialBudget = iter0Allocation?.get(demandId),
            demandConsumed = demandConsumed,
            nodeQtyCaps = achievableQtyMaps?.get(demandId),
            demandBlueprint = planBlueprint?.get(demandId),
            preferenceKb = preferenceKb,
            andSiblingCaps = andSiblingCaps?.get(demandId),
            andSiblingDominators = andSiblingDominators?.get(demandId),
            diamondRecipientConsumed = diamondRecipientConsumed,
            diamondRecipients = diamondRecipients,
            diamondCriticalEntitlement = diamondCriticalEntitlement?.get(demandId),
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
    /** Optional Preferences KB override, see [plan]'s `preferenceKb` param. `null` when no
     *  Preferences KB exists for this case — preserves today's exact raw-preference behavior. */
    preferenceKb: PreferenceKb? = null,
    /** Optional Demand Ordering KB override: demand_id -> canonical processing order (10, 20,
     *  30, ...). When present, demands covered by it sort first (by that order); demands NOT
     *  covered (e.g. added after the KB was last generated) fall back to today's raw
     *  `(priority, demand_id)` sort and sort after all covered demands — the same
     *  per-alternative-not-all-or-nothing fallback principle as [preferenceKb]. `null` (no KB
     *  for this case) preserves today's exact `(priority, demand_id)` sort. */
    demandOrder: Map<String, Int>? = null,
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

    // Coarse, whole-run progress reporting. The three pre-legacyCommit steps below
    // (buildSupplyAllocation, computePlanBlueprint, computeAndSiblingCaps) are each a full,
    // read-only BOM walk over every demand and — since computeAndSiblingCaps's own gather pass
    // was added — can now dominate total run time on a large case, with legacyCommit's
    // per-demand callback (the only progress this ever reported) staying frozen at "0/N" the
    // entire time. These percentages are rough, fixed weights (not measured per-run) — good
    // enough to turn "looks hung for minutes" into "visibly moving through named stages";
    // exact proportion isn't load-bearing anywhere else.
    fun emitPhase(phase: String, label: String, percent: Int) {
        progressCallback?.invoke(mapOf("phase" to phase, "phase_label" to label, "percent" to percent))
    }
    emitPhase("allocating", "Allocating supply across demands…", 0)

    // Demand processing order: covered by the Demand Ordering KB (see `demandOrder` doc) sorts
    // first by its canonical order; anything not covered falls back to the raw (priority,
    // demand_id) rule and sorts after all covered demands. demandOrder == null (no KB) reduces
    // the first two comparator keys to no-ops, preserving today's exact sort unchanged.
    val demands = (data["demand"] ?: emptyList()).sortedWith(
        compareBy(
            { d: Map<String, Any?> -> if (demandOrder?.containsKey(d["demand_id"]?.toString()) == true) 0 else 1 },
            { d: Map<String, Any?> -> demandOrder?.get(d["demand_id"]?.toString()) ?: Int.MAX_VALUE },
            { d: Map<String, Any?> -> (d["priority"] as? Number)?.toInt() ?: 0 },
            { d: Map<String, Any?> -> d["demand_id"]?.toString() ?: "" },
        )
    )

    val committedDemands = mutableListOf<Map<String, Any?>>()
    val workOrders = mutableListOf<Map<String, Any?>>()
    val planningPegging = mutableListOf<Map<String, Any?>>()

    // Conservation baseline: after supply-split overrides so that demand-tagged sub-buckets
    // (Σcaps ≤ original_qty) are the reference, not the raw input buckets.
    val inventoryEffectiveInitial: List<Map<String, Any?>> = inventory.map { it.toMap() }

    // ── Phase 1 (supply allocation) + Phase 2 (commit) ──────────────────────
    val consolidationConfig = parseConsolidationConfig(config)
    val producedByComponent: Map<String, Double> = emptyMap()
    val releasedByComponent: Map<String, Double> = emptyMap()

    // Step 1+2: pure allocation — BOM reachability walk + proportional supply split.
    val sgAllocationBase = buildSupplyAllocation(demands, data, config)
    val sgAllocation = if (precomputedBudgets != null) {
        log.info("[supply-guided] using case_allocation override: {} demand budget entries", precomputedBudgets.size)
        sgAllocationBase.copy(perLotBudgets = precomputedBudgets)
    } else {
        sgAllocationBase
    }
    // Step 2b: sketch phase — one read-only BOM walk that both computes achievable caps
    // AND pre-selects the first-feasible BOM method per node per demand.
    // Replaces computeAchievableQtyMaps; eliminates getPreferredMethodCascade overhead
    // (probeChildren per node) from the commit phase entirely.
    emitPhase("blueprint", "Computing achievable quantities…", 8)
    val planBlueprint = computePlanBlueprint(demands, sgAllocation, data, preferenceKb, config)
    val achievableQtyMaps = planBlueprint.mapValues { (_, db) ->
        db.mapValues { (_, nb) -> nb.achievable }
    }
    // Static per-demand, per-lot allocation cap — a deep, immutable snapshot taken before
    // `legacyCommit` starts mutating `sgAllocation.perLotBudgets` demand-by-demand. Budget
    // (the cap) and stock/inventory (the dynamic balance) were previously mixed into one
    // mutable structure with no read-only reference to fall back on; this snapshot is that
    // reference, threaded through as `initialBudget` so `plan()`/`planMethodSlot` can enforce
    // "the dynamic remaining balance may never exceed the static cap" as a hard invariant.
    val pristineBudgetCaps: Map<Any?, Map<String, Double>> =
        sgAllocation.perLotBudgets.mapValues { (_, lotMap) -> lotMap.toMap() }
    // Intra-demand sibling contention (the "diamond problem"): a demand's own AND-required
    // BOM siblings, or its root-level rootSplitWeights candidates, can independently reach
    // the same scarce critical material — the live commit phase explores them sequentially
    // and greedily, so an early branch can exhaust a shared lot before an equally-entitled
    // later sibling gets a look even when a fair split across all of them would let more
    // succeed. Computed once here, up front, via its own top-down gather pass (Phase 1 +
    // Phase 2, SupplyGuidedPlanning.kt) over the same KB-ranked topology the sketch phase
    // above already walks — see gatherAndSiblingRequests/computeAndSiblingCaps docs.
    emitPhase("contention", "Resolving shared-material contention…", 20)
    var lastContentionPercent = 20
    val andSiblingCapsResult = computeAndSiblingCaps(
        demands, sgAllocation, data, config, preferenceKb,
        onDemandProcessed = { done, total ->
            val percent = (20 + (done.toDouble() / total.coerceAtLeast(1)) * 63).toInt().coerceIn(20, 83)
            if (percent > lastContentionPercent) {
                lastContentionPercent = percent
                emitPhase("contention", "Resolving shared-material contention… ($done/$total)", percent)
            }
        },
        planBlueprint = planBlueprint,
    )
    val andSiblingCaps = andSiblingCapsResult.caps
    val andSiblingDominators = andSiblingCapsResult.dominators
    // Structural (BOM-topology-only) OR-group grand-parent recipients — generalizes the former
    // hardcoded 160-1153/A1/A3 shape to any critical material reached through an OR-group.
    // Takes precedence over andSiblingCaps at the AND-loop lookup site for whichever recipients
    // are found; andSiblingCaps stays available, unchanged, for anything else. Computed once
    // here (pure BOM topology, no quantities); the actual per-recipient split happens live,
    // per waterfall-candidate attempt, inside plan()'s own loop — see
    // computeDiamondCapsForAttempt's own doc for why.
    val diamondRecipients = findOrGroupRecipients(
        sgAllocation.criticalMatrix.byColumn.keys.map { it.productId }.toSet(), data,
    )
    val diamondCriticalEntitlement = buildDiamondCriticalEntitlement(demands, sgAllocation, diamondRecipients)
    // Step 3: commit with pre-selected methods, per-node caps, and per-lot budget guards.
    // Wraps the raw per-demand callback with the overall phase/percent this stage occupies
    // (83%→95%) so the UI can keep showing "N / total demands" (from legacyCommit's own
    // payload, passed through unchanged) alongside a moving overall bar, instead of two
    // disconnected progress notions.
    val commitProgressCallback: ((Map<String, Any?>) -> Unit)? = progressCallback?.let { cb ->
        { p: Map<String, Any?> ->
            val current = (p["current"] as? Number)?.toDouble() ?: 0.0
            val total = (p["total"] as? Number)?.toDouble()?.takeIf { it > 0 } ?: 1.0
            val percent = (83 + (current / total) * 12).toInt().coerceIn(83, 95)
            cb(p + mapOf("phase" to "committing", "phase_label" to "Committing demands…", "percent" to percent))
        }
    }
    emitPhase("committing", "Committing demands…", 83)
    var commitResult = legacyCommit(
        demands           = demands,
        inventory         = inventory,
        data              = data,
        config            = config,
        useTaggedLookup   = false,
        progressCallback  = commitProgressCallback,
        budgets           = sgAllocation.perLotBudgets,
        iter0Allocation   = pristineBudgetCaps,
        achievableQtyMaps = achievableQtyMaps,
        planBlueprint     = planBlueprint,
        preferenceKb      = preferenceKb,
        andSiblingCaps    = andSiblingCaps,
        andSiblingDominators = andSiblingDominators,
        diamondRecipients = diamondRecipients,
        diamondCriticalEntitlement = diamondCriticalEntitlement,
    )
    // Post-planning trace + compensation-pass telemetry (opt-in: trace_lots=true).
    if (sgAllocation.sgConfig.traceLots)
        logSupplyGuidedTrace(sgAllocation, commitResult.planningPegging, data)

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
        val base = planningPegging
        base.mapNotNull { entry ->
            val tree = entry["tree"] as? Map<String, Any?> ?: return@mapNotNull null
            val pruned = prunePhantomLoops(tree, isRoot = true) ?: return@mapNotNull null
            entry.toMutableMap().apply { put("tree", pruned) }
        }
    }
    // Release original pegging lists so GC can reclaim them before the verification + timing passes.
    planningPegging.clear()
    val suppliesForCap = data["supply"] ?: emptyList()
    // Critical supply IDs = lots whose perLotBudget was computed (non-purchasable raw materials).
    // Non-critical lots are consumed FIFO without allocation; they get qty_allocated = null.
    val criticalSupplyIds: Set<String> = sgAllocation.perLotBudgets.values
        .flatMap { it.keys }
        .filterTo(mutableSetOf()) { it.count { c -> c == '|' } >= 2 }
        .mapTo(mutableSetOf()) { it.substringAfterLast('|') }
    // Use pristineBudgetCaps (the deep snapshot taken before legacyCommit starts mutating
    // sgAllocation.perLotBudgets in place via write-back) — the live map holds REMAINING
    // budget by this point, not the original entitlement that was actually enforced.
    val supplyAllocations = extractSupplyAllocations(allPegging, suppliesForCap, criticalSupplyIds, pristineBudgetCaps)
    val supplyCapViolations = verifySupplyCap(suppliesForCap, supplyAllocations)
    // Served demand IDs (committed > 0, excluding hard-planning-failure rows) — used by R7f
    // below. R7e (mass conservation) used to be computed inline here too, but that result was
    // never actually read by anything (not the frontend, not any other backend consumer) — the
    // real, user-facing soundness check always re-computes it independently from persisted data
    // via SoundnessChecker.kt's checkRunSoundness/checkRunSoundnessStreaming (the /soundness API
    // endpoint). Having two implementations of the same rule let them silently drift out of
    // sync (one was correctly servedDemandIds-filtered, the other wasn't) — removed here so
    // SoundnessChecker.kt is the single source of truth.
    val servedDemandIds: Set<String> = committedDemands
        .filter { !isHardPlanningFailure(it["commit_reason"] as? String) }
        .filter { ((it["quantity"] as? Number)?.toDouble() ?: 0.0) > 1e-9 }
        .mapNotNull { it["demand_id"]?.toString() }
        .toSet()
    // R7f: component conservation — produced qty (Phase 1 Step 2) vs consumed by served demands.
    val componentConservationViolations = if (producedByComponent.isNotEmpty())
        verifyComponentConservation(producedByComponent, allPegging, servedDemandIds = servedDemandIds)
    else emptyList<String>()
    // R7g computed after adjustedConsolidated is available (below the timing-readjust block).

    emitPhase("verifying", "Verifying supply conservation…", 95)
    val timingFix = fixTimingFromPegging(workOrders, allPegging, data)
    // Release pruned trees; timingFix holds the timing-adjusted copies.
    allPegging = emptyList()

    // ── Phase 3 — bottom-up COMMITMENT aggregate over the timing-fixed pegging. The closing step
    // of the quantity pass in the mental model: a parent commits min over children of
    // (child_commit / bom_rate) — "least supplied dominates". Top-down planning commits greedily,
    // so cross-demand inventory contention can leave a make committed ABOVE what its children
    // actually supply (the R4/R8 break). `reconcile` trims those (a no-op for already-consistent
    // trees). Runs here (before consolidation/arbitration) since it only trims quantities — that's
    // orthogonal to timing/capacity, so there's no need to wait for either.
    emitPhase("reconciling", "Reconciling commitments…", 96)
    // Dominators are trusted verbatim from wherever they were resolved during planning (the
    // sketch phase, live commit's AND/OR collapses, or step (c)'s intra-demand fair-split) —
    // reconcile() propagates whichever child/winner already carries one, never re-deriving or
    // re-validating it against a demand-wide aggregate entitlement. An earlier version of this
    // pass re-validated each dominator's named lot against isLotExhausted/isProductExhausted
    // (comparing actual consumption to a demand-wide fair-split cap) — traced live on
    // 858_F35_2024_07_VIRTUAL and found to be comparing against the wrong baseline: with a
    // material's budget fragmented across ~300 independent draw positions (many different BOM
    // paths converging on the same scarce material), no single position's local draw ever comes
    // close to the demand's large, theoretical aggregate entitlement, so the check disqualified
    // every candidate everywhere in the tree — leaving a genuinely-short demand with NO dominator
    // at all, worse than a merely-imprecise one. The real, local constraint that actually bound
    // each position was already correctly captured at the moment of live commit; re-deriving
    // "exhaustion" from an aggregate that was never reachable just second-guesses a correct
    // signal with a misleading yardstick.
    val reconciledByDemand = mutableMapOf<String, Double>()
    val reconciledTrees = timingFix.peggingTrees.map { entry ->
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        val rootReq = (tree["quantity"] as? Number)?.toDouble() ?: 0.0
        val (reconciled, after) = reconcile(tree, rootReq, data, config)
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

    emitPhase("consolidating", "Consolidating work orders…", 97)
    // ── Pass 2 — WO consolidation + timing, bottom-up wave propagation ─────────────────────────
    // consolidateByWaves walks the global WO dependency graph one layer at a time, bucketing and
    // recomputing duration per wave BEFORE propagating timing to parents — so a parent is never
    // bucketed/timed until every live dependency has been finalized (replaces the old
    // consolidate-once-then-patch design, which could leave a pushed WO merged with stale
    // bucket-mates). OFF path (consolidate_wos=false): each node-level WO acts as its own
    // consolidated group (1:1, no cross-demand merging) — stamped with consolidated_group_id =
    // wo_group_id so the downstream capacity-patch step below applies uniformly either way.
    @Suppress("UNCHECKED_CAST")
    val consolidationCfg = config?.get("consolidation") as? Map<String, Any?>
    val globalScale = consolidationCfg?.get("wo_batch_scale")?.toString()
    val makeBatchScale     = (consolidationCfg?.get("make_batch_scale")?.toString() ?: globalScale) ?: "weekly"
    val moveBatchScale     = (consolidationCfg?.get("move_batch_scale")?.toString() ?: globalScale) ?: "weekly"
    val purchaseBatchScale = (consolidationCfg?.get("purchase_batch_scale")?.toString() ?: globalScale) ?: "weekly"
    val consolidateWos = consolidationConfig.enabled
        && consolidationCfg?.get("consolidate_wos") != false
        && listOf(makeBatchScale, moveBatchScale, purchaseBatchScale).any { it != "none" }
    val waveResult = if (consolidateWos) {
        val legacyWindowDays = (consolidationCfg?.get("wo_window_days") as? Number)?.toInt()
            ?: consolidationConfig.periodDays
        val woBatchConfig = WoBatchConfig(
            make     = makeBatchScale,
            move     = moveBatchScale,
            purchase = purchaseBatchScale,
            legacyWindowDays = legacyWindowDays,
        )
        val r = consolidateByWaves(reconciledTrees, data, legacyWindowDays, woBatchConfig, config)
        val merged = crossWaveCalendarMerge(r.consolidation, legacyWindowDays, data, woBatchConfig)
        log.info("Pass 2 WO batch (make={} move={} purchase={}): {} native → {} consolidated → {} after cross-wave merge",
            makeBatchScale, moveBatchScale, purchaseBatchScale,
            r.consolidation.native.size, r.consolidation.consolidated.size, merged.consolidated.size)
        WaveConsolidationResult(r.peggingTrees, merged)
    } else {
        val nodeLevelWos = flattenPeggingToWorkOrders(reconciledTrees, data)
            .map { it + ("consolidated_group_id" to it["wo_group_id"]) }
        log.info("Pass 2 WO-consolidation OFF: {} node-level work orders (1:1 with per-demand pegging)", nodeLevelWos.size)
        WaveConsolidationResult(reconciledTrees, WoConsolidation(nodeLevelWos, nodeLevelWos))
    }

    emitPhase("scheduling", "Scheduling resources…", 98)
    // Phase-1 cross-WO arbitration, now over the CONSOLIDATED (post-merge) lots so capacity is
    // checked against the real production-lot count instead of an inflated per-demand count that
    // gets collapsed afterward. The scheduling pass is always on — the config toggle has been
    // removed. The `run {}` block is kept to avoid reindenting the body.
    var resourceContentionPushed = 0
    // consolidateByWaves builds its rows via buildMap{}, which is sealed read-only after
    // construction (it still satisfies `is MutableMap` structurally, so an `as?` cast would
    // wrongly succeed and skip the copy) — always force a genuine mutable copy here.
    val mutableLots: List<MutableMap<String, Any?>> = waveResult.consolidation.consolidated.map {
        it.toMutableMap()
    }
    var adjustedTrees = waveResult.peggingTrees
    run {
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

        // Static: cgid mapping and relabeled trees don't change between passes.
        // Relabel WO nodes to their consolidated_group_id so resequenceFromPegging's
        // DAG walk operates at the same (merged-batch) granularity as mutableLots.
        // Singleton consolidated rows keep wo_group_id = original native gid; merged
        // rows use cgid — relabeling both to cgid makes them uniformly visible.
        val gidToCgid = waveResult.consolidation.native.mapNotNull { n ->
            val g = n["wo_group_id"] as? String
            val c = n["consolidated_group_id"] as? String
            if (g != null && c != null) g to c else null
        }.toMap()
        val relabeledTrees = relabelTreesToConsolidatedGids(waveResult.peggingTrees, gidToCgid)

        // Cascade a resource-push into the pegging DAG: shifts parent WOs later when
        // a resource-pushed child now ends later than the parent's original start.
        // Rebuilds a scratch cgidLots copy (keyed uniformly by cgid) each time so the
        // DAG walk is clean; writes corrected timings back onto mutableLots in place.
        fun cascadePush() {
            val cgidLots: List<MutableMap<String, Any?>> = mutableLots.map { lot ->
                val cgid = lot["consolidated_group_id"] as? String ?: return@map lot.toMutableMap()
                lot.toMutableMap().also { it["wo_group_id"] = cgid }
            }
            resequenceFromPegging(cgidLots, relabeledTrees)
            val startByCgid = cgidLots.mapNotNull { l ->
                val cgid = l["consolidated_group_id"] as? String ?: return@mapNotNull null
                val start = parseDate(l["start_time"] as? String) ?: return@mapNotNull null
                cgid to start
            }.toMap()
            val endByCgid = cgidLots.mapNotNull { l ->
                val cgid = l["consolidated_group_id"] as? String ?: return@mapNotNull null
                val end = parseDate(l["end_time"] as? String) ?: return@mapNotNull null
                cgid to end
            }.toMap()
            for (lot in mutableLots) {
                val cgid = lot["consolidated_group_id"] as? String ?: continue
                startByCgid[cgid]?.let { lot["start_time"] = formatDate(it) }
                endByCgid[cgid]?.let { lot["end_time"] = formatDate(it) }
            }
        }

        // Two-pass arbitration: the cascade from Pass 1 can land parent WOs on dates
        // that conflict with other already-placed WOs (e.g. a child is pushed to Jul28
        // → parent cascades to Jul29, but Jul29 is already fully booked). Pass 2 runs
        // ResourceScheduler again on those post-cascade positions to resolve the new
        // conflicts. A second cascade propagates any Pass-2 pushes upward.
        resourceContentionPushed = ResourceScheduler.arbitrate(mutableLots, data, priorityMap, dueMap)
        // Always cascade: ResourceScheduler extends end_time for multi-wave consolidated WOs even
        // when no start is shifted (lots.size==1 branch fires unconditionally). Parent WOs must
        // be re-sequenced to start after the extended child end_time regardless of push count.
        cascadePush()
        val pushed2 = ResourceScheduler.arbitrate(mutableLots, data, priorityMap, dueMap)
        if (pushed2 > 0) {
            resourceContentionPushed += pushed2
            cascadePush()
        }
        // Always sync pegging trees from final mutableLots. ResourceScheduler extends consolidated
        // WO end_time to waveCount × perWaveDays even when no WO is pushed (the lots.size==1 branch
        // in ResourceScheduler fires unconditionally), so the pegging tree must always be updated.
        val finalStartByCgid = mutableLots.mapNotNull { l ->
            val cgid = l["consolidated_group_id"] as? String ?: return@mapNotNull null
            val start = parseDate(l["start_time"] as? String) ?: return@mapNotNull null
            cgid to start
        }.toMap()
        val finalEndByCgid = mutableLots.mapNotNull { l ->
            val cgid = l["consolidated_group_id"] as? String ?: return@mapNotNull null
            val end = parseDate(l["end_time"] as? String) ?: return@mapNotNull null
            cgid to end
        }.toMap()
        val startByOriginalGid = gidToCgid.mapNotNull { (g, c) -> finalStartByCgid[c]?.let { g to it } }.toMap()
        val endByOriginalGid = gidToCgid.mapNotNull { (g, c) -> finalEndByCgid[c]?.let { g to it } }.toMap()
        adjustedTrees = rewritePeggingTimings(
            waveResult.peggingTrees, startByOriginalGid, endByOriginalGid,
            data = data, config = config,
        )
    }
    val adjustedConsolidated: List<Map<String, Any?>> = mutableLots

    // Patch native WO timings to match the FINAL (post-arbitration) consolidated WO for each
    // group. Preserve original per-demand start and lead before overwriting with consolidated
    // timing so the frontend can show per-demand vs. batch-timing deltas.
    val adjustedTimingByCgid = adjustedConsolidated.associate { c ->
        (c["consolidated_group_id"] as? String ?: "") to
        Pair(c["start_time"] as? String, c["end_time"] as? String)
    }
    val adjustedNative = waveResult.consolidation.native.map { n ->
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

    // Sync committed_demands' commit_time (and carry along time_dominator) from the FINAL,
    // wave-consolidated pegging root (adjustedTrees) — same "last tree per demand wins"
    // convention R0 uses for quantity above. committed_demands.commit_time was previously only
    // ever set during the initial top-down commit (Phase 2), before wave consolidation (Pass 2)
    // could push it out further — so a demand delayed by WO batching/cross-demand consolidation
    // showed its PRE-consolidation date here, silently understating (or entirely hiding) real
    // lateness in any UI that reads committed_demands directly instead of walking the pegging
    // tree. time_dominator is attached here too (not just left on the tree) so the UI's lateness
    // column can show the same "delayed by a different demand" flag without needing the full
    // pegging tree loaded — committed_demands is always present; planning_pegging is not (large
    // cases stream it from the DB per-demand, lazily, as the user opens each one).
    run {
        val finalCommitTimeByDemand = mutableMapOf<String, String?>()
        val finalTimeDominatorByDemand = mutableMapOf<String, Any?>()
        for (entry in adjustedTrees) {
            val did = entry["demand_id"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
            @Suppress("UNCHECKED_CAST")
            val tree = entry["tree"] as? Map<String, Any?> ?: continue
            finalCommitTimeByDemand[did] = tree["commit_time"] as? String
            finalTimeDominatorByDemand[did] = tree["time_dominator"]
        }
        if (finalCommitTimeByDemand.isNotEmpty()) {
            val synced = committedDemands.map { row ->
                val did = row["demand_id"]?.toString() ?: return@map row
                if (isHardPlanningFailure(row["commit_reason"] as? String)) return@map row
                val finalCommitTime = finalCommitTimeByDemand[did] ?: return@map row
                val timeDominator = finalTimeDominatorByDemand[did]
                (if (finalCommitTime == row["commit_time"]) row else row + ("commit_time" to finalCommitTime)) +
                    (if (timeDominator != null) mapOf("time_dominator" to timeDominator) else emptyMap())
            }
            committedDemands.clear(); committedDemands.addAll(synced)
        }
    }

    // R7g: WO conservation — post-trim consolidated WO qty vs served-demand consumption.
    // Uses adjustedTrees (final pegging) and adjustedConsolidated (final WO list).
    val woConservationViolations = if (producedByComponent.isNotEmpty())
        verifyWoConservation(producedByComponent, adjustedTrees, adjustedConsolidated, servedDemandIds = servedDemandIds)
    else emptyList<String>()
    log.info("[plan] verifyWoConservation done: violations={}", woConservationViolations.size)
    emitPhase("finalizing", "Finalizing plan…", 100)

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
        // R7f component conservation: produced vs consumed per merged-leaf component.
        // Non-empty when unserved demands left their Phase 1 allocation unused. Surfaced
        // here so callers can detect budget leakage without running the full soundness checker.
        "component_conservation_violations" to componentConservationViolations,
        "wo_conservation_violations" to woConservationViolations,
        "budget_released_by_component" to releasedByComponent,
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
     * When provided, only count supply allocations from these demand IDs — i.e.
     * "pegged" means pegged to a real SERVED demand, not to a dead/collapsed branch.
     * Without this, `pegged` includes leaves that survive in the pegging tree with a
     * genuine, non-rolled-back quantity (e.g. one AND-sibling's own branch drew real
     * inventory successfully) even though the AND-parent's overall committed_qty
     * collapsed to 0 because a DIFFERENT sibling failed — the formula then balances
     * using two different notions of "consumed" (physical draw vs. reported-served),
     * silently hiding supply that's neither leftover nor actually helping any demand.
     * Implements the formula:
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
 * R10: inventory-priority check. For each (product_id, location_id) where the plan
 * created new work orders, verify that all timely physical supply inventory at that
 * component was consumed before resorting to new production.
 *
 * "Timely" means the supply lot's [supply_date] is on or before the earliest WO
 * [start_time] at the same component — i.e., the inventory was available when the
 * WO would have started and should have been drawn instead.
 *
 * Violations indicate the planner under-consumed existing stock and created
 * unnecessary WOs, which over-states production load and inflates WO count.
 *
 * Skipped when [inventoryLeftover] or [workOrders] is empty (R7e snapshots not
 * available for historical runs, or a plan with no work orders).
 *
 * @param inventoryLeftover   Compact inventory snapshot after all planning passes
 *        (supply_id + qty only, as persisted to [PlanRuns.inventoryLeftover]).
 * @param workOrders          Consolidated work orders from the plan output.
 * @param supplies            Full supply rows from case data ([data["supply"]]).
 *        Required to look up (product_id, location_id, supply_date) from supply_id.
 */
internal fun verifyInventoryPriority(
    inventoryLeftover: List<Map<String, Any?>>,
    workOrders: List<Map<String, Any?>>,
    supplies: List<Map<String, Any?>>,
    tolerance: Double = 1e-6,
): List<String> {
    if (inventoryLeftover.isEmpty() || workOrders.isEmpty()) return emptyList()

    // supply_id → (product_id, location_id, supply_date)
    data class SupplyMeta(val pid: String, val lid: String, val supplyDate: java.time.LocalDate?)
    val supplyMeta = mutableMapOf<String, SupplyMeta>()
    for (s in supplies) {
        val sid = s["supply_id"]?.toString() ?: continue
        val pid = (s["product_id"] as? String)?.trim() ?: continue
        val lid = (s["location_id"] as? String)?.trim() ?: continue
        val supplyDate = (s["supply_date"] as? String)?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        }
        supplyMeta[sid] = SupplyMeta(pid, lid, supplyDate)
    }

    // Leftover qty by supply_id, skipping consolidated synthetic buckets.
    // Demand-tagged buckets ARE included — if a demand's tagged supply sits
    // unused while that demand also has WOs, that is a genuine priority violation:
    // the planner should have consumed the reserved inventory before creating WOs.
    val leftoverBySupply = mutableMapOf<String, Double>()
    for (inv in inventoryLeftover) {
        val sid = inv["supply_id"]?.toString() ?: continue
        if (sid.startsWith("consolidated_")) continue
        val qty = (inv["qty"] as? Number)?.toDouble() ?: 0.0
        if (qty > tolerance) leftoverBySupply.merge(sid, qty, Double::plus)
    }
    if (leftoverBySupply.isEmpty()) return emptyList()

    // Earliest WO start_time per component (pid|lid). Skip zero-qty, failed, and move WOs.
    // Move WOs deliver supply to location_id (they create, not consume, inventory there), so they
    // are irrelevant to the inventory-priority check: leftover stock at the destination does not
    // imply the move was unnecessary — it may have been needed for a different downstream demand.
    val woEarliestStart = mutableMapOf<String, java.time.LocalDate>()
    for (wo in workOrders) {
        val pid = (wo["product_id"] as? String)?.trim() ?: continue
        val lid = (wo["location_id"] as? String)?.trim() ?: continue
        if (wo["method"] == "move") continue
        if ((wo["quantity"] as? Number)?.toDouble() ?: 0.0 <= 0.0) continue
        if (wo["failed"] == true) continue
        val start = (wo["start_time"] as? String)?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: continue
        val key = "$pid|$lid"
        val cur = woEarliestStart[key]
        if (cur == null || start.isBefore(cur)) woEarliestStart[key] = start
    }
    if (woEarliestStart.isEmpty()) return emptyList()

    // Accumulate timely leftover by component: leftover lots whose supply_date ≤ earliest WO start.
    val timelyLeftoverByComponent = mutableMapOf<String, Double>()
    for ((sid, leftover) in leftoverBySupply) {
        val meta = supplyMeta[sid] ?: continue
        val compKey = "${meta.pid}|${meta.lid}"
        val woStart = woEarliestStart[compKey] ?: continue   // no WO at this component → skip
        // Supply is "timely" if it was available on or before the WO's start date.
        // A null supply_date is treated as earliest possible (e.g. on-hand stock) → always timely.
        if (meta.supplyDate != null && meta.supplyDate.isAfter(woStart)) continue
        timelyLeftoverByComponent.merge(compKey, leftover, Double::plus)
    }

    val violations = mutableListOf<String>()
    for ((compKey, leftover) in timelyLeftoverByComponent.entries.sortedBy { it.key }) {
        val (pid, lid) = compKey.split("|", limit = 2)
        val woStart = woEarliestStart[compKey]
        violations.add(
            "R10: $pid@$lid — %.4f units of inventory available before earliest WO ($woStart) were not consumed. Existing stock should be exhausted before new WOs are issued.".format(leftover)
        )
        log.warn("inventory-priority violation — R10: {}@{} leftover={} woStart={}", pid, lid, leftover, woStart)
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
        "consolidation_split_details",
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
    // Cross-tree gid consistency: when the same physical WO (same planningGid) appears in
    // multiple pegging trees as an OR-group member, each tree would otherwise mint a fresh
    // orGroupId, leaving all but the first tree with an orphaned gid (no lots in mutableLots).
    // resequenceFromPegging's pushUp can't propagate through orphaned gids, so parent WOs in
    // those trees (e.g. VIRTUAL consolidation WOs) are never pushed when ResourceScheduler
    // shifts the underlying real WOs.  Fix: record the finalGid assigned the first time each
    // planningGid is processed and reuse it in all subsequent trees.
    val planningGidToFinalGid = mutableMapOf<String, String>()
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
            // For OR-groups: reuse the finalGid from the first tree that processed any of these
            // children.  Without this, each tree mints a fresh orGroupId; only the first gets
            // lots, and subsequent trees' nodes are orphaned — pushUp in resequenceFromPegging
            // can't propagate through them.
            val existingOrGid = if (isOrGroup) {
                nonFailedWoChildren.mapNotNull { (_, ch) ->
                    (ch["wo_group_id"] as? String)?.let { planningGidToFinalGid[it] }
                }.firstOrNull()
            } else null
            val orGroupId = if (isOrGroup) existingOrGid ?: nextWoGroupId() else null
            val altIndexByChild = if (isOrGroup) {
                nonFailedWoChildren.withIndex()
                    .associate { (altIndex, idxAndCh) -> idxAndCh.index to altIndex }
            } else emptyMap()

            val newChildren = children.mapIndexed { i, ch ->
                if (ch["type"] == "work_order" && ch["failed"] != true) {
                    val planningGid = ch["wo_group_id"] as? String
                    val finalGid = orGroupId ?: planningGidToFinalGid[planningGid] ?: planningGid
                    val altIndex = altIndexByChild[i]
                    // Record the mapping so subsequent trees reuse the same finalGid.
                    if (planningGid != null && finalGid != null) planningGidToFinalGid[planningGid] = finalGid
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
    val finalTrees = peggingTrees.map { entry ->
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: return@map entry
        entry.toMutableMap().apply { put("tree", rewriteTreeFromWorkOrders(tree, lotsByGroup)) }
    }

    return TimingFixResult(mutableLots, finalTrees)
}

/**
 * Rewrite all timing in a single pegging tree node (and its descendants) using
 * the scheduled lots in [wosByGid] as the single authoritative source.
 *
 * Processing is bottom-up: children are rewritten first, then each node's
 * timestamps are derived from the already-corrected children.
 *
 * - **WO nodes**: `start_time`/`end_time` stamped from the matching lots in
 *   [wosByGid] (filtered by `method_slot_index`). Nodes with no matching lots
 *   (failed stubs, orphaned gids) are left unchanged.
 * - **Supply / purchase nodes**: `commit_time` set to the max `end_time` of
 *   any child WO, or the max `commit_time` of any nested demand/supply child.
 *   Physical supply with no sub-tree is left unchanged.
 * - **Demand nodes**: `commit_time` set to the max of child WO `end_time` /
 *   child demand `commit_time`, skipping hard-failure children.
 *
 * This makes each demand's pegging tree self-contained: every node's timing
 * reflects the post-scheduling reality, so R5 (predecessor_sequencing) within
 * the tree is sufficient for timing soundness — no cross-tree checks needed.
 */
@Suppress("UNCHECKED_CAST")
internal fun rewriteTreeFromWorkOrders(
    node: Map<String, Any?>,
    wosByGid: Map<String, List<Map<String, Any?>>>,
    depth: Int = 0,
): Map<String, Any?> {
    if (depth > 60) return node
    if (node["type"] == "work_order" && node["failed"] == true) return node
    val originalChildren = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
    val newChildren = originalChildren.map { rewriteTreeFromWorkOrders(it, wosByGid, depth + 1) }
    val updated = node.toMutableMap()
    if (originalChildren.isNotEmpty()) updated["children"] = newChildren
    when (node["type"] as? String) {
        "work_order" -> {
            val gid = node["wo_group_id"] as? String ?: return updated
            val altIndex = node["method_slot_index"] as? Int
            val wos = (wosByGid[gid] ?: emptyList()).filter { (it["method_slot_index"] as? Int) == altIndex }
            if (wos.isNotEmpty()) {
                val newStart = wos.mapNotNull { parseDate(it["start_time"] as? String) }.minOrNull()
                var newEnd = wos.mapNotNull { parseDate(it["end_time"] as? String) }.maxOrNull()
                // Floor: this node's OWN pre-rewrite span (as buildWorkOrders originally, correctly
                // computed from the method's own transit_time/lead_time) is the physically-required
                // minimum duration. The group-wide min(start)/max(end) above is meant to WIDEN a
                // WO's window to cover resource-scheduling shifts across every lot sharing its
                // wo_group_id — not narrow it. If `wosByGid[gid]` ever contains a peer lot with a
                // tighter window (e.g. a different product/direction sharing a consolidated group
                // id, or a stale/short entry from an earlier pass), naively taking the raw min/max
                // can compress this WO below what it physically needs (R5_transit_time /
                // R5_lead_time). Re-extend `newEnd` to preserve the original duration if so —
                // idempotent across repeated resequencing passes since each rewrite only ever
                // widens relative to its own input, never narrows.
                if (newStart != null && newEnd != null) {
                    val originalStart = parseDate(node["start_time"] as? String)
                    val originalEnd = parseDate(node["end_time"] as? String)
                    val minRequiredDays = if (originalStart != null && originalEnd != null)
                        (originalEnd.toEpochDay() - originalStart.toEpochDay()).coerceAtLeast(0L) else 0L
                    val actualDays = newEnd.toEpochDay() - newStart.toEpochDay()
                    if (actualDays < minRequiredDays) {
                        newEnd = newStart.plusDays(minRequiredDays)
                    }
                }
                newStart?.let { updated["start_time"] = formatDate(it) }
                newEnd?.let { updated["end_time"] = formatDate(it) }
            }
        }
        "demand" -> {
            val newCommit = newChildren.mapNotNull { ch ->
                val r = ch["commit_reason"] as? String
                if (r == "cycle_stopped" || r == "cycle_detected" || isHardPlanningFailure(r)) return@mapNotNull null
                when (ch["type"] as? String) {
                    "work_order" -> parseDate(ch["end_time"] as? String)
                    "demand", "supply", "purchase" -> parseDate(ch["commit_time"] as? String)
                    else -> null
                }
            }.maxOrNull()
            val current = parseDate(node["commit_time"] as? String)
            if (newCommit != null && (current == null || newCommit > current)) updated["commit_time"] = formatDate(newCommit)
        }
        "supply", "purchase" -> {
            val newCommit = newChildren.mapNotNull { ch ->
                when (ch["type"] as? String) {
                    "work_order" -> parseDate(ch["end_time"] as? String)
                    "demand", "supply", "purchase" -> parseDate(ch["commit_time"] as? String)
                    else -> null
                }
            }.maxOrNull()
            val current = parseDate(node["commit_time"] as? String)
            if (newCommit != null && (current == null || newCommit > current)) updated["commit_time"] = formatDate(newCommit)
        }
    }
    return updated
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
                // Recurse into children: when a supply lot is *produced* by a WO (sub-tree exists),
                // we must add that producing WO into the DAG as a child of currentParent so pushUp
                // propagates the producing WO's end_time up to the parent WO's start constraint.
                // Without this, ResourceScheduler shifts (e.g. 500-4212/1000 pushed to Oct) are
                // invisible to the parent (F30__888 stays at Aug because its leafConstraint is
                // derived from the supply node's stale pre-arbitration commit_time).
                for (c in nodeChildren) walk(c, currentParent, depth + 1)
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
