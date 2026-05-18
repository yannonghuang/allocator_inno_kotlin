package com.allocator

import com.allocator.services.OperationLookup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Locks in the UPH-override applicability gate and the effective-lead formula.
 *
 * Dataset mirrors csv/operation.csv + csv/bor.csv + csv/resource.csv as of
 * commit db6f686:
 *   operation: oe-operation @ PROD_AREA=OE, UPH=100, yield=0.98,
 *              pre/process/post = 1800/3600/1000 seconds
 *   bor:       oe-bor → [oe-machine1, oe-machine2, crew]
 *   resource:  oe-machine1@1000=10, oe-machine2@1000=15, crew@1000=100
 *              (location 2000 also stocked; OB row left in for noise)
 *
 * Formula (per-unit process_time interpretation):
 *   seconds = pre + qty/yield*process_time + (qty/yield/UPH)*3600 + post
 *   days = seconds / 86400
 */
class OperationLookupTest : FunSpec({

    fun fixtureDataset(
        withProductLocation: Boolean = true,
        withOperation: Boolean = true,
        withBor: Boolean = true,
        withCrewAt1000: Boolean = true,
    ): Map<String, List<Map<String, Any?>>> {
        val productLocation = if (withProductLocation) listOf(
            mapOf("product_id" to "X", "location_id" to "1000", "prod_area" to "OE", "max_lot_size" to null),
            mapOf("product_id" to "Y", "location_id" to "1000", "prod_area" to "FA", "max_lot_size" to null),
        ) else emptyList()

        val operation = if (withOperation) listOf(
            mapOf(
                "operation_id" to "oe-operation",
                "prod_area" to "OE",
                "uph" to 100.0,
                "yield_factor" to 0.98,
                "bor_id" to "oe-bor",
                "process_time" to 3600,
                "pre_process_time" to 1800,
                "post_process_time" to 1000,
            ),
        ) else emptyList()

        val bor = if (withBor) listOf(
            mapOf("bor_id" to "oe-bor", "resource_id" to "oe-machine1", "resource_rate" to 1.0),
            mapOf("bor_id" to "oe-bor", "resource_id" to "oe-machine2", "resource_rate" to 2.0),
            mapOf("bor_id" to "oe-bor", "resource_id" to "crew", "resource_rate" to 20.0),
        ) else emptyList()

        val resource = buildList {
            add(mapOf("resource_id" to "oe-machine1", "location_id" to "1000", "size" to 10.0))
            add(mapOf("resource_id" to "oe-machine2", "location_id" to "1000", "size" to 15.0))
            if (withCrewAt1000) add(mapOf("resource_id" to "crew", "location_id" to "1000", "size" to 100.0))
            // location 2000 always present so we can verify the gate is per-location.
            add(mapOf("resource_id" to "oe-machine1", "location_id" to "2000", "size" to 10.0))
            add(mapOf("resource_id" to "oe-machine2", "location_id" to "2000", "size" to 5.0))
            add(mapOf("resource_id" to "crew", "location_id" to "2000", "size" to 200.0))
        }

        return mapOf(
            "productlocation" to productLocation,
            "operation" to operation,
            "bor" to bor,
            "resource" to resource,
        )
    }

    test("UPH override applies when product/location → prod_area → operation → BOR → resources all match") {
        val data = fixtureDataset()
        val result = OperationLookup.effectiveLeadDays(
            productId = "X",
            locationId = "1000",
            qty = 100.0,
            methodMakeLeadDays = 5.0,
            data = data,
        )

        // UPH branch (UPH > 0 → ignore pre/process/post):
        //   seconds_per_lot = (lot_qty / yield / UPH) * 3600
        //                   = (100 / 0.98 / 100) * 3600
        //                   = 3673.469 seconds ≈ 0.0425 days
        // The planner schedules in whole-day buckets, so we ceil to 1 day.
        result.source shouldBe "uph"
        result.operationId shouldBe "oe-operation"
        result.days shouldBe 1.0
    }

    test("large lot under UPH spans multiple whole days") {
        // qty 10,000 / 0.98 / 100 UPH * 3600 = 367,346.94 s ≈ 4.25 days
        // → ceil = 5 days per lot.
        val data = fixtureDataset()
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 10000.0, methodMakeLeadDays = 5.0, data = data)
        result.source shouldBe "uph"
        result.days shouldBe 5.0
    }

    test("UPH branch caps qty at productlocation.max_lot_size") {
        // Override the fixtures so X@1000 has max_lot_size = 50; a slot qty of 200
        // becomes a 50-piece lot.
        val base = fixtureDataset()
        val withCap = base.toMutableMap()
        withCap["productlocation"] = listOf(
            mapOf("product_id" to "X", "location_id" to "1000", "prod_area" to "OE", "max_lot_size" to 50.0),
            mapOf("product_id" to "Y", "location_id" to "1000", "prod_area" to "FA", "max_lot_size" to null),
        )
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 200.0, methodMakeLeadDays = 5.0, data = withCap)
        // lot_qty = min(200, 50) = 50; (50 / 0.98 / 100) * 3600 ≈ 0.021 days → ceil = 1.
        result.source shouldBe "uph"
        result.days shouldBe 1.0
    }

    test("fixed pre+process+post branch kicks in when UPH is 0 or missing") {
        // Drop UPH (set to 0) so the fixed-time branch fires. Pre+process+post
        // refer to the lot as a whole — yield/qty don't apply.
        val base = fixtureDataset()
        val withoutUph = base.toMutableMap()
        withoutUph["operation"] = listOf(
            mapOf(
                "operation_id" to "oe-operation",
                "prod_area" to "OE",
                "uph" to 0.0,
                "yield_factor" to 0.98,
                "bor_id" to "oe-bor",
                "process_time" to 3600,
                "pre_process_time" to 1800,
                "post_process_time" to 1000,
            ),
        )
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = withoutUph)
        // seconds_per_lot = 1800 + 3600 + 1000 = 6400; days ≈ 0.074 → ceil = 1.
        result.source shouldBe "uph"
        result.days shouldBe 1.0
    }

    test("falls back when productlocation has no row for (product, location)") {
        val data = fixtureDataset()
        val result = OperationLookup.effectiveLeadDays(
            productId = "Z",  // not in productlocation
            locationId = "1000",
            qty = 100.0,
            methodMakeLeadDays = 5.0,
            data = data,
        )
        result.source shouldBe "method_make"
        result.days shouldBe 5.0
        result.operationId shouldBe null
    }

    test("falls back when prod_area exists but no operation covers it") {
        val data = fixtureDataset()
        // Product Y has prod_area=FA, but the only operation is OE.
        val result = OperationLookup.effectiveLeadDays("Y", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = data)
        result.source shouldBe "method_make"
        result.days shouldBe 5.0
    }

    test("falls back when a required BOR resource is missing at the WO's location") {
        // Crew not present at location 1000 (machines still are).
        val data = fixtureDataset(withCrewAt1000 = false)
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = data)
        result.source shouldBe "method_make"
        result.days shouldBe 5.0
        result.operationId shouldBe null
    }

    test("falls back when BOR table has no rows for the operation's bor_id") {
        val data = fixtureDataset(withBor = false)
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = data)
        result.source shouldBe "method_make"
        result.days shouldBe 5.0
    }

    test("falls back when operation table is empty") {
        val data = fixtureDataset(withOperation = false)
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = data)
        result.source shouldBe "method_make"
        result.days shouldBe 5.0
    }

    test("falls back when productlocation table is empty") {
        val data = fixtureDataset(withProductLocation = false)
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = data)
        result.source shouldBe "method_make"
        result.days shouldBe 5.0
    }

    test("non-positive qty falls back to method_make.lead_time") {
        val data = fixtureDataset()
        val zero = OperationLookup.effectiveLeadDays("X", "1000", qty = 0.0, methodMakeLeadDays = 5.0, data = data)
        zero.source shouldBe "method_make"
        zero.days shouldBe 5.0
        val negative = OperationLookup.effectiveLeadDays("X", "1000", qty = -5.0, methodMakeLeadDays = 5.0, data = data)
        negative.source shouldBe "method_make"
    }

    test("override is per-location: same product at location 2000 with full resources also applies") {
        // OB-style: change productlocation to put X at 2000 too, then run with locationId=2000.
        val base = fixtureDataset()
        val withX2000 = base.toMutableMap()
        withX2000["productlocation"] = (base["productlocation"] ?: emptyList()) + mapOf(
            "product_id" to "X", "location_id" to "2000", "prod_area" to "OE", "max_lot_size" to null,
        )
        val result = OperationLookup.effectiveLeadDays("X", "2000", qty = 100.0, methodMakeLeadDays = 5.0, data = withX2000)
        result.source shouldBe "uph"
        // Same formula as the 1000 test — resources at 2000 also cover all of oe-bor.
        result.days shouldBe 1.0
    }

    // ── parallelismCap ────────────────────────────────────────────────────────

    test("parallelismCap returns min(floor(size/rate)) across BOR resources") {
        // Default fixture: rates 1/2/20, location 1000 sizes 10/15/100.
        // floors: 10, 7, 5 → min = 5.
        val data = fixtureDataset()
        OperationLookup.parallelismCap("X", "1000", data) shouldBe 5
    }

    test("parallelismCap follows the tightest resource at the location") {
        // Override resources at 1000 so oe-machine1 is the bottleneck.
        val base = fixtureDataset()
        val tighter = base.toMutableMap()
        tighter["resource"] = listOf(
            mapOf("resource_id" to "oe-machine1", "location_id" to "1000", "size" to 2.0),
            mapOf("resource_id" to "oe-machine2", "location_id" to "1000", "size" to 15.0),
            mapOf("resource_id" to "crew", "location_id" to "1000", "size" to 100.0),
        )
        // floors: 2, 7, 5 → min = 2 (oe-machine1 is the limiter).
        OperationLookup.parallelismCap("X", "1000", tighter) shouldBe 2
    }

    test("parallelismCap returns 0 when any rate exceeds size") {
        val base = fixtureDataset()
        val starved = base.toMutableMap()
        starved["resource"] = listOf(
            // oe-machine1 with size=0 can't fit even one lot (rate=1).
            mapOf("resource_id" to "oe-machine1", "location_id" to "1000", "size" to 0.0),
            mapOf("resource_id" to "oe-machine2", "location_id" to "1000", "size" to 15.0),
            mapOf("resource_id" to "crew", "location_id" to "1000", "size" to 100.0),
        )
        OperationLookup.parallelismCap("X", "1000", starved) shouldBe 0
    }

    test("parallelismCap returns 0 when override doesn't apply at all") {
        val data = fixtureDataset()
        // Product Z has no productlocation row.
        OperationLookup.parallelismCap("Z", "1000", data) shouldBe 0
        // Product Y has prod_area FA but no operation matches FA.
        OperationLookup.parallelismCap("Y", "1000", data) shouldBe 0
    }

    test("effectiveLeadDays falls back when any BOR resource has rate > size") {
        // Same starved fixture as the parallelismCap rate>size test —
        // effectiveLeadDays should also bail since cap would be 0.
        val base = fixtureDataset()
        val starved = base.toMutableMap()
        starved["resource"] = listOf(
            mapOf("resource_id" to "oe-machine1", "location_id" to "1000", "size" to 0.0),
            mapOf("resource_id" to "oe-machine2", "location_id" to "1000", "size" to 15.0),
            mapOf("resource_id" to "crew", "location_id" to "1000", "size" to 100.0),
        )
        val result = OperationLookup.effectiveLeadDays("X", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = starved)
        result.source shouldBe "method_make"
        result.days shouldBe 5.0
    }
})
