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

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.prefs.di.asNodeColorDataStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class NodeColorPrefsImplTest {
    private lateinit var tmpDir: Path
    private lateinit var prefs: NodeColorPrefsImpl
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @BeforeTest
    fun setup() {
        tmpDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "nodeColorPrefsTest-${Uuid.random()}"
        FileSystem.SYSTEM.createDirectories(tmpDir)
        val dataStore =
            PreferenceDataStoreFactory.createWithPath(
                scope = testScope,
                produceFile = { tmpDir / "test.preferences_pb" },
            )
        val dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
        prefs = NodeColorPrefsImpl(dataStore.asNodeColorDataStore(), dispatchers)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(tmpDir)
    }

    @Test
    fun `setColor persists a color and an explicit clear`() = testScope.runTest {
        val black = 0xFF000000.toInt()
        prefs.setColor(7, black)
        assertEquals(black, prefs.colors.value[7])

        prefs.setColor(7, null)
        assertTrue(7 in prefs.colors.value)
        assertNull(prefs.colors.value[7])
    }

    @Test
    fun `importIfAbsent does not replace a color or a clear`() = testScope.runTest {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        prefs.setColor(1, red)
        prefs.setColor(2, null)

        prefs.importIfAbsent(mapOf(1 to blue, 2 to blue, 3 to blue))

        assertEquals(red, prefs.colors.value[1])
        assertNull(prefs.colors.value[2])
        assertEquals(blue, prefs.colors.value[3])
        assertFalse(4 in prefs.colors.value)
    }

    @Test
    fun `negative node numbers round trip`() = testScope.runTest {
        val num = -12345
        val yellow = 0xFFFFFF00.toInt()
        prefs.setColor(num, yellow)
        assertEquals(yellow, prefs.colors.value[num])
    }
}
