package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.IdentityResolution

/** An [IdentityResolution] for tests that do not exercise identity resolution itself. */
val NOT_REQUIRED_IDENTITY: IdentityResolution =
    IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED)
