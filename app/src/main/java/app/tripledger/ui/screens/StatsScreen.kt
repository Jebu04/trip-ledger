package app.tripledger.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import app.tripledger.domain.Dates
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tripledger.data.RefData
import app.tripledger.data.Trip
import app.tripledger.domain.CategorySlice
import app.tripledger.domain.DayTotal
import app.tripledger.domain.Stats
import app.tripledger.ui.StatsRange
import app.tripledger.ui.StatsUi
import app.tripledger.ui.StatsViewModel
import app.tripledger.ui.appViewModel
import app.tripledger.ui.components.AppDatePickerDialog
import app.tripledger.ui.components.EmptyState
import app.tripledger.ui.components.Panel
import app.tripledger.ui.components.SummaryCard
import java.time.LocalDate

private const val UNKNOWN_COLOR = 0xFF9E9E9E.toInt()

@Composable
fun StatsScreen(onOpenEntry: (Long) -> Unit) {
    val vm = appViewModel { repo, handle -> StatsViewModel(repo, handle) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ref = ui.ref
    val trip = ui.trip
    // 1 = picking the start of a custom range, 2 = picking the end.
    var picking by rememberSaveable { mutableStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text("Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (trip != null) {
                            Text(
                                trip.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (ui.loading || ref == null || trip == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
                .testTag("stats_content"),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsRange.entries.forEach { range ->
                    FilterChip(
                        selected = ui.range == range,
                        onClick = { vm.setRange(range) },
                        label = { Text(range.label) },
                        modifier = Modifier.testTag("range_${range.name}"),
                    )
                }
            }
            if (ui.range == StatsRange.CUSTOM) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateFilterChip("From", ui.customFrom, ref, { picking = 1 }, { vm.setCustomFrom(null) }, "stats_from")
                    DateFilterChip("To", ui.customTo, ref, { picking = 2 }, { vm.setCustomTo(null) }, "stats_to")
                }
            }
            Text(
                "${ref.date(ui.from)} – ${ref.date(ui.to)} · ${ui.summary.days} ${if (ui.summary.days == 1) "day" else "days"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("stats_period"),
            )
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("Total", ref.money(ui.summary.total, trip.homeCurrency), Modifier.weight(1f).testTag("stats_total"), emphasized = true)
                SummaryCard("Daily average", ref.money(ui.summary.dailyAverage, trip.homeCurrency), Modifier.weight(1f).testTag("stats_average"))
            }

            when {
                !ui.tripHasEntries -> EmptyState(
                    Icons.Rounded.PieChart, "Nothing to chart yet",
                    "Add a few expenses to ${trip.name} and the breakdown will appear here.",
                )
                ui.summary.count == 0 -> EmptyState(
                    Icons.Rounded.PieChart, "No expenses in this period", "Try a different date range.",
                )
                else -> {
                    if (ui.summary.unconverted > 0) {
                        Text(
                            "${ui.summary.unconverted} ${if (ui.summary.unconverted == 1) "entry is" else "entries are"} left out: no exchange rate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    if (ui.slices.isNotEmpty()) CategoryBreakdown(ui, ref, trip)
                    DailyChart(ui.perDay, ref, trip)
                    CumulativeChart(ui.cumulative, ui.budgetLine, ref, trip)
                    if (ui.byPayment.isNotEmpty()) {
                        GroupPanel(
                            title = "By payment method",
                            tag = "by_payment",
                            rows = ui.byPayment.map { slice ->
                                GroupRow(
                                    label = slice.key?.let { ref.paymentMap[it]?.name } ?: "No payment method",
                                    detail = "${slice.count} ${if (slice.count == 1) "expense" else "expenses"}",
                                    amount = ref.money(slice.total, trip.homeCurrency),
                                    permille = slice.permille,
                                )
                            },
                            dec = ref.settings.numberStyle.dec,
                        )
                    }
                    if (ui.byCurrency.size > 1 || ui.byCurrency.any { it.key != trip.homeCurrency }) {
                        GroupPanel(
                            title = "By currency",
                            tag = "by_currency",
                            rows = ui.byCurrency.map { slice ->
                                GroupRow(
                                    label = "${slice.key} · ${ref.currency(slice.key).name}",
                                    detail = if (slice.key == trip.homeCurrency) "${slice.count} ${if (slice.count == 1) "expense" else "expenses"}"
                                    else "Paid " + ref.money(ui.currencyOriginals[slice.key] ?: 0L, slice.key),
                                    amount = ref.money(slice.total, trip.homeCurrency),
                                    permille = slice.permille,
                                )
                            },
                            dec = ref.settings.numberStyle.dec,
                        )
                    }
                    if (ui.biggest.isNotEmpty()) {
                        StatsPanel("Biggest expenses") {
                            ui.biggest.forEachIndexed { i, item ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                EntryListItem(item, ref, trip, subtitleDate = true, showTrip = false) { onOpenEntry(item.entry.id) }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            if (ui.trips.size > 1) TripComparison(ui, ref)
        }
    }

    if (picking != 0 && ref != null) {
        val initial = (if (picking == 1) ui.customFrom else ui.customTo) ?: (if (picking == 1) ui.from else ui.to)
        AppDatePickerDialog(
            initial = initial,
            firstDayOfWeek = ref.settings.firstDayOfWeek,
            onPick = {
                if (picking == 1) vm.setCustomFrom(it) else vm.setCustomTo(it)
                picking = 0
            },
            onDismiss = { picking = 0 },
        )
    }
}

/** A chip showing one end of a date range, with a clear button once a date is set. */
@Composable
fun DateFilterChip(label: String, value: Long?, ref: RefData, onClick: () -> Unit, onClear: () -> Unit, tag: String) {
    InputChip(
        selected = value != null,
        onClick = onClick,
        label = { Text(if (value == null) "$label: any" else "$label: ${ref.date(value)}", maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)) },
        trailingIcon = if (value == null) null else ({
            Icon(Icons.Rounded.Close, "Clear $label date", Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onClear))
        }),
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun StatsPanel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Panel(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun CategoryBreakdown(ui: StatsUi, ref: RefData, trip: Trip) {
    StatsPanel("By category") {
        val colors = ui.slices.map { Color(ref.categoryMap[it.categoryId]?.color ?: UNKNOWN_COLOR) }
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            DonutChart(ui.slices, colors, 168.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(108.dp)) {
                Text("Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    ref.money(ui.summary.total, trip.homeCurrency),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        ui.slices.forEachIndexed { i, slice ->
            val category = ref.categoryMap[slice.categoryId]
            Column(Modifier.padding(horizontal = 16.dp, vertical = 7.dp).testTag("category_slice")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(colors[i]))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        category?.name ?: "No category",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Stats.percentText(slice.permille, ref.settings.numberStyle.dec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Text(
                        ref.money(slice.total, trip.homeCurrency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth((slice.permille / 1000f).coerceIn(0.01f, 1f))
                            .clip(RoundedCornerShape(3.dp)).background(colors[i]),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DonutChart(slices: List<CategorySlice>, colors: List<Color>, size: Dp) {
    val total = slices.sumOf { it.total }.toFloat()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.size(size).semantics { contentDescription = "Spending by category chart" }.testTag("donut_chart")) {
        val stroke = this.size.minDimension * 0.15f
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(inset, inset)
        if (total <= 0f) {
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            return@Canvas
        }
        // A small gap between slices, unless there is only one.
        val gap = if (slices.size > 1) 1.6f else 0f
        var start = -90f
        slices.forEachIndexed { i, slice ->
            val sweep = slice.total / total * 360f
            if (sweep > 0f) {
                drawArc(
                    color = colors[i],
                    startAngle = start + gap / 2,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Butt),
                )
            }
            start += sweep
        }
    }
}

@Composable
private fun DailyChart(days: List<DayTotal>, ref: RefData, trip: Trip) {
    if (days.isEmpty()) return
    val max = days.maxOf { it.total }
    val peak = days.maxByOrNull { it.total }!!
    var selectedDate by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }
    val selected = days.firstOrNull { it.date == selectedDate }
    val shown = selected ?: peak

    StatsPanel("Per day") {
        Text(
            (if (selected == null) "Highest: " else "") + "${ref.date(shown.date)} · ${ref.money(shown.total, trip.homeCurrency)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("day_chart_caption"),
        )
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
            // Few days: bars share the width. Many days: fixed width and the chart scrolls.
            val barSlot = maxOf(30.dp, maxWidth / days.size)
            val listState = rememberLazyListState()
            LaunchedEffect(days.size, days.last().date) { listState.scrollToItem(days.lastIndex) }
            LazyRow(state = listState, modifier = Modifier.testTag("day_chart")) {
                itemsIndexed(days, key = { _, d -> d.date }) { _, day ->
                    val isSelected = day.date == shown.date
                    val date = LocalDate.ofEpochDay(day.date)
                    Column(
                        Modifier.width(barSlot)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedDate = if (selectedDate == day.date) Long.MIN_VALUE else day.date }
                            .semantics { contentDescription = "${ref.date(day.date)}: ${ref.money(day.total, trip.homeCurrency)}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.height(132.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            val fraction = if (max <= 0) 0f else day.total.toFloat() / max
                            Box(
                                Modifier.width(minOf(barSlot - 8.dp, 34.dp))
                                    .height(if (day.total > 0) maxOf(3.dp, 132.dp * fraction) else 2.dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                    .background(
                                        when {
                                            day.total <= 0 -> MaterialTheme.colorScheme.outlineVariant
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        },
                                    ),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class GroupRow(val label: String, val detail: String, val amount: String, val permille: Int)

/** A list of groups with their totals and share bars, e.g. spending per payment method. */
@Composable
private fun GroupPanel(title: String, tag: String, rows: List<GroupRow>, dec: Char) {
    StatsPanel(title, Modifier.testTag(tag)) {
        rows.forEach { row ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 7.dp).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            row.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        Stats.percentText(row.permille, dec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Text(row.amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (row.permille > 0) {
                        Box(
                            Modifier.fillMaxHeight().fillMaxWidth((row.permille / 1000f).coerceIn(0.01f, 1f))
                                .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Running total over the period, with the trip's budget drawn as a dashed line when it has one. */
@Composable
private fun CumulativeChart(points: List<DayTotal>, budget: Long?, ref: RefData, trip: Trip) {
    // A single day has no line to draw, but the budget progress is still worth showing.
    if (points.isEmpty() || (points.size < 2 && budget == null)) return
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val budgetColor = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    val last = points.last().total
    val top = maxOf(points.maxOf { it.total }, budget ?: 0L, 1L).toFloat()
    val bottom = minOf(points.minOf { it.total }, 0L).toFloat()
    val description = buildString {
        append("Running total reaches ${ref.money(last, trip.homeCurrency)} by ${ref.date(points.last().date)}")
        if (budget != null) append(", against a budget of ${ref.money(budget, trip.homeCurrency)}")
    }
    StatsPanel("Running total", Modifier.testTag("cumulative_chart")) {
        Text(
            if (budget == null) "${ref.money(last, trip.homeCurrency)} so far"
            else "${ref.money(last, trip.homeCurrency)} of ${ref.money(budget, trip.homeCurrency)} budget",
            style = MaterialTheme.typography.bodySmall,
            color = if (budget != null && last > budget) budgetColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (points.size < 2) {
            Spacer(Modifier.height(12.dp))
            return@StatsPanel
        }
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = description },
        ) {
            val w = size.width
            val h = size.height
            fun x(i: Int) = if (points.size == 1) 0f else w * i / (points.size - 1)
            fun y(v: Float) = h - (v - bottom) / (top - bottom) * h
            drawLine(grid, Offset(0f, y(0f)), Offset(w, y(0f)), strokeWidth = 1.dp.toPx())
            val path = Path().apply {
                moveTo(x(0), y(points[0].total.toFloat()))
                points.forEachIndexed { i, p -> if (i > 0) lineTo(x(i), y(p.total.toFloat())) }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(points.lastIndex), y(0f))
                lineTo(x(0), y(0f))
                close()
            }
            drawPath(area, fill)
            drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            if (budget != null) {
                val by = y(budget.toFloat())
                drawLine(
                    budgetColor, Offset(0f, by), Offset(w, by), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Text(Dates.short(points.first().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(Dates.short(points.last().date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Every trip side by side: total and daily average, each in that trip's own home currency. */
@Composable
private fun TripComparison(ui: StatsUi, ref: RefData) {
    val maxAverage = ui.trips.filter { it.trip.homeCurrency == ui.trip?.homeCurrency }.maxOfOrNull { it.dailyAverage } ?: 0L
    StatsPanel("All trips compared", Modifier.testTag("trip_comparison")) {
        ui.trips.forEach { row ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 7.dp).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.trip.name + if (row.current) " (this trip)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (row.current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${ref.money(row.total, row.trip.homeCurrency)} over ${row.days} ${if (row.days == 1) "day" else "days"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        ref.money(row.dailyAverage, row.trip.homeCurrency) + " / day",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Bars only compare trips that share the current trip's home currency.
                if (row.trip.homeCurrency == ui.trip?.homeCurrency && maxAverage > 0 && row.dailyAverage > 0) {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth((row.dailyAverage.toFloat() / maxAverage).coerceIn(0.02f, 1f)).height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (row.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
