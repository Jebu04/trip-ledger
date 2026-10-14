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
        title: String = "x", note: String = "", share: Long? = null, spread: Int = 1, excluded: Boolean = false,
        rate: String = "", method: Long? = null,
    ) = Entry(id = nextId++, tripId = 1, amountMinor = amount, currency = currency, categoryId = category,
        title = title, date = date, note = note, myShareMinor = share, spreadDays = spread,
        excludeFromAverage = excluded, rateOverride = rate, paymentMethodId = method)

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

    // ---- shared bills, refunds, own rates ----

    @Test fun aSharedBillCountsOnlyMyShare() {
        // A 60.00 dinner of which 20.00 is mine, and 800.00 THB (20.00 EUR) of which half is mine.
        val list = converted(entry(6000, share = 2000), entry(80000, "THB", share = 40000))
        assertEquals(listOf(2000L, 1000L), list.map { it.homeMinor })
        assertEquals(3000L, Stats.total(list))
    }

    @Test fun refundsReduceTheTotal() {
        val list = converted(entry(5000), entry(-1500), entry(-40000, "THB"))
        assertEquals(2500L, Stats.total(list))
        assertEquals(listOf(5000L), Stats.biggest(list, 5).map { it.homeMinor })
    }

    @Test fun anExpensesOwnRateBeatsTheTripRate() {
        val list = converted(entry(40000, "THB"), entry(40000, "THB", rate = "32"), entry(1600, "JPY", rate = "160"))
        assertEquals(listOf(1000L, 1250L, 1000L), list.map { it.homeMinor })
        assertEquals(0, Stats.unconvertedCount(list))
    }

    @Test fun aCategoryThatNetsToARefundGetsNoShare() {
        val slices = Stats.byCategory(converted(entry(3000, category = 1), entry(1000, category = 2), entry(-500, category = 3)))
        assertEquals(listOf(1L, 2L, 3L), slices.map { it.categoryId })
        assertEquals(listOf(750, 250, 0), slices.map { it.permille })
        assertEquals(-500L, slices[2].total)
    }

    @Test fun totalsByPaymentMethodAndCurrency() {
        val list = converted(entry(3000, method = 1), entry(40000, "THB", method = 2), entry(1000), entry(999, "JPY", method = 1))
        val byMethod = Stats.byPaymentMethod(list)
        assertEquals(listOf<Long?>(1L, null, 2L).toSet(), byMethod.map { it.key }.toSet())
        assertEquals(3000L, byMethod.first { it.key == 1L }.total) // the yen entry has no rate
        assertEquals(1000L, byMethod.first { it.key == null }.total)
        assertEquals(600, byMethod.first { it.key == 1L }.permille)
        val byCurrency = Stats.byCurrency(list)
        assertEquals(listOf("EUR", "THB"), byCurrency.map { it.key })
        assertEquals(listOf(4000L, 1000L), byCurrency.map { it.total })
        assertEquals(listOf(800, 200), byCurrency.map { it.permille })
    }

    // ---- spreading and the daily average ----

    @Test fun aSpreadExpenseIsSplitEvenlyAcrossItsDays() {
        val item = converted(entry(1000, date = 10, spread = 4)).single()
        val parts = Stats.allocations(item)
        assertEquals(listOf(10L, 11L, 12L, 13L), parts.map { it.date })
        assertEquals(listOf(250L, 250L, 250L, 250L), parts.map { it.total })
    }

    @Test fun leftoverCentsGoToTheFirstDaysSoThePartsAddUp() {
        val parts = Stats.allocations(converted(entry(1000, date = 10, spread = 3)).single())
        assertEquals(listOf(334L, 333L, 333L), parts.map { it.total })
        val refund = Stats.allocations(converted(entry(-1000, date = 10, spread = 3)).single())
        assertEquals(listOf(-334L, -333L, -333L), refund.map { it.total })
        val tiny = Stats.allocations(converted(entry(2, date = 10, spread = 5)).single())
        assertEquals(listOf(1L, 1L, 0L, 0L, 0L), tiny.map { it.total })
        for (n in 1..40) {
            assertEquals(61900L, Stats.allocations(converted(entry(61900, spread = n)).single()).sumOf { it.total })
        }
    }

    @Test fun unspreadAndUnconvertedExpenses() {
        assertEquals(listOf(100L to 700L), Stats.allocations(converted(entry(700)).single()).map { it.date to it.total })
        assertTrue(Stats.allocations(converted(entry(700, "JPY", spread = 3)).single()).isEmpty())
    }

    @Test fun perDayFiguresUseTheSpread() {
        val list = converted(entry(900, date = 10, spread = 3), entry(100, date = 11))
        assertEquals(listOf(300L, 400L, 300L, 0L), Stats.perDay(list, 10, 13).map { it.total })
        assertEquals(400L, Stats.dayTotal(list, 11))
        assertEquals(0L, Stats.dayTotal(list, 9))
        // Days outside the charted range are simply not shown; the total is unaffected.
        assertEquals(listOf(400L), Stats.perDay(list, 11, 11).map { it.total })
        assertEquals(1000L, Stats.total(list))
    }

    @Test fun cumulativeIsARunningTotal() {
        val days = Stats.perDay(converted(entry(100, date = 1), entry(250, date = 3), entry(-50, date = 4)), 1, 4)
        assertEquals(listOf(100L, 100L, 350L, 300L), Stats.cumulative(days).map { it.total })
        assertTrue(Stats.cumulative(emptyList()).isEmpty())
    }

    @Test fun expensesCanBeLeftOutOfTheDailyAverage() {
        // A 600.00 flight plus 40.00 of everyday spending over 4 days.
        val summary = Stats.summary(converted(entry(60000, excluded = true), entry(4000)), 4)
        assertEquals(64000L, summary.total)
        assertEquals(60000L, summary.excludedFromAverage)
        assertEquals(1000L, summary.dailyAverage)
    }

    // ---- budget ----

    @Test fun aWholeTripBudgetIsDividedIntoADailyOne() {
        val status = Stats.budget(budgetMinor = 140000, perDay = false, tripDays = 14, spent = 50000, spentToday = 2500)!!
        assertEquals(140000L, status.tripBudget)
        assertEquals(10000L, status.dailyBudget)
        assertEquals(90000L, status.leftForTrip)
        assertEquals(7500L, status.leftToday)
    }

    @Test fun aDailyBudgetIsMultipliedIntoATripOne() {
        val status = Stats.budget(budgetMinor = 8000, perDay = true, tripDays = 10, spent = 90000, spentToday = 9500)!!
        assertEquals(80000L, status.tripBudget)
        assertEquals(8000L, status.dailyBudget)
        assertEquals("overspent shows as a negative amount", -10000L, status.leftForTrip)
        assertEquals(-1500L, status.leftToday)
    }

    @Test fun budgetEdgeCases() {
        assertEquals(null, Stats.budget(0, false, 10, 500, 0))
        assertEquals(null, Stats.budget(-5, true, 10, 500, 0))
        // 100.00 over 3 days is 33.33 a day; a zero-length trip counts as one day.
        assertEquals(3333L, Stats.budget(10000, false, 3, 0, 0)!!.dailyBudget)
        assertEquals(10000L, Stats.budget(10000, false, 0, 0, 0)!!.dailyBudget)
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
        val shared = converted(entry(6000, share = 2000)).single()
        assertTrue("a shared bill is matched by my share", SearchFilter(minMinor = 2000, maxMinor = 2000).matches(shared, ""))
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

    @Test fun datesAreAlwaysInEnglish() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("fi", "FI"))
            val day = LocalDate.of(2026, 10, 14).toEpochDay()
            assertEquals("Wed, 14 Oct 2026", DateStyle.WEEKDAY_MEDIUM.format(day))
            assertEquals("14 October 2026", DateStyle.LONG.format(day))
            assertEquals("14 Oct", Dates.short(day, today = day))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun shortDatesShowTheYearOnlyWhenItDiffers() {
        val today = LocalDate.of(2026, 10, 14).toEpochDay()
        assertEquals("5 Oct", Dates.short(LocalDate.of(2026, 10, 5).toEpochDay(), today))
        assertEquals("25 May 2025", Dates.short(LocalDate.of(2025, 5, 25).toEpochDay(), today))
    }

    @Test fun firstDayOfWeekFollowsTheRegion() {
        assertEquals(DayOfWeek.SUNDAY, Dates.firstDayFor(Locale.US))
        assertEquals(DayOfWeek.MONDAY, Dates.firstDayFor(Locale.UK))
        assertEquals(DayOfWeek.MONDAY, Dates.firstDayFor(Locale("fi", "FI")))
        assertEquals(DayOfWeek.SATURDAY, Dates.firstDayFor(Locale("ar", "EG")))
    }
}
