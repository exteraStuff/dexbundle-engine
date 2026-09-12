package io.github.exterastuff.dexbundle.engine.eject

import androidx.annotation.AnyThread
import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.util.Logger

object EjectNotifier {
    private data class Listener(val priority: Int, val callback: () -> Unit)

    private val listeners = ArrayList<Listener>()

    @Volatile private var fired = false

    /**
     * Подписывается на выгрузку плагина. Подписка после выгрузки сразу же вызывает [listener], так
     * как уведомлять о ней уже некому.
     *
     * @return отписка
     */
    @AnyThread
    fun subscribe(priority: Int = 0, listener: () -> Unit): () -> Unit {
        val entry = Listener(priority, listener)

        synchronized(this) {
            if (!fired) {
                listeners.add(entry)

                return { synchronized(this) { listeners.remove(entry) } }
            }
        }

        listener()

        return {}
    }

    @AnyThread
    fun fire() {
        val pending =
            synchronized(this) {
                if (fired) return

                fired = true

                listeners.sortedBy { it.priority }.also { listeners.clear() }
            }

        // упавший слушатель не должен мешать остальным отпустить плагин
        for (listener in pending) try {
            listener.callback()
        } catch (e: Throwable) {
            Logger.warn("Eject listener failed: ${e.format()}")
        }
    }

    interface Delegate {
        @AnyThread fun onEject()
    }

    fun subscribe(delegate: Delegate, priority: Int = 0): () -> Unit =
        subscribe(priority, delegate::onEject)
}
