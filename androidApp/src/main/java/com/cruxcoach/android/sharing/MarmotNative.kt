package com.cruxcoach.android.sharing

import androidx.annotation.Keep

/** The callback delegates to the active account signer; no private key crosses JNI. */
@Keep
fun interface MarmotAccountCallback {
    fun invoke(operation: String, argument: String): String?
}

@Keep
internal object MarmotNative {
    val loaded: Boolean = runCatching { System.loadLibrary("cruxcoach_marmot") }.isSuccess
    @JvmStatic external fun open(path: String, key: ByteArray, config: String, signer: MarmotAccountCallback): Long
    @JvmStatic external fun call(handle: Long, command: String): String
    @JvmStatic external fun archive(path: String): Boolean
    @JvmStatic external fun close(handle: Long)
}
