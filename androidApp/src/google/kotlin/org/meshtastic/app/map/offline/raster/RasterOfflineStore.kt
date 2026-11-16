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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.meshtastic.feature.map.terrain.GeoBounds
import org.meshtastic.feature.map.terrain.TerrainTileMath
import org.meshtastic.feature.map.terrain.TileIndex
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private val LOG = Logger.withTag("RasterOfflineStore")

private const val DOWNLOAD_WORKERS = 8

/** What fetching one tile found. A failure to fetch is an [IOException] from the fetcher, not a value. */
internal sealed interface FetchedTile {
    class Real(val bytes: ByteArray) : FetchedTile

    /** The provider has no tile here (HTTP 404). */
    data object Hole : FetchedTile
}

internal fun interface TileFetcher {
    /** Throws [IOException] when the tile could not be fetched for any reason other than the provider having none. */
    suspend fun fetch(basemapId: String, tile: TileIndex): FetchedTile
}

internal sealed interface SaveResult {
    data class Saved(val region: RasterOfflineRegion) : SaveResult

    /** Every tile was a tile hole, so there is nothing to keep. */
    data object NoCoverage : SaveResult

    data class Failed(val cause: Throwable) : SaveResult
}

/** What the map should draw for a tile, from the saved tiles of one basemap alone. */
internal sealed interface TileLookup {
    data object Blank : TileLookup

    class Exact(val bytes: ByteArray) : TileLookup

    /** The requested tile is finer than the saved zoom range: crop and enlarge [bytes], the tile at [sourceTile]. */
    class Overzoom(val bytes: ByteArray, val sourceTile: TileIndex) : TileLookup

    /** The requested tile is coarser than the saved zoom range: shrink and join [tiles], all at [sourceZoom]. */
    class Underzoom(val sourceZoom: Int, val tiles: List<Pair<TileIndex, ByteArray>>) : TileLookup
}

internal data class RasterOfflineSummary(val regionCount: Int, val totalBytes: Long)

/** One saved offline region of a raster basemap. */
@Serializable
internal data class RasterOfflineRegion(
    val id: String,
    val name: String,
    val basemapId: String,
    val southLat: Double,
    val westLon: Double,
    val northLat: Double,
    val eastLon: Double,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Long,
    /** Tile holes in this region that no other region fills with a real tile. */
    val holeCount: Long,
    /** Bytes of every real tile the region covers, shared tiles included. */
    val byteSize: Long,
    /** Bytes deleting this region would free: its real tiles that no other region covers. */
    val freesBytes: Long,
    val savedAtEpochSeconds: Long,
) {
    val zoomRange: IntRange
        get() = minZoom..maxZoom

    val bounds: GeoBounds
        get() = GeoBounds(south = southLat, west = westLon, north = northLat, east = eastLon)

    val isPartlyCovered: Boolean
        get() = holeCount > 0
}

@Serializable
private data class Manifest(val regions: List<RasterOfflineRegion> = emptyList(), val totalBytes: Long = 0)

/**
 * The saved tiles of the raster basemaps, one deduplicated store under [baseDir] (the app's durable files directory,
 * never a cache the OS may clear).
 *
 * A tile lives at `tiles/<basemap>/<z>/<x>/<y>` (or `<y>.hole` for a tile hole) and is stored once however many regions
 * cover it. A save downloads into `staging/`, which is thrown away on any stop, and only a finished download is
 * promoted into `tiles/` and recorded. A stop in the middle of promoting or deleting leaves a `dirty` marker, and the
 * next open sweeps tiles no region covers.
 */
