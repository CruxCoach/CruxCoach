package com.cruxcoach.app.sharedsession

import com.cruxcoach.app.athlete.TrainingRestTimer

/**
 * Android's participant (`SessionGattBridge.applyRemoteEvent`): the host's rest runs on the
 * phone's own rest timer — the app-wide rest banner and its end notification — with the seconds
 * the host had left. The host's RestEnded normally arrives first and cancels the notification.
 */
internal class RestTimerHostRest(private val timer: TrainingRestTimer) : HostRestSink {
    override fun hostRestStarted(seconds: Int) = timer.startRestTimer(seconds)

    override fun hostRestEnded() = timer.cancelRestTimer()
}
