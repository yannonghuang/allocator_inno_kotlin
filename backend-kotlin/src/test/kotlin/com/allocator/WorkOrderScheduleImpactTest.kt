package com.allocator

import com.allocator.api.bucketOf
import com.allocator.api.bucketStartOf
import com.allocator.services.resequenceFromPegging
import io.kotest.core.spec.style.FunSpec
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
})
