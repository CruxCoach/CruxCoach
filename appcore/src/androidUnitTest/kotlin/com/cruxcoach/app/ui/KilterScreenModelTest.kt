package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.kilter.FakeCatalogue
import com.cruxcoach.app.kilter.FakeLogbook
import com.cruxcoach.app.kilter.InMemoryKilterUploadLedger
import com.cruxcoach.app.kilter.KilterApi
import com.cruxcoach.app.kilter.KilterBulkOutcome
import com.cruxcoach.app.kilter.KilterClimbIdTables
import com.cruxcoach.app.kilter.KilterDeleteOutcome
import com.cruxcoach.app.kilter.KilterFailure
import com.cruxcoach.app.kilter.KilterLog
import com.cruxcoach.app.kilter.KilterLogImporter
import com.cruxcoach.app.kilter.KilterLogsOutcome
import com.cruxcoach.app.kilter.KilterNotUploadedEntry
import com.cruxcoach.app.kilter.KilterNotUploadedReason
import com.cruxcoach.app.kilter.KilterTokens
import com.cruxcoach.app.kilter.KilterUploadApi
import com.cruxcoach.app.kilter.KilterUploadDiagnostics
import com.cruxcoach.app.kilter.KilterUploadEngine
import com.cruxcoach.app.kilter.KilterUploadReason
import com.cruxcoach.app.kilter.KilterUploadStatus
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.data.repository.RawAscent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Kilter screen's upload block: Android's status wording and actions, the list and the report. */
@OptIn(ExperimentalCoroutinesApi::class)
class KilterScreenModelTest {
    private fun keys(status: KilterUploadStatus) = KilterScreenModel.statusLines(status).map { it.key }

    @Test fun success_is_a_single_line_without_a_problem() {
        assertEquals(listOf("kilter_upload_done"), keys(KilterUploadStatus(uploaded = 200)))
        val state = KilterScreenModel.withUpload(blank(), KilterUploadStatus(uploaded = 200), expired = false)
        assertFalse(state.uploadProblem)
        assertFalse(state.uploadShowEntries)
        assertTrue(state.uploadChecked)
    }

    @Test fun a_partial_failure_is_a_short_problem_without_the_http_code() {
        val status = KilterUploadStatus(uploaded = 200, pending = 1, reason = KilterUploadReason.HTTP, httpStatus = 422)
        assertEquals(listOf("kilter_upload_http"), keys(status))
        val state = KilterScreenModel.withUpload(blank(), status, expired = false)
        assertTrue(state.uploadProblem, "retry and report are offered")
        assertFalse(state.uploadNeedsLogin)
    }

    @Test fun every_count_gets_its_plural_line_in_androids_order() {
        val lines = KilterScreenModel.statusLines(KilterUploadStatus(
            uploaded = 5, rejectedByKilter = 2, unconfirmed = 1, rejectedInvalid = 3, rejectedConflict = 4,
            heldImported = 6, nextRetry = 1,
        ))
        assertEquals(
            listOf("kilter_upload_done_except", "kilter_upload_auto_retry", "kilter_upload_rejected", "kilter_upload_unconfirmed",
                "kilter_upload_invalid", "kilter_upload_conflicts", "kilter_upload_held_imported"),
            lines.map { it.key },
        )
        assertEquals(listOf(0, 0, 2, 1, 3, 4, 6), lines.map { it.count })
        assertEquals(listOf(false, false, true, true, true, true, true), lines.map { it.plural })
        assertEquals(listOf("kilter_upload_pending"), keys(KilterUploadStatus(pending = 3)))
        assertEquals(listOf("kilter_upload_disabled"), keys(KilterUploadStatus(reason = KilterUploadReason.DISABLED)))
        assertEquals(listOf("kilter_upload_timeout"), keys(KilterUploadStatus(reason = KilterUploadReason.TIMEOUT)))
        val wall = KilterScreenModel.statusLines(KilterUploadStatus(reason = KilterUploadReason.WALL_CONTEXT))
        assertEquals(listOf("kilter_upload_wall", "kilter_upload_wall_hint"), wall.map { it.key })
        assertTrue(wall[1].iosOnly)
    }

