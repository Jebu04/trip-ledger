package app.tripledger

import android.app.Application
import app.tripledger.data.AppDatabase
import app.tripledger.data.Repository

/** Holds the app-wide singletons. Small enough that a DI framework would be overkill. */
class TripLedgerApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val repository: Repository by lazy { Repository(database) }
}
