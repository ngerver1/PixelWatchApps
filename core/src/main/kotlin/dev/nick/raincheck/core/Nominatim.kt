package dev.nick.raincheck.core

import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URLEncoder

data class PlaceResult(val name: String, val detail: String, val coords: Coords)

/**
 * Address search via Nominatim (OpenStreetMap).
 *
 * Usage policy: custom User-Agent, at most one request per second, and only called when the
 * user actually searches (no autocomplete).
 */
class NominatimClient(
    private val baseUrl: String = "https://nominatim.openstreetmap.org/search",
    private val http: (String) -> String = { httpGet(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val lock = Any()
    private var lastRequestAt = 0L

    @Throws(IOException::class)
    fun search(query: String): List<PlaceResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        synchronized(lock) {
            val wait = lastRequestAt + MIN_INTERVAL_MS - clock()
            if (wait > 0) sleep(wait)
            lastRequestAt = clock()
        }
        return parse(http(url(q)))
    }

    fun url(query: String): String =
        "$baseUrl?q=${URLEncoder.encode(query, "UTF-8")}&format=jsonv2&countrycodes=au&limit=$MAX_RESULTS&addressdetails=0"

    companion object {
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_RESULTS = 5

        @Serializable
        private data class Hit(
            val lat: String,
            val lon: String,
            val display_name: String = "",
            val name: String? = null,
        )

        fun parse(body: String): List<PlaceResult> =
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Hit.serializer()), body)
                .mapNotNull { hit ->
                    val lat = hit.lat.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = hit.lon.toDoubleOrNull() ?: return@mapNotNull null
                    val parts = hit.display_name.split(", ")
                    val name = hit.name?.takeIf { it.isNotBlank() } ?: parts.firstOrNull().orEmpty()
                    val detail = parts.filter { it != name }.take(2).joinToString(", ")
                    PlaceResult(name = name, detail = detail, coords = Coords(lat, lon))
                }
                .take(MAX_RESULTS)
    }
}
