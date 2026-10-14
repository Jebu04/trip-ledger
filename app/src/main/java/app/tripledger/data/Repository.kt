package app.tripledger.data

import androidx.room.withTransaction
import app.tripledger.domain.Dates
import app.tripledger.domain.Money
import app.tripledger.domain.NumberStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/** The single entry point to stored data. View models talk to this, never to the DAOs. */
class Repository(private val db: AppDatabase) {
    private val trips = db.tripDao()
    private val entries = db.entryDao()
    private val categories = db.categoryDao()
    private val methods = db.paymentMethodDao()
    private val currencies = db.currencyDao()
    private val settings = db.settingDao()
    private val seedMutex = Mutex()

    /** Reference data as one snapshot. Seeds the defaults first on a fresh install. */
    val refData: Flow<RefData> = combine(
        combine(settings.observeAll(), currencies.observeAll(), categories.observeAll()) { s, cur, cat ->
            Triple(s, cur, cat)
        },
        methods.observeAll(),
        trips.observeAll(),
        trips.observeCurrencies(),
    ) { (s, cur, cat), pm, t, tc ->
        val map = s.associate { it.key to it.value }
        // Null until the first-run seed has been written, so screens never see a half-built state.
        if (map[Keys.SEEDED] == "1") RefData(AppSettings.fromMap(map), cur, cat, pm, t, tc) else null
    }
        .onStart { ensureSeeded() }
        .filterNotNull()
        .distinctUntilChanged()

    fun entriesForTrip(tripId: Long): Flow<List<Entry>> = entries.observeForTrip(tripId)

    val allEntries: Flow<List<Entry>> = entries.observeAll()

    suspend fun ensureSeeded() = seedMutex.withLock {
        if (settings.get(Keys.SEEDED) == "1") {
            applyLocaleDefaults()
            return@withLock
        }
        db.withTransaction {
            applyLocaleDefaults()
            if (currencies.getAll().isEmpty()) currencies.upsertAll(Defaults.currencies)
            if (categories.getAll().isEmpty()) categories.insertAll(Defaults.categories)
            if (methods.getAll().isEmpty()) methods.insertAll(Defaults.paymentMethods)
            if (settings.get(Keys.HOME_CURRENCY) == null) {
                settings.put(Setting(Keys.HOME_CURRENCY, Defaults.deviceHomeCurrency()))
            }
            settings.put(Setting(Keys.SEEDED, "1"))
        }
    }

    /**
     * Number format and first day of week start out as the phone's region writes them. Only
     * fills in settings that have never been chosen, so it is safe to run on every start.
     */
    private suspend fun applyLocaleDefaults() {
        val locale = Locale.getDefault()
        if (settings.get(Keys.NUMBER_STYLE) == null) settings.put(Setting(Keys.NUMBER_STYLE, NumberStyle.forLocale(locale).key))
        if (settings.get(Keys.FIRST_DAY) == null) settings.put(Setting(Keys.FIRST_DAY, Dates.firstDayFor(locale).name))
    }

    // ---- settings ----

    suspend fun setSetting(key: String, value: String) = settings.put(Setting(key, value))

    suspend fun setSetting(key: String, value: Boolean) = setSetting(key, if (value) "1" else "0")

    suspend fun setActiveTrip(tripId: Long) = setSetting(Keys.ACTIVE_TRIP, tripId.toString())

    // ---- trips ----

    suspend fun getTrip(id: Long): Trip? = trips.get(id)

    suspend fun getTripCurrencies(tripId: Long): List<TripCurrency> = trips.getCurrencies(tripId)

    /**
     * Creates or updates a trip and replaces its local currencies. [locals] is a list of
     * currency code to rate text; the home currency is ignored if present. Returns the trip id.
     */
    suspend fun saveTrip(trip: Trip, locals: List<Pair<String, String>>): Long = db.withTransaction {
        val id = if (trip.id == 0L) {
            trips.insert(trip.copy(createdAt = if (trip.createdAt == 0L) System.currentTimeMillis() else trip.createdAt))
        } else {
            trips.update(trip)
            trip.id
        }
        trips.deleteCurrencies(id)
        trips.insertCurrencies(
            locals.filter { it.first != trip.homeCurrency }.distinctBy { it.first }
                .mapIndexed { i, (code, rate) -> TripCurrency(id, code, rate.trim(), i) },
        )
        id
    }

