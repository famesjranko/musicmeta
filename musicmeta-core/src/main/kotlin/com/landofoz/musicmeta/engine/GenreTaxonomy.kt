package com.landofoz.musicmeta.engine

/**
 * Static genre taxonomy covering ~70 relationships across 12 genre families:
 * rock, pop, hip-hop, electronic, jazz, metal, folk, country, classical, r&b/soul, punk, blues.
 *
 * Each entry maps a normalized genre name to its related genres with relationship type and weight.
 * Weights: sibling=0.9, child=0.8, parent=0.7
 */
internal data class TaxonomyEntry(val related: String, val relationship: String, val weight: Float)

internal val GENRE_TAXONOMY: Map<String, List<TaxonomyEntry>> = buildMap {
    // Rock family
    put("rock", listOf(
        TaxonomyEntry("alternative rock", "child", 0.8f),
        TaxonomyEntry("hard rock", "child", 0.8f),
        TaxonomyEntry("classic rock", "child", 0.8f),
        TaxonomyEntry("indie rock", "child", 0.8f),
        TaxonomyEntry("blues", "parent", 0.7f),
        TaxonomyEntry("folk", "parent", 0.7f),
    ))
    put("alternative rock", listOf(
        TaxonomyEntry("rock", "parent", 0.7f),
        TaxonomyEntry("indie rock", "sibling", 0.9f),
        TaxonomyEntry("grunge", "sibling", 0.9f),
        TaxonomyEntry("post-punk", "sibling", 0.9f),
    ))
    put("indie rock", listOf(
        TaxonomyEntry("alternative rock", "sibling", 0.9f),
        TaxonomyEntry("indie pop", "sibling", 0.9f),
        TaxonomyEntry("rock", "parent", 0.7f),
    ))
    put("hard rock", listOf(
        TaxonomyEntry("rock", "parent", 0.7f),
        TaxonomyEntry("metal", "sibling", 0.9f),
        TaxonomyEntry("classic rock", "sibling", 0.9f),
    ))
    put("classic rock", listOf(
        TaxonomyEntry("rock", "parent", 0.7f),
        TaxonomyEntry("hard rock", "sibling", 0.9f),
        TaxonomyEntry("blues rock", "sibling", 0.9f),
    ))
    put("grunge", listOf(
        TaxonomyEntry("alternative rock", "parent", 0.7f),
        TaxonomyEntry("post-punk", "sibling", 0.9f),
        TaxonomyEntry("heavy metal", "sibling", 0.9f),
    ))
    put("post-punk", listOf(
        TaxonomyEntry("punk", "parent", 0.7f),
        TaxonomyEntry("alternative rock", "sibling", 0.9f),
        TaxonomyEntry("new wave", "sibling", 0.9f),
    ))
    put("post-rock", listOf(
        TaxonomyEntry("rock", "parent", 0.7f),
        TaxonomyEntry("math rock", "sibling", 0.9f),
        TaxonomyEntry("ambient", "sibling", 0.9f),
    ))
    put("math rock", listOf(
        TaxonomyEntry("post-rock", "sibling", 0.9f),
        TaxonomyEntry("progressive rock", "sibling", 0.9f),
        TaxonomyEntry("rock", "parent", 0.7f),
    ))
    put("progressive rock", listOf(
        TaxonomyEntry("rock", "parent", 0.7f),
        TaxonomyEntry("art rock", "sibling", 0.9f),
        TaxonomyEntry("math rock", "sibling", 0.9f),
    ))
    put("art rock", listOf(
        TaxonomyEntry("rock", "parent", 0.7f),
        TaxonomyEntry("progressive rock", "sibling", 0.9f),
        TaxonomyEntry("experimental", "sibling", 0.9f),
    ))
    // Pop family
    put("pop", listOf(
        TaxonomyEntry("indie pop", "child", 0.8f),
        TaxonomyEntry("synthpop", "child", 0.8f),
        TaxonomyEntry("dance pop", "child", 0.8f),
        TaxonomyEntry("pop rock", "child", 0.8f),
    ))
    put("indie pop", listOf(
        TaxonomyEntry("pop", "parent", 0.7f),
        TaxonomyEntry("indie rock", "sibling", 0.9f),
        TaxonomyEntry("dream pop", "sibling", 0.9f),
    ))
    put("synthpop", listOf(
        TaxonomyEntry("pop", "parent", 0.7f),
        TaxonomyEntry("electronic", "sibling", 0.9f),
        TaxonomyEntry("new wave", "sibling", 0.9f),
    ))
    put("dream pop", listOf(
        TaxonomyEntry("indie pop", "sibling", 0.9f),
        TaxonomyEntry("shoegaze", "sibling", 0.9f),
        TaxonomyEntry("pop", "parent", 0.7f),
    ))
    put("dance pop", listOf(
        TaxonomyEntry("pop", "parent", 0.7f),
        TaxonomyEntry("electronic", "sibling", 0.9f),
        TaxonomyEntry("pop rock", "sibling", 0.9f),
    ))
    // Hip-hop family
    put("hip-hop", listOf(
        TaxonomyEntry("rap", "child", 0.8f),
        TaxonomyEntry("trap", "child", 0.8f),
        TaxonomyEntry("boom bap", "child", 0.8f),
        TaxonomyEntry("r&b", "sibling", 0.9f),
        TaxonomyEntry("soul", "sibling", 0.9f),
    ))
    put("rap", listOf(
        TaxonomyEntry("hip-hop", "parent", 0.7f),
        TaxonomyEntry("trap", "sibling", 0.9f),
        TaxonomyEntry("boom bap", "sibling", 0.9f),
    ))
    put("trap", listOf(
        TaxonomyEntry("hip-hop", "parent", 0.7f),
        TaxonomyEntry("rap", "sibling", 0.9f),
        TaxonomyEntry("electronic", "sibling", 0.9f),
    ))
    put("boom bap", listOf(
        TaxonomyEntry("hip-hop", "parent", 0.7f),
        TaxonomyEntry("rap", "sibling", 0.9f),
        TaxonomyEntry("jazz", "sibling", 0.9f),
    ))
    // Electronic family
    put("electronic", listOf(
        TaxonomyEntry("house", "child", 0.8f),
        TaxonomyEntry("techno", "child", 0.8f),
        TaxonomyEntry("ambient", "child", 0.8f),
        TaxonomyEntry("synthpop", "child", 0.8f),
        TaxonomyEntry("drum and bass", "child", 0.8f),
    ))
    put("house", listOf(
        TaxonomyEntry("electronic", "parent", 0.7f),
        TaxonomyEntry("techno", "sibling", 0.9f),
        TaxonomyEntry("dance", "sibling", 0.9f),
    ))
    put("techno", listOf(
        TaxonomyEntry("electronic", "parent", 0.7f),
        TaxonomyEntry("house", "sibling", 0.9f),
        TaxonomyEntry("industrial", "sibling", 0.9f),
    ))
    put("ambient", listOf(
        TaxonomyEntry("electronic", "parent", 0.7f),
        TaxonomyEntry("post-rock", "sibling", 0.9f),
        TaxonomyEntry("experimental", "sibling", 0.9f),
    ))
    put("drum and bass", listOf(
        TaxonomyEntry("electronic", "parent", 0.7f),
        TaxonomyEntry("jungle", "sibling", 0.9f),
        TaxonomyEntry("breakbeat", "sibling", 0.9f),
    ))
    put("industrial", listOf(
        TaxonomyEntry("electronic", "parent", 0.7f),
        TaxonomyEntry("techno", "sibling", 0.9f),
        TaxonomyEntry("noise", "sibling", 0.9f),
    ))
    // Jazz family
    put("jazz", listOf(
        TaxonomyEntry("bebop", "child", 0.8f),
        TaxonomyEntry("fusion", "child", 0.8f),
        TaxonomyEntry("smooth jazz", "child", 0.8f),
        TaxonomyEntry("blues", "sibling", 0.9f),
        TaxonomyEntry("soul", "sibling", 0.9f),
    ))
    put("bebop", listOf(
        TaxonomyEntry("jazz", "parent", 0.7f),
        TaxonomyEntry("hard bop", "sibling", 0.9f),
        TaxonomyEntry("cool jazz", "sibling", 0.9f),
    ))
    put("fusion", listOf(
        TaxonomyEntry("jazz", "parent", 0.7f),
        TaxonomyEntry("progressive rock", "sibling", 0.9f),
        TaxonomyEntry("funk", "sibling", 0.9f),
    ))
    put("smooth jazz", listOf(
        TaxonomyEntry("jazz", "parent", 0.7f),
        TaxonomyEntry("r&b", "sibling", 0.9f),
        TaxonomyEntry("soul", "sibling", 0.9f),
    ))
    // Metal family
    put("metal", listOf(
        TaxonomyEntry("heavy metal", "child", 0.8f),
        TaxonomyEntry("thrash metal", "child", 0.8f),
        TaxonomyEntry("doom metal", "child", 0.8f),
        TaxonomyEntry("death metal", "child", 0.8f),
        TaxonomyEntry("hard rock", "parent", 0.7f),
    ))
    put("heavy metal", listOf(
        TaxonomyEntry("metal", "parent", 0.7f),
        TaxonomyEntry("hard rock", "sibling", 0.9f),
        TaxonomyEntry("thrash metal", "sibling", 0.9f),
    ))
    put("thrash metal", listOf(
        TaxonomyEntry("metal", "parent", 0.7f),
        TaxonomyEntry("heavy metal", "sibling", 0.9f),
        TaxonomyEntry("death metal", "sibling", 0.9f),
    ))
    put("doom metal", listOf(
        TaxonomyEntry("metal", "parent", 0.7f),
        TaxonomyEntry("black metal", "sibling", 0.9f),
        TaxonomyEntry("gothic metal", "sibling", 0.9f),
    ))
    put("death metal", listOf(
        TaxonomyEntry("metal", "parent", 0.7f),
        TaxonomyEntry("thrash metal", "sibling", 0.9f),
        TaxonomyEntry("black metal", "sibling", 0.9f),
    ))
    put("black metal", listOf(
        TaxonomyEntry("metal", "parent", 0.7f),
        TaxonomyEntry("death metal", "sibling", 0.9f),
        TaxonomyEntry("doom metal", "sibling", 0.9f),
    ))
    // Folk family
    put("folk", listOf(
        TaxonomyEntry("folk rock", "child", 0.8f),
        TaxonomyEntry("indie folk", "child", 0.8f),
        TaxonomyEntry("country", "sibling", 0.9f),
        TaxonomyEntry("blues", "sibling", 0.9f),
    ))
    put("folk rock", listOf(
        TaxonomyEntry("folk", "parent", 0.7f),
        TaxonomyEntry("indie folk", "sibling", 0.9f),
        TaxonomyEntry("rock", "sibling", 0.9f),
    ))
    put("indie folk", listOf(
        TaxonomyEntry("folk", "parent", 0.7f),
        TaxonomyEntry("folk rock", "sibling", 0.9f),
        TaxonomyEntry("indie pop", "sibling", 0.9f),
    ))
    // Country family
    put("country", listOf(
        TaxonomyEntry("americana", "child", 0.8f),
        TaxonomyEntry("bluegrass", "child", 0.8f),
        TaxonomyEntry("rockabilly", "child", 0.8f),
        TaxonomyEntry("folk", "sibling", 0.9f),
    ))
    put("americana", listOf(
        TaxonomyEntry("country", "parent", 0.7f),
        TaxonomyEntry("folk", "sibling", 0.9f),
        TaxonomyEntry("rock", "sibling", 0.9f),
    ))
    put("bluegrass", listOf(
        TaxonomyEntry("country", "parent", 0.7f),
        TaxonomyEntry("folk", "sibling", 0.9f),
        TaxonomyEntry("americana", "sibling", 0.9f),
    ))
    // Classical family
    put("classical", listOf(
        TaxonomyEntry("baroque", "child", 0.8f),
        TaxonomyEntry("romantic", "child", 0.8f),
        TaxonomyEntry("contemporary classical", "child", 0.8f),
        TaxonomyEntry("opera", "child", 0.8f),
    ))
    put("baroque", listOf(
        TaxonomyEntry("classical", "parent", 0.7f),
        TaxonomyEntry("romantic", "sibling", 0.9f),
    ))
    put("contemporary classical", listOf(
        TaxonomyEntry("classical", "parent", 0.7f),
        TaxonomyEntry("experimental", "sibling", 0.9f),
        TaxonomyEntry("ambient", "sibling", 0.9f),
    ))
    // R&B/Soul family
    put("r&b", listOf(
        TaxonomyEntry("soul", "sibling", 0.9f),
        TaxonomyEntry("hip-hop", "sibling", 0.9f),
        TaxonomyEntry("funk", "sibling", 0.9f),
        TaxonomyEntry("neo soul", "child", 0.8f),
    ))
    put("soul", listOf(
        TaxonomyEntry("r&b", "sibling", 0.9f),
        TaxonomyEntry("jazz", "sibling", 0.9f),
        TaxonomyEntry("funk", "sibling", 0.9f),
        TaxonomyEntry("gospel", "sibling", 0.9f),
    ))
    put("funk", listOf(
        TaxonomyEntry("r&b", "sibling", 0.9f),
        TaxonomyEntry("soul", "sibling", 0.9f),
        TaxonomyEntry("jazz", "sibling", 0.9f),
        TaxonomyEntry("hip-hop", "sibling", 0.9f),
    ))
    put("neo soul", listOf(
        TaxonomyEntry("r&b", "parent", 0.7f),
        TaxonomyEntry("soul", "sibling", 0.9f),
        TaxonomyEntry("hip-hop", "sibling", 0.9f),
    ))
    // Punk family
    put("punk", listOf(
        TaxonomyEntry("hardcore", "child", 0.8f),
        TaxonomyEntry("post-punk", "child", 0.8f),
        TaxonomyEntry("pop punk", "child", 0.8f),
        TaxonomyEntry("rock", "parent", 0.7f),
    ))
    put("hardcore", listOf(
        TaxonomyEntry("punk", "parent", 0.7f),
        TaxonomyEntry("metal", "sibling", 0.9f),
        TaxonomyEntry("post-hardcore", "sibling", 0.9f),
    ))
    put("pop punk", listOf(
        TaxonomyEntry("punk", "parent", 0.7f),
        TaxonomyEntry("alternative rock", "sibling", 0.9f),
        TaxonomyEntry("indie rock", "sibling", 0.9f),
    ))
    put("post-hardcore", listOf(
        TaxonomyEntry("hardcore", "parent", 0.7f),
        TaxonomyEntry("post-punk", "sibling", 0.9f),
        TaxonomyEntry("emo", "sibling", 0.9f),
    ))
    // Blues family
    put("blues", listOf(
        TaxonomyEntry("blues rock", "child", 0.8f),
        TaxonomyEntry("rhythm and blues", "child", 0.8f),
        TaxonomyEntry("rock", "sibling", 0.9f),
        TaxonomyEntry("jazz", "sibling", 0.9f),
        TaxonomyEntry("soul", "sibling", 0.9f),
    ))
    put("blues rock", listOf(
        TaxonomyEntry("blues", "parent", 0.7f),
        TaxonomyEntry("rock", "sibling", 0.9f),
        TaxonomyEntry("hard rock", "sibling", 0.9f),
    ))
    put("rhythm and blues", listOf(
        TaxonomyEntry("blues", "parent", 0.7f),
        TaxonomyEntry("r&b", "sibling", 0.9f),
        TaxonomyEntry("soul", "sibling", 0.9f),
    ))
}
