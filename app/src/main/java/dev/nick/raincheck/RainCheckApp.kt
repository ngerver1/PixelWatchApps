package dev.nick.raincheck

import android.app.Application
import android.content.Context
import android.text.format.DateFormat
import dev.nick.raincheck.core.Presentation
import dev.nick.raincheck.core.present
import dev.nick.raincheck.data.Perms
import dev.nick.raincheck.data.RainRepository
import dev.nick.raincheck.data.Snapshot
import dev.nick.raincheck.work.Refresh
import java.time.Instant
import java.util.Date

class RainCheckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Refresh.schedulePeriodic(this)
    }
}

/** Builds what to draw from stored data and the current clock. */
fun Snapshot.presentAt(context: Context, now: Instant = Instant.now()): Presentation =
    present(cache, selection, places, Perms.hasCoarse(context), pendingSeed, now)

/** "9:41" or "9:41 am", following the watch's 12/24-hour setting. */
fun formatTime(context: Context, instant: Instant): String =
    DateFormat.getTimeFormat(context).format(Date.from(instant))
