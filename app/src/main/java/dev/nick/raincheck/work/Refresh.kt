package dev.nick.raincheck.work

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.nick.raincheck.complication.RainComplicationService
import dev.nick.raincheck.core.CachedForecast
import dev.nick.raincheck.core.LocationInputs
import dev.nick.raincheck.core.LocationResolver
import dev.nick.raincheck.core.OpenMeteoRainSource
import dev.nick.raincheck.core.RainSource
import dev.nick.raincheck.data.Perms
import dev.nick.raincheck.data.RainRepository
import dev.nick.raincheck.data.WatchLocation
import dev.nick.raincheck.tile.RainTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Scheduling entry points for fetching. */
object Refresh {
    private const val PERIODIC = "raincheck-periodic"
    private const val NOW = "raincheck-now"
    internal const val KEY_FOREGROUND = "foreground"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Every 15 minutes (WorkManager's minimum), whenever there's a network. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Immediate one-off fetch (tile tap, Refresh button, location change).
     * @param foreground true when the app screen is open, so a fresh location fix is allowed.
     */
    fun now(context: Context, foreground: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(KEY_FOREGROUND to foreground))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Something that reacts to a new forecast. The tile/complication updater is one; a future
 * rain-alert buzz would be another (compare [previous] and [current] and vibrate).
 */
fun interface ForecastObserver {
    fun onForecastUpdated(context: Context, previous: CachedForecast?, current: CachedForecast)
}

/** Asks the tile and complication to redraw. */
object SurfaceUpdater : ForecastObserver {
    override fun onForecastUpdated(context: Context, previous: CachedForecast?, current: CachedForecast) =
        requestRedraw(context)

    fun requestRedraw(context: Context) {
        TileService.getUpdater(context).requestUpdate(RainTileService::class.java)
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, RainComplicationService::class.java))
            .requestUpdateAll()
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ok = RefreshRunner(applicationContext).run(inputData.getBoolean(Refresh.KEY_FOREGROUND, false))
        return when {
            ok -> Result.success()
            runAttemptCount < 2 -> Result.retry()
            // Keep showing the last good data until it goes stale; the next periodic run tries again.
            else -> Result.failure()
        }
    }
}

/** One fetch: resolve location, fetch, cache, notify observers. */
class RefreshRunner(
    private val context: Context,
    private val repo: RainRepository = RainRepository(context),
    private val location: WatchLocation = WatchLocation(context),
    private val source: RainSource = OpenMeteoRainSource(),
    private val observers: List<ForecastObserver> = listOf(SurfaceUpdater),
) {
    suspend fun run(inForeground: Boolean): Boolean {
        repo.ensureSeeded()
        val snap = repo.snapshot()
        val coarse = Perms.hasCoarse(context)
        val background = Perms.hasBackground(context)

        val fix = if (LocationResolver.needsFix(snap.selection, snap.places, coarse, snap.pendingSeed)) {
            location.fix(activeRequestAllowed = inForeground || background)
        } else {
            null
        }
        val resolved = LocationResolver.resolve(
            LocationInputs(
                selection = snap.selection,
                places = snap.places,
                hasCoarsePermission = coarse,
                hasBackgroundPermission = background,
                inForeground = inForeground,
                fix = fix,
                lastWorking = snap.lastWorking,
                seed = snap.pendingSeed,
            ),
        )

        val slots = try {
            withContext(Dispatchers.IO) { source.fetch(resolved.coords) }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed: ${e.message}")
            return false
        }
        if (slots.isEmpty()) return false

        val cache = CachedForecast(
            slots = slots,
            fetchedAtEpochSec = System.currentTimeMillis() / 1000,
            lat = resolved.coords.lat,
            lon = resolved.coords.lon,
            label = resolved.label,
            locationKey = resolved.key,
            showLocationHint = resolved.showLocationHint,
        )
        val saved = repo.saveForecast(cache, lastWorking = resolved.coords.takeIf { resolved.isLiveFix }, hasCoarse = coarse)
        if (saved) observers.forEach { it.onForecastUpdated(context, snap.cache, cache) }
        return true
    }

    private companion object {
        const val TAG = "RainCheck"
    }
}
