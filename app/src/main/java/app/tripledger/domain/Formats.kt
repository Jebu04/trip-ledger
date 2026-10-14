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

    fun format(epochDay: Long, locale: Locale = Locale.getDefault()): String =
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
}
