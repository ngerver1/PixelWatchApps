package dev.nick.raincheck.core

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/** How far ahead the verdict looks. */
val HORIZON: Duration = Duration.ofHours(3)

/** A cache older than this is treated as "No recent data". */
val STALE_AFTER: Duration = Duration.ofMinutes(60)

sealed interface Verdict {
    val longText: String
    val shortText: String

    /** Dry now; the first wet slot starts in about [minutes]. */
    data class DryFor(val minutes: Int) : Verdict {
        override val longText get() = "Dry for ~$minutes min"
        override val shortText get() = "Dry ${minutes}m"
    }

    data object DryAllHorizon : Verdict {
        override val longText = "Dry for 3h+"
        override val shortText = "Dry 3h+"
    }

    /** Wet now; the first dry slot starts in about [minutes]. */
    data class RainClearsIn(val minutes: Int) : Verdict {
        override val longText get() = "Raining · clears in ~$minutes min"
        override val shortText get() = "Rain"
    }

    data object RainAllHorizon : Verdict {
        override val longText = "Raining for 3h+"
        override val shortText = "Rain"
    }

    data object NoData : Verdict {
        override val longText = "No recent data"
        override val shortText = "—"
    }

    val isRainingNow: Boolean get() = this is RainClearsIn || this is RainAllHorizon
}

/**
 * Pure verdict calculation. Call it at render time with the cached slots and the current
 * clock so the countdown stays right between fetches.
 *
 * @param slots cached 15-minute slots, in any order.
 * @param now the current time.
 * @param fetchedAt when the slots were fetched; null means unknown and is treated as stale.
 */
fun verdict(slots: List<Slot>, now: Instant, fetchedAt: Instant?): Verdict {
    if (slots.isEmpty() || fetchedAt == null) return Verdict.NoData
    if (Duration.between(fetchedAt, now) > STALE_AFTER) return Verdict.NoData

    val sorted = slots.sortedBy { it.startEpochSec }
    val nowSec = now.epochSecond
    val currentIndex = sorted.indexOfFirst { nowSec >= it.startEpochSec && nowSec < it.endEpochSec }
    if (currentIndex < 0) return Verdict.NoData

    val horizonEnd = nowSec + HORIZON.seconds
    val current = sorted[currentIndex]
    val ahead = sorted.drop(currentIndex + 1).filter { it.startEpochSec < horizonEnd }

    return if (!current.isWet) {
        val firstWet = ahead.firstOrNull { it.isWet }
        if (firstWet == null) Verdict.DryAllHorizon
        else Verdict.DryFor(roundMinutes(firstWet.startEpochSec - nowSec))
    } else {
        val firstDry = ahead.firstOrNull { !it.isWet }
        if (firstDry == null) Verdict.RainAllHorizon
        else Verdict.RainClearsIn(roundMinutes(firstDry.startEpochSec - nowSec))
    }
}

/** Rounds seconds to the nearest 15 minutes, with a minimum of 15. */
internal fun roundMinutes(seconds: Long): Int {
    val minutes = seconds / 60.0
    val rounded = (minutes / 15.0).roundToLong() * 15
    return maxOf(15L, rounded).toInt()
}

/**
 * Precipitation for the 12 slots starting with the one covering [now], for the mini chart.
 * Entries are null where the cache has no slot.
 */
fun chartBars(slots: List<Slot>, now: Instant, count: Int = 12): List<Double?> {
    val byStart = slots.associateBy { it.startEpochSec }
    val nowSec = now.epochSecond
    val firstStart = nowSec - Math.floorMod(nowSec, SLOT_SECONDS)
    // Slots from Open-Meteo are aligned to 15-minute boundaries; look up by aligned start.
    return (0 until count).map { i -> byStart[firstStart + i * SLOT_SECONDS]?.precipitationMm }
}
