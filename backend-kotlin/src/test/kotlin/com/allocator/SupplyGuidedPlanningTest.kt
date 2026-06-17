package com.allocator

import com.allocator.services.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for supply-guided planning: the two-loop model.
 *
 * Tests cover:
 *  A. Config parsing
 *  B. Demand-qty allocation mode (new "demand_qty" policy)
 *  C. Inventory-aware BOM walk (buildInventoryAwareNeedsMatrix)
 *  D. End-to-end supply-guided planning via runPlanning with supply_guided.enabled=true
 */
class SupplyGuidedPlanningTest : FunSpec({

    // ── helpers ───────────────────────────────────────────────────────────────

    fun supply(pid: String, lid: String, qty: Double, sid: String = "S_${pid}_${lid}"): Map<String, Any?> =
        mapOf("product_id" to pid, "location_id" to lid, "qty" to qty, "supply_id" to sid, "supply_date" to null)

    fun demand(id: String, pid: String, lid: String, qty: Double, priority: Int = 0): Map<String, Any?> =
        mapOf("demand_id" to id, "product_id" to pid, "location_id" to lid, "quantity" to qty, "priority" to priority, "request_due_time" to "2025-01-01")

    fun mkData(
        supplies: List<Map<String, Any?>> = emptyList(),
        methodMake: List<Map<String, Any?>> = emptyList(),
        methodBuy: List<Map<String, Any?>> = emptyList(),
        methodMove: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
        demands: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "supply" to supplies,
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to methodMove,
        "bom" to bom,
        "demand" to demands,
        "overrides" to emptyList(),
    )

    val supplyGuidedConfig = mapOf(
        "purchase_allowed" to false,
        "supply_guided" to mapOf("enabled" to true, "allocation_mode" to "demand_qty"),
    )

    // ── A. Config parsing ─────────────────────────────────────────────────────

    test("parseSupplyGuidedConfig: defaults when key absent") {
        val cfg = parseSupplyGuidedConfig(emptyMap<String, Any?>())
        cfg.enabled shouldBe false
        cfg.allocationMode shouldBe "demand_qty"
        cfg.maxCompensationPasses shouldBe 1
    }

    test("parseSupplyGuidedConfig: explicit values") {
        val raw = mapOf(
            "supply_guided" to mapOf(
                "enabled" to true,
                "allocation_mode" to "fair",
                "max_compensation_passes" to 3,
            )
        )
        val cfg = parseSupplyGuidedConfig(raw)
        cfg.enabled shouldBe true
        cfg.allocationMode shouldBe "fair"
        cfg.maxCompensationPasses shouldBe 3
    }

    test("parseSupplyGuidedConfig: unknown allocation_mode defaults to demand_qty") {
        val raw = mapOf("supply_guided" to mapOf("enabled" to true, "allocation_mode" to "bogus"))
        parseSupplyGuidedConfig(raw).allocationMode shouldBe "demand_qty"
    }

    // ── B. Demand-qty allocation mode ─────────────────────────────────────────

    test("demand_qty allocates proportionally to raw demand quantity") {
        // 100-unit supply; D1 needs 30 (BOM rate 1.0), D2 needs 150 (BOM rate 5.0 ⟹ demand 30 each).
        // With demand_qty mode each demand gets qty(d)/Σqty(d) share of the supply.
        // D1 demand_qty=30, D2 demand_qty=30 → equal split: 50 each.
        val candidates = listOf(
            AllocationCandidate(demandId = "D1", neededQty = 30.0,  priority = 0, demandQty = 30.0),
            AllocationCandidate(demandId = "D2", neededQty = 150.0, priority = 0, demandQty = 30.0),
        )
        val result = allocate(candidates, 100.0, "demand_qty")
        result["D1"]!! shouldBe (50.0 plusOrMinus 1e-6)
        result["D2"]!! shouldBe (50.0 plusOrMinus 1e-6)
    }

    test("demand_qty no-shortage: every demand gets its BOM need") {
        // 200-unit supply ≥ totalNeed=180 → no-shortage: give each its neededQty
        val candidates = listOf(
            AllocationCandidate("D1", 80.0, 0, demandQty = 100.0),
            AllocationCandidate("D2", 100.0, 0, demandQty = 50.0),
        )
        val result = allocate(candidates, 200.0, "demand_qty")
        result["D1"]!! shouldBe (80.0 plusOrMinus 1e-6)
        result["D2"]!! shouldBe (100.0 plusOrMinus 1e-6)
    }

    test("demand_qty fallback to proportional when demandQty all zero") {
        // Both candidates have demandQty=0 → fall back to proportional by neededQty
        val candidates = listOf(
            AllocationCandidate("D1", 60.0, 0, demandQty = 0.0),
            AllocationCandidate("D2", 40.0, 0, demandQty = 0.0),
        )
        val result = allocate(candidates, 50.0, "demand_qty")
        // proportional: 60/(60+40)*50=30, 40/100*50=20
        result["D1"]!! shouldBe (30.0 plusOrMinus 1e-6)
        result["D2"]!! shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("allocateSupplies propagates demandQuantities into candidates") {
        // Two demands; supply R@L = 20, totalNeed = 40 → shortage triggers demand_qty split.
        // demand_qty: D1 gets 20×(10/40)=5, D2 gets 20×(30/40)=15.
        val matrix = buildNeedsMatrix(
            demands = listOf(
                demand("D1", "FG", "L", qty = 10.0),
                demand("D2", "FG", "L", qty = 30.0),
            ),
            data = mkData(
                supplies = listOf(supply("R", "L", 20.0)),
                methodMake = listOf(
                    mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0),
                ),
                bom = listOf(
                    mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "R", "rate" to 1.0, "alt_group" to null),
                ),
            ),
        )
        val allocations = allocateSupplies(
            matrix = matrix,
            supplyTotals = mapOf(SupplyKey("R", "L") to 20.0),
            demandPriorities = mapOf("D1" to 0, "D2" to 0),
            mode = "demand_qty",
            demandQuantities = mapOf("D1" to 10.0, "D2" to 30.0),
        )
        // demand_qty proportional: 20 × (10/40) = 5.0, 20 × (30/40) = 15.0
        val sk = SupplyKey("R", "L")
        allocations.byRow["D1"]!![sk]!! shouldBe (5.0 plusOrMinus 1e-6)
        allocations.byRow["D2"]!![sk]!! shouldBe (15.0 plusOrMinus 1e-6)
    }

    // ── C. Inventory-aware BOM walk ───────────────────────────────────────────

    test("inventory-aware walk prunes non-supply path and misses deeper supply") {
        // FG can be made via:
        //   BOM_A → R1@L  (R1 has supply: direct supply-bearing child)
        //   BOM_B → R2@L  (R2 has NO supply, but R2 can be made from R3@L which DOES)
        // inventory-aware: only BOM_A path followed (R2 child is not supply-bearing).
        // naive:            both paths followed → reaches R3 through R2.
        val data = mkData(
            supplies = listOf(supply("R1", "L", 50.0), supply("R3", "L", 50.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A",  "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B",  "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_R2", "product_id" to "R2", "location_id" to "L", "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A",  "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B",  "parent_id" to "FG", "child_id" to "R2", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_R2", "parent_id" to "R2", "child_id" to "R3", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L", qty = 100.0))

        val aware = buildInventoryAwareNeedsMatrix(demands, data)
        val naive = buildNeedsMatrix(demands, data)

        // Inventory-aware: BOM_A chosen (R1 is supply-bearing); BOM_B pruned → R3 never discovered.
        aware.byRow["D1"] shouldNotBe null
        aware.byRow["D1"]!!.containsKey(SupplyKey("R1", "L")) shouldBe true
        aware.byRow["D1"]!!.containsKey(SupplyKey("R3", "L")) shouldBe false

        // Naive (union-all): BOM_B also traversed → R2 explored → R3 reached and emitted.
        naive.byRow["D1"]!!.containsKey(SupplyKey("R1", "L")) shouldBe true
        naive.byRow["D1"]!!.containsKey(SupplyKey("R3", "L")) shouldBe true
    }

    test("inventory-aware walk falls back to all paths when no direct child has supply") {
        // FG methods: BOM_A → R2 (no supply), BOM_B → R4 (no supply).
        // R2 can be made from R3 (has supply). R4 is a dead end.
        // When no method's direct children are supply-bearing, inventoryEdges stays empty
        // and the walker falls back to allMethodEdges — same as naive.
        val data = mkData(
            supplies = listOf(supply("R3", "L", 50.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM_A",  "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B",  "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_R2", "product_id" to "R2", "location_id" to "L", "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM_A",  "parent_id" to "FG", "child_id" to "R2", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B",  "parent_id" to "FG", "child_id" to "R4", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_R2", "parent_id" to "R2", "child_id" to "R3", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L", qty = 100.0))

        val aware = buildInventoryAwareNeedsMatrix(demands, data)
        val naive = buildNeedsMatrix(demands, data)

        // Both find R3 via the fallback (neither R2 nor R4 is supply-bearing → allMethodEdges).
        aware.byRow["D1"]!!.containsKey(SupplyKey("R3", "L")) shouldBe true
        naive.byRow["D1"]!!.containsKey(SupplyKey("R3", "L")) shouldBe true
        // R2 and R4 have no supply → not in matrix
        aware.byRow["D1"]!!.containsKey(SupplyKey("R2", "L")) shouldBe false
        aware.byRow["D1"]!!.containsKey(SupplyKey("R4", "L")) shouldBe false
        // Fallback: aware == naive (no pruning when no inventory-first path exists)
        aware.byRow["D1"] shouldBe naive.byRow["D1"]
    }

    // ── D. End-to-end: supply-guided routing in runPlanning ──────────────────

    test("supply-guided: two competing demands share a single supply proportionally by demand qty") {
        // FG made from R1 (1:1). Supply R1=60. D1 needs 10 FG, D2 needs 30 FG.
        // demand_qty allocation: D1 gets 60×(10/40)=15, D2 gets 60×(30/40)=45.
        // Both should commit fully (15 ≤ 10 needs only 10, but budget=15 so 10 consumed;
        // D2 needs 30, budget=45 ≥ 30 so fully committed).
        val data = mkData(
            supplies = listOf(supply("R1", "L", 60.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            ),
            demands = listOf(
                demand("D1", "FG", "L", qty = 10.0, priority = 0),
                demand("D2", "FG", "L", qty = 30.0, priority = 1),
            ),
        )
        val result = runPlanning(data, supplyGuidedConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>

        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        val d2Total = committed.filter { it["demand_id"] == "D2" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        // D1 committed exactly its demand (10 ≤ budget 15)
        d1Total shouldBe (10.0 plusOrMinus 1e-6)
        // D2 committed exactly its demand (30 ≤ budget 45)
        d2Total shouldBe (30.0 plusOrMinus 1e-6)
    }

    test("supply-guided: scarce supply shared fairly, both demands partially filled") {
        // R1=30. D1 needs 20, D2 needs 40 → total need 60 > supply 30.
        // demand_qty: D1=20/(20+40)=1/3 → 10; D2=40/60=2/3 → 20.
        // After commit: D1 commits 10, D2 commits 20 (both partial).
        val data = mkData(
            supplies = listOf(supply("R1", "L", 30.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            ),
            demands = listOf(
                demand("D1", "FG", "L", qty = 20.0),
                demand("D2", "FG", "L", qty = 40.0),
            ),
        )
        val result = runPlanning(data, supplyGuidedConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>

        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        val d2Total = committed.filter { it["demand_id"] == "D2" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        d1Total shouldBe (10.0 plusOrMinus 1e-6)
        d2Total shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("supply-guided routing does not fire when disabled") {
        // Legacy path: first demand consumes everything, second gets nothing.
        val data = mkData(
            supplies = listOf(supply("R1", "L", 30.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            ),
            demands = listOf(
                demand("D1", "FG", "L", qty = 20.0, priority = 0),
                demand("D2", "FG", "L", qty = 40.0, priority = 1),
            ),
        )
        val legacyConfig = mapOf("purchase_allowed" to false)
        val result = runPlanning(data, legacyConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>

        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        val d2Total = committed.filter { it["demand_id"] == "D2" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        // Legacy (FIFO): D1 gets all 20 (priority 0), D2 gets remaining 10
        d1Total shouldBe (20.0 plusOrMinus 1e-6)
        d2Total shouldBe (10.0 plusOrMinus 1e-6)
    }
})
