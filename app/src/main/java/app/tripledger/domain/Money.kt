package app.tripledger.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** How numbers are written: grouping separator and decimal separator. */
enum class NumberStyle(val key: String, val group: String, val dec: Char, val sample: String) {
    COMMA_DOT("comma_dot", ",", '.', "1,234.56"),
    DOT_COMMA("dot_comma", ".", ',', "1.234,56"),
    SPACE_COMMA("space_comma", " ", ',', "1 234,56"),
    APOS_DOT("apos_dot", "'", '.', "1'234.56"),
    PLAIN_DOT("plain_dot", "", '.', "1234.56"),
    PLAIN_COMMA("plain_comma", "", ',', "1234,56");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: COMMA_DOT

        /** The style a locale writes numbers in, used as the default on first launch. */
        fun forLocale(locale: java.util.Locale): NumberStyle {
            val symbols = java.text.DecimalFormatSymbols.getInstance(locale)
            val group = symbols.groupingSeparator
            return when {
                symbols.decimalSeparator == ',' && group == '.' -> DOT_COMMA
                symbols.decimalSeparator == ',' -> SPACE_COMMA
                group == '\'' || group == '’' -> APOS_DOT
                else -> COMMA_DOT
            }
        }
    }
}

enum class CurrencyDisplay(val key: String, val label: String) {
    SYMBOL("symbol", "Symbol (€12.50)"),
    CODE("code", "Code (EUR 12.50)");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYMBOL
    }
}

/**
 * All money in the app is a [Long] count of minor units (cents, satang, ...). Nothing here uses
 * Float or Double: parsing, conversion and rounding go through [BigDecimal].
 */
object Money {
    /** Largest amount accepted from the keyboard, in minor units. Keeps sums far from overflow. */
    const val MAX_MINOR = 999_999_999_999_999L

    /**
     * Turns user-typed text into a plain decimal string such as "1234.5", or null if it is not a
     * non-negative number. Accepts either '.' or ',' as the decimal separator; when both appear
     * the last one is the decimal separator and the other is treated as grouping.
     */
    fun normalizeDecimal(input: String): String? {
        val s = input.trim()
            .replace(" ", "").replace(" ", "").replace(" ", "").replace("'", "")
        if (s.isEmpty()) return null
        val decIdx = maxOf(s.lastIndexOf('.'), s.lastIndexOf(','))
        val normalized = if (decIdx < 0) s else {
            val intPart = s.substring(0, decIdx).replace(".", "").replace(",", "")
            val frac = s.substring(decIdx + 1)
            if (frac.isEmpty()) intPart else "$intPart.$frac"
        }
        if (!Regex("\\d*(\\.\\d+)?").matches(normalized)) return null
        if (normalized.none { it.isDigit() }) return null
        return if (normalized.startsWith(".")) "0$normalized" else normalized
    }

