package app.tripledger

import app.tripledger.data.Entry
import app.tripledger.domain.ConvertedEntry
import app.tripledger.domain.Converter
import app.tripledger.domain.Dates
import app.tripledger.domain.DateStyle
import app.tripledger.domain.SearchFilter
import app.tripledger.domain.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class StatsTest {
    private val converter = Converter(
        homeCode = "EUR",
        rates = mapOf("THB" to BigDecimal("40"), "JPY" to null),
        decimalsOf = { if (it == "JPY") 0 else 2 },
    )

    private var nextId = 1L

    private fun entry(
        amount: Long, currency: String = "EUR", category: Long = 1, date: Long = 100,
        title: String = "x", note: String = "",
    ) = Entry(id = nextId++, tripId = 1, amountMinor = amount, currency = currency, categoryId = category,
        title = title, date = date, note = note)

    private fun converted(vararg entries: Entry): List<ConvertedEntry> = Stats.convert(entries.toList(), converter)

    // ---- totals ----

    @Test fun totalAddsConvertedAmounts() {
        // 10.00 EUR + 400.00 THB (10.00 EUR at 40) = 20.00 EUR
        val list = converted(entry(1000), entry(40000, "THB"))
        assertEquals(2000L, Stats.total(list))
    }

    @Test fun totalOfNothingIsZero() {
        assertEquals(0L, Stats.total(emptyList()))
        assertEquals(0, Stats.summary(emptyList(), 5).count)
        assertEquals(0L, Stats.summary(emptyList(), 5).dailyAverage)
    }

    @Test fun entriesWithoutRateAreLeftOutAndCounted() {
        val list = converted(entry(1000), entry(5000, "JPY"), entry(700, "USD"))
        assertEquals(1000L, Stats.total(list))
        assertEquals(2, Stats.unconvertedCount(list))
        val summary = Stats.summary(list, 1)
        assertEquals(3, summary.count)
        assertEquals(2, summary.unconverted)
    }

    @Test fun totalIsTheSumOfRoundedEntriesSoDaySubtotalsAddUp() {
        // Each 1.00 THB is 0.025 EUR and rounds to 0.03; three of them make 0.09, not 0.08.
        val list = converted(entry(100, "THB", date = 1), entry(100, "THB", date = 1), entry(100, "THB", date = 2))
        assertEquals(9L, Stats.total(list))
        assertEquals(Stats.total(list), Stats.groupByDay(list).sumOf { it.subtotal })
    }

    // ---- daily average ----

    @Test fun dailyAverageDividesAndRoundsHalfUp() {
        assertEquals(500L, Stats.dailyAverage(1000, 2))
        assertEquals(333L, Stats.dailyAverage(1000, 3))
        assertEquals(667L, Stats.dailyAverage(2000, 3))
        assertEquals(1L, Stats.dailyAverage(1, 2)) // 0.5 rounds up
        assertEquals(0L, Stats.dailyAverage(0, 7))
    }

    @Test fun dailyAverageNeverDividesByZero() {
        assertEquals(0L, Stats.dailyAverage(1000, 0))
        assertEquals(0L, Stats.dailyAverage(1000, -3))
    }

    @Test fun dayCountDuringTheTripCountsElapsedDays() {
        // Trip days 100..109, today is day 104: five days so far.
        assertEquals(5, Stats.dayCount(100, 109, 104, null, null))
        assertEquals(1, Stats.dayCount(100, 109, 100, null, null))
    }

    @Test fun dayCountAfterTheTripUsesTheWholeTrip() {
        assertEquals(10, Stats.dayCount(100, 109, 500, 101, 108))
    }

    @Test fun dayCountBeforeTheTripIsOneDay() {
        assertEquals(1, Stats.dayCount(100, 109, 50, null, null))
    }

    @Test fun dayCountWidensToIncludeEntriesOutsideTheTrip() {
        // A flight booked 20 days before the trip starts.
        assertEquals(25, Stats.dayCount(100, 109, 104, 80, 104))
        // An entry dated after the trip's last day.
        assertEquals(12, Stats.dayCount(100, 109, 500, 100, 111))
        // Booked before a trip that has not started yet: from that entry up to that entry.
        assertEquals(1, Stats.dayCount(100, 109, 50, 60, 60))
    }

    @Test fun daysInRangeIsInclusiveAndAtLeastOne() {
        assertEquals(7, Stats.daysInRange(10, 16))
        assertEquals(1, Stats.daysInRange(10, 10))
        assertEquals(1, Stats.daysInRange(10, 5))
    }

    @Test fun summaryCombinesTotalAndAverage() {
        val summary = Stats.summary(converted(entry(1000), entry(2000)), 4)
        assertEquals(3000L, summary.total)
        assertEquals(750L, summary.dailyAverage)
        assertEquals(4, summary.days)
    }

    // ---- grouping ----

    @Test fun groupsByDayNewestFirstWithSubtotals() {
        val list = converted(entry(100, date = 5), entry(200, date = 7), entry(300, date = 5), entry(40000, "THB", date = 7))
        val groups = Stats.groupByDay(list)
        assertEquals(listOf(7L, 5L), groups.map { it.date })
        assertEquals(1200L, groups[0].subtotal)
        assertEquals(400L, groups[1].subtotal)
        assertEquals(2, groups[1].entries.size)
    }

    @Test fun categoryBreakdownIsSortedWithShares() {
        val list = converted(entry(2500, category = 1), entry(5000, category = 2), entry(2500, category = 1), entry(999, "JPY", category = 3))
        val slices = Stats.byCategory(list)
        assertEquals(listOf(1L, 2L), slices.map { it.categoryId }) // equal totals fall back to id order
        assertEquals(listOf(5000L, 5000L), slices.map { it.total })
        assertEquals(listOf(500, 500), slices.map { it.permille })
        assertEquals(2, slices[0].count)
    }

    @Test fun categorySharesRoundToTenthsOfAPercent() {
        val slices = Stats.byCategory(converted(entry(1000, category = 1), entry(2000, category = 2)))
        assertEquals(2L, slices[0].categoryId)
        assertEquals(667, slices[0].permille)
        assertEquals(333, slices[1].permille)
        assertEquals("66.7%", Stats.percentText(667))
        assertEquals("5,0%", Stats.percentText(50, ','))
        assertEquals("100.0%", Stats.percentText(1000))
    }

    @Test fun categoryBreakdownOfNothingIsEmpty() {
        assertTrue(Stats.byCategory(emptyList()).isEmpty())
        assertTrue(Stats.byCategory(converted(entry(100, "JPY"))).isEmpty())
    }

    @Test fun perDayIncludesDaysWithoutSpending() {
        val list = converted(entry(100, date = 10), entry(250, date = 12), entry(50, date = 12), entry(999, date = 99))
        val days = Stats.perDay(list, 10, 13)
        assertEquals(listOf(10L, 11L, 12L, 13L), days.map { it.date })
        assertEquals(listOf(100L, 0L, 300L, 0L), days.map { it.total })
        assertTrue(Stats.perDay(list, 13, 10).isEmpty())
    }

    @Test fun biggestExpensesAreRankedByHomeValue() {
        val small = entry(500, title = "small")
        val thb = entry(100000, "THB", title = "thb") // 25.00 EUR
        val big = entry(9000, title = "big")
        val noRate = entry(99999999, "JPY", title = "norate")
        val top = Stats.biggest(converted(small, thb, big, noRate), 2)
        assertEquals(listOf("big", "thb"), top.map { it.entry.title })
    }

    // ---- search ----

    @Test fun searchMatchesTitleNoteAndCategoryIgnoringCase() {
        val item = converted(entry(1200, title = "Pad Thai", note = "night market")).single()
        assertTrue(SearchFilter(query = "pad").matches(item, "Food"))
        assertTrue(SearchFilter(query = "MARKET").matches(item, "Food"))
        assertTrue(SearchFilter(query = "foo").matches(item, "Food"))
        assertTrue(SearchFilter(query = "thai market").matches(item, "Food"))
        assertFalse(SearchFilter(query = "thai taxi").matches(item, "Food"))
        assertFalse(SearchFilter(query = "hotel").matches(item, "Food"))
        assertTrue(SearchFilter(query = "   ").matches(item, "Food"))
    }

    @Test fun searchFiltersByCategoryDateAndAmount() {
        val item = converted(entry(1200, category = 3, date = 50)).single()
        assertTrue(SearchFilter(categoryIds = setOf(3L, 4L)).matches(item, ""))
        assertFalse(SearchFilter(categoryIds = setOf(4L)).matches(item, ""))
        assertTrue(SearchFilter(fromDate = 50, toDate = 50).matches(item, ""))
        assertFalse(SearchFilter(fromDate = 51).matches(item, ""))
        assertFalse(SearchFilter(toDate = 49).matches(item, ""))
        assertTrue(SearchFilter(minMinor = 1200, maxMinor = 1200).matches(item, ""))
        assertFalse(SearchFilter(minMinor = 1201).matches(item, ""))
        assertFalse(SearchFilter(maxMinor = 1199).matches(item, ""))
    }

    @Test fun searchAmountUsesHomeValueWhenThereIsOne() {
        val thb = converted(entry(40000, "THB")).single() // 10.00 EUR
        assertTrue(SearchFilter(minMinor = 900, maxMinor = 1100).matches(thb, ""))
        val noRate = converted(entry(5000, "JPY")).single()
        assertTrue("falls back to the original amount", SearchFilter(minMinor = 5000, maxMinor = 5000).matches(noRate, ""))
    }

    @Test fun emptyFilterIsRecognised() {
        assertTrue(SearchFilter().isEmpty)
        assertFalse(SearchFilter(query = "a").isEmpty)
        assertFalse(SearchFilter(minMinor = 1).isEmpty)
    }

    // ---- dates ----

    @Test fun startOfWeekHonoursTheFirstDaySetting() {
        val wednesday = LocalDate.of(2026, 10, 14).toEpochDay()
        assertEquals(LocalDate.of(2026, 10, 12).toEpochDay(), Dates.startOfWeek(wednesday, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 10, 11).toEpochDay(), Dates.startOfWeek(wednesday, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 10, 10).toEpochDay(), Dates.startOfWeek(wednesday, DayOfWeek.SATURDAY))
        assertEquals(wednesday, Dates.startOfWeek(wednesday, DayOfWeek.WEDNESDAY))
        assertEquals(DayOfWeek.SUNDAY, Dates.weekdays(DayOfWeek.SUNDAY).first())
        assertEquals(DayOfWeek.SATURDAY, Dates.weekdays(DayOfWeek.SUNDAY).last())
    }

    @Test fun dateStylesFormat() {
        val day = LocalDate.of(2026, 3, 7).toEpochDay()
        assertEquals("2026-03-07", DateStyle.ISO.format(day, Locale.UK))
        assertEquals("07/03/2026", DateStyle.DMY_SLASH.format(day, Locale.UK))
        assertEquals("03/07/2026", DateStyle.MDY_SLASH.format(day, Locale.UK))
        assertEquals("7.3.2026", DateStyle.DMY_DOT.format(day, Locale.UK))
        assertEquals("7 March 2026", DateStyle.LONG.format(day, Locale.UK))
        assertEquals(LocalDate.of(2026, 3, 1).toEpochDay(), Dates.startOfMonth(day))
    }
}
