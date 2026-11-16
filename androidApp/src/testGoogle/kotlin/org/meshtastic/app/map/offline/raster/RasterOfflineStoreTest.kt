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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.meshtastic.feature.map.terrain.GeoBounds
import org.meshtastic.feature.map.terrain.TerrainTileMath
import org.meshtastic.feature.map.terrain.TileIndex
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Serves `"<tag>:z/x/y"` for every tile unless told to answer a hole or fail. */
private class FakeFetcher(var tag: String = "v1") : TileFetcher {
    val holes = mutableSetOf<TileIndex>()
    var failAfter: Int? = null
    var gate: CompletableDeferred<Unit>? = null
    var calls = 0

    override suspend fun fetch(basemapId: String, tile: TileIndex): FetchedTile {
        calls++
        gate?.await()
        failAfter?.let { if (calls > it) throw IOException("network down") }
        return if (tile in holes) FetchedTile.Hole else FetchedTile.Real(bytesFor(tag, tile))
    }
}

private fun bytesFor(tag: String, t: TileIndex) = "$tag:${t.zoom}/${t.x}/${t.y}".toByteArray()

@OptIn(ExperimentalCoroutinesApi::class)
class RasterOfflineStoreTest {

    private lateinit var dir: File
    private lateinit var store: RasterOfflineStore
    private var now = 1_000L

    // Roughly 3 x 3 tiles at zoom 10 around Denver, and a box overlapping its eastern half.
    private val west = GeoBounds(south = 39.60, west = -105.20, north = 39.90, east = -104.85)
    private val east = GeoBounds(south = 39.60, west = -105.00, north = 39.90, east = -104.65)

    private fun tiles(bounds: GeoBounds, zooms: IntRange) = zooms.flatMap { TerrainTileMath.tilesAt(it, bounds) }

    private fun open() = RasterOfflineStore(dir) { now++ }