    /**
     * Parses typed text into minor units for a currency with [decimals] fraction digits.
     * Extra fraction digits are rounded half-up. Returns null for anything that is not a
     * non-negative number or that is unreasonably large.
     */
    fun parseMinor(input: String, decimals: Int): Long? {
        val normalized = normalizeDecimal(input) ?: return null
        return try {
            val minor = BigDecimal(normalized).movePointRight(decimals).setScale(0, RoundingMode.HALF_UP)
            if (minor > BigDecimal.valueOf(MAX_MINOR)) null else minor.longValueExact()
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Parses an exchange rate. Returns null unless it is a number strictly greater than zero. */
    fun parseRate(input: String?): BigDecimal? {
        val normalized = normalizeDecimal(input ?: return null) ?: return null
        return try {
            BigDecimal(normalized).takeIf { it.signum() > 0 }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Minor units as plain text for an edit field, e.g. 1250 with 2 decimals gives "12.50". */
    fun toPlain(minor: Long, decimals: Int): String =
        BigDecimal.valueOf(minor).movePointLeft(decimals).setScale(decimals, RoundingMode.UNNECESSARY).toPlainString()

    /**
     * Converts an amount in a local currency to the home currency.
     *
     * [rate] is "how many local units one home unit buys" (1 EUR = 37.85 THB gives 37.85), so the
     * home amount is local / rate, rounded half-up to the home currency's minor unit.
     * Returns null when the rate is missing, zero or negative.
     */
    fun convert(amountMinor: Long, fromDecimals: Int, toDecimals: Int, rate: BigDecimal?): Long? {
        if (rate == null || rate.signum() <= 0) return null
        return try {
            BigDecimal.valueOf(amountMinor)
                .movePointLeft(fromDecimals)
                .divide(rate, toDecimals, RoundingMode.HALF_UP)
                .movePointRight(toDecimals)
                .longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    /** Formats minor units as a number with grouping, e.g. 123456 with 2 decimals gives "1,234.56". */
    fun formatNumber(minor: Long, decimals: Int, style: NumberStyle): String {
        val negative = minor < 0
        val abs = BigDecimal.valueOf(minor).abs().movePointLeft(decimals).setScale(decimals, RoundingMode.UNNECESSARY)
        val plain = abs.toPlainString()
        val dot = plain.indexOf('.')
        val intPart = if (dot < 0) plain else plain.substring(0, dot)
        val frac = if (dot < 0) "" else plain.substring(dot + 1)
        val grouped = if (style.group.isEmpty()) intPart else {
            val sb = StringBuilder()
            for ((i, c) in intPart.withIndex()) {
                if (i > 0 && (intPart.length - i) % 3 == 0) sb.append(style.group)
                sb.append(c)
            }
            sb.toString()
        }
        val body = if (decimals > 0) "$grouped${style.dec}$frac" else grouped
        return if (negative) "-$body" else body
    }

    /** Formats an amount with its currency, e.g. "€12.50", "kr 120.00" or "EUR 12.50". */
    fun format(
        minor: Long,
        code: String,
        symbol: String,
        decimals: Int,
        style: NumberStyle,
        display: CurrencyDisplay,
    ): String {
        // A refund reads "-€5.00", with the sign in front of the symbol.
        if (minor < 0) return "-" + format(-minor, code, symbol, decimals, style, display)
        val number = formatNumber(minor, decimals, style)
        val useSymbol = display == CurrencyDisplay.SYMBOL && symbol.isNotBlank()
        if (!useSymbol) return "$code $number"
        // Symbols that end in a letter ("kr", "CHF", "Rp") read better with a space.
        return if (symbol.last().isLetter()) "$symbol $number" else "$symbol$number"
    }
}

/**
 * Converts amounts of one trip into that trip's home currency.
 *
 * @param rates local units per one home unit, by currency code; missing or null means "no rate".
 * @param decimalsOf fraction digits of a currency code.
 */
class Converter(
    val homeCode: String,
    private val rates: Map<String, BigDecimal?>,
    private val decimalsOf: (String) -> Int,
) {
    fun rateFor(code: String): BigDecimal? = if (code == homeCode) BigDecimal.ONE else rates[code]

    fun hasRate(code: String): Boolean = rateFor(code) != null

    /** Amount in home minor units, or null when there is no usable rate for [code]. */
    fun toHome(amountMinor: Long, code: String): Long? = toHome(amountMinor, code, null)

    /**
     * Like [toHome], but [override] (a rate typed on one expense) wins over the trip's rate when it
     * is a usable number.
     */
    fun toHome(amountMinor: Long, code: String, override: String?): Long? {
        if (code == homeCode) return amountMinor
        return Money.convert(amountMinor, decimalsOf(code), decimalsOf(homeCode), effectiveRate(code, override))
    }

    /** The rate that applies to an expense: its own if set, otherwise the trip's. */
    fun effectiveRate(code: String, override: String?): BigDecimal? =
        if (code == homeCode) BigDecimal.ONE else Money.parseRate(override) ?: rates[code]
}
