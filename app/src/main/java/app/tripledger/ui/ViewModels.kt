package app.tripledger.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tripledger.TripLedgerApp
import app.tripledger.data.BackupException
import app.tripledger.data.Category
import app.tripledger.data.Currency
import app.tripledger.data.Entry
import app.tripledger.data.PaymentMethod
import app.tripledger.data.RefData
import app.tripledger.data.Repository
import app.tripledger.data.Trip
import app.tripledger.domain.CategorySlice
import app.tripledger.domain.ConvertedEntry
import app.tripledger.domain.Dates
import app.tripledger.domain.DayGroup
import app.tripledger.domain.DayTotal
import app.tripledger.domain.Money
import app.tripledger.domain.SearchFilter
import app.tripledger.domain.Stats
import app.tripledger.domain.Summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Creates a view model wired to the app's [Repository], with saved state for process death. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (Repository, SavedStateHandle) -> VM): VM {
    val app = LocalContext.current.applicationContext as TripLedgerApp
    return viewModel(factory = viewModelFactory { initializer { create(app.repository, createSavedStateHandle()) } })
}

private val WhileShown = SharingStarted.WhileSubscribed(5_000)

// ---------------------------------------------------------------------------------------------
// App shell
// ---------------------------------------------------------------------------------------------

class MainViewModel(repo: Repository) : ViewModel() {
    /** Null until the database has been read (and seeded on first launch). */
    val ref: StateFlow<RefData?> = repo.refData.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

// ---------------------------------------------------------------------------------------------
// Entries list
// ---------------------------------------------------------------------------------------------

data class EntriesUi(
    val loading: Boolean = true,
    val ref: RefData? = null,
    val trip: Trip? = null,
    val groups: List<DayGroup> = emptyList(),
    val summary: Summary = Summary(),
    val todayTotal: Long = 0,
    val today: Long = 0,
)

class EntriesViewModel(private val repo: Repository) : ViewModel() {
    val ui: StateFlow<EntriesUi> = repo.refData.flatMapLatest { ref ->
        val trip = ref.activeTrip
        if (trip == null) flowOf(EntriesUi(loading = false, ref = ref))
        else repo.entriesForTrip(trip.id).map { entries -> build(ref, trip, entries) }
    }.stateIn(viewModelScope, WhileShown, EntriesUi())

    private fun build(ref: RefData, trip: Trip, entries: List<Entry>): EntriesUi {
        val converted = Stats.convert(entries, ref.converter(trip))
        val today = Dates.today()
        val days = Stats.dayCount(
            trip.startDate, trip.endDate, today,
            entries.minOfOrNull { it.date }, entries.maxOfOrNull { it.date },
        )
        return EntriesUi(
            loading = false,
            ref = ref,
            trip = trip,
            groups = Stats.groupByDay(converted),
            summary = Stats.summary(converted, days),
            todayTotal = Stats.total(converted.filter { it.entry.date == today }),
            today = today,
        )
    }

    fun loadSampleData() {
        viewModelScope.launch { repo.loadSampleData() }
    }
}

// ---------------------------------------------------------------------------------------------
// Add / edit entry
// ---------------------------------------------------------------------------------------------

data class EntryForm(
    val amount: String = "",
    val currency: String = "",
    val categoryId: Long = 0,
    val title: String = "",
    val date: Long = 0,
    val note: String = "",
    val paymentId: Long = 0,
    val tripId: Long = 0,
    val ready: Boolean = false,
)

data class EntryEditUi(
    val ref: RefData? = null,
    val trip: Trip? = null,
    val form: EntryForm = EntryForm(),
    val isEdit: Boolean = false,
    val currencyOptions: List<String> = emptyList(),
    /** Live "≈ €12.34" preview, or a warning when the currency has no rate. */
    val hint: String? = null,
    val hintIsWarning: Boolean = false,
    val amountError: String? = null,
)

class EntryEditViewModel(private val repo: Repository, private val handle: SavedStateHandle) : ViewModel() {
    private val entryId: Long = handle.get<Long>("id") ?: 0L
    private val amountError = MutableStateFlow<String?>(null)
    private var saving = false

