package dev.nick.raincheck.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nick.raincheck.BuildConfig
import dev.nick.raincheck.core.ActiveSelection
import dev.nick.raincheck.core.CachedForecast
import dev.nick.raincheck.core.Coords
import dev.nick.raincheck.core.ForecastCodec
import dev.nick.raincheck.core.LocationResolver
import dev.nick.raincheck.core.Places
import dev.nick.raincheck.core.SEED_LABEL
import dev.nick.raincheck.core.SavedPlace
import dev.nick.raincheck.core.parseSeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.rainStore: DataStore<Preferences> by preferencesDataStore(name = "raincheck")

/** Everything stored on the watch, read in one go. */
data class Snapshot(
    val places: List<SavedPlace>,
    val selection: ActiveSelection,
    val cache: CachedForecast?,
    val lastWorking: Coords?,
    /** Build-time seed, only while it hasn't been turned into a saved place yet. */
    val pendingSeed: Coords?,
)

/** Settings and forecast cache, all in DataStore. */
class RainRepository(context: Context) {
    private val store = context.applicationContext.rainStore

    val snapshots: Flow<Snapshot> = store.data.map { it.toSnapshot() }

    suspend fun snapshot(): Snapshot = snapshots.first()

    /**
     * First run: if HOME_LAT/HOME_LON were set at build time, save them as "Home" and make it
     * active. Runs once; deleting Home later does not bring it back.
     */
    suspend fun ensureSeeded() {
        store.edit { prefs ->
            if (prefs[SEEDED] == true) return@edit
            val seed = buildSeed
            val places = Places.decode(prefs[PLACES])
            if (seed != null && places.isEmpty()) {
                val home = SavedPlace(id = "home", label = SEED_LABEL, lat = seed.lat, lon = seed.lon)
                prefs[PLACES] = Places.encode(listOf(home))
                prefs[ACTIVE] = ActiveSelection.Place(home.id).encode()!!
            }
            prefs[SEEDED] = true
        }
    }

    suspend fun setSelection(selection: ActiveSelection) {
        store.edit { prefs ->
            val raw = selection.encode()
            if (raw == null) prefs.remove(ACTIVE) else prefs[ACTIVE] = raw
        }
    }

    /** Adds a place and makes it active. Returns null if the list is full. */
    suspend fun addPlace(label: String, coords: Coords): SavedPlace? {
        var added: SavedPlace? = null
        store.edit { prefs ->
            val places = Places.decode(prefs[PLACES])
            if (!Places.canAdd(places)) return@edit
            val place = SavedPlace(UUID.randomUUID().toString(), label.trim().ifEmpty { "Place" }, coords.lat, coords.lon)
            prefs[PLACES] = Places.encode(Places.add(places, place))
            prefs[ACTIVE] = ActiveSelection.Place(place.id).encode()!!
            added = place
        }
        return added
    }

    suspend fun renamePlace(id: String, label: String) {
        store.edit { prefs -> prefs[PLACES] = Places.encode(Places.rename(Places.decode(prefs[PLACES]), id, label)) }
    }

    suspend fun deletePlace(id: String) {
        store.edit { prefs ->
            prefs[PLACES] = Places.encode(Places.delete(Places.decode(prefs[PLACES]), id))
            val next = Places.selectionAfterDelete(ActiveSelection.decode(prefs[ACTIVE]), id)
            val raw = next.encode()
            if (raw == null) prefs.remove(ACTIVE) else prefs[ACTIVE] = raw
        }
    }

    /**
     * Saves a fetch result, unless the active location changed while it was in flight
     * (then a newer fetch is already queued and this result belongs to another place).
     *
     * @param hasCoarse used to work out which location the settings currently point at.
     * @return true if saved.
     */
    suspend fun saveForecast(cache: CachedForecast, lastWorking: Coords?, hasCoarse: Boolean): Boolean {
        var saved = false
        store.edit { prefs ->
            val snap = prefs.toSnapshot()
            val expected = LocationResolver.expectedKey(snap.selection, snap.places, hasCoarse, snap.pendingSeed)
            if (!LocationResolver.cacheMatches(cache.locationKey, expected)) return@edit
            prefs[CACHE] = ForecastCodec.encode(cache)
            if (lastWorking != null) {
                prefs[LAST_LAT] = lastWorking.lat
                prefs[LAST_LON] = lastWorking.lon
            }
            saved = true
        }
        return saved
    }

    private fun Preferences.toSnapshot(): Snapshot {
        val lat = this[LAST_LAT]
        val lon = this[LAST_LON]
        return Snapshot(
            places = Places.decode(this[PLACES]),
            selection = ActiveSelection.decode(this[ACTIVE]),
            cache = ForecastCodec.decode(this[CACHE]),
            lastWorking = if (lat != null && lon != null) Coords(lat, lon) else null,
            pendingSeed = if (this[SEEDED] == true) null else buildSeed,
        )
    }

    companion object {
        private val PLACES = stringPreferencesKey("places")
        private val ACTIVE = stringPreferencesKey("active")
        private val SEEDED = booleanPreferencesKey("seeded")
        private val CACHE = stringPreferencesKey("cache")
        private val LAST_LAT = doublePreferencesKey("last_lat")
        private val LAST_LON = doublePreferencesKey("last_lon")

        val buildSeed: Coords? = parseSeed(BuildConfig.HOME_LAT, BuildConfig.HOME_LON)
    }
}
