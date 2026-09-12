package io.github.exterastuff.dexbundle.engine.impl

import android.util.Log
import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.hooks.PluginsHooks
import com.exteragram.messenger.plugins.models.SettingItem
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet
import dalvik.system.DexClassLoader
import io.github.exterastuff.dexbundle.api.BasePlugin
import io.github.exterastuff.dexbundle.api.PluginContext
import io.github.exterastuff.dexbundle.api.PluginLogger
import io.github.exterastuff.dexbundle.engine.compat.ExteraConfigCompat
import io.github.exterastuff.dexbundle.engine.exception.UnsatisfiedRequirementException
import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.i18n.Strings
import io.github.exterastuff.dexbundle.engine.ui.PluginInstallBottomSheet
import io.github.exterastuff.dexbundle.engine.ui.PluginSignatureState
import io.github.exterastuff.dexbundle.engine.util.Logger
import io.github.exterastuff.dexbundle.engine.util.compareVersions
import io.github.exterastuff.dexbundle.engine.util.runOnMainThread
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.MessageObject
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory

class DexBundlePluginsEngine : PluginsController.PluginsEngine {
    companion object {
        const val ID = "dex-bundle"
        const val PLUGINS_EXTENSION = "jar"

        private const val ENABLED_KEY_PREFIX = "plugin_enabled_"

        /** Jar установленного плагина [id]. */
        fun pluginFile(id: String): File =
            File(
                PluginsController.getInstance().pluginsDir,
                "$id.$PLUGINS_EXTENSION",
            )

        /** Папка сгенерированного кода: кеш dex и распакованные определения сервисов. */
        val codeCacheDir: File
            get() = ApplicationLoader.applicationContext.codeCacheDir

        private fun warn(message: String) {
            Logger.warn("DexBundlePluginsEngine: $message")
        }

        private fun info(message: String) {
            Logger.info("DexBundlePluginsEngine: $message")
        }
    }

    /** Манифесты установленных плагинов по id. */
    private val manifests = ConcurrentHashMap<String, PluginManifest>()

    /** Точки входа загруженных плагинов по id. */
    private val activePlugins = ConcurrentHashMap<String, BasePlugin>()

    /** Контексты, выданные загруженным плагинам, по id. */
    private val contexts = ConcurrentHashMap<String, PluginContextImpl>()

    /** Определения сервисов включённых плагинов. Родитель class-loader'а каждого плагина. */
    @Volatile private var services: PluginServices? = null

    private val pluginsController
        get() = PluginsController.getInstance()

    /** Плагины этого движка в списке клиента. */
    private val ownPlugins
        get() = pluginsController.plugins.values.filter { it.getEngine() == ID }

    /** Id установленных плагинов, которые должны работать. */
    private val enabledPlugins
        get() = manifests.keys.filter(::isPluginEnabled)

    private val engineClassLoader
        get() = this::class.java.classLoader!!

    /** Должен ли плагин [id] работать. */
    private fun isPluginEnabled(id: String): Boolean =
        pluginsController.preferences.getBoolean("$ENABLED_KEY_PREFIX$id", false)

    /** Сохраняет, должен ли [id] работать, и ошибку [error], из-за которой он остановлен. */
    private fun savePluginEnabled(id: String, enabled: Boolean, error: Throwable? = null) {
        pluginsController.preferences.edit().putBoolean("$ENABLED_KEY_PREFIX$id", enabled).apply()

        pluginsController.plugins[id]?.setState(enabled, error)
    }

    /** Добавляет плагин из [manifest] в список плагинов клиента как ещё не запущенный. */
    private fun publishPlugin(manifest: PluginManifest) {
        pluginsController.plugins[manifest.id] = manifest.toPlugin()
    }

    /**
     * Отмечает в списке клиента, работает ли плагин [id] прямо сейчас, и с какой ошибкой он встал.
     *
     * Клиент смотрит на это поле, когда решает, жив ли плагин, поэтому оно следует за реальной
     * загрузкой, а не за сохранённым выбором пользователя.
     */
    private fun publishPluginRunning(id: String, running: Boolean, error: Throwable? = null) {
        pluginsController.plugins[id]?.setState(running, error)
    }

