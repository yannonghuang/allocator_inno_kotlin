package com.allocator.services

import com.allocator.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Loads all case entities from the DB into in-memory maps/lists for the engines.
 * Port of services/case_loader.py — preserves the same dict-key naming conventions
 * so the engine ports can stay close to the Python originals.
 */
object CaseLoader {

    /**
     * Returns a map whose keys match case_loader.py's load_case_data() output:
     *   "bom", "customer", "demand", "location", "method_buy", "method_make",
     *   "product", "productlocation", "operation", "bor", "resource",
     *   "supply", "method_move", "vendor", "overrides"
     *
     * Each value is a List<Map<String, Any?>> matching the Python dict structure.
     */
    fun load(caseId: Int): Map<String, List<Map<String, Any?>>> = transaction {

        val boms = Boms.selectAll().where { Boms.caseId eq caseId }.map {
            mapOf(
                "bom_id" to it[Boms.bomId],
                "parent_id" to it[Boms.parentId],
                "child_id" to it[Boms.childId],
                "rate" to (it[Boms.rate] ?: 0.0),
                "alt_group" to it[Boms.altGroup],
                "elem_ix" to it[Boms.elemIx],
            )
        }

        val customers = Customers.selectAll().where { Customers.caseId eq caseId }.map {
            mapOf(
                "customer" to it[Customers.customer],
                "description" to it[Customers.description],
            )
        }
        val custById = customers.associate { it["customer"] as String to (it["description"] ?: it["customer"]) }

        val demands = Demands.selectAll().where { Demands.caseId eq caseId }.map {
            val demandId = it[Demands.demandId].ifBlank { "demand_${it[Demands.id]}" }
            mapOf(
                "demand_id" to demandId,
                "description" to it[Demands.description],
                "customer_id" to it[Demands.customerId],
                "customer" to (custById[it[Demands.customerId]] ?: it[Demands.customerId]),
                "priority" to (it[Demands.priority] ?: 0),
                "request_due_time" to it[Demands.requestDueTime],
                "product_id" to it[Demands.productId],
                "location_id" to (it[Demands.locationId] ?: "VIRTUAL"),
                "quantity" to (it[Demands.quantity]),
            )
        }

        val locations = Locations.selectAll().where { Locations.caseId eq caseId }.map {
            mapOf("location_id" to it[Locations.locationId])
        }

        val methodBuy = MethodBuys.selectAll().where { MethodBuys.caseId eq caseId }.map {
            mapOf(
                "product_id" to it[MethodBuys.productId],
                "location_id" to it[MethodBuys.locationId],
                "preference" to it[MethodBuys.preference],
                "lead_days_supply" to it[MethodBuys.leadDaysSupply],
                "cycle_days_supply" to it[MethodBuys.cycleDaysSupply],
                "vendor_id" to it[MethodBuys.vendorId],
            )
        }

        val methodMake = MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }.map {
            mapOf(
                "bom_id" to it[MethodMakes.bomId],
                "product_id" to it[MethodMakes.productId],
                "location_id" to it[MethodMakes.locationId],
                "preference" to (it[MethodMakes.preference] ?: 0),
                "lead_time" to it[MethodMakes.leadTime],
                "yield" to (it[MethodMakes.yield] ?: 1.0),
            )
        }

        val products = Products.selectAll().where { Products.caseId eq caseId }.map {
            mapOf(
                "product_id" to it[Products.productId],
                "description" to it[Products.description],
            )
        }

        val productLocations = ProductLocations.selectAll().where { ProductLocations.caseId eq caseId }.map {
            mapOf(
                "product_id" to it[ProductLocations.productId],
                "location_id" to it[ProductLocations.locationId],
                "max_lot_size" to it[ProductLocations.maxLotSize],
                "prod_area" to it[ProductLocations.prodArea],
            )
        }

        val operations = Operations.selectAll().where { Operations.caseId eq caseId }.map {
            mapOf(
                "operation_id" to it[Operations.operationId],
                "prod_area" to it[Operations.prodArea],
                "uph" to it[Operations.uph],
                "yield_factor" to it[Operations.yieldFactor],
                "bor_id" to it[Operations.borId],
                "process_time" to it[Operations.processTime],
                "pre_process_time" to it[Operations.preProcessTime],
                "post_process_time" to it[Operations.postProcessTime],
            )
        }

        val bors = Bors.selectAll().where { Bors.caseId eq caseId }.map {
            mapOf(
                "bor_id" to it[Bors.borId],
                "resource_id" to it[Bors.resourceId],
                "resource_rate" to it[Bors.resourceRate],
            )
        }

        val resources = Resources.selectAll().where { Resources.caseId eq caseId }.map {
            mapOf(
                "resource_id" to it[Resources.resourceId],
                "location_id" to it[Resources.locationId],
                "size" to it[Resources.size],
            )
        }

        val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.map {
            mapOf(
                "supply_id" to it[Supplies.supplyId],
                "product_id" to it[Supplies.productId],
                "location_id" to (it[Supplies.locationId] ?: ""),
                "supply_date" to it[Supplies.supplyDate],
                "qty" to it[Supplies.qty],
                "target" to it[Supplies.targetCustomerId],
            )
        }

        val methodMove = MethodMoves.selectAll().where { MethodMoves.caseId eq caseId }.map {
            mapOf(
                "product_id" to it[MethodMoves.productId],
                "from_location_id" to it[MethodMoves.fromLocationId],
                "to_location_id" to it[MethodMoves.toLocationId],
                "transit_time" to it[MethodMoves.transitTime],
                "transit_time_uom" to it[MethodMoves.transitTimeUom],
                "preference" to (it[MethodMoves.preference] ?: 0),
            )
        }

        val vendors = Vendors.selectAll().where { Vendors.caseId eq caseId }.map {
            mapOf("vendor_id" to it[Vendors.vendorId])
        }

        mapOf(
            "bom" to boms,
            "customer" to customers,
            "demand" to demands,
            "location" to locations,
            "method_buy" to methodBuy,
            "method_make" to methodMake,
            "product" to products,
            "productlocation" to productLocations,
            "operation" to operations,
            "bor" to bors,
            "resource" to resources,
            "supply" to supplies,
            "method_move" to methodMove,
            "vendor" to vendors,
        )
    }
}
