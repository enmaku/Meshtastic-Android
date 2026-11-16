/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.app.map.offline.raster

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.meshtastic.feature.map.terrain.TileIndex
import org.meshtastic.feature.map.tiles.MapTileCatalogue
import org.meshtastic.feature.map.tiles.tileUrl
import java.io.IOException
import java.net.HttpURLConnection

/**
 * Fetches a catalogue basemap's tiles for saving. A 404 is a tile hole, which the provider uses for "no tile here";
 * every other failure is an [IOException] and stops the save.
 */
internal class HttpTileFetcher(client: OkHttpClient, private val ioDispatcher: CoroutineDispatcher) : TileFetcher {
    // A save would only churn the live-tile cache, so it goes around it.
    private val client = client.newBuilder().cache(null).build()

    override suspend fun fetch(basemapId: String, tile: TileIndex): FetchedTile = withContext(ioDispatcher) {
        val spec = MapTileCatalogue.basemaps.firstOrNull { it.id == basemapId }?.spec
        val url = spec?.tileUrl(tile.x, tile.y, tile.zoom) ?: throw IOException("No tile url for $basemapId")
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            when {
                response.code == HttpURLConnection.HTTP_NOT_FOUND -> FetchedTile.Hole

                !response.isSuccessful -> throw IOException("Tile server answered ${response.code}")

                else ->
                    response.body.bytes().takeIf { it.isNotEmpty() }?.let { FetchedTile.Real(it) }
                        ?: throw IOException("Tile server answered an empty body")
            }
        }
    }
}
