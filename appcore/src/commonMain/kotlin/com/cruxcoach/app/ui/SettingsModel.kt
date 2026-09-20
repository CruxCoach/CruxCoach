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

    val usesFrenchGrades: Boolean get() = gradeScale != "V_SCALE"

    fun gradeFormatter(): GradeFormatter = GradeFormatter(usesFrenchGrades)

    companion object {
        const val GRADE_SCALE = "grade_scale"
        const val MOONBOARD_LED_MODE = "moonboard_led_mode"
        const val BLE_AUTO_DISCONNECT = "ble_auto_disconnect_seconds"
        private val LED_MODES = setOf("BELOW", "ABOVE", "BOTH")
    }
}
