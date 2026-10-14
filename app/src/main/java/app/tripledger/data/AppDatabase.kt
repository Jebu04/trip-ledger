package app.tripledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Trip::class, TripCurrency::class, Entry::class, Category::class,
        PaymentMethod::class, Currency::class, Setting::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun entryDao(): EntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun currencyDao(): CurrencyDao
    abstract fun settingDao(): SettingDao

    companion object {
        /** Version 2 adds budgets, shared bills, spreading, per-expense rates and receipt photos. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN budgetMinor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN budgetPerDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN myShareMinor INTEGER")
                db.execSQL("ALTER TABLE entries ADD COLUMN spreadDays INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE entries ADD COLUMN excludeFromAverage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE entries ADD COLUMN rateOverride TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE entries ADD COLUMN photo TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tripledger.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }
}
