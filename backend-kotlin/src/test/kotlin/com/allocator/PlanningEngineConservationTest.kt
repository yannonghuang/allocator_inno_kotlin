package com.allocator

import com.allocator.services.verifyInventoryConservation
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain

class PlanningEngineConservationTest : FunSpec({

    fun supply(id: String, qty: Double, demandTag: String? = null): Map<String, Any?> =
        if (demandTag != null) mapOf("supply_id" to id, "qty" to qty, "demand_tag" to demandTag)
        else mapOf("supply_id" to id, "qty" to qty)

    fun alloc(id: String, qty: Double): Map<String, Any?> =
        mapOf("supply_id" to id, "qty_consumed" to qty)

    fun allocFor(id: String, demandId: String, qty: Double): Map<String, Any?> =
        mapOf("supply_id" to id, "demand_id" to demandId, "qty_consumed" to qty)

    test("full consumption: initial == pegged, zero leftover — no violation") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 0.0))
        val allocs = listOf(alloc("S1", 100.0))
        verifyInventoryConservation(initial, leftover, allocs).shouldBeEmpty()
    }

    test("partial use: leftover + pegged == initial — no violation") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 40.0))
        val allocs = listOf(alloc("S1", 60.0))
        verifyInventoryConservation(initial, leftover, allocs).shouldBeEmpty()
    }

    test("multi-supply: each supply balanced independently — no violation") {
        val initial = listOf(supply("S1", 100.0), supply("S2", 50.0))
        val leftover = listOf(supply("S1", 30.0), supply("S2", 50.0))
        val allocs = listOf(alloc("S1", 70.0))
        verifyInventoryConservation(initial, leftover, allocs).shouldBeEmpty()
    }

    test("synthetic consolidated_ supply excluded from conservation check") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 100.0), supply("consolidated_P1_L1", 999.0))
        val allocs = listOf(alloc("consolidated_P1_L1", 500.0))
        verifyInventoryConservation(initial, leftover, allocs).shouldBeEmpty()
    }

    test("supply-split override: demand-tagged sub-buckets sum correctly — no violation") {
        // Supply S1 originally 100 units, split: D1 gets 60, D2 gets 40
        val initial = listOf(supply("S1", 60.0, "D1"), supply("S1", 40.0, "D2"))
        // D1 used 50, D2 used 30 → leftover: 10 + 10 = 20
        val leftover = listOf(supply("S1", 10.0, "D1"), supply("S1", 10.0, "D2"))
        val allocs = listOf(alloc("S1", 50.0), alloc("S1", 30.0))
        verifyInventoryConservation(initial, leftover, allocs).shouldBeEmpty()
    }

    test("ghost depletion: leftover + pegged < initial — violation detected") {
        // initial=100, leftover=20, pegged=60 → total=80, discrepancy=20
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 20.0))
        val allocs = listOf(alloc("S1", 60.0))
        val violations = verifyInventoryConservation(initial, leftover, allocs)
        violations shouldHaveSize 1
        violations[0].shouldContain("S1")
        violations[0].shouldContain("100.0000")
        violations[0].shouldContain("20.0000")
        violations[0].shouldContain("60.0000")
    }

    test("overclaim: pegged > initial, leftover=0 — violation detected") {
        // initial=100, leftover=0, pegged=120 → discrepancy=20
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 0.0))
        val allocs = listOf(alloc("S1", 120.0))
        val violations = verifyInventoryConservation(initial, leftover, allocs)
        violations shouldHaveSize 1
        violations[0].shouldContain("S1")
    }

    test("orphaned leftover: supply in post-planning inventory not in effective initial — violation") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 0.0), supply("GHOST", 50.0))
        val allocs = listOf(alloc("S1", 100.0))
        val violations = verifyInventoryConservation(initial, leftover, allocs)
        violations shouldHaveSize 1
        violations[0].shouldContain("GHOST")
        violations[0].shouldContain("orphaned")
    }

    test("floating-point tolerance: discrepancy within 1e-6 is not a violation") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 40.0 + 1e-9))
        val allocs = listOf(alloc("S1", 60.0))
        verifyInventoryConservation(initial, leftover, allocs).shouldBeEmpty()
    }

    test("empty inputs produce no violations") {
        verifyInventoryConservation(emptyList(), emptyList(), emptyList()).shouldBeEmpty()
    }

    // ── servedDemandIds: "pegged" must mean pegged to a real served demand ─────────────

    test("dead branch: real draw attributed to an unserved demand is excluded from pegged — violation detected") {
        // D1's own AND-sibling branch genuinely drew 60 units (real, non-rolled-back
        // physical consumption — leftover correctly reflects it as gone), but D1's
        // overall committed_qty collapsed to 0 because a different sibling failed.
        // Without the servedDemandIds filter this balances (100 = 40 + 60) and hides
        // the fact that 60 units are neither leftover nor helping any served demand.
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 40.0))
        val allocs = listOf(allocFor("S1", "D1", 60.0))
        val violations = verifyInventoryConservation(initial, leftover, allocs, servedDemandIds = emptySet())
        violations shouldHaveSize 1
        violations[0].shouldContain("S1")
    }

    test("served demand: real draw attributed to a served demand is still counted — no violation") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 40.0))
        val allocs = listOf(allocFor("S1", "D1", 60.0))
        verifyInventoryConservation(initial, leftover, allocs, servedDemandIds = setOf("D1")).shouldBeEmpty()
    }

    test("mixed: served demand's draw counted, dead branch's draw excluded") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 10.0))
        val allocs = listOf(
            allocFor("S1", "D1", 30.0),  // served — counted
            allocFor("S1", "D2", 40.0),  // unserved — excluded, so this is the missing 40
        )
        val violations = verifyInventoryConservation(initial, leftover, allocs, servedDemandIds = setOf("D1"))
        violations shouldHaveSize 1
        violations[0].shouldContain("S1")
    }

    test("servedDemandIds=null preserves legacy unfiltered behavior") {
        val initial = listOf(supply("S1", 100.0))
        val leftover = listOf(supply("S1", 40.0))
        val allocs = listOf(allocFor("S1", "D1", 60.0))
        verifyInventoryConservation(initial, leftover, allocs, servedDemandIds = null).shouldBeEmpty()
    }
})
