package app.tripledger.data

import java.util.Locale

/** The data a fresh install starts with. Everything here can be changed in Settings. */
object Defaults {
    private fun c(code: String, name: String, symbol: String, decimals: Int = 2) = Currency(code, name, symbol, decimals)

    /** No rates are shipped: the app is offline, so rates are always typed in by the user. */
    val currencies: List<Currency> = listOf(
        c("EUR", "Euro", "€"),
        c("USD", "US Dollar", "$"),
        c("GBP", "British Pound", "£"),
        c("AED", "UAE Dirham", "AED"),
        c("ARS", "Argentine Peso", "AR$"),
        c("AUD", "Australian Dollar", "A$"),
        c("BRL", "Brazilian Real", "R$"),
        c("CAD", "Canadian Dollar", "C$"),
        c("CHF", "Swiss Franc", "CHF"),
        c("CLP", "Chilean Peso", "CLP$", 0),
        c("CNY", "Chinese Yuan", "CN¥"),
        c("COP", "Colombian Peso", "COL$"),
        c("CZK", "Czech Koruna", "Kč"),
        c("DKK", "Danish Krone", "kr"),
        c("EGP", "Egyptian Pound", "E£"),
        c("HKD", "Hong Kong Dollar", "HK$"),
        c("HUF", "Hungarian Forint", "Ft"),
        c("IDR", "Indonesian Rupiah", "Rp", 0),
        c("ILS", "Israeli Shekel", "₪"),
        c("INR", "Indian Rupee", "₹"),
        c("ISK", "Icelandic Króna", "kr", 0),
        c("JPY", "Japanese Yen", "¥", 0),
        c("KHR", "Cambodian Riel", "៛"),
        c("KRW", "South Korean Won", "₩", 0),
        c("KWD", "Kuwaiti Dinar", "KD", 3),
        c("LAK", "Lao Kip", "₭"),
        c("LKR", "Sri Lankan Rupee", "Rs"),
        c("MAD", "Moroccan Dirham", "MAD"),
        c("MXN", "Mexican Peso", "MX$"),
        c("MYR", "Malaysian Ringgit", "RM"),
        c("NOK", "Norwegian Krone", "kr"),
        c("NPR", "Nepalese Rupee", "Rs"),
        c("NZD", "New Zealand Dollar", "NZ$"),
        c("PEN", "Peruvian Sol", "S/"),
        c("PHP", "Philippine Peso", "₱"),
        c("PLN", "Polish Złoty", "zł"),
        c("SEK", "Swedish Krona", "kr"),
        c("SGD", "Singapore Dollar", "S$"),
        c("THB", "Thai Baht", "฿"),
        c("TRY", "Turkish Lira", "₺"),
        c("TWD", "Taiwan Dollar", "NT$"),
        c("VND", "Vietnamese Dong", "₫", 0),
        c("ZAR", "South African Rand", "R"),
    ).mapIndexed { i, cur -> cur.copy(sortOrder = i) }

    private fun cat(name: String, icon: String, color: Long) = Category(name = name, icon = icon, color = color.toInt())

    val categories: List<Category> = listOf(
        cat("Food & Drink", "restaurant", 0xFFF4511E),
        cat("Groceries", "grocery", 0xFF7CB342),
        cat("Coffee", "cafe", 0xFF8D6E63),
        cat("Accommodation", "hotel", 0xFF5E35B1),
        cat("Transport", "bus", 0xFF1E88E5),
        cat("Flights", "flight", 0xFF00ACC1),
        cat("Activities", "attractions", 0xFFD81B60),
        cat("Shopping", "shopping_bag", 0xFFFB8C00),
        cat("Nightlife", "bar", 0xFF8E24AA),
        cat("Health", "pharmacy", 0xFFE53935),
        cat("Fees", "atm", 0xFF546E7A),
        cat("Other", "more", 0xFF78909C),
    ).mapIndexed { i, c -> c.copy(sortOrder = i) }

    val paymentMethods: List<PaymentMethod> = listOf("Cash", "Credit card", "Debit card")
        .mapIndexed { i, name -> PaymentMethod(name = name, sortOrder = i) }

    /** The device locale's currency when we know it, otherwise EUR. Uses no network. */
    fun deviceHomeCurrency(): String {
        val code = runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()
        return if (code != null && currencies.any { it.code == code }) code else "EUR"
    }
}
