package app.tripledger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tripledger.data.DEFAULT_ACCENT
import app.tripledger.domain.ThemeMode
import app.tripledger.ui.screens.CategoriesScreen
import app.tripledger.ui.screens.CurrenciesScreen
import app.tripledger.ui.screens.EntriesScreen
import app.tripledger.ui.screens.EntryEditScreen
import app.tripledger.ui.screens.PaymentMethodsScreen
import app.tripledger.ui.screens.SearchScreen
import app.tripledger.ui.screens.SettingsScreen
import app.tripledger.ui.screens.StatsScreen
import app.tripledger.ui.screens.TripEditScreen
import app.tripledger.ui.screens.TripsScreen
import app.tripledger.ui.theme.TripLedgerTheme

object Routes {
    const val ENTRIES = "entries"
    const val STATS = "stats"
    const val SEARCH = "search"
    const val TRIPS = "trips"
    const val SETTINGS = "settings"
    const val ENTRY = "entry?id={id}&copyFrom={copyFrom}"
    const val TRIP = "trip?id={id}"
    const val CATEGORIES = "settings/categories"
    const val PAYMENTS = "settings/payments"
    const val CURRENCIES = "settings/currencies"

    fun entry(id: Long = 0, copyFrom: Long = 0) = "entry?id=$id&copyFrom=$copyFrom"
    fun trip(id: Long = 0) = "trip?id=$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.ENTRIES, "Entries", Icons.AutoMirrored.Rounded.ReceiptLong),
    Tab(Routes.STATS, "Statistics", Icons.Rounded.PieChart),
    Tab(Routes.SEARCH, "Search", Icons.Rounded.Search),
    Tab(Routes.TRIPS, "Trips", Icons.Rounded.Luggage),
    Tab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

/**
 * @param addExpenseRequests increases each time the app is opened from the "Add expense"
 *   shortcut or widget, so the form opens even if the app was already running.
 */
@Composable
fun TripLedgerRoot(addExpenseRequests: Int = 0, onAddExpenseHandled: () -> Unit = {}) {
    val mainVm = appViewModel { repo, _ -> MainViewModel(repo) }
    val ref by mainVm.ref.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val settings = ref?.settings

    TripLedgerTheme(settings?.themeMode ?: ThemeMode.SYSTEM, settings?.accent ?: DEFAULT_ACCENT) {
        Surface(
            // Lets UI tests and adb's uiautomator find elements by their test tag.
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            color = MaterialTheme.colorScheme.background,
        ) {
            val data = ref
            if (data != null) {
                val hasTrips = data.trips.isNotEmpty()
                LaunchedEffect(addExpenseRequests, hasTrips) {
                    if (addExpenseRequests > 0) {
                        // Without a trip there is nowhere to put an expense: the welcome screen shows instead.
                        if (hasTrips) navController.navigate(Routes.entry()) { launchSingleTop = true }
                        onAddExpenseHandled()
                    }
                }
                AppScaffold(navController, hasTrips, mainVm)
            }
        }
    }
}

private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppScaffold(navController: NavHostController, hasTrips: Boolean, mainVm: MainViewModel) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val onTab = tabs.any { it.route == route }
    val snackbar = remember { SnackbarHostState() }
    val deleted by mainVm.recentlyDeleted.collectAsStateWithLifecycle()

    // With no trips left there is nothing to show on the other tabs: go back to the welcome screen.
    LaunchedEffect(hasTrips, route) {
        if (!hasTrips && route != null && route != Routes.ENTRIES && route != Routes.TRIP) {
            navController.popBackStack(Routes.ENTRIES, inclusive = false)
        }
    }

    // "Expense deleted · Undo", shown wherever the user lands after deleting.
    LaunchedEffect(deleted) {
        val entry = deleted ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "\"${entry.title}\" deleted",
            actionLabel = "Undo",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) mainVm.undoDelete() else mainVm.dismissUndo()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tablets and phones in landscape get a side rail, which leaves the height for content.
        val wide = maxWidth >= 600.dp
        val showNav = onTab && hasTrips
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.testTag("snackbar")) },
            bottomBar = {
                if (showNav && !wide) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { navController.openTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.testTag("nav_${tab.route}"),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.padding(padding).consumeWindowInsets(padding)) {
                if (showNav && wide) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight()) {
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = route == tab.route,
                                onClick = { navController.openTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label, maxLines = 1) },
                                colors = NavigationRailItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.testTag("nav_${tab.route}"),
                            )
                        }
                    }
                }
                // On wide screens content keeps a readable width instead of stretching edge to edge.
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    AppNavHost(navController, Modifier.widthIn(max = 720.dp).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, modifier: Modifier) {
    NavHost(navController = navController, startDestination = Routes.ENTRIES, modifier = modifier) {
        composable(Routes.ENTRIES) {
            EntriesScreen(
                onAdd = { navController.navigate(Routes.entry()) },
                onOpenEntry = { navController.navigate(Routes.entry(it)) },
                onCreateTrip = { navController.navigate(Routes.trip()) },
                onOpenTrips = { navController.openTab(Routes.TRIPS) },
                onEditTrip = { navController.navigate(Routes.trip(it)) },
            )
        }
        composable(Routes.STATS) {
            StatsScreen(onOpenEntry = { navController.navigate(Routes.entry(it)) })
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpenEntry = { navController.navigate(Routes.entry(it)) })
        }
        composable(Routes.TRIPS) {
            TripsScreen(
                onSelected = { navController.openTab(Routes.ENTRIES) },
                onEdit = { navController.navigate(Routes.trip(it)) },
                onCreate = { navController.navigate(Routes.trip()) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpen = { navController.navigate(it) },
                onEditTrip = { navController.navigate(Routes.trip(it)) },
            )
        }
        composable(
            Routes.ENTRY,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType; defaultValue = 0L },
                navArgument("copyFrom") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            EntryEditScreen(
                onDone = { navController.popBackStack() },
                // The copy replaces the original's form, so Back returns to where the user came from.
                onDuplicate = {
                    navController.navigate(Routes.entry(copyFrom = id)) {
                        popUpTo(Routes.ENTRY) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.TRIP, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L })) {
            TripEditScreen(
                onDone = { navController.popBackStack() },
                onCreated = { navController.popBackStack(Routes.ENTRIES, inclusive = false) },
            )
        }
        composable(Routes.CATEGORIES) { CategoriesScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.PAYMENTS) { PaymentMethodsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.CURRENCIES) { CurrenciesScreen(onBack = { navController.popBackStack() }) }
    }
}
