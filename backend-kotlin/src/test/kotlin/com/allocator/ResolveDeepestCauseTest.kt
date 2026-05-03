package com.allocator

import com.allocator.services.resolveDeepestCause
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for the cascade unwrapping helper that powers commit_reason
 * "deepest cause" labels at the AND-bottleneck branch in PlanningEngine.
 *
 * Pre-fix the planner reported `child_failed:<immediate_child>(no_inventory)`
 * for every blocked AND-method, which mislabeled deep BOM failures: a demand
 * for product P could fail because raw material RM (4 levels down) had no
 * supply, but the user saw "child_failed:P_subassembly@VIRTUAL(no_inventory)"
 * — pointing at the propagation, not the actual root.
 *
 * The helper unwraps the matryoshka-style nested reason string until it
 * reaches a non-cascade terminal, returning the deepest (pid, loc, cause)
 * triple. The caller falls back to the immediate child's coordinates when
 * unwrap yields "?", because broken/terminal pegging structures (rows
 * carrying reasons like "no_methods" without pid@loc) are an expected case.
 */
class ResolveDeepestCauseTest : FunSpec({

    test("simple terminal reason returns its pid/loc/cause") {
        resolveDeepestCause("child_failed:A@1(no_inventory)") shouldBe Triple("A", "1", "no_inventory")
    }

    test("two-level cascade unwraps to deepest") {
        resolveDeepestCause("child_failed:A@1(child_failed:B@2(no_inventory))") shouldBe
            Triple("B", "2", "no_inventory")
    }

    test("three-level cascade unwraps all the way down") {
        resolveDeepestCause(
            "child_failed:A@1(child_failed:B@2(child_failed:C@3(no_methods)))"
        ) shouldBe Triple("C", "3", "no_methods")
    }

    test("cascade with no_methods terminal preserves the cause") {
        resolveDeepestCause("child_failed:A@1(child_failed:B@2(no_methods))") shouldBe
            Triple("B", "2", "no_methods")
    }

    test("non-cascade reason returns ?/?/<reason>") {
        // E.g. "no_methods" or "depth_limit" appearing as the bottleneck
        // child's own row reason (broken pegging structure — expected).
        resolveDeepestCause("no_methods") shouldBe Triple("?", "?", "no_methods")
        resolveDeepestCause("depth_limit") shouldBe Triple("?", "?", "depth_limit")
    }

    test("null reason gives sensible default") {
        resolveDeepestCause(null) shouldBe Triple("?", "?", "no_inventory")
    }

    test("blank reason gives sensible default") {
        resolveDeepestCause("") shouldBe Triple("?", "?", "no_inventory")
        resolveDeepestCause("   ") shouldBe Triple("?", "?", "no_inventory")
    }

    test("location id with hyphens parses (real product/location strings)") {
        // Real data: "500-3953-02@VIRTUAL", "280-0845-030@1000" etc.
        resolveDeepestCause("child_failed:500-3953-02@VIRTUAL(no_inventory)") shouldBe
            Triple("500-3953-02", "VIRTUAL", "no_inventory")
        resolveDeepestCause(
            "child_failed:500-3953-02@VIRTUAL(child_failed:280-0845-030@1000(no_inventory))"
        ) shouldBe Triple("280-0845-030", "1000", "no_inventory")
    }

    test("malformed reason without parens returns the raw reason as terminal cause") {
        // Defensive: shouldn't crash, just surface what we got.
        resolveDeepestCause("garbage_reason_no_parens") shouldBe
            Triple("?", "?", "garbage_reason_no_parens")
    }

    test("cycle_stopped at any level is preserved") {
        resolveDeepestCause("child_failed:A@1(cycle_stopped)") shouldBe
            Triple("A", "1", "cycle_stopped")
    }
})
