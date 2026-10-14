package app.tripledger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.RefData
import app.tripledger.data.Trip
import app.tripledger.domain.ConvertedEntry
import app.tripledger.domain.DayGroup
import app.tripledger.ui.EntriesUi
import app.tripledger.ui.EntriesViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.EmptyState
import app.tripledger.ui.components.EntryRow
import app.tripledger.ui.components.Panel
import app.tripledger.ui.components.SummaryCard

@Composable
fun EntriesScreen(
    onAdd: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onCreateTrip: () -> Unit,
    onOpenTrips: () -> Unit,
    onEditTrip: (Long) -> Unit,
) {
    val vm = appViewModel { repo, _ -> EntriesViewModel(repo) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref
    val trip = ui.trip

    if (ui.loading || ref == null) return
    if (trip == null) {
        Welcome(onCreateTrip = onCreateTrip, onLoadSample = vm::loadSampleData)
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column(
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpenTrips)
                            .testTag("trip_switcher").padding(end = 4.dp, top = 2.dp, bottom = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                trip.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Switch trip")
                        }
                        Text(
                            "${ref.date(trip.startDate)} – ${ref.date(trip.endDate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_add"),
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add expense") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("entries_list"),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "summary") { SummaryCards(ui, ref, trip) }
            if (ui.summary.unconverted > 0) {
                item(key = "warning") { MissingRateBanner(ui.summary.unconverted) { onEditTrip(trip.id) } }
            }
            if (ui.groups.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        title = "No expenses yet",
                        body = "Tap + to add your first expense for ${trip.name}.",
                    )
                }
            }
            items(ui.groups, key = { it.date }) { group ->
                DaySection(group, ui.today, ref, trip, onOpenEntry)
            }
        }
    }
}

@Composable
private fun SummaryCards(ui: EntriesUi, ref: RefData, trip: Trip) {
    val s = ref.settings
    val home = trip.homeCurrency
    val cards = buildList {
        if (s.showTotal) add("Total" to ref.money(ui.summary.total, home))
        if (s.showDailyAverage) add("Daily average" to ref.money(ui.summary.dailyAverage, home))
        if (s.showToday) add("Today" to ref.money(ui.todayTotal, home))
        if (s.showCount) add("Entries" to ui.summary.count.toString())
    }
    if (cards.isEmpty()) return
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("summary_cards"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.chunked(2).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, (label, value) ->
                    SummaryCard(
                        label = label,
                        value = value,
                        emphasized = rowIndex == 0 && i == 0,
                        modifier = Modifier.weight(1f).testTag("card_$label"),
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingRateBanner(count: Int, onFix: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("missing_rate_banner"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (count == 1) "1 entry isn't in the totals: its currency has no exchange rate."
                else "$count entries aren't in the totals: their currency has no exchange rate.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onFix) { Text("Set rate") }
        }
    }
}

@Composable
private fun DaySection(group: DayGroup, today: Long, ref: RefData, trip: Trip, onOpenEntry: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val prefix = when (group.date) {
                today -> "Today · "
                today - 1 -> "Yesterday · "
                else -> ""
            }
            Text(
                prefix + ref.date(group.date),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                ref.money(group.subtotal, trip.homeCurrency),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("subtotal_${group.date}"),
            )
        }
        Panel {
            Column {
                group.entries.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    EntryListItem(item, ref, trip, subtitleDate = false, showTrip = false, onClick = { onOpenEntry(item.entry.id) })
                }
            }
        }
    }
}

/** An entry row with the amounts worked out. Shared by the entries, statistics and search screens. */
@Composable
fun EntryListItem(
    item: ConvertedEntry,
    ref: RefData,
    trip: Trip,
    subtitleDate: Boolean,
    showTrip: Boolean,
    onClick: () -> Unit,
) {
    val e = item.entry
    val category = ref.categoryMap[e.categoryId]
    val parts = buildList {
        if (subtitleDate) add(ref.date(e.date))
        add(category?.name ?: "No category")
        e.paymentMethodId?.let { id -> ref.paymentMap[id]?.name?.let { add(it) } }
        if (showTrip) add(trip.name)
    }
    val original = ref.money(e.amountMinor, e.currency)
    val home = item.homeMinor
    EntryRow(
        icon = category?.icon,
        color = category?.color ?: 0xFF9E9E9E.toInt(),
        title = e.title,
        subtitle = parts.joinToString(" · "),
        amount = if (home != null) ref.money(home, trip.homeCurrency) else original,
        originalAmount = when {
            home == null -> "No rate"
            e.currency != trip.homeCurrency -> original
            else -> null
        },
        amountMissing = home == null,
        onClick = onClick,
        modifier = Modifier.testTag("entry_row"),
    )
}

@Composable
private fun Welcome(onCreateTrip: () -> Unit, onLoadSample: () -> Unit) {
    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                icon = Icons.Rounded.Luggage,
                title = "Welcome to Trip Ledger",
                body = "Track what you spend while travelling, in any currency. " +
                    "Everything stays on this device and works without internet.",
            )
            Button(onClick = onCreateTrip, modifier = Modifier.testTag("create_first_trip")) {
                Text("Create your first trip")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onLoadSample, modifier = Modifier.testTag("load_sample")) {
                Text("Or explore with sample trips")
            }
        }
    }
}
