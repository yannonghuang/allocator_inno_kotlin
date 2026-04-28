package com.allocator

import com.allocator.api.computeFairness
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class PlanKpiFairnessTest : FunSpec({

    fun closeTo(expected: Double, tol: Double = 1e-3): (Any?) -> Boolean = { actual ->
        actual is Number && abs(actual.toDouble() - expected) < tol
    }

    test("empty list — every metric is null") {
        val r = computeFairness(emptyList())
        r["gini"] shouldBe null
        r["p10_fill_ratio"] shouldBe null
        r["median_fill_ratio"] shouldBe null
        r["starvation_pct"] shouldBe null
    }

    test("single demand at full fill — Gini=0, all-1.0") {
        val r = computeFairness(listOf(1.0))
        (r["gini"] as Double) shouldBe 0.0
        (r["p10_fill_ratio"] as Double) shouldBe 1.0
        (r["median_fill_ratio"] as Double) shouldBe 1.0
        (r["starvation_pct"] as Double) shouldBe 0.0
    }

    test("all equal at 0.5 — Gini=0, no starvation") {
        val r = computeFairness(List(8) { 0.5 })
        (r["gini"] as Double) shouldBe 0.0
        (r["p10_fill_ratio"] as Double) shouldBe 0.5
        (r["median_fill_ratio"] as Double) shouldBe 0.5
        (r["starvation_pct"] as Double) shouldBe 0.0
    }

    test("one starved + nine full — starvation 10%, Gini ~0.18") {
        val r = computeFairness(listOf(0.0) + List(9) { 1.0 })
        // Gini for {0, 1×9} on n=10, sum=9:
        //   weighted = 1·0 + 2·1 + 3·1 + ... + 10·1 = 54
        //   G = 2·54 / (10·9) − 11/10 = 108/90 − 1.1 = 1.2 − 1.1 = 0.1
        (closeTo(0.1)(r["gini"])) shouldBe true
        (r["p10_fill_ratio"] as Double) shouldBe 0.0
        (r["median_fill_ratio"] as Double) shouldBe 1.0
        (r["starvation_pct"] as Double) shouldBe 10.0
    }

    test("linear ramp 0.1..1.0 — Gini=0.30, no full starvation") {
        val r = computeFairness((1..10).map { it / 10.0 })
        // For arithmetic progression 0.1..1.0 step 0.1, Gini ≈ 0.30
        (closeTo(0.30, tol = 0.005)(r["gini"])) shouldBe true
        (r["p10_fill_ratio"] as Double) shouldBe 0.1
        // Median of even-length list: avg of x[4] and x[5] = (0.5 + 0.6)/2 = 0.55
        (closeTo(0.55)(r["median_fill_ratio"])) shouldBe true
        (r["starvation_pct"] as Double) shouldBe 0.0
    }

    test("all zero — Gini=0 (degenerate), starvation=100%") {
        val r = computeFairness(List(5) { 0.0 })
        (r["gini"] as Double) shouldBe 0.0
        (r["p10_fill_ratio"] as Double) shouldBe 0.0
        (r["median_fill_ratio"] as Double) shouldBe 0.0
        (r["starvation_pct"] as Double) shouldBe 100.0
    }
})
