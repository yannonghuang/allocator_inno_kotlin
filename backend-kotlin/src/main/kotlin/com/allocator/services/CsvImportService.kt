package com.allocator.services

import com.allocator.*
import com.opencsv.CSVReaderHeaderAware
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.FileReader
import java.io.InputStreamReader
import java.io.Reader
import java.nio.file.Path

private val log = LoggerFactory.getLogger("com.allocator.CsvImportService")

/**
 * Parses CSV tables and bulk-inserts them for a given case_id.
 * Port of services/csv_import.py — column names are the same uppercase header names from the CSV files.
 */
object CsvImportService {

    private val TABLES = listOf(
        "bom.csv" to ::importBom,
        "customer.csv" to ::importCustomer,
        "location.csv" to ::importLocation,
        "product.csv" to ::importProduct,
        "vendor.csv" to ::importVendor,
        "demand.csv" to ::importDemand,
        "method_buy.csv" to ::importMethodBuy,
        "method_make.csv" to ::importMethodMake,
        "productlocation.csv" to ::importProductLocation,
        "operation.csv" to ::importOperation,
        "bor.csv" to ::importBor,
        "resource.csv" to ::importResource,
        "supply.csv" to ::importSupply,
        "method_move.csv" to ::importMethodMove,
    )

    /** Filenames recognized by [importFromFolder] / [importFromUploads]. */
    val KNOWN_FILENAMES: Set<String> = TABLES.map { it.first }.toSet()

    fun importFromFolder(caseId: Int, folder: Path) {
        importFrom(caseId) { filename ->
            val file = folder.resolve(filename).toFile()
            if (file.exists()) FileReader(file) else null
        }
    }

    /** Same import, but sourced from in-memory uploads (e.g. a browser multipart upload) keyed by filename. */
    fun importFromUploads(caseId: Int, filesByName: Map<String, ByteArray>) {
        importFrom(caseId) { filename ->
            filesByName[filename]?.let { InputStreamReader(ByteArrayInputStream(it), Charsets.UTF_8) }
        }
    }

    private fun importFrom(caseId: Int, openReader: (String) -> Reader?) {
        transaction {
            for ((filename, importer) in TABLES) {
                val reader = openReader(filename)
                if (reader == null) {
                    log.warn("CSV not found, skipping: $filename")
                    continue
                }
                log.info("Importing $filename for case $caseId...")
                val rows = readCsv(reader)
                importer(caseId, rows)
            }
        }
        log.info("CSV import complete for case $caseId.")
    }

    private fun readCsv(reader: Reader): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        CSVReaderHeaderAware(reader).use { r ->
            var row: Map<String, String>?
            while (r.readMap().also { row = it } != null) {
                results.add(row!!)
            }
        }
        return results
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String?.toDoubleOrNullSafe(): Double? {
        if (this.isNullOrBlank() || this.uppercase() == "NULL") return null
        return this.toDoubleOrNull()
    }

    private fun String?.toIntOrNullSafe(): Int? {
        if (this.isNullOrBlank() || this.uppercase() == "NULL") return null
        return this.toDoubleOrNull()?.toInt()
    }

    // ── Per-table importers ───────────────────────────────────────────────────

    private fun importBom(caseId: Int, rows: List<Map<String, String>>) {
        Boms.deleteWhere { Boms.caseId eq caseId }
        Boms.batchInsert(rows) { r ->
            this[Boms.caseId] = caseId
            this[Boms.bomId] = r["BOM_ID"] ?: ""
            this[Boms.parentId] = r["PARENT_ID"] ?: ""
            this[Boms.childId] = r["CHILD_ID"] ?: ""
            this[Boms.elemIx] = r["ELEM_IX"].toIntOrNullSafe()
            this[Boms.altGroup] = r["ALT_GROUP"]?.takeIf { it.isNotBlank() }
            this[Boms.rate] = r["RATE"].toDoubleOrNullSafe()
        }
    }

    private fun importCustomer(caseId: Int, rows: List<Map<String, String>>) {
        Customers.deleteWhere { Customers.caseId eq caseId }
        Customers.batchInsert(rows) { r ->
            this[Customers.caseId] = caseId
            this[Customers.customer] = r["CUSTOMER"] ?: ""
            this[Customers.description] = r["DESCRIPTION"]?.takeIf { it.isNotBlank() }
        }
    }

    private fun importLocation(caseId: Int, rows: List<Map<String, String>>) {
        Locations.deleteWhere { Locations.caseId eq caseId }
        Locations.batchInsert(rows) { r ->
            this[Locations.caseId] = caseId
            this[Locations.locationId] = r["LOCATION_ID"] ?: ""
            this[Locations.locationDescription] = r["LOCATION_DESCRIPTION"]?.takeIf { it.isNotBlank() }
        }
    }

