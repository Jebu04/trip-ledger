package app.tripledger.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DateStyle(val key: String, val pattern: String) {
    WEEKDAY_MEDIUM("weekday_medium", "EEE, d MMM yyyy"),
    LONG("long", "d MMMM yyyy"),
    DMY_SLASH("dmy_slash", "dd/MM/yyyy"),
    MDY_SLASH("mdy_slash", "MM/dd/yyyy"),
    DMY_DOT("dmy_dot", "d.M.yyyy"),
    ISO("iso", "yyyy-MM-dd");

    /** Month and weekday names are always English: the app has no translations. */
    fun format(epochDay: Long, locale: Locale = Locale.ENGLISH): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern(pattern, locale))

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: WEEKDAY_MEDIUM
    }
}

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Follow system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

object Dates {
    fun today(): Long = LocalDate.now().toEpochDay()

    /** First day of the week containing [epochDay], for a week that starts on [firstDay]. */
    fun startOfWeek(epochDay: Long, firstDay: DayOfWeek): Long {
        val date = LocalDate.ofEpochDay(epochDay)
        val back = ((date.dayOfWeek.value - firstDay.value) + 7) % 7
        return epochDay - back
    }

    fun startOfMonth(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay).withDayOfMonth(1).toEpochDay()

    /** The seven weekdays in display order for a week starting on [firstDay]. */
    fun weekdays(firstDay: DayOfWeek): List<DayOfWeek> = (0 until 7).map { firstDay.plus(it.toLong()) }

    fun parseDayOfWeek(key: String?): DayOfWeek =
        runCatching { DayOfWeek.valueOf(key ?: "") }.getOrDefault(DayOfWeek.MONDAY)

    /** The day a locale's week starts on, limited to the three choices the app offers. */
    fun firstDayFor(locale: Locale): DayOfWeek =
        when (val day = java.time.temporal.WeekFields.of(locale).firstDayOfWeek) {
            DayOfWeek.SUNDAY, DayOfWeek.SATURDAY -> day
            else -> DayOfWeek.MONDAY
        }

    /** A compact date for list rows: "14 Oct", with the year only when it is not [today]'s year. */
    fun short(epochDay: Long, today: Long = today()): String {
        val date = LocalDate.ofEpochDay(epochDay)
        val pattern = if (date.year == LocalDate.ofEpochDay(today).year) "d MMM" else "d MMM yyyy"
        return date.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
    }
}
