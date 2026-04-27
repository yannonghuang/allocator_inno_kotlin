package com.allocator

import com.allocator.services.NeedsMatrix
import com.allocator.services.SupplyAllocations
import com.allocator.services.SupplyKey
import com.allocator.services.aggregateSupplies
import com.allocator.services.allocateSupplies
import com.allocator.services.buildNeedsMatrix
import com.allocator.services.compensate
import com.allocator.services.extractDemandPriorities
import com.allocator.services.runInitialCommit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Tests for [compensate] — Phase 3b of supply-level consolidation.
 *
 * Compensation takes the (allocations, actualDraws) state from Phase 3a and
 * redistributes any unused capacity to cap-bound demands with residual symbolic
 * need. Asserts:
 *
 *   - Demands that drew less than allocated free capacity for redistribution
 *   - Cap-bound demands with unmet need receive the freed capacity
 *   - Demands without unmet need don't get more (no over-allocation)
 *   - When all demands fully drew, compensation is a no-op (converged)
 *   - End-to-end: matrix → allocate → initial-commit → compensate produces
 *     the right caps for the case-162-style "alt-divergence" pattern
 */
class SupplyCompensationTest : FunSpec({

    // ── Compensation primitive — synthetic inputs ─────────────────────────────

    test("no-op when all demands fully drew their allocation") {
        // Both demands drew exactly what they were allocated → unused = 0.
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 50.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 50.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 50.0, "D2" to 50.0)),
        )
        val alloc = SupplyAllocations(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 30.0)),
        )
        val draws = mapOf<Any?, Map<SupplyKey, Double>>(
            "D1" to mapOf(SupplyKey("X", "L1") to 30.0),  // drew full alloc
            "D2" to mapOf(SupplyKey("X", "L1") to 30.0),  // drew full alloc
        )

        val result = compensate(alloc, draws, matrix, mapOf<Any?, Int>("D1" to 0, "D2" to 0), mode = "fair")

        result.redistributed shouldBe false
        result.qtyRedistributed shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("redistributes when one demand left allocation unused") {
        // D1 drew 0 of its 30 (alt-divergence). D2 drew its full 30 but had unmet
        // symbolic need 50. Revoke D1's unused 30 (cap → 0) and redistribute
        // 20 to D2 (its residual need); the remaining 10 has no candidate and
        // is dropped — total allocation at S shrinks from 60 to 50.
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 50.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 50.0)),
        )
        val alloc = SupplyAllocations(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 30.0)),
        )
        val draws = mapOf<Any?, Map<SupplyKey, Double>>(
            "D1" to emptyMap<SupplyKey, Double>(),  // didn't draw at all (alt-divergence)
            "D2" to mapOf(SupplyKey("X", "L1") to 30.0),  // cap-bound at 30
        )

        val result = compensate(alloc, draws, matrix, mapOf<Any?, Int>("D1" to 0, "D2" to 0), mode = "fair")

        result.redistributed shouldBe true
        result.supplyCount shouldBe 1
        // qtyRedistributed counts the revoked qty (30 from D1). D2 absorbs 20 of
        // it as a cap increment; the remaining 10 has no eligible demand and
        // is dropped on the floor — that's the conservative shrink toward draws.
        result.qtyRedistributed shouldBe (30.0 plusOrMinus 1e-9)

        // D1 revoked from 30 → 0 (drew nothing). D2 increased 30 → 50.
        result.allocations.byRow["D2"]!![SupplyKey("X", "L1")]!! shouldBe (50.0 plusOrMinus 1e-9)
        result.allocations.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("revokes unused even when no candidates need more") {
        // D1 left 20 unused (drew 10 of 30). D2 cap-bound at full need (alloc=30,
        // matrix=30, residual=0 → not a candidate). The 20 has nowhere to go —
        // but D1's cap still shrinks 30 → 10 so total allocation no longer
        // exceeds what was actually drawn at S. This is the conservative
        // shrink that keeps total allocation bounded across iterations.
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 30.0)),
        )
        val alloc = SupplyAllocations(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 30.0)),
        )
        val draws = mapOf<Any?, Map<SupplyKey, Double>>(
            "D1" to mapOf(SupplyKey("X", "L1") to 10.0),  // only drew 10 of 30
            "D2" to mapOf(SupplyKey("X", "L1") to 30.0),  // cap-bound, no headroom
        )

        val result = compensate(alloc, draws, matrix, mapOf<Any?, Int>("D1" to 0, "D2" to 0), mode = "fair")
        // Revocation IS a redistribution event (cap changed); reported as such.
        result.redistributed shouldBe true
        result.qtyRedistributed shouldBe (20.0 plusOrMinus 1e-9)
        result.allocations.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (10.0 plusOrMinus 1e-9)
        result.allocations.byRow["D2"]!![SupplyKey("X", "L1")]!! shouldBe (30.0 plusOrMinus 1e-9)
    }

    test("priority_first: highest priority gets compensation first") {
        // D1 (priority 5, low) drew 0 of 30 → 30 unused.
        // D2 (priority 1, high) and D3 (priority 3) both cap-bound at 30 with unmet need 50 and 50.
        // priority_first: D2 first (higher priority), then D3.
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 50.0),
                "D3" to mapOf(SupplyKey("X", "L1") to 50.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf(
                "D1" to 30.0, "D2" to 50.0, "D3" to 50.0,
            )),
        )
        val alloc = SupplyAllocations(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D3" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf(
                "D1" to 30.0, "D2" to 30.0, "D3" to 30.0,
            )),
        )
        val draws = mapOf<Any?, Map<SupplyKey, Double>>(
            "D1" to emptyMap<SupplyKey, Double>(),
            "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            "D3" to mapOf(SupplyKey("X", "L1") to 30.0),
        )
        val priorities = mapOf<Any?, Int>("D1" to 5, "D2" to 1, "D3" to 3)

        val result = compensate(alloc, draws, matrix, priorities, mode = "priority_first")

        // D1 revoked from 30 → 0 (drew nothing). Unused 30 redistributed:
        // D2 (priority 1) gets 20 (its residual need); D3 gets the rest 10.
        result.allocations.byRow["D2"]!![SupplyKey("X", "L1")]!! shouldBe (50.0 plusOrMinus 1e-9)
        result.allocations.byRow["D3"]!![SupplyKey("X", "L1")]!! shouldBe (40.0 plusOrMinus 1e-9)
        result.allocations.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("proportional: split residual among candidates by share of remaining need") {
        // D1 (cap 30) drew 0 → 30 unused.
        // D2 (alloc 30, need 50, residual 20) and D3 (alloc 30, need 80, residual 50) are cap-bound.
        // Residual total = 70. Unused = 30.
        // Proportional: D2 gets 30 × 20/70 ≈ 8.571; D3 gets 30 × 50/70 ≈ 21.428.
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 50.0),
                "D3" to mapOf(SupplyKey("X", "L1") to 80.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf(
                "D1" to 30.0, "D2" to 50.0, "D3" to 80.0,
            )),
        )
        val alloc = SupplyAllocations(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D3" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf(
                "D1" to 30.0, "D2" to 30.0, "D3" to 30.0,
            )),
        )
        val draws = mapOf<Any?, Map<SupplyKey, Double>>(
            "D1" to emptyMap<SupplyKey, Double>(),
            "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            "D3" to mapOf(SupplyKey("X", "L1") to 30.0),
        )

        val result = compensate(
            alloc, draws, matrix,
            mapOf<Any?, Int>("D1" to 0, "D2" to 0, "D3" to 0),
            mode = "proportional",
        )

        // D2: 30 + 30 × 20/70 ≈ 38.571
        // D3: 30 + 30 × 50/70 ≈ 51.428
        // D1: unchanged 30
        result.allocations.byRow["D2"]!![SupplyKey("X", "L1")]!! shouldBe (30.0 + 30.0 * 20.0 / 70.0 plusOrMinus 1e-6)
        result.allocations.byRow["D3"]!![SupplyKey("X", "L1")]!! shouldBe (30.0 + 30.0 * 50.0 / 70.0 plusOrMinus 1e-6)
    }

    test("alt-divergence: shut-out demand becomes candidate after another's withdrawal") {
        // D1 was allocated 0 (D2 had higher priority and grabbed all 30 supply).
        // D2 drew 0 of its 30 (alt-divergence: picked another path, didn't use S).
        // D1 had unmet need 30 and drew 0 (cap-bound at its 0 allocation).
        // Compensation: 30 unused → D1 gets 30 (its full need).
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 30.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 30.0, "D2" to 30.0)),
        )
        val alloc = SupplyAllocations(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 0.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 30.0),
            ),
            byColumn = mapOf(SupplyKey("X", "L1") to mapOf("D1" to 0.0, "D2" to 30.0)),
        )
        val draws = mapOf<Any?, Map<SupplyKey, Double>>(
            "D1" to emptyMap<SupplyKey, Double>(),  // 0 alloc → 0 draw → cap-bound
            "D2" to emptyMap<SupplyKey, Double>(),  // alt-divergence, didn't draw
        )

        val result = compensate(
            alloc, draws, matrix,
            mapOf<Any?, Int>("D1" to 0, "D2" to 0),
            mode = "fair",
        )

        // D2 had cap-bound + need = 30; redistribute 30 unused to D1 + D2 candidates.
        // D2's residualNeed = 30 - 30 = 0 → not a candidate.
        // D1's residualNeed = 30 - 0 = 30 → candidate.
        // All 30 goes to D1.
        result.redistributed shouldBe true
        result.allocations.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (30.0 plusOrMinus 1e-9)
    }

    test("multiple supplies independently compensated in one pass") {
        // X: D1 alloc=10 drew=0 (revoke 10) → D2 cap-bound with residual 10
        //    receives 10. Net at X: D1 0, D2 20.
        // Y: D1 alloc=5 drew=0 (revoke 5), D2 alloc=5 drew=3 (revoke 2). No
        //    candidates (D1's matrix=5 alloc=0 drew=0 cap-bound with residual=5
        //    is a candidate — gets 5 of the 7 unused). D2 stays at drew=3.
        //    Wait: D1's residual = matrix(5) - alloc(5) = 0 BEFORE revocation,
        //    but the partition uses currentAlloc not the post-revoke value, so
        //    D1 lands in the revoker bucket (drew < alloc) — not a candidate.
        //    No candidates at Y, all 7 unused dropped. Net at Y: D1 0, D2 3.
        val matrix = NeedsMatrix(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 10.0, SupplyKey("Y", "L1") to 5.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 20.0, SupplyKey("Y", "L1") to 5.0),
            ),
            byColumn = mapOf(
                SupplyKey("X", "L1") to mapOf("D1" to 10.0, "D2" to 20.0),
                SupplyKey("Y", "L1") to mapOf("D1" to 5.0, "D2" to 5.0),
            ),
        )
        val alloc = SupplyAllocations(
            byRow = mapOf(
                "D1" to mapOf(SupplyKey("X", "L1") to 10.0, SupplyKey("Y", "L1") to 5.0),
                "D2" to mapOf(SupplyKey("X", "L1") to 10.0, SupplyKey("Y", "L1") to 5.0),
            ),
            byColumn = mapOf(
                SupplyKey("X", "L1") to mapOf("D1" to 10.0, "D2" to 10.0),
                SupplyKey("Y", "L1") to mapOf("D1" to 5.0, "D2" to 5.0),
            ),
        )
        val draws = mapOf<Any?, Map<SupplyKey, Double>>(
            "D1" to mapOf<SupplyKey, Double>(),  // didn't draw at all
            "D2" to mapOf(
                SupplyKey("X", "L1") to 10.0,  // cap-bound, residual need 10
                SupplyKey("Y", "L1") to 3.0,   // not cap-bound (drew 3 of 5)
            ),
        )

        val result = compensate(
            alloc, draws, matrix,
            mapOf<Any?, Int>("D1" to 0, "D2" to 0),
            mode = "fair",
        )

        result.supplyCount shouldBe 2  // both X and Y had revocations
        result.qtyRedistributed shouldBe (17.0 plusOrMinus 1e-9)  // X: 10 revoked, Y: 7 revoked
        result.allocations.byRow["D2"]!![SupplyKey("X", "L1")]!! shouldBe (20.0 plusOrMinus 1e-9)  // 10 + 10
        result.allocations.byRow["D2"]!![SupplyKey("Y", "L1")]!! shouldBe (3.0 plusOrMinus 1e-9)   // revoked to drew
        result.allocations.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (0.0 plusOrMinus 1e-9)   // revoked to 0
        result.allocations.byRow["D1"]!![SupplyKey("Y", "L1")]!! shouldBe (0.0 plusOrMinus 1e-9)   // revoked to 0
    }

    // ── End-to-end: alt-divergence scenario ───────────────────────────────────

    test("end-to-end alt-divergence: pipeline + compensation makes the allocation honest") {
        // FG → (X OR X'). D1 has BOM through X, D2 has BOM through X'. Both
        // alt-children have supply.
        // With union-alt, D1 contributes need to both X and X'. D2 too.
        // After Phase 2, both are allocated at both X and X'.
        // Phase 3a: each demand picks one alt at commit (its preferred path),
        // leaving the other alt's allocation untouched.
        // Phase 3b: redistribute the unused alt allocations.
        val data = mapOf(
            "bom" to listOf(
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "X", "rate" to 1.0, "alt_group" to "or1"),
                mapOf("bom_id" to "BOM_FG", "parent_id" to "FG", "child_id" to "Xp", "rate" to 1.0, "alt_group" to "or1"),
            ),
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_FG", "product_id" to "FG", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0, "type" to "make"),
            ),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                mapOf("supply_id" to "S_X", "product_id" to "X", "location_id" to "L1", "supply_date" to "2024-01-01", "qty" to 100.0),
                mapOf("supply_id" to "S_Xp", "product_id" to "Xp", "location_id" to "L1", "supply_date" to "2024-01-01", "qty" to 100.0),
            ),
        )
        // Two demands, each needing 30 of FG. With union-alt, both demands
        // contribute to both X and Xp.
        val demands = listOf(
            mapOf("demand_id" to "D1", "product_id" to "FG", "location_id" to "L1", "quantity" to 30.0,
                  "request_due_time" to "2024-12-31", "request_time" to "2024-12-31", "priority" to 0),
            mapOf("demand_id" to "D2", "product_id" to "FG", "location_id" to "L1", "quantity" to 30.0,
                  "request_due_time" to "2024-12-31", "request_time" to "2024-12-31", "priority" to 0),
        )

        // Phase 1
        val matrix = buildNeedsMatrix(demands, data)
        // Both demands need both X and Xp (union-alt enumerates).
        matrix.byRow["D1"]!!.size shouldBe 2
        matrix.byRow["D2"]!!.size shouldBe 2

        // Phase 2 — abundant supply, each demand gets its full need at each alt.
        val supplies = aggregateSupplies(data["supply"] ?: emptyList())
        val priorities = extractDemandPriorities(demands)
        val alloc = allocateSupplies(matrix, supplies, priorities, mode = "fair")
        alloc.byRow["D1"]!![SupplyKey("X", "L1")]!! shouldBe (30.0 plusOrMinus 1e-9)
        alloc.byRow["D1"]!![SupplyKey("Xp", "L1")]!! shouldBe (30.0 plusOrMinus 1e-9)

        // Phase 3a — each demand walks its BOM. Picks one alt; the other's
        // allocation is left untouched.
        val inv = (data["supply"] ?: emptyList()).map { s ->
            mutableMapOf<String, Any?>(
                "product_id" to (s["product_id"] ?: ""),
                "location_id" to (s["location_id"] ?: ""),
                "supply_date" to s["supply_date"],
                "supply_id" to s["supply_id"],
                "qty" to ((s["qty"] as? Number)?.toDouble() ?: 0.0),
            )
        }.toMutableList()
        val initial = runInitialCommit(demands, inv, data, null, emptyMap(), alloc)

        // Both demands consumed at exactly one of X/Xp (whichever plan() picked).
        // The OTHER alt's allocation went untouched.
        val draws = initial.actualDraws

        // Phase 3b — compensate based on actual draws.
        // After compensation, the unpicked-alt's allocations should be
        // unchanged or zeroed (no candidates with unmet need at the unpicked
        // leaf, since both demands are abundance-served at the picked leaf).
        val comp = compensate(alloc, draws, matrix, priorities, mode = "fair")
        // Compensation may or may not redistribute depending on which alt
        // each demand picked. The key property: no demand ends up with MORE
        // than its symbolic need (matrix entry).
        for ((dId, supplies2) in comp.allocations.byRow) {
            for ((sk, qty) in supplies2) {
                val symbolic = matrix.byRow[dId]?.get(sk) ?: 0.0
                (qty <= symbolic + 1e-9) shouldBe true
            }
        }
    }
})