    private fun importProduct(caseId: Int, rows: List<Map<String, String>>) {
        Products.deleteWhere { Products.caseId eq caseId }
        Products.batchInsert(rows) { r ->
            this[Products.caseId] = caseId
            this[Products.productId] = r["PRODUCT_ID"] ?: ""
            this[Products.description] = r["DESCRIPTION"]?.takeIf { it.isNotBlank() }
        }
    }

    private fun importVendor(caseId: Int, rows: List<Map<String, String>>) {
        Vendors.deleteWhere { Vendors.caseId eq caseId }
        Vendors.batchInsert(rows) { r ->
            this[Vendors.caseId] = caseId
            this[Vendors.vendorId] = r["VENDOR_ID"] ?: ""
        }
    }

    private fun importDemand(caseId: Int, rows: List<Map<String, String>>) {
        Demands.deleteWhere { Demands.caseId eq caseId }
        Demands.batchInsert(rows) { r ->
            this[Demands.caseId] = caseId
            this[Demands.demandId] = r["ID"] ?: ""
            this[Demands.description] = r["DESCRIPTION"]?.takeIf { it.isNotBlank() }
            this[Demands.customerId] = r["CUSTOMER_ID"] ?: ""
            this[Demands.priority] = r["PRIORITY"].toIntOrNullSafe()
            this[Demands.requestDueTime] = r["REQUEST_DUE_TIME"]?.takeIf { it.isNotBlank() }
            this[Demands.productId] = r["PRODUCT_ID"] ?: ""
            // LOCATION or LOCATION_ID; fall back to "VIRTUAL"
            this[Demands.locationId] = (r["LOCATION"] ?: r["LOCATION_ID"])
                ?.takeIf { it.isNotBlank() } ?: "VIRTUAL"
            this[Demands.quantity] = r["QUANTITY"].toDoubleOrNullSafe() ?: 0.0
        }
    }

    /** Blank/whitespace-only (or literal "*") LOCATION_ID is a meaningful wildcard — "purchasable
     *  at any location" — not an omission to warn about. See isWildcardLocation (PlanningEngine.kt)
     *  for how planning resolves it. */
    private fun importMethodBuy(caseId: Int, rows: List<Map<String, String>>) {
        MethodBuys.deleteWhere { MethodBuys.caseId eq caseId }
        MethodBuys.batchInsert(rows) { r ->
            this[MethodBuys.caseId] = caseId
            this[MethodBuys.productId] = r["PRODUCT_ID"] ?: ""
            this[MethodBuys.locationId] = r["LOCATION_ID"]?.trim() ?: ""
            this[MethodBuys.preference] = r["PREFERENCE"].toIntOrNullSafe()
            this[MethodBuys.leadDaysSupply] = r["LEAD_DAYS_SUPPLY"].toIntOrNullSafe()
            this[MethodBuys.cycleDaysSupply] = r["CYCLE_DAYS_SUPPLY"].toIntOrNullSafe()
            this[MethodBuys.vendorId] = r["VENDOR_ID"]?.takeIf { it.isNotBlank() }
        }
    }

    /** Same "blank/whitespace-only/'*' LOCATION_ID = any location" wildcard as importMethodBuy. */
    private fun importMethodMake(caseId: Int, rows: List<Map<String, String>>) {
        MethodMakes.deleteWhere { MethodMakes.caseId eq caseId }
        MethodMakes.batchInsert(rows) { r ->
            this[MethodMakes.caseId] = caseId
            this[MethodMakes.bomId] = r["BOM_ID"] ?: ""
            this[MethodMakes.productId] = r["PRODUCT_ID"] ?: ""
            this[MethodMakes.locationId] = r["LOCATION_ID"]?.trim() ?: ""
            this[MethodMakes.preference] = r["PREFERENCE"].toIntOrNullSafe()
            this[MethodMakes.leadTime] = r["LEAD_TIME"].toIntOrNullSafe()
        }
    }

    /** Same wildcard convention as importMethodBuy — blank LOCATION_ID means this product's
     *  PROD_AREA/MAX_LOT_SIZE apply at any location (used as a fallback when no exact-location row
     *  exists for a queried location; see maxLotSize/getProdArea in PlanningEngine.kt). */
    private fun importProductLocation(caseId: Int, rows: List<Map<String, String>>) {
        ProductLocations.deleteWhere { ProductLocations.caseId eq caseId }
        ProductLocations.batchInsert(rows) { r ->
            this[ProductLocations.caseId] = caseId
            this[ProductLocations.productId] = r["PRODUCT_ID"] ?: ""
            this[ProductLocations.description] = r["DESCRIPTION"]?.takeIf { it.isNotBlank() }
            this[ProductLocations.locationId] = r["LOCATION_ID"]?.trim() ?: ""
            this[ProductLocations.maxLotSize] = r["MAX_LOT_SIZE"].toDoubleOrNullSafe()
            this[ProductLocations.prodArea] = r["PROD_AREA"]?.takeIf { it.isNotBlank() }
        }
    }

