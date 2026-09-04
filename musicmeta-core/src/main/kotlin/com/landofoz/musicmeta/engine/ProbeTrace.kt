package com.landofoz.musicmeta.engine

/**
 * Throwaway measurement hook for the non-Latin coverage A/B probe. Off unless a probe turns it on,
 * and identical on every arm so the arms differ only in the property under test.
 */
internal object ProbeTrace {

    data class Pick(val site: String, val artistName: String, val detail: String)

    data class NameCompare(val expected: String, val candidate: String, val quality: Int)

    @Volatile
    var enabled: Boolean = false

    /** The alias pool candidate matching may accept a name under; empty outside an arm that uses one. */
    @Volatile
    var aliasPool: List<AlternativeName> = emptyList()

    /** The tier each alias-only acceptance was granted at, in the order they were granted. */
    val aliasTiers: MutableList<Pair<String, NameMatchTier>> = mutableListOf()

    val picks: MutableList<Pick> = mutableListOf()
    val compares: MutableList<NameCompare> = mutableListOf()
    val titles: MutableList<Triple<String, String, Boolean>> = mutableListOf()

    fun reset() {
        aliasTiers.clear()
        picks.clear()
        compares.clear()
        titles.clear()
    }

    fun picked(site: String, artistName: String, detail: String) {
        if (enabled) picks += Pick(site, artistName, detail)
    }

    fun compared(expected: String, candidate: String, quality: Int) {
        if (enabled) compares += NameCompare(expected, candidate, quality)
    }

    fun titleCompared(requested: String, candidate: String, equivalent: Boolean) {
        if (enabled) titles += Triple(requested, candidate, equivalent)
    }
}

/**
 * Runs [body] with [pool] as the alias pool candidate matching may accept a name under. The control
 * arm has no pool: the names the request carries are the only ones a candidate can match.
 */
internal suspend fun <T> withAliasPool(pool: List<AlternativeName>, body: suspend () -> T): T {
    ProbeTrace.aliasPool = pool
    return try {
        body()
    } finally {
        ProbeTrace.aliasPool = emptyList()
    }
}