    /**
     * Выставляет флаг и ошибку разом.
     *
     * Клиент игнорирует включение, пока на плагине висит ошибка, и сам гасит флаг, когда ошибку
     * ставят, поэтому порядок важен.
     */
    private fun Plugin.setState(enabled: Boolean, error: Throwable?) {
        setError(error)
        setEnabled(enabled)
    }

    @Throws(Throwable::class)
    private fun manifestOf(id: String): PluginManifest =
        manifests.getOrPut(id) {
            PluginManifest.of(pluginFile(id))
                ?: throw IllegalArgumentException("Provided file is not a plugin")
        }

    /**
     * Вызывает код плагина под watchdog'ом клиента: если плагин повесит очередь плагинов, его можно
     * будет выключить вручную, а не ловить намертво зависший клиент.
     */
    private inline fun <R> watched(id: String, block: () -> R): R {
        val watchdog = pluginsController.watchdog

        watchdog.onPluginExecutionStarted(id)

        return try {
            block()
        } finally {
            watchdog.onPluginExecutionFinished(id)
        }
    }

    /** Пересобирает общий class-loader из определений сервисов, которые дают [ids]. */
    private fun rebuildServices(ids: Collection<String>): PluginServices {
        if (activePlugins.isNotEmpty())
            warn("Rebuilding services while ${activePlugins.keys.joinToString()} are loaded")

        return PluginServices.build(ids.mapNotNull(manifests::get), engineClassLoader).also {
            services = it
        }
    }

    /** Общий class-loader включённых плагинов. Собирается при первом обращении. */
    private fun requireServices(): PluginServices = services ?: rebuildServices(enabledPlugins)

    /** [ids] и все установленные плагины, которые от них зависят, прямо или нет. */
    private fun withDependents(ids: Collection<String>): Set<String> {
        val result = LinkedHashSet(ids)

        do {
            val dependents =
                manifests
                    .filterKeys { it !in result }
                    .filterValues { manifest -> manifest.dependencies.keys.any { it in result } }
                    .keys

            result.addAll(dependents)
        } while (dependents.isNotEmpty())

        return result
    }

    /** [ids] в порядке, где плагин идёт после тех, от кого зависит. */
    private fun dependencyOrder(ids: Collection<String>): List<String> {
        val pending = ids.toSet()
        val ordered = LinkedHashSet<String>()
        val visiting = LinkedHashSet<String>()

        fun visit(id: String) {
            if (id !in pending || id in ordered) return

            // ура dep-cycle
            if (!visiting.add(id)) {
                warn("Dependency cycle: ${visiting.joinToString(" -> ")} -> $id")
                return
            }

            manifests[id]?.dependencies?.keys?.forEach { visit(it) }

            visiting.remove(id)
            ordered.add(id)
        }

        ids.forEach { visit(it) }

        return ordered.toList()
    }

    /** Сообщает [dependentId], что его зависимость [dependencyId] сейчас выгрузят. */
    private fun notifyDependencyUnload(dependentId: String, dependencyId: String) {
        val dependent = activePlugins[dependentId] ?: return

        runCatching { watched(dependentId) { dependent.onDependencyUnload(dependencyId) } }
            .onFailure {
                warn(
                    "Plugin '$dependentId' failed to handle unload of '$dependencyId': ${it.format()}"
                )
            }
    }

    /** Выгружает [ids], начиная с зависимых. */
    private fun unloadPlugins(ids: Collection<String>) {
        val ordered = dependencyOrder(ids).asReversed()
        val batch = ordered.toHashSet()

        // зависимый плагин уходит вместе со своими зависимостями и узнаёт об этом, пока ещё
        // может отпустить всё, что у них взял
        for (id in ordered) manifests[id]
            ?.dependencies
            ?.keys
            .orEmpty()
            .filter { it in batch }
            .forEach { dependencyId -> notifyDependencyUnload(id, dependencyId) }

        for (id in ordered) runCatching { unloadPlugin(id) }
            .onFailure { warn("Failed to unload plugin '$id': ${it.format()}") }
    }

