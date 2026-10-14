package app.tripledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startDate DESC, id DESC")
    fun observeAll(): Flow<List<Trip>>

    @Query("SELECT * FROM trips ORDER BY startDate DESC, id DESC")
    suspend fun getAll(): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): Trip?

    @Insert
    suspend fun insert(trip: Trip): Long

    @Insert
    suspend fun insertAll(trips: List<Trip>)

    @Update
    suspend fun update(trip: Trip)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trips")
    suspend fun clear()

    @Query("SELECT * FROM trip_currencies ORDER BY tripId, sortOrder, code")
    fun observeCurrencies(): Flow<List<TripCurrency>>

    @Query("SELECT * FROM trip_currencies ORDER BY tripId, sortOrder, code")
    suspend fun getAllCurrencies(): List<TripCurrency>

    @Query("SELECT * FROM trip_currencies WHERE tripId = :tripId ORDER BY sortOrder, code")
    suspend fun getCurrencies(tripId: Long): List<TripCurrency>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCurrencies(currencies: List<TripCurrency>)

    @Query("DELETE FROM trip_currencies WHERE tripId = :tripId")
    suspend fun deleteCurrencies(tripId: Long)

    @Query("DELETE FROM trip_currencies")
    suspend fun clearCurrencies()

    @Query("SELECT COUNT(*) FROM trips WHERE homeCurrency = :code")
    suspend fun countWithHome(code: String): Int

    @Query("SELECT COUNT(*) FROM trip_currencies WHERE code = :code")
    suspend fun countWithLocal(code: String): Int
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE tripId = :tripId ORDER BY date DESC, createdAt DESC, id DESC")
    fun observeForTrip(tripId: Long): Flow<List<Entry>>

    @Query("SELECT * FROM entries ORDER BY date DESC, createdAt DESC, id DESC")
    fun observeAll(): Flow<List<Entry>>

    @Query("SELECT * FROM entries ORDER BY id")
    suspend fun getAll(): List<Entry>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun get(id: Long): Entry?

    @Insert
    suspend fun insert(entry: Entry): Long

    @Insert
    suspend fun insertAll(entries: List<Entry>)

    @Update
    suspend fun update(entry: Entry)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM entries")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM entries WHERE tripId = :tripId AND currency = :code")
    suspend fun countForTripCurrency(tripId: Long, code: String): Int

    @Query("SELECT COUNT(*) FROM entries WHERE currency = :code")
    suspend fun countForCurrency(code: String): Int

    @Query("SELECT COUNT(*) FROM entries WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Query("UPDATE entries SET categoryId = :to WHERE categoryId = :from")
    suspend fun reassignCategory(from: Long, to: Long)

    @Query("UPDATE entries SET paymentMethodId = NULL WHERE paymentMethodId = :paymentMethodId")
    suspend fun clearPaymentMethod(paymentMethodId: Long)

    @Query("SELECT currency FROM entries WHERE tripId = :tripId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastCurrency(tripId: Long): String?

    @Query("SELECT * FROM entries WHERE tripId = :tripId ORDER BY id")
    suspend fun getForTrip(tripId: Long): List<Entry>

    @Query("SELECT categoryId, COUNT(*) AS uses FROM entries GROUP BY categoryId")
    suspend fun categoryUsage(): List<CategoryUse>

    @Query("SELECT photo FROM entries WHERE photo != ''")
    suspend fun photos(): List<String>
}

/** How many expenses use a category; drives the "most used first" order of the quick picker. */
data class CategoryUse(val categoryId: Long, val uses: Int)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    suspend fun getAll(): List<Category>

    @Insert
    suspend fun insert(category: Category): Long

    @Insert
    suspend fun insertAll(categories: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Update
    suspend fun updateAll(categories: List<Category>)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM categories")
    suspend fun clear()
}

@Dao
interface PaymentMethodDao {
    @Query("SELECT * FROM payment_methods ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<PaymentMethod>>

    @Query("SELECT * FROM payment_methods ORDER BY sortOrder, id")
    suspend fun getAll(): List<PaymentMethod>

    @Insert
    suspend fun insert(method: PaymentMethod): Long

    @Insert
    suspend fun insertAll(methods: List<PaymentMethod>)

    @Update
    suspend fun update(method: PaymentMethod)

    @Update
    suspend fun updateAll(methods: List<PaymentMethod>)

    @Query("DELETE FROM payment_methods WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM payment_methods")
    suspend fun clear()
}

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currencies ORDER BY sortOrder, code")
    fun observeAll(): Flow<List<Currency>>

    @Query("SELECT * FROM currencies ORDER BY sortOrder, code")
    suspend fun getAll(): List<Currency>

    @Query("SELECT * FROM currencies WHERE code = :code")
    suspend fun get(code: String): Currency?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(currency: Currency)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(currencies: List<Currency>)

    @Query("DELETE FROM currencies WHERE code = :code")
    suspend fun delete(code: String)

    @Query("DELETE FROM currencies")
    suspend fun clear()
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<Setting>>

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<Setting>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: Setting)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(settings: List<Setting>)

    @Query("DELETE FROM settings")
    suspend fun clear()
}
