package io.github.exterastuff.dexbundle.engine.impl

/** Расчёт зависимостей без обращения к состоянию хоста и загрузки классов. */
internal class DependencyGraph(private val dependencies: Map<String, Collection<String>>) {
    fun affectedBy(ids: Collection<String>): Set<String> {
        val result = LinkedHashSet(ids)

        do {
            val dependents = dependencies
                .filterKeys { it !in result }
                .filterValues { deps -> deps.any { it in result } }
                .keys

            result.addAll(dependents)
        } while (dependents.isNotEmpty())

        return result
    }

    fun order(ids: Collection<String>, onCycle: (List<String>) -> Unit = {}): List<String> {
        val pending = ids.toSet()
        val ordered = LinkedHashSet<String>()
        val visiting = LinkedHashSet<String>()

        fun visit(id: String) {
            if (id !in pending || id in ordered)
                return

            if (!visiting.add(id)) {
                onCycle(visiting.toList() + id)
                return
            }

            dependencies[id]?.forEach(::visit)

            visiting.remove(id)
            ordered.add(id)
        }

        ids.forEach(::visit)

        return ordered.toList()
    }
}
