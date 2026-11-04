package io.github.exterastuff.dexbundle.engine

import io.github.exterastuff.dexbundle.engine.impl.DependencyGraph
import org.junit.Assert.*
import org.junit.Test

class DependencyGraphTest {
    private val graph =
        DependencyGraph(
            mapOf(
                "app" to listOf("left", "right"),
                "left" to listOf("base"),
                "right" to listOf("base"),
                "unrelated" to emptyList(),
            )
        )

    @Test
    fun transitiveDependentsAndUnknownRoots() {
        assertEquals(setOf("base", "left", "right", "app"), graph.affectedBy(listOf("base")))
        assertEquals(setOf("left", "app"), graph.affectedBy(listOf("left")))
        assertEquals(setOf("unknown"), graph.affectedBy(listOf("unknown")))
        assertTrue(graph.affectedBy(emptyList()).isEmpty())
    }

    @Test
    fun topologicalOrderRestrictsItselfToRequestedPlugins() {
        assertEquals(
            listOf("base", "left", "right", "app"),
            graph.order(listOf("app", "left", "right", "base", "app")),
        )

        assertEquals(listOf("app", "unknown"), graph.order(listOf("app", "unknown")))
        assertTrue(graph.order(emptyList()).isEmpty())
    }

    @Test
    fun cyclesTerminateAndReportPath() {
        val cyclic =
            DependencyGraph(mapOf("a" to listOf("b"), "b" to listOf("a"), "self" to listOf("self")))
        val cycles = mutableListOf<List<String>>()

        assertEquals(listOf("b", "a", "self"), cyclic.order(listOf("a", "b", "self"), cycles::add))
        assertEquals(listOf(listOf("a", "b", "a"), listOf("self", "self")), cycles)
        assertEquals(setOf("a", "b"), cyclic.affectedBy(listOf("a")))
    }
}
