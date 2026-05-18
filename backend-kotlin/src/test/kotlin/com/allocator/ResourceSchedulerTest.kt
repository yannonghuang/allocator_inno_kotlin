package com.allocator

import com.allocator.services.ResourceCalendar
import com.allocator.services.ResourceScheduler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Phase-1 cross-WO arbitration tests. ResourceCalendar covers the low-level
 * "can I fit this interval?" mechanics; ResourceScheduler.arbitrate covers
 * the WO-orchestration: priority order, push-when-busy, and the
 * push-no-matter-what semantics for contention starvation.
 */
class ResourceSchedulerTest : FunSpec({

    // ── ResourceCalendar primitives ──────────────────────────────────────────

    test("empty calendar fits anything <= size") {
        val cal = ResourceCalendar(size = 10.0)
        cal.canFit(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), 7.0) shouldBe true
        cal.canFit(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), 10.0) shouldBe true
        cal.canFit(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), 10.5) shouldBe false
    }

    test("reservation reduces remaining capacity on overlapped days") {
        val cal = ResourceCalendar(size = 10.0)
        cal.reserve(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), 7.0)
        // Day inside the reservation: 7 used, 3 free; need 4 → no fit.
        cal.canFit(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-04"), 4.0) shouldBe false
        cal.canFit(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-04"), 3.0) shouldBe true
        // Day after end is free.
        cal.canFit(LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-07"), 10.0) shouldBe true
    }

    test("end is exclusive — d..d+1 occupies exactly day d") {
        val cal = ResourceCalendar(size = 5.0)
        cal.reserve(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), 5.0)
        // The next day is fully free.
        cal.canFit(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-03"), 5.0) shouldBe true
        // Day 1 itself is full.
        cal.canFit(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), 1.0) shouldBe false
    }

    test("multiple overlapping reservations sum") {
        val cal = ResourceCalendar(size = 10.0)
        cal.reserve(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-04"), 3.0)
        cal.reserve(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-05"), 4.0)
        // Day 2: 3 + 4 = 7 used, 3 free.
        cal.usageOn(LocalDate.parse("2026-01-02")) shouldBe 7.0
        cal.canFit(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-03"), 3.0) shouldBe true
        cal.canFit(LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-03"), 4.0) shouldBe false
    }

    // ── ResourceScheduler.arbitrate ──────────────────────────────────────────

    /** Minimal dataset: ob-machine2 at location 2000, rate 2, size 5 → cap = 2. */
    fun obDataset(): Map<String, List<Map<String, Any?>>> = mapOf(
        "productlocation" to listOf(
            mapOf("product_id" to "P1", "location_id" to "2000", "prod_area" to "OB"),
            mapOf("product_id" to "P2", "location_id" to "2000", "prod_area" to "OB"),
        ),
        "operation" to listOf(
            mapOf(
                "operation_id" to "ob-op", "prod_area" to "OB",
                "uph" to 100.0, "yield_factor" to 0.98, "bor_id" to "ob-bor",
                "pre_process_time" to 0, "process_time" to 0, "post_process_time" to 0,
            ),
        ),
        "bor" to listOf(
            mapOf("bor_id" to "ob-bor", "resource_id" to "ob-machine2", "resource_rate" to 2.0),
        ),
        "resource" to listOf(
            mapOf("resource_id" to "ob-machine2", "location_id" to "2000", "size" to 5.0),
        ),
    )

    /** Build a lot row. The arbitrate function only reads
     *  wo_group_id / method / product_id / location_id / demand_id /
     *  start_time / end_time and writes start_time / end_time. */
    fun lot(gid: String, demandId: String, product: String, start: String, end: String) = mutableMapOf<String, Any?>(
        "wo_group_id" to gid,
        "demand_id" to demandId,
        "product_id" to product,
        "location_id" to "2000",
        "method" to "make",
        "start_time" to start,
        "end_time" to end,
    )

    test("single WO with no contention is not pushed") {
        val data = obDataset()
        val lots = listOf(lot("g1", "d1", "P1", "2026-01-01", "2026-01-02"))
        val pushed = ResourceScheduler.arbitrate(lots, data)
        pushed shouldBe 0
        lots[0]["start_time"] shouldBe "2026-01-01"
    }

    test("two concurrent WOs that each need full cap serialize — second is pushed") {
        // ob-machine2 cap = floor(5/2) = 2. One WO of 2 lots in a single wave
        // consumes 2 × 2 = 4 of 5 slots. Two such WOs together would consume
        // 8 > 5, so the second WO gets pushed past the first's end.
        val data = obDataset()
        val wo1Lots = listOf(
            lot("g1", "d1", "P1", "2026-01-01", "2026-01-02"),
            lot("g1", "d1", "P1", "2026-01-01", "2026-01-02"),
        )
        val wo2Lots = listOf(
            lot("g2", "d2", "P2", "2026-01-01", "2026-01-02"),
            lot("g2", "d2", "P2", "2026-01-01", "2026-01-02"),
        )
        // Priority and due-date all equal → tiebreak by original start; both
        // start on the same day → tiebreak by gid (deterministic insertion order).
        // Both WOs need cap × rate = 4 slots; together would be 8 > 5.
        // First WO reserves; second WO finds no fit on day 1, steps to day 2.
        val pushed = ResourceScheduler.arbitrate(wo1Lots + wo2Lots, data)
        pushed shouldBe 1
        wo1Lots.all { it["start_time"] == "2026-01-01" } shouldBe true
        wo2Lots.all { it["start_time"] == "2026-01-02" } shouldBe true
        wo2Lots.all { it["end_time"] == "2026-01-03" } shouldBe true
    }

    test("priority order: higher-priority demand wins contention") {
        val data = obDataset()
        // d-low at the front of the input list, but priority is lower.
        val lowLots = listOf(
            lot("g-low", "d-low", "P1", "2026-01-01", "2026-01-02"),
            lot("g-low", "d-low", "P1", "2026-01-01", "2026-01-02"),
        )
        val highLots = listOf(
            lot("g-high", "d-high", "P2", "2026-01-01", "2026-01-02"),
            lot("g-high", "d-high", "P2", "2026-01-01", "2026-01-02"),
        )
        val priorities = mapOf("d-high" to 10, "d-low" to 1)
        val pushed = ResourceScheduler.arbitrate(lowLots + highLots, data, priorities)
        pushed shouldBe 1
        // High-priority WO keeps its original date; low-priority is pushed.
        highLots.all { it["start_time"] == "2026-01-01" } shouldBe true
        lowLots.all { it["start_time"] == "2026-01-02" } shouldBe true
    }

    test("WO with no operation override is skipped (parallelismCap = 0)") {
        // Drop the productlocation row so override doesn't apply; arbitrate
        // should leave the WO untouched.
        val data = obDataset().toMutableMap().apply {
            this["productlocation"] = emptyList()
        }
        val lots = listOf(
            lot("g1", "d1", "P1", "2026-01-01", "2026-01-02"),
            lot("g1", "d1", "P1", "2026-01-01", "2026-01-02"),
        )
        // Even with another "WO" stacked on top, no arbitration happens —
        // these aren't tracked WOs from the scheduler's POV.
        val pushed = ResourceScheduler.arbitrate(lots, data)
        pushed shouldBe 0
        lots[0]["start_time"] shouldBe "2026-01-01"
    }

    test("non-make WOs (move / purchase) are not arbitrated") {
        val data = obDataset()
        val moveLot = mutableMapOf<String, Any?>(
            "wo_group_id" to "g1",
            "demand_id" to "d1",
            "product_id" to "P1",
            "location_id" to "2000",
            "method" to "move",
            "start_time" to "2026-01-01",
            "end_time" to "2026-01-02",
        )
        val makeLots = listOf(
            lot("g2", "d2", "P2", "2026-01-01", "2026-01-02"),
            lot("g2", "d2", "P2", "2026-01-01", "2026-01-02"),
        )
        // The move WO doesn't reserve resources, so the make WO sees an
        // empty calendar and stays put.
        val pushed = ResourceScheduler.arbitrate(listOf(moveLot) + makeLots, data)
        pushed shouldBe 0
        moveLot["start_time"] shouldBe "2026-01-01"
        makeLots.all { it["start_time"] == "2026-01-01" } shouldBe true
    }
})
