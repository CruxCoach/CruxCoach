package com.cruxcoach.android.util

/**
 * Parses a decimal entered through a locale-aware decimal keyboard.
 *
 * Kotlin's numeric parser accepts only a dot, while many IMEs emit a comma.
 * These fields do not accept grouping separators, so normalising the one
 * decimal separator is unambiguous.
 */
fun String.toUserDoubleOrNull(): Double? =
    trim().replace(',', '.').toDoubleOrNull()
