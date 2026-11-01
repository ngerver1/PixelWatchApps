package dev.nick.raincheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationResolverTest {
    private val home = SavedPlace("h", "Home", -27.45, 153.01)
    private val work = SavedPlace("w", "Work", -27.47, 153.03)
    private val here = Coords(-27.50, 152.99)
    private val last = Coords(-27.60, 153.10)
    private val seed = Coords(-27.40, 153.00)

    private fun input(
        selection: ActiveSelection = ActiveSelection.Current,
        places: List<SavedPlace> = listOf(home, work),
        coarse: Boolean = true,
        background: Boolean = true,
        foreground: Boolean = false,
        fix: LocationFix? = LocationFix(here, ageSeconds = 60),
        lastWorking: Coords? = last,
        seed: Coords? = null,
    ) = LocationInputs(selection, places, coarse, background, foreground, fix, lastWorking, seed)

    @Test
    fun savedPlaceIgnoresPermissionsAndFix() {
        val r = LocationResolver.resolve(input(selection = ActiveSelection.Place("w"), coarse = false, fix = null))
        assertEquals(work.coords, r.coords)
        assertEquals("Work", r.label)
        assertEquals(LocationSource.SAVED_PLACE, r.source)
        assertEquals("place:w", r.key)
    }

    @Test
    fun currentWithFreshFixAndBackgroundPermission() {
        val r = LocationResolver.resolve(input())
        assertEquals(here, r.coords)
        assertEquals("Current location", r.label)
        assertEquals(LocationSource.CURRENT, r.source)
        assertEquals(LocationResolver.KEY_CURRENT, r.key)
    }

    @Test
    fun currentWithoutBackgroundPermissionInBackgroundIsMaybeStale() {
        val r = LocationResolver.resolve(input(background = false, foreground = false))
        assertEquals(here, r.coords)
        assertEquals("Current location (may be stale)", r.label)
    }

    @Test
    fun currentWithoutBackgroundPermissionButAppOpenIsFresh() {
        val r = LocationResolver.resolve(input(background = false, foreground = true))
        assertEquals("Current location", r.label)
    }

    @Test
    fun oldFixIsMaybeStale() {
        val r = LocationResolver.resolve(input(fix = LocationFix(here, ageSeconds = 2 * 3600)))
        assertEquals(LocationSource.CURRENT_STALE, r.source)
    }

    @Test
    fun currentWithNoFixUsesLastWorkingAndSaysSo() {
        val r = LocationResolver.resolve(input(fix = null))
        assertEquals(last, r.coords)
        assertEquals("Last known location", r.label)
        assertEquals(LocationResolver.KEY_CURRENT, r.key)
        assertFalse(r.showLocationHint)
    }

    @Test
    fun currentWithPermissionRevokedUsesLastWorking() {
        val r = LocationResolver.resolve(input(coarse = false, background = false))
        assertEquals(last, r.coords)
        assertEquals(LocationSource.LAST_KNOWN, r.source)
    }

    @Test
    fun currentWithNothingFallsBackToBrisbaneWithHint() {
        val r = LocationResolver.resolve(input(fix = null, lastWorking = null))
        assertEquals(FALLBACK_COORDS, r.coords)
        assertEquals("Brisbane (default)", r.label)
        assertTrue(r.showLocationHint)
    }

    @Test
    fun unsetWithSeedUsesHome() {
        val r = LocationResolver.resolve(input(selection = ActiveSelection.Unset, places = emptyList(), seed = seed))
        assertEquals(seed, r.coords)
        assertEquals("Home", r.label)
        assertEquals(LocationSource.SEED, r.source)
    }

    @Test
    fun unsetWithoutSeedButWithPermissionUsesCurrent() {
        val r = LocationResolver.resolve(input(selection = ActiveSelection.Unset, places = emptyList()))
        assertEquals(here, r.coords)
        assertEquals(LocationSource.CURRENT, r.source)
    }

    @Test
    fun unsetWithoutSeedOrPermissionFallsBack() {
        val r = LocationResolver.resolve(
            input(selection = ActiveSelection.Unset, places = emptyList(), coarse = false, fix = null, lastWorking = null),
        )
        assertEquals(FALLBACK_COORDS, r.coords)
        assertTrue(r.showLocationHint)
    }

    @Test
    fun deletedActivePlaceBehavesAsUnset() {
        val r = LocationResolver.resolve(input(selection = ActiveSelection.Place("gone"), coarse = false, fix = null, lastWorking = null))
        assertEquals(LocationSource.FALLBACK, r.source)
        val withPerm = LocationResolver.resolve(input(selection = ActiveSelection.Place("gone")))
        assertEquals(LocationSource.CURRENT, withPerm.source)
    }

    @Test
    fun needsFixOnlyForCurrentWithPermission() {
        assertTrue(LocationResolver.needsFix(ActiveSelection.Current, emptyList(), true, null))
        assertFalse(LocationResolver.needsFix(ActiveSelection.Current, emptyList(), false, null))
        assertFalse(LocationResolver.needsFix(ActiveSelection.Place("h"), listOf(home), true, null))
        assertFalse(LocationResolver.needsFix(ActiveSelection.Unset, emptyList(), true, seed))
    }

    @Test
    fun expectedKeyAndMatching() {
        assertEquals("place:h", LocationResolver.expectedKey(ActiveSelection.Place("h"), listOf(home), false, null))
        assertEquals("current", LocationResolver.expectedKey(ActiveSelection.Current, emptyList(), false, null))
        assertTrue(LocationResolver.cacheMatches("fallback", "current"))
        assertFalse(LocationResolver.cacheMatches("place:h", "place:w"))
        assertFalse(LocationResolver.cacheMatches("current", "place:h"))
    }

    @Test
    fun seedParsing() {
        assertEquals(Coords(-27.4, 153.0), parseSeed("-27.4", " 153.0 "))
        assertEquals(null, parseSeed("", "153"))
        assertEquals(null, parseSeed(null, null))
        assertEquals(null, parseSeed("-127", "153"))
    }
}
