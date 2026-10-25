package com.cruxcoach.android.ui.board.games

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/** Numbers on the wall-games screen, in the user's locale. */
internal object WallGameFormat {

    /** An interval in seconds without trailing zeros: 3, 0.5, 0.25 ("0,25" in German). */
    fun seconds(ms: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }.format(ms / 1000.0)

    /** A stopwatch reading with tenths: 0:07.4, 1:05.3, 12:00.0. */
    fun clock(ms: Long, locale: Locale = Locale.getDefault()): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe / 1_000) % 60
        val tenths = (safe / 100) % 10
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return "$minutes:${seconds.toString().padStart(2, '0')}$separator$tenths"
    }
}