    /**
     * Загружает [ids], начиная с зависимостей.
     *
     * Упавший плагин выключается и сохраняет ошибку, на остальных это не влияет. Плагин, который
     * просто ждёт чего-то извне, остаётся включённым и стартует сам, когда дождётся.
     *
     * @return ошибки по id плагина
     */
    private fun loadPlugins(ids: Collection<String>): Map<String, Throwable> {
        if (ids.isEmpty()) return emptyMap()

        if (ExteraConfigCompat.isPluginsSafeMode()) {
            info("Safe mode is enabled, no plugin is started")
            return emptyMap()
        }

        val errors = LinkedHashMap<String, Throwable>()

        for (id in dependencyOrder(ids)) {
            try {
                loadPlugin(id)
            } catch (e: UnsatisfiedRequirementException) {
                info("Plugin '$id' is not started: ${e.message}")

                publishPluginRunning(id, running = false, error = e)
                errors[id] = e
            } catch (e: Throwable) {
                warn("Failed to load plugin '$id': ${e.format()}")

                savePluginEnabled(id, false, e)
                errors[id] = e
            }
        }

        return errors
    }

    /**
     * Перезапускает [ids] вместе с зависящими от них плагинами.
     *
     * @param withServices пересобрать ещё и общий class-loader. Это перезапускает все работающие
     *   плагины, так как их class-loader'ы — его потомки.
     * @return ошибки по id плагина
     */
    @Synchronized
    private fun reload(
        ids: Collection<String>,
        withServices: Boolean,
    ): Map<String, Throwable> {
        val enabled = enabledPlugins

        val affected =
            if (withServices) activePlugins.keys + enabled
            else withDependents(ids).filter { activePlugins.containsKey(it) || it in enabled }

        info(
            "reload ${affected.joinToString().ifEmpty { "nothing" }}" +
                if (withServices) " (with services)" else ""
        )

        unloadPlugins(affected)

        if (withServices) rebuildServices(enabled)

        return loadPlugins(affected.filter { it in enabled })
    }

    /**
     * Заменяет установленный [pluginId] на jar по пути [filePath].
     *
     * @param callback получает ошибку установки, или `null` при успехе
     */
    fun install(
        pluginId: String,
        filePath: String,
        callback: (error: String?) -> Unit,
    ) = PluginsController.runOnPluginsQueue {
        val error = runCatching {
            installBlocking(pluginId, filePath)
        }
            .getOrElse {
                warn("Failed to install plugin '$pluginId': ${it.format()}")
                it.toString()
            }

        pluginsController.notifyPluginsChanged()

        callback(error)
    }

    /**
     * Проверяет и устанавливает плагин, перезапуская всё, что работало с его предыдущей версией.
     *
     * @return описание ошибки, или `null` при успехе
     */
    @Throws(Throwable::class)
    @Synchronized
    private fun installBlocking(pluginId: String, filePath: String): String? {
        val source = File(filePath)

        if (!source.isFile) return "File $filePath not found"

        val manifest =
            PluginManifest.of(source)
                ?: throw IllegalArgumentException("Provided file is not an plugin")

        if (manifest.id != pluginId)
            throw IllegalArgumentException(
                "Plugin file declares id '${manifest.id}', but '$pluginId' was expected"
            )

        val fakeServices = manifest.getFakeServices(source)

        if (fakeServices.isNotEmpty())
            throw IllegalArgumentException(
                "Plugin does not ship declared services ${fakeServices.joinToString()}"
            )

        val installedManifest = manifests[pluginId]

        // файл мог измениться с тех пор, как его смотрел диалог установки
        val signature = PluginSignatureState.of(manifest.signers, installedSigners(pluginId))

        if (signature.blocksInstall)
            throw SecurityException("Plugin signature is ${signature.name.lowercase()}")

        // определения сервисов лежат в общем class-loader'е, поэтому их появление или пропажа
        // задевает все плагины
        val withServices =
            manifest.providedServices.isNotEmpty() ||
                installedManifest?.providedServices?.isNotEmpty() == true

        // останавливаем работающую копию и всё, что на неё опирается, а при смене общих
        // определений — вообще все плагины
        if (withServices) unloadPlugins(activePlugins.keys.toList())
        else unloadPlugins(withDependents(listOf(pluginId)))

        try {
            writePluginFile(source, pluginFile(pluginId))
        } catch (e: Throwable) {
            // предыдущая версия осталась на месте, поэтому просто возвращаем её в работу
            reload(listOf(pluginId), withServices)

            throw e
        }

        manifests[pluginId] = manifest

        // определения сервисов обеих версий уже распакованы
        PluginServices.invalidate(listOfNotNull(installedManifest, manifest))

        publishPlugin(manifest)

        // зависящие от него плагины работают с предыдущей версией, поэтому их тоже
        // перезапускаем
        reload(listOf(pluginId), withServices)

        return null
    }

