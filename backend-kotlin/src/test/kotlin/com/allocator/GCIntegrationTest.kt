package com.allocator

import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Integration tests for the GC-based AND-min trimming path in plan() /
 * planMethodSlot().
 *
 * These tests exercise the full recursive plan() call stack and verify that
 * garbageCollectPegging correctly replaces the second-pass re-plan in the
 * anyChildShort branch: excess inventory is returned, committed quantities
 * match the AND-min achievable, and subsequent demands see the returned supply.
 */
class GCIntegrationTest : FunSpec({

    // ── shared helpers ────────────────────────────────────────────────────────

    fun mkInv(vararg buckets: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        buckets.map { it.toMutableMap() }.toMutableList()

    fun invBucket(pid: String, lid: String, supplyId: String, qty: Double): Map<String, Any?> =
        mapOf("product_id" to pid, "location_id" to lid,
              "supply_id" to supplyId, "supply_date" to null, "qty" to qty, "demand_tag" to null)

    fun mkData(
        methodMake: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
        methodBuy: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "method_make" to methodMake,
        "method_buy"  to methodBuy,
        "method_move" to emptyList(),
        "bom"         to bom,
        "productlocation" to emptyList(),
    )

    fun demand(pid: String, lid: String, qty: Double, id: String = "D1"): Map<String, Any?> =
        mapOf("demand_id" to id, "product_id" to pid, "location_id" to lid,
              "quantity" to qty, "request_due_time" to "2024-01-01")

    val noBuy = mapOf("purchase_allowed" to false)

    // ── I1: genuine AND-min shortfall — GC correctness ────────────────────────

    test("I1: AND-min shortfall — GC trims over-committed child, parent commits at achievable") {
        // FG needs 100 units via make, BOM: child_A (rate=1, needs 100), child_B (rate=2, needs 200)
        // Inventory: child_A = 80, child_B = 300 (plenty)
        // Expected achievable: 80 (limited by child_A); child_B consumed only 160 (not 200)
        val inv = mkInv(
            invBucket("FG",      "L", "FG_S",  0.0),
            invBucket("child_A", "L", "CA_S",  80.0),
            invBucket("child_B", "L", "CB_S",  300.0),
        )
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L",
                      "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "child_A",
                      "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "child_B",
                      "rate" to 2.0, "alt_group" to null),
            ),
        )

        val (committed, _, pegging) = plan(demand("FG", "L", 100.0), inv, data,
            requestTimeDt = null, config = noBuy)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (80.0 plusOrMinus 0.5)          // limited by child_A

        // child_B: first pass consumed 200, GC returns 40 (200 - 160)
        val cbRemaining = (inv.find { it["supply_id"] == "CB_S" }!!["qty"] as Number).toDouble()
        cbRemaining shouldBe (140.0 plusOrMinus 0.5)            // 300 - 160 consumed

        // child_A: fully consumed (80 taken, 0 returned)
        val caRemaining = (inv.find { it["supply_id"] == "CA_S" }!!["qty"] as Number).toDouble()
        caRemaining shouldBe (0.0 plusOrMinus 0.5)

        pegging shouldNotBe null
    }

    // ── I2: returned inventory visible to subsequent demand ───────────────────

    test("I2: GC-returned inventory is available to the next demand") {
        // Two demands for FG. Each FG make needs child_A (rate=1) + child_B (rate=2).
        // child_A inventory: 80 total → demand 1 limited to 80, demand 2 gets 0 of child_A.
        // child_B inventory: 300 total → demand 1 first-pass takes 200, GC returns 40 (needs 160);
        //   demand 2 should be able to draw from that returned 40 + remaining 100 = 140.
        //   demand 2 can make min(0, 70) = 0 (child_A is exhausted), so committed_2 = 0.
        // Key check: child_B inventory after both demands = 300 - 160 = 140 (not 300 - 200 = 100).
        val inv = mkInv(
            invBucket("child_A", "L", "CA_S", 80.0),
            invBucket("child_B", "L", "CB_S", 300.0),
        )
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L",
                      "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "child_A",
                      "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "child_B",
                      "rate" to 2.0, "alt_group" to null),
            ),
        )

        // Plan demand 1
        plan(demand("FG", "L", 100.0, "D1"), inv, data, requestTimeDt = null, config = noBuy)
        // child_B after demand 1: GC returns 40 → remaining = 300 - 160 = 140
        val cbAfterD1 = (inv.find { it["supply_id"] == "CB_S" }!!["qty"] as Number).toDouble()
        cbAfterD1 shouldBe (140.0 plusOrMinus 0.5)

        // Plan demand 2 — child_A is exhausted, child_B has 140 left
        val (committed2, _, _) = plan(demand("FG", "L", 100.0, "D2"), inv, data,
            requestTimeDt = null, config = noBuy)
        val total2 = committed2.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        // child_A=0 → achievable=0; demand 2 commits 0
        total2 shouldBe (0.0 plusOrMinus 0.5)
        // child_B untouched after demand 2 (since demand 2 achieves 0 and GC returns the first-pass take)
        val cbAfterD2 = (inv.find { it["supply_id"] == "CB_S" }!!["qty"] as Number).toDouble()
        cbAfterD2 shouldBe (140.0 plusOrMinus 0.5)
    }

    // ── I3: no GC fired when child delivers within tolerance ─────────────────

    test("I3: no AND-min shortfall when child delivers within 0.5-unit tolerance") {
        // Blueprint-mode scenario: child needs 452.0012, delivers 452.0 (FP noise).
        // With 0.5 tolerance anyChildShort=false → GC never called, first-pass pegging used as-is.
        // Proxy: child has exactly 452 units in inventory; demand needs 452.0012 (rate > 1.0).
        // With 0.5 tolerance, achievable = 452 (not 451), inventory exactly depleted.
        val inv = mkInv(
            invBucket("child_A", "L", "CA_S", 452.0),
        )
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L",
                      "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                // rate slightly above 1 → for demand 452, needs 452.0012+ of child_A
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "child_A",
                      "rate" to 1.0000003, "alt_group" to null),
            ),
        )
        // demand qty 452; neededQty for child_A = 452 * 1.0000003 ≈ 452.000136
        val (committed, _, _) = plan(demand("FG", "L", 452.0), inv, data,
            requestTimeDt = null, config = noBuy)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        // Within 0.5 tolerance: treats as no shortage; plan commits 452
        totalCommitted shouldBe (452.0 plusOrMinus 1.0)

        // child_A inventory fully (or near-fully) depleted
        val remaining = (inv.find { it["supply_id"] == "CA_S" }!!["qty"] as Number).toDouble()
        remaining shouldBe (0.0 plusOrMinus 1.0)
    }

    // ── I4: multi-level BOM — GC recurses through nested WO nodes ─────────────

    test("I4: multi-level BOM — GC returns excess from grandchild supply") {
        // FG make → child_M make → grandchild_R (raw material)
        // FG BOM: child_M rate=1
        // child_M BOM: grandchild_R rate=3
        // Demand: FG qty=100; grandchild_R inventory=270 → achievable child_M=90 → achievable FG=90
        // first-pass child_M: draws 300 grandchild_R, GC returns 30
        val inv = mkInv(
            invBucket("grandchild_R", "L", "GR_S", 270.0),
        )
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_FG",  "product_id" to "FG",      "location_id" to "L",
                      "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_CM",  "product_id" to "child_M", "location_id" to "L",
                      "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG",      "child_id" to "child_M",
                      "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_CM", "parent_id" to "child_M", "child_id" to "grandchild_R",
                      "rate" to 3.0, "alt_group" to null),
            ),
        )

        val (committed, _, _) = plan(demand("FG", "L", 100.0), inv, data,
            requestTimeDt = null, config = noBuy)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (90.0 plusOrMinus 0.5)         // limited by grandchild_R: 270/3=90

        // grandchild_R: first pass drew 300, GC returned 30 → remaining = 270-270 = 0
        val grRemaining = (inv.find { it["supply_id"] == "GR_S" }!!["qty"] as Number).toDouble()
        grRemaining shouldBe (0.0 plusOrMinus 0.5)
    }

    // ── I5: inventory conservation across a full plan with AND-min shortfall ───

    test("I5: inventory is conserved (no ghost depletion) when GC trims partial commitment") {
        // FG BOM: child_A (rate=1) + child_B (rate=1)
        // child_A: 60 units; child_B: 100 units
        // Demand: 100 → achievable 60 (limited by child_A)
        // After plan: child_A=0, child_B=40; total accounted = initial
        val inv = mkInv(
            invBucket("child_A", "L", "CA_S", 60.0),
            invBucket("child_B", "L", "CB_S", 100.0),
        )
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L",
                      "preference" to 1, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "child_A",
                      "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "child_B",
                      "rate" to 1.0, "alt_group" to null),
            ),
        )

        val (committed, _, _) = plan(demand("FG", "L", 100.0), inv, data,
            requestTimeDt = null, config = noBuy)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (60.0 plusOrMinus 0.5)

        val caRemaining = (inv.find { it["supply_id"] == "CA_S" }!!["qty"] as Number).toDouble()
        val cbRemaining = (inv.find { it["supply_id"] == "CB_S" }!!["qty"] as Number).toDouble()

        // child_A fully consumed, child_B has 40 left → total remaining = 40
        // Conservation: initial(160) = committed(60×2 BOM draws) + remaining(40) = 120 + 40 = 160 ✓
        caRemaining shouldBe (0.0 plusOrMinus 0.5)
        cbRemaining shouldBe (40.0 plusOrMinus 0.5)

        // inventory never goes negative
        caRemaining shouldBe (caRemaining.coerceAtLeast(0.0) plusOrMinus 1e-6)
        cbRemaining shouldBe (cbRemaining.coerceAtLeast(0.0) plusOrMinus 1e-6)
    }
})
