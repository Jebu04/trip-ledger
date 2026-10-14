package app.tripledger.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.TripLedgerApp
import app.tripledger.data.Category
import app.tripledger.domain.Dates
import app.tripledger.ui.EntryEditUi
import app.tripledger.ui.EntryEditViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.AppDatePickerDialog
import app.tripledger.ui.components.CategoryBadge
import app.tripledger.ui.components.ConfirmDialog
import app.tripledger.ui.components.DiscardDialog
import app.tripledger.ui.components.Panel
import app.tripledger.ui.components.ReceiptThumbnail
import app.tripledger.ui.components.ReceiptViewer
import kotlinx.coroutines.launch

/**
 * Add or edit an expense. [onDuplicate] is called when the user asks for a copy of the expense
 * being edited; the screen has already dealt with any unsaved changes by then.
 */
@Composable
fun EntryEditScreen(onDone: () -> Unit, onDuplicate: () -> Unit) {
    val vm = appViewModel { repo, handle -> EntryEditViewModel(repo, handle) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref
    val form = ui.form
    val focusManager = LocalFocusManager.current
    val amountFocus = remember { FocusRequester() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val receipts = (LocalContext.current.applicationContext as TripLedgerApp).receipts
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf(false) }
    // What to do once the user agrees to discard their changes: "close" or "duplicate".
    var pendingLeave by rememberSaveable { mutableStateOf("") }
    var viewingPhoto by rememberSaveable { mutableStateOf(false) }

    val save = {
        focusManager.clearFocus()
        vm.save(onDone)
    }
    fun leave(action: String) {
        when {
            ui.dirty -> pendingLeave = action
            action == "duplicate" -> onDuplicate()
            else -> onDone()
        }
    }
    BackHandler(enabled = ui.dirty) { pendingLeave = "close" }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val temp = vm.pendingCapture
        vm.pendingCapture = ""
        if (temp.isNotBlank()) {
            scope.launch {
                if (!taken) {
                    receipts.delete(temp)
                } else {
                    val name = receipts.adoptCapture(temp)
                    if (name != null) vm.setPhoto(name) else snackbar.showSnackbar("The photo could not be saved")
                }
            }
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = receipts.importFrom(uri)
                if (name != null) vm.setPhoto(name) else snackbar.showSnackbar("That picture could not be read")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(if (ui.isEdit) "Edit expense" else "Add expense", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { leave("close") }, modifier = Modifier.testTag("close_entry")) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (ui.isEdit) {
                        IconButton(onClick = { leave("duplicate") }, modifier = Modifier.testTag("duplicate_entry")) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicate expense")
                        }
                        IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete_entry")) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete expense")
                        }
                    }
                    TextButton(onClick = save, enabled = form.ready, modifier = Modifier.testTag("save_entry")) {
                        Text("Save", fontWeight = FontWeight.SemiBold)
                    }
                },
            )
        },
    ) { padding ->
        if (ref == null || !form.ready) return@Scaffold

        // New expense: put the cursor in the amount field straight away, once.
        LaunchedEffect(Unit) {
            if (!ui.isEdit && !focused) {
                focused = true
                runCatching { amountFocus.requestFocus() }
            }
        }

        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AmountPanel(ui, vm, amountFocus, save)

            FieldLabel("Category")
            CategoryGrid(ref.categories, form.categoryId, vm::setCategory)

            OutlinedTextField(
                value = vm.titleText,
                onValueChange = vm::setTitle,
                modifier = Modifier.fillMaxWidth().testTag("title_field"),
                label = { Text("Title") },
                placeholder = { Text(ref.categoryMap[form.categoryId]?.name ?: "What was it?") },
                supportingText = { Text("Optional: left empty, the category name is used.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )

            FieldLabel("Date")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val today = Dates.today()
                FilterChip(
                    selected = form.date == today,
                    onClick = { vm.setDate(today) },
                    label = { Text("Today") },
                    modifier = Modifier.testTag("date_today"),
                )
                FilterChip(
                    selected = form.date == today - 1,
                    onClick = { vm.setDate(today - 1) },
                    label = { Text("Yesterday") },
                    modifier = Modifier.testTag("date_yesterday"),
                )
                AssistChip(
                    onClick = { showDatePicker = true },
                    label = { Text(Dates.short(form.date), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("date_pick").semantics { contentDescription = "Date: ${ref.date(form.date)}. Change date" },
                )
            }

            if (ref.paymentMethods.isNotEmpty()) {
                FieldLabel("Payment method (optional)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ref.paymentMethods.forEach { method ->
                        FilterChip(
                            selected = form.paymentId == method.id,
                            // Tapping the selected chip again clears it.
                            onClick = { vm.setPayment(if (form.paymentId == method.id) 0 else method.id) },
                            label = { Text(method.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }

            MoreOptions(
                ui, vm,
                onTakePhoto = {
                    val (name, uri) = receipts.newCaptureTarget()
                    vm.pendingCapture = name
                    try {
                        takePicture.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        vm.pendingCapture = ""
                        scope.launch { snackbar.showSnackbar("No camera app is available") }
                    }
                },
                onChoosePhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onViewPhoto = { viewingPhoto = true },
            )

            Button(onClick = save, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_entry_bottom")) {
                Text(if (ui.isEdit) "Save changes" else "Save expense")
            }
        }
    }

    if (showDatePicker && ref != null) {
        AppDatePickerDialog(
            initial = form.date,
            firstDayOfWeek = ref.settings.firstDayOfWeek,
            onPick = {
                vm.setDate(it)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this expense?",
            text = "You can undo this for a few seconds afterwards.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete(onDone)
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (pendingLeave.isNotEmpty()) {
        DiscardDialog(
            what = if (ui.isEdit) "your changes" else "this expense",
            onDiscard = {
                val action = pendingLeave
                pendingLeave = ""
                if (action == "duplicate") onDuplicate() else onDone()
            },
            onKeepEditing = { pendingLeave = "" },
        )
    }
    if (viewingPhoto && form.photo.isNotBlank()) {
        ReceiptViewer(form.photo, onDismiss = { viewingPhoto = false })
    }
}

@Composable
private fun AmountPanel(ui: EntryEditUi, vm: EntryEditViewModel, amountFocus: FocusRequester, save: () -> Unit) {
    val form = ui.form
    Panel {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = vm.amountText,
                onValueChange = vm::setAmount,
                modifier = Modifier.fillMaxWidth().focusRequester(amountFocus).testTag("amount_field"),
                label = { Text(if (form.refund) "Refund amount" else "Amount") },
                placeholder = { Text("0") },
                prefix = if (form.refund) ({ Text("−") }) else null,
                suffix = { Text(form.currency, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                singleLine = true,
                isError = ui.errors["amount"] != null,
                supportingText = when {
                    ui.errors["amount"] != null -> ({ Text(ui.errors["amount"]!!, modifier = Modifier.testTag("amount_error")) })
                    ui.hint != null -> ({
                        Text(
                            ui.hint,
                            color = if (ui.hintIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("amount_hint"),
                        )
                    })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
            if (ui.currencyOptions.size > 1) {
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.currencyOptions.forEach { code ->
                        FilterChip(
                            selected = code == form.currency,
                            onClick = { vm.setCurrency(code) },
                            label = { Text(code) },
                            modifier = Modifier.testTag("currency_chip_$code"),
                        )
                    }
                }
            }
            // Categories, most used first, in one row that stays visible above the keyboard.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
                modifier = Modifier.testTag("quick_categories"),
            ) {
                items(ui.quickCategories, key = { it.id }) { category ->
                    FilterChip(
                        selected = category.id == form.categoryId,
                        onClick = { vm.setCategory(category.id) },
                        label = { Text(category.name, maxLines = 1) },
                        leadingIcon = { CategoryBadge(category.icon, category.color, size = 22.dp) },
                        modifier = Modifier.testTag("quick_category_${category.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreOptions(
    ui: EntryEditUi,
    vm: EntryEditViewModel,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onViewPhoto: () -> Unit,
) {
    val form = ui.form
    val trip = ui.trip
    val ref = ui.ref ?: return
    Panel {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (form.moreOpen) "Hide options" else "Show options") {
                        vm.setMoreOpen(!form.moreOpen)
                    }
                    .testTag("more_options")
                    .semantics { stateDescription = if (form.moreOpen) "Expanded" else "Collapsed" }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("More options", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Refund, shared bill, spread over days, own rate, receipt photo, note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (form.moreOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            if (!form.moreOpen) return@Column

            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchLine(
                    "This is a refund",
                    "Money back: it is subtracted from your totals.",
                    form.refund, vm::setRefund, "refund_switch",
                )

                Column {
                    OutlinedTextField(
                        value = vm.shareText,
                        onValueChange = vm::setShare,
                        modifier = Modifier.fillMaxWidth().testTag("share_field"),
                        label = { Text("My share (optional)") },
                        placeholder = { Text("All of it") },
                        suffix = { Text(form.currency) },
                        singleLine = true,
                        isError = ui.errors["share"] != null,
                        supportingText = { Text(ui.errors["share"] ?: "For a shared bill: only your part counts in totals.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4).forEach { people ->
                            AssistChip(
                                onClick = { vm.splitBetween(people) },
                                label = { Text("Split $people ways") },
                                modifier = Modifier.testTag("split_$people"),
                            )
                        }
                    }
                }

                Column {
                    OutlinedTextField(
                        value = vm.spreadText,
                        onValueChange = vm::setSpread,
                        modifier = Modifier.fillMaxWidth().testTag("spread_field"),
                        label = { Text("Spread over (days)") },
                        placeholder = { Text("1") },
                        singleLine = true,
                        isError = ui.errors["spread"] != null,
                        supportingText = {
                            Text(ui.errors["spread"] ?: "Shares the cost out day by day from its date, e.g. a hotel paid up front.")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    )
                    if (trip != null) {
                        // From this expense's date to the last day of the trip.
                        val remaining = (trip.endDate - form.date + 1).coerceIn(1, ref.tripDays(trip).toLong()).toInt()
                        AssistChip(
                            onClick = { vm.setSpread(remaining.toString()) },
                            label = { Text("Rest of the trip ($remaining ${if (remaining == 1) "day" else "days"})") },
                            modifier = Modifier.testTag("spread_rest_of_trip"),
                        )
                    }
                }

                SwitchLine(
                    "Leave out of the daily average",
                    "It still counts in the total. Useful for flights and other one-off costs.",
                    form.exclude, vm::setExclude, "exclude_switch",
                )

                if (trip != null && form.currency != trip.homeCurrency) {
                    OutlinedTextField(
                        value = vm.rateText,
                        onValueChange = vm::setRate,
                        modifier = Modifier.fillMaxWidth().testTag("entry_rate_field"),
                        label = { Text("Rate for this expense: 1 ${trip.homeCurrency} =") },
                        placeholder = { Text(ui.tripRate.ifBlank { "No trip rate" }) },
                        suffix = { Text(form.currency) },
                        singleLine = true,
                        isError = ui.errors["rate"] != null,
                        supportingText = { Text(ui.errors["rate"] ?: "Optional. Leave empty to use the trip's rate.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    )
                }

                FieldLabel("Receipt photo")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (form.photo.isNotBlank()) {
                        ReceiptThumbnail(form.photo, onClick = onViewPhoto)
                        TextButton(onClick = { vm.setPhoto("") }, modifier = Modifier.testTag("remove_photo")) { Text("Remove") }
                    } else {
                        OutlinedButton(onClick = onTakePhoto, modifier = Modifier.testTag("take_photo")) {
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Camera")
                        }
                        OutlinedButton(onClick = onChoosePhoto, modifier = Modifier.testTag("choose_photo")) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Gallery")
                        }
                    }
                }

                OutlinedTextField(
                    value = vm.noteText,
                    onValueChange = vm::setNote,
                    modifier = Modifier.fillMaxWidth().testTag("note_field"),
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 0.dp),
    )
}

@Composable
private fun CategoryGrid(categories: List<Category>, selectedId: Long, onSelect: (Long) -> Unit) {
    Panel {
        Column(Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
            categories.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { category ->
                        val isSelected = category.id == selectedId
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelect(category.id) }
                                .semantics {
                                    selected = isSelected
                                    contentDescription = "Category ${category.name}"
                                }
                                .testTag("category_${category.name}")
                                .padding(vertical = 5.dp, horizontal = 1.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.border(
                                    width = 2.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape,
                                ).padding(4.dp),
                            ) { CategoryBadge(category.icon, category.color, size = 42.dp) }
                            Spacer(Modifier.height(2.dp))
                            // Two lines and tight spacing so long names like "Accommodation" fit on narrow phones.
                            Text(
                                category.name,
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Keep the last row's columns the same width as the others.
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
