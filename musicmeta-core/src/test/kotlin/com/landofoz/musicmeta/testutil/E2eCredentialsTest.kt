package com.landofoz.musicmeta.testutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class E2eCredentialsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an absolute pointer finds the main file`() {
        // Given - a temporary worktree has an absolute gitdir pointer and only its main checkout has credentials.
        val main = tmp.newFolder("main")
        val worktree = tmp.newFolder("worktree")
        File(worktree, ".git").writeText("gitdir: ${File(main, ".git/worktrees/wt").absolutePath}\n")
        writeSecrets(File(main, "secrets.properties"), allValues("main"))

        // When - resolving credentials with empty fake property and environment lookups.
        val credentials = resolve(worktree)

        // Then - the resolved credentials equal the main file's fake values.
        assertEquals(allValues("main"), credentials)
    }

    @Test
    fun `a relative pointer is rooted at the checkout`() {
        // Given - a temporary worktree has a relative gitdir pointer to a main checkout with fake credentials.
        val parent = tmp.newFolder("outside-repository")
        val main = File(parent, "main").apply { mkdirs() }
        val worktree = File(parent, "worktree").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: ../main/.git/worktrees/wt\n")
        writeSecrets(File(main, "secrets.properties"), allValues("relative"))

        // When - resolving credentials from that worktree root with empty fake external lookups.
        val credentials = resolve(worktree)

        // Then - the main checkout supplies the credentials independently of the process working directory.
        assertEquals(allValues("relative"), credentials)
    }

    @Test
    fun `per-key precedence includes every source`() {
        // Given - each credential has distinct fake candidates across system properties, local file, main file, and environment.
        val main = tmp.newFolder("main")
        val worktree = tmp.newFolder("worktree")
        File(worktree, ".git").writeText("gitdir: ${File(main, ".git/worktrees/wt").absolutePath}\n")
        writeSecrets(
            File(main, "secrets.properties"),
            mapOf(
                "lastfm.apikey" to "main-lastfm",
                "fanarttv.apikey" to "main-fanart",
                "discogs.token" to "main-discogs",
            ),
        )
        writeSecrets(
            File(worktree, "secrets.properties"),
            mapOf(
                "lastfm.apikey" to "local-lastfm",
                "fanarttv.apikey" to "local-fanart",
            ),
        )
        val properties = mapOf("lastfm.apikey" to "property-lastfm")
        val environment = allEnvironmentValues("environment")

        // When - resolving a table of present and absent candidates for each credential.
        val mixed = resolve(worktree, properties, environment)
        val localWins = resolve(
            worktree,
            environment = environment,
        )
        val mainOnlyRoot = tmp.newFolder("main-only")
        File(mainOnlyRoot, ".git").writeText("gitdir: ${File(main, ".git/worktrees/wt").absolutePath}\n")
        val mainWinsWhenLocalIsAbsent = resolve(
            mainOnlyRoot,
            properties = mapOf("fanarttv.apikey" to ""),
            environment = environment,
        )
        val environmentOnlyRoot = tmp.newFolder("environment-only")
        val environmentOnly = resolve(environmentOnlyRoot, environment = environment)
        val absent = resolve(tmp.newFolder("absent"))

        // Then - each key independently selects the highest-priority available source with the exact environment name.
        assertEquals(
            mapOf(
                "lastfm.apikey" to "property-lastfm",
                "fanarttv.apikey" to "local-fanart",
                "discogs.token" to "main-discogs",
                "listenbrainz.token" to "environment-listenbrainz",
            ),
            mixed,
        )
        assertEquals("local-lastfm", localWins["lastfm.apikey"])
        assertEquals("main-fanart", mainWinsWhenLocalIsAbsent["fanarttv.apikey"])
        assertEquals(allValues("environment"), environmentOnly)
        assertEquals(allValues(""), absent)
    }

    @Test
    fun `blank candidates fall through per key`() {
        // Given - higher-priority candidates are empty or whitespace-only and lower-priority sources contain fake values.
        val main = tmp.newFolder("main")
        val worktree = tmp.newFolder("worktree")
        File(worktree, ".git").writeText("gitdir: ${File(main, ".git/worktrees/wt").absolutePath}\n")
        writeSecrets(
            File(main, "secrets.properties"),
            mapOf(
                "fanarttv.apikey" to "main-fanart",
                "discogs.token" to "   ",
                "listenbrainz.token" to "\t",
            ),
        )
        File(worktree, "secrets.properties").writeText(
            "lastfm.apikey=local-lastfm\n" +
                "fanarttv.apikey= \t \n" +
                "discogs.token=\n" +
                "listenbrainz.token= \n" +
                "# listenbrainz.token=commented-template\n",
        )
        val properties = mapOf("lastfm.apikey" to "", "discogs.token" to "")
        val environment = mapOf(
            "LASTFM_API_KEY" to "env-lastfm",
            "FANARTTV_API_KEY" to "env-fanart",
            "DISCOGS_TOKEN" to "env-discogs",
            "LISTENBRAINZ_TOKEN" to " ",
        )

        // When - resolving each credential across the blank-candidate table.
        val credentials = resolve(worktree, properties, environment)

        // Then - blank candidates never mask a lower source and all-blank candidates remain missing.
        assertEquals("local-lastfm", credentials["lastfm.apikey"])
        assertEquals("main-fanart", credentials["fanarttv.apikey"])
        assertEquals("env-discogs", credentials["discogs.token"])
        assertEquals("", credentials["listenbrainz.token"])
    }

    @Test
    fun `strict missing-key errors list names only`() {
        // Given - fake resolved credentials include present sentinels and missing values for the four known key pairs.
        val allMissing = emptyMap<String, String>()
        val oneMissing = mapOf(
            "lastfm.apikey" to "lastfm-sentinel",
            "fanarttv.apikey" to "fanart-sentinel",
            "discogs.token" to "discogs-sentinel",
        )
        val mixedMissing = mapOf(
            "lastfm.apikey" to "lastfm-sentinel",
            "fanarttv.apikey" to " ",
            "discogs.token" to "discogs-sentinel",
            "listenbrainz.token" to "listenbrainz-sentinel",
        )
        val allPresent = allValues("present")

        // When - requiring all credentials for the preflight.
        val allMissingFailure = runCatching { E2eCredentials.requireAll(allMissing) }.exceptionOrNull()
        val oneMissingFailure = runCatching { E2eCredentials.requireAll(oneMissing) }.exceptionOrNull()
        val mixedMissingFailure = runCatching { E2eCredentials.requireAll(mixedMissing) }.exceptionOrNull()
        val allPresentFailure = runCatching { E2eCredentials.requireAll(allPresent) }.exceptionOrNull()

        // Then - one failure names exactly the missing property and environment pairs without any sentinel value.
        val allMissingMessage = "Missing E2E credentials: " +
            "lastfm.apikey (LASTFM_API_KEY), fanarttv.apikey (FANARTTV_API_KEY), " +
            "discogs.token (DISCOGS_TOKEN), listenbrainz.token (LISTENBRAINZ_TOKEN). " +
            "Configure the properties in secrets.properties or set the corresponding environment variables."
        assertTrue(allMissingFailure is IllegalStateException)
        assertEquals(allMissingMessage, allMissingFailure?.message)
        assertTrue(oneMissingFailure is IllegalStateException)
        assertEquals(
            "Missing E2E credentials: listenbrainz.token (LISTENBRAINZ_TOKEN). " +
                "Configure the properties in secrets.properties or set the corresponding environment variables.",
            oneMissingFailure?.message,
        )
        assertTrue(mixedMissingFailure is IllegalStateException)
        assertEquals(
            "Missing E2E credentials: fanarttv.apikey (FANARTTV_API_KEY). " +
                "Configure the properties in secrets.properties or set the corresponding environment variables.",
            mixedMissingFailure?.message,
        )
        assertFalse(allPresentFailure is Throwable)
        listOf("lastfm-sentinel", "fanart-sentinel", "discogs-sentinel", "listenbrainz-sentinel").forEach { sentinel ->
            assertFalse(allMissingMessage.contains(sentinel))
        }
    }

    private fun resolve(
        root: File,
        properties: Map<String, String?> = emptyMap(),
        environment: Map<String, String?> = emptyMap(),
    ): Map<String, String> = E2eCredentials.resolve(root, properties::get, environment::get)

    private fun writeSecrets(file: File, values: Map<String, String>) {
        file.writeText(values.entries.joinToString("\n") { (key, value) -> "$key=$value" })
    }

    private fun allValues(prefix: String): Map<String, String> = E2E_CREDENTIAL_SPECS.associate { spec ->
        spec.property to if (prefix.isEmpty()) "" else "$prefix-${spec.property.substringBefore('.')}"
    }

    private fun allEnvironmentValues(prefix: String): Map<String, String> = E2E_CREDENTIAL_SPECS.associate { spec ->
        spec.environment to if (prefix.isEmpty()) "" else "$prefix-${spec.property.substringBefore('.')}"
    }
}
