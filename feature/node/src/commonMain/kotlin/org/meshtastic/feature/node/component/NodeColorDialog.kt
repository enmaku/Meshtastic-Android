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
package org.meshtastic.feature.node.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.HueSlider
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import org.jetbrains.compose.resources.stringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.cancel
import org.meshtastic.core.resources.node_color
import org.meshtastic.core.resources.node_color_use_default
import org.meshtastic.core.resources.save

private val PickerHeight = 220.dp
private val HueSliderHeight = 32.dp

/** Opaque ARGB. Node chips ignore transparency. */
internal fun Color.opaqueArgb(): Int = (toArgb() and RGB_MASK) or OPAQUE

@Composable
internal fun NodeColorDialog(
    initialColor: Color,
    onConfirm: (Int) -> Unit,
    onUseDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val controller = rememberColorPickerController()
    var selected by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.node_color)) },
        text = {
            Column {
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(PickerHeight),
                    controller = controller,
                    initialColor = initialColor,
                    onColorChanged = { selected = it.color },
                )
                HueSlider(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(HueSliderHeight),
                    controller = controller,
                )
                BrightnessSlider(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(HueSliderHeight),
                    controller = controller,
                    initialColor = initialColor,
                )
                TextButton(onClick = onUseDefault) { Text(stringResource(Res.string.node_color_use_default)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.opaqueArgb()) }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

private const val RGB_MASK = 0x00FFFFFF
private const val OPAQUE = 0xFF000000.toInt()
