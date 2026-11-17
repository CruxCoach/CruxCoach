package com.cruxcoach.android.ui.board.games

import android.content.Context
import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.games.KnotCall
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotLimb
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the phone can speak Knot's calls. */
enum class KnotVoiceStatus {
    CHECKING, READY,
    /** No speech engine on the phone (common without Google services). */
    NO_ENGINE,
    /** An engine, but without the app's language. */
    NO_LANGUAGE,
}

/**
 * Speaks Knot's calls with the phone's own text-to-speech engine, in the app's
 * language (German, else English). Whether there is an engine with that
 * language is checked at runtime; without one the status says so and a person
 * reads the calls from the screen. Nothing here needs a network or a
 * particular app.
 */
class KnotVoice @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutableStatus = MutableStateFlow(KnotVoiceStatus.CHECKING)
    val status: StateFlow<KnotVoiceStatus> = mutableStatus.asStateFlow()

    private var tts: TextToSpeech? = null
    private var prepared = false

    /** Looks for an engine with the app's language, once. Call on the main thread. */
    fun prepare() {
        if (prepared) return
        prepared = true
        val locale = language()
        try {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { result ->
                val ready = engine
                if (result != TextToSpeech.SUCCESS || ready == null) {
                    mutableStatus.value = KnotVoiceStatus.NO_ENGINE
                    runCatching { ready?.shutdown() }
                    return@TextToSpeech
                }
                val language = runCatching { ready.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    mutableStatus.value = KnotVoiceStatus.NO_LANGUAGE
                    runCatching { ready.shutdown() }
                } else {
                    tts = ready
                    mutableStatus.value = KnotVoiceStatus.READY
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "No text-to-speech: ${e.javaClass.simpleName}")
            mutableStatus.value = KnotVoiceStatus.NO_ENGINE
        }
    }

    /** Speaks [call]; [names] are the climbers' names as the screen shows them. */
    fun say(call: KnotCall, names: List<String>) {
        val engine = tts ?: return
        val text = callText(localized(), call, names)
        runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "knot_${call.number}") }
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun release() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }

    /** The app's language when it is German, else English: the languages of the calls. */
    private fun language(): Locale {
        val app = AppCompatDelegate.getApplicationLocales()[0] ?: Locale.getDefault()
        return if (app.language == Locale.GERMAN.language) Locale.GERMAN else Locale.ENGLISH
    }

    private fun localized(): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(language()) })

    private companion object {
        const val TAG = "KnotVoice"
    }
}

/** A call as one sentence: "Anna: right hand on blue"; solo without a name. */
internal fun callText(context: Context, call: KnotCall, names: List<String>): String {
    val limb = context.getString(call.limb.labelRes)
    val colour = context.getString(call.colour.labelRes)
    return if (names.size <= 1) context.getString(R.string.knot_call_solo, limb, colour)
    else context.getString(R.string.knot_call, climberName(context, names, call.climber), limb, colour)
}

/** A climber's name as typed, or "Player n". */
internal fun climberName(context: Context, names: List<String>, climber: Int): String =
    names.getOrNull(climber)?.trim()?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.knot_player, climber + 1)

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
