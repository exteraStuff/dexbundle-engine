package io.github.exterastuff.dexbundle.engine.impl

import dalvik.system.DexClassLoader
import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.util.Logger
import io.github.exterastuff.dexbundle.engine.util.compareVersions
import java.io.File
import java.io.IOException
import java.util.jar.JarFile

/**
 * Список предоставленных плагинами определений сервисов, которые будут предоставлены каждому
 * плагину через отдельный родительский class-loader.
 *
 * @property definitions список определений в виде `com.example:api` -> `1.0.0`.
 * @property classLoader class-loader
 */
class PluginServices
private constructor(
    private val definitions: Map<String, String>,
    val classLoader: ClassLoader,
) {
    /** Зарегистрированные реализации по имени класса, объявляющего сервис. */
    private val implementations = LinkedHashMap<String, Implementation>()

    /** Реализация сервиса и плагин, который её зарегистрировал. */
    private data class Implementation(val pluginId: String, val instance: Any)

    private data class Definition(
        val coordinates: String,
        val version: String,

        /** Плагин, из jar'а которого берётся определение. */
        val pluginId: String,
    ) {
        val fileName: String
            get() = fileNameOf(coordinates, version)
    }

    companion object {
        private const val CACHE_DIRECTORY = "dexbundle-services"

        /** Папка, в которую распаковываются определения. */
        private fun cacheDir(): File =
            File(DexBundlePluginsEngine.codeCacheDir, CACHE_DIRECTORY).apply { mkdirs() }

        private fun fileNameOf(coordinates: String, version: String): String =
            "${coordinates.replace(':', '.')}-$version.jar"

        /**
         * Удаляет кешированные определения сервисов списка предоставленных плагинов.
         *
         * Требуется при обновлении плагина для обновления предоставленных им определений.
         *
         * Удалённые определения читают уже загруженные class-loader'ы, поэтому вызов обязан быть
         * частью пересборки родительского class-loader'а.
         *
         * @param manifests список обновляемых плагинов
         */
        fun invalidate(manifests: Collection<PluginManifest>) {
            for (manifest in manifests) for ((coordinates, version) in manifest.providedServices) {
                val file = File(cacheDir(), fileNameOf(coordinates, version))

                if (!file.exists()) continue

                file.setWritable(true)

                if (!file.delete())
                    Logger.warn("Failed to drop extracted service $coordinates:$version")
            }
        }

        /**
         * Конвертирует список плагинов в таблицу определений сервисов. При наличии определения в
         * двух разных плагинах, выбирается наиболее свежая версия.
         *
         * @param manifests список загружаемых плагинов
         * @return таблица определений
         */
        private fun resolve(manifests: Collection<PluginManifest>): Map<String, Definition> {
            val resolved = LinkedHashMap<String, Definition>()

            for (manifest in manifests) for ((coordinates, version) in manifest.providedServices) {
                val candidate = Definition(coordinates, version, manifest.id)
                val current = resolved[coordinates]

                if (current == null || current.version == version) {
                    resolved[coordinates] = candidate
                    continue
                }

                // а проигравший идёт нахуй :)
                val winner =
                    if (compareVersions(version, current.version) > 0) candidate else current

                Logger.warn(
                    "Service $coordinates is provided by '${current.pluginId}' as ${current.version} " +
                        "and by '${manifest.id}' as $version, taking ${winner.version}"
                )

                resolved[coordinates] = winner
            }

            return resolved
        }

        /**
         * Кеширует определение сервиса из плагина в отдельную папку для быстрой загрузки.
         *
         * @param definition описание местоположения определения
         * @param target требуемое местоположение на диске
         * @throws IOException
         * @throws SecurityException
         */
        @Throws(IOException::class, SecurityException::class)
        private fun extract(definition: Definition, target: File) {
            // координаты и версия однозначно описывают содержимое
            if (target.exists()) return

            val entryName = PluginManifest.serviceEntry(definition.coordinates, definition.version)
            val temp = File(target.parentFile, "${target.name}.tmp")

            try {
                JarFile(DexBundlePluginsEngine.pluginFile(definition.pluginId), true).use { jar ->
                    val entry =
                        jar.getJarEntry(entryName)
                            ?: throw IOException(
                                "Plugin '${definition.pluginId}' does not ship $entryName"
                            )

                    jar.getInputStream(entry).use { input ->
                        temp.outputStream().use(input::copyTo)
                    }
                }

                if (!temp.renameTo(target))
                    throw IOException("Failed to move ${temp.name} to ${target.name}")
            } finally {
                temp.delete()
            }

            target.setWritable(false)
        }

        /**
         * Удаляет более не используемые определения сервисов.
         *
         * @param keep список ещё используемых определений
         */
        private fun cleanUp(keep: Collection<File>) {
            val names = keep.mapTo(HashSet(), File::getName)

            // рядом с распакованными определениями рантайм держит свою папку oat
            cacheDir()
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name !in names }
                .forEach {
                    it.setWritable(true)

                    if (!it.delete()) Logger.warn("Failed to delete stale service ${it.name}")
                }
        }

        /**
         * Создаёт родительский class-loader для плагинов, в котором будут находиться
         * предоставленные ими же определения сервисов.
         *
         * Определения, которые больше никто не предоставляет, удаляются с диска, поэтому к моменту
         * вызова ни один плагин не должен быть загружен.
         *
         * @param manifests список загружаемых плагинов
         * @param parent class-loader который будет выступать в качестве родительского при
         *   отсутствии определений, или родителем созданного class-loader'а
         * @return список определений и родительский class-loader
         */
        fun build(
            manifests: Collection<PluginManifest>,
            parent: ClassLoader,
        ): PluginServices {
            val definitions = resolve(manifests)
            val files = LinkedHashMap<String, File>()

            for ((coordinates, definition) in definitions) {
                val file = File(cacheDir(), definition.fileName)

                try {
                    extract(definition, file)
                    files[coordinates] = file
                } catch (e: Throwable) {
                    Logger.warn(
                        "Failed to extract service $coordinates:${definition.version}: ${e.format()}"
                    )
                }
            }

            cleanUp(files.values)

            val services =
                files.keys
                    .joinToString { "$it:${definitions.getValue(it).version}" }
                    .ifEmpty { "none" }

            Logger.info("Services: $services")

            val classLoader =
                if (files.isEmpty()) parent
                else
                    DexClassLoader(
                        files.values.joinToString(
                            File.pathSeparator,
                            transform = File::getAbsolutePath,
                        ),
                        DexBundlePluginsEngine.codeCacheDir.absolutePath,
                        null,
                        parent,
                    )

            return PluginServices(
                files.keys.associateWith { definitions.getValue(it).version },
                classLoader,
            )
        }
    }

    /** Загружено ли определение версии не ниже [version]. */
    fun provides(coordinates: String, version: String): Boolean =
        definitions[coordinates]?.let { compareVersions(it, version) >= 0 } ?: false

    /**
     * Регистрирует реализацию сервиса от имени плагина [pluginId]. Реализация живёт ровно столько,
     * сколько сам плагин: при его выгрузке она снимается через [unregisterServices].
     */
    @Synchronized
    fun <T : Any, C : T> registerService(pluginId: String, baseClass: Class<T>, implementation: C) {
        val serviceName = baseClass.name

        if (!baseClass.isAssignableFrom(implementation.javaClass))
            throw ClassCastException("Provided implementation isn't implements $serviceName")

        implementations[serviceName]?.let {
            throw IllegalStateException(
                "Implementation of $serviceName service is already registered by '${it.pluginId}'"
            )
        }

        implementations[serviceName] = Implementation(pluginId, implementation)
    }

    @Synchronized
    fun <T : Any> getService(klass: Class<T>): T? {
        val implementation = implementations[klass.name] ?: return null

        // одно и то же определение из разных class-loader'ов совпадает по имени, но не по типу;
        // сюда же попадает плагин, принёсший свою копию определения
        if (!klass.isInstance(implementation.instance))
            throw ClassCastException(
                "Service ${klass.name} is registered by '${implementation.pluginId}' as " +
                    "${implementation.instance.javaClass.name} of an incompatible class-loader"
            )

        return klass.cast(implementation.instance)
    }

    /** Снимает все реализации сервисов, зарегистрированные плагином [pluginId]. */
    @Synchronized
    fun unregisterServices(pluginId: String) {
        val iterator = implementations.entries.iterator()

        while (iterator.hasNext()) {
            val (serviceName, implementation) = iterator.next()

            if (implementation.pluginId != pluginId) continue

            iterator.remove()

            Logger.info("Unregistered service $serviceName of '$pluginId'")
        }
    }
}
