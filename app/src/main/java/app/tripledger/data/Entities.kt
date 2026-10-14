package app.tripledger.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Dates are stored as epoch days (days since 1970-01-01), which keeps them time-zone free. */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startDate: Long,
    val endDate: Long,
    val homeCurrency: String,
    val createdAt: Long = 0,
    /** Budget in home minor units; 0 means no budget. */
    val budgetMinor: Long = 0,
    /** True when [budgetMinor] is an amount per day rather than for the whole trip. */
    val budgetPerDay: Boolean = false,
)

/**
 * A local currency of a trip. [rate] is decimal text meaning "one home unit = rate local units";
 * an empty string means the rate has not been set yet.
 */
@Entity(
    tableName = "trip_currencies",
    primaryKeys = ["tripId", "code"],
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class TripCurrency(
    val tripId: Long,
    val code: String,
    val rate: String,
    val sortOrder: Int = 0,
)

/**
 * An expense. [amountMinor] is in minor units of [currency] (cents, satang, ...) and is negative
 * for a refund.
 */
@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tripId"), Index("date")],
)
data class Entry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val title: String,
    val date: Long,
    val note: String = "",
    val paymentMethodId: Long? = null,
    val createdAt: Long = 0,
    /** The part of a shared bill that is mine, in the same currency and sign; null = all of it. */
    val myShareMinor: Long? = null,
    /** Number of days, starting at [date], the cost is spread over in per-day figures. */
    val spreadDays: Int = 1,
    /** Leaves the expense out of the daily average (it still counts towards the total). */
    val excludeFromAverage: Boolean = false,
    /** Rate for this expense only, same meaning as a trip rate; empty = use the trip's rate. */
    val rateOverride: String = "",
    /** File name of a receipt photo in the app's private storage; empty = none. */
    val photo: String = "",
) {
    /** What this expense cost me: my share if the bill was shared, otherwise the full amount. */
    val effectiveMinor: Long get() = myShareMinor ?: amountMinor
}

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val color: Int,
    val sortOrder: Int = 0,
)

@Entity(tableName = "payment_methods")
data class PaymentMethod(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

/**
 * A currency the user can pick. [defaultRate] is an optional rate against the default home
 * currency, used to pre-fill a trip's rate when the currency is added to a trip.
 */
@Entity(tableName = "currencies")
data class Currency(
    @PrimaryKey val code: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
    val defaultRate: String = "",
    val sortOrder: Int = 0,
)

@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String,
)
