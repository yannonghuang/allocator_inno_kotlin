package com.allocator

import com.allocator.services.RunPlanningResult
import com.allocator.services.reconcileReallocatedSupplyAllocations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Behavior contract for [reconcileReallocatedSupplyAllocations] (PlanningEngine.kt) — the
 * reporting-only fix for R13_dominator_budget_exhausted false positives caused by
 * reallocate_critical_leftover's own prospective (round-behind) grant computation. See project
 * memory reallocation_r13_dominator_mismatch.md for the live case-173 trace this fix is based on:
 * demand 888_F37_2024_10_VIRTUAL was granted 1476.79 of critical material lot
 * 283-0226_2000_9075 in the round runPlanning returned as "best", but that round's own real
 * cross-demand competition for the shared lot only let it consume 507.05 — a later round's grant,
 * computed from THAT round's actual pattern, correctly re-targeted it to exactly 507.05.
 */
class ReallocationReconciliationTest : FunSpec({

    fun resultWith(supplyAllocations: List<Map<String, Any?>>) = RunPlanningResult(
        output = mapOf("supply_allocations" to supplyAllocations),
        inventoryEffectiveInitial = emptyList(),
        inventoryLeftover = emptyList(),
    )

    fun allocations(result: RunPlanningResult): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return result.output["supply_allocations"] as List<Map<String, Any?>>
    }

    test("qty_allocated above qty_consumed is floored down to qty_consumed") {
        val result = resultWith(listOf(
            mapOf("demand_id" to "888_F37_2024_10_VIRTUAL", "supply_id" to "283-0226_2000_9075",
                "qty_allocated" to 1476.7857109919125, "qty_consumed" to 507.0545632819418),
        ))
        val fixed = reconcileReallocatedSupplyAllocations(result)
        allocations(fixed)[0]["qty_allocated"] shouldBe 507.0545632819418
        allocations(fixed)[0]["qty_consumed"] shouldBe 507.0545632819418
    }

    test("a demand that consumed nothing of its grant is floored to zero") {
        // Matches 858_F37_2024_10_VIRTUAL: granted budget for a lot its own tree can no longer
        // structurally reach (a different, still-open issue — see project memory) — regardless
        // of cause, this reconciliation makes the reported number match reality either way.
        val result = resultWith(listOf(
            mapOf("demand_id" to "858_F37_2024_10_VIRTUAL", "supply_id" to "283-0226_2000_9075",
                "qty_allocated" to 738.3655973757267, "qty_consumed" to 0.0),
        ))
        val fixed = reconcileReallocatedSupplyAllocations(result)
        allocations(fixed)[0]["qty_allocated"] shouldBe 0.0
    }

    test("qty_allocated already equal to qty_consumed is left untouched (self-consistent round)") {
        val row = mapOf("demand_id" to "D1", "supply_id" to "S1", "qty_allocated" to 100.0, "qty_consumed" to 100.0)
        val result = resultWith(listOf(row))
        val fixed = reconcileReallocatedSupplyAllocations(result)
        allocations(fixed)[0] shouldBe row
    }

    test("null qty_allocated (non-critical/purchasable lot) is left untouched, stays null") {
        val row = mapOf("demand_id" to "D1", "supply_id" to "S1", "qty_allocated" to null, "qty_consumed" to 250.0)
        val result = resultWith(listOf(row))
        val fixed = reconcileReallocatedSupplyAllocations(result)
        allocations(fixed)[0]["qty_allocated"] shouldBe null
    }

    test("nothing to reconcile returns the SAME RunPlanningResult instance, not a copy") {
        val result = resultWith(listOf(
            mapOf("demand_id" to "D1", "supply_id" to "S1", "qty_allocated" to 50.0, "qty_consumed" to 50.0),
        ))
        (reconcileReallocatedSupplyAllocations(result) === result) shouldBe true
    }

    test("mixed rows: only the over-allocated ones change, others pass through as-is") {
        val fine = mapOf("demand_id" to "D1", "supply_id" to "S1", "qty_allocated" to 30.0, "qty_consumed" to 30.0)
        val stale = mapOf("demand_id" to "D2", "supply_id" to "S2", "qty_allocated" to 900.0, "qty_consumed" to 12.5)
        val result = resultWith(listOf(fine, stale))
        val fixed = reconcileReallocatedSupplyAllocations(result)
        allocations(fixed)[0] shouldBe fine
        allocations(fixed)[1]["qty_allocated"] shouldBe 12.5
    }

    test("missing supply_allocations key returns the result unchanged") {
        val result = RunPlanningResult(output = mapOf("committed_demands" to emptyList<Any?>()),
            inventoryEffectiveInitial = emptyList(), inventoryLeftover = emptyList())
        (reconcileReallocatedSupplyAllocations(result) === result) shouldBe true
    }
})
