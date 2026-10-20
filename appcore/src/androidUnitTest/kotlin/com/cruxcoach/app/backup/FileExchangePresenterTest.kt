package com.cruxcoach.app.backup

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.sync.JvmFileSystem
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
 * The payload codec itself is `:shared` and tested there; what matters here is
 * that a file really appears, that it is the payload, and that a file the
 * store rejects is reported rather than silently swallowed.
 */
class FileExchangePresenterTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val root: File = File.createTempFile("exchange", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val files = JvmFileSystem(root)
    private val clock = object : WallClock {
        override fun epochSeconds(): Long = 1_789_800_000L
        override fun epochMillis(): Long = epochSeconds() * 1000
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private class FakeStore(
        var payload: String? = """{"ascents":[]}""",
        var summary: BackupImportSummary? = BackupImportSummary(6, 0, 3, 2, 1),
    ) : BackupPayloadStore {
        var exportedFor: String? = null
        var imported: String? = null
        override fun exportJson(exportedAt: String, nostrPubkey: String, categories: Set<com.cruxcoach.data.CruxCoachBackup.Category>): String? {
            exportedFor = nostrPubkey
            return payload
        }

        override fun importJson(json: String, expectedNostrPubkey: String): BackupImportSummary? {
            imported = json
            return summary
        }
    }

    private fun presenter(store: FakeStore, pubkey: String = "abcd") = FileExchangePresenter(
        payloads = store,
        files = files,
        clock = clock,
        pubkeyHex = { pubkey },
        scope = CoroutineScope(serial),
        ioDispatcher = serial,
    )

    private suspend fun await(
        presenter: FileExchangePresenter,
        predicate: (FileExchangeState) -> Boolean,
    ): FileExchangeState = withTimeout(CI_WAIT_MS) {
        var seen = presenter.state.value
        while (!predicate(seen)) {
            delay(5)
            seen = presenter.state.value
        }
        seen
    }

    @Test
    fun `export writes the payload and discarding removes the file`() = runBlocking {
        val store = FakeStore()
        val presenter = presenter(store)
        presenter.export()

        val done = await(presenter) { it.exportPath.isNotEmpty() || it.error != FileExchangeError.NONE }
        assertEquals(FileExchangeError.NONE, done.error)
        val file = File(done.exportPath)
        assertTrue(file.isFile, "no file at ${done.exportPath}")
        assertEquals(store.payload, file.readText())
        assertEquals(file.length(), done.exportBytes)
        assertEquals("abcd", store.exportedFor, "the export is bound to the active identity")

        presenter.discardExport()
        await(presenter) { it.exportPath.isEmpty() }
        assertFalse(file.exists(), "the export must not linger after it was discarded")
        presenter.close()
    }

    @Test
    fun `the csv zip export writes Android's archive from the same payload`() = runBlocking {
        val store = FakeStore(
            payload = """{"version":3,"app":"CruxCoach","boardAscents":[{"climbUuid":"c1","angle":40}],"boardBids":[]}""",
        )
        val presenter = presenter(store)
        presenter.exportCsvZip()

        val done = await(presenter) { it.exportPath.isNotEmpty() || it.error != FileExchangeError.NONE }
        assertEquals(FileExchangeError.NONE, done.error)
        val file = File(done.exportPath)
        assertEquals("cruxcoach_csv_export.zip", file.name)
        assertEquals(file.length(), done.exportBytes)
        val names = java.util.zip.ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals(listOf("manifest.json", "board_logbook.csv", "climb_notes.csv", "climb_lists.csv", "own_climbs.csv"), names)
        presenter.discardExport()
        await(presenter) { it.exportPath.isEmpty() }
        assertFalse(file.exists())

        // A payload the archive cannot read is an export failure, not a crash or an empty file.
        store.payload = "[]"
        presenter.exportCsvZip()
        val failed = await(presenter) { it.error != FileExchangeError.NONE }
        assertEquals(FileExchangeError.EXPORT_FAILED, failed.error)
        assertFalse(File(root, "cruxcoach_csv_export.zip").exists())

        // Android's readable XLSX copy from the same payload.
        store.payload = """{"version":3,"app":"CruxCoach","boardAscents":[{"climbUuid":"c1","angle":40}],"boardBids":[]}"""
        presenter.consumeError()
        presenter.exportExcel()
        val excel = await(presenter) { it.exportPath.isNotEmpty() || it.error != FileExchangeError.NONE }
        assertEquals(FileExchangeError.NONE, excel.error)
        assertEquals("cruxcoach_readable_export.xlsx", File(excel.exportPath).name)
        val parts = java.util.zip.ZipFile(File(excel.exportPath)).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals("[Content_Types].xml", parts.first())
        assertTrue("xl/worksheets/sheet4.xml" in parts)
        presenter.close()
    }

    @Test
    fun `a failed export is reported instead of leaving a file behind`() = runBlocking {
        val store = FakeStore(payload = null)
        val presenter = presenter(store)
        presenter.export()

        val done = await(presenter) { it.error != FileExchangeError.NONE || it.exportPath.isNotEmpty() }
        assertEquals(FileExchangeError.EXPORT_FAILED, done.error)
        assertTrue(done.exportPath.isEmpty())
        assertTrue(root.listFiles()?.isEmpty() != false, "nothing may be written on failure")
        presenter.close()
    }

    @Test
    fun `no identity means no export`() = runBlocking {
        val presenter = presenter(FakeStore(), pubkey = "")
        presenter.export()
        assertEquals(FileExchangeError.NO_IDENTITY, presenter.state.value.error)
        presenter.close()
    }
}
