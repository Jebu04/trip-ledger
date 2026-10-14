package app.tripledger.data

import androidx.room.withTransaction
import app.tripledger.domain.Dates
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
        if (settings.get(Keys.SEEDED) == "1") return@withLock
        db.withTransaction {
            if (currencies.getAll().isEmpty()) currencies.upsertAll(Defaults.currencies)
            if (categories.getAll().isEmpty()) categories.insertAll(Defaults.categories)
            if (methods.getAll().isEmpty()) methods.insertAll(Defaults.paymentMethods)
            if (settings.get(Keys.HOME_CURRENCY) == null) {
                settings.put(Setting(Keys.HOME_CURRENCY, Defaults.deviceHomeCurrency()))
            }
            settings.put(Setting(Keys.SEEDED, "1"))
        }
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

    suspend fun deleteEntry(id: Long) = entries.delete(id)

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
            clearTables()
            currencies.upsertAll(data.currencies.ifEmpty { Defaults.currencies })
            categories.insertAll(data.categories)
            methods.insertAll(data.paymentMethods)
            trips.insertAll(data.trips)
            trips.insertCurrencies(data.tripCurrencies)
            entries.insertAll(data.entries)
            settings.putAll(data.settings.map { Setting(it.key, it.value) })
            settings.put(Setting(Keys.SEEDED, "1"))
        }
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
        }
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
