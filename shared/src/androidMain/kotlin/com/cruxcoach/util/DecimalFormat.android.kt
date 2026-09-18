package com.cruxcoach.util

actual fun formatDecimal(value: Double, decimals: Int): String = "%.${decimals}f".format(value)
