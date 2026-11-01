package dev.nick.raincheck.core

import java.time.Instant

/** Everything the tile, complication and app screen need to draw, computed at render time. */
data class Presentation(
    val locationLabel: String,
    val longText: String,
    val shortText: String,
    /** Null while the cache belongs to another location. */
    val verdict: Verdict?,
    val bars: List<Double?>,
    val updatedAt: Instant?,
    val updating: Boolean,
    val showLocationHint: Boolean,
)

const val TEXT_UPDATING = "Updating…"

fun present(
    cache: CachedForecast?,
    selection: ActiveSelection,
    places: List<SavedPlace>,
    hasCoarsePermission: Boolean,
    seed: Coords?,
    now: Instant,
): Presentation {
    val expectedKey = LocationResolver.expectedKey(selection, places, hasCoarsePermission, seed)
    val expectedLabel = LocationResolver.expectedLabel(selection, places, hasCoarsePermission, seed)

    if (cache == null) {
        return Presentation(
            locationLabel = expectedLabel,
            longText = Verdict.NoData.longText,
            shortText = Verdict.NoData.shortText,
            verdict = Verdict.NoData,
            bars = List(12) { null },
            updatedAt = null,
            updating = false,
            showLocationHint = expectedKey == LocationResolver.KEY_FALLBACK,
        )
    }

    if (!LocationResolver.cacheMatches(cache.locationKey, expectedKey)) {
        return Presentation(
            locationLabel = expectedLabel,
            longText = TEXT_UPDATING,
            shortText = "…",
            verdict = null,
            bars = List(12) { null },
            updatedAt = null,
            updating = true,
            showLocationHint = false,
        )
    }

    val fetchedAt = Instant.ofEpochSecond(cache.fetchedAtEpochSec)
    val v = verdict(cache.slots, now, fetchedAt)
    // Saved places may have been renamed since the fetch; show the current name.
    val label = if (expectedKey.startsWith("place:")) expectedLabel else cache.label
    return Presentation(
        locationLabel = label,
        longText = v.longText,
        shortText = v.shortText,
        verdict = v,
        bars = if (v == Verdict.NoData) List(12) { null } else chartBars(cache.slots, now),
        updatedAt = fetchedAt,
        updating = false,
        showLocationHint = cache.showLocationHint,
    )
}
