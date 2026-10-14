package app.tripledger

import android.content.Intent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tripledger.data.Entry
import app.tripledger.data.Keys
import app.tripledger.data.Repository
import app.tripledger.data.Trip
import app.tripledger.domain.Dates
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

/** UI tests for the second round of features: budgets, shared bills, undo, discard, duplicate, shortcut. */
@RunWith(AndroidJUnit4::class)
class NewFeaturesFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var repo: Repository
    private var scenario: ActivityScenario<MainActivity>? = null
    private val today = Dates.today()

    @Before fun reset() {
        repo = ApplicationProvider.getApplicationContext<TripLedgerApp>().repository
        runBlocking {
            repo.deleteAllData()
            repo.setSetting(Keys.HOME_CURRENCY, "EUR")
            repo.setSetting(Keys.NUMBER_STYLE, "comma_dot")
            repo.setSetting(Keys.FIRST_DAY, "MONDAY")
        }
    }

    @After fun close() {
        scenario?.close()
    }

    private fun launch(intent: Intent? = null) {
        scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
    }

    private fun entries(): List<Entry> = runBlocking { repo.snapshot().entries }

    private fun categoryId(name: String): Long = runBlocking { repo.refData.first().categories.first { it.name == name }.id }

    private fun seedTrip(budget: Long = 0, perDay: Boolean = false): Long = runBlocking {
        val id = repo.saveTrip(
            Trip(name = "Bangkok", startDate = today, endDate = today + 9, homeCurrency = "EUR", budgetMinor = budget, budgetPerDay = perDay),
            listOf("THB" to "40"),
        )
        repo.setActiveTrip(id)
        id
    }

    private fun seedEntry(tripId: Long, title: String, amount: Long, currency: String = "EUR"): Long = runBlocking {
        repo.saveEntry(Entry(tripId = tripId, amountMinor = amount, currency = currency, categoryId = categoryId("Food & Drink"), title = title, date = today))
    }

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGone(matcher: SemanticsMatcher, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    }

    private fun waitForText(text: String, substring: Boolean = false) = waitFor(hasText(text, substring = substring))

    private fun tap(tag: String) {
        waitFor(hasTestTag(tag))
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun inCard(card: String, text: String) = hasText(text) and hasAnyAncestor(hasTestTag(card))

    @Test fun theQuickCategoryRowSetsTheCategory() {
        seedTrip()
        launch()
        tap("fab_add")
        waitFor(hasTestTag("amount_field"))
        compose.onNodeWithTag("amount_field").performTextInput("5")
        // The row scrolls sideways; Transport is not in view at first.
        compose.onNodeWithTag("quick_categories").performScrollToNode(hasTestTag("quick_category_Transport"))
        tap("quick_category_Transport")
        tap("save_entry")
        waitFor(hasTestTag("entry_row"))
        assertEquals(categoryId("Transport"), entries().single().categoryId)
    }

    @Test fun theMostUsedCategoryComesFirstInTheQuickRow() {
        val trip = seedTrip()
        runBlocking {
            repeat(3) { repo.saveEntry(Entry(tripId = trip, amountMinor = 100, currency = "EUR", categoryId = categoryId("Shopping"), title = "x$it", date = today)) }
        }
        launch()
        tap("fab_add")
        waitFor(hasTestTag("quick_category_Shopping"))
        val shopping = compose.onNodeWithTag("quick_category_Shopping").fetchSemanticsNode().boundsInRoot
        val food = compose.onNodeWithTag("quick_category_Food & Drink").fetchSemanticsNode().boundsInRoot
        assertTrue("Shopping (used 3 times) should come before Food & Drink", shopping.left < food.left)
    }

    @Test fun aSharedSpreadBillWithItsOwnRate() {
        seedTrip()
        launch()
        tap("fab_add")
        waitFor(hasTestTag("amount_field"))
        // 800 baht for a shared villa: my half, at an exchange counter's 32, spread over 4 days.
        compose.onNodeWithTag("amount_field").performTextInput("800")
        tap("category_Accommodation")
        compose.onNodeWithTag("title_field").performScrollTo().performTextInput("Villa")
        tap("more_options")
        tap("split_2")
        waitFor(hasTestTag("share_field") and hasText("400.00", substring = true))
        compose.onNodeWithTag("spread_field").performScrollTo().performTextInput("4")
        compose.onNodeWithTag("entry_rate_field").performScrollTo().performTextInput("32")
        tap("exclude_switch")
        tap("save_entry")

        waitForText("Villa")
        val saved = entries().single()
        assertEquals(80000L, saved.amountMinor)
        assertEquals(40000L, saved.myShareMinor)
        assertEquals(4, saved.spreadDays)
        assertEquals("32", saved.rateOverride)
        assertTrue(saved.excludeFromAverage)
        // 400.00 THB at 32 is 12.50 EUR; left out of the average, so that stays at zero.
        waitFor(inCard("card_Total", "€12.50"))
        compose.onNode(inCard("card_Daily average", "€0.00")).assertIsDisplayed()
        compose.onNodeWithText("of ฿800.00").assertIsDisplayed()
        compose.onNodeWithText("Shared", substring = true).assertIsDisplayed()
    }

    @Test fun aRefundIsSubtracted() {
        val trip = seedTrip()
        seedEntry(trip, "Hotel", 10000)
        launch()
        tap("fab_add")
        waitFor(hasTestTag("amount_field"))
        compose.onNodeWithTag("amount_field").performTextInput("25")
        tap("currency_chip_EUR")
        compose.onNodeWithTag("title_field").performScrollTo().performTextInput("Deposit back")
        tap("more_options")
        tap("refund_switch")
        tap("save_entry")
        waitForText("Deposit back")
        assertEquals(-2500L, entries().first { it.title == "Deposit back" }.amountMinor)
        waitFor(inCard("card_Total", "€75.00"))
        compose.onNodeWithText("-€25.00").assertIsDisplayed()
    }

    @Test fun aShareLargerThanTheAmountIsRefused() {
        seedTrip()
        launch()
        tap("fab_add")
        waitFor(hasTestTag("amount_field"))
        compose.onNodeWithTag("amount_field").performTextInput("10")
        tap("more_options")
        compose.onNodeWithTag("share_field").performScrollTo().performTextInput("20")
        tap("save_entry")
        waitForText("Your share must be more than zero and no more than the amount")
        assertTrue(entries().isEmpty())
    }

    @Test fun aBudgetSetOnTheTripShowsOnTheEntriesScreen() {
        val trip = seedTrip()
        seedEntry(trip, "Dinner", 3000)
        launch()
        waitForText("Dinner")
        compose.onAllNodesWithTag("card_Budget left").assertCountEquals(0)

        compose.onNodeWithTag("trip_switcher").performClick()
        tap("edit_trip_Bangkok")
        compose.onNodeWithTag("budget_field").performScrollTo().performTextInput("50")
        tap("budget_per_day")
        tap("save_trip")
        waitFor(hasTestTag("trips_list"))
        compose.onNodeWithText("Bangkok").performClick()

        // 50 a day for 10 days is 500; 30 spent today leaves 470 and 20.
        waitFor(inCard("card_Budget left", "€470.00"))
        compose.onNode(inCard("card_Left today", "€20.00")).assertIsDisplayed()
        val saved = runBlocking { repo.getTrip(trip)!! }
        assertEquals(5000L, saved.budgetMinor)
        assertTrue(saved.budgetPerDay)
    }

    @Test fun goingOverBudgetIsShownAsAWarning() {
        val trip = seedTrip(budget = 2000)
        seedEntry(trip, "Splurge", 3000)
        launch()
        waitFor(inCard("card_Over budget", "€10.00"))
    }

    @Test fun leavingAnEditedFormAsksFirst() {
        seedTrip()
        launch()
        tap("fab_add")
        waitFor(hasTestTag("amount_field"))
        // Untouched: closing just closes.
        tap("close_entry")
        waitFor(hasTestTag("fab_add"))

        tap("fab_add")
        waitFor(hasTestTag("amount_field"))
        compose.onNodeWithTag("amount_field").performTextInput("12")
        tap("close_entry")
        waitForText("Discard this expense?")
        tap("keep_editing")
        compose.onNodeWithTag("amount_field").assertTextContains("12")
        // The system Back gesture asks too.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("Discard this expense?")
        tap("discard_button")
        waitFor(hasTestTag("fab_add"))
        assertTrue(entries().isEmpty())
    }

    @Test fun aDeletedExpenseCanBeUndone() {
        val trip = seedTrip()
        seedEntry(trip, "Museum", 1500)
        launch()
        compose.onNodeWithText("Museum").let { waitForText("Museum"); it.performClick() }
        tap("delete_entry")
        tap("confirm_button")
        waitForText("\"Museum\" deleted")
        assertTrue(entries().isEmpty())
        compose.onNodeWithText("Undo").performClick()
        waitForText("Museum")
        assertEquals(listOf("Museum"), entries().map { it.title })
        assertNull(repo.recentlyDeleted.value)
    }

    @Test fun anExpenseCanBeDuplicated() {
        val trip = seedTrip()
        runBlocking {
            repo.saveEntry(Entry(tripId = trip, amountMinor = 6000, currency = "THB", categoryId = categoryId("Coffee"), title = "Iced latte", date = today - 2, myShareMinor = 3000))
        }
        launch()
        waitForText("Iced latte")
        compose.onNodeWithText("Iced latte").performClick()
        tap("duplicate_entry")
        waitForText("Add expense")
        compose.onNodeWithTag("amount_field").assertTextContains("60.00")
        tap("save_entry")
        waitFor(hasTestTag("fab_add"))
        val copies = entries().filter { it.title == "Iced latte" }
        assertEquals(2, copies.size)
        val copy = copies.maxBy { it.id }
        assertEquals("a copy is dated today", today, copy.date)
        assertEquals(3000L, copy.myShareMinor)
        assertEquals(categoryId("Coffee"), copy.categoryId)
    }

    @Test fun theAddExpenseShortcutOpensTheForm() {
        seedTrip()
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(MainActivity.ACTION_ADD_EXPENSE)
        launch(intent)
        waitFor(hasTestTag("amount_field"))
        compose.onNodeWithText("Add expense").assertIsDisplayed()
    }

    @Test fun theShortcutWithoutATripShowsTheWelcomeScreen() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(MainActivity.ACTION_ADD_EXPENSE)
        launch(intent)
        waitForText("Welcome to Trip Ledger")
        compose.onAllNodesWithTag("amount_field").assertCountEquals(0)
    }

    @Test fun statisticsShowTheNewBreakdowns() {
        val trip = seedTrip(budget = 100000)
        seedEntry(trip, "Dinner", 2000)
        seedEntry(trip, "Hostel", 160000, "THB")
        runBlocking { repo.saveTrip(Trip(name = "Earlier trip", startDate = today - 90, endDate = today - 85, homeCurrency = "EUR"), emptyList()) }
        runBlocking { repo.setActiveTrip(trip) }
        launch()
        waitFor(hasTestTag("nav_stats"))
        compose.onNodeWithTag("nav_stats").performClick()
        waitFor(hasTestTag("stats_total"))
        compose.onNodeWithTag("cumulative_chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("€60.00 of €1,000.00 budget").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("by_payment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No payment method").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("by_currency").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Paid ฿1,600.00").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("trip_comparison").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Earlier trip").performScrollTo().assertIsDisplayed()
    }

    @Test fun datesAreEnglishWhateverTheLanguage() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("fi", "FI"))
            seedTrip()
            launch()
            waitFor(hasTestTag("trip_switcher"))
            val english = java.time.LocalDate.ofEpochDay(today).format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy", java.util.Locale.ENGLISH))
            compose.onNodeWithText(english, substring = true).assertIsDisplayed()
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
