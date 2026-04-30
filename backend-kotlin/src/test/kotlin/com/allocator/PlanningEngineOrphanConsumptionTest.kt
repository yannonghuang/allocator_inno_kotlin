package com.allocator

import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Regression tests for the orphan-inventory-consumption bug.
 *
 * Pre-fix: planMethodSlot's AND-bottleneck blocked branch returned without
 * restoring the global `inventory` or per-demand `budget` snapshots, even
 * though the first pass had recursively descended the BOM and consumed
 * supply at every depth. Result: parent committed 0 of P, but raw materials
 * (and intermediates several BOM levels deep) stayed depleted in the live
 * inventory state. Subsequent demands saw phantom-consumed inventory.
 *
 * Post-fix: blocked branch restores `inventory` + `budget` from the
 * snapshots taken at planMethodSlot entry — symmetrical to the non-blocked
 * second-pass branch — and drops the failed-child pegging from the
 * placeholder WO so the tree doesn't claim consumption that no longer
 * exists.
 */
class PlanningEngineOrphanConsumptionTest : FunSpec({

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

    /** Look up the live qty of a supply by supply_id in the post-plan inventory. */
    fun MutableList<MutableMap<String, Any?>>.qtyOf(supplyId: String): Double =
        this.firstOrNull { it["supply_id"] == supplyId }
            ?.let { (it["qty"] as? Number)?.toDouble() }
            ?: error("supply $supplyId not in inventory")

    val noPurchaseConfig = mapOf("purchase_allowed" to false)

    // ── Test 1: single-level orphan reproduction ─────────────────────────────

    test("single-level: A-supply intact when blocked by sibling B with no supply") {
        // FG → make via BOM_A with two AND-required BOM children: A and B.
        // A has supply=100, B has supply=0. Planner kicks off first-pass:
        //   - A's child plan consumes 100 of A's supply
        //   - B's child plan returns 0 (no supply)
        //   - bottleneck = 0 → blocked branch
        // Pre-fix: A's supply leaks to qty=0 even though parent committed 0.
        // Post-fix: A's supply still has qty=100 after the restore.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_FG", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "A", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "B", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(
            supply("A", "L", 100.0, "SUP_A"),
            supply("B", "L", 0.0, "SUP_B"),
        )
        val demand = mapOf(
            "demand_id" to "D1", "product_id" to "FG", "location_id" to "L",
            "quantity" to 100.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, pegging) = plan(demand, inventory, data, requestTimeDt = null, config = noPurchaseConfig)

        // Parent committed 0 (B is the bottleneck).
        committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (0.0 plusOrMinus 1e-6)

        // A's supply must still be at qty=100 — the post-fix restore reverts the
        // first-pass take. This is the core regression assertion.
        inventory.qtyOf("SUP_A") shouldBe (100.0 plusOrMinus 1e-6)
        inventory.qtyOf("SUP_B") shouldBe (0.0 plusOrMinus 1e-6)

        // The placeholder WO under the demand pegging should have NO children:
        // the failed-child pegging trees were dropped post-fix to avoid lying
        // about consumption.
        @Suppress("UNCHECKED_CAST")
        val woNodes = (pegging?.get("children") as? List<Map<String, Any?>>) ?: emptyList()
        // The placeholder WO is under the demand. It may live nested inside other
        // pegging structure depending on how the demand node was built.
        val placeholderWoChildren = woNodes
            .firstOrNull { it["type"] == "work_order" }
            ?.let { (it["children"] as? List<*>) ?: emptyList<Any>() }
        // Either no WO emitted at all, or the WO has empty children.
        (placeholderWoChildren?.isEmpty() ?: true) shouldBe true
    }

    // ── Test 2: multi-level subtree restore ─────────────────────────────────

    test("multi-level: deep raw material RM is restored when outer parent is blocked") {
        // FG → make via BOM_FG, AND-children A and B.
        //   - A → make via BOM_A, AND-child RM (raw material, supply=50).
        //   - B has no make method (raw, no supply).
        // First pass at the OUTER planMethodSlot:
        //   - A's child plan recurses: planMethodSlot for A's make runs its own
        //     first/second-pass. A consumes 50 of RM → A commits 50.
        //   - B's child plan returns 0 (no supply, no method).
        //   - Outer bottleneck = 0 → outer blocked branch.
        // Pre-fix: RM's supply ends at qty=0 despite the OUTER parent committing 0.
        // Post-fix: RM's supply still at qty=50 — the outer-level snapshot
        // captures (and restores) the entire subtree, including the deeper
        // sub-make's commits at any depth.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_FG", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_A", "product_id" to "A", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                // FG's BOM: requires A AND B (both alt_group=null → AND).
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "A", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "B", "rate" to 1.0, "alt_group" to null),
                // A's BOM: requires RM.
                mapOf("bom_id" to "BOM_A", "parent_id" to "A", "child_id" to "RM", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(
            supply("RM", "L", 50.0, "SUP_RM"),
            // No supply for A, B — A is made via BOM_A from RM; B has no method.
            supply("B", "L", 0.0, "SUP_B"),
        )
        val demand = mapOf(
            "demand_id" to "D1", "product_id" to "FG", "location_id" to "L",
            "quantity" to 50.0, "request_due_time" to "2024-01-01",
        )

        val (committed, _, _) = plan(demand, inventory, data, requestTimeDt = null, config = noPurchaseConfig)

        // FG committed 0 (B is the bottleneck).
        committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (0.0 plusOrMinus 1e-6)

        // RM's supply must still be at qty=50 — the deep raw material consumed
        // by A's first-pass sub-make was rolled back when the OUTER FG slot was
        // blocked. Proves the restore covers the entire subtree.
        inventory.qtyOf("SUP_RM") shouldBe (50.0 plusOrMinus 1e-6)
    }

    // ── Test 3: inter-demand isolation ──────────────────────────────────────

    test("two demands blocked the same way don't steal inventory from each other") {
        // Same FG/A/B layout as test 1, but plan TWO separate demands D1 and D2.
        // Both should be independently blocked. D2 must see the same inventory
        // D1 saw at the start — D1's first-pass takes must not bleed through.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_FG", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "A", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "B", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val inventory = mkInv(
            supply("A", "L", 100.0, "SUP_A"),
            supply("B", "L", 0.0, "SUP_B"),
        )

        val (c1, _, _) = plan(
            mapOf("demand_id" to "D1", "product_id" to "FG", "location_id" to "L",
                  "quantity" to 100.0, "request_due_time" to "2024-01-01"),
            inventory, data, requestTimeDt = null, config = noPurchaseConfig,
        )
        // After D1, A's supply must still be 100.
        inventory.qtyOf("SUP_A") shouldBe (100.0 plusOrMinus 1e-6)

        val (c2, _, _) = plan(
            mapOf("demand_id" to "D2", "product_id" to "FG", "location_id" to "L",
                  "quantity" to 100.0, "request_due_time" to "2024-01-01"),
            inventory, data, requestTimeDt = null, config = noPurchaseConfig,
        )
        // D2 saw the same starting state and was blocked the same way.
        inventory.qtyOf("SUP_A") shouldBe (100.0 plusOrMinus 1e-6)

        // Both demands committed 0.
        c1.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (0.0 plusOrMinus 1e-6)
        c2.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (0.0 plusOrMinus 1e-6)
    }
})
