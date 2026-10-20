package com.cruxcoach.app.library

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.WorkoutTextParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A typed text as read: [text] is the text [result] was read from, null before the first read. */
class TypedRead(val text: String?, val result: WorkoutTextParser.Result)

/**
 * Reads a typed workout the way Android's `WorkoutTextSheet` does (owner 2026-10-19: free text, reliable and
 * fast): off the main thread on [reader], the first text at once and every later one [READ_DELAY_MS] after the
 * last keystroke; a newer text cancels the read of an older one. "Add" takes [resultFor] the text in the field:
 * the last read when it is of that very text, else the text read on the spot.
 *
 * [read] and [reset] are called from one thread (the main thread in the app); [state] may be read anywhere.
 */
class TypedWorkoutReader(
    private val scope: CoroutineScope,
    private val reader: CoroutineDispatcher,
    private val catalog: () -> ExerciseCatalog,
    private val onRead: (TypedRead) -> Unit = {},
) {
    private val _state = MutableStateFlow(TypedRead(null, EMPTY))
    val state: StateFlow<TypedRead> = _state.asStateFlow()
    private var job: Job? = null

    fun read(text: String) {
        val t = text.take(WorkoutTextParser.MAX_CHARS)
        job?.cancel()
        // Android: `if (text.isNotEmpty() && readText != null) delay(READ_DELAY_MS)`.
        val wait = t.isNotEmpty() && _state.value.text != null
        job = scope.launch {
            if (wait) delay(READ_DELAY_MS)
            val result = withContext(reader) { parse(t) }
            val read = TypedRead(t, result)
            _state.value = read
            onRead(read)
        }
    }

    /** What "Add" takes for [text]: typed a moment ago and not read yet, it is read now. */
    fun resultFor(text: String): WorkoutTextParser.Result {
        val t = text.take(WorkoutTextParser.MAX_CHARS)
        val last = _state.value
        return if (last.text == t) last.result else parse(t)
    }

    /** A closed sheet: nothing read, and the next text is read at once. */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = TypedRead(null, EMPTY)
    }

    private fun parse(text: String): WorkoutTextParser.Result = try {
        WorkoutTextParser.parse(text, catalog())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EMPTY
    }

    companion object {
        /** How long after the last keystroke the text is read again (Android `READ_DELAY_MS`). */
        const val READ_DELAY_MS = 120L
        val EMPTY = WorkoutTextParser.Result(null, emptyList())
    }
}
