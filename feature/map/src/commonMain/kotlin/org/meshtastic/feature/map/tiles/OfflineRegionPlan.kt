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

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** What the sheet should do about a save of a given size. */
sealed interface SaveCheck {
    /** Nothing to say; the save can go ahead. */
    data object Allowed : SaveCheck

    /** Over the warning threshold: the user may still go ahead ("Download anyway"). */
    data object Large : SaveCheck

    /** Over the tile cap; blocked until the user zooms in or lowers the maximum zoom. */
    data object TooManyTiles : SaveCheck

    /** The estimate does not fit in free space once the reserve is held back; blocked. */
    data class InsufficientSpace(val neededBytes: Long, val freeBytes: Long) : SaveCheck
}

/** A geographic box, degrees. [west] greater than [east] means it crosses the antimeridian. */
data class TileArea(val south: Double, val west: Double, val north: Double, val east: Double)

/** Platform-free planning for saving an offline region: tile count, size estimate and the limits on both. */
object OfflineRegionPlan {

    /** A rough average tile; the estimate is deliberately an upper bound ("up to"). */
    const val ESTIMATED_BYTES_PER_TILE = 25L * 1024

    private const val MAX_LATITUDE = 85.05112878
    private const val HALF_TURN_DEGREES = 180.0
    private const val FULL_TURN_DEGREES = 360.0

    /** The USGS export cap, and so the hard stop. */
    const val MAX_TILES = 100_000L

    const val WARN_BYTES = 200L * 1024 * 1024

    /** Free space the save must leave untouched. */
    const val RESERVE_BYTES = 500L * 1024 * 1024

    /** The nearest whole zoom to [currentZoom], clamped to the basemap, through the basemap's maximum. */
    fun defaultZoomRange(currentZoom: Float, minZoom: Int, maxZoom: Int): IntRange =
        currentZoom.roundToInt().coerceIn(minZoom, maxZoom)..maxZoom

    /** How many slippy-map tiles cover [area] across [zoomRange], both corners' own tiles included. */
    fun tileCount(area: TileArea, zoomRange: IntRange): Long = zoomRange.sumOf { tileCountAt(it, area) }

    private fun tileCountAt(zoom: Int, area: TileArea): Long {
        val worldTiles = 2.0.pow(zoom)
        val last = worldTiles.toInt() - 1
        val northY = tileY(area.north, worldTiles, last)
        val southY = tileY(area.south, worldTiles, last)
        val westX = tileX(area.west, worldTiles, last)
        val eastX = tileX(area.east, worldTiles, last)
        val rows = (southY - northY + 1).coerceAtLeast(0)
        val columns = if (westX <= eastX) eastX - westX + 1 else (last - westX + 1) + (eastX + 1)
        return columns.toLong() * rows.toLong()
    }

    private fun tileX(longitude: Double, worldTiles: Double, last: Int): Int =
        ((longitude + HALF_TURN_DEGREES) / FULL_TURN_DEGREES * worldTiles).toInt().coerceIn(0, last)

    private fun tileY(latitude: Double, worldTiles: Double, last: Int): Int {
        val radians = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE) * PI / HALF_TURN_DEGREES
        return ((1.0 - asinh(tan(radians)) / PI) / 2.0 * worldTiles).toInt().coerceIn(0, last)
    }

    fun estimateBytes(tileCount: Long): Long = tileCount * ESTIMATED_BYTES_PER_TILE

    fun check(tileCount: Long, freeBytes: Long): SaveCheck {
        val estimate = estimateBytes(tileCount)
        return when {
            tileCount > MAX_TILES -> SaveCheck.TooManyTiles
            estimate > freeBytes - RESERVE_BYTES -> SaveCheck.InsufficientSpace(estimate, freeBytes)
            estimate > WARN_BYTES -> SaveCheck.Large
            else -> SaveCheck.Allowed
        }
    }
}
