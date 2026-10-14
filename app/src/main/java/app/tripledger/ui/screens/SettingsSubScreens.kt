package app.tripledger.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.Category
import app.tripledger.data.Currency
import app.tripledger.data.PaymentMethod
import app.tripledger.data.RefData
import app.tripledger.domain.Money
import app.tripledger.ui.SettingsViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.CategoryBadge
import app.tripledger.ui.components.CategoryIcons
import app.tripledger.ui.components.ConfirmDialog
import app.tripledger.ui.components.exposeTestTags
import app.tripledger.ui.components.EmptyState
import app.tripledger.ui.theme.CategoryColors

/** Shared frame for the settings sub-screens: back arrow, title, optional add button. */
@Composable
private fun SubScreen(
    title: String,
    onBack: () -> Unit,
    vm: SettingsViewModel,
    onAdd: (() -> Unit)? = null,
    addDescription: String = "Add",
    content: @Composable (PaddingValues, RefData) -> Unit,
) {
    val refState by vm.ref.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (onAdd != null) {
                FloatingActionButton(
                    onClick = onAdd,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_add_item"),
                ) { Icon(Icons.Rounded.Add, contentDescription = addDescription) }
            }
        },
    ) { padding ->
        val ref = refState ?: return@Scaffold
        content(padding, ref)
    }
}

@Composable
private fun ReorderButtons(index: Int, count: Int, name: String, onMove: (Int) -> Unit) {
    IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move $name up")
    }
    IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move $name down")
    }
}

@Composable
private fun ListCard(index: Int, count: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // First and last rows get the rounded corners so the list reads as one panel.
    val r = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp, bottomEnd = if (index == count - 1) r else 0.dp,
    )
    Surface(modifier = modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 1.dp), shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer, content = content)
}

// ---------------------------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------------------------

@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val vm = appViewModel { repo, _ -> SettingsViewModel(repo) }
    // -1 = closed, 0 = new category, otherwise the id being edited
    var editingId by rememberSaveable { mutableLongStateOf(-1L) }
    var deletingId by rememberSaveable { mutableLongStateOf(-1L) }

    SubScreen("Categories", onBack, vm, onAdd = { editingId = 0 }, addDescription = "Add category") { padding, ref ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("categories_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        ) {
            itemsIndexed(ref.categories, key = { _, c -> c.id }) { index, category ->
                ListCard(index, ref.categories.size) {
                    Row(
                        Modifier.clickable { editingId = category.id }.testTag("category_row")
                            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryBadge(category.icon, category.color, size = 38.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ReorderButtons(index, ref.categories.size, category.name) { vm.moveCategory(category.id, it) }
                    }
                }
            }
        }

        if (editingId >= 0) {
            val existing = ref.categoryMap[editingId]
            CategoryDialog(
                existing = existing,
                canDelete = existing != null && ref.categories.size > 1,
                onSave = {
                    vm.saveCategory(it)
                    editingId = -1
                },
                onDelete = {
                    deletingId = editingId
                    editingId = -1
                },
                onDismiss = { editingId = -1 },
            )
        }
        ref.categoryMap[deletingId]?.let { category ->
            DeleteCategoryDialog(category, ref.categories.filter { it.id != category.id }, vm, onDone = { deletingId = -1 })
        }
    }
}

