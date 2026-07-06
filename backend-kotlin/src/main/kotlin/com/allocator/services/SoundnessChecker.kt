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
 *      Three sub-rules emit independently:
 *        R5_lead_time              — make WO duration ≥ lead_time
 *        R5_transit_time           — move WO duration ≥ transit_time
 *        R5_predecessor_sequencing — every WO's start_time ≥ max(child demand
 *           commit_time).  OR-aware: when a demand has multiple WO children
 *           (waterfall split), each alternative is checked against the union
 *           of all alternatives' children — mirroring the planner's
 *           `fixTimingFromPegging` post-processing pass.
 *   R6 variant consistency — children of a make WO share the same alt_group
 *      (or all have null alt_group).
 *   R7a per-demand supply leaf bound — each supply leaf references a real
 *      supply.supply_id; per-demand consumed qty ≤ available supply.qty.
 *   R7b cross-demand supply conservation — Σ leaf.qty across all demands ≤
 *      supply.qty for each supply_id.
 *   R7d orphan-leaf check — for any work_order with `quantity ≤ ε`, the sum
 *      of supply/purchase leaf qty in its subtree must also be ≤ ε. Catches
 *      "stock claimed but no output" patterns the planner historically
 *      created in the AND-bottleneck blocked path before that path was
 *      taught to restore inventory.
 *   R8 conservation (deep check) — committed_qty at root = Σ over leaves
 *      of (leaf.qty × ∏ rates along leaf→root path), within tolerance.
 *      Enabled by default ([SoundnessConfig.deepCheck] = true).
 *   R10 inventory priority — for every (product, location) that has work orders,
 *   R11 wo_group_id orphan check — every wo_group_id referenced in any pegging
 *      tree WO node must map to at least one lot in work_orders. An orphaned gid
 *      means resequenceFromPegging's pushUp skipped the subtree — parent WOs in
 *      that tree are not pushed when ResourceScheduler shifts the underlying lots.
 *      Skipped when work_orders is not provided to [checkRunSoundness].
 *      all physical supply inventory available on or before the earliest WO
 *      start_time must have been consumed. Leftover timely inventory while WOs
 *      run indicates the planner created unnecessary production. Gates overallSound.
 *      Skipped when inventoryLeftover snapshots are not provided.
 *   R12 resource overload — no (resource_id, location_id) pair is loaded beyond
 *      its declared size on any day. Daily load = Σ over concurrent make WOs of
 *      min(lot_count, parallelCap) × resource_rate, where parallelCap comes from
 *      the operation's BOR (floor(size/rate)). Gates overallSound.
 *      Skipped when work_orders is not provided.
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
    val deepCheck: Boolean = true,
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
    /**
     * R7e conservation-of-mass violations: initial_qty ≠ leftover_qty + pegged_qty for a
     * physical supply bucket. Empty when the inventory snapshots were not provided to
     * [checkRunSoundness] (e.g. DB-loaded soundness checks on historical runs).
     */
    val conservationViolations: List<String> = emptyList(),
    /**
     * R7f component conservation violations: Phase 1 Step 2 produced qty at a merged-leaf
     * component exceeds what served demands actually consumed from the synthetic bucket.
     * Indicates unserved demands left their allocation unused (budget leakage). Empty when
     * [producedByComponent] was not passed to [checkRunSoundness].
     */
    val componentConservationViolations: List<String> = emptyList(),
    /**
     * R7g WO conservation violations: post-trim consolidated WO qty exceeds served-demand
     * consumption at a component. Indicates [reconcileOverProduction] did not fully eliminate
     * unserved-demand capacity from WOs (e.g. BOM-child WOs not cascade-trimmed). Empty when
     * [producedByComponent] or [workOrders] was not passed to [checkRunSoundness].
     */
    val woConservationViolations: List<String> = emptyList(),
    /**
     * R10 inventory-priority violations: timely physical supply inventory was left unconsumed
     * at a (product, location) while new work orders were created there. Empty when
     * [inventoryLeftover] was not provided (e.g. historical runs without persisted snapshots).
     */
    val inventoryPriorityViolations: List<String> = emptyList(),
    /**
     * R11 wo_group_id orphan violations: a wo_group_id referenced in a pegging-tree WO node
     * has no corresponding lot in work_orders. Orphaned gids break pushUp propagation in
     * resequenceFromPegging, causing parent WOs in those trees to not be rescheduled when
     * ResourceScheduler shifts the underlying real WOs. Empty when work_orders was not passed.
     */
    val woGidOrphanViolations: List<Violation> = emptyList(),
    /**
     * R12 resource-overload violations: one entry per (resource_id, location_id) pair whose
     * daily concurrent load exceeds its declared size on at least one day. Daily load uses
     * min(lot_count, parallelCap) × rate, matching ResourceScheduler's arbitration model.
     * Empty when work_orders was not passed (cannot compute load without WO data).
     */
    val resourceOverloadViolations: List<Violation> = emptyList(),
)

