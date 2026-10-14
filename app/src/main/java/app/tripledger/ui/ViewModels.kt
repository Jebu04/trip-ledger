package app.tripledger.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
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
import app.tripledger.data.AutoBackup
import app.tripledger.data.BackupException
import app.tripledger.data.Category
import app.tripledger.data.Currency
import app.tripledger.data.Entry
import app.tripledger.data.Keys
import app.tripledger.data.PaymentMethod
import app.tripledger.data.RefData
import app.tripledger.data.Repository
import app.tripledger.data.Trip
import app.tripledger.domain.BudgetStatus
import app.tripledger.domain.CategorySlice
import app.tripledger.domain.ConvertedEntry
import app.tripledger.domain.Dates
import app.tripledger.domain.DayGroup
import app.tripledger.domain.DayTotal
import app.tripledger.domain.GroupSlice
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

/** Keeps only what a typed number can contain, so pasted text cannot break a field. */
private fun numeric(text: String, max: Int = 18): String = text.filter { it.isDigit() || it == '.' || it == ',' }.take(max)

// ---------------------------------------------------------------------------------------------
// App shell
// ---------------------------------------------------------------------------------------------

class MainViewModel(private val repo: Repository) : ViewModel() {
    /** Null until the database has been read (and seeded on first launch). */
    val ref: StateFlow<RefData?> = repo.refData.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The expense that was just deleted, while it can still be brought back. */
    val recentlyDeleted: StateFlow<Entry?> = repo.recentlyDeleted

    fun undoDelete() {
        viewModelScope.launch { repo.undoDelete() }
    }

    fun dismissUndo() = repo.clearUndo()
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
    /** Spending attributed to today, including today's part of anything spread over several days. */
    val todayTotal: Long = 0,
    val today: Long = 0,
    val budget: BudgetStatus? = null,
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
        val summary = Stats.summary(converted, days)
        val todayTotal = Stats.dayTotal(converted, today)
        return EntriesUi(
            loading = false,
            ref = ref,
            trip = trip,
            groups = Stats.groupByDay(converted),
            summary = summary,
            todayTotal = todayTotal,
            today = today,
            budget = Stats.budget(trip.budgetMinor, trip.budgetPerDay, ref.tripDays(trip), summary.total, todayTotal),
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
    val refund: Boolean = false,
    val share: String = "",
    val spread: String = "",
    val exclude: Boolean = false,
    val rate: String = "",
    val photo: String = "",
    /** Whether the "More options" section is open. Not part of what counts as a change. */
    val moreOpen: Boolean = false,
) {
    /** Everything the user can change, as one string, for telling whether the form was edited. */
    fun signature(): String = listOf(
        amount, currency, categoryId, title, date, note, paymentId, refund, share, spread, exclude, rate, photo,
    ).joinToString("\u0001")

    /** True when any of the less common options is in use, so the section should start open. */
    val usesMore: Boolean
        get() = refund || share.isNotBlank() || (spread.toIntOrNull() ?: 1) > 1 || exclude || rate.isNotBlank() ||
            photo.isNotBlank() || note.isNotBlank()
}

data class EntryEditUi(
    val ref: RefData? = null,
    val trip: Trip? = null,
    val form: EntryForm = EntryForm(),
    val isEdit: Boolean = false,
    val currencyOptions: List<String> = emptyList(),
    /** All categories, most used first, for the quick picker next to the amount. */
    val quickCategories: List<Category> = emptyList(),
    /** Live "≈ €12.34" preview, or a warning when the currency has no rate. */
    val hint: String? = null,
    val hintIsWarning: Boolean = false,
    /** Validation messages by field: "amount", "share", "rate", "spread". */
    val errors: Map<String, String> = emptyMap(),
    /** True once something has been changed since the form opened. */
    val dirty: Boolean = false,
    /** The trip's own rate for the chosen currency, shown as the placeholder of the rate field. */
    val tripRate: String = "",
)

class EntryEditViewModel(private val repo: Repository, private val handle: SavedStateHandle) : ViewModel() {
    private val entryId: Long = handle.get<Long>("id") ?: 0L
    private val copyFrom: Long = handle.get<Long>("copyFrom") ?: 0L
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val usage = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private var saving = false

