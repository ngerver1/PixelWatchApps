package dev.nick.raincheck.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.nick.raincheck.R
import dev.nick.raincheck.core.Presentation
import dev.nick.raincheck.core.Verdict
import dev.nick.raincheck.data.RainRepository
import dev.nick.raincheck.presentAt
import dev.nick.raincheck.ui.MainActivity

class RainComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val p = RainRepository(this).snapshot().presentAt(this)
        return build(request.complicationType, p)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(
            type,
            Presentation(
                locationLabel = "Home",
                longText = Verdict.DryFor(45).longText,
                shortText = Verdict.DryFor(45).shortText,
                verdict = Verdict.DryFor(45),
                bars = List(12) { 0.0 },
                updatedAt = null,
                updating = false,
                showLocationHint = false,
            ),
        )

    private fun build(type: ComplicationType, p: Presentation): ComplicationData? {
        val description = PlainComplicationText.Builder("${p.locationLabel}: ${p.longText}").build()
        val image = icon(p)?.let { MonochromaticImage.Builder(it).build() }
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(p.shortText).build(), description)
                    .setMonochromaticImage(image)
                    .setTapAction(openApp())
                    .build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(description, description)
                    .setMonochromaticImage(image)
                    .setTapAction(openApp())
                    .build()
            else -> null
        }
    }

    private fun icon(p: Presentation): Icon? = when (val v = p.verdict) {
        null, Verdict.NoData -> null
        else -> Icon.createWithResource(this, if (v.isRainingNow) R.drawable.ic_rain else R.drawable.ic_sun)
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
