package app.tripledger.domain

import app.tripledger.data.Entry
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An entry together with what it cost me in the trip's home currency: my share of it, converted
 * with the expense's own rate if it has one, otherwise the trip's. Null when no rate is set.
 */
data class ConvertedEntry(val entry: Entry, val homeMinor: Long?)

data class DayGroup(val date: Long, val subtotal: Long, val entries: List<ConvertedEntry>)

data class CategorySlice(val categoryId: Long, val total: Long, val count: Int, val permille: Int)

/** A total for one group (a payment method, a currency, ...) with its share in permille. */
data class GroupSlice<K>(val key: K, val total: Long, val count: Int, val permille: Int)

data class DayTotal(val date: Long, val total: Long)

data class Summary(
    val total: Long = 0,
    val days: Int = 1,
    val dailyAverage: Long = 0,
    val count: Int = 0,
    /** Entries left out of the totals because their currency has no exchange rate. */
    val unconverted: Int = 0,
    /** Part of [total] that is marked "leave out of the daily average". */
    val excludedFromAverage: Long = 0,
)

/** Where a trip stands against its budget. Amounts are home minor units and can be negative. */
data class BudgetStatus(
    val tripBudget: Long,
    val dailyBudget: Long,
    val leftForTrip: Long,
    val leftToday: Long,
)

object Stats {
    fun convert(entries: List<Entry>, converter: Converter): List<ConvertedEntry> =
        entries.map { ConvertedEntry(it, converter.toHome(it.effectiveMinor, it.currency, it.rateOverride)) }

    /** Sum in home minor units. Entries without a rate contribute nothing; refunds subtract. */
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

    /** Totals for a period. Expenses flagged "leave out of the average" count in the total only. */
    fun summary(entries: List<ConvertedEntry>, days: Int): Summary {
        val total = total(entries)
        val excluded = total(entries.filter { it.entry.excludeFromAverage })
        return Summary(
            total = total,
            days = days,
            dailyAverage = dailyAverage(total - excluded, days),
            count = entries.size,
            unconverted = unconvertedCount(entries),
            excludedFromAverage = excluded,
        )
    }

    /** Newest day first; within a day the order of [entries] is kept. */
    fun groupByDay(entries: List<ConvertedEntry>): List<DayGroup> =
        entries.groupBy { it.entry.date }
            .map { (date, list) -> DayGroup(date, total(list), list) }
            .sortedByDescending { it.date }

    private fun permille(part: Long, whole: Long): Int =
        if (whole <= 0 || part <= 0) 0 else
            BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(1000))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).toInt()

    /**
     * Totals per group, largest first. Shares are of the sum of the groups that are above zero,
     * so a group that nets out to a refund shows 0% instead of a negative share.
     */
    fun <K> groupTotals(entries: List<ConvertedEntry>, keyOf: (Entry) -> K): List<GroupSlice<K>> {
        val sums = entries.filter { it.homeMinor != null }.groupBy { keyOf(it.entry) }
            .map { (key, list) -> Triple(key, total(list), list.size) }
        val positive = sums.sumOf { maxOf(it.second, 0L) }
        return sums.map { (key, sum, count) -> GroupSlice(key, sum, count, permille(sum, positive)) }
            .sortedByDescending { it.total }
    }

    /** Spend per category, largest first, with each share in permille (tenths of a percent). */
    fun byCategory(entries: List<ConvertedEntry>): List<CategorySlice> =
        groupTotals(entries) { it.categoryId }
            .map { CategorySlice(it.key, it.total, it.count, it.permille) }
            .sortedWith(compareByDescending<CategorySlice> { it.total }.thenBy { it.categoryId })

    /** Spend per payment method; the key is null for expenses without one. */
    fun byPaymentMethod(entries: List<ConvertedEntry>): List<GroupSlice<Long?>> =
        groupTotals(entries) { it.paymentMethodId }

    /** Spend per currency paid in, valued in the home currency. */
    fun byCurrency(entries: List<ConvertedEntry>): List<GroupSlice<String>> =
        groupTotals(entries) { it.currency }

    /**
     * How one expense is attributed to days. Normally all of it lands on its own date; an expense
     * spread over N days is split evenly from that date on, with any leftover minor units going
     * to the first days so the parts always add up to the whole.
     */
    fun allocations(item: ConvertedEntry): List<DayTotal> {
        val home = item.homeMinor ?: return emptyList()
        val n = item.entry.spreadDays.coerceIn(1, 3660)
        if (n == 1) return listOf(DayTotal(item.entry.date, home))
        val base = home / n
        val leftover = home - base * n // same sign as home, magnitude below n
        val step = if (leftover < 0) -1L else 1L
        val extraDays = Math.abs(leftover)
        return (0 until n).map { i -> DayTotal(item.entry.date + i, base + if (i < extraDays) step else 0L) }
    }

    /** Spending attributed to one day, taking spread expenses into account. */
    fun dayTotal(entries: List<ConvertedEntry>, day: Long): Long =
        entries.sumOf { item -> allocations(item).filter { it.date == day }.sumOf { it.total } }

    /** One total per day from [from] to [to] inclusive, including days with no spending. */
    fun perDay(entries: List<ConvertedEntry>, from: Long, to: Long): List<DayTotal> {
        if (to < from) return emptyList()
        val sums = HashMap<Long, Long>()
        for (item in entries) {
            for (part in allocations(item)) sums[part.date] = (sums[part.date] ?: 0L) + part.total
        }
        return (from..to).map { DayTotal(it, sums[it] ?: 0L) }
    }

    /** Running total of a per-day series. */
    fun cumulative(days: List<DayTotal>): List<DayTotal> {
        var running = 0L
        return days.map { running += it.total; DayTotal(it.date, running) }
    }

    fun biggest(entries: List<ConvertedEntry>, limit: Int): List<ConvertedEntry> =
        entries.filter { (it.homeMinor ?: 0L) > 0L }
            .sortedWith(compareByDescending<ConvertedEntry> { it.homeMinor }.thenByDescending { it.entry.date })
            .take(limit)

    /**
     * Budget position, or null when the trip has no budget. A per-day budget is multiplied by the
     * trip's length for the trip figure; a whole-trip budget is divided by it for the daily one.
     */
    fun budget(budgetMinor: Long, perDay: Boolean, tripDays: Int, spent: Long, spentToday: Long): BudgetStatus? {
        if (budgetMinor <= 0) return null
        val days = tripDays.coerceAtLeast(1)
        val tripBudget = if (perDay) Math.multiplyExact(budgetMinor, days.toLong()) else budgetMinor
        val dailyBudget = if (perDay) budgetMinor else dailyAverage(budgetMinor, days)
        return BudgetStatus(tripBudget, dailyBudget, tripBudget - spent, dailyBudget - spentToday)
    }

    /** "34.5%" style text for a permille value. */
    fun percentText(permille: Int, dec: Char = '.'): String = "${permille / 10}$dec${permille % 10}%"
}
