package com.allocator

import com.allocator.services.buildDemandOrder
import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Behavior contract for the Demand Ordering build engine (services/DemandOrderBuilder.kt) and
 * its integration into [runPlanning]'s demand-processing sort.
 */
class DemandOrderBuilderTest : FunSpec({

    fun demand(id: String, dueTime: String? = null, priority: Int? = null) = mapOf<String, Any?>(
        "demand_id" to id, "product_id" to "FG", "location_id" to "L",
        "quantity" to 10.0, "request_due_time" to dueTime, "priority" to priority,
    )

    // ── buildDemandOrder unit tests ──────────────────────────────────────────

    test("earliest request_due_time sorts first") {
        val data = mapOf("demand" to listOf(
            demand("D1", dueTime = "2024-03-01"),
            demand("D2", dueTime = "2024-01-01"),
            demand("D3", dueTime = "2024-02-01"),
        ))
        val rows = buildDemandOrder(data).sortedBy { it.order }
        rows.map { it.demandId } shouldBe listOf("D2", "D3", "D1")
        rows.map { it.order } shouldBe listOf(10, 20, 30)
    }

    test("same due_time: lower priority number sorts first (tie-break)") {
        val data = mapOf("demand" to listOf(
            demand("D1", dueTime = "2024-01-01", priority = 5),
            demand("D2", dueTime = "2024-01-01", priority = 1),
        ))
        val rows = buildDemandOrder(data).sortedBy { it.order }
        rows.map { it.demandId } shouldBe listOf("D2", "D1")
    }

    test("same due_time and priority: demand_id sorts as final tie-break") {
        val data = mapOf("demand" to listOf(
            demand("DB", dueTime = "2024-01-01", priority = 1),
            demand("DA", dueTime = "2024-01-01", priority = 1),
        ))
        val rows = buildDemandOrder(data).sortedBy { it.order }
        rows.map { it.demandId } shouldBe listOf("DA", "DB")
    }

    test("null/unparseable due_time sorts last") {
        val data = mapOf("demand" to listOf(
            demand("D1", dueTime = null),
            demand("D2", dueTime = "2024-01-01"),
            demand("D3", dueTime = "not-a-date"),
        ))
        val rows = buildDemandOrder(data).sortedBy { it.order }
        rows.map { it.demandId }.first() shouldBe "D2"
        rows.map { it.demandId }.toSet() shouldBe setOf("D1", "D2", "D3")
        // D1 and D3 (both no usable due date) trail behind D2, order between them per priority/id
    }

    // ── Planning integration: KB order override + per-demand fallback ───────

    fun supply(pid: String, lid: String, qty: Double) = mapOf<String, Any?>(
        "product_id" to pid, "location_id" to lid, "qty" to qty, "supply_id" to "S_$pid",
    )

    // An empty/absent purchasable_materials config makes buildReachabilityMatrix treat EVERY
    // reachable supply node as "critical" (unfiltered — see SupplyDemandMatrix.kt's
    // buildReachabilityMatrix: `criticalPids == null` short-circuits its filter to true for
    // everything), which would proportionally pre-split FG's own direct supply across demands
    // regardless of processing order — defeating the point of this test. An explicit whitelist
    // (that doesn't include FG, which isn't raw-buyable anyway) makes criticalPids a real,
    // restricted set that excludes FG's supply node, so it stays uncapped and genuinely
    // first-come-first-served in the live commit loop — the actual order-dependent path this
    // feature targets.
    val nonCriticalConfig = mapOf("purchase_allowed" to true, "purchasable_materials" to listOf("__dummy__"))

    // "no_methods" rows record the UNFULFILLED remainder (a diagnostic placeholder — these test
    // fixtures give FG no methods at all, so any shortfall past on-hand supply surfaces as its
    // own committed_demands row tagged "no_methods" rather than simply being absent) — exclude
    // it so this sums only what was genuinely fulfilled from inventory.
    @Suppress("UNCHECKED_CAST")
    fun committedQty(output: Map<String, Any>, demandId: String): Double =
        (output["committed_demands"] as List<Map<String, Any?>>)
            .filter { it["demand_id"] == demandId && it["commit_reason"] != "no_methods" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

    // Two demands for the SAME finished good's own on-hand supply — no methods at all, so this
    // exercises the live FCFS inventory-consumption path directly (no BOM/critical-matrix
    // involvement, per this session's investigation into where demand order actually matters).
    fun scarceSupplyData() = mapOf(
        "method_make" to emptyList<Map<String, Any?>>(),
        "method_buy" to emptyList<Map<String, Any?>>(),
        "method_move" to emptyList<Map<String, Any?>>(),
        "bom" to emptyList<Map<String, Any?>>(),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "supply" to listOf(supply("FG", "L", 50.0)),
        "demand" to listOf(
            demand("D1", dueTime = "2024-06-01"),
            demand("D2", dueTime = "2024-06-01"),
        ).map { it + ("quantity" to 30.0) },
    )

    test("no Demand Ordering KB: default (priority, demand_id) order — D1 wins the scarce supply") {
        val result = runPlanning(scarceSupplyData(), config = nonCriticalConfig, demandOrder = null)
        committedQty(result.output, "D1") shouldBe (30.0 plusOrMinus 1e-6)
        committedQty(result.output, "D2") shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("Demand Ordering KB flips the winner: D2 first now wins the scarce supply") {
        val demandOrder = mapOf("D2" to 10, "D1" to 20)
        val result = runPlanning(scarceSupplyData(), config = nonCriticalConfig, demandOrder = demandOrder)
        committedQty(result.output, "D2") shouldBe (30.0 plusOrMinus 1e-6)
        committedQty(result.output, "D1") shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("partial KB coverage: covered demand always wins; uncovered demands fall back to (priority, demand_id) and sort after") {
        // D1, D2, D3 all compete for 50 units, each needing 30. Only D3 has a KB entry (forced
        // first). D1/D2 are uncovered -> fall back to (priority, demand_id): D1 before D2.
        val data = mapOf(
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to emptyList<Map<String, Any?>>(),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("FG", "L", 50.0)),
            "demand" to listOf(
                demand("D1", dueTime = "2024-06-01") + ("quantity" to 30.0),
                demand("D2", dueTime = "2024-06-01") + ("quantity" to 30.0),
                demand("D3", dueTime = "2024-06-01") + ("quantity" to 30.0),
            ),
        )
        val demandOrder = mapOf("D3" to 10)
        val result = runPlanning(data, config = nonCriticalConfig, demandOrder = demandOrder)
        committedQty(result.output, "D3") shouldBe (30.0 plusOrMinus 1e-6)
        committedQty(result.output, "D1") shouldBe (20.0 plusOrMinus 1e-6)
        committedQty(result.output, "D2") shouldBe (0.0 plusOrMinus 1e-6)
    }
})
