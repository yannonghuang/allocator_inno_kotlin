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
            if (methodType != "make") continue  // move / purchase don't consume operation resources

            val productId = (first["product_id"] as? String)?.trim() ?: continue
            val locationId = (first["location_id"] as? String)?.trim() ?: continue

            val cap = OperationLookup.parallelismCap(productId, locationId, data)
            if (cap < 1) continue  // override inapplicable → nothing to arbitrate

            val borRows = borRowsFor(productId, locationId, data)
            if (borRows.isEmpty()) continue

            // Per-wave duration: a wave = the set of lots that share start/end.
            // Sample one lot to read perWaveDays, then derive waveCount from
            // lotCount + cap. Sequential WOs collapse to perWaveDays == per-lot.
            val sample = lots.minByOrNull { parseDay(it["start_time"])?.toEpochDay() ?: Long.MAX_VALUE } ?: continue
            val sampleStart = parseDay(sample["start_time"]) ?: continue
            val sampleEnd = parseDay(sample["end_time"]) ?: continue
            val perWaveDays = (sampleEnd.toEpochDay() - sampleStart.toEpochDay()).coerceAtLeast(0L)
            if (perWaveDays <= 0) continue  // zero-duration WO, nothing to reserve

            val originalGroupStart = lots.minOf { parseDay(it["start_time"])?.toEpochDay() ?: Long.MAX_VALUE }
            val origStartDt = LocalDate.ofEpochDay(originalGroupStart)
            val lotCount = lots.size
            val waveCount = kotlin.math.ceil(lotCount.toDouble() / cap.toDouble()).toInt().coerceAtLeast(1)

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
            if (shiftDays > 0) {
                pushedCount++
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
