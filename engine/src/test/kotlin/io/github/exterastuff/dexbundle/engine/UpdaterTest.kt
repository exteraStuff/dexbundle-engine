package io.github.exterastuff.dexbundle.engine

import android.content.SharedPreferences
import io.github.exterastuff.dexbundle.engine.download.PluginDownloader
import io.github.exterastuff.dexbundle.engine.download.PluginUpdater
import io.github.exterastuff.dexbundle.engine.impl.DownloadSource
import io.github.exterastuff.dexbundle.engine.impl.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.IOException

class UpdaterTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val interval = 6 * 60 * 60 * 1000L
    private val key = "dexengine_last_update_check"

    private fun manifest(id: String = "test", version: String = "1") =
        PluginManifest(
            id = id,
            name = id,
            description = "",
            icon = "",
            author = "",
            version = version,
            minClientVersion = "1",
            entryClass = "a.Main",
            dependencies = emptyMap(),
            dependencySources = emptyMap(),
            updateSources = listOf(DownloadSource.Url("https://host/file", null)),
            providedServices = emptyMap(),
            requiredServices = emptyMap(),
            signers = null,
        )

    private fun preferences(last: Long = 0): SharedPreferences {
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)

        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getLong(key, 0)).thenReturn(last)

        return prefs
    }

    @Test
    fun throttleBoundaryAndClockRollback() {
        for ((now, expected) in listOf(100L to 0, interval to 1, -1L to 1)) {
            val prefs = preferences()
            var calls = 0

            PluginUpdater.check(
                preferences = prefs,
                installed = listOf(manifest()),
                onUpdate = { fail("no update expected") },
                now = now,
            ) { _, _ ->
                calls++
                throw IOException("offline")
            }

            assertEquals(expected, calls)

            if (expected == 0)
                verify(prefs, never()).edit()
            else
                verify(prefs.edit()).putLong(key, now)
        }
    }

    @Test
    fun onlyNewUnskippedVersionsAreOfferedAndOthersDeleted() {
        val variants = listOf(
            Triple("2", null, true),
            Triple("1", null, false),
            Triple("0", null, false),
            Triple("2", "2", false),
        )

        for ((version, skipped, offered) in variants) {
            val prefs = preferences()

            `when`(prefs.getString("dexengine_skipped_update_test", null))
                .thenReturn(skipped)

            val file = temp.newFile()
            val download = PluginDownloader.Download(file, manifest(version = version))
            val updates = mutableListOf<PluginDownloader.Download>()

            PluginUpdater.check(prefs, listOf(manifest()), updates::add, interval) { id, sources ->
                assertEquals("test", id)
                assertEquals(manifest().updateSources, sources)
                download
            }

            assertEquals(
                if (offered) listOf(download) else emptyList<PluginDownloader.Download>(),
                updates,
            )

            assertEquals(offered, file.exists())
        }
    }

    @Test
    fun failedPluginDoesNotPreventNextCheckAndEmptySourcesAreSkipped() {
        val seen = mutableListOf<String>()
        val updates = mutableListOf<PluginDownloader.Download>()
        val download = PluginDownloader.Download(temp.newFile(), manifest("good", "2"))

        PluginUpdater.check(
            preferences(),
            listOf(
                manifest("none").copy(updateSources = emptyList()),
                manifest("bad"),
                manifest("good"),
            ),
            updates::add,
            interval,
        ) { id, _ ->
            seen += id

            if (id == "bad")
                throw IOException("offline")

            download
        }

        assertEquals(listOf("bad", "good"), seen)
        assertEquals(listOf(download), updates)
    }

    @Test
    fun skippedVersionIsPersistedPerPlugin() {
        val prefs = preferences()
        PluginUpdater.skipVersion(prefs, "test", "2")

        verify(prefs.edit()).putString("dexengine_skipped_update_test", "2")
        verify(prefs.edit()).apply()
    }
}
