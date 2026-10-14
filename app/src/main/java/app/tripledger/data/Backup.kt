package app.tripledger.data

import app.tripledger.domain.Converter
import app.tripledger.domain.Money
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/** A complete copy of the database contents. */
data class BackupData(
    val settings: Map<String, String>,
    val currencies: List<Currency>,
    val categories: List<Category>,
    val paymentMethods: List<PaymentMethod>,
    val trips: List<Trip>,
    val tripCurrencies: List<TripCurrency>,
    val entries: List<Entry>,
)

class BackupException(message: String) : Exception(message)

/** What an import added and what it left alone because it was already there. */
data class ImportResult(val tripsAdded: Int = 0, val entriesAdded: Int = 0, val entriesSkipped: Int = 0) {
    fun describe(): String {
        val added = "$entriesAdded ${if (entriesAdded == 1) "expense" else "expenses"} added" +
            if (tripsAdded > 0) " in $tripsAdded new ${if (tripsAdded == 1) "trip" else "trips"}" else ""
        return if (entriesSkipped > 0) "$added, $entriesSkipped already there" else added
    }
}

/** Reads and writes the JSON backup file. Amounts stay integers end to end. */
object Backup {
    const val FORMAT = "tripledger-backup"

    /** Version 2 added budgets, shared bills, spreading, per-expense rates and photo names. */
    const val VERSION = 2

    fun toJson(data: BackupData, exportedAt: String = ""): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("exportedAt", exportedAt)
        root.put("settings", JSONObject().apply { data.settings.toSortedMap().forEach { (k, v) -> put(k, v) } })
        root.put("currencies", JSONArray().apply {
            data.currencies.forEach {
                put(JSONObject().put("code", it.code).put("name", it.name).put("symbol", it.symbol)
                    .put("decimals", it.decimals).put("defaultRate", it.defaultRate).put("sortOrder", it.sortOrder))
            }
        })
        root.put("categories", JSONArray().apply {
            data.categories.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon)
                    .put("color", it.color).put("sortOrder", it.sortOrder))
            }
        })
        root.put("paymentMethods", JSONArray().apply {
            data.paymentMethods.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("sortOrder", it.sortOrder))
            }
        })
        root.put("trips", JSONArray().apply {
            data.trips.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("startDate", it.startDate)
                    .put("endDate", it.endDate).put("homeCurrency", it.homeCurrency).put("createdAt", it.createdAt)
                    .put("budgetMinor", it.budgetMinor).put("budgetPerDay", it.budgetPerDay))
            }
        })
        root.put("tripCurrencies", JSONArray().apply {
            data.tripCurrencies.forEach {
                put(JSONObject().put("tripId", it.tripId).put("code", it.code).put("rate", it.rate)
                    .put("sortOrder", it.sortOrder))
            }
        })
        root.put("entries", JSONArray().apply {
            data.entries.forEach {
                val o = JSONObject().put("id", it.id).put("tripId", it.tripId).put("amountMinor", it.amountMinor)
                    .put("currency", it.currency).put("categoryId", it.categoryId).put("title", it.title)
                    .put("date", it.date).put("note", it.note).put("createdAt", it.createdAt)
                    .put("spreadDays", it.spreadDays).put("excludeFromAverage", it.excludeFromAverage)
                    .put("rateOverride", it.rateOverride).put("photo", it.photo)
                if (it.paymentMethodId != null) o.put("paymentMethodId", it.paymentMethodId)
                if (it.myShareMinor != null) o.put("myShareMinor", it.myShareMinor)
                put(o)
            }
        })
        return root.toString(2)
    }

    /**
     * Parses a backup file of this or an older format version. Throws [BackupException] with a
     * readable message if it is not valid.
     */
    fun fromJson(text: String): BackupData {
        try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) throw BackupException("This is not a Trip Ledger backup file.")
            val version = root.optInt("version", -1)
            if (version < 1 || version > VERSION) {
                throw BackupException("This backup was made by a newer version of the app (format $version).")
            }
            val settingsObj = root.getJSONObject("settings")
            val settings = settingsObj.keys().asSequence().associateWith { settingsObj.getString(it) }
            val currencies = root.getJSONArray("currencies").objects().map {
                Currency(
                    code = it.getString("code"), name = it.getString("name"), symbol = it.getString("symbol"),
                    decimals = it.getInt("decimals").coerceIn(0, 4), defaultRate = it.optString("defaultRate", ""),
                    sortOrder = it.optInt("sortOrder", 0),
                )
            }
            val categories = root.getJSONArray("categories").objects().map {
                Category(it.getLong("id"), it.getString("name"), it.getString("icon"), it.getInt("color"), it.optInt("sortOrder", 0))
            }
            val methods = root.getJSONArray("paymentMethods").objects().map {
                PaymentMethod(it.getLong("id"), it.getString("name"), it.optInt("sortOrder", 0))
            }
            val trips = root.getJSONArray("trips").objects().map {
                Trip(
                    it.getLong("id"), it.getString("name"), it.getLong("startDate"), it.getLong("endDate"),
                    it.getString("homeCurrency"), it.optLong("createdAt", 0),
                    budgetMinor = it.optLong("budgetMinor", 0).coerceAtLeast(0),
                    budgetPerDay = it.optBoolean("budgetPerDay", false),
                )
            }
            val tripIds = trips.map { it.id }.toSet()
            val tripCurrencies = root.getJSONArray("tripCurrencies").objects().map {
                TripCurrency(it.getLong("tripId"), it.getString("code"), it.optString("rate", ""), it.optInt("sortOrder", 0))
            }.filter { it.tripId in tripIds }
            val methodIds = methods.map { it.id }.toSet()
            val entries = root.getJSONArray("entries").objects().map {
                val pm = if (it.has("paymentMethodId") && !it.isNull("paymentMethodId")) it.getLong("paymentMethodId") else null
                val share = if (it.has("myShareMinor") && !it.isNull("myShareMinor")) it.getLong("myShareMinor") else null
                Entry(
                    id = it.getLong("id"), tripId = it.getLong("tripId"), amountMinor = it.getLong("amountMinor"),
                    currency = it.getString("currency"), categoryId = it.getLong("categoryId"),
                    title = it.getString("title"), date = it.getLong("date"), note = it.optString("note", ""),
                    paymentMethodId = pm?.takeIf { id -> id in methodIds }, createdAt = it.optLong("createdAt", 0),
                    myShareMinor = share,
                    spreadDays = it.optInt("spreadDays", 1).coerceIn(1, 3660),
                    excludeFromAverage = it.optBoolean("excludeFromAverage", false),
                    rateOverride = it.optString("rateOverride", ""),
                    photo = it.optString("photo", ""),
                )
            }.filter { it.tripId in tripIds }
            if (categories.isEmpty()) throw BackupException("The backup contains no categories.")
            return BackupData(settings, currencies, categories, methods, trips, tripCurrencies, entries)
        } catch (e: JSONException) {
            // The parser's own message quotes the file contents, which is no use to the user.
            throw BackupException("The backup file is damaged or incomplete.")
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}