    /**
     * Кладёт [source] на место установленного плагина. Незавершённая запись не оставляет после себя
     * повреждённый jar.
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun writePluginFile(source: File, target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")

        try {
            source.copyTo(temp, overwrite = true)

            if (!temp.renameTo(target))
                throw IOException("Failed to move ${temp.name} to ${target.name}")
        } finally {
            temp.delete()
        }

        target.setWritable(false)
    }

    /** Требования [manifest], которые ничем не покрыты, в читаемом виде. */
    private fun unsatisfiedRequirements(manifest: PluginManifest): List<String> {
        val requirements = ArrayList<String>()

        val clientVersion = BuildVars.BUILD_VERSION_STRING

        if (
            manifest.minClientVersion.isNotBlank() &&
                !clientVersion.isNullOrBlank() &&
                compareVersions(clientVersion, manifest.minClientVersion) < 0
        )
            requirements.add("client ${manifest.minClientVersion} (running $clientVersion)")

        for ((id, version) in manifest.dependencies) {
            val dependency = manifests[id]

            when {
                dependency == null -> requirements.add("plugin $id:$version (not installed)")

                compareVersions(dependency.version, version) < 0 ->
                    requirements.add("plugin $id:$version (installed ${dependency.version})")

                !activePlugins.containsKey(id) ->
                    requirements.add("plugin $id:$version (not running)")
            }
        }

        val services = requireServices()

        for ((coordinates, version) in manifest.requiredServices) if (
            !services.provides(coordinates, version)
        )
            requirements.add("service $coordinates:$version")

        return requirements
    }

    @Throws(Throwable::class)
    @Synchronized
    fun loadPlugin(id: String) {
        if (activePlugins.containsKey(id)) return

        if (ExteraConfigCompat.isPluginsSafeMode())
            throw UnsatisfiedRequirementException("safe mode is enabled")

        val file = pluginFile(id)

        if (!file.isFile) throw IllegalStateException("Plugin '$id' is not installed")

        file.setWritable(false)

        val manifest = manifestOf(id)

        val requirements = unsatisfiedRequirements(manifest)

        if (requirements.isNotEmpty())
            throw UnsatisfiedRequirementException("requires ${requirements.joinToString()}")

        val services = requireServices()

        val classLoader =
            DexClassLoader(
                file.absolutePath,
                codeCacheDir.absolutePath,
                null,
                services.classLoader,
            )

        val entryClass =
            try {
                classLoader.loadClass(manifest.entryClass)
            } catch (e: Throwable) {
                throw IllegalArgumentException(
                    "Failed to load entry class ${manifest.entryClass}",
                    e,
                )
            }

        if (!BasePlugin::class.java.isAssignableFrom(entryClass))
            throw ClassCastException("Provided entry class is not a child of BasePlugin")

        val plugin =
            try {
                entryClass.getDeclaredConstructor().newInstance() as BasePlugin
            } catch (e: NoSuchMethodException) {
                throw IllegalArgumentException(
                    "Entry class ${manifest.entryClass} has no public constructor without arguments",
                    e,
                )
            } catch (e: InvocationTargetException) {
                // конструктор точки входа — код плагина, поэтому показываем именно его ошибку
                throw e.targetException ?: e
            }

        val context = PluginContextImpl(id, services)

        contexts[id] = context

        try {
            watched(id) { plugin.load(context) }
        } catch (e: Throwable) {
            try {
                watched(id) { plugin.unload() }
            } catch (_: Throwable) {
                // плагин уже упал, ошибку выгрузки пробрасывать незачем
            }

            // за плагином, который не завёлся, не должно остаться регистраций
            context.detach()
            contexts.remove(id, context)
            services.unregisterServices(id)

            runCatching { pluginsController.cleanupPlugin(id) }
                .onFailure { warn("Failed to clean up plugin '$id': ${it.format()}") }

            throw e
        }

        activePlugins[id] = plugin

        publishPluginRunning(id, running = true)
    }

