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
package org.meshtastic.app.map.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.app.map.offline.raster.RasterOfflineRegion
import org.meshtastic.app.map.offline.raster.RasterOfflineSummary
import org.meshtastic.app.map.offline.raster.SaveNotice
import org.meshtastic.app.map.offline.raster.SaveProgress
import org.meshtastic.core.common.util.formatByteSize
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.delete
import org.meshtastic.core.resources.raster_offline_blocked_space
import org.meshtastic.core.resources.raster_offline_blocked_tiles
import org.meshtastic.core.resources.raster_offline_delete_message
import org.meshtastic.core.resources.raster_offline_delete_title
import org.meshtastic.core.resources.raster_offline_download
import org.meshtastic.core.resources.raster_offline_download_anyway
import org.meshtastic.core.resources.raster_offline_empty
import org.meshtastic.core.resources.raster_offline_estimate
import org.meshtastic.core.resources.raster_offline_failed
import org.meshtastic.core.resources.raster_offline_name_label
import org.meshtastic.core.resources.raster_offline_no_coverage
import org.meshtastic.core.resources.raster_offline_partial_chip
import org.meshtastic.core.resources.raster_offline_partial_notice
import org.meshtastic.core.resources.raster_offline_progress
import org.meshtastic.core.resources.raster_offline_progress_note
import org.meshtastic.core.resources.raster_offline_progress_title
import org.meshtastic.core.resources.raster_offline_row_detail
import org.meshtastic.core.resources.raster_offline_row_frees
import org.meshtastic.core.resources.raster_offline_save_map
import org.meshtastic.core.resources.raster_offline_saved_heading
import org.meshtastic.core.resources.raster_offline_saved_summary
import org.meshtastic.core.resources.raster_offline_warning_large
import org.meshtastic.core.resources.raster_offline_zoom_current_max
import org.meshtastic.core.resources.raster_offline_zoom_range
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.feature.map.terrain.GeoBounds
import org.meshtastic.feature.map.tiles.OfflineRegionPlan
import org.meshtastic.feature.map.tiles.SaveCheck
import org.meshtastic.feature.map.tiles.TileArea
import kotlin.math.roundToInt

