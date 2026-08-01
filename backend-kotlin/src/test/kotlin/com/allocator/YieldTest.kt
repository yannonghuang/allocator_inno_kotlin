package com.allocator

import com.allocator.services.plan
import com.allocator.services.variantsForMake
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Behavior contract for `method_make.csv`'s YIELD column: a lossy make process needs MORE input
 * to net the same finished output — childQty = parentQty x rate / yield, not parentQty x rate.
 * Absent/non-positive YIELD means no loss (equivalent to 1.0), so existing cases without a YIELD
 * column are unaffected — see variantsForMake's own doc.
 */
class YieldTest : FunSpec({

    fun makeMethod(bomId: String, pid: String, lid: String, yield: Double? = null) =
        mapOf<String, Any?>("bom_id" to bomId, "product_id" to pid, "location_id" to lid, "preference" to 1, "lead_time" to 0.0, "yield" to yield)

    fun bomRow(bomId: String, parent: String, child: String, rate: Double) =
        mapOf<String, Any?>("bom_id" to bomId, "parent_id" to parent, "child_id" to child, "rate" to rate, "alt_group" to null)

    // ── variantsForMake unit tests ───────────────────────────────────────────

    test("variantsForMake: absent YIELD behaves exactly as rate alone (no regression for existing cases)") {
        val data = mapOf("bom" to listOf(bomRow("B", "P", "C", rate = 2.0)))
        val method = makeMethod("B", "P", "L")
        val variants = variantsForMake("P", "L", 10.0, method, data)
        variants.size shouldBe 1
        val childQty = (variants.first().second.first()["quantity"] as Number).toDouble()
        childQty shouldBe (20.0 plusOrMinus 1e-9)  // 10 * 2.0 / 1.0
    }

    test("variantsForMake: YIELD < 1 inflates child quantity beyond rate alone") {
        val data = mapOf("bom" to listOf(bomRow("B", "P", "C", rate = 2.0)))
        val method = makeMethod("B", "P", "L", yield = 0.8)
        val variants = variantsForMake("P", "L", 10.0, method, data)
        val childQty = (variants.first().second.first()["quantity"] as Number).toDouble()
        childQty shouldBe (25.0 plusOrMinus 1e-9)  // 10 * 2.0 / 0.8
    }

    test("variantsForMake: YIELD = 1 is a no-op") {
        val data = mapOf("bom" to listOf(bomRow("B", "P", "C", rate = 1.0)))
        val method = makeMethod("B", "P", "L", yield = 1.0)
        val variants = variantsForMake("P", "L", 40.0, method, data)
        val childQty = (variants.first().second.first()["quantity"] as Number).toDouble()
        childQty shouldBe (40.0 plusOrMinus 1e-9)
    }

    test("variantsForMake: non-positive YIELD falls back to no loss, not a divide-by-zero blowup") {
        val data = mapOf("bom" to listOf(bomRow("B", "P", "C", rate = 1.0)))
        val method = makeMethod("B", "P", "L", yield = 0.0)
        val variants = variantsForMake("P", "L", 40.0, method, data)
        val childQty = (variants.first().second.first()["quantity"] as Number).toDouble()
        childQty shouldBe (40.0 plusOrMinus 1e-9)
    }

    // ── end-to-end: yield loss compounds across a 2-level make chain ─────────

    test("plan: YIELD loss compounds across levels — raw material draw exceeds rate-only expectation") {
        // FG -> (make, yield 0.9) -> MID -> (make, yield 0.8) -> RAW (buy, abundant).
        // rate=1.0 at both levels, so a naive rate-only model would draw exactly 100 of RAW for
        // a 100-unit FG demand. With yield: MID needed = 100/0.9; RAW needed = (100/0.9)/0.8.
        val data = mapOf(
            "method_make" to listOf(
                mapOf<String, Any?>("bom_id" to "B_FG", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0, "yield" to 0.9),
                mapOf<String, Any?>("bom_id" to "B_MID", "product_id" to "MID", "location_id" to "L", "preference" to 1, "lead_time" to 0.0, "yield" to 0.8),
            ),
            "method_buy" to listOf(
                mapOf<String, Any?>("product_id" to "RAW", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(
                mapOf<String, Any?>("bom_id" to "B_FG", "parent_id" to "FG", "child_id" to "MID", "rate" to 1.0, "alt_group" to null),
                mapOf<String, Any?>("bom_id" to "B_MID", "parent_id" to "MID", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            ),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
        )
        val demand = mapOf<String, Any?>(
            "demand_id" to "D1", "product_id" to "FG", "location_id" to "L",
            "quantity" to 100.0, "request_due_time" to "2024-01-01",
        )
        val (committed, wos, _) = plan(demand, mutableListOf(), data, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true))

        val fgCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        fgCommitted shouldBe (100.0 plusOrMinus 1e-6)

        val rawPurchased = wos.filter { it["method"] == "purchase" && it["product_id"] == "RAW" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        val expectedRaw = 100.0 / 0.9 / 0.8
        // WO quantity is rounded to a whole unit at the API boundary (roundQty) — tolerance
        // must cover that rounding, not just floating-point slop.
        rawPurchased shouldBe (expectedRaw plusOrMinus 1.0)
        (rawPurchased > 100.0) shouldBe true  // sanity: yield loss must inflate consumption, never shrink it
    }
})
