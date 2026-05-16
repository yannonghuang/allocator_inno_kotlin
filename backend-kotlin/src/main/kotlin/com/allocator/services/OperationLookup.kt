package com.allocator.services

/**
 * Effective lead-time decision for a make method.
 *
 * `source = "uph"` means the operation/BOR/resource model produced a granular
 * estimate; `source = "method_make"` means the static lead_time was used (no
 * matching operation, or some BOR resource was missing at the WO's location).
 */
data class EffectiveLead(
    val days: Double,
    val source: String,
    val operationId: String? = null,
)

object OperationLookup {

    /**
     * Override `method_make.lead_time` with an operation-derived effective lead
     * time when ALL hold:
     *   1. productlocation row exists for (productId, locationId)
     *   2. productlocation.prod_area matches an operation.prod_area
     *   3. The operation's BOR has at least one resource row
     *   4. Every BOR resource has a resource row at the WO's locationId
     *
     * Effective seconds = pre + qty/yield * process_time + (qty/yield/UPH)*3600 + post.
     * `process_time` is interpreted as per-unit overhead (matches the spec's
     * placement as a top-level operation field alongside UPH). If a future
     * reading treats it as a baseline floor instead, change this branch.
     */
    fun effectiveLeadDays(
        productId: String,
        locationId: String,
        qty: Double,
        methodMakeLeadDays: Double,
        data: Map<String, List<Map<String, Any?>>>,
    ): EffectiveLead {
        val fallback = EffectiveLead(methodMakeLeadDays, "method_make")
        if (qty <= 0.0) return fallback

        val prodArea = (data["productlocation"] ?: return fallback)
            .firstOrNull {
                (it["product_id"] as? String)?.trim() == productId &&
                (it["location_id"] as? String)?.trim() == locationId
            }
            ?.get("prod_area")?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return fallback

        val op = (data["operation"] ?: return fallback)
            .firstOrNull { (it["prod_area"] as? String)?.trim() == prodArea }
            ?: return fallback

        val borId = (op["bor_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
        val uph = (op["uph"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: return fallback
        val yieldFactor = (op["yield_factor"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
        val pre = (op["pre_process_time"] as? Number)?.toDouble() ?: 0.0
        val procPerUnit = (op["process_time"] as? Number)?.toDouble() ?: 0.0
        val post = (op["post_process_time"] as? Number)?.toDouble() ?: 0.0

        val borRows = (data["bor"] ?: emptyList())
            .filter { (it["bor_id"] as? String)?.trim() == borId }
        if (borRows.isEmpty()) return fallback

        val resourceRows = data["resource"] ?: emptyList()
        val allPresent = borRows.all { br ->
            val rid = (br["resource_id"] as? String)?.trim() ?: return@all false
            resourceRows.any {
                (it["resource_id"] as? String)?.trim() == rid &&
                (it["location_id"] as? String)?.trim() == locationId
            }
        }
        if (!allPresent) return fallback

        val adjustedQty = qty / yieldFactor
        val seconds = pre + adjustedQty * procPerUnit + (adjustedQty / uph) * 3600.0 + post
        return EffectiveLead(
            days = seconds / 86400.0,
            source = "uph",
            operationId = (op["operation_id"] as? String)?.trim(),
        )
    }
}
