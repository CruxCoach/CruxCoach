package com.cruxcoach.util

/**
 * Replaces every run of characters that are neither letters (`\p{L}`), numbers
 * (`\p{N}`) nor one of [alsoKeep] with [replacement] — the regex
 * `[^\p{L}\p{N}<alsoKeep>]+` the text parsers use.
 *
 * Kotlin/Native's regex engine drops the literal characters of a negated class
 * that also holds a `\p{…}` property: there `[^\p{L}\p{N}.,]+` matches "." and
 * ",", so "1,5 l" became "1 5 l" on iOS. The Android actual is exactly the regex
 * the call sites used before; the iOS actual is a plain loop over the same
 * Unicode categories.
 */
expect fun replaceNonWordRuns(text: String, alsoKeep: String, replacement: String): String
