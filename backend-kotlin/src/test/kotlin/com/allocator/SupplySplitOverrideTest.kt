package com.allocator

import com.allocator.services.buildOverrideIndex
import com.allocator.services.buildSupplyCapMap
import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class SupplySplitOverrideTest : FunSpec({

    // ── Fixtures ──────────────────────────────────────────────────────────────
    fun demand(id: String, pid: String, lid: String, qty: Double, priority: Int = 1): Map<String, Any?> =
        mapOf(
            "demand_id" to id, "product_id" to pid, "location_id" to lid,
            "quantity" to qty, "priority" to priority,
            "request_due_time" to "2025-03-01", "request_time" to "2025-03-01",
            "customer_id" to null, "customer" to null,
        )

    fun supply(sid: String, pid: String, lid: String, qty: Double, date: String = "2025-01-01"): Map<String, Any?> =
        mapOf(
            "supply_id" to sid, "product_id" to pid, "location_id" to lid,
            "qty" to qty, "supply_date" to date,
        )

    fun override(supplyId: String, allocations: List<Pair<String, Double>>): Map<String, Any?> = mapOf(
        "entity_type" to "supply_split",
        "entity_key" to supplyId,
        "payload" to mapOf(
            "allocations" to allocations.map { (d, q) -> mapOf("demand_id" to d, "qty" to q) }
        ),
    )

    @Suppress("UNCHECKED_CAST")
    fun supplyAllocs(result: Map<String, Any>): List<Map<String, Any?>> =
        result["supply_allocations"] as? List<Map<String, Any?>> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun overrideWarnings(result: Map<String, Any>): List<Map<String, Any?>> =
        result["override_warnings"] as? List<Map<String, Any?>> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun committed(result: Map<String, Any>): List<Map<String, Any?>> =
        result["committed_demands"] as? List<Map<String, Any?>> ?: emptyList()

    // Real supply-backed commit qty (excludes no_methods/child_failed shortfall rows).
    fun committedFor(result: Map<String, Any>, demandId: String): Double {
        val hardFailure = setOf("no_methods", "no_preferred_method", "depth_limit")
        return committed(result).asSequence()
            .filter { it["demand_id"] == demandId }
            .filter { (it["commit_reason"] as? String).let { r -> r == null || (r !in hardFailure && !r.startsWith("child_failed")) } }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
    }

    // ── Unit test: buildSupplyCapMap ──────────────────────────────────────────

    test("buildSupplyCapMap: basic parse") {
        val overrideIdx = buildOverrideIndex(listOf(
            override("S1", listOf("D1" to 30.0, "D2" to 70.0))
        ))
        val supplies = listOf(supply("S1", "P", "L", 100.0))
        val demands  = listOf(demand("D1", "P", "L", 80.0), demand("D2", "P", "L", 20.0))
        val caps = buildSupplyCapMap(overrideIdx, supplies, demands)
        caps shouldContainKey "S1"
        caps["S1"]!!["D1"]!! shouldBe (30.0 plusOrMinus 1e-9)
        caps["S1"]!!["D2"]!! shouldBe (70.0 plusOrMinus 1e-9)
    }

    test("buildSupplyCapMap: clamps total > supply.qty proportionally") {
        val overrideIdx = buildOverrideIndex(listOf(
            override("S1", listOf("D1" to 90.0, "D2" to 60.0))  // sum=150, supply=100
        ))
        val supplies = listOf(supply("S1", "P", "L", 100.0))
        val demands  = listOf(demand("D1", "P", "L", 80.0), demand("D2", "P", "L", 80.0))
        val caps = buildSupplyCapMap(overrideIdx, supplies, demands)
        caps["S1"]!!["D1"]!! shouldBe (60.0 plusOrMinus 1e-9)  // 90 * (100/150)
        caps["S1"]!!["D2"]!! shouldBe (40.0 plusOrMinus 1e-9)  // 60 * (100/150)
    }

    test("buildSupplyCapMap: drops unknown demand_ids") {
        val overrideIdx = buildOverrideIndex(listOf(
            override("S1", listOf("D1" to 30.0, "D_UNKNOWN" to 50.0))
        ))
        val supplies = listOf(supply("S1", "P", "L", 100.0))
        val demands  = listOf(demand("D1", "P", "L", 80.0))
        val caps = buildSupplyCapMap(overrideIdx, supplies, demands)
        caps["S1"] shouldBe mapOf("D1" to 30.0)
    }

    test("buildSupplyCapMap: ignores non-supply_split entries") {
        val overrideIdx = buildOverrideIndex(listOf(
            mapOf("entity_type" to "component_split", "entity_key" to "wo1",
                  "payload" to mapOf("allocations" to listOf(mapOf("demand_id" to "D1", "qty" to 10.0))))
        ))
        val caps = buildSupplyCapMap(overrideIdx, listOf(supply("S1", "P", "L", 100.0)), listOf(demand("D1", "P", "L", 10.0)))
        caps.size shouldBe 0
    }

    // ── Integration: runPlanning end-to-end ───────────────────────────────────

    test("basic enforcement: plan_supply_allocation matches override") {
        val data = mapOf(
            "demand"    to listOf(demand("D1", "P", "L", 80.0), demand("D2", "P", "L", 20.0)),
            "supply"    to listOf(supply("S1", "P", "L", 100.0)),
            "overrides" to listOf(override("S1", listOf("D1" to 30.0, "D2" to 70.0))),
        )
        val result = runPlanning(data)
        val allocs = supplyAllocs(result).filter { it["supply_id"] == "S1" }
        val byDemand = allocs.groupBy { it["demand_id"] as String }
            .mapValues { (_, list) -> list.sumOf { (it["qty_consumed"] as Number).toDouble() } }
        byDemand["D1"]!! shouldBe (30.0 plusOrMinus 1e-6)
        byDemand["D2"]!! shouldBe (20.0 plusOrMinus 1e-6)  // D2 only needed 20, cap was 70
    }

    test("clamp: over-budget override is scaled down") {
        val data = mapOf(
            "demand"    to listOf(demand("D1", "P", "L", 150.0), demand("D2", "P", "L", 150.0)),
            "supply"    to listOf(supply("S1", "P", "L", 100.0)),
            "overrides" to listOf(override("S1", listOf("D1" to 90.0, "D2" to 60.0))),  // sum=150, clamps to 60/40
        )
        val result = runPlanning(data)
        val allocs = supplyAllocs(result).filter { it["supply_id"] == "S1" }
        val byDemand = allocs.groupBy { it["demand_id"] as String }
            .mapValues { (_, list) -> list.sumOf { (it["qty_consumed"] as Number).toDouble() } }
        byDemand["D1"]!! shouldBe (60.0 plusOrMinus 1e-6)
        byDemand["D2"]!! shouldBe (40.0 plusOrMinus 1e-6)
    }

    test("exclusion: demand absent from override gets 0 from that supply") {
        // Supply S1=100. Override {D1:100}. D2 gets 0 from S1 even if it needs more.
        val data = mapOf(
            "demand"    to listOf(demand("D1", "P", "L", 40.0), demand("D2", "P", "L", 30.0)),
            "supply"    to listOf(supply("S1", "P", "L", 100.0)),
            "overrides" to listOf(override("S1", listOf("D1" to 100.0))),
        )
        val result = runPlanning(data)
        val allocs = supplyAllocs(result).filter { it["supply_id"] == "S1" }
        val byDemand = allocs.groupBy { it["demand_id"] as String }
            .mapValues { (_, list) -> list.sumOf { (it["qty_consumed"] as Number).toDouble() } }
        byDemand["D1"]!! shouldBe (40.0 plusOrMinus 1e-6)   // D1 took 40 of its 100 cap
        byDemand["D2"] shouldBe null                         // D2 got nothing from S1
    }

    test("soft-warn: infeasible override emits override_warnings, plan still completes") {
        // S1 is the only supply. D1 needs 100, override caps D1 at 40.
        // D1 must commit at most 40 (no alternative supply); warning emitted.
        val data = mapOf(
            "demand"    to listOf(demand("D1", "P", "L", 100.0)),
            "supply"    to listOf(supply("S1", "P", "L", 100.0)),
            "overrides" to listOf(override("S1", listOf("D1" to 40.0))),
        )
        val result = runPlanning(data)
        val warnings = overrideWarnings(result)
        warnings shouldHaveSize 1
        warnings[0]["supply_id"] shouldBe "S1"
        @Suppress("UNCHECKED_CAST")
        val affected = warnings[0]["affected_demands"] as List<Map<String, Any?>>
        affected shouldHaveSize 1
        affected[0]["demand_id"] shouldBe "D1"
        (affected[0]["shortfall"] as Number).toDouble() shouldBe (60.0 plusOrMinus 1e-6)
        // D1 commits at most 40 via supply path
        committedFor(result, "D1") shouldBe (40.0 plusOrMinus 1e-6)
    }

    test("no warning when feasible: override does not cause shortfall") {
        val data = mapOf(
            "demand"    to listOf(demand("D1", "P", "L", 30.0), demand("D2", "P", "L", 20.0)),
            "supply"    to listOf(supply("S1", "P", "L", 100.0)),
            "overrides" to listOf(override("S1", listOf("D1" to 50.0, "D2" to 50.0))),
        )
        val result = runPlanning(data)
        overrideWarnings(result).shouldBeEmpty()
        committedFor(result, "D1") shouldBe (30.0 plusOrMinus 1e-6)
        committedFor(result, "D2") shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("override removal: no override restores auto allocation") {
        // Without override, demands consume from S1 in FIFO/priority order
        val data = mapOf(
            "demand" to listOf(demand("D1", "P", "L", 60.0, priority = 1), demand("D2", "P", "L", 40.0, priority = 2)),
            "supply" to listOf(supply("S1", "P", "L", 100.0)),
        )
        val result = runPlanning(data)
        overrideWarnings(result).shouldBeEmpty()
        committedFor(result, "D1") shouldBe (60.0 plusOrMinus 1e-6)
        committedFor(result, "D2") shouldBe (40.0 plusOrMinus 1e-6)
    }

    test("unknown demand in override is dropped, run proceeds") {
        val data = mapOf(
            "demand"    to listOf(demand("D1", "P", "L", 50.0)),
            "supply"    to listOf(supply("S1", "P", "L", 100.0)),
            "overrides" to listOf(override("S1", listOf("D1" to 50.0, "D_GHOST" to 30.0))),
        )
        val result = runPlanning(data)  // does not throw
        committedFor(result, "D1") shouldBe (50.0 plusOrMinus 1e-6)
    }

    test("supply cap invariant: total consumed ≤ supply.qty with override") {
        val data = mapOf(
            "demand"    to listOf(demand("D1", "P", "L", 100.0), demand("D2", "P", "L", 100.0)),
            "supply"    to listOf(supply("S1", "P", "L", 100.0)),
            "overrides" to listOf(override("S1", listOf("D1" to 40.0, "D2" to 40.0))),
        )
        val result = runPlanning(data)
        @Suppress("UNCHECKED_CAST")
        val violations = result["supply_cap_violations"] as? List<String> ?: emptyList()
        violations.shouldBeEmpty()
        val total = supplyAllocs(result).filter { it["supply_id"] == "S1" }
            .sumOf { (it["qty_consumed"] as Number).toDouble() }
        (total <= 100.0 + 1e-6) shouldBe true
    }
})
