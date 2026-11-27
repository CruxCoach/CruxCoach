package com.cruxcoach.android.spraywall

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.Closeable

/**
 * The hold model for one screen (FEAT-078). The session is loaded on the
 * first [run] and kept for the next photo; one photo runs at a time.
 *
 * Cancelling the caller stops a run, also in the middle of a tile, and
 * [run] returns only after the runtime has let go of the session. [close]
 * frees the session at once when nothing runs, else when the run has
 * stopped; a session that finishes loading after [close] is freed straight
 * away. Never closing a session under a running inference is what keeps a
 * screen left mid-run from crashing in native code.
 */
class HoldModelSession<D : HoldModelSession.Runner>(private val load: () -> D) {
    /** A loaded model: [stop] ends a running photo from another thread, [close] frees it. */
    interface Runner : Closeable {
        fun stop()
    }

    private val lock = Any()
    private var detector: D? = null
    private var running = false
    private var closed = false

    /** [block] gets the loaded detector and a check that turns true once the caller is cancelled. */
    suspend fun <T> run(block: (detector: D, isCancelled: () -> Boolean) -> T): T = coroutineScope {
        val work = async(Dispatchers.Default) {
            begin()
            try {
                val loaded = detector()
                ensureActive()
                block(loaded) { !isActive }
            } catch (e: Exception) {
                // A stopped run ends with an error from the runtime: it is a cancellation, not a model failure.
                ensureActive()
                throw e
            } finally {
                end()
            }
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // coroutineScope still waits for [work]: this only makes the wait short.
            synchronized(lock) { detector }?.stop()
            throw e
        }
    }

    /** Frees the session now, or when the running photo has stopped. Later runs are cancelled. */
    fun close() {
        val free = synchronized(lock) {
            closed = true
            if (running) null else detector.also { detector = null }
        }
        free?.close()
    }

    private fun begin() = synchronized(lock) {
        if (closed) throw CancellationException("hold model closed")
        check(!running) { "one photo at a time" }
        running = true
    }

    private fun detector(): D {
        synchronized(lock) { detector }?.let { return it }
        // Loading takes seconds: outside the lock, so close() never waits for it.
        val loaded = load()
        synchronized(lock) { detector = loaded }
        return loaded
    }

    private fun end() {
        val free = synchronized(lock) {
            running = false
            if (closed) detector.also { detector = null } else null
        }
        free?.close()
    }
}
