package com.cruxcoach.android.fips

import android.util.Log
import com.cruxcoach.android.BuildConfig

/** Stable, grep-friendly debug trace for the complete FIPS/BoardCell pipeline. */
object FipsDebugLog {
    const val TAG = "CruxFIPS"

    fun event(component: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val detail = fields.joinToString(" | ") { (key, value) -> "$key=${value ?: "-"}" }
        Log.i(TAG, buildString {
            append("component=").append(component).append(" | event=").append(event)
            if (detail.isNotEmpty()) append(" | ").append(detail)
        })
    }

    fun warning(component: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val detail = fields.joinToString(" | ") { (key, value) -> "$key=${value ?: "-"}" }
        Log.w(TAG, "component=$component | event=$event" +
            if (detail.isEmpty()) "" else " | $detail")
    }

    fun id(value: String?): String = when {
        value.isNullOrBlank() -> "-"
        value.length <= 16 -> value
        else -> "${value.take(8)}…${value.takeLast(6)}"
    }

    fun tag(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
