package com.allocator

import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * End-to-end integration coverage for the new `max_methods` + `split_mechanism`
 * axes on method_selection.
 *
 * Uses the same minimal-fixture pattern as PlanningEngineEqualSplitCapTest:
 * an FG with two make methods (BOM_A → R1, BOM_B → R2), each with abundant
 * supply so the planner doesn't hit a bottleneck. The methods carry distinct
 * `preference` ints so we can verify rank-based weighting.
 *
 * What we're verifying that the unit tests for the helpers can't easily prove:
 *   - `selectAndSplitMethods` is actually wired into `plan()` (vs being dead
 *     code).
 *   - The resulting WO qtys match the mechanism's allocation.
 *   - Conservation holds: Σ committed across slots == requested demand qty.
 *   - max_methods=1 short-circuits to the existing single-method path.
 */
class MaxMethodsSplitMechanismTest : FunSpec({

    fun mkData(
        methodMake: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "method_make" to methodMake,
        "method_buy" to emptyList(),
        "method_move" to emptyList(),
        "bom" to bom,
        "productlocation" to emptyList(),
    )

    fun mkInv(vararg buckets: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        buckets.map { it.toMutableMap() }.toMutableList()

    fun supply(productId: String, locationId: String, qty: Double, supplyId: String = "S_$productId"): Map<String, Any?> =
        mapOf("supply_id" to supplyId, "product_id" to productId, "location_id" to locationId,
              "supply_date" to null, "qty" to qty, "demand_tag" to null)

    fun fixture(): Pair<Map<String, List<Map<String, Any?>>>, MutableList<MutableMap<String, Any?>>> {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG", "location_id" to "L", "preference" to 5, "lead_time" to 0.0),
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
        return data to inventory
    }

    val baseDemand = mapOf(
        "demand_id" to "D1",
        "product_id" to "FG",
        "location_id" to "L",
        "quantity" to 100.0,
        "request_due_time" to "2024-01-01",
    )

    test("max_methods=1 → single best method, full demand to it") {
        val (data, inventory) = fixture()
        val config = mapOf(
            "purchase_allowed" to false,
            "method_selection" to mapOf("max_methods" to 1, "split_mechanism" to "equal"),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val (committed, wos, pegging) = plan(baseDemand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)
        // With cap=1, only ONE make WO emitted (the cascade picks pref=1 by default).
        val makeWos = wos.filter { it["method"] == "make" }
        makeWos.size shouldBe 1
    }

    test("max_methods=2 + equal → demand split 50/50 across both methods") {
        val (data, inventory) = fixture()
        val config = mapOf(
            "purchase_allowed" to false,
            "method_selection" to mapOf("max_methods" to 2, "split_mechanism" to "equal"),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val (committed, wos, pegging) = plan(baseDemand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)

        val makeWos = wos.filter { it["method"] == "make" }
        makeWos.size shouldBe 2
        // Each WO got half the demand (largest-remainder for integer demand=100).
        val qtys = makeWos.map { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }.sorted()
        qtys shouldBe listOf(50.0, 50.0)
    }

    test("max_methods=2 + preference → demand weighted by dense rank (1/1 vs 1/2)") {
        // Methods have prefs [1, 5] → ranks [1, 2] → weights [1, 1/2] → normalized [2/3, 1/3].
        // For integer demand=99 (multiple of 3), expect [66, 33] cleanly.
        val (data, inventory) = fixture()
        val config = mapOf(
            "purchase_allowed" to false,
            "method_selection" to mapOf("max_methods" to 2, "split_mechanism" to "preference"),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val demand = baseDemand + ("quantity" to 99.0)
        val (committed, wos, pegging) = plan(demand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (99.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (99.0 plusOrMinus 1e-6)

        val makeWos = wos.filter { it["method"] == "make" }
        makeWos.size shouldBe 2
        // Sorted desc: 66 from rank-1 method, 33 from rank-2.
        val qtys = makeWos.map { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }.sortedDescending()
        qtys shouldBe listOf(66.0, 33.0)
    }

    test("max_methods=2 + score → splits the demand and conservation holds") {
        // The exact ratio depends on the simulated commit_time / inventory / purchase
        // metrics for each method (both make-from-supply paths look symmetric here),
        // so we only assert: two methods used, total committed == demand, and each
        // slot got a positive non-zero share.
        val (data, inventory) = fixture()
        val config = mapOf(
            "purchase_allowed" to false,
            "method_selection" to mapOf(
                "mode" to "elaborate",
                "depth" to 2,
                "max_methods" to 2,
                "split_mechanism" to "score",
                "score_weights" to mapOf("commit_time" to 1.0, "inventory_consumed" to 0.0, "purchase" to 0.0),
            ),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val (committed, wos, pegging) = plan(baseDemand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        (pegging?.get("committed_qty") as? Number)?.toDouble() shouldBe (100.0 plusOrMinus 1e-6)

        val makeWos = wos.filter { it["method"] == "make" }
        makeWos.size shouldBe 2
        val qtys = makeWos.map { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        qtys.sum() shouldBe (100.0 plusOrMinus 1e-6)
        // Both slots got at least 1 (no degenerate zero-share). Score-tied methods
        // hit the equal-split fallback so we get [50, 50] in this fixture.
        qtys.forEach { it shouldBe (it.coerceAtLeast(1.0)) }
    }

    test("legacy multiple=true + no max_methods → resolves to max_methods=2 (default)") {
        // Verifies the legacy resolution path documented in the design doc: a
        // saved config with `multiple: true` and no `max_methods` should now
        // behave like `max_methods=2, split_mechanism=equal` (default).
        val (data, inventory) = fixture()
        val config = mapOf(
            "purchase_allowed" to false,
            "method_selection" to mapOf("multiple" to true),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val (committed, wos, _) = plan(baseDemand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        val makeWos = wos.filter { it["method"] == "make" }
        makeWos.size shouldBe 2  // top-2 of the 2 available, equal split
        val qtys = makeWos.map { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }.sorted()
        qtys shouldBe listOf(50.0, 50.0)
    }

    test("legacy multiple=false + no max_methods → resolves to single-method") {
        val (data, inventory) = fixture()
        val config = mapOf(
            "purchase_allowed" to false,
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val (committed, wos, _) = plan(baseDemand, inventory, data, requestTimeDt = null, config = config)

        val totalCommitted = committed.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        totalCommitted shouldBe (100.0 plusOrMinus 1e-6)
        val makeWos = wos.filter { it["method"] == "make" }
        makeWos.size shouldBe 1  // single method, full demand
    }
})
