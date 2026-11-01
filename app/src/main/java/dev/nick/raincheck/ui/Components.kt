package dev.nick.raincheck.ui

import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.input.RemoteInputIntentHelper
import dev.nick.raincheck.core.WET_THRESHOLD_MM

/** 12-bar mini chart, same rules as the tile. */
@Composable
fun RainChart(bars: List<Double?>, modifier: Modifier = Modifier) {
    val wet = MaterialTheme.colorScheme.primary
    val dry = MaterialTheme.colorScheme.onSurfaceVariant
    val missing = MaterialTheme.colorScheme.outlineVariant
    val chartHeight = 32f
    val scaleMax = maxOf(1.0, bars.filterNotNull().maxOrNull() ?: 0.0)
    Row(
        modifier = modifier.height(chartHeight.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { mm ->
            val (h, color) = when {
                mm == null -> 2f to missing
                mm >= WET_THRESHOLD_MM -> maxOf(4f, (chartHeight * mm / scaleMax).toFloat()) to wet
                else -> 2f to dry
            }
            Box(
                Modifier
                    .width(7.dp)
                    .height(h.dp)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private const val INPUT_KEY = "text"

/**
 * Wear's standard text input (voice, keyboard or handwriting). Returns a function that opens it
 * with the given prompt; [onResult] receives non-blank trimmed text.
 */
@Composable
fun rememberTextInput(onResult: (String) -> Unit): (prompt: String) -> Unit {
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data: Intent = result.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(INPUT_KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) callback(text)
    }
    return remember(launcher) {
        { prompt: String ->
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(
                intent,
                listOf(RemoteInput.Builder(INPUT_KEY).setLabel(prompt).build()),
            )
            launcher.launch(intent)
        }
    }
}