    @BeforeTest
    fun setUp() {
        dir =
            File.createTempFile("raster-offline", "").apply {
                delete()
                mkdirs()
            }
        store = open()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun exact(basemap: String, tile: TileIndex): String? =
        (store.lookup(basemap, tile) as? TileLookup.Exact)?.bytes?.decodeToString()

    @Test
    fun `a complete save is listed with its counts and serves its tiles`() = runTest {
        val all = tiles(west, 10..11)

        val result = store.save("usgs-topo", west, 10..11, "Front Range", FakeFetcher()) { _, _ -> }

        val region = assertIs<SaveResult.Saved>(result).region
        assertEquals("Front Range", region.name)
        assertEquals("usgs-topo", region.basemapId)
        assertEquals(10..11, region.zoomRange)
        assertEquals(all.size.toLong(), region.tileCount)
        assertEquals(0L, region.holeCount)
        assertEquals(listOf(region), store.list())
        assertEquals(all.sumOf { bytesFor("v1", it).size.toLong() }, region.byteSize)
        all.forEach { assertEquals("v1:${it.zoom}/${it.x}/${it.y}", exact("usgs-topo", it)) }
    }

    @Test
    fun `a blank name falls back to a coordinate label`() = runTest {
        val region =
            assertIs<SaveResult.Saved>(store.save("usgs-topo", west, 10..10, "  ", FakeFetcher()) { _, _ -> }).region

        assertTrue(region.name.isNotBlank())
        assertTrue(region.name.contains("39."), region.name)
    }

    @Test
    fun `a save reports progress up to the tile count`() = runTest {
        val seen = mutableListOf<Pair<Long, Long>>()
        val total = tiles(west, 10..10).size.toLong()

        store.save("usgs-topo", west, 10..10, "", FakeFetcher()) { done, of -> seen += done to of }

        assertEquals(total to total, seen.last())
        assertTrue(seen.all { it.second == total })
    }

    @Test
    fun `a network failure leaves nothing listed or served`() = runTest {
        val fetcher = FakeFetcher().apply { failAfter = 3 }

        val result = store.save("usgs-topo", west, 10..11, "", fetcher) { _, _ -> }

        assertIs<SaveResult.Failed>(result)
        assertEquals(emptyList(), store.list())
        tiles(west, 10..11).forEach { assertEquals(TileLookup.Blank, store.lookup("usgs-topo", it)) }
        assertEquals(0L, store.summary().totalBytes)
    }

    @Test
    fun `a cancelled save leaves nothing behind`() = runTest {
        val fetcher = FakeFetcher().apply { gate = CompletableDeferred() }
        val job = launch { store.save("usgs-topo", west, 10..11, "", fetcher) { _, _ -> } }
        advanceUntilIdle()

        job.cancel()
        advanceUntilIdle()

        assertEquals(emptyList(), store.list())
        tiles(west, 10..11).forEach { assertEquals(TileLookup.Blank, store.lookup("usgs-topo", it)) }
    }

    @Test
    fun `a save killed midway leaves nothing after reopening the store`() = runTest {
        // Simulate the process dying after tiles were staged but before the region was recorded.
        File(dir, "staging/dead/usgs-topo/10/1").apply { mkdirs() }.resolve("2").writeText("partial")

        val reopened = open()

        assertEquals(emptyList(), reopened.list())
        assertFalse(File(dir, "staging").exists() && File(dir, "staging").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `holes are counted and the save is flagged as partly covered`() = runTest {
        val all = tiles(west, 10..10)
        val fetcher = FakeFetcher().apply { holes += all.take(2) }

        val region = assertIs<SaveResult.Saved>(store.save("usgs-topo", west, 10..10, "", fetcher) { _, _ -> }).region

        assertEquals(2L, region.holeCount)
        assertEquals(all.size.toLong(), region.tileCount)
        assertTrue(region.isPartlyCovered)
        assertEquals(TileLookup.Blank, store.lookup("usgs-topo", all.first()))
    }

    @Test
    fun `a save where every tile is a hole is refused`() = runTest {
        val fetcher = FakeFetcher().apply { holes += tiles(west, 10..10) }

        val result = store.save("usgs-topo", west, 10..10, "", fetcher) { _, _ -> }

        assertEquals(SaveResult.NoCoverage, result)
        assertEquals(emptyList(), store.list())
    }

    @Test
    fun `overlapping regions are both kept and a shared tile is counted once in the total`() = runTest {
        val a = assertIs<SaveResult.Saved>(store.save("usgs-topo", west, 10..10, "A", FakeFetcher()) { _, _ -> }).region
        val b = assertIs<SaveResult.Saved>(store.save("usgs-topo", east, 10..10, "B", FakeFetcher()) { _, _ -> }).region

        val union = (tiles(west, 10..10) + tiles(east, 10..10)).toSet()
        val shared = tiles(west, 10..10).toSet().intersect(tiles(east, 10..10).toSet())
        assertTrue(shared.isNotEmpty())

        val summary = store.summary()
        assertEquals(2, summary.regionCount)
        assertEquals(union.sumOf { bytesFor("v1", it).size.toLong() }, summary.totalBytes)
        val sharedBytes = shared.sumOf { bytesFor("v1", it).size.toLong() }
        assertEquals(a.byteSize - sharedBytes, store.list().first { it.id == a.id }.freesBytes)
        assertEquals(b.byteSize - sharedBytes, store.list().first { it.id == b.id }.freesBytes)
    }

    @Test
    fun `the newest save supplies a shared tile`() = runTest {
        store.save("usgs-topo", west, 10..10, "A", FakeFetcher("old")) { _, _ -> }
        store.save("usgs-topo", east, 10..10, "B", FakeFetcher("new")) { _, _ -> }

        val shared = tiles(west, 10..10).toSet().intersect(tiles(east, 10..10).toSet()).first()
        val onlyWest = tiles(west, 10..10).first { it !in tiles(east, 10..10) }

        assertEquals("new:${shared.zoom}/${shared.x}/${shared.y}", exact("usgs-topo", shared))
        assertEquals("old:${onlyWest.zoom}/${onlyWest.x}/${onlyWest.y}", exact("usgs-topo", onlyWest))
    }

    @Test
    fun `a real tile beats a hole when regions overlap`() = runTest {
        val shared = tiles(west, 10..10).toSet().intersect(tiles(east, 10..10).toSet()).first()
        store.save("usgs-topo", west, 10..10, "A", FakeFetcher("real")) { _, _ -> }
        val holey = FakeFetcher("later").apply { holes += shared }
        store.save("usgs-topo", east, 10..10, "B", holey) { _, _ -> }

        assertEquals("real:${shared.zoom}/${shared.x}/${shared.y}", exact("usgs-topo", shared))
    }

    @Test
    fun `deleting one of two overlapping regions keeps the shared tiles and frees the rest`() = runTest {
        val a = assertIs<SaveResult.Saved>(store.save("usgs-topo", west, 10..10, "A", FakeFetcher()) { _, _ -> }).region
        store.save("usgs-topo", east, 10..10, "B", FakeFetcher()) { _, _ -> }
        val shared = tiles(west, 10..10).toSet().intersect(tiles(east, 10..10).toSet())
        val onlyWest = tiles(west, 10..10).first { it !in tiles(east, 10..10) }

        store.delete(a.id)

        shared.forEach { assertIs<TileLookup.Exact>(store.lookup("usgs-topo", it)) }
        assertEquals(TileLookup.Blank, store.lookup("usgs-topo", onlyWest))
        val remaining = store.list().single()
        assertEquals(remaining.byteSize, remaining.freesBytes)
        assertEquals(remaining.byteSize, store.summary().totalBytes)
    }

    @Test
    fun `deleting the last region leaves nothing`() = runTest {
        val a = assertIs<SaveResult.Saved>(store.save("usgs-topo", west, 10..11, "A", FakeFetcher()) { _, _ -> }).region

        store.delete(a.id)

        assertEquals(emptyList(), store.list())
        assertEquals(RasterOfflineSummary(0, 0L), store.summary())
        assertEquals(TileLookup.Blank, store.lookup("usgs-topo", tiles(west, 10..10).first()))
    }

    @Test
    fun `regions survive reopening the store`() = runTest {
        val saved = assertIs<SaveResult.Saved>(store.save("usgs-topo", west, 10..10, "A", FakeFetcher()) { _, _ -> })

        val reopened = open()

        assertEquals(listOf(saved.region), reopened.list())
        assertIs<TileLookup.Exact>(reopened.lookup("usgs-topo", tiles(west, 10..10).first()))
    }

    @Test
    fun `tiles are served only under their own basemap`() = runTest {
        store.save("usgs-topo", west, 10..10, "A", FakeFetcher()) { _, _ -> }

        assertEquals(TileLookup.Blank, store.lookup("usgs-sat", tiles(west, 10..10).first()))
    }

    @Test
    fun `past the tile aligned edge the lookup is blank`() = runTest {
        store.save("usgs-topo", west, 10..10, "A", FakeFetcher()) { _, _ -> }
        val covered = tiles(west, 10..10)
        val outside = TileIndex(10, covered.maxOf { it.x } + 5, covered.first().y)

        assertEquals(TileLookup.Blank, store.lookup("usgs-topo", outside))
    }

    @Test
    fun `finer than the zoom range is an overzoom of the deepest saved tile`() = runTest {
        store.save("usgs-topo", west, 10..11, "A", FakeFetcher()) { _, _ -> }
        val deepest = tiles(west, 11..11).first()
        val child = TileIndex(13, deepest.x * 4 + 3, deepest.y * 4 + 1)

        val lookup = assertIs<TileLookup.Overzoom>(store.lookup("usgs-topo", child))

        assertEquals(deepest, lookup.sourceTile)
        assertContentEquals(bytesFor("v1", deepest), lookup.bytes)
    }

    @Test
    fun `a tile hole inside the zoom range is blank rather than overzoomed`() = runTest {
        val all11 = tiles(west, 11..11)
        val fetcher = FakeFetcher().apply { holes += all11.first() }
        store.save("usgs-topo", west, 10..11, "A", fetcher) { _, _ -> }

        assertEquals(TileLookup.Blank, store.lookup("usgs-topo", all11.first()))
    }

    @Test
    fun `up to two levels wider than the widest saved level is an underzoom of its tiles`() = runTest {
        store.save("usgs-topo", west, 10..11, "A", FakeFetcher()) { _, _ -> }
        val parent = TileIndex(9, tiles(west, 10..10).first().x / 2, tiles(west, 10..10).first().y / 2)

        val lookup = assertIs<TileLookup.Underzoom>(store.lookup("usgs-topo", parent))

        assertEquals(10, lookup.sourceZoom)
        assertTrue(lookup.tiles.isNotEmpty())
        lookup.tiles.forEach { (tile, bytes) -> assertContentEquals(bytesFor("v1", tile), bytes) }
    }

    @Test
    fun `three levels wider than the widest saved level is blank`() = runTest {
        store.save("usgs-topo", west, 10..11, "A", FakeFetcher()) { _, _ -> }
        val first = tiles(west, 10..10).first()

        assertEquals(TileLookup.Blank, store.lookup("usgs-topo", TileIndex(7, first.x / 8, first.y / 8)))
    }
}
