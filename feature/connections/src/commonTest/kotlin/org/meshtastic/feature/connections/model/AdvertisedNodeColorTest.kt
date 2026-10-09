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
    fun `a saved color is the four hex digits of the bluetooth name`() {
        val red = 0xFFFF0000.toInt()
        val match = uniqueCustomColorForAdvertisedName("Meshtastic_1889", mapOf(0x1889 to red))
        assertEquals(AdvertisedNodeColor(num = 0x1889, color = red, suffix = "1889"), match)
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
    fun `the four digit key is the only key`() {
        val blue = 0xFF0000FF.toInt()
        val match = uniqueCustomColorForAdvertisedName("B_1889", mapOf(0x1889 to blue))
        assertEquals(blue, match?.color)
        assertNull(uniqueCustomColorForAdvertisedName("B_1889", mapOf(0xBA5B1889.toInt() to blue)))
    }
}
