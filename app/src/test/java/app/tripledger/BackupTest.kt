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
        trips = listOf(Trip(7, "Thailand \"2026\"", day - 3, day + 4, "EUR", 1000)),
        tripCurrencies = listOf(TripCurrency(7, "THB", "37.85", 0), TripCurrency(7, "JPY", "", 1)),
        entries = listOf(
            Entry(1, 7, 45000, "THB", 1, "Pad thai, extra spicy", day, "line one\nline two", 1, 5),
            Entry(2, 7, 2500, "EUR", 2, "=SUM(A1)", day - 1, "", null, 6),
            Entry(3, 7, 3000, "JPY", 1, "Ramen", day - 2, "", 2, 7),
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
            Backup.fromJson(json.replace("\"version\": 1", "\"version\": 99"))
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
            entries = data.entries + Entry(9, 999, 100, "EUR", 1, "orphan", day),
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
    }

    @Test fun csvConvertsAndEscapes() {
        val csv = Csv.build(data)
        // Oldest first. The yen entry has no rate: its home amount and rate columns are empty.
        val expected = listOf(
            Csv.HEADER.joinToString(","),
            "\"Thailand \"\"2026\"\"\",2026-10-12,Ramen,Food & Drink,3000,JPY,,EUR,,Card,",
            "\"Thailand \"\"2026\"\"\",2026-10-13,'=SUM(A1),Stay,25.00,EUR,25.00,EUR,1,,",
            "\"Thailand \"\"2026\"\"\",2026-10-14,\"Pad thai, extra spicy\",Food & Drink,450.00,THB,11.89,EUR,37.85,Cash,\"line one\nline two\"",
        ).joinToString("\r\n") + "\r\n"
        assertEquals(expected, csv)
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
