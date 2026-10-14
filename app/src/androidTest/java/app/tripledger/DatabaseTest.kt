package app.tripledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tripledger.data.AppDatabase
import app.tripledger.data.BackupException
import app.tripledger.data.Category
import app.tripledger.data.Currency
import app.tripledger.data.Defaults
import app.tripledger.data.Entry
import app.tripledger.data.Keys
import app.tripledger.data.PaymentMethod
import app.tripledger.data.Repository
import app.tripledger.data.Setting
import app.tripledger.data.Trip
import app.tripledger.data.TripCurrency
import app.tripledger.domain.Stats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room DAOs and the repository on top of them, against a real (in-memory) SQLite database. */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = Repository(db)
    }

    @After fun tearDown() = db.close()

    private suspend fun trip(name: String = "Trip", start: Long = 100, home: String = "EUR"): Long =
        db.tripDao().insert(Trip(name = name, startDate = start, endDate = start + 5, homeCurrency = home))

    private fun entry(tripId: Long, amount: Long = 1000, date: Long = 100, title: String = "x", category: Long = 1,
                      currency: String = "EUR", method: Long? = null, createdAt: Long = 0) =
        Entry(tripId = tripId, amountMinor = amount, currency = currency, categoryId = category, title = title,
            date = date, paymentMethodId = method, createdAt = createdAt)

    // ---- TripDao ----

    @Test fun tripsAreStoredAndListedNewestFirst() = runBlocking {
        val a = trip("Old", start = 10)
        val b = trip("New", start = 500)
        assertEquals(listOf("New", "Old"), db.tripDao().getAll().map { it.name })
        assertEquals("Old", db.tripDao().get(a)!!.name)
        db.tripDao().update(db.tripDao().get(b)!!.copy(name = "Renamed"))
        assertEquals("Renamed", db.tripDao().observeAll().first().first().name)
        assertNull(db.tripDao().get(9999))
    }

    @Test fun deletingATripCascadesToItsEntriesAndCurrencies() = runBlocking {
        val keep = trip("Keep")
        val drop = trip("Drop")
        db.tripDao().insertCurrencies(listOf(TripCurrency(keep, "THB", "40"), TripCurrency(drop, "THB", "41"), TripCurrency(drop, "JPY", "")))
        db.entryDao().insertAll(listOf(entry(keep, title = "kept"), entry(drop), entry(drop)))

        db.tripDao().delete(drop)

        assertEquals(listOf("kept"), db.entryDao().getAll().map { it.title })
        assertEquals(listOf(TripCurrency(keep, "THB", "40")), db.tripDao().getAllCurrencies())
    }

    @Test fun tripCurrenciesAreKeyedByTripAndCode() = runBlocking {
        val id = trip()
        db.tripDao().insertCurrencies(listOf(TripCurrency(id, "THB", "40", 1), TripCurrency(id, "JPY", "160", 0)))
        db.tripDao().insertCurrencies(listOf(TripCurrency(id, "THB", "41.5", 1))) // replaces, does not duplicate
        assertEquals(listOf("JPY" to "160", "THB" to "41.5"), db.tripDao().getCurrencies(id).map { it.code to it.rate })
        assertEquals(1, db.tripDao().countWithLocal("THB"))
        assertEquals(1, db.tripDao().countWithHome("EUR"))
    }

    // ---- EntryDao ----

    @Test fun entriesKeepExactIntegerAmounts() = runBlocking {
        val id = trip()
        val huge = 999_999_999_999_999L
        val entryId = db.entryDao().insert(entry(id, amount = huge))
        db.entryDao().insert(entry(id, amount = 1))
        assertEquals(huge, db.entryDao().get(entryId)!!.amountMinor)
        assertEquals(huge + 1, db.entryDao().getAll().sumOf { it.amountMinor })
    }

    @Test fun entriesAreListedPerTripNewestFirst() = runBlocking {
        val a = trip("A")
        val b = trip("B")
        db.entryDao().insertAll(
            listOf(
                entry(a, date = 100, title = "old", createdAt = 5),
                entry(a, date = 102, title = "new-first", createdAt = 1),
                entry(a, date = 102, title = "new-second", createdAt = 2),
                entry(b, date = 300, title = "other trip"),
            ),
        )
        assertEquals(listOf("new-second", "new-first", "old"), db.entryDao().observeForTrip(a).first().map { it.title })
        assertEquals(listOf("other trip"), db.entryDao().observeForTrip(b).first().map { it.title })
        assertEquals(4, db.entryDao().observeAll().first().size)
        assertEquals("other trip", db.entryDao().observeAll().first().first().title)
    }

    @Test fun entryUpdateAndDelete() = runBlocking {
        val id = trip()
        val entryId = db.entryDao().insert(entry(id, title = "before"))
        db.entryDao().update(db.entryDao().get(entryId)!!.copy(title = "after", amountMinor = 5, note = "n"))
        val updated = db.entryDao().get(entryId)!!
        assertEquals("after", updated.title)
        assertEquals(5L, updated.amountMinor)
        assertEquals("n", updated.note)
        db.entryDao().delete(entryId)
        assertNull(db.entryDao().get(entryId))
    }

    @Test fun entryHelperQueries() = runBlocking {
        val id = trip()
        db.entryDao().insertAll(
            listOf(
                entry(id, currency = "THB", category = 1, method = 7, createdAt = 1),
                entry(id, currency = "THB", category = 2, method = 7, createdAt = 2),
                entry(id, currency = "JPY", category = 2, method = 8, createdAt = 3),
            ),
        )
        assertEquals(2, db.entryDao().countForTripCurrency(id, "THB"))
        assertEquals(0, db.entryDao().countForTripCurrency(id, "USD"))
        assertEquals(1, db.entryDao().countForCurrency("JPY"))
        assertEquals(2, db.entryDao().countForCategory(2))
        assertEquals("JPY", db.entryDao().lastCurrency(id))
        assertNull(db.entryDao().lastCurrency(id + 1))

        db.entryDao().reassignCategory(2, 1)
        assertEquals(3, db.entryDao().countForCategory(1))
        db.entryDao().clearPaymentMethod(7)
        assertEquals(listOf(null, null, 8L), db.entryDao().getAll().map { it.paymentMethodId })
    }

    // ---- other DAOs ----

    @Test fun categoriesAndPaymentMethodsFollowSortOrder() = runBlocking {
        db.categoryDao().insertAll(listOf(Category(name = "b", icon = "i", color = 1, sortOrder = 2), Category(name = "a", icon = "i", color = 2, sortOrder = 1)))
        assertEquals(listOf("a", "b"), db.categoryDao().observeAll().first().map { it.name })
        db.paymentMethodDao().insertAll(listOf(PaymentMethod(name = "Card", sortOrder = 1), PaymentMethod(name = "Cash", sortOrder = 0)))
        assertEquals(listOf("Cash", "Card"), db.paymentMethodDao().getAll().map { it.name })
    }

    @Test fun settingsAndCurrenciesUpsert() = runBlocking {
        db.settingDao().put(Setting("k", "1"))
        db.settingDao().put(Setting("k", "2"))
        assertEquals("2", db.settingDao().get("k"))
        assertNull(db.settingDao().get("missing"))
        db.currencyDao().upsert(Currency("XTS", "Test", "T", 2))
        db.currencyDao().upsert(Currency("XTS", "Test renamed", "T", 2, "1.5"))
        assertEquals("Test renamed", db.currencyDao().get("XTS")!!.name)
        assertEquals(1, db.currencyDao().getAll().size)
    }

    // ---- Repository ----

    @Test fun firstReadSeedsDefaultsExactlyOnce() = runBlocking {
        val ref = repo.refData.first()
        assertEquals(Defaults.categories.map { it.name }, ref.categories.map { it.name })
        assertEquals(Defaults.currencies.size, ref.currencies.size)
        assertEquals(Defaults.paymentMethods.size, ref.paymentMethods.size)
        assertTrue(ref.trips.isEmpty())
        assertNull(ref.activeTrip)
        repo.ensureSeeded()
        repo.ensureSeeded()
        assertEquals(Defaults.categories.size, db.categoryDao().getAll().size)
    }

    @Test fun saveTripReplacesLocalCurrenciesAndDropsTheHomeCurrency() = runBlocking {
        val id = repo.saveTrip(Trip(name = "T", startDate = 1, endDate = 2, homeCurrency = "EUR"), listOf("THB" to " 40 ", "EUR" to "1", "THB" to "99"))
        assertEquals(listOf(TripCurrency(id, "THB", "40", 0)), repo.getTripCurrencies(id))
        assertTrue(repo.getTrip(id)!!.createdAt > 0)

        repo.saveTrip(repo.getTrip(id)!!.copy(name = "T2"), listOf("JPY" to "", "USD" to "1.1"))
        assertEquals("T2", repo.getTrip(id)!!.name)
        assertEquals(listOf("JPY", "USD"), repo.getTripCurrencies(id).map { it.code })
    }

    @Test fun activeTripFallsBackWhenTheSelectedOneIsDeleted() = runBlocking {
        val first = repo.saveTrip(Trip(name = "First", startDate = 1, endDate = 2, homeCurrency = "EUR"), emptyList())
        val second = repo.saveTrip(Trip(name = "Second", startDate = 50, endDate = 60, homeCurrency = "EUR"), emptyList())
        repo.setActiveTrip(first)
        assertEquals("First", repo.refData.first().activeTrip!!.name)
        repo.deleteTrip(first)
        assertEquals(second, repo.refData.first().activeTrip!!.id)
        repo.deleteTrip(second)
        assertNull(repo.refData.first().activeTrip)
    }

    @Test fun saveEntryInsertsThenUpdates() = runBlocking {
        val tripId = trip()
        val id = repo.saveEntry(entry(tripId, title = "a"))
        val created = repo.getEntry(id)!!.createdAt
        assertTrue(created > 0)
        assertEquals(id, repo.saveEntry(repo.getEntry(id)!!.copy(title = "b")))
        assertEquals("b", repo.getEntry(id)!!.title)
        assertEquals(created, repo.getEntry(id)!!.createdAt)
        assertEquals(1, db.entryDao().getAll().size)
        repo.deleteEntry(id)
        assertNull(repo.getEntry(id))
    }

    @Test fun deletingACategoryMovesItsEntriesAndResetsTheDefault() = runBlocking {
        val ref = repo.refData.first()
        val (first, second) = ref.categories
        val tripId = trip()
        repo.saveEntry(entry(tripId, category = first.id))
        repo.saveEntry(entry(tripId, category = first.id))
        repo.setSetting(Keys.DEFAULT_CATEGORY, first.id.toString())
        assertEquals(2, repo.countEntriesInCategory(first.id))

        assertFalse("cannot move entries to itself", repo.deleteCategory(first.id, first.id))
        assertFalse("target must exist", repo.deleteCategory(first.id, 987654))
        assertTrue(repo.deleteCategory(first.id, second.id))

        assertEquals(2, repo.countEntriesInCategory(second.id))
        val after = repo.refData.first()
        assertNull(after.categoryMap[first.id])
        assertEquals(0L, after.settings.defaultCategoryId)
    }

    @Test fun theLastCategoryCannotBeDeleted() = runBlocking {
        val categories = repo.refData.first().categories
        categories.drop(1).forEach { assertTrue(repo.deleteCategory(it.id, categories[0].id)) }
        assertFalse(repo.deleteCategory(categories[0].id, categories[0].id))
        assertEquals(1, db.categoryDao().getAll().size)
    }

    @Test fun categoriesCanBeAddedRenamedAndReordered() = runBlocking {
        val before = repo.refData.first().categories
        val id = repo.saveCategory(Category(name = "Souvenirs", icon = "gift", color = 5))
        assertEquals("Souvenirs", repo.refData.first().categories.last().name)

        repo.moveCategory(id, -1)
        val moved = repo.refData.first().categories
        assertEquals(id, moved[moved.size - 2].id)
        repeat(50) { repo.moveCategory(id, -1) } // stops quietly at the top
        assertEquals(id, repo.refData.first().categories.first().id)
        repo.moveCategory(id, 1)
        assertEquals(before[0].id, repo.refData.first().categories.first().id)

        repo.saveCategory(repo.refData.first().categoryMap[id]!!.copy(name = "Gifts", color = 9))
        val renamed = repo.refData.first().categoryMap[id]!!
        assertEquals("Gifts", renamed.name)
        assertEquals(9, renamed.color)
        assertEquals(before.size + 1, repo.refData.first().categories.size)
    }

    @Test fun deletingAPaymentMethodKeepsEntriesWithoutIt() = runBlocking {
        val methods = repo.refData.first().paymentMethods
        val tripId = trip()
        val id = repo.saveEntry(entry(tripId, method = methods[0].id))
        repo.setSetting(Keys.DEFAULT_PAYMENT, methods[0].id.toString())
        repo.deletePaymentMethod(methods[0].id)
        assertNull(repo.getEntry(id)!!.paymentMethodId)
        assertEquals(0L, repo.refData.first().settings.defaultPaymentMethodId)
        assertEquals(methods.size - 1, repo.refData.first().paymentMethods.size)

        val added = repo.savePaymentMethod(PaymentMethod(name = "Revolut"))
        repo.movePaymentMethod(added, -10) // out of range: ignored
        repo.movePaymentMethod(added, -1)
        val names = repo.refData.first().paymentMethods.map { it.name }
        assertEquals("Revolut", names[names.size - 2])
    }

    @Test fun currenciesInUseCannotBeDeletedAndDecimalsStayFixed() = runBlocking {
        repo.refData.first()
        repo.setSetting(Keys.HOME_CURRENCY, "EUR")
        val tripId = repo.saveTrip(Trip(name = "T", startDate = 1, endDate = 2, homeCurrency = "USD"), listOf("THB" to "36"))
        repo.saveEntry(entry(tripId, currency = "JPY"))

        assertFalse("settings home currency", repo.deleteCurrency("EUR"))
        assertFalse("trip home currency", repo.deleteCurrency("USD"))
        assertFalse("trip local currency", repo.deleteCurrency("THB"))
        assertFalse("used by an entry", repo.deleteCurrency("JPY"))
        assertTrue(repo.deleteCurrency("ZAR"))
        assertNull(db.currencyDao().get("ZAR"))

        repo.saveCurrency(Currency("JPY", "Yen", "¥", decimals = 2, defaultRate = "160"))
        val jpy = db.currencyDao().get("JPY")!!
        assertEquals("existing amounts must not be reinterpreted", 0, jpy.decimals)
        assertEquals("160", jpy.defaultRate)

        repo.saveCurrency(Currency("BTN", "Ngultrum", "Nu", 2))
        assertNotNull(db.currencyDao().get("BTN"))
        assertEquals("BTN", repo.refData.first().currencies.last().code)
    }

    @Test fun refDataConvertsWithTheTripRates() = runBlocking {
        val id = repo.saveTrip(Trip(name = "T", startDate = 1, endDate = 2, homeCurrency = "EUR"), listOf("THB" to "37.85", "JPY" to "", "KWD" to "0"))
        val ref = repo.refData.first()
        val converter = ref.converter(ref.trips.single())
        assertEquals(1189L, converter.toHome(45000, "THB"))
        assertNull("blank rate", converter.toHome(1000, "JPY"))
        assertNull("zero rate", converter.toHome(1000, "KWD"))
        assertEquals("€11.89", ref.money(1189, "EUR"))
        assertEquals("¥1,000", ref.money(1000, "JPY"))
        assertEquals(3, ref.localCurrencies(id).size)
    }

    @Test fun backupRoundTripRestoresEverything() = runBlocking {
        repo.loadSampleData(today = 20000)
        repo.setSetting(Keys.THEME, "dark")
        repo.saveCategory(Category(name = "Custom", icon = "star", color = 123))
        val before = repo.snapshot()
        val json = repo.exportBackup()

        repo.deleteAllData()
        assertTrue(repo.snapshot().trips.isEmpty())
        assertTrue(repo.snapshot().entries.isEmpty())

        repo.importBackup(json)
        assertEquals(before, repo.snapshot())
        assertEquals("Thailand", repo.refData.first().activeTrip!!.name)
    }

    @Test fun aBadBackupLeavesExistingDataUntouched() = runBlocking {
        repo.loadSampleData(today = 20000)
        val before = repo.snapshot()
        for (bad in listOf("", "{}", "garbage", repo.exportBackup().dropLast(40))) {
            try {
                repo.importBackup(bad)
                fail("expected the import to be rejected")
            } catch (e: BackupException) {
                // expected
            }
        }
        assertEquals(before, repo.snapshot())
    }

    @Test fun deleteAllDataReturnsToAFreshInstall() = runBlocking {
        repo.loadSampleData(today = 20000)
        repo.setSetting(Keys.THEME, "dark")
        repo.deleteAllData()
        val ref = repo.refData.first()
        assertTrue(ref.trips.isEmpty())
        assertTrue(db.entryDao().getAll().isEmpty())
        assertTrue(db.tripDao().getAllCurrencies().isEmpty())
        assertEquals(Defaults.categories.size, ref.categories.size)
        assertEquals(app.tripledger.domain.ThemeMode.SYSTEM, ref.settings.themeMode)
    }

    @Test fun sampleDataLoadsAndAddsUp() = runBlocking {
        repo.loadSampleData(today = 20000)
        val ref = repo.refData.first()
        assertEquals(2, ref.trips.size)
        val thailand = ref.activeTrip!!
        assertEquals("Thailand", thailand.name)
        val converted = Stats.convert(repo.entriesForTrip(thailand.id).first(), ref.converter(thailand))
        assertTrue(converted.size > 20)
        assertEquals("every sample entry has a usable rate", 0, Stats.unconvertedCount(converted))
        assertEquals(Stats.total(converted), Stats.groupByDay(converted).sumOf { it.subtotal })
        assertEquals(Stats.total(converted), Stats.byCategory(converted).sumOf { it.total })
        val csv = repo.exportCsv()
        assertEquals(1 + db.entryDao().getAll().size, csv.trim().split("\r\n").size)
    }
}
