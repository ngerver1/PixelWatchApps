package dev.nick.raincheck.core

import kotlinx.serialization.Serializable

/** Brisbane CBD, used when nothing better is available. */
val FALLBACK_COORDS = Coords(-27.4698, 153.0251)
const val FALLBACK_LABEL = "Brisbane (default)"
const val SEED_LABEL = "Home"

const val LABEL_CURRENT = "Current location"
const val LABEL_CURRENT_STALE = "Current location (may be stale)"
const val LABEL_LAST_KNOWN = "Last known location"

/** A location fix from the platform. [ageSeconds] is how old the fix was when we got it. */
@Serializable
data class LocationFix(val coords: Coords, val ageSeconds: Long)

/** A fix older than this is labelled as possibly stale. */
const val FRESH_FIX_MAX_AGE_SECONDS: Long = 30 * 60

data class LocationInputs(
    val selection: ActiveSelection,
    val places: List<SavedPlace>,
    val hasCoarsePermission: Boolean,
    val hasBackgroundPermission: Boolean,
    /** True when the app screen is open, so foreground location access is allowed. */
    val inForeground: Boolean,
    /** A fix obtained during this run, or null if none could be obtained. */
    val fix: LocationFix?,
    /** The last current-location coordinates that produced a forecast. */
    val lastWorking: Coords?,
    /** Build-time HOME_LAT/HOME_LON, if provided. */
    val seed: Coords?,
)

enum class LocationSource { SAVED_PLACE, SEED, CURRENT, CURRENT_STALE, LAST_KNOWN, FALLBACK }

data class ResolvedLocation(
    val coords: Coords,
    val label: String,
    val source: LocationSource,
    val key: String,
) {
    val showLocationHint: Boolean get() = source == LocationSource.FALLBACK
    /** True when [coords] came from a platform fix that should be remembered as "last working". */
    val isLiveFix: Boolean get() = source == LocationSource.CURRENT || source == LocationSource.CURRENT_STALE
}

object LocationResolver {
    const val KEY_CURRENT = "current"
    const val KEY_SEED = "seed"
    const val KEY_FALLBACK = "fallback"
    fun placeKey(id: String) = "place:$id"

    /** Whether the fetcher should ask the platform for a location fix at all. */
    fun needsFix(selection: ActiveSelection, places: List<SavedPlace>, hasCoarse: Boolean, seed: Coords?): Boolean =
        hasCoarse && effectiveMode(selection, places, hasCoarse, seed) == Mode.CURRENT

    /** Chooses which coordinates and label to fetch for. Pure; no Android. */
    fun resolve(input: LocationInputs): ResolvedLocation =
        when (effectiveMode(input.selection, input.places, input.hasCoarsePermission, input.seed)) {
            Mode.PLACE -> {
                val place = input.places.first { it.id == (input.selection as ActiveSelection.Place).id }
                ResolvedLocation(place.coords, place.label, LocationSource.SAVED_PLACE, placeKey(place.id))
            }
            Mode.SEED -> ResolvedLocation(input.seed!!, SEED_LABEL, LocationSource.SEED, KEY_SEED)
            Mode.CURRENT -> resolveCurrent(input)
            Mode.FALLBACK -> fallback()
        }

    private fun resolveCurrent(input: LocationInputs): ResolvedLocation {
        val fix = input.fix
        if (input.hasCoarsePermission && fix != null) {
            val backgroundLimited = !input.inForeground && !input.hasBackgroundPermission
            val old = fix.ageSeconds > FRESH_FIX_MAX_AGE_SECONDS
            return if (backgroundLimited || old) {
                ResolvedLocation(fix.coords, LABEL_CURRENT_STALE, LocationSource.CURRENT_STALE, KEY_CURRENT)
            } else {
                ResolvedLocation(fix.coords, LABEL_CURRENT, LocationSource.CURRENT, KEY_CURRENT)
            }
        }
        val last = input.lastWorking
        if (last != null) {
            return ResolvedLocation(last, LABEL_LAST_KNOWN, LocationSource.LAST_KNOWN, KEY_CURRENT)
        }
        return fallback()
    }

    private fun fallback() =
        ResolvedLocation(FALLBACK_COORDS, FALLBACK_LABEL, LocationSource.FALLBACK, KEY_FALLBACK)

    /**
     * The cache key the UI expects for the current settings, without needing a fix.
     * Used to avoid showing one place's forecast under another place's label.
     */
    fun expectedKey(selection: ActiveSelection, places: List<SavedPlace>, hasCoarse: Boolean, seed: Coords?): String =
        when (effectiveMode(selection, places, hasCoarse, seed)) {
            Mode.PLACE -> placeKey((selection as ActiveSelection.Place).id)
            Mode.SEED -> KEY_SEED
            Mode.CURRENT -> KEY_CURRENT
            Mode.FALLBACK -> KEY_FALLBACK
        }

    /** Label for the active choice before any data exists for it. */
    fun expectedLabel(selection: ActiveSelection, places: List<SavedPlace>, hasCoarse: Boolean, seed: Coords?): String =
        when (effectiveMode(selection, places, hasCoarse, seed)) {
            Mode.PLACE -> places.first { it.id == (selection as ActiveSelection.Place).id }.label
            Mode.SEED -> SEED_LABEL
            Mode.CURRENT -> LABEL_CURRENT
            Mode.FALLBACK -> FALLBACK_LABEL
        }

    /** A "current" choice that found no coordinates at all falls back, and that's still a match. */
    fun cacheMatches(cacheKey: String, expectedKey: String): Boolean =
        cacheKey == expectedKey || (expectedKey == KEY_CURRENT && cacheKey == KEY_FALLBACK)

    private enum class Mode { PLACE, SEED, CURRENT, FALLBACK }

    private fun effectiveMode(
        selection: ActiveSelection,
        places: List<SavedPlace>,
        hasCoarse: Boolean,
        seed: Coords?,
    ): Mode = when (selection) {
        is ActiveSelection.Place ->
            if (places.any { it.id == selection.id }) Mode.PLACE else unsetMode(hasCoarse, seed)
        ActiveSelection.Current -> Mode.CURRENT
        ActiveSelection.Unset -> unsetMode(hasCoarse, seed)
    }

    private fun unsetMode(hasCoarse: Boolean, seed: Coords?): Mode = when {
        seed != null -> Mode.SEED
        hasCoarse -> Mode.CURRENT
        else -> Mode.FALLBACK
    }
}
