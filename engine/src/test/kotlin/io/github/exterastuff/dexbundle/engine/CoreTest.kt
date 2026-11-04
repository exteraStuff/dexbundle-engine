package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.eject.EjectNotifier
import io.github.exterastuff.dexbundle.engine.extension.format
import io.github.exterastuff.dexbundle.engine.util.addInt
import io.github.exterastuff.dexbundle.engine.util.cloneFields
import io.github.exterastuff.dexbundle.engine.util.compareVersions
import io.github.exterastuff.dexbundle.engine.util.getAccessibleFields
import io.github.exterastuff.dexbundle.engine.util.getAs
import io.github.exterastuff.dexbundle.engine.util.getAsUnchecked
import io.github.exterastuff.dexbundle.engine.util.getField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun versions() {
        val cases =
            listOf(
                Triple("1.2", "1.10", -1),
                Triple("1", "1.0", -1),
                Triple("1.0", "1.0-alpha", 1),
                Triple("1.0-alpha", "1.0-beta", -1),
                Triple("01.002", "1.2", 0),
                Triple("1-2_3+4", "1.2.3.4", 0),
                Triple("", "alpha", 1),
                Triple("", "0", -1),
                Triple("9223372036854775806", "9223372036854775807", -1),
                Triple("1.alpha.2", "1.alpha.10", -1),
                Triple("1", "1", 0),
            )

        for ((a, b, expected) in cases) {
            assertEquals("$a vs $b", expected, compareVersions(a, b).compareTo(0))
            assertEquals("$b vs $a", -expected, compareVersions(b, a).compareTo(0))
        }

        val ordered = listOf("1", "1.0", "1.1", "2")

        for (i in ordered.indices) {
            for (j in i + 1 until ordered.size) {
                assertTrue(compareVersions(ordered[i], ordered[j]) < 0)
            }
        }
    }

    private open class Parent {
        private val inherited = "parent"
    }

    private class Fields(var number: Int, val reference: Any?) : Parent() {
        companion object {
            @JvmField
            var staticField = 10
        }
    }

    @Test
    fun reflectionCopiesInheritedPrivateFieldsAndPreservesReferences() {
        val marker = Any()
        val source = Fields(7, marker)
        val target = Fields(0, null)
        val fields = getAccessibleFields(Fields::class.java)

        assertEquals(setOf("number", "reference", "inherited"), fields.map { it.name }.toSet())
        cloneFields(source, target, fields)

        assertEquals(7, target.number)
        assertSame(marker, target.reference)
        assertEquals("parent", getField(Parent::class.java, "inherited").get(target))

        val number = getField(Fields::class.java, "number")
        number.addInt(target, -3)

        assertEquals(4, number.getAsUnchecked<Int>(target))
        assertNull(number.getAs<String>(target))
        assertTrue(getAccessibleFields(Any::class.java).isEmpty())
        assertThrows(NoSuchFieldException::class.java) { getField(Fields::class.java, "missing") }
    }

    @Test
    fun throwableIncludesAllCausesAndFrames() {
        val cause = IllegalArgumentException("root").apply { stackTrace = emptyArray() }

        val error = IllegalStateException("outer", cause)
            .apply { stackTrace = arrayOf(StackTraceElement("Example", "run", "Example.kt", 42)) }

        assertEquals(
            "java.lang.IllegalStateException: outer\nExample.run(Example.kt:42)\nCaused by: java.lang.IllegalArgumentException: root",
            error.format(),
        )

        assertEquals("java.lang.IllegalArgumentException: root", cause.format())
    }

    // Уведомитель — одноразовый singleton, поэтому проверяем весь жизненный цикл одним тестом.
    @Test
    fun ejectOrdersListenersHandlesFailureAndReentrancy() {
        val calls = mutableListOf<String>()

        val cancel = EjectNotifier.subscribe { calls += "cancelled" }

        cancel()
        cancel()

        EjectNotifier.subscribe(10) {
            calls += "last"
        }

        EjectNotifier.subscribe(-10) {
            calls += "first"
            EjectNotifier.fire()
        }

        EjectNotifier.subscribe {
            calls += "failed"
            error("listener failure")
        }

        EjectNotifier.subscribe(
            object : EjectNotifier.Delegate {
                override fun onEject() {
                    calls += "delegate"
                }
            }
        )

        EjectNotifier.fire()

        EjectNotifier.fire()

        EjectNotifier.subscribe { calls += "late" }()

        assertEquals(listOf("first", "failed", "delegate", "last", "late"), calls)
    }
}
