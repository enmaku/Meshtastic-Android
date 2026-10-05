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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdvertisedNodeColorTest {
    @Test
    fun `a unique saved color matches the last four hex digits of the node number`() {
        val num = 0xBA5B1889.toInt()
        val red = 0xFFFF0000.toInt()
        val match = uniqueCustomColorForAdvertisedName("Meshtastic_1889", mapOf(num to red))
        assertEquals(AdvertisedNodeColor(num = num, color = red, suffix = "1889"), match)
    }

    @Test
    fun `no chip when nothing is saved or the name has no suffix`() {
        assertNull(uniqueCustomColorForAdvertisedName("Meshtastic_1889", emptyMap()))
        assertNull(uniqueCustomColorForAdvertisedName("Heltec T114", mapOf(1 to 0xFF00FF00.toInt())))
    }

    @Test
    fun `an explicit clear is not a saved color`() {
        val num = 0x1889
        assertNull(uniqueCustomColorForAdvertisedName("Meshtastic_1889", mapOf(num to null)))
    }

    @Test
    fun `two saved colors with the same suffix are not shown`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val colors = mapOf(0x11111889 to red, 0x22221889 to blue)
        assertNull(uniqueCustomColorForAdvertisedName("Meshtastic_1889", colors))
    }

    @Test
    fun `a negative node number still matches its low sixteen bits`() {
        val num = 0xFFFF1889.toInt()
        val black = 0xFF000000.toInt()
        val match = uniqueCustomColorForAdvertisedName("Meshtastic_1889", mapOf(num to black))
        assertEquals(num, match?.num)
        assertEquals(black, match?.color)
    }
}
