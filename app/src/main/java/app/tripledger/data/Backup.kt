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

/** Reads and writes the JSON backup file. Amounts stay integers end to end. */
object Backup {
    const val FORMAT = "tripledger-backup"
    const val VERSION = 1

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
                    .put("endDate", it.endDate).put("homeCurrency", it.homeCurrency).put("createdAt", it.createdAt))
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
                if (it.paymentMethodId != null) o.put("paymentMethodId", it.paymentMethodId)
                put(o)
            }
        })
        return root.toString(2)
    }

    /** Parses a backup file. Throws [BackupException] with a readable message if it is not valid. */
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
                )
            }
            val tripIds = trips.map { it.id }.toSet()
            val tripCurrencies = root.getJSONArray("tripCurrencies").objects().map {
                TripCurrency(it.getLong("tripId"), it.getString("code"), it.optString("rate", ""), it.optInt("sortOrder", 0))
            }.filter { it.tripId in tripIds }
            val methodIds = methods.map { it.id }.toSet()
            val entries = root.getJSONArray("entries").objects().map {
                val pm = if (it.has("paymentMethodId") && !it.isNull("paymentMethodId")) it.getLong("paymentMethodId") else null
                Entry(
                    id = it.getLong("id"), tripId = it.getLong("tripId"), amountMinor = it.getLong("amountMinor"),
                    currency = it.getString("currency"), categoryId = it.getLong("categoryId"),
                    title = it.getString("title"), date = it.getLong("date"), note = it.optString("note", ""),
                    paymentMethodId = pm?.takeIf { id -> id in methodIds }, createdAt = it.optLong("createdAt", 0),
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

/** Builds the CSV export: one row per entry across all trips, readable by any spreadsheet. */
object Csv {
    val HEADER = listOf(
        "Trip", "Date", "Title", "Category", "Amount", "Currency", "Amount (home)", "Home currency",
        "Exchange rate", "Payment method", "Note",
    )

    /** Quotes a field when needed and neutralises text a spreadsheet would run as a formula. */
    fun escape(raw: String, text: Boolean = true): String {
        var s = raw
        if (text && s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

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
                val home = converter.toHome(e.amountMinor, e.currency)
                val rate = converter.rateFor(e.currency)
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
                )
                sb.append(fields.joinToString(",")).append("\r\n")
            }
        }
        return sb.toString()
    }
}
