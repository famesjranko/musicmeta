package com.landofoz.musicmeta.drift

/**
 * Which JSON type a pinned route answers with, so the pin asks the http client for the same body
 * type the api client asks for. Reading an array route as an object reports drift about a healthy
 * provider.
 */
internal enum class BodyShape { OBJECT, ARRAY }

/**
 * One pinned upstream route: the URL an api client builds, and the JSON paths that api client's
 * own parse reads out of the answer.
 *
 * [url] must come from the api client's route function, never from a hand-written string. A pin
 * that builds its own URL asserts against a document the library never requests — the shape of a
 * response depends on the query parameters, so a hand-built URL can report a missing field that is
 * missing only because the pin forgot to ask for it.
 *
 * [requiredPaths] are dotted paths with `[n]` indices, read against the parsed body. Each names a
 * field whose absence changes an enrichment answer, and each is declared in the same file as the
 * parse it mirrors, so a diff that moves a field shows the pin going stale in the same hunk. They
 * are the pin's whole output vocabulary: a report names a path, never the value at it, which is
 * what keeps lyrics text and preview URLs out of a CI log.
 *
 * [headers] carries an `Authorization` header where a provider takes its credential that way.
 */
internal data class SchemaTarget(
    val provider: String,
    val route: String,
    val url: String,
    val requiredPaths: List<String>,
    val shape: BodyShape = BodyShape.OBJECT,
    val headers: Map<String, String> = emptyMap(),
) {

    /**
     * [url] with its query string removed.
     *
     * The only form of [url] that may reach a log, an annotation or an artifact: three providers
     * put their credential in the query string, and this job runs with all of them in scope.
     */
    val loggableUrl: String get() = url.substringBefore('?')

    /** `provider route` — how a finding names the route it is about. */
    override fun toString(): String = "$provider $route"
}