    @Throws(Throwable::class)
    @Synchronized
    fun unloadPlugin(id: String) {
        // дальше плагин уже не сможет попросить выгрузить себя ещё раз
        val plugin = activePlugins.remove(id) ?: return

        // предупреждаем зависимые плагины, которые переживут этот
        manifests.values
            .filter { it.dependencies.containsKey(id) }
            .forEach { notifyDependencyUnload(it.id, id) }

        try {
            watched(id) { plugin.unload() }
        } finally {
            // плагин закончил уборку, остальное убираем за него
            contexts.remove(id)?.detach()

            services?.unregisterServices(id)

            // ошибку не трогаем: её мог только что выставить сам плагин или выключение руками
            pluginsController.plugins[id]?.setEnabled(false)

            // хуки, пункты меню и настройки, которые плагин сам зарегистрировал в клиенте
            runCatching { pluginsController.cleanupPlugin(id) }
                .onFailure { warn("Failed to clean up plugin '$id': ${it.format()}") }
        }
    }

    override fun isPlugin(file: File?, messageObject: MessageObject?): Boolean {
        if (file == null) return false

        if (file.extension != PLUGINS_EXTENSION) return false

        return try {
            PluginManifest.of(file) != null
        } catch (_: SecurityException) {
            // подпись сломана, но надо пробросить, чтоб показать буллетин
            true
        } catch (e: Throwable) {
            warn("broken manifest: $e")
            false
        }
    }

    override fun isEngineAvailable(): Boolean = true

    /** Находит установленные плагины и запускает включённые. */
    @Synchronized
    override fun init(callback: Runnable) {
        info("init")

        // клиент ждёт колбэк, чтобы продолжить, с ошибкой или без
        try {
            manifests.clear()

            val pluginsDir = pluginsController.pluginsDir

            if (!pluginsDir.isDirectory && !pluginsDir.mkdirs())
                warn("Plugins directory ${pluginsDir.absolutePath} is not available")

            // оборванная установка оставляет после себя временный jar
            pluginsDir
                .listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.name.endsWith(".$PLUGINS_EXTENSION.tmp") }
                .forEach { file ->
                    if (!file.delete()) warn("Failed to delete leftover ${file.name}")
                }

            pluginsDir
                .listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.extension == PLUGINS_EXTENSION }
                .mapNotNull { file ->
                    val manifest =
                        runCatching { PluginManifest.of(file) }
                            .onFailure {
                                warn("Failed to read plugin '${file.name}': ${it.format()}")
                            }
                            .getOrNull() ?: return@mapNotNull null

                    if (file.nameWithoutExtension != manifest.id) {
                        warn("Plugin '${file.name}' declares id '${manifest.id}', skipping it")

                        return@mapNotNull null
                    }

                    return@mapNotNull manifest
                }
                .forEach { manifest ->
                    manifests[manifest.id] = manifest

                    publishPlugin(manifest)
                }

            // плагин, чей jar пропал, уходит и из списка клиента
            ownPlugins
                .map { it.getId() }
                .filterNot(manifests::containsKey)
                .forEach(pluginsController.plugins::remove)

            val enabled = enabledPlugins

