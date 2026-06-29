package com.allocator

import com.allocator.services.SoundnessConfig
import com.allocator.services.checkRunSoundness
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as stringShouldContain

/**
 * Unit tests for [checkRunSoundness] — one test per rule, both pass and fail
 * synthetic peggings. Each test builds a minimal case_data + planning_pegging
 * pair and asserts on the resulting [SoundnessReport].
 */
class SoundnessCheckerTest : FunSpec({

    // ── Synthetic helpers ─────────────────────────────────────────────────────

    /** Demand row builder — matches CaseLoader's shape. */
    fun demand(id: String, pid: String, lid: String = "VIRTUAL", qty: Double = 10.0) = mapOf(
        "demand_id" to id,
        "product_id" to pid,
        "location_id" to lid,
        "quantity" to qty,
    )

    /** Supply row builder. */
    fun supply(supplyId: String, pid: String, lid: String, qty: Double) = mapOf(
        "supply_id" to supplyId,
        "product_id" to pid,
        "location_id" to lid,
        "qty" to qty,
    )

    /** method_make row builder. */
    fun mk(pid: String, lid: String, leadTime: Double = 0.0, bomId: String = "BOM_$pid") = mapOf(
        "bom_id" to bomId,
        "product_id" to pid,
        "location_id" to lid,
        "preference" to 0,
        "lead_time" to leadTime,
    )

    /** method_move row builder. */
    fun mv(pid: String, fromLid: String, toLid: String, transit: Double = 0.0) = mapOf(
        "product_id" to pid,
        "from_location_id" to fromLid,
        "to_location_id" to toLid,
        "transit_time" to transit,
        "preference" to 0,
    )

    /** BOM row builder. */
    fun bom(parent: String, child: String, rate: Double = 1.0, altGroup: String? = null, bomId: String = "BOM_$parent") = mapOf(
        "bom_id" to bomId,
        "parent_id" to parent,
        "child_id" to child,
        "rate" to rate,
        "alt_group" to altGroup,
    )

    // Pegging tree node builders.
    fun demandNode(
        demandId: String,
        pid: String,
        lid: String,
        qty: Double,
        committedQty: Double,
        children: List<Map<String, Any?>> = emptyList(),
        commitTime: String? = null,
        requestTime: String = "2024-12-01",
    ) = mapOf(
        "type" to "demand",
        "demand_id" to demandId,
        "product_id" to pid,
        "location_id" to lid,
        "quantity" to qty,
        "committed_qty" to committedQty,
        "request_time" to requestTime,
        "commit_time" to commitTime,
        "children" to children,
    )

    fun supplyLeaf(pid: String, lid: String, supplyId: String, qty: Double, commitTime: String? = null) = mapOf(
        "type" to "supply",
        "product_id" to pid,
        "location_id" to lid,
        "supply_id" to supplyId,
        "quantity" to qty,
        "commit_time" to commitTime,
        "children" to emptyList<Any>(),
    )

    fun makeWO(
        pid: String,
        lid: String,
        qty: Double,
        children: List<Map<String, Any?>> = emptyList(),
        startTime: String = "2024-11-01",
        endTime: String = "2024-11-01",
    ) = mapOf(
        "type" to "work_order",
        "product_id" to pid,
        "location_id" to lid,
        "quantity" to qty,
        "start_time" to startTime,
        "end_time" to endTime,
        "method" to "make",
        "children" to children,
    )

    fun moveWO(
        pid: String,
        fromLid: String,
        toLid: String,
        qty: Double,
        children: List<Map<String, Any?>>,
        startTime: String = "2024-11-01",
        endTime: String = "2024-11-01",
    ) = mapOf(
        "type" to "work_order",
        "product_id" to pid,
        "location_id" to toLid,
        "location_source" to fromLid,
        "quantity" to qty,
        "start_time" to startTime,
        "end_time" to endTime,
        "method" to "move",
        "children" to children,
    )

    fun pegEntry(demandId: String, tree: Map<String, Any?>) = mapOf(
        "demand_id" to demandId,
        "tree" to tree,
    )

    // ── R0: structural sanity ─────────────────────────────────────────────────

    test("R0_no_tree: demand with no pegging entry is unsound") {
        val demands = listOf(demand("D1", "FG", qty = 10.0))
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S1", "FG", "VIRTUAL", 10.0)),
        )
        // R0_no_tree only fires when planningPegging is non-empty — that signals
        // pegging WAS generated but D1's tree is genuinely missing (engine bug),
        // vs. an empty list meaning pegging wasn't stored (historical run, fine).
        val phantomEntry = mapOf(
            "demand_id" to "D_OTHER",
            "tree" to mapOf("type" to "demand", "product_id" to "X", "location_id" to "VIRTUAL",
                "quantity" to 0.0, "committed_qty" to 0.0, "children" to emptyList<Any>()),
        )
        val report = checkRunSoundness(
            planningPegging = listOf(phantomEntry), demands = demands, data = data,
        )
        report.overallSound shouldBe false
        report.demands shouldHaveSize 1
        report.demands[0].sound shouldBe false
        report.demands[0].violations[0].rule shouldBe "R0_no_tree"
    }

    test("R0_no_tree: zero-qty demand without tree is sound") {
        val demands = listOf(demand("D1", "FG", qty = 0.0))
        val data = mapOf<String, List<Map<String, Any?>>>()
        val report = checkRunSoundness(planningPegging = emptyList(), demands = demands, data = data)
        report.overallSound shouldBe true
    }

    // ── R1: root match ────────────────────────────────────────────────────────

    test("R1_root_match: tree pid/lid mismatched flags violation") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "WRONG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(supplyLeaf("WRONG", "L1", "S1", 10.0)))
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S1", "WRONG", "L1", 10.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].sound shouldBe false
        report.demands[0].violations.map { it.rule } shouldContain "R1_root_match"
    }

    test("R1_committed_bound: committed_qty > quantity flags violation") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 15.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 15.0)))
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R1_committed_bound"
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    test("happy path: direct supply consumption is sound") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 10.0)))
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.overallSound shouldBe true
        report.demands[0].sound shouldBe true
    }

    test("happy path: make WO with correct rate propagation is sound") {
        // FG → make → consumes 2× of X. Engine convention: child = parent × rate,
        // so parent=10 with rate=2 needs 20 of X.
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0,
                children = listOf(
                    demandNode("D1", "X", "L1", qty = 20.0, committedQty = 20.0,
                        children = listOf(supplyLeaf("X", "L1", "S1", 20.0))),
                ))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(bom("FG", "X", rate = 2.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.overallSound shouldBe true
    }

    // ── R2: make method validity ──────────────────────────────────────────────

    test("R2_make_method_missing: make WO without matching method_make row") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0,
                children = listOf(demandNode("D1", "X", "L1", qty = 10.0, committedQty = 10.0,
                    children = listOf(supplyLeaf("X", "L1", "S1", 10.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0)),
            "method_make" to emptyList<Map<String, Any?>>(),  // No make for FG!
            "bom" to listOf(bom("FG", "X", rate = 1.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R2_make_method_missing"
    }

    // ── R3: move method validity ──────────────────────────────────────────────

    test("R3_move_method_missing: move WO without matching method_move row") {
        val demands = listOf(demand("D1", "FG", "L2", qty = 10.0))
        val tree = demandNode("D1", "FG", "L2", qty = 10.0, committedQty = 10.0,
            children = listOf(moveWO("FG", "L1", "L2", qty = 10.0,
                children = listOf(demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
                    children = listOf(supplyLeaf("FG", "L1", "S1", 10.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
            "method_move" to emptyList<Map<String, Any?>>(),  // No move for FG!
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R3_move_method_missing"
    }

    // ── R4: quantity propagation ──────────────────────────────────────────────

    test("R4_qty_propagation: child qty doesn't match parent_qty/rate") {
        // FG → make at rate 2.0 → child should be 5, not 7
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0,
                children = listOf(demandNode("D1", "X", "L1", qty = 7.0, committedQty = 7.0,
                    children = listOf(supplyLeaf("X", "L1", "S1", 7.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(bom("FG", "X", rate = 2.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R4_qty_propagation"
    }

    test("R4_qty_conservation_move: move WO child qty doesn't equal parent qty") {
        val demands = listOf(demand("D1", "FG", "L2", qty = 10.0))
        val tree = demandNode("D1", "FG", "L2", qty = 10.0, committedQty = 10.0,
            children = listOf(moveWO("FG", "L1", "L2", qty = 10.0,
                children = listOf(demandNode("D1", "FG", "L1", qty = 8.0, committedQty = 8.0,
                    children = listOf(supplyLeaf("FG", "L1", "S1", 8.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
            "method_move" to listOf(mv("FG", "L1", "L2")),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R4_qty_conservation_move"
    }

    // ── R5: time propagation ──────────────────────────────────────────────────

    test("R5_lead_time: make WO duration shorter than lead_time") {
        // Lead_time=10 days; WO has start=11-01, end=11-01 (0 days)
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0, startTime = "2024-11-01", endTime = "2024-11-01",
                children = listOf(demandNode("D1", "X", "L1", qty = 10.0, committedQty = 10.0,
                    children = listOf(supplyLeaf("X", "L1", "S1", 10.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1", leadTime = 10.0)),
            "bom" to listOf(bom("FG", "X", rate = 1.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R5_lead_time"
    }

    test("R5_transit_time: move WO duration doesn't match transit_time") {
        val demands = listOf(demand("D1", "FG", "L2", qty = 10.0))
        val tree = demandNode("D1", "FG", "L2", qty = 10.0, committedQty = 10.0,
            children = listOf(moveWO("FG", "L1", "L2", qty = 10.0, startTime = "2024-11-01", endTime = "2024-11-01",
                children = listOf(demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
                    children = listOf(supplyLeaf("FG", "L1", "S1", 10.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
            "method_move" to listOf(mv("FG", "L1", "L2", transit = 5.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R5_transit_time"
    }

    // ── R5_predecessor_sequencing ─────────────────────────────────────────────

    test("R5_predecessor_sequencing: make WO with start ≥ child commit_time is sound") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0, startTime = "2024-11-15", endTime = "2024-11-15",
                children = listOf(
                    demandNode("D1", "X", "L1", qty = 10.0, committedQty = 10.0, commitTime = "2024-11-10",
                        children = listOf(supplyLeaf("X", "L1", "S1", 10.0))),
                    demandNode("D1", "Y", "L1", qty = 10.0, committedQty = 10.0, commitTime = "2024-11-15",
                        children = listOf(supplyLeaf("Y", "L1", "S2", 10.0))),
                ))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0), supply("S2", "Y", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(bom("FG", "X"), bom("FG", "Y")),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldNotContain "R5_predecessor_sequencing"
    }

    test("R5_predecessor_sequencing: make WO start before child commit_time flags violation") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0, startTime = "2024-11-01", endTime = "2024-11-01",
                children = listOf(demandNode("D1", "X", "L1", qty = 10.0, committedQty = 10.0,
                    commitTime = "2024-11-15",
                    children = listOf(supplyLeaf("X", "L1", "S1", 10.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(bom("FG", "X")),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R5_predecessor_sequencing"
    }

    test("R5_predecessor_sequencing: OR-group early alternative caught against sibling's later child commit") {
        // Demand D1 has TWO alternative make WOs (waterfall split): wog-A makes 3
        // units (small) using component X arriving 2024-11-01; wog-B makes 7 units
        // (large) using component Y arriving 2024-11-15.  The post-processing
        // OR-merge should have shifted wog-A to 2024-11-15 too.  Here wog-A is
        // still at 2024-11-01 — strict per-WO check passes (≥ X's commit), but
        // the OR-aware check flags wog-A against the union (which includes Y).
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val woA = makeWO("FG", "L1", qty = 3.0, startTime = "2024-11-01", endTime = "2024-11-01",
            children = listOf(demandNode("D1", "X", "L1", qty = 3.0, committedQty = 3.0,
                commitTime = "2024-11-01",
                children = listOf(supplyLeaf("X", "L1", "S1", 3.0))))) +
            mapOf("wo_group_id" to "wog-A")
        val woB = makeWO("FG", "L1", qty = 7.0, startTime = "2024-11-15", endTime = "2024-11-15",
            children = listOf(demandNode("D1", "Y", "L1", qty = 7.0, committedQty = 7.0,
                commitTime = "2024-11-15",
                children = listOf(supplyLeaf("Y", "L1", "S2", 7.0))))) +
            mapOf("wo_group_id" to "wog-B")
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(woA, woB))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0), supply("S2", "Y", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(bom("FG", "X"), bom("FG", "Y")),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        val violations = report.demands[0].violations.filter { it.rule == "R5_predecessor_sequencing" }
        violations shouldHaveSize 1
        // The violation is on wog-A (the early alt), not wog-B.
        violations[0].nodePath stringShouldContain "0-0"  // first WO under root demand
    }

    test("R5_predecessor_sequencing: move WO scheduled before source-side commit_time") {
        val demands = listOf(demand("D1", "FG", "L2", qty = 10.0))
        val tree = demandNode("D1", "FG", "L2", qty = 10.0, committedQty = 10.0,
            children = listOf(moveWO("FG", "L1", "L2", qty = 10.0, startTime = "2024-11-01", endTime = "2024-11-06",
                children = listOf(demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
                    commitTime = "2024-11-15",
                    children = listOf(supplyLeaf("FG", "L1", "S1", 10.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
            "method_move" to listOf(mv("FG", "L1", "L2", transit = 5.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R5_predecessor_sequencing"
    }

    test("R5_predecessor_sequencing: 1-day slack absorbed by timeToleranceDays") {
        // start = commit - 1 day with default tolerance=1 → no violation.
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0, startTime = "2024-11-09", endTime = "2024-11-09",
                children = listOf(demandNode("D1", "X", "L1", qty = 10.0, committedQty = 10.0,
                    commitTime = "2024-11-10",
                    children = listOf(supplyLeaf("X", "L1", "S1", 10.0)))))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(bom("FG", "X")),
        )
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands,
            data = data,
            config = SoundnessConfig(timeToleranceDays = 1L),
        )
        report.demands[0].violations.map { it.rule } shouldNotContain "R5_predecessor_sequencing"
    }

    test("R5_predecessor_sequencing: cycle_stopped child excluded from target") {
        // Only child has commit_reason=cycle_stopped (no commit_time) — should be
        // skipped and the rule passes (no other children to set a target).
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val cycleChild = mapOf(
            "type" to "demand",
            "demand_id" to "D1",
            "product_id" to "X",
            "location_id" to "L1",
            "quantity" to 10.0,
            "committed_qty" to 0.0,
            "commit_reason" to "cycle_stopped",
            "children" to emptyList<Any>(),
        )
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0, startTime = "2024-11-01", endTime = "2024-11-01",
                children = listOf(cycleChild))))
        val data = mapOf(
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(bom("FG", "X")),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldNotContain "R5_predecessor_sequencing"
    }

    // ── R6: variant consistency ───────────────────────────────────────────────

    test("R6_alt_group_inconsistent: make WO mixes children from two multi-row alt_groups") {
        // BOM: alt_group A has {X1, X2}; alt_group B has {Y1, Y2}.
        // Tree picks one child from A AND one child from B → cross-alt mixing.
        // Both alt_groups are multi-row, so this is a genuine OR violation
        // (engine should pick ONE alt_group's required-set, not mix).
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0,
                children = listOf(
                    demandNode("D1", "X1", "L1", qty = 10.0, committedQty = 10.0,
                        children = listOf(supplyLeaf("X1", "L1", "S1", 10.0))),
                    demandNode("D1", "Y1", "L1", qty = 10.0, committedQty = 10.0,
                        children = listOf(supplyLeaf("Y1", "L1", "S2", 10.0))),
                ))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X1", "L1", 100.0), supply("S2", "Y1", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(
                bom("FG", "X1", rate = 1.0, altGroup = "A"),
                bom("FG", "X2", rate = 1.0, altGroup = "A"),
                bom("FG", "Y1", rate = 1.0, altGroup = "B"),
                bom("FG", "Y2", rate = 1.0, altGroup = "B"),
            ),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R6_alt_group_inconsistent"
    }

    test("R6 OK: each child is its own alt_group (single-row groups, multi-variant pattern)") {
        // BOM: alt_group=child_id for each row → every alt_group has exactly 1 row.
        // Engine's multi-variant mode picks all such variants. Not flagged.
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0,
                children = listOf(
                    demandNode("D1", "X", "L1", qty = 10.0, committedQty = 10.0,
                        children = listOf(supplyLeaf("X", "L1", "S1", 10.0))),
                    demandNode("D1", "Y", "L1", qty = 10.0, committedQty = 10.0,
                        children = listOf(supplyLeaf("Y", "L1", "S2", 10.0))),
                ))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0), supply("S2", "Y", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(
                bom("FG", "X", rate = 1.0, altGroup = "X"),  // single-row group
                bom("FG", "Y", rate = 1.0, altGroup = "Y"),  // single-row group
            ),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldNotContain "R6_alt_group_inconsistent"
    }

    test("R6 OK: make WO children share same alt_group") {
        // BOM: FG → X (alt=A), FG → Y (alt=A). Both required (AND within same alt group).
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(makeWO("FG", "L1", qty = 10.0,
                children = listOf(
                    demandNode("D1", "X", "L1", qty = 10.0, committedQty = 10.0,
                        children = listOf(supplyLeaf("X", "L1", "S1", 10.0))),
                    demandNode("D1", "Y", "L1", qty = 10.0, committedQty = 10.0,
                        children = listOf(supplyLeaf("Y", "L1", "S2", 10.0))),
                ))))
        val data = mapOf(
            "supply" to listOf(supply("S1", "X", "L1", 100.0), supply("S2", "Y", "L1", 100.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "bom" to listOf(
                bom("FG", "X", rate = 1.0, altGroup = "A"),
                bom("FG", "Y", rate = 1.0, altGroup = "A"),
            ),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.overallSound shouldBe true
    }

    // ── R7: supply-leaf bounds ────────────────────────────────────────────────

    test("R7a_supply_id_unknown: leaf references nonexistent supply") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(supplyLeaf("FG", "L1", "GHOST", 10.0)))
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),  // GHOST doesn't exist
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R7a_supply_id_unknown"
    }

    test("R7a_supply_qty_exceeded: per-demand leaf qty exceeds supply.qty") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 100.0))
        val tree = demandNode("D1", "FG", "L1", qty = 100.0, committedQty = 100.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 100.0)))  // S1 only has 50
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 50.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        report.demands[0].violations.map { it.rule } shouldContain "R7a_supply_qty_exceeded"
    }

    test("R7b_supply_overconsumption: total qty across demands exceeds supply.qty") {
        val demands = listOf(
            demand("D1", "FG", "L1", qty = 60.0),
            demand("D2", "FG", "L1", qty = 60.0),
        )
        val tree1 = demandNode("D1", "FG", "L1", qty = 60.0, committedQty = 60.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 60.0)))
        val tree2 = demandNode("D2", "FG", "L1", qty = 60.0, committedQty = 60.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 60.0)))
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),  // Only 100, but D1+D2 want 120
        )
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree1), pegEntry("D2", tree2)),
            demands = demands,
            data = data,
        )
        // Each demand's per-demand check (R7a) passes (60 ≤ 100).
        report.demands[0].sound shouldBe true
        report.demands[1].sound shouldBe true
        // But the cross-demand check catches the total over-consumption.
        report.crossDemandViolations.map { it.rule } shouldContain "R7b_supply_overconsumption"
        report.overallSound shouldBe false
    }

    test("R7d_orphan_leaf_under_blocked_wo: zero-qty WO with non-zero leaves under it") {
        // Synthetic legacy run: demand committed 0 of FG (R0_committed_consistency
        // skipped here — we don't pass committed_demands), but the placeholder WO
        // beneath it has a real supply leaf claiming consumption. R7d catches
        // this orphan pattern by name.
        val demands = listOf(demand("D1", "FG", "L1", qty = 100.0))
        val tree = demandNode(
            "D1", "FG", "L1", qty = 100.0, committedQty = 0.0,
            children = listOf(
                makeWO(
                    "FG", "L1", qty = 0.0,                 // zero-qty placeholder WO
                    children = listOf(
                        // demand-child for component A (orphan path)
                        mapOf(
                            "type" to "demand",
                            "demand_id" to "D1",
                            "product_id" to "A",
                            "location_id" to "L1",
                            "quantity" to 100.0,
                            "committed_qty" to 100.0,
                            "children" to listOf(supplyLeaf("A", "L1", "SUP_A", 100.0)),
                        ),
                    ),
                ),
            ),
        )
        val data = mapOf("supply" to listOf(supply("SUP_A", "A", "L1", 100.0)))
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        // Among the violations on D1 there must be R7d. (R4 may also fire — that's
        // fine; R7d is the named-pattern surface alongside the structural R4.)
        report.demands[0].violations.map { it.rule } shouldContain "R7d_orphan_leaf_under_blocked_wo"
    }

    // ── Aggregation report semantics ──────────────────────────────────────────

    test("report aggregation: mixed sound/unsound demands") {
        val demands = listOf(
            demand("D1", "FG", "L1", qty = 10.0),
            demand("D2", "FG", "L1", qty = 10.0),
        )
        val goodTree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 10.0)))
        val badTree = demandNode("D2", "WRONG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(supplyLeaf("WRONG", "L1", "S1", 10.0)))  // R1 violation
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
        )
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", goodTree), pegEntry("D2", badTree)),
            demands = demands,
            data = data,
        )
        report.demandCount shouldBe 2
        report.soundCount shouldBe 1
        report.overallSound shouldBe false
    }

    test("report deepCheck flag is propagated") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 10.0)))
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 100.0)),
        )
        val deepReport = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands,
            data = data,
            config = SoundnessConfig(deepCheck = true),
        )
        deepReport.deepCheck shouldBe true

        val shallowReport = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands,
            data = data,
            config = SoundnessConfig(deepCheck = false),
        )
        shallowReport.deepCheck shouldBe false
    }

    test("violation contains useful context fields") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 100.0))
        val tree = demandNode("D1", "FG", "L1", qty = 100.0, committedQty = 100.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 100.0)))
        val data = mapOf(
            "supply" to listOf(supply("S1", "FG", "L1", 50.0)),
        )
        val report = checkRunSoundness(planningPegging = listOf(pegEntry("D1", tree)), demands = demands, data = data)
        val v = report.demands[0].violations.first { it.rule == "R7a_supply_qty_exceeded" }
        v.expected shouldBe 50.0
        v.actual shouldBe 100.0
        v.message stringShouldContain "supply.qty"
    }

    // ── R7c — synthetic-bucket cap tied to consolidator's allocation ──────────
    //
    // Regression for the WO-production-based cap that false-positived on every
    // chat-driven plan with purchase=on. The cap is the consolidator's
    // allocation at (pid|lid), read from the consolidator-emitted pegging
    // entries (consolidated=true / passthrough=true), NOT from work_orders.
    //
    // Helper: build a consolidator-emitted entry. Marked consolidated=true
    // means a multi-demand group's pegging tree; the root.committed_qty
    // equals producedQty for that group at its (pid|lid).
    fun consolidatorPegEntry(
        pid: String,
        lid: String,
        committedQty: Double,
        children: List<Map<String, Any?>> = emptyList(),
    ) = mapOf(
        "demand_id" to null,
        "consolidated" to true,
        "tree" to mapOf(
            "type" to "demand",
            "product_id" to pid,
            "location_id" to lid,
            "quantity" to committedQty,
            "committed_qty" to committedQty,
            "demand_id" to null,
            "request_time" to "2024-11-01",
            "commit_time" to "2024-11-01",
            "children" to children,
        ),
    )

    test("R7c sound: bucket consumption ≤ consolidator allocation, no WOs at component (inventory-backed bucket)") {
        // Two demands consuming from a synthetic bucket sized 100. The bucket
        // was filled by the consolidator drawing real inventory at FG@L1
        // (not from new WOs at FG|L1) — i.e. NO purchase or make WOs at
        // FG|L1 in the workOrders list. Pre-fix this would false-positive R7c
        // because woQtyByComponent[FG|L1] = 0.
        val demands = listOf(
            demand("D1", "FG", "L1", qty = 60.0),
            demand("D2", "FG", "L1", qty = 40.0),
        )
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S_FG", "FG", "L1", 200.0)),
        )
        val d1 = demandNode("D1", "FG", "L1", 60.0, 60.0,
            children = listOf(supplyLeaf("FG", "L1", "consolidated_FG_L1", 60.0)))
        val d2 = demandNode("D2", "FG", "L1", 40.0, 40.0,
            children = listOf(supplyLeaf("FG", "L1", "consolidated_FG_L1", 40.0)))
        val cons = consolidatorPegEntry("FG", "L1", 100.0,
            children = listOf(supplyLeaf("FG", "L1", "S_FG", 100.0)))
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", d1), pegEntry("D2", d2), cons),
            demands = demands, data = data,
            workOrders = emptyList(),  // no WOs — bucket is inventory-backed
        )
        report.crossDemandViolations.none { it.rule == "R7c_consolidated_overconsumption" } shouldBe true
    }

    test("R7c fires when bucket consumption exceeds consolidator allocation") {
        // Consolidator allocated 100 at FG|L1, but demand pegging trees claim
        // 150 from the synthetic bucket. That's an over-draw bug.
        val demands = listOf(
            demand("D1", "FG", "L1", qty = 100.0),
            demand("D2", "FG", "L1", qty = 50.0),
        )
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S_FG", "FG", "L1", 200.0)),
        )
        val d1 = demandNode("D1", "FG", "L1", 100.0, 100.0,
            children = listOf(supplyLeaf("FG", "L1", "consolidated_FG_L1", 100.0)))
        val d2 = demandNode("D2", "FG", "L1", 50.0, 50.0,
            children = listOf(supplyLeaf("FG", "L1", "consolidated_FG_L1", 50.0)))
        val cons = consolidatorPegEntry("FG", "L1", 100.0,
            children = listOf(supplyLeaf("FG", "L1", "S_FG", 100.0)))
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", d1), pegEntry("D2", d2), cons),
            demands = demands, data = data,
        )
        val v = report.crossDemandViolations.first { it.rule == "R7c_consolidated_overconsumption" }
        v.expected shouldBe 100.0
        v.actual shouldBe 150.0
        v.message stringShouldContain "consolidator's allocation"
    }

    // ── R11: wo_group_id orphan check ────────────────────────────────────────────

    test("R11: orphan gid — WO node in tree has no lot in work_orders") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(
                makeWO("FG", "L1", qty = 10.0).toMutableMap().also { it["wo_group_id"] = "WOG-1" }
            ))
        val data = mapOf("method_make" to listOf(mk("FG", "L1")), "bom" to emptyList<Map<String, Any?>>())
        // work_orders list doesn't contain WOG-1
        val workOrders = listOf(
            mapOf("product_id" to "FG", "location_id" to "L1", "quantity" to 10.0,
                "start_time" to "2024-11-01", "end_time" to "2024-11-01", "wo_group_id" to "WOG-OTHER")
        )
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands, data = data, workOrders = workOrders,
        )
        report.woGidOrphanViolations.map { it.rule } shouldContain "R11_wo_gid_orphan"
        report.overallSound shouldBe false
    }

    test("R11: no violation when all tree gids have lots in work_orders") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(
                makeWO("FG", "L1", qty = 10.0).toMutableMap().also { it["wo_group_id"] = "WOG-1" }
            ))
        val data = mapOf("method_make" to listOf(mk("FG", "L1")), "bom" to emptyList<Map<String, Any?>>())
        val workOrders = listOf(
            mapOf("product_id" to "FG", "location_id" to "L1", "quantity" to 10.0,
                "start_time" to "2024-11-01", "end_time" to "2024-11-01", "wo_group_id" to "WOG-1")
        )
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands, data = data, workOrders = workOrders,
        )
        report.woGidOrphanViolations shouldHaveSize 0
    }

    test("R11: skipped when work_orders is empty") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(
                makeWO("FG", "L1", qty = 10.0).toMutableMap().also { it["wo_group_id"] = "WOG-ORPHAN" }
            ))
        val data = mapOf("method_make" to listOf(mk("FG", "L1")), "bom" to emptyList<Map<String, Any?>>())
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands, data = data, workOrders = emptyList(),
        )
        report.woGidOrphanViolations shouldHaveSize 0
    }

    // ── R12: cross-tree WO temporal ordering ─────────────────────────────────────

    test("R12: violation when parent WO starts before child WO ends") {
        // Two-level tree: FG(WOG-P) depends on COMP(WOG-C).
        // workOrders: COMP ends Sep-07, FG starts Aug-20 → violation.
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(
                makeWO("FG", "L1", qty = 10.0,
                    children = listOf(
                        demandNode("D1", "COMP", "L1", qty = 10.0, committedQty = 10.0,
                            commitTime = "2024-09-07",
                            children = listOf(
                                makeWO("COMP", "L1", qty = 10.0).toMutableMap().also { it["wo_group_id"] = "WOG-C" }
                            ))
                    )
                ).toMutableMap().also { it["wo_group_id"] = "WOG-P" }
            ))
        val data = mapOf(
            "method_make" to listOf(mk("FG", "L1"), mk("COMP", "L1")),
            "bom" to listOf(bom("FG", "COMP", rate = 1.0)),
        )
        val workOrders = listOf(
            mapOf("wo_group_id" to "WOG-P", "product_id" to "FG",   "location_id" to "L1",
                "start_time" to "2024-08-20", "end_time" to "2024-09-01", "quantity" to 10.0),
            mapOf("wo_group_id" to "WOG-C", "product_id" to "COMP", "location_id" to "L1",
                "start_time" to "2024-08-15", "end_time" to "2024-09-07", "quantity" to 10.0),
        )
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands, data = data, workOrders = workOrders,
            config = SoundnessConfig(timeToleranceDays = 1L),
        )
        report.crossTreeTimingViolations.map { it.rule } shouldContain "R12_cross_tree_wo_timing"
        report.overallSound shouldBe false
    }

    test("R12: no violation when parent starts after child ends") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(
                makeWO("FG", "L1", qty = 10.0,
                    children = listOf(
                        demandNode("D1", "COMP", "L1", qty = 10.0, committedQty = 10.0,
                            commitTime = "2024-09-07",
                            children = listOf(
                                makeWO("COMP", "L1", qty = 10.0).toMutableMap().also { it["wo_group_id"] = "WOG-C" }
                            ))
                    )
                ).toMutableMap().also { it["wo_group_id"] = "WOG-P" }
            ))
        val data = mapOf(
            "method_make" to listOf(mk("FG", "L1"), mk("COMP", "L1")),
            "bom" to listOf(bom("FG", "COMP", rate = 1.0)),
        )
        val workOrders = listOf(
            mapOf("wo_group_id" to "WOG-P", "product_id" to "FG",   "location_id" to "L1",
                "start_time" to "2024-09-08", "end_time" to "2024-09-15", "quantity" to 10.0),
            mapOf("wo_group_id" to "WOG-C", "product_id" to "COMP", "location_id" to "L1",
                "start_time" to "2024-08-15", "end_time" to "2024-09-07", "quantity" to 10.0),
        )
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands, data = data, workOrders = workOrders,
            config = SoundnessConfig(timeToleranceDays = 1L),
        )
        report.crossTreeTimingViolations shouldHaveSize 0
    }

    test("R12: skipped when work_orders is empty") {
        val demands = listOf(demand("D1", "FG", "L1", qty = 10.0))
        val tree = demandNode("D1", "FG", "L1", qty = 10.0, committedQty = 10.0,
            children = listOf(supplyLeaf("FG", "L1", "S1", 10.0)))
        val data = mapOf("supply" to listOf(supply("S1", "FG", "L1", 100.0)))
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands, data = data, workOrders = emptyList(),
        )
        report.crossTreeTimingViolations shouldHaveSize 0
    }

    // ── R7c skipped when no consolidator entries are present ──────────────────

    test("R7c skipped when no consolidator entries are present") {
        // Pure non-consolidation run: no consolidated/passthrough entries.
        // Even if a per-demand tree happens to reference a synthetic bucket,
        // R7c can't establish a cap → silent (no violation, no crash).
        val demands = listOf(demand("D1", "FG", "L1", qty = 50.0))
        val data = mapOf<String, List<Map<String, Any?>>>(
            "supply" to listOf(supply("S_FG", "FG", "L1", 100.0)),
        )
        val tree = demandNode("D1", "FG", "L1", 50.0, 50.0,
            children = listOf(supplyLeaf("FG", "L1", "consolidated_FG_L1", 50.0)))
        val report = checkRunSoundness(
            planningPegging = listOf(pegEntry("D1", tree)),
            demands = demands, data = data,
        )
        report.crossDemandViolations.none { it.rule == "R7c_consolidated_overconsumption" } shouldBe true
    }
})
