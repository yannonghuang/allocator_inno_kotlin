package com.allocator

import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * A supply lot dated before the planning horizon is stock whose consumption is presumed already
 * folded into the case's WIP snapshot for that product — letting a new WO peg to it too
 * double-counts that material (see the live case-173-style repro: 283-0504-31 dated 7/15 pegged
 * under a WO for a demand whose horizon started 8/1). [runPlanning] excludes such lots from the
 * FIFO pool uniformly — including "wip"-dated rows: under the default/"auto" resolution a wip
 * row's date IS horizon start (see resolveWipSupplyDates), so it clears the floor with no special
 * treatment; an explicit per-lot override that resolves a wip row to an earlier date is excluded
 * exactly like any other stale lot, no exemption.
 */
class HorizonSupplyFloorTest : FunSpec({

    fun bom(bomId: String, parent: String, child: String) =
        mapOf<String, Any?>("bom_id" to bomId, "parent_id" to parent, "child_id" to child, "alt_group" to null, "rate" to 1.0)

    fun demandP(qty: Double) = mapOf<String, Any?>(
        "demand_id" to "D1", "product_id" to "P", "location_id" to "L",
        "quantity" to qty, "request_due_time" to "2026-06-15",
    )

    fun supply(pid: String, qty: Double, sid: String, date: String?) = mapOf<String, Any?>(
        "product_id" to pid, "location_id" to "L", "qty" to qty, "supply_id" to sid, "supply_date" to date,
    )

    fun mkData(supplies: List<Map<String, Any?>>) = mapOf(
        "method_make" to listOf(mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
        "method_buy" to emptyList<Map<String, Any?>>(),
        "method_move" to emptyList<Map<String, Any?>>(),
        "bom" to listOf(bom("B", "P", "C")),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "demand" to listOf(demandP(20.0)),
        "supply" to supplies,
        "overrides" to emptyList<Map<String, Any?>>(),
    )

    val horizonConfig = mapOf<String, Any?>(
        "purchase_allowed" to false,
        "method_selection" to mapOf("horizon_start" to "2026-06-01"),
    )

    @Suppress("UNCHECKED_CAST")
    fun committedQty(result: com.allocator.services.RunPlanningResult): Double =
        (result.output["committed_demands"] as List<Map<String, Any?>>)
            .filter { it["demand_id"] == "D1" && it["commit_reason"] != "no_methods" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

    test("a component lot dated before the horizon is excluded from the FIFO pool") {
        val data = mkData(listOf(supply("C", 100.0, "S_C", "2026-05-01")))  // before the 2026-06-01 horizon
        val result = runPlanning(data, horizonConfig)
        committedQty(result) shouldBe (0.0 plusOrMinus 1e-6)
    }

    test("a component lot dated at/after the horizon is unaffected (baseline)") {
        val data = mkData(listOf(supply("C", 100.0, "S_C", "2026-06-01")))  // exactly at the horizon
        val result = runPlanning(data, horizonConfig)
        committedQty(result) shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("a blank/undated component lot is unaffected (existing always-available convention)") {
        val data = mkData(listOf(supply("C", 100.0, "S_C", null)))
        val result = runPlanning(data, horizonConfig)
        committedQty(result) shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("a WIP lot with no override defaults to horizon start and clears the floor (no special-casing needed)") {
        // C's own supply is the literal "wip" marker with no per-lot override — resolveWipSupplyDates'
        // "auto" default resolves it to horizon start itself, so it naturally passes the same
        // `!d.isBefore(horizonStart)` check as any ordinary on/after-horizon lot.
        val data = mkData(listOf(supply("C", 100.0, "S_C_WIP", "wip")))
        val result = runPlanning(data, horizonConfig)
        committedQty(result) shouldBe (20.0 plusOrMinus 1e-6)
    }

    test("a WIP lot explicitly overridden to a date before the horizon is excluded, same as any stale lot") {
        val data = mkData(listOf(supply("C", 100.0, "S_C_WIP", "wip")))
        val config = horizonConfig + mapOf(
            "app_specific_config" to mapOf("wip_supply_dates" to mapOf("S_C_WIP" to "2026-05-01")),
        )
        val result = runPlanning(data, config)
        committedQty(result) shouldBe (0.0 plusOrMinus 1e-6)
    }
})
