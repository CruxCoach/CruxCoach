package com.cruxcoach.android.updater

import android.os.Build
import com.cruxcoach.android.BuildConfig

/**
 * Answers one question: will this device still be offered releases after the
 * one it is running?
 *
 * A device below [BuildConfig.MIN_SDK_NEXT_RELEASE] is at the end of its
 * update path. Telling it so is not a nicety — without it the failure is
 * silent and indistinguishable from "no update available yet", and the user
 * would keep waiting for updates that can never arrive. Worse, an updater
 * that happily downloaded the next release would hand Android an APK it must
 * reject for `minSdk`, turning a clean end-of-support into a recurring,
 * unexplained install failure.
 *
 * The check is a pure integer comparison against a build-time constant, so
 * it is correct offline and costs nothing.
 */
class DeviceSupportGate(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val minSdkNextRelease: Int = BuildConfig.MIN_SDK_NEXT_RELEASE,
) {

    /** False once the next release will no longer install on this device. */
    fun receivesFutureUpdates(): Boolean = sdkInt >= minSdkNextRelease

    /**
     * The API level the next release requires. Shown to the user so the
     * message names a concrete Android version rather than "too old".
     */
    fun requiredSdkInt(): Int = minSdkNextRelease
}
