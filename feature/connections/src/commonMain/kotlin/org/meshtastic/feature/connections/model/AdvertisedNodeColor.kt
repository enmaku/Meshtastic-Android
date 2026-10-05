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
package org.meshtastic.feature.connections.model

import org.meshtastic.core.ble.MeshtasticBleConstants.BLE_NAME_PATTERN

/** A saved chip color whose node number ends with the same four hex digits as a Bluetooth name. */
internal data class AdvertisedNodeColor(val num: Int, val color: Int, val suffix: String)

private val advertisedName = Regex(BLE_NAME_PATTERN)
private const val SUFFIX_BITS = 0xFFFFu

/**
 * The saved color for [name], when exactly one non-null entry in [colors] shares its four-digit suffix.
 *
 * Two colored nodes can share those digits. Showing neither is safer than guessing.
 */
internal fun uniqueCustomColorForAdvertisedName(name: String, colors: Map<Int, Int?>): AdvertisedNodeColor? {
    val suffix = advertisedName.find(name)?.groupValues?.get(1) ?: return null
    val suffixValue = suffix.toInt(16)
    return colors
        .mapNotNull { (num, color) ->
            if (color == null || (num.toUInt() and SUFFIX_BITS).toInt() != suffixValue) {
                null
            } else {
                AdvertisedNodeColor(num = num, color = color, suffix = suffix.uppercase())
            }
        }
        .singleOrNull()
}
