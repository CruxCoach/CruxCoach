package com.cruxcoach.app.ui

import com.cruxcoach.app.platform.KeyValueStore

/**
 * The settings that have an effect on iOS today, on Android's preference keys
 * and value spellings so a later backup/restore stays compatible.
 */
class SettingsModel(private val store: KeyValueStore) {

    /** "FRENCH" or "V_SCALE". */
    var gradeScale: String
        get() = store.getString(GRADE_SCALE) ?: "FRENCH"
        set(value) { if (value == "FRENCH" || value == "V_SCALE") store.putString(GRADE_SCALE, value) }

    /** "BELOW", "ABOVE" or "BOTH". */
    var moonBoardLedMode: String
        get() = store.getString(MOONBOARD_LED_MODE) ?: "BELOW"
        set(value) { if (value in LED_MODES) store.putString(MOONBOARD_LED_MODE, value) }

    /** 0 = never disconnect automatically. */
    var bleAutoDisconnectSeconds: Int
        get() = store.getString(BLE_AUTO_DISCONNECT)?.toIntOrNull()?.coerceIn(0, 3600) ?: 0
        set(value) { store.putString(BLE_AUTO_DISCONNECT, value.coerceIn(0, 3600).toString()) }

    /** "system", "light" or "dark", as Android stores `dark_mode`. */
    val darkMode: String
        get() = when (store.getString(DARK_MODE)) {
            "LIGHT" -> "light"
            "DARK" -> "dark"
            else -> "system"
        }

    /** Drives `isIdleTimerDisabled` on iOS; `keep_screen_on` on Android. */
    val keepScreenOn: Boolean get() = store.getString(KEEP_SCREEN_ON) == "true"

    val usesFrenchGrades: Boolean get() = gradeScale != "V_SCALE"

    fun gradeFormatter(): GradeFormatter = GradeFormatter(usesFrenchGrades)

    companion object {
        const val GRADE_SCALE = "grade_scale"
        const val DARK_MODE = "dark_mode"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val MOONBOARD_LED_MODE = "moonboard_led_mode"
        const val BLE_AUTO_DISCONNECT = "ble_auto_disconnect_seconds"
        private val LED_MODES = setOf("BELOW", "ABOVE", "BOTH")
    }
}
