package dev.nick.raincheck.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where rain slots come from. Swap this for a radar-based source later without touching the UI.
 */
interface RainSource {
    /** Returns 15-minute slots around now (one past slot plus the next few hours). */
    @Throws(IOException::class)
    fun fetch(coords: Coords): List<Slot>
}

const val USER_AGENT = "RainCheck/1.0 (personal Wear OS app; https://github.com/ngerver1/pixelwatchapps)"

internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Open-Meteo forecast API. No key needed.
 *
 * Note: Open-Meteo has no native 15-minute model for Australia, so these values are
 * smoothed from hourly models. It's a forecast, not radar.
 */
class OpenMeteoRainSource(
    private val baseUrl: String = "https://api.open-meteo.com/v1/forecast",
    private val http: (String) -> String = { httpGet(it) },
) : RainSource {

    override fun fetch(coords: Coords): List<Slot> = parse(http(url(coords)))

    fun url(coords: Coords): String =
        "$baseUrl?latitude=${fmt(coords.lat)}&longitude=${fmt(coords.lon)}" +
            "&minutely_15=precipitation" +
            "&past_minutely_15=1" +
            "&forecast_minutely_15=$FORECAST_SLOTS" +
            "&timeformat=unixtime"

    companion object {
        /**
         * 3 hours is 12 slots. We ask for 20 (5 hours) so that the full 3-hour window is still
         * covered as the cache ages towards its 60-minute stale limit.
         */
        const val FORECAST_SLOTS = 20

        @Serializable
        private data class Response(val minutely_15: Minutely? = null)

        @Serializable
        private data class Minutely(
            val time: List<Long> = emptyList(),
            val precipitation: List<Double?> = emptyList(),
        )

        /**
         * Open-Meteo reports precipitation as the sum over the *preceding* 15 minutes, so the
         * value stamped at time T describes the slot [T - 15 min, T).
         */
        fun parse(body: String): List<Slot> {
            val m = json.decodeFromString(Response.serializer(), body).minutely_15
                ?: throw IOException("Response has no minutely_15 block")
            return m.time.zip(m.precipitation)
                .mapNotNull { (t, p) -> p?.let { Slot(startEpochSec = t - SLOT_SECONDS, precipitationMm = it) } }
                .sortedBy { it.startEpochSec }
        }

        private fun fmt(d: Double) = String.format(java.util.Locale.ROOT, "%.4f", d)
    }
}

/** Plain GET returning the body. Throws [IOException] on any non-2xx. */
fun httpGet(url: String, userAgent: String = USER_AGENT, timeoutMs: Int = 15_000): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code from ${conn.url.host}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
