package com.cruxcoach.android.ui.board.games

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.games.KnotCall
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotLimb
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

/**
 * Speaks Knot's calls from the recordings bundled in `assets/knot_voice/<language>/`: "Player 2", "right hand",
 * "blue", one after another. German when the app runs in German, otherwise English. Works on every phone: no speech
 * engine, no network, no other app. Best effort: a clip that can't be played is skipped.
 */
class KnotVoice @Inject constructor(@ApplicationContext private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    /** A clip to play, or a [cue] (the beep) followed by a pause. [toWall] parts belong to a "to the wall". */
    private class Part(val toWall: Boolean, val path: String? = null, val cue: (() -> Unit)? = null, val pauseMs: Long = 0)

    private val queue = ArrayDeque<Part>()
    /** The part playing now, or its pause; null when quiet. */
    private var current: Part? = null

    /**
     * Plays [cue] (the beep), then [call] [afterMs] later. A call still being spoken is cut off; a "to the wall" is
     * not, the call follows it. Solo calls have no player.
     */
    fun say(call: KnotCall, solo: Boolean, afterMs: Long = 0, cue: () -> Unit = {}) {
        queue.removeAll { !it.toWall }
        if (current?.toWall != true) {
            handler.removeCallbacksAndMessages(null)
            silence()
            current = null
        }
        enqueue(false, clipNames(call, solo), afterMs, cue)
    }

    /** Plays [cue] (the beep), then "Player n, to the wall" for [player] (from 0) [afterMs] later, after anything still being said. */
    fun sayToWall(player: Int, afterMs: Long = 0, cue: () -> Unit = {}) {
        enqueue(true, listOf("player_${player + 1}", TO_THE_WALL), afterMs, cue)
    }

    private fun enqueue(toWall: Boolean, clips: List<String>, afterMs: Long, cue: () -> Unit) {
        val folder = "$DIR/${language()}"
        queue += Part(toWall, cue = cue, pauseMs = afterMs)
        queue += clips.map { Part(toWall, path = "$folder/$it.ogg") }
        if (current == null) playNext()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        current = null
        silence()
    }

    /**
     * Cuts off the clip playing now. `reset()`, not `stop()`: a clip still preparing can't be stopped (the error was
     * swallowed and the clip started anyway once prepared); after a reset it never starts.
     */
    private fun silence() {
        runCatching { player?.reset() }
    }

    fun release() {
        stop()
        runCatching { player?.release() }
        player = null
    }

    private fun playNext() {
        val part = queue.removeFirstOrNull()
        current = part
        if (part == null) return
        part.cue?.let { cue ->
            cue()
            handler.postDelayed(::playNext, part.pauseMs)
            return
        }
        val path = part.path ?: return playNext()
        try {
            val media = player ?: MediaPlayer().also { created ->
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                created.setOnCompletionListener { handler.postDelayed(::playNext, GAP_MS) }
                created.setOnPreparedListener { it.start() }
                player = created
            }
            media.reset()
            context.assets.openFd(path).use { media.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            // Off the main thread: a game's screen keeps its frames.
            media.prepareAsync()
        } catch (e: IOException) {
            skip(path, e)
        } catch (e: RuntimeException) {
            skip(path, e)
        }
    }

    private fun skip(path: String, e: Exception) {
        Log.w(TAG, "Knot clip $path not played: ${e.javaClass.simpleName}")
        handler.post(::playNext)
    }

    /** The app's language when it is German, else English: the languages of the clips. */
    private fun language(): String {
        val app = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
        return if (app.language == Locale.GERMAN.language) "de" else "en"
    }

    companion object {
        private const val TAG = "KnotVoice"
        const val DIR = "knot_voice"
        /** Pause between the parts of a call. */
        const val GAP_MS = 120L

        const val TO_THE_WALL = "to_the_wall"

        /** The clips of [call] in the order they are spoken: player (unless solo), hand or foot, colour. */
        fun clipNames(call: KnotCall, solo: Boolean): List<String> = listOfNotNull(
            "player_${call.climber + 1}".takeUnless { solo },
            call.limb.name.lowercase(Locale.ROOT),
            call.colour.name.lowercase(Locale.ROOT),
        )
    }
}

/**
 * A climber's name as typed, or "Player n". [numbered] puts the number before a typed name ("2 · Anna"): the calls
 * say "Player 2", so everyone knows which one is theirs.
 */
internal fun climberName(context: Context, names: List<String>, climber: Int, numbered: Boolean = true): String =
    names.getOrNull(climber)?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { if (numbered) context.getString(R.string.knot_numbered, climber + 1, it) else it }
        ?: context.getString(R.string.knot_player, climber + 1)

internal val KnotLimb.labelRes: Int
    @StringRes get() = when (this) {
        KnotLimb.LEFT_HAND -> R.string.knot_left_hand
        KnotLimb.RIGHT_HAND -> R.string.knot_right_hand
        KnotLimb.LEFT_FOOT -> R.string.knot_left_foot
        KnotLimb.RIGHT_FOOT -> R.string.knot_right_foot
    }

internal val KnotColour.labelRes: Int
    @StringRes get() = when (this) {
        KnotColour.BLUE -> R.string.knot_blue
        KnotColour.YELLOW -> R.string.knot_yellow
        KnotColour.MAGENTA -> R.string.knot_magenta
        KnotColour.WHITE -> R.string.knot_white
        KnotColour.GREEN -> R.string.knot_green
        KnotColour.RED -> R.string.knot_red
    }
