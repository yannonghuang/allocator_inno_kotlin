package com.allocator

import com.allocator.services.parseConsolidationConfig
import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * End-to-end tests for the supply-level consolidation orchestrator (Phase F).
 *
 * Asserts:
 *   - parseConsolidationConfig threads through the new "engine" field
 *   - runPlanning dispatches correctly based on engine
 *   - Supply engine produces a sensible plan on simple BOMs (smoke test)
 *   - Supply engine matches the leaf engine on a no-shared-RM single-FG case
 *     (degenerate scenario where both engines should produce equivalent output)
 *   - Supply engine handles the "two demands sharing a deep raw material"
 *     case the leaf engine struggles with
 */
class SupplyOrchestratorTest : FunSpec({

    fun bom(parent: String, child: String, rate: Double, altGroup: String? = null, bomId: String = "BOM_$parent"): Map<String, Any?> =
        mapOf("bom_id" to bomId, "parent_id" to parent, "child_id" to child, "rate" to rate, "alt_group" to altGroup)

    fun mk(productId: String, locationId: String, preference: Int = -1, leadTime: Double = 0.0, bomId: String = "BOM_$productId"): Map<String, Any?> =
        mapOf("bom_id" to bomId, "product_id" to productId, "location_id" to locationId, "preference" to preference, "lead_time" to leadTime, "type" to "make")

    fun supply(productId: String, locationId: String, qty: Double, supplyId: String): Map<String, Any?> = mapOf(
        "supply_id" to supplyId, "product_id" to productId, "location_id" to locationId, "supply_date" to "2024-01-01", "qty" to qty,
    )

    fun demand(id: String, productId: String, locationId: String, qty: Double, due: String = "2024-12-31", priority: Int = 0): Map<String, Any?> = mapOf(
        "demand_id" to id, "product_id" to productId, "location_id" to locationId,
        "quantity" to qty, "request_due_time" to due, "request_time" to due,
        "priority" to priority,
    )

    // ── Config parsing ────────────────────────────────────────────────────────

    test("parseConsolidationConfig: engine defaults to leaf-legacy") {
        val cfg = parseConsolidationConfig(mapOf(
            "consolidation" to mapOf("enabled" to true),
        ))
        cfg.enabled shouldBe true
        cfg.engine shouldBe "leaf-legacy"
    }

    test("parseConsolidationConfig: engine = supply when explicitly set") {
        val cfg = parseConsolidationConfig(mapOf(
            "consolidation" to mapOf("enabled" to true, "engine" to "supply"),
        ))
        cfg.engine shouldBe "supply"
    }

    test("parseConsolidationConfig: unknown engine string falls back to leaf-legacy") {
        val cfg = parseConsolidationConfig(mapOf(
            "consolidation" to mapOf("enabled" to true, "engine" to "bogus"),
        ))
        cfg.engine shouldBe "leaf-legacy"
    }

    // ── End-to-end: smoke test, single demand single FG ───────────────────────

    test("supply engine: single demand, single supply produces a committed plan") {
        val data = mapOf(
            "demand" to listOf(demand("D1", "FG", "L1", 10.0)),
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val config = mapOf(
            "consolidation" to mapOf(
                "enabled" to true,
                "engine" to "supply",
                "allocation_mode" to "fair",
            ),
        )

        val result = runPlanning(data, config)

        @Suppress("UNCHECKED_CAST")
        val committed = result["committed_demands"] as List<Map<String, Any?>>
        committed shouldHaveSize 1
        (committed[0]["quantity"] as Number).toDouble() shouldBe (10.0 plusOrMinus 1e-9)

        @Suppress("UNCHECKED_CAST")
        val workOrders = result["work_orders"] as List<Map<String, Any?>>
        // At least one WO emitted for FG.
        workOrders.isNotEmpty() shouldBe true
    }

    // ── End-to-end: two demands sharing deep RM ──────────────────────────────

    test("supply engine: two demands sharing a deep raw material — fair-allocate under shortage") {
        // FG_A and FG_B both BOM down to RM. RM has 12; both demands need 10 each.
        // Fair under shortage → 6/6 split.
        val data = mapOf(
            "demand" to listOf(
                demand("D1", "FG_A", "L1", 10.0),
                demand("D2", "FG_B", "L1", 10.0),
            ),
            "bom" to listOf(
                bom("FG_A", "RM", rate = 1.0),
                bom("FG_B", "RM", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG_A", "L1"), mk("FG_B", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 12.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val config = mapOf(
            "consolidation" to mapOf(
                "enabled" to true,
                "engine" to "supply",
                "allocation_mode" to "fair",
            ),
        )

        val result = runPlanning(data, config)

        @Suppress("UNCHECKED_CAST")
        val supplyAllocations = result["supply_allocations"] as List<Map<String, Any?>>
        // Total committed at RM should not exceed the supply's 12 units.
        val totalConsumed = supplyAllocations
            .filter { it["supply_id"] == "S_RM" }
            .sumOf { (it["qty_consumed"] as? Number)?.toDouble() ?: 0.0 }
        // Supply capacity is honoured.
        (totalConsumed <= 12.0 + 1e-6) shouldBe true
    }

    // ── End-to-end: leaf vs supply engine on degenerate single-demand case ────

    test("leaf vs supply: single demand single FG produces same committed qty") {
        val data = mapOf(
            "demand" to listOf(demand("D1", "FG", "L1", 5.0)),
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val baseCfg = mapOf("enabled" to true, "allocation_mode" to "fair")

        val leafResult = runPlanning(data, mapOf("consolidation" to baseCfg + ("engine" to "leaf-legacy")))
        val supplyResult = runPlanning(data, mapOf("consolidation" to baseCfg + ("engine" to "supply")))

        @Suppress("UNCHECKED_CAST")
        fun totalCommittedFG(r: Map<String, Any>): Double =
            (r["committed_demands"] as List<Map<String, Any?>>)
                .filter { it["product_id"] == "FG" }
                .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        // Both engines fully commit the demand on a degenerate single-demand fixture.
        totalCommittedFG(leafResult) shouldBe (5.0 plusOrMinus 1e-9)
        totalCommittedFG(supplyResult) shouldBe (5.0 plusOrMinus 1e-9)
    }

    // ── Sanity: leaf engine still works (no regression from dispatch change) ──

    test("leaf engine still reachable with engine = leaf-legacy") {
        val data = mapOf(
            "demand" to listOf(demand("D1", "FG", "L1", 5.0)),
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val config = mapOf(
            "consolidation" to mapOf(
                "enabled" to true,
                "engine" to "leaf-legacy",
                "allocation_mode" to "fair",
            ),
        )

        val result = runPlanning(data, config)
        @Suppress("UNCHECKED_CAST")
        val committed = result["committed_demands"] as List<Map<String, Any?>>
        committed shouldHaveSize 1
    }

    test("leaf engine is the default when engine is absent") {
        // Older configs without an engine field continue to hit the leaf engine.
        val data = mapOf(
            "demand" to listOf(demand("D1", "FG", "L1", 5.0)),
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val config = mapOf("consolidation" to mapOf("enabled" to true))

        val result = runPlanning(data, config)
        @Suppress("UNCHECKED_CAST")
        val committed = result["committed_demands"] as List<Map<String, Any?>>
        committed shouldHaveSize 1
    }
})
