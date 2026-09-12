package io.github.exterastuff.dexbundle.engine

import androidx.annotation.AnyThread
import com.exteragram.messenger.plugins.PluginsController
import io.github.exterastuff.dexbundle.engine.eject.EjectNotifier
import io.github.exterastuff.dexbundle.engine.i18n.setupI18n
import io.github.exterastuff.dexbundle.engine.impl.DexBundlePluginsEngine
import io.github.exterastuff.dexbundle.engine.util.Logger
import io.github.exterastuff.dexbundle.engine.util.getAs
import io.github.exterastuff.dexbundle.engine.util.getField
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import kotlin.concurrent.thread
import kotlin.time.Instant
import org.jetbrains.annotations.Blocking

class Plugin private constructor() {
    @Suppress("unused")
    companion object {
        const val ID = "dexbundle-engine"

        private val STATE_LOCK = Any()

        private const val HANDLE_KEY = "io.github.exterastuff.dexbundle.engine.handle"

        @Volatile private var WAS_INJECTED = false

        @Volatile private var INSTANCE: Plugin? = null

        private var VERSION: String? = null

        internal fun getInstance(): Plugin = INSTANCE!!

        @JvmStatic
        fun getBuildDate(): String =
            Instant.fromEpochMilliseconds(BuildConfig.BUILD_TIME).toString()

        @JvmStatic fun getVersion(): String? = VERSION

        @Blocking
        @JvmStatic
        fun inject(version: String): Unit =
            synchronized(STATE_LOCK) {
                if (INSTANCE != null) return

                if (WAS_INJECTED)
                    throw IllegalStateException("Cannot inject plugin from same class-loader twice")

                VERSION = version
                WAS_INJECTED = true

                val props = System.getProperties()

                // не даём двум инъекциям (из разных class-loader'ов) идти одновременно
                synchronized(props) {
                    @Suppress("UNCHECKED_CAST")
                    (props.put(HANDLE_KEY, Supplier { runEjectThread() }) as? Supplier<Thread>)
                        ?.apply {
                            Logger.info("Plugin is probably injected in different class loader!")

                            Logger.info("Ejecting old plugin...")
                            get().join()
                        }

                    setupI18n()

                    try {
                        val plugin = Plugin().also { INSTANCE = it }

                        plugin.onInject()
                    } catch (e: Throwable) {
                        Logger.fatal("Failed to create and inject plugin", e, preventEject = true)

                        // всё, что успела поднять неудавшаяся инъекция, уходит вместе с ней, а
                        // ручка не должна указывать на несуществующий плагин
                        props.remove(HANDLE_KEY)

                        ejectLocked()
                    }
                }
            }

        @AnyThread
        private fun runEjectThread(): Thread =
            thread(contextClassLoader = Plugin::class.java.classLoader) {
                synchronized(STATE_LOCK) { ejectLocked() }
            }

        /** Останавливает движок. Вызывающий владеет [STATE_LOCK]. */
        private fun ejectLocked() {
            // сама выгрузка не должна запускать ещё одну, что бы в ней ни сломалось
            Logger.suppressFatal()

            try {
                INSTANCE?.onEject()
            } catch (e: Throwable) {
                Logger.fatal("Failed to eject plugin", e, preventEject = true)
            } finally {
                INSTANCE = null

                EjectNotifier.fire()
            }
        }

        @AnyThread
        @Blocking
        @JvmStatic
        fun eject() {
            // поток, который уже держит состояние (упавшая инъекция, фатальная ошибка внутри
            // движка), не может ждать, пока состояние заберёт другой
            if (Thread.holdsLock(STATE_LOCK)) {
                ejectLocked()
                return
            }

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

        val engine = enginesMap[DexBundlePluginsEngine.ID] as? DexBundlePluginsEngine ?: return

        try {
            engine.shutdown {}
        } finally {
            // место могла уже занять следующая инъекция
            enginesMap.remove(DexBundlePluginsEngine.ID, engine)
        }
    }
}
