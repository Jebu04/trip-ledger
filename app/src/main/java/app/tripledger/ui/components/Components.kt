package app.tripledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tripledger.data.Currency
import app.tripledger.domain.Dates
import app.tripledger.ui.theme.contentColorOn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tripledger.TripLedgerApp

/**
 * Dialogs live in their own window, so the test-tag-as-resource-id switch set on the app root
 * does not reach them. Applying this to a dialog makes its tags visible to uiautomator as well.
 */
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }

/** The round coloured icon that identifies a category. */
@Composable
fun CategoryBadge(icon: String?, color: Int, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val background = Color(color)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = CategoryIcons.get(icon),
            contentDescription = null,
            tint = contentColorOn(background),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** A white (or dark grey) rounded panel used to group content. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content,
    )
}

@Composable
fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false, warning: Boolean = false) {
    val container = when {
        warning -> MaterialTheme.colorScheme.errorContainer
        emphasized -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val content = when {
        warning -> MaterialTheme.colorScheme.onErrorContainer
        emphasized -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = content.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Shrinks to fit rather than cutting digits off on narrow screens or with large text.
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp, stepSize = 1.sp),
            )
        }
    }
}

/** One expense in a list: icon, title and category on the left, amounts on the right. */
@Composable
fun EntryRow(
    icon: String?,
    color: Int,
    title: String,
    subtitle: String,
    amount: String,
    originalAmount: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    amountMissing: Boolean = false,
    refund: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                amount,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    amountMissing -> MaterialTheme.colorScheme.error
                    refund -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            if (originalAmount != null) {
                Text(
                    originalAmount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
                modifier = Modifier.testTag("confirm_button"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A dialog with one radio button per option. Picking one applies it and closes the dialog. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null, modifier = Modifier.padding(10.dp))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Searchable list of currencies. */
@Composable
fun CurrencyPickerDialog(
    title: String,
    currencies: List<Currency>,
    onPick: (Currency) -> Unit,
    onDismiss: () -> Unit,
    exclude: Set<String> = emptySet(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = currencies.filter { it.code !in exclude }.filter {
        query.isBlank() || it.code.contains(query.trim(), true) || it.name.contains(query.trim(), true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search currencies") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    modifier = Modifier.fillMaxWidth().testTag("currency_search"),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(shown, key = { it.code }) { currency ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(currency) }
                                .testTag("currency_${currency.code}")
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(currency.code, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(56.dp))
                            Text(currency.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(currency.symbol, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                "No currency matches. You can add your own in Settings → Currencies.",
                                Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A month-grid date picker. Written here rather than using the Material one so that it honours
 * the "first day of week" setting.
 */
@Composable
fun AppDatePickerDialog(
    initial: Long,
    firstDayOfWeek: DayOfWeek,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDate = LocalDate.ofEpochDay(initial)
    var monthIndex by rememberSaveable { mutableIntStateOf(initialDate.year * 12 + initialDate.monthValue - 1) }
    var selected by rememberSaveable { mutableLongStateOf(initial) }
    val first = LocalDate.of(monthIndex / 12, monthIndex % 12 + 1, 1)
    val today = Dates.today()
    // Month and weekday names are English, like the rest of the app.
    val locale = Locale.ENGLISH

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.exposeTestTags(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthIndex-- }, modifier = Modifier.testTag("month_prev")) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month")
                }
                Text(
                    "${first.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${first.year}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { monthIndex++ }, modifier = Modifier.testTag("month_next")) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
                }
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Dates.weekdays(firstDayOfWeek).forEach { day ->
                        Text(
                            day.getDisplayName(TextStyle.SHORT_STANDALONE, locale).take(2),
                            Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                val offset = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
                val daysInMonth = first.lengthOfMonth()
                // Always six rows so the dialog does not change height between months.
                for (week in 0 until 6) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val dayNumber = week * 7 + col - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (dayNumber in 1..daysInMonth) {
                                    val epochDay = first.toEpochDay() + dayNumber - 1
                                    val isSelected = epochDay == selected
                                    val isToday = epochDay == today
                                    Box(
                                        Modifier.fillMaxSize()
                                            .clip(CircleShape)
                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                            .then(
                                                if (isToday && !isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                else Modifier,
                                            )
                                            .clickable { selected = epochDay }
                                            .testTag("day_$dayNumber")
                                            .semantics { contentDescription = LocalDate.ofEpochDay(epochDay).toString() },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            dayNumber.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }, modifier = Modifier.testTag("date_ok")) { Text("OK") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    selected = today
                    val t = LocalDate.ofEpochDay(today)
                    monthIndex = t.year * 12 + t.monthValue - 1
                }) { Text("Today") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Asks before throwing away a form the user has typed into. */
@Composable
fun DiscardDialog(what: String, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        modifier = Modifier.exposeTestTags(),
        title = { Text("Discard $what?") },
        text = { Text("Your changes haven't been saved.") },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("discard_button"),
            ) { Text("Discard") }
        },
        dismissButton = { TextButton(onClick = onKeepEditing, modifier = Modifier.testTag("keep_editing")) { Text("Keep editing") } },
    )
}

/** Loads a stored receipt photo off the main thread. Null while loading or if the file is gone. */
@Composable
fun rememberReceipt(name: String, maxSize: Int): ImageBitmap? {
    val receipts = (LocalContext.current.applicationContext as TripLedgerApp).receipts
    val state = produceState<ImageBitmap?>(null, name, maxSize) {
        value = if (name.isBlank()) null else receipts.load(name, maxSize)?.asImageBitmap()
    }
    return state.value
}

@Composable
fun ReceiptThumbnail(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val image = rememberReceipt(name, 400)
    Box(
        modifier
            .size(88.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClickLabel = "Open receipt photo", onClick = onClick)
            .testTag("receipt_thumbnail"),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = "Receipt photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("Photo missing", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

/** Shows a receipt photo on a dark background, as large as the screen allows. Tap to close. */
@Composable
fun ReceiptViewer(name: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val image = rememberReceipt(name, 2000)
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = "Close", onClick = onDismiss).testTag("receipt_viewer"),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(image, contentDescription = "Receipt photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Text("The photo could not be loaded.", color = Color.White)
            }
        }
    }
}
