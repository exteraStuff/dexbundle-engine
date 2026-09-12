package io.github.exterastuff.dexbundle.engine.util

import android.util.Log
import io.github.exterastuff.dexbundle.engine.Plugin
import io.github.exterastuff.dexbundle.engine.eject.EjectNotifier
import io.github.exterastuff.dexbundle.engine.extension.format
import java.util.concurrent.ThreadLocalRandom

object Logger : EjectNotifier.Delegate {

    init {
        EjectNotifier.subscribe(this, priority = 1000)
    }

    // различает строки лога инстансов плагина из разных class-loader'ов
    private val ID =
        ThreadLocalRandom.current()
            .nextInt()
            .toHexString(
                HexFormat {
                    upperCase = true

                    number {
                        minLength = 4
                    }
                }
            )
            .take(4)
            .let { "${Plugin.ID}[$it]" }

    @Volatile private var suppressFatal = false

    fun debug(message: String) {
        Log.d(ID, message)
    }

    fun info(message: String) {
        Log.i(ID, message)
    }

    fun warn(message: String) {
        Log.w(ID, message)
    }

    fun error(message: String) {
        Log.e(ID, message)
    }

    /** Оставляет [fatal] без последствий: плагин уже выгружается. */
    internal fun suppressFatal() {
        suppressFatal = true
    }

    fun fatal(message: String, exception: Throwable, preventEject: Boolean = false) {
        Log.wtf(ID, message)
        Log.wtf(ID, exception.format())

        if (!suppressFatal && !preventEject) Plugin.eject()
    }

    fun tryOrFatal(action: String, block: () -> Unit): Unit? =
        try {
            block()
        } catch (e: Throwable) {
            fatal("Failed to $action", e)
            null
        }

    override fun onEject() {
        // логгер узнаёт о выгрузке последним
        info("Ejected!")
    }
}
