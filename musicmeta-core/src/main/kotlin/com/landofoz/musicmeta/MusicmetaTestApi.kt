package com.landofoz.musicmeta

/**
 * Marks an API that ships in the published jar only so that tests — including a consumer's own — can
 * reach a behaviour they otherwise could not observe. Calling one from production code is a mistake
 * the compiler refuses until the call site says otherwise:
 *
 * ```kotlin
 * @OptIn(MusicmetaTestApi::class)
 * class MyHttpClientTest { /* … */ }
 * ```
 *
 * The opt-in is the point. There is no separate test artifact to keep these out of a runtime
 * classpath, so the deliberate silencing is what stands in for one.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "A test-only API. Opt in from a test; a production call is almost certainly a mistake.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class MusicmetaTestApi
