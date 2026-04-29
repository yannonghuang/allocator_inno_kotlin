package com.allocator

import com.allocator.api.mergeJsonObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Phase 1 unit tests for the planning agent's JsonObject deep-merge.
 *
 * The merge is the contract behind the agent's `update_config` tool: it must
 * preserve unrelated keys, replace scalar / array leaves, and recurse into
 * nested objects. The 3 user-stated intent buckets each rely on a different
 * shape of partial — these tests exercise all three.
 *
 * Full LLM-loop integration tests (PURCHASE / DEMAND / SUPPLY scenarios)
 * are deferred to Phase 2 because they require either a stubbable LLM
 * abstraction or a recorded fixture, which doesn't exist yet.
 */
class PlanningAgentMergeTest : FunSpec({

    val parser = Json { ignoreUnknownKeys = true; isLenient = true }
    fun obj(s: String) = parser.parseToJsonElement(s).jsonObject

    test("merge preserves unrelated top-level keys") {
        val base = obj("""{"purchase_allowed": true, "consolidation": {"enabled": true}}""")
        val patch = obj("""{"check_soundness": false}""")
        val out = mergeJsonObject(base, patch)
        out["purchase_allowed"].toString() shouldBe "true"
        out["consolidation"].toString() shouldBe """{"enabled":true}"""
        out["check_soundness"].toString() shouldBe "false"
    }

    test("PURCHASE intent: scalar replace at top level") {
        val base = obj("""{"purchase_allowed": true, "method_selection": {"max_methods": 2}}""")
        val patch = obj("""{"purchase_allowed": false}""")
        val out = mergeJsonObject(base, patch)
        out["purchase_allowed"].toString() shouldBe "false"
        // method_selection untouched
        out["method_selection"].toString() shouldBe """{"max_methods":2}"""
    }

    test("DEMAND intent: nested merge in consolidation + method_selection") {
        val base = obj(
            """{
                "consolidation": {"enabled": false, "period_days": 30},
                "method_selection": {"mode": "elaborate", "depth": 1}
            }""".trimIndent(),
        )
        val patch = obj(
            """{
                "consolidation": {"enabled": true, "allocation_mode": "fair"},
                "method_selection": {"max_methods": 2, "mode": "preference"}
            }""".trimIndent(),
        )
        val out = mergeJsonObject(base, patch)
        // consolidation: enabled replaced (false → true), period_days preserved (not in patch),
        // allocation_mode added, no other keys leaked.
        val cs = out["consolidation"].toString()
        cs.contains(""""enabled":true""") shouldBe true
        cs.contains(""""period_days":30""") shouldBe true
        cs.contains(""""allocation_mode":"fair"""") shouldBe true
        // method_selection: depth preserved, mode replaced, max_methods added.
        val ms = out["method_selection"].toString()
        ms.contains(""""depth":1""") shouldBe true
        ms.contains(""""mode":"preference"""") shouldBe true
        ms.contains(""""max_methods":2""") shouldBe true
    }

    test("SUPPLY intent: nested score_weights replacement") {
        val base = obj(
            """{"method_selection": {"mode": "preference",
                "score_weights": {"commit_time": 0.4, "inventory_consumed": 0.35, "purchase": 0.25}}}""".trimMargin(),
        )
        val patch = obj(
            """{"method_selection": {"mode": "elaborate",
                "score_weights": {"commit_time": 1, "inventory_consumed": 0, "purchase": 0}}}""".trimMargin(),
        )
        val out = mergeJsonObject(base, patch)
        val ms = out["method_selection"].toString()
        ms.contains(""""mode":"elaborate"""") shouldBe true
        ms.contains(""""commit_time":1""") shouldBe true
        ms.contains(""""inventory_consumed":0""") shouldBe true
        ms.contains(""""purchase":0""") shouldBe true
    }

    test("merge against null base = use patch as-is") {
        val patch = obj("""{"purchase_allowed": false, "consolidation": {"enabled": true}}""")
        val out = mergeJsonObject(null, patch)
        out shouldBe patch
    }

    test("array values in patch fully replace base arrays (no append)") {
        val base = obj("""{"some_list": [1, 2, 3]}""")
        val patch = obj("""{"some_list": [9]}""")
        val out = mergeJsonObject(base, patch)
        out["some_list"].toString() shouldBe "[9]"
    }
})
