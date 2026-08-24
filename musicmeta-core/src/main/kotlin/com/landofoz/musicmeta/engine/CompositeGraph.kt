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

/**
 * Refuses a type that carries both a [CompositeSynthesizer] and a [ResultMerger].
 *
 * A composite has no provider chain to collect from, so the merger can never run: the type is
 * synthesized and the registration is dead. Nothing about that is visible to the caller who wrote
 * it — the merged answer they configured simply never appears — so it is refused where it can still
 * be fixed. Registration order does not rescue it either; before this, composite won because the
 * classification happened to test for it first.
 *
 * A default registration counts: `DEFAULT_MERGERS` and `DEFAULT_SYNTHESIZERS` share no key today,
 * so adding a synthesizer for `GENRE` collides with `GenreMerger` exactly as a caller's own merger
 * would, and is exactly as dead.
 *
 * @throws IllegalArgumentException naming every type that carries both.
 */
internal fun requireDisjointRoles(
    compositeTypes: Set<EnrichmentType>,
    mergeableTypes: Set<EnrichmentType>,
) {
    val both = compositeTypes intersect mergeableTypes
    require(both.isEmpty()) {
        "Registered as both a composite and a mergeable: ${both.joinToString(", ") { it.name }}. " +
            "A composite type is synthesized from its dependencies and never collected from a " +
            "provider chain, so its ResultMerger could never run. Register one or the other."
    }
}
