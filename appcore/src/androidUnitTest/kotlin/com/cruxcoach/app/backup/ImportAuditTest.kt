package com.cruxcoach.app.backup

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Android's audit line for an import across identities (`DataExchangeViewModel.confirmImport`,
 * `import-with-pubkey-override`): written exactly when the user accepted another identity's file,
 * with no more than 8 hex characters of either key.
 */
class ImportAuditTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val root: File = File.createTempFile("audit", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val files = JvmFileSystem(root)
    private val clock = object : WallClock {
        override fun epochSeconds(): Long = 1_789_800_000L
        override fun epochMillis(): Long = epochSeconds() * 1000
    }
    private val me = "a".repeat(64)
    private val other = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private class NoExport : BackupPayloadStore {
        override fun exportJson(exportedAt: String, nostrPubkey: String, categories: Set<com.cruxcoach.data.CruxCoachBackup.Category>): String? = null
        override fun importJson(json: String, expectedNostrPubkey: String): BackupImportSummary? = null
    }

    private class CountingImporter : ManualImportStore {
        var calls = 0
        override fun importSelected(
            json: String,
            categories: Set<Category>,
            expectedNostrPubkey: String?,
            adoptLocalDraftsForPubkey: String?,
        ): CruxCoachBackup.ImportResult {
            calls++
            return CruxCoachBackup.ImportResult(boardAscents = 1)
        }
    }

    private fun presenter(importer: CountingImporter, audit: MutableList<String>) = FileExchangePresenter(
        payloads = NoExport(),
        files = files,
        clock = clock,
        pubkeyHex = { me },
        importer = importer,
        scope = CoroutineScope(serial),
        ioDispatcher = serial,
        audit = { audit += it },
    )

    private fun backup(owner: String): String =
        """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z","nostrPubkey":"$owner",""" +
            """"boardAscents":[{"uuid":"11111111-1111-1111-1111-111111111111","climbUuid":"22222222-2222-2222-2222-222222222222",""" +
            """"angle":40,"isMirror":false,"bidCount":1,"climbedAt":"2026-09-01 18:30:00"}]}"""

    private suspend fun await(presenter: FileExchangePresenter, predicate: (FileExchangeState) -> Boolean): FileExchangeState =
        withTimeout(CI_WAIT_MS) {
            var seen = presenter.state.value
            while (!predicate(seen)) {
                delay(5)
                seen = presenter.state.value
            }
            seen
        }

    private suspend fun importFile(presenter: FileExchangePresenter, owner: String, accept: Boolean): FileExchangeState {
        val path = File(root, "export-$owner.json").apply { writeText(backup(owner)) }.absolutePath
        presenter.loadPreview(path)
        await(presenter) { !it.isLoadingPreview && it.preview != null }
        if (accept) presenter.confirmMismatchImport()
        presenter.confirmImport()
        return await(presenter) { it.importResult != null || it.error != FileExchangeError.NONE }
    }

    @Test
    fun `an accepted import of another identity writes Android's audit line`() = runBlocking {
        val audit = mutableListOf<String>()
        val importer = CountingImporter()
        val presenter = presenter(importer, audit)
        val done = importFile(presenter, other, accept = true)
        assertEquals(FileExchangeError.NONE, done.error)
        assertEquals(1, importer.calls)
        assertEquals(listOf("import-with-pubkey-override: source=3bf0c63f… active=aaaaaaaa…"), audit)
        // Never a whole key.
        assertFalse(audit.single().contains(other))
        assertFalse(audit.single().contains(me))
        presenter.close()
    }

    @Test
    fun `an import of the own identity writes no audit line`() = runBlocking {
        val audit = mutableListOf<String>()
        val importer = CountingImporter()
        val presenter = presenter(importer, audit)
        importFile(presenter, me, accept = false)
        assertEquals(1, importer.calls)
        assertTrue(audit.isEmpty())
        presenter.close()
    }

    @Test
    fun `the line marks an unknown source`() {
        assertEquals(
            "import-with-pubkey-override: source=?… active=abcdef01…",
            FileExchangePresenter.overrideAuditLine(null, "abcdef0123456789"),
        )
    }
}
