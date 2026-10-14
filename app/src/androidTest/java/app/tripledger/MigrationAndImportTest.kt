package app.tripledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tripledger.data.AppDatabase
import app.tripledger.data.BackupException
import app.tripledger.data.Entry
import app.tripledger.data.Keys
import app.tripledger.data.Repository
import app.tripledger.data.Trip
import app.tripledger.domain.NumberStyle
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

/** The version 1 to 2 migration, merge and CSV import, undo, and drag-and-drop ordering. */
@RunWith(AndroidJUnit4::class)
class MigrationAndImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before fun setUp() {
        db = AppDatabase.inMemory(context)
        repo = Repository(db)
    }

    @After fun tearDown() = db.close()

    // ---- migration ----

    @Test fun aVersion1DatabaseIsUpgradedWithItsDataIntact() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        // The schema exactly as version 1 of the app created it.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
            old.execSQL("CREATE TABLE `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startDate` INTEGER NOT NULL, `endDate` INTEGER NOT NULL, `homeCurrency` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE `trip_currencies` (`tripId` INTEGER NOT NULL, `code` TEXT NOT NULL, `rate` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`tripId`, `code`), FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            old.execSQL("CREATE TABLE `entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `title` TEXT NOT NULL, `date` INTEGER NOT NULL, `note` TEXT NOT NULL, `paymentMethodId` INTEGER, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            old.execSQL("CREATE TABLE `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `color` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE `payment_methods` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE `currencies` (`code` TEXT NOT NULL, `name` TEXT NOT NULL, `symbol` TEXT NOT NULL, `decimals` INTEGER NOT NULL, `defaultRate` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`code`))")
            old.execSQL("CREATE TABLE `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            old.execSQL("CREATE INDEX `index_entries_tripId` ON `entries` (`tripId`)")
            old.execSQL("CREATE INDEX `index_entries_date` ON `entries` (`date`)")
            old.execSQL("INSERT INTO trips VALUES (1, 'Old trip', 100, 105, 'EUR', 7)")
            old.execSQL("INSERT INTO trip_currencies VALUES (1, 'THB', '40', 0)")
            old.execSQL("INSERT INTO categories VALUES (3, 'Food', 'restaurant', -1, 0)")
            old.execSQL("INSERT INTO entries VALUES (9, 1, 40000, 'THB', 3, 'Dinner', 101, 'nice', NULL, 55)")
            old.execSQL("INSERT INTO settings VALUES ('seeded', '1')")
            old.version = 1
        }

        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            runBlocking {
                val trip = upgraded.tripDao().get(1)!!
                assertEquals("Old trip", trip.name)
                assertEquals(0L, trip.budgetMinor)
                assertFalse(trip.budgetPerDay)
                val entry = upgraded.entryDao().get(9)!!
                assertEquals(40000L, entry.amountMinor)
                assertEquals("nice", entry.note)
                assertNull(entry.myShareMinor)
                assertEquals(1, entry.spreadDays)
                assertFalse(entry.excludeFromAverage)
                assertEquals("", entry.rateOverride)
                assertEquals("", entry.photo)
                // And the new columns can be written.
                upgraded.entryDao().update(entry.copy(spreadDays = 3, myShareMinor = 20000))
                assertEquals(3, upgraded.entryDao().get(9)!!.spreadDays)
            }
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    // ---- merge import ----

    @Test fun mergingAddsWhatIsMissingAndIsSafeToRepeat() = runBlocking {
        repo.loadSampleData(today = 20000)
        val backup = repo.exportBackup()
        val before = repo.snapshot()

        // Merging a backup of the same data changes nothing.
        val again = repo.mergeBackup(backup)
        assertEquals(0, again.tripsAdded)
        assertEquals(0, again.entriesAdded)
        assertEquals(before.entries.size, again.entriesSkipped)
        assertEquals(before.entries.size, repo.snapshot().entries.size)

        // On a phone with other data, the backup's trips and expenses are added alongside it.
        repo.deleteAllData()
        val own = repo.saveTrip(Trip(name = "My own trip", startDate = 1, endDate = 2, homeCurrency = "EUR"), emptyList())
        repo.saveEntry(Entry(tripId = own, amountMinor = 500, currency = "EUR", categoryId = repo.refData.first().categories[0].id, title = "Mine", date = 1))
        val result = repo.mergeBackup(backup)
        assertEquals(2, result.tripsAdded)
        assertEquals(before.entries.size, result.entriesAdded)
        val after = repo.snapshot()
        assertEquals(3, after.trips.size)
        assertEquals(before.entries.size + 1, after.entries.size)
        // Every merged expense points at an existing category, by name.
        val names = after.categories.associate { it.id to it.name }
        val originalNames = before.categories.associate { it.id to it.name }
        assertEquals(
            before.entries.map { originalNames[it.categoryId] }.sortedBy { it },
            after.entries.filter { it.tripId != own }.map { names[it.categoryId] }.sortedBy { it },
        )
        // Totals of the merged trip are identical to the original.
        val ref = repo.refData.first()
        val thailand = ref.trips.first { it.name == "Thailand" }
        val total = Stats.total(Stats.convert(repo.entriesForTrip(thailand.id).first(), ref.converter(thailand)))
        val originalThai = before.trips.first { it.name == "Thailand" }
        val originalTotal = Stats.total(
            Stats.convert(
                before.entries.filter { it.tripId == originalThai.id },
                app.tripledger.domain.Converter("EUR", mapOf("THB" to java.math.BigDecimal("37.85"), "USD" to java.math.BigDecimal("1.08"))) { 2 },
            ),
        )
        assertEquals(originalTotal, total)
    }

    @Test fun aBadFileMergesNothing() = runBlocking {
        repo.loadSampleData(today = 20000)
        val before = repo.snapshot()
        try {
            repo.mergeBackup("{\"format\":\"nope\"}")
            fail("expected a BackupException")
        } catch (e: BackupException) {
            // expected
        }
        assertEquals(before, repo.snapshot())
    }

    // ---- CSV import ----

    @Test fun aCsvExportImportsBackIntoAnEmptyApp() = runBlocking {
        repo.loadSampleData(today = 20000)
        val ref = repo.refData.first()
        val thai = ref.trips.first { it.name == "Thailand" }
        val totalBefore = Stats.total(Stats.convert(repo.entriesForTrip(thai.id).first(), ref.converter(thai)))
        val countBefore = repo.snapshot().entries.size
        val csv = repo.exportCsv()

        repo.deleteAllData()
        val result = repo.importCsv(csv)
        assertEquals(2, result.tripsAdded)
        assertEquals(countBefore, result.entriesAdded)

        val after = repo.refData.first()
        val thaiAfter = after.trips.first { it.name == "Thailand" }
        assertEquals("EUR", thaiAfter.homeCurrency)
        val entries = repo.entriesForTrip(thaiAfter.id).first()
        assertEquals(totalBefore, Stats.total(Stats.convert(entries, after.converter(thaiAfter))))
        // Shared bills and spreading survive the round trip; rates are recreated on the trip.
        assertNotNull(entries.first { it.title == "Rooftop cocktails" }.myShareMinor)
        assertTrue(entries.first { it.title.startsWith("Flight") }.spreadDays > 1)
        assertEquals(setOf("THB", "USD"), after.localCurrencies(thaiAfter.id).map { it.code }.toSet())
        assertEquals(0, entries.count { it.rateOverride.isNotEmpty() })

        // Importing the same file again adds nothing.
        val again = repo.importCsv(csv)
        assertEquals(0, again.entriesAdded)
        assertEquals(countBefore, again.entriesSkipped)
    }

    @Test fun csvRowsAtADifferentRateKeepTheirOwnRate() = runBlocking {
        val id = repo.saveTrip(Trip(name = "Asia", startDate = 1, endDate = 30, homeCurrency = "EUR"), listOf("THB" to "40"))
        val csv = "Trip,Date,Title,Category,Amount,Currency,Home currency,Exchange rate\n" +
            "Asia,1970-01-05,Cash changed at the airport,Brand new category,320.00,THB,EUR,32\n" +
            "Asia,1970-01-05,At the trip rate,Food & Drink,400.00,THB,EUR,40\n"
        val result = repo.importCsv(csv)
        assertEquals(0, result.tripsAdded)
        assertEquals(2, result.entriesAdded)
        val ref = repo.refData.first()
        val trip = ref.trips.single { it.id == id }
        val entries = repo.entriesForTrip(id).first()
        assertEquals("32", entries.first { it.title.startsWith("Cash") }.rateOverride)
        assertEquals("", entries.first { it.title.startsWith("At the trip") }.rateOverride)
        assertEquals(listOf(1000L, 1000L), Stats.convert(entries, ref.converter(trip)).map { it.homeMinor })
        assertNotNull(ref.categories.firstOrNull { it.name == "Brand new category" })
    }

    @Test fun aBadCsvImportsNothing() = runBlocking {
        repo.ensureSeeded()
        val before = repo.snapshot()
        val csv = "Trip,Date,Title,Category,Amount,Currency\nAsia,2026-01-01,Ok,Food,1,THB\nAsia,yesterday,Bad,Food,1,THB\n"
        try {
            repo.importCsv(csv)
            fail("expected a BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("Line 3"))
        }
        assertEquals(before, repo.snapshot())
    }

    // ---- undo, ordering, locale defaults ----

    @Test fun aDeletedExpenseCanBeRestoredExactly() = runBlocking {
        repo.loadSampleData(today = 20000)
        val entry = repo.snapshot().entries.first { it.myShareMinor != null }
        repo.deleteEntry(entry.id)
        assertNull(repo.getEntry(entry.id))
        assertEquals(entry, repo.recentlyDeleted.value)
        assertTrue(repo.undoDelete())
        assertEquals(entry, repo.getEntry(entry.id))
        assertNull(repo.recentlyDeleted.value)
        assertFalse("nothing left to undo", repo.undoDelete())
    }

    @Test fun undoIsImpossibleOnceTheTripIsGone() = runBlocking {
        repo.loadSampleData(today = 20000)
        val entry = repo.snapshot().entries.first()
        repo.deleteEntry(entry.id)
        repo.deleteTrip(entry.tripId)
        assertFalse(repo.undoDelete())
    }

    @Test fun categoriesAndPaymentMethodsMoveToAnyPosition() = runBlocking {
        val original = repo.refData.first().categories
        repo.moveCategoryTo(original[0].id, 5)
        val moved = repo.refData.first().categories
        assertEquals(original[0].id, moved[5].id)
        assertEquals(original[1].id, moved[0].id)
        repo.moveCategoryTo(original[0].id, 999) // clamped to the end
        assertEquals(original[0].id, repo.refData.first().categories.last().id)

        val methods = repo.refData.first().paymentMethods
        repo.movePaymentMethodTo(methods.last().id, 0)
        assertEquals(methods.last().id, repo.refData.first().paymentMethods.first().id)
    }

    @Test fun numberFormatAndFirstDayStartFromTheRegionButNeverOverrideAChoice() = runBlocking {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("fi", "FI"))
            repo.deleteAllData()
            var s = repo.refData.first().settings
            assertEquals(NumberStyle.SPACE_COMMA, s.numberStyle)
            assertEquals(java.time.DayOfWeek.MONDAY, s.firstDayOfWeek)

            java.util.Locale.setDefault(java.util.Locale.US)
            repo.setSetting(Keys.NUMBER_STYLE, NumberStyle.APOS_DOT.key)
            repo.ensureSeeded()
            s = repo.refData.first().settings
            assertEquals("a choice the user made stays", NumberStyle.APOS_DOT, s.numberStyle)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun restoringABackupKeepsThisPhonesBackupFolder() = runBlocking {
        repo.ensureSeeded()
        repo.setSetting(Keys.AUTO_BACKUP_FOLDER, "content://somewhere/on/this/phone")
        val json = repo.exportBackup().replace("content://somewhere/on/this/phone", "content://another/phone")
        repo.importBackup(json)
        assertEquals("content://somewhere/on/this/phone", repo.refData.first().settings.autoBackupFolder)
    }
}
