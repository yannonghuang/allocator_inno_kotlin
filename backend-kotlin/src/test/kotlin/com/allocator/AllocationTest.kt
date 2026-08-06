package com.allocator

import com.allocator.api.CaseAllocRow
import com.allocator.api.partitionEditableAllocationRows
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [partitionEditableAllocationRows] is the pure, DB-free half of the critical-stock read-only-row
 * enforcement wired into `Allocation.kt`'s `PUT`/`import`/`versions` routes (see
 * `recomputeCriticalStockAllocationRows`'s own doc for the DB-coupled other half, exercised live
 * against a running case in this branch's own validation rather than here — this file, like the
 * rest of `Allocation.kt`, has no existing DB-backed test harness to build on).
 */
class AllocationTest : FunSpec({

    // ── helpers (mirrors SupplyGuidedPlanningTest's own) ────────────────────────

    fun supply(pid: String, lid: String, qty: Double, sid: String, target: String? = null): Map<String, Any?> =
        mapOf("product_id" to pid, "location_id" to lid, "qty" to qty, "supply_id" to sid, "supply_date" to "2024-01-01", "target" to target)

    fun mkData(
        supplies: List<Map<String, Any?>> = emptyList(),
        methodMake: List<Map<String, Any?>> = emptyList(),
        methodBuy: List<Map<String, Any?>> = emptyList(),
        bom: List<Map<String, Any?>> = emptyList(),
        demands: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "supply" to supplies,
        "method_make" to methodMake,
        "method_buy" to methodBuy,
        "method_move" to emptyList(),
        "bom" to bom,
        "demand" to demands,
        "overrides" to emptyList(),
        "productlocation" to emptyList(),
    )

    val noPurchaseConfig = mapOf<String, Any?>("purchase_allowed" to false)

    // Shared node M (no target of its own) fed by two top products, both routing through raw
    // material X (TARGETed Q6J/Q6K) — same shape as cases/inno2026_2's wip_280-1001.
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

    test("partitionEditableAllocationRows: a case with no critical stock leaves every row editable") {
        val xLots = listOf(supply("X", "L", 100.0, "LOT_X"))   // untargeted -> no critical stock at all
        val data = mkData(
            supplies = xLots,
            methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
        )
        val rows = listOf(CaseAllocRow("LOT_X", "D1", 10.0))
        val (editable, rejected) = partitionEditableAllocationRows(rows, data, noPurchaseConfig)

        editable shouldBe rows
        rejected shouldBe emptyList()
    }

    test("partitionEditableAllocationRows: rejects a submitted row for a critical-stock supply_id, keeps the rest") {
        val xLots = listOf(
            supply("X", "L", 90.0, "LOT_A", target = "CUST_A"),
            supply("X", "L", 10.0, "LOT_B", target = "CUST_B"),
        )
        val data = sharedStockData(50.0, xLots)
        val rows = listOf(
            CaseAllocRow("LOT_M", "DA", 999.0),      // M is critical stock -> must be rejected
            CaseAllocRow("LOT_A", "DA", 20.0),        // the raw material itself -> stays editable
        )
        val (editable, rejected) = partitionEditableAllocationRows(rows, data, noPurchaseConfig)

        editable shouldBe listOf(CaseAllocRow("LOT_A", "DA", 20.0))
        rejected shouldBe listOf("LOT_M")
    }

    test("partitionEditableAllocationRows: rejected list is de-duplicated across multiple rows for the same supply_id") {
        val xLots = listOf(
            supply("X", "L", 90.0, "LOT_A", target = "CUST_A"),
            supply("X", "L", 10.0, "LOT_B", target = "CUST_B"),
        )
        val data = sharedStockData(50.0, xLots)
        val rows = listOf(
            CaseAllocRow("LOT_M", "DA", 5.0),
            CaseAllocRow("LOT_M", "DB", 7.0),
        )
        val (editable, rejected) = partitionEditableAllocationRows(rows, data, noPurchaseConfig)

        editable shouldBe emptyList()
        rejected shouldBe listOf("LOT_M")
    }

    test("partitionEditableAllocationRows: a critical stock with its OWN explicit target is still an editable raw-material-style row (never classified as needing a derived split, but its case_allocation rows are still critical-stock-derived and rejected)") {
        // Same wip_280-0001 shape: M2 has its OWN target, so expandCriticalStockSupplies never
        // fractures it — but it's still a critical-stock POSITION (computeCriticalStockPositions
        // still returns it), so its case_allocation rows are still derived/read-only, same as any
        // other critical stock. Only the SPLIT step treats it differently, not the write-path gate.
        val xLots = listOf(
            supply("X", "L", 90.0, "LOT_A", target = "CUST_A"),
            supply("X", "L", 10.0, "LOT_B", target = "CUST_B"),
        )
        val data = mkData(
            supplies = xLots + listOf(supply("M2", "L", 50.0, "LOT_M2", target = "CUST_A")),
            methodMake = listOf(mapOf("bom_id" to "BM2", "product_id" to "M2", "location_id" to "L", "preference" to 1)),
            methodBuy = listOf(mapOf("product_id" to "X", "location_id" to "L", "preference" to 1)),
            bom = listOf(mapOf("bom_id" to "BM2", "parent_id" to "M2", "child_id" to "X", "rate" to 1.0, "alt_group" to null)),
        )
        val rows = listOf(CaseAllocRow("LOT_M2", "DA", 999.0))
        val (editable, rejected) = partitionEditableAllocationRows(rows, data, noPurchaseConfig)

        editable shouldBe emptyList()
        rejected shouldBe listOf("LOT_M2")
    }
})
