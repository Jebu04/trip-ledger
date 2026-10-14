package app.tripledger

import android.app.Application
import app.tripledger.data.AppDatabase
import app.tripledger.data.AutoBackup
import app.tripledger.data.Receipts
import app.tripledger.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds the app-wide singletons. Small enough that a DI framework would be overkill. */
class TripLedgerApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val repository: Repository by lazy { Repository(database) }
    val receipts: Receipts by lazy { Receipts(this) }

    /** For work that must outlive a screen, such as a backup started as the app is being left. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching {
                repository.ensureSeeded()
                receipts.cleanup(repository.photoNames())
            }
        }
    }

    /** Writes an automatic backup if one is due. Safe to call often. */
    fun backupIfDue() {
        appScope.launch { runCatching { AutoBackup.run(this@TripLedgerApp, repository, force = false) } }
    }
}
