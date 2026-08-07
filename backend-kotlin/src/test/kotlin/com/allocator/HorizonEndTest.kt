package com.allocator

import com.allocator.services.computePlanningHorizonEnd
import com.allocator.services.resolveHorizonEnd
import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Horizon end: the mirror-image ceiling to [com.allocator.services.resolveHorizonStart]'s floor.
 * No work order may be scheduled to START after it — enforced as an outright rejection (qty 0,
 * no BOM-child recursion, so nothing is ever consumed for an attempt that structurally can't be
 * scheduled in time) in [com.allocator.services.planMethodSlot], since — unlike horizon_start —
 * there's no way to "clamp" a late start earlier without missing the demand's own need.
 */
class HorizonEndTest : FunSpec({

    // ── Pure resolution functions ────────────────────────────────────────────

    fun demandDue(date: String) = mapOf<String, Any?>("demand_id" to "D", "request_due_time" to date)

    test("computePlanningHorizonEnd: last day of the month of the LATEST demand due date") {
        val demands = listOf(demandDue("2026-06-15"), demandDue("2026-08-03"), demandDue("2026-07-01"))
        computePlanningHorizonEnd(demands) shouldBe LocalDate.of(2026, 8, 31)
    }

    test("computePlanningHorizonEnd: null when no demand has a parseable date") {
        computePlanningHorizonEnd(listOf(mapOf("demand_id" to "D"))) shouldBe null
    }

    test("resolveHorizonEnd: absent/blank/'auto' all fall back to computePlanningHorizonEnd") {
        val demands = listOf(demandDue("2026-06-15"))
        val expected = LocalDate.of(2026, 6, 30)
        resolveHorizonEnd(null, demands) shouldBe expected
        resolveHorizonEnd(mapOf("method_selection" to mapOf("horizon_end" to "")), demands) shouldBe expected
        resolveHorizonEnd(mapOf("method_selection" to mapOf("horizon_end" to "auto")), demands) shouldBe expected
    }

    test("resolveHorizonEnd: explicit override is used literally, not re-floored to month end") {
        val demands = listOf(demandDue("2026-06-15"))
        resolveHorizonEnd(mapOf("method_selection" to mapOf("horizon_end" to "2026-07-10")), demands) shouldBe
            LocalDate.of(2026, 7, 10)
    }

    test("resolveHorizonEnd: unparseable override falls back to auto rather than disabling the ceiling") {
        val demands = listOf(demandDue("2026-06-15"))
        resolveHorizonEnd(mapOf("method_selection" to mapOf("horizon_end" to "not-a-date")), demands) shouldBe
            LocalDate.of(2026, 6, 30)
    }

    // ── End-to-end enforcement via runPlanning ───────────────────────────────

    fun bom(bomId: String, parent: String, child: String) =
        mapOf<String, Any?>("bom_id" to bomId, "parent_id" to parent, "child_id" to child, "alt_group" to null, "rate" to 1.0)

    // 40-day lead time: a demand due 2026-06-30 needs its WO to START 2026-05-21.
    fun mkData(dueDate: String, leadTime: Double) = mapOf(
        "method_make" to listOf(mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to leadTime)),
        "method_buy" to emptyList<Map<String, Any?>>(),
        "method_move" to emptyList<Map<String, Any?>>(),
        "bom" to listOf(bom("B", "P", "C")),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "demand" to listOf(mapOf<String, Any?>("demand_id" to "D1", "product_id" to "P", "location_id" to "L", "quantity" to 20.0, "request_due_time" to dueDate)),
        // Undated (blank supply_date) — always available, sidestepping the UNRELATED horizon_start
        // FIFO-pool floor (a dated lot before auto horizon_start would be excluded regardless of
        // horizon_end; see HorizonSupplyFloorTest) so this fixture isolates horizon_end only.
        "supply" to listOf(mapOf<String, Any?>("product_id" to "C", "location_id" to "L", "qty" to 100.0, "supply_id" to "S_C", "supply_date" to null)),
        "overrides" to emptyList<Map<String, Any?>>(),
    )

    @Suppress("UNCHECKED_CAST")
    fun committedQty(result: com.allocator.services.RunPlanningResult): Double =
        (result.output["committed_demands"] as List<Map<String, Any?>>)
            .filter { it["demand_id"] == "D1" && it["commit_reason"] != "no_methods" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

    test("a demand whose required start falls after horizon_end is rejected outright (0 committed)") {
        val data = mkData(dueDate = "2026-06-30", leadTime = 40.0)   // needs to start 2026-05-21
        val config = mapOf<String, Any?>(
            "purchase_allowed" to false,
            "method_selection" to mapOf("horizon_end" to "2026-05-15"),   // before the required start
        )
        val result = runPlanning(data, config)
        committedQty(result) shouldBe (0.0 plusOrMinus 1e-6)
    }

    test("a demand whose required start is at/before horizon_end is unaffected (baseline)") {
        val data = mkData(dueDate = "2026-06-30", leadTime = 40.0)   // needs to start 2026-05-21
        val config = mapOf<String, Any?>(
            "purchase_allowed" to false,
            "method_selection" to mapOf("horizon_end" to "2026-05-25"),   // after the required start
        )
        val result = runPlanning(data, config)
        committedQty(result) shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("no horizon_end configured (null) never rejects anything, same as today") {
        val data = mkData(dueDate = "2026-06-30", leadTime = 400.0)   // absurdly early required start
        val config = mapOf<String, Any?>("purchase_allowed" to false)
        val result = runPlanning(data, config)
        committedQty(result) shouldBe (20.0 plusOrMinus 1e-6)
    }
})
