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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import org.meshtastic.feature.map.terrain.TileIndex
import java.io.ByteArrayOutputStream

/** PNG is lossless; the quality argument is ignored but required. */
private const val PNG_QUALITY = 100

/**
 * Draws a saved basemap: saved tiles first, online or off, and live tiles for everything else. With no network, what
 * the saved zoom range does not hold is drawn from it instead — enlarged past the range (overzoom), shrunk just short
 * of it (underzoom) — and left blank past that.
 */
internal class SavedRasterTileProvider(
    private val basemapId: String,
    private val tileSize: Int,
    private val controller: RasterOfflineController,
    private val live: TileProvider,
    private val isOnline: () -> Boolean,
) : TileProvider {

    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val target = TileIndex(zoom, x, y)
        val lookup = controller.lookup(basemapId, target)
        return when {
            lookup is TileLookup.Exact -> Tile(tileSize, tileSize, lookup.bytes)
            isOnline() -> live.getTile(x, y, zoom) ?: fromSaved(lookup, target)
            else -> fromSaved(lookup, target)
        }
    }

    private fun fromSaved(lookup: TileLookup, target: TileIndex): Tile? = when (lookup) {
        is TileLookup.Overzoom -> overzoom(lookup, target)
        is TileLookup.Underzoom -> underzoom(lookup, target)
        else -> null
    }

    private fun overzoom(lookup: TileLookup.Overzoom, target: TileIndex): Tile? {
        val shift = target.zoom - lookup.sourceTile.zoom
        val source = BitmapFactory.decodeByteArray(lookup.bytes, 0, lookup.bytes.size) ?: return null
        val span = (source.width shr shift).coerceAtLeast(1)
        val left = (target.x - (lookup.sourceTile.x shl shift)) * span
        val top = (target.y - (lookup.sourceTile.y shl shift)) * span
        return render { canvas, paint ->
            canvas.drawBitmap(source, Rect(left, top, left + span, top + span), Rect(0, 0, tileSize, tileSize), paint)
        }
    }

    private fun underzoom(lookup: TileLookup.Underzoom, target: TileIndex): Tile? {
        val shift = lookup.sourceZoom - target.zoom
        val cell = (tileSize shr shift).coerceAtLeast(1)
        return render { canvas, paint ->
            for ((tile, bytes) in lookup.tiles) {
                val child = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val left = (tile.x - (target.x shl shift)) * cell
                val top = (tile.y - (target.y shl shift)) * cell
                canvas.drawBitmap(child, null, Rect(left, top, left + cell, top + cell), paint)
            }
        }
    }

    private fun render(draw: (Canvas, Paint) -> Unit): Tile {
        val bitmap = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), Paint(Paint.FILTER_BITMAP_FLAG))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        return Tile(tileSize, tileSize, out.toByteArray())
    }
}