    // Text fields read these directly: typing must update synchronously or keystrokes get lost.
    var amountText by mutableStateOf(handle.get<String>("f_amount") ?: "")
        private set
    var titleText by mutableStateOf(handle.get<String>("f_title") ?: "")
        private set
    var noteText by mutableStateOf(handle.get<String>("f_note") ?: "")
        private set

    private val form: StateFlow<EntryForm> = combine<Any, EntryForm>(
        handle.getStateFlow("f_amount", ""),
        handle.getStateFlow("f_currency", ""),
        handle.getStateFlow("f_category", 0L),
        handle.getStateFlow("f_title", ""),
        handle.getStateFlow("f_date", 0L),
        handle.getStateFlow("f_note", ""),
        handle.getStateFlow("f_payment", 0L),
        handle.getStateFlow("f_trip", 0L),
        handle.getStateFlow("f_ready", false),
    ) { v ->
        EntryForm(
            v[0] as String, v[1] as String, v[2] as Long, v[3] as String, v[4] as Long,
            v[5] as String, v[6] as Long, v[7] as Long, v[8] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EntryForm())

    val ui: StateFlow<EntryEditUi> = combine(repo.refData, form, amountError) { ref, f, error ->
        val trip = ref.trips.firstOrNull { it.id == f.tripId }
        val options = if (trip == null) emptyList() else
            (ref.localCurrencies(trip.id).map { it.code } + trip.homeCurrency + f.currency).filter { it.isNotBlank() }.distinct()
        var hint: String? = null
        var warning = false
        if (trip != null && f.currency.isNotBlank() && f.currency != trip.homeCurrency) {
            val converter = ref.converter(trip)
            if (!converter.hasRate(f.currency)) {
                hint = "No exchange rate set for ${f.currency}. This entry won't count towards totals until you add one to the trip."
                warning = true
            } else {
                val minor = Money.parseMinor(f.amount, ref.currency(f.currency).decimals)
                val home = minor?.let { converter.toHome(it, f.currency) }
                if (home != null) hint = "≈ ${ref.money(home, trip.homeCurrency)}"
            }
        }
        EntryEditUi(ref, trip, f, entryId != 0L, options, hint, warning, error)
    }.stateIn(viewModelScope, WhileShown, EntryEditUi())

    init {
        viewModelScope.launch {
            if (handle.get<Boolean>("f_ready") == true) return@launch
            val ref = repo.refData.first()
            val existing = if (entryId != 0L) repo.getEntry(entryId) else null
            if (existing != null) {
                handle["f_amount"] = Money.toPlain(existing.amountMinor, ref.currency(existing.currency).decimals)
                handle["f_currency"] = existing.currency
                handle["f_category"] = existing.categoryId
                handle["f_title"] = existing.title
                handle["f_date"] = existing.date
                handle["f_note"] = existing.note
                handle["f_payment"] = existing.paymentMethodId ?: 0L
                handle["f_trip"] = existing.tripId
                handle["f_created"] = existing.createdAt
                amountText = handle.get<String>("f_amount") ?: ""
                titleText = existing.title
                noteText = existing.note
            } else {
                val trip = ref.activeTrip ?: return@launch
                val s = ref.settings
                val options = ref.localCurrencies(trip.id).map { it.code } + trip.homeCurrency
                val last = repo.lastCurrency(trip.id)
                handle["f_currency"] = when {
                    s.defaultCurrency in options -> s.defaultCurrency
                    last != null && last in options -> last
                    else -> options.first()
                }
                handle["f_category"] = (ref.categoryMap[s.defaultCategoryId] ?: ref.categories.firstOrNull())?.id ?: 0L
                handle["f_payment"] = ref.paymentMap[s.defaultPaymentMethodId]?.id ?: 0L
                handle["f_date"] = Dates.today()
                handle["f_trip"] = trip.id
            }
            handle["f_ready"] = true
        }
    }

    fun setAmount(text: String) {
        // Keep only what a number can contain, so pasted text cannot break the field.
        amountText = text.filter { it.isDigit() || it == '.' || it == ',' }.take(18)
        handle["f_amount"] = amountText
        amountError.value = null
    }

    fun setCurrency(code: String) { handle["f_currency"] = code }
    fun setCategory(id: Long) { handle["f_category"] = id }
    fun setTitle(text: String) {
        titleText = text.take(200)
        handle["f_title"] = titleText
    }
    fun setDate(epochDay: Long) { handle["f_date"] = epochDay }
    fun setNote(text: String) {
        noteText = text.take(2000)
        handle["f_note"] = noteText
    }
    fun setPayment(id: Long) { handle["f_payment"] = id }

    fun save(onSaved: () -> Unit) {
        if (saving) return
        val f = form.value
        if (!f.ready || f.tripId == 0L) return
        viewModelScope.launch {
            val ref = repo.refData.first()
            val minor = Money.parseMinor(f.amount, ref.currency(f.currency).decimals)
            if (minor == null || minor <= 0) {
                amountError.value = if (f.amount.isBlank()) "Enter an amount" else "Enter an amount greater than zero"
                return@launch
            }
            saving = true
            val title = f.title.trim().ifEmpty { ref.categoryMap[f.categoryId]?.name ?: "Expense" }
            repo.saveEntry(
                Entry(
                    id = entryId,
                    tripId = f.tripId,
                    amountMinor = minor,
                    currency = f.currency,
                    categoryId = f.categoryId,
                    title = title,
                    date = f.date,
                    note = f.note.trim(),
                    paymentMethodId = f.paymentId.takeIf { it > 0 },
                    createdAt = handle.get<Long>("f_created") ?: 0L,
                ),
            )
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        if (entryId == 0L) return
        viewModelScope.launch {
            repo.deleteEntry(entryId)
            onDeleted()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------------------------

enum class StatsRange(val label: String) {
    ALL("Whole trip"), LAST7("Last 7 days"), WEEK("This week"), MONTH("This month"), CUSTOM("Custom")
}

data class StatsUi(
    val loading: Boolean = true,
    val ref: RefData? = null,
    val trip: Trip? = null,
    val range: StatsRange = StatsRange.ALL,
    val customFrom: Long? = null,
    val customTo: Long? = null,
    val from: Long = 0,
    val to: Long = 0,
    val tripHasEntries: Boolean = false,
    val summary: Summary = Summary(),
    val slices: List<CategorySlice> = emptyList(),
    val perDay: List<DayTotal> = emptyList(),
    val biggest: List<ConvertedEntry> = emptyList(),
)

class StatsViewModel(repo: Repository, private val handle: SavedStateHandle) : ViewModel() {
    private val range = handle.getStateFlow("range", StatsRange.ALL.name)
    private val customFrom = handle.getStateFlow("from", NO_DATE)
    private val customTo = handle.getStateFlow("to", NO_DATE)

    private val tripEntries = repo.refData.flatMapLatest { ref ->
        val trip = ref.activeTrip
        if (trip == null) flowOf(Triple(ref, null, emptyList<Entry>()))
        else repo.entriesForTrip(trip.id).map { Triple(ref, trip, it) }
    }

    val ui: StateFlow<StatsUi> = combine(tripEntries, range, customFrom, customTo) { (ref, trip, entries), rangeName, cf, ct ->
        val selected = runCatching { StatsRange.valueOf(rangeName) }.getOrDefault(StatsRange.ALL)
        val from = cf.takeIf { it != NO_DATE }
        val to = ct.takeIf { it != NO_DATE }
        if (trip == null) return@combine StatsUi(loading = false, ref = ref, range = selected, customFrom = from, customTo = to)

        val all = Stats.convert(entries, ref.converter(trip))
        val today = Dates.today()
        val first = entries.minOfOrNull { it.date }
        val last = entries.maxOfOrNull { it.date }
        val tripStart = minOf(trip.startDate, first ?: trip.startDate)
        val tripEnd = maxOf(minOf(today, trip.endDate), last ?: Long.MIN_VALUE).coerceAtLeast(tripStart)

        var (start, end) = when (selected) {
            StatsRange.ALL -> tripStart to tripEnd
            StatsRange.LAST7 -> (today - 6) to today
            StatsRange.WEEK -> Dates.startOfWeek(today, ref.settings.firstDayOfWeek) to today
            StatsRange.MONTH -> Dates.startOfMonth(today) to today
            StatsRange.CUSTOM -> (from ?: tripStart) to (to ?: maxOf(tripEnd, from ?: tripStart))
        }
        if (end < start) start = end.also { end = start }

        val inRange = all.filter { it.entry.date in start..end }
        StatsUi(
            loading = false,
            ref = ref,
            trip = trip,
            range = selected,
            customFrom = from,
            customTo = to,
            from = start,
            to = end,
            tripHasEntries = entries.isNotEmpty(),
            summary = Stats.summary(inRange, Stats.daysInRange(start, end)),
            slices = Stats.byCategory(inRange),
            // A very long range would make an unreadable chart; show the most recent year of it.
            perDay = Stats.perDay(inRange, maxOf(start, end - 365), end),
            biggest = Stats.biggest(inRange, 5),
        )
    }.stateIn(viewModelScope, WhileShown, StatsUi())

    fun setRange(range: StatsRange) { handle["range"] = range.name }
    fun setCustomFrom(day: Long?) { handle["from"] = day ?: NO_DATE }
    fun setCustomTo(day: Long?) { handle["to"] = day ?: NO_DATE }

    companion object {
        const val NO_DATE = Long.MIN_VALUE
    }
}

// ---------------------------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------------------------

data class SearchCriteria(
    val query: String = "",
    val allTrips: Boolean = false,
    val categoryIds: Set<Long> = emptySet(),
    val from: Long? = null,
    val to: Long? = null,
    val minText: String = "",
    val maxText: String = "",
) {
    val filterCount: Int
        get() = (if (categoryIds.isNotEmpty()) 1 else 0) + (if (from != null || to != null) 1 else 0) +
            (if (minText.isNotBlank() || maxText.isNotBlank()) 1 else 0)
}

data class SearchResult(val item: ConvertedEntry, val trip: Trip)

data class SearchUi(
    val loading: Boolean = true,
    val ref: RefData? = null,
    val criteria: SearchCriteria = SearchCriteria(),
    val results: List<SearchResult> = emptyList(),
    /** Sum of the results, when they all share one home currency. */
    val totalText: String? = null,
    val anyEntries: Boolean = false,
)

class SearchViewModel(repo: Repository, private val handle: SavedStateHandle) : ViewModel() {
    var queryText by mutableStateOf(handle.get<String>("q") ?: "")
        private set
    var minText by mutableStateOf(handle.get<String>("min") ?: "")
        private set
    var maxText by mutableStateOf(handle.get<String>("max") ?: "")
        private set

    private val criteria: StateFlow<SearchCriteria> = combine<Any, SearchCriteria>(
        handle.getStateFlow("q", ""),
        handle.getStateFlow("all", false),
        handle.getStateFlow("cats", LongArray(0)),
        handle.getStateFlow("from", NO_DATE),
        handle.getStateFlow("to", NO_DATE),
        handle.getStateFlow("min", ""),
        handle.getStateFlow("max", ""),
    ) { v ->
        SearchCriteria(
            query = v[0] as String,
            allTrips = v[1] as Boolean,
            categoryIds = (v[2] as LongArray).toSet(),
            from = (v[3] as Long).takeIf { it != NO_DATE },
            to = (v[4] as Long).takeIf { it != NO_DATE },
            minText = v[5] as String,
            maxText = v[6] as String,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchCriteria())

    val ui: StateFlow<SearchUi> = combine(repo.refData, repo.allEntries, criteria) { ref, entries, c ->
        val active = ref.activeTrip
        val trips = if (c.allTrips) ref.trips else listOfNotNull(active)
        val results = ArrayList<SearchResult>()
        for (trip in trips) {
            val homeDecimals = ref.currency(trip.homeCurrency).decimals
            val filter = SearchFilter(
                query = c.query,
                categoryIds = c.categoryIds,
                fromDate = c.from,
                toDate = c.to,
                minMinor = Money.parseMinor(c.minText, homeDecimals),
                maxMinor = Money.parseMinor(c.maxText, homeDecimals),
            )
            val converted = Stats.convert(entries.filter { it.tripId == trip.id }, ref.converter(trip))
            converted.filterTo(ArrayList()) { filter.matches(it, ref.categoryMap[it.entry.categoryId]?.name ?: "") }
                .forEach { results.add(SearchResult(it, trip)) }
        }
        results.sortWith(compareByDescending<SearchResult> { it.item.entry.date }.thenByDescending { it.item.entry.createdAt })
        val homes = results.map { it.trip.homeCurrency }.distinct()
        val total = if (homes.size == 1) ref.money(Stats.total(results.map { it.item }), homes[0]) else null
        SearchUi(
            loading = false,
            ref = ref,
            criteria = c,
            results = results,
            totalText = total,
            anyEntries = entries.any { e -> trips.any { it.id == e.tripId } },
        )
    }.stateIn(viewModelScope, WhileShown, SearchUi())

    fun setQuery(text: String) {
        queryText = text.take(100)
        handle["q"] = queryText
    }
    fun setAllTrips(all: Boolean) { handle["all"] = all }
    fun toggleCategory(id: Long) {
        val current = criteria.value.categoryIds
        handle["cats"] = (if (id in current) current - id else current + id).toLongArray()
    }
    fun setFrom(day: Long?) { handle["from"] = day ?: NO_DATE }
    fun setTo(day: Long?) { handle["to"] = day ?: NO_DATE }
    fun setMin(text: String) {
        minText = text.filter { it.isDigit() || it == '.' || it == ',' }.take(15)
        handle["min"] = minText
    }
    fun setMax(text: String) {
        maxText = text.filter { it.isDigit() || it == '.' || it == ',' }.take(15)
        handle["max"] = maxText
    }
    fun clearFilters() {
        handle["cats"] = LongArray(0)
        handle["from"] = NO_DATE
        handle["to"] = NO_DATE
        handle["min"] = ""
        handle["max"] = ""
        minText = ""
        maxText = ""
    }

    companion object {
        const val NO_DATE = Long.MIN_VALUE
    }
}

// ---------------------------------------------------------------------------------------------
// Trips
// ---------------------------------------------------------------------------------------------

data class TripRow(val trip: Trip, val total: Long, val count: Int, val unconverted: Int)

data class TripsUi(val loading: Boolean = true, val ref: RefData? = null, val rows: List<TripRow> = emptyList())

class TripsViewModel(private val repo: Repository) : ViewModel() {
    val ui: StateFlow<TripsUi> = combine(repo.refData, repo.allEntries) { ref, entries ->
        val byTrip = entries.groupBy { it.tripId }
        TripsUi(
            loading = false,
            ref = ref,
            rows = ref.trips.map { trip ->
                val converted = Stats.convert(byTrip[trip.id].orEmpty(), ref.converter(trip))
                TripRow(trip, Stats.total(converted), converted.size, Stats.unconvertedCount(converted))
            },
        )
    }.stateIn(viewModelScope, WhileShown, TripsUi())

    fun select(tripId: Long, then: () -> Unit) {
        viewModelScope.launch {
            repo.setActiveTrip(tripId)
            then()
        }
    }
}

data class LocalRate(val code: String, val rate: String)

data class TripForm(
    val name: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val home: String = "",
    val locals: List<LocalRate> = emptyList(),
    val ready: Boolean = false,
)

data class TripEditUi(
    val ref: RefData? = null,
    val form: TripForm = TripForm(),
    val isEdit: Boolean = false,
    val nameError: String? = null,
)

class TripEditViewModel(private val repo: Repository, private val handle: SavedStateHandle) : ViewModel() {
    private val tripId: Long = handle.get<Long>("id") ?: 0L
    private val nameError = MutableStateFlow<String?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    private var saving = false

    var nameText by mutableStateOf(handle.get<String>("t_name") ?: "")
        private set

    private val form: StateFlow<TripForm> = combine<Any, TripForm>(
        handle.getStateFlow("t_name", ""),
        handle.getStateFlow("t_start", 0L),
        handle.getStateFlow("t_end", 0L),
        handle.getStateFlow("t_home", ""),
        handle.getStateFlow("t_locals", ArrayList<String>()),
        handle.getStateFlow("t_ready", false),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        TripForm(
            v[0] as String, v[1] as Long, v[2] as Long, v[3] as String,
            (v[4] as ArrayList<String>).map { LocalRate(it.substringBefore('|'), it.substringAfter('|', "")) },
            v[5] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TripForm())

    val ui: StateFlow<TripEditUi> = combine(repo.refData, form, nameError) { ref, f, error ->
        TripEditUi(ref, f, tripId != 0L, error)
    }.stateIn(viewModelScope, WhileShown, TripEditUi())

    init {
        viewModelScope.launch {
            if (handle.get<Boolean>("t_ready") == true) return@launch
            val ref = repo.refData.first()
            val existing = if (tripId != 0L) repo.getTrip(tripId) else null
            if (existing != null) {
                handle["t_name"] = existing.name
                handle["t_start"] = existing.startDate
                handle["t_end"] = existing.endDate
                handle["t_home"] = existing.homeCurrency
                handle["t_created"] = existing.createdAt
                nameText = existing.name
                setLocals(repo.getTripCurrencies(tripId).map { LocalRate(it.code, it.rate) })
            } else {
                val today = Dates.today()
                handle["t_start"] = today
                handle["t_end"] = today + 6
                handle["t_home"] = ref.settings.homeCurrency
            }
            handle["t_ready"] = true
        }
    }

    private fun setLocals(list: List<LocalRate>) {
        handle["t_locals"] = ArrayList(list.map { "${it.code}|${it.rate}" })
    }

    fun setName(text: String) {
        nameText = text.take(80)
        handle["t_name"] = nameText
        nameError.value = null
    }

    /** Keeps the range valid: moving one end past the other drags the other along. */
    fun setStart(day: Long) {
        handle["t_start"] = day
        if (form.value.end < day) handle["t_end"] = day
    }

    fun setEnd(day: Long) {
        handle["t_end"] = day
        if (form.value.start > day) handle["t_start"] = day
    }

    fun setHome(code: String) {
        handle["t_home"] = code
        setLocals(form.value.locals.filter { it.code != code })
    }

    fun addLocal(currency: Currency) {
        val f = form.value
        if (currency.code == f.home || f.locals.any { it.code == currency.code }) return
        viewModelScope.launch {
            val ref = repo.refData.first()
            // The saved default rate is only meaningful against the default home currency.
            val rate = if (f.home == ref.settings.homeCurrency) currency.defaultRate else ""
            setLocals(form.value.locals + LocalRate(currency.code, rate))
        }
    }

    fun setRate(code: String, text: String) {
        val clean = text.filter { it.isDigit() || it == '.' || it == ',' }.take(16)
        setLocals(form.value.locals.map { if (it.code == code) it.copy(rate = clean) else it })
    }

    fun removeLocal(code: String) {
        viewModelScope.launch {
            val used = if (tripId == 0L) 0 else repo.countEntriesInCurrency(tripId, code)
            if (used > 0) {
                _messages.tryEmit("$code is used by $used ${if (used == 1) "entry" else "entries"} on this trip, so it can't be removed.")
            } else {
                setLocals(form.value.locals.filter { it.code != code })
            }
        }
    }

    fun save(onSaved: (Long) -> Unit) {
        if (saving) return
        val f = form.value
        if (!f.ready) return
        if (f.name.isBlank()) {
            nameError.value = "Give the trip a name"
            return
        }
        saving = true
        viewModelScope.launch {
            val id = repo.saveTrip(
                Trip(
                    id = tripId, name = f.name.trim(), startDate = f.start, endDate = maxOf(f.end, f.start),
                    homeCurrency = f.home, createdAt = handle.get<Long>("t_created") ?: 0L,
                ),
                f.locals.map { it.code to it.rate },
            )
            if (tripId == 0L) repo.setActiveTrip(id)
            onSaved(id)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        if (tripId == 0L) return
        viewModelScope.launch {
            repo.deleteTrip(tripId)
            onDeleted()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

class SettingsViewModel(private val repo: Repository) : ViewModel() {
    val ref: StateFlow<RefData?> = repo.refData.stateIn(viewModelScope, WhileShown, null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun set(key: String, value: String) {
        viewModelScope.launch { repo.setSetting(key, value) }
    }

    fun set(key: String, value: Boolean) {
        viewModelScope.launch { repo.setSetting(key, value) }
    }

    // categories
    fun saveCategory(category: Category) {
        viewModelScope.launch { repo.saveCategory(category) }
    }

    fun moveCategory(id: Long, delta: Int) {
        viewModelScope.launch { repo.moveCategory(id, delta) }
    }

    suspend fun entriesInCategory(id: Long): Int = repo.countEntriesInCategory(id)

    fun deleteCategory(id: Long, moveTo: Long) {
        viewModelScope.launch {
            if (!repo.deleteCategory(id, moveTo)) _messages.tryEmit("The last category can't be deleted.")
        }
    }

    // payment methods
    fun savePaymentMethod(method: PaymentMethod) {
        viewModelScope.launch { repo.savePaymentMethod(method) }
    }

    fun movePaymentMethod(id: Long, delta: Int) {
        viewModelScope.launch { repo.movePaymentMethod(id, delta) }
    }

    fun deletePaymentMethod(id: Long) {
        viewModelScope.launch { repo.deletePaymentMethod(id) }
    }

    // currencies
    fun saveCurrency(currency: Currency) {
        viewModelScope.launch { repo.saveCurrency(currency) }
    }

    fun deleteCurrency(code: String) {
        viewModelScope.launch {
            if (!repo.deleteCurrency(code)) {
                _messages.tryEmit("$code is in use by a trip, an entry or as your home currency, so it can't be deleted.")
            }
        }
    }

    // data
    fun exportBackup(resolver: ContentResolver, uri: Uri) = write(resolver, uri, "Backup saved") { repo.exportBackup() }

    /** A byte-order mark makes Excel read the file as UTF-8. */
    fun exportCsv(resolver: ContentResolver, uri: Uri) = write(resolver, uri, "CSV exported") { "﻿" + repo.exportCsv() }

    private fun write(resolver: ContentResolver, uri: Uri, done: String, content: suspend () -> String) {
        viewModelScope.launch {
            val result = runCatching {
                val text = content()
                withContext(Dispatchers.IO) {
                    // "wt" truncates, so overwriting a longer existing file leaves no tail behind.
                    (resolver.openOutputStream(uri, "wt") ?: error("Could not open the file")).use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            _messages.tryEmit(if (result.isSuccess) done else "Export failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
        }
    }

    fun importBackup(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val message = try {
                val text = withContext(Dispatchers.IO) {
                    (resolver.openInputStream(uri) ?: error("Could not open the file")).use {
                        // Refuse absurdly large files rather than running out of memory.
                        val bytes = it.readNBytesCompat(MAX_IMPORT_BYTES + 1)
                        if (bytes.size > MAX_IMPORT_BYTES) throw BackupException("The file is too large to be a backup.")
                        String(bytes, Charsets.UTF_8).removePrefix("﻿")
                    }
                }
                repo.importBackup(text)
                "Backup restored"
            } catch (e: BackupException) {
                "Import failed: ${e.message}"
            } catch (e: Exception) {
                "Import failed: ${e.message ?: "could not read the file"}"
            }
            _messages.tryEmit(message)
        }
    }

    fun loadSampleData() {
        viewModelScope.launch {
            repo.loadSampleData()
            _messages.tryEmit("Sample trips added")
        }
    }

    fun deleteAllData(then: () -> Unit) {
        viewModelScope.launch {
            repo.deleteAllData()
            then()
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 50 * 1024 * 1024
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
