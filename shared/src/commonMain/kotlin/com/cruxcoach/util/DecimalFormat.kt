package com.cruxcoach.util

/**
 * Fixed-decimal formatting in the platform's current locale.
 *
 * `String.format` is JVM-only, so common code cannot call it. The Android
 * actual is exactly the `"%.Nf".format(value)` call these sites used before
 * iOS became a target, so Android output (including the locale's decimal
 * separator) is unchanged.
 */
expect fun formatDecimal(value: Double, decimals: Int): String
