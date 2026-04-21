package com.allocator

import com.allocator.services.extractSupplyAllocations
import com.allocator.services.verifySupplyCap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class ExtractSupplyAllocationsTest : FunSpec({

    fun supplyRow(id: String, qty: Double): Map<String, Any?> =
        mapOf("supply_id" to id, "qty" to qty)

    fun supplyLeaf(supplyId: String, qty: Double): Map<String, Any?> = mapOf(
        "type" to "supply", "supply_id" to supplyId, "quantity" to qty, "children" to emptyList<Any>(),
    )

    fun demandTree(demandId: String?, children: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "type" to "demand", "demand_id" to demandId, "children" to children,
    )

    fun entry(demandId: String?, tree: Map<String, Any?>): Map<String, Any?> =
        mapOf("demand_id" to demandId, "tree" to tree)

    test("passes bindings through untouched when within cap") {
        val supplies = listOf(supplyRow("S1", 100.0))
        val pegging = listOf(entry("D1", demandTree("D1", listOf(supplyLeaf("S1", 40.0)))))

        val allocs = extractSupplyAllocations(pegging, supplies)
        allocs shouldHaveSize 1
        allocs[0]["supply_id"] shouldBe "S1"
        allocs[0]["demand_id"] shouldBe "D1"
        (allocs[0]["qty_consumed"] as Double) shouldBe (40.0 plusOrMinus 1e-9)
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("truncates a binding that exceeds remaining cap") {
        val supplies = listOf(supplyRow("S1", 100.0))
        val pegging = listOf(
            entry("D1", demandTree("D1", listOf(supplyLeaf("S1", 80.0)))),
            entry("D2", demandTree("D2", listOf(supplyLeaf("S1", 50.0)))),
        )

        val allocs = extractSupplyAllocations(pegging, supplies)
        allocs shouldHaveSize 2
        (allocs[0]["qty_consumed"] as Double) shouldBe (80.0 plusOrMinus 1e-9)
        (allocs[1]["qty_consumed"] as Double) shouldBe (20.0 plusOrMinus 1e-9)
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("drops a binding when supply is fully exhausted") {
        val supplies = listOf(supplyRow("S1", 100.0))
        val pegging = listOf(
            entry("D1", demandTree("D1", listOf(supplyLeaf("S1", 100.0)))),
            entry("D2", demandTree("D2", listOf(supplyLeaf("S1", 30.0)))),
        )

        val allocs = extractSupplyAllocations(pegging, supplies)
        allocs shouldHaveSize 1
        allocs[0]["demand_id"] shouldBe "D1"
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("passes synthetic consolidated_ supply_ids through uncapped") {
        val supplies = listOf(supplyRow("S1", 50.0))  // S1 exists; synthetic does not
        val pegging = listOf(
            entry("D1", demandTree("D1", listOf(supplyLeaf("consolidated_D1_P1", 300.0)))),
            entry("D2", demandTree("D2", listOf(supplyLeaf("S1", 40.0)))),
        )

        val allocs = extractSupplyAllocations(pegging, supplies)
        allocs shouldHaveSize 2
        allocs[0]["supply_id"] shouldBe "consolidated_D1_P1"
        (allocs[0]["qty_consumed"] as Double) shouldBe (300.0 plusOrMinus 1e-9)  // uncapped
        allocs[1]["supply_id"] shouldBe "S1"
        (allocs[1]["qty_consumed"] as Double) shouldBe (40.0 plusOrMinus 1e-9)
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("consolidation tree (null demand_id) gets first claim, then per-demand trees share remainder") {
        val supplies = listOf(supplyRow("S1", 100.0))
        val pegging = listOf(
            entry(null, demandTree(null, listOf(supplyLeaf("S1", 60.0)))),
            entry("D1", demandTree("D1", listOf(supplyLeaf("S1", 50.0)))),
            entry("D2", demandTree("D2", listOf(supplyLeaf("S1", 50.0)))),
        )

        val allocs = extractSupplyAllocations(pegging, supplies)
        allocs shouldHaveSize 2  // third one drops to 0
        allocs[0]["demand_id"] shouldBe ""
        (allocs[0]["qty_consumed"] as Double) shouldBe (60.0 plusOrMinus 1e-9)
        allocs[1]["demand_id"] shouldBe "D1"
        (allocs[1]["qty_consumed"] as Double) shouldBe (40.0 plusOrMinus 1e-9)
        verifySupplyCap(supplies, allocs).shouldBeEmpty()
    }

    test("multiple leaves in same tree each decrement remaining") {
        val supplies = listOf(supplyRow("S1", 100.0))
        val pegging = listOf(
            entry("D1", demandTree("D1", listOf(
                supplyLeaf("S1", 40.0),
                supplyLeaf("S1", 50.0),
                supplyLeaf("S1", 30.0),
            ))),
        )

        val allocs = extractSupplyAllocations(pegging, supplies)
        allocs shouldHaveSize 3
        (allocs[0]["qty_consumed"] as Double) shouldBe (40.0 plusOrMinus 1e-9)
        (allocs[1]["qty_consumed"] as Double) shouldBe (50.0 plusOrMinus 1e-9)
        (allocs[2]["qty_consumed"] as Double) shouldBe (10.0 plusOrMinus 1e-9)  // 100-40-50=10
    }

    test("aggregated multi-row initial supply caps against total") {
        val supplies = listOf(supplyRow("S1", 60.0), supplyRow("S1", 40.0))  // total 100
        val pegging = listOf(
            entry("D1", demandTree("D1", listOf(supplyLeaf("S1", 150.0)))),
        )
        val allocs = extractSupplyAllocations(pegging, supplies)
        allocs shouldHaveSize 1
        (allocs[0]["qty_consumed"] as Double) shouldBe (100.0 plusOrMinus 1e-9)
    }
})
