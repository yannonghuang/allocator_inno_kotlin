package com.allocator

import com.allocator.api.TOOLS
import com.allocator.api.parseSelectorsArg
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Pure-logic tests for the planning-agent's WO schedule-change / availability
 * tool surface. The tool *handlers* themselves are DB-dependent and exercised
 * through manual e2e + the WorkOrderScheduleImpactTest fixtures (which test
 * the underlying computeAvailability / runWoScheduleImpactInline helpers the
 * handlers wrap). What's covered here:
 *  - All 7 new tools are wired into the TOOLS registry (catches dispatch typos).
 *  - parseSelectorsArg handles valid + malformed input shapes.
 */
class PlanningAgentWoToolsTest : FunSpec({

    test("TOOLS registry — all 7 maintenance/downtime tools wired") {
        val names = TOOLS.map { it.name }.toSet()
        names.shouldContainAll(listOf(
            "list_prod_areas",
            "list_locations",
            "find_wos",
            "analyze_wo_availability",
            "analyze_wo_schedule_impact",
            "create_wo_schedule_event",
            "promote_plan_run",
        ))
    }

    test("TOOLS registry — every tool has non-empty description and a parameters object") {
        TOOLS.forEach { t ->
            t.name.isNotBlank() shouldBe true
            t.description.isNotBlank() shouldBe true
            t.parameters shouldNotBe null
        }
    }

    test("parseSelectorsArg — happy path: single selector") {
        val args = Json.parseToJsonElement(
            """{"selectors":[{"bucketStart":"2026-05-09","woGroupIds":["wog1","wog2"]}]}""",
        ).let { it as JsonObject }
        val sels = parseSelectorsArg(args)
        sels shouldNotBe null
        sels!!.size shouldBe 1
        sels[0].bucketStart shouldBe "2026-05-09"
        sels[0].woGroupIds shouldContain "wog1"
        sels[0].woGroupIds shouldContain "wog2"
    }

    test("parseSelectorsArg — multiple selectors") {
        val args = Json.parseToJsonElement("""
            {"selectors":[
              {"bucketStart":"2026-05-09","woGroupIds":["wog1"]},
              {"bucketStart":"2026-06-01","woGroupIds":["wog2","wog3"]}
            ]}
        """).let { it as JsonObject }
        val sels = parseSelectorsArg(args)
        sels!!.size shouldBe 2
        sels[1].woGroupIds.size shouldBe 2
    }

    test("parseSelectorsArg — missing key returns null") {
        val args = Json.parseToJsonElement("""{"foo":"bar"}""").let { it as JsonObject }
        parseSelectorsArg(args) shouldBe null
    }

    test("parseSelectorsArg — selector missing bucketStart is dropped") {
        val args = Json.parseToJsonElement("""
            {"selectors":[
              {"woGroupIds":["wog1"]},
              {"bucketStart":"2026-05-09","woGroupIds":["wog2"]}
            ]}
        """).let { it as JsonObject }
        val sels = parseSelectorsArg(args)
        sels!!.size shouldBe 1
        sels[0].woGroupIds shouldContain "wog2"
    }

    test("parseSelectorsArg — selector missing woGroupIds is dropped") {
        val args = Json.parseToJsonElement("""
            {"selectors":[{"bucketStart":"2026-05-09"}]}
        """).let { it as JsonObject }
        val sels = parseSelectorsArg(args)
        sels!!.isEmpty() shouldBe true
    }

    test("parseSelectorsArg — empty selectors array returns empty list") {
        val args = Json.parseToJsonElement("""{"selectors":[]}""").let { it as JsonObject }
        val sels = parseSelectorsArg(args)
        sels shouldNotBe null
        sels!!.isEmpty() shouldBe true
    }
})
