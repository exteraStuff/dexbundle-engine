package io.github.exterastuff.dexbundle.engine

import androidx.annotation.AnyThread
import com.exteragram.messenger.plugins.PluginsController
import io.github.exterastuff.dexbundle.engine.eject.EjectNotifier
import io.github.exterastuff.dexbundle.engine.i18n.setupI18n
import io.github.exterastuff.dexbundle.engine.impl.DexBundlePluginsEngine
import io.github.exterastuff.dexbundle.engine.util.Logger
import io.github.exterastuff.dexbundle.engine.util.getAs
import io.github.exterastuff.dexbundle.engine.util.getField
import org.jetbrains.annotations.Blocking
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import kotlin.concurrent.thread
import kotlin.time.Instant

class Plugin private constructor() {
    @Suppress("unused")
    companion object {
        const val ID = "dexbundle-engine"

        private val STATE_LOCK = Any()

        private const val HANDLE_KEY = "io.github.exterastuff.dexbundle.engine.handle"

        @Volatile
        private var WAS_INJECTED = false

        @Volatile
        private var INSTANCE: Plugin? = null

        private var VERSION: String? = null

        internal fun getInstance(): Plugin = INSTANCE!!

        @JvmStatic
        fun getBuildDate(): String = Instant
            .fromEpochMilliseconds(BuildConfig.BUILD_TIME)
            .toString()

        @JvmStatic
        fun getVersion(): String? = VERSION

        @Blocking
        @JvmStatic
        fun inject(version: String): Unit = synchronized(STATE_LOCK) {
            if (INSTANCE != null)
                return

            if (WAS_INJECTED)
                throw IllegalStateException("Cannot inject plugin from same class-loader twice")

            VERSION = version
            WAS_INJECTED = true

            val props = System.getProperties()

            // prevent two plugin injects concurrently (from different class-loaders)
            synchronized(props) {
                @Suppress("UNCHECKED_CAST")
                (props.put(HANDLE_KEY, Supplier { runEjectThread() }) as? Supplier<Thread>)
                    ?.apply {
                        Logger.info("Plugin is probably injected in different class loader!")

                        Logger.info("Ejecting old plugin...")
                        get().join()
                    }

                setupI18n()

                Logger.tryOrFatal("create and inject plugin") {
                    val plugin = Plugin()
                        .also { INSTANCE = it }

                    plugin.onInject()
                }
            }
        }

        @AnyThread
        private fun runEjectThread(): Thread =
            thread(contextClassLoader = Plugin::class.java.classLoader) {
                synchronized(STATE_LOCK) {
                    Logger.tryOrFatal("Failed to eject plugin") {
                        INSTANCE?.onEject()
                    }

                    INSTANCE = null
                }
            }

        @AnyThread
        @Blocking
        @JvmStatic
        fun eject() {
            runEjectThread().join()
        }
    }

    private val enginesMap by lazy {
        val pluginsController = PluginsController.getInstance()

        getField(PluginsController::class.java, "enginesMap")
            .getAs<ConcurrentHashMap<String, PluginsController.PluginsEngine>>(pluginsController)!!
    }

    private fun onInject() {
        DexBundlePluginsEngine().apply {
            enginesMap[DexBundlePluginsEngine.ID] = this
            init {}
        }

        Logger.info("Injected!")
    }

    @Blocking
    private fun onEject() {
        Logger.info("onEject called!")

        (enginesMap[DexBundlePluginsEngine.ID] as? DexBundlePluginsEngine)?.apply {
            shutdown { }
            enginesMap.remove(DexBundlePluginsEngine.ID)
        }

        EjectNotifier.fire()
    }
}
