package com.allocator

import com.allocator.services.synthesizeConsolidatedWOs
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Tests for [synthesizeConsolidatedWOs] — Phase E of supply-level
 * consolidation.
 *
 * Phase E merges per-demand WOs at the same (product_id, location_id,
 * start_time, end_time, method) into a single consolidated WO with split-
 * details. Output shape matches the leaf-engine's `consolidated:true` WO
 * format so downstream consumers don't need changes.
 */
class SupplyWoSynthesisTest : FunSpec({

    fun wo(
        pid: String,
        lid: String,
        qty: Double,
        demandId: String?,
        startTime: String = "2024-12-31",
        endTime: String = "2024-12-31",
        method: String = "make",
    ): Map<String, Any?> = mapOf(
        "product_id" to pid,
        "location_id" to lid,
        "quantity" to qty,
        "start_time" to startTime,
        "end_time" to endTime,
        "method" to method,
        "demand_id" to demandId,
        "prod_area" to null,
        "override_active" to false,
    )

    test("empty list returns empty list") {
        synthesizeConsolidatedWOs(
            perDemandWOs = emptyList(),
            demandPriorities = emptyMap(),
            mode = "fair",
        ).isEmpty() shouldBe true
    }

    test("single WO passes through unchanged") {
        val input = listOf(wo("X", "L1", 10.0, "D1"))
        val result = synthesizeConsolidatedWOs(input, mapOf<Any?, Int>("D1" to 0), "fair")
        result shouldHaveSize 1
        result[0]["product_id"] shouldBe "X"
        result[0]["quantity"] shouldBe 10.0
        result[0]["demand_id"] shouldBe "D1"
        // No consolidation marker — passthrough.
        result[0].containsKey("consolidated") shouldBe false
    }

    test("two WOs at the same (pid, lid, time, method) merge") {
        val input = listOf(
            wo("X", "L1", 10.0, "D1"),
            wo("X", "L1", 20.0, "D2"),
        )
        val result = synthesizeConsolidatedWOs(
            input,
            mapOf<Any?, Int>("D1" to 0, "D2" to 5),
            "fair",
        )
        result shouldHaveSize 1

        val merged = result[0]
        merged["product_id"] shouldBe "X"
        merged["location_id"] shouldBe "L1"
        merged["quantity"] shouldBe 30.0
        merged["demand_id"] shouldBe null  // consolidated → no single demand
        merged["consolidated"] shouldBe true
        merged["consolidation_split_mode"] shouldBe "fair"
        merged["consolidation_total_planned"] shouldBe 30.0

        @Suppress("UNCHECKED_CAST")
        val splits = merged["consolidation_split_details"] as List<Map<String, Any?>>
        splits shouldHaveSize 2
        // D1's split entry
        val d1Split = splits.find { it["demand_id"] == "D1" }!!
        (d1Split["allocated_qty"] as Number).toDouble() shouldBe (10.0 plusOrMinus 1e-9)
        d1Split["priority"] shouldBe 0
        // D2's split entry
        val d2Split = splits.find { it["demand_id"] == "D2" }!!
        (d2Split["allocated_qty"] as Number).toDouble() shouldBe (20.0 plusOrMinus 1e-9)
        d2Split["priority"] shouldBe 5
    }

    test("WOs at different (pid, lid) stay separate") {
        val input = listOf(
            wo("X", "L1", 10.0, "D1"),
            wo("Y", "L1", 20.0, "D2"),
        )
        val result = synthesizeConsolidatedWOs(input, mapOf<Any?, Int>("D1" to 0, "D2" to 0), "fair")
        result shouldHaveSize 2
        // Both passthrough — no consolidation.
        result.all { !it.containsKey("consolidated") } shouldBe true
    }

    test("WOs at same (pid, lid) but different times stay separate") {
        // Different start_time → different physical runs.
        val input = listOf(
            wo("X", "L1", 10.0, "D1", startTime = "2024-12-31", endTime = "2024-12-31"),
            wo("X", "L1", 20.0, "D2", startTime = "2025-03-15", endTime = "2025-03-15"),
        )
        val result = synthesizeConsolidatedWOs(input, mapOf<Any?, Int>("D1" to 0, "D2" to 0), "fair")
        result shouldHaveSize 2
        // Both passthrough — same (pid, lid) but different times.
        result.all { !it.containsKey("consolidated") } shouldBe true
    }

    test("WOs at same (pid, lid) but different methods stay separate") {
        // make vs move at same coordinates → different operations.
        val input = listOf(
            wo("X", "L1", 10.0, "D1", method = "make"),
            wo("X", "L1", 20.0, "D2", method = "move"),
        )
        val result = synthesizeConsolidatedWOs(input, mapOf<Any?, Int>("D1" to 0, "D2" to 0), "fair")
        result shouldHaveSize 2
        result.all { !it.containsKey("consolidated") } shouldBe true
    }

    test("three demands at same (pid, lid) merge into one with three splits") {
        val input = listOf(
            wo("X", "L1", 10.0, "D1"),
            wo("X", "L1", 15.0, "D2"),
            wo("X", "L1", 25.0, "D3"),
        )
        val result = synthesizeConsolidatedWOs(
            input,
            mapOf<Any?, Int>("D1" to 0, "D2" to 0, "D3" to 0),
            "proportional",
        )
        result shouldHaveSize 1
        result[0]["quantity"] shouldBe 50.0
        result[0]["consolidation_split_mode"] shouldBe "proportional"

        @Suppress("UNCHECKED_CAST")
        val splits = result[0]["consolidation_split_details"] as List<Map<String, Any?>>
        splits shouldHaveSize 3
        // Verify each demand's allocated_qty.
        splits.find { it["demand_id"] == "D1" }!!["allocated_qty"] shouldBe 10.0
        splits.find { it["demand_id"] == "D2" }!!["allocated_qty"] shouldBe 15.0
        splits.find { it["demand_id"] == "D3" }!!["allocated_qty"] shouldBe 25.0
    }

    test("mixed consolidated + passthrough output") {
        // Two demands share P@L1 (consolidate); one demand alone at Q@L1
        // (passthrough).
        val input = listOf(
            wo("P", "L1", 10.0, "D1"),
            wo("P", "L1", 20.0, "D2"),
            wo("Q", "L1", 5.0, "D1"),  // single-demand at Q
        )
        val result = synthesizeConsolidatedWOs(
            input,
            mapOf<Any?, Int>("D1" to 0, "D2" to 0),
            "fair",
        )
        result shouldHaveSize 2

        val pWO = result.find { it["product_id"] == "P" }!!
        pWO["consolidated"] shouldBe true
        pWO["quantity"] shouldBe 30.0

        val qWO = result.find { it["product_id"] == "Q" }!!
        qWO.containsKey("consolidated") shouldBe false
        qWO["demand_id"] shouldBe "D1"
        qWO["quantity"] shouldBe 5.0
    }
})
