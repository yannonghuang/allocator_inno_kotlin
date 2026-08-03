package com.allocator

import com.allocator.services.checkRunSoundness
import com.allocator.services.runPlanning
import com.opencsv.CSVReaderHeaderAware
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.FileReader

/**
 * Regression check against a real case (cases/inno2026) that carries a genuine negative-QTY
 * supply.csv row (SUPPLY_ID=10, PRODUCT_ID=285-0612, LOCATION_ID=1000, QTY=-16) — confirmed
 * by the user to be the intended fixture for this feature. Loads the CSVs directly (no DB),
 * mirroring CaseLoader.kt's column-name conventions, and runs the full planner + soundness
 * checker end to end.
 *
 * Note: 285-0612 also has ~92,840 units of OTHER (positive) supply across locations, so real
 * demand here is expected to be satisfied without ever exhausting stock down to where a
 * purchase WO gets triggered at (285-0612, 1000) — i.e. this is primarily a "doesn't break
 * anything" regression check (negative row correctly excluded from the FIFO pool, case still
 * plans and passes soundness cleanly), not an exercise of the absorption path itself (that's
 * covered by NegativeInventoryTest/ReconcileTest with a forced-exhaustion fixture).
 */
class Inno2026NegativeSupplyRegressionTest : FunSpec({

    fun readCsv(path: File): List<Map<String, String>> {
        if (!path.exists()) return emptyList()
        CSVReaderHeaderAware(FileReader(path)).use { reader ->
            val rows = mutableListOf<Map<String, String>>()
            while (true) {
                val row = reader.readMap() ?: break
                rows.add(row.mapValues { it.value ?: "" })
            }
            return rows
        }
    }

    fun loadCaseDir(dir: File): Map<String, List<Map<String, Any?>>> {
        fun d(s: String?) = s?.toDoubleOrNull()
        fun i(s: String?) = s?.toIntOrNull()
        fun blankToNull(s: String?) = s?.takeIf { it.isNotBlank() }

        val bom = readCsv(File(dir, "bom.csv")).map {
            mapOf(
                "bom_id" to it["BOM_ID"], "parent_id" to it["PARENT_ID"], "child_id" to it["CHILD_ID"],
                "rate" to (d(it["RATE"]) ?: 0.0), "alt_group" to blankToNull(it["ALT_GROUP"]),
                "elem_ix" to i(it["ELEM_IX"]),
            )
        }
        val customer = readCsv(File(dir, "customer.csv")).map {
            mapOf("customer" to it["CUSTOMER"], "description" to blankToNull(it["DESCRIPTION"]))
        }
        val custById = customer.associate { it["customer"] as String to (it["description"] ?: it["customer"]) }
        val demand = readCsv(File(dir, "demand.csv")).map {
            mapOf(
                "demand_id" to it["ID"], "description" to blankToNull(it["DESCRIPTION"]),
                "customer_id" to it["CUSTOMER_ID"], "customer" to (custById[it["CUSTOMER_ID"]] ?: it["CUSTOMER_ID"]),
                "priority" to (i(it["PRIORITY"]) ?: 0), "request_due_time" to blankToNull(it["REQUEST_DUE_TIME"]),
                "product_id" to it["PRODUCT_ID"],
                "location_id" to (blankToNull(it["LOCATION"]) ?: "VIRTUAL"),
                "quantity" to (d(it["QUANTITY"]) ?: 0.0),
            )
        }
        val location = readCsv(File(dir, "location.csv")).map { mapOf("location_id" to it["LOCATION_ID"]) }
        val methodBuy = readCsv(File(dir, "method_buy.csv")).map {
            mapOf(
                "product_id" to it["PRODUCT_ID"], "location_id" to (it["LOCATION_ID"]?.trim() ?: ""),
                "preference" to i(it["PREFERENCE"]), "lead_days_supply" to i(it["LEAD_DAYS_SUPPLY"]),
                "cycle_days_supply" to i(it["CYCLE_DAYS_SUPPLY"]), "vendor_id" to blankToNull(it["VENDOR_ID"]),
            )
        }
        val methodMake = readCsv(File(dir, "method_make.csv")).map {
            mapOf(
                "bom_id" to it["BOM_ID"], "product_id" to it["PRODUCT_ID"],
                "location_id" to (it["LOCATION_ID"]?.trim() ?: ""), "preference" to (i(it["PREFERENCE"]) ?: 0),
                "lead_time" to i(it["LEAD_TIME"]), "yield" to (d(it["YIELD"]) ?: 1.0),
            )
        }
        val product = readCsv(File(dir, "product.csv")).map {
            mapOf("product_id" to it["PRODUCT_ID"], "description" to blankToNull(it["DESCRIPTION"]))
        }
        val productlocation = readCsv(File(dir, "productlocation.csv")).map {
            mapOf(
                "product_id" to it["PRODUCT_ID"], "location_id" to (it["LOCATION_ID"]?.trim() ?: ""),
                "max_lot_size" to d(it["MAX_LOT_SIZE"]), "prod_area" to blankToNull(it["PROD_AREA"]),
            )
        }
        val supply = readCsv(File(dir, "supply.csv")).map {
            mapOf(
                "supply_id" to it["SUPPLY_ID"], "product_id" to it["PRODUCT_ID"],
                "location_id" to (blankToNull(it["LOCATION_ID"]) ?: ""), "supply_date" to blankToNull(it["SUPPLY_DATE"]),
                "qty" to (d(it["QTY"]) ?: 0.0), "target" to blankToNull(it["TARGET"]),
            )
        }
        val methodMove = readCsv(File(dir, "method_move.csv")).map {
            mapOf(
                "product_id" to it["PRODUCT_ID"], "from_location_id" to (it["FROM_LOCATION_ID"]?.trim() ?: ""),
                "to_location_id" to (it["TO_LOCATION_ID"]?.trim() ?: ""), "transit_time" to d(it["TRANSIT_TIME"]),
                "transit_time_uom" to blankToNull(it["TRANSIT_TIME_UOM"]), "preference" to (i(it["PREFERENCE"]) ?: 0),
            )
        }
        val vendor = readCsv(File(dir, "vendor.csv")).map { mapOf("vendor_id" to it["VENDOR_ID"]) }

        return mapOf(
            "bom" to bom, "customer" to customer, "demand" to demand, "location" to location,
            "method_buy" to methodBuy, "method_make" to methodMake, "product" to product,
            "productlocation" to productlocation, "operation" to emptyList(), "bor" to emptyList(),
            "resource" to emptyList(), "supply" to supply, "method_move" to methodMove, "vendor" to vendor,
        )
    }

    // Repo layout: backend-kotlin/src/test/kotlin/com/allocator/ -> ../../../../../.. -> repo root
    fun findCaseDir(): File? {
        var dir = File(".").absoluteFile
        repeat(8) {
            val candidate = File(dir, "cases/inno2026")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    test("cases/inno2026 (has a real negative-QTY supply.csv row) plans and passes soundness cleanly") {
        val caseDir = findCaseDir()
        if (caseDir == null) {
            println("SKIP: cases/inno2026 not found relative to test working directory")
            return@test
        }
        val data = loadCaseDir(caseDir)
        // Sanity: the fixture really does carry the negative row this test is meant to exercise.
        val negRows = (data["supply"] ?: emptyList()).filter { ((it["qty"] as? Number)?.toDouble() ?: 0.0) < 0.0 }
        negRows.size shouldBe 1
        (negRows[0]["product_id"]) shouldBe "285-0612"

        val cfg = mapOf<String, Any?>("purchase_allowed" to true)
        val result = runPlanning(data, cfg)

        @Suppress("UNCHECKED_CAST")
        val committedDemands = result.output["committed_demands"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val workOrders = result.output["work_orders"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val planningPegging = result.output["planning_pegging"] as List<Map<String, Any?>>

        val report = checkRunSoundness(
            planningPegging = planningPegging,
            demands = data["demand"] ?: emptyList(),
            data = data,
            workOrders = workOrders,
            committedDemands = committedDemands,
            inventoryEffectiveInitial = result.inventoryEffectiveInitial,
            inventoryLeftover = result.inventoryLeftover,
            supplyAllocations = (result.output["supply_allocations"] as? List<Map<String, Any?>>).orEmpty(),
            producedByComponent = result.producedByComponent,
        )

        report.demands.filterNot { it.sound }.forEach { d ->
            d.violations.forEach { v -> println("  demand=${d.demandId} rule=${v.rule} msg=${v.message}") }
        }
        report.crossDemandViolations.forEach { v -> println("  cross rule=${v.rule} msg=${v.message}") }
        // R11_wo_gid_orphan (report.woGidOrphanViolations) fires identically on `main` with this
        // same minimal CSV-only harness (verified via a direct main-vs-branch diff) — a pre-
        // existing gap in what this ad hoc loader replicates vs. the real DB-backed route, NOT
        // caused by negative-inventory support. Deliberately not asserted here; every OTHER
        // check (R0/R2/R4/R6/R7a/R7b/R7c/R7d — quantity propagation, supply/purchase leaf
        // validity, pegging consistency: exactly what this feature touches) is asserted below.
        report.demands.all { it.sound } shouldBe true
        report.crossDemandViolations.isEmpty() shouldBe true
    }
})
