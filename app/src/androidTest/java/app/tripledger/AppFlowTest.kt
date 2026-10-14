package app.tripledger

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tripledger.data.Entry
import app.tripledger.data.Keys
import app.tripledger.data.RefData
import app.tripledger.data.Repository
import app.tripledger.data.Trip
import app.tripledger.domain.Dates
import app.tripledger.domain.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Drives the real app through its main flows. Every test starts from a wiped database with EUR
 * as the home currency, so results do not depend on the device locale or on test order.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var repo: Repository
    private var scenario: ActivityScenario<MainActivity>? = null
    private val today = Dates.today()

    @Before fun reset() {
        repo = ApplicationProvider.getApplicationContext<TripLedgerApp>().repository
        runBlocking {
            repo.deleteAllData()
            repo.setSetting(Keys.HOME_CURRENCY, "EUR")
            // Formats normally follow the phone's region; pin them so results don't depend on it.
            repo.setSetting(Keys.NUMBER_STYLE, "comma_dot")
            repo.setSetting(Keys.FIRST_DAY, "MONDAY")
        }
    }

    @After fun close() {
        scenario?.close()
    }

    // ---- helpers ----

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun ref(): RefData = runBlocking { repo.refData.first() }

    private fun categoryId(name: String): Long = ref().categories.first { it.name == name }.id

    /** A trip in EUR with baht at 40 per euro, which keeps the expected numbers easy to read. */
    private fun seedTrip(name: String = "Bangkok", locals: List<Pair<String, String>> = listOf("THB" to "40")): Long = runBlocking {
        val id = repo.saveTrip(Trip(name = name, startDate = today, endDate = today + 6, homeCurrency = "EUR"), locals)
        repo.setActiveTrip(id)
        id
    }

    private fun seedEntry(
        tripId: Long, title: String, amountMinor: Long, currency: String = "EUR",
        category: String = "Food & Drink", note: String = "", date: Long = today,
    ): Long = runBlocking {
        repo.saveEntry(
            Entry(tripId = tripId, amountMinor = amountMinor, currency = currency, categoryId = categoryId(category),
                title = title, date = date, note = note),
        )
    }

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String, substring: Boolean = false) = waitFor(hasText(text, substring = substring))

    private fun waitForTag(tag: String) = waitFor(hasTestTag(tag))

    private fun waitGone(matcher: SemanticsMatcher, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    }

    /** Taps an element, first scrolling it into view the way a user would (e.g. from behind the keyboard). */
    private fun tap(tag: String) {
        waitForTag(tag)
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() } // not everything sits inside a scrolling container
        node.performClick()
    }

    private fun tapText(text: String) {
        waitForText(text)
        compose.onNodeWithText(text).performClick()
    }

    private fun inCard(card: String, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(card))

    // ---- first launch and trips ----

    @Test fun firstLaunchGuidesIntoCreatingATrip() {
        launch()
        waitForText("Welcome to Trip Ledger")
        compose.onAllNodesWithTag("nav_entries").assertCountEquals(0) // nothing else to do yet

        tap("create_first_trip")
        waitForTag("trip_name_field")
        compose.onNodeWithTag("trip_name_field").performTextInput("Rome")
        tap("add_local_currency")
        waitForTag("currency_search")
        compose.onNodeWithTag("currency_search").performTextInput("baht")
        tap("currency_THB")
        waitForTag("rate_THB")
        compose.onNodeWithTag("rate_THB").performTextInput("40")
        tap("save_trip")

        waitForTag("fab_add")
        compose.onNodeWithText("Rome").assertIsDisplayed()
        compose.onNodeWithText("No expenses yet").assertIsDisplayed()
        compose.onNodeWithTag("nav_entries").assertIsDisplayed()

        val data = ref()
        assertEquals("Rome", data.activeTrip!!.name)
        assertEquals("EUR", data.activeTrip!!.homeCurrency)
        assertEquals(listOf("THB" to "40"), data.localCurrencies(data.activeTrip!!.id).map { it.code to it.rate })
    }

    @Test fun aTripNeedsAName() {
        launch()
        tap("create_first_trip")
        waitForTag("trip_name_field")
        tap("save_trip")
        waitForText("Give the trip a name")
        assertTrue(ref().trips.isEmpty())
    }

    @Test fun editSwitchAndDeleteTrips() {
        val lisbon = seedTrip("Lisbon", emptyList())
        val bangkok = seedTrip("Bangkok")
        seedEntry(lisbon, "Sardines", 1850)
        seedEntry(bangkok, "Pad thai", 8000, "THB")
        launch()
        waitForText("Pad thai")

        // Switch to the other trip from the Trips tab.
        tap("nav_trips")
        waitForText("Lisbon")
        compose.onNodeWithText("Lisbon").performClick()
        waitForText("Sardines")
        compose.onAllNodesWithText("Pad thai").assertCountEquals(0)
        assertEquals(lisbon, ref().activeTrip!!.id)

        // Rename it.
        tap("nav_trips")
        waitForTag("trips_list")
        tap("edit_trip_Lisbon")
        waitForTag("trip_name_field")
        compose.onNodeWithTag("trip_name_field").assertTextContains("Lisbon")
        compose.onNodeWithTag("trip_name_field").performTextReplacement("Porto")
        tap("save_trip")
        waitForText("Porto")
        assertEquals("Porto", ref().trips.first { it.id == lisbon }.name)

        // Delete it: its entries go with it and the other trip becomes current.
        tap("edit_trip_Porto")
        tap("delete_trip")
        tap("confirm_button")
        waitGone(hasText("Porto"))
        val after = ref()
        assertEquals(listOf("Bangkok"), after.trips.map { it.name })
        assertEquals(bangkok, after.activeTrip!!.id)
        assertEquals(1, runBlocking { repo.snapshot().entries.size })
    }

    // ---- entries ----

    @Test fun addEditAndDeleteAnEntry() {
        seedTrip()
        launch()
        waitForText("No expenses yet")

        // Add: 400 baht at 40 per euro is 10 euros.
        tap("fab_add")
        waitForTag("amount_field")
        compose.onNodeWithTag("amount_field").performTextInput("400")
        waitForText("≈ €10.00")
        tap("category_Coffee")
        compose.onNodeWithTag("title_field").performScrollTo().performTextInput("Iced latte")
        tap("save_entry")

        waitForText("Iced latte")
        compose.onNodeWithText("฿400.00").assertIsDisplayed()
        compose.onNode(inCard("card_Total", "€10.00")).assertIsDisplayed()
        compose.onNode(inCard("card_Daily average", "€10.00")).assertIsDisplayed()
        compose.onNodeWithTag("subtotal_$today").assertTextContains("€10.00")
        val saved = runBlocking { repo.snapshot().entries.single() }
        assertEquals(40000L, saved.amountMinor)
        assertEquals("THB", saved.currency)
        assertEquals(categoryId("Coffee"), saved.categoryId)

        // Edit: the form opens with the saved values; change the amount.
        compose.onNodeWithText("Iced latte").performClick()
        waitForText("Edit expense")
        waitForTag("amount_field")
        compose.onNodeWithTag("amount_field").assertTextContains("400.00")
        compose.onNodeWithTag("amount_field").performTextReplacement("800")
        tap("save_entry")
        waitFor(inCard("card_Total", "€20.00"))
        compose.onNodeWithText("฿800.00").assertIsDisplayed()
        assertEquals(1, runBlocking { repo.snapshot().entries.size })

        // Delete, behind a confirmation.
        compose.onNodeWithText("Iced latte").performClick()
        tap("delete_entry")
        waitForText("Delete this expense?")
        tap("confirm_button")
        waitForText("No expenses yet")
        compose.onAllNodesWithText("Iced latte").assertCountEquals(0)
        compose.onNode(inCard("card_Total", "€0.00")).assertIsDisplayed()
        assertTrue(runBlocking { repo.snapshot().entries.isEmpty() })
    }

    @Test fun anAmountIsRequiredAndTheTitleIsOptional() {
        seedTrip()
        launch()
        tap("fab_add")
        waitForTag("amount_field")
        tap("save_entry")
        waitForText("Enter an amount")
        compose.onNodeWithTag("amount_field").performTextInput("0")
        tap("save_entry")
        waitForText("Enter an amount greater than zero")
        assertTrue(runBlocking { repo.snapshot().entries.isEmpty() })

        compose.onNodeWithTag("amount_field").performTextReplacement("12,50")
        tap("currency_chip_EUR")
        tap("save_entry")
        waitForTag("entry_row")
        compose.onNode(inCard("card_Total", "€12.50")).assertIsDisplayed()
        val saved = runBlocking { repo.snapshot().entries.single() }
        assertEquals(1250L, saved.amountMinor)
        assertEquals("no title typed, so the category name is used", "Food & Drink", saved.title)
    }

    @Test fun entriesAreGroupedByDayWithSubtotals() {
        val trip = seedTrip()
        seedEntry(trip, "Breakfast", 500)
        seedEntry(trip, "Lunch", 1200)
        seedEntry(trip, "Hotel", 400000, "THB", "Accommodation", date = today - 1)
        launch()
        waitForText("Breakfast")
        compose.onNodeWithTag("subtotal_$today").assertTextContains("€17.00")
        compose.onNodeWithTag("subtotal_${today - 1}").assertTextContains("€100.00")
        compose.onNode(inCard("card_Total", "€117.00")).assertIsDisplayed()
        // The entry dated yesterday widens the period to two days: 117.00 / 2 = 58.50.
        compose.onNode(inCard("card_Daily average", "€58.50")).assertIsDisplayed()
        compose.onNodeWithText("Today · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Yesterday · ", substring = true).assertIsDisplayed()
    }

    @Test fun aCurrencyWithoutARateIsFlaggedAndLeftOutOfTotals() {
        val trip = seedTrip(locals = listOf("THB" to "40", "JPY" to ""))
        seedEntry(trip, "Lunch", 1000)
        seedEntry(trip, "Ramen", 1200, "JPY")
        launch()
        waitForText("Ramen")
        compose.onNodeWithTag("missing_rate_banner").assertIsDisplayed()
        compose.onNodeWithText("No rate").assertIsDisplayed()
        compose.onNodeWithText("¥1,200").assertIsDisplayed()
        compose.onNode(inCard("card_Total", "€10.00")).assertIsDisplayed()

        // Setting the rate from the banner fixes it: 1200 yen at 160 per euro is 7.50.
        compose.onNodeWithText("Set rate").performClick()
        waitForTag("rate_JPY")
        compose.onNodeWithTag("rate_JPY").performScrollTo().performTextInput("160")
        tap("save_trip")
        waitFor(inCard("card_Total", "€17.50"))
        compose.onAllNodesWithTag("missing_rate_banner").assertCountEquals(0)
    }

    @Test fun aVeryLongTitleDoesNotPushTheAmountOffScreen() {
        val trip = seedTrip()
        seedEntry(trip, "An extremely long expense title that just keeps going and going well past the edge of any phone screen ".repeat(3), 123456)
        launch()
        waitForTag("entry_row")
        // The row merges its children for accessibility, so look at the unmerged tree for the amount itself.
        val amount = compose.onNode(hasText("€1,234.56") and hasAnyAncestor(hasTestTag("entry_row")), useUnmergedTree = true)
        amount.assertIsDisplayed()
        val row = compose.onNodeWithTag("entry_row").fetchSemanticsNode().boundsInRoot
        val bounds = amount.fetchSemanticsNode().boundsInRoot
        assertTrue("amount must sit fully inside the row", bounds.right <= row.right && bounds.left > row.left && bounds.width > 0f)
    }

    @Test fun theEntryFormSurvivesBeingRecreated() {
        seedTrip()
        launch()
        tap("fab_add")
        waitForTag("amount_field")
        compose.onNodeWithTag("amount_field").performTextInput("55.5")
        tap("category_Shopping")
        compose.onNodeWithTag("title_field").performScrollTo().performTextInput("Silk scarf")

        scenario!!.recreate() // what a rotation does

        waitForTag("amount_field")
        compose.onNodeWithTag("amount_field").assertTextContains("55.5")
        compose.onNodeWithTag("title_field").performScrollTo().assertTextContains("Silk scarf")
        tap("save_entry")
        waitForText("Silk scarf")
        assertEquals(categoryId("Shopping"), runBlocking { repo.snapshot().entries.single().categoryId })
    }

    // ---- statistics ----

    @Test fun statisticsShowTotalsCategoriesAndBiggestExpenses() {
        val trip = seedTrip()
        seedEntry(trip, "Dinner", 2000, category = "Food & Drink")
        seedEntry(trip, "Hostel", 160000, "THB", "Accommodation") // 40.00
        launch()
        tap("nav_stats")
        waitForTag("stats_total")
        compose.onNode(inCard("stats_total", "€60.00")).assertIsDisplayed()
        compose.onNode(inCard("stats_average", "€60.00")).assertIsDisplayed()
        compose.onNodeWithTag("donut_chart").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("66.7%") and hasAnyAncestor(hasTestTag("category_slice"))).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("33.3%") and hasAnyAncestor(hasTestTag("category_slice"))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("day_chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Biggest expenses").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hostel").performScrollTo().assertIsDisplayed()

        // A custom range that ends before today excludes both expenses.
        compose.onNodeWithTag("stats_content").performTouchInput { swipeDown() } // back to the top
        compose.onNodeWithTag("range_CUSTOM").performScrollTo().performClick()
        tap("stats_to")
        tap("month_prev")
        tap("day_1")
        tap("date_ok")
        waitForText("No expenses in this period")
        compose.onNode(inCard("stats_total", "€0.00")).assertIsDisplayed()

        compose.onNodeWithTag("stats_content").performTouchInput { swipeDown() }
        compose.onNodeWithTag("range_LAST7").performScrollTo().performClick()
        waitFor(inCard("stats_total", "€60.00"))
        compose.onNodeWithTag("stats_period").assertTextContains("7 days", substring = true)
    }

    @Test fun statisticsOfATripWithoutEntriesShowAnEmptyState() {
        seedTrip()
        launch()
        tap("nav_stats")
        waitForText("Nothing to chart yet")
        compose.onNode(inCard("stats_total", "€0.00")).assertIsDisplayed()
    }

    // ---- search ----

    @Test fun searchMatchesTitleNoteAndCategoryWithFilters() {
        val trip = seedTrip()
        seedEntry(trip, "Pad thai", 800, category = "Food & Drink")
        seedEntry(trip, "Taxi to airport", 2500, category = "Transport", note = "late night ride")
        seedEntry(trip, "Riverside hostel", 6000, category = "Accommodation")
        val other = seedTrip("Naples", emptyList())
        seedEntry(other, "Pizza margherita", 900)
        runBlocking { repo.setActiveTrip(trip) }
        launch()
        tap("nav_search")
        waitForText("3 results", substring = true)

        // Live text search over title...
        compose.onNodeWithTag("search_field").performTextInput("taxi")
        waitForText("1 result", substring = true)
        compose.onNodeWithText("Taxi to airport").assertIsDisplayed()
        compose.onAllNodesWithText("Pad thai").assertCountEquals(0)
        // ...note...
        compose.onNodeWithTag("search_field").performTextReplacement("NIGHT")
        waitForText("1 result", substring = true)
        compose.onNodeWithText("Taxi to airport").assertIsDisplayed()
        // ...and category name.
        compose.onNodeWithTag("search_field").performTextReplacement("accommod")
        waitForText("Riverside hostel")
        compose.onAllNodesWithText("Taxi to airport").assertCountEquals(0)

        // Scope: the other trip's entry only shows up under "All trips".
        compose.onNodeWithTag("search_field").performTextReplacement("pizza")
        waitForText("No matches")
        tap("scope_all")
        waitForText("Pizza margherita")
        tap("scope_trip")
        waitForText("No matches")

        // Filters: category, then an amount range.
        compose.onNodeWithTag("search_field").performTextClearance()
        waitForText("3 results", substring = true)
        tap("chip_category")
        tap("filter_category_Transport")
        waitForText("1 result", substring = true)
        tap("sheet_done")
        waitGone(hasTestTag("sheet_done"))
        compose.onNodeWithText("Taxi to airport").assertIsDisplayed()
        compose.onNodeWithTag("chip_category").assertTextContains("Transport")
        tap("chip_category")
        tap("filter_category_Transport")
        tap("sheet_done")
        waitForText("3 results", substring = true)

        tap("chip_amount")
        compose.onNodeWithTag("amount_min").performTextInput("20")
        waitForText("2 results", substring = true)
        compose.onNodeWithTag("amount_max").performTextInput("30")
        waitForText("1 result", substring = true)
        tap("sheet_done")
        waitGone(hasTestTag("sheet_done"))
        compose.onNodeWithTag("chip_amount").assertTextContains("20 – 30")
        tap("clear_filters")
        waitForText("3 results", substring = true)

        // Date range: nothing is dated before the first of last month.
        tap("chip_date")
        tap("search_to")
        tap("month_prev")
        tap("day_1")
        tap("date_ok")
        waitForText("No matches")
        tap("sheet_done")
    }

    // ---- settings ----

    @Test fun changingFormatsTakesEffectOnTheEntriesScreen() {
        val trip = seedTrip()
        seedEntry(trip, "Laptop repair", 123456)
        launch()
        waitFor(inCard("card_Total", "€1,234.56"))

        tap("nav_settings")
        compose.onNodeWithTag("setting_number_format").performScrollTo().performClick()
        tapText("1.234,56")
        compose.onNodeWithTag("setting_currency_display").performScrollTo().performClick()
        tapText("Code (EUR 12.50)")
        compose.onNodeWithTag("setting_date_format").performScrollTo().performClick()
        tapText(LocalDate.now().toString())

        tap("nav_entries")
        waitFor(inCard("card_Total", "EUR 1.234,56"))
        compose.onNodeWithText("Today · ${LocalDate.now()}").assertIsDisplayed()
    }

    @Test fun summaryCardsCanBeTurnedOnAndOff() {
        seedTrip()
        launch()
        waitForTag("card_Total")
        compose.onNodeWithTag("card_Daily average").assertIsDisplayed()
        compose.onAllNodesWithTag("card_Today").assertCountEquals(0)

        tap("nav_settings")
        compose.onNodeWithTag("switch_average").performScrollTo().performClick()
        compose.onNodeWithTag("switch_today").performScrollTo().performClick()
        compose.onNodeWithTag("switch_count").performScrollTo().performClick()

        tap("nav_entries")
        waitForTag("card_Today")
        compose.onNodeWithTag("card_Entries").assertIsDisplayed()
        compose.onNodeWithTag("card_Total").assertIsDisplayed()
        compose.onAllNodesWithTag("card_Daily average").assertCountEquals(0)
    }

    @Test fun themeAndAccentAreSaved() {
        seedTrip()
        launch()
        tap("nav_settings")
        tap("setting_theme")
        tapText("Dark")
        waitFor(hasTestTag("setting_theme") and hasText("Dark"))
        tap("setting_accent")
        tap("accent_Purple")
        waitFor(hasTestTag("setting_accent") and hasText("Purple"))
        val s = ref().settings
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertEquals(0xFF8E24AA.toInt(), s.accent)
    }

    @Test fun defaultsForNewEntriesAreApplied() {
        seedTrip()
        runBlocking {
            repo.setSetting(Keys.DEFAULT_CATEGORY, categoryId("Transport").toString())
            repo.setSetting(Keys.DEFAULT_CURRENCY, "EUR")
        }
        launch()
        tap("fab_add")
        waitForTag("amount_field")
        compose.onNodeWithTag("amount_field").performTextInput("3")
        tap("save_entry")
        waitForTag("entry_row")
        val saved = runBlocking { repo.snapshot().entries.single() }
        assertEquals("EUR", saved.currency)
        assertEquals(categoryId("Transport"), saved.categoryId)
        assertEquals("Transport", saved.title)
    }

    @Test fun categoriesCanBeAddedRenamedAndDeleted() {
        val trip = seedTrip()
        seedEntry(trip, "Flat white", 450, category = "Coffee")
        launch()
        tap("nav_settings")
        compose.onNodeWithTag("setting_categories").performScrollTo().performClick()
        waitForTag("categories_list")

        // Add.
        tap("fab_add_item")
        waitForTag("category_name_field")
        compose.onNodeWithTag("category_name_field").performTextInput("Souvenirs")
        compose.onNodeWithTag("icon_gift").performScrollTo().performClick()
        compose.onNodeWithTag("color_2").performScrollTo().performClick()
        tap("save_category")
        waitGone(hasTestTag("category_name_field"))
        val added = ref().categories.last()
        assertEquals("Souvenirs", added.name)
        assertEquals("gift", added.icon)

        // Rename.
        tapText("Coffee")
        waitForTag("category_name_field")
        compose.onNodeWithTag("category_name_field").performTextReplacement("Café")
        tap("save_category")
        waitForText("Café")
        assertEquals("Café", ref().categoryMap[categoryId("Café")]!!.name)

        // Delete: the entry that used it is moved, not lost.
        tapText("Café")
        tap("delete_category")
        waitForText("1 expense uses this category", substring = true)
        compose.onNode(hasText("Groceries") and hasAnyAncestor(isDialog())).performClick()
        tap("confirm_button")
        waitGone(hasText("Café"))
        val entry = runBlocking { repo.snapshot().entries.single() }
        assertEquals(categoryId("Groceries"), entry.categoryId)
    }

    @Test fun paymentMethodsCanBeAddedAndDeleted() {
        seedTrip()
        launch()
        tap("nav_settings")
        compose.onNodeWithTag("setting_payments").performScrollTo().performClick()
        waitForTag("payments_list")
        tap("fab_add_item")
        waitForTag("name_field")
        compose.onNodeWithTag("name_field").performTextInput("Travel card")
        tap("save_name")
        waitForText("Travel card")
        assertEquals("Travel card", ref().paymentMethods.last().name)

        compose.onNode(hasContentDescription("Delete Cash")).performClick()
        tap("confirm_button")
        waitGone(hasText("Cash"))
        assertNull(ref().paymentMethods.firstOrNull { it.name == "Cash" })
    }

    @Test fun deleteAllDataAsksFirstAndReturnsToTheWelcomeScreen() {
        val trip = seedTrip()
        seedEntry(trip, "Lunch", 1000)
        launch()
        tap("nav_settings")
        compose.onNodeWithTag("setting_delete_all").performScrollTo().performClick()
        waitForText("Delete all data?")
        tapText("Cancel")
        assertEquals("cancelling keeps everything", 1, ref().trips.size)

        compose.onNodeWithTag("setting_delete_all").performScrollTo().performClick()
        tap("confirm_button")
        waitForText("Welcome to Trip Ledger")
        assertTrue(ref().trips.isEmpty())
        assertTrue(runBlocking { repo.snapshot().entries.isEmpty() })
    }

    @Test fun sampleDataCanBeLoadedFromTheWelcomeScreen() {
        launch()
        tap("load_sample")
        waitForText("Thailand")
        waitForTag("entry_row")
        assertEquals(2, ref().trips.size)
    }
}
