package app.tripledger

import app.tripledger.data.AppSettings
import app.tripledger.data.Backup
import app.tripledger.data.BackupData
import app.tripledger.data.BackupException
import app.tripledger.data.Category
import app.tripledger.data.Csv
import app.tripledger.data.Currency
import app.tripledger.data.Defaults
import app.tripledger.data.Entry
import app.tripledger.data.Keys
import app.tripledger.data.PaymentMethod
import app.tripledger.data.SampleData
import app.tripledger.data.Trip
import app.tripledger.data.TripCurrency
import app.tripledger.domain.NumberStyle
import app.tripledger.domain.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class BackupTest {
    private val day = LocalDate.of(2026, 10, 14).toEpochDay()

    private val data = BackupData(
        settings = mapOf(Keys.THEME to "dark", Keys.ACTIVE_TRIP to "7", Keys.SEEDED to "1"),
        currencies = listOf(Currency("EUR", "Euro", "€", 2, "", 0), Currency("THB", "Thai Baht", "฿", 2, "37.85", 1), Currency("JPY", "Yen", "¥", 0, "", 2)),
        categories = listOf(Category(1, "Food & Drink", "restaurant", 0xFFF4511E.toInt(), 0), Category(2, "Stay", "hotel", -123456, 1)),
        paymentMethods = listOf(PaymentMethod(1, "Cash", 0), PaymentMethod(2, "Card", 1)),
        trips = listOf(Trip(7, "Thailand \"2026\"", day - 3, day + 4, "EUR", 1000, budgetMinor = 150000, budgetPerDay = true)),
        tripCurrencies = listOf(TripCurrency(7, "THB", "37.85", 0), TripCurrency(7, "JPY", "", 1)),
        entries = listOf(
            Entry(1, 7, 45000, "THB", 1, "Pad thai, extra spicy", day, "line one\nline two", 1, 5),
            Entry(2, 7, 2500, "EUR", 2, "=SUM(A1)", day - 1, "", null, 6),
            Entry(3, 7, 3000, "JPY", 1, "Ramen", day - 2, "", 2, 7),
            // A shared bill, spread over days, with its own rate and a photo; and a refund.
            Entry(4, 7, 80000, "THB", 2, "Villa", day - 3, "", 1, 8, myShareMinor = 40000, spreadDays = 4,
                excludeFromAverage = true, rateOverride = "40", photo = "receipt-a.jpg"),
            Entry(5, 7, -1500, "EUR", 1, "Deposit back", day - 3, "", null, 9),
        ),
    )

    @Test fun backupRoundTripsEverything() {
        val restored = Backup.fromJson(Backup.toJson(data, "2026-10-14T10:00:00"))
        assertEquals(data, restored)
    }

    @Test fun backupKeepsAmountsAsIntegers() {
        val json = Backup.toJson(data)
        assertTrue(json.contains("\"amountMinor\": 45000"))
        assertTrue("no floating point amounts in the file", !json.contains("450.0"))
    }

    @Test fun importRejectsOtherFiles() {
        for (bad in listOf("", "not json", "{}", "[1,2]", "{\"format\":\"something-else\",\"version\":1}")) {
            try {
                Backup.fromJson(bad)
                fail("expected a BackupException for: $bad")
            } catch (e: BackupException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test fun importRejectsNewerFormatAndTruncatedFiles() {
        val json = Backup.toJson(data)
        try {
            Backup.fromJson(json.replace("\"version\": ${Backup.VERSION}", "\"version\": 99"))
            fail("expected a BackupException")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("newer"))
        }
        try {
            Backup.fromJson(json.substring(0, json.length / 2))
            fail("expected a BackupException")
        } catch (e: BackupException) {
            // expected
        }
    }

    @Test fun importDropsRowsThatPointAtMissingTrips() {
        val orphaned = data.copy(
            entries = data.entries + Entry(99, 999, 100, "EUR", 1, "orphan", day),
            tripCurrencies = data.tripCurrencies + TripCurrency(999, "USD", "1.1", 0),
        )
        val restored = Backup.fromJson(Backup.toJson(orphaned))
        assertEquals(data.entries, restored.entries)
        assertEquals(data.tripCurrencies, restored.tripCurrencies)
    }

    @Test fun importClearsPaymentMethodsThatNoLongerExist() {
        val dangling = data.copy(entries = listOf(data.entries[0].copy(paymentMethodId = 42)))
        assertNull(Backup.fromJson(Backup.toJson(dangling)).entries.single().paymentMethodId)
    }

    // ---- CSV ----

    @Test fun csvHasHeaderAndOneRowPerEntry() {
        val lines = Csv.build(data.copy(entries = listOf(data.entries[2]))).trimEnd().split("\r\n")
        assertEquals(2, lines.size)
        assertEquals(Csv.HEADER.joinToString(","), lines[0])
        assertEquals(Csv.HEADER.size, lines[1].split(",").size)
    }

    @Test fun csvConvertsAndEscapes() {
        val csv = Csv.build(data)
        // Oldest first. The yen entry has no rate: its home amount and rate columns are empty.
        val expected = listOf(
            Csv.HEADER.joinToString(","),
            // The shared villa: my half (400.00 THB) at the expense's own rate of 40 is 10.00 EUR.
            "\"Thailand \"\"2026\"\"\",2026-10-11,Villa,Stay,800.00,THB,10.00,EUR,40,Cash,,400.00,4,yes",
            "\"Thailand \"\"2026\"\"\",2026-10-11,Deposit back,Food & Drink,-15.00,EUR,-15.00,EUR,1,,,,1,no",
            "\"Thailand \"\"2026\"\"\",2026-10-12,Ramen,Food & Drink,3000,JPY,,EUR,,Card,,,1,no",
            "\"Thailand \"\"2026\"\"\",2026-10-13,'=SUM(A1),Stay,25.00,EUR,25.00,EUR,1,,,,1,no",
            "\"Thailand \"\"2026\"\"\",2026-10-14,\"Pad thai, extra spicy\",Food & Drink,450.00,THB,11.89,EUR,37.85,Cash,\"line one\nline two\",,1,no",
        ).joinToString("\r\n") + "\r\n"
        assertEquals(expected, csv)
    }

    @Test fun csvReadsBackWhatItWrote() {
        val rows = Csv.read("\uFEFF" + Csv.build(data))
        assertEquals(data.entries.size, rows.size)
        val villa = rows.first { it.title == "Villa" }
        assertEquals("Thailand \"2026\"", villa.trip)
        assertEquals(day - 3, villa.date)
        assertEquals("800.00", villa.amount)
        assertEquals("400.00", villa.myShare)
        assertEquals("40", villa.rate)
        assertEquals(4, villa.spreadDays)
        assertTrue(villa.excludeFromAverage)
        assertEquals("EUR", villa.homeCurrency)
        // The formula guard is taken off again, and a note with a line break survives.
        assertEquals("=SUM(A1)", rows.first { it.date == day - 1 }.title)
        assertEquals("line one\nline two", rows.first { it.title.startsWith("Pad thai") }.note)
        assertEquals("-15.00", rows.first { it.title == "Deposit back" }.amount)
        assertEquals("", rows.first { it.title == "Ramen" }.rate)
    }

    @Test fun csvParserHandlesQuotesAndLineEndings() {
        assertEquals(
            listOf(listOf("a", "b,c", "say \"hi\""), listOf("1", "", "two\nlines"), listOf("last")),
            Csv.parse("a,\"b,c\",\"say \"\"hi\"\"\"\n1,,\"two\nlines\"\r\n\r\nlast"),
        )
        assertTrue(Csv.parse("").isEmpty())
    }

    @Test fun csvImportFindsColumnsByNameAndExplainsBadFiles() {
        // Columns in another order, with an extra one, and only the required ones present.
        val rows = Csv.read("Currency,Extra,Amount,Title,Date,Category,Trip\nthb,x,\"1,5\",Tea,2026-10-14,Coffee,Asia\n")
        assertEquals("THB", rows.single().currency)
        assertEquals("1,5", rows.single().amount)
        assertEquals(1, rows.single().spreadDays)
        assertEquals("", rows.single().paymentMethod)

        fun failure(text: String): String = try {
            Csv.read(text)
            fail("expected a BackupException")
            ""
        } catch (e: BackupException) {
            e.message!!
        }
        assertTrue(failure("").contains("empty"))
        assertTrue(failure("Name,Price\nTea,1").contains("\"Trip\" column"))
        val header = "Trip,Date,Title,Category,Amount,Currency\n"
        assertTrue(failure(header + "Asia,14.10.2026,Tea,Coffee,1,THB").contains("Line 2"))
        assertTrue(failure(header + "Asia,2026-10-14,Tea,Coffee,1,THB\nAsia,2026-10-14,Tea,Coffee,abc,THB").contains("Line 3"))
        assertTrue(failure(header + "Asia,2026-10-14,Tea,Coffee,1,baht!").contains("currency"))
        assertTrue(failure(header + ",2026-10-14,Tea,Coffee,1,THB").contains("trip name"))
        assertTrue(failure(header + "Asia,2026-10-14,\"Tea,Coffee,1,THB").contains("quoted"))
    }

    @Test fun olderBackupsWithoutTheNewFieldsStillLoad() {
        val v1 = """
            {"format":"tripledger-backup","version":1,"settings":{"theme":"dark"},
             "currencies":[{"code":"EUR","name":"Euro","symbol":"€","decimals":2}],
             "categories":[{"id":1,"name":"Food","icon":"restaurant","color":-1}],
             "paymentMethods":[],
             "trips":[{"id":3,"name":"Old trip","startDate":10,"endDate":12,"homeCurrency":"EUR"}],
             "tripCurrencies":[],
             "entries":[{"id":1,"tripId":3,"amountMinor":1250,"currency":"EUR","categoryId":1,"title":"Lunch","date":11}]}
        """.trimIndent()
        val restored = Backup.fromJson(v1)
        assertEquals(0L, restored.trips.single().budgetMinor)
        val entry = restored.entries.single()
        assertEquals(1250L, entry.amountMinor)
        assertNull(entry.myShareMinor)
        assertEquals(1, entry.spreadDays)
        assertEquals(false, entry.excludeFromAverage)
        assertEquals("", entry.rateOverride)
        assertEquals("", entry.photo)
        assertEquals(1250L, entry.effectiveMinor)
    }

    @Test fun importResultReadsNaturally() {
        assertEquals("1 expense added", app.tripledger.data.ImportResult(0, 1, 0).describe())
        assertEquals("12 expenses added in 2 new trips", app.tripledger.data.ImportResult(2, 12, 0).describe())
        assertEquals("0 expenses added, 5 already there", app.tripledger.data.ImportResult(0, 0, 5).describe())
    }

    @Test fun csvEscapeRules() {
        assertEquals("plain", Csv.escape("plain"))
        assertEquals("\"a,b\"", Csv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
        assertEquals("'=1+1", Csv.escape("=1+1"))
        assertEquals("'-5", Csv.escape("-5"))
        assertEquals("'@cmd", Csv.escape("@cmd"))
        assertEquals("", Csv.escape(""))
    }

    @Test fun csvOfNoEntriesIsJustTheHeader() {
        assertEquals(Csv.HEADER.joinToString(",") + "\r\n", Csv.build(data.copy(entries = emptyList())))
    }

    // ---- settings and defaults ----

    @Test fun settingsParseWithDefaultsForMissingOrBadValues() {
        val defaults = AppSettings.fromMap(emptyMap())
        assertEquals(AppSettings(), defaults)
        val parsed = AppSettings.fromMap(
            mapOf(
                Keys.THEME to "dark", Keys.NUMBER_STYLE to "dot_comma", Keys.FIRST_DAY to "SUNDAY",
                Keys.SHOW_TOTAL to "0", Keys.SHOW_TODAY to "1", Keys.ACCENT to "garbage", Keys.ACTIVE_TRIP to "12",
                Keys.DATE_STYLE to "nonsense",
            ),
        )
        assertEquals(ThemeMode.DARK, parsed.themeMode)
        assertEquals(NumberStyle.DOT_COMMA, parsed.numberStyle)
        assertEquals(DayOfWeek.SUNDAY, parsed.firstDayOfWeek)
        assertEquals(false, parsed.showTotal)
        assertEquals(true, parsed.showToday)
        assertEquals(AppSettings().accent, parsed.accent)
        assertEquals(AppSettings().dateStyle, parsed.dateStyle)
        assertEquals(12L, parsed.activeTripId)
    }

    @Test fun defaultsAreConsistent() {
        assertEquals(Defaults.currencies.size, Defaults.currencies.map { it.code }.toSet().size)
        assertTrue(Defaults.currencies.all { it.code.length == 3 && it.decimals in 0..3 && it.defaultRate.isEmpty() })
        assertTrue(Defaults.categories.isNotEmpty())
        assertTrue(Defaults.paymentMethods.isNotEmpty())
    }

    @Test fun sampleDataOnlyUsesKnownCurrenciesAndCategories() {
        val categories = Defaults.categories.mapIndexed { i, c -> c.copy(id = i + 1L) }
        val methods = Defaults.paymentMethods.mapIndexed { i, m -> m.copy(id = i + 1L) }
        val built = SampleData.build(day, categories, methods)
        assertEquals(built.trips.size, built.entries.size)
        built.trips.forEachIndexed { i, (trip, locals) ->
            val allowed = locals.map { it.code } + trip.homeCurrency
            assertTrue(built.entries[i].isNotEmpty())
            assertTrue(built.entries[i].all { it.currency in allowed && it.amountMinor > 0 })
            assertTrue(built.entries[i].all { e -> categories.any { it.id == e.categoryId } })
            assertTrue(allowed.all { code -> Defaults.currencies.any { it.code == code } })
        }
    }
}
