package com.allocator

import com.allocator.services.verifySupplyCap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PlanningEngineSupplyCapTest : FunSpec({

    fun supply(id: String, qty: Double): Map<String, Any?> =
        mapOf("supply_id" to id, "qty" to qty)

    fun alloc(id: String, qty: Double): Map<String, Any?> =
        mapOf("supply_id" to id, "qty_consumed" to qty)

    test("no violations when pegged <= initial") {
        val supplies = listOf(supply("S1", 100.0), supply("S2", 50.0))
        val allocs = listOf(alloc("S1", 70.0), alloc("S1", 30.0), alloc("S2", 25.0))
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("violation when a supply is over-pegged") {
        val supplies = listOf(supply("S1", 100.0))
        val allocs = listOf(alloc("S1", 80.0), alloc("S1", 30.0))
        val violations = verifySupplyCap(supplies, allocs)
        violations shouldHaveSize 1
        violations[0].shouldContain("S1")
        violations[0].shouldContain("110.0000")
        violations[0].shouldContain("100.0000")
    }

    test("synthetic supply_id not in supplies table is silently skipped (not a violation)") {
        val supplies = listOf(supply("S1", 100.0))
        val allocs = listOf(alloc("consolidated_D1_P1", 5.0))
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("tolerates 1e-6 floating-point slack") {
        val supplies = listOf(supply("S1", 100.0))
        val allocs = listOf(alloc("S1", 100.0 + 1e-9))
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("aggregates multi-row initial supply correctly") {
        val supplies = listOf(supply("S1", 60.0), supply("S1", 40.0))
        val allocs = listOf(alloc("S1", 95.0))
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("empty inputs produce no violations") {
        verifySupplyCap(emptyList(), emptyList()).shouldBeEmpty()
    }
})
