package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AttributionRequirement
import com.landofoz.musicmeta.ProviderPolicies
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The footer renders the policy notice; this does not verify Apple's nearby badge conditions. */
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
