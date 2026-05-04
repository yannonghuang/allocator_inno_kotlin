package com.allocator

import com.allocator.services.maxMakeDepth
import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Validates the (B) feasibility-gate make-fallback added in 84e8167 +
 * mini-waterfall in 2f46de7.
 *
 * Setup (mirror case 171's 260-0385 shape):
 *   FG produced via move from L2 (preferred) OR make at L1 (alternative).
 *   move L2→L1 source has limited supply (qty-capped).
 *
 * Expected:
 *   - With move-only fallback, only the move qty is committed.
 *   - With make admitted via cache, the residual is filled by the make.
 */
class MakeFallbackTest : FunSpec({

    fun mkData(
        methodMake: List<Map<String, Any?>> = emptyList(),
        methodBuy: List<Map<String, Any?>> = emptyList(),
        methodMove: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
        productLocation: List<Map<String, Any?>> = emptyList(),
        supply: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to methodMove,
        "bom" to bom,
        "productlocation" to productLocation,
        "supply" to supply,
    )

    fun supply(productId: String, locationId: String, qty: Double, supplyId: String = "S_$productId$locationId") =
        mapOf<String, Any?>(
            "supply_id" to supplyId, "product_id" to productId, "location_id" to locationId,
            "supply_date" to null, "qty" to qty, "demand_tag" to null,
        )

    fun mkInv(vararg buckets: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        buckets.map { it.toMutableMap() }.toMutableList()

    test("maxMakeDepth: direct supply yields 0") {
        val data = mkData(
            supply = listOf(supply("X", "L1", 100.0)),
        )
        val cache = mutableMapOf<Pair<String, String>, Int>()
        maxMakeDepth("X", "L1", data, true, cache) shouldBe 0
    }

    test("maxMakeDepth: move-from-source with supply yields 0 (move is free)") {
        val data = mkData(
            methodMove = listOf(
                mapOf("product_id" to "X", "from_location_id" to "L2", "to_location_id" to "L1", "preference" to 0, "transit_time" to 0.0),
            ),
            supply = listOf(supply("X", "L2", 100.0)),
        )
        val cache = mutableMapOf<Pair<String, String>, Int>()
        maxMakeDepth("X", "L1", data, true, cache) shouldBe 0
    }

    test("maxMakeDepth: 1 real make to reach supplied leaf") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_X", "product_id" to "X", "location_id" to "L1", "preference" to 0, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_X", "parent_id" to "X", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            ),
            supply = listOf(supply("RAW", "L1", 100.0)),
        )
        val cache = mutableMapOf<Pair<String, String>, Int>()
        maxMakeDepth("X", "L1", data, true, cache) shouldBe 1
    }

    test("maxMakeDepth: 2 real makes deep") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_X", "product_id" to "X", "location_id" to "L1", "preference" to 0, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_Y", "product_id" to "Y", "location_id" to "L1", "preference" to 0, "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_X", "parent_id" to "X", "child_id" to "Y", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_Y", "parent_id" to "Y", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            ),
            supply = listOf(supply("RAW", "L1", 100.0)),
        )
        val cache = mutableMapOf<Pair<String, String>, Int>()
        maxMakeDepth("X", "L1", data, true, cache) shouldBe 2
    }

    test("maxMakeDepth: cycle handled, returns ∞ via cycled path but other path may be finite") {
        // X@L1 ↔ X@L2 via moves, plus X@L1 supply → depth=0.
        val data = mkData(
            methodMove = listOf(
                mapOf("product_id" to "X", "from_location_id" to "L2", "to_location_id" to "L1", "preference" to 0, "transit_time" to 0.0),
                mapOf("product_id" to "X", "from_location_id" to "L1", "to_location_id" to "L2", "preference" to 0, "transit_time" to 0.0),
            ),
            supply = listOf(supply("X", "L1", 100.0)),
        )
        val cache = mutableMapOf<Pair<String, String>, Int>()
        maxMakeDepth("X", "L1", data, true, cache) shouldBe 0
        maxMakeDepth("X", "L2", data, true, cache) shouldBe 0  // via move from L1 (supplied)
    }

    test("make-fallback fires after partial move slot, produces extra qty") {
        // Demand FG@L1 qty=100. supply at L2=30 only. move L2→L1 (gets 30).
        // make at L1 via BOM_FG → RAW; supply RAW@L1 = 200.
        // Expected: move covers 30, then make covers 70 from RAW@L1.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_FG", "product_id" to "FG", "location_id" to "L1", "preference" to 1, "lead_time" to 0.0),
            ),
            methodMove = listOf(
                mapOf("product_id" to "FG", "from_location_id" to "L2", "to_location_id" to "L1", "preference" to 0, "transit_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            ),
            supply = listOf(
                supply("FG", "L2", 30.0, "S_FG_L2"),
                supply("RAW", "L1", 200.0, "S_RAW_L1"),
            ),
        )
        val inventory = mkInv(
            supply("FG", "L2", 30.0, "S_FG_L2"),
            supply("RAW", "L1", 200.0, "S_RAW_L1"),
        )
        val cache = mutableMapOf<Pair<String, String>, Int>()
        val demand = mapOf<String, Any?>(
            "demand_id" to "D1", "product_id" to "FG", "location_id" to "L1",
            "quantity" to 100.0, "request_due_time" to "2024-01-01",
        )
        val (committed, _, _) = plan(demand, inventory, data, requestTimeDt = null, feasibilityCache = cache)
        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        // Should fully fill: 30 from move + 70 from make
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
    }
})
