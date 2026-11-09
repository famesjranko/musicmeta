package com.landofoz.musicmeta.testutil

import java.io.File
import java.util.Properties

internal data class E2eCredentialSpec(val property: String, val environment: String)

internal val E2E_CREDENTIAL_SPECS = listOf(
    E2eCredentialSpec("lastfm.apikey", "LASTFM_API_KEY"),
    E2eCredentialSpec("fanarttv.apikey", "FANARTTV_API_KEY"),
    E2eCredentialSpec("discogs.token", "DISCOGS_TOKEN"),
    E2eCredentialSpec("listenbrainz.token", "LISTENBRAINZ_TOKEN"),
)

internal object E2eCredentials {

    fun resolve(
        checkoutRoot: File,
        property: (String) -> String?,
        environment: (String) -> String?,
    ): Map<String, String> {
        val localSecrets = readSecrets(File(checkoutRoot, "secrets.properties"))
        val mainSecrets = mainCheckoutSecrets(checkoutRoot)?.let(::readSecrets)
        return E2E_CREDENTIAL_SPECS.associate { spec ->
            spec.property to listOf(
                property(spec.property),
                localSecrets.getProperty(spec.property),
                mainSecrets?.getProperty(spec.property),
                environment(spec.environment),
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        }
    }

    fun requireAll(credentials: Map<String, String>) {
        val missing = E2E_CREDENTIAL_SPECS.filter { credentials[it.property].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            error(
                "Missing E2E credentials: " +
                    missing.joinToString(", ") { "${it.property} (${it.environment})" } +
                    ". Configure the properties in secrets.properties or set the corresponding " +
                    "environment variables.",
            )
        }
    }

    fun checkoutRoot(): File = File(System.getProperty("user.dir")).absoluteFile.parentFile

    private fun readSecrets(file: File): Properties = Properties().apply {
        file.takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }

    internal fun mainCheckoutSecrets(checkoutRoot: File): File? {
        val gitMarker = File(checkoutRoot, ".git")
        if (!gitMarker.isFile) return null
        val gitdir = gitMarker.readLines().firstOrNull { it.startsWith("gitdir:") }
            ?.removePrefix("gitdir:")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = File(gitdir).let { if (it.isAbsolute) it else File(checkoutRoot, gitdir) }
            .absoluteFile.normalize()
        val worktrees = resolved.parentFile?.takeIf { it.name == "worktrees" } ?: return null
        val dotGit = worktrees.parentFile?.takeIf { it.name == ".git" } ?: return null
        val main = dotGit.parentFile ?: return null
        return File(main, "secrets.properties")
    }
}

fun main() = E2eCredentials.requireAll(
    E2eCredentials.resolve(E2eCredentials.checkoutRoot(), System::getProperty, System::getenv),
)
