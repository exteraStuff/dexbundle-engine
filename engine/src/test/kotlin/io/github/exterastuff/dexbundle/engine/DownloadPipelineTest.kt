package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.download.DownloadPipeline
import io.github.exterastuff.dexbundle.engine.impl.DownloadSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class DownloadPipelineTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val source = DownloadSource.Url("https://host/plugin", null)

    private fun plugin(file: File, id: String = "test") {
        val manifest =
            Manifest().apply {
                mainAttributes.putAll(
                    attributes(
                        "Manifest-Version" to "1.0",
                        "Plugin-Id" to id,
                        "Plugin-Name" to "Test",
                        "Plugin-Description" to "",
                        "Plugin-Icon" to "",
                        "Plugin-Author" to "",
                        "Plugin-Version" to "1",
                        "Plugin-Min-Client-Version" to "1",
                        "Plugin-Class" to "a.Main",
                        "Plugin-Dependencies" to "",
                        "Plugin-Provided-Services" to "",
                        "Plugin-Required-Services" to "",
                    )
                )
            }

        JarOutputStream(file.outputStream(), manifest).use {
            it.putNextEntry(JarEntry("classes.dex"))
            it.write(ByteArray(20000).also { bytes -> java.util.Random(42).nextBytes(bytes) })
            it.closeEntry()
        }
    }

    @Test
    fun successfulDownloadVerifiesHashAndStopsAtFirstSuccess() {
        val original = temp.newFile()

        plugin(original)

        val hash = MessageDigest.getInstance("SHA-1")
            .digest(original.readBytes())
            .joinToString("") { "%02x".format(it) }

        val dir = File(temp.root, "downloads")
        var calls = 0

        val result = DownloadPipeline.download(
            pluginId = "test",
            sources = listOf(source.copy(sha1 = hash), source),
            directory = dir,
        ) { _, target ->
            calls++
            original.copyTo(target, overwrite = true)
        }

        assertEquals(1, calls)
        assertEquals("test", result.manifest.id)
        assertArrayEquals(original.readBytes(), result.file.readBytes())
        assertEquals(listOf(result.file), dir.listFiles()!!.toList())
    }

    @Test
    fun failedSourcesAreDeletedAndErrorsAreRetained() {
        val dir = temp.newFolder()

        val failures =
            listOf(
                source.copy(url = "network"),
                source.copy(url = "wrong-id"),
                source.copy(url = "corrupt"),
                source.copy(sha1 = "0".repeat(40)),
            )

        val error = assertThrows(IOException::class.java) {
            DownloadPipeline.download("test", failures, dir) { src, target ->
                when ((src as DownloadSource.Url).url) {
                    "network" -> {
                        target.writeText("partial")
                        throw IOException("offline")
                    }

                    "wrong-id" -> plugin(target, "other")
                    "corrupt" -> target.writeText("not a jar")
                    else -> plugin(target)
                }
            }
        }

        assertEquals(4, error.suppressed.size)
        assertTrue(error.message!!.contains("offline"))
        assertTrue(error.suppressed[1].message!!.contains("other"))
        assertTrue(error.suppressed[3].message!!.contains("sha1"))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun fallbackAndEarlyValidation() {
        val dir = temp.newFolder()
        var calls = 0

        val download = DownloadPipeline.download("test", listOf(source, source), dir) { _, target ->
            calls++

            if (calls == 1)
                JarOutputStream(target.outputStream()).close()
            else
                plugin(target)
        }

        assertEquals(2, calls)
        assertTrue(download.file.exists())
        assertEquals(1, dir.listFiles()!!.size)

        assertThrows(IOException::class.java) {
            DownloadPipeline.download("test", emptyList(), dir) { _, _ -> fail() }
        }

        assertThrows(IOException::class.java) {
            DownloadPipeline.download("test", listOf(source), temp.newFile()) { _, _ -> fail() }
        }
    }
}
