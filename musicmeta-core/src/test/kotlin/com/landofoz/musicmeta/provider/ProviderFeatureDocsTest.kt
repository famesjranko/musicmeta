package com.landofoz.musicmeta.provider

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * `## What We Extract` in each `docs/providers/<name>.md`, against the capabilities that package
 * actually declares. Both directions fail: a row for a capability the code dropped is as wrong as a
 * missing row for one it gained. This is the only checkable claim in a feature doc — the prose
 * around the table is not checked and should not grow a checker.
 *
 * It reads the live `capabilities` off registered providers rather than parsing Kotlin, so a
 * capability added inside an `if`, or by a second provider class in the same package, counts like
 * any other. Every key and token is supplied because ListenBrainz registers `ARTIST_RADIO_DISCOVERY`
 * only when it has a token, and a doc describes the package rather than one configuration of it.
 *
 * The three-way set equality is the part not to weaken. Reading runtime providers means a provider
 * dropped from `withDefaultProviders()`, a package with no doc, or a doc with no package would each
 * otherwise leave this green having compared nothing.
 */
class ProviderFeatureDocsTest {

    @Test fun `every provider doc lists exactly the capabilities its package declares`() {
        // Given -- every default provider registered, with every key and token supplied
        val engine = EnrichmentEngine.Builder()
            .httpClient(FakeHttpClient())
            .apiKeys(
                ApiKeyConfig(
                    lastFmKey = "test-lastfm-key",
                    fanartTvProjectKey = "test-fanarttv-key",
                    discogsPersonalToken = "test-discogs-token",
                    listenBrainzToken = "test-listenbrainz-token",
                ),
            )
            .withDefaultProviders()
            .build()

        // When -- grouping every declared capability by the package its provider lives in
        val declared = engine.getProviders()
            .groupBy { it.id.substringBefore('-') } // deezer-similar-albums -> deezer
            .mapValues { (_, infos) -> infos.flatMap { it.capabilities }.map { it.type.name }.toSet() }

        // Then -- packages on disk, feature docs and registered providers are one and the same set
        val packages = dirs(File(repoRoot, PROVIDER_DIR)) { it.isDirectory }
        val docs = dirs(File(repoRoot, DOC_DIR)) { it.extension == "md" && it.nameWithoutExtension != "README" }
            .map { it.removeSuffix(".md") }.toSet()
        assertEquals("a provider package with no feature doc, or a doc with no package", packages, docs)
        assertEquals("a registered provider id no longer names its package", packages, declared.keys)

        // ...and each doc's table matches the capabilities of its own package, exactly
        for (name in packages.sorted()) {
            assertEquals("$DOC_DIR/$name.md", declared.getValue(name), documented(File(repoRoot, "$DOC_DIR/$name.md")))
        }
    }

    /** Names of the entries matching [keep], or a failure — an empty directory is never an answer. */
    private fun dirs(parent: File, keep: (File) -> Boolean): Set<String> {
        val names = parent.listFiles().orEmpty().filter(keep).map { it.name }.toSet()
        check(names.isNotEmpty()) { "$parent holds nothing to check — this test would compare empty sets" }
        return names
    }

    /** The `EnrichmentType` names in one doc's `## What We Extract` table. */
    private fun documented(doc: File): Set<String> {
        val lines = doc.readLines()
        val heading = lines.indexOfFirst { it.trim() == HEADING }
        check(heading >= 0) { "${doc.name}: no `$HEADING` section" }
        check(lines.count { it.trim() == HEADING } == 1) { "${doc.name}: a second `$HEADING` section goes unread" }

        val names = lines.drop(heading + 1)
            .takeWhile { !it.trimStart().startsWith("#") } // any heading ends the section, `###` included
            .filter { it.trimStart().startsWith("|") }
            .map { it.trim().split("|")[1].trim() }
            .filterNot { it == "EnrichmentType" || it.all { char -> char in "-: " } } // header, separator
            .map { cell ->
                NAME.matchEntire(cell)?.groupValues?.get(1)
                    ?: error("${doc.name}: `$cell` is not a backticked EnrichmentType — every row is a claim")
            }
        check(names.isNotEmpty()) { "${doc.name}: `$HEADING` lists no capabilities" }
        check(names.size == names.toSet().size) { "${doc.name}: a capability is listed twice" }
        return names.toSet()
    }

    private companion object {
        const val PROVIDER_DIR = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider"
        const val DOC_DIR = "docs/providers"
        const val HEADING = "## What We Extract"
        val NAME = Regex("`([A-Z][A-Z0-9_]*)`")

        /** Gradle runs tests from the module directory; walk up rather than depend on that. */
        val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, DOC_DIR).isDirectory }
            ?: error("no $DOC_DIR above ${File("").absolutePath} — this test would check nothing")
    }
}
