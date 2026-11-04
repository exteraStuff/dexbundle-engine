package io.github.exterastuff.dexbundle.engine.download

import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.impl.DownloadSource
import io.github.exterastuff.dexbundle.engine.impl.PluginManifest
import io.github.exterastuff.dexbundle.engine.util.Logger
import io.github.exterastuff.dexbundle.engine.util.compareVersions

/** Ищет более новые сборки установленных плагинов по их `updateSources`. */
object PluginUpdater {
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private const val LAST_CHECK_KEY = "dexengine_last_update_check"

    private const val SKIPPED_VERSION_KEY_PREFIX = "dexengine_skipped_update_"

    /**
     * Проверяет обновления [installed], если с прошлой проверки прошло достаточно времени.
     *
     * Для каждой найденной версии, которую пользователь не пропустил, вызывает [onUpdate] со
     * скачанным jar'ом. Файлы без обновления удаляются сразу.
     */
    @WorkerThread
    fun check(
        preferences: SharedPreferences,
        installed: Collection<PluginManifest>,
        onUpdate: (PluginDownloader.Download) -> Unit,
    ) = check(preferences, installed, onUpdate, System.currentTimeMillis(), PluginDownloader::download)

    internal fun check(
        preferences: SharedPreferences,
        installed: Collection<PluginManifest>,
        onUpdate: (PluginDownloader.Download) -> Unit,
        now: Long,
        downloadPlugin: (String, List<DownloadSource>) -> PluginDownloader.Download,
    ) {

        if ((now - preferences.getLong(LAST_CHECK_KEY, 0)) in 0..<CHECK_INTERVAL_MS)
            return

        preferences.edit()
            .putLong(LAST_CHECK_KEY, now)
            .apply()

        // Вау, спасибо Android Studio, что предложила заменить 3 ебучих обращения на тонну нижних
        // подчёркиваний.
        //
        // Просто ебать как круто!
        @Suppress("DestructuringDeclaration")
        for (manifest in installed) {
            if (manifest.updateSources.isEmpty())
                continue

            val download = try {
                downloadPlugin(manifest.id, manifest.updateSources)
            } catch (e: Throwable) {
                Logger.warn("Failed to check updates of '${manifest.id}': ${e.format()}")
                continue
            }

            val version = download.manifest.version

            val isNew = compareVersions(version, manifest.version) > 0
                    && preferences.getString(
                SKIPPED_VERSION_KEY_PREFIX + manifest.id,
                null
            ) != version

            if (isNew)
                onUpdate(download)
            else
                download.file.delete()
        }
    }

    /** Запоминает, что [version] плагина [id] предлагать больше не нужно. */
    fun skipVersion(preferences: SharedPreferences, id: String, version: String) {
        preferences.edit()
            .putString(SKIPPED_VERSION_KEY_PREFIX + id, version)
            .apply()
    }
}
