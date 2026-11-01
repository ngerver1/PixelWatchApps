package dev.nick.raincheck.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.compactButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.TileBuilders
import dev.nick.raincheck.core.Presentation
import dev.nick.raincheck.core.Verdict
import dev.nick.raincheck.core.WET_THRESHOLD_MM
import dev.nick.raincheck.data.Snapshot
import dev.nick.raincheck.formatTime
import dev.nick.raincheck.presentAt
import dev.nick.raincheck.ui.MainActivity
import java.time.Duration
import java.time.Instant

object TileLayout {
    const val ID_REFRESH = "refresh"
    private const val ID_LOCATIONS = "locations"
    const val RESOURCES_VERSION = "1"

    /** The tile carries a timeline of layouts so the countdown advances without a fetch. */
    private val ENTRY_STEP: Duration = Duration.ofMinutes(5)
    private const val ENTRY_COUNT = 12
    private val FRESHNESS: Duration = Duration.ofMinutes(15)

    fun tile(context: Context, device: DeviceParameters, snapshot: Snapshot): TileBuilders.Tile {
        val now = Instant.now()
        val first = snapshot.presentAt(context, now)
        val count = if (first.updating || first.verdict == Verdict.NoData) 1 else ENTRY_COUNT

        val timeline = TimelineBuilders.Timeline.Builder()
        for (i in 0 until count) {
            val start = now.plus(ENTRY_STEP.multipliedBy(i.toLong()))
            val p = if (i == 0) first else snapshot.presentAt(context, start)
            val validity = TimelineBuilders.TimeInterval.Builder().setStartMillis(start.toEpochMilli())
            if (i < count - 1) validity.setEndMillis(start.plus(ENTRY_STEP).toEpochMilli())
            timeline.addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setValidity(validity.build())
                    .setLayout(LayoutElementBuilders.Layout.fromLayoutElement(layout(context, device, p)))
                    .build(),
            )
        }

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(timeline.build())
            .setFreshnessIntervalMillis(FRESHNESS.toMillis())
            .build()
    }

    fun layout(context: Context, device: DeviceParameters, p: Presentation): LayoutElement =
        materialScope(context, device) {
            primaryLayout(
                titleSlot = { locationChip(p.locationLabel) },
                mainSlot = { body(p) },
                onClick = refreshClickable(),
            )
        }

    private fun MaterialScope.locationChip(label: String): LayoutElement =
        compactButton(
            onClick = openLocationsClickable(context),
            labelContent = { text(LayoutString(label), maxLines = 1) },
        )

    private fun MaterialScope.body(p: Presentation): LayoutElement {
        val footer = when {
            p.showLocationHint -> "Set a location in the app"
            p.updatedAt != null -> "Updated ${formatTime(context, p.updatedAt!!)}"
            else -> "Tap to refresh"
        }
        return LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                text(
                    LayoutString(p.longText),
                    typography = if (p.longText.length <= 9) Typography.DISPLAY_SMALL else Typography.TITLE_LARGE,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                ),
            )
            .addContent(vSpace(6f))
            .addContent(
                rainChart(
                    bars = p.bars,
                    wetColor = colorScheme.primary.staticArgb,
                    dryColor = colorScheme.onSurfaceVariant.staticArgb,
                    missingColor = colorScheme.outlineVariant.staticArgb,
                ),
            )
            .addContent(vSpace(6f))
            .addContent(
                text(
                    LayoutString(footer),
                    typography = Typography.BODY_SMALL,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                ),
            )
            .build()
    }

    private const val CHART_HEIGHT = 28f
    private const val BAR_WIDTH = 6f
    private const val BAR_GAP = 3f
    private const val WET_STUB = 4f
    private const val BASELINE = 2f

    /**
     * 12 bars for the next 3 hours. Wet bars are proportional to precipitation (scaled to the
     * wettest slot, at least 1 mm) with a visible minimum; dry slots are a flat baseline.
     */
    fun rainChart(bars: List<Double?>, wetColor: Int, dryColor: Int, missingColor: Int): LayoutElement {
        val scaleMax = maxOf(1.0, bars.filterNotNull().maxOrNull() ?: 0.0)
        val row = LayoutElementBuilders.Row.Builder()
            .setHeight(dp(CHART_HEIGHT))
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_BOTTOM)
        bars.forEachIndexed { i, mm ->
            if (i > 0) row.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(BAR_GAP)).build())
            val (height, color) = when {
                mm == null -> BASELINE to missingColor
                mm >= WET_THRESHOLD_MM -> maxOf(WET_STUB, (CHART_HEIGHT * mm / scaleMax).toFloat()) to wetColor
                else -> BASELINE to dryColor
            }
            row.addContent(bar(height, color))
        }
        return row.build()
    }

    private fun bar(height: Float, color: Int): LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(BAR_WIDTH))
            .setHeight(dp(height))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(1.5f)).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun vSpace(height: Float): LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun refreshClickable(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(ID_REFRESH)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

    private fun openLocationsClickable(context: Context): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(ID_LOCATIONS)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_DEST,
                                ActionBuilders.stringExtra(MainActivity.DEST_LOCATIONS),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
}
