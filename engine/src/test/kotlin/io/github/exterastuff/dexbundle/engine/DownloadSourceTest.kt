package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.exception.InvalidPluginManifestException
import io.github.exterastuff.dexbundle.engine.impl.DownloadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.jar.Attributes

internal fun attributes(vararg values: Pair<String, String>) =
    Attributes().apply { values.forEach { (key, value) -> putValue(key, value) } }

class DownloadSourceTest {
    @Test
    fun validSourcesAndDisplayNames() {
        val url =
            DownloadSource.of(
                "test",
                attributes(
                    "Source-Type" to "url",
                    "Source-Url" to "https://example.org/p.jar",
                    "Source-Sha1" to "AB".repeat(20),
                ),
            )

        assertEquals(DownloadSource.Url("https://example.org/p.jar", "ab".repeat(20)), url)
        assertEquals("https://example.org/p.jar", url.toString())

        val telegram =
            DownloadSource.of(
                "test",
                attributes(
                    "Source-Type" to "telegram",
                    "Source-Channel" to "abc_1",
                    "Source-Post" to "2147483647",
                ),
            )

        assertEquals(DownloadSource.Telegram("abc_1", Int.MAX_VALUE, null), telegram)
        assertEquals("https://t.me/abc_1/2147483647", telegram.toString())

        val github =
            DownloadSource.of(
                "test",
                attributes(
                    "Source-Type" to "github",
                    "Source-Repository" to "owner/repo",
                    "Source-Tag" to " ",
                    "Source-Asset" to "",
                ),
            )

        assertEquals(DownloadSource.GitHub("owner/repo", null, null, null), github)
        assertEquals("github:owner/repo@latest", github.toString())
        assertEquals(
            "github:o/r@v1/a.jar",
            DownloadSource.GitHub("o/r", "v1", "a.jar", null).toString(),
        )
    }

    @Test
    fun rejectsInvalidOrMissingFieldsWithSectionContext() {
        val cases = mutableListOf(attributes(), attributes("Source-Type" to "ftp"))

        for (url in listOf("", " ", "ftp://a", "https://", "https://a b"))
            cases += attributes("Source-Type" to "url", "Source-Url" to url)

        for (hash in listOf("", "a".repeat(39), "a".repeat(41), "z".repeat(40))) {
            cases +=
                attributes(
                    "Source-Type" to "url",
                    "Source-Url" to "http://a",
                    "Source-Sha1" to hash,
                )
        }

        for (channel in listOf("abcd", "a".repeat(33), "@valid", "тесты")) {
            cases +=
                attributes(
                    "Source-Type" to "telegram",
                    "Source-Channel" to channel,
                    "Source-Post" to "1",
                )
        }

        for (post in listOf("0", "-1", "2147483648", "one", "")) {
            cases +=
                attributes(
                    "Source-Type" to "telegram",
                    "Source-Channel" to "valid",
                    "Source-Post" to post,
                )
        }

        for (repo in listOf("owner", "o/r/x", "/r", "o/", "o/r r"))
            cases += attributes("Source-Type" to "github", "Source-Repository" to repo)

        for (input in cases) {
            val error =
                assertThrows(InvalidPluginManifestException::class.java) {
                    DownloadSource.of("fixture", input)
                }

            assertTrue(error.message!!.contains("fixture"))
        }

        for (type in listOf("url", "telegram", "github")) {
            assertThrows(InvalidPluginManifestException::class.java) {
                DownloadSource.of("fixture", attributes("Source-Type" to type))
            }
        }
    }
}
