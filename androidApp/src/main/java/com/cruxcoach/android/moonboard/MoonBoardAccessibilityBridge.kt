package com.cruxcoach.android.moonboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MoonBoardScanState(
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val status: String = "",
    val captured: Int = 0,
    /** Training days finished so far, and how many Moon says there are. */
    val sessionsDone: Int = 0,
    val sessionsTotal: Int = 0,
    val cancelling: Boolean = false,
    val result: MoonBoardCsvImportResult? = null,
) {
    /** 0f..1f once Moon has told us how big the logbook is, else null. */
    val progress: Float?
        get() = if (sessionsTotal > 0) (sessionsDone.toFloat() / sessionsTotal).coerceIn(0f, 1f) else null
}

/** In-process bridge between the opt-in settings screen and accessibility service. */
object MoonBoardAccessibilityBridge {
    private val mutableState = MutableStateFlow(MoonBoardScanState())
    val state = mutableState.asStateFlow()
    internal var service: MoonBoardAccessibilityService? = null

    internal fun connected(value: MoonBoardAccessibilityService?) {
        service = value
        mutableState.value = if (value == null && mutableState.value.running) {
            mutableState.value.copy(
                serviceConnected = false,
                running = false,
                status = "",
                result = MoonBoardCsvImportResult(error = "Moon import was interrupted"),
            )
        } else {
            mutableState.value.copy(serviceConnected = value != null)
        }
    }

    internal fun update(block: (MoonBoardScanState) -> MoonBoardScanState) {
        mutableState.value = block(mutableState.value)
    }

    fun start(context: Context) {
        val scanner = service
        if (scanner == null) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else scanner.startScan()
    }

    /**
     * Asks a running scan to stop at the next safe point. Everything read so
     * far is already stored per training day, so cancelling costs nothing but
     * the days that were not reached yet — the next run picks those up.
     */
    fun cancel() {
        mutableState.value = mutableState.value.copy(cancelling = true)
        service?.cancelScan()
    }

    fun reset() {
        mutableState.value = mutableState.value.copy(status = "", captured = 0, result = null)
    }
}