            rebuildServices(enabled)
            loadPlugins(enabled)
        } catch (e: Throwable) {
            warn("Failed to initialize plugins engine: ${e.format()}")
        } finally {
            pluginsController.notifyPluginsChanged()

            callback.run()
        }
    }

    override fun checkDevServer() {
        info("check dev server")

        if (ExteraConfigCompat.isPluginsSafeMode()) {
            info("Safe mode enabled (but how then this plugin engine works?)")
            return
        }

        if (ExteraConfigCompat.isPluginsDevMode()) {
            info("dev server should be started")
            // TODO: наверное, здесь надо запускать dev-сервер
        } else {
            info("dev server should be stopped")
            // TODO: наверное, здесь надо останавливать dev-сервер
        }
    }

    /** Останавливает все работающие плагины и забывает про установленные. */
    @Synchronized
    override fun shutdown(callback: Runnable) {
        info("shutdown")

        try {
            unloadPlugins(activePlugins.keys.toList())

            ownPlugins.map { it.getId() }.forEach(pluginsController.plugins::remove)

            // сбрасываем все ссылки
            activePlugins.clear()
            contexts.clear()
            manifests.clear()
            services = null

            // TODO: остановить dev-сервер
        } catch (e: Throwable) {
            warn("Failed to shutdown plugins engine: ${e.format()}")
        } finally {
            callback.run()
        }
    }

    /**
     * Запускает или останавливает [pluginId] и запоминает выбор.
     *
     * @param callback получает ошибку плагина, или `null` при успехе
     */
    @Synchronized
    fun setPluginEnabled(
        pluginId: String,
        enabled: Boolean,
        error: Throwable?,
        // onError
        callback: Utilities.Callback<String>?,
    ) {
        info("set plugin '$pluginId' enabled: $enabled")

        try {
            if (enabled && ExteraConfigCompat.isPluginsSafeMode())
                throw UnsatisfiedRequirementException("safe mode is enabled")

            if (enabled && !pluginFile(pluginId).isFile)
                throw IllegalStateException("Plugin '$pluginId' is not installed")

            // плагин раздаёт свои определения сервисов через общий class-loader, поэтому и
            // запуск, и остановка требуют его пересборки
            val withServices =
                runCatching { manifestOf(pluginId) }.getOrNull()?.providedServices?.isNotEmpty() ==
                    true

            savePluginEnabled(pluginId, enabled, error)

            // зависящие от него плагины идут следом: без него они не работают
            reload(listOf(pluginId), withServices)[pluginId]?.let { throw it }

            // сообщаем об успехе
            runOnMainThread { callback?.run(null) }
        } catch (e: Throwable) {
            // с самим плагином всё в порядке, если он лишь ждёт требование: оставляем его
            // включённым, и он стартует, как только дождётся
            if (e is UnsatisfiedRequirementException)
                publishPluginRunning(pluginId, running = false, error = e)
            else savePluginEnabled(pluginId, false, e)

            // сообщаем об ошибке буллетином
            runOnMainThread { callback?.run(e.toString()) }
        } finally {
            pluginsController.notifyPluginsChanged()
        }
    }

    /**
     * Запускает или останавливает [pluginId] и запоминает выбор.
     *
     * @param callback получает ошибку плагина, или `null` при успехе
     */
    override fun setPluginEnabled(
        pluginId: String,
        enabled: Boolean,
        // onError
        callback: Utilities.Callback<String>?,
    ) = setPluginEnabled(pluginId, enabled, null, callback)

    /**
     * Останавливает [pluginId] и удаляет его jar.
     *
     * @param callback получает ошибку удаления, или `null` при успехе
     */
    @Synchronized
    override fun deletePlugin(
        pluginId: String,
        callback: Utilities.Callback<String>?,
    ) {
        info("delete plugin '$pluginId'")

        val manifest = manifests[pluginId]
        val withServices = manifest?.providedServices?.isNotEmpty() == true

        try {
            // загруженный jar удалить нельзя, как и jar'ы плагинов, которые сейчас лишатся
            // зависимости; общие определения читают все плагины, поэтому их тоже останавливаем
            if (withServices) unloadPlugins(activePlugins.keys.toList())
            else unloadPlugins(withDependents(listOf(pluginId)))

            val file = pluginFile(pluginId)

            if (file.exists()) {
                file.setWritable(true)

                if (!file.delete()) throw IOException("Failed to delete ${file.name}")
            }
        } catch (e: Throwable) {
            warn("Failed to delete plugin '$pluginId': ${e.format()}")

            // раз плагин не удалился, убирать его из списка незачем: он загрузится при
            // следующем старте движка
            reload(listOf(pluginId), withServices)

            pluginsController.notifyPluginsChanged()

            runOnMainThread { callback?.run(e.toString()) }

            return
        }

        // убираем плагин из списка
        runCatching { pluginsController.cleanupPlugin(pluginId) }
            .onFailure { warn("Failed to clean up plugin '$pluginId': ${it.format()}") }

        // клиент забывает настройки и состояние включённости пропавшего плагина
        runCatching { pluginsController.clearPluginSettingsPreferences(pluginId, true) }
            .onFailure { warn("Failed to drop preferences of '$pluginId': ${it.format()}") }

        manifests.remove(pluginId)
        pluginsController.plugins.remove(pluginId)

        // определения, которые он поставлял, уходят из общего class-loader'а вместе с ним
        if (withServices) manifest?.let { PluginServices.invalidate(listOf(it)) }

        // зависевшие от него плагины покажут это как невыполненное требование
        reload(listOf(pluginId), withServices)

        pluginsController.notifyPluginsChanged()

        // сообщаем
        runOnMainThread { callback?.run(null) }
    }

    override fun getPluginPath(id: String): String = pluginFile(id).absolutePath

    override fun canOpenInExternalApp(): Boolean = false

    override fun openInExternalApp(id: String) {
        info("open plugin '$id' in external app")
    }

    override fun sharePlugin(id: String) {
        info("share plugin '$id'")
    }

    override fun loadPluginSettings(id: String): List<SettingItem> {
        info("load plugin '$id' settings")

        return emptyList()
    }

    override fun getPluginSetting(pluginId: String, key: String, defaultValue: Any?): Any? {
        info("get plugin '$pluginId' setting '$key'")

        return null
    }

    override fun setPluginSetting(pluginId: String, key: String, value: Any?) {
        info("set plugin '$pluginId' setting '$key' -> '$value'")
    }

    override fun clearPluginSettings(pluginId: String) {
        info("clear plugin '$pluginId' settings")
    }

    override fun getAllPluginSettings(pluginId: String): Map<String, *> {
        info("get all plugin '$pluginId' settings")

        return emptyMap<String, Any>()
    }

    override fun openPluginSettings(id: String, fragment: BaseFragment) {
        info("open plugin '$id' settings")
    }

    override fun openPluginSettings(plugin: Plugin, fragment: BaseFragment) =
        openPluginSettings(plugin.getId(), fragment)

    override fun openPluginSetting(pluginId: String, linkAlias: String, fragment: BaseFragment) {
        info("open plugin '$pluginId' setting '$linkAlias'")
    }

    override fun openPluginSetting(plugin: Plugin, linkAlias: String, fragment: BaseFragment) =
        openPluginSetting(plugin.getId(), linkAlias, fragment)

    override fun executeOnAppEvent(eventType: String) {
        info("execute on app event '$eventType'")
    }

    override fun executePreRequestHook(
        requestName: String,
        account: Int,
        request: TLObject?,
        pluginId: String,
    ): PluginsController.HookResult<TLObject> = emptyHookResult()

    override fun executePostRequestHook(
        requestName: String,
        account: Int,
        response: TLObject?,
        error: TLRPC.TL_error?,
        pluginId: String,
    ): PluginsController.HookResult<PluginsHooks.PostRequestResult> = emptyHookResult()

    override fun executeUpdateHook(
        updateName: String,
        account: Int,
        update: TLRPC.Update?,
        pluginId: String,
    ): PluginsController.HookResult<TLRPC.Update> = emptyHookResult()

    override fun executeUpdatesHook(
        containerName: String,
        account: Int,
        updates: TLRPC.Updates?,
        pluginId: String,
    ): PluginsController.HookResult<TLRPC.Updates> = emptyHookResult()

    override fun executeSendMessageHook(
        account: Int,
        params: SendMessagesHelper.SendMessageParams?,
        pluginId: String,
    ): PluginsController.HookResult<SendMessagesHelper.SendMessageParams> = emptyHookResult()

    /** Ответ хука, который ничего не меняет в вызове клиента. */
    private fun <T> emptyHookResult(): PluginsController.HookResult<T> =
        PluginsController.HookResult(
            null,
            cancel = false,
            isFinal = false,
        )

    /** Подписи установленной версии [pluginId], если он вообще установлен. */
    private fun installedSigners(pluginId: String): Map<String, SignerInfo>? {
        val file = pluginFile(pluginId).takeIf(File::exists) ?: return null

        return runCatching { PluginManifest.of(file) }
            .onFailure { info("failed to read installed plugin '$pluginId': $it") }
            .getOrNull()
            ?.signers
    }

    override fun showInstallDialog(
        fragment: BaseFragment,
        params: InstallPluginBottomSheet.PluginInstallParams,
    ) {
        info("show install dialog for '${params.filePath}'")

        val file = File(params.filePath)

        val manifest =
            try {
                PluginManifest.of(file)?.let {
                    val fakeServices = it.getFakeServices(file)

                    if (fakeServices.isNotEmpty()) {
                        warn("Plugin '${file.name}' is missing declared services: $fakeServices")

                        runOnMainThread {
                            BulletinFactory.of(fragment)
                                .createSimpleBulletin(R.raw.error, Strings.servicesMissing())
                                .show()
                        }

                        return@let null
                    }

                    return@let it
                }
            } catch (e: SecurityException) {
                warn("broken signature: $e")

                runOnMainThread {
                    BulletinFactory.of(fragment)
                        .createSimpleBulletin(R.raw.error, Strings.signatureBroken())
                        .show()
                }

                return
            } catch (e: Throwable) {
                warn("broken manifest: $e")
                null
            }

        if (manifest == null) {
            warn("install dialog is not shown: file has no valid plugin manifest")
            return
        }

        PluginInstallBottomSheet.show(
            fragment,
            this,
            manifest.toPlugin(),
            params,
            PluginSignatureState.of(manifest.signers, installedSigners(manifest.id)),
            manifest.signers,
        )
    }

    /** Всё, что плагин получает от движка. */
    private inner class PluginContextImpl(
        private val id: String,
        private val services: PluginServices,
    ) : PluginContext {
        @Volatile private var attached = true

        private val logger =
            object : PluginLogger {
                override fun debug(message: String?) {
                    Log.d(id, message ?: "null")
                }

                override fun info(message: String?) {
                    Log.i(id, message ?: "null")
                }

                override fun warn(message: String?) {
                    Log.w(id, message ?: "null")
                }

                override fun error(message: String?) {
                    Log.e(id, message ?: "null")
                }
            }

        /** Отвязывает контекст от движка после выгрузки плагина. */
        fun detach() {
            attached = false
        }

        override fun getLogger(): PluginLogger = logger

        override fun unloadSelf(error: Throwable?) {
            if (!attached) return

            // плагин может попросить об этом прямо из своей загрузки или выгрузки, поэтому
            // сначала даём движку кончить :)
            PluginsController.runOnPluginsQueue {
                if (attached) setPluginEnabled(id, false, error, null)
            }
        }

        override fun <T : Any, I : T> registerService(
            declaration: Class<T>,
            implementation: I,
        ) {
            check(attached) { "Plugin '$id' is unloaded" }

            services.registerService(id, declaration, implementation)
        }

        override fun <T : Any> getService(klass: Class<T>): T? {
            check(attached) { "Plugin '$id' is unloaded" }

            return services.getService(klass)
        }
    }
}
