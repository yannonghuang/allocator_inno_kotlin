package com.allocator.services

import com.allocator.MethodBuys
import com.allocator.ProductLocations
import com.allocator.Products
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * A raw material that can be purchased — used to populate the "selective purchase"
 * whitelist UI and to give the planning copilot a catalog it can resolve free-text
 * conditions against.
 *
 * "Raw" is defined by the data: a product with a `productlocation` row whose
 * `prod_area = 'raw'` (case-insensitive). We further restrict to materials that
 * actually have a `method_buy` — only those are meaningful to whitelist.
 */
data class PurchasableMaterial(
    val productId: String,
    val description: String?,
    val vendorId: String?,
    val leadDaysSupply: Int?,
    /** SKU series (e.g. "1xx-xxxx") if the product_id matches a raw-material pattern, else null. */
    val skuPattern: String?,
)

object RawMaterials {

    /**
     * Raw materials (`productlocation.prod_area='raw'`) that have a `method_buy`,
     * with the attributes the copilot LLM uses to evaluate conditions. Sorted by
     * product_id for stable UI ordering.
     */
    fun purchasable(caseId: Int): List<PurchasableMaterial> = transaction {
        // product_ids tagged raw in productlocation (case-insensitive on prod_area)
        val rawPids = ProductLocations.selectAll()
            .where { ProductLocations.caseId eq caseId }
            .filter { (it[ProductLocations.prodArea]?.trim()?.lowercase()) == "raw" }
            .map { it[ProductLocations.productId].trim() }
            .toSet()
        if (rawPids.isEmpty()) return@transaction emptyList()

        // first method_buy row per product (vendor / lead time for display & conditions)
        val buyByProduct = MethodBuys.selectAll()
            .where { MethodBuys.caseId eq caseId }
            .mapNotNull { row ->
                val pid = row[MethodBuys.productId].trim()
                if (pid in rawPids) pid to row else null
            }
            .groupBy({ it.first }, { it.second })

        // descriptions from product (fall back to productlocation description)
        val descByProduct = Products.selectAll()
            .where { Products.caseId eq caseId }
            .associate { it[Products.productId].trim() to it[Products.description] }

        buyByProduct.keys.sorted().map { pid ->
            val firstBuy = buyByProduct[pid]?.firstOrNull()
            PurchasableMaterial(
                productId = pid,
                description = descByProduct[pid],
                vendorId = firstBuy?.get(MethodBuys.vendorId),
                leadDaysSupply = firstBuy?.get(MethodBuys.leadDaysSupply),
                skuPattern = SkuPatterns.rawMaterialPattern(pid),
            )
        }
    }
}
