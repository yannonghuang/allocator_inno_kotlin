package com.allocator

import com.allocator.api.TOOLS
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Pure-logic (DB-independent) coverage for the agent's read AND write tools over
 * the 5 versioned external config objects (Targeted Supply Allocation, Supply
 * Preferences, Demand Ordering, Purchasable Materials, Constraints) plus the
 * generic version lister — mirrors PlanningAgentWoToolsTest's "catches
 * dispatch typos" pattern. Handlers themselves are DB-dependent (read/write real
 * case_preference/case_demand_order/case_purchasable_material/case_constraint/
 * case_allocation rows, and resolveWritableVersionId hits CaseConfigVersioning)
 * and exercised via manual e2e against a live case instead.
 */
class PlanningAgentConfigObjectToolsTest : FunSpec({

    val readToolNames = setOf(
        "list_config_versions", "get_supply_preferences", "get_demand_ordering",
        "get_purchasable_materials", "get_constraints",
    )
    val writeToolNames = setOf(
        "set_purchasable_materials", "set_constraints", "set_demand_ordering",
        "set_supply_preferences", "set_targeted_supply_allocation",
    )

    test("TOOLS registry — all 5 external-config-object read tools wired") {
        val names = TOOLS.map { it.name }.toSet()
        names.shouldContainAll(readToolNames)
    }

    test("TOOLS registry — all 5 external-config-object write tools wired") {
        val names = TOOLS.map { it.name }.toSet()
        names.shouldContainAll(writeToolNames)
    }

    test("new tools — non-empty description and a parameters object") {
        val newToolNames = readToolNames + writeToolNames
        TOOLS.filter { it.name in newToolNames }.forEach { t ->
            t.description.isNotBlank() shouldBe true
            t.parameters shouldNotBe null
        }
        // Every tool name we expect was actually found (guards against a typo silently
        // matching zero tools and the loop above vacuously passing).
        TOOLS.map { it.name }.toSet().shouldContainAll(newToolNames)
    }

    test("list_config_versions requires kind") {
        val t = TOOLS.first { it.name == "list_config_versions" }
        val params = t.parameters as kotlinx.serialization.json.JsonObject
        val required = params["required"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .map { it.let { p -> p as kotlinx.serialization.json.JsonPrimitive }.content }
        required shouldBe listOf("kind")
    }

    test("write tools requiring `rows` declare it as required") {
        // set_demand_ordering and set_supply_preferences are the two exceptions — they accept
        // EITHER rows OR generate:true, so `rows` is intentionally NOT in their required list.
        val requiresRows = setOf("set_constraints", "set_targeted_supply_allocation")
        requiresRows.forEach { name ->
            val t = TOOLS.first { it.name == name }
            val params = t.parameters as kotlinx.serialization.json.JsonObject
            val required = params["required"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .map { it.let { p -> p as kotlinx.serialization.json.JsonPrimitive }.content }
            required shouldContainAll listOf("rows")
        }
    }

    test("set_purchasable_materials requires product_ids") {
        val t = TOOLS.first { it.name == "set_purchasable_materials" }
        val params = t.parameters as kotlinx.serialization.json.JsonObject
        val required = params["required"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .map { it.let { p -> p as kotlinx.serialization.json.JsonPrimitive }.content }
        required shouldBe listOf("product_ids")
    }
})
