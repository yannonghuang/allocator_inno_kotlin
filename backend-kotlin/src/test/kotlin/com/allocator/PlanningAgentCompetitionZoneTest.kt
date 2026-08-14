package com.allocator

import com.allocator.api.TOOLS
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure-logic (DB-independent) coverage for the `explain_competition_zone` tool's
 * registration — mirrors PlanningAgentWoToolsTest's "catches dispatch typos"
 * pattern. The handler itself (toolExplainCompetitionZone) recomputes
 * computeAndSiblingCaps/findOrGroupRecipients against a real case's data+config,
 * so it's DB-dependent and exercised via manual e2e against a live case instead.
 */
class PlanningAgentCompetitionZoneTest : FunSpec({

    test("TOOLS registry — explain_competition_zone is wired") {
        val names = TOOLS.map { it.name }.toSet()
        names shouldContain "explain_competition_zone"
    }

    test("explain_competition_zone — has non-empty description and required params") {
        val t = TOOLS.first { it.name == "explain_competition_zone" }
        t.description.isNotBlank() shouldBe true
        t.parameters shouldNotBe null

        val params = t.parameters as JsonObject
        val properties = params["properties"]!!.jsonObject
        properties.keys shouldContain "run_id"
        properties.keys shouldContain "demand_id"
        properties.keys shouldContain "product_id"
        properties.keys shouldContain "location_id"

        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        required shouldBe setOf("run_id", "demand_id", "product_id", "location_id")
    }
})
