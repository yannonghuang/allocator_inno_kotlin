package com.allocator

import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Regression test for the equal-split cap-propagation bug (Image 7 on case 97).
 *
 * When `method_selection.multiple=true` and an FG has multiple make methods, each method's
 * slot must honor its children's real capacity. Prior to the fix, the equal-split branch
 * planned each child at its full methodQty without a probe, so a deeper supply cap never
 * bubbled up — the demand node still advertised the original 2,500 qty while the WO below
 * it correctly showed 471.
 *
 * These tests exercise the shallow bottleneck path directly:
 *   FG ─┬─ (make via BOM_A) ← R1 (supply-capped)
 *       └─ (make via BOM_B) ← R2 (supply-capped)
 */
class PlanningEngineEqualSplitCapTest : FunSpec({

    /** Build a minimal [data] map the way CaseLoader produces it. */
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

    val equalSplitConfig: Map<String, Any?> = mapOf(
        "purchase_allowed" to true,
        "method_selection" to mapOf("multiple" to true),
        "variant_selection" to mapOf<String, Any?>(),
    )

    test("equal-split caps each method slot at its own child's supply") {
        // FG demand = 200, two make methods.
        // M1 via BOM_A → R1 (supply 50).  M2 via BOM_B → R2 (supply 50).
        // Without cap propagation the demand would "commit" 200; with the fix it caps at 100.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
            ),
            methodBuy = listOf(
                mapOf("product_id" to "R1", "location_id" to "L", "preference" to 1, "lead_days_supply" to 0.0),
                mapOf("product_id" to "R2", "location_id" to "L", "preference" to 1, "lead_days_supply" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B", "parent_id" to "FG", "child_id" to "R2", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(
            supply("R1", "L", 50.0, "SUP_R1"),
            supply("R2", "L", 50.0, "SUP_R2"),
        )
        // Don't allow purchase — we want to observe the supply cap, not fall back to buying.
        val config = equalSplitConfig + ("purchase_allowed" to false)
        val demand = mapOf(
            "demand_id" to "D1",
            "product_id" to "FG",
            "location_id" to "L",
            "quantity" to 200.0,
            "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)

        val topCommittedQty = (pegging?.get("committed_qty") as? Number)?.toDouble() ?: 0.0
        topCommittedQty shouldBe (100.0 plusOrMinus 1e-6)

        val reasons = committed.mapNotNull { it["commit_reason"] as? String }
        // At least one row must be marked "partial" so downstream shortage KPIs see the cap.
        (reasons.any { it == "partial" }) shouldBe true
    }

    test("equal-split with one short slot still allows the other slot to commit") {
        // M1 scarce (R1=50 vs needed 100), M2 plentiful (R2=1000).
        // Expected: M1 caps at 50, M2 delivers 100 → total committed = 150 (not the 200 req'd).
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B", "parent_id" to "FG", "child_id" to "R2", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(
            supply("R1", "L", 50.0, "SUP_R1"),
            supply("R2", "L", 1000.0, "SUP_R2"),
        )
        val config = equalSplitConfig + ("purchase_allowed" to false)
        val demand = mapOf(
            "demand_id" to "D2",
            "product_id" to "FG",
            "location_id" to "L",
            "quantity" to 200.0,
            "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (150.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (150.0 plusOrMinus 1e-6)
    }

    test("equal-split with both slots plentiful commits full demand (no regression)") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B", "parent_id" to "FG", "child_id" to "R2", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(
            supply("R1", "L", 1000.0, "SUP_R1"),
            supply("R2", "L", 1000.0, "SUP_R2"),
        )
        val config = equalSplitConfig + ("purchase_allowed" to false)
        val demand = mapOf(
            "demand_id" to "D3",
            "product_id" to "FG",
            "location_id" to "L",
            "quantity" to 200.0,
            "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (200.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (200.0 plusOrMinus 1e-6)
        committed.mapNotNull { it["commit_reason"] as? String }.any { it == "partial" } shouldBe false
    }

    test("pegging tree reports the capped qty inside the nested demand nodes") {
        // Two methods, both share a SHALLOW scarce raw: demand 200, per-method 100, supply
        // (R1=50, R2=50) → each method caps at 50, total 100. The outer demand node's
        // committed_qty must reflect 100 (NOT 200) — this is the precise field that went
        // wrong in the Image-7 bug and is what the UI reads.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B", "parent_id" to "FG", "child_id" to "R2", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(
            supply("R1", "L", 50.0, "SUP_R1"),
            supply("R2", "L", 50.0, "SUP_R2"),
        )
        val config = equalSplitConfig + ("purchase_allowed" to false)
        val demand = mapOf(
            "demand_id" to "D4",
            "product_id" to "FG",
            "location_id" to "L",
            "quantity" to 200.0,
            "request_due_time" to "2024-01-01",
        )

        val (_, workOrders, pegging) = plan(demand, inventory, data, requestTimeDt = null, config = config)
        pegging shouldNotBe null

        // The top-level demand node records both the request (quantity) and what actually
        // committed (committed_qty) — after the fix, committed_qty must be the capped total.
        pegging!!["quantity"] shouldBe 200.0
        (pegging["committed_qty"] as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)
        pegging["commit_reason"] shouldBe "partial"

        // Work orders for the FG itself should sum to the capped qty — not the requested qty.
        val fgWoQty = workOrders
            .filter { it["product_id"] == "FG" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        fgWoQty shouldBe (100.0 plusOrMinus 1e-6)
    }
})
