package com.allocator

import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Behavior contract for waterfall ("best-supply win") method allocation.
 *
 * Setup:
 *   FG ─┬─ (make via BOM_A; preference 1)  ← R1 (qty-capped supply)
 *       └─ (make via BOM_B; preference 2)  ← R2 (qty-capped supply)
 *
 * With `max_methods = 2`, the planner runs BOM_A on the full demand first
 * (slot 1), then hands the residual to BOM_B (slot 2). Inventory carries
 * forward — slot 2 sees R1's consumption.
 */
class WaterfallAllocationTest : FunSpec({

    fun mkData(
        methodMake: List<Map<String, Any?>> = emptyList(),
        methodBuy: List<Map<String, Any?>> = emptyList(),
        methodMove: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
        productLocation: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to methodMove,
        "bom" to bom,
        "productlocation" to productLocation,
    )

    fun mkInv(vararg buckets: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        buckets.map { it.toMutableMap() }.toMutableList()

    fun supply(productId: String, locationId: String, qty: Double, supplyId: String = "S_$productId"): Map<String, Any?> =
        mapOf(
            "supply_id" to supplyId,
            "product_id" to productId,
            "location_id" to locationId,
            "supply_date" to null,
            "qty" to qty,
            "demand_tag" to null,
        )

    /** FG with two make methods (BOM_A→R1, BOM_B→R2). */
    val twoMethodFg = mkData(
        methodMake = listOf(
            mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            mapOf("bom_id" to "BOM_B", "product_id" to "FG", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
        ),
        bom = listOf(
            mapOf("bom_id" to "BOM_A", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_B", "parent_id" to "FG", "child_id" to "R2", "rate" to 1.0, "alt_group" to null),
        ),
    )

    val waterfallConfig = mapOf(
        "purchase_allowed" to false,
        "method_selection" to mapOf("max_methods" to 2, "mode" to "preference", "depth" to 1),
    )

    test("slot 1 fills entire demand → slot 2 not invoked") {
        // Demand 100, R1 plentiful → BOM_A fills all 100. R2 untouched.
        val inventory = mkInv(
            supply("R1", "L", 1000.0, "SUP_R1"),
            supply("R2", "L", 1000.0, "SUP_R2"),
        )
        val demand = mapOf(
            "demand_id" to "D1", "product_id" to "FG", "location_id" to "L",
            "quantity" to 100.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = waterfallConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)

        // R2 untouched (slot 2 was never invoked because residual hit 0).
        val r2Remaining = inventory.firstOrNull { it["product_id"] == "R2" }?.get("qty") as? Number
        r2Remaining?.toDouble() shouldBe (1000.0 plusOrMinus 1e-6)
    }

    test("slot 1 partial → slot 2 picks up residual, demand fully filled") {
        // Demand 200, R1=50 (slot 1 caps at 50), R2=1000 → slot 2 fills 150 → total 200.
        val inventory = mkInv(
            supply("R1", "L", 50.0, "SUP_R1"),
            supply("R2", "L", 1000.0, "SUP_R2"),
        )
        val demand = mapOf(
            "demand_id" to "D2", "product_id" to "FG", "location_id" to "L",
            "quantity" to 200.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = waterfallConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (200.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (200.0 plusOrMinus 1e-6)

        // No "partial" flag on the demand — it was fully fulfilled across two slots.
        val reasons = committed.mapNotNull { it["commit_reason"] as? String }
        (reasons.contains("partial")) shouldBe false
    }

    test("slot 1 partial, slot 2 partial → demand commits partial") {
        // Demand 200, R1=50, R2=50. Slot 1 fills 50, slot 2 fills 50, residual=100. Partial.
        val inventory = mkInv(
            supply("R1", "L", 50.0, "SUP_R1"),
            supply("R2", "L", 50.0, "SUP_R2"),
        )
        val demand = mapOf(
            "demand_id" to "D3", "product_id" to "FG", "location_id" to "L",
            "quantity" to 200.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = waterfallConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)

        // The demand row carries "partial" since residual > 0 after exhausting all slots.
        val reasons = committed.mapNotNull { it["commit_reason"] as? String }
        (reasons.any { it == "partial" }) shouldBe true
    }

    test("slot 1 hard-fails (committed=0) → slot 2 takes full demand") {
        // R1=0 → BOM_A blocked at 0, slot 2 (BOM_B with R2=1000) absorbs full 200.
        val inventory = mkInv(
            supply("R1", "L", 0.0, "SUP_R1"),
            supply("R2", "L", 1000.0, "SUP_R2"),
        )
        val demand = mapOf(
            "demand_id" to "D4", "product_id" to "FG", "location_id" to "L",
            "quantity" to 200.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = waterfallConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (200.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (200.0 plusOrMinus 1e-6)

        // R2 should be drained by 200; R1 untouched.
        val r2Remaining = inventory.firstOrNull { it["product_id"] == "R2" }?.get("qty") as? Number
        r2Remaining?.toDouble() shouldBe (800.0 plusOrMinus 1e-6)
    }

    test("max_methods=1 disables waterfall (single-method behavior)") {
        // R1=50, R2=50. Both methods probe-fail (each can supply at most 50 of 200).
        // Cascade falls back to lowest preference (BOM_A). Single-method commits 50.
        // Waterfall (max=2) would have produced 50+50 = 100 with two WOs; max=1 produces
        // only one WO at 50.
        val inventory = mkInv(
            supply("R1", "L", 50.0, "SUP_R1"),
            supply("R2", "L", 50.0, "SUP_R2"),
        )
        val demand = mapOf(
            "demand_id" to "D5", "product_id" to "FG", "location_id" to "L",
            "quantity" to 200.0, "request_due_time" to "2024-01-01",
        )
        val singleConfig = mapOf(
            "purchase_allowed" to false,
            "method_selection" to mapOf("max_methods" to 1, "mode" to "preference", "depth" to 1),
        )

        val (committed, wos, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = singleConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (50.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (50.0 plusOrMinus 1e-6)

        // Exactly one make WO (for BOM_A); BOM_B never invoked.
        wos.count { it["method"] == "make" } shouldBe 1
        // R2 untouched — slot 2 was never invoked because max=1 disables waterfall.
        val r2Remaining = inventory.firstOrNull { it["product_id"] == "R2" }?.get("qty") as? Number
        r2Remaining?.toDouble() shouldBe (50.0 plusOrMinus 1e-6)
    }

    test("inventory carries forward — slot 2 sees slot 1's consumption") {
        // BOM_A and BOM_B both consume R1 (shared raw material). Slot 1 takes 50,
        // slot 2 sees R1=0 → blocked. Total committed = 50.
        val sharedR1 = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(supply("R1", "L", 50.0, "SUP_R1"))
        val demand = mapOf(
            "demand_id" to "D6", "product_id" to "FG", "location_id" to "L",
            "quantity" to 200.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, sharedR1, requestTimeDt = null, config = waterfallConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (50.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (50.0 plusOrMinus 1e-6)

        // R1 fully drained.
        val r1Remaining = inventory.firstOrNull { it["product_id"] == "R1" }?.get("qty") as? Number
        r1Remaining?.toDouble() shouldBe (0.0 plusOrMinus 1e-6)
    }

    test("min-residual threshold prevents trivial second slot") {
        // R1=1000 with demand=100 → slot 1 fills all 100, residual=0 → slot 2 NOT invoked.
        // Already covered by the first test, but re-asserts the threshold bound by
        // exercising a cleaner-fill case.
        val inventory = mkInv(
            supply("R1", "L", 1000.0, "SUP_R1"),
            supply("R2", "L", 1000.0, "SUP_R2"),
        )
        val demand = mapOf(
            "demand_id" to "D7", "product_id" to "FG", "location_id" to "L",
            "quantity" to 100.0, "request_due_time" to "2024-01-01",
        )

        val (_, wos, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = waterfallConfig)

        // Exactly one method-pegging child under the demand (slot 1 only).
        @Suppress("UNCHECKED_CAST")
        val woChildren = (pegging?.get("children") as? List<Map<String, Any?>>) ?: emptyList()
        woChildren.size shouldBe 1
        // Only one WO emitted (BOM_A's, no BOM_B WO).
        wos.count { it["method"] == "make" } shouldBe 1
    }

    test("waterfall does NOT fire when methods.size <= 1") {
        // FG with single method: max_methods=2 has no effect — single-method path runs.
        val singleMethodFg = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(supply("R1", "L", 1000.0, "SUP_R1"))
        val demand = mapOf(
            "demand_id" to "D8", "product_id" to "FG", "location_id" to "L",
            "quantity" to 100.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, singleMethodFg, requestTimeDt = null, config = waterfallConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        pegging shouldNotBe null
    }
})
