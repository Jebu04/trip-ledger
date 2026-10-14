package app.tripledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import app.tripledger.ui.TripLedgerRoot

/** The only activity: everything else is Compose destinations inside [TripLedgerRoot]. */
class MainActivity : ComponentActivity() {
    /** Counts "Add expense" requests from the launcher shortcut and the widget. */
    private val addExpenseRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only a fresh launch acts on the intent; after a restore the form is already open if it was.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TripLedgerRoot(
                addExpenseRequests = addExpenseRequests.intValue,
                onAddExpenseHandled = { addExpenseRequests.intValue = 0 },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app is the natural moment for an automatic backup, if one is due.
        (application as TripLedgerApp).backupIfDue()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_ADD_EXPENSE) addExpenseRequests.intValue++
    }

    companion object {
        const val ACTION_ADD_EXPENSE = "app.tripledger.ADD_EXPENSE"
    }
}
