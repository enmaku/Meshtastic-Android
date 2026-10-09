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
package org.meshtastic.core.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Chip colors for nodes, stored once for the whole app.
 *
 * Keys are [org.meshtastic.core.model.nodeColorKey] values, the low 16 bits of the node number. A missing key means
 * "not chosen here yet" and may be filled from a radio database. A present null means the generated color should be
 * used.
 */
interface NodeColorPrefs {
    val colors: StateFlow<Map<Int, Int?>>

    suspend fun setColor(num: Int, color: Int?)

    /** Copies radio-database colors that this phone has not already decided. */
    suspend fun importIfAbsent(entries: Map<Int, Int>)
}
