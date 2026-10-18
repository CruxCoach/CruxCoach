package com.cruxcoach.app.crash

import com.cruxcoach.app.sync.JvmFileSystem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Android's start-up crash flow (`MainActivity` + `CrashReportDialog`) as a state machine. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportPromptTest {
    private val dir: File = Files.createTempDirectory("crash-prompt").toFile()
    private val files = CrashReportFiles(JvmFileSystem(dir), dir.absolutePath)
    private var optIn: Boolean? = null
    private val sent = ArrayList<String>()
    private var outcome = CrashSendOutcome.DELIVERED
    private var drains = 0

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun TestScope.model(): CrashReportPromptModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return CrashReportPromptModel(
            files = files,
            readOptIn = { optIn },
            writeOptIn = { optIn = it },
            sender = { report -> sent += report; outcome },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
            drainQueue = { drains++ },
        )
    }

    @Test
    fun `no report means no prompt and nothing sent`() = runTest {
        val model = model()
        model.start()
        advanceUntilIdle()
        assertEquals("idle", model.currentState.phaseCode)
        assertTrue(sent.isEmpty())
        model.close()
    }

    @Test
    fun `an unset choice asks with the report text`() = runTest {
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        assertEquals("ask", model.currentState.phaseCode)
        assertEquals("REPORT", model.currentState.reportText)
        assertTrue(sent.isEmpty(), "nothing leaves the device before the user chooses")
        assertTrue(files.exists())
        model.close()
    }

    @Test
    fun `automatic sending deletes a delivered report without asking`() = runTest {
        optIn = true
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        assertEquals(listOf("REPORT"), sent)
        assertEquals("idle", model.currentState.phaseCode)
        assertFalse(files.exists())
        model.close()
    }

    @Test
    fun `automatic sending queues a report no relay took once instead of once per launch`() = runTest {
        optIn = true
        outcome = CrashSendOutcome.NOT_DELIVERED
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        assertEquals(1, sent.size)
        // Android's sendCrashReport: the stored, queued report leaves the file; the queue retries it.
        assertFalse(files.exists())
        assertEquals("none", model.currentState.noticeCode, "automatic sending is silent")
        model.close()

        // The next launch sends no second copy; it drains the queue, which holds the first.
        val next = model()
        next.start()
        advanceUntilIdle()
        assertEquals(1, sent.size)
        assertEquals(2, drains)
        next.close()
    }

    @Test
    fun `automatic sending keeps a report that could not be stored for the next launch`() = runTest {
        optIn = true
        outcome = CrashSendOutcome.FAILED
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        assertEquals(1, sent.size)
        assertTrue(files.exists())
        model.close()
    }

    @Test
    fun `the queue is drained only while automatic sending is on`() = runTest {
        files.write("REPORT")
        val asking = model()
        asking.start()
        advanceUntilIdle()
        assertEquals(0, drains)
        asking.close()

        optIn = false
        val off = model()
        off.start()
        advanceUntilIdle()
        assertEquals(0, drains)
        off.close()
    }

    @Test
    fun `a send from the prompt that no relay took is queued and counts as sent`() = runTest {
        outcome = CrashSendOutcome.NOT_DELIVERED
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        model.send(rememberChoice = true)
        advanceUntilIdle()
        assertEquals("sent", model.currentState.noticeCode)
        assertEquals(true, optIn)
        assertFalse(files.exists(), "not offered again: the queue holds it")
        model.close()
    }

    @Test
    fun `a sender that throws keeps the report`() = runTest {
        optIn = true
        files.write("REPORT")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = CrashReportPromptModel(
            files, { optIn }, { optIn = it }, { error("offline") },
            CoroutineScope(SupervisorJob() + dispatcher), dispatcher,
        )
        model.start()
        advanceUntilIdle()
        assertTrue(files.exists())
        model.close()
    }

    @Test
    fun `switched off deletes the report unsent`() = runTest {
        optIn = false
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
        assertFalse(files.exists())
        model.close()
    }

    @Test
    fun `send from the prompt with remember switches automatic sending on`() = runTest {
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        model.send(rememberChoice = true)
        assertEquals("sending", model.currentState.phaseCode)
        advanceUntilIdle()
        assertEquals("idle", model.currentState.phaseCode)
        assertEquals("sent", model.currentState.noticeCode)
        assertEquals(true, optIn)
        assertFalse(files.exists())
        model.consumeNotice()
        assertEquals("none", model.currentState.noticeCode)
        model.close()
    }

    @Test
    fun `send without remember leaves the choice open`() = runTest {
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        model.send(rememberChoice = false)
        advanceUntilIdle()
        assertNull(optIn)
        assertFalse(files.exists())
        model.close()
    }

    @Test
    fun `a failed send from the prompt says so and keeps the report`() = runTest {
        outcome = CrashSendOutcome.FAILED
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        model.send(rememberChoice = true)
        advanceUntilIdle()
        assertEquals("sendFailed", model.currentState.noticeCode)
        assertNull(optIn, "remember only applies to a report that went out, as on Android")
        assertTrue(files.exists())
        model.close()
    }

    @Test
    fun `no thanks deletes and remember stores the refusal`() = runTest {
        files.write("REPORT")
        val model = model()
        model.start()
        advanceUntilIdle()
        model.decline(rememberChoice = true)
        advanceUntilIdle()
        assertEquals(false, optIn)
        assertFalse(files.exists())
        assertTrue(sent.isEmpty())
        model.close()
    }

    @Test
    fun `the prompt runs once per model and ignores actions while idle`() = runTest {
        optIn = true
        files.write("REPORT")
        val model = model()
        model.start()
        model.start()
        advanceUntilIdle()
        assertEquals(1, sent.size)
        model.send(rememberChoice = true)
        model.decline(rememberChoice = true)
        advanceUntilIdle()
        assertEquals(1, sent.size)
        assertEquals(true, optIn)
        model.close()
    }

    @Test
    fun `settings switch reads an unset choice as off`() {
        var stored: Boolean? = null
        val settings = CrashReportSettingsModel({ stored }, { stored = it })
        assertFalse(settings.isAutoSendOn())
        settings.setAutoSend(true)
        assertTrue(settings.isAutoSendOn())
        settings.setAutoSend(false)
        assertEquals(false, stored)
    }
}
