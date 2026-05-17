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

        // qty/yield = 100/0.98 = 102.04081632653...
        // seconds = 1800 + 102.04 * 3600 + (102.04/100) * 3600 + 1000
        //         = 1800 + 367346.939 + 3673.469 + 1000
        //         = 373820.408 seconds
        // days   ≈ 4.3266
        result.source shouldBe "uph"
        result.operationId shouldBe "oe-operation"
        result.days shouldBe (4.32662 plusOrMinus 1e-4)
    }

    test("override scales with qty (linear in adjusted qty)") {
        val data = fixtureDataset()
        val small = OperationLookup.effectiveLeadDays("X", "1000", qty = 10.0, methodMakeLeadDays = 5.0, data = data)
        val big = OperationLookup.effectiveLeadDays("X", "1000", qty = 100.0, methodMakeLeadDays = 5.0, data = data)
        small.source shouldBe "uph"
        big.source shouldBe "uph"
        // Linear part dominates pre/post → big should be ~10× the linear component
        // larger than small (not exactly 10× because of the fixed pre/post overhead).
        (big.days > small.days * 5) shouldBe true
        (big.days < small.days * 11) shouldBe true
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
        result.days shouldBe (4.32662 plusOrMinus 1e-4)
    }
})
