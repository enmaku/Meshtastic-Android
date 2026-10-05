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
package org.meshtastic.core.prefs

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.NodeColorDataStore
import org.meshtastic.core.repository.NodeColorPrefs

@Single
class NodeColorPrefsImpl(private val dataStore: NodeColorDataStore, dispatchers: CoroutineDispatchers) :
    NodeColorPrefs {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    override val colors: StateFlow<Map<Int, Int?>> =
        dataStore.data
            .map { prefs -> decodeNodeColors(prefs[KEY].orEmpty()) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun setColor(num: Int, color: Int?) {
        dataStore.edit { prefs ->
            val current = decodeNodeColors(prefs[KEY].orEmpty()).toMutableMap()
            current[num] = color
            prefs[KEY] = encodeNodeColors(current)
        }
    }

    override suspend fun importIfAbsent(entries: Map<Int, Int>) {
        if (entries.isEmpty()) return
        dataStore.edit { prefs ->
            val current = decodeNodeColors(prefs[KEY].orEmpty()).toMutableMap()
            var changed = false
            for ((num, color) in entries) {
                if (num !in current) {
                    current[num] = color
                    changed = true
                }
            }
            if (changed) prefs[KEY] = encodeNodeColors(current)
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("node_custom_colors")
    }
}

internal fun encodeNodeColors(colors: Map<Int, Int?>): String = colors.entries.joinToString("\n") { (num, color) ->
    val payload = color?.toUInt()?.toString(16) ?: CLEARED
    "$num=$payload"
}

internal fun decodeNodeColors(raw: String): Map<Int, Int?> {
    if (raw.isEmpty()) return emptyMap()
    val colors = LinkedHashMap<Int, Int?>()
    for (line in raw.split('\n')) {
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val num = line.substring(0, eq).toIntOrNull() ?: continue
        val payload = line.substring(eq + 1)
        val color =
            if (payload == CLEARED) {
                null
            } else {
                payload.toUIntOrNull(16)?.toInt() ?: continue
            }
        colors[num] = color
    }
    return colors
}

private const val CLEARED = "-"
