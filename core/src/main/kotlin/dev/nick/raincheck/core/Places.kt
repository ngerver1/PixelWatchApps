package dev.nick.raincheck.core

import kotlinx.serialization.builtins.ListSerializer

const val MAX_PLACES = 5

/** Pure list operations for saved places, plus their JSON encoding for DataStore. */
object Places {
    fun canAdd(places: List<SavedPlace>) = places.size < MAX_PLACES

    fun add(places: List<SavedPlace>, place: SavedPlace): List<SavedPlace> {
        require(canAdd(places)) { "At most $MAX_PLACES places" }
        return places + place
    }

    fun rename(places: List<SavedPlace>, id: String, label: String): List<SavedPlace> =
        places.map { if (it.id == id) it.copy(label = label.trim().ifEmpty { it.label }) else it }

    fun delete(places: List<SavedPlace>, id: String): List<SavedPlace> = places.filterNot { it.id == id }

    /** If the active place was deleted, drop back to Unset (which resolves to current/seed/fallback). */
    fun selectionAfterDelete(selection: ActiveSelection, deletedId: String): ActiveSelection =
        if (selection is ActiveSelection.Place && selection.id == deletedId) ActiveSelection.Unset else selection

    private val serializer = ListSerializer(SavedPlace.serializer())

    fun encode(places: List<SavedPlace>): String = json.encodeToString(serializer, places)

    fun decode(raw: String?): List<SavedPlace> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
}

object ForecastCodec {
    fun encode(cache: CachedForecast): String = json.encodeToString(CachedForecast.serializer(), cache)

    fun decode(raw: String?): CachedForecast? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString(CachedForecast.serializer(), raw) }.getOrNull()
}

/** Parses build-time seed strings; returns null unless both are valid coordinates. */
fun parseSeed(lat: String?, lon: String?): Coords? {
    val la = lat?.trim()?.toDoubleOrNull() ?: return null
    val lo = lon?.trim()?.toDoubleOrNull() ?: return null
    if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
    return Coords(la, lo)
}
