package app.tripledger.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.RefData
import app.tripledger.domain.Dates
import app.tripledger.ui.SearchCriteria
import app.tripledger.ui.SearchViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.AppDatePickerDialog
import app.tripledger.ui.components.EmptyState
import app.tripledger.ui.components.exposeTestTags

@Composable
fun SearchScreen(onOpenEntry: (Long) -> Unit) {
    val vm = appViewModel { repo, handle -> SearchViewModel(repo, handle) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref
    val criteria = ui.criteria
    val focusManager = LocalFocusManager.current
    // Which filter sheet is open: "category", "date", "amount" or "" for none.
    var sheet by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        TextField(
            value = vm.queryText,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("search_field"),
            placeholder = { Text("Search title, note or category") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (vm.queryText.isEmpty()) null else ({
                IconButton(onClick = { vm.setQuery("") }, modifier = Modifier.testTag("search_clear")) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            }),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        if (ref == null || ui.loading) return@Column

        fun open(which: String) {
            focusManager.clearFocus()
            sheet = which
        }

        // Scope and filters as one compact, scrollable row. Each filter opens its own small sheet.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !criteria.allTrips,
                onClick = { vm.setAllTrips(false) },
                label = { Text("This trip") },
                modifier = Modifier.testTag("scope_trip"),
            )
            FilterChip(
                selected = criteria.allTrips,
                onClick = { vm.setAllTrips(true) },
                label = { Text("All trips") },
                modifier = Modifier.testTag("scope_all"),
            )
            FilterChip(
                selected = criteria.categoryIds.isNotEmpty(),
                onClick = { open("category") },
                label = { Text(categoryLabel(criteria, ref), maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Category, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("chip_category"),
            )
            FilterChip(
                selected = criteria.hasDate,
                onClick = { open("date") },
                label = { Text(dateLabel(criteria), maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.DateRange, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("chip_date"),
            )
            FilterChip(
                selected = criteria.hasAmount,
                onClick = { open("amount") },
                label = { Text(amountLabel(criteria), maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Payments, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("chip_amount"),
            )
            if (criteria.filterCount > 0) {
                TextButton(onClick = vm::clearFilters, modifier = Modifier.testTag("clear_filters")) { Text("Clear") }
            }
        }

        val n = ui.results.size
        Text(
            "$n ${if (n == 1) "result" else "results"}" + (ui.totalText?.takeIf { n > 0 }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("result_count"),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("search_results"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (ui.results.isEmpty()) {
                item(key = "empty") {
                    if (!ui.anyEntries) {
                        EmptyState(
                            Icons.Rounded.Search, "Nothing to search yet",
                            if (criteria.allTrips) "You haven't added any expenses." else "This trip has no expenses yet.",
                        )
                    } else {
                        EmptyState(
                            Icons.Rounded.SearchOff, "No matches",
                            "No expense matches your search" + (if (criteria.filterCount > 0) " and filters." else "."),
                        )
                    }
                }
            } else {
                groupedItems(ui.results, keyPrefix = "r", key = { it.item.entry.id }) { result, shape ->
                    EntryListItem(
                        result.item, ref, result.trip,
                        subtitleDate = true,
                        showTrip = criteria.allTrips,
                        shape = shape,
                    ) { onOpenEntry(result.item.entry.id) }
                }
            }
        }
    }

    if (ref != null && sheet.isNotEmpty()) {
        FilterSheet(
            title = when (sheet) {
                "category" -> "Category"
                "date" -> "Date"
                else -> "Amount (in home currency)"
            },
            onClear = {
                when (sheet) {
                    "category" -> vm.clearCategories()
                    "date" -> {
                        vm.setFrom(null)
                        vm.setTo(null)
                    }
                    else -> {
                        vm.setMin("")
                        vm.setMax("")
                    }
                }
            },
            onDone = { sheet = "" },
        ) {
            when (sheet) {
                "category" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ref.categories.forEach { category ->
                        FilterChip(
                            selected = category.id in criteria.categoryIds,
                            onClick = { vm.toggleCategory(category.id) },
                            label = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("filter_category_${category.name}"),
                        )
                    }
                }
                "date" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateFilterChip("From", criteria.from, ref, { picking = 1 }, { vm.setFrom(null) }, "search_from")
                    DateFilterChip("To", criteria.to, ref, { picking = 2 }, { vm.setTo(null) }, "search_to")
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = vm.minText,
                        onValueChange = vm::setMin,
                        modifier = Modifier.weight(1f).testTag("amount_min"),
                        label = { Text("Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        value = vm.maxText,
                        onValueChange = vm::setMax,
                        modifier = Modifier.weight(1f).testTag("amount_max"),
                        label = { Text("Max") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    )
                }
            }
        }
    }

    if (picking != 0 && ref != null) {
        AppDatePickerDialog(
            initial = (if (picking == 1) criteria.from else criteria.to) ?: Dates.today(),
            firstDayOfWeek = ref.settings.firstDayOfWeek,
            onPick = {
                if (picking == 1) vm.setFrom(it) else vm.setTo(it)
                picking = 0
            },
            onDismiss = { picking = 0 },
        )
    }
}

private fun categoryLabel(c: SearchCriteria, ref: RefData): String = when (c.categoryIds.size) {
    0 -> "Category"
    1 -> ref.categoryMap[c.categoryIds.first()]?.name ?: "1 category"
    else -> "${c.categoryIds.size} categories"
}

private fun dateLabel(c: SearchCriteria): String {
    val from = c.from
    val to = c.to
    return when {
        from != null && to != null -> "${Dates.short(from)} – ${Dates.short(to)}"
        from != null -> "From ${Dates.short(from)}"
        to != null -> "Until ${Dates.short(to)}"
        else -> "Date"
    }
}

private fun amountLabel(c: SearchCriteria): String = when {
    c.minText.isNotBlank() && c.maxText.isNotBlank() -> "${c.minText} – ${c.maxText}"
    c.minText.isNotBlank() -> "At least ${c.minText}"
    c.maxText.isNotBlank() -> "Up to ${c.maxText}"
    else -> "Amount"
}

/** A bottom sheet holding one filter. Results update live behind it as the filter changes. */
@Composable
private fun FilterSheet(title: String, onClear: () -> Unit, onDone: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = state, modifier = Modifier.exposeTestTags()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .navigationBarsPadding().imePadding(),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClear, modifier = Modifier.testTag("sheet_clear")) { Text("Clear") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onDone, modifier = Modifier.testTag("sheet_done")) { Text("Done") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