    suspend fun deleteTrip(id: Long) = trips.delete(id)

    suspend fun countEntriesInCurrency(tripId: Long, code: String): Int = entries.countForTripCurrency(tripId, code)

    // ---- entries ----

    suspend fun getEntry(id: Long): Entry? = entries.get(id)

    suspend fun saveEntry(entry: Entry): Long =
        if (entry.id == 0L) {
            entries.insert(entry.copy(createdAt = if (entry.createdAt == 0L) System.currentTimeMillis() else entry.createdAt))
        } else {
            entries.update(entry)
            entry.id
        }

    private val _recentlyDeleted = MutableStateFlow<Entry?>(null)

    /** The expense deleted most recently, kept so the deletion can be undone. */
    val recentlyDeleted: StateFlow<Entry?> = _recentlyDeleted

    suspend fun deleteEntry(id: Long) {
        val entry = entries.get(id) ?: return
        entries.delete(id)
        _recentlyDeleted.value = entry
    }

    /** Puts the last deleted expense back exactly as it was. Returns false if there is nothing to undo. */
    suspend fun undoDelete(): Boolean {
        val entry = _recentlyDeleted.value ?: return false
        _recentlyDeleted.value = null
        if (trips.get(entry.tripId) == null || entries.get(entry.id) != null) return false
        entries.insert(entry)
        return true
    }

    fun clearUndo() {
        _recentlyDeleted.value = null
    }

    /** How many expenses use each category. */
    suspend fun categoryUsage(): Map<Long, Int> = entries.categoryUsage().associate { it.categoryId to it.uses }

    /** File names of all receipt photos still attached to an expense. */
    suspend fun photoNames(): Set<String> = entries.photos().toSet()

    suspend fun lastCurrency(tripId: Long): String? = entries.lastCurrency(tripId)

    // ---- categories ----

