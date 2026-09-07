package io.github.exterastuff.dexbundle.engine.impl

import com.exteragram.messenger.plugins.Plugin
import io.github.exterastuff.dexbundle.engine.exception.InvalidPluginManifestException
import io.github.exterastuff.dexbundle.engine.util.Logger
import java.io.File
import java.io.FileNotFoundException
import java.util.jar.JarFile
import java.util.zip.ZipException

data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val author: String,
    val version: String,
    val minClientVersion: String,
    val entryClass: String,
) {
    companion object {
        private val ID_REGEX =
            Regex("""[^a-zA-Z0-9-_]""")

        private val CLASS_FQN_REGEX =
            Regex("""(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*""")

        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null",
        )

        private fun isValidPluginId(id: String): Boolean =
            !ID_REGEX.containsMatchIn(id)

        private fun isValidClassFqn(s: String): Boolean =
            CLASS_FQN_REGEX.matches(s) && s.split('.', '$').none { it in JAVA_KEYWORDS }

        fun parse(file: File): PluginManifest? = JarFile(file).use {
            val jarFile = try {
                JarFile(file)
            } catch (_: NoSuchFileException) {
                // file not found
                Logger.info("file not found")
                return null
            } catch (_: FileNotFoundException) {
                Logger.info("file not found")
                // file not found
                return null
            } catch (_: ZipException) {
                Logger.info("zip is broken")
                // broken or non-zip
                return null
            }

            // regular jar file
            val manifest = jarFile.manifest?.mainAttributes
                ?: run {
                    Logger.info("no manifest")
                    return null
                }

            // regular jar file
            val id = manifest.getValue("Plugin-Id")
                ?: run {
                    Logger.info("no id in manifest")
                    return null
                }

            if (!isValidPluginId(id))
                throw InvalidPluginManifestException("Plugin id should contain only ASCII-letters, digits, minus or underscore")

            fun getAttribute(name: String): String =
                manifest.getValue(name)
                    ?: throw InvalidPluginManifestException("Attribute $name not found")

            return PluginManifest(
                id = id,
                name = getAttribute("Plugin-Name"),
                description = getAttribute("Plugin-Description"),
                icon = getAttribute("Plugin-Icon"),
                author = getAttribute("Plugin-Author"),
                version = getAttribute("Plugin-Version"),
                minClientVersion = getAttribute("Plugin-Min-Client-Version"),

                entryClass = getAttribute("Plugin-Class")
                    .takeIf(::isValidClassFqn)
                    ?: throw InvalidPluginManifestException("Invalid main class FQN")
            )
        }
    }

    fun toPlugin(): Plugin =
        Plugin(id, name).apply {
            setDescription(description)
            setIcon(icon)
            setAuthor(author)
            setVersion(version)
            setAppVersion(minClientVersion)
            setEngine(DexBundlePluginsEngine.ID)
        }
}
