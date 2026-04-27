package com.allocator

import com.allocator.services.AllocationCandidate
import com.allocator.services.NeedsMatrix
import com.allocator.services.SupplyKey
import com.allocator.services.aggregateSupplies
import com.allocator.services.allocate
import com.allocator.services.allocateSupplies
import com.allocator.services.extractDemandPriorities
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Tests for [allocate] (low-level policy) and [allocateSupplies] (matrix-level
 * orchestration) — Phase 2 of supply-level consolidation.
 *
 * Two layers of coverage:
 *
 *   1. [allocate] policy tests against synthetic candidate lists. Asserts
 *      each policy ("fair" / "proportional" / "priority_first") produces the
 *      same shape as the leaf-engine's splitFair/Proportional/PriorityFirst
 *      under shortage and abundance.
 *
 *   2. [allocateSupplies] matrix-level tests verifying the per-supply
 *      orchestration: matrix-in, allocations-out, with row/column index
 *      consistency and proper handling of empty supply and missing demands.
 */
class SupplyAllocatorTest : FunSpec({

    // ── Fixture helpers ───────────────────────────────────────────────────────

    fun cand(demandId: String, needed: Double, priority: Int = 0) =
        AllocationCandidate(demandId, needed, priority)

    // ── allocate() — abundance ────────────────────────────────────────────────

    test("allocate fair under abundance: every candidate gets full need") {
        val a = allocate(
            listOf(cand("D1", 10.0, priority = 5), cand("D2", 20.0, priority = 1)),
            availableQty = 100.0,
            mode = "fair",
        )
        a["D1"]!! shouldBe (10.0 plusOrMinus 1e-9)
        a["D2"]!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    test("allocate proportional under abundance: bypasses fraction math (full need)") {
        // Total need = 690 + 345 = 1035. With availableQty = 1100, abundance.
        val a = allocate(
            listOf(cand("D1", 690.0), cand("D2", 345.0)),
            availableQty = 1100.0,
            mode = "proportional",
        )
        // No-shortage shortcut: exact full need, no floating-point residual
        // from multiplying by shares like 690/1035 = 0.6666... × 1100.
        a["D1"]!! shouldBe (690.0 plusOrMinus 1e-12)
        a["D2"]!! shouldBe (345.0 plusOrMinus 1e-12)
    }

    test("allocate priority_first under abundance: every candidate satisfied") {
        val a = allocate(
            listOf(cand("D1", 10.0, priority = 5), cand("D2", 20.0, priority = 1)),
            availableQty = 100.0,
            mode = "priority_first",
        )
        a["D1"]!! shouldBe (10.0 plusOrMinus 1e-9)
        a["D2"]!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    // ── allocate() — shortage ────────────────────────────────────────────────

    test("allocate priority_first under shortage: lowest priority drains first") {
        // D2 (priority 1) gets first dibs, D1 (priority 5) gets the rest.
        val a = allocate(
            listOf(cand("D1", 50.0, priority = 5), cand("D2", 80.0, priority = 1)),
            availableQty = 100.0,
            mode = "priority_first",
        )
        a["D2"]!! shouldBe (80.0 plusOrMinus 1e-9)  // priority 1, full need
        a["D1"]!! shouldBe (20.0 plusOrMinus 1e-9)  // remaining 100 - 80
    }

    test("allocate priority_first: tie-broken by demand id for determinism") {
        // Both same priority — sort secondary by demandId string ascending.
        val a = allocate(
            listOf(cand("Z", 60.0, priority = 1), cand("A", 60.0, priority = 1)),
            availableQty = 80.0,
            mode = "priority_first",
        )
        a["A"]!! shouldBe (60.0 plusOrMinus 1e-9)  // alphabetical first → full need
        a["Z"]!! shouldBe (20.0 plusOrMinus 1e-9)  // remaining
    }

    test("allocate proportional under shortage: every demand gets a share") {
        val a = allocate(
            listOf(cand("D1", 30.0), cand("D2", 70.0)),
            availableQty = 50.0,
            mode = "proportional",
        )
        // D1 share: 50 × 30/100 = 15. D2 share: 50 × 70/100 = 35.
        a["D1"]!! shouldBe (15.0 plusOrMinus 1e-9)
        a["D2"]!! shouldBe (35.0 plusOrMinus 1e-9)
    }

    test("allocate fair under shortage: falls back to proportional (no demand starves)") {
        // priority differs but supply is short — fair must use proportional split.
        val a = allocate(
            listOf(cand("D1", 30.0, priority = 5), cand("D2", 70.0, priority = 1)),
            availableQty = 50.0,
            mode = "fair",
        )
        // Same as proportional: every demand gets a non-zero share.
        a["D1"]!! shouldBe (15.0 plusOrMinus 1e-9)
        a["D2"]!! shouldBe (35.0 plusOrMinus 1e-9)
    }

    // ── allocate() — edge cases ──────────────────────────────────────────────

    test("allocate empty candidates returns empty map") {
        allocate(emptyList(), availableQty = 100.0, mode = "fair").isEmpty() shouldBe true
    }

    test("allocate zero-availability returns empty map") {
        val a = allocate(
            listOf(cand("D1", 10.0)),
            availableQty = 0.0,
            mode = "fair",
        )
        a.isEmpty() shouldBe true
    }

    test("allocate zero-need fallback: split availableQty equally") {
        val a = allocate(
            listOf(cand("D1", 0.0), cand("D2", 0.0), cand("D3", 0.0)),
            availableQty = 30.0,
            mode = "proportional",
        )
        // Each gets 30/3 = 10.
        a["D1"]!! shouldBe (10.0 plusOrMinus 1e-9)
        a["D2"]!! shouldBe (10.0 plusOrMinus 1e-9)
        a["D3"]!! shouldBe (10.0 plusOrMinus 1e-9)
    }

    test("allocate unknown mode falls back to fair") {
        val a = allocate(
            listOf(cand("D1", 10.0), cand("D2", 20.0)),
            availableQty = 30.0,  // exact match, no shortage
            mode = "bogus",
        )
        a["D1"]!! shouldBe (10.0 plusOrMinus 1e-9)
        a["D2"]!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    // ── aggregateSupplies() ───────────────────────────────────────────────────

    test("aggregateSupplies sums multiple rows for the same key") {
        val supplies = listOf(
            mapOf("product_id" to "X", "location_id" to "L1", "qty" to 30.0, "supply_id" to "S1"),
            mapOf("product_id" to "X", "location_id" to "L1", "qty" to 70.0, "supply_id" to "S2"),
            mapOf("product_id" to "Y", "location_id" to "L1", "qty" to 50.0, "supply_id" to "S3"),
        )
        val totals = aggregateSupplies(supplies)
        totals[SupplyKey("X", "L1")]!! shouldBe (100.0 plusOrMinus 1e-9)
        totals[SupplyKey("Y", "L1")]!! shouldBe (50.0 plusOrMinus 1e-9)
        totals.size shouldBe 2
    }

    test("aggregateSupplies excludes non-positive qty rows") {
        val supplies = listOf(
            mapOf("product_id" to "X", "location_id" to "L1", "qty" to 100.0, "supply_id" to "S1"),
            mapOf("product_id" to "X", "location_id" to "L1", "qty" to 0.0, "supply_id" to "S_zero"),
            mapOf("product_id" to "X", "location_id" to "L1", "qty" to -5.0, "supply_id" to "S_neg"),
        )
        val totals = aggregateSupplies(supplies)
        totals[SupplyKey("X", "L1")]!! shouldBe (100.0 plusOrMinus 1e-9)
        totals.size shouldBe 1
    }

    // ── allocateSupplies() — matrix-level orchestration ──────────────────────

    test("allocateSupplies: single supply, single demand, abundance") {
        val matrix = NeedsMatrix(
            byRow = mapOf("D1" to mapOf(SupplyKey("X", "L1") to 10.0)),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 10.0)),
        )
        val a = allocateSupplies(
            matrix,
            supplyTotals = mapOf(SupplyKey("X", "L1") to 100.0),
            demandPriorities = mapOf("D1" to 0),
            mode = "fair",
        )
        a.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
        a.byColumn[SupplyKey("X", "L1")]!!["D1"]!! shouldBe (10.0 plusOrMinus 1e-9)
    }

    test("allocateSupplies: 2 demands × 1 supply, fair under shortage") {
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 70.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 70.0)),
        )
        val a = allocateSupplies(
            matrix,
            supplyTotals = mapOf(SupplyKey("X", "L1") to 50.0),
            demandPriorities = mapOf("D1" to 5, "D2" to 1),
            mode = "fair",  // under shortage → proportional
        )
        a.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (15.0 plusOrMinus 1e-9)
        a.byRow["D2"]!![SupplyKey("X", "L1")]!! shouldBe (35.0 plusOrMinus 1e-9)
    }

    test("allocateSupplies: priority_first respects per-demand priority") {
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D_low" to mapOf(SupplyKey("X", "L1") to 50.0),
                "D_hi" to mapOf(SupplyKey("X", "L1") to 50.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D_low" to 50.0, "D_hi" to 50.0)),
        )
        val a = allocateSupplies(
            matrix,
            supplyTotals = mapOf(SupplyKey("X", "L1") to 70.0),
            demandPriorities = mapOf("D_low" to 5, "D_hi" to 1),
            mode = "priority_first",
        )
        a.byRow["D_hi"]!![SupplyKey("X", "L1")]!! shouldBe (50.0 plusOrMinus 1e-9)  // first
        a.byRow["D_low"]!![SupplyKey("X", "L1")]!! shouldBe (20.0 plusOrMinus 1e-9)  // remaining
    }

    test("allocateSupplies: multi-supply, multi-demand allocation") {
        // Demands D1, D2 each need both X and Y (e.g., shared raw materials).
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 10.0, SupplyKey("Y", "L1") to 5.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 20.0, SupplyKey("Y", "L1") to 10.0),
            ),
            byColumn = mapOf(
                SupplyKey("X", "L1") to mapOf("D1" to 10.0, "D2" to 20.0),
                SupplyKey("Y", "L1") to mapOf("D1" to 5.0, "D2" to 10.0),
            ),
        )
        val a = allocateSupplies(
            matrix,
            supplyTotals = mapOf(
                SupplyKey("X", "L1") to 100.0,  // abundant
                SupplyKey("Y", "L1") to 100.0,  // abundant
            ),
            demandPriorities = mapOf("D1" to 0, "D2" to 0),
            mode = "fair",
        )
        // Both abundant → each demand gets full need at each supply.
        a.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
        a.byRow["D1"]!![SupplyKey("Y", "L1")]!! shouldBe (5.0 plusOrMinus 1e-9)
        a.byRow["D2"]!![SupplyKey("X", "L1")]!! shouldBe (20.0 plusOrMinus 1e-9)
        a.byRow["D2"]!![SupplyKey("Y", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
    }

    test("allocateSupplies: column with no supply quietly omitted") {
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 10.0, SupplyKey("Y", "L1") to 10.0),
            ),
            byColumn = mapOf(
                SupplyKey("X", "L1") to mapOf("D1" to 10.0),
                SupplyKey("Y", "L1") to mapOf("D1" to 10.0),
            ),
        )
        val a = allocateSupplies(
            matrix,
            supplyTotals = mapOf(SupplyKey("X", "L1") to 100.0),  // Y has no supply listed
            demandPriorities = mapOf("D1" to 0),
            mode = "fair",
        )
        // X allocated, Y not.
        a.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
        a.byRow["D1"]!!.containsKey(SupplyKey("Y", "L1")) shouldBe false
    }

    test("allocateSupplies: empty matrix returns empty allocation") {
        val a = allocateSupplies(
            NeedsMatrix(byRow = emptyMap(), byColumn = emptyMap()),
            supplyTotals = emptyMap(),
            demandPriorities = emptyMap(),
            mode = "fair",
        )
        a.isEmpty() shouldBe true
        a.cellCount() shouldBe 0
    }

    test("allocateSupplies: byRow and byColumn are consistent") {
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0, SupplyKey("Y", "L1") to 5.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 70.0),
            ),
            byColumn = mapOf(
                SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 70.0),
                SupplyKey("Y", "L1") to mapOf("D1" to 5.0),
            ),
        )
        val a = allocateSupplies(
            matrix,
            supplyTotals = mapOf(
                SupplyKey("X", "L1") to 50.0,   // shortage
                SupplyKey("Y", "L1") to 100.0,  // abundance
            ),
            demandPriorities = mapOf("D1" to 0, "D2" to 0),
            mode = "fair",
        )
        // Verify transpose consistency.
        for ((demandId, supplyMap) in a.byRow) {
            for ((supplyKey, qty) in supplyMap) {
                a.byColumn[supplyKey]!![demandId] shouldBe (qty plusOrMinus 1e-9)
            }
        }
        // Cell counts equal.
        a.byRow.values.sumOf { it.size } shouldBe a.byColumn.values.sumOf { it.size }
    }

    // ── extractDemandPriorities() helper ──────────────────────────────────────

    test("extractDemandPriorities: pulls priority from demand rows, defaults to 0") {
        val demands = listOf(
            mapOf("demand_id" to "D1", "priority" to 5),
            mapOf("demand_id" to "D2"),  // no priority
            mapOf("demand_id" to "D3", "priority" to 1),
        )
        val p = extractDemandPriorities(demands)
        p["D1"] shouldBe 5
        p["D2"] shouldBe 0
        p["D3"] shouldBe 1
    }

    // ── End-to-end: matrix builder + allocator wired together ─────────────────

    test("end-to-end: builds matrix from BOM, allocates supplies under shortage") {
        // Two demands sharing raw RM; demand qty 10 each; RM has only 12 (shortage).
        // Phase 1 builds matrix: D1 needs 10 RM, D2 needs 10 RM.
        // Phase 2 fair-allocates: 12 RM split proportionally → 6 each.
        val data = mapOf(
            "bom" to listOf(
                mapOf("bom_id" to "BOM_A", "parent_id" to "FG_A", "child_id" to "RM", "rate" to 1.0, "alt_group" to null),
                mapOf("bom_id" to "BOM_B", "parent_id" to "FG_B", "child_id" to "RM", "rate" to 1.0, "alt_group" to null),
            ),
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG_A", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0, "type" to "make"),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG_B", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0, "type" to "make"),
            ),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                mapOf("supply_id" to "S_RM", "product_id" to "RM", "location_id" to "L1", "supply_date" to "2024-01-01", "qty" to 12.0),
            ),
        )
        val demands = listOf(
            mapOf("demand_id" to "D1", "product_id" to "FG_A", "location_id" to "L1", "quantity" to 10.0,
                  "request_due_time" to "2024-12-31", "request_time" to "2024-12-31", "priority" to 0),
            mapOf("demand_id" to "D2", "product_id" to "FG_B", "location_id" to "L1", "quantity" to 10.0,
                  "request_due_time" to "2024-12-31", "request_time" to "2024-12-31", "priority" to 0),
        )

        val matrix = com.allocator.services.buildNeedsMatrix(demands, data)
        val supplies = aggregateSupplies(data["supply"] ?: emptyList())
        val priorities = extractDemandPriorities(demands)
        val a = allocateSupplies(matrix, supplies, priorities, mode = "fair")

        // 12 split proportionally between two demands needing 10 each: 6/6.
        a.byRow["D1"]!![SupplyKey("RM", "L1")]!! shouldBe (6.0 plusOrMinus 1e-9)
        a.byRow["D2"]!![SupplyKey("RM", "L1")]!! shouldBe (6.0 plusOrMinus 1e-9)
        a.byColumn[SupplyKey("RM", "L1")]!!.keys shouldContainExactlyInAnyOrder listOf("D1", "D2")
    }
})
