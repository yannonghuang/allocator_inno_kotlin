package com.allocator

import com.allocator.services.committedQtyOf
import com.allocator.services.garbageCollectPegging
import com.allocator.services.scaleWos
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Unit tests for GCEngine: garbageCollectPegging and scaleWos.
 *
 * These tests exercise the GC trim logic directly, without running a full plan.
 * Each test builds a minimal pegging subtree and an inventory list, calls GC
 * with an effectiveCap, and asserts:
 *  - the returned node has the correct committed/quantity value
 *  - the inventory bucket was restored by exactly the expected excess
 *  - the budget map was restored correctly (when applicable)
 */
class GCEngineTest : FunSpec({

    // ── helpers ───────────────────────────────────────────────────────────────

    fun mkInv(vararg buckets: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        buckets.map { it.toMutableMap() }.toMutableList()

    fun invBucket(pid: String, lid: String, supplyId: String, qty: Double): Map<String, Any?> =
        mapOf("product_id" to pid, "location_id" to lid, "supply_id" to supplyId, "qty" to qty)

    fun supplyNode(pid: String, lid: String, supplyId: String, qty: Double): Map<String, Any?> =
        mapOf("type" to "supply", "product_id" to pid, "location_id" to lid,
              "supply_id" to supplyId, "quantity" to qty, "children" to emptyList<Any>())

    fun woNode(qty: Double, relation: String? = null, children: List<Map<String, Any?>> = emptyList()): Map<String, Any?> =
        mapOf("type" to "work_order", "quantity" to qty, "children_relation" to relation,
              "children" to children)

    fun demandNode(committedQty: Double, children: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf("type" to "demand", "committed_qty" to committedQty, "children" to children)

    // ── T1: supply node trim returns excess to inventory ─────────────────────

    test("T1: supply node trim returns excess to inventory bucket") {
        val inv = mkInv(invBucket("A", "L1", "S1", 100.0))
        val node = supplyNode("A", "L1", "S1", 80.0)

        val result = garbageCollectPegging(node, 50.0, inv, null)

        committedQtyOf(result) shouldBe (50.0 plusOrMinus 1e-6)
        val remaining = (inv.first()["qty"] as Number).toDouble()
        remaining shouldBe (130.0 plusOrMinus 1e-6)  // 100 original + 30 returned
    }

    // ── T2: supply node — no trim when committed <= cap ───────────────────────

    test("T2: supply node returns unchanged when committed <= effectiveCap") {
        val inv = mkInv(invBucket("A", "L1", "S1", 20.0))
        val node = supplyNode("A", "L1", "S1", 80.0)

        val result = garbageCollectPegging(node, 80.0, inv, null)

        committedQtyOf(result) shouldBe (80.0 plusOrMinus 1e-6)
        (inv.first()["qty"] as Number).toDouble() shouldBe (20.0 plusOrMinus 1e-6)  // unchanged
    }

    // ── T3: WO AND node — children scaled by bom_rate ─────────────────────────

    test("T3: WO AND node trims each child by gc_bom_rate") {
        // WO committed 100, AND children:
        //   child A: bom_rate=0.5 → committed 50, supply in inv bucket IA (qty=0)
        //   child B: bom_rate=2.0 → committed 200, supply in inv bucket IB (qty=0)
        val inv = mkInv(
            invBucket("CA", "L", "IA", 0.0),
            invBucket("CB", "L", "IB", 0.0),
        )
        val childA = demandNode(50.0,  listOf(supplyNode("CA", "L", "IA", 50.0))).plus("gc_bom_rate" to 0.5)
        val childB = demandNode(200.0, listOf(supplyNode("CB", "L", "IB", 200.0))).plus("gc_bom_rate" to 2.0)
        val wo = woNode(100.0, "and", listOf(childA, childB))

        val result = garbageCollectPegging(wo, 60.0, inv, null)

        // WO quantity trimmed to 60
        committedQtyOf(result) shouldBe (60.0 plusOrMinus 1e-6)

        @Suppress("UNCHECKED_CAST")
        val kids = result["children"] as List<Map<String, Any?>>
        // child A: target = 60 * 0.5 = 30 → 50-30=20 returned
        committedQtyOf(kids[0]) shouldBe (30.0 plusOrMinus 1e-6)
        (inv.find { it["supply_id"] == "IA" }!!["qty"] as Number).toDouble() shouldBe (20.0 plusOrMinus 1e-6)
        // child B: target = 60 * 2.0 = 120 → 200-120=80 returned
        committedQtyOf(kids[1]) shouldBe (120.0 plusOrMinus 1e-6)
        (inv.find { it["supply_id"] == "IB" }!!["qty"] as Number).toDouble() shouldBe (80.0 plusOrMinus 1e-6)
    }

    // ── T4: demand node — WO trimmed first, then supply ───────────────────────

    test("T4: demand node trims WO child first, supply child second") {
        // demand committed 100 = supply 40 + WO 60
        // WO has one AND child demand → supply(qty=60)
        val inv = mkInv(
            invBucket("P", "L", "S_INV", 0.0),   // direct inventory for parent
            invBucket("C", "L", "S_WO",  0.0),   // child supply under WO
        )
        val woChildSupply = supplyNode("C", "L", "S_WO", 60.0)
        val woChildDemand = demandNode(60.0, listOf(woChildSupply)).plus("gc_bom_rate" to 1.0)
        val wo = woNode(60.0, null, listOf(woChildDemand))
        val directSupply = supplyNode("P", "L", "S_INV", 40.0)
        val demand = demandNode(100.0, listOf(directSupply, wo))

        // Trim to 70: remove 30 from WO (leaving WO at 30); supply intact at 40
        val result = garbageCollectPegging(demand, 70.0, inv, null)

        committedQtyOf(result) shouldBe (70.0 plusOrMinus 1e-6)
        (inv.find { it["supply_id"] == "S_INV" }!!["qty"] as Number).toDouble() shouldBe (0.0 plusOrMinus 1e-6)  // untouched
        (inv.find { it["supply_id"] == "S_WO" }!!["qty"] as Number).toDouble() shouldBe (30.0 plusOrMinus 1e-6)  // 30 returned
    }

    // ── T5: demand node — supply trimmed when WO exhausted ────────────────────

    test("T5: demand node trims supply after WO fully removed") {
        // demand committed 100 = supply 40 + WO 60; trim to 30
        val inv = mkInv(
            invBucket("P", "L", "S_INV", 0.0),
            invBucket("C", "L", "S_WO",  0.0),
        )
        val woChildSupply = supplyNode("C", "L", "S_WO", 60.0)
        val woChildDemand = demandNode(60.0, listOf(woChildSupply)).plus("gc_bom_rate" to 1.0)
        val wo = woNode(60.0, null, listOf(woChildDemand))
        val directSupply = supplyNode("P", "L", "S_INV", 40.0)
        val demand = demandNode(100.0, listOf(directSupply, wo))

        // Need to return 70 total: WO gives 60, supply gives 10
        val result = garbageCollectPegging(demand, 30.0, inv, null)

        committedQtyOf(result) shouldBe (30.0 plusOrMinus 1e-6)
        (inv.find { it["supply_id"] == "S_WO" }!!["qty"] as Number).toDouble() shouldBe (60.0 plusOrMinus 1e-6)  // fully returned
        (inv.find { it["supply_id"] == "S_INV" }!!["qty"] as Number).toDouble() shouldBe (10.0 plusOrMinus 1e-6)  // 10 returned
    }

    // ── T6: OR-split (waterfall demand node) ──────────────────────────────────

    test("T6: OR-split trims last WO slot first (least preferred)") {
        // demand OR-split: WO1 committed 50, WO2 committed 30, total 80
        // Trim to 55: WO2 trimmed from 30 to 5, WO1 untouched at 50
        val inv = mkInv(
            invBucket("M1", "L", "SM1", 0.0),
            invBucket("M2", "L", "SM2", 0.0),
        )
        val wo1 = woNode(50.0, null, listOf(supplyNode("M1", "L", "SM1", 50.0)))
        val wo2 = woNode(30.0, null, listOf(supplyNode("M2", "L", "SM2", 30.0)))
        val demand = demandNode(80.0, listOf(wo1, wo2))
        // children_relation "or" triggers waterfall trimming at demand level
        val demandOr = demand + ("children_relation" to "or")

        val result = garbageCollectPegging(demandOr, 55.0, inv, null)

        committedQtyOf(result) shouldBe (55.0 plusOrMinus 1e-6)
        (inv.find { it["supply_id"] == "SM1" }!!["qty"] as Number).toDouble() shouldBe (0.0 plusOrMinus 1e-6)   // WO1 untouched
        (inv.find { it["supply_id"] == "SM2" }!!["qty"] as Number).toDouble() shouldBe (25.0 plusOrMinus 1e-6)  // WO2 returns 25
    }

    // ── T7: budget restoration ─────────────────────────────────────────────────

    test("T7: supply trim restores budget aggregate and per-lot keys") {
        val inv = mkInv(invBucket("X", "L", "SX", 0.0))
        val budget = mutableMapOf("X|L" to 20.0, "X|L|SX" to 20.0)
        val node = supplyNode("X", "L", "SX", 20.0)

        garbageCollectPegging(node, 12.0, inv, budget)

        budget["X|L"]   shouldBe (28.0 plusOrMinus 1e-6)   // 20 + 8 returned
        budget["X|L|SX"] shouldBe (28.0 plusOrMinus 1e-6)
    }

    // ── T8: operation node is skipped ─────────────────────────────────────────

    test("T8: operation and resource nodes pass through unchanged") {
        val opNode = mapOf("type" to "operation", "quantity" to 100.0, "children" to emptyList<Any>())
        val resNode = mapOf("type" to "resource", "size" to 5.0, "children" to emptyList<Any>())
        val inv = mkInv()

        garbageCollectPegging(opNode, 50.0, inv, null) shouldBe opNode
        garbageCollectPegging(resNode, 50.0, inv, null) shouldBe resNode
    }

    // ── T9: scaleWos — proportional quantity scaling ───────────────────────────

    test("T9: scaleWos scales quantities and drops zero lots") {
        val wos = listOf(
            mapOf("quantity" to 200.0, "demand_id" to "D1"),
            mapOf("quantity" to 200.0, "demand_id" to "D1"),
            mapOf("quantity" to 5.0,   "demand_id" to "D1"),
        )
        val scaled = scaleWos(wos, 0.5)

        scaled.size shouldBe 3
        (scaled[0]["quantity"] as Number).toDouble() shouldBe (100.0 plusOrMinus 1e-6)
        (scaled[1]["quantity"] as Number).toDouble() shouldBe (100.0 plusOrMinus 1e-6)
        // 5 * 0.5 = 2.5 → rounds to 3 (roundQty = Math.round)
        (scaled[2]["quantity"] as Number).toDouble() shouldBe (3.0 plusOrMinus 1.0)
    }

    test("T9b: scaleWos with scale=0 returns empty list") {
        val wos = listOf(mapOf<String, Any?>("quantity" to 100.0))
        scaleWos(wos, 0.0) shouldBe emptyList()
    }

    test("T9c: scaleWos with scale>=1 returns list unchanged") {
        val wos = listOf(mapOf<String, Any?>("quantity" to 100.0))
        scaleWos(wos, 1.0) shouldBe wos
    }

    // ── T10: WO AND node — bom_rate derived from committed_qty/woQty fallback ─

    test("T10: bom_rate fallback (no gc_bom_rate tag) still trims correctly") {
        // If gc_bom_rate is absent, GCEngine derives it from child.committed_qty / wo.quantity
        val inv = mkInv(invBucket("C", "L", "SC", 0.0))
        val childSupply = supplyNode("C", "L", "SC", 150.0)
        // No gc_bom_rate tag on the demand child
        val childDemand = mapOf("type" to "demand", "committed_qty" to 150.0, "children" to listOf(childSupply))
        val wo = woNode(100.0, "and", listOf(childDemand))  // bomRate implied = 150/100 = 1.5

        val result = garbageCollectPegging(wo, 60.0, inv, null)

        committedQtyOf(result) shouldBe (60.0 plusOrMinus 1e-6)
        // child target = 60 * (150/100) = 90; excess = 150-90 = 60 returned
        (inv.find { it["supply_id"] == "SC" }!!["qty"] as Number).toDouble() shouldBe (60.0 plusOrMinus 1e-6)
    }

    // ── T11/T12: rounded committed_qty inflates excess, over-trimming a child ──
    // Reproduces the 858_F35_2024_07_VIRTUAL / 500-6161 collapse (2.667 instead of
    // ~36): demandNode() rounds "committed_qty" for display (e.g. 1.6667 -> 2.0),
    // but until this fix gcDemandNode read that ROUNDED value to compute excess,
    // then applied the inflated excess straight against a child's own UNROUNDED
    // quantity — over-trimming by the rounding gap.

    fun demandNodePrecise(committedQty: Double, committedQtyPrecise: Double, children: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf("type" to "demand", "committed_qty" to committedQty, "committed_qty_precise" to committedQtyPrecise, "children" to children)

    fun woNodePrecise(qty: Double, qtyPrecise: Double, relation: String? = null, children: List<Map<String, Any?>> = emptyList()): Map<String, Any?> =
        mapOf("type" to "work_order", "quantity" to qty, "quantity_precise" to qtyPrecise,
              "children_relation" to relation, "children" to children)

    test("T11: gcDemandNode uses committed_qty_precise, not the rounded committed_qty, to compute excess") {
        // True achieved = 1.6667, but demandNode() rounds committed_qty to 2.0 for display.
        // Trimming to effectiveCap=0.36 must land the supply leaf at 0.36 (1.6667-1.3067),
        // not 0.0267 (1.6667 - (2.0-0.36) — the rounding-inflated excess).
        // Shape mirrors the real tree: WO's own child is a "demand" wrapper carrying an
        // explicit gc_bom_rate=1.0 tag (as plan()'s childPassResults construction always
        // sets), not a bare supply node directly under the WO.
        val inv = mkInv(invBucket("A", "L", "SA", 900.0))
        val supply = supplyNode("A", "L", "SA", 1.6666666666666665)
        val childDemand = demandNodePrecise(2.0, 1.6666666666666665, listOf(supply)).plus("gc_bom_rate" to 1.0)
        val wo = woNodePrecise(2.0, 1.6666666666666665, "and", listOf(childDemand))
        val demand = demandNodePrecise(2.0, 1.6666666666666665, listOf(wo))

        val result = garbageCollectPegging(demand, 0.36, inv, null)

        (result["committed_qty_precise"] as Number).toDouble() shouldBe (0.36 plusOrMinus 1e-6)
        @Suppress("UNCHECKED_CAST")
        val woResult = (result["children"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val childDemandResult = (woResult["children"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val supplyResult = (childDemandResult["children"] as List<Map<String, Any?>>).first()
        (supplyResult["quantity"] as Number).toDouble() shouldBe (0.36 plusOrMinus 1e-6)
    }

    test("T12: gcDemandNode and gcWoNode refresh their own _precise field after trimming, not leaving it stale") {
        // Reproduces the second-order bug found verifying T11's fix live: GC trim updated
        // the rounded "committed_qty"/"quantity" on return but left "committed_qty_precise"/
        // "quantity_precise" at their PRE-trim value — a downstream reader that (correctly)
        // prefers the precise field then sees a stale, un-trimmed figure. This caused
        // conservation violations (initial != leftover + pegged) elsewhere in case 173
        // once T11's fix made gcDemandNode itself prefer the precise field.
        val inv = mkInv(invBucket("A", "L", "SA", 0.0))
        val supply = supplyNode("A", "L", "SA", 3000.0)
        val wo = woNodePrecise(3000.0, 3000.0, null, listOf(supply))
        val demand = demandNodePrecise(3000.0, 3000.0, listOf(wo))

        val result = garbageCollectPegging(demand, 0.0, inv, null)

        (result["committed_qty"] as Number).toDouble() shouldBe (0.0 plusOrMinus 1e-6)
        (result["committed_qty_precise"] as Number).toDouble() shouldBe (0.0 plusOrMinus 1e-6)
        @Suppress("UNCHECKED_CAST")
        val woResult = (result["children"] as List<Map<String, Any?>>).first()
        (woResult["quantity"] as Number).toDouble() shouldBe (0.0 plusOrMinus 1e-6)
        (woResult["quantity_precise"] as Number).toDouble() shouldBe (0.0 plusOrMinus 1e-6)
    }
})
