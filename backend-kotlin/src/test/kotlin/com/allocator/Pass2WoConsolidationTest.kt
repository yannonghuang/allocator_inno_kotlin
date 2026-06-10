package com.allocator

import com.allocator.services.consolidateWorkOrdersByTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Pass 2 — work-order timing/scheduling consolidation. Batches same-
 * (product, location, method, source) WOs that start within the same window into
 * fewer, larger orders, preserving total quantity.
 */
class Pass2WoConsolidationTest : FunSpec({

    fun wo(
        pid: String, lid: String, method: String, qty: Double,
        start: String, end: String, demand: String?, source: String? = null,
        failed: Boolean = false,
    ): Map<String, Any?> = buildMap {
        put("product_id", pid); put("location_id", lid); put("method", method)
        put("quantity", qty); put("start_time", start); put("end_time", end)
        put("demand_id", demand); put("location_source", source)
        if (failed) put("failed", true)
    }

    val data: Map<String, List<Map<String, Any?>>> = mapOf(
        "productlocation" to listOf(
            mapOf("product_id" to "RAW1", "location_id" to "L", "max_lot_size" to 1000.0),
        ),
    )

    fun qtyOf(w: Map<String, Any?>) = (w["quantity"] as Number).toDouble()

    test("same product/location/method in same window merge; total qty preserved") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 200.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 300.0, "2024-05-03", "2024-06-22", "D2"),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30)
        out.size shouldBe 1
        qtyOf(out[0]) shouldBe (500.0 plusOrMinus 1e-6)
        out[0]["consolidated"] shouldBe true
        out[0]["demand_id"] shouldBe null
        @Suppress("UNCHECKED_CAST")
        (out[0]["wo_competing_demands"] as List<String>) shouldContainExactlyInAnyOrder listOf("D1", "D2")
    }

    test("different products are NOT merged") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW2", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D2"),
        )
        consolidateWorkOrdersByTiming(wos, data, windowDays = 30).size shouldBe 2
    }

    test("same product in DIFFERENT windows are NOT merged") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 100.0, "2024-09-01", "2024-10-21", "D2"),
        )
        consolidateWorkOrdersByTiming(wos, data, windowDays = 7).size shouldBe 2
    }

    test("merged group collapses to ONE batch WO carrying total qty + lot_count") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 1500.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 1000.0, "2024-05-02", "2024-06-21", "D2"),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30)
        out.size shouldBe 1                                   // two lot-WOs → one batch order
        qtyOf(out[0]) shouldBe (2500.0 plusOrMinus 1e-6)
        out[0]["lot_count"] shouldBe 3                        // ceil(2500 / max_lot_size 1000)
    }

    test("singletons and failed WOs pass through unchanged") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "make", 50.0, "2024-05-01", "2024-05-10", "D2", failed = true),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30)
        out.size shouldBe 2
        out.none { it["consolidated"] == true } shouldBe true
    }
})
