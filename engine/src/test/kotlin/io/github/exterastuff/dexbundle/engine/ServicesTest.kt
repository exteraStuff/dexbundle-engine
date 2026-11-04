package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.impl.PluginServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesTest {
    private fun services(): PluginServices =
        PluginServices::class
            .java
            .getDeclaredConstructor(Map::class.java, ClassLoader::class.java)
            .apply { isAccessible = true }
            .newInstance(mapOf("g:a" to "1.10"), javaClass.classLoader)

    @Test
    fun versionRequirements() {
        val services = services()

        assertTrue(services.provides("g:a", "1.2"))
        assertTrue(services.provides("g:a", "1.10"))
        assertFalse(services.provides("g:a", "2"))
        assertFalse(services.provides("missing", "1"))
    }

    @Test
    fun registrationOwnershipAndCleanup() {
        val services = services()
        val runnable = Runnable {}
        val other = AutoCloseable {}

        assertNull(services.getService(Runnable::class.java))

        services.registerService("a", Runnable::class.java, runnable)
        services.registerService("b", AutoCloseable::class.java, other)

        assertSame(runnable, services.getService(Runnable::class.java))
        assertThrows(IllegalStateException::class.java) {
            services.registerService("b", Runnable::class.java, Runnable {})
        }

        services.unregisterServices("unknown")

        assertSame(runnable, services.getService(Runnable::class.java))

        services.unregisterServices("a")
        services.unregisterServices("a")

        assertNull(services.getService(Runnable::class.java))
        assertSame(other, services.getService(AutoCloseable::class.java))

        services.registerService("b", Runnable::class.java, runnable)
        services.unregisterServices("b")

        assertNull(services.getService(Runnable::class.java))
        assertNull(services.getService(AutoCloseable::class.java))
    }

    @Test
    fun rejectsWrongImplementationAtRuntime() {
        @Suppress("UNCHECKED_CAST") val type = Runnable::class.java as Class<Any>
        assertThrows(ClassCastException::class.java) {
            services().registerService("bad", type, Any())
        }
    }

    interface ExampleService {
        fun value(): String
    }

    @Test
    fun rejectsSameNameServiceFromAnotherClassLoader() {
        val services = services()

        services.registerService(
            "owner",
            ExampleService::class.java,
            object : ExampleService {
                override fun value() = "value"
            },
        )

        val original = ExampleService::class.java

        val bytes = original
            .getResourceAsStream("/" + original.name.replace('.', '/') + ".class")!!
            .use { it.readBytes() }

        val isolated =
            object : ClassLoader(original.classLoader) {
                fun copy(): Class<*> = defineClass(original.name, bytes, 0, bytes.size)
            }
                .copy()

        assertEquals(original.name, isolated.name)
        assertNotSame(original, isolated)
        assertThrows(ClassCastException::class.java) { services.getService(isolated) }
    }
}
