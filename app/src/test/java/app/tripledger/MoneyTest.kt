package app.tripledger

import app.tripledger.domain.Converter
import app.tripledger.domain.CurrencyDisplay
import app.tripledger.domain.Money
import app.tripledger.domain.NumberStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {

    // ---- parsing ----

    @Test fun parsesWholeNumbers() {
        assertEquals(1200L, Money.parseMinor("12", 2))
        assertEquals(12L, Money.parseMinor("12", 0))
        assertEquals(12000L, Money.parseMinor("12", 3))
    }

    @Test fun parsesDotAndCommaDecimals() {
        assertEquals(1250L, Money.parseMinor("12.5", 2))
        assertEquals(1250L, Money.parseMinor("12,50", 2))
        assertEquals(5L, Money.parseMinor(".05", 2))
        assertEquals(1200L, Money.parseMinor("12.", 2))
        assertEquals(1200L, Money.parseMinor(" 12 ", 2))
    }

    @Test fun parsesGroupedInput() {
        assertEquals(123456L, Money.parseMinor("1,234.56", 2))
        assertEquals(123456L, Money.parseMinor("1.234,56", 2))
        assertEquals(123456L, Money.parseMinor("1 234,56", 2))
        assertEquals(123456L, Money.parseMinor("1'234.56", 2))
        assertEquals(123456789L, Money.parseMinor("1,234,567.89", 2))
    }

    @Test fun amountsThatFloatingPointGetsWrongAreExact() {
        // 0.1 + 0.2 != 0.3 in binary floating point; in minor units it is exact.
        val sum = Money.parseMinor("0.1", 2)!! + Money.parseMinor("0.2", 2)!!
        assertEquals(Money.parseMinor("0.3", 2), sum)
        // 1.15 * 100 is 114.99999999999999 as a double.
        assertEquals(115L, Money.parseMinor("1.15", 2))
        assertEquals(1999L, Money.parseMinor("19.99", 2))
        assertEquals(402L, Money.parseMinor("4.02", 2))
    }

    @Test fun roundsExtraFractionDigitsHalfUp() {
        assertEquals(1235L, Money.parseMinor("12.345", 2))
        assertEquals(1234L, Money.parseMinor("12.344", 2))
        assertEquals(13L, Money.parseMinor("12.5", 0))
        assertEquals(12L, Money.parseMinor("12.4", 0))
    }

    @Test fun rejectsGarbage() {
        assertNull(Money.parseMinor("", 2))
        assertNull(Money.parseMinor("   ", 2))
        assertNull(Money.parseMinor("abc", 2))
        assertNull(Money.parseMinor("-5", 2))
        assertNull(Money.parseMinor("1e5", 2))
        assertNull(Money.parseMinor(".", 2))
        assertNull(Money.parseMinor(",", 2))
        assertNull(Money.parseMinor("12.3.x", 2))
    }

    @Test fun rejectsAbsurdlyLargeAmounts() {
        assertNull(Money.parseMinor("99999999999999999999", 2))
        assertEquals(Money.MAX_MINOR, Money.parseMinor("9999999999999.99", 2))
    }

    @Test fun zeroParsesToZero() {
        assertEquals(0L, Money.parseMinor("0", 2))
        assertEquals(0L, Money.parseMinor("0.00", 2))
    }

    // ---- plain text and formatting ----

    @Test fun plainTextKeepsAllFractionDigits() {
        assertEquals("12.50", Money.toPlain(1250, 2))
        assertEquals("0.05", Money.toPlain(5, 2))
        assertEquals("450", Money.toPlain(450, 0))
        assertEquals("1.500", Money.toPlain(1500, 3))
    }

    @Test fun plainTextRoundTripsThroughParsing() {
        for (minor in listOf(0L, 1L, 99L, 100L, 1250L, 123456789L)) {
            for (decimals in 0..3) {
                assertEquals(minor, Money.parseMinor(Money.toPlain(minor, decimals), decimals))
            }
        }
    }

    @Test fun formatsNumbersInEveryStyle() {
        assertEquals("1,234.56", Money.formatNumber(123456, 2, NumberStyle.COMMA_DOT))
        assertEquals("1.234,56", Money.formatNumber(123456, 2, NumberStyle.DOT_COMMA))
        assertEquals("1 234,56", Money.formatNumber(123456, 2, NumberStyle.SPACE_COMMA))
        assertEquals("1'234.56", Money.formatNumber(123456, 2, NumberStyle.APOS_DOT))
        assertEquals("1234.56", Money.formatNumber(123456, 2, NumberStyle.PLAIN_DOT))
        assertEquals("1234,56", Money.formatNumber(123456, 2, NumberStyle.PLAIN_COMMA))
    }

    @Test fun formatsEdgeCases() {
        assertEquals("0.00", Money.formatNumber(0, 2, NumberStyle.COMMA_DOT))
        assertEquals("0.05", Money.formatNumber(5, 2, NumberStyle.COMMA_DOT))
        assertEquals("999.99", Money.formatNumber(99999, 2, NumberStyle.COMMA_DOT))
        assertEquals("1,000.00", Money.formatNumber(100000, 2, NumberStyle.COMMA_DOT))
        assertEquals("1,234,567", Money.formatNumber(1234567, 0, NumberStyle.COMMA_DOT))
        assertEquals("1.500", Money.formatNumber(1500, 3, NumberStyle.COMMA_DOT))
        assertEquals("-12.50", Money.formatNumber(-1250, 2, NumberStyle.COMMA_DOT))
    }

    @Test fun formatsWithSymbolOrCode() {
        assertEquals("€12.50", Money.format(1250, "EUR", "€", 2, NumberStyle.COMMA_DOT, CurrencyDisplay.SYMBOL))
        assertEquals("EUR 12.50", Money.format(1250, "EUR", "€", 2, NumberStyle.COMMA_DOT, CurrencyDisplay.CODE))
        // A symbol that ends in a letter gets a space.
        assertEquals("kr 120,00", Money.format(12000, "SEK", "kr", 2, NumberStyle.DOT_COMMA, CurrencyDisplay.SYMBOL))
        // No symbol falls back to the code.
        assertEquals("XTS 1.00", Money.format(100, "XTS", "", 2, NumberStyle.COMMA_DOT, CurrencyDisplay.SYMBOL))
    }

    @Test fun refundsPutTheMinusBeforeTheSymbol() {
        assertEquals("-€12.50", Money.format(-1250, "EUR", "€", 2, NumberStyle.COMMA_DOT, CurrencyDisplay.SYMBOL))
        assertEquals("-kr\u00A0120,00", Money.format(-12000, "SEK", "kr", 2, NumberStyle.DOT_COMMA, CurrencyDisplay.SYMBOL))
        assertEquals("-EUR\u00A012.50", Money.format(-1250, "EUR", "€", 2, NumberStyle.COMMA_DOT, CurrencyDisplay.CODE))
        assertEquals("-12.50", Money.toPlain(-1250, 2))
    }

    @Test fun numberStyleFollowsTheRegion() {
        assertEquals(NumberStyle.COMMA_DOT, NumberStyle.forLocale(java.util.Locale.US))
        assertEquals(NumberStyle.COMMA_DOT, NumberStyle.forLocale(java.util.Locale.UK))
        assertEquals(NumberStyle.DOT_COMMA, NumberStyle.forLocale(java.util.Locale.GERMANY))
        assertEquals(NumberStyle.SPACE_COMMA, NumberStyle.forLocale(java.util.Locale("fi", "FI")))
        assertEquals(NumberStyle.SPACE_COMMA, NumberStyle.forLocale(java.util.Locale.FRANCE))
        assertEquals(NumberStyle.APOS_DOT, NumberStyle.forLocale(java.util.Locale("de", "CH")))
    }

    // ---- exchange rates and conversion ----

    @Test fun parsesRates() {
        assertEquals(BigDecimal("37.85"), Money.parseRate("37.85"))
        assertEquals(BigDecimal("37.85"), Money.parseRate("37,85"))
        assertEquals(BigDecimal("0.0064"), Money.parseRate("0.0064"))
    }

    @Test fun zeroMissingOrInvalidRatesAreNull() {
        assertNull(Money.parseRate(null))
        assertNull(Money.parseRate(""))
        assertNull(Money.parseRate("0"))
        assertNull(Money.parseRate("0.000"))
        assertNull(Money.parseRate("-1"))
        assertNull(Money.parseRate("abc"))
    }

    @Test fun convertsLocalToHome() {
        // 450.00 THB at 1 EUR = 37.85 THB is 11.889... EUR, which rounds to 11.89.
        assertEquals(1189L, Money.convert(45000, 2, 2, BigDecimal("37.85")))
        // 100 THB at 40 is exactly 2.50.
        assertEquals(250L, Money.convert(10000, 2, 2, BigDecimal("40")))
    }

    @Test fun convertsBetweenCurrenciesWithDifferentFractionDigits() {
        // 1000 JPY (no decimals) at 1 EUR = 160 JPY is 6.25 EUR.
        assertEquals(625L, Money.convert(1000, 0, 2, BigDecimal("160")))
        // 12.50 EUR at 1 JPY = 0.00625 EUR is 2000 JPY.
        assertEquals(2000L, Money.convert(1250, 2, 0, BigDecimal("0.00625")))
        // 1.500 KWD (three decimals) at 1 EUR = 0.3 KWD is 5.00 EUR.
        assertEquals(500L, Money.convert(1500, 3, 2, BigDecimal("0.3")))
    }

    @Test fun conversionRoundsHalfUp() {
        // 0.05 at rate 2 is 0.025, which rounds up to 0.03.
        assertEquals(3L, Money.convert(5, 2, 2, BigDecimal("2")))
        // 0.01 at rate 3 is 0.00333..., which rounds to 0.00.
        assertEquals(0L, Money.convert(1, 2, 2, BigDecimal("3")))
    }

    @Test fun conversionWithoutUsableRateIsNull() {
        assertNull(Money.convert(1000, 2, 2, null))
        assertNull(Money.convert(1000, 2, 2, BigDecimal.ZERO))
        assertNull(Money.convert(1000, 2, 2, BigDecimal("-2")))
    }

    @Test fun converterUsesTripRates() {
        val decimals = mapOf("EUR" to 2, "THB" to 2, "JPY" to 0)
        val converter = Converter(
            homeCode = "EUR",
            rates = mapOf("THB" to BigDecimal("37.85"), "JPY" to null),
            decimalsOf = { decimals[it] ?: 2 },
        )
        assertEquals(1250L, converter.toHome(1250, "EUR"))
        assertEquals(1189L, converter.toHome(45000, "THB"))
        assertNull("no rate set", converter.toHome(1000, "JPY"))
        assertNull("currency not on the trip", converter.toHome(1000, "USD"))
        assertTrue(converter.hasRate("EUR"))
        assertTrue(converter.hasRate("THB"))
        assertFalse(converter.hasRate("JPY"))
        assertEquals(BigDecimal.ONE, converter.rateFor("EUR"))
    }

    @Test fun anExpenseCanCarryItsOwnRate() {
        val converter = Converter("EUR", mapOf("THB" to BigDecimal("40"), "JPY" to null)) { if (it == "JPY") 0 else 2 }
        // 400.00 THB: 10.00 at the trip's rate of 40, 12.50 at this expense's rate of 32.
        assertEquals(1000L, converter.toHome(40000, "THB", ""))
        assertEquals(1250L, converter.toHome(40000, "THB", "32"))
        assertEquals(BigDecimal("32"), converter.effectiveRate("THB", "32"))
        // An unusable override falls back to the trip's rate.
        assertEquals(1000L, converter.toHome(40000, "THB", "0"))
        assertEquals(1000L, converter.toHome(40000, "THB", "abc"))
        // An override rescues a currency the trip has no rate for.
        assertNull(converter.toHome(1600, "JPY", ""))
        assertEquals(1000L, converter.toHome(1600, "JPY", "160"))
        // The home currency never converts, whatever is typed.
        assertEquals(500L, converter.toHome(500, "EUR", "3"))
        assertEquals(BigDecimal.ONE, converter.effectiveRate("EUR", "3"))
    }
}