    @Test fun a_lost_session_asks_for_a_sign_in_and_hides_the_imported_action() {
        val status = KilterUploadStatus(pending = 2, heldImported = 3)
        val expired = KilterScreenModel.withUpload(blank(), status, expired = true)
        assertTrue(expired.uploadNeedsLogin)
        assertTrue(expired.uploadProblem)
        assertEquals(listOf("kilter_upload_auth"), expired.uploadLines.map { it.key })
        assertFalse(expired.uploadShowImported)
        assertTrue(KilterScreenModel.withUpload(blank(), status, expired = false).uploadShowImported)
        // While a run is under way nothing is shown as a problem.
        assertFalse(KilterScreenModel.withUpload(blank().copy(uploading = true), status, expired = true).uploadProblem)
        // Never run yet.
        val unchecked = KilterScreenModel.withUpload(blank(), null, expired = false)
        assertFalse(unchecked.uploadChecked)
        assertEquals(listOf("kilter_upload_unchecked"), unchecked.uploadLines.map { it.key })
    }

    @Test fun list_rows_carry_androids_date_and_the_id_to_report() {
        val row = KilterScreenModel.row(KilterNotUploadedEntry(
            logUuid = "l", climbUuid = "local-id", climbName = null, angle = 40, climbedAt = "2026-09-19 18:30:00",
            isAscent = false, reason = KilterNotUploadedReason.RETRY_LATER, wireId = null,
        ))
        assertEquals("19.09.2026", row.date)
        assertEquals("", row.climbName)
        assertEquals("retryLater", row.reason)
        assertEquals("local-id", row.reportId)
        val refused = KilterScreenModel.row(KilterNotUploadedEntry(
            logUuid = "l", climbUuid = "local-id", climbName = "Ghost", angle = 40, climbedAt = "x",
            isAscent = true, reason = KilterNotUploadedReason.NOT_ON_KILTER, wireId = "WIRE",
        ))
        assertEquals("WIRE", refused.reportId)
        assertEquals("x", refused.date)
        assertEquals("notOnKilter", refused.reason)
    }

    @Test fun the_bug_report_has_androids_shape() {
        assertEquals(
            "Kilter logbook upload\n\nBeschreibung:\nlist\n\n---\niOS 18 | de\nApp 0.2.4 (204001)\n\nKilter upload diagnostics:\nline",
            KilterScreenModel.composeBugReport(" Kilter logbook upload ", "list\n", "iOS 18 | de", "0.2.4 (204001)", "line"),
        )
        assertEquals(
            "t\n\nBeschreibung:\nd\n\n---\niOS 18 | en\nApp v",
            KilterScreenModel.composeBugReport("t", "d", "iOS 18 | en", "v", null),
        )
    }

    // ── The model over a real engine ───────────────────────────────────

    private val db = BrowseTestDb()
    @AfterTest fun close() = db.close()

    private fun blank() = KilterScreenState(
        signedIn = true, userUuid = "", busy = false, phase = "idle", failure = "none",
        retryAfterSeconds = 0, imported = 0, alreadyPresent = 0, unknownClimb = 0,
    )

    private class Secrets : SecretStore {
        val items = HashMap<String, ByteArray>()
        override fun read(name: String) = items[name]
        override fun write(name: String, value: ByteArray): Boolean { items[name] = value; return true }
        override fun delete(name: String) = items.remove(name) != null
    }

