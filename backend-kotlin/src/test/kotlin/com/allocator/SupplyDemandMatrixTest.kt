package com.allocator

import com.allocator.services.NeedsMatrix
import com.allocator.services.SupplyKey
import com.allocator.services.buildNeedsMatrix
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Tests for [buildNeedsMatrix] — Phase 1 of supply-level consolidation.
 *
 * Confirms the bipartite (demand × supply) matrix is built correctly across:
 *   - linear chains
 *   - diamond BOMs
 *   - alt branches (union-alt summing)
 *   - intermediate-inventory pass-through (the cornerstone difference vs the
 *     leaf-engine's resolution walker, which would stop at first inventory)
 *   - shared raw materials across multiple demands
 *
 * Each test sets up a small BOM + supply fixture, calls [buildNeedsMatrix],
 * and asserts the matrix's row/column entries against rate-converted symbolic
 * needs.
 */
class SupplyDemandMatrixTest : FunSpec({

    // ── Fixtures ──────────────────────────────────────────────────────────────

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

    fun NeedsMatrix.qty(demandId: Any?, pid: String, lid: String): Double =
        byRow[demandId]?.get(SupplyKey(pid, lid)) ?: 0.0

    // ── Single demand, single leaf ────────────────────────────────────────────

    test("single demand, single supply — one matrix entry") {
        // FG (no supply) -> RM (has supply). Walker reaches RM, records need, terminates.
        val data = mapOf(
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        m.byRow.keys shouldContainExactlyInAnyOrder listOf("D1")
        m.byRow["D1"]!!.size shouldBe 1
        m.qty("D1", "RM", "L1") shouldBe (10.0 plusOrMinus 1e-9)

        // Column index inverted correctly.
        m.byColumn[SupplyKey("RM", "L1")]!![("D1")] shouldBe (10.0 plusOrMinus 1e-9)
    }

    // ── Linear chain through intermediate inventory ───────────────────────────

    test("linear chain past intermediate supply — records needs at every supply-bearing node") {
        // FG -> X -> Y -> Z, all with supply. Walker continues past every
        // supply-bearing node and records needs at each.
        // Cumulative rate 1.0 throughout, demand qty 10.0.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "X", rate = 1.0),
                bom("X", "Y", rate = 1.0),
                bom("Y", "Z", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("X", "L1"), mk("Y", "L1"), mk("Z", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("X", "L1", 30.0, "S_X"),
                supply("Y", "L1", 100.0, "S_Y"),
                supply("Z", "L1", 1000.0, "S_Z"),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        // Three supply-bearing nodes on the chain — all should appear.
        m.byRow["D1"]!!.size shouldBe 3
        m.qty("D1", "X", "L1") shouldBe (10.0 plusOrMinus 1e-9)
        m.qty("D1", "Y", "L1") shouldBe (10.0 plusOrMinus 1e-9)
        m.qty("D1", "Z", "L1") shouldBe (10.0 plusOrMinus 1e-9)
    }

    test("chain with rates compounds cumulatively") {
        // FG → X (rate 2) → Y (rate 3). Demand qty = 5. So Y's symbolic need = 5 × 2 × 3 = 30.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "X", rate = 2.0),
                bom("X", "Y", rate = 3.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("X", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("Y", "L1", 1000.0, "S_Y")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 5.0))

        val m = buildNeedsMatrix(demands, data)
        m.qty("D1", "Y", "L1") shouldBe (30.0 plusOrMinus 1e-9)
    }

    // ── Diamond BOM ──────────────────────────────────────────────────────────

    test("diamond BOM (independent paths to shared leaf) — needs sum across paths") {
        // FG splits into B and C (both via the same alt_group? no — these are
        // independent BOM lines, each contributing one child). Both reach Z.
        //   FG → B → Z
        //   FG → C → Z
        // Demand qty 10. Z's symbolic need = 10 (via B) + 10 (via C) = 20.
        val data = mapOf(
            "bom" to listOf(
                // Two independent BOM lines under FG (different alt_groups so they're AND-combined)
                bom("FG", "B", rate = 1.0, altGroup = "g1"),
                bom("FG", "C", rate = 1.0, altGroup = "g2"),
                bom("B", "Z", rate = 1.0),
                bom("C", "Z", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("B", "L1"), mk("C", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("Z", "L1", 1000.0, "S_Z")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        m.qty("D1", "Z", "L1") shouldBe (20.0 plusOrMinus 1e-9)
    }

    // ── Alt branches (union-alt) ──────────────────────────────────────────────

    test("alt_group children both contribute via union-alt") {
        // FG → (B OR B') (same alt_group). Each alt has its own downstream supply.
        // With union-alt, demand contributes to both B-leaf and B'-leaf.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "B", rate = 1.0, altGroup = "or1"),
                bom("FG", "B'", rate = 1.0, altGroup = "or1"),
                bom("B", "C", rate = 1.0),
                bom("B'", "C'", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("B", "L1"), mk("B'", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("C", "L1", 100.0, "S_C"),
                supply("C'", "L1", 100.0, "S_Cp"),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        // Both alt branches recorded — D1 contributes 10 to each candidate leaf.
        m.qty("D1", "C", "L1") shouldBe (10.0 plusOrMinus 1e-9)
        m.qty("D1", "C'", "L1") shouldBe (10.0 plusOrMinus 1e-9)
    }

    test("alt_group with shared downstream — needs sum across alts") {
        // FG → (B OR B') → C (shared). Both alts converge on C.
        // Demand qty 10. C's symbolic need = 10 (via B) + 10 (via B') = 20.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "B", rate = 1.0, altGroup = "or1"),
                bom("FG", "B'", rate = 1.0, altGroup = "or1"),
                bom("B", "C", rate = 1.0),
                bom("B'", "C", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("B", "L1"), mk("B'", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("C", "L1", 1000.0, "S_C")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        m.qty("D1", "C", "L1") shouldBe (20.0 plusOrMinus 1e-9)
    }

    // ── Two demands sharing a deep raw material ───────────────────────────────

    test("two demands sharing a deep raw material — column has both") {
        // Different FGs, both BOMs descend to the same raw material RM.
        val data = mapOf(
            "bom" to listOf(
                bom("FG_A", "X", rate = 2.0),
                bom("FG_B", "X", rate = 3.0),
                bom("X", "RM", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG_A", "L1"), mk("FG_B", "L1"), mk("X", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 10000.0, "S_RM")),
        )
        val demands = listOf(
            demand("D1", "FG_A", "L1", 10.0),
            demand("D2", "FG_B", "L1", 5.0),
        )

        val m = buildNeedsMatrix(demands, data)
        // Column-wise check: RM's column has both demands.
        val rmCol = m.byColumn[SupplyKey("RM", "L1")]!!
        rmCol[("D1")] shouldBe (20.0 plusOrMinus 1e-9)  // 10 × 2 × 1
        rmCol[("D2")] shouldBe (15.0 plusOrMinus 1e-9)  // 5 × 3 × 1
        rmCol.size shouldBe 2
    }

    // ── Cycle detection ───────────────────────────────────────────────────────

    test("BOM cycles do not loop forever") {
        // A → B → A (cycle). Walker should stop at the cycle detection.
        val data = mapOf(
            "bom" to listOf(
                bom("A", "B", rate = 1.0),
                bom("B", "A", rate = 1.0),
            ),
            "method_make" to listOf(mk("A", "L1"), mk("B", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            // Both nodes have supply so each visit emits a need; cycle detection
            // prevents recursion, so we don't double-count.
            "supply" to listOf(
                supply("A", "L1", 50.0, "S_A"),
                supply("B", "L1", 50.0, "S_B"),
            ),
        )
        val demands = listOf(demand("D1", "A", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        // Visited at: A (start), B (descendant). Cycle prevents re-entering A from B.
        m.qty("D1", "A", "L1") shouldBe (10.0 plusOrMinus 1e-9)
        m.qty("D1", "B", "L1") shouldBe (10.0 plusOrMinus 1e-9)
        m.byRow["D1"]!!.size shouldBe 2
    }

    // ── Demand with no path / no methods ──────────────────────────────────────

    test("demand with no methods or supply produces no row") {
        // FG has no make/buy/move methods AND no supply. Walker terminates immediately
        // with empty out, so the matrix has no row for D1.
        val data = mapOf(
            "bom" to emptyList<Map<String, Any?>>(),
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        m.isEmpty() shouldBe true
        m.cellCount() shouldBe 0
    }

    // ── Forward / inverted index consistency ──────────────────────────────────

    test("byRow and byColumn are consistent") {
        val data = mapOf(
            "bom" to listOf(
                bom("FG_A", "X", rate = 2.0),
                bom("FG_B", "X", rate = 3.0),
            ),
            "method_make" to listOf(mk("FG_A", "L1"), mk("FG_B", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("X", "L1", 1000.0, "S_X")),
        )
        val demands = listOf(
            demand("D1", "FG_A", "L1", 10.0),
            demand("D2", "FG_B", "L1", 5.0),
        )

        val m = buildNeedsMatrix(demands, data)
        // Total cells in byRow == total cells in byColumn.
        val rowCells = m.byRow.values.sumOf { it.size }
        val colCells = m.byColumn.values.sumOf { it.size }
        rowCells shouldBe colCells

        // Every entry in byRow must match byColumn.
        for ((demandId, supplyMap) in m.byRow) {
            for ((supplyKey, qty) in supplyMap) {
                m.byColumn[supplyKey]!![demandId] shouldBe (qty plusOrMinus 1e-9)
            }
        }
    }

    // ── Zero/missing supply doesn't appear in matrix ──────────────────────────

    test("only positive-qty supplies become columns") {
        // X has 0 supply → not in supplyIndex → no column.
        // Y has positive supply → column exists.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "X", rate = 1.0),
                bom("X", "Y", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("X", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("X", "L1", 0.0, "S_X_empty"),
                supply("Y", "L1", 100.0, "S_Y"),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val m = buildNeedsMatrix(demands, data)
        m.byRow["D1"]!!.size shouldBe 1
        m.qty("D1", "X", "L1") shouldBe 0.0  // not in matrix
        m.qty("D1", "Y", "L1") shouldBe (10.0 plusOrMinus 1e-9)
    }
})
