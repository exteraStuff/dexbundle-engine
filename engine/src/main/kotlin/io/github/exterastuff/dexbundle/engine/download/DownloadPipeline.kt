package io.github.exterastuff.dexbundle.engine.download

import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.impl.DownloadSource
import io.github.exterastuff.dexbundle.engine.impl.PluginManifest
import io.github.exterastuff.dexbundle.engine.util.Logger
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Проверка файлов и перебор источников; функцию загрузки предоставляет адаптер хоста. */
internal object DownloadPipeline {
    fun download(
        pluginId: String,
        sources: List<DownloadSource>,
        directory: File,
        fetch: (DownloadSource, File) -> Unit,
    ): PluginDownloader.Download {
        if (sources.isEmpty())
            throw IOException("Plugin '$pluginId' has no download sources")

        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Failed to create ${directory.absolutePath}")

        val errors = ArrayList<Throwable>()

        for (source in sources) {
            val target = File.createTempFile("$pluginId-", ".jar", directory)

            try {
                fetch(source, target)

                source.sha1?.let { expected ->
                    val actual = sha1(target)

                    if (actual != expected)
                        throw IOException("$source serves a file with sha1 $actual, $expected expected")
                }

                val manifest = PluginManifest.of(target)
                    ?: throw IOException("$source serves a file that is not a plugin")

                if (manifest.id != pluginId)
                    throw IOException("$source serves plugin '${manifest.id}', but '$pluginId' was expected")

                return PluginDownloader.Download(target, manifest)
            } catch (e: Throwable) {
                target.delete()

                Logger.warn("Failed to download '$pluginId' from $source: ${e.format()}")

                errors.add(e)
            }
        }

        throw IOException(
            "Failed to download plugin '$pluginId': " +
                    errors.joinToString("; ") { it.message ?: it.toString() }
        )
            .apply { errors.forEach(::addSuppressed) }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")

        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            while (true) {
                val read = input.read(buffer)

                if (read < 0)
                    break

                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
