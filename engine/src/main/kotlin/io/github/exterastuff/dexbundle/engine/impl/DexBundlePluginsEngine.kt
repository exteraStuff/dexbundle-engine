package io.github.exterastuff.dexbundle.engine.impl

import android.util.Log
import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.hooks.PluginsHooks
import com.exteragram.messenger.plugins.models.SettingItem
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet
import dalvik.system.DexClassLoader
import io.github.exterastuff.dexbundle.api.BasePlugin
import io.github.exterastuff.dexbundle.engine.compat.ExteraConfigCompat
import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.ui.PluginInstallBottomSheet
import io.github.exterastuff.dexbundle.engine.util.Logger
import io.github.exterastuff.dexbundle.engine.util.runOnMainThread
import kotlinx.collections.immutable.toImmutableSet
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.Utilities
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DexBundlePluginsEngine : PluginsController.PluginsEngine {
    companion object {
        const val ID = "dex-bundle"
        const val PLUGINS_EXTENSION = "jar"

        private fun info(message: String) {
            Logger.info("DexPluginsEngine: $message")
        }

        fun pluginFile(id: String): File =
            File(
                PluginsController.getInstance().pluginsDir,
                "$id.$PLUGINS_EXTENSION"
            )

        fun install(
            filePath: String,
            plugin: Plugin,
            callback: (error: String?) -> Unit
        ) = PluginsController.runOnPluginsQueue {
            val pluginId = plugin.getId()

            val error = runCatching {
                val source = File(filePath)
                    .takeIf(File::exists)
                    ?: return@runCatching "File $filePath not found"

                val pluginsController = PluginsController.getInstance()

                val loadedPlugin = pluginsController
                    .plugins[pluginId]
                    ?.takeIf { it.cachedEngine is DexBundlePluginsEngine }

                // unload old copy
                (loadedPlugin?.cachedEngine as? DexBundlePluginsEngine)
                    ?.unloadPlugin(pluginId)

                // replace with new version
                pluginFile(pluginId).apply {
                    if (exists())
                        setWritable(true)

                    source.copyTo(this, overwrite = true)
                }

                // load plugin if it was unloaded before
                (loadedPlugin?.cachedEngine as? DexBundlePluginsEngine)
                    ?.unloadPlugin(pluginId)

                pluginsController.apply {
                    plugins[pluginId] = if (loadedPlugin == null)
                        plugin
                    else
                        plugin.apply { setEnabled(loadedPlugin.isEnabled()) }

                    notifyPluginsChanged()
                }

                null
            }.getOrElse {
                Logger.info("Failed to install plugin '${plugin.getId()}': ${it.format()}")
                it.toString()
            }

            callback(error)
        }
    }

    private data class ActivePlugin(
        val id: String,
        val classLoader: ClassLoader,
        val entryClass: BasePlugin,
    )

    private val activePlugins = ConcurrentHashMap<String, ActivePlugin>()

    private val pluginsController
        get() = PluginsController.getInstance()

    private val ownPlugins
        get() = pluginsController.plugins.values
            .filter { it.getEngine() == ID }

    @Synchronized
    fun loadPlugin(id: String) {
        if (activePlugins.containsKey(id))
            return

        val file = pluginFile(id)
        file.setWritable(false)

        val manifest = PluginManifest.parse(file)
            ?: throw IllegalArgumentException("Provided file is not a plugin")

        val classLoader = DexClassLoader(
            file.absolutePath,
            ApplicationLoader.applicationContext.codeCacheDir.absolutePath,
            null,
            this::class.java.classLoader
        )

        @Suppress("CAST_NEVER_SUCCEEDS")
        val entryClass = classLoader.loadClass(manifest.entryClass)

        if (!BasePlugin::class.java.isAssignableFrom(entryClass))
            throw ClassCastException("Provided entry class is not a child of BasePlugin")

        val plugin = entryClass
            .getDeclaredConstructor()
            .newInstance() as BasePlugin

        try {
            plugin.load {
                object : io.github.exterastuff.dexbundle.api.Logger {
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
            }
        } catch (e: Throwable) {
            try {
                plugin.unload()
            } catch (_: Throwable) {
                // plugin has been already crashed, no need to re-throw exception on unload
            }

            throw e
        }

        activePlugins[id] = ActivePlugin(id, classLoader, plugin)
    }

    @Synchronized
    fun unloadPlugin(id: String) {
        val activePlugin = activePlugins.remove(id)
            ?: return

        activePlugin.entryClass.unload()
    }

    override fun isPlugin(
        file: File?,
        messageObject: MessageObject?
    ): Boolean {
        if (file == null)
            return false

        if (file.extension != "jar")
            return false

        return runCatching { PluginManifest.parse(file) }
            .onFailure { Logger.info("broken manifest: ${it.toString()}") }
            .getOrNull() != null
    }

    override fun isEngineAvailable(): Boolean =
        true

    override fun init(callback: Runnable) {
        info("init")

        ownPlugins.forEach { it.cachedEngine = this }

        ownPlugins
            .filter { it.isEnabled() }
            .forEach { setPluginEnabled(it.getId(), true) {} }
    }

    override fun checkDevServer() {
        info("check dev server")

        if (ExteraConfigCompat.isPluginsSafeMode()) {
            info("Safe mode enabled (but how then this plugin engine works?)")
            return
        }

        if (ExteraConfigCompat.isPluginsDevMode()) {
            info("dev server should be started")
            // TODO: run dev server probably?
        } else {
            info("dev server should be stopped")
            // TODO: stop dev server probably?
        }
    }

    override fun shutdown(callback: Runnable) {
        info("shutdown")

        try {
            activePlugins
                .keys
                .toImmutableSet()
                .forEach { runCatching { unloadPlugin(it) } }

            // clear all refs
            activePlugins.clear()

            // TODO: stop dev server
            // TODO: unload all plugins
        } catch (e: Throwable) {
            info("Failed to shutdown plugins engine: ${e.toString()}")
        }

        callback.run()
    }

    private fun savePluginEnabled(pluginId: String, enabled: Boolean, error: Throwable? = null) {
        val editor = pluginsController.preferences.edit()

        editor.putBoolean("plugin_enabled_$pluginId", enabled)
        editor.apply()

        pluginsController.plugins[pluginId]
            ?.apply {
                setEnabled(enabled)
                setError(error)
            }
    }

    override fun setPluginEnabled(
        pluginId: String,
        enabled: Boolean,
        // onError
        callback: Utilities.Callback<String>?
    ) {
        info("set plugin '$pluginId' enabled: $enabled")

        try {
            //todo

            if (enabled)
                loadPlugin(pluginId)
            else
                unloadPlugin(pluginId)

            savePluginEnabled(pluginId, enabled)

            // notify about ok
            runOnMainThread { callback?.run(null) }
        } catch (e: Throwable) {
            savePluginEnabled(pluginId, false, e)

            // notify about error via bulletin
            runOnMainThread { callback?.run(e.toString()) }
        } finally {
            pluginsController.notifyPluginsChanged()
        }
    }

    override fun deletePlugin(
        pluginId: String,
        callback: Utilities.Callback<String>?
    ) {
        info("delete plugin '$pluginId'")
        callback?.run(pluginId)
    }

    override fun getPluginPath(id: String): String {
        info("get plugin '$id' path")
        return "/sdcard/123.py"
    }

    override fun canOpenInExternalApp(): Boolean =
        false

    override fun openInExternalApp(id: String) {
        info("open plugin '$id' in external app")
    }

    override fun sharePlugin(id: String) {
        info("share plugin '$id'")
    }

    override fun loadPluginSettings(id: String): List<SettingItem> {
        info("load plugin '$id' settings")
        return listOf()
    }

    override fun getPluginSetting(
        pluginId: String,
        key: String,
        defaultValue: Any?
    ): Any? {
        info("get plugin '$pluginId' setting")
        return null
    }

    override fun setPluginSetting(pluginId: String, key: String, value: Any?) {
        info("set plugin '$pluginId' setting '$key' -> '${value?.toString()}'")
    }

    override fun clearPluginSettings(pluginId: String) {
        info("clear plugin '$pluginId' settings")
    }

    override fun getAllPluginSettings(pluginId: String): Map<String, *> {
        info("get all plugin '$pluginId' settings")
        return mapOf<String, Any>()
    }

    override fun executeOnAppEvent(eventType: String) {
        info("execute on app event '$eventType'")
    }

    override fun executePreRequestHook(
        requestName: String,
        account: Int,
        request: TLObject?,
        pluginId: String
    ): PluginsController.HookResult<TLObject> {
        info("execute pre-request hook for plugin '$pluginId'")

        return PluginsController.HookResult(
            result = null,
            cancel = false,
            isFinal = false
        )
    }

    override fun executePostRequestHook(
        requestName: String,
        account: Int,
        response: TLObject?,
        error: TLRPC.TL_error?,
        pluginId: String
    ): PluginsController.HookResult<PluginsHooks.PostRequestResult> {
        info("execute post-request hook for plugin '$pluginId'")

        return PluginsController.HookResult(
            result = null,
            cancel = false,
            isFinal = false
        )
    }

    override fun executeUpdateHook(
        updateName: String,
        account: Int,
        update: TLRPC.Update?,
        pluginId: String
    ): PluginsController.HookResult<TLRPC.Update> {
        info("execute update hook for plugin '$pluginId'")

        return PluginsController.HookResult(
            result = null,
            cancel = false,
            isFinal = false
        )
    }

    override fun executeUpdatesHook(
        containerName: String,
        account: Int,
        updates: TLRPC.Updates?,
        pluginId: String
    ): PluginsController.HookResult<TLRPC.Updates> {
        info("execute updates hook for plugin '$pluginId'")

        return PluginsController.HookResult(
            result = null,
            cancel = false,
            isFinal = false
        )
    }

    override fun executeSendMessageHook(
        account: Int,
        params: SendMessagesHelper.SendMessageParams?,
        pluginId: String
    ): PluginsController.HookResult<SendMessagesHelper.SendMessageParams> {
        info("execute send messages hook for plugin '$pluginId'")

        return PluginsController.HookResult(
            result = null,
            cancel = false,
            isFinal = false
        )
    }

    override fun showInstallDialog(
        fragment: BaseFragment,
        params: InstallPluginBottomSheet.PluginInstallParams
    ) {
        info("show install dialog for '${params.filePath}'")

        val manifest = runCatching { PluginManifest.parse(File(params.filePath)) }
            .onFailure { info("broken manifest: $it") }
            .getOrNull()

        if (manifest == null) {
            info("install dialog is not shown: file has no valid plugin manifest")
            return
        }

        PluginInstallBottomSheet.show(fragment, manifest.toPlugin(), params)
    }

    override fun openPluginSettings(
        id: String,
        fragment: BaseFragment
    ) {
        info("open plugin '$id' settings (via plugin id)")
    }

    override fun openPluginSettings(
        plugin: Plugin,
        fragment: BaseFragment
    ) {
        info("open plugin '${plugin.getId()}' settings (via plugin instance)")
    }

    override fun openPluginSetting(
        plugin: Plugin,
        linkAlias: String,
        fragment: BaseFragment
    ) {
        info("open plugin '${plugin.getId()}' setting '$linkAlias' (via plugin instance)")
    }

    override fun openPluginSetting(
        pluginId: String,
        linkAlias: String,
        fragment: BaseFragment
    ) {
        info("open plugin '${pluginId}' setting '$linkAlias' (via plugin id)")
    }
}