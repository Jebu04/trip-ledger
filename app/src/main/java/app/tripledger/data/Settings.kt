package app.tripledger.data

import app.tripledger.domain.Converter
import app.tripledger.domain.CurrencyDisplay
import app.tripledger.domain.DateStyle
import app.tripledger.domain.Dates
import app.tripledger.domain.Money
import app.tripledger.domain.NumberStyle
import app.tripledger.domain.ThemeMode
import java.time.DayOfWeek

object Keys {
    const val SEEDED = "seeded"
    const val THEME = "theme"
    const val ACCENT = "accent"
    const val HOME_CURRENCY = "home_currency"
    const val CURRENCY_DISPLAY = "currency_display"
    const val NUMBER_STYLE = "number_style"
    const val DATE_STYLE = "date_style"
    const val FIRST_DAY = "first_day_of_week"
    const val DEFAULT_CATEGORY = "default_category"
    const val DEFAULT_CURRENCY = "default_currency"
    const val DEFAULT_PAYMENT = "default_payment"
    const val SHOW_TOTAL = "card_total"
    const val SHOW_AVERAGE = "card_average"
    const val SHOW_TODAY = "card_today"
    const val SHOW_COUNT = "card_count"
    const val SHOW_BUDGET = "card_budget"
    const val SHOW_LEFT_TODAY = "card_left_today"
    const val ACTIVE_TRIP = "active_trip"
    const val AUTO_BACKUP_INTERVAL = "auto_backup_interval"
    const val AUTO_BACKUP_FOLDER = "auto_backup_folder"
    const val AUTO_BACKUP_LAST = "auto_backup_last"
    const val AUTO_BACKUP_STATUS = "auto_backup_status"
}

const val DEFAULT_ACCENT: Int = 0xFF00897B.toInt()

enum class BackupInterval(val key: String, val label: String, val days: Int) {
    OFF("off", "Off", 0),
    DAILY("daily", "Daily", 1),
    WEEKLY("weekly", "Weekly", 7);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: OFF
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Int = DEFAULT_ACCENT,
    /** Home currency suggested for new trips. Each trip keeps its own. */
    val homeCurrency: String = "EUR",
    val currencyDisplay: CurrencyDisplay = CurrencyDisplay.SYMBOL,
    val numberStyle: NumberStyle = NumberStyle.COMMA_DOT,
    val dateStyle: DateStyle = DateStyle.WEEKDAY_MEDIUM,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    /** 0 means "first category in the list". */
    val defaultCategoryId: Long = 0,
    /** Empty means "the currency used for the trip's most recent entry". */
    val defaultCurrency: String = "",
    /** 0 means none. */
    val defaultPaymentMethodId: Long = 0,
    val showTotal: Boolean = true,
    val showDailyAverage: Boolean = true,
    val showToday: Boolean = false,
    val showCount: Boolean = false,
    /** Budget cards only ever appear on trips that have a budget. */
    val showBudget: Boolean = true,
    val showLeftToday: Boolean = true,
    val activeTripId: Long = 0,
    val autoBackupInterval: BackupInterval = BackupInterval.OFF,
    /** Tree URI of the folder chosen for automatic backups; empty = none chosen. */
    val autoBackupFolder: String = "",
    /** When the last automatic backup was written, in epoch milliseconds; 0 = never. */
    val autoBackupLast: Long = 0,
    /** Human-readable outcome of the last automatic backup attempt. */
    val autoBackupStatus: String = "",
) {
    companion object {
        fun fromMap(m: Map<String, String>): AppSettings {
            val d = AppSettings()
            fun bool(key: String, default: Boolean) = m[key]?.let { it == "1" } ?: default
            return AppSettings(
                themeMode = ThemeMode.fromKey(m[Keys.THEME]),
                accent = m[Keys.ACCENT]?.toIntOrNull() ?: d.accent,
                homeCurrency = m[Keys.HOME_CURRENCY]?.takeIf { it.isNotBlank() } ?: d.homeCurrency,
                currencyDisplay = CurrencyDisplay.fromKey(m[Keys.CURRENCY_DISPLAY]),
                numberStyle = NumberStyle.fromKey(m[Keys.NUMBER_STYLE]),
                dateStyle = DateStyle.fromKey(m[Keys.DATE_STYLE]),
                firstDayOfWeek = Dates.parseDayOfWeek(m[Keys.FIRST_DAY]),
                defaultCategoryId = m[Keys.DEFAULT_CATEGORY]?.toLongOrNull() ?: 0,
                defaultCurrency = m[Keys.DEFAULT_CURRENCY] ?: "",
                defaultPaymentMethodId = m[Keys.DEFAULT_PAYMENT]?.toLongOrNull() ?: 0,
                showTotal = bool(Keys.SHOW_TOTAL, d.showTotal),
                showDailyAverage = bool(Keys.SHOW_AVERAGE, d.showDailyAverage),
                showToday = bool(Keys.SHOW_TODAY, d.showToday),
                showCount = bool(Keys.SHOW_COUNT, d.showCount),
                showBudget = bool(Keys.SHOW_BUDGET, d.showBudget),
                showLeftToday = bool(Keys.SHOW_LEFT_TODAY, d.showLeftToday),
                activeTripId = m[Keys.ACTIVE_TRIP]?.toLongOrNull() ?: 0,
                autoBackupInterval = BackupInterval.fromKey(m[Keys.AUTO_BACKUP_INTERVAL]),
                autoBackupFolder = m[Keys.AUTO_BACKUP_FOLDER] ?: "",
                autoBackupLast = m[Keys.AUTO_BACKUP_LAST]?.toLongOrNull() ?: 0,
                autoBackupStatus = m[Keys.AUTO_BACKUP_STATUS] ?: "",
            )
        }
    }
}

/** Everything the screens need besides the entries themselves, as one consistent snapshot. */
data class RefData(
    val settings: AppSettings,
    val currencies: List<Currency>,
    val categories: List<Category>,
    val paymentMethods: List<PaymentMethod>,
    val trips: List<Trip>,
    val tripCurrencies: List<TripCurrency>,
) {
    val currencyMap: Map<String, Currency> = currencies.associateBy { it.code }
    val categoryMap: Map<Long, Category> = categories.associateBy { it.id }
    val paymentMap: Map<Long, PaymentMethod> = paymentMethods.associateBy { it.id }

    val activeTrip: Trip? = trips.firstOrNull { it.id == settings.activeTripId } ?: trips.firstOrNull()

    /** Looks a currency up, falling back to a plain two-decimal one if it was deleted. */
    fun currency(code: String): Currency = currencyMap[code] ?: Currency(code, code, code, 2)

    fun localCurrencies(tripId: Long): List<TripCurrency> = tripCurrencies.filter { it.tripId == tripId }

    fun converter(trip: Trip): Converter = Converter(
        homeCode = trip.homeCurrency,
        rates = localCurrencies(trip.id).associate { it.code to Money.parseRate(it.rate) },
        decimalsOf = { currency(it).decimals },
    )

    fun money(minor: Long, code: String): String {
        val c = currency(code)
        return Money.format(minor, c.code, c.symbol, c.decimals, settings.numberStyle, settings.currencyDisplay)
    }

    fun date(epochDay: Long): String = settings.dateStyle.format(epochDay)

    /** How long a trip is, in days, counting both the first and the last. */
    fun tripDays(trip: Trip): Int = (trip.endDate - trip.startDate + 1).coerceAtLeast(1).toInt()
}
