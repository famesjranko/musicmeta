// The Android-specific context android.md's narrative may assume without teaching it again,
// injected by check_doc_samples.py ahead of android.md's narrative(), the same role
// docs-samples/.../prelude/Prelude.kt plays for every other guide. Nothing here runs — this
// module only compiles, so a framework type nothing can construct outside a running app
// (`Context`, `WorkerParameters`) is a `TODO()`: it satisfies the type checker and is never
// evaluated.
package doc.samples.android.prelude

import android.content.Context

/** Stand-in for the app `Context` every Room/WorkManager call in the guide takes. */
val preludeContext: Context get() = TODO("compile-only stand-in, never executed")

/** Stand-in for the album IDs the "Enqueue it" fence batches — never named earlier in the guide. */
val preludeAlbumIds: List<String> = emptyList()

/**
 * Stand-in for "your own album entity" — the `AlbumEnrichmentWorker` fence bridges to a domain
 * type the guide never defines, the way `preludeMyLibrary` stands in for "your own local catalog".
 * Named to match the identifier the fence itself declares, not a `prelude`-prefixed alias, since
 * it is a type name a top-level fence resolves by wildcard import rather than a local `val`.
 */
data class Album(val title: String, val artist: String)

/** Stand-in for "your own repository" the `AlbumEnrichmentWorker` fence persists results through. */
interface AlbumRepository {
    suspend fun getById(id: String): Album?

    suspend fun updateEnrichment(title: String, artworkUrl: String?, genres: List<String>, trackCount: Int?)
}
