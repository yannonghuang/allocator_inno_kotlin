package com.allocator

import com.allocator.api.CaseAllocRow
import com.allocator.api.buildTsaOverridesFromCaseAlloc
import com.allocator.api.caseAllocMaterialSet
import com.allocator.api.computeAllocationPreview
import com.allocator.api.filterOutCriticalStockRows
import com.allocator.services.TsaOverride
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Pure, DB-free helpers in `Allocation.kt` that turn Targeted Supply Allocation (TSA) input rows
 * — `case_allocation`'s repurposed shape, one row per critical-material lot's `qty_cap`/`target`
 * override — into the pieces planning actually consumes: an override map for
 * [com.allocator.services.buildSupplyAllocation], and the read-only recomputed
 * (supply_lot, demand) -> qty_allocated preview grid.
 */
class AllocationTest : FunSpec({

    fun supply(pid: String, lid: String, qty: Double, sid: String, target: String? = null): Map<String, Any?> =
        mapOf("product_id" to pid, "location_id" to lid, "qty" to qty, "supply_id" to sid, "supply_date" to "2024-01-01", "target" to target)

    fun mkData(
        supplies: List<Map<String, Any?>> = emptyList(),
        methodMake: List<Map<String, Any?>> = emptyList(),
        methodBuy: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
        demands: List<Map<String, Any?>> = emptyList(),
        productlocation: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "supply" to supplies,
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to emptyList(),
        "bom" to bom,
        "demand" to demands,
        "overrides" to emptyList(),
        "productlocation" to productlocation,
    )

    val noPurchaseConfig = mapOf<String, Any?>("purchase_allowed" to false)

    test("buildTsaOverridesFromCaseAlloc: maps rows to overrides keyed by supply_id") {
        val rows = listOf(
            CaseAllocRow("LOT_A", qtyCap = 5.0, target = "CUST_A"),
            CaseAllocRow("LOT_B", qtyCap = null, target = null),
        )
        buildTsaOverridesFromCaseAlloc(rows) shouldBe mapOf(
            "LOT_A" to TsaOverride(qtyCap = 5.0, target = "CUST_A"),
            "LOT_B" to TsaOverride(qtyCap = null, target = null),
        )
    }

    test("caseAllocMaterialSet: resolves stored rows' supply_ids back to their product_ids") {
        val supplies = listOf(supply("X", "L", 30.0, "LOT_X"), supply("Y", "L", 10.0, "LOT_Y"))
        val rows = listOf(CaseAllocRow("LOT_X", qtyCap = 20.0, target = null))
        caseAllocMaterialSet(rows, supplies) shouldBe setOf("X")
    }

    test("computeAllocationPreview: qtyCap override caps the recomputed per-lot budget") {
        val lots = listOf(supply("X", "L", 30.0, "LOT_X", target = "CUST_A"))
        val demands = listOf(
            mapOf<String, Any?>("demand_id" to "DA", "product_id" to "P", "location_id" to "L", "quantity" to 20.0, "request_due_time" to "2024-06-01", "customer_id" to "CUST_A"),
        )
        val data = mkData(
            supplies = lots,
            methodMake = listOf(mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
            methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
            bom = listOf(mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "X", "rate" to 1.0, "alt_group" to null)),
            demands = demands,
            productlocation = listOf(mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw")),
        )
        val preview = computeAllocationPreview(data, noPurchaseConfig, mapOf("LOT_X" to TsaOverride(qtyCap = 5.0)))

        preview.single { it.demandId == "DA" }.qtyAllocated shouldBe (5.0 plusOrMinus 1e-6)
    }

    test("computeAllocationPreview: no overrides reproduces the plain buildSupplyAllocation result") {
        val lots = listOf(supply("X", "L", 30.0, "LOT_X", target = "CUST_A"))
        val demands = listOf(
            mapOf<String, Any?>("demand_id" to "DA", "product_id" to "P", "location_id" to "L", "quantity" to 20.0, "request_due_time" to "2024-06-01", "customer_id" to "CUST_A"),
        )
        val data = mkData(
            supplies = lots,
            methodMake = listOf(mapOf("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
            methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
            bom = listOf(mapOf("bom_id" to "BP", "parent_id" to "P", "child_id" to "X", "rate" to 1.0, "alt_group" to null)),
            demands = demands,
            productlocation = listOf(mapOf("product_id" to "X", "location_id" to "L", "prod_area" to "raw")),
        )
        val preview = computeAllocationPreview(data, noPurchaseConfig, emptyMap())

        preview.single { it.demandId == "DA" }.qtyAllocated shouldBe (20.0 plusOrMinus 1e-6)
    }

    // ── filterOutCriticalStockRows: TSA only ever covers genuinely RAW critical materials ───────
    // Critical STOCK (on-hand inventory of an otherwise-elastic, non-raw product that merely
    // INHERITS targeting from its mandatory raw material) must never get its own TSA row — its
    // effective targeting has to stay entirely DERIVED, computed inside buildSupplyAllocation.

    // Shared node M (no target of its own) fed by two top products, both routing through raw
    // material X (TARGETed) — same shape as cases/inno2026_2's wip_280-1001.
    fun sharedStockData(mStockQty: Double, xLots: List<Map<String, Any?>>) = mkData(
        supplies = xLots + listOf(supply("M", "L", mStockQty, "LOT_M")),
        methodMake = listOf(
            mapOf("bom_id" to "BM", "product_id" to "M", "location_id" to "L", "preference" to 1),
            mapOf("bom_id" to "BP1", "product_id" to "P1", "location_id" to "L", "preference" to 1),
            mapOf("bom_id" to "BP2", "product_id" to "P2", "location_id" to "L", "preference" to 1),
        ),
        methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
        bom = listOf(
            mapOf("bom_id" to "BM", "parent_id" to "M", "child_id" to "X", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP1", "parent_id" to "P1", "child_id" to "M", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BP2", "parent_id" to "P2", "child_id" to "M", "rate" to 1.0, "alt_group" to null),
        ),
    )

    test("filterOutCriticalStockRows: a case with no critical stock leaves every row editable") {
        val xLots = listOf(supply("X", "L", 100.0, "LOT_X"))   // untargeted -> no critical stock at all
        val data = mkData(
            supplies = xLots,
            methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
        )
        val rows = listOf(CaseAllocRow("LOT_X", qtyCap = 10.0, target = null))
        val (editable, rejected) = filterOutCriticalStockRows(rows, data, noPurchaseConfig)

        editable shouldBe rows
        rejected shouldBe emptyList()
    }

    test("filterOutCriticalStockRows: rejects a submitted row for a critical-stock supply_id, keeps the rest") {
        val xLots = listOf(
            supply("X", "L", 90.0, "LOT_A", target = "CUST_A"),
            supply("X", "L", 10.0, "LOT_B", target = "CUST_B"),
        )
        val data = sharedStockData(50.0, xLots)
        val rows = listOf(
            CaseAllocRow("LOT_M", qtyCap = 999.0, target = null),   // M is critical stock -> must be rejected
            CaseAllocRow("LOT_A", qtyCap = 20.0, target = "CUST_A"), // the raw material itself -> stays editable
        )
        val (editable, rejected) = filterOutCriticalStockRows(rows, data, noPurchaseConfig)

        editable shouldBe listOf(CaseAllocRow("LOT_A", qtyCap = 20.0, target = "CUST_A"))
        rejected shouldBe listOf("LOT_M")
    }

    test("filterOutCriticalStockRows: rejected list is de-duplicated") {
        val xLots = listOf(
            supply("X", "L", 90.0, "LOT_A", target = "CUST_A"),
            supply("X", "L", 10.0, "LOT_B", target = "CUST_B"),
        )
        val data = sharedStockData(50.0, xLots)
        // Two separate submitted edits for the same critical-stock lot (e.g. qty then target).
        val rows = listOf(
            CaseAllocRow("LOT_M", qtyCap = 5.0, target = null),
            CaseAllocRow("LOT_M", qtyCap = 7.0, target = null),
        )
        val (editable, rejected) = filterOutCriticalStockRows(rows, data, noPurchaseConfig)

        editable shouldBe emptyList()
        rejected shouldBe listOf("LOT_M")
    }
})