    private val noNetwork = object : HttpTransport {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int) =
            HttpResult.Failed(HttpFailure.OFFLINE, "NSURLErrorDomain:-1009")
        override suspend fun download(url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit) =
            HttpResult.Failed(HttpFailure.OFFLINE, "")
    }

    @Test fun uploading_shows_the_engines_status_then_the_list_and_the_report() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = MemoryKeyValueStore()
        val tokens = KilterTokens(Secrets(), prefs, "abc").apply {
            store("a", "r", 4_000_000_000)
            storeUserUuid("user-1")
            storeWallContext(com.cruxcoach.app.kilter.KilterWallContext("g", "w", "1"))
        }
        val logbook = FakeLogbook()
        fun ascent(uuid: String, climb: String, external: String? = null) = RawAscent(
            uuid = uuid, climbUuid = climb, angle = 40, isMirror = false, attemptId = 1, bidCount = 2, quality = null,
            difficulty = null, isBenchmark = false, comment = null, climbedAt = "2026-09-19T18:30:00Z", synced = false,
            externalId = external,
        )
        logbook.ascents["good"] = ascent("good", "climb-ok")
        logbook.ascents["bad"] = ascent("bad", "ghost").copy(climbedAt = "2026-09-20T18:30:00Z")
        logbook.ascents["imported"] = ascent("imported", "climb-ok", "aurora-json:ascent:1").copy(climbedAt = "2026-09-10T18:30:00Z")
        val catalogue = FakeCatalogue().apply { names["ghost"] = "Ghost Climb" }
        val sent = ArrayList<String>()
        val api = object : KilterUploadApi {
            override suspend fun fetchLogs() = KilterLogsOutcome(emptyList<KilterLog>(), KilterFailure.NONE)
            override suspend fun uploadLogs(logs: List<KilterLog>): KilterBulkOutcome {
                sent += logs.map { it.logUuid }
                return KilterBulkOutcome(if (logs.any { it.climbUuid == "ghost" }) 500 else 200, KilterFailure.NONE, true)
            }
            override suspend fun deleteLogIfHeld(logUuid: String) = KilterDeleteOutcome.NOT_HELD
            override suspend fun deleteLog(logUuid: String) = KilterFailure.NONE
        }
        val ledger = InMemoryKilterUploadLedger()
        val engine = KilterUploadEngine(
            api, tokens, logbook, catalogue, ledger, KilterUploadDiagnostics(prefs, "abc") { 1_000L },
            KilterClimbIdTables.EMPTY, "0.2.4", 204_001, clock = { 1_000L },
        )
        // Not backgroundScope: advanceUntilIdle does not run its work.
        val runScope = CoroutineScope(SupervisorJob() + dispatcher)
        val model = KilterScreenModel(
            KilterApi(noNetwork, FixedClock(1), tokens), tokens, KilterLogImporter(db.boardRepo, db.personalRepo),
            engine, runScope, "0.2.4 (204001)", main = dispatcher, io = dispatcher,
        )
        advanceUntilIdle()
        assertEquals(listOf("kilter_upload_unchecked"), model.currentState.uploadLines.map { it.key })

        model.pushLogs()
        advanceUntilIdle()
        val after = model.currentState
        assertFalse(after.uploading)
        assertEquals("pushed", after.phase)
        assertEquals(listOf("kilter_upload_done_except", "kilter_upload_rejected", "kilter_upload_held_imported"), after.uploadLines.map { it.key })
        assertTrue(after.uploadShowEntries)
        assertTrue(after.uploadShowImported)
        assertFalse(after.uploadProblem)

        model.loadNotUploaded()
        advanceUntilIdle()
        assertEquals("loaded", model.currentState.notUploadedState)
        val row = model.currentState.notUploaded.single()
        assertEquals("bad", row.logUuid)
        assertEquals("Ghost Climb", row.climbName)
        assertEquals("notOnKilter", row.reason)

        model.loadDiagnostics()
        advanceUntilIdle()
        val report = model.bugReportText("t", "d", "iOS 18 | en", attachDiagnostics = true)
        assertTrue(report.contains("Kilter upload diagnostics:\napp=0.2.4 (204001) operation=logs.bulk"), report)
        assertFalse(model.bugReportText("t", "d", "iOS 18 | en", attachDiagnostics = false).contains("diagnostics"))

        // The opt-in uploads the imported entry, and ends with the run that worked through it.
        model.uploadImported()
        advanceUntilIdle()
        assertTrue("imported" in sent)
        assertFalse(ledger.importedEnabled)
        assertFalse(model.currentState.uploadShowImported)

        // A lost session: a new sign-in rather than another upload.
        model.signInAgain()
        assertFalse(model.currentState.signedIn)
        assertFalse(tokens.isSignedIn)
        model.close()
        runScope.cancel()
    }
}
