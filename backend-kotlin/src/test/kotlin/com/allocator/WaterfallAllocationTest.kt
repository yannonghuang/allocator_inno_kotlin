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
 * `waterfallConfig` pins `root_waterfall: false` (legacy root-only equal-split — see its own
 * doc): FG's demand is a ROOT demand, and rootWaterfall now defaults to true, which would
 * otherwise make the root use the SAME ordinary sequential 100%-then-spillover waterfall as
 * every non-root node. With the legacy split pinned, `max_methods = 2` divides the root's
 * quantity evenly across BOM_A/BOM_B up front; genuine spillover (slot 2 picking up a residual
 * slot 1 couldn't cover, inventory carrying forward) still applies whenever a slot's granted
 * share exceeds what it can actually deliver.
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

    // root_waterfall: false pins the legacy root-only equal-split behavior these fixtures were
    // written around — rootWaterfall now defaults to true (root-alt-waterfall A/B promotion; see
    // MethodSelectionConfig's own doc), which would otherwise make a root demand use ordinary
    // sequential waterfall instead of splitting evenly across its top-`max_methods` alternatives.
    val waterfallConfig = mapOf(
        "purchase_allowed" to false,
        "method_selection" to mapOf("max_methods" to 2, "root_waterfall" to false),
    )

    test("root demand with both R1 and R2 plentiful: equal split across both slots, not slot-1-only") {
        // Demand 100, R1 and R2 both plentiful. This is a ROOT demand with max_methods=2, so
        // the root-only equal-split default applies even though slot 1 alone could satisfy
        // the whole demand: each of BOM_A/BOM_B gets an even 50/50 share.
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

        // Both R1 and R2 consumed 50 each (equal split), not R1=100/R2=0.
        val r1Remaining = inventory.firstOrNull { it["product_id"] == "R1" }?.get("qty") as? Number
        val r2Remaining = inventory.firstOrNull { it["product_id"] == "R2" }?.get("qty") as? Number
        r1Remaining?.toDouble() shouldBe (950.0 plusOrMinus 1e-6)
        r2Remaining?.toDouble() shouldBe (950.0 plusOrMinus 1e-6)
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

    test("max_methods no longer bounds ordinary-waterfall candidate count (root_waterfall=true)") {
        // R1=50, R2=50, demand=200. max_methods=1 does NOT restrict the root to one candidate
        // when root_waterfall=true (the default): PlanningEngine.kt's rootSplitWeights/loopCap
        // doc (~3489-3513) is explicit that max_methods only shapes the root's up-front
        // equal-split when root_waterfall=false AND cap>1 — "the loop previously didn't
        // actually honor" a max_methods-bounds-candidate-count contract, and that was an
        // intentional fix, not a regression. Ordinary sequential waterfall (root_waterfall=true,
        // or any non-root node) tries every available alternative regardless of max_methods:
        // slot 1 (BOM_A/R1) commits 50, residual 150 spills to slot 2 (BOM_B/R2) which commits
        // another 50 — total 100, both R1 and R2 drained, both WOs present.
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
            "method_selection" to mapOf("max_methods" to 1),
        )

        val (committed, wos, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = singleConfig)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)

        // Both BOM_A and BOM_B fire despite max_methods=1.
        wos.count { it["method"] == "make" } shouldBe 2
        val r2Remaining = inventory.firstOrNull { it["product_id"] == "R2" }?.get("qty") as? Number
        r2Remaining?.toDouble() shouldBe (0.0 plusOrMinus 1e-6)
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

    test("root equal-split still runs both slots even for a clean, evenly-divisible fill") {
        // R1=1000, R2=1000, demand=100 → root equal-split targets 50/50, both plentiful, so
        // both slots commit and both show up in pegging/wos. (MIN_WATERFALL_RESIDUAL still
        // guards the ordinary sequential/cascade path — e.g. once a slot's target is
        // satisfied with zero carry-forward and cap is exhausted — but a root demand with
        // max_methods > 1 always tries every one of its top-`cap` candidates up front.)
        val inventory = mkInv(
            supply("R1", "L", 1000.0, "SUP_R1"),
            supply("R2", "L", 1000.0, "SUP_R2"),
        )
        val demand = mapOf(
            "demand_id" to "D7", "product_id" to "FG", "location_id" to "L",
            "quantity" to 100.0, "request_due_time" to "2024-01-01",
        )

        val (_, wos, pegging) = plan(demand, inventory, twoMethodFg, requestTimeDt = null, config = waterfallConfig)

        // Both BOM_A and BOM_B pegging children present under the demand.
        @Suppress("UNCHECKED_CAST")
        val woChildren = (pegging?.get("children") as? List<Map<String, Any?>>) ?: emptyList()
        woChildren.size shouldBe 2
        // Both WOs emitted (BOM_A and BOM_B), 50 each.
        val makeWos = wos.filter { it["method"] == "make" }
        makeWos.size shouldBe 2
        makeWos.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (100.0 plusOrMinus 1e-6)
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
