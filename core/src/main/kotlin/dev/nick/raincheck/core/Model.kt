package dev.nick.raincheck.core

import kotlinx.serialization.Serializable

/** Length of one forecast slot. */
const val SLOT_SECONDS: Long = 15 * 60

/** A slot is wet if precipitation is at least this many millimetres. */
const val WET_THRESHOLD_MM: Double = 0.1

/**
 * One 15-minute forecast slot covering `[startEpochSec, startEpochSec + 15 min)`.
 */
@Serializable
data class Slot(
    val startEpochSec: Long,
    val precipitationMm: Double,
) {
    val endEpochSec: Long get() = startEpochSec + SLOT_SECONDS
    val isWet: Boolean get() = precipitationMm >= WET_THRESHOLD_MM
}

@Serializable
data class Coords(val lat: Double, val lon: Double) {
    /** True when both coordinates are within ~100 m of each other. */
    fun sameAs(other: Coords): Boolean =
        kotlin.math.abs(lat - other.lat) < 0.001 && kotlin.math.abs(lon - other.lon) < 0.001
}

@Serializable
data class SavedPlace(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
) {
    val coords: Coords get() = Coords(lat, lon)
}

/** Which location the user picked in settings. */
sealed interface ActiveSelection {
    /** Nothing chosen yet (fresh install without a seed). */
    data object Unset : ActiveSelection

    data object Current : ActiveSelection

    data class Place(val id: String) : ActiveSelection

    fun encode(): String? = when (this) {
        Unset -> null
        Current -> "current"
        is Place -> "place:$id"
    }

    companion object {
        fun decode(raw: String?): ActiveSelection = when {
            raw == null -> Unset
            raw == "current" -> Current
            raw.startsWith("place:") -> Place(raw.removePrefix("place:"))
            else -> Unset
        }
    }
}

/** What a fetch stored, so the UI can render it later without the network. */
@Serializable
data class CachedForecast(
    val slots: List<Slot>,
    val fetchedAtEpochSec: Long,
    val lat: Double,
    val lon: Double,
    /** Label shown with this forecast, e.g. "Home" or "Current location (may be stale)". */
    val label: String,
    /** Identifies which location choice produced this cache; see [LocationResolver.locationKey]. */
    val locationKey: String,
    /** True when the fallback location was used and the user should be nudged to set one. */
    val showLocationHint: Boolean = false,
)
