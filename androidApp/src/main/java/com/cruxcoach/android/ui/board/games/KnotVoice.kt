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
    private val queue = ArrayDeque<String>()

    /** Plays [call] after [afterMs] (the beep before it); a call still playing is cut off. Solo calls have no player. */
    fun say(call: KnotCall, solo: Boolean, afterMs: Long = 0) {
        stop()
        val folder = "$DIR/${language()}"
        queue += clipNames(call, solo).map { "$folder/$it.ogg" }
        handler.postDelayed(::playNext, afterMs)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        runCatching { player?.stop() }
    }

    fun release() {
        stop()
        runCatching { player?.release() }
        player = null
    }

    private fun playNext() {
        val path = queue.removeFirstOrNull() ?: return
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
