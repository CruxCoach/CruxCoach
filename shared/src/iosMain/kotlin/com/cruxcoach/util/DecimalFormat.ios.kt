package com.cruxcoach.util

import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp

// Mirrors the JVM's locale-aware `%.Nf`: current locale's decimal separator,
// no grouping, half-up rounding.
actual fun formatDecimal(value: Double, decimals: Int): String {
    val formatter = NSNumberFormatter()
    formatter.numberStyle = NSNumberFormatterDecimalStyle
    formatter.usesGroupingSeparator = false
    formatter.minimumFractionDigits = decimals.toULong()
    formatter.maximumFractionDigits = decimals.toULong()
    formatter.roundingMode = NSNumberFormatterRoundHalfUp
    return formatter.stringFromNumber(NSNumber(value)) ?: value.toString()
}