    private fun importOperation(caseId: Int, rows: List<Map<String, String>>) {
        Operations.deleteWhere { Operations.caseId eq caseId }
        Operations.batchInsert(rows) { r ->
            this[Operations.caseId] = caseId
            this[Operations.operationId] = r["OPERATION_ID"] ?: ""
            this[Operations.prodArea] = r["PROD_AREA"] ?: ""
            this[Operations.uph] = r["UPH"].toDoubleOrNullSafe() ?: 0.0
            this[Operations.yieldFactor] = r["YIELD_FACTOR"].toDoubleOrNullSafe() ?: 1.0
            this[Operations.borId] = r["BOR_ID"] ?: ""
            this[Operations.processTime] = r["PROCESS_TIME"].toIntOrNullSafe() ?: 0
            this[Operations.preProcessTime] = r["PRE_PROCESS_TIME"].toIntOrNullSafe() ?: 0
            this[Operations.postProcessTime] = r["POST_PROCESS_TIME"].toIntOrNullSafe() ?: 0
        }
    }

    private fun importBor(caseId: Int, rows: List<Map<String, String>>) {
        Bors.deleteWhere { Bors.caseId eq caseId }
        Bors.batchInsert(rows) { r ->
            this[Bors.caseId] = caseId
            this[Bors.borId] = r["BOR_ID"] ?: ""
            this[Bors.resourceId] = r["RESOURCE_ID"] ?: ""
            this[Bors.resourceRate] = r["RESOURCE_RATE"].toDoubleOrNullSafe() ?: 0.0
        }
    }

    private fun importResource(caseId: Int, rows: List<Map<String, String>>) {
        Resources.deleteWhere { Resources.caseId eq caseId }
        Resources.batchInsert(rows) { r ->
            this[Resources.caseId] = caseId
            this[Resources.resourceId] = r["RESOURCE_ID"] ?: ""
            this[Resources.locationId] = r["LOCATION_ID"] ?: ""
            this[Resources.size] = r["SIZE"].toDoubleOrNullSafe() ?: 0.0
        }
    }

    private fun importSupply(caseId: Int, rows: List<Map<String, String>>) {
        Supplies.deleteWhere { Supplies.caseId eq caseId }
        Supplies.batchInsert(rows) { r ->
            this[Supplies.caseId] = caseId
            this[Supplies.supplyId] = r["SUPPLY_ID"] ?: ""
            this[Supplies.description] = r["DESCRIPTION"]?.takeIf { it.isNotBlank() }
            this[Supplies.vendorId] = r["VENDOR_ID"]?.takeIf { it.isNotBlank() }
            this[Supplies.locationId] = r["LOCATION_ID"]?.takeIf { it.isNotBlank() }
            this[Supplies.productId] = r["PRODUCT_ID"] ?: ""
            this[Supplies.supplyDate] = r["SUPPLY_DATE"]?.takeIf { it.isNotBlank() }
            // Handle scientific notation in qty (e.g. "1e+06")
            this[Supplies.qty] = r["QTY"].toDoubleOrNullSafe() ?: 0.0
        }
    }

    /** Same wildcard convention as importMethodBuy, on EITHER location column: blank
     *  TO_LOCATION_ID means "movable to any location" (mirrors make/buy); blank FROM_LOCATION_ID
     *  means "movable from any location" and gets expanded into one concrete candidate per known
     *  case location at planning time, since (unlike TO) the source has no other concrete value to
     *  resolve against — see expandMoveWildcardSource in PlanningEngine.kt. */
    private fun importMethodMove(caseId: Int, rows: List<Map<String, String>>) {
        MethodMoves.deleteWhere { MethodMoves.caseId eq caseId }
        MethodMoves.batchInsert(rows) { r ->
            this[MethodMoves.caseId] = caseId
            this[MethodMoves.productId] = r["PRODUCT_ID"] ?: ""
            this[MethodMoves.fromLocationId] = r["FROM_LOCATION_ID"]?.trim() ?: ""
            this[MethodMoves.toLocationId] = r["TO_LOCATION_ID"]?.trim() ?: ""
            this[MethodMoves.transitTime] = r["TRANSIT_TIME"].toDoubleOrNullSafe()
            this[MethodMoves.transitTimeUom] = r["TRANSIT_TIME_UOM"]?.takeIf { it.isNotBlank() }
            this[MethodMoves.preference] = r["PREFERENCE"].toIntOrNullSafe()
        }
    }
}
