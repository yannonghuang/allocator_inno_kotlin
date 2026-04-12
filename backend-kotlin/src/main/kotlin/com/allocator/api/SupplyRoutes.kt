package com.allocator.api

import com.allocator.Supplies
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
            val rows = transaction {
                Supplies.selectAll()
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
            val results = rows
                .filter { q.isEmpty() || it.id.startsWith(q, ignoreCase = true) || it.productId.contains(q, ignoreCase = true) }
                .take(20)
            call.respond(results)
        }
    }
}