/**
 * Run the soundness check on a planning run.
 *
 * @param planningPegging the run's `planning_pegging` field — list of
 *        `{demand_id, tree, ...}` entries from `runPlanning`.
 * @param demands the case's input demand rows.
 * @param data full case data (bom, method_make, method_move, method_buy, supply).
 *        Needed to validate WOs against their declared BOM/method rows.
 * @param workOrders the run's `work_orders` field. Currently informational
 *        (not consulted by any active rule). R7c was previously bounded by
 *        WO production at (pid|lid); now bounded by consolidator allocation,
 *        which is read from the consolidator-emitted pegging entries instead.
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
    workOrdersNative: List<Map<String, Any?>> = emptyList(),
    committedDemands: List<Map<String, Any?>> = emptyList(),
    /**
     * R7e: inventory snapshot taken BEFORE planning.
     * Pass [RunPlanningResult.inventoryEffectiveInitial]. Leave empty to skip the
     * conservation check (e.g. when verifying a DB-loaded historical run).
     */
    inventoryEffectiveInitial: List<Map<String, Any?>> = emptyList(),
    /**
     * R7e: inventory state AFTER all planning passes (physical supply leftover).
     * Pass [RunPlanningResult.inventoryLeftover]. Leave empty to skip the check.
     */
    inventoryLeftover: List<Map<String, Any?>> = emptyList(),
    /**
     * R7e: supply allocations from the plan output (`supply_allocations` field).
     * Required when [inventoryEffectiveInitial] and [inventoryLeftover] are provided.
     */
    supplyAllocations: List<Map<String, Any?>> = emptyList(),
    /**
     * R7f: Phase 1 Step 2 component production totals (pid|lid → qty).
     * Pass [RunPlanningResult.producedByComponent]. Leave empty to skip the
     * component-conservation check (e.g. when verifying a DB-loaded historical run).
     */
    producedByComponent: Map<String, Double> = emptyMap(),
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
    // demand_ids whose committed_demands rows are ALL hard-planning-failures (no benign row at
    // all) — a genuine "nothing could be planned" demand, for which the engine legitimately
    // emits no pegging tree. R0_no_tree below must skip these the same way committedQtyById
    // already does (see comment below), or it false-positives on Negative_Inventory_* pseudo-
    // demands and any fully-blocked real demand.
    val anyBenignRowByDemand = mutableMapOf<String, Boolean>()
    for (row in committedDemands) {
        val did = row["demand_id"]?.toString() ?: continue
        if (did.isBlank()) continue
        // Skip hard-planning-failure rows. plan() emits a committedRow with
        // `quantity=residual` and `commit_reason=no_methods | no_preferred_method
        // | cycle_stopped | depth_limit | child_failed:*` to flag the SHORTFALL,
        // not actual commit. The tree root's committed_qty correctly reports 0
        // in those cases, so summing the shortfall qty here causes spurious R0
        // mismatches (Negative_Inventory_* pseudo-demands and any fully-blocked
        // real demand). Benign reasons (inventory, partial, null) and the
        // `no_methods_succeeded` zero-qty placeholder pass through.
        val reason = row["commit_reason"] as? String
        if (isHardPlanningFailure(reason)) {
            if (did !in anyBenignRowByDemand) anyBenignRowByDemand[did] = false
            continue
        }
        anyBenignRowByDemand[did] = true
        val q = (row["quantity"] as? Number)?.toDouble() ?: 0.0
        committedQtyById[did] = (committedQtyById[did] ?: 0.0) + q
    }
    // Negative_Inventory_* pseudo-demands (synthetic rows representing negative starting
    // inventory) can fail planning so completely that the engine emits no committed_demands
    // row AT ALL — not even a hard-failure shortfall row — so they never reach
    // anyBenignRowByDemand above. Catch that case directly by demand_id prefix instead of
    // relying on committed_demands presence.
    val hardFailureDemandIds = anyBenignRowByDemand.filterValues { !it }.keys +
        demands.mapNotNull { it["demand_id"]?.toString() }
            .filter { it.startsWith("Negative_Inventory_") && it !in anyBenignRowByDemand }

    // Total WO production at each (pid, lid). Currently unused by any rule
    // (R7c moved to a more correct cap below). Kept around because rule
    // additions in this file commonly need this aggregation.
    val woQtyByComponent = mutableMapOf<String, Double>()
    for (wo in workOrders) {
        val pid = (wo["product_id"] as? String)?.trim() ?: continue
        val lid = (wo["location_id"] as? String)?.trim() ?: continue
        val qty = (wo["quantity"] as? Number)?.toDouble() ?: 0.0
        if (pid.isBlank() || lid.isBlank() || qty <= 0) continue
        val key = "$pid|$lid"
        woQtyByComponent[key] = (woQtyByComponent[key] ?: 0.0) + qty
    }

    // R7c synthetic-bucket cap: bucket size at (pid|lid) is what the
    // consolidator allocated there — not what the WOs produced. Each
    // consolidator-emitted pegging entry (consolidated=true for multi-demand
    // groups, passthrough=true for single-demand passthrough groups) carries
    // a tree whose root committed_qty equals that group's producedQty (the
    // sum of split shares = the synthetic bucket's contribution at the
    // group's component). Sum across groups gives the bucket's total size.
    //
    // Why not WO production: the consolidator's planFn(syntheticDemand) can
    // satisfy producedQty from EITHER real inventory consumption OR new WOs
    // (or a mix). When the planFn drew from real inventory (e.g. 310-0362
    // had a 29.4k physical supply), no WOs are emitted at that component
    // but the bucket still gets sized for the consolidator's allocation.
    // Comparing Σ leaves to WO production then false-positives whenever the
    // bucket was inventory-backed.
    val consolidatorAllocationByComponent = mutableMapOf<String, Double>()
    for (entry in planningPegging) {
        val isConsolidated = entry["consolidated"] == true
        val isPassthrough = entry["passthrough"] == true
        if (!isConsolidated && !isPassthrough) continue
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        val pid = (tree["product_id"] as? String)?.trim() ?: continue
        val lid = (tree["location_id"] as? String)?.trim() ?: continue
        val committed = (tree["committed_qty"] as? Number)?.toDouble() ?: 0.0
        if (pid.isBlank() || lid.isBlank() || committed <= 0) continue
        val key = "$pid|$lid"
        consolidatorAllocationByComponent[key] = (consolidatorAllocationByComponent[key] ?: 0.0) + committed
    }

    // For each demand_id, locate its canonical pegging tree.
    //
    // Under the leaf-legacy engine, the consolidator (ConsolidationEngine.kt:606-620)
    // legitimately emits a second entry tagged with the same demand_id but
    // marked `passthrough: true` — these represent supply-allocation pegging
    // at deeper (pid, lid) levels needed by the WO-pegging endpoint. They are
    // NOT alternate views of the demand tree. Filter them out so they don't
    // count as duplicates of the canonical tree.
    //
    // R0_pegging_duplicate then fires only on TRUE duplicates: two or more
    // canonical-tagged entries for the same demand_id (which would indicate
    // a real engine emission bug).
    val peggingByDemand = planningPegging
        .mapNotNull { entry ->
            val did = entry["demand_id"]?.toString() ?: return@mapNotNull null
            if (did.isBlank()) return@mapNotNull null
            // Skip passthrough/consolidated tags — supply-allocation pegging
            // fragments, not the demand's canonical tree.
            if (entry["passthrough"] == true) return@mapNotNull null
            if (entry["consolidated"] == true) return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val tree = entry["tree"] as? Map<String, Any?> ?: return@mapNotNull null
            did to tree
        }
        .groupBy({ it.first }, { it.second })
    val peggingDuplicateDemands = peggingByDemand.filter { it.value.size > 1 }.keys
    // Last wins (preserves prior behavior when no duplicates exist).
    val treeByDemand: Map<String, Map<String, Any?>> = peggingByDemand.mapValues { it.value.last() }

    // ── Per-demand walk ───────────────────────────────────────────────────────
    val demandReports = mutableListOf<DemandSoundness>()
    val perDemandSupplyConsumption = mutableMapOf<String, Double>()  // supply_id → total qty (across leaves of one demand)
    val crossDemandSupplyConsumption = mutableMapOf<String, Double>()  // supply_id → total qty (across all demands)
    val crossDemandSyntheticConsumption = mutableMapOf<String, Double>()  // "$pid|$lid" → total qty drawn from synthetic consolidated buckets

    for ((demandId, demandRow) in demandById) {
        val tree = treeByDemand[demandId]
        if (tree == null) {
            val qty = (demandRow["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 0 && planningPegging.isNotEmpty() && demandId !in hardFailureDemandIds) {
                // Demand has no pegging tree but pegging was provided — genuine missing
                // tree. The engine should emit a tree even for cycle_stopped / depth_limit.
                // (Demands whose committed_demands rows are ALL hard-planning-failures —
                // e.g. Negative_Inventory_* pseudo-demands with commit_reason=no_methods —
                // legitimately have no tree at all; skipped above via hardFailureDemandIds,
                // matching committedQtyById's existing skip for the same row class.)
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
                // Either zero-qty demand (no tree expected) or planning_pegging was not
                // stored in this result (stripped to keep DB size manageable). Cannot
                // check R0-R9 — treat as unchecked (no violations).
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
            data = data,
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

    val crossViolations = mutableListOf<Violation>()

    // ── R0 pegging-tree uniqueness ────────────────────────────────────────────
    // If a demand_id appears in planning_pegging more than once, the per-demand
    // walk silently uses the last entry. Surface the duplicates so a regression
    // in either engine's pegging emission can't hide behind "last wins."
    for (did in peggingDuplicateDemands) {
        val n = peggingByDemand[did]?.size ?: 0
        crossViolations.add(Violation(
            rule = "R0_pegging_duplicate",
            nodePath = "demand:$did",
            message = "demand_id=$did has $n entries in planning_pegging; expected exactly one canonical tree.",
            expected = 1,
            actual = n,
        ))
    }

    // ── R7b cross-demand supply.qty bound ─────────────────────────────────────
    // Tolerance: max(config.tolerance, 1e-9 × available) — the absolute floor
    // covers small-supply cases (e.g. supply.qty=4 with 6 demands at 0.667
    // each), the relative term scales for large supplies where summing many
    // unrounded fair-split takes can drift by more than 1e-6 (FP error grows
    // with the number of summands; bound by ~N × eps × magnitude).
    for ((supplyId, totalConsumed) in crossDemandSupplyConsumption) {
        val supply = supplyById[supplyId]
        if (supply == null) continue  // R7a already flagged this per-demand
        val available = (supply["qty"] as? Number)?.toDouble() ?: 0.0
        val tol = maxOf(config.tolerance, 1e-9 * available)
        if (totalConsumed > available + tol) {
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
    // Each `consolidated_<pid>_<lid>` synthetic inventory bucket is sized to
    // the consolidator's allocation at that component (= Σ split shares =
    // producedQty across all groups at (pid|lid)). Per-demand consumption
    // from that bucket must not exceed the bucket's size. Runtime's
    // consumeFromInventory clamps takes to bucket.qty, so this bound holds
    // by construction in normal operation; R7c catches bugs that bypass it
    // (pegging-tree edits, save-path corruption, wrong producer attribution).
    //
    // Skipped (no violation) when consolidatorAllocationByComponent is empty —
    // either the run didn't use leaf-legacy consolidation, or the caller
    // didn't pass the consolidator's pegging entries.
    if (consolidatorAllocationByComponent.isNotEmpty()) {
        for ((componentKey, totalConsumed) in crossDemandSyntheticConsumption) {
            val cap = consolidatorAllocationByComponent[componentKey] ?: 0.0
            val tol = maxOf(config.tolerance, 1e-9 * cap)
            if (totalConsumed > cap + tol) {
                crossViolations.add(Violation(
                    rule = "R7c_consolidated_overconsumption",
                    nodePath = "synthetic:$componentKey",
                    message = "Σ consumption from synthetic 'consolidated_$componentKey' bucket exceeds " +
                        "consolidator's allocation at $componentKey.",
                    expected = cap,
                    actual = totalConsumed,
                ))
            }
        }
    }

    // Served demand IDs: demands with committed qty > 0. Used to exclude unserved demands
    // from conservation checks — their inventory was restored by plan()'s invCopy rollback.
    val servedDemandIds: Set<String> = committedQtyById
        .filter { (_, qty) -> qty > 1e-9 }
        .keys

    // R7e: conservation of mass — only when both inventory snapshots are provided.
    // DB-loaded soundness checks on historical runs pass empty lists and skip this.
    //
    // No servedDemandIds filter here: supply allocations already represent real
    // inventory consumption (extractSupplyAllocations skips failed=true subtrees whose
    // draws were rolled back). Filtering by post-reconcile servedDemandIds would
    // incorrectly exclude VIRTUAL consolidation demands whose inventory was consumed
    // pre-reconcile but whose committed_qty was zeroed by reconcile() post-extraction.
    val conservationViolations: List<String> =
        if (inventoryEffectiveInitial.isNotEmpty() && inventoryLeftover.isNotEmpty())
            verifyInventoryConservation(
                inventoryEffectiveInitial, inventoryLeftover, supplyAllocations,
            )
        else emptyList()

    // R7f: component conservation — only when producedByComponent is provided.
    val componentConservationViolations: List<String> =
        if (producedByComponent.isNotEmpty())
            verifyComponentConservation(
                producedByComponent, planningPegging,
                servedDemandIds = servedDemandIds.ifEmpty { null },
            )
        else emptyList()

    // R7g: WO conservation — post-trim WO qty vs served-demand consumption.
    val woConservationViolations: List<String> =
        if (producedByComponent.isNotEmpty() && workOrders.isNotEmpty())
            verifyWoConservation(
                producedByComponent, planningPegging, workOrders,
                servedDemandIds = servedDemandIds.ifEmpty { null },
            )
        else emptyList()

    // R10: inventory priority — timely physical inventory must be exhausted before
    // creating WOs at the same component. Only runs when inventoryLeftover is available
    // (same guard as R7e; skipped for historical runs without persisted snapshots).
    val inventoryPriorityViolations: List<String> =
        if (inventoryLeftover.isNotEmpty() && workOrders.isNotEmpty())
            verifyInventoryPriority(inventoryLeftover, workOrders, supplies)
        else emptyList()

    // R11: wo_group_id orphan check — only when work_orders is available.
    val woGidOrphanViolations: List<Violation> =
        if (workOrders.isNotEmpty()) verifyWoGidOrphans(planningPegging, workOrders, workOrdersNative)
        else emptyList()

    // R12: resource overload — only when work_orders is available.
    val resourceOverloadViolations: List<Violation> =
        if (workOrders.isNotEmpty()) verifyResourceOverload(workOrders, data)
        else emptyList()

    val soundCount = demandReports.count { it.sound }
    // R7f (component conservation) and R7g (WO conservation) are efficiency signals:
    // they fire at max_iterations=1 by design (budget leakage is expected and reclaimed
    // naturally at higher iter counts). They do NOT gate overallSound so that sound plans
    // can still be promoted to KB even when single-pass allocation leaves some slack.
    // R10/R11/R12 gate overallSound: scheduling errors are planning bugs.
    val overallSound = soundCount == demandReports.size &&
        crossViolations.isEmpty() &&
        conservationViolations.isEmpty() &&
        inventoryPriorityViolations.isEmpty() &&
        woGidOrphanViolations.isEmpty() &&
        resourceOverloadViolations.isEmpty()

    return SoundnessReport(
        overallSound = overallSound,
        demandCount = demandReports.size,
        soundCount = soundCount,
        demands = demandReports,
        crossDemandViolations = crossViolations,
        deepCheck = config.deepCheck,
        conservationViolations = conservationViolations,
        componentConservationViolations = componentConservationViolations,
        woConservationViolations = woConservationViolations,
        inventoryPriorityViolations = inventoryPriorityViolations,
        woGidOrphanViolations = woGidOrphanViolations,
        resourceOverloadViolations = resourceOverloadViolations,
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
    /** Full dataset — needed by R5_lead_time to consult OperationLookup
     *  (productlocation + operation + bor + resource) instead of the static
     *  method_make.lead_time. */
    val data: Map<String, List<Map<String, Any?>>>,
) {
    val violations = mutableListOf<Violation>()
    /** supply_id → qty consumed across all leaves of this demand's tree. */
    val supplyConsumption = mutableMapOf<String, Double>()
    /** "$pid|$lid" → qty consumed via synthetic `consolidated_<pid>_<lid>` buckets. */
    val syntheticConsumption = mutableMapOf<String, Double>()
    /**
     * OR-group merge map for R5_predecessor_sequencing.
     * Keyed by a WO's `wo_group_id`; value is the union of demand-typed
     * grandchildren of all OR-sibling WOs sharing the same parent demand.
     * Populated by [preBuildOrGroups] before the main walk.  When a WO is in
     * the map, [validatePredecessorSequencing] uses the union as its child
     * set (mirroring `fixTimingFromPegging`'s OR-merge in PlanningEngine.kt).
     * WOs not in the map fall back to their own direct demand children.
     */
    val woUnionChildren = mutableMapOf<String, List<Map<String, Any?>>>()

    /**
     * Pre-pass: walk the pegging tree once to identify OR-groups (demand
     * nodes with > 1 non-failed work_order children) and record, for every
     * member WO, the union of demand grandchildren contributed by all OR
     * siblings.  Mirrors the canonicalization in PlanningEngine.kt's
     * `detectOrGroups` so the soundness check can verify the post-condition
     * the planner enforces.
     */
    @Suppress("UNCHECKED_CAST")
    fun preBuildOrGroups(tree: Map<String, Any?>) {
        fun visit(node: Map<String, Any?>, depth: Int) {
            if (depth > 60) return
            if (node["type"] == "demand") {
                val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
                val woChildren = children.filter { it["type"] == "work_order" && it["failed"] != true }
                if (woChildren.size > 1) {
                    val unionDemandChildren = woChildren.flatMap { wo ->
                        ((wo["children"] as? List<Map<String, Any?>>) ?: emptyList())
                            .filter { it["type"] == "demand" }
                    }
                    for (wo in woChildren) {
                        val gid = wo["wo_group_id"] as? String ?: continue
                        woUnionChildren[gid] = unionDemandChildren
                    }
                }
            }
            val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
            for (c in children) visit(c, depth + 1)
        }
        visit(tree, 0)
    }

    fun walkRoot(tree: Map<String, Any?>) {
        preBuildOrGroups(tree)
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
        // Note: committed < requested (partial fulfillment) is an acceptable
        // planner outcome, not a soundness violation — the planner does its
        // best with available supply. Likewise, commit_time > request_due_time
        // (late delivery) is an acceptable outcome when supply timing forces
        // it. The soundness checker validates the plan's *internal*
        // consistency (no phantom WOs, conservation holds, no over-commitment),
        // not whether every customer contract was met perfectly. Both signals
        // surface to the user via committed_demands.shortage and the partial
        // commit_reason; they don't belong in the soundness verdict.
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
            "operation", "resource" -> {
                // Visualization nodes attached to make WOs (UPH/BOR model). They
                // carry no supply qty and aren't part of the demand-supply graph,
                // so soundness rules don't apply — skip silently.
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
        // Purchase leaves are terminal. Pricing / vendor selection is out of
        // scope for soundness, but the structural fields that downstream
        // consumers rely on (UI, supply allocation extraction) must be
        // present and well-formed: pid + lid + non-negative qty. A qty=0
        // purchase leaf is legitimate (placeholder under a blocked-WO at
        // line 1381 of PlanningEngine.kt), so only flag qty<0 and missing
        // identifiers.
        val pid = (node["product_id"] as? String)?.trim()
        val lid = (node["location_id"] as? String)?.trim()
        val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
        if (pid.isNullOrBlank()) {
            violations.add(Violation(
                rule = "R7d_purchase_pid_missing",
                nodePath = path,
                message = "Purchase leaf has no product_id.",
            ))
        }
        if (lid.isNullOrBlank()) {
            violations.add(Violation(
                rule = "R7d_purchase_lid_missing",
                nodePath = path,
                message = "Purchase leaf has no location_id.",
            ))
        }
        if (qty < -config.tolerance) {
            violations.add(Violation(
                rule = "R7d_purchase_qty_negative",
                nodePath = path,
                message = "Purchase leaf has negative quantity: $qty.",
                actual = qty,
            ))
        }
    }

    private fun walkWorkOrder(node: Map<String, Any?>, path: String) {
        // Failed-marker contract: nodes flagged `failed = true` are debug
        // snapshots from the AND-bottleneck blocked branch in
        // PlanningEngine.planMethodSlot. Their qty math, leaf consumption,
        // and child pegging are deliberately stale — the first-pass takes
        // were rolled back, but the structural snapshot is preserved so the
        // UI can show the user *why* the method was blocked. Skip both rule
        // evaluation and recursion: validating a known-broken snapshot would
        // surface engine-bug-shaped violations (R4 qty propagation, R7d
        // orphan leaves) for what is actually expected behaviour.
        if (node["failed"] == true) return
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

        // R7d — orphan-leaf check: if this WO emitted zero qty, no supply or
        // purchase leaf below it should claim consumption. Catches the
        // pre-fix planMethodSlot bug where a blocked-AND-bottleneck branch
        // returned without restoring inventory, leaving first-pass leaf
        // takes attached to a zero-qty placeholder WO. Structurally implied
        // by R4 (qty propagation), but R4's message is generic — R7d names
        // the pattern so operators see "orphan inventory consumption" rather
        // than a vague "child qty mismatch."
        val woQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
        if (woQty <= config.tolerance) {
            val leafSum = sumSupplyAndPurchaseLeavesIn(node)
            if (leafSum > config.tolerance) {
                violations.add(Violation(
                    rule = "R7d_orphan_leaf_under_blocked_wo",
                    nodePath = path,
                    message = "WO emitted ~0 qty but supply/purchase leaves under its subtree consume " +
                        "$leafSum total. Likely a planner under-consumption bug — inventory was claimed " +
                        "but no output was produced.",
                    expected = 0.0,
                    actual = leafSum,
                ))
            }
        }
        // Recurse into children regardless of method (purchase has no children).
        @Suppress("UNCHECKED_CAST")
        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        children.forEachIndexed { i, child ->
            walkChildOfWorkOrder(child, "$path-$i", parentNode = node)
        }
    }

    /**
     * R7d helper — sum the qty of every supply / purchase leaf reachable from
     * this node's subtree. Used to detect orphan inventory consumption (leaves
     * claiming qty under a zero-qty parent WO). Stops descending into nested
     * `work_order` nodes whose `quantity` is non-zero — those have their own
     * R7d frame and own R4 propagation check, so we shouldn't double-count.
     */
    private fun sumSupplyAndPurchaseLeavesIn(node: Map<String, Any?>): Double {
        val type = node["type"]?.toString()
        if (type == "supply" || type == "purchase") {
            return (node["quantity"] as? Number)?.toDouble() ?: 0.0
        }
        // Don't descend into nested non-zero WOs — they're independent R7d
        // frames. Zero-qty nested WOs ARE descended into (their leaves count
        // toward the outer WO's orphan tally too — orphan can chain).
        if (type == "work_order") {
            val q = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            if (q > config.tolerance) return 0.0
        }
        @Suppress("UNCHECKED_CAST")
        val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
        return children.sumOf { sumSupplyAndPurchaseLeavesIn(it) }
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

    /**
     * R5_predecessor_sequencing — at every WO node W, W.start_time must be ≥
     * max(commit_time) across W's effective child demand set.  When W is part
     * of an OR-group (waterfall alternatives under a shared parent demand),
     * the effective set is the union of all alternatives' demand grandchildren
     * (matching `fixTimingFromPegging`'s OR-merge); otherwise it's W's own
     * demand children.  Children with no commit_time, or with
     * commit_reason ∈ {cycle_stopped, cycle_detected}, are excluded from the
     * target so synthetic-cycle markers don't trigger false positives.
     * `config.timeToleranceDays` slack absorbs date-truncation rounding.
     */
    private fun validatePredecessorSequencing(node: Map<String, Any?>, path: String) {
        val parentStart = parseDateLocal(node["start_time"]?.toString()) ?: return
        @Suppress("UNCHECKED_CAST")
        val ownDemandChildren = ((node["children"] as? List<Map<String, Any?>>) ?: emptyList())
            .filter { it["type"] == "demand" }
        val gid = node["wo_group_id"] as? String
        val effectiveChildren = gid?.let { woUnionChildren[it] } ?: ownDemandChildren
        val commitTimes = effectiveChildren
            .filter { ch ->
                val r = ch["commit_reason"] as? String
                // Cycle-stop diagnostic markers carry no real timing.
                if (r == "cycle_stopped" || r == "cycle_detected") return@filter false
                // Hard-planning-failure children (no_methods, depth_limit,
                // child_failed:*, etc.) never actually produced anything;
                // their commit_time is the planner's wishful request_time
                // fallback — not a real predecessor constraint on the
                // parent's start.  Exclude.
                if (isHardPlanningFailure(r)) return@filter false
                true
            }
            .mapNotNull { parseDateLocal(it["commit_time"]?.toString()) }
        val target = commitTimes.maxOrNull() ?: return
        if (parentStart.toEpochDay() + config.timeToleranceDays < target.toEpochDay()) {
            val orSuffix = if (gid != null && woUnionChildren.containsKey(gid)) " (OR-merged group)" else ""
            violations.add(Violation(
                rule = "R5_predecessor_sequencing",
                nodePath = path,
                message = "WO start_time ($parentStart) is earlier than max child commit_time ($target)$orSuffix.",
                expected = target.toString(),
                actual = parentStart.toString(),
            ))
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

        // R5: parent_time = max(child_times) + LEAD_TIME.
        // When the WO falls under an applicable operation override (UPH/BOR),
        // the planner used OperationLookup.effectiveLeadDays for per-lot
        // duration and OperationLookup.parallelismCap for concurrent-lot waves,
        // so expected total duration = wave_count * per-lot. Fall back to the
        // method_make minimum (existing behavior) when the override doesn't
        // apply.
        // R5: WO duration must be ≥ waveCount × lead_time (the calendar constraint).
        // consolidateByWaves emits span = lead_time; ResourceScheduler extends to waveCount × lead_time.
        // Checking against lead_time directly (not UPH-derived perLotLead) makes R5 a direct
        // enforcement of the calendar contract.
        val staticLeadTime = matchingMakes
            .mapNotNull { (it["lead_time"] as? Number)?.toDouble() }
            .minOrNull()
            ?: 0.0
        val lotCount = (node["lot_count"] as? Number)?.toInt()?.takeIf { it > 0 } ?: 1
        val rawCap = OperationLookup.parallelismCap(pid, lid, data)
        val parallelismCap = if (rawCap > 0) rawCap else Int.MAX_VALUE
        val waveCount = kotlin.math.ceil(lotCount.toDouble() / parallelismCap.toDouble()).toInt().coerceAtLeast(1)
        val leadTime = staticLeadTime * waveCount
        val startTime = parseDateLocal(node["start_time"]?.toString())
        val endTime = parseDateLocal(node["end_time"]?.toString())
        if (startTime != null && endTime != null) {
            val duration = endTime.toEpochDay() - startTime.toEpochDay()
            if (duration < (leadTime - config.timeToleranceDays).toLong()) {
                violations.add(Violation(
                    rule = "R5_lead_time",
                    nodePath = path,
                    message = "Make WO duration (end - start = $duration days) shorter than expected $leadTime days (= $staticLeadTime lead_time × $waveCount wave(s) at cap ${if (parallelismCap == Int.MAX_VALUE) "∞" else parallelismCap}).",
                    expected = leadTime,
                    actual = duration.toDouble(),
                ))
            }
        }

        // R5_predecessor_sequencing — start_time ≥ max(child commit_time).
        validatePredecessorSequencing(node, path)
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

        // R5_predecessor_sequencing — start_time ≥ max(source-side child commit_time).
        validatePredecessorSequencing(node, path)
    }
}

/**
 * R11 — wo_group_id orphan check.
 *
 * Collects every wo_group_id referenced by a non-failed WO node across all
 * pegging trees, then checks each against the set of gids present in
 * work_orders.  An orphaned gid (in trees but not in lots) means
 * resequenceFromPegging's pushUp returned early for that subtree —
 * parent WOs in the affected chain are not rescheduled when
 * ResourceScheduler shifts underlying real WOs, leading to stale timings.
 */
@Suppress("UNCHECKED_CAST")
internal fun verifyWoGidOrphans(
    planningPegging: List<Map<String, Any?>>,
    workOrders: List<Map<String, Any?>>,
    workOrdersNative: List<Map<String, Any?>> = emptyList(),
): List<Violation> {
    fun extractGids(list: List<Map<String, Any?>>): Set<String> =
        list.mapNotNullTo(mutableSetOf()) { (it["wo_group_id"] as? String)?.trim()?.takeIf(String::isNotBlank) }
    // Include native (pre-consolidation) gids: move WOs merged into a mixed-cargo consolidated WO
    // retain their original gid only in work_orders_native, not in consolidated work_orders.
    val gidsInLots: Set<String> = extractGids(workOrders) + extractGids(workOrdersNative)

    val orphans = mutableSetOf<String>()

    fun collect(node: Map<String, Any?>, depth: Int) {
        if (depth > 60) return
        if (node["failed"] == true) return  // mirror flattenPeggingToWorkOrders: skip entire failed subtree
        if (node["type"] == "work_order") {
            val gid = (node["wo_group_id"] as? String)?.trim()
            if (!gid.isNullOrBlank() && gid !in gidsInLots) orphans.add(gid)
        }
        for (c in (node["children"] as? List<Map<String, Any?>>) ?: emptyList()) collect(c, depth + 1)
    }

    for (entry in planningPegging) {
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        collect(tree, 0)
    }

    if (orphans.isEmpty()) return emptyList()
    val violations = mutableListOf<Violation>()
    val sample = orphans.take(20)
    for (gid in sample) {
        violations.add(Violation(
            rule = "R11_wo_gid_orphan",
            nodePath = "gid:$gid",
            message = "wo_group_id '$gid' is referenced in pegging trees but has no lot in work_orders. " +
                "resequenceFromPegging's pushUp cannot propagate timing through this subtree.",
            actual = gid,
        ))
    }
    if (orphans.size > 20) {
        violations.add(Violation(
            rule = "R11_wo_gid_orphan",
            nodePath = "summary",
            message = "${orphans.size} orphaned wo_group_ids total (first 20 shown).",
            actual = orphans.size,
        ))
    }
    return violations
}

/**
 * R12: resource overload check.
 *
 * For every make WO, compute the daily concurrent load on each BOR resource using
 * min(lot_count, parallelCap) × rate — matching ResourceScheduler's arbitration
 * model exactly. Any (resource_id, location_id) pair whose load exceeds its
 * declared size on at least one day produces one violation.
 *
 * Only WOs whose entire BOR (all resources) is resolvable at the WO's location
 * are included — same applicability gate as ResourceUtilization.kt and
 * ResourceScheduler.arbitrate so the rule is consistent with what the scheduler
 * actually controls.
 */
internal fun verifyResourceOverload(
    workOrders: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): List<Violation> {
    val operations = data["operation"] ?: emptyList()
    val bors = data["bor"] ?: emptyList()
    val resources = data["resource"] ?: emptyList()
    val productLocations = data["productlocation"] ?: emptyList()

    val operationByProdArea = operations.associateBy { (it["prod_area"] as? String)?.trim() ?: "" }
    val borsById = bors.groupBy { (it["bor_id"] as? String)?.trim() ?: "" }
    val sizeByResLoc = resources.associate {
        val rid = (it["resource_id"] as? String)?.trim() ?: ""
        val lid = (it["location_id"] as? String)?.trim() ?: ""
        (rid to lid) to ((it["size"] as? Number)?.toDouble() ?: 0.0)
    }
    val prodAreaByPidLid = productLocations.associate {
        val pid = (it["product_id"] as? String)?.trim() ?: ""
        val lid = (it["location_id"] as? String)?.trim() ?: ""
        (pid to lid) to ((it["prod_area"] as? String)?.trim() ?: "")
    }

    // Daily load accumulator keyed by (resource_id, location_id).
    val loadMap = mutableMapOf<Pair<String, String>, MutableMap<LocalDate, Double>>()

    for (wo in workOrders) {
        if ((wo["method"] as? String)?.lowercase() != "make") continue
        val pid = (wo["product_id"] as? String)?.trim() ?: continue
        val lid = (wo["location_id"] as? String)?.trim() ?: continue
        val startDt = parseDateLocal(wo["start_time"] as? String) ?: continue
        val endDt   = parseDateLocal(wo["end_time"]   as? String) ?: continue
        if (!endDt.isAfter(startDt)) continue

        val prodArea = prodAreaByPidLid[pid to lid]?.takeIf { it.isNotBlank() } ?: continue
        val op = operationByProdArea[prodArea] ?: continue
        val borId = (op["bor_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
        val borRows = borsById[borId] ?: continue
        if (borRows.isEmpty()) continue
        // Applicability gate: skip WOs where any BOR resource has no size entry at this location.
        val allPresent = borRows.all { br ->
            val rid = (br["resource_id"] as? String)?.trim() ?: return@all false
            sizeByResLoc.containsKey(rid to lid)
        }
        if (!allPresent) continue

        val rawLotCnt = (wo["lot_count"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
        val parallelCap = borRows.minOfOrNull { br ->
            val rid   = (br["resource_id"]   as? String)?.trim() ?: return@minOfOrNull Int.MAX_VALUE
            val bRate = (br["resource_rate"] as? Number)?.toDouble() ?: return@minOfOrNull Int.MAX_VALUE
            val sz    = sizeByResLoc[rid to lid] ?: return@minOfOrNull Int.MAX_VALUE
            if (bRate <= 0.0 || sz <= 0.0) Int.MAX_VALUE
            else Math.floor(sz / bRate).toInt()
        }?.coerceAtLeast(1) ?: Int.MAX_VALUE
        // Split into per-wave windows (every wave except the last runs `cap` concurrent
        // lots; the last, often-partial wave runs fewer) instead of a single flat
        // min(lot_count, cap) across the whole span — the flat model overstates load on
        // the tail wave's days and can both under- and over-trigger this rule relative
        // to what ResourceScheduler.arbitrate actually reserved.
        val perWaveDaysHint = (wo["per_wave_days"] as? Number)?.toLong()
        val waveWindows = OperationLookup.waveLotWindows(startDt, endDt, rawLotCnt, parallelCap, perWaveDaysHint)
            .ifEmpty { listOf(Triple(startDt, endDt, minOf(rawLotCnt, parallelCap))) }

        for (br in borRows) {
            val rid  = (br["resource_id"]   as? String)?.trim() ?: continue
            val rate = (br["resource_rate"] as? Number)?.toDouble() ?: continue
            val key  = rid to lid
            val bucket = loadMap.getOrPut(key) { mutableMapOf() }
            for ((waveStart, waveEnd, lotsThisWave) in waveWindows) {
                val dayLoad = rate * lotsThisWave
                var d = waveStart
                while (d.isBefore(waveEnd)) {
                    bucket[d] = (bucket[d] ?: 0.0) + dayLoad
                    d = d.plusDays(1)
                }
            }
        }
    }

    // Emit one violation per overloaded (resource, location); report worst day + overload count.
    val violations = mutableListOf<Violation>()
    for ((key, dayLoad) in loadMap) {
        val (rid, lid) = key
        val size = sizeByResLoc[key] ?: continue
        if (size <= 0.0) continue
        val overloaded = dayLoad.entries.filter { (_, v) -> v > size + 1e-9 }
        if (overloaded.isEmpty()) continue
        val (worstDay, worstLoad) = overloaded.maxByOrNull { (_, v) -> v }!!
        violations.add(Violation(
            rule     = "R12_resource_overload",
            nodePath = "resource:$rid@$lid",
            message  = "$rid at location $lid exceeds capacity on $worstDay: " +
                       "load=${String.format("%.1f", worstLoad)} > size=${size.toInt()} " +
                       "(${overloaded.size} day(s) overloaded).",
            expected = size,
            actual   = worstLoad,
        ))
    }
    return violations.sortedByDescending { (it.actual as? Double ?: 0.0) - (it.expected as? Double ?: 0.0) }
}

private fun parseDateLocal(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    return try {
        LocalDate.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * Streaming variant of [checkRunSoundness] that processes pegging entries one at a time.
 *
 * Use when the full pegging dataset is too large to hold in memory (e.g. a persisted run
 * loaded from the plan_pegging table where total JSON can exceed 2 GB).  The caller supplies
 * a [forEachEntry] callback that drives iteration: the callback receives a block and must call
 * the block exactly once per pegging entry.  Each entry is processed immediately and can be
 * GC-collected before the next one arrives.
 *
 * Cross-tree rule R11 accumulates a lightweight gid set across all entries and checks it after
 * the pass completes.
 *
 * Differences from [checkRunSoundness]:
 * - R7f (component conservation) and R7g (WO conservation) are not supported (they require
 *   producedByComponent, which is only available for in-transit runs).
 * - Otherwise equivalent: same per-demand rules (R0–R9), same cross-demand rules (R7b, R7c,
 *   R7e, R10, R11).
 */
@Suppress("UNCHECKED_CAST")
internal fun checkRunSoundnessStreaming(
    forEachEntry: ((Map<String, Any?>) -> Unit) -> Unit,
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: SoundnessConfig = SoundnessConfig(),
    workOrders: List<Map<String, Any?>> = emptyList(),
    workOrdersNative: List<Map<String, Any?>> = emptyList(),
    committedDemands: List<Map<String, Any?>> = emptyList(),
    inventoryEffectiveInitial: List<Map<String, Any?>> = emptyList(),
    inventoryLeftover: List<Map<String, Any?>> = emptyList(),
    supplyAllocations: List<Map<String, Any?>> = emptyList(),
): SoundnessReport {
    // ── Index building (same as checkRunSoundness) ────────────────────────────
    val demandById: Map<String, Map<String, Any?>> = demands.associateBy { it["demand_id"]?.toString() ?: "" }
    val bomRows = data["bom"] ?: emptyList()
    val methodMakes = data["method_make"] ?: emptyList()
    val methodMoves = data["method_move"] ?: emptyList()
    val supplies = data["supply"] ?: emptyList()
    val supplyById: Map<String, Map<String, Any?>> = supplies.associateBy { it["supply_id"]?.toString() ?: "" }

    val committedQtyById = mutableMapOf<String, Double>()
    // See checkRunSoundness's anyBenignRowByDemand/hardFailureDemandIds for the rationale:
    // demands whose committed_demands rows are ALL hard-planning-failures (or who have no
    // committed_demands row at all, e.g. Negative_Inventory_* pseudo-demands) legitimately
    // get no pegging tree — R0_no_tree below must skip them or it false-positives.
    val anyBenignRowByDemand = mutableMapOf<String, Boolean>()
    for (row in committedDemands) {
        val did = row["demand_id"]?.toString() ?: continue
        if (did.isBlank()) continue
        val reason = row["commit_reason"] as? String
        if (isHardPlanningFailure(reason)) {
            if (did !in anyBenignRowByDemand) anyBenignRowByDemand[did] = false
            continue
        }
        anyBenignRowByDemand[did] = true
        val q = (row["quantity"] as? Number)?.toDouble() ?: 0.0
        committedQtyById[did] = (committedQtyById[did] ?: 0.0) + q
    }
    val hardFailureDemandIds = anyBenignRowByDemand.filterValues { !it }.keys +
        demands.mapNotNull { it["demand_id"]?.toString() }
            .filter { it.startsWith("Negative_Inventory_") && it !in anyBenignRowByDemand }

    // ── Pre-build work_orders index (R11) ────────────────────────────────────
    // Include native (pre-consolidation) gids: after consolidation, individual move-WO gids
    // (e.g. wog19 for a per-product VIRTUAL move) survive only in work_orders_native, not in
    // the consolidated work_orders (which merges them into a mixed-cargo WO with a new gid).
    fun extractGids(list: List<Map<String, Any?>>): Set<String> =
        list.mapNotNullTo(mutableSetOf()) { (it["wo_group_id"] as? String)?.trim()?.takeIf(String::isNotBlank) }
    val gidsInLots: Set<String> = extractGids(workOrders) + extractGids(workOrdersNative)

    // ── Streaming accumulators ────────────────────────────────────────────────
    val consolidatorAllocationByComponent = mutableMapOf<String, Double>()
    val seenCanonicalDemands = mutableSetOf<String>()
    val peggingDuplicateDemandCount = mutableMapOf<String, Int>()
    val demandReports = mutableListOf<DemandSoundness>()
    val crossDemandSupplyConsumption  = mutableMapOf<String, Double>()
    val crossDemandSyntheticConsumption = mutableMapOf<String, Double>()
    var anyPeggingEntry = false

    val orphanGids = mutableSetOf<String>()                            // R11

    // ── Single streaming pass ─────────────────────────────────────────────────
    forEachEntry { entry ->
        anyPeggingEntry = true
        val isConsolidated = entry["consolidated"] == true
        val isPassthrough  = entry["passthrough"]  == true
        val did = entry["demand_id"]?.toString()
        val tree = (entry["tree"] as? Map<String, Any?>) ?: return@forEachEntry

        // R7c: consolidator allocation accumulation
        if (isConsolidated || isPassthrough) {
            val pid = (tree["product_id"]    as? String)?.trim()?.takeIf(String::isNotBlank)
            val lid = (tree["location_id"]   as? String)?.trim()?.takeIf(String::isNotBlank)
            val committed = (tree["committed_qty"] as? Number)?.toDouble() ?: 0.0
            if (pid != null && lid != null && committed > 0) {
                val key = "$pid|$lid"
                consolidatorAllocationByComponent[key] = (consolidatorAllocationByComponent[key] ?: 0.0) + committed
            }
        }

        // R11: collect orphaned gids from this tree
        if (workOrders.isNotEmpty()) {
            fun collectGids(node: Map<String, Any?>, depth: Int) {
                if (depth > 60) return
                if (node["failed"] == true) return  // mirror flattenPeggingToWorkOrders: skip entire failed subtree
                if (node["type"] == "work_order") {
                    val gid = (node["wo_group_id"] as? String)?.trim()
                    if (!gid.isNullOrBlank() && gid !in gidsInLots) orphanGids.add(gid)
                }
                for (c in (node["children"] as? List<Map<String, Any?>>) ?: emptyList()) collectGids(c, depth + 1)
            }
            collectGids(tree, 0)
        }

        // Per-demand checks (R0–R9): only canonical demand entries
        if (did == null || did.isBlank() || isPassthrough || isConsolidated) return@forEachEntry

        peggingDuplicateDemandCount[did] = (peggingDuplicateDemandCount[did] ?: 0) + 1
        if (!seenCanonicalDemands.add(did)) return@forEachEntry  // duplicate — skip; R0 fires below

        val demandRow = demandById[did] ?: return@forEachEntry

        val ctx = WalkContext(
            demandId   = did,
            demandRow  = demandRow,
            bomRows    = bomRows,
            methodMakes = methodMakes,
            methodMoves = methodMoves,
            supplyById  = supplyById,
            config      = config,
            data        = data,
        )
        ctx.walkRoot(tree)

        for ((supplyId, qty) in ctx.supplyConsumption)    crossDemandSupplyConsumption.merge(supplyId, qty, Double::plus)
        for ((compKey, qty) in ctx.syntheticConsumption)  crossDemandSyntheticConsumption.merge(compKey, qty, Double::plus)

        if (committedQtyById.isNotEmpty()) {
            val tableQty = committedQtyById[did]
            val treeQty  = (tree["committed_qty"] as? Number)?.toDouble() ?: 0.0
            if (tableQty != null && kotlin.math.abs(tableQty - treeQty) > config.tolerance) {
                ctx.violations.add(Violation(
                    rule     = "R0_committed_consistency",
                    nodePath = "0",
                    message  = "committed_demands.quantity=$tableQty doesn't match pegging root.committed_qty=$treeQty for demand $did.",
                    expected = treeQty,
                    actual   = tableQty,
                ))
            }
        }

        demandReports.add(DemandSoundness(demandId = did, sound = ctx.violations.isEmpty(), violations = ctx.violations.toList()))
    }

    // ── Post-pass: missing-tree reports ──────────────────────────────────────
    for ((demandId, demandRow) in demandById) {
        if (demandId in seenCanonicalDemands) continue
        val qty = (demandRow["quantity"] as? Number)?.toDouble() ?: 0.0
        if (qty > 0 && anyPeggingEntry && demandId !in hardFailureDemandIds) {
            demandReports.add(DemandSoundness(
                demandId = demandId, sound = false,
                violations = listOf(Violation(rule = "R0_no_tree", nodePath = "", message = "Demand has no pegging tree in planning_pegging.")),
            ))
        } else {
            demandReports.add(DemandSoundness(demandId = demandId, sound = true, violations = emptyList()))
        }
    }

    val crossViolations = mutableListOf<Violation>()

    // R0 pegging duplicate
    for ((did, count) in peggingDuplicateDemandCount) {
        if (count <= 1) continue
        crossViolations.add(Violation(
            rule = "R0_pegging_duplicate", nodePath = "demand:$did",
            message = "demand_id=$did has $count entries in planning_pegging; expected exactly one canonical tree.",
            expected = 1, actual = count,
        ))
    }

    // R7b cross-demand supply.qty bound
    for ((supplyId, totalConsumed) in crossDemandSupplyConsumption) {
        val supply = supplyById[supplyId] ?: continue
        val available = (supply["qty"] as? Number)?.toDouble() ?: 0.0
        val tol = maxOf(config.tolerance, 1e-9 * available)
        if (totalConsumed > available + tol) {
            crossViolations.add(Violation(
                rule = "R7b_supply_overconsumption", nodePath = "supply:$supplyId",
                message = "Total qty consumed across all demands exceeds supply.qty.",
                expected = available, actual = totalConsumed,
            ))
        }
    }

    // R7c synthetic-bucket bound
    if (consolidatorAllocationByComponent.isNotEmpty()) {
        for ((componentKey, totalConsumed) in crossDemandSyntheticConsumption) {
            val cap = consolidatorAllocationByComponent[componentKey] ?: 0.0
            val tol = maxOf(config.tolerance, 1e-9 * cap)
            if (totalConsumed > cap + tol) {
                crossViolations.add(Violation(
                    rule = "R7c_consolidated_overconsumption", nodePath = "synthetic:$componentKey",
                    message = "Σ consumption from synthetic 'consolidated_$componentKey' bucket exceeds consolidator's allocation at $componentKey.",
                    expected = cap, actual = totalConsumed,
                ))
            }
        }
    }

    // R7e conservation — no servedDemandIds filter (see checkRunSoundness comment above)
    val conservationViolations: List<String> =
        if (inventoryEffectiveInitial.isNotEmpty() && inventoryLeftover.isNotEmpty())
            verifyInventoryConservation(inventoryEffectiveInitial, inventoryLeftover, supplyAllocations)
        else emptyList()

    // R10 inventory priority
    val inventoryPriorityViolations: List<String> =
        if (inventoryLeftover.isNotEmpty() && workOrders.isNotEmpty())
            verifyInventoryPriority(inventoryLeftover, workOrders, supplies)
        else emptyList()

    // R11: from accumulated orphan gids
    val woGidOrphanViolations: List<Violation> = if (workOrders.isNotEmpty() && orphanGids.isNotEmpty()) {
        val vs = mutableListOf<Violation>()
        for (gid in orphanGids.take(20)) {
            vs.add(Violation(rule = "R11_wo_gid_orphan", nodePath = "gid:$gid",
                message = "wo_group_id '$gid' is referenced in pegging trees but has no lot in work_orders. " +
                    "resequenceFromPegging's pushUp cannot propagate timing through this subtree.",
                actual = gid))
        }
        if (orphanGids.size > 20) vs.add(Violation(rule = "R11_wo_gid_orphan", nodePath = "summary",
            message = "${orphanGids.size} orphaned wo_group_ids total (first 20 shown).", actual = orphanGids.size))
        vs
    } else emptyList()

    // R12: resource overload — only when work_orders is available.
    val resourceOverloadViolations: List<Violation> =
        if (workOrders.isNotEmpty()) verifyResourceOverload(workOrders, data)
        else emptyList()

    val soundCount = demandReports.count { it.sound }
    val overallSound = soundCount == demandReports.size &&
        crossViolations.isEmpty() &&
        conservationViolations.isEmpty() &&
        inventoryPriorityViolations.isEmpty() &&
        woGidOrphanViolations.isEmpty() &&
        resourceOverloadViolations.isEmpty()

    return SoundnessReport(
        overallSound  = overallSound,
        demandCount   = demandReports.size,
        soundCount    = soundCount,
        demands       = demandReports,
        crossDemandViolations = crossViolations,
        deepCheck     = config.deepCheck,
        conservationViolations = conservationViolations,
        componentConservationViolations = emptyList(),  // not applicable for DB-loaded runs
        woConservationViolations        = emptyList(),  // not applicable for DB-loaded runs
        inventoryPriorityViolations     = inventoryPriorityViolations,
        woGidOrphanViolations           = woGidOrphanViolations,
        resourceOverloadViolations      = resourceOverloadViolations,
    )
}