/**
 * Saves the captured view of the selected basemap as an offline region, and lists the saved ones. Pure UI: the limits
 * come from [OfflineRegionPlan] and all state arrives as parameters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun RasterOfflineSheet(
    capture: OfflineCapture,
    freeBytes: Long,
    regions: List<RasterOfflineRegion>,
    summary: RasterOfflineSummary,
    progress: SaveProgress?,
    notice: SaveNotice?,
    onSave: (name: String, zoomRange: IntRange) -> Unit,
    onCancelSave: () -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var maxZoom by remember { mutableFloatStateOf(capture.maxZoom.toFloat()) }
    var deleting by remember { mutableStateOf<RasterOfflineRegion?>(null) }

    val zoomRange = capture.currentZoom..maxZoom.roundToInt()
    val tileCount = OfflineRegionPlan.tileCount(capture.bounds.toTileArea(), zoomRange)
    val check = OfflineRegionPlan.check(tileCount, freeBytes)
    val estimate = OfflineRegionPlan.estimateBytes(tileCount)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.raster_offline_save_map), style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.raster_offline_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                Text(
                    stringResource(Res.string.raster_offline_zoom_range, zoomRange.first, zoomRange.last),
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = maxZoom,
                    onValueChange = { maxZoom = it },
                    valueRange = capture.currentZoom.toFloat()..capture.maxZoom.toFloat(),
                    steps = (capture.maxZoom - capture.currentZoom - 1).coerceAtLeast(0),
                    enabled = capture.maxZoom > capture.currentZoom,
                )
                Text(
                    stringResource(Res.string.raster_offline_zoom_current_max, capture.currentZoom, capture.maxZoom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                stringResource(Res.string.raster_offline_estimate, tileCount.toString(), formatByteSize(estimate)),
                style = MaterialTheme.typography.bodyLarge,
            )

            when (check) {
                SaveCheck.Large -> Banner(stringResource(Res.string.raster_offline_warning_large), BannerKind.Warning)

                SaveCheck.TooManyTiles ->
                    Banner(stringResource(Res.string.raster_offline_blocked_tiles), BannerKind.Error)

                is SaveCheck.InsufficientSpace ->
                    Banner(
                        stringResource(
                            Res.string.raster_offline_blocked_space,
                            formatByteSize(check.neededBytes),
                            formatByteSize(check.freeBytes),
                        ),
                        BannerKind.Error,
                    )

                SaveCheck.Allowed -> Unit
            }

            when (notice) {
                SaveNotice.PartlyCovered ->
                    Banner(stringResource(Res.string.raster_offline_partial_notice), BannerKind.Info)

                SaveNotice.NoCoverage -> Banner(stringResource(Res.string.raster_offline_no_coverage), BannerKind.Error)

                SaveNotice.Failed -> Banner(stringResource(Res.string.raster_offline_failed), BannerKind.Error)

                null -> Unit
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                Button(
                    onClick = { onSave(name, zoomRange) },
                    enabled = (check == SaveCheck.Allowed || check == SaveCheck.Large) && progress == null,
                ) {
                    Text(
                        stringResource(
                            if (check == SaveCheck.Large) {
                                Res.string.raster_offline_download_anyway
                            } else {
                                Res.string.raster_offline_download
                            },
                        ),
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.raster_offline_saved_heading),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        Res.string.raster_offline_saved_summary,
                        summary.regionCount,
                        formatByteSize(summary.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (regions.isEmpty()) {
                Text(
                    stringResource(Res.string.raster_offline_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            regions.forEach { region -> RegionRow(region, onDelete = { deleting = region }) }
            Spacer(Modifier.height(24.dp))
        }
    }

    progress?.let { SaveProgressDialog(it, onCancelSave) }

    deleting?.let { region ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.raster_offline_delete_title)) },
            text = {
                Text(
                    stringResource(
                        Res.string.raster_offline_delete_message,
                        region.name,
                        formatByteSize(region.freesBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(region.id)
                        deleting = null
                    },
                ) {
                    Text(stringResource(Res.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun RegionRow(region: RasterOfflineRegion, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(region.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    Res.string.raster_offline_row_detail,
                    region.basemapLabel(),
                    region.minZoom,
                    region.maxZoom,
                    formatByteSize(region.byteSize),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.raster_offline_row_frees, formatByteSize(region.freesBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (region.isPartlyCovered) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(Res.string.raster_offline_partial_chip, region.holeCount)) },
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(MeshtasticIcons.Delete, contentDescription = stringResource(Res.string.delete))
        }
    }
}

@Composable
private fun SaveProgressDialog(progress: SaveProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.raster_offline_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        Res.string.raster_offline_progress,
                        progress.done.toInt(),
                        progress.total.toInt(),
                        progress.percent,
                    ),
                )
                Text(
                    stringResource(Res.string.raster_offline_progress_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) } },
    )
}

private enum class BannerKind {
    Info,
    Warning,
    Error,
}

@Composable
private fun Banner(text: String, kind: BannerKind) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when (kind) {
            BannerKind.Info -> colors.secondaryContainer to colors.onSecondaryContainer
            BannerKind.Warning -> colors.tertiaryContainer to colors.onTertiaryContainer
            BannerKind.Error -> colors.errorContainer to colors.onErrorContainer
        }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(12.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The basemap's own name, which is a product name and so not translated. */
private fun RasterOfflineRegion.basemapLabel(): String =
    org.meshtastic.feature.map.tiles.MapTileCatalogue.basemaps.firstOrNull { it.id == basemapId }?.label ?: basemapId

private fun GeoBounds.toTileArea() = TileArea(south = south, west = west, north = north, east = east)
