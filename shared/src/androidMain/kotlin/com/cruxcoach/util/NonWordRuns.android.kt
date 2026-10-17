package com.cruxcoach.util

actual fun replaceNonWordRuns(text: String, alsoKeep: String, replacement: String): String =
    text.replace(Regex("[^\\p{L}\\p{N}$alsoKeep]+"), replacement)
