package com.allocator

import com.allocator.services.checkRunSoundness
import com.allocator.services.getMethods
import com.allocator.services.runPlanning
import com.opencsv.CSVReaderHeaderAware
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.FileReader

/**
 * Live regression against the real case cases/inno2026w, which was authored anticipating this
 * feature: method_move.csv carries `PRODUCT_ID=CB,FROM=2000,TO=1000` — "CB" is not any real
 * product_id, it's the PROD_AREA of 280-2695 and 280-A3831 (see productlocation.csv). Before
 * PROD_AREA/global wildcarding, this row matched nothing and was silently dead: 280-2695 and
 * 280-A3831 have method_make ONLY at location 2000 (method_make.csv), no method_buy at all, and
 * no move at 1000 — so the 500-0003@1000 -> 280-2695@1000 / 280-A3831@1000 BOM edges (feeding
 * Q4R@1000's demand chain) had no possible source. This test confirms the PROD_AREA-wildcarded
 * move row now resolves for both products and the case plans soundly end to end.
 */
class Inno2026wMethodWildcardLiveTest : FunSpec({

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
            val candidate = File(dir, "cases/inno2026w")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    test("cases/inno2026w: 'CB' PROD_AREA wildcard method_move resolves for both CB-area products") {
        val caseDir = findCaseDir()
        if (caseDir == null) {
            println("SKIP: cases/inno2026w not found relative to test working directory")
            return@test
        }
        val data = loadCaseDir(caseDir)

        // Sanity: the fixture really does carry the dead-without-this-feature wildcard row.
        val cbMoveRows = (data["method_move"] ?: emptyList()).filter { it["product_id"] == "CB" }
        cbMoveRows.size shouldBe 1
        val knownProductIds = (data["productlocation"] ?: emptyList()).map { it["product_id"] }
        knownProductIds shouldContain "280-2695"
        knownProductIds shouldContain "280-A3831"
        (knownProductIds.contains("CB")) shouldBe false // confirms "CB" is a PROD_AREA, not a product_id

        // 280-2695 / 280-A3831 have no method_make at 1000 and no method_buy at all — the CB
        // wildcard move is their ONLY possible source at 1000.
        val methods2695 = getMethods("280-2695", "1000", data)
        val methods3831 = getMethods("280-A3831", "1000", data)
        methods2695.any { it["type"] == "move" && it["from_location_id"] == "2000" } shouldBe true
        methods3831.any { it["type"] == "move" && it["from_location_id"] == "2000" } shouldBe true

        val cfg = mapOf<String, Any?>("purchase_allowed" to true)
        val result = runPlanning(data, cfg)

        @Suppress("UNCHECKED_CAST")
        val committedDemands = result.output["committed_demands"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val workOrders = result.output["work_orders"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val planningPegging = result.output["planning_pegging"] as List<Map<String, Any?>>

        // The move must actually get used to source 280-2695/280-A3831 at 1000, not just be
        // reachable in principle. Pass 2 WO consolidation batches SAME-ROUTE moves for
        // DIFFERENT products (280-2695 + 280-A3831, both "CB", same 2000->1000 leg) into one
        // mixed-cargo WO row: top-level product_id is null, and the per-product breakdown lives
        // in move_components (see WoConsolidation/consolidateByWaves) — so assert against that
        // breakdown rather than the top-level product_id field.
        val cbMoveWos = workOrders.filter {
            it["method"] == "move" && it["location_id"] == "1000" && it["location_source"] == "2000" &&
                it["prod_area"] == "CB"
        }
        cbMoveWos.isEmpty() shouldBe false
        @Suppress("UNCHECKED_CAST")
        val movedComponentProducts = cbMoveWos
            .flatMap { (it["move_components"] as? List<Map<String, Any?>>).orEmpty() }
            .mapNotNull { it["product_id"] as? String }
            .toSet()
        movedComponentProducts shouldContain "280-2695"
        movedComponentProducts shouldContain "280-A3831"

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
        report.demands.all { it.sound } shouldBe true
        report.crossDemandViolations.isEmpty() shouldBe true
    }
})
