package app.tripledger.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.Keys
import app.tripledger.domain.CurrencyDisplay
import app.tripledger.domain.DateStyle
import app.tripledger.domain.Dates
import app.tripledger.domain.NumberStyle
import app.tripledger.domain.ThemeMode
import app.tripledger.ui.Routes
import app.tripledger.ui.SettingsViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.ChoiceDialog
import app.tripledger.ui.components.ConfirmDialog
import app.tripledger.ui.components.exposeTestTags
import app.tripledger.data.BackupInterval
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import app.tripledger.ui.components.exposeTestTags
import app.tripledger.ui.components.CurrencyPickerDialog
import app.tripledger.ui.components.Panel
import app.tripledger.ui.components.SectionTitle
import app.tripledger.ui.theme.AccentColors
import app.tripledger.ui.theme.contentColorOn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SettingsScreen(onOpen: (String) -> Unit, onEditTrip: (Long) -> Unit) {
    val vm = appViewModel { repo, _ -> SettingsViewModel(repo) }
    val refState by vm.ref.collectAsStateWithLifecycle()
    val ref = refState
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by rememberSaveable { mutableStateOf("") }
    var pendingImport by rememberSaveable { mutableStateOf<Uri?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(context.contentResolver, uri)
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(context.contentResolver, uri)
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(context.contentResolver, uri)
    }
    val pickBackupFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setBackupFolder(context, uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        if (ref == null) return@Scaffold
        val s = ref.settings
        val locale = Locale.ENGLISH
        val sampleDay = LocalDate.now().toEpochDay()
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
                .testTag("settings_list"),
        ) {
            SectionTitle("Appearance")
            SettingsGroup {
                SettingRow("Theme", s.themeMode.label, "setting_theme") { dialog = "theme" }
                RowDivider()
                SettingRow(
                    "Accent colour",
                    AccentColors.firstOrNull { it.second == s.accent }?.first ?: "Custom",
                    "setting_accent",
                    trailing = { Box(Modifier.size(22.dp).clip(CircleShape).background(Color(s.accent))) },
                ) { dialog = "accent" }
            }

            SectionTitle("Currencies")
            SettingsGroup {
                SettingRow(
                    "Home currency",
                    "${s.homeCurrency} · ${ref.currency(s.homeCurrency).name}. Used for new trips.",
                    "setting_home_currency",
                ) { dialog = "home" }
                RowDivider()
                SettingRow("Currency list and default rates", "${ref.currencies.size} currencies", "setting_currencies") {
                    onOpen(Routes.CURRENCIES)
                }
                val trip = ref.activeTrip
                if (trip != null) {
                    RowDivider()
                    val locals = ref.localCurrencies(trip.id)
                    SettingRow(
                        "Exchange rates for ${trip.name}",
                        if (locals.isEmpty()) "No local currencies" else locals.joinToString { c ->
                            if (c.rate.isBlank()) "${c.code}: not set" else "1 ${trip.homeCurrency} = ${c.rate} ${c.code}"
                        },
                        "setting_trip_rates",
                    ) { onEditTrip(trip.id) }
                }
                RowDivider()
                SettingRow("Show currencies as", s.currencyDisplay.label, "setting_currency_display") { dialog = "currency_display" }
            }

            SectionTitle("Entries")
            SettingsGroup {
                SettingRow("Categories", "${ref.categories.size} categories", "setting_categories") { onOpen(Routes.CATEGORIES) }
                RowDivider()
                SettingRow("Payment methods", ref.paymentMethods.joinToString { it.name }.ifEmpty { "None" }, "setting_payments") {
                    onOpen(Routes.PAYMENTS)
                }
                RowDivider()
                SettingRow(
                    "Default category",
                    ref.categoryMap[s.defaultCategoryId]?.name ?: "First in the list",
                    "setting_default_category",
                ) { dialog = "default_category" }
                RowDivider()
                SettingRow(
                    "Default currency",
                    s.defaultCurrency.ifEmpty { "Last used on the trip" },
                    "setting_default_currency",
                ) { dialog = "default_currency" }
                RowDivider()
                SettingRow(
                    "Default payment method",
                    ref.paymentMap[s.defaultPaymentMethodId]?.name ?: "None",
                    "setting_default_payment",
                ) { dialog = "default_payment" }
            }

            SectionTitle("Summary cards on the entries screen")
            SettingsGroup {
                SwitchRow("Total", s.showTotal, "switch_total") { vm.set(Keys.SHOW_TOTAL, it) }
                RowDivider()
                SwitchRow("Daily average", s.showDailyAverage, "switch_average") { vm.set(Keys.SHOW_AVERAGE, it) }
                RowDivider()
                SwitchRow("Today", s.showToday, "switch_today") { vm.set(Keys.SHOW_TODAY, it) }
                RowDivider()
                SwitchRow("Number of entries", s.showCount, "switch_count") { vm.set(Keys.SHOW_COUNT, it) }
                RowDivider()
                SwitchRow("Budget left (trips with a budget)", s.showBudget, "switch_budget") { vm.set(Keys.SHOW_BUDGET, it) }
                RowDivider()
                SwitchRow("Left today (trips with a budget)", s.showLeftToday, "switch_left_today") { vm.set(Keys.SHOW_LEFT_TODAY, it) }
            }

            SectionTitle("Formats")
            SettingsGroup {
                SettingRow("Date format", s.dateStyle.format(sampleDay), "setting_date_format") { dialog = "date" }
                RowDivider()
                SettingRow("Number format", s.numberStyle.sample, "setting_number_format") { dialog = "number" }
                RowDivider()
                SettingRow(
                    "First day of week",
                    s.firstDayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale),
                    "setting_first_day",
                ) { dialog = "first_day" }
            }

            SectionTitle("Data")
            SettingsGroup {
                SettingRow("Export backup", "Save everything to a file you choose", "setting_export") {
                    exportBackup.launch("tripledger-backup-${LocalDate.now()}.json")
                }
                RowDivider()
                SettingRow("Import backup", "Replace everything, or merge into what's here", "setting_import") {
                    importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                RowDivider()
                SettingRow(
                    "Automatic backup",
                    when {
                        s.autoBackupInterval == BackupInterval.OFF -> "Off"
                        s.autoBackupFolder.isBlank() -> "${s.autoBackupInterval.label}, but no folder chosen"
                        s.autoBackupStatus.isNotBlank() -> "${s.autoBackupInterval.label} · ${s.autoBackupStatus}"
                        else -> "${s.autoBackupInterval.label} · no backup made yet"
                    },
                    "setting_auto_backup",
                ) { dialog = "auto_backup" }
                RowDivider()
                SettingRow("Export CSV", "All entries as a spreadsheet file", "setting_csv") {
                    exportCsv.launch("tripledger-${LocalDate.now()}.csv")
                }
                RowDivider()
                SettingRow("Import CSV", "Add expenses from a CSV exported by Trip Ledger", "setting_import_csv") {
                    importCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
                }
                RowDivider()
                SettingRow("Add sample trips", "Two demo trips to try the app with", "setting_sample") { dialog = "sample" }
                RowDivider()
                SettingRow("Delete all data", "Trips, entries and settings", "setting_delete_all", danger = true) { dialog = "delete_all" }
            }

            Text(
                "Trip Ledger 1.0 · All data is stored on this device only. The app has no internet permission.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        }

        val close = { dialog = "" }
        when (dialog) {
            "theme" -> ChoiceDialog("Theme", ThemeMode.entries, s.themeMode, { it.label }, {
                vm.set(Keys.THEME, it.key)
                close()
            }, close)

            "accent" -> AccentDialog(s.accent, {
                vm.set(Keys.ACCENT, it.toString())
                close()
            }, close)

            "home" -> CurrencyPickerDialog("Home currency", ref.currencies, {
                vm.set(Keys.HOME_CURRENCY, it.code)
                close()
            }, close)

            "currency_display" -> ChoiceDialog("Show currencies as", CurrencyDisplay.entries, s.currencyDisplay, { it.label }, {
                vm.set(Keys.CURRENCY_DISPLAY, it.key)
                close()
            }, close)

            "default_category" -> ChoiceDialog(
                "Default category",
                listOf(0L) + ref.categories.map { it.id },
                if (ref.categoryMap.containsKey(s.defaultCategoryId)) s.defaultCategoryId else 0L,
                { id -> ref.categoryMap[id]?.name ?: "First in the list" },
                {
                    vm.set(Keys.DEFAULT_CATEGORY, it.toString())
                    close()
                },
                close,
            )

            "default_currency" -> ChoiceDialog(
                "Default currency",
                listOf("") + ref.currencies.map { it.code },
                s.defaultCurrency,
                { code -> if (code.isEmpty()) "Last used on the trip" else "$code · ${ref.currency(code).name}" },
                {
                    vm.set(Keys.DEFAULT_CURRENCY, it)
                    close()
                },
                close,
            )

            "default_payment" -> ChoiceDialog(
                "Default payment method",
                listOf(0L) + ref.paymentMethods.map { it.id },
                if (ref.paymentMap.containsKey(s.defaultPaymentMethodId)) s.defaultPaymentMethodId else 0L,
                { id -> ref.paymentMap[id]?.name ?: "None" },
                {
                    vm.set(Keys.DEFAULT_PAYMENT, it.toString())
                    close()
                },
                close,
            )

            "date" -> ChoiceDialog("Date format", DateStyle.entries, s.dateStyle, { it.format(sampleDay) }, {
                vm.set(Keys.DATE_STYLE, it.key)
                close()
            }, close)

            "number" -> ChoiceDialog("Number format", NumberStyle.entries, s.numberStyle, { it.sample }, {
                vm.set(Keys.NUMBER_STYLE, it.key)
                close()
            }, close)

            "first_day" -> ChoiceDialog(
                "First day of week",
                listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY),
                s.firstDayOfWeek,
                { it.getDisplayName(TextStyle.FULL_STANDALONE, locale) },
                {
                    vm.set(Keys.FIRST_DAY, it.name)
                    close()
                },
                close,
            )

            "sample" -> ConfirmDialog(
                "Add sample trips?",
                "Two demo trips with expenses are added next to your own data. You can delete them like any other trip.",
                "Add",
                onConfirm = {
                    close()
                    vm.loadSampleData()
                },
                onDismiss = close,
            )

            "delete_all" -> ConfirmDialog(
                "Delete all data?",
                "Every trip, expense, category, currency and setting is erased and the app returns to its initial state. " +
                    "This can't be undone. Export a backup first if you might want it back.",
                "Delete everything",
                destructive = true,
                onConfirm = {
                    close()
                    vm.deleteAllData {}
                },
                onDismiss = close,
            )
        }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            modifier = Modifier.exposeTestTags(),
            title = { Text("Import this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Merge adds the trips and expenses from the file to what is already here, skipping anything already present.")
                    Text("Replace deletes everything currently in the app first. This can't be undone.")
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            pendingImport = null
                            vm.importBackup(context.contentResolver, uri, merge = false)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("import_replace"),
                    ) { Text("Replace") }
                    TextButton(
                        onClick = {
                            pendingImport = null
                            vm.importBackup(context.contentResolver, uri, merge = true)
                        },
                        modifier = Modifier.testTag("import_merge"),
                    ) { Text("Merge") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }

    val settingsNow = ref?.settings
    if (dialog == "auto_backup" && settingsNow != null) {
        AlertDialog(
            onDismissRequest = { dialog = "" },
            modifier = Modifier.exposeTestTags(),
            title = { Text("Automatic backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A backup file is written to a folder you choose, at most once a day or a week. " +
                            "It happens when you open or leave the app; there is no background service. The newest 10 files are kept.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackupInterval.entries.forEach { interval ->
                            FilterChip(
                                selected = settingsNow.autoBackupInterval == interval,
                                onClick = { vm.set(Keys.AUTO_BACKUP_INTERVAL, interval.key) },
                                label = { Text(interval.label) },
                                modifier = Modifier.testTag("backup_${interval.key}"),
                            )
                        }
                    }
                    Text(
                        if (settingsNow.autoBackupFolder.isBlank()) "No folder chosen"
                        else "Folder: " + Uri.decode(settingsNow.autoBackupFolder.substringAfterLast("/")).substringAfter(":").ifBlank { "storage root" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (settingsNow.autoBackupStatus.isNotBlank()) {
                        Text(
                            settingsNow.autoBackupStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settingsNow.autoBackupStatus.startsWith("Backup failed")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("backup_status"),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickBackupFolder.launch(null) }, modifier = Modifier.testTag("backup_choose_folder")) {
                            Text(if (settingsNow.autoBackupFolder.isBlank()) "Choose folder" else "Change folder")
                        }
                        OutlinedButton(
                            onClick = { vm.backupNow(context) },
                            enabled = settingsNow.autoBackupFolder.isNotBlank(),
                            modifier = Modifier.testTag("backup_now"),
                        ) { Text("Back up now") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } },
        )
    }
}

@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Column { content() } }
}

@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun SettingRow(
    title: String,
    value: String?,
    tag: String,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (!value.isNullOrEmpty()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) trailing()
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AccentDialog(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Accent colour") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AccentColors.forEach { (name, value) ->
                    ColorSwatch(value, value == selected, name, Modifier.testTag("accent_$name")) { onSelect(value) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun ColorSwatch(color: Int, selected: Boolean, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(color))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = contentColorOn(Color(color)))
    }
}
