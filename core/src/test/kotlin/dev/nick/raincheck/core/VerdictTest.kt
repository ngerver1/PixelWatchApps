package dev.nick.raincheck.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class VerdictTest {
    /** Aligned to a 15-minute boundary. */
    private val base = 1_800_000_000L
    private val now = Instant.ofEpochSecond(base)
    private val fetched = now.minusSeconds(5 * 60)

    /** One past slot (dry) followed by the given forecast values, the first covering [now]. */
    private fun slots(vararg mm: Double): List<Slot> =
        listOf(Slot(base - SLOT_SECONDS, 0.0)) +
            mm.mapIndexed { i, p -> Slot(base + i * SLOT_SECONDS, p) }

    private fun dry(n: Int) = DoubleArray(n) { 0.0 }

    @Test
    fun dryThroughout() {
        val v = verdict(slots(*dry(20)), now, fetched)
        assertEquals(Verdict.DryAllHorizon, v)
        assertEquals("Dry for 3h+", v.longText)
        assertEquals("Dry 3h+", v.shortText)
    }

    @Test
    fun rainStartingInNextSlot() {
        val v = verdict(slots(0.0, 0.5, *dry(18)), now, fetched)
        assertEquals(Verdict.DryFor(15), v)
        assertEquals("Dry for ~15 min", v.longText)
        assertEquals("Dry 15m", v.shortText)
    }

    @Test
    fun rainStartingLater() {
        // Wet from slot index 3 => starts 45 minutes from now.
        val v = verdict(slots(0.0, 0.0, 0.0, 1.2, 0.4, *dry(15)), now, fetched)
        assertEquals(Verdict.DryFor(45), v)
        assertEquals("Dry for ~45 min", v.longText)
        assertEquals("Dry 45m", v.shortText)
    }

    @Test
    fun rainBeyondThreeHoursIsIgnored() {
        // Index 12 starts exactly 3 h from now, outside the window.
        val v = verdict(slots(*dry(12), 2.0, *dry(7)), now, fetched)
        assertEquals(Verdict.DryAllHorizon, v)
    }

    @Test
    fun rainingNowAndClearing() {
        val v = verdict(slots(0.8, 0.3, 0.0, *dry(17)), now, fetched)
        assertEquals(Verdict.RainClearsIn(30), v)
        assertEquals("Raining · clears in ~30 min", v.longText)
        assertEquals("Rain", v.shortText)
    }

    @Test
    fun rainingNowAndThroughout() {
        val v = verdict(slots(*DoubleArray(20) { 0.6 }), now, fetched)
        assertEquals(Verdict.RainAllHorizon, v)
        assertEquals("Raining for 3h+", v.longText)
        assertEquals("Rain", v.shortText)
    }

    @Test
    fun exactlyPointOneMillimetreCountsAsWet() {
        assertEquals(Verdict.DryFor(15), verdict(slots(0.0, 0.1, *dry(18)), now, fetched))
        assertEquals(Verdict.RainClearsIn(15), verdict(slots(0.1, 0.0, *dry(18)), now, fetched))
        // Just under the threshold is dry.
        assertEquals(Verdict.DryAllHorizon, verdict(slots(0.0, 0.09, *dry(18)), now, fetched))
    }

    @Test
    fun staleCache() {
        val s = slots(*dry(20))
        assertEquals(Verdict.NoData, verdict(s, now, now.minusSeconds(61 * 60)))
        // Exactly 60 minutes is still fine.
        assertEquals(Verdict.DryAllHorizon, verdict(s, now, now.minusSeconds(60 * 60)))
        assertEquals("No recent data", Verdict.NoData.longText)
        assertEquals("—", Verdict.NoData.shortText)
    }

    @Test
    fun noData() {
        assertEquals(Verdict.NoData, verdict(emptyList(), now, fetched))
        assertEquals(Verdict.NoData, verdict(slots(*dry(20)), now, null))
        // No slot covers now.
        assertEquals(Verdict.NoData, verdict(slots(*dry(4)), now.plusSeconds(5 * 3600), now.plusSeconds(5 * 3600)))
    }

    @Test
    fun nowPartwayThroughASlot() {
        // Rain starts at slot 3 (45 min after base). 10 minutes into slot 0 => 35 min away,
        // which rounds to 30.
        val s = slots(0.0, 0.0, 0.0, 1.0, *dry(16))
        assertEquals(Verdict.DryFor(30), verdict(s, now.plusSeconds(10 * 60), fetched))
        // 5 minutes in => 40 min away, rounds to 45.
        assertEquals(Verdict.DryFor(45), verdict(s, now.plusSeconds(5 * 60), fetched))
        // 14 minutes into slot 2 => 1 minute away, rounds to the minimum of 15.
        assertEquals(Verdict.DryFor(15), verdict(s, now.plusSeconds(44 * 60), fetched))
    }

    @Test
    fun countdownAdvancesWithTimeUsingSameCache() {
        val s = slots(*dry(6), 1.0, *dry(13)) // rain at +90 min
        assertEquals(Verdict.DryFor(90), verdict(s, now, fetched))
        assertEquals(Verdict.DryFor(60), verdict(s, now.plusSeconds(30 * 60), fetched))
        assertEquals(Verdict.DryFor(45), verdict(s, now.plusSeconds(45 * 60), fetched))
    }

    @Test
    fun slotsInAnyOrder() {
        val v = verdict(slots(0.0, 0.0, 2.0, *dry(17)).shuffled(java.util.Random(1)), now, fetched)
        assertEquals(Verdict.DryFor(30), v)
    }

    @Test
    fun roundingRules() {
        assertEquals(15, roundMinutes(0))
        assertEquals(15, roundMinutes(7 * 60))
        assertEquals(15, roundMinutes(22 * 60))
        assertEquals(30, roundMinutes(23 * 60))
        assertEquals(180, roundMinutes(180 * 60))
    }

    @Test
    fun chartBarsStartAtCurrentSlot() {
        val s = slots(0.0, 0.2, 0.0, 3.0)
        val bars = chartBars(s, now.plusSeconds(7 * 60))
        assertEquals(12, bars.size)
        assertEquals(listOf(0.0, 0.2, 0.0, 3.0, null), bars.take(5))
    }
}
