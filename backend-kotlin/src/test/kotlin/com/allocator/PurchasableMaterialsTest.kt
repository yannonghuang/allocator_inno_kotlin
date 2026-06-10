package com.allocator

import com.allocator.services.maxMakeDepth
import com.allocator.services.plan
import com.allocator.services.purchasableSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Behavior contract for "selective purchase" — the `purchasable_materials`
 * whitelist that refines the all-or-nothing `purchase_allowed` gate.
 *
 * Semantics:
 *   • purchase_allowed=false                     → no buys (whitelist ignored)
 *   • purchase_allowed=true, no/empty whitelist  → all buys admitted (default)
 *   • purchase_allowed=true, non-empty whitelist → only listed products keep
 *                                                  their buy method; all others dropped
 */
class PurchasableMaterialsTest : FunSpec({

    fun mkData(
        methodBuy: List<Map<String, Any?>> = emptyList(),
        methodMake: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
        supply: List<Map<String, Any?>> = emptyList(),
        productLocation: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to emptyList(),
        "bom" to bom,
        "productlocation" to productLocation,
        "supply" to supply,
    )

    fun buy(productId: String, locationId: String = "L") =
        mapOf<String, Any?>("product_id" to productId, "location_id" to locationId, "preference" to 1, "lead_time" to 0.0)

    /** Mark a product as a RAW material at L — the whitelist only gates raw materials. */
    fun rawPl(productId: String, locationId: String = "L") =
        mapOf<String, Any?>("product_id" to productId, "location_id" to locationId, "prod_area" to "raw")

    fun supply(productId: String, locationId: String, qty: Double) =
        mapOf<String, Any?>(
            "supply_id" to "S_$productId$locationId", "product_id" to productId, "location_id" to locationId,
            "supply_date" to null, "qty" to qty, "demand_tag" to null,
        )

    fun mkInv(vararg buckets: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        buckets.map { it.toMutableMap() }.toMutableList()

    fun demand(productId: String, qty: Double, locationId: String = "L") =
        mapOf<String, Any?>(
            "demand_id" to "D_$productId", "product_id" to productId, "location_id" to locationId,
            "quantity" to qty, "request_due_time" to "2024-01-01",
        )

    fun committedQty(committed: List<Map<String, Any?>>): Double =
        committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

    // RAW1 + RAW2 each buyable at L and marked raw; no inventory (purchase is the only
    // supply). prod_area='raw' is required for the whitelist to apply — it gates raw
    // materials only (non-raw buyables stay admitted).
    val twoBuyables = mkData(
        methodBuy = listOf(buy("RAW1"), buy("RAW2")),
        productLocation = listOf(rawPl("RAW1"), rawPl("RAW2")),
    )

    // ── purchasableSet parsing ───────────────────────────────────────────────

    test("purchasableSet: absent / empty / blank-only ⇒ null (no restriction)") {
        purchasableSet(null) shouldBe null
        purchasableSet(emptyMap()) shouldBe null
        purchasableSet(mapOf("purchasable_materials" to emptyList<String>())) shouldBe null
        purchasableSet(mapOf("purchasable_materials" to listOf("  ", ""))) shouldBe null
        purchasableSet(mapOf("purchasable_materials" to "not-a-list")) shouldBe null
    }

    test("purchasableSet: trims and dedups non-empty entries") {
        purchasableSet(mapOf("purchasable_materials" to listOf(" RAW1 ", "RAW2", "RAW1"))) shouldBe
            setOf("RAW1", "RAW2")
    }

    // ── plan() gating ────────────────────────────────────────────────────────

    test("purchase_allowed=true, no whitelist ⇒ buy admitted (baseline)") {
        val cfg = mapOf<String, Any?>("purchase_allowed" to true)
        val (committed, _, pegging) = plan(demand("RAW1", 100.0), mkInv(), twoBuyables, requestTimeDt = null, config = cfg)
        committedQty(committed) shouldBe (100.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)
    }

    test("whitelist containing the product ⇒ buy admitted") {
        val cfg = mapOf<String, Any?>("purchase_allowed" to true, "purchasable_materials" to listOf("RAW1"))
        val (committed, _, _) = plan(demand("RAW1", 100.0), mkInv(), twoBuyables, requestTimeDt = null, config = cfg)
        committedQty(committed) shouldBe (100.0 plusOrMinus 1e-6)
    }

    test("whitelist excluding the product ⇒ buy dropped, demand unmet with whitelist explanation") {
        val cfg = mapOf<String, Any?>("purchase_allowed" to true, "purchasable_materials" to listOf("RAW1"))
        // RAW2 is buyable but not whitelisted → no method admitted. The unmet demand is
        // recorded as a `no_methods` bookkeeping row + pegging committed_qty 0.
        val (_, _, pegging) = plan(demand("RAW2", 100.0), mkInv(), twoBuyables, requestTimeDt = null, config = cfg)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (0.0 plusOrMinus 1e-6)
        pegging?.get("commit_reason") shouldBe "no_methods"
        (pegging?.get("failure_explanation") as? String).orEmpty() shouldContain "whitelist"
    }

    test("non-raw buyable stays admitted despite a whitelist (raw-scoped) ⇒ all-raw-selected ≡ all") {
        // SUB is buyable but NOT raw (no prod_area='raw' row). The whitelist controls raw
        // materials only, so SUB's buy is admitted even though it isn't in the list — which
        // is exactly why selecting every listed (raw) material equals an empty list.
        val data = mkData(
            methodBuy = listOf(buy("RAW1"), buy("SUB")),
            productLocation = listOf(rawPl("RAW1")),   // SUB intentionally not raw
        )
        val cfg = mapOf<String, Any?>("purchase_allowed" to true, "purchasable_materials" to listOf("RAW1"))
        val (committed, _, _) = plan(demand("SUB", 40.0), mkInv(), data, requestTimeDt = null, config = cfg)
        committedQty(committed) shouldBe (40.0 plusOrMinus 1e-6)
    }

    test("whitelist does not affect a different whitelisted product (selective, not global block)") {
        val cfg = mapOf<String, Any?>("purchase_allowed" to true, "purchasable_materials" to listOf("RAW1"))
        val (committed, _, _) = plan(demand("RAW1", 60.0), mkInv(), twoBuyables, requestTimeDt = null, config = cfg)
        committedQty(committed) shouldBe (60.0 plusOrMinus 1e-6)
    }

    test("purchase_allowed=false ⇒ whitelist ignored, no buys") {
        val cfg = mapOf<String, Any?>("purchase_allowed" to false, "purchasable_materials" to listOf("RAW1"))
        val (_, _, pegging) = plan(demand("RAW1", 100.0), mkInv(), twoBuyables, requestTimeDt = null, config = cfg)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (0.0 plusOrMinus 1e-6)
        pegging?.get("commit_reason") shouldBe "no_methods"
        (pegging?.get("failure_explanation") as? String).orEmpty() shouldContain "purchase_allowed=false"
    }

    // ── Purchase lots are concurrent, not marched forward ────────────────────

    test("a large purchase split by max_lot_size does NOT march forward in time") {
        // RAW1 buyable at L, lead 50d, max_lot_size 1000. A 5000-unit demand splits
        // into 5 lots — all must share one start/end window (~due − lead), not step
        // forward 50 days per lot (which is what produced WOs decades into the future).
        val data = mapOf(
            "method_make" to emptyList(),
            "method_buy" to listOf(mapOf<String, Any?>(
                "product_id" to "RAW1", "location_id" to "L", "preference" to 1, "lead_time" to 50.0,
                "lead_days_supply" to 50,
            )),
            "method_move" to emptyList(),
            "bom" to emptyList(),
            "productlocation" to listOf(mapOf<String, Any?>(
                "product_id" to "RAW1", "location_id" to "L", "prod_area" to "raw", "max_lot_size" to 1000.0,
            )),
            "supply" to emptyList(),
        )
        val cfg = mapOf<String, Any?>("purchase_allowed" to true)
        val (_, wos, _) = plan(demand("RAW1", 5000.0), mkInv(), data, requestTimeDt = null, config = cfg)
        val buys = wos.filter { it["method"] == "purchase" }
        buys.size shouldBe 5                              // 5000 / 1000
        // All lots share a single start AND end date → one wave, no forward march,
        // and each lot keeps the FULL 50-day procurement lead (not compressed).
        buys.map { it["start_time"] }.toSet().size shouldBe 1
        buys.map { it["end_time"] }.toSet().size shouldBe 1
        val s = java.time.LocalDate.parse(buys[0]["start_time"] as String)
        val e = java.time.LocalDate.parse(buys[0]["end_time"] as String)
        (e.toEpochDay() - s.toEpochDay()) shouldBe 50L
    }

    // ── maxMakeDepth respects the whitelist ──────────────────────────────────

    test("maxMakeDepth: buy edge bottoms out at 0 only when the product is purchasable") {
        val data = mkData(methodBuy = listOf(buy("RAW1")))
        // null whitelist ⇒ admitted ⇒ depth 0
        maxMakeDepth("RAW1", "L", data, true, mutableMapOf(), purchasable = null) shouldBe 0
        // whitelisted ⇒ depth 0
        maxMakeDepth("RAW1", "L", data, true, mutableMapOf(), purchasable = setOf("RAW1")) shouldBe 0
        // excluded ⇒ no admitted method, no supply ⇒ unreachable
        maxMakeDepth("RAW1", "L", data, true, mutableMapOf(), purchasable = setOf("OTHER")) shouldBe Int.MAX_VALUE
    }
})
