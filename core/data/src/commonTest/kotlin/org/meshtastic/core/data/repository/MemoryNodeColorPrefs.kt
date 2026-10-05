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
package org.meshtastic.core.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.meshtastic.core.repository.NodeColorPrefs

class MemoryNodeColorPrefs : NodeColorPrefs {
    private val stored = MutableStateFlow<Map<Int, Int?>>(emptyMap())

    override val colors: StateFlow<Map<Int, Int?>> = stored

    override suspend fun setColor(num: Int, color: Int?) {
        stored.value = stored.value + (num to color)
    }

    override suspend fun importIfAbsent(entries: Map<Int, Int>) {
        val additions = entries.filterKeys { it !in stored.value }
        if (additions.isNotEmpty()) stored.value = stored.value + additions
    }
}
