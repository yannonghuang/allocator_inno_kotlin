package com.allocator

import com.allocator.api.WoScheduleSelector
import com.allocator.api.bucketOf
import com.allocator.api.bucketStartOf
import com.allocator.api.computeAvailability
import com.allocator.services.resequenceFromPegging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import java.time.LocalDate

/**
 * Tests the WO schedule-impact pipeline: bucket helpers + the sequencing-only
 * resequence path that propagates a manual WO shift up the DAG.
 *
 * The full HTTP route is tested manually end-to-end (per the plan); this file
 * covers the building blocks that don't require Ktor + DB setup.
 */
class WorkOrderScheduleImpactTest : FunSpec({

    // ── bucket helpers ────────────────────────────────────────────────────────

    test("bucketOf / bucketStartOf — day granularity round-trips") {
        val d = LocalDate.of(2026, 5, 9)
        bucketOf(d, "day") shouldBe "2026-05-09"
        bucketStartOf("2026-05-09", "day") shouldBe d
    }

    test("bucketOf / bucketStartOf — month granularity") {
        val d = LocalDate.of(2026, 5, 9)
        bucketOf(d, "month") shouldBe "2026-05"
        bucketStartOf("2026-05", "month") shouldBe LocalDate.of(2026, 5, 1)
    }

    test("bucketOf / bucketStartOf — quarter granularity (Q2 starts April)") {
        val q1 = LocalDate.of(2026, 2, 14)
        val q2 = LocalDate.of(2026, 5, 9)
        val q4 = LocalDate.of(2026, 11, 30)
        bucketOf(q1, "quarter") shouldBe "2026-Q1"
        bucketOf(q2, "quarter") shouldBe "2026-Q2"
        bucketOf(q4, "quarter") shouldBe "2026-Q4"
        bucketStartOf("2026-Q2", "quarter") shouldBe LocalDate.of(2026, 4, 1)
        bucketStartOf("2026-Q1", "quarter") shouldBe LocalDate.of(2026, 1, 1)
    }

    test("bucketOf / bucketStartOf — ISO week granularity") {
        // 2026-05-04 is a Monday — ISO week 19 of week-based-year 2026.
        val d = LocalDate.of(2026, 5, 4)
        bucketOf(d, "week") shouldBe "2026-W19"
        // bucketStartOf returns the Monday of the week.
        bucketStartOf("2026-W19", "week") shouldBe LocalDate.of(2026, 5, 4)
    }

    // ── resequence: manual shift propagates up the DAG ────────────────────────

    /** Build a synthetic 3-level pegging fixture:
     *
     *   D_FG  →  WO_FG (FG@L1, gid=fg)
     *              └─ D_SUB →  WO_SUB (SUB@L1, gid=sub)
     *                              └─ Supply RAW (commit_time fixed at 2026-01-01)
     *
     * Demand commit_time = WO_FG.end_time. WO_FG.start_time ≥ WO_SUB.end_time.
     */
    fun buildFixture(): Pair<MutableList<MutableMap<String, Any?>>, MutableList<MutableMap<String, Any?>>> {
        val woSub = mutableMapOf<String, Any?>(
            "product_id" to "SUB", "location_id" to "L1",
            "quantity" to 10.0, "method" to "make",
            "start_time" to "2026-03-01", "end_time" to "2026-03-10",
            "demand_id" to "D_FG", "prod_area" to "ASSY",
            "override_active" to false, "wo_group_id" to "sub",
        )
        val woFg = mutableMapOf<String, Any?>(
            "product_id" to "FG", "location_id" to "L1",
            "quantity" to 10.0, "method" to "make",
            "start_time" to "2026-03-11", "end_time" to "2026-03-20",
            "demand_id" to "D_FG", "prod_area" to "FINISH",
            "override_active" to false, "wo_group_id" to "fg",
        )
        val workOrders: MutableList<MutableMap<String, Any?>> = mutableListOf(woSub, woFg)

        // Pegging tree: D_FG → WO_FG → D_SUB → WO_SUB → supply
        val supplyNode = mapOf<String, Any?>(
            "type" to "supply",
            "supply_id" to "S_RAW",
            "commit_time" to "2026-01-01",
            "quantity" to 10.0,
        )
        val subWoNode = mapOf<String, Any?>(
            "type" to "work_order",
            "wo_group_id" to "sub",
            "product_id" to "SUB", "location_id" to "L1", "method" to "make",
            "start_time" to "2026-03-01", "end_time" to "2026-03-10",
            "failed" to false,
            "children" to listOf(supplyNode),
        )
        val subDemandNode = mapOf<String, Any?>(
            "type" to "demand",
            "commit_time" to "2026-03-10",
            "children" to listOf(subWoNode),
        )
        val fgWoNode = mapOf<String, Any?>(
            "type" to "work_order",
            "wo_group_id" to "fg",
            "product_id" to "FG", "location_id" to "L1", "method" to "make",
            "start_time" to "2026-03-11", "end_time" to "2026-03-20",
            "failed" to false,
            "children" to listOf(subDemandNode),
        )
        val fgRoot = mapOf<String, Any?>(
            "type" to "demand",
            "commit_time" to "2026-03-20",
            "children" to listOf(fgWoNode),
        )
        val pegging: MutableList<MutableMap<String, Any?>> = mutableListOf(
            mutableMapOf<String, Any?>("demand_id" to "D_FG", "tree" to fgRoot),
        )
        return workOrders to pegging
    }

    test("resequenceFromPegging: shifting a child WO pushes the parent forward") {
        val (workOrders, pegging) = buildFixture()
        // Manual delay: SUB shifts +14 days (end becomes 2026-03-24).
        val woSub = workOrders.first { it["wo_group_id"] == "sub" }
        woSub["start_time"] = "2026-03-15"
        woSub["end_time"] = "2026-03-24"
        // Mirror to tree node so the DAG walk sees consistent timings.
        @Suppress("UNCHECKED_CAST")
        fun stamp(node: Map<String, Any?>, gid: String, start: String, end: String): Map<String, Any?> {
            val updated = node.toMutableMap()
            if (node["type"] == "work_order" && node["wo_group_id"] == gid) {
                updated["start_time"] = start
                updated["end_time"] = end
            }
            val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
            if (children.isNotEmpty()) {
                updated["children"] = children.map { stamp(it, gid, start, end) }
            }
            return updated
        }
        val mutatedTrees = pegging.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val tree = entry["tree"] as Map<String, Any?>
            entry.toMutableMap().apply { put("tree", stamp(tree, "sub", "2026-03-15", "2026-03-24")) }
        }

        val result = resequenceFromPegging(workOrders, mutatedTrees)

        // FG WO must have start ≥ SUB end (= 2026-03-24).
        val fgLot = result.workOrders.first { it["wo_group_id"] == "fg" }
        val fgStart = LocalDate.parse(fgLot["start_time"] as String)
        fgStart shouldBeGreaterThanOrEqualTo LocalDate.of(2026, 3, 24)

        // FG WO span preserved: original 9-day duration.
        val fgEnd = LocalDate.parse(fgLot["end_time"] as String)
        (fgEnd.toEpochDay() - fgStart.toEpochDay()) shouldBe 9L

        // Demand-root commit_time picked up from FG WO end.
        @Suppress("UNCHECKED_CAST")
        val rootTree = result.peggingTrees[0]["tree"] as Map<String, Any?>
        val rootCommit = LocalDate.parse(rootTree["commit_time"] as String)
        rootCommit shouldBeGreaterThanOrEqualTo LocalDate.of(2026, 3, 24)
    }

    test("resequenceFromPegging: leaf supply commit_time pre-shifts the parent WO") {
        // SUB starts 2026-03-01 but its leaf supply commit_time is 2026-03-15:
        // resequence's leaf-time pre-shift should move SUB to 2026-03-15.
        val (workOrders, pegging) = buildFixture()
        // Move the supply commit_time forward.
        @Suppress("UNCHECKED_CAST")
        fun bumpSupply(node: Map<String, Any?>, newCommit: String): Map<String, Any?> {
            val updated = node.toMutableMap()
            if (node["type"] == "supply") updated["commit_time"] = newCommit
            val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()
            if (children.isNotEmpty()) {
                updated["children"] = children.map { bumpSupply(it, newCommit) }
            }
            return updated
        }
        val mutatedTrees = pegging.map { entry ->
            @Suppress("UNCHECKED_CAST")
            val tree = entry["tree"] as Map<String, Any?>
            entry.toMutableMap().apply { put("tree", bumpSupply(tree, "2026-03-15")) }
        }

        val result = resequenceFromPegging(workOrders, mutatedTrees)

        val subLot = result.workOrders.first { it["wo_group_id"] == "sub" }
        LocalDate.parse(subLot["start_time"] as String) shouldBeGreaterThanOrEqualTo LocalDate.of(2026, 3, 15)
        val fgLot = result.workOrders.first { it["wo_group_id"] == "fg" }
        // FG must have moved to ≥ SUB.end which itself is now ≥ 2026-03-24 (start 03-15 + 9d span).
        LocalDate.parse(fgLot["start_time"] as String) shouldBeGreaterThanOrEqualTo LocalDate.of(2026, 3, 24)
    }

    // ── availability: closed-form max-safe-delay ──────────────────────────────

    test("availability — boundary slack: SUB→FG, baseline slack = 1 day") {
        val (workOrders, pegging) = buildFixture()
        val sel = WoScheduleSelector(
            bucketStart = "2026-03-01",
            bucketEnd = "2026-03-10",
            woGroupIds = listOf("sub"),
        )
        val result = computeAvailability(workOrders, pegging, listOf(sel))

        // SUB.start (2026-03-01) = bucketStart → absorption = 0.
        // SUB.end = 2026-03-10, FG.start = 2026-03-11 → baseline slack = 1.
        // maxFeasibleDays = 1 + 0 = 1.
        result.maxFeasibleDays shouldBe 1
        result.bottlenecks.shouldNotBeEmpty()
        result.bottlenecks.first().kind shouldBe "boundary"
        result.bottlenecks.first().gid shouldBe "sub"
        result.bottlenecks.first().parentGid shouldBe "fg"
    }

    test("availability — window absorption grows max-N when WO is deeper inside the bucket") {
        // Same fixture, but pretend SUB.start = 2026-03-06 (5 days into bucket starting 2026-03-01).
        // baseline slack stays at 1 (we'd shift SUB.end too — but we keep the window's bucketStart at 03-01).
        val (workOrders, pegging) = buildFixture()
        // Mutate the in-memory copy: shift SUB by 5 days forward (start 03-06, end 03-15) AND
        // shift FG to maintain slack=1 (start 03-16, end 03-25).
        for (lot in workOrders) {
            when (lot["wo_group_id"]) {
                "sub" -> { lot["start_time"] = "2026-03-06"; lot["end_time"] = "2026-03-15" }
                "fg"  -> { lot["start_time"] = "2026-03-16"; lot["end_time"] = "2026-03-25" }
            }
        }
        // Tree mirrors the lots — but the closed-form reads from lots only, so tree mismatch
        // doesn't affect the math (parentsOf still derived from tree structure).

        val sel = WoScheduleSelector(
            bucketStart = "2026-03-01",
            bucketEnd = "2026-03-15",
            woGroupIds = listOf("sub"),
        )
        val result = computeAvailability(workOrders, pegging, listOf(sel))

        // absorption = SUB.start − bucketStart = 5 days. baseline slack = 1.
        // maxFeasibleDays = 1 + 5 = 6.
        result.maxFeasibleDays shouldBe 6
    }

    test("availability — demand-root: FG-in-set with no sibling => slack = 0, max = absorption only") {
        // FG is the only direct WO child of D_FG. With FG in WO_set and no sibling out-of-set,
        // MAX_END = FG.end (binding). slack = 0. absorption = FG.start − bucketStart.
        val (workOrders, pegging) = buildFixture()
        val sel = WoScheduleSelector(
            bucketStart = "2026-03-01",
            bucketEnd = "2026-03-31",
            woGroupIds = listOf("fg"),
        )
        val result = computeAvailability(workOrders, pegging, listOf(sel))

        // FG.start = 2026-03-11, bucketStart = 2026-03-01 → absorption = 10. slack = 0.
        result.maxFeasibleDays shouldBe 10
        result.bottlenecks.map { it.kind } shouldContain "demand_root"
    }

    test("availability — empty selector returns max=0") {
        val (workOrders, pegging) = buildFixture()
        val sel = WoScheduleSelector(
            bucketStart = "2026-03-01",
            bucketEnd = "2026-03-31",
            woGroupIds = emptyList(),
        )
        val result = computeAvailability(workOrders, pegging, listOf(sel))
        result.maxFeasibleDays shouldBe 0
        result.matchedWoCount shouldBe 0
    }
})
