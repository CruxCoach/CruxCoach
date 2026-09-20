package com.cruxcoach.app.settings

import com.cruxcoach.app.ble.LedHoldColors
import com.cruxcoach.app.platform.KeyValueStore

/**
 * The four `led_color_*` preferences as an [LedHoldColors].
 *
 * An unset role keeps the built-in CruxCoach colour rather than a stored value:
 * a fresh install, a never-customised user and anyone who tapped "reset" all
 * have no keys, and Android renders all three the same way
 * (`UserPreferences.ledHoldColors`). Only Kilter consults these — the Aurora
 * family and MoonBoard use their own standard palettes.
 */
object UserLedColors {
    fun read(store: KeyValueStore): LedHoldColors = LedHoldColors(
        start = byte(store, SettingsStore.LED_COLOR_START) ?: LedHoldColors.CRUXCOACH_START,
        hand = byte(store, SettingsStore.LED_COLOR_HAND) ?: LedHoldColors.CRUXCOACH_HAND,
        finish = byte(store, SettingsStore.LED_COLOR_FINISH) ?: LedHoldColors.CRUXCOACH_FINISH,
        foot = byte(store, SettingsStore.LED_COLOR_FOOT) ?: LedHoldColors.CRUXCOACH_FOOT,
    )

    /** Out-of-range text is treated as unset: an RGB332 byte is all the wall accepts. */
    private fun byte(store: KeyValueStore, key: String): Int? =
        store.safeGet(key)?.toIntOrNull()?.takeIf { it in 0..0xFF }
}
