package app.tripledger.data

/** Two realistic demo trips, dated relative to today so the app looks alive whenever it is loaded. */
object SampleData {
    private data class Row(
        val daysAgo: Int, val icon: String, val title: String, val amountMinor: Long, val currency: String,
        val method: Int = 0, val note: String = "",
        /** Spread over this many days; 0 means "the whole trip". */
        val spread: Int = 1,
        /** My share of a shared bill, or null when it was all mine. */
        val share: Long? = null,
    )

    // method: 0 = cash, 1 = credit card, 2 = debit card, -1 = none
    private val thailand = listOf(
        Row(9, "flight", "Flight HEL–BKK", 61900, "EUR", 1, "Booked in August, one checked bag", spread = 0),
        Row(9, "bus", "Airport Rail Link", 4500, "THB"),
        Row(9, "hotel", "Riverside Hostel, 3 nights", 285000, "THB", 1),
        Row(9, "restaurant", "Pad thai at the night market", 8000, "THB"),
        Row(8, "cafe", "Iced latte", 9500, "THB"),
        Row(8, "attractions", "Grand Palace ticket", 50000, "THB", 0, "Cover shoulders and knees"),
        Row(8, "bus", "Chao Phraya river boat", 3200, "THB"),
        Row(8, "restaurant", "Green curry and rice", 18000, "THB"),
        Row(8, "bar", "Rooftop cocktails", 78000, "THB", 1, "Split the bill with Aino", share = 39000),
        Row(7, "grocery", "7-Eleven snacks and water", 12600, "THB"),
        Row(7, "attractions", "Wat Pho entrance", 30000, "THB"),
        Row(7, "restaurant", "Boat noodles", 6000, "THB"),
        Row(7, "shopping_bag", "Chatuchak market: shirts and a bag", 124000, "THB"),
        Row(6, "bus", "Train to Chiang Mai (sleeper)", 104100, "THB", 2),
        Row(6, "atm", "ATM withdrawal fee", 22000, "THB", 2, "Flat fee charged by the Thai bank"),
        Row(6, "restaurant", "Mango sticky rice", 7000, "THB"),
        Row(5, "hotel", "Old City Guesthouse, 4 nights", 360000, "THB", 1),
        Row(5, "cafe", "Flat white and a croissant", 16500, "THB"),
        Row(5, "restaurant", "Khao soi", 9000, "THB"),
        Row(4, "attractions", "Elephant sanctuary day trip", 250000, "THB", 1, "Ethical sanctuary, includes lunch and transfer"),
        Row(4, "pharmacy", "Sunscreen and mosquito spray", 42000, "THB"),
        Row(3, "bus", "Scooter rental, 2 days", 50000, "THB"),
        Row(3, "bus", "Petrol", 12000, "THB"),
        Row(3, "restaurant", "Dinner at the Sunday walking street market with far too many small plates to list", 34000, "THB"),
        Row(3, "attractions", "Thai massage, 1 hour", 30000, "THB"),
        Row(2, "attractions", "Cooking class", 100000, "THB", 1),
        Row(2, "cafe", "Coconut coffee", 8500, "THB"),
        Row(2, "bar", "Beers by the moat", 28000, "THB"),
        Row(1, "grocery", "Fruit from the morning market", 9000, "THB"),
        Row(1, "restaurant", "Lunch: som tam and grilled chicken", 15000, "THB"),
        Row(1, "shopping_bag", "Souvenirs", 2400, "USD", 0, "Paid with leftover dollars"),
        Row(1, "bus", "Grab to the bus station", 14000, "THB", 2),
        Row(0, "cafe", "Espresso", 6000, "THB"),
        Row(0, "restaurant", "Breakfast at the guesthouse", 12000, "THB"),
        Row(0, "bus", "Songthaew to Doi Suthep", 8000, "THB"),
    )

    private val lisbon = listOf(
        Row(142, "flight", "Return flights", 18900, "EUR", 1),
        Row(142, "bus", "Metro day pass", 680, "EUR", 2),
        Row(142, "hotel", "Alfama apartment, 2 nights", 16400, "EUR", 1),
        Row(142, "restaurant", "Grilled sardines", 1850, "EUR"),
        Row(141, "cafe", "Pastéis de nata ×4", 520, "EUR"),
        Row(141, "attractions", "Belém Tower", 1500, "EUR", 2),
        Row(141, "restaurant", "Seafood dinner", 4700, "EUR", 1),
        Row(141, "bar", "Ginjinha", 300, "EUR"),
        Row(140, "shopping_bag", "Ceramic tiles", 2800, "EUR"),
        Row(140, "bus", "Tram 28", 310, "EUR"),
    )

    data class Built(val trips: List<Pair<Trip, List<TripCurrency>>>, val entries: List<List<Entry>>)

    /**
     * Builds the demo trips against the user's current categories and payment methods.
     * Trip and entry ids are left at 0 so Room assigns them; entry.tripId is filled by the caller.
     */
    fun build(today: Long, categories: List<Category>, methods: List<PaymentMethod>): Built {
        fun categoryId(icon: String): Long =
            (categories.firstOrNull { it.icon == icon } ?: categories.first()).id

        fun entries(rows: List<Row>, tripDays: Int): List<Entry> = rows.mapIndexed { i, r ->
            Entry(
                tripId = 0,
                amountMinor = r.amountMinor,
                currency = r.currency,
                categoryId = categoryId(r.icon),
                title = r.title,
                date = today - r.daysAgo,
                note = r.note,
                paymentMethodId = methods.getOrNull(r.method)?.id,
                createdAt = (today - r.daysAgo) * 86_400_000L + i * 60_000L,
                myShareMinor = r.share,
                spreadDays = if (r.spread == 0) tripDays else r.spread,
            )
        }

        val thai = Trip(
            name = "Thailand", startDate = today - 9, endDate = today + 4, homeCurrency = "EUR", createdAt = 2,
            budgetMinor = 150000, // 1,500.00 EUR for the whole trip
        )
        val thaiCurrencies = listOf(TripCurrency(0, "THB", "37.85", 0), TripCurrency(0, "USD", "1.08", 1))
        val lis = Trip(name = "Lisbon weekend", startDate = today - 142, endDate = today - 140, homeCurrency = "EUR", createdAt = 1)
        return Built(
            trips = listOf(thai to thaiCurrencies, lis to emptyList()),
            entries = listOf(entries(thailand, 14), entries(lisbon, 3)),
        )
    }
}
