package com.landofoz.musicmeta

/** Returns this string if it's not blank, or null otherwise. */
internal fun String.takeIfNotEmpty(): String? = takeIf { it.isNotBlank() }
