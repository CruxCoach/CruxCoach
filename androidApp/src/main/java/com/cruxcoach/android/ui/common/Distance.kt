package com.cruxcoach.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A distance as people read it at a glance: exact to 10 m below a kilometre
 * (walkable or not), one decimal below 10 km, whole kilometres beyond.
 * Returns the number only; the unit comes from [distanceLabel].
 */
internal fun distanceNumber(meters: Double, locale: Locale = Locale.getDefault()): Pair<String, Boolean> {
    val format = NumberFormat.getNumberInstance(locale)
    return when {
        meters < 995 -> format.apply { maximumFractionDigits = 0 }.format((meters / 10).roundToInt() * 10) to false
        meters < 9_950 -> format.apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
            .format((meters / 100).roundToInt() / 10.0) to true
        else -> format.apply { maximumFractionDigits = 0 }.format((meters / 1000).roundToInt()) to true
    }
}

/** "350 m", "1,2 km", "12 km". */
@Composable
fun distanceLabel(meters: Double): String {
    val (number, km) = distanceNumber(meters)
    return stringResource(if (km) R.string.distance_km else R.string.distance_m, number)
}
