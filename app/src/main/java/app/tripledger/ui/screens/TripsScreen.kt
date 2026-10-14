package app.tripledger.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.RefData
import app.tripledger.domain.Money
import app.tripledger.ui.LocalRate
import app.tripledger.ui.TripEditViewModel
import app.tripledger.ui.TripRow
import app.tripledger.ui.TripsViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.AppDatePickerDialog
import app.tripledger.ui.components.ConfirmDialog
import app.tripledger.ui.components.DiscardDialog
import androidx.activity.compose.BackHandler
import androidx.compose.material3.FilterChip
import app.tripledger.ui.components.CurrencyPickerDialog
import app.tripledger.ui.components.EmptyState
import app.tripledger.ui.components.Panel

@Composable
fun TripsScreen(onSelected: () -> Unit, onEdit: (Long) -> Unit, onCreate: () -> Unit) {
    val vm = appViewModel { repo, _ -> TripsViewModel(repo) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Trips", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New trip") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_new_trip"),
            )
        },
    ) { padding ->
        if (ui.loading || ref == null) return@Scaffold
        if (ui.rows.isEmpty()) {
            EmptyState(Icons.Rounded.Luggage, "No trips", "Create a trip to start tracking.", Modifier.padding(padding))
            return@Scaffold
        }
        val activeId = ref.activeTrip?.id
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("trips_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(ui.rows, key = { it.trip.id }) { row ->
                TripCard(row, ref, row.trip.id == activeId, { vm.select(row.trip.id, onSelected) }, { onEdit(row.trip.id) })
            }
        }
    }
}

