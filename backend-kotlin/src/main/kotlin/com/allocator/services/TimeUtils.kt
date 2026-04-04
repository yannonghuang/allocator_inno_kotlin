package com.allocator.services

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Time/period handling for allocation.
 * Port of services/time_utils.py.
 *
 * Period 0 = preexisting (null date).
 * Periods 1, 2, ... = chronological order of distinct dates across supplies + demands.
 */
object TimeUtils {

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Parse a date string to LocalDate, tolerating ISO-8601 and truncated formats. */
    fun parseDate(v: String?): LocalDate? {
        if (v.isNullOrBlank() || v.uppercase() in setOf("NULL", "NONE")) return null
        return try {
            LocalDate.parse(v.replace("Z", "").take(10), FMT)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /**
     * Build the period index from supply and demand date strings.
     * Returns (date_str → period_index, sorted date strings).
     * Period 0 = preexisting; 1, 2, ... = chronological order of distinct dates.
     */
    fun buildPeriodIndex(
        supplies: List<Map<String, Any?>>,
        demands: List<Map<String, Any?>>,
    ): Pair<Map<String, Int>, List<String>> {
        val dates = sortedSetOf<LocalDate>()
        supplies.forEach { s -> parseDate(s["supply_date"] as? String)?.let { dates.add(it) } }
        demands.forEach { d -> parseDate(d["request_due_time"] as? String)?.let { dates.add(it) } }
        val sortedDates = dates.toList()
        val dateToPeriod = sortedDates.mapIndexed { i, dt -> dt.format(FMT) to (i + 1) }.toMap()
        return dateToPeriod to sortedDates.map { it.format(FMT) }
    }

    /** Period 0 → "preexisting"; periods 1..n → sortedDates[period-1]. */
    fun periodToDate(period: Int?, sortedDates: List<String>): String? {
        if (period == null) return null
        if (period <= 0) return "preexisting"
        val i = period - 1
        if (i < 0 || i >= sortedDates.size) return null
        return sortedDates[i]
    }

    /** Period for a supply: 0 if null (preexisting), else period index. */
    fun supplyPeriod(supplyDate: String?, dateToPeriod: Map<String, Int>): Int {
        val d = parseDate(supplyDate) ?: return 0
        return dateToPeriod[d.format(FMT)] ?: 0
    }

    /** Due period for a demand: 0 = no date / as early as possible. */
    fun demandDuePeriod(requestDueTime: String?, dateToPeriod: Map<String, Int>): Int {
        val d = parseDate(requestDueTime) ?: return 0
        return dateToPeriod[d.format(FMT)] ?: 0
    }

    /**
     * Return period index that is [days] after the given period.
     * Used for lead_time / transit_time forward shift.
     */
    fun periodPlusDays(period: Int, days: Double, dateToPeriod: Map<String, Int>, sortedDates: List<String>): Int {
        if (sortedDates.isEmpty() || days <= 0) return period
        val baseStr = if (period <= 0) sortedDates[0] else sortedDates[minOf(period - 1, sortedDates.lastIndex)]
        val base = parseDate(baseStr) ?: return period
        val newDate = base.plusDays(days.toLong())
        val newStr = newDate.format(FMT)
        dateToPeriod[newStr]?.let { return it }
        // Smallest period whose date >= newStr
        sortedDates.forEachIndexed { i, d -> if (d >= newStr) return i + 1 }
        return sortedDates.size
    }

    /**
     * Return period index that is [days] before the given period.
     * Used for lead_time / transit_time backward shift.
     */
    fun periodMinusDays(period: Int, days: Double, dateToPeriod: Map<String, Int>, sortedDates: List<String>): Int {
        if (sortedDates.isEmpty() || days <= 0 || period <= 0) return maxOf(0, period)
        val i = minOf(period - 1, sortedDates.lastIndex)
        val base = parseDate(sortedDates[i]) ?: return period
        val newDate = base.minusDays(days.toLong())
        val newStr = newDate.format(FMT)
        dateToPeriod[newStr]?.let { return it }
        sortedDates.forEachIndexed { idx, d -> if (d >= newStr) return idx + 1 }
        return 0
    }
}
