package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AttributionRequirement
import com.landofoz.musicmeta.ProviderPolicies
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A played preview owes its provider a notice, and this page states each one once, in the credits
 * footer, rather than beside the button that played it. Deezer's is carried by `attribution.js`
 * (`standingNotices`, pinned in `attribution.test.js`); Apple's is carried by the policy snapshot
 * the same footer renders, which is what this pins — nothing in this repo writes those words, so a
 * snapshot that stopped calling the attribution owed would drop the only place the page says it.
 */
class PreviewNoticeCoverageTest {

    /** The levels `index.js` renders a notice for; anything else leaves the footer silent. */
    private val owed = setOf(AttributionRequirement.REQUIRED, AttributionRequirement.DEPENDS_ON_DATA)

    @Test fun `Apple's courtesy attribution reaches the footer through the policy snapshot`() {
        // Given - the shipped policies, which are where the footer's iTunes notice comes from
        val policies = ProviderPolicies.all

        // When - reading the entry for iTunes, whose previews this demo plays
        val policy = policies["itunes"]

        // Then - it exists, its attribution is at a level the footer renders, and it carries words
        assertNotNull("no shipped policy for itunes", policy)
        assertTrue(
            "itunes attribution ${policy!!.attribution} is not one the footer renders",
            policy.attribution in owed,
        )
        val notice = policy.attributionNotice
        assertNotNull("itunes carries no attributionNotice for the footer to state", notice)
        assertTrue("itunes notice does not credit iTunes: $notice", notice!!.contains("iTunes"))
    }
}
