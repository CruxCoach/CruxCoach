package com.cruxcoach.app.background

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Sync continuously" at the start, as Android's KilterSyncEngine.syncOnAppStartIfEnabled decides
 * it: gated on the choice and a session, the upload only with "Upload to Kilter", a refused
 * session or a missing token stops before the download, and "last sync" is written only when
 * something moved and the upload left nothing behind.
 */
class KilterContinuousSyncStepTest {
    private var syncEnabled = true
    private var signedIn = true
    private var pushEnabled = false
    private var uploadResult: KilterStartUpload? = KilterStartUpload(uploaded = 0, authenticationFailed = false, failed = false, pending = 0)
    private var tokenOk = true
    private var downloaded = 0
    private val calls = ArrayList<String>()

    private fun step() = KilterContinuousSyncStep(
        syncEnabled = { syncEnabled },
        signedIn = { signedIn },
        pushEnabled = { pushEnabled },
        upload = { calls += "upload"; uploadResult },
        freshToken = { calls += "token"; tokenOk },
        download = { calls += "download"; downloaded },
        recordLastSync = { calls += "lastSync" },
    )

    @Test
    fun `runs once per start and only with the choice and a session`() = runTest {
        val step = step()
        syncEnabled = false
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_FOREGROUND))
        syncEnabled = true
        signedIn = false
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_FOREGROUND))
        signedIn = true
        assertTrue(step.enabled(BackgroundRefresh.TRIGGER_FOREGROUND))
        assertTrue(step.run().ok)
        assertFalse(step.enabled(BackgroundRefresh.TRIGGER_FOREGROUND), "once per start")
    }

    @Test
    fun `without upload switched on only the logbook comes down`() = runTest {
        downloaded = 3
        step().run()
        assertEquals(listOf("token", "download", "lastSync"), calls)
    }

    @Test
    fun `nothing new and nothing uploaded leaves the last sync alone`() = runTest {
        pushEnabled = true
        step().run()
        assertEquals(listOf("upload", "token", "download"), calls)
    }

    @Test
    fun `an upload records the sync when nothing is left behind`() = runTest {
        pushEnabled = true
        uploadResult = KilterStartUpload(uploaded = 2, authenticationFailed = false, failed = false, pending = 0)
        step().run()
        assertEquals(listOf("upload", "token", "download", "lastSync"), calls)
    }

    @Test
    fun `a failed or unfinished upload never counts as a sync`() = runTest {
        pushEnabled = true
        downloaded = 4
        uploadResult = KilterStartUpload(uploaded = 1, authenticationFailed = false, failed = true, pending = 0)
        step().run()
        assertFalse("lastSync" in calls)
        calls.clear()
        uploadResult = KilterStartUpload(uploaded = 1, authenticationFailed = false, failed = false, pending = 2)
        step().run()
        assertFalse("lastSync" in calls)
    }

    @Test
    fun `a refused session or a missing token stops before the download`() = runTest {
        pushEnabled = true
        uploadResult = KilterStartUpload(uploaded = 0, authenticationFailed = true, failed = true, pending = 3)
        step().run()
        assertEquals(listOf("upload"), calls)
        calls.clear()
        pushEnabled = false
        tokenOk = false
        step().run()
        assertEquals(listOf("token"), calls)
    }

    @Test
    fun `a failed logbook fetch writes nothing`() = runTest {
        downloaded = -1
        assertTrue(step().run().ok)
        assertEquals(listOf("token", "download"), calls)
    }
}
