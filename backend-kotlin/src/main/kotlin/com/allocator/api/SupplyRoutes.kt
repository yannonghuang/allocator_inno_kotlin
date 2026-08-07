package com.allocator.api

import com.allocator.Cases
import com.allocator.ProductLocations
import com.allocator.Supplies
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class SupplySuggestion(
    val id:          String,
    val description: String?,
    val productId:   String,
    val supplyDate:  String?,
    val qty:         Double,
    val vendorId:    String?,
    val locationId:  String?,
)

@Serializable
data class CaseSupplyRow(
    val id:          Int,
    val supplyId:    String,
    val productId:   String,
    val locationId:  String?,
    val vendorId:    String?,
    val supplyDate:  String?,
    val qty:         Double,
    val description: String?,
    /** From productlocation.prod_area — null when this (product, location) has no productlocation
     *  row (or the row's own prod_area is unset). Lets callers group/label supply by PROD_AREA
     *  without a separate lookup — e.g. the Planning page's Demand Summary attributes a synthetic
     *  inventory-consumption row to this same prod_area (see demandSummaryRows' own doc). */
    val prodArea:    String? = null,
    /** The lot's own physical TARGET (customer id), from supply.csv — null/unrestricted for most
     *  lots. Shown by the Targeted Supply Allocation editor as each row's starting/default value,
     *  distinct from any TSA `target` override (`case_allocation.target`) a user has since set. */
    val target:      String? = null,
)

/**
 * GET /supplies?q=<prefix>
 * Returns up to 20 supplies whose supplyId starts with the given prefix
 * (case-insensitive). description includes productId for context.
 * Used by the schub copilot /supply typeahead.
 */
fun Routing.supplyRoutes() {
    route("/supplies") {
        get {
            val q = call.request.queryParameters["q"]?.trim() ?: ""
            val caseId = call.request.queryParameters["caseId"]?.toIntOrNull()
            val rows = transaction {
                val query = if (caseId != null)
                    Supplies.selectAll().where { Supplies.caseId eq caseId }
                else
                    Supplies.selectAll()
                query
                    .orderBy(Supplies.supplyId, SortOrder.ASC)
                    .map { row ->
                        SupplySuggestion(
                            id          = row[Supplies.supplyId],
                            description = row[Supplies.description],
                            productId   = row[Supplies.productId],
                            supplyDate  = row[Supplies.supplyDate],
                            qty         = row[Supplies.qty],
                            vendorId    = row[Supplies.vendorId],
                            locationId  = row[Supplies.locationId],
                        )
                    }
            }
            // When no caseId is given, dedupe by supplyId so the typeahead doesn't
            // show the same row N times (one per case that imported the same CSV).
            val deduped = if (caseId == null)
                rows.distinctBy { it.id }
            else
                rows
            val results = deduped
                .filter { q.isEmpty() || it.id.startsWith(q, ignoreCase = true) || it.productId.contains(q, ignoreCase = true) }
                .take(20)
            call.respond(results)
        }
    }

    /**
     * GET /cases/{case_id}/supplies
     * Returns all supply records for the given case, ordered by supply_id.
     * Used by the planning Supply View tab to join with pegging data.
     */
    route("/cases/{case_id}/supplies") {
        get {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid case_id")
            val rows = transaction {
                val productLocationRows = ProductLocations.selectAll().where { ProductLocations.caseId eq caseId }.toList()
                // Exact (product, location) match when productlocation actually carries a location —
                // falls back to a product-only match for case data (like this app's own CSV fixtures)
                // where productlocation.csv's LOCATION_ID column is blank, meaning prod_area is
                // defined per-product only, not per (product, location) pair.
                val prodAreaByProductLocation = productLocationRows
                    .filter { it[ProductLocations.locationId].isNotBlank() }
                    .associate { "${it[ProductLocations.productId]}|${it[ProductLocations.locationId]}" to it[ProductLocations.prodArea] }
                val prodAreaByProduct = productLocationRows
                    .filter { it[ProductLocations.locationId].isBlank() }
                    .associate { it[ProductLocations.productId] to it[ProductLocations.prodArea] }
                Supplies.selectAll()
                    .where { Supplies.caseId eq caseId }
                    .orderBy(Supplies.supplyId, SortOrder.ASC)
                    .map { row ->
                        val productId = row[Supplies.productId]
                        val locationId = row[Supplies.locationId] ?: ""
                        CaseSupplyRow(
                            id          = row[Supplies.id],
                            supplyId    = row[Supplies.supplyId],
                            productId   = productId,
                            locationId  = row[Supplies.locationId],
                            vendorId    = row[Supplies.vendorId],
                            supplyDate  = row[Supplies.supplyDate],
                            qty         = row[Supplies.qty],
                            description = row[Supplies.description],
                            prodArea    = prodAreaByProductLocation["$productId|$locationId"] ?: prodAreaByProduct[productId],
                            target      = row[Supplies.targetCustomerId],
                        )
                    }
            }
            call.respond(rows)
        }
    }
}
