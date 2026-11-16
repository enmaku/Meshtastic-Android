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
package org.meshtastic.feature.map.tiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineRegionPlanTest {

    private val mb = 1024L * 1024

    @Test
    fun `only the two USGS basemaps are saveable`() {
        val saveable = MapTileCatalogue.basemaps.filter { it.saveable }.map { it.id }

        assertEquals(listOf("usgs-topo", "usgs-sat"), saveable)
    }

    @Test
    fun `zoom stop is the basemap maximum for catalogue basemaps and absent otherwise`() {
        assertEquals(16, MapTileCatalogue.zoomStopFor("usgs-topo"))
        assertEquals(19, MapTileCatalogue.zoomStopFor("osm"))
        assertEquals(20, MapTileCatalogue.zoomStopFor("esri-imagery"))
        assertNull(MapTileCatalogue.zoomStopFor("some-custom-id"))
    }

    @Test
    fun `default zoom range runs from the nearest whole current zoom to the basemap maximum`() {
        assertEquals(12..16, OfflineRegionPlan.defaultZoomRange(currentZoom = 12.4f, minZoom = 0, maxZoom = 16))
        assertEquals(13..16, OfflineRegionPlan.defaultZoomRange(currentZoom = 12.5f, minZoom = 0, maxZoom = 16))
    }

    @Test
    fun `default zoom range clamps the current zoom to the basemap limits`() {
        assertEquals(16..16, OfflineRegionPlan.defaultZoomRange(currentZoom = 19.2f, minZoom = 0, maxZoom = 16))
        assertEquals(2..16, OfflineRegionPlan.defaultZoomRange(currentZoom = 0.6f, minZoom = 2, maxZoom = 16))
    }

    @Test
    fun `tile count of the whole world at zoom 0 and 1`() {
        val world = TileArea(south = -80.0, west = -179.0, north = 80.0, east = 179.0)

        assertEquals(1L, OfflineRegionPlan.tileCount(world, 0..0))
        assertEquals(5L, OfflineRegionPlan.tileCount(world, 0..1))
    }

    @Test
    fun `estimate is tile count times 25 KB`() {
        assertEquals(40L * 25 * 1024, OfflineRegionPlan.estimateBytes(40))
    }

    @Test
    fun `a small save is allowed`() {
        assertEquals(SaveCheck.Allowed, OfflineRegionPlan.check(tileCount = 100, freeBytes = 10_000 * mb))
    }

    @Test
    fun `above 200 MB the save needs confirmation`() {
        // 8,500 tiles * 25 KB is about 207 MB.
        assertEquals(SaveCheck.Large, OfflineRegionPlan.check(tileCount = 8_500, freeBytes = 10_000 * mb))
        assertEquals(SaveCheck.Allowed, OfflineRegionPlan.check(tileCount = 8_000, freeBytes = 10_000 * mb))
    }

    @Test
    fun `above 100000 tiles the save is blocked`() {
        assertEquals(SaveCheck.TooManyTiles, OfflineRegionPlan.check(tileCount = 100_001, freeBytes = 1_000_000 * mb))
        assertTrue(OfflineRegionPlan.check(tileCount = 100_000, freeBytes = 1_000_000 * mb) != SaveCheck.TooManyTiles)
    }

    @Test
    fun `the save is blocked when the estimate exceeds free space minus a 500 MB reserve`() {
        // 4,000 tiles is about 98 MB. Free 590 MB leaves 90 MB after the reserve.
        val blocked = OfflineRegionPlan.check(tileCount = 4_000, freeBytes = 590 * mb)

        assertTrue(blocked is SaveCheck.InsufficientSpace)
        assertEquals(OfflineRegionPlan.estimateBytes(4_000), blocked.neededBytes)
        assertEquals(590 * mb, blocked.freeBytes)
        assertFalse(OfflineRegionPlan.check(tileCount = 4_000, freeBytes = 700 * mb) is SaveCheck.InsufficientSpace)
    }
}
