package app.tripledger.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
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
    const val ENTRY = "entry?id={id}"
    const val TRIP = "trip?id={id}"
    const val CATEGORIES = "settings/categories"
    const val PAYMENTS = "settings/payments"
    const val CURRENCIES = "settings/currencies"

    fun entry(id: Long = 0) = "entry?id=$id"
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

@Composable
fun TripLedgerRoot() {
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
            if (data != null) AppScaffold(navController, hasTrips = data.trips.isNotEmpty())
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
private fun AppScaffold(navController: NavHostController, hasTrips: Boolean) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val onTab = tabs.any { it.route == route }

    // With no trips left there is nothing to show on the other tabs: go back to the welcome screen.
    LaunchedEffect(hasTrips, route) {
        if (!hasTrips && route != null && route != Routes.ENTRIES && route != Routes.TRIP) {
            navController.popBackStack(Routes.ENTRIES, inclusive = false)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (onTab && hasTrips) {
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
        NavHost(
            navController = navController,
            startDestination = Routes.ENTRIES,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
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
            composable(Routes.ENTRY, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L })) {
                EntryEditScreen(onDone = { navController.popBackStack() })
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
}
