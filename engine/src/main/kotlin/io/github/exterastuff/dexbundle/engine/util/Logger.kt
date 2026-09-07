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

    // distinguishes log lines of plugin instances loaded from different class loaders
    private val ID = ThreadLocalRandom.current()
        .nextInt()
        .toHexString(HexFormat {
            upperCase = true

            number {
                minLength = 4
            }
        })
        .take(4)
        .let { "${Plugin.ID}[$it]" }

    @Volatile
    private var suppressFatal = false

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

    fun fatal(message: String, exception: Throwable, preventEject: Boolean = false) {
        Log.wtf(ID, message)
        Log.wtf(ID, exception.format())

        if (!suppressFatal && !preventEject)
            Plugin.eject()
    }

    fun tryOrFatal(action: String, block: () -> Unit): Unit? =
        try {
            block()
        } catch (e: Throwable) {
            fatal("Failed to $action", e)
            null
        }

    override fun onEject() {
        suppressFatal = true

        // logger is notified about eject last
        info("Ejected!")
    }
}