@Suppress("TooManyFunctions")
internal class RasterOfflineStore(
    private val baseDir: File,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) {
    private val tilesDir = File(baseDir, "tiles")
    private val stagingDir = File(baseDir, "staging")
    private val manifestFile = File(baseDir, "manifest.json")
    private val dirtyMarker = File(baseDir, "dirty")
    private val mutex = Mutex()

    @Volatile private var manifest: Manifest

    init {
        baseDir.mkdirs()
        stagingDir.deleteRecursively()
        manifest = readManifest()
        if (dirtyMarker.exists()) {
            sweepOrphans()
            dirtyMarker.delete()
        }
    }

    fun list(): List<RasterOfflineRegion> = manifest.regions.sortedByDescending { it.savedAtEpochSeconds }

    fun summary(): RasterOfflineSummary = manifest.let { RasterOfflineSummary(it.regions.size, it.totalBytes) }

    /** Whether any region of [basemapId] exists, so the map can skip lookups for a basemap with nothing saved. */
    fun hasRegions(basemapId: String): Boolean = manifest.regions.any { it.basemapId == basemapId }

    @Suppress("LongParameterList", "ReturnCount")
    suspend fun save(
        basemapId: String,
        bounds: GeoBounds,
        zoomRange: IntRange,
        name: String,
        fetcher: TileFetcher,
        onProgress: (done: Long, total: Long) -> Unit,
    ): SaveResult {
        val regionId = UUID.randomUUID().toString()
        val staging = File(stagingDir, regionId)
        val tiles = zoomRange.flatMap { TerrainTileMath.tilesAt(it, bounds) }
        try {
            val real = AtomicLong()
            val done = AtomicLong()
            val total = tiles.size.toLong()
            onProgress(0, total)
            try {
                download(basemapId, tiles, fetcher, staging, real, done, total, onProgress)
            } catch (e: IOException) {
                LOG.w(e) { "Offline region download stopped" }
                return SaveResult.Failed(e)
            }
            if (real.get() == 0L) return SaveResult.NoCoverage

            val region =
                RasterOfflineRegion(
                    id = regionId,
                    name = name.trim().ifBlank { coordinateLabel(bounds) },
                    basemapId = basemapId,
                    southLat = bounds.south,
                    westLon = bounds.west,
                    northLat = bounds.north,
                    eastLon = bounds.east,
                    minZoom = zoomRange.first,
                    maxZoom = zoomRange.last,
                    tileCount = total,
                    holeCount = 0,
                    byteSize = 0,
                    freesBytes = 0,
                    savedAtEpochSeconds = nowEpochSeconds(),
                )
            mutex.withLock {
                dirtyMarker.writeText("1")
                promote(basemapId, tiles, staging)
                commit(manifest.regions + region)
                dirtyMarker.delete()
            }
            return SaveResult.Saved(manifest.regions.first { it.id == regionId })
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            val remaining = manifest.regions.filterNot { it.id == id }
            if (remaining.size == manifest.regions.size) return
            val gone = manifest.regions.first { it.id == id }
            dirtyMarker.writeText("1")
            commit(remaining)
            val stillCovered = remaining.filter { it.basemapId == gone.basemapId }
            gone
                .tiles()
                .filterNot { tile -> stillCovered.any { it.covers(tile) } }
                .forEach { deleteTileFiles(gone.basemapId, it) }
            dirtyMarker.delete()
        }
    }

    fun lookup(basemapId: String, tile: TileIndex): TileLookup {
        val real = realFile(basemapId, tile)
        return when {
            real.isFile -> TileLookup.Exact(real.readBytes())

            holeFile(basemapId, tile).exists() -> TileLookup.Blank

            else -> {
                val regions = manifest.regions.filter { it.basemapId == basemapId }
                overzoom(basemapId, tile, regions) ?: underzoom(basemapId, tile, regions) ?: TileLookup.Blank
            }
        }
    }

    private fun overzoom(basemapId: String, tile: TileIndex, regions: List<RasterOfflineRegion>): TileLookup? {
        for (maxZoom in regions.map { it.maxZoom }.distinct().filter { tile.zoom > it }.sortedDescending()) {
            val shift = tile.zoom - maxZoom
            val source = TileIndex(maxZoom, tile.x shr shift, tile.y shr shift)
            val file = realFile(basemapId, source)
            if (file.isFile) return TileLookup.Overzoom(file.readBytes(), source)
        }
        return null
    }

    private fun underzoom(basemapId: String, tile: TileIndex, regions: List<RasterOfflineRegion>): TileLookup? {
        for (
        minZoom in
        regions
            .map { it.minZoom }
            .distinct()
            .filter { tile.zoom < it && tile.zoom >= it - UNDERZOOM_LEVELS }
            .sorted()
        ) {
            val shift = minZoom - tile.zoom
            val span = 1 shl shift
            val found = buildList {
                for (x in (tile.x shl shift) until (tile.x shl shift) + span) {
                    for (y in (tile.y shl shift) until (tile.y shl shift) + span) {
                        val child = TileIndex(minZoom, x, y)
                        val file = realFile(basemapId, child)
                        if (file.isFile) add(child to file.readBytes())
                    }
                }
            }
            if (found.isNotEmpty()) return TileLookup.Underzoom(minZoom, found)
        }
        return null
    }

    @Suppress("LongParameterList")
    private suspend fun download(
        basemapId: String,
        tiles: List<TileIndex>,
        fetcher: TileFetcher,
        staging: File,
        real: AtomicLong,
        done: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) = coroutineScope {
        val queue = Channel<TileIndex>(Channel.UNLIMITED)
        tiles.forEach { queue.trySend(it) }
        queue.close()
        repeat(DOWNLOAD_WORKERS) {
            launch {
                for (tile in queue) {
                    when (val fetched = fetcher.fetch(basemapId, tile)) {
                        is FetchedTile.Real -> {
                            stagedFile(staging, basemapId, tile, hole = false).writeBytesCreatingParents(fetched.bytes)
                            real.incrementAndGet()
                        }

                        FetchedTile.Hole ->
                            stagedFile(staging, basemapId, tile, hole = true).writeBytesCreatingParents(ByteArray(0))
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }
        }
    }

    /** Moves each staged tile into place; a real tile always beats a hole, and a newer real tile replaces an older. */
    private fun promote(basemapId: String, tiles: List<TileIndex>, staging: File) {
        for (tile in tiles) {
            val stagedReal = stagedFile(staging, basemapId, tile, hole = false)
            val stagedHole = stagedFile(staging, basemapId, tile, hole = true)
            if (stagedReal.exists()) {
                moveReplacing(stagedReal, realFile(basemapId, tile))
                holeFile(basemapId, tile).delete()
            } else if (stagedHole.exists() && !realFile(basemapId, tile).exists()) {
                moveReplacing(stagedHole, holeFile(basemapId, tile))
            }
        }
    }

    /** Records [regions] with sizes recomputed from what is on disk, shared tiles counted once. */
    private fun commit(regions: List<RasterOfflineRegion>) {
        val measured = measure(regions)
        val next = Manifest(measured.first, measured.second)
        val tmp = File(baseDir, "manifest.json.tmp")
        tmp.writeText(Json.encodeToString(Manifest.serializer(), next))
        Files.move(
            tmp.toPath(),
            manifestFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        manifest = next
    }

    private fun measure(regions: List<RasterOfflineRegion>): Pair<List<RasterOfflineRegion>, Long> {
        var total = 0L
        val result = mutableListOf<RasterOfflineRegion>()
        for ((basemapId, group) in regions.groupBy { it.basemapId }) {
            val (measured, bytes) = measureBasemap(basemapId, group)
            result += measured
            total += bytes
        }
        return result to total
    }

    /** Sizes [group] (all one basemap's regions) from disk; the second value counts each shared tile once. */
    private fun measureBasemap(
        basemapId: String,
        group: List<RasterOfflineRegion>,
    ): Pair<List<RasterOfflineRegion>, Long> {
        val coverage = HashMap<TileIndex, Int>()
        val tilesByRegion = group.associateWith { it.tiles() }
        tilesByRegion.values.forEach { tiles -> tiles.forEach { coverage.merge(it, 1, Int::plus) } }
        val sizes = HashMap<TileIndex, Long>() // -1 marks a tile hole
        for (tile in coverage.keys) {
            val real = realFile(basemapId, tile)
            sizes[tile] =
                if (real.isFile) {
                    real.length()
                } else if (holeFile(basemapId, tile).exists()) {
                    -1L
                } else {
                    0L
                }
        }
        val measured = tilesByRegion.map { (region, tiles) ->
            val held = tiles.map { it to sizes.getValue(it) }
            region.copy(
                byteSize = held.sumOf { (_, size) -> size.coerceAtLeast(0) },
                freesBytes =
                held
                    .filter { (tile, _) -> coverage.getValue(tile) == 1 }
                    .sumOf { (_, size) -> size.coerceAtLeast(0) },
                holeCount = held.count { (_, size) -> size < 0 }.toLong(),
            )
        }
        return measured to sizes.values.filter { it > 0 }.sum()
    }

    private fun sweepOrphans() {
        val regions = manifest.regions
        val basemaps = tilesDir.listFiles().orEmpty().filter { it.isDirectory }
        for (basemapDir in basemaps) {
            val covering = regions.filter { it.basemapId == basemapDir.name }
            basemapDir
                .walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val parts = file.relativeTo(basemapDir).invariantSeparatorsPath.split('/')
                    val tile =
                        parts
                            .takeIf { it.size == TILE_PATH_PARTS }
                            ?.let {
                                val z = it[0].toIntOrNull()
                                val x = it[1].toIntOrNull()
                                val y = it[2].removeSuffix(HOLE_SUFFIX).toIntOrNull()
                                if (z != null && x != null && y != null) TileIndex(z, x, y) else null
                            }
                    if (tile == null || covering.none { it.covers(tile) }) file.delete()
                }
        }
    }

    private fun readManifest(): Manifest {
        if (!manifestFile.exists()) return Manifest()
        return try {
            Json.decodeFromString(Manifest.serializer(), manifestFile.readText())
        } catch (e: SerializationException) {
            LOG.w(e) { "Raster offline manifest is corrupt; treating it as empty" }
            Manifest()
        }
    }

    private fun tileRelativePath(basemapId: String, tile: TileIndex) = "$basemapId/${tile.zoom}/${tile.x}/${tile.y}"

    private fun realFile(basemapId: String, tile: TileIndex) = File(tilesDir, tileRelativePath(basemapId, tile))

    private fun holeFile(basemapId: String, tile: TileIndex) =
        File(tilesDir, tileRelativePath(basemapId, tile) + HOLE_SUFFIX)

    private fun deleteTileFiles(basemapId: String, tile: TileIndex) {
        realFile(basemapId, tile).delete()
        holeFile(basemapId, tile).delete()
    }

    private fun stagedFile(staging: File, basemapId: String, tile: TileIndex, hole: Boolean) =
        File(staging, tileRelativePath(basemapId, tile) + if (hole) HOLE_SUFFIX else "")

    private fun File.writeBytesCreatingParents(bytes: ByteArray) {
        parentFile?.mkdirs()
        writeBytes(bytes)
    }

    private fun moveReplacing(from: File, to: File) {
        to.parentFile?.mkdirs()
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun coordinateLabel(bounds: GeoBounds): String = String.format(
        Locale.ROOT,
        "%.3f, %.3f",
        (bounds.south + bounds.north) / 2,
        (bounds.west + bounds.east) / 2,
    )

    private fun RasterOfflineRegion.tiles(): List<TileIndex> = zoomRange.flatMap { TerrainTileMath.tilesAt(it, bounds) }

    /** Whether [tile] is one of this region's tiles — tile-aligned, so exactly what the download enumerated. */
    private fun RasterOfflineRegion.covers(tile: TileIndex): Boolean {
        if (tile.zoom !in zoomRange) return false
        val northwest = TerrainTileMath.tileAt(tile.zoom, northLat, westLon)
        val southeast = TerrainTileMath.tileAt(tile.zoom, southLat, eastLon)
        val inY = tile.y in northwest.y..southeast.y
        val inX =
            if (northwest.x <= southeast.x) {
                tile.x in northwest.x..southeast.x
            } else {
                tile.x >= northwest.x || tile.x <= southeast.x
            }
        return inX && inY
    }

    private companion object {
        const val HOLE_SUFFIX = ".hole"
        const val UNDERZOOM_LEVELS = 2
        const val TILE_PATH_PARTS = 3
        const val MILLIS_PER_SECOND = 1000L
    }
}
