package com.allocator.services

import com.allocator.Boms
import com.allocator.Customers
import com.allocator.Demands
import com.allocator.MethodMakes
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** A customer that appears on this case's demands — drives the constraint "customer" dropdown. */
data class ConstraintCustomer(val customerId: String, val description: String?)

/**
 * A parent product that has BOM alternatives (≥2 distinct non-null alt_groups), with the
 * make locations where it's produced and the alternative children that can be forced.
 */
data class ConstraintParent(
    val parent: String,
    val locations: List<String>,
    val children: List<String>,
)

data class ConstraintOptionsData(
    val customers: List<ConstraintCustomer>,
    val parents: List<ConstraintParent>,
)

/**
 * Options for the planning page "Constraints" section: the customers on this case's demands,
 * and the parent products that actually have BOM alternatives (so the user can pin which
 * child a given customer's demand should resolve to). Mirrors [RawMaterials].
 */
object ConstraintOptions {
    fun forCase(caseId: Int): ConstraintOptionsData = transaction {
        // Distinct customer ids present on demands, with their description from the customer table.
        val custIds = Demands.selectAll().where { Demands.caseId eq caseId }
            .map { it[Demands.customerId].trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
        val descByCust = Customers.selectAll().where { Customers.caseId eq caseId }
            .associate { it[Customers.customer].trim() to it[Customers.description] }
        val customers = custIds.map { ConstraintCustomer(it, descByCust[it]) }

        // BOM rows for the case: (parent, child, altGroup).
        val bomRows = Boms.selectAll().where { Boms.caseId eq caseId }.map {
            Triple(it[Boms.parentId].trim(), it[Boms.childId].trim(), it[Boms.altGroup]?.trim())
        }
        // Make locations per parent product (where the make WO is produced).
        val locByParent = MethodMakes.selectAll().where { MethodMakes.caseId eq caseId }
            .groupBy({ it[MethodMakes.productId].trim() }, { it[MethodMakes.locationId].trim() })

        // A parent has OR-alternatives when its rows span ≥2 distinct non-null alt_groups.
        // The selectable children are the children of those alt-group rows.
        val parents = bomRows.groupBy { it.first }.mapNotNull { (parent, rows) ->
            val altGroups = rows.mapNotNull { it.third?.takeIf { g -> g.isNotEmpty() } }.toSet()
            if (altGroups.size < 2) return@mapNotNull null
            val children = rows.filter { !it.third.isNullOrEmpty() }.map { it.second }.toSortedSet().toList()
            val locations = locByParent[parent]?.toSortedSet()?.toList() ?: emptyList()
            ConstraintParent(parent, locations, children)
        }.sortedBy { it.parent }

        ConstraintOptionsData(customers, parents)
    }
}
