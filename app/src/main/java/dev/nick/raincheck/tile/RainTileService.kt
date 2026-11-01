package dev.nick.raincheck.tile

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.nick.raincheck.data.RainRepository
import dev.nick.raincheck.work.Refresh
import kotlinx.coroutines.Dispatchers

class RainTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            // Tapping the tile body sends a LoadAction with this id: fetch now.
            if (requestParams.currentState.lastClickableId == TileLayout.ID_REFRESH) {
                Refresh.now(this@RainTileService)
            }
            val snapshot = RainRepository(this@RainTileService).snapshot()
            TileLayout.tile(this@RainTileService, requestParams.deviceConfiguration, snapshot)
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture(Dispatchers.Main.immediate) {
            ResourceBuilders.Resources.Builder().setVersion(TileLayout.RESOURCES_VERSION).build()
        }

    override fun onTileAddEvent(requestParams: EventBuilders.TileAddEvent) {
        Refresh.now(this)
    }
}
