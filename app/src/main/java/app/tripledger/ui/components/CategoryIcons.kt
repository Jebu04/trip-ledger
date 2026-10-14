package app.tripledger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Atm
import androidx.compose.material.icons.rounded.Attractions
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.Icecream
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocalLaundryService
import androidx.compose.material.icons.rounded.LocalPharmacy
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Museum
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons a category can use, by the key stored in the database. They are vector graphics compiled
 * into the app, so nothing is downloaded.
 */
object CategoryIcons {
    val all: List<Pair<String, ImageVector>> = listOf(
        "restaurant" to Icons.Rounded.Restaurant,
        "fastfood" to Icons.Rounded.Fastfood,
        "cafe" to Icons.Rounded.LocalCafe,
        "bar" to Icons.Rounded.LocalBar,
        "beer" to Icons.Rounded.SportsBar,
        "icecream" to Icons.Rounded.Icecream,
        "grocery" to Icons.Rounded.LocalGroceryStore,
        "cart" to Icons.Rounded.ShoppingCart,
        "shopping_bag" to Icons.Rounded.ShoppingBag,
        "gift" to Icons.Rounded.CardGiftcard,
        "hotel" to Icons.Rounded.Hotel,
        "home" to Icons.Rounded.Home,
        "flight" to Icons.Rounded.Flight,
        "train" to Icons.Rounded.Train,
        "bus" to Icons.Rounded.DirectionsBus,
        "taxi" to Icons.Rounded.LocalTaxi,
        "car" to Icons.Rounded.DirectionsCar,
        "scooter" to Icons.Rounded.TwoWheeler,
        "boat" to Icons.Rounded.DirectionsBoat,
        "fuel" to Icons.Rounded.LocalGasStation,
        "luggage" to Icons.Rounded.Luggage,
        "attractions" to Icons.Rounded.Attractions,
        "museum" to Icons.Rounded.Museum,
        "movie" to Icons.Rounded.Movie,
        "music" to Icons.Rounded.MusicNote,
        "camera" to Icons.Rounded.CameraAlt,
        "hiking" to Icons.Rounded.Hiking,
        "beach" to Icons.Rounded.BeachAccess,
        "spa" to Icons.Rounded.Spa,
        "fitness" to Icons.Rounded.FitnessCenter,
        "pharmacy" to Icons.Rounded.LocalPharmacy,
        "hospital" to Icons.Rounded.LocalHospital,
        "phone" to Icons.Rounded.Phone,
        "wifi" to Icons.Rounded.Wifi,
        "laundry" to Icons.Rounded.LocalLaundryService,
        "atm" to Icons.Rounded.Atm,
        "bank" to Icons.Rounded.AccountBalance,
        "payments" to Icons.Rounded.Payments,
        "receipt" to Icons.Rounded.Receipt,
        "work" to Icons.Rounded.Work,
        "school" to Icons.Rounded.School,
        "child" to Icons.Rounded.ChildCare,
        "pets" to Icons.Rounded.Pets,
        "star" to Icons.Rounded.Star,
        "category" to Icons.Rounded.Category,
        "more" to Icons.Rounded.MoreHoriz,
    )

    private val byKey = all.toMap()

    fun get(key: String?): ImageVector = byKey[key] ?: Icons.Rounded.Category
}
