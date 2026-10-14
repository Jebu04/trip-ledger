package app.tripledger.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.RefData
import app.tripledger.ui.SearchUi
import app.tripledger.ui.SearchViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.AppDatePickerDialog
import app.tripledger.ui.components.EmptyState
import app.tripledger.ui.components.Panel
import app.tripledger.domain.Dates

@Composable
fun SearchScreen(onOpenEntry: (Long) -> Unit) {
    val vm = appViewModel { repo, handle -> SearchViewModel(repo, handle) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref
    val criteria = ui.criteria
    val focusManager = LocalFocusManager.current
    var showFilters by rememberSaveable { mutableStateOf(false) }
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

        FlowRow(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                selected = showFilters || criteria.filterCount > 0,
                onClick = {
                    // Get the keyboard out of the way so the filters are actually visible.
                    focusManager.clearFocus()
                    showFilters = !showFilters
                },
                label = { Text(if (criteria.filterCount > 0) "Filters (${criteria.filterCount})" else "Filters") },
                leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("filters_toggle"),
            )
        }

        // Kept outside the list so the count stays visible while filters are being adjusted.
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
            if (showFilters) {
                item(key = "filters") {
                    FilterPanel(ui, ref, vm, onPickFrom = { picking = 1 }, onPickTo = { picking = 2 })
                }
            }
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
                item(key = "results") {
                    Panel(Modifier.padding(horizontal = 16.dp)) {
                        Column {
                            ui.results.forEachIndexed { i, result ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                EntryListItem(
                                    result.item, ref, result.trip,
                                    subtitleDate = true,
                                    showTrip = criteria.allTrips,
                                    onClick = { onOpenEntry(result.item.entry.id) },
                                )
                            }
                        }
                    }
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

@Composable
private fun FilterPanel(ui: SearchUi, ref: RefData, vm: SearchViewModel, onPickFrom: () -> Unit, onPickTo: () -> Unit) {
    val criteria = ui.criteria
    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("filter_panel")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ref.categories.forEach { category ->
                    FilterChip(
                        selected = category.id in criteria.categoryIds,
                        onClick = { vm.toggleCategory(category.id) },
                        label = { Text(category.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("filter_category_${category.name}"),
                    )
                }
            }
            FieldLabel("Date")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateFilterChip("From", criteria.from, ref, onPickFrom, { vm.setFrom(null) }, "search_from")
                DateFilterChip("To", criteria.to, ref, onPickTo, { vm.setTo(null) }, "search_to")
            }
            FieldLabel("Amount (in home currency)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                )
            }
            Spacer(Modifier.height(2.dp))
            TextButton(
                onClick = vm::clearFilters,
                enabled = criteria.filterCount > 0,
                modifier = Modifier.align(Alignment.End).testTag("clear_filters"),
            ) { Text("Clear filters") }
        }
    }
}
