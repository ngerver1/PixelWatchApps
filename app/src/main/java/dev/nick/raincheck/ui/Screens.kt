package dev.nick.raincheck.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.nick.raincheck.core.ActiveSelection
import dev.nick.raincheck.core.Coords
import dev.nick.raincheck.core.LocationResolver
import dev.nick.raincheck.core.NominatimClient
import dev.nick.raincheck.core.PlaceResult
import dev.nick.raincheck.core.Places
import dev.nick.raincheck.data.Perms
import dev.nick.raincheck.data.Snapshot
import dev.nick.raincheck.data.WatchLocation
import dev.nick.raincheck.formatTime
import dev.nick.raincheck.presentAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** One shared client so the 1 request/second limit holds across screens. */
private val nominatim by lazy { NominatimClient() }

/** Standard scrolling screen: scaling list inside a ScreenScaffold. */
@Composable
private fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

@Composable
private fun WideButton(
    text: String,
    onClick: () -> Unit,
    secondary: String? = null,
    tonal: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) "Edit" else null,
        enabled = enabled,
        colors = if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
        secondaryLabel = if (secondary != null) {
            { Text(secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        label = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------------------------------------
// Main

@Composable
fun MainScreen(snapshot: Snapshot?, refreshing: Boolean, onRefresh: () -> Unit, onLocations: () -> Unit) {
    val context = LocalContext.current
    // Recompute the countdown from the cache as time passes.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    val p = snapshot?.presentAt(context, now)
    val footer = when {
        p == null -> ""
        p.showLocationHint -> "Set a location below"
        p.updatedAt != null -> "Updated ${formatTime(context, p.updatedAt!!)}"
        else -> ""
    }

    ListScreen {
        item {
            ListHeader {
                Text(p?.locationLabel.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            Text(
                p?.longText ?: "…",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item { RainChart(p?.bars ?: List(12) { null }, Modifier.padding(vertical = 4.dp)) }
        item { Caption(footer) }
        item { WideButton(if (refreshing) "Refreshing…" else "Refresh", onClick = onRefresh) }
        item { WideButton("Location", onClick = onLocations, secondary = p?.locationLabel, tonal = true) }
    }
}

// ---------------------------------------------------------------------------------------------
// Location settings

@Composable
fun LocationsScreen(
    snapshot: Snapshot?,
    onCurrent: () -> Unit,
    onFixBackground: () -> Unit,
    onPlace: (String) -> Unit,
    onEditPlace: (String) -> Unit,
    onSaveHere: () -> Unit,
    onSearch: () -> Unit,
) {
    val context = LocalContext.current
    val snap = snapshot ?: return
    val coarse = Perms.hasCoarse(context)
    val background = Perms.hasBackground(context)
    val activeKey = LocationResolver.expectedKey(snap.selection, snap.places, coarse, snap.pendingSeed)
    fun tick(on: Boolean, label: String) = if (on) "✓ $label" else label

    ListScreen {
        item { ListHeader { Text("Location") } }
        item {
            val currentActive = activeKey == LocationResolver.KEY_CURRENT
            WideButton(
                tick(currentActive, "Current location"),
                onClick = onCurrent,
                secondary = when {
                    !coarse -> "Needs location permission"
                    !background -> "May be stale when closed"
                    else -> null
                },
                tonal = !currentActive,
            )
        }
        if (activeKey == LocationResolver.KEY_CURRENT && coarse && !background) {
            item { WideButton("Allow all the time", onClick = onFixBackground, tonal = true) }
        }
        snap.places.forEach { place ->
            item(key = place.id) {
                val active = activeKey == LocationResolver.placeKey(place.id)
                WideButton(
                    tick(active, place.label),
                    onClick = { onPlace(place.id) },
                    onLongClick = { onEditPlace(place.id) },
                    secondary = "Hold to edit",
                    tonal = !active,
                )
            }
        }
        val canAdd = Places.canAdd(snap.places)
        item {
            WideButton(
                "Save where I am now",
                onClick = onSaveHere,
                enabled = canAdd,
                secondary = if (canAdd) null else "5 places max",
                tonal = true,
            )
        }
        item {
            WideButton(
                "Search",
                onClick = onSearch,
                enabled = canAdd,
                secondary = if (canAdd) "Suburb or address" else "5 places max",
                tonal = true,
            )
        }
    }
}

@Composable
fun EditPlaceScreen(
    snapshot: Snapshot?,
    placeId: String,
    onSetActive: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onGone: () -> Unit,
) {
    val snap = snapshot ?: return
    val place = snap.places.firstOrNull { it.id == placeId }
    if (place == null) {
        LaunchedEffect(Unit) { onGone() }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val rename = rememberTextInput(onRename)
    val isActive = snap.selection == ActiveSelection.Place(place.id)

    ListScreen {
        item { ListHeader { Text(place.label, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        if (!isActive) item { WideButton("Set active", onClick = onSetActive) }
        item { WideButton("Rename", onClick = { rename("New name") }, tonal = true) }
        item { WideButton("Delete", onClick = { confirmDelete = true }, tonal = true) }
    }

    AlertDialog(
        visible = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmDelete = false
                onDelete()
            })
        },
        title = { Text("Delete ${place.label}?") },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmDelete = false }) },
    )
}

@Composable
fun SaveHereScreen(onFound: (Coords) -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        failed = false
        val fix = WatchLocation(context).fix(activeRequestAllowed = true)
        if (fix != null) onFound(fix.coords) else failed = true
    }

    ListScreen {
        item { ListHeader { Text("Save where I am") } }
        if (failed) {
            item { Caption("Couldn't get your location. Check location is on.") }
            item { WideButton("Try again", onClick = { attempt++ }) }
        } else {
            item { Caption("Finding your approximate location…") }
        }
    }
}

@Composable
fun SearchScreen(onPick: (PlaceResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var autoOpened by rememberSaveable { mutableStateOf(false) }

    val input = rememberTextInput { query ->
        status = "Searching “$query”…"
        results = emptyList()
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { nominatim.search(query) }
                results = found
                status = if (found.isEmpty()) "No matches in Australia" else null
            } catch (e: Exception) {
                status = "Search failed. Check your connection."
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!autoOpened) {
            autoOpened = true
            input("Suburb or address")
        }
    }

    ListScreen {
        item { ListHeader { Text("Search") } }
        status?.let { s -> item { Caption(s) } }
        results.forEach { r ->
            item { WideButton(r.name, onClick = { onPick(r) }, secondary = r.detail.ifEmpty { null }, tonal = true) }
        }
        item { WideButton("Type or speak", onClick = { input("Suburb or address") }) }
        item { Caption("Search by OpenStreetMap Nominatim") }
    }
}

@Composable
fun LabelScreen(suggestion: String?, onLabel: (String) -> Unit) {
    val input = rememberTextInput(onLabel)
    val quickPicks = (listOfNotNull(suggestion) + listOf("Home", "Work", "Gym", "Other")).distinct()

    ListScreen {
        item { ListHeader { Text("Name this place") } }
        quickPicks.forEach { label ->
            item { WideButton(label, onClick = { onLabel(label) }, tonal = true) }
        }
        item { WideButton("Type or speak", onClick = { input("Place name") }) }
    }
}

// ---------------------------------------------------------------------------------------------
// Background location explanation

@Composable
fun BackgroundLocationScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onDone() else showSettings = true
    }

    ListScreen {
        item { ListHeader { Text("Keep the tile current") } }
        item {
            Caption(
                "To update rain for where you are while the app is closed, allow location " +
                    "“All the time”. Rain Check only uses approximate location, only to fetch the forecast.",
            )
        }
        item {
            WideButton("Allow all the time", onClick = {
                launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            })
        }
        if (showSettings) {
            item { Caption("In settings, open Permissions → Location → Allow all the time.") }
            item {
                WideButton("Open settings", tonal = true, onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
            }
        }
        item {
            WideButton("Not now", onClick = onDone, tonal = true, secondary = "Tile may show stale location")
        }
    }
}
