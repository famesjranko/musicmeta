package com.landofoz.musicmeta.engine

/**
 * Throwaway measurement hook for the non-Latin title-side probe. Records, per candidate-selection
 * site, how many candidates cleared the artist side and how many cleared the title side, so a
 * rejection can be attributed to one or the other. Never on outside a probe.
 */
internal object ProbeTrace {

    /** One selection site's pool, sifted. [titleOk] is -1 where the site has no title side. */
    data class Sift(
        val site: String,
        val poolSize: Int,
        val artistOk: Int,
        val titleOk: Int,
        val accepted: Int,
        val names: List<String> = emptyList(),
    )

    data class Pick(val site: String, val artistName: String)

    @Volatile
    var enabled: Boolean = false

    val sifts: MutableList<Sift> = mutableListOf()
    val picks: MutableList<Pick> = mutableListOf()

    fun sift(
        site: String,
        poolSize: Int,
        artistOk: Int,
        titleOk: Int,
        accepted: Int,
        names: List<String> = emptyList(),
    ) {
        if (!enabled) return
        sifts += Sift(site, poolSize, artistOk, titleOk, accepted, names.take(5))
    }

    fun picked(site: String, artistName: String) {
        if (!enabled) return
        picks += Pick(site, artistName)
    }

    fun reset() {
        sifts.clear()
        picks.clear()
    }
}
