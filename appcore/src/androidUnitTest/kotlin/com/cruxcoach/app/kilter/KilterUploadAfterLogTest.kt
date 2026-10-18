package com.cruxcoach.app.kilter

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.LogAttemptListener
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.LogTarget
import com.cruxcoach.app.logbook.NoSessionRecorder
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.logbook.newPersonalRepo
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Android `KilterSyncEngine.uploadNewAscentIfEnabled` as the detail's `onAscentSaved`:
 * gates, which logs upload (dialog save at once, quick log after its undo window,
 * never an edit or an undone quick log), and the rest-timer follow-ups still running.
 */
class KilterUploadAfterLogTest {
    private val serial = SerialDispatcher().dispatcher

    private val target = LogTarget(
        climbUuid = "climb-1",
        climbName = "After Log",
        frames = "p1100r12",
        framesCount = 1L,
        difficultyAverage = 17.0,
        boardBrand = "kilter",
        layoutId = 1L,
        angle = 40,
        isMirrored = false,
    )

    private class Next : LogAttemptListener {
        val saved = AtomicInteger()
        val quick = AtomicInteger()
        val status = AtomicInteger()
        override fun onAscentSaved(isSend: Boolean) { saved.incrementAndGet() }
        override fun onQuickLogSaved(isSend: Boolean) { quick.incrementAndGet() }
        override fun onClimbStatusChanged(climbUuid: String) { status.incrementAndGet() }
    }

    private class Gates(var sync: Boolean = true, var push: Boolean = true)

    private val uploads = AtomicInteger()

    private fun hook(gates: Gates, next: LogAttemptListener? = null) = KilterUploadAfterLog(
        syncEnabled = { gates.sync },
        pushEnabled = { gates.push },
        upload = { uploads.incrementAndGet() },
        scope = CoroutineScope(serial),
        next = next,
    )

    private fun presenter(listener: LogAttemptListener) =
        LogAttemptPresenter(newPersonalRepo(), NoSessionRecorder, listener, CoroutineScope(serial), serial)
            .also { it.setTarget(target) }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(CI_WAIT_MS) {
        while (!condition()) delay(10)
    }

    /** Lets queued work on the serial thread (a gated-off launch) finish before asserting nothing happened. */
    private suspend fun drain() = withContext(serial) { delay(50) }

    @Test
    fun `dialog save uploads once with sync and push on`() = runBlocking {
        val p = presenter(hook(Gates()))
        p.showDialog()
        p.updateIsSend(true)
        p.save()
        withTimeout(CI_WAIT_MS) { p.state.first { it.userAscents.size == 1 } }
        awaitUntil { uploads.get() == 1 }
        drain()
        assertEquals(1, uploads.get())
    }

    @Test
    fun `editing an entry does not upload`() = runBlocking {
        val p = presenter(hook(Gates()))
        p.showDialog()
        p.updateBidCount(3)
        p.save()
        val entry = withTimeout(CI_WAIT_MS) { p.state.first { it.userAscents.size == 1 } }.userAscents.single()
        awaitUntil { uploads.get() == 1 }

        p.edit(entry)
        p.updateBidCount(5)
        p.save()
        withTimeout(CI_WAIT_MS) { p.state.first { it.userAscents.singleOrNull()?.bidCount == 5L } }
        drain()
        assertEquals(1, uploads.get())
    }

    @Test
    fun `quick log uploads only once its undo window closed`() = runBlocking {
        val p = presenter(hook(Gates()))
        p.quickLog(isSend = true)
        withTimeout(CI_WAIT_MS) { p.state.first { !it.isQuickLogging && it.quickLogFeedback != null } }
        drain()
        assertEquals(0, uploads.get())

        p.consumeQuickLogFeedback()
        awaitUntil { uploads.get() == 1 }
    }

    @Test
    fun `undone quick log never uploads`() = runBlocking {
        val p = presenter(hook(Gates()))
        p.quickLog(isSend = true)
        withTimeout(CI_WAIT_MS) { p.state.first { !it.isQuickLogging && it.quickLogFeedback != null } }
        p.undoQuickLog()
        withTimeout(CI_WAIT_MS) { p.state.first { it.userAscents.isEmpty() && it.quickLogFeedback == null } }
        p.consumeQuickLogFeedback()
        p.close()
        drain()
        assertEquals(0, uploads.get())
    }

    @Test
    fun `without sync continuously or upload to Kilter nothing is uploaded`() = runBlocking {
        for (gates in listOf(Gates(sync = false), Gates(push = false), Gates(sync = false, push = false))) {
            val h = hook(gates)
            h.onAscentSaved(isSend = true)
            h.onAscentSaved(isSend = false)
        }
        drain()
        assertEquals(0, uploads.get())
    }

    @Test
    fun `gates are read when the log is saved`() = runBlocking {
        val gates = Gates(push = false)
        val h = hook(gates)
        h.onAscentSaved(isSend = true)
        drain()
        gates.push = true
        h.onAscentSaved(isSend = true)
        awaitUntil { uploads.get() == 1 }
    }

    @Test
    fun `rest timer follow-ups still run and a failing upload is contained`() = runBlocking {
        val next = Next()
        val failing = KilterUploadAfterLog(
            syncEnabled = { true },
            pushEnabled = { true },
            upload = { uploads.incrementAndGet(); throw IllegalStateException("offline") },
            scope = CoroutineScope(serial),
            next = next,
        )
        failing.onQuickLogSaved(isSend = false)
        failing.onClimbStatusChanged("climb-1")
        failing.onAscentSaved(isSend = true)
        awaitUntil { uploads.get() == 1 }
        failing.onAscentSaved(isSend = false)
        awaitUntil { uploads.get() == 2 }
        assertEquals(2, next.saved.get())
        assertEquals(1, next.quick.get())
        assertEquals(1, next.status.get())
    }
}
