package dev.nick.raincheck.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.nick.raincheck.core.ActiveSelection
import dev.nick.raincheck.core.Coords
import dev.nick.raincheck.data.Perms
import dev.nick.raincheck.data.RainRepository
import dev.nick.raincheck.work.Refresh
import dev.nick.raincheck.work.SurfaceUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val destRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destRequest.value = intent?.getStringExtra(EXTRA_DEST)
        setContent {
            MaterialTheme {
                RainCheckNav(destRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destRequest.value = intent.getStringExtra(EXTRA_DEST)
    }

    override fun onResume() {
        super.onResume()
        // While the app is open, foreground location is allowed even without background
        // permission, so refresh the location and forecast now.
        Refresh.now(this, foreground = true)
    }

    companion object {
        const val EXTRA_DEST = "dest"
        const val DEST_LOCATIONS = "locations"
    }
}

private object Routes {
    const val MAIN = "main"
    const val LOCATIONS = "locations"
    const val PLACE = "place/{id}"
    fun place(id: String) = "place/$id"
    const val SAVE_HERE = "save_here"
    const val SEARCH = "search"
    const val LABEL = "label"
    const val BACKGROUND = "background"
}

/** A place found by "Save where I am now" or "Search", waiting for a label. */
data class PendingPlace(val coords: Coords, val suggestion: String?)

@Composable
private fun RainCheckNav(destRequest: MutableState<String?>) {
    val context = LocalContext.current
    val repo = remember { RainRepository(context) }
    val snapshot by repo.snapshots.collectAsState(initial = null)
    val nav = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingPlace?>(null) }
    var refreshRequestedAt by remember { mutableStateOf<Long?>(null) }

    // Coarse permission, then run whatever needed it.
    var afterCoarse by remember { mutableStateOf<(() -> Unit)?>(null) }
    val coarseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterCoarse
        afterCoarse = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(context, "Location permission is needed for that", Toast.LENGTH_SHORT).show()
        }
    }
    fun withCoarse(action: () -> Unit) {
        if (Perms.hasCoarse(context)) {
            action()
        } else {
            afterCoarse = action
            coarseLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun afterLocationChange() {
        // Tile shows "Updating…" straight away, then the fetch fills it in.
        SurfaceUpdater.requestRedraw(context)
        Refresh.now(context, foreground = true)
    }

    fun select(selection: ActiveSelection) {
        scope.launch {
            repo.setSelection(selection)
            afterLocationChange()
        }
    }

    LaunchedEffect(destRequest.value) {
        if (destRequest.value == MainActivity.DEST_LOCATIONS) {
            nav.navigate(Routes.LOCATIONS) { launchSingleTop = true }
            destRequest.value = null
        }
    }
    LaunchedEffect(refreshRequestedAt) {
        if (refreshRequestedAt != null) {
            delay(30_000)
            refreshRequestedAt = null
        }
    }

    AppScaffold {
        SwipeDismissableNavHost(navController = nav, startDestination = Routes.MAIN) {
            composable(Routes.MAIN) {
                val requested = refreshRequestedAt
                val fetchedAtMs = (snapshot?.cache?.fetchedAtEpochSec ?: 0L) * 1000
                MainScreen(
                    snapshot = snapshot,
                    refreshing = requested != null && fetchedAtMs < requested,
                    onRefresh = {
                        refreshRequestedAt = System.currentTimeMillis()
                        Refresh.now(context, foreground = true)
                    },
                    onLocations = { nav.navigate(Routes.LOCATIONS) },
                )
            }
            composable(Routes.LOCATIONS) {
                LocationsScreen(
                    snapshot = snapshot,
                    onCurrent = {
                        withCoarse {
                            select(ActiveSelection.Current)
                            if (!Perms.hasBackground(context)) nav.navigate(Routes.BACKGROUND)
                        }
                    },
                    onFixBackground = { nav.navigate(Routes.BACKGROUND) },
                    onPlace = { select(ActiveSelection.Place(it)) },
                    onEditPlace = { nav.navigate(Routes.place(it)) },
                    onSaveHere = { withCoarse { nav.navigate(Routes.SAVE_HERE) } },
                    onSearch = { nav.navigate(Routes.SEARCH) },
                )
            }
            composable(Routes.PLACE) { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                EditPlaceScreen(
                    snapshot = snapshot,
                    placeId = id,
                    onSetActive = {
                        select(ActiveSelection.Place(id))
                        nav.popBackStack()
                    },
                    onRename = { label ->
                        scope.launch {
                            repo.renamePlace(id, label)
                            SurfaceUpdater.requestRedraw(context)
                        }
                    },
                    onDelete = {
                        scope.launch {
                            repo.deletePlace(id)
                            afterLocationChange()
                        }
                        nav.popBackStack()
                    },
                    onGone = { nav.popBackStack() },
                )
            }
            composable(Routes.SAVE_HERE) {
                SaveHereScreen(onFound = { coords ->
                    pending = PendingPlace(coords, suggestion = null)
                    nav.navigate(Routes.LABEL) { popUpTo(Routes.LOCATIONS) }
                })
            }
            composable(Routes.SEARCH) {
                SearchScreen(onPick = { result ->
                    pending = PendingPlace(result.coords, suggestion = result.name)
                    nav.navigate(Routes.LABEL)
                })
            }
            composable(Routes.LABEL) {
                val p = pending
                LabelScreen(suggestion = p?.suggestion, onLabel = { label ->
                    if (p != null) {
                        scope.launch {
                            if (repo.addPlace(label, p.coords) == null) {
                                Toast.makeText(context, "You already have 5 places", Toast.LENGTH_SHORT).show()
                            } else {
                                afterLocationChange()
                            }
                        }
                    }
                    pending = null
                    nav.popBackStack(Routes.LOCATIONS, inclusive = false)
                })
            }
            composable(Routes.BACKGROUND) {
                BackgroundLocationScreen(onDone = { nav.popBackStack() })
            }
        }
    }
}
