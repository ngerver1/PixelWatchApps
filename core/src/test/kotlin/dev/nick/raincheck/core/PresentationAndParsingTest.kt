package dev.nick.raincheck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PresentationAndParsingTest {
    private val base = 1_800_000_000L
    private val now = Instant.ofEpochSecond(base)
    private val home = SavedPlace("h", "Home", -27.45, 153.01)
    private val work = SavedPlace("w", "Work", -27.47, 153.03)

    private fun cache(key: String, label: String) = CachedForecast(
        slots = (0 until 13).map { Slot(base + it * SLOT_SECONDS, if (it == 2) 1.0 else 0.0) },
        fetchedAtEpochSec = base - 60,
        lat = home.lat, lon = home.lon,
        label = label,
        locationKey = key,
    )

    @Test
    fun showsUpdatingWhenCacheIsForAnotherPlace() {
        val p = present(cache("place:h", "Home"), ActiveSelection.Place("w"), listOf(home, work), true, null, now)
        assertTrue(p.updating)
        assertEquals("Updating…", p.longText)
        assertEquals("Work", p.locationLabel)
    }

    @Test
    fun showsVerdictWhenCacheMatches() {
        val p = present(cache("place:h", "Home"), ActiveSelection.Place("h"), listOf(home), true, null, now)
        assertFalse(p.updating)
        assertEquals("Dry for ~30 min", p.longText)
        assertEquals("Home", p.locationLabel)
        assertEquals(1.0, p.bars[2]!!, 0.0)
    }

    @Test
    fun renamedPlaceShowsNewName() {
        val renamed = Places.rename(listOf(home), "h", "Casa")
        val p = present(cache("place:h", "Home"), ActiveSelection.Place("h"), renamed, true, null, now)
        assertEquals("Casa", p.locationLabel)
    }

    @Test
    fun noCacheShowsNoData() {
        val p = present(null, ActiveSelection.Unset, emptyList(), false, null, now)
        assertEquals("No recent data", p.longText)
        assertEquals("Brisbane (default)", p.locationLabel)
        assertTrue(p.showLocationHint)
    }

    @Test
    fun parsesOpenMeteoAndShiftsToPrecedingSlot() {
        val body = """
            {"latitude":-27.5,"longitude":153.0,"utc_offset_seconds":0,
             "minutely_15_units":{"time":"unixtime","precipitation":"mm"},
             "minutely_15":{"time":[${base},${base + 900},${base + 1800}],"precipitation":[0.0,null,0.4]}}
        """.trimIndent()
        val slots = OpenMeteoRainSource.parse(body)
        assertEquals(listOf(Slot(base - 900, 0.0), Slot(base + 900, 0.4)), slots)
    }

    @Test
    fun openMeteoUrlHasRequiredParameters() {
        val url = OpenMeteoRainSource().url(Coords(-27.4698, 153.0251))
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=-27.4698&longitude=153.0251"))
        listOf("minutely_15=precipitation", "past_minutely_15=1", "forecast_minutely_15=", "timeformat=unixtime")
            .forEach { assertTrue(it, url.contains(it)) }
    }

    @Test
    fun parsesNominatim() {
        val body = """
            [{"lat":"-27.4650","lon":"153.0080","name":"Paddington","display_name":"Paddington, Brisbane City, Queensland, 4064, Australia"},
             {"lat":"bad","lon":"1","display_name":"x"}]
        """.trimIndent()
        val r = NominatimClient.parse(body)
        assertEquals(1, r.size)
        assertEquals("Paddington", r[0].name)
        assertEquals("Brisbane City, Queensland", r[0].detail)
        assertEquals(Coords(-27.465, 153.008), r[0].coords)
    }

    @Test
    fun nominatimIsRateLimitedAndBiasedToAustralia() {
        var t = 10_000L
        val slept = mutableListOf<Long>()
        val urls = mutableListOf<String>()
        val client = NominatimClient(http = { urls += it; "[]" }, clock = { t }, sleep = { slept += it; t += it })
        client.search("Paddington")
        t += 200
        client.search("Red Hill")
        assertEquals(listOf(800L), slept)
        assertTrue(urls[0].contains("countrycodes=au"))
        assertTrue(urls[0].contains("limit=5"))
        assertEquals(emptyList<PlaceResult>(), client.search("   "))
        assertEquals(2, urls.size)
    }

    @Test
    fun placesRoundTripAndLimits() {
        val five = (1..5).map { SavedPlace("$it", "P$it", 0.0, 0.0) }
        assertFalse(Places.canAdd(five))
        assertEquals(five, Places.decode(Places.encode(five)))
        assertEquals(emptyList<SavedPlace>(), Places.decode("not json"))
        assertEquals(ActiveSelection.Unset, Places.selectionAfterDelete(ActiveSelection.Place("3"), "3"))
        assertEquals(ActiveSelection.Current, Places.selectionAfterDelete(ActiveSelection.Current, "3"))
        assertEquals(ActiveSelection.Place("x"), ActiveSelection.decode(ActiveSelection.Place("x").encode()))
    }
}
