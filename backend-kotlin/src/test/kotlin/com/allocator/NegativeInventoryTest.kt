package com.allocator

import com.allocator.services.NegativeInventoryLot
import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Negative starting inventory (raw materials / purchase only): a pre-existing deficit at
 * (product_id, location_id) is absorbed by the FIRST demand that triggers a PURCHASE work
 * order at that exact node — enlarging the purchase, never a make. See
 * NegativeInventoryLot's doc and planMethodSlot's purchase branch.
 */
class NegativeInventoryTest : FunSpec({

    fun mkData(
        methodBuy: List<Map<String, Any?>> = emptyList(),
        methodMake: List<Map<String, Any?>> = emptyList(),
        productLocation: List<Map<String, Any?>> = emptyList(),
        supply: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to emptyList(),
        "bom" to emptyList(),
        "productlocation" to productLocation,
        "supply" to supply,
    )

    fun buy(productId: String, locationId: String = "L") =
        mapOf<String, Any?>("product_id" to productId, "location_id" to locationId, "preference" to 1, "lead_time" to 0.0)

    fun rawPl(productId: String, locationId: String = "L") =
        mapOf<String, Any?>("product_id" to productId, "location_id" to locationId, "prod_area" to "raw")

    fun mkInv(): MutableList<MutableMap<String, Any?>> = mutableListOf()

    fun demand(productId: String, qty: Double, locationId: String = "L") =
        mapOf<String, Any?>(
            "demand_id" to "D_$productId", "product_id" to productId, "location_id" to locationId,
            "quantity" to qty, "request_due_time" to "2024-01-01",
        )

    val cfg = mapOf<String, Any?>("purchase_allowed" to true)
    val data = mkData(methodBuy = listOf(buy("RAW1")), productLocation = listOf(rawPl("RAW1")))
    val key = Pair("RAW1", "L")

    test("purchase WO is enlarged by the deficit; demand's own committed qty stays at its real need") {
        val pending = mutableMapOf(key to listOf(NegativeInventoryLot("NEG1", -1424.0, "2024-01-01")))
        val (committed, wos, pegging) = plan(
            demand("RAW1", 500.0), mkInv(), data, requestTimeDt = null, config = cfg,
            negativeInventoryPending = pending,
        )

        // Demand's own reported fulfillment is unaffected by the deficit — it only ever
        // asked for (and is credited with) 500.
        committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (500.0 plusOrMinus 1e-6)

        // The flat work-order list (what actually gets purchased) is enlarged to 500 + 1424.
        wos.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (1924.0 plusOrMinus 1e-6)

        // Pegging tree: the negative_inventory=true leaf is a genuine CHILD of the enlarged WO
        // (nested alongside the WO's own purchase leaf) — "this purchase's total splits into
        // what the demand needed and what it paid down" — not a demand-level sibling of the WO.
        @Suppress("UNCHECKED_CAST")
        val children = pegging?.get("children") as List<Map<String, Any?>>
        val woNode = children.first { it["type"] == "work_order" }
        (woNode["quantity"] as Number).toDouble() shouldBe (1924.0 plusOrMinus 1e-6)
        @Suppress("UNCHECKED_CAST")
        val woChildren = woNode["children"] as List<Map<String, Any?>>
        val negLeaf = woChildren.first { it["negative_inventory"] == true }
        (negLeaf["quantity"] as Number).toDouble() shouldBe (-1424.0 plusOrMinus 1e-6)
        negLeaf["supply_id"] shouldBe "NEG1"
        val purchaseLeaf = woChildren.first { it["type"] == "purchase" }
        (purchaseLeaf["quantity"] as Number).toDouble() shouldBe (500.0 plusOrMinus 1e-6)

        // First-touch-wins: the pending entry is drained after absorption.
        pending.containsKey(key) shouldBe false
    }

    test("a second demand at the same node after absorption gets the normal, unenlarged purchase") {
        val pending = mutableMapOf(key to listOf(NegativeInventoryLot("NEG1", -1424.0, "2024-01-01")))
        plan(demand("RAW1", 500.0), mkInv(), data, requestTimeDt = null, config = cfg, negativeInventoryPending = pending)

        val (_, wos2, _) = plan(
            demand("RAW1", 200.0), mkInv(), data, requestTimeDt = null, config = cfg,
            negativeInventoryPending = pending,
        )
        wos2.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (200.0 plusOrMinus 1e-6)
    }

    test("a make-only product never absorbs a pending deficit — it stays pending for a later purchase") {
        val makeData = mkData(
            methodMake = listOf(mapOf<String, Any?>(
                "product_id" to "MAKE1", "location_id" to "L", "preference" to 1, "lead_time" to 0.0, "yield" to 1.0,
            )),
        )
        val makeKey = Pair("MAKE1", "L")
        val pending = mutableMapOf(makeKey to listOf(NegativeInventoryLot("NEG2", -50.0, "2024-01-01")))
        val (_, wos, pegging) = plan(
            demand("MAKE1", 300.0), mkInv(), makeData, requestTimeDt = null, config = cfg,
            negativeInventoryPending = pending,
        )

        wos.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe (300.0 plusOrMinus 1e-6)
        @Suppress("UNCHECKED_CAST")
        val children = (pegging?.get("children") as? List<Map<String, Any?>>).orEmpty()
        children.none { it["negative_inventory"] == true } shouldBe true
        pending.containsKey(makeKey) shouldBe true
    }
})
