package com.allocator.services

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val log = LoggerFactory.getLogger("com.allocator.ResourceScheduler")

private val DATE_FMT_RS: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/**
 * Cross-WO arbitration over a per-(resource, location) shared capacity timeline.
 *
 * Today's parallelism cap is single-WO: each WO sees the location's full pool
 * size for its own waves but can't see other WOs that share the same resource
 * at the same location. Two such WOs can run concurrently and stack their
 * rates past the pool capacity (the peak > size case the resource utilization
 * view warns about).
 *
 * This scheduler is a post-pass that runs after the planner has emitted WOs
 * with single-WO wave structure. It walks every WO in priority/temporal
 * order, reserves resource slots from a shared calendar, and pushes the WO's
 * lots later when slots aren't free. It mutates only lot start_time/end_time
 * — the caller is expected to follow up with [resequenceFromPegging] so the
 * shifts cascade upward (parent demand commit_time, grandparent WO start_time)
 * and the pegging trees re-sync from the updated lots.
 *
 * Phase 1 strategy: "push-no-matter-what". Always find *some* free window —
 * even months later than the demand needs. Demand commit_time cascades late;
 * existing late-commit reporting handles the rest. No new failure state.
 */
object ResourceScheduler {

    /**
     * Bucket the lots by `wo_group_id`, walk groups in
     * (demand priority desc, due-date asc, original start asc) order, and for
     * each make-WO group find the earliest start ≥ the original where every
     * BOR resource has enough free slots throughout the WO's wave structure.
     * Mutates the lots' start_time / end_time in place.
     *
     * Returns the number of WO groups whose start was pushed.
     */
    fun arbitrate(
        workOrders: List<MutableMap<String, Any?>>,
        data: Map<String, List<Map<String, Any?>>>,
        demandPriorityByDemandId: Map<String, Int> = emptyMap(),
        demandDueByDemandId: Map<String, LocalDate?> = emptyMap(),
    ): Int {
        // Group lots by wo_group_id; rows without a gid are skipped (legacy /
        // non-tracked plans).
        val groups: Map<String, List<MutableMap<String, Any?>>> = workOrders
            .filter { !((it["wo_group_id"] as? String).isNullOrBlank()) }
            .groupBy { it["wo_group_id"] as String }
        if (groups.isEmpty()) return 0

        val resourceRows = data["resource"] ?: emptyList()
        val sizeByResLoc = resourceRows.associate {
            val rid = (it["resource_id"] as? String)?.trim() ?: ""
            val lid = (it["location_id"] as? String)?.trim() ?: ""
            (rid to lid) to ((it["size"] as? Number)?.toDouble() ?: 0.0)
        }
        val calendars = mutableMapOf<Pair<String, String>, ResourceCalendar>()

        // Sort key: priority desc (negate for ascending), due asc, original-start asc.
        data class SortKey(val negPriority: Int, val due: Long, val origStart: Long) : Comparable<SortKey> {
            override fun compareTo(other: SortKey): Int {
                var c = negPriority.compareTo(other.negPriority); if (c != 0) return c
                c = due.compareTo(other.due); if (c != 0) return c
                return origStart.compareTo(other.origStart)
            }
        }
        fun keyFor(lots: List<MutableMap<String, Any?>>): SortKey {
            val demandId = (lots.firstOrNull()?.get("demand_id") as? String)?.trim().orEmpty()
            val priority = demandPriorityByDemandId[demandId] ?: 0
            val due = demandDueByDemandId[demandId]?.toEpochDay() ?: Long.MAX_VALUE
            val origStart = lots.minOfOrNull { parseDay(it["start_time"])?.toEpochDay() ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
            return SortKey(-priority, due, origStart)
        }

        val orderedGroups = groups.entries.sortedBy { keyFor(it.value) }

        var pushedCount = 0
        for ((_, lots) in orderedGroups) {
            val first = lots.firstOrNull() ?: continue
            val methodType = (first["method"] as? String)?.lowercase() ?: ""
            if (methodType != "make") continue

            val productId = (first["product_id"] as? String)?.trim() ?: continue
            val locationId = (first["location_id"] as? String)?.trim() ?: continue

            val borRows = borRowsFor(productId, locationId, data)
            if (borRows.isEmpty()) continue

            // Compute cap from the BOR rows directly, matching ResourceUtilization.kt:
            // resources missing at this location or with zero rate are unconstrained
            // (don't limit parallelism — don't skip the WO on their account).
            // OperationLookup.parallelismCap returns 0 if ANY resource fails the lookup,
            // causing the whole WO to be skipped and leaving its slots un-reserved in the
            // shared calendar, which lets other WOs book the same days → real overload.
            var minCap = Int.MAX_VALUE
            for (br in borRows) {
                val rid = (br["resource_id"] as? String)?.trim() ?: continue
                val bRate = (br["resource_rate"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: continue
                val sz = sizeByResLoc[rid to locationId] ?: continue
                if (sz <= 0.0) continue
                val c = Math.floor(sz / bRate).toInt()
                if (c > 0 && c < minCap) minCap = c
            }
            if (minCap == Int.MAX_VALUE) continue
            val cap = minCap

            // Each consolidated WO arrives as exactly ONE row in mutableLots; lots.size==1.
            // The actual concurrent-lot count comes from the "lot_count" field. On the row's
            // FIRST pass through this scheduler its span is a single wave's raw lead time
            // (wave sequencing across a cap is this scheduler's job, matching SoundnessChecker's
            // R5_lead_time = perLotLead × ceil(lot_count/cap)), so totalSpan == one wave. On any
            // later pass the row's span has already been extended to cover all waves — the
            // `per_wave_days` fallback below (persisted after the first pass) is what keeps
            // perWaveDays correct instead of re-deriving it from that extended span.
            val sample = lots.minByOrNull { parseDay(it["start_time"])?.toEpochDay() ?: Long.MAX_VALUE } ?: continue
            val sampleStart = parseDay(sample["start_time"]) ?: continue
            val sampleEnd = parseDay(sample["end_time"]) ?: continue
            val totalSpan = (sampleEnd.toEpochDay() - sampleStart.toEpochDay()).coerceAtLeast(0L)
            if (totalSpan <= 0L) continue

            val originalGroupStart = lots.minOf { parseDay(it["start_time"])?.toEpochDay() ?: Long.MAX_VALUE }
            val origStartDt = LocalDate.ofEpochDay(originalGroupStart)
            // For consolidated rows (lots.size==1) the true lot count is stored in the field.
            val lotCount = if (lots.size > 1) lots.size
                           else (first["lot_count"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
            val waveCount = kotlin.math.ceil(lotCount.toDouble() / cap.toDouble()).toInt().coerceAtLeast(1)
            // Prefer the stable per-wave lead time persisted by a prior pass (see the
            // lots.size==1 branch below) over the row's current span. Pass 1 extends a
            // consolidated row's end_time to waveCount × perWaveDays; without this, Pass 2
            // would re-derive perWaveDays from that already-extended span and multiply by
            // waveCount again, doubling the true duration.
            val perWaveDays = (first["per_wave_days"] as? Number)?.toLong() ?: totalSpan

            val newStart = findEarliestFit(
                origStartDt = origStartDt,
                waveCount = waveCount,
                perWaveDays = perWaveDays,
                cap = cap,
                borRows = borRows,
                locationId = locationId,
                sizeByResLoc = sizeByResLoc,
                calendars = calendars,
            )

            // Reserve the WO's slots on each BOR resource. Last wave may have
            // fewer than cap lots; fitsAt was pessimistic on this (it checked
            // cap × rate for every wave), so reservations may "overbook" the
            // tail by a small amount. That's fine — we only need to ensure
            // FUTURE WOs see at least the actual consumption.
            val lotsInLastWave = lotCount - (waveCount - 1) * cap
            for (waveIdx in 0 until waveCount) {
                val waveStart = newStart.plusDays(waveIdx * perWaveDays)
                val waveEnd = waveStart.plusDays(perWaveDays)
                val lotsThisWave = if (waveIdx == waveCount - 1) lotsInLastWave else cap
                for (br in borRows) {
                    val rid = (br["resource_id"] as? String)?.trim() ?: continue
                    val rate = (br["resource_rate"] as? Number)?.toDouble() ?: continue
                    val slots = lotsThisWave * rate
                    val size = sizeByResLoc[rid to locationId] ?: continue
                    val cal = calendars.getOrPut(rid to locationId) { ResourceCalendar(size) }
                    cal.reserve(waveStart, waveEnd, slots)
                }
            }

            val shiftDays = newStart.toEpochDay() - originalGroupStart
            if (shiftDays > 0) pushedCount++

            if (lots.size == 1) {
                // Consolidated row: rewrite the single row's span to cover ALL waves
                // serially (waveCount × perWaveDays) — consolidateByWaves only gave us
                // one wave's worth, and unlike the shift-only path below this must
                // happen even with zero contention (cap alone can force multiple waves).
                val trueDurationDays = waveCount * perWaveDays
                lots[0]["start_time"] = formatDay(newStart)
                lots[0]["end_time"] = formatDay(newStart.plusDays(trueDurationDays))
                lots[0]["per_wave_days"] = perWaveDays
            } else if (shiftDays > 0) {
                for (lot in lots) {
                    parseDay(lot["start_time"])?.let { lot["start_time"] = formatDay(it.plusDays(shiftDays)) }
                    parseDay(lot["end_time"])?.let { lot["end_time"] = formatDay(it.plusDays(shiftDays)) }
                }
            }
        }

        if (pushedCount > 0) {
            log.info("ResourceScheduler.arbitrate: pushed_wo_groups={} total_wo_groups={}", pushedCount, orderedGroups.size)
        }
        return pushedCount
    }

    /**
     * Step forward one day at a time from origStartDt until every wave's
     * window fits every BOR resource's free slots. Bounded by a 2-year
     * ceiling — beyond that we accept whatever we can find (the existing
     * late-commit reporting flags such cases via the demand's commit_time
     * after the cascade pass).
     */
    private fun findEarliestFit(
        origStartDt: LocalDate,
        waveCount: Int,
        perWaveDays: Long,
        cap: Int,
        borRows: List<Map<String, Any?>>,
        locationId: String,
        sizeByResLoc: Map<Pair<String, String>, Double>,
        calendars: Map<Pair<String, String>, ResourceCalendar>,
    ): LocalDate {
        var candidate = origStartDt
        val ceiling = origStartDt.plusYears(2)
        while (candidate.isBefore(ceiling)) {
            if (fitsAt(candidate, waveCount, perWaveDays, cap, borRows, locationId, sizeByResLoc, calendars)) {
                return candidate
            }
            candidate = candidate.plusDays(1)
        }
        log.warn("ResourceScheduler: WO at {} couldn't find a slot within 2 years; pushing to ceiling {}", origStartDt, ceiling)
        return ceiling
    }

    private fun fitsAt(
        start: LocalDate,
        waveCount: Int,
        perWaveDays: Long,
        cap: Int,
        borRows: List<Map<String, Any?>>,
        locationId: String,
        sizeByResLoc: Map<Pair<String, String>, Double>,
        calendars: Map<Pair<String, String>, ResourceCalendar>,
    ): Boolean {
        for (waveIdx in 0 until waveCount) {
            val waveStart = start.plusDays(waveIdx * perWaveDays)
            val waveEnd = waveStart.plusDays(perWaveDays)
            for (br in borRows) {
                val rid = (br["resource_id"] as? String)?.trim() ?: continue
                val rate = (br["resource_rate"] as? Number)?.toDouble() ?: continue
                val needed = cap * rate  // pessimistic for the last wave; safe
                val size = sizeByResLoc[rid to locationId] ?: return false
                if (needed > size + 1e-9) return false  // single wave too big — should already have been caught by cap=0 fallback
                val cal = calendars[rid to locationId] ?: continue
                if (!cal.canFit(waveStart, waveEnd, needed)) return false
            }
        }
        return true
    }

    private fun borRowsFor(
        productId: String,
        locationId: String,
        data: Map<String, List<Map<String, Any?>>>,
    ): List<Map<String, Any?>> {
        val plRow = data["productlocation"]?.firstOrNull {
            (it["product_id"] as? String)?.trim() == productId &&
            (it["location_id"] as? String)?.trim() == locationId
        } ?: return emptyList()
        val prodArea = plRow["prod_area"]?.toString()?.trim() ?: return emptyList()
        val op = data["operation"]?.firstOrNull {
            (it["prod_area"] as? String)?.trim() == prodArea
        } ?: return emptyList()
        val borId = (op["bor_id"] as? String)?.trim() ?: return emptyList()
        return data["bor"]?.filter { (it["bor_id"] as? String)?.trim() == borId } ?: emptyList()
    }

    private fun parseDay(s: Any?): LocalDate? {
        val str = (s as? String)?.trim() ?: return null
        if (str.isBlank()) return null
        return try { LocalDate.parse(str.take(10)) } catch (_: Exception) { null }
    }

    private fun formatDay(d: LocalDate): String = d.format(DATE_FMT_RS)
}

/**
 * Sorted-interval resource calendar. Each interval = [start, end) consuming
 * `slots` units of the resource's pool of `size`. Day-granularity (matches
 * the planner's LocalDate buckets); end is exclusive — a [d, d+1) interval
 * occupies exactly day d.
 *
 * Day-by-day O(intervals × days) usage check is acceptable for Phase 1
 * (small N intervals per case, modest horizon). If this becomes a bottleneck,
 * a sweep-line / interval-tree representation would speed it up.
 */
class ResourceCalendar(val size: Double) {

    data class Interval(val start: LocalDate, val end: LocalDate, val slots: Double)

    private val intervals = mutableListOf<Interval>()

    fun usageOn(d: LocalDate): Double {
        var sum = 0.0
        for (iv in intervals) {
            if (!d.isBefore(iv.start) && d.isBefore(iv.end)) sum += iv.slots
        }
        return sum
    }

    fun canFit(start: LocalDate, end: LocalDate, slots: Double): Boolean {
        if (slots > size + 1e-9) return false
        var d = start
        while (d.isBefore(end)) {
            if (usageOn(d) + slots > size + 1e-9) return false
            d = d.plusDays(1)
        }
        return true
    }

    fun reserve(start: LocalDate, end: LocalDate, slots: Double) {
        if (!start.isBefore(end)) return
        intervals.add(Interval(start, end, slots))
    }
}
