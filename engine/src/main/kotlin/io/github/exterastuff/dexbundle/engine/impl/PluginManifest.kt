package io.github.exterastuff.dexbundle.engine.impl

import com.exteragram.messenger.plugins.Plugin
import io.github.exterastuff.dexbundle.engine.exception.InvalidPluginManifestException
import io.github.exterastuff.dexbundle.engine.util.Logger
import java.io.File
import java.util.TreeMap
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.zip.ZipFile

data class PluginManifest(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val author: String,
    val version: String,
    val minClientVersion: String,
    val entryClass: String,

    // id -> версия
    val dependencies: Map<String, String>,

    // id -> источники, по порядку
    val dependencySources: Map<String, List<DownloadSource>>,

    // источники более новых сборок этого плагина, по порядку
    val updateSources: List<DownloadSource>,

    // group:name -> версия
    val providedServices: Map<String, String>,

    // group:name -> версия
    val requiredServices: Map<String, String>,

    // подпись
    val signers: LinkedHashMap<String, SignerInfo>?,
) {
    companion object {
        private const val SERVICES_DIRECTORY = "services"

        /** Путь, по которому определение сервиса лежит внутри jar'а плагина. */
        fun serviceEntry(coordinates: String, version: String): String =
            "$SERVICES_DIRECTORY/${coordinates.replace(':', '.')}-$version.jar"

        private val ID_REGEX = Regex("""[^a-zA-Z0-9-_]""")

        private val UPDATE_SOURCE_SECTION = Regex("""update-source/(\d+)""")
        private val DEPENDENCY_SECTION = Regex("""dependency/([^/]+)""")
        private val DEPENDENCY_SOURCE_SECTION = Regex("""dependency/([^/]+)/source/(\d+)""")

        private val CLASS_FQN_REGEX =
            Regex("""(?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*""")

        private val JAVA_KEYWORDS =
            setOf(
                "abstract",
                "assert",
                "boolean",
                "break",
                "byte",
                "case",
                "catch",
                "char",
                "class",
                "const",
                "continue",
                "default",
                "do",
                "double",
                "else",
                "enum",
                "extends",
                "final",
                "finally",
                "float",
                "for",
                "goto",
                "if",
                "implements",
                "import",
                "instanceof",
                "int",
                "interface",
                "long",
                "native",
                "new",
                "package",
                "private",
                "protected",
                "public",
                "return",
                "short",
                "static",
                "strictfp",
                "super",
                "switch",
                "synchronized",
                "this",
                "throw",
                "throws",
                "transient",
                "try",
                "void",
                "volatile",
                "while",
                "true",
                "false",
                "null",
            )

        /** Символы, из которых может состоять часть координат в стиле maven. */
        private val ARTIFACT_PART_REGEX = Regex("""[A-Za-z0-9._-]+""")

        private fun isValidPluginId(id: String): Boolean =
            id.isNotEmpty() && !ID_REGEX.containsMatchIn(id)

        /**
         * Проверяет часть координат сервиса. Из координат и версии складывается имя файла, в
         * который определение распаковывается, поэтому в них не может быть разделителей пути.
         */
        private fun isValidArtifactPart(part: String): Boolean = ARTIFACT_PART_REGEX.matches(part)

        private fun isValidClassFqn(s: String): Boolean =
            CLASS_FQN_REGEX.matches(s) && s.split('.', '$').none { it in JAVA_KEYWORDS }

        /**
         * Разбирает секции источников и зависимостей.
         *
         * @param sections секции манифеста по имени
         * @param dependencies зависимости из `Plugin-Dependencies`
         * @return источники обновлений и источники зависимостей по id
         */
        private fun parseSourceSections(
            sections: Map<String, Attributes>,
            dependencies: Map<String, String>,
        ): Pair<List<DownloadSource>, Map<String, List<DownloadSource>>> {
            val updateSources = TreeMap<Int, DownloadSource>()
            val dependencySources = HashMap<String, TreeMap<Int, DownloadSource>>()

            for ((name, attributes) in sections) {
                UPDATE_SOURCE_SECTION.matchEntire(name)
                    ?.let { match ->
                        val source = DownloadSource.of(name, attributes)

                        if (source.sha1 != null)
                            throw InvalidPluginManifestException("Section $name must not have sha1: an update source serves new releases")

                        updateSources[match.groupValues[1].toInt()] = source

                        continue
                    }

                DEPENDENCY_SECTION.matchEntire(name)
                    ?.let { match ->
                        val id = match.groupValues[1]

                        if (attributes.getValue("Dependency-Id") != id)
                            throw InvalidPluginManifestException("Section $name has Dependency-Id that does not match its name")

                        val version = dependencies[id]

                        if (version == null || attributes.getValue("Dependency-Min-Version") != version)
                            throw InvalidPluginManifestException("Section $name does not match Plugin-Dependencies")

                        continue
                    }

                DEPENDENCY_SOURCE_SECTION.matchEntire(name)
                    ?.let { match ->
                        val id = match.groupValues[1]

                        if (id !in dependencies)
                            throw InvalidPluginManifestException("Section $name describes plugin '$id' missing in Plugin-Dependencies")

                        val source = DownloadSource.of(name, attributes)

                        if (source.sha1 == null)
                            throw InvalidPluginManifestException("Section $name has no sha1")

                        if (source is DownloadSource.GitHub && source.tag == null)
                            throw InvalidPluginManifestException("Section $name has no tag")

                        val index = match.groupValues[2].toInt()

                        dependencySources.getOrPut(id, ::TreeMap)[index] = source
                    }
            }

            val sources = updateSources
                .values
                .toList()

            val depSources = dependencySources
                .mapValues { (_, sources) -> sources.values.toList() }

            return sources to depSources
        }

        /**
         * Парсит манифест плагина из jar файла.
         *
         * @param file jar файл плагина
         * @return манифест
         * @throws SecurityException при сломанной или частичной подписи
         * @throws InvalidPluginManifestException некорректный формат манифе
         */
        @Throws(SecurityException::class)
        fun of(file: File): PluginManifest? =
            JarFile(file, true).use { jarFile ->
                // это просто jar, а не плагин
                val jarManifest =
                    jarFile.manifest
                        ?: run {
                            Logger.info("no manifest")
                            return null
                        }

                val manifest = jarManifest.mainAttributes

                // это просто jar, а не плагин
                val id =
                    manifest.getValue("Plugin-Id")
                        ?: run {
                            Logger.info("no id in manifest")
                            return null
                        }

                if (!isValidPluginId(id))
                    throw InvalidPluginManifestException(
                        "Plugin id should contain only ASCII-letters, digits, minus or underscore"
                    )

                fun getAttribute(name: String): String =
                    manifest.getValue(name)
                        ?: throw InvalidPluginManifestException("Attribute $name not found")

                fun getAttributeList(name: String): List<String> =
                    getAttribute(name).trim().let {
                        if (it.isEmpty()) emptyList() else it.split(", ")
                    }

                val dependencies =
                    getAttributeList("Plugin-Dependencies")
                        .map {
                            val parts = it.split(":")

                            if (parts.size != 2)
                                throw InvalidPluginManifestException(
                                    "Invalid Plugin-Dependencies attribute value"
                                )

                            if (!isValidPluginId(parts[0]))
                                throw InvalidPluginManifestException(
                                    "Invalid plugin id '${parts[0]}' in Plugin-Dependencies"
                                )

                            if (parts[0] == id)
                                throw InvalidPluginManifestException(
                                    "Plugin cannot depend on itself"
                                )

                            if (!isValidArtifactPart(parts[1]))
                                throw InvalidPluginManifestException(
                                    "Invalid version '${parts[1]}' in Plugin-Dependencies"
                                )

                            return@map parts[0] to parts[1]
                        }
                        .toMap()

                fun parseArtifactMap(attributeName: String): Map<String, String> =
                    getAttributeList(attributeName)
                        .map {
                            val parts = it.split(":")

                            if (parts.size != 3)
                                throw InvalidPluginManifestException(
                                    "Invalid $attributeName attribute value"
                                )

                            if (parts.any { part -> !isValidArtifactPart(part) })
                                throw InvalidPluginManifestException(
                                    "Invalid coordinates '$it' in $attributeName"
                                )

                            return@map "${parts[0]}:${parts[1]}" to parts[2]
                        }
                        .toMap()

                val (updateSources, dependencySources) =
                    parseSourceSections(jarManifest.entries, dependencies)

                val providedServices = parseArtifactMap("Plugin-Provided-Services")
                val requiredServices = parseArtifactMap("Plugin-Required-Services")

                return PluginManifest(
                    id = id,
                    name = getAttribute("Plugin-Name"),
                    description = getAttribute("Plugin-Description"),
                    icon = getAttribute("Plugin-Icon"),
                    author = getAttribute("Plugin-Author"),
                    version = getAttribute("Plugin-Version"),
                    minClientVersion = getAttribute("Plugin-Min-Client-Version"),
                    entryClass =
                        getAttribute("Plugin-Class").takeIf(::isValidClassFqn)
                            ?: throw InvalidPluginManifestException("Invalid main class FQN"),
                    dependencies = dependencies,
                    dependencySources = dependencySources,
                    updateSources = updateSources,
                    providedServices = providedServices,
                    requiredServices = requiredServices,
                    signers = SignerInfo.of(jarFile),
                )
            }
    }

    /**
     * Конвертирует манифест в описание плагина exteraGram.
     *
     * @return описание плагина
     */
    fun toPlugin(): Plugin =
        Plugin(id, name).apply {
            setDescription(description)
            setIcon(icon)
            setAuthor(author)
            setVersion(version)
            setAppVersion(minClientVersion)
            setEngine(DexBundlePluginsEngine.ID)
        }

    /**
     * Проверяет, существуют ли перечисленные в манифесте плагина определения сервисов.
     *
     * @param file jar файл плагина
     * @return список несуществующих определений
     */
    fun getFakeServices(file: File): Set<String> {
        val declaredServices =
            providedServices.map { serviceEntry(it.key, it.value) }.toMutableSet()

        ZipFile(file).use { zipFile ->
            zipFile.stream().filter { !it.isDirectory }.forEach { declaredServices.remove(it.name) }
        }

        return declaredServices
    }
}
