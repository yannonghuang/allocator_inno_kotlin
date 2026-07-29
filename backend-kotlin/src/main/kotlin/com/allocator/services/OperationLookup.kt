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
     * Returns the PER-LOT duration. Lot qty = min(slot qty, productlocation.max_lot_size).
     *
     * Two formulas (use one, not both):
     *   - First choice: UPH. seconds_per_lot = (lot_qty / yield / UPH) * 3600.
     *     Yield inflates the processed input so the requested good-output
     *     quantity is met after scrap.
     *   - Fallback (when UPH is 0/missing): the three time fields apply to the
     *     LOT as a whole (not per-unit). seconds_per_lot = pre + process + post.
     *
     * If the operation is found but has neither valid UPH nor any non-zero fixed
     * times, fall back to method_make.lead_time — better than emitting a zero.
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

        // Exact (product, location) row wins; a wildcard-location row (see isWildcardLocation,
        // PlanningEngine.kt) is only consulted when no exact row exists for this product.
        val productLocationRows = data["productlocation"] ?: return fallback
        val productLocationRow = productLocationRows.firstOrNull {
                (it["product_id"] as? String)?.trim() == productId &&
                (it["location_id"] as? String)?.trim() == locationId
            }
            ?: productLocationRows.firstOrNull {
                (it["product_id"] as? String)?.trim() == productId &&
                isWildcardLocation(it["location_id"] as? String)
            }
            ?: return fallback

        val prodArea = productLocationRow["prod_area"]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return fallback

        val op = (data["operation"] ?: return fallback)
            .firstOrNull { (it["prod_area"] as? String)?.trim() == prodArea }
            ?: return fallback

        val borId = (op["bor_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return fallback
        val uph = (op["uph"] as? Number)?.toDouble() ?: 0.0
        val yieldFactor = (op["yield_factor"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: 1.0
        val pre = (op["pre_process_time"] as? Number)?.toDouble() ?: 0.0
        val processTime = (op["process_time"] as? Number)?.toDouble() ?: 0.0
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

        // Tighten: a resource must have enough size to fit at least one lot
        // (size >= rate). If not, the override is infeasible — fall back to
        // the static lead so the planner doesn't emit a WO it can't staff.
        // Same gate parallelismCap below uses; keeps the two helpers
        // consistent so callers can rely on (effective=uph) ⇒ (cap >= 1).
        val allFeasible = borRows.all { br ->
            val rid = (br["resource_id"] as? String)?.trim() ?: return@all false
            val rate = (br["resource_rate"] as? Number)?.toDouble() ?: return@all false
            if (rate <= 0.0) return@all false
            val resRow = resourceRows.firstOrNull {
                (it["resource_id"] as? String)?.trim() == rid &&
                (it["location_id"] as? String)?.trim() == locationId
            } ?: return@all false
            val size = (resRow["size"] as? Number)?.toDouble() ?: return@all false
            rate <= size
        }
        if (!allFeasible) return fallback

        // Per-lot qty: max_lot_size caps it; otherwise the slot is one lot.
        val maxLotSize = (productLocationRow["max_lot_size"] as? Number)?.toDouble()?.takeIf { it > 0.0 }
        val lotQty = if (maxLotSize != null) minOf(qty, maxLotSize) else qty

        // Pick ONE of UPH or pre+process+post — never both.
        val secondsPerLot = when {
            uph > 0.0 -> (lotQty / yieldFactor / uph) * 3600.0
            (pre + processTime + post) > 0.0 -> pre + processTime + post
            else -> return fallback
        }

        // Ceil to whole days. The planner schedules in day-granularity buckets
        // (LocalDate.plusDays(Long)), so a sub-day lot must still consume one
        // calendar day; otherwise lot_end == lot_start and SoundnessChecker's
        // R5_lead_time (which expects end - start >= leadTime days) fires on
        // every operation-override WO.
        val daysExact = secondsPerLot / 86400.0
        val days = if (daysExact > 0.0) kotlin.math.ceil(daysExact) else 0.0
        return EffectiveLead(
            days = days,
            source = "uph",
            operationId = (op["operation_id"] as? String)?.trim(),
        )
    }

    /**
     * Maximum number of lots that can run in parallel at the given location,
     * limited by per-lot resource consumption against the location's pool sizes.
     *
     *   cap = min over BOR resources of floor(resource.size / resource_rate)
     *
     * Returns 0 when the operation override doesn't apply at all (no
     * productlocation row, no operation, empty BOR, missing resource at the
     * location, rate <= 0, or rate > size). Callers treat 0 as "fall back to
     * sequential method_make.lead_time", same shape as the existing override
     * fallback path.
     *
     * Same applicability gate as effectiveLeadDays — when that returns
     * source="uph", parallelismCap is guaranteed to be >= 1.
     */
    fun parallelismCap(
        productId: String,
        locationId: String,
        data: Map<String, List<Map<String, Any?>>>,
    ): Int {
        // Same exact-first, wildcard-fallback rule as effectiveLeadDays above.
        val productLocationRows = data["productlocation"] ?: return 0
        val productLocationRow = productLocationRows.firstOrNull {
                (it["product_id"] as? String)?.trim() == productId &&
                (it["location_id"] as? String)?.trim() == locationId
            }
            ?: productLocationRows.firstOrNull {
                (it["product_id"] as? String)?.trim() == productId &&
                isWildcardLocation(it["location_id"] as? String)
            }
            ?: return 0

        val prodArea = productLocationRow["prod_area"]?.toString()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return 0

        val op = (data["operation"] ?: return 0)
            .firstOrNull { (it["prod_area"] as? String)?.trim() == prodArea }
            ?: return 0

        val borId = (op["bor_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return 0

        val borRows = (data["bor"] ?: emptyList())
            .filter { (it["bor_id"] as? String)?.trim() == borId }
        if (borRows.isEmpty()) return 0

        val resourceRows = data["resource"] ?: emptyList()
        var minCap = Int.MAX_VALUE
        for (br in borRows) {
            val rid = (br["resource_id"] as? String)?.trim() ?: return 0
            val rate = (br["resource_rate"] as? Number)?.toDouble() ?: return 0
            if (rate <= 0.0) return 0
            val resRow = resourceRows.firstOrNull {
                (it["resource_id"] as? String)?.trim() == rid &&
                (it["location_id"] as? String)?.trim() == locationId
            } ?: return 0
            val size = (resRow["size"] as? Number)?.toDouble() ?: return 0
            val cap = kotlin.math.floor(size / rate).toInt()
            if (cap <= 0) return 0
            if (cap < minCap) minCap = cap
        }
        return if (minCap == Int.MAX_VALUE) 0 else minCap
    }

    /**
     * Split a make WO's [start, end) span into per-wave sub-windows carrying each
     * wave's TRUE concurrent-lot count — matching ResourceScheduler.arbitrate's
     * actual reservation model exactly: every wave except the last runs `cap`
     * concurrent lots; the last (often partial) wave runs
     * `lotCount - (waveCount-1)*cap` lots, which is always <= cap.
     *
     * Callers that instead apply a single flat `min(lotCount, cap)` across the
     * WHOLE span (as ResourceUtilization.kt and SoundnessChecker's R12 both used
     * to) overstate load on the tail wave's days — e.g. 3 lots at cap=2 is 2
     * waves (2 lots, then 1 lot), not "2 lots for both waves".
     *
     * `perWaveDaysHint` should be the WO row's persisted `per_wave_days` field
     * when present (the authoritative single-wave calendar lead time, set by
     * consolidateByWaves/crossWaveCalendarMerge and persisted by
     * ResourceScheduler after its first arbitration pass) — falls back to
     * splitting the observed span evenly across waveCount when absent (e.g.
     * native, single-wave WOs that never went through consolidation).
     */
    fun waveLotWindows(
        startDt: java.time.LocalDate,
        endDt: java.time.LocalDate,
        lotCount: Int,
        cap: Int,
        perWaveDaysHint: Long?,
    ): List<Triple<java.time.LocalDate, java.time.LocalDate, Int>> {
        if (cap <= 0 || lotCount <= 0 || !endDt.isAfter(startDt)) return emptyList()
        val waveCount = kotlin.math.ceil(lotCount.toDouble() / cap.toDouble()).toInt().coerceAtLeast(1)
        val totalSpanDays = endDt.toEpochDay() - startDt.toEpochDay()
        val perWaveDays = (perWaveDaysHint?.takeIf { it > 0 } ?: (totalSpanDays / waveCount)).coerceAtLeast(1L)
        val lotsInLastWave = lotCount - (waveCount - 1) * cap
        val windows = mutableListOf<Triple<java.time.LocalDate, java.time.LocalDate, Int>>()
        for (waveIdx in 0 until waveCount) {
            val waveStart = startDt.plusDays(waveIdx * perWaveDays)
            // Last wave ends exactly at endDt (not waveStart+perWaveDays) so integer-division
            // remainders in the fallback branch don't leave a gap or overrun the WO's real span.
            val waveEnd = if (waveIdx == waveCount - 1) endDt else waveStart.plusDays(perWaveDays)
            val lotsThisWave = if (waveIdx == waveCount - 1) lotsInLastWave else cap
            windows.add(Triple(waveStart, waveEnd, lotsThisWave))
        }
        return windows
    }
}
