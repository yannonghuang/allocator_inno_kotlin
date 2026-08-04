package com.allocator

import com.allocator.services.reconcile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Unit tests for reconcile()'s Phase 3 bottom-up commitment pass, focused on the
 * purchase-elasticity fix: a "purchase" leaf (and its wrapping "work_order") must be
 * granted the full `target` asked of it, never capped at whatever quantity the live
 * commit's first pass happened to buy — unlike a "supply" leaf, which is a fixed,
 * existing inventory lot and must stay capped.
 */
class ReconcileTest : FunSpec({

    fun purchaseLeaf(qty: Double): Map<String, Any?> =
        mapOf("type" to "purchase", "quantity" to qty, "quantity_precise" to qty, "children" to emptyList<Any>())

    fun purchaseWo(qty: Double, leaf: Map<String, Any?>): Map<String, Any?> =
        mapOf("type" to "work_order", "method" to "purchase", "quantity" to qty, "quantity_precise" to qty,
              "children" to listOf(leaf))

    fun supplyLeaf(qty: Double): Map<String, Any?> =
        mapOf("type" to "supply", "quantity" to qty, "children" to emptyList<Any>())

    test("purchase leaf: expands to target even when recorded quantity is far below it") {
        val leaf = purchaseLeaf(0.0267)
        val (reconciled, supplied) = reconcile(leaf, 0.36, emptyMap(), null)

        supplied shouldBe (0.36 plusOrMinus 1e-9)
        (reconciled["quantity"] as Number).toDouble() shouldBe (0.36 plusOrMinus 1e-9)
    }

    test("purchase leaf: never over-supplies beyond what target actually asks for") {
        val leaf = purchaseLeaf(100.0)
        val (reconciled, supplied) = reconcile(leaf, 5.0, emptyMap(), null)

        supplied shouldBe (5.0 plusOrMinus 1e-9)
        (reconciled["quantity"] as Number).toDouble() shouldBe (5.0 plusOrMinus 1e-9)
    }

    test("purchase work_order wrapper: want is not capped at the wrapper's own stale curQty") {
        val leaf = purchaseLeaf(0.0267)
        val wo = purchaseWo(0.0267, leaf)
        val (reconciled, supplied) = reconcile(wo, 0.36, emptyMap(), null)

        supplied shouldBe (0.36 plusOrMinus 1e-9)
        (reconciled["quantity"] as Number).toDouble() shouldBe (0.36 plusOrMinus 1e-9)
        @Suppress("UNCHECKED_CAST")
        val childLeaf = (reconciled["children"] as List<Map<String, Any?>>).first()
        (childLeaf["quantity"] as Number).toDouble() shouldBe (0.36 plusOrMinus 1e-9)
    }

    test("control: supply leaf stays capped at its recorded quantity — no regression to the fixed-inventory path") {
        val leaf = supplyLeaf(0.0267)
        val (reconciled, supplied) = reconcile(leaf, 0.36, emptyMap(), null)

        supplied shouldBe (0.0267 plusOrMinus 1e-9)
        (reconciled["quantity"] as Number).toDouble() shouldBe (0.0267 plusOrMinus 1e-9)
    }

    test("AND-group with a tiny-rate purchase sibling: reconciled group no longer collapses to the purchase's stale amount") {
        // Mirrors the real 260-0141-02 shape: an AND-group whose true bottleneck used to be
        // a purchase leaf reached through a tiny BOM rate (0.01) — before the fix, capping
        // the purchase at its stale recorded quantity (0.0267) and dividing back by the tiny
        // rate amplified a small, fixable shortfall into a large false collapse (2.667).
        val bom = listOf(
            mapOf("bom_id" to "B1", "parent_id" to "P", "child_id" to "NORMAL", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B1", "parent_id" to "P", "child_id" to "TINY", "rate" to 0.01, "alt_group" to null),
        )
        val normalChild = mapOf(
            "type" to "demand", "product_id" to "NORMAL", "location_id" to "L", "committed_qty" to 36.0,
            "children" to listOf(mapOf("type" to "work_order", "method" to "make", "quantity" to 36.0,
                "quantity_precise" to 36.0, "children" to emptyList<Any>())),
        )
        val tinyChild = mapOf(
            "type" to "demand", "product_id" to "TINY", "location_id" to "L", "committed_qty" to 0.36,
            "children" to listOf(purchaseWo(0.0267, purchaseLeaf(0.0267))),
        )
        val parentWo = mapOf(
            "type" to "work_order", "method" to "make", "product_id" to "P", "quantity" to 36.0,
            "quantity_precise" to 36.0, "children_relation" to "and",
            "children" to listOf(normalChild, tinyChild),
        )

        val (_, supplied) = reconcile(parentWo, 36.0, mapOf("bom" to bom), null)

        // Before the fix this would have been ~2.667 (0.0267 / 0.01); after the fix the
        // purchase sibling expands to its true need (0.36) and no longer dominates the group.
        supplied shouldBe (36.0 plusOrMinus 1e-6)
    }

    // ── Negative starting inventory: nested under the absorbing purchase WO ──

    fun negativeInventoryLeaf(qty: Double, supplyId: String? = "NEG1"): Map<String, Any?> =
        mapOf("type" to "supply", "quantity" to qty, "supply_id" to supplyId,
              "negative_inventory" to true, "children" to emptyList<Any>())

    test("purchase WO with a negative_inventory child: displays target+deficit, but reports only target upward") {
        // Mirrors planMethodSlot's purchase branch: the WO's own children are the real
        // purchase leaf (500, what this demand needs) plus the deficit leaf (-1424, a fixed
        // historical fact) — reconcile() must read the deficit straight off this child, grant
        // the WO's own displayed quantity = target + deficit, but still report only `target`
        // upward so the PARENT demand's own committed/remaining bookkeeping is untouched.
        val negLeaf = negativeInventoryLeaf(-1424.0)
        val wo = mapOf(
            "type" to "work_order", "method" to "purchase", "quantity" to 1924.0, "quantity_precise" to 1924.0,
            "children" to listOf(purchaseLeaf(1924.0), negLeaf),
        )

        val (reconciled, supplied) = reconcile(wo, 500.0, emptyMap(), null)

        // Upward-reported contribution is exactly the target — the deficit is invisible to
        // whatever asked for this WO.
        supplied shouldBe (500.0 plusOrMinus 1e-9)
        // The WO's own displayed quantity is the real, enlarged total.
        (reconciled["quantity"] as Number).toDouble() shouldBe (1924.0 plusOrMinus 1e-9)
        @Suppress("UNCHECKED_CAST")
        val children = reconciled["children"] as List<Map<String, Any?>>
        val purchaseChild = children.first { it["type"] == "purchase" }
        val negChild = children.first { it["negative_inventory"] == true }
        // The purchase leaf gets only the real contribution (target)...
        (purchaseChild["quantity"] as Number).toDouble() shouldBe (500.0 plusOrMinus 1e-9)
        // ...and the deficit leaf survives verbatim (a fixed fact, not re-derived from target).
        (negChild["quantity"] as Number).toDouble() shouldBe (-1424.0 plusOrMinus 1e-9)
    }

    test("control: purchase WO without a negative_inventory child behaves exactly as before") {
        val wo = purchaseWo(0.0267, purchaseLeaf(0.0267))
        val (reconciled, supplied) = reconcile(wo, 0.36, emptyMap(), null)

        supplied shouldBe (0.36 plusOrMinus 1e-9)
        (reconciled["quantity"] as Number).toDouble() shouldBe (0.36 plusOrMinus 1e-9)
    }
})
