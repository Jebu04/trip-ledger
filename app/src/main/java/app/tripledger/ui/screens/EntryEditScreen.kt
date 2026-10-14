package app.tripledger.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.Category
import app.tripledger.domain.Dates
import app.tripledger.ui.EntryEditViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.AppDatePickerDialog
import app.tripledger.ui.components.CategoryBadge
import app.tripledger.ui.components.ConfirmDialog
import app.tripledger.ui.components.Panel

@Composable
fun EntryEditScreen(onDone: () -> Unit) {
    val vm = appViewModel { repo, handle -> EntryEditViewModel(repo, handle) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref
    val form = ui.form
    val focusManager = LocalFocusManager.current
    val amountFocus = remember { FocusRequester() }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var focused by rememberSaveable { mutableStateOf(false) }

    val save = {
        focusManager.clearFocus()
        vm.save(onDone)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(if (ui.isEdit) "Edit expense" else "Add expense") },
                navigationIcon = {
                    IconButton(onClick = onDone, modifier = Modifier.testTag("close_entry")) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (ui.isEdit) {
                        IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("delete_entry")) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete expense")
                        }
                    }
                    TextButton(onClick = save, modifier = Modifier.testTag("save_entry")) {
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
            Panel {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = vm.amountText,
                        onValueChange = vm::setAmount,
                        modifier = Modifier.fillMaxWidth().focusRequester(amountFocus).testTag("amount_field"),
                        label = { Text("Amount") },
                        placeholder = { Text("0") },
                        suffix = { Text(form.currency, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                        singleLine = true,
                        isError = ui.amountError != null,
                        supportingText = when {
                            ui.amountError != null -> ({ Text(ui.amountError!!, modifier = Modifier.testTag("amount_error")) })
                            ui.hint != null -> ({
                                Text(
                                    ui.hint!!,
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
                }
            }

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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    label = { Text(ref.date(form.date)) },
                    leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("date_pick"),
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

            OutlinedTextField(
                value = vm.noteText,
                onValueChange = vm::setNote,
                modifier = Modifier.fillMaxWidth().testTag("note_field"),
                label = { Text("Note (optional)") },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
            text = "This can't be undone.",
            confirmLabel = "Delete",
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
        Column(Modifier.padding(vertical = 10.dp, horizontal = 6.dp)) {
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
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.border(
                                    width = 2.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape,
                                ).padding(5.dp),
                            ) { CategoryBadge(category.icon, category.color, size = 44.dp) }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                category.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                minLines = 2,
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