    suspend fun saveCategory(category: Category): Long =
        if (category.id == 0L) {
            val next = (categories.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
            categories.insert(category.copy(sortOrder = next))
        } else {
            categories.update(category)
            category.id
        }

    suspend fun countEntriesInCategory(id: Long): Int = entries.countForCategory(id)

    /**
     * Deletes a category and moves its entries to [moveTo]. The last remaining category cannot
     * be deleted. Returns false when nothing was deleted.
     */
    suspend fun deleteCategory(id: Long, moveTo: Long): Boolean = db.withTransaction {
        val all = categories.getAll()
        if (all.size <= 1 || all.none { it.id == id } || moveTo == id || all.none { it.id == moveTo }) {
            return@withTransaction false
        }
        entries.reassignCategory(id, moveTo)
        categories.delete(id)
        if (settings.get(Keys.DEFAULT_CATEGORY)?.toLongOrNull() == id) settings.put(Setting(Keys.DEFAULT_CATEGORY, "0"))
        true
    }

    /** Moves a category one place up (-1) or down (+1) in the list. */
    suspend fun moveCategory(id: Long, delta: Int) = db.withTransaction {
        val list = categories.getAll().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return@withTransaction
        list.add(to, list.removeAt(from))
        categories.updateAll(list.mapIndexed { i, c -> c.copy(sortOrder = i) })
    }

    /** Moves a category to position [index] in the list (used by drag-and-drop). */
    suspend fun moveCategoryTo(id: Long, index: Int) = db.withTransaction {
        val list = categories.getAll().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@withTransaction
        list.add(index.coerceIn(0, list.size - 1), list.removeAt(from))
        categories.updateAll(list.mapIndexed { i, c -> c.copy(sortOrder = i) })
    }

    // ---- payment methods ----

    suspend fun savePaymentMethod(method: PaymentMethod): Long =
        if (method.id == 0L) {
            val next = (methods.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
            methods.insert(method.copy(sortOrder = next))
        } else {
            methods.update(method)
            method.id
        }

    suspend fun deletePaymentMethod(id: Long) = db.withTransaction {
        entries.clearPaymentMethod(id)
        methods.delete(id)
        if (settings.get(Keys.DEFAULT_PAYMENT)?.toLongOrNull() == id) settings.put(Setting(Keys.DEFAULT_PAYMENT, "0"))
    }

    suspend fun movePaymentMethod(id: Long, delta: Int) = db.withTransaction {
        val list = methods.getAll().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return@withTransaction
        list.add(to, list.removeAt(from))
        methods.updateAll(list.mapIndexed { i, m -> m.copy(sortOrder = i) })
    }

    suspend fun movePaymentMethodTo(id: Long, index: Int) = db.withTransaction {
        val list = methods.getAll().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return@withTransaction
        list.add(index.coerceIn(0, list.size - 1), list.removeAt(from))
        methods.updateAll(list.mapIndexed { i, m -> m.copy(sortOrder = i) })
    }

    // ---- currencies ----

    suspend fun saveCurrency(currency: Currency) {
        val existing = currencies.get(currency.code)
        val order = existing?.sortOrder ?: ((currencies.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1)
        // Fraction digits are fixed once a currency exists: stored amounts depend on them.
        currencies.upsert(currency.copy(sortOrder = order, decimals = existing?.decimals ?: currency.decimals))
    }

    /** How many trips and entries use a currency; it can only be deleted when this is zero. */
    suspend fun currencyUsage(code: String): Int =
        trips.countWithHome(code) + trips.countWithLocal(code) + entries.countForCurrency(code)

    suspend fun deleteCurrency(code: String): Boolean = db.withTransaction {
        if (currencyUsage(code) > 0 || settings.get(Keys.HOME_CURRENCY) == code) return@withTransaction false
        currencies.delete(code)
        if (settings.get(Keys.DEFAULT_CURRENCY) == code) settings.put(Setting(Keys.DEFAULT_CURRENCY, ""))
        true
    }

    // ---- backup, export, reset ----

    suspend fun snapshot(): BackupData = db.withTransaction {
        BackupData(
            settings = settings.getAll().associate { it.key to it.value },
            currencies = currencies.getAll(),
            categories = categories.getAll(),
            paymentMethods = methods.getAll(),
            trips = trips.getAll(),
            tripCurrencies = trips.getAllCurrencies(),
            entries = entries.getAll(),
        )
    }

    suspend fun exportBackup(): String = Backup.toJson(snapshot(), LocalDateTime.now().withNano(0).toString())

    suspend fun exportCsv(): String = Csv.build(snapshot())

    /** Replaces everything with the contents of a backup. Nothing changes if the text is invalid. */
    suspend fun importBackup(json: String) {
        val data = Backup.fromJson(json)
        db.withTransaction {
            // The automatic-backup folder belongs to this phone, not to the file being restored.
            val deviceKeys = listOf(Keys.AUTO_BACKUP_FOLDER, Keys.AUTO_BACKUP_INTERVAL, Keys.AUTO_BACKUP_LAST, Keys.AUTO_BACKUP_STATUS)
            val keep = deviceKeys.mapNotNull { key -> settings.get(key)?.let { Setting(key, it) } }
            clearTables()
            currencies.upsertAll(data.currencies.ifEmpty { Defaults.currencies })
            categories.insertAll(data.categories)
            methods.insertAll(data.paymentMethods)
            trips.insertAll(data.trips)
            trips.insertCurrencies(data.tripCurrencies)
            entries.insertAll(data.entries)
            settings.putAll(data.settings.filterKeys { it !in deviceKeys }.map { Setting(it.key, it.value) })
            settings.putAll(keep)
            settings.put(Setting(Keys.SEEDED, "1"))
            applyLocaleDefaults()
        }
        _recentlyDeleted.value = null
    }

    /**
     * Adds the contents of a backup to what is already here instead of replacing it. Trips are
     * matched by name, dates and home currency; categories and payment methods by name. Expenses
     * that are already present (same day, title, amount, currency and creation time) are skipped,
     * so merging the same file twice changes nothing.
     */
    suspend fun mergeBackup(json: String): ImportResult {
        val data = Backup.fromJson(json)
        ensureSeeded()
        return db.withTransaction {
            val known = currencies.getAll().map { it.code }.toSet()
            var order = (currencies.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
            data.currencies.filter { it.code !in known }.forEach { currencies.upsert(it.copy(sortOrder = order++)) }

            val categoryMap = HashMap<Long, Long>()
            data.categories.forEach { c -> categoryMap[c.id] = categoryIdFor(c.name, c.icon, c.color) }
            val methodMap = HashMap<Long, Long>()
            data.paymentMethods.forEach { m -> paymentMethodIdFor(m.name)?.let { methodMap[m.id] = it } }
            val fallbackCategory = categories.getAll().first().id

            var tripsAdded = 0
            var added = 0
            var skipped = 0
            for (trip in data.trips) {
                val locals = data.tripCurrencies.filter { it.tripId == trip.id }
                val existing = trips.getAll().firstOrNull {
                    it.name == trip.name && it.startDate == trip.startDate && it.endDate == trip.endDate &&
                        it.homeCurrency == trip.homeCurrency
                }
                val localId = if (existing != null) {
                    val have = trips.getCurrencies(existing.id).map { it.code }.toSet()
                    trips.insertCurrencies(locals.filter { it.code !in have }.map { it.copy(tripId = existing.id) })
                    existing.id
                } else {
                    tripsAdded++
                    trips.insert(trip.copy(id = 0)).also { id -> trips.insertCurrencies(locals.map { it.copy(tripId = id) }) }
                }
                val present = entries.getForTrip(localId)
                    .map { EntryKey(it.date, it.title, it.amountMinor, it.currency, it.createdAt) }.toMutableSet()
                for (e in data.entries.filter { it.tripId == trip.id }) {
                    val key = EntryKey(e.date, e.title, e.amountMinor, e.currency, e.createdAt)
                    if (!present.add(key)) {
                        skipped++
                        continue
                    }
                    entries.insert(
                        e.copy(
                            id = 0, tripId = localId,
                            categoryId = categoryMap[e.categoryId] ?: fallbackCategory,
                            paymentMethodId = e.paymentMethodId?.let { methodMap[it] },
                        ),
                    )
                    added++
                }
            }
            ImportResult(tripsAdded, added, skipped)
        }
    }

    private data class EntryKey(val date: Long, val title: String, val amountMinor: Long, val currency: String, val createdAt: Long)

    /** Finds a category by name, ignoring case, or creates it at the end of the list. */
    private suspend fun categoryIdFor(name: String, icon: String = "category", color: Int = 0xFF78909C.toInt()): Long {
        val all = categories.getAll()
        all.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.let { return it.id }
        return categories.insert(Category(name = name.trim(), icon = icon, color = color, sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1))
    }

    private suspend fun paymentMethodIdFor(name: String): Long? {
        if (name.isBlank()) return null
        val all = methods.getAll()
        all.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.let { return it.id }
        return methods.insert(PaymentMethod(name = name.trim(), sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1))
    }

    /**
     * Adds expenses from a CSV file in the layout this app exports. Trips, categories, payment
     * methods and currencies that do not exist yet are created by name. An expense whose rate in
     * the file differs from the trip's rate keeps the file's rate as its own. Expenses already
     * present in the trip (same day, title, amount and currency) are skipped.
     */
    suspend fun importCsv(text: String): ImportResult {
        val rows = Csv.read(text)
        ensureSeeded()
        return db.withTransaction {
            val homeDefault = settings.get(Keys.HOME_CURRENCY) ?: "EUR"
            var tripsAdded = 0
            var added = 0
            var skipped = 0
            var stamp = System.currentTimeMillis()
            for ((tripName, tripRows) in rows.groupBy { it.trip }) {
                var trip = trips.getAll().firstOrNull { it.name.equals(tripName, ignoreCase = true) }
                if (trip == null) {
                    val home = tripRows.firstOrNull { it.homeCurrency.isNotBlank() }?.homeCurrency ?: homeDefault
                    ensureCurrency(home)
                    val id = trips.insert(
                        Trip(name = tripName, startDate = tripRows.minOf { it.date }, endDate = tripRows.maxOf { it.date },
                            homeCurrency = home, createdAt = stamp),
                    )
                    trip = trips.get(id)!!
                    tripsAdded++
                }
                val tripRates = trips.getCurrencies(trip.id).associate { it.code to it.rate }.toMutableMap()
                val present = entries.getForTrip(trip.id).map { EntryKey(it.date, it.title, it.amountMinor, it.currency, 0) }.toMutableSet()
                val fallbackCategory = categories.getAll().first().name
                for (row in tripRows) {
                    val decimals = ensureCurrency(row.currency)
                    val negative = row.amount.startsWith("-")
                    val magnitude = Money.parseMinor(row.amount.removePrefix("-"), decimals)
                        ?: throw BackupException("Line ${row.line} of the CSV file: the amount could not be read.")
                    val amount = if (negative) -magnitude else magnitude
                    val share = Money.parseMinor(row.myShare.removePrefix("-"), decimals)?.let { if (negative) -it else it }

                    var override = ""
                    if (row.currency != trip.homeCurrency) {
                        val fileRate = Money.parseRate(row.rate)
                        if (row.currency !in tripRates) {
                            val rate = if (fileRate != null) row.rate else ""
                            trips.insertCurrencies(listOf(TripCurrency(trip.id, row.currency, rate, tripRates.size)))
                            tripRates[row.currency] = rate
                        }
                        val tripRate = Money.parseRate(tripRates[row.currency])
                        if (fileRate != null && (tripRate == null || fileRate.compareTo(tripRate) != 0)) override = row.rate
                    }

                    val categoryName = row.category.ifBlank { fallbackCategory }
                    val title = row.title.ifBlank { categoryName }
                    if (!present.add(EntryKey(row.date, title, amount, row.currency, 0))) {
                        skipped++
                        continue
                    }
                    entries.insert(
                        Entry(
                            tripId = trip.id, amountMinor = amount, currency = row.currency,
                            categoryId = categoryIdFor(categoryName), title = title, date = row.date, note = row.note,
                            paymentMethodId = paymentMethodIdFor(row.paymentMethod), createdAt = stamp++,
                            myShareMinor = share, spreadDays = row.spreadDays,
                            excludeFromAverage = row.excludeFromAverage, rateOverride = override,
                        ),
                    )
                    added++
                }
            }
            ImportResult(tripsAdded, added, skipped)
        }
    }

    /** Makes sure a currency code exists (adding an unknown one with two decimals) and returns its decimals. */
    private suspend fun ensureCurrency(code: String): Int {
        currencies.get(code)?.let { return it.decimals }
        val order = (currencies.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        currencies.upsert(Currency(code, code, code, 2, "", order))
        return 2
    }

    /** Wipes every table and puts the factory defaults back. */
    suspend fun deleteAllData() {
        db.withTransaction {
            clearTables()
            currencies.upsertAll(Defaults.currencies)
            categories.insertAll(Defaults.categories)
            methods.insertAll(Defaults.paymentMethods)
            settings.put(Setting(Keys.HOME_CURRENCY, Defaults.deviceHomeCurrency()))
            settings.put(Setting(Keys.SEEDED, "1"))
            applyLocaleDefaults()
        }
        _recentlyDeleted.value = null
    }

    private suspend fun clearTables() {
        entries.clear()
        trips.clearCurrencies()
        trips.clear()
        categories.clear()
        methods.clear()
        currencies.clear()
        settings.clear()
    }

    /** Adds the demo trips next to whatever already exists and makes the first one active. */
    suspend fun loadSampleData(today: Long = Dates.today()) {
        ensureSeeded()
        db.withTransaction {
            val built = SampleData.build(today, categories.getAll(), methods.getAll())
            var firstId = 0L
            built.trips.forEachIndexed { i, (trip, locals) ->
                val id = trips.insert(trip)
                if (i == 0) firstId = id
                trips.insertCurrencies(locals.map { it.copy(tripId = id) })
                entries.insertAll(built.entries[i].map { it.copy(tripId = id) })
            }
            settings.put(Setting(Keys.ACTIVE_TRIP, firstId.toString()))
        }
    }
}
