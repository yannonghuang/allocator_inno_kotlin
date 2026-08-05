package com.allocator

import com.allocator.api.TOOLS
import com.allocator.api.clearPendingMaintenance
import com.allocator.api.loadPendingMaintenance
import com.allocator.api.parseSelectorsArg
import com.allocator.api.periodLabel
import com.allocator.api.rememberPendingMaintenance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

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

    // ── Scenario Q&A tool surface: find_wos customer_id/prod_areas, compare_alternatives group_by ──

    test("TOOLS registry — find_wos exposes customer_id and prod_areas params") {
        val findWos = TOOLS.first { it.name == "find_wos" }
        val props = findWos.parameters["properties"]!!.jsonObject
        props.keys shouldContainAll listOf("prod_area", "prod_areas", "customer_id")
    }

    test("TOOLS registry — list_customers is wired (grounds find_wos' customer_id, mirrors list_prod_areas/list_locations)") {
        val names = TOOLS.map { it.name }.toSet()
        names shouldContain "list_customers"
    }

    test("TOOLS registry — compare_alternatives exposes group_by param") {
        val compareAlternatives = TOOLS.first { it.name == "compare_alternatives" }
        val props = compareAlternatives.parameters["properties"]!!.jsonObject
        props.keys shouldContain "group_by"
    }

    test("periodLabel — day scale returns the date itself") {
        periodLabel(LocalDate.parse("2026-08-14"), "day") shouldBe "2026-08-14"
    }

    test("periodLabel — week scale returns the Monday of that ISO week") {
        // 2026-08-14 is a Friday; the Monday of its week is 2026-08-10.
        periodLabel(LocalDate.parse("2026-08-14"), "week") shouldBe "2026-08-10"
        // A Monday maps to itself.
        periodLabel(LocalDate.parse("2026-08-10"), "week") shouldBe "2026-08-10"
        // A Sunday maps back to the preceding Monday.
        periodLabel(LocalDate.parse("2026-08-16"), "week") shouldBe "2026-08-10"
    }

    test("periodLabel — unrecognized scale falls back to the raw date") {
        periodLabel(LocalDate.parse("2026-08-14"), "month") shouldBe "2026-08-14"
    }

    // ── PendingMaintenanceDecision cache lifecycle ───────────────────────────

    test("pending maintenance — round-trip remember + load") {
        val caseId = 990001
        clearPendingMaintenance(caseId)
        loadPendingMaintenance(caseId) shouldBe null

        rememberPendingMaintenance(caseId, contingentPlanRunId = 612, maxFeasibleDays = 5)
        val loaded = loadPendingMaintenance(caseId)
        loaded shouldNotBe null
        loaded!!.contingentPlanRunId shouldBe 612
        loaded.maxFeasibleDays shouldBe 5

        clearPendingMaintenance(caseId)
        loadPendingMaintenance(caseId) shouldBe null
    }

    test("pending maintenance — overwrite on second remember") {
        val caseId = 990002
        clearPendingMaintenance(caseId)
        rememberPendingMaintenance(caseId, contingentPlanRunId = 100, maxFeasibleDays = 3)
        rememberPendingMaintenance(caseId, contingentPlanRunId = 200, maxFeasibleDays = 7)
        loadPendingMaintenance(caseId)!!.contingentPlanRunId shouldBe 200
        clearPendingMaintenance(caseId)
    }

    test("pending maintenance — independent per case") {
        clearPendingMaintenance(990003)
        clearPendingMaintenance(990004)
        rememberPendingMaintenance(990003, contingentPlanRunId = 111, maxFeasibleDays = null)
        rememberPendingMaintenance(990004, contingentPlanRunId = 222, maxFeasibleDays = null)
        loadPendingMaintenance(990003)!!.contingentPlanRunId shouldBe 111
        loadPendingMaintenance(990004)!!.contingentPlanRunId shouldBe 222
        clearPendingMaintenance(990003)
        loadPendingMaintenance(990003) shouldBe null
        loadPendingMaintenance(990004)!!.contingentPlanRunId shouldBe 222
        clearPendingMaintenance(990004)
    }
})
