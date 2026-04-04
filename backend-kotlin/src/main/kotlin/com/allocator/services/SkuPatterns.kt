package com.allocator.services

/**
 * SKU pattern helpers for raw material detection.
 * Port of utils/sku_patterns.py — patterns: 1xx-xxxx, 2xx-xxxx, 3xx-xxxx.
 */
object SkuPatterns {

    private val RAW_MATERIAL_PATTERNS = listOf(
        "1xx-xxxx" to Regex("^1..-...."),
        "2xx-xxxx" to Regex("^2..-...."),
        "3xx-xxxx" to Regex("^3..-...."),
    )

    /** Returns pattern name (e.g. "1xx-xxxx") if product_id is a raw material SKU, else null. */
    fun rawMaterialPattern(productId: String?): String? {
        if (productId.isNullOrBlank()) return null
        return RAW_MATERIAL_PATTERNS.firstOrNull { (_, pat) -> pat.containsMatchIn(productId) }?.first
    }
}
