package com.cruxcoach.android.ui.board.games

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Countdown beeps for the wall games: a short beep each second and a longer
 * one at the start, so a climber already on the wall does not have to look at
 * the phone. Best-effort: a device without a tone generator stays silent.
 */
internal class GameSounds {
    private var tones: ToneGenerator? = null
    private var unavailable = false

    private fun generator(): ToneGenerator? {
        if (tones == null && !unavailable) {
            tones = try {
                ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
            } catch (e: RuntimeException) {
                markUnavailable(e)
            } catch (e: UnsatisfiedLinkError) {
                markUnavailable(e)
            }
        }
        return tones
    }

    private fun markUnavailable(e: Throwable): ToneGenerator? {
        Log.w(TAG, "No tone generator: ${e.javaClass.simpleName}")
        unavailable = true
        return null
    }

    fun play(beep: GameBeep) {
        when (beep) {
            GameBeep.COUNTDOWN -> generator()?.startTone(ToneGenerator.TONE_PROP_BEEP, COUNTDOWN_MS)
            GameBeep.GO -> generator()?.startTone(ToneGenerator.TONE_PROP_BEEP2, GO_MS)
        }
    }

    fun release() {
        runCatching { tones?.release() }
        tones = null
    }

    private companion object {
        const val TAG = "GameSounds"
        const val VOLUME = 80
        const val COUNTDOWN_MS = 150
        const val GO_MS = 500
    }
}