/** One expense read from a CSV file, still as text: the repository resolves names to rows. */
data class CsvRow(
    val line: Int,
    val trip: String,
    val date: Long,
    val title: String,
    val category: String,
    val amount: String,
    val currency: String,
    val homeCurrency: String,
    val rate: String,
    val paymentMethod: String,
    val note: String,
    val myShare: String,
    val spreadDays: Int,
    val excludeFromAverage: Boolean,
)

/** Builds and reads the CSV export: one row per expense across all trips. */
object Csv {
    val HEADER = listOf(
        "Trip", "Date", "Title", "Category", "Amount", "Currency", "Amount (home)", "Home currency",
        "Exchange rate", "Payment method", "Note", "My share", "Spread days", "Excluded from average",
    )

    /** Quotes a field when needed and neutralises text a spreadsheet would run as a formula. */
    fun escape(raw: String, text: Boolean = true): String {
        var s = raw
        if (text && s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    /** Undoes the formula guard that [escape] adds, so an export reads back unchanged. */
    private fun unguard(s: String): String =
        if (s.length >= 2 && s[0] == '\'' && s[1] in "=+-@\t\r") s.substring(1) else s

    fun build(data: BackupData): String {
        val currencies = data.currencies.associateBy { it.code }
        fun decimals(code: String) = currencies[code]?.decimals ?: 2
        val categories = data.categories.associateBy { it.id }
        val methods = data.paymentMethods.associateBy { it.id }
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (trip in data.trips.sortedBy { it.startDate }) {
            val locals = data.tripCurrencies.filter { it.tripId == trip.id }
            val converter = Converter(trip.homeCurrency, locals.associate { it.code to Money.parseRate(it.rate) }, ::decimals)
            val rows = data.entries.filter { it.tripId == trip.id }.sortedWith(compareBy({ it.date }, { it.createdAt }, { it.id }))
            for (e in rows) {
                val home = converter.toHome(e.effectiveMinor, e.currency, e.rateOverride)
                val rate = converter.effectiveRate(e.currency, e.rateOverride)
                val fields = listOf(
                    escape(trip.name),
                    LocalDate.ofEpochDay(e.date).toString(),
                    escape(e.title),
                    escape(categories[e.categoryId]?.name ?: ""),
                    Money.toPlain(e.amountMinor, decimals(e.currency)),
                    escape(e.currency),
                    home?.let { Money.toPlain(it, decimals(trip.homeCurrency)) } ?: "",
                    escape(trip.homeCurrency),
                    rate?.toPlainString() ?: "",
                    escape(e.paymentMethodId?.let { methods[it]?.name } ?: ""),
                    escape(e.note),
                    e.myShareMinor?.let { Money.toPlain(it, decimals(e.currency)) } ?: "",
                    e.spreadDays.toString(),
                    if (e.excludeFromAverage) "yes" else "no",
                )
                sb.append(fields.joinToString(",")).append("\r\n")
            }
        }
        return sb.toString()
    }

    /** Splits CSV text into records and fields, honouring quotes, doubled quotes and line breaks in fields. */
    fun parse(text: String): List<List<String>> {
        val src = text.removePrefix("﻿")
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endRecord() {
            fields.add(field.toString())
            field.setLength(0)
            if (fields.size > 1 || fields[0].isNotEmpty()) records.add(fields)
            fields = ArrayList()
        }
        while (i < src.length) {
            val c = src[i]
            when {
                quoted && c == '"' && i + 1 < src.length && src[i + 1] == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> { fields.add(field.toString()); field.setLength(0) }
                c == '\r' -> { if (i + 1 < src.length && src[i + 1] == '\n') i++; endRecord() }
                c == '\n' -> endRecord()
                else -> field.append(c)
            }
            i++
        }
        if (quoted) throw BackupException("The CSV file ends in the middle of a quoted field.")
        if (field.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    /**
     * Reads a CSV file in the layout this app exports (columns are found by header name, so their
     * order and any extra columns do not matter). Throws [BackupException] naming the first bad line.
     */
    fun read(text: String): List<CsvRow> {
        val records = parse(text)
        if (records.isEmpty()) throw BackupException("The CSV file is empty.")
        val header = records[0].map { it.trim().lowercase() }
        fun col(name: String, required: Boolean = true): Int {
            val index = header.indexOf(name.lowercase())
            if (index < 0 && required) throw BackupException("The CSV file has no \"$name\" column. Only files exported by Trip Ledger can be imported.")
            return index
        }
        val cTrip = col("Trip"); val cDate = col("Date"); val cTitle = col("Title"); val cCategory = col("Category")
        val cAmount = col("Amount"); val cCurrency = col("Currency")
        val cHome = col("Home currency", false); val cRate = col("Exchange rate", false)
        val cMethod = col("Payment method", false); val cNote = col("Note", false)
        val cShare = col("My share", false); val cSpread = col("Spread days", false)
        val cExcluded = col("Excluded from average", false)

        return records.drop(1).mapIndexed { index, r ->
            val line = index + 2
            fun get(c: Int): String = if (c in r.indices) unguard(r[c].trim()) else ""
            fun bad(what: String): Nothing = throw BackupException("Line $line of the CSV file: $what.")
            val date = runCatching { LocalDate.parse(get(cDate)).toEpochDay() }.getOrElse { bad("\"${get(cDate)}\" is not a date like 2026-10-14") }
            if (get(cTrip).isEmpty()) bad("the trip name is missing")
            val currency = get(cCurrency).uppercase()
            if (!Regex("[A-Z]{3,4}").matches(currency)) bad("\"${get(cCurrency)}\" is not a currency code")
            val amount = get(cAmount)
            if (Money.normalizeDecimal(amount.removePrefix("-")) == null) bad("\"$amount\" is not an amount")
            CsvRow(
                line = line,
                trip = get(cTrip),
                date = date,
                title = get(cTitle),
                category = get(cCategory),
                amount = amount,
                currency = currency,
                homeCurrency = get(cHome).uppercase(),
                rate = get(cRate),
                paymentMethod = get(cMethod),
                note = if (cNote in r.indices) unguard(r[cNote]) else "",
                myShare = get(cShare),
                spreadDays = get(cSpread).toIntOrNull()?.coerceIn(1, 3660) ?: 1,
                excludeFromAverage = get(cExcluded).lowercase() in setOf("yes", "true", "1"),
            )
        }
    }
}
