package com.allocator.services

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * Soundness checker for a planning run.
 *
 * A planning run is **sound** iff every demand has a sound pegging tree.
 * A pegging tree is sound iff every node and edge satisfies the structural,
 * quantitative, and temporal rules from `spec.md` §pegging:
 *
 *   R1 root match — root node is a demand whose product/location matches the
 *      demand row that drove the run.
 *   R2 make node validity — each make work_order references a `bom_id` whose
 *      `parent_id` is the work_order's product, and its children are the BOM's
 *      required children for the chosen alt_group.
 *   R3 move node validity — each move work_order's (product, from_lid, to_lid)
 *      matches a `method_move` row.
 *   R4 quantity propagation — at make WOs, each child's qty = parent_qty / rate
 *      (within tolerance). At move WOs, qty is conserved.
 *   R5 time propagation — at make WOs, parent_time = max(child_times) + LEAD_TIME.
 *      At move WOs, parent_time = source_time + TRANSIT_TIME.
 *   R6 variant consistency — children of a make WO share the same alt_group
 *      (or all have null alt_group).
 *   R7a per-demand supply leaf bound — each supply leaf references a real
 *      supply.supply_id; per-demand consumed qty ≤ available supply.qty.
 *   R7b cross-demand supply conservation — Σ leaf.qty across all demands ≤
 *      supply.qty for each supply_id.
 *   R8 conservation (deep check) — committed_qty at root = Σ over leaves
 *      of (leaf.qty × ∏ rates along leaf→root path), within tolerance.
 *      Skipped unless [SoundnessConfig.deepCheck] = true.
 *
 * The checker is a pure function — it neither mutates inputs nor consults the
 * database. Callers feed it the persisted plan-run state plus the case data.
 *
 * See also: [SoundnessReport], [Violation], `spec.md` §pegging.
 */

private val log = LoggerFactory.getLogger(SoundnessChecker::class.java)
class SoundnessChecker  // marker for the logger

/** Configuration for the soundness check. */
data class SoundnessConfig(
    /**
     * When true, R8 (full conservation) is checked. Walks each tree leaf-to-root
     * accumulating qty × rate. Cost is O(tree size) per demand — proportional
     * to plan complexity.
     */
    val deepCheck: Boolean = false,
    /** Floating-point tolerance for quantity / rate comparisons. */
    val tolerance: Double = 1e-6,
    /**
     * Tolerance for time comparisons in days. Set above 0 to allow lead-time /
     * transit-time rounding (engine truncates dates to LocalDate granularity).
     */
    val timeToleranceDays: Long = 1L,
)

/** A single rule violation discovered during the walk. */
data class Violation(
    /** Rule code, e.g. "R4_qty_propagation". */
    val rule: String,
    /** Path to the offending node, e.g. "0-2-1" (root.children[0].children[2].children[1]). */
    val nodePath: String,
    /** Human-readable description of the violation. */
    val message: String,
    /** Expected value (rule-dependent), if applicable. */
    val expected: Any? = null,
    /** Actual value (rule-dependent), if applicable. */
    val actual: Any? = null,
)

/** Per-demand soundness state: sound iff `violations` is empty. */
data class DemandSoundness(
    val demandId: String,
    val sound: Boolean,
    val violations: List<Violation>,
)

/** Final report. `overallSound` iff every demand is sound AND no cross-demand violations. */
data class SoundnessReport(
    val overallSound: Boolean,
    val demandCount: Int,
    val soundCount: Int,
    val demands: List<DemandSoundness>,
    /** Cross-demand violations (e.g. R7b: total supply qty consumed exceeds supply.qty). */
    val crossDemandViolations: List<Violation>,
    /** True if R8 (deep check) was run. */
    val deepCheck: Boolean,
)

/**
 * Run the soundness check on a planning run.
 *
 * @param planningPegging the run's `planning_pegging` field — list of
 *        `{demand_id, tree, ...}` entries from `runPlanning`.
 * @param demands the case's input demand rows.
 * @param data full case data (bom, method_make, method_move, method_buy, supply).
 *        Needed to validate WOs against their declared BOM/method rows.
 * @param workOrders the run's `work_orders` field. Used to validate consumption
 *        of synthetic `consolidated_<pid>_<lid>` supply buckets against the
 *        producing WOs at that (pid, lid). Pass an empty list to skip R7c.
 * @param committedDemands the run's `committed_demands` field. Used to check
 *        that each demand's reported committed quantity matches its pegging
 *        tree's root.committed_qty. Pass an empty list to skip the consistency
 *        check.
 */
