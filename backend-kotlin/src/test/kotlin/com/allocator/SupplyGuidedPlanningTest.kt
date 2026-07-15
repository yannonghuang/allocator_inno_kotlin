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
        productlocation: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "supply" to supplies,
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to methodMove,
        "bom" to bom,
        "demand" to demands,
        "overrides" to emptyList(),
        "productlocation" to productlocation,
    )

    val supplyGuidedConfig = mapOf(
        "purchase_allowed" to false,
        "supply_guided" to mapOf("enabled" to true, "allocation_mode" to "demand_qty"),
    )

    // ── A. Config parsing ─────────────────────────────────────────────────────

    test("parseSupplyGuidedConfig: defaults when key absent") {
        val cfg = parseSupplyGuidedConfig(emptyMap<String, Any?>())
        cfg.enabled shouldBe true
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

    test("reachability: all reachable supply paths are included regardless of inventory preference") {
        // FG can be made via:
        //   BOM_A → R1@L  (R1 has supply)
        //   BOM_B → R2@L  (R2 has no supply, but R2 can be made from R3@L which does)
        // Reachability-based budgeting follows union-all paths → both R1 and R3 are reached.
        // buildInventoryAwareNeedsMatrix is now an alias for buildNeedsMatrix (same behavior).
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

        // Both follow all paths: D1 competes for R1 (direct) and R3 (via R2).
        aware.byRow["D1"] shouldNotBe null
        aware.byRow["D1"]!!.containsKey(SupplyKey("R1", "L")) shouldBe true
        aware.byRow["D1"]!!.containsKey(SupplyKey("R3", "L")) shouldBe true
        // Both functions are identical under reachability-based budgeting.
        aware.byRow["D1"] shouldBe naive.byRow["D1"]
    }

    // ── C1. Simple test + simple claim (buildNeedsMatrix) ────────────────────

    test("request map: demand request weight equals raw demand quantity (not BOM-rate-adjusted)") {
        // D1 needs FG@L (qty=100). FG is made from R3@L at rate 2.0. R3 has supply.
        // Reachability-based budgeting: D1's request weight = demand qty = 100 (not 100×2=200).
        // BOM rates are a planning concern, not a budgeting concern.
        val data = mkData(
            supplies = listOf(supply("R3", "L", 500.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "R3", "rate" to 2.0, "alt_group" to null),
            ),
        )
        val matrix = buildNeedsMatrix(listOf(demand("D1", "FG", "L", qty = 100.0)), data)
        matrix.byRow["D1"]!![SupplyKey("R3", "L")]!! shouldBe (100.0 plusOrMinus 1e-6)
    }

    test("request map: demand appears in supply column even when an alternate supply-bearing path exists") {
        // D1 can reach R3@L via BOM_B→R2→R3, but FG also has BOM_A→R1 (R1 has supply).
        // buildNeedsMatrix (union-all): D1 appears in BOTH R1@L and R3@L columns.
        // This is the competing-demand correctness guarantee: no demand is excluded
        // from a supply column it can reach via any BOM path.
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
        val matrix = buildNeedsMatrix(listOf(demand("D1", "FG", "L", qty = 100.0)), data)
        matrix.byRow["D1"]!!.containsKey(SupplyKey("R1", "L")) shouldBe true
        matrix.byRow["D1"]!!.containsKey(SupplyKey("R3", "L")) shouldBe true
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

    test("supply-guided: proportional budget is binding constraint (3:1 demand ratio, supply=50% of total)") {
        // FG made from R1 (1:1). Supply R1=20. D1 needs 30, D2 needs 10 → total demand 40.
        // R1 qualifies as critical: in method_buy (UI purchasable-list candidate) + prod_area='raw'
        // + NOT in purchasable_materials config (unselected/unchecked by user).
        // demand_qty: D1 budget = 30/40 × 20 = 15; D2 budget = 10/40 × 20 = 5.
        // Both budgets are BELOW actual demand → budgets are the binding constraint.
        // Without supply-guided (FIFO, D1 listed first): D1 gets 20, D2 gets 0.
        val data = mkData(
            supplies = listOf(supply("R1", "L", 20.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            ),
            methodBuy = listOf(
                mapOf("product_id" to "R1", "location_id" to "L", "preference" to 1),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            ),
            demands = listOf(
                demand("D1", "FG", "L", qty = 30.0),
                demand("D2", "FG", "L", qty = 10.0),
            ),
            productlocation = listOf(
                mapOf("product_id" to "R1", "location_id" to "L", "prod_area" to "raw"),
            ),
        )
        val result = runPlanning(data, supplyGuidedConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>

        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        val d2Total = committed.filter { it["demand_id"] == "D2" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        d1Total shouldBe (15.0 plusOrMinus 1e-6)
        d2Total shouldBe (5.0 plusOrMinus 1e-6)
    }

    test("supply-guided: scarce supply shared fairly, both demands partially filled") {
        // R1=30. D1 needs 20, D2 needs 40 → total need 60 > supply 30.
        // demand_qty: D1=20/(20+40)=1/3 → 10; D2=40/60=2/3 → 20.
        // R1 qualifies as critical: in method_buy (UI purchasable-list candidate) + prod_area='raw'
        // + NOT in purchasable_materials config (unselected/unchecked by user).
        val data = mkData(
            supplies = listOf(supply("R1", "L", 30.0)),
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            ),
            methodBuy = listOf(
                mapOf("product_id" to "R1", "location_id" to "L", "preference" to 1),
            ),
            bom = listOf(
                mapOf("bom_id" to "BOM1", "parent_id" to "FG", "child_id" to "R1", "rate" to 1.0, "alt_group" to null),
            ),
            demands = listOf(
                demand("D1", "FG", "L", qty = 20.0),
                demand("D2", "FG", "L", qty = 40.0),
            ),
            productlocation = listOf(
                mapOf("product_id" to "R1", "location_id" to "L", "prod_area" to "raw"),
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

    // ── E. Intra-demand sibling contention ("diamond problem") ─────────────────

    // P = make(C1, C2, C3, C4), rate 1 each — a genuine AND, all four required together.
    // Each Ci = make(X), rate 1 — so each of the 4 siblings independently needs exactly P's
    // own quantity of the SAME shared critical material X. Demand = 40, X supply = 48 (but a
    // single demand's own aggregate per-lot cap for a critical material is the raw demand
    // quantity, not the BOM-rate-amplified total — see buildReachabilityMatrix — so D1's own
    // ceiling on X is 40, not 48).
    val diamondConfig = mapOf<String, Any?>("purchase_allowed" to false)
    fun diamondData() = mkData(
        supplies = listOf(supply("X", "L", 48.0)),
        methodMake = listOf(
            mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B1", "product_id" to "C1", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B2", "product_id" to "C2", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B3", "product_id" to "C3", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B4", "product_id" to "C4", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
        ),
        methodBuy = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "preference" to 1),
        ),
        bom = listOf(
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C2", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C3", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C4", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B1", "parent_id" to "C1", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B2", "parent_id" to "C2", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B3", "parent_id" to "C3", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B4", "parent_id" to "C4", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
        ),
        demands = listOf(demand("D1", "P", "L", qty = 40.0)),
        productlocation = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw"),
        ),
    )

    test("gatherAndSiblingRequests: finds all 4 AND-siblings requesting the shared critical material") {
        val data = diamondData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, diamondConfig)
        val reqs = gatherAndSiblingRequests(data["demand"]!!.first(), alloc, data, diamondConfig, null)

        reqs.size shouldBe 4
        reqs.all { it.cohort == ("P" to "L") } shouldBe true
        reqs.all { it.supplyKey == SupplyKey("X", "L") } shouldBe true
        reqs.all { it.requestedQty == 40.0 } shouldBe true
        reqs.map { it.branch }.toSet() shouldBe setOf("C1", "C2", "C3", "C4").map { BranchKey(it, "L") }.toSet()
    }

    test("computeAndSiblingCaps: splits D1's own 40-unit X allowance evenly, 10 per sibling") {
        val data = diamondData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, diamondConfig)
        val caps = computeAndSiblingCaps(data["demand"]!!, alloc, data, diamondConfig, null).caps

        val d1Caps = caps["D1"]
        d1Caps shouldNotBe null
        d1Caps!!.size shouldBe 4
        for (child in listOf("C1", "C2", "C3", "C4")) {
            val branchCap = d1Caps[BranchKey(child, "L")]
            branchCap shouldNotBe null
            branchCap!!.values.sum() shouldBe (10.0 plusOrMinus 1e-6)
        }
    }

    test("computeAndSiblingCaps: step (c) copies the SAME bom_child dominator (X's own lot) onto all 4 constrained siblings") {
        // Total ask (4 x 40 = 160) massively exceeds D1's own 40-unit X ceiling — a genuinely
        // constrained group, so every sibling should be tagged, not just one arbitrary "worst" one.
        // All 4 branches share the same material X, so step (c) resolves ONE rawSupplyLotRefs
        // result for X@L (diamondData's single lot, S_X_L) and copies it onto every branch —
        // never independently recomputes or synthesizes a "shared budget" abstraction per branch.
        val data = diamondData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, diamondConfig)
        val result = computeAndSiblingCaps(data["demand"]!!, alloc, data, diamondConfig, null)

        val d1Dominators = result.dominators["D1"]
        d1Dominators shouldNotBe null
        d1Dominators!!.size shouldBe 4
        for (child in listOf("C1", "C2", "C3", "C4")) {
            val refs = d1Dominators[BranchKey(child, "L")]
            refs shouldNotBe null
            refs!!.size shouldBe 1
            val ref = refs.first()
            ref.kind shouldBe "bom_child"
            ref.productId shouldBe "X"
            ref.locationId shouldBe "L"
            ref.supplyId shouldBe "S_X_L"
            // Which siblings were also drawing on the same lot rides along purely for a UI
            // tooltip — every OTHER branch in the group ("$productId@$locationId", no [slot]
            // suffix since these are top-level, unnested AND-siblings), not itself — and is
            // never baked into the ref's kind or label.
            val competing = ref.competingDemandIds
            competing shouldNotBe null
            competing!!.size shouldBe 3
            competing.contains("$child@L") shouldBe false
            listOf("C1", "C2", "C3", "C4").filter { it != child }
                .forEach { other -> competing.contains("$other@L") shouldBe true }
        }
    }

    // A demand's own aggregate ceiling on a critical material is its raw demand quantity (see
    // diamondData's own comment), NOT scaled down by BOM rate — so an AND-group where every
    // sibling asks for the FULL demand quantity of the shared material (rate 1.0, as in
    // diamondData) is ALWAYS constrained once there are >=2 siblings, regardless of the
    // demand's absolute size. To build a genuinely UNconstrained fixture, each sibling's own
    // rate down to the shared material must be small enough that even N siblings' combined ask
    // stays under the demand's own (unscaled) ceiling.
    val unconstrainedConfig = mapOf<String, Any?>("purchase_allowed" to false)
    fun unconstrainedData() = mkData(
        supplies = listOf(supply("X", "L", 1000.0)),
        methodMake = listOf(
            mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B1", "product_id" to "C1", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B2", "product_id" to "C2", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
        ),
        methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
        bom = listOf(
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C2", "rate" to 1.0, "alt_group" to null),
            // Each sibling only needs 10% of P's own quantity in X — two siblings combined
            // (20%) stays well under the demand's own 100%-of-quantity ceiling on X.
            mapOf("bom_id" to "B1", "parent_id" to "C1", "child_id" to "X", "rate" to 0.1, "alt_group" to null),
            mapOf("bom_id" to "B2", "parent_id" to "C2", "child_id" to "X", "rate" to 0.1, "alt_group" to null),
        ),
        demands = listOf(demand("D1", "P", "L", qty = 40.0)),
        productlocation = listOf(mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw")),
    )

    test("computeAndSiblingCaps: an UNconstrained group (availability >= total ask) attaches no dominator") {
        val data = unconstrainedData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, unconstrainedConfig)
        val result = computeAndSiblingCaps(data["demand"]!!, alloc, data, unconstrainedConfig, null)

        (result.dominators["D1"] ?: emptyMap<BranchKey, List<DominatorRef>>()) shouldBe emptyMap<BranchKey, List<DominatorRef>>()
    }

    // ── rawSupplyLotRefs fix: sketch-phase terminal dominator only names lots THIS demand is
    // entitled to, not every physical lot of the product@location (the 858_F35_2024_07_VIRTUAL
    // bug: 7 unrelated lots shown, none of which the demand had exhausted). Two demands (D1, D2)
    // each need P = make(X) — X reached as a BOM child, same shape as diamondData/cousinData
    // (a demand directly asking for the critical material itself, with no BOM in between,
    // doesn't populate perLotBudgets the same way). Combined ask (80) exceeds total X supply
    // (60), so buildSupplyAllocation must split entitlement between them; neither demand alone
    // is entitled to the whole 60.
    // No buy method for X: computePlanBlueprint's sketch phase (unlike the live commit) doesn't
    // check purchase_allowed/whitelist admission when deciding whether "buy" resolves a
    // shortfall — it treats any present buy row as unconditionally elastic. So the fixture must
    // give X no method at all to force a genuine, sketch-visible shortfall at the leaf itself
    // (matching a truly raw material — the real 858_F35_2024_07_VIRTUAL case's 160-1153 had no
    // move/make into that exact location either).
    val perDemandBudgetConfig = mapOf<String, Any?>("purchase_allowed" to false)
    fun perDemandBudgetData(d1Qty: Double, d2Qty: Double) = mkData(
        supplies = listOf(supply("X", "L", 30.0, "X_LOT_A"), supply("X", "L", 30.0, "X_LOT_B")),
        methodMake = listOf(mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "lead_time" to 0.0, "preference" to 1)),
        bom = listOf(mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "X", "rate" to 1.0, "alt_group" to null)),
        demands = listOf(demand("D1", "P", "L", qty = d1Qty), demand("D2", "P", "L", qty = d2Qty)),
        productlocation = listOf(mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw")),
    )

    test("computePlanBlueprint: constrained leaf's quantityDominator only names lots this demand's own perLotBudgets covers") {
        val data = perDemandBudgetData(d1Qty = 40.0, d2Qty = 40.0)
        val alloc = buildSupplyAllocation(data["demand"]!!, data, perDemandBudgetConfig)
        val blueprint = computePlanBlueprint(data["demand"]!!, alloc, data, null)

        val d1Budget = alloc.perLotBudgets["D1"] ?: emptyMap()
        val d1OwnLotIds = d1Budget.keys.filter { it.startsWith("X|L|") }.map { it.removePrefix("X|L|") }.toSet()

        val d1Node = blueprint["D1"]?.get("X" to "L")
        d1Node shouldNotBe null
        // Constrained (combined ask 80 > total supply 60), so there should be a dominator.
        (d1Node!!.achievable < 40.0 - 1e-6) shouldBe true
        d1Node.quantityDominator.isNotEmpty() shouldBe true
        // Every named lot must be one D1 actually has entitlement to — never a lot that only
        // belongs to D2's own share, and never the FULL physical lot list regardless of split.
        for (ref in d1Node.quantityDominator) {
            if (ref.supplyId != null) (ref.supplyId in d1OwnLotIds) shouldBe true
        }
    }

    test("computePlanBlueprint: zero entitlement collapses to the self-referencing terminal ref, not a lot list") {
        // D1 asks for a tiny amount, D2 asks for far more — proportional split can leave D1
        // with an entitlement small enough that, combined with a demand size mismatch, we can
        // directly exercise the zero-entitlement path via a demand for a product with NO
        // critical supply reachable at all (sk !in allocation.graph.supplyIndex — Case A).
        val data = mkData(
            supplies = listOf(supply("X", "L", 30.0)),
            methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
            demands = listOf(demand("D1", "UNREACHABLE", "L", qty = 10.0)),
            productlocation = listOf(mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw")),
        )
        val alloc = buildSupplyAllocation(data["demand"]!!, data, perDemandBudgetConfig)
        val blueprint = computePlanBlueprint(data["demand"]!!, alloc, data, null)

        val d1Node = blueprint["D1"]?.get("UNREACHABLE" to "L")
        d1Node shouldNotBe null
        d1Node!!.quantityDominator.size shouldBe 1
        val ref = d1Node.quantityDominator.first()
        ref.supplyId shouldBe null
        ref.label shouldBe "UNREACHABLE@L (no supply)"
    }

    test("diamond: AND-required siblings independently reaching one scarce material split fairly, not sequentially") {
        // Sequential/greedy exploration draws C1=40 (0 left of D1's own 40-unit cap), C2=0,
        // C3=0, C4=0 — an AND-min of 0, so P commits NOTHING despite that same 40-unit
        // allowance being enough to give all four siblings a meaningful (10 each) share. This
        // is the exact shape of the real F35__444 bug this session traced (a make with several
        // AND-siblings that all route through one non-purchasable raw material). The
        // gather-then-allocate fix (computeAndSiblingCaps) should instead give each sibling a
        // fair, pre-computed 40/4=10-unit cap, so all four succeed at 10 and P commits 10
        // instead of 0.
        val data = diamondData()
        val result = runPlanning(data, config = diamondConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>
        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        // The core regression check: a fair split lets the demand commit a genuine partial
        // quantity instead of zeroing out on the AND-min of an unlucky, sequentially-starved
        // sibling.
        (d1Total > 1e-6) shouldBe true
        d1Total shouldBe (10.0 plusOrMinus 1e-6)

        @Suppress("UNCHECKED_CAST")
        val workOrders = result.output["work_orders"] as List<Map<String, Any?>>
        val perChild = listOf("C1", "C2", "C3", "C4").associateWith { pid ->
            workOrders.filter { it["product_id"] == pid }.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        }
        // All four AND-siblings get a meaningful, roughly equal share — not 1 full + 3 starved.
        perChild.values.forEach { qty -> (qty > 1e-6) shouldBe true }
        perChild.values.forEach { qty -> qty shouldBe (10.0 plusOrMinus 1e-6) }
    }

    // ── F. Cross-cohort ("cousin") contention ───────────────────────────────────

    // P = make(C1, C2), rate 1 each — a genuine AND. C1 reaches the shared critical
    // material X directly, making it a branch of cohort P. C2 is itself an AND-parent —
    // make(C2a, C2b) — so C2a reaches X as a branch of a DIFFERENT cohort (C2), not P.
    // C1 and C2a are "cousins": both draw from the exact same demand-wide X budget, but
    // neither is a direct AND-sibling of the other, so a per-cohort split (grouping by
    // (cohort, supplyKey)) never puts them in the same contention group — each cohort
    // sees only ONE branch reaching X and skips the fair-split entirely (byBranch.size <
    // 2), leaving both branches uncapped and free to race for the shared demand-wide
    // budget. C2b needs an unrelated, abundantly-supplied non-critical material Y so C2's
    // own AND-min isn't blocked by anything other than the X contention.
    val cousinConfig = mapOf<String, Any?>("purchase_allowed" to false)
    fun cousinData() = mkData(
        supplies = listOf(supply("X", "L", 48.0), supply("Y", "L", 1000.0)),
        methodMake = listOf(
            mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B1", "product_id" to "C1", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B2", "product_id" to "C2", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B2a", "product_id" to "C2a", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B2b", "product_id" to "C2b", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
        ),
        methodBuy = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "preference" to 1),
        ),
        bom = listOf(
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C2", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B1", "parent_id" to "C1", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B2", "parent_id" to "C2", "child_id" to "C2a", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B2", "parent_id" to "C2", "child_id" to "C2b", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B2a", "parent_id" to "C2a", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B2b", "parent_id" to "C2b", "child_id" to "Y", "rate" to 1.0, "alt_group" to null),
        ),
        demands = listOf(demand("D1", "P", "L", qty = 40.0)),
        productlocation = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw"),
        ),
    )

    test("gatherAndSiblingRequests: finds cousin branches from different cohorts reaching the shared critical material") {
        val data = cousinData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, cousinConfig)
        val reqs = gatherAndSiblingRequests(data["demand"]!!.first(), alloc, data, cousinConfig, null)

        val xReqs = reqs.filter { it.supplyKey == SupplyKey("X", "L") }
        xReqs.size shouldBe 2
        // C1 is a top-level AND-sibling of P with nothing enclosing it (slot=null). C2a is
        // nested one level under C2's own AND-fanout, so its branch carries "C2@L" as its
        // lineage-derived slot — proof the two cousins stay distinguishable even though
        // C1's own (pid, lid) alone would already tell them apart here (this fixture's
        // point is the cross-cohort pooling in the next test, not the key shape itself —
        // see section G for a case where lineage is the ONLY thing that disambiguates).
        xReqs.map { it.branch }.toSet() shouldBe setOf(BranchKey("C1", "L", null), BranchKey("C2a", "L", "C2@L"))
        // Different cohorts — C1's fanout is P, C2a's is C2 — confirming these are genuine
        // cousins, not direct AND-siblings of one common parent.
        xReqs.map { it.cohort }.toSet() shouldBe setOf("P" to "L", "C2" to "L")
    }

    test("computeAndSiblingCaps: pools cousin branches from different cohorts into one fair split") {
        val data = cousinData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, cousinConfig)
        val caps = computeAndSiblingCaps(data["demand"]!!, alloc, data, cousinConfig, null).caps

        val d1Caps = caps["D1"]
        d1Caps shouldNotBe null
        val c1Cap = d1Caps!![BranchKey("C1", "L", null)]
        val c2aCap = d1Caps[BranchKey("C2a", "L", "C2@L")]
        c1Cap shouldNotBe null
        c2aCap shouldNotBe null
        // Fair 2-way split of D1's own 40-unit X ceiling, even though C1 and C2a are cousins
        // from unrelated cohorts, not siblings under one shared AND-parent.
        c1Cap!!.values.sum() shouldBe (20.0 plusOrMinus 1e-6)
        c2aCap!!.values.sum() shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("cousin contention: branches from different AND-parents sharing one scarce material split fairly, not sequentially") {
        // Without cross-cohort pooling, each cohort sees only one branch touching X and
        // skips the fair split (no contention detected in isolation) — both C1 and C2a go
        // uncapped, and whichever is evaluated first in the live commit's tree walk grabs
        // the whole 40-unit budget, starving the other to 0. That drags C2's own AND-min to
        // 0 (since C2a would get nothing), and P's own AND-min to 0 — D1 commits nothing
        // despite the same 40 units being enough to give both cousins a meaningful 20 each.
        val data = cousinData()
        val result = runPlanning(data, config = cousinConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>
        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        (d1Total > 1e-6) shouldBe true
        d1Total shouldBe (20.0 plusOrMinus 1e-6)
    }

    // ── G. Identical-subtree ("shared descendant") contention ──────────────────

    // P = make(C1, C2), rate 1 each — two direct AND-siblings, NOT cousins this time. Both
    // C1 and C2 independently route through the exact same downstream product S (make(S),
    // rate 1) — a shared sub-assembly, like two BOM components that happen to both be built
    // from the same intermediate part. S itself is an AND-parent — make(X, Y) — with X the
    // shared critical material. Since C1's own instance of (X@L) and C2's own SEPARATE
    // instance of (X@L) are reached via IDENTICAL (productId, locationId), a plain
    // BranchKey(X, L) can't tell them apart — without lineage disambiguation, they'd
    // collapse into ONE branch entry, computeAndSiblingCaps would see no contention
    // (byBranch.size < 2) and skip the split entirely, leaving X uncapped at the branch
    // level and free for whichever of C1/C2 is evaluated first to drain in full.
    val sharedDescendantConfig = mapOf<String, Any?>("purchase_allowed" to false)
    fun sharedDescendantData() = mkData(
        supplies = listOf(supply("X", "L", 48.0), supply("Y", "L", 1000.0)),
        methodMake = listOf(
            mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B1", "product_id" to "C1", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B2", "product_id" to "C2", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "BS", "product_id" to "S", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
        ),
        methodBuy = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "preference" to 1),
        ),
        bom = listOf(
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C2", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B1", "parent_id" to "C1", "child_id" to "S", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B2", "parent_id" to "C2", "child_id" to "S", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BS", "parent_id" to "S", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BS", "parent_id" to "S", "child_id" to "Y", "rate" to 1.0, "alt_group" to null),
        ),
        demands = listOf(demand("D1", "P", "L", qty = 40.0)),
        productlocation = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw"),
        ),
    )

    test("gatherAndSiblingRequests: C1's and C2's own separate instances of the shared X touch get distinct lineage-tagged branches") {
        val data = sharedDescendantData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, sharedDescendantConfig)
        val reqs = gatherAndSiblingRequests(data["demand"]!!.first(), alloc, data, sharedDescendantConfig, null)

        val xReqs = reqs.filter { it.supplyKey == SupplyKey("X", "L") }
        xReqs.size shouldBe 2
        // Same (productId, locationId) for both — X@L — but DIFFERENT slots (lineage),
        // proving they're distinguishable despite being structurally identical otherwise.
        xReqs.map { it.branch.productId to it.branch.locationId }.toSet() shouldBe setOf("X" to "L")
        xReqs.map { it.branch.slot }.toSet().size shouldBe 2
        xReqs.map { it.branch }.toSet().size shouldBe 2
    }

    test("computeAndSiblingCaps: splits X fairly between C1's and C2's own identical-shaped subtrees") {
        val data = sharedDescendantData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, sharedDescendantConfig)
        val caps = computeAndSiblingCaps(data["demand"]!!, alloc, data, sharedDescendantConfig, null).caps

        val d1Caps = caps["D1"]
        d1Caps shouldNotBe null
        val xBranches = d1Caps!!.keys.filter { it.productId == "X" && it.locationId == "L" }
        xBranches.size shouldBe 2
        for (branch in xBranches) {
            d1Caps[branch]!!.values.sum() shouldBe (20.0 plusOrMinus 1e-6)
        }
    }

    test("shared-descendant contention: two AND-siblings routing through an identical downstream subtree split fairly, not sequentially") {
        // Without lineage disambiguation, C1's and C2's own separate draws of X collapse to
        // the same BranchKey — no contention detected, X goes uncapped, and whichever
        // sibling is evaluated first grabs the whole 40-unit budget. That leaves the other
        // sibling's own S-instance at 0, dragging P's AND-min to 0 despite the same 40 units
        // being enough to give both a meaningful 20 each.
        val data = sharedDescendantData()
        val result = runPlanning(data, config = sharedDescendantConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>
        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        (d1Total > 1e-6) shouldBe true
        d1Total shouldBe (20.0 plusOrMinus 1e-6)
    }

    // ── H. Root-split ("OR-alternative") identical-subtree contention ──────────

    // Same shared-descendant shape as section G, but the fanout at P is a root-split — ONE
    // make method with two alt_group variants (G1 → C1, G2 → C2), proportionally split —
    // rather than an AND-group's mandatory children. This is the realistic shape root-split
    // candidates normally take (distinguished by alt_group within one method; slotIdFor
    // falls back to location only when a candidate has no altKey at all — e.g. purchase/
    // move — which would collide two candidates at the same location, a separate,
    // pre-existing slotIdFor gap outside this fix's scope). Root-split candidates all
    // share the exact same (productId, locationId) — P@L — since they're alternative
    // ROUTES to building the identical product, not distinct BOM line items. That makes
    // the lineage-collision risk even more direct than section G's: extending lineage with
    // plain "P@L" would produce the SAME segment for both alt_group candidates, so the fix
    // also folds each candidate's own slot identifier in (see the fix's own comment at its
    // call site).
    val rootSplitSharedDescendantConfig = mapOf<String, Any?>("purchase_allowed" to false)
    fun rootSplitSharedDescendantData() = mkData(
        supplies = listOf(supply("X", "L", 48.0), supply("Y", "L", 1000.0)),
        methodMake = listOf(
            mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B1", "product_id" to "C1", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "B2", "product_id" to "C2", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
            mapOf("bom_id" to "BS", "product_id" to "S", "location_id" to "L", "lead_time" to 0.0, "preference" to 1),
        ),
        methodBuy = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "preference" to 1),
        ),
        bom = listOf(
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "rate" to 1.0, "alt_group" to "G1"),
            mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C2", "rate" to 1.0, "alt_group" to "G2"),
            mapOf("bom_id" to "B1", "parent_id" to "C1", "child_id" to "S", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "B2", "parent_id" to "C2", "child_id" to "S", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BS", "parent_id" to "S", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BS", "parent_id" to "S", "child_id" to "Y", "rate" to 1.0, "alt_group" to null),
        ),
        demands = listOf(demand("D1", "P", "L", qty = 40.0)),
        productlocation = listOf(
            mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw"),
        ),
    )

    test("gatherAndSiblingRequests: M1's and M2's root-split branches touching the shared X get distinct lineage-tagged keys") {
        val data = rootSplitSharedDescendantData()
        val alloc = buildSupplyAllocation(data["demand"]!!, data, rootSplitSharedDescendantConfig)
        val reqs = gatherAndSiblingRequests(data["demand"]!!.first(), alloc, data, rootSplitSharedDescendantConfig, null)

        val xReqs = reqs.filter { it.supplyKey == SupplyKey("X", "L") }
        xReqs.size shouldBe 2
        xReqs.map { it.branch }.toSet().size shouldBe 2
    }

    test("root-split shared-descendant contention: two OR-alternative candidates routing through an identical downstream subtree both get their fair share") {
        // Root-split (additive, not AND-min): if the fix correctly gives M1's and M2's own
        // X-touches their fair, non-colliding share, EACH candidate fully covers its own
        // 20-unit target and the two contributions sum — D1 commits close to its full 40,
        // not the "one candidate wins everything, the other gets nothing, total capped at
        // whichever single candidate's own need" outcome the lineage collision would cause.
        val data = rootSplitSharedDescendantData()
        val result = runPlanning(data, config = rootSplitSharedDescendantConfig)
        @Suppress("UNCHECKED_CAST")
        val committed = result.output["committed_demands"] as List<Map<String, Any?>>
        val d1Total = committed.filter { it["demand_id"] == "D1" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        // Strictly better than the AND-min case's known-partial 20 — both candidates share
        // the pool fairly instead of one starving the other.
        (d1Total > 20.0 + 1e-6) shouldBe true
    }

    // ── F. findOrGroupRecipients: structural OR-group grand-parent discovery ───────

    test("findOrGroupRecipients: AND-mandatory recipients each with their own nested OR-group reaching X") {
        // P -> AND(A1, A3). A1 -> OR(V1a, V1b), both -> X. A3 -> OR(V3a, V3b), both -> X.
        // Mirrors the real dataset's 160-1153/A1/A3 shape (an added OR layer between the
        // AND-mandatory recipient and the critical leaf) — A1 and A3 are the collapsing
        // points, not V1a/V1b/V3a/V3b individually, and not P itself.
        val data = mkData(
            bom = listOf(
                mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "A1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "A3", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BA1", "parent_id" to "A1", "child_id" to "V1a", "rate" to 1.0, "alt_group" to "g1a"),
                mapOf("bom_id" to "BA1", "parent_id" to "A1", "child_id" to "V1b", "rate" to 1.0, "alt_group" to "g1b"),
                mapOf("bom_id" to "BA3", "parent_id" to "A3", "child_id" to "V3a", "rate" to 1.0, "alt_group" to "g3a"),
                mapOf("bom_id" to "BA3", "parent_id" to "A3", "child_id" to "V3b", "rate" to 1.0, "alt_group" to "g3b"),
                mapOf("bom_id" to "BV1a", "parent_id" to "V1a", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BV1b", "parent_id" to "V1b", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BV3a", "parent_id" to "V3a", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BV3b", "parent_id" to "V3b", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val recipients = findOrGroupRecipients(setOf("X"), data)
        recipients["X"] shouldBe setOf("A1", "A3")
    }

    test("findOrGroupRecipients: mutually exclusive OR-alternatives collapse to their shared parent, not each other") {
        // FG -> OR(P, P2) directly. P -> X. P2 -> X (a different path to the same X).
        // X's own link to P/P2 has no alt_group, so the walk continues up past P/P2 to their
        // shared parent FG, which IS the OR-group's parent — FG is the recipient, not P/P2.
        val data = mkData(
            bom = listOf(
                mapOf("bom_id" to "BFG", "parent_id" to "FG", "child_id" to "P", "rate" to 1.0, "alt_group" to "gP"),
                mapOf("bom_id" to "BFG", "parent_id" to "FG", "child_id" to "P2", "rate" to 1.0, "alt_group" to "gP2"),
                mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BP2", "parent_id" to "P2", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val recipients = findOrGroupRecipients(setOf("X"), data)
        recipients["X"] shouldBe setOf("FG")
    }

    test("findOrGroupRecipients: plain AND-only chain (no alt_group anywhere) yields zero recipients") {
        val data = mkData(
            bom = listOf(
                mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BC1", "parent_id" to "C1", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val recipients = findOrGroupRecipients(setOf("X"), data)
        recipients.containsKey("X") shouldBe false
    }

    test("findOrGroupRecipients: a lone (singleton) alt_group is not a real OR-group") {
        // Only ONE child under (P, BP) has a non-null alt_group — not a genuine >=2-member
        // OR-group, so the walk must continue past P rather than recording it.
        val data = mkData(
            bom = listOf(
                mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "rate" to 1.0, "alt_group" to "onlyGroup"),
                mapOf("bom_id" to "BC1", "parent_id" to "C1", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            ),
        )
        val recipients = findOrGroupRecipients(setOf("X"), data)
        recipients.containsKey("X") shouldBe false
    }
})
