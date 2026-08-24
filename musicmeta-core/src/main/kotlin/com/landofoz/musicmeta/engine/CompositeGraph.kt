package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentType

/**
 * Refuses a composite dependency graph that cycles back on itself, a type depending on itself
 * included.
 *
 * A cycle has no resolution order: every type on it waits for another type on it, so under the
 * await-driven fan-out nothing on the cycle can ever settle and the run reaches its deadline with
 * those types unanswered. Refusing at construction converts that into a message naming the loop,
 * which is the one form a consumer can act on — `CompositeSynthesizer` is a public extension point
 * and the cycle is in their code, not ours.
 *
 * Depth-first with an explicit path so the message names every type on the cycle, not only the one
 * where the walk closed it. The first cycle found is the one reported; a graph with two is fixed
 * one at a time.
 *
 * @throws IllegalArgumentException naming every type on the first cycle found.
 */
internal fun requireAcyclic(dependencies: Map<EnrichmentType, Set<EnrichmentType>>) {
    val settled = mutableSetOf<EnrichmentType>()
    val onPath = mutableSetOf<EnrichmentType>()

    fun visit(type: EnrichmentType, path: List<EnrichmentType>) {
        if (type in settled) return
        if (type in onPath) {
            val cycle = path.subList(path.indexOf(type), path.size) + type
            throw IllegalArgumentException(
                "Composite dependency cycle: ${cycle.joinToString(" -> ") { it.name }}. " +
                    "A CompositeSynthesizer cannot depend, directly or transitively, on its own type.",
            )
        }
        val next = dependencies[type] ?: return
        onPath.add(type)
        for (dependency in next) visit(dependency, path + type)
        onPath.remove(type)
        settled.add(type)
    }

    for (type in dependencies.keys) visit(type, emptyList())
}