@Composable
private fun CategoryDialog(
    existing: Category?,
    canDelete: Boolean,
    onSave: (Category) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var icon by rememberSaveable { mutableStateOf(existing?.icon ?: "category") }
    var color by rememberSaveable { mutableIntStateOf(existing?.color ?: CategoryColors[5]) }
    var nameError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(if (existing == null) "New category" else "Edit category") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(icon, color, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it.take(40)
                            nameError = false
                        },
                        modifier = Modifier.weight(1f).testTag("category_name_field"),
                        label = { Text("Name") },
                        singleLine = true,
                        isError = nameError,
                        supportingText = if (nameError) ({ Text("Enter a name") }) else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Keep a colour that is not in the palette (e.g. from an imported backup) selectable.
                    (if (color in CategoryColors) CategoryColors else listOf(color) + CategoryColors).forEachIndexed { i, value ->
                        ColorSwatch(value, value == color, "Colour ${i + 1}", Modifier.size(36.dp).testTag("color_$i")) { color = value }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryIcons.all.forEach { (key, vector) ->
                        val selected = key == icon
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (selected) Color(color) else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { icon = key }
                                .testTag("icon_$key")
                                .semantics { contentDescription = "Icon $key" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                vector, contentDescription = null, modifier = Modifier.size(22.dp),
                                tint = if (selected) app.tripledger.ui.theme.contentColorOn(Color(color)) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) nameError = true
                    else onSave((existing ?: Category(name = "", icon = "", color = 0)).copy(name = name.trim(), icon = icon, color = color))
                },
                modifier = Modifier.testTag("save_category"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("delete_category"),
                    ) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DeleteCategoryDialog(category: Category, others: List<Category>, vm: SettingsViewModel, onDone: () -> Unit) {
    var count by remember { mutableIntStateOf(-1) }
    var target by rememberSaveable { mutableLongStateOf(others.firstOrNull()?.id ?: 0L) }
    LaunchedEffect(category.id) { count = vm.entriesInCategory(category.id) }
    if (count < 0) return

    AlertDialog(
        onDismissRequest = onDone,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Delete \"${category.name}\"?") },
        text = {
            Column {
                if (count == 0) {
                    Text("No expenses use this category.")
                } else {
                    Text("$count ${if (count == 1) "expense uses" else "expenses use"} this category. Move ${if (count == 1) "it" else "them"} to:")
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        others.forEach { other ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .selectable(selected = other.id == target, role = Role.RadioButton, onClick = { target = other.id }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = other.id == target, onClick = null, modifier = Modifier.padding(10.dp))
                                Text(other.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.deleteCategory(category.id, target)
                    onDone()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_button"),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------
// Payment methods
// ---------------------------------------------------------------------------------------------

@Composable
fun PaymentMethodsScreen(onBack: () -> Unit) {
    val vm = appViewModel { repo, _ -> SettingsViewModel(repo) }
    var editingId by rememberSaveable { mutableLongStateOf(-1L) }
    var deletingId by rememberSaveable { mutableLongStateOf(-1L) }

    SubScreen("Payment methods", onBack, vm, onAdd = { editingId = 0 }, addDescription = "Add payment method") { padding, ref ->
        if (ref.paymentMethods.isEmpty()) {
            EmptyState(
                Icons.Rounded.Add, "No payment methods",
                "Payment methods are optional. Add one with the + button.", Modifier.padding(padding),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("payments_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        ) {
            itemsIndexed(ref.paymentMethods, key = { _, m -> m.id }) { index, method ->
                ListCard(index, ref.paymentMethods.size) {
                    Row(
                        Modifier.clickable { editingId = method.id }.testTag("payment_row")
                            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(method.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ReorderButtons(index, ref.paymentMethods.size, method.name) { vm.movePaymentMethod(method.id, it) }
                        IconButton(onClick = { deletingId = method.id }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${method.name}")
                        }
                    }
                }
            }
        }

        if (editingId >= 0) {
            val existing = ref.paymentMap[editingId]
            NameDialog(
                title = if (existing == null) "New payment method" else "Rename payment method",
                initial = existing?.name ?: "",
                onSave = {
                    vm.savePaymentMethod((existing ?: PaymentMethod(name = "")).copy(name = it))
                    editingId = -1
                },
                onDismiss = { editingId = -1 },
            )
        }
        ref.paymentMap[deletingId]?.let { method ->
            ConfirmDialog(
                "Delete \"${method.name}\"?",
                "Expenses that used it are kept, with no payment method.",
                "Delete",
                destructive = true,
                onConfirm = {
                    vm.deletePaymentMethod(method.id)
                    deletingId = -1
                },
                onDismiss = { deletingId = -1 },
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(40)
                    error = false
                },
                modifier = Modifier.fillMaxWidth().testTag("name_field"),
                label = { Text("Name") },
                singleLine = true,
                isError = error,
                supportingText = if (error) ({ Text("Enter a name") }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isBlank()) error = true else onSave(name.trim()) }, modifier = Modifier.testTag("save_name")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------
// Currencies
// ---------------------------------------------------------------------------------------------

@Composable
fun CurrenciesScreen(onBack: () -> Unit) {
    val vm = appViewModel { repo, _ -> SettingsViewModel(repo) }
    // "" = closed, "+" = new currency, otherwise the code being edited
    var editing by rememberSaveable { mutableStateOf("") }

    SubScreen("Currencies", onBack, vm, onAdd = { editing = "+" }, addDescription = "Add currency") { padding, ref ->
        val home = ref.settings.homeCurrency
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("currencies_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        ) {
            item(key = "#intro") {
                Text(
                    "A default rate is filled in automatically when you add the currency to a new trip whose home currency is $home. " +
                        "Each trip keeps its own rates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
                )
            }
            itemsIndexed(ref.currencies, key = { _, c -> c.code }) { index, currency ->
                ListCard(index, ref.currencies.size) {
                    Row(
                        Modifier.clickable { editing = currency.code }.testTag("currency_row_${currency.code}")
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(currency.code, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(currency.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val rate = when {
                                currency.code == home -> "Home currency"
                                currency.defaultRate.isBlank() -> "No default rate"
                                else -> "1 $home = ${currency.defaultRate} ${currency.code}"
                            }
                            Text(rate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(currency.symbol, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (editing.isNotEmpty()) {
            val existing = ref.currencyMap[editing]
            CurrencyDialog(
                existing = existing,
                home = home,
                taken = ref.currencyMap.keys,
                onSave = {
                    vm.saveCurrency(it)
                    editing = ""
                },
                onDelete = {
                    vm.deleteCurrency(editing)
                    editing = ""
                },
                onDismiss = { editing = "" },
            )
        }
    }
}

@Composable
private fun CurrencyDialog(
    existing: Currency?,
    home: String,
    taken: Set<String>,
    onSave: (Currency) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf(existing?.code ?: "") }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var symbol by rememberSaveable { mutableStateOf(existing?.symbol ?: "") }
    var decimals by rememberSaveable { mutableIntStateOf(existing?.decimals ?: 2) }
    var rate by rememberSaveable { mutableStateOf(existing?.defaultRate ?: "") }
    var error by rememberSaveable { mutableStateOf("") }
    val isHome = existing?.code == home

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(if (existing == null) "New currency" else "Edit ${existing.code}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (existing == null) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v ->
                            code = v.filter { it.isLetter() }.uppercase().take(4)
                            error = ""
                        },
                        modifier = Modifier.fillMaxWidth().testTag("currency_code_field"),
                        label = { Text("Code (e.g. BTN)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(40)
                        error = ""
                    },
                    modifier = Modifier.fillMaxWidth().testTag("currency_name_field"),
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.take(5) },
                    modifier = Modifier.fillMaxWidth().testTag("currency_symbol_field"),
                    label = { Text("Symbol (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                if (existing == null) {
                    Text("Decimal places", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 2, 3).forEach { d ->
                            androidx.compose.material3.FilterChip(
                                selected = decimals == d,
                                onClick = { decimals = d },
                                label = { Text(d.toString()) },
                            )
                        }
                    }
                } else {
                    Text(
                        "${existing.decimals} decimal places (fixed, because saved amounts depend on it)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isHome) {
                    OutlinedTextField(
                        value = rate,
                        onValueChange = { v ->
                            rate = v.filter { it.isDigit() || it == '.' || it == ',' }.take(16)
                            error = ""
                        },
                        modifier = Modifier.fillMaxWidth().testTag("currency_rate_field"),
                        label = { Text("Default rate: 1 $home =") },
                        suffix = { Text(code) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    )
                }
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    error = when {
                        code.length < 3 -> "The code needs 3 or 4 letters."
                        existing == null && code in taken -> "$code already exists."
                        name.isBlank() -> "Enter a name."
                        rate.isNotBlank() && Money.parseRate(rate) == null -> "The rate must be a number greater than 0."
                        else -> ""
                    }
                    if (error.isEmpty()) {
                        onSave(Currency(code, name.trim(), symbol.trim(), decimals, if (isHome) "" else rate.trim(), existing?.sortOrder ?: 0))
                    }
                },
                modifier = Modifier.testTag("save_currency"),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null && !isHome) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("delete_currency"),
                    ) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
