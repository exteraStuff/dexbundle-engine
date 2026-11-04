package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.exception.InvalidPluginManifestException
import io.github.exterastuff.dexbundle.engine.impl.PluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class ManifestTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun manifest() =
        Manifest().apply {
            mainAttributes.putAll(
                attributes(
                    "Manifest-Version" to "1.0",
                    "Plugin-Id" to "test-plugin",
                    "Plugin-Name" to "Тест",
                    "Plugin-Description" to "Description",
                    "Plugin-Icon" to "icon",
                    "Plugin-Author" to "author",
                    "Plugin-Version" to "1.2",
                    "Plugin-Min-Client-Version" to "12",
                    "Plugin-Class" to "org.example.Main",
                    "Plugin-Dependencies" to "",
                    "Plugin-Provided-Services" to "",
                    "Plugin-Required-Services" to "",
                )
            )
        }

    private fun jar(manifest: Manifest?, vararg entries: String): File =
        temp.newFile().also { file ->
            val output =
                if (manifest == null)
                    JarOutputStream(file.outputStream())
                else
                    JarOutputStream(file.outputStream(), manifest)

            output.use { jar ->
                entries.forEach {
                    jar.putNextEntry(JarEntry(it))
                    jar.write(byteArrayOf(1, 2))
                    jar.closeEntry()
                }
            }
        }

    @Test
    fun ordinaryJarsAreNotPlugins() {
        assertNull(PluginManifest.of(jar(null, "data")))

        assertNull(
            PluginManifest.of(
                jar(
                    Manifest()
                        .apply { mainAttributes.putValue("Manifest-Version", "1.0") })
            )
        )
    }

    @Test
    fun parsesMetadataDependenciesServicesAndUnsignedPayload() {
        val input = manifest()
            .apply {
                mainAttributes.putValue("Plugin-Dependencies", "first:1.0, second:2")
                mainAttributes.putValue(
                    "Plugin-Provided-Services",
                    "org.example:api:1.0, org.example:other:2",
                )
                mainAttributes.putValue("Plugin-Required-Services", "org.example:base:3")
            }

        val file = jar(input, "classes.dex", "services/org.example.api-1.0.jar")
        val parsed = PluginManifest.of(file)!!

        assertEquals("test-plugin", parsed.id)
        assertEquals("Тест", parsed.name)
        assertEquals("Description", parsed.description)
        assertEquals("icon", parsed.icon)
        assertEquals("author", parsed.author)
        assertEquals("1.2", parsed.version)
        assertEquals("12", parsed.minClientVersion)
        assertEquals("org.example.Main", parsed.entryClass)
        assertEquals(mapOf("first" to "1.0", "second" to "2"), parsed.dependencies)
        assertEquals(mapOf("org.example:base" to "3"), parsed.requiredServices)
        assertNull(parsed.signers)
        assertEquals(setOf("services/org.example.other-2.jar"), parsed.missingServiceEntries(file))
    }

    @Test
    fun allRequiredAttributesAreRequired() {
        val attributes = manifest()
            .mainAttributes
            .keys
            .map { it.toString() }
            .filter { it.startsWith("Plugin-") && it != "Plugin-Id" }

        for (key in attributes) {
            val input = manifest()
                .apply { mainAttributes.remove(Attributes.Name(key)) }

            val error =
                assertThrows(InvalidPluginManifestException::class.java) {
                    PluginManifest.of(jar(input))
                }

            assertTrue("$key: ${error.message}", error.message!!.contains(key))
        }
    }

    @Test
    fun invalidIdentifiersClassesDependenciesAndCoordinates() {
        val cases =
            mapOf(
                "Plugin-Id" to listOf("", "../evil", "with space", "тест"),
                "Plugin-Class" to
                        listOf("Main", "a..Main", "1a.Main", "a.class", $$"a.Main$null", "a/Main"),
                "Plugin-Dependencies" to
                        listOf("bad", "a:b:c", "test-plugin:1", "a:", "../bad:1", "a:../1"),
                "Plugin-Provided-Services" to
                        listOf("a:b", "a:b:c:d", "a::1", "../a:b:1", "a:b:1/2"),
                "Plugin-Required-Services" to listOf("a:b", "a:b:", "a:b:../1"),
            )

        for ((key, values) in cases) {
            for (value in values) {
                assertThrows("$key=$value", InvalidPluginManifestException::class.java) {
                    PluginManifest.of(jar(manifest().apply { mainAttributes.putValue(key, value) }))
                }
            }
        }
    }

    @Test
    fun sourceOrderingIsNumericAndDependencyMetadataMatches() {
        val input = manifest()
            .apply {
                mainAttributes.putValue("Plugin-Dependencies", "dep:1")
                entries["dependency/dep"] =
                    attributes("Dependency-Id" to "dep", "Dependency-Min-Version" to "1")

                for (i in listOf(10, 2, 1)) {
                    entries["update-source/$i"] =
                        attributes("Source-Type" to "url", "Source-Url" to "https://host/$i")

                    entries["dependency/dep/source/$i"] =
                        attributes(
                            "Source-Type" to "url",
                            "Source-Url" to "https://host/$i",
                            "Source-Sha1" to "a".repeat(40),
                        )
                }
                entries["unrelated"] = attributes("Other" to "value")
            }

        val parsed = PluginManifest.of(jar(input))!!
        val urls = listOf("https://host/1", "https://host/2", "https://host/10")

        assertEquals(urls, parsed.updateSources.map { it.toString() })
        assertEquals(urls, parsed.dependencySources.getValue("dep").map { it.toString() })
    }

    @Test
    fun rejectsInconsistentSourceSections() {
        val url = attributes("Source-Type" to "url", "Source-Url" to "https://host/file")

        val cases =
            listOf(
                "update-source/1" to
                        Attributes(url).apply { putValue("Source-Sha1", "a".repeat(40)) },
                "dependency/dep/source/1" to url,
                "dependency/unknown/source/1" to url,
                "dependency/dep" to
                        attributes("Dependency-Id" to "wrong", "Dependency-Min-Version" to "1"),
                "dependency/dep" to
                        attributes("Dependency-Id" to "dep", "Dependency-Min-Version" to "2"),
                "dependency/dep/source/1" to
                        attributes(
                            "Source-Type" to "github",
                            "Source-Repository" to "o/r",
                            "Source-Sha1" to "a".repeat(40),
                        ),
            )

        for ((section, attrs) in cases) {
            assertThrows(InvalidPluginManifestException::class.java) {
                PluginManifest.of(
                    jar(
                        manifest().apply {
                            mainAttributes.putValue("Plugin-Dependencies", "dep:1")
                            entries[section] = attrs
                        }
                    )
                )
            }
        }
    }

    @Test
    fun fakeSignatureBlocksDoNotMakeUnsignedPayloadAcceptable() {
        val paths = listOf(
            "META-INF/TEST.SF",
            "META-INF/TEST.RSA",
            "META-INF/TEST.EC",
            "META-INF/TEST.DSA",
        )

        for (signature in paths) {
            assertThrows(SecurityException::class.java) {
                PluginManifest.of(jar(manifest(), signature, "classes.dex"))
            }
        }

        assertNull(
            PluginManifest.of(jar(manifest(), "META-INF/nested/TEST.SF", "classes.dex"))!!.signers
        )
    }
}
