package com.allocator

import com.allocator.services.denseRankByPreference
import com.allocator.services.equalSplitQty
import com.allocator.services.proportionalSplitQty
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * Unit tests for the multi-method split helpers backing
 * [com.allocator.services.selectAndSplitMethods].
 *
 * The full `selectAndSplitMethods` requires plan() and inventory state, which
 * makes it integration-shaped; we cover the deterministic math (rank, equal
 * split, proportional split) here and exercise the full pipeline via case-171
 * scenarios in the integration suite.
 */
class SelectAndSplitMethodsTest : FunSpec({

    fun method(pref: Long?): Map<String, Any?> = mapOf("preference" to pref)

    fun closeTo(expected: List<Double>, actual: List<Double>, tol: Double = 1e-3): Boolean {
        if (expected.size != actual.size) return false
        return expected.zip(actual).all { (e, a) -> abs(e - a) < tol }
    }

    // ── denseRankByPreference ───────────────────────────────────────────────

    test("dense rank: distinct ascending values get 1, 2, 3, ...") {
        val ms = listOf(method(0), method(5), method(100))
        denseRankByPreference(ms).map { it.second } shouldBe listOf(1, 2, 3)
    }

    test("dense rank: ties share a rank, next distinct value advances by 1") {
        // Raw [0, 0, 5, 100] → ranks [1, 1, 2, 3].
        val ms = listOf(method(0), method(0), method(5), method(100))
        denseRankByPreference(ms).map { it.second } shouldBe listOf(1, 1, 2, 3)
    }

    test("dense rank: negative values still order naturally") {
        // Real data has values like -7304; lower is better.
        val ms = listOf(method(50), method(-7304), method(50))
        // -7304 → rank 1; 50 ties → rank 2 for both.
        denseRankByPreference(ms).map { it.second } shouldBe listOf(2, 1, 2)
    }

    test("dense rank: missing preference gets last-resort rank") {
        val ms = listOf(method(0), method(null), method(5))
        // Distinct present values: [0, 5] → ranks {0:1, 5:2}; null → 3 (last-resort).
        denseRankByPreference(ms).map { it.second } shouldBe listOf(1, 3, 2)
    }

    test("dense rank: empty input returns empty") {
        denseRankByPreference(emptyList()).shouldBeEmpty()
    }

    // ── equalSplitQty ───────────────────────────────────────────────────────

    test("equal split: integer demand=10 across 3 → [4, 3, 3]") {
        equalSplitQty(10.0, 3) shouldBe listOf(4.0, 3.0, 3.0)
    }

    test("equal split: integer demand=10 across 2 → [5, 5]") {
        equalSplitQty(10.0, 2) shouldBe listOf(5.0, 5.0)
    }

    test("equal split: fractional demand=10.5 across 3 → [3.5, 3.5, 3.5]") {
        closeTo(listOf(3.5, 3.5, 3.5), equalSplitQty(10.5, 3)) shouldBe true
    }

    test("equal split: n=1 returns [qty] regardless of fractional-ness") {
        equalSplitQty(7.0, 1) shouldBe listOf(7.0)
        equalSplitQty(7.5, 1) shouldBe listOf(7.5)
    }

    test("equal split: n=0 returns empty") {
        equalSplitQty(10.0, 0).shouldBeEmpty()
    }

    // ── proportionalSplitQty ────────────────────────────────────────────────

    test("proportional, integer demand=10, weights [3, 1] → [8, 2]") {
        // exact = [7.5, 2.5]; floor = [7, 2]; leftover 1 → highest-remainder slot 0.
        proportionalSplitQty(10.0, listOf(3.0, 1.0)) shouldBe listOf(8.0, 2.0)
    }

    test("proportional, fractional demand=10.0 (treated as integer) but explicit fractional via 10.5") {
        closeTo(listOf(7.875, 2.625), proportionalSplitQty(10.5, listOf(3.0, 1.0))) shouldBe true
    }

    test("proportional, integer demand=11, weights inverse ranks [1, 1/2, 1/3] → [6, 3, 2]") {
        // Σ = 1 + 1/2 + 1/3 = 11/6; shares: 6, 3, 2 exactly. Sum = 11. ✓
        val w = listOf(1.0, 0.5, 1.0 / 3.0)
        proportionalSplitQty(11.0, w) shouldBe listOf(6.0, 3.0, 2.0)
    }

    test("proportional fallback on all-zero weights → equal split") {
        proportionalSplitQty(10.0, listOf(0.0, 0.0, 0.0)) shouldBe listOf(4.0, 3.0, 3.0)
    }

    test("proportional fallback on all-equal weights → equal split") {
        proportionalSplitQty(10.0, listOf(0.5, 0.5, 0.5)) shouldBe listOf(4.0, 3.0, 3.0)
    }

    test("proportional fallback on empty weights → empty") {
        proportionalSplitQty(10.0, emptyList()).shouldBeEmpty()
    }

    test("proportional, integer conservation under largest-remainder for many slots") {
        // 5 weights with awkward fractions; sum must equal demand exactly.
        val out = proportionalSplitQty(100.0, listOf(0.31, 0.27, 0.19, 0.13, 0.10))
        out.sum() shouldBe 100.0
        out.size shouldBe 5
    }
})

private fun List<*>.shouldBeEmpty() {
    require(this.isEmpty()) { "expected empty list, got $this" }
}
