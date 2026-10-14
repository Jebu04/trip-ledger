package app.tripledger.domain

import app.tripledger.data.Entry
import java.math.BigDecimal
import java.math.RoundingMode

/** An entry together with its value in the trip's home currency (null when no rate is set). */
data class ConvertedEntry(val entry: Entry, val homeMinor: Long?)

data class DayGroup(val date: Long, val subtotal: Long, val entries: List<ConvertedEntry>)

data class CategorySlice(val categoryId: Long, val total: Long, val count: Int, val permille: Int)

data class DayTotal(val date: Long, val total: Long)

data class Summary(
    val total: Long = 0,
    val days: Int = 1,
    val dailyAverage: Long = 0,
    val count: Int = 0,
    /** Entries left out of the totals because their currency has no exchange rate. */
    val unconverted: Int = 0,
)

object Stats {
    fun convert(entries: List<Entry>, converter: Converter): List<ConvertedEntry> =
        entries.map { ConvertedEntry(it, converter.toHome(it.amountMinor, it.currency)) }

    /** Sum in home minor units. Entries without a rate contribute nothing. */
    fun total(entries: List<ConvertedEntry>): Long = entries.sumOf { it.homeMinor ?: 0L }

    fun unconvertedCount(entries: List<ConvertedEntry>): Int = entries.count { it.homeMinor == null }

    /**
     * Number of days the daily average is spread over.
     *
     * The period runs from the trip start to today, capped at the trip end, and is widened to
     * include any entry dated outside it. It is never less than one day.
     */
    fun dayCount(tripStart: Long, tripEnd: Long, today: Long, firstEntry: Long?, lastEntry: Long?): Int {
        val start = minOf(tripStart, firstEntry ?: tripStart)
        val elapsedEnd = minOf(today, tripEnd)
        val end = maxOf(elapsedEnd, lastEntry ?: elapsedEnd)
        return daysInRange(start, end)
    }

    /** Inclusive day count of a range, never less than one. */
    fun daysInRange(from: Long, to: Long): Int = (to - from + 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    /** Total divided by days, rounded half-up to a whole minor unit. */
    fun dailyAverage(total: Long, days: Int): Long {
        if (days <= 0) return 0
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(days.toLong()), 0, RoundingMode.HALF_UP).longValueExact()
    }

    fun summary(entries: List<ConvertedEntry>, days: Int): Summary {
        val total = total(entries)
        return Summary(
            total = total,
            days = days,
            dailyAverage = dailyAverage(total, days),
            count = entries.size,
            unconverted = unconvertedCount(entries),
        )
    }

    /** Newest day first; within a day the order of [entries] is kept. */
    fun groupByDay(entries: List<ConvertedEntry>): List<DayGroup> =
        entries.groupBy { it.entry.date }
            .map { (date, list) -> DayGroup(date, total(list), list) }
            .sortedByDescending { it.date }

    /** Spend per category, largest first, with each share in permille (tenths of a percent). */
    fun byCategory(entries: List<ConvertedEntry>): List<CategorySlice> {
        val converted = entries.filter { it.homeMinor != null }
        val grand = total(converted)
        return converted.groupBy { it.entry.categoryId }
            .map { (categoryId, list) ->
                val sum = total(list)
                val permille = if (grand <= 0) 0 else
                    BigDecimal.valueOf(sum).multiply(BigDecimal.valueOf(1000))
                        .divide(BigDecimal.valueOf(grand), 0, RoundingMode.HALF_UP).toInt()
                CategorySlice(categoryId, sum, list.size, permille)
            }
            .sortedWith(compareByDescending<CategorySlice> { it.total }.thenBy { it.categoryId })
    }

    /** One total per day from [from] to [to] inclusive, including days with no spending. */
    fun perDay(entries: List<ConvertedEntry>, from: Long, to: Long): List<DayTotal> {
        if (to < from) return emptyList()
        val sums = HashMap<Long, Long>()
        for (e in entries) {
            val home = e.homeMinor ?: continue
            sums[e.entry.date] = (sums[e.entry.date] ?: 0L) + home
        }
        return (from..to).map { DayTotal(it, sums[it] ?: 0L) }
    }

    fun biggest(entries: List<ConvertedEntry>, limit: Int): List<ConvertedEntry> =
        entries.filter { it.homeMinor != null }
            .sortedWith(compareByDescending<ConvertedEntry> { it.homeMinor }.thenByDescending { it.entry.date })
            .take(limit)

    /** "34.5%" style text for a permille value. */
    fun percentText(permille: Int, dec: Char = '.'): String = "${permille / 10}$dec${permille % 10}%"
}
