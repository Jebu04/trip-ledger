package app.tripledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tripledger.ui.TripLedgerRoot

/** The only activity: everything else is Compose destinations inside [TripLedgerRoot]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TripLedgerRoot() }
    }
}
