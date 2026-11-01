package dev.nick.raincheck.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import dev.nick.raincheck.core.Coords
import dev.nick.raincheck.core.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object Perms {
    fun hasCoarse(context: Context) = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasBackground(context: Context) = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Coarse location via the Fused Location Provider. Never asks for fine location: the weather
 * grid is ~15 km, so precise location adds nothing.
 */
class WatchLocation(context: Context) {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /**
     * @param activeRequestAllowed true when the app is in the foreground or has background
     *   permission, so a fresh fix may be requested. Otherwise only the OS's last known fix is used.
     */
    @SuppressLint("MissingPermission")
    suspend fun fix(activeRequestAllowed: Boolean): LocationFix? {
        if (!Perms.hasCoarse(appContext)) return null
        return try {
            val fresh = if (activeRequestAllowed) {
                withTimeoutOrNull(FIX_TIMEOUT_MS) {
                    val request = CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                        .setGranularity(Granularity.GRANULARITY_COARSE)
                        .setMaxUpdateAgeMillis(MAX_CACHED_AGE_MS)
                        .build()
                    client.getCurrentLocation(request, null).awaitOrNull()
                }
            } else {
                null
            }
            (fresh ?: client.lastLocation.awaitOrNull())?.toFix()
        } catch (e: SecurityException) {
            null
        }
    }

    private fun Location.toFix(): LocationFix {
        val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
        return LocationFix(Coords(latitude, longitude), ageSeconds = (ageNanos / 1_000_000_000L).coerceAtLeast(0))
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 20_000L
        const val MAX_CACHED_AGE_MS = 10 * 60 * 1000L
    }
}

/** Minimal Task -> coroutine bridge, to avoid an extra dependency. */
private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
    }
}
