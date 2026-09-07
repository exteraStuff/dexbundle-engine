package io.github.exterastuff.dexbundle.engine.util

import androidx.annotation.AnyThread
import org.telegram.messenger.AndroidUtilities

@AnyThread
inline fun <R> runOnMainThread(crossinline block: () -> R) {
    AndroidUtilities.runOnUIThread { block.invoke() }
}