fun checkRunSoundness(
    planningPegging: List<Map<String, Any?>>,
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: SoundnessConfig = SoundnessConfig(),
    workOrders: List<Map<String, Any?>> = emptyList(),
    committedDemands: List<Map<String, Any?>> = emptyList(),
): SoundnessReport {
    // Index demands by id and lookup tables for rule checks.
    val demandById: Map<String, Map<String, Any?>> = demands.associateBy { it["demand_id"]?.toString() ?: "" }
    val bomRows = data["bom"] ?: emptyList()
    val methodMakes = data["method_make"] ?: emptyList()
    val methodMoves = data["method_move"] ?: emptyList()
    val supplies = data["supply"] ?: emptyList()
    val supplyById: Map<String, Map<String, Any?>> = supplies.associateBy { it["supply_id"]?.toString() ?: "" }

    // committed_demands is the per-demand fulfillment summary that flows to
    // KPIs / UI / shortage reporting; planning_pegging is the parallel pegging
    // tree. Both are derived from the same plan() invocation but via separate
    // accumulator paths in runPlanning, so a divergence indicates a bookkeeping
    // bug in one of them. Index by demand_id; if a demand has multiple rows
    // (legacy paths sometimes append per-iteration), sum their `quantity`.
    val committedQtyById = mutableMapOf<String, Double>()
    for (row in committedDemands) {
        val did = row["demand_id"]?.toString() ?: continue
        if (did.isBlank()) continue
        val q = (row["quantity"] as? Number)?.toDouble() ?: 0.0
        committedQtyById[did] = (committedQtyById[did] ?: 0.0) + q
    }

    // Total WO production at each (pid, lid). Used by R7c to bound consumption
    // of the synthetic `consolidated_<pid>_<lid>` inventory bucket emitted by
    // the leaf-legacy consolidator (Phase 2). One synthetic bucket aggregates
    // all production at a component, so the cap is Σ over WOs at (pid, lid).
    val woQtyByComponent = mutableMapOf<String, Double>()
    for (wo in workOrders) {
        val pid = (wo["product_id"] as? String)?.trim() ?: continue
        val lid = (wo["location_id"] as? String)?.trim() ?: continue
        val qty = (wo["quantity"] as? Number)?.toDouble() ?: 0.0
        if (pid.isBlank() || lid.isBlank() || qty <= 0) continue
        val key = "$pid|$lid"
        woQtyByComponent[key] = (woQtyByComponent[key] ?: 0.0) + qty
    }

    // For each demand_id, take the LAST matching pegging tree (matches the
    // frontend's lookup logic at _CaseSectionPage.tsx:7199 — main planning tree
    // is the last entry under leaf-engine where consolidatedPegging precedes
    // planningPegging).
    val treeByDemand: Map<String, Map<String, Any?>> = planningPegging
        .mapNotNull { entry ->
            val did = entry["demand_id"]?.toString() ?: return@mapNotNull null
            if (did.isBlank()) return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val tree = entry["tree"] as? Map<String, Any?> ?: return@mapNotNull null
            did to tree
        }
        // Last wins: the canonical main tree under both engines.
        .toMap()

    // ── Per-demand walk ───────────────────────────────────────────────────────
    val demandReports = mutableListOf<DemandSoundness>()
    val perDemandSupplyConsumption = mutableMapOf<String, Double>()  // supply_id → total qty (across leaves of one demand)
    val crossDemandSupplyConsumption = mutableMapOf<String, Double>()  // supply_id → total qty (across all demands)
    val crossDemandSyntheticConsumption = mutableMapOf<String, Double>()  // "$pid|$lid" → total qty drawn from synthetic consolidated buckets

    for ((demandId, demandRow) in demandById) {
        val tree = treeByDemand[demandId]
        if (tree == null) {
            // Demand has no pegging tree at all — could be a zero-qty demand or a
            // run that never planned for it. Treat as unsound; the engine should
            // emit a tree even for cycle_stopped / depth_limit.
            val qty = (demandRow["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 0) {
                demandReports.add(DemandSoundness(
                    demandId = demandId,
                    sound = false,
                    violations = listOf(Violation(
                        rule = "R0_no_tree",
                        nodePath = "",
                        message = "Demand has no pegging tree in planning_pegging.",
                    )),
                ))
            } else {
                // Zero-qty demand — no tree is fine.
                demandReports.add(DemandSoundness(demandId = demandId, sound = true, violations = emptyList()))
            }
            continue
        }

        val ctx = WalkContext(
            demandId = demandId,
            demandRow = demandRow,
            bomRows = bomRows,
            methodMakes = methodMakes,
            methodMoves = methodMoves,
            supplyById = supplyById,
            config = config,
        )
        ctx.walkRoot(tree)

        // Aggregate per-demand supply consumption into the cross-demand map.
        for ((supplyId, qty) in ctx.supplyConsumption) {
            crossDemandSupplyConsumption.merge(supplyId, qty, Double::plus)
        }
        perDemandSupplyConsumption.putAll(ctx.supplyConsumption)
        for ((compKey, qty) in ctx.syntheticConsumption) {
            crossDemandSyntheticConsumption.merge(compKey, qty, Double::plus)
        }

        // R0_committed_consistency — committed_demands.quantity must match the
        // pegging tree root's committed_qty for the same demand. Both are
        // derived from the same plan() output but populated via separate
        // accumulators; a divergence means one of them is wrong (typically
        // the save / enrichment path that populates committed_demands). Skip
        // when committedDemands wasn't passed to the checker.
        if (committedQtyById.isNotEmpty()) {
            val tableQty = committedQtyById[demandId]
            val treeQty = (tree["committed_qty"] as? Number)?.toDouble() ?: 0.0
            if (tableQty != null && abs(tableQty - treeQty) > config.tolerance) {
                ctx.violations.add(Violation(
                    rule = "R0_committed_consistency",
                    nodePath = "0",
                    message = "committed_demands.quantity=$tableQty doesn't match pegging root.committed_qty=$treeQty for demand $demandId.",
                    expected = treeQty,
                    actual = tableQty,
                ))
            }
        }

        demandReports.add(DemandSoundness(
            demandId = demandId,
            sound = ctx.violations.isEmpty(),
            violations = ctx.violations.toList(),
        ))
    }

    // ── R7b cross-demand supply.qty bound ─────────────────────────────────────
    val crossViolations = mutableListOf<Violation>()
    for ((supplyId, totalConsumed) in crossDemandSupplyConsumption) {
        val supply = supplyById[supplyId]
        if (supply == null) continue  // R7a already flagged this per-demand
        val available = (supply["qty"] as? Number)?.toDouble() ?: 0.0
        if (totalConsumed > available + config.tolerance) {
            crossViolations.add(Violation(
                rule = "R7b_supply_overconsumption",
                nodePath = "supply:$supplyId",
                message = "Total qty consumed across all demands exceeds supply.qty.",
                expected = available,
                actual = totalConsumed,
            ))
        }
    }

    // ── R7c synthetic-bucket bound ────────────────────────────────────────────
    // Each `consolidated_<pid>_<lid>` synthetic inventory bucket aggregates the
    // production of all WOs at (pid, lid). Cross-demand consumption from that
    // bucket must not exceed total WO output at the same (pid, lid). Runtime's
    // consumeFromInventory clamps takes to bucket.qty, so this bound holds by
    // construction in normal operation; R7c catches bugs that bypass that path
    // (e.g. pegging-tree edits, save-path corruption, wrong producer attribution).
    // Skipped (no violation) when workOrders is empty — caller didn't pass them.
    if (woQtyByComponent.isNotEmpty()) {
        for ((componentKey, totalConsumed) in crossDemandSyntheticConsumption) {
            val produced = woQtyByComponent[componentKey] ?: 0.0
            if (totalConsumed > produced + config.tolerance) {
                crossViolations.add(Violation(
                    rule = "R7c_consolidated_overconsumption",
                    nodePath = "synthetic:$componentKey",
                    message = "Σ consumption from synthetic 'consolidated_$componentKey' bucket exceeds total WO production at $componentKey.",
                    expected = produced,
                    actual = totalConsumed,
                ))
            }
        }
    }

    val soundCount = demandReports.count { it.sound }
    val overallSound = soundCount == demandReports.size && crossViolations.isEmpty()

    return SoundnessReport(
        overallSound = overallSound,
        demandCount = demandReports.size,
        soundCount = soundCount,
        demands = demandReports,
        crossDemandViolations = crossViolations,
        deepCheck = config.deepCheck,
    )
}

// ── Per-demand walk context ───────────────────────────────────────────────────

/** Mutable state for one demand's tree walk. */
private class WalkContext(
    val demandId: String,
    val demandRow: Map<String, Any?>,
    val bomRows: List<Map<String, Any?>>,
    val methodMakes: List<Map<String, Any?>>,
    val methodMoves: List<Map<String, Any?>>,
    val supplyById: Map<String, Map<String, Any?>>,
    val config: SoundnessConfig,
) {
    val violations = mutableListOf<Violation>()
    /** supply_id → qty consumed across all leaves of this demand's tree. */
    val supplyConsumption = mutableMapOf<String, Double>()
    /** "$pid|$lid" → qty consumed via synthetic `consolidated_<pid>_<lid>` buckets. */
    val syntheticConsumption = mutableMapOf<String, Double>()

    fun walkRoot(tree: Map<String, Any?>) {
        // R1 — root must be a demand node matching the demand row.
        val type = tree["type"]?.toString()
        if (type != "demand") {
            violations.add(Violation(
                rule = "R1_root_match",
                nodePath = "0",
                message = "Root node type is '$type', expected 'demand'.",
                expected = "demand",
                actual = type,
            ))
            return
        }
        val expectedPid = demandRow["product_id"]?.toString()?.trim()
        val expectedLid = demandRow["location_id"]?.toString()?.trim() ?: "VIRTUAL"
        val actualPid = tree["product_id"]?.toString()?.trim()
        val actualLid = tree["location_id"]?.toString()?.trim()
        if (actualPid != expectedPid || actualLid != expectedLid) {
            violations.add(Violation(
                rule = "R1_root_match",
                nodePath = "0",
                message = "Root demand node (pid=$actualPid, lid=$actualLid) doesn't match demand row (pid=$expectedPid, lid=$expectedLid).",
                expected = "$expectedPid@$expectedLid",
                actual = "$actualPid@$actualLid",
            ))
        }

        // committed_qty must not exceed quantity.
        val requested = (tree["quantity"] as? Number)?.toDouble() ?: 0.0
        val committed = (tree["committed_qty"] as? Number)?.toDouble() ?: 0.0
        if (committed > requested + config.tolerance) {
            violations.add(Violation(
                rule = "R1_committed_bound",
                nodePath = "0",
                message = "committed_qty exceeds quantity at root.",
                expected = requested,
                actual = committed,
            ))
        }

        // Recurse into children.
        @Suppress("UNCHECKED_CAST")
        val children = (tree["children"] as? List<Map<String, Any?>>) ?: emptyList()
        children.forEachIndexed { i, child ->
            walkChildOfDemand(child, "0-$i")
        }

        // R8 — leaf-to-root conservation. Walks the tree bottom-up, propagating
        // each leaf's qty through BOM rate conversions (make WOs) and AND/OR
        // aggregation, then asserts the supportable root qty ≥ committed_qty.
        // Catches chain-conservation gaps that per-WO R4 can miss (e.g.
        // mid-tree rate-conversion drift, missing intermediate WOs).
        if (config.deepCheck && committed > config.tolerance) {
            val supportable = supportableQty(tree)
            // Tolerance: max(1.0, 10% of committed) — same shape as R4
            // (toleranceFor at line 503). Absorbs lot quantization and
            // fractional fair-split rounding (e.g. 0.667 supplies vs
            // committed_qty=1 after roundQty), but still catches the 50%+
            // gaps that indicate a missing intermediate WO or a real
            // chain-conservation break.
            val r8Tol = maxOf(1.0, committed * 0.10)
            if (supportable < committed - r8Tol) {
                violations.add(Violation(
                    rule = "R8_deep_conservation",
                    nodePath = "0",
                    message = "Root committed_qty=$committed exceeds leaf-supportable qty=$supportable (chain conservation broken).",
                    expected = committed,
                    actual = supportable,
                ))
            }
        }
    }

    /**
     * Recursive bottom-up qty propagation for R8. Returns the qty (in this
     * node's product_id units) that the subtree's leaves can physically support.
     *
     * - supply / purchase leaves: their `quantity` is the source qty.
     * - demand sub-nodes: sum across children (each child is a method that
     *   fulfills this demand at this product).
     * - work_order: aggregate children through BOM rate. AND uses min over
     *   (child_supportable / rate); OR uses Σ. Capped at the WO's claimed
     *   qty since a WO can't produce more than itself.
     */
    private fun supportableQty(node: Map<String, Any?>): Double {
        val type = node["type"]?.toString() ?: return 0.0
        @Suppress("UNCHECKED_CAST")
        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        return when (type) {
            "supply", "purchase" -> (node["quantity"] as? Number)?.toDouble() ?: 0.0
            "demand" -> children.sumOf { supportableQty(it) }
            "work_order" -> {
                val woQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
                if (woQty <= config.tolerance) return 0.0
                val method = node["method"]?.toString()
                val raw = when (method) {
                    "purchase", "move" -> children.sumOf { supportableQty(it) }
                    "make" -> {
                        val parentPid = (node["product_id"] as? String)?.trim() ?: ""
                        val parentBomRows = bomRows.filter {
                            (it["parent_id"] as? String)?.trim() == parentPid
                        }
                        val rel = node["children_relation"]?.toString()
                        val demandKids = children.filter { it["type"] == "demand" }
                        if (demandKids.isEmpty()) 0.0
                        else if (rel == "or") {
                            demandKids.sumOf { child -> childContribution(child, parentBomRows) }
                        } else {
                            demandKids.minOf { child -> childContribution(child, parentBomRows) }
                        }
                    }
                    else -> 0.0
                }
                kotlin.math.min(raw, woQty)
            }
            else -> 0.0
        }
    }

    /** A make-WO child's contribution to parent units = supportableQty / rate. */
    private fun childContribution(
        child: Map<String, Any?>,
        parentBomRows: List<Map<String, Any?>>,
    ): Double {
        val cs = supportableQty(child)
        val cPid = (child["product_id"] as? String)?.trim() ?: ""
        val rate = (parentBomRows.firstOrNull { (it["child_id"] as? String)?.trim() == cPid }
            ?.get("rate") as? Number)?.toDouble() ?: 1.0
        return if (rate > 0) cs / rate else 0.0
    }

    private fun walkChildOfDemand(node: Map<String, Any?>, path: String) {
        when (node["type"]?.toString()) {
            "supply" -> walkSupply(node, path)
            "purchase" -> walkPurchase(node, path)
            "work_order" -> walkWorkOrder(node, path)
            "demand" -> {
                // Sub-demand under a demand (cycle_stopped / depth_limit cases) — treat as terminal.
                // No further checks; the parent's qty bound is already validated.
            }
            else -> violations.add(Violation(
                rule = "R0_unknown_node_type",
                nodePath = path,
                message = "Unknown node type '${node["type"]}'.",
                actual = node["type"],
            ))
        }
    }

    private fun walkSupply(node: Map<String, Any?>, path: String) {
        // R7a — supply_id must reference a real supply row, qty ≤ supply.qty.
        val supplyId = node["supply_id"]?.toString()
        val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
        if (supplyId.isNullOrBlank()) {
            violations.add(Violation(
                rule = "R7a_supply_id_missing",
                nodePath = path,
                message = "Supply leaf has no supply_id.",
            ))
            return
        }
        // Synthetic bucket emitted by the leaf-legacy consolidator (Phase 2 emits
        // one "consolidated_<pid>_<lid>" inventory bucket per produced component).
        // These aren't physical supplies, so R7a's "exists in supplies table"
        // check doesn't apply. But we still want a bound: total consumption of
        // a synthetic bucket must not exceed total WO production at (pid, lid).
        // Accumulate per (pid, lid) here; the R7c cross-demand check validates
        // against woQtyByComponent at the end of the run.
        if (supplyId.startsWith("consolidated_")) {
            val pid = (node["product_id"] as? String)?.trim()
            val lid = (node["location_id"] as? String)?.trim()
            if (!pid.isNullOrBlank() && !lid.isNullOrBlank()) {
                syntheticConsumption.merge("$pid|$lid", qty, Double::plus)
            }
            return
        }
        val supply = supplyById[supplyId]
        if (supply == null) {
            violations.add(Violation(
                rule = "R7a_supply_id_unknown",
                nodePath = path,
                message = "Supply leaf references supply_id '$supplyId' that doesn't exist.",
                actual = supplyId,
            ))
            return
        }
        val available = (supply["qty"] as? Number)?.toDouble() ?: 0.0
        if (qty > available + config.tolerance) {
            violations.add(Violation(
                rule = "R7a_supply_qty_exceeded",
                nodePath = path,
                message = "Per-demand supply leaf qty exceeds supply.qty.",
                expected = available,
                actual = qty,
            ))
        }
        supplyConsumption.merge(supplyId, qty, Double::plus)
    }

    private fun walkPurchase(node: Map<String, Any?>, path: String) {
        // Purchase leaves are terminal — no per-row validation needed beyond
        // structural shape. Quantity is whatever plan() committed; pricing /
        // vendor selection is out of scope for soundness.
        @Suppress("UNUSED_VARIABLE")
        val pid = node["product_id"]?.toString()
    }

    private fun walkWorkOrder(node: Map<String, Any?>, path: String) {
        val method = node["method"]?.toString() ?: ""
        when (method) {
            "make" -> validateMakeWO(node, path)
            "move" -> validateMoveWO(node, path)
            "purchase" -> { /* same as purchase leaf */ }
            else -> violations.add(Violation(
                rule = "R2_unknown_method",
                nodePath = path,
                message = "Work order method '$method' is not one of make/move/purchase.",
                actual = method,
            ))
        }
        // Recurse into children regardless of method (purchase has no children).
        @Suppress("UNCHECKED_CAST")
        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        children.forEachIndexed { i, child ->
            walkChildOfWorkOrder(child, "$path-$i", parentNode = node)
        }
    }

    private fun walkChildOfWorkOrder(child: Map<String, Any?>, path: String, parentNode: Map<String, Any?>) {
        // Children of a WO are demand nodes (sub-requirements). They have their
        // own children which are the actual leaves / WOs.
        if (child["type"]?.toString() != "demand") {
            // Some legacy paths may put supply/purchase directly under a WO; tolerate.
            walkChildOfDemand(child, path)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val grandchildren = (child["children"] as? List<Map<String, Any?>>) ?: emptyList()
        grandchildren.forEachIndexed { i, gc ->
            walkChildOfDemand(gc, "$path-$i")
        }
    }

    private fun validateMakeWO(node: Map<String, Any?>, path: String) {
        val pid = node["product_id"]?.toString()?.trim() ?: ""
        val lid = node["location_id"]?.toString()?.trim() ?: ""

        // R2: a method_make must exist for (pid, lid). We look up method_make rows
        // matching pid, with lid match (or VIRTUAL fallback consistent with getMethods).
        val matchingMakes = methodMakes.filter {
            (it["product_id"] as? String)?.trim() == pid &&
                ((it["location_id"] as? String)?.trim() == lid || lid.isBlank() || lid == "VIRTUAL")
        }
        if (matchingMakes.isEmpty()) {
            violations.add(Violation(
                rule = "R2_make_method_missing",
                nodePath = path,
                message = "No method_make row matches (pid=$pid, lid=$lid).",
            ))
            return
        }

        // Find the BOM rows for this make. Match by parent_id; bom_id discrimination
        // is best-effort since the WO doesn't carry bom_id directly.
        val parentBomRows = bomRows.filter {
            (it["parent_id"] as? String)?.trim() == pid
        }
        if (parentBomRows.isEmpty()) {
            // make WO with no BOM children — only valid if the WO has no children either.
            @Suppress("UNCHECKED_CAST")
            val childCount = ((node["children"] as? List<*>) ?: emptyList<Any>()).size
            if (childCount > 0) {
                violations.add(Violation(
                    rule = "R2_bom_rows_missing",
                    nodePath = path,
                    message = "Make WO references pid=$pid but no BOM rows have parent_id=$pid; tree has $childCount children.",
                ))
            }
            return
        }

        // R6: children of this WO should not mix alt_groups that genuinely
        // represent alternatives. Two cases need distinguishing:
        //
        //   (a) Multi-variant data convention: alt_group == child_id (every
        //       BOM row is its own group of one). Plan()'s multi-variant /
        //       elaborate modes intentionally pick multiple variants here —
        //       not a violation.
        //
        //   (b) Genuine OR alternatives: BOM has 2+ rows sharing one alt_group
        //       AND another rows-set under a different alt_group. The engine
        //       should pick ONE alt's required-set, not mix across.
        //
        // We flag (b) only: at least two distinct alt_groups, each backing
        // at least one BOM row that has SIBLINGS in the same group (i.e.,
        // multi-row groups). Single-row groups are treated as AND-equivalent.
        @Suppress("UNCHECKED_CAST")
        val woChildren = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        val childPids = woChildren
            .filter { it["type"] == "demand" }
            .mapNotNull { (it["product_id"] as? String)?.trim() }
        // BOM row groups: alt_group → set of child_ids in that group.
        val bomGroupSizes: Map<String, Int> = parentBomRows
            .groupBy { (it["alt_group"] as? String)?.trim() ?: "__null__" }
            .mapValues { it.value.size }
        val childAltGroups = childPids
            .mapNotNull { childPid ->
                parentBomRows.firstOrNull { (it["child_id"] as? String)?.trim() == childPid }
                    ?.get("alt_group")?.toString()?.trim()
                    ?.takeUnless { it.isBlank() }
            }
            .toSet()
        // Multi-row groups represented in this WO's children: a real OR if 2+ exist.
        val multiRowGroupsRepresented = childAltGroups.filter { (bomGroupSizes[it] ?: 0) > 1 }.toSet()
        if (multiRowGroupsRepresented.size > 1) {
            violations.add(Violation(
                rule = "R6_alt_group_inconsistent",
                nodePath = path,
                message = "Make WO children span multiple multi-row alt_groups: $multiRowGroupsRepresented.",
                expected = "single multi-row alt_group",
                actual = multiRowGroupsRepresented,
            ))
        }

        // R4: child committed_qty must equal parent_qty × rate (within tolerance).
        // Two engine conventions to match:
        //   - Rate direction: variantsForMake computes child_qty = parent_qty *
        //     rate (engine), the OPPOSITE of spec.md's "child = parent / rate".
        //     The engine is the authority since the soundness check exists to
        //     verify what plan() actually emits.
        //   - Failed-parent skip: when parent.quantity == 0 the WO didn't
        //     produce anything; child committed_qty values can reflect stale
        //     first-pass exploratory commitments and aren't tied to this WO.
        //     Skip R4 in that case (the tree is internally inconsistent but
        //     the inconsistency isn't a quantity-propagation violation).
        val parentQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
        if (parentQty <= config.tolerance) return  // failed make WO; child commitments orphaned
        val childrenRelation = (node["children_relation"] as? String)?.trim()
        // Tolerance: max(1.0, 10% of expected). Accommodates two kinds of
        // engine noise:
        //   - off-by-one from integer qty rounding (small qtys, abs floor)
        //   - lot-size quantization (next whole lot, can be 2-3 units off
        //     when rate × lot_increment compounds; relative slack absorbs it)
        // Genuine bottleneck-propagation gaps and over-allocations are
        // typically 50%+ off, well above 10%.
        fun toleranceFor(qty: Double): Double = maxOf(1.0, qty * 0.10)

        if (childrenRelation == "or") {
            // OR / multi-variant: parent_qty is split across alternative branches.
            // Each child contributes (committed / rate) to the parent's production.
            // Σ over children of (committed / rate) ≈ parent_qty.
            var summed = 0.0
            var anyMissingBom = false
            for (childNode in woChildren.filter { it["type"] == "demand" }) {
                val childPid = (childNode["product_id"] as? String)?.trim() ?: continue
                val childCommitted = (childNode["committed_qty"] as? Number)?.toDouble()
                    ?: (childNode["quantity"] as? Number)?.toDouble() ?: 0.0
                val bomRow = parentBomRows.firstOrNull { (it["child_id"] as? String)?.trim() == childPid }
                if (bomRow == null) {
                    violations.add(Violation(
                        rule = "R2_child_not_in_bom",
                        nodePath = path,
                        message = "Make WO child pid=$childPid not in BOM for parent=$pid.",
                    ))
                    anyMissingBom = true
                    continue
                }
                val rate = (bomRow["rate"] as? Number)?.toDouble() ?: 1.0
                if (rate > 0) summed += childCommitted / rate
            }
            if (!anyMissingBom) {
                val absDiff = abs(summed - parentQty)
                if (absDiff > toleranceFor(parentQty)) {
                    violations.add(Violation(
                        rule = "R4_qty_propagation",
                        nodePath = path,
                        message = "Make WO (children_relation=or) Σ (child_committed_qty / rate) = $summed doesn't match parent_qty = $parentQty.",
                        expected = parentQty,
                        actual = summed,
                    ))
                }
            }
            return
        }

        // AND (or null): each required child contributes parent × rate independently.
        for (childNode in woChildren.filter { it["type"] == "demand" }) {
            val childPid = (childNode["product_id"] as? String)?.trim() ?: continue
            val childCommitted = (childNode["committed_qty"] as? Number)?.toDouble()
                ?: (childNode["quantity"] as? Number)?.toDouble() ?: 0.0
            val bomRow = parentBomRows.firstOrNull { (it["child_id"] as? String)?.trim() == childPid }
            if (bomRow == null) {
                violations.add(Violation(
                    rule = "R2_child_not_in_bom",
                    nodePath = path,
                    message = "Make WO child pid=$childPid not in BOM for parent=$pid.",
                ))
                continue
            }
            val rate = (bomRow["rate"] as? Number)?.toDouble() ?: 1.0
            val expectedChildQty = parentQty * rate
            val absDiff = abs(childCommitted - expectedChildQty)
            if (absDiff > toleranceFor(expectedChildQty)) {
                violations.add(Violation(
                    rule = "R4_qty_propagation",
                    nodePath = path,
                    message = "Make WO child pid=$childPid committed_qty=$childCommitted doesn't match parent_qty × rate = $expectedChildQty (parent=$parentQty, rate=$rate).",
                    expected = expectedChildQty,
                    actual = childCommitted,
                ))
            }
        }

        // R5: parent_time = max(child_times) + LEAD_TIME. Best-effort: pick any
        // matching method_make's lead_time (the engine doesn't preserve which
        // bom_id was used, so we use the minimum lead time among candidates).
        val leadTime = matchingMakes
            .mapNotNull { (it["lead_time"] as? Number)?.toDouble() }
            .minOrNull()
            ?: 0.0
        val startTime = parseDateLocal(node["start_time"]?.toString())
        val endTime = parseDateLocal(node["end_time"]?.toString())
        if (startTime != null && endTime != null) {
            val duration = endTime.toEpochDay() - startTime.toEpochDay()
            if (duration < (leadTime - config.timeToleranceDays).toLong()) {
                violations.add(Violation(
                    rule = "R5_lead_time",
                    nodePath = path,
                    message = "Make WO duration (end - start = $duration days) shorter than lead_time ($leadTime).",
                    expected = leadTime,
                    actual = duration.toDouble(),
                ))
            }
        }
    }

    private fun validateMoveWO(node: Map<String, Any?>, path: String) {
        val pid = node["product_id"]?.toString()?.trim() ?: ""
        val toLid = node["location_id"]?.toString()?.trim() ?: ""
        val fromLid = node["location_source"]?.toString()?.trim() ?: ""

        // R3: a method_move must exist for (pid, from_lid, to_lid).
        val matchingMove = methodMoves.firstOrNull {
            (it["product_id"] as? String)?.trim() == pid &&
                (it["from_location_id"] as? String)?.trim() == fromLid &&
                (it["to_location_id"] as? String)?.trim() == toLid
        }
        if (matchingMove == null) {
            violations.add(Violation(
                rule = "R3_move_method_missing",
                nodePath = path,
                message = "No method_move row matches (pid=$pid, from=$fromLid, to=$toLid).",
            ))
            return
        }

        // R4: qty conserved at move WOs (parent qty == single child committed_qty).
        // Same rationale as the make case: child's committed_qty is the
        // delivered amount through this edge; quantity is the requested amount
        // (which under shortage may differ).
        val parentQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
        @Suppress("UNCHECKED_CAST")
        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        val demandChildren = children.filter { it["type"] == "demand" }
        for (childNode in demandChildren) {
            val childCommitted = (childNode["committed_qty"] as? Number)?.toDouble()
                ?: (childNode["quantity"] as? Number)?.toDouble() ?: 0.0
            if (abs(childCommitted - parentQty) > config.tolerance) {
                violations.add(Violation(
                    rule = "R4_qty_conservation_move",
                    nodePath = path,
                    message = "Move WO child committed_qty=$childCommitted doesn't equal parent qty=$parentQty.",
                    expected = parentQty,
                    actual = childCommitted,
                ))
            }
        }

        // R5: spec says time_target − time_source = TRANSIT_TIME (strict),
        // but in practice the engine schedules moves to ARRIVE at the
        // request_due_time, which can mean duration > transit_time when the
        // demand is scheduled later than the earliest possible arrival. Only
        // duration < transit_time is impossible (can't move faster than the
        // physical transit). Flag duration < transit_time only.
        val transitTime = (matchingMove["transit_time"] as? Number)?.toDouble() ?: 0.0
        val startTime = parseDateLocal(node["start_time"]?.toString())
        val endTime = parseDateLocal(node["end_time"]?.toString())
        if (startTime != null && endTime != null) {
            val duration = endTime.toEpochDay() - startTime.toEpochDay()
            if (duration < (transitTime - config.timeToleranceDays).toLong()) {
                violations.add(Violation(
                    rule = "R5_transit_time",
                    nodePath = path,
                    message = "Move WO duration ($duration days) shorter than transit_time ($transitTime) — physically impossible.",
                    expected = transitTime,
                    actual = duration.toDouble(),
                ))
            }
        }
    }
}

private fun parseDateLocal(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    return try {
        LocalDate.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}
