package com.allocator

import com.allocator.services.resolveActiveRunId
import com.allocator.services.resolveInitialRunId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the pure active/initial resolvers. Covers:
 *   - no designation, no success runs → null
 *   - no designation, one success → that run
 *   - no designation, multiple successes → highest id
 *   - valid designation → designated wins
 *   - stale designation (id not in successes — e.g. deleted or non-success) → fallback
 *   - initial picks earliest non-failed
 */
class PlanRunHistoryResolverTest : FunSpec({

    // ── resolveActiveRunId ────────────────────────────────────────────────────

    test("no designation, no success runs → null") {
        resolveActiveRunId(null, emptyList()) shouldBe null
    }

    test("no designation, one success → that run") {
        resolveActiveRunId(null, listOf(7)) shouldBe 7
    }

    test("no designation, multiple successes → highest id") {
        resolveActiveRunId(null, listOf(3, 9, 5)) shouldBe 9
    }

    test("valid designation → designated wins over latest") {
        resolveActiveRunId(designatedId = 5, successRunIds = listOf(3, 5, 9)) shouldBe 5
    }

    test("designation pointing at non-success id → falls back to latest success") {
        // e.g. the designated run was failed, or deleted but pointer not yet nulled
        resolveActiveRunId(designatedId = 42, successRunIds = listOf(3, 9)) shouldBe 9
    }

    test("designation set with zero successes → null (nothing to fall back to)") {
        resolveActiveRunId(designatedId = 42, successRunIds = emptyList()) shouldBe null
    }

    // ── resolveInitialRunId ───────────────────────────────────────────────────

    test("empty → null") {
        resolveInitialRunId(emptyList()) shouldBe null
    }

    test("only failed runs → null (failed can't be initial)") {
        resolveInitialRunId(listOf(1 to "failed", 2 to "failed")) shouldBe null
    }

    test("mixed statuses → earliest non-failed wins") {
        // id 2 is earliest non-failed (1 is failed, skipped)
        resolveInitialRunId(listOf(1 to "failed", 2 to "success", 3 to "contingent")) shouldBe 2
    }

    test("contingent qualifies as initial when it's the earliest non-failed") {
        resolveInitialRunId(listOf(1 to "contingent", 2 to "success")) shouldBe 1
    }

    test("running/ready don't count as initial") {
        // Neither running nor ready should be considered; falls through to id=3 success
        resolveInitialRunId(listOf(1 to "running", 2 to "ready", 3 to "success")) shouldBe 3
    }
})