    // Text fields read these directly: typing must update synchronously or keystrokes get lost.
    var amountText by mutableStateOf(handle.get<String>("f_amount") ?: "")
        private set
    var titleText by mutableStateOf(handle.get<String>("f_title") ?: "")
        private set
    var noteText by mutableStateOf(handle.get<String>("f_note") ?: "")
        private set
    var shareText by mutableStateOf(handle.get<String>("f_share") ?: "")
        private set
    var rateText by mutableStateOf(handle.get<String>("f_rate") ?: "")
        private set
    var spreadText by mutableStateOf(handle.get<String>("f_spread") ?: "")
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
        handle.getStateFlow("f_refund", false),
        handle.getStateFlow("f_share", ""),
        handle.getStateFlow("f_spread", ""),
        handle.getStateFlow("f_exclude", false),
        handle.getStateFlow("f_rate", ""),
        handle.getStateFlow("f_photo", ""),
        handle.getStateFlow("f_more", false),
    ) { v ->
        EntryForm(
            v[0] as String, v[1] as String, v[2] as Long, v[3] as String, v[4] as Long,
            v[5] as String, v[6] as Long, v[7] as Long, v[8] as Boolean, v[9] as Boolean,
            v[10] as String, v[11] as String, v[12] as Boolean, v[13] as String, v[14] as String, v[15] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EntryForm())

    private val initial = handle.getStateFlow("f_initial", "")

    val ui: StateFlow<EntryEditUi> = combine(repo.refData, form, errors, usage, initial) { ref, f, errs, uses, start ->
        val trip = ref.trips.firstOrNull { it.id == f.tripId }
        val options = if (trip == null) emptyList() else
            (ref.localCurrencies(trip.id).map { it.code } + trip.homeCurrency + f.currency).filter { it.isNotBlank() }.distinct()
        var hint: String? = null
        var warning = false
        var tripRate = ""
        if (trip != null && f.currency.isNotBlank() && f.currency != trip.homeCurrency) {
            val converter = ref.converter(trip)
            tripRate = ref.localCurrencies(trip.id).firstOrNull { it.code == f.currency }?.rate ?: ""
            if (converter.effectiveRate(f.currency, f.rate) == null) {
                hint = "No exchange rate set for ${f.currency}. This expense won't count towards totals until you add one."
                warning = true
            } else {
                val decimals = ref.currency(f.currency).decimals
                val mine = Money.parseMinor(f.share, decimals) ?: Money.parseMinor(f.amount, decimals)
                val home = mine?.let { converter.toHome(it, f.currency, f.rate) }
                if (home != null) hint = "≈ ${ref.money(if (f.refund) -home else home, trip.homeCurrency)}"
            }
        }
        EntryEditUi(
            ref = ref,
            trip = trip,
            form = f,
            isEdit = entryId != 0L,
            currencyOptions = options,
            // Most used first; ties keep the order chosen in Settings.
            quickCategories = ref.categories.sortedByDescending { uses[it.id] ?: 0 },
            hint = hint,
            hintIsWarning = warning,
            errors = errs,
            dirty = f.ready && start.isNotEmpty() && f.signature() != start,
            tripRate = tripRate,
        )
    }.stateIn(viewModelScope, WhileShown, EntryEditUi())

    init {
        viewModelScope.launch {
            usage.value = repo.categoryUsage()
            if (handle.get<Boolean>("f_ready") == true) return@launch
            val ref = repo.refData.first()
            val source = repo.getEntry(if (entryId != 0L) entryId else copyFrom)
            if (source != null) {
                val decimals = ref.currency(source.currency).decimals
                handle["f_amount"] = Money.toPlain(Math.abs(source.amountMinor), decimals)
                handle["f_refund"] = source.amountMinor < 0
                handle["f_share"] = source.myShareMinor?.let { Money.toPlain(Math.abs(it), decimals) } ?: ""
                handle["f_currency"] = source.currency
                handle["f_category"] = source.categoryId
                handle["f_title"] = source.title
                handle["f_note"] = source.note
                handle["f_payment"] = source.paymentMethodId ?: 0L
                handle["f_trip"] = source.tripId
                handle["f_spread"] = if (source.spreadDays > 1) source.spreadDays.toString() else ""
                handle["f_exclude"] = source.excludeFromAverage
                handle["f_rate"] = source.rateOverride
                if (entryId != 0L) {
                    handle["f_date"] = source.date
                    handle["f_created"] = source.createdAt
                    handle["f_photo"] = source.photo
                } else {
                    // A duplicate is a new expense made today; the receipt photo stays with the original.
                    handle["f_date"] = Dates.today()
                }
                amountText = handle.get<String>("f_amount") ?: ""
                shareText = handle.get<String>("f_share") ?: ""
                spreadText = handle.get<String>("f_spread") ?: ""
                rateText = source.rateOverride
                titleText = source.title
                noteText = source.note
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
            val loaded = snapshot()
            handle["f_more"] = loaded.usesMore
            // A duplicate counts as changed from the start: leaving it should ask before discarding.
            handle["f_initial"] = if (entryId == 0L && copyFrom != 0L) "duplicate" else loaded.signature()
            handle["f_ready"] = true
        }
    }

    /** The form as currently stored, read straight from saved state rather than the async flow. */
    private fun snapshot(): EntryForm = EntryForm(
        amount = handle["f_amount"] ?: "", currency = handle["f_currency"] ?: "", categoryId = handle["f_category"] ?: 0L,
        title = handle["f_title"] ?: "", date = handle["f_date"] ?: 0L, note = handle["f_note"] ?: "",
        paymentId = handle["f_payment"] ?: 0L, tripId = handle["f_trip"] ?: 0L, ready = handle["f_ready"] ?: false,
        refund = handle["f_refund"] ?: false, share = handle["f_share"] ?: "", spread = handle["f_spread"] ?: "",
        exclude = handle["f_exclude"] ?: false, rate = handle["f_rate"] ?: "", photo = handle["f_photo"] ?: "",
        moreOpen = handle["f_more"] ?: false,
    )

    private fun clearError(field: String) {
        if (field in errors.value) errors.value = errors.value - field
    }

    fun setAmount(text: String) {
        amountText = numeric(text)
        handle["f_amount"] = amountText
        clearError("amount")
        clearError("share")
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
    fun setRefund(on: Boolean) { handle["f_refund"] = on }
    fun setExclude(on: Boolean) { handle["f_exclude"] = on }
    fun setMoreOpen(open: Boolean) { handle["f_more"] = open }

    fun setShare(text: String) {
        shareText = numeric(text)
        handle["f_share"] = shareText
        clearError("share")
    }

    /** Sets my share to the amount divided by [people], rounded to the currency's smallest unit. */
    fun splitBetween(people: Int) {
        viewModelScope.launch {
            val f = snapshot()
            val decimals = repo.refData.first().currency(f.currency).decimals
            val amount = Money.parseMinor(f.amount, decimals) ?: return@launch
            setShare(Money.toPlain(Stats.dailyAverage(amount, people), decimals))
        }
    }

    fun setRate(text: String) {
        rateText = numeric(text, 16)
        handle["f_rate"] = rateText
        clearError("rate")
    }

    fun setSpread(text: String) {
        spreadText = text.filter { it.isDigit() }.take(4)
        handle["f_spread"] = spreadText
        clearError("spread")
    }

    fun setPhoto(name: String) { handle["f_photo"] = name }

    /** Name of the temporary file a camera capture is being written to; survives process death. */
    var pendingCapture: String
        get() = handle["f_capture"] ?: ""
        set(value) { handle["f_capture"] = value }

    fun save(onSaved: () -> Unit) {
        if (saving) return
        val f = snapshot()
        if (!f.ready || f.tripId == 0L) return
        viewModelScope.launch {
            val ref = repo.refData.first()
            val decimals = ref.currency(f.currency).decimals
            val problems = HashMap<String, String>()
            val amount = Money.parseMinor(f.amount, decimals)
            if (amount == null || amount <= 0) {
                problems["amount"] = if (f.amount.isBlank()) "Enter an amount" else "Enter an amount greater than zero"
            }
            val share = if (f.share.isBlank()) null else Money.parseMinor(f.share, decimals)
            if (f.share.isNotBlank() && (share == null || share <= 0 || (amount != null && share > amount))) {
                problems["share"] = "Your share must be more than zero and no more than the amount"
            }
            if (f.rate.isNotBlank() && Money.parseRate(f.rate) == null) problems["rate"] = "The rate must be a number greater than zero"
            val spread = if (f.spread.isBlank()) 1 else f.spread.toIntOrNull() ?: 0
            if (spread !in 1..MAX_SPREAD) problems["spread"] = "Enter a number of days from 1 to $MAX_SPREAD"
            if (problems.isNotEmpty()) {
                errors.value = problems
                // Make sure a problem hidden in the collapsed section is visible.
                if (problems.keys.any { it != "amount" }) setMoreOpen(true)
                return@launch
            }
            saving = true
            val sign = if (f.refund) -1 else 1
            val title = f.title.trim().ifEmpty { ref.categoryMap[f.categoryId]?.name ?: "Expense" }
            repo.saveEntry(
                Entry(
                    id = entryId,
                    tripId = f.tripId,
                    amountMinor = sign * amount!!,
                    currency = f.currency,
                    categoryId = f.categoryId,
                    title = title,
                    date = f.date,
                    note = f.note.trim(),
                    paymentMethodId = f.paymentId.takeIf { it > 0 },
                    createdAt = handle.get<Long>("f_created") ?: 0L,
                    myShareMinor = share?.let { sign * it },
                    spreadDays = spread,
                    excludeFromAverage = f.exclude,
                    // A rate on a home-currency expense would mean nothing, so it is not kept.
                    rateOverride = if (f.currency == ref.trips.firstOrNull { it.id == f.tripId }?.homeCurrency) "" else f.rate.trim(),
                    photo = f.photo,
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

    companion object {
        const val MAX_SPREAD = 366
    }
}

// ---------------------------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------------------------

enum class StatsRange(val label: String) {
    ALL("Whole trip"), LAST7("Last 7 days"), WEEK("This week"), MONTH("This month"), CUSTOM("Custom")
}

/** One trip in the trip-to-trip comparison. */
data class TripCompare(val trip: Trip, val total: Long, val dailyAverage: Long, val days: Int, val current: Boolean)

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
    val cumulative: List<DayTotal> = emptyList(),
    /** The trip's whole budget, drawn as a line on the cumulative chart; null when there is none. */
    val budgetLine: Long? = null,
    val byPayment: List<GroupSlice<Long?>> = emptyList(),
    val byCurrency: List<GroupSlice<String>> = emptyList(),
    /** What was paid in each currency, in that currency's own minor units. */
    val currencyOriginals: Map<String, Long> = emptyMap(),
    val biggest: List<ConvertedEntry> = emptyList(),
    val trips: List<TripCompare> = emptyList(),
)

class StatsViewModel(repo: Repository, private val handle: SavedStateHandle) : ViewModel() {
    private val range = handle.getStateFlow("range", StatsRange.ALL.name)
    private val customFrom = handle.getStateFlow("from", NO_DATE)
    private val customTo = handle.getStateFlow("to", NO_DATE)

    val ui: StateFlow<StatsUi> = combine(repo.refData, repo.allEntries, range, customFrom, customTo) { ref, everything, rangeName, cf, ct ->
        val selected = runCatching { StatsRange.valueOf(rangeName) }.getOrDefault(StatsRange.ALL)
        val from = cf.takeIf { it != NO_DATE }
        val to = ct.takeIf { it != NO_DATE }
        val trip = ref.activeTrip
            ?: return@combine StatsUi(loading = false, ref = ref, range = selected, customFrom = from, customTo = to)

        val today = Dates.today()
        val byTrip = everything.groupBy { it.tripId }
        val entries = byTrip[trip.id].orEmpty()
        val all = Stats.convert(entries, ref.converter(trip))
        val first = entries.minOfOrNull { it.date }
        val last = entries.maxOfOrNull { it.date }
        val tripStart = minOf(trip.startDate, first ?: trip.startDate)
        val tripEnd = maxOf(minOf(today, trip.endDate), last ?: Long.MIN_VALUE).coerceAtLeast(tripStart)

        var (start, end) = when (selected) {
            StatsRange.ALL -> tripStart to tripEnd
            StatsRange.LAST7 -> (today - 6) to today
            StatsRange.WEEK -> Dates.startOfWeek(today, ref.settings.firstDayOfWeek) to today
            StatsRange.MONTH -> Dates.startOfMonth(today) to today
            StatsRange.CUSTOM -> {
                // An open end falls back to the trip's own bounds, without crossing the other end.
                val f = from ?: minOf(tripStart, to ?: tripStart)
                f to (to ?: maxOf(tripEnd, f))
            }
        }
        if (end < start) start = end.also { end = start }

        val inRange = all.filter { it.entry.date in start..end }
        // A very long range would make an unreadable chart; show the most recent year of it.
        val perDay = Stats.perDay(inRange, maxOf(start, end - 365), end)
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
            perDay = perDay,
            cumulative = Stats.cumulative(perDay),
            budgetLine = Stats.budget(trip.budgetMinor, trip.budgetPerDay, ref.tripDays(trip), 0, 0)?.tripBudget,
            byPayment = Stats.byPaymentMethod(inRange),
            byCurrency = Stats.byCurrency(inRange),
            currencyOriginals = inRange.groupBy { it.entry.currency }.mapValues { (_, list) -> list.sumOf { it.entry.effectiveMinor } },
            biggest = Stats.biggest(inRange, 5),
            trips = ref.trips.map { t ->
                val list = byTrip[t.id].orEmpty()
                val converted = Stats.convert(list, ref.converter(t))
                val days = Stats.dayCount(t.startDate, t.endDate, today, list.minOfOrNull { it.date }, list.maxOfOrNull { it.date })
                val summary = Stats.summary(converted, days)
                TripCompare(t, summary.total, summary.dailyAverage, days, t.id == trip.id)
            },
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
    val hasDate: Boolean get() = from != null || to != null
    val hasAmount: Boolean get() = minText.isNotBlank() || maxText.isNotBlank()
    val filterCount: Int
        get() = (if (categoryIds.isNotEmpty()) 1 else 0) + (if (hasDate) 1 else 0) + (if (hasAmount) 1 else 0)
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
    fun clearCategories() { handle["cats"] = LongArray(0) }
    fun setFrom(day: Long?) { handle["from"] = day ?: NO_DATE }
    fun setTo(day: Long?) { handle["to"] = day ?: NO_DATE }
    fun setMin(text: String) {
        minText = numeric(text, 15)
        handle["min"] = minText
    }
    fun setMax(text: String) {
        maxText = numeric(text, 15)
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

data class TripRow(val trip: Trip, val total: Long, val count: Int, val unconverted: Int, val budget: BudgetStatus?)

data class TripsUi(val loading: Boolean = true, val ref: RefData? = null, val rows: List<TripRow> = emptyList())

class TripsViewModel(private val repo: Repository) : ViewModel() {
    val ui: StateFlow<TripsUi> = combine(repo.refData, repo.allEntries) { ref, entries ->
        val byTrip = entries.groupBy { it.tripId }
        TripsUi(
            loading = false,
            ref = ref,
            rows = ref.trips.map { trip ->
                val converted = Stats.convert(byTrip[trip.id].orEmpty(), ref.converter(trip))
                val total = Stats.total(converted)
                TripRow(
                    trip, total, converted.size, Stats.unconvertedCount(converted),
                    Stats.budget(trip.budgetMinor, trip.budgetPerDay, ref.tripDays(trip), total, 0),
                )
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
    val budget: String = "",
    val budgetPerDay: Boolean = false,
) {
    fun signature(): String =
        listOf(name, start, end, home, locals.joinToString(";") { "${it.code}=${it.rate}" }, budget, budgetPerDay).joinToString("\u0001")
}

data class TripEditUi(
    val ref: RefData? = null,
    val form: TripForm = TripForm(),
    val isEdit: Boolean = false,
    val nameError: String? = null,
    val budgetError: String? = null,
    val dirty: Boolean = false,
)

class TripEditViewModel(private val repo: Repository, private val handle: SavedStateHandle) : ViewModel() {
    private val tripId: Long = handle.get<Long>("id") ?: 0L
    private val nameError = MutableStateFlow<String?>(null)
    private val budgetError = MutableStateFlow<String?>(null)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    private var saving = false

    var nameText by mutableStateOf(handle.get<String>("t_name") ?: "")
        private set
    var budgetText by mutableStateOf(handle.get<String>("t_budget") ?: "")
        private set

    private val form: StateFlow<TripForm> = combine<Any, TripForm>(
        handle.getStateFlow("t_name", ""),
        handle.getStateFlow("t_start", 0L),
        handle.getStateFlow("t_end", 0L),
        handle.getStateFlow("t_home", ""),
        handle.getStateFlow("t_locals", ArrayList<String>()),
        handle.getStateFlow("t_ready", false),
        handle.getStateFlow("t_budget", ""),
        handle.getStateFlow("t_per_day", false),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        TripForm(
            v[0] as String, v[1] as Long, v[2] as Long, v[3] as String,
            (v[4] as ArrayList<String>).map { LocalRate(it.substringBefore('|'), it.substringAfter('|', "")) },
            v[5] as Boolean, v[6] as String, v[7] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TripForm())

    private val initial = handle.getStateFlow("t_initial", "")

    val ui: StateFlow<TripEditUi> = combine(repo.refData, form, nameError, budgetError, initial) { ref, f, nameErr, budgetErr, start ->
        TripEditUi(ref, f, tripId != 0L, nameErr, budgetErr, f.ready && start.isNotEmpty() && f.signature() != start)
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
                handle["t_per_day"] = existing.budgetPerDay
                handle["t_budget"] = if (existing.budgetMinor > 0) {
                    Money.toPlain(existing.budgetMinor, ref.currency(existing.homeCurrency).decimals)
                } else ""
                nameText = existing.name
                budgetText = handle.get<String>("t_budget") ?: ""
                setLocals(repo.getTripCurrencies(tripId).map { LocalRate(it.code, it.rate) })
            } else {
                val today = Dates.today()
                handle["t_start"] = today
                handle["t_end"] = today + 6
                handle["t_home"] = ref.settings.homeCurrency
            }
            handle["t_initial"] = snapshot().signature()
            handle["t_ready"] = true
        }
    }

    private fun locals(): List<LocalRate> =
        (handle.get<ArrayList<String>>("t_locals") ?: arrayListOf()).map { LocalRate(it.substringBefore('|'), it.substringAfter('|', "")) }

    private fun snapshot(): TripForm = TripForm(
        name = handle["t_name"] ?: "", start = handle["t_start"] ?: 0L, end = handle["t_end"] ?: 0L,
        home = handle["t_home"] ?: "", locals = locals(), ready = handle["t_ready"] ?: false,
        budget = handle["t_budget"] ?: "", budgetPerDay = handle["t_per_day"] ?: false,
    )

    private fun setLocals(list: List<LocalRate>) {
        handle["t_locals"] = ArrayList(list.map { "${it.code}|${it.rate}" })
    }

    fun setName(text: String) {
        nameText = text.take(80)
        handle["t_name"] = nameText
        nameError.value = null
    }

    fun setBudget(text: String) {
        budgetText = numeric(text, 15)
        handle["t_budget"] = budgetText
        budgetError.value = null
    }

    fun setBudgetPerDay(perDay: Boolean) { handle["t_per_day"] = perDay }

    /** Keeps the range valid: moving one end past the other drags the other along. */
    fun setStart(day: Long) {
        handle["t_start"] = day
        if ((handle.get<Long>("t_end") ?: day) < day) handle["t_end"] = day
    }

    fun setEnd(day: Long) {
        handle["t_end"] = day
        if ((handle.get<Long>("t_start") ?: day) > day) handle["t_start"] = day
    }

    fun setHome(code: String) {
        handle["t_home"] = code
        setLocals(locals().filter { it.code != code })
    }

    fun addLocal(currency: Currency) {
        val home = handle.get<String>("t_home") ?: ""
        if (currency.code == home || locals().any { it.code == currency.code }) return
        viewModelScope.launch {
            val ref = repo.refData.first()
            // The saved default rate is only meaningful against the default home currency.
            val rate = if (home == ref.settings.homeCurrency) currency.defaultRate else ""
            setLocals(locals() + LocalRate(currency.code, rate))
        }
    }

    fun setRate(code: String, text: String) {
        val clean = numeric(text, 16)
        setLocals(locals().map { if (it.code == code) it.copy(rate = clean) else it })
    }

    fun removeLocal(code: String) {
        viewModelScope.launch {
            val used = if (tripId == 0L) 0 else repo.countEntriesInCurrency(tripId, code)
            if (used > 0) {
                _messages.tryEmit("$code is used by $used ${if (used == 1) "expense" else "expenses"} on this trip, so it can't be removed.")
            } else {
                setLocals(locals().filter { it.code != code })
            }
        }
    }

    fun save(onSaved: (Long) -> Unit) {
        if (saving) return
        val f = snapshot()
        if (!f.ready) return
        if (f.name.isBlank()) {
            nameError.value = "Give the trip a name"
            return
        }
        viewModelScope.launch {
            val ref = repo.refData.first()
            val budget = if (f.budget.isBlank()) 0L else Money.parseMinor(f.budget, ref.currency(f.home).decimals)
            if (budget == null) {
                budgetError.value = "Enter the budget as a number, or leave it empty"
                return@launch
            }
            saving = true
            val id = repo.saveTrip(
                Trip(
                    id = tripId, name = f.name.trim(), startDate = f.start, endDate = maxOf(f.end, f.start),
                    homeCurrency = f.home, createdAt = handle.get<Long>("t_created") ?: 0L,
                    budgetMinor = budget, budgetPerDay = f.budgetPerDay && budget > 0,
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

    fun moveCategoryTo(id: Long, index: Int) {
        viewModelScope.launch { repo.moveCategoryTo(id, index) }
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

    fun movePaymentMethodTo(id: Long, index: Int) {
        viewModelScope.launch { repo.movePaymentMethodTo(id, index) }
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
                _messages.tryEmit("$code is in use by a trip, an expense or as your home currency, so it can't be deleted.")
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

    private suspend fun readText(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        (resolver.openInputStream(uri) ?: error("Could not open the file")).use {
            // Refuse absurdly large files rather than running out of memory.
            val bytes = it.readLimited(MAX_IMPORT_BYTES + 1)
            if (bytes.size > MAX_IMPORT_BYTES) throw BackupException("The file is too large.")
            String(bytes, Charsets.UTF_8).removePrefix("﻿")
        }
    }

    private fun import(what: String, block: suspend () -> String) {
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: BackupException) {
                "$what failed: ${e.message}"
            } catch (e: Exception) {
                "$what failed: ${e.message ?: "could not read the file"}"
            }
            _messages.tryEmit(message)
        }
    }

    /** Restores a backup: [merge] adds it to the current data, otherwise it replaces everything. */
    fun importBackup(resolver: ContentResolver, uri: Uri, merge: Boolean) = import("Import") {
        val text = readText(resolver, uri)
        if (merge) "Merged: " + repo.mergeBackup(text).describe()
        else {
            repo.importBackup(text)
            "Backup restored"
        }
    }

    fun importCsv(resolver: ContentResolver, uri: Uri) = import("CSV import") {
        "CSV imported: " + repo.importCsv(readText(resolver, uri)).describe()
    }

    /** Remembers the folder for automatic backups and keeps access to it across restarts. */
    fun setBackupFolder(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            repo.setSetting(Keys.AUTO_BACKUP_FOLDER, uri.toString())
            repo.setSetting(Keys.AUTO_BACKUP_STATUS, "")
        }
    }

    fun backupNow(context: Context) {
        viewModelScope.launch {
            val status = AutoBackup.run(context.applicationContext, repo, force = true)
            _messages.tryEmit(status ?: "Nothing to back up")
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

private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
