package com.allocator.api

import com.allocator.Products
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class ProductSuggestion(val id: String, val description: String?)

/**
 * GET /products?q=<prefix>
 * Returns up to 20 products whose productId starts with the given prefix
 * (case-insensitive). Used by the schub copilot /material typeahead.
 * Filters in-memory — fine for typical materials catalogue sizes.
 */
fun Routing.productRoutes() {
    route("/products") {
        get {
            val q = call.request.queryParameters["q"]?.trim() ?: ""
            val rows = transaction {
                Products.selectAll()
                    .orderBy(Products.productId, SortOrder.ASC)
                    .map { row ->
                        ProductSuggestion(
                            id          = row[Products.productId],
                            description = row[Products.description],
                        )
                    }
            }
            val results = rows
                .filter { it.id.startsWith(q, ignoreCase = true) }
                .take(20)
            call.respond(results)
        }
    }
}