@Composable
private fun TripCard(row: TripRow, ref: RefData, active: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    val trip = row.trip
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).testTag("trip_card"),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (active) Icons.Rounded.Check else Icons.Rounded.Luggage,
                    contentDescription = if (active) "Current trip" else null,
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    trip.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${ref.date(trip.startDate)} – ${ref.date(trip.endDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${ref.money(row.total, trip.homeCurrency)} · ${row.count} ${if (row.count == 1) "entry" else "entries"}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.budget?.let { b ->
                    val over = b.leftForTrip < 0
                    Text(
                        if (over) "${ref.money(-b.leftForTrip, trip.homeCurrency)} over the ${ref.money(b.tripBudget, trip.homeCurrency)} budget"
                        else "${ref.money(b.leftForTrip, trip.homeCurrency)} left of ${ref.money(b.tripBudget, trip.homeCurrency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_trip_${trip.name}")) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit ${trip.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TripEditScreen(onDone: () -> Unit, onCreated: () -> Unit) {
    val vm = appViewModel { repo, handle -> TripEditViewModel(repo, handle) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref
    val form = ui.form
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    // 1 = start date, 2 = end date
    var pickingDate by rememberSaveable { mutableStateOf(0) }
    var pickingHome by rememberSaveable { mutableStateOf(false) }
    var pickingLocal by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = ui.dirty) { confirmDiscard = true }
    val close = { if (ui.dirty) confirmDiscard = true else onDone() }

    val save = {
        focusManager.clearFocus()
        vm.save { if (ui.isEdit) onDone() else onCreated() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(if (ui.isEdit) "Edit trip" else "New trip") },
                navigationIcon = {
                    IconButton(onClick = close, modifier = Modifier.testTag("close_trip")) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (ui.isEdit) {
                        IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete_trip")) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete trip")
                        }
                    }
                    TextButton(onClick = save, enabled = form.ready, modifier = Modifier.testTag("save_trip")) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        if (ref == null || !form.ready) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = vm.nameText,
                onValueChange = vm::setName,
                modifier = Modifier.fillMaxWidth().testTag("trip_name_field"),
                label = { Text("Trip name") },
                placeholder = { Text("e.g. Thailand 2026") },
                singleLine = true,
                isError = ui.nameError != null,
                supportingText = ui.nameError?.let { { Text(it, modifier = Modifier.testTag("trip_name_error")) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            )

            FieldLabel("Dates")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateButton("Start", ref.date(form.start), Modifier.weight(1f).testTag("trip_start")) { pickingDate = 1 }
                DateButton("End", ref.date(form.end), Modifier.weight(1f).testTag("trip_end")) { pickingDate = 2 }
            }

            FieldLabel("Home currency")
            val home = ref.currency(form.home)
            Panel(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { pickingHome = true }.testTag("trip_home_currency")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(home.code, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                    Text(home.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                "Totals and statistics for this trip are shown in ${home.code}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            FieldLabel("Budget (optional)")
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = vm.budgetText,
                        onValueChange = vm::setBudget,
                        modifier = Modifier.fillMaxWidth().testTag("budget_field"),
                        label = { Text(if (form.budgetPerDay) "Budget per day" else "Budget for the whole trip") },
                        placeholder = { Text("No budget") },
                        suffix = { Text(home.code) },
                        singleLine = true,
                        isError = ui.budgetError != null,
                        supportingText = { Text(ui.budgetError ?: "Shown as \"Budget left\" and \"Left today\" on the entries screen.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !form.budgetPerDay,
                            onClick = { vm.setBudgetPerDay(false) },
                            label = { Text("Whole trip") },
                            modifier = Modifier.testTag("budget_whole_trip"),
                        )
                        FilterChip(
                            selected = form.budgetPerDay,
                            onClick = { vm.setBudgetPerDay(true) },
                            label = { Text("Per day") },
                            modifier = Modifier.testTag("budget_per_day"),
                        )
                    }
                }
            }

            FieldLabel("Local currencies and exchange rates")
            Panel(Modifier.fillMaxWidth()) {
                Column {
                    if (form.locals.isEmpty()) {
                        Text(
                            "No local currencies yet. Add the currencies you'll pay in and type the rate you get. " +
                                "Rates are never downloaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    form.locals.forEachIndexed { i, local ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LocalCurrencyRow(local, home.code, ref, { vm.setRate(local.code, it) }, { vm.removeLocal(local.code) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(
                        onClick = { pickingLocal = true },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("add_local_currency"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add currency")
                    }
                }
            }

            Button(onClick = save, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_trip_bottom")) {
                Text(if (ui.isEdit) "Save changes" else "Create trip")
            }
        }
    }

    if (ref != null) {
        if (pickingDate != 0) {
            AppDatePickerDialog(
                initial = if (pickingDate == 1) form.start else form.end,
                firstDayOfWeek = ref.settings.firstDayOfWeek,
                onPick = {
                    if (pickingDate == 1) vm.setStart(it) else vm.setEnd(it)
                    pickingDate = 0
                },
                onDismiss = { pickingDate = 0 },
            )
        }
        if (pickingHome) {
            CurrencyPickerDialog(
                title = "Home currency",
                currencies = ref.currencies,
                onPick = {
                    vm.setHome(it.code)
                    pickingHome = false
                },
                onDismiss = { pickingHome = false },
            )
        }
        if (pickingLocal) {
            CurrencyPickerDialog(
                title = "Add local currency",
                currencies = ref.currencies,
                exclude = form.locals.map { it.code }.toSet() + form.home,
                onPick = {
                    vm.addLocal(it)
                    pickingLocal = false
                },
                onDismiss = { pickingLocal = false },
            )
        }
    }
    if (confirmDiscard) {
        DiscardDialog(
            what = if (ui.isEdit) "your changes" else "this trip",
            onDiscard = {
                confirmDiscard = false
                onDone()
            },
            onKeepEditing = { confirmDiscard = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this trip?",
            text = "\"${form.name}\" and all of its expenses will be deleted. This can't be undone.",
            confirmLabel = "Delete trip",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete(onDone)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DateButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LocalCurrencyRow(local: LocalRate, homeCode: String, ref: RefData, onRate: (String) -> Unit, onRemove: () -> Unit) {
    // Local text state keeps typing smooth; the view model holds the saved copy.
    var text by remember(local.code) { mutableStateOf(local.rate) }
    val invalid = text.isNotBlank() && Money.parseRate(text) == null
    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(local.code, fontWeight = FontWeight.SemiBold)
            Text(
                ref.currency(local.code).name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() || it == '.' || it == ',' }.take(16)
                onRate(text)
            },
            modifier = Modifier.width(190.dp).testTag("rate_${local.code}"),
            label = { Text("1 $homeCode =") },
            suffix = { Text(local.code) },
            singleLine = true,
            isError = invalid,
            supportingText = {
                Text(
                    when {
                        invalid -> "Must be more than 0"
                        text.isBlank() -> "No rate yet"
                        else -> ""
                    },
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        )
        IconButton(onClick = onRemove, modifier = Modifier.testTag("remove_${local.code}")) {
            Icon(Icons.Rounded.Close, contentDescription = "Remove ${local.code}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
