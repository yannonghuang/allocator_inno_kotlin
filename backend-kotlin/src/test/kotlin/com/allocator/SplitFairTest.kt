package com.allocator

import com.allocator.services.ComponentNeed
import com.allocator.services.ConsolidationGroup
import com.allocator.services.splitFair
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class SplitFairTest : FunSpec({

    fun need(id: String, qty: Double, priority: Int): ComponentNeed = ComponentNeed(
        productId       = "P",
        locationId      = "L",
        dueDate         = null,
        qty             = qty,
        demandId        = id,
        priority        = priority,
        parentProductId = "",
    )

    fun group(needs: List<ComponentNeed>) = ConsolidationGroup(
        productId  = "P",
        locationId = "L",
        timeBucket = LocalDate.of(2024, 1, 1),
        needs      = needs,
        totalQty   = needs.sumOf { it.qty },
    )

    test("no shortage — behaves like priority_first (everyone gets their full need)") {
        val g = group(listOf(need("D1", 40.0, 1), need("D2", 30.0, 2)))
        val out = splitFair(g, 100.0)
        out["D1"]!! shouldBe (40.0 plusOrMinus 1e-9)
        out["D2"]!! shouldBe (30.0 plusOrMinus 1e-9)
    }

    test("shortage — falls back to proportional so no demand is starved") {
        val g = group(listOf(need("D1", 60.0, 1), need("D2", 40.0, 2)))
        val out = splitFair(g, 50.0)
        // proportional: 50 * 60/100 = 30,  50 * 40/100 = 20
        out["D1"]!! shouldBe (30.0 plusOrMinus 1e-9)
        out["D2"]!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    test("shortage with unequal priority — every demand still gets non-zero share") {
        val g = group(listOf(
            need("D1", 1000.0, 1),
            need("D2", 1000.0, 2),
            need("D3", 1000.0, 3),
        ))
        val out = splitFair(g, 300.0)
        // priority_first would give D1=300, D2=0, D3=0; fair must give all three
        out["D1"]!! shouldBe (100.0 plusOrMinus 1e-9)
        out["D2"]!! shouldBe (100.0 plusOrMinus 1e-9)
        out["D3"]!! shouldBe (100.0 plusOrMinus 1e-9)
    }

    test("exactly-matches threshold — no shortage branch") {
        val g = group(listOf(need("D1", 60.0, 1), need("D2", 40.0, 2)))
        val out = splitFair(g, 100.0)
        out["D1"]!! shouldBe (60.0 plusOrMinus 1e-9)
        out["D2"]!! shouldBe (40.0 plusOrMinus 1e-9)
    }
})
