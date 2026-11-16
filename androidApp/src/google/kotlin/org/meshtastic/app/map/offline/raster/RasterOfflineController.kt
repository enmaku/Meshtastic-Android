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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.meshtastic.feature.map.terrain.GeoBounds

/** Progress of the one save that may run at a time. */
internal data class SaveProgress(val done: Long, val total: Long) {
    val percent: Int
        get() = if (total <= 0) 0 else (done * PERCENT / total).toInt()

    private companion object {
        const val PERCENT = 100
    }
}

/** What to tell the user once a save has ended without a normal completion. */
internal enum class SaveNotice {
    /** Saved, but some tiles are tile holes and will be blank offline. */
    PartlyCovered,

    /** Refused: every tile was a hole. */
    NoCoverage,

    /** The download stopped; nothing was kept. */
    Failed,
}

/** Drives [RasterOfflineStore] for the UI: one save at a time, plus the list the sheet shows. */
internal class RasterOfflineController(
    private val store: RasterOfflineStore,
    private val fetcher: TileFetcher,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _regions = MutableStateFlow(store.list())
    val regions: StateFlow<List<RasterOfflineRegion>> = _regions.asStateFlow()

    private val _summary = MutableStateFlow(store.summary())
    val summary: StateFlow<RasterOfflineSummary> = _summary.asStateFlow()

    private val _progress = MutableStateFlow<SaveProgress?>(null)
    val progress: StateFlow<SaveProgress?> = _progress.asStateFlow()

    private val _notice = MutableStateFlow<SaveNotice?>(null)
    val notice: StateFlow<SaveNotice?> = _notice.asStateFlow()

    private var job: Job? = null

    fun lookup(basemapId: String, tile: org.meshtastic.feature.map.terrain.TileIndex) = store.lookup(basemapId, tile)

    fun hasRegions(basemapId: String) = store.hasRegions(basemapId)

    fun save(basemapId: String, bounds: GeoBounds, zoomRange: IntRange, name: String) {
        if (job?.isActive == true) return
        _notice.value = null
        _progress.value = SaveProgress(0, 0)
        job =
            scope.launch(ioDispatcher) {
                try {
                    val result =
                        store.save(basemapId, bounds, zoomRange, name, fetcher) { done, total ->
                            _progress.value = SaveProgress(done, total)
                        }
                    _notice.value =
                        when (result) {
                            is SaveResult.Saved -> SaveNotice.PartlyCovered.takeIf { result.region.isPartlyCovered }
                            SaveResult.NoCoverage -> SaveNotice.NoCoverage
                            is SaveResult.Failed -> SaveNotice.Failed
                        }
                    refresh()
                } finally {
                    _progress.value = null
                }
            }
    }

    /** Stops a running save; the store discards what it had staged. */
    fun cancel() {
        job?.cancel()
    }

    fun delete(id: String) {
        scope.launch(ioDispatcher) {
            store.delete(id)
            refresh()
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    private fun refresh() {
        _regions.value = store.list()
        _summary.value = store.summary()
    }
}
