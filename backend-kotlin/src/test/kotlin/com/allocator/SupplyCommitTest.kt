package com.allocator

import com.allocator.services.SupplyAllocations
import com.allocator.services.SupplyKey
import com.allocator.services.aggregateSupplies
import com.allocator.services.allocateSupplies
import com.allocator.services.buildNeedsMatrix
import com.allocator.services.extractDemandPriorities
import com.allocator.services.runInitialCommit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

/**
 * Tests for [runInitialCommit] — Phase 3a of supply-level consolidation.
 *
 * Phase 3a takes a SupplyAllocations from Phase 2 and runs the existing plan()
 * recursive walker per demand with supply-level budget caps. Asserts:
 *
 *   - Each demand commits within its allocation cap
 *   - Inventory is consumed according to the cap, not free FIFO
 *   - actualDraws matches input allocation when fully drawn (abundance), is
 *     less when intermediate inventory absorbs need (compensation seed)
 *   - Pegging tree per demand is emitted
 *   - When allocation is short, demand partially commits within its cap
 *
 * End-to-end fixtures wire Phase 1 (matrix) → Phase 2 (allocator) → Phase 3a
 * (commit) so the contract between the modules is verified.
 */
class SupplyCommitTest : FunSpec({

    // ── Fixture helpers (mirror those in SupplyDemandMatrixTest) ──────────────

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

    /** Materialize inventory mutable list from data["supply"] rows the way runPlanning does. */
    fun mkInventory(data: Map<String, List<Map<String, Any?>>>): MutableList<MutableMap<String, Any?>> =
        (data["supply"] ?: emptyList()).map { s ->
            mutableMapOf<String, Any?>(
                "product_id" to (s["product_id"] ?: ""),
                "location_id" to (s["location_id"] ?: ""),
                "supply_date" to s["supply_date"],
                "supply_id" to s["supply_id"],
                "qty" to ((s["qty"] as? Number)?.toDouble() ?: 0.0),
            )
        }.toMutableList()

    // ── Single demand, abundant supply ────────────────────────────────────────

    test("single demand fully drawn under abundance") {
        // FG → RM (RM has 100 supply). Demand qty=10. Phase 1 records RM=10.
        // Phase 2 allocates 10 to D1 (no shortage). Phase 3a draws 10.
        val data = mapOf(
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val matrix = buildNeedsMatrix(demands, data)
        val supplies = aggregateSupplies(data["supply"] ?: emptyList())
        val priorities = extractDemandPriorities(demands)
        val alloc = allocateSupplies(matrix, supplies, priorities, mode = "fair")

        val result = runInitialCommit(
            demands = demands,
            inventory = mkInventory(data),
            data = data,
            config = null,
            overrideIndex = emptyMap(),
            allocations = alloc,
        )

        // D1 fully committed — 10 units of FG.
        result.committedDemands shouldHaveSize 1
        (result.committedDemands[0]["quantity"] as Number).toDouble() shouldBe (10.0 plusOrMinus 1e-9)

        // Pegging tree exists.
        result.planningPegging shouldHaveSize 1
        result.planningPegging[0]["demand_id"] shouldBe "D1"

        // actualDraws[D1][RM] == 10 (fully drew its allocation).
        result.actualDraws.shouldContainKey("D1")
        result.actualDraws["D1"]!![SupplyKey("RM", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
    }

    // ── Two demands sharing supply, abundance ─────────────────────────────────

    test("two demands sharing abundant supply each commit fully") {
        val data = mapOf(
            "bom" to listOf(
                bom("FG_A", "RM", rate = 1.0),
                bom("FG_B", "RM", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG_A", "L1"), mk("FG_B", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
        )
        val demands = listOf(
            demand("D1", "FG_A", "L1", 10.0),
            demand("D2", "FG_B", "L1", 20.0),
        )

        val matrix = buildNeedsMatrix(demands, data)
        val supplies = aggregateSupplies(data["supply"] ?: emptyList())
        val priorities = extractDemandPriorities(demands)
        val alloc = allocateSupplies(matrix, supplies, priorities, mode = "fair")

        val result = runInitialCommit(
            demands = demands,
            inventory = mkInventory(data),
            data = data,
            config = null,
            overrideIndex = emptyMap(),
            allocations = alloc,
        )

        result.committedDemands shouldHaveSize 2
        result.actualDraws["D1"]!![SupplyKey("RM", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
        result.actualDraws["D2"]!![SupplyKey("RM", "L1")]!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    // ── Two demands, shortage — fair allocation caps each demand ──────────────

    test("two demands sharing supply under shortage — each capped at its allocation") {
        // RM has 12; both demands need 10 each (total 20). Fair → 6/6 split.
        // Each demand draws up to its 6-unit cap.
        val data = mapOf(
            "bom" to listOf(
                bom("FG_A", "RM", rate = 1.0),
                bom("FG_B", "RM", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG_A", "L1"), mk("FG_B", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 12.0, "S_RM")),
        )
        val demands = listOf(
            demand("D1", "FG_A", "L1", 10.0),
            demand("D2", "FG_B", "L1", 10.0),
        )

        val matrix = buildNeedsMatrix(demands, data)
        val supplies = aggregateSupplies(data["supply"] ?: emptyList())
        val priorities = extractDemandPriorities(demands)
        val alloc = allocateSupplies(matrix, supplies, priorities, mode = "fair")

        // Sanity: allocator gave each demand 6.0.
        alloc.byRow["D1"]!![SupplyKey("RM", "L1")]!! shouldBe (6.0 plusOrMinus 1e-9)
        alloc.byRow["D2"]!![SupplyKey("RM", "L1")]!! shouldBe (6.0 plusOrMinus 1e-9)

        val result = runInitialCommit(
            demands = demands,
            inventory = mkInventory(data),
            data = data,
            config = null,
            overrideIndex = emptyMap(),
            allocations = alloc,
        )

        // Each demand drew exactly 6 (its cap).
        result.actualDraws["D1"]!![SupplyKey("RM", "L1")]!! shouldBe (6.0 plusOrMinus 1e-9)
        result.actualDraws["D2"]!![SupplyKey("RM", "L1")]!! shouldBe (6.0 plusOrMinus 1e-9)
    }

    // ── Intermediate-inventory absorption — actualDraw < allocation ───────────

    test("intermediate inventory shortcut leaves deeper allocation unused") {
        // FG → X → Y. X has 30 supply, Y has 1000 supply.
        // Phase 1 records D1 needs 10 at X AND 10 at Y (symbolic over-estimate).
        // Phase 2 allocates 10 of each to D1.
        // Phase 3a: D1 walks BOM. Needs 10 of FG. Make from X.
        //          Need 10 of X. Inventory[X]=30 has plenty. Draw 10.
        //          The walk is satisfied at X — never recurses to Y.
        //          actualDraw[D1][X] = 10, actualDraw[D1][Y] = 0.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "X", rate = 1.0),
                bom("X", "Y", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("X", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("X", "L1", 30.0, "S_X"),
                supply("Y", "L1", 1000.0, "S_Y"),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val matrix = buildNeedsMatrix(demands, data)
        // Sanity: matrix has D1 needing both X and Y.
        matrix.byRow["D1"]!!.size shouldBe 2

        val supplies = aggregateSupplies(data["supply"] ?: emptyList())
        val priorities = extractDemandPriorities(demands)
        val alloc = allocateSupplies(matrix, supplies, priorities, mode = "fair")

        // Allocations: D1 gets 10 at X, 10 at Y (both abundant).
        alloc.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
        alloc.byRow["D1"]!![SupplyKey("Y", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)

        val result = runInitialCommit(
            demands = demands,
            inventory = mkInventory(data),
            data = data,
            config = null,
            overrideIndex = emptyMap(),
            allocations = alloc,
        )

        // D1 drew 10 from X (intermediate inventory satisfied the need).
        result.actualDraws["D1"]!![SupplyKey("X", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
        // D1's Y allocation went unused — no draw recorded.
        // (Either no key, or 0 — runInitialCommit omits zero draws.)
        (result.actualDraws["D1"]!![SupplyKey("Y", "L1")] ?: 0.0) shouldBe (0.0 plusOrMinus 1e-9)
    }

    // ── Demand absent from allocation gets empty budget ──────────────────────

    test("demand without allocation entries proceeds with empty budget") {
        // Allocation is empty for D1 (e.g., the matrix saw nothing). plan()
        // gets an empty budget map; this means no per-component cap is applied
        // (consumeFromInventory's null/missing budgetCap means unlimited).
        // For this test the demand has no BOM either, so it'll fail to commit.
        val data = mapOf(
            "bom" to emptyList<Map<String, Any?>>(),
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val emptyAlloc = SupplyAllocations(byRow = emptyMap(), byColumn = emptyMap())

        val result = runInitialCommit(
            demands = demands,
            inventory = mkInventory(data),
            data = data,
            config = null,
            overrideIndex = emptyMap(),
            allocations = emptyAlloc,
        )

        // Demand attempted to commit but had no methods — produces a row with
        // a hard-failure commit_reason (no_methods or similar); doesn't crash.
        result.committedDemands shouldHaveSize 1
        // No actualDraws recorded for D1 because nothing was allocated to draw.
        (result.actualDraws["D1"] ?: emptyMap()).isEmpty() shouldBe true
    }

    // ── Progress callback fires for each demand ──────────────────────────────

    test("progress callback fires once per demand") {
        val data = mapOf(
            "bom" to emptyList<Map<String, Any?>>(),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("FG", "L1", 100.0, "S_FG")),
        )
        val demands = listOf(
            demand("D1", "FG", "L1", 5.0),
            demand("D2", "FG", "L1", 7.0),
            demand("D3", "FG", "L1", 3.0),
        )
        val matrix = buildNeedsMatrix(demands, data)
        val supplies = aggregateSupplies(data["supply"] ?: emptyList())
        val priorities = extractDemandPriorities(demands)
        val alloc = allocateSupplies(matrix, supplies, priorities, mode = "fair")

        val callbackEvents = mutableListOf<Map<String, Any?>>()
        runInitialCommit(
            demands = demands,
            inventory = mkInventory(data),
            data = data,
            config = null,
            overrideIndex = emptyMap(),
            allocations = alloc,
            progressCallback = { evt -> callbackEvents.add(evt) },
        )

        callbackEvents shouldHaveSize 3
        callbackEvents[0]["current"] shouldBe 1
        callbackEvents[0]["total"] shouldBe 3
        callbackEvents[2]["current"] shouldBe 3
    }
})
