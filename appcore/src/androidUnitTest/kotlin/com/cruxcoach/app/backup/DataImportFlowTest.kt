package com.cruxcoach.app.backup

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.sync.JvmFileSystem
import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.ui.DataExchangeScreenModel
import com.cruxcoach.app.ui.DataExchangeScreenState
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Android's DataImportScreen flow on the iOS presenter and its Swift facade:
 * preview first, category choice, identity-mismatch confirmation, then the
 * import of the chosen categories, with Android's error texts.
 */
class DataImportFlowTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher
    private val root: File = File.createTempFile("import", "").let {
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
        override fun importJson(json: String, expectedNostrPubkey: String): BackupImportSummary? =
            error("the manual import never takes the all-categories cloud path")
    }

    private class Call(val json: String, val categories: Set<Category>, val expected: String?, val adopt: String?)

    private class FakeImporter(
        var result: CruxCoachBackup.ImportResult = CruxCoachBackup.ImportResult(boardAscents = 1, boardBids = 2, climbNotes = 1),
        var failure: Exception? = null,
    ) : ManualImportStore {
        val calls = mutableListOf<Call>()
        override fun importSelected(
            json: String,
            categories: Set<Category>,
            expectedNostrPubkey: String?,
            adoptLocalDraftsForPubkey: String?,
        ): CruxCoachBackup.ImportResult {
            calls += Call(json, categories, expectedNostrPubkey, adoptLocalDraftsForPubkey)
            failure?.let { throw it }
            return result
        }
    }

    private fun presenter(
        importer: FakeImporter,
        pubkey: String = me,
        catalogue: MutableStateFlow<Boolean> = MutableStateFlow(false),
        maxBytes: Long = ImportFiles.MAX_IMPORT_BYTES,
    ) = FileExchangePresenter(
        payloads = NoExport(),
        files = files,
        clock = clock,
        pubkeyHex = { pubkey },
        importer = importer,
        catalogueLoading = catalogue,
        scope = CoroutineScope(serial),
        ioDispatcher = serial,
        maxImportBytes = maxBytes,
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

    private val climb = "22222222-2222-2222-2222-222222222222"

    /** A JSON export with 1 send, 2 attempts, 1 list and 1 private note. */
    private fun backup(owner: String?): String = buildString {
        append("""{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z",""")
        if (owner != null) append(""""nostrPubkey":"$owner",""")
        append(""""boardAscents":[{"uuid":"11111111-1111-1111-1111-111111111111","climbUuid":"$climb","angle":40,"isMirror":false,"bidCount":1,"climbedAt":"2026-09-01 18:30:00"}],""")
        append(""""boardBids":[""")
        append("""{"uuid":"44444444-4444-4444-4444-444444444444","climbUuid":"$climb","angle":40,"isMirror":false,"bidCount":2,"climbedAt":"2026-09-02 18:30:00"},""")
        append("""{"uuid":"55555555-5555-5555-5555-555555555555","climbUuid":"$climb","angle":45,"isMirror":false,"bidCount":1,"climbedAt":"2026-09-03 18:30:00"}],""")
        append(""""climbLists":[{"name":"Projects","isBuiltin":false,"createdAt":"2026-09-01 10:00:00","entries":["$climb"]}],""")
        append(""""climbNotes":[{"climbUuid":"$climb","note":"left heel","updatedAt":"2026-09-03 10:00:00"}]}""")
    }

    private fun file(name: String, text: String) = File(root, name).apply { writeText(text) }.absolutePath

    private suspend fun previewed(presenter: FileExchangePresenter, path: String): FileExchangeState {
        presenter.loadPreview(path)
        return await(presenter) { !it.isLoadingPreview && (it.preview != null || it.error != FileExchangeError.NONE) }
    }

    private fun screen(presenter: FileExchangePresenter): DataExchangeScreenState {
        val state = DataExchangeScreenModel(presenter).currentState
        TrTextCheck.checkAll(state)
        return state
    }

    private fun keys(text: TrText): List<String> =
        listOf(text.key).filter { it.isNotEmpty() } + text.args.mapNotNull { arg ->
            when (arg.kind) {
                "key" -> listOf(arg.text)
                "nested" -> arg.nested?.let { keys(it) }
                else -> null
            }
        }.flatten()

    @Test
    fun `a picked file is previewed and nothing is written before the user confirms`() = runBlocking {
        val importer = FakeImporter()
        val presenter = presenter(importer)
        val done = previewed(presenter, file("mine.json", backup(me)))

        assertEquals(FileExchangeError.NONE, done.error)
        val preview = assertNotNull(done.preview)
        assertEquals(1, preview.boardAscents)
        assertEquals(2, preview.boardBids)
        assertEquals(1, preview.climbLists)
        assertEquals(1, preview.climbNotes)
        // Notes fold into the logbook row, as on Android.
        assertEquals(setOf(Category.BOARD_LOGBOOK, Category.CLIMB_LISTS), done.importCategories)
        assertFalse(done.importPubkeyMismatch)
        assertTrue(importer.calls.isEmpty(), "a preview never writes")

        val ui = screen(presenter)
        assertTrue(ui.hasPreview)
        assertFalse(ui.previewEmpty)
        assertTrue(ui.canImport)
        assertEquals(listOf("board_logbook", "climb_lists"), ui.categories.map { it.code })
        assertEquals("export_category_board_logbook", ui.categories[0].label.key)
        val logbookCount = ui.categories[0].countLine
        assertEquals(", ", logbookCount.joinWith)
        assertEquals(
            listOf("import_result_board_sends" to 1, "import_result_board_bids" to 2),
            logbookCount.args.map { it.nested!!.key to it.nested!!.pluralCount },
        )
        assertEquals("import_result_lists", ui.categories[1].countLine.key)
        presenter.close()
    }

    @Test
    fun `only the chosen categories are imported, notes travel with the logbook`() = runBlocking {
        val importer = FakeImporter()
        val presenter = presenter(importer)
        val json = backup(me)
        previewed(presenter, file("mine.json", json))
        val model = DataExchangeScreenModel(presenter)

        model.toggleCategory("climb_lists")
        assertEquals(setOf(Category.BOARD_LOGBOOK), presenter.state.value.importCategories)
        model.confirmImport()
        val done = await(presenter) { it.importResult != null || it.error != FileExchangeError.NONE }

        assertEquals(FileExchangeError.NONE, done.error)
        val call = importer.calls.single()
        assertEquals(json, call.json)
        assertEquals(setOf(Category.BOARD_LOGBOOK, Category.CLIMB_NOTES), call.categories)
        assertEquals(me, call.expected, "the codec refuses another identity by default")
        assertNull(call.adopt)
        assertNull(done.preview, "the preview closes after the import")

        val ui = screen(presenter)
        assertFalse(ui.hasPreview)
        assertEquals("import_result_summary", ui.resultText.key)
        val summary = ui.resultText.args.single().nested!!
        val parts = summary.args.first().nested!!
        assertEquals(
            listOf("import_result_board_sends", "import_result_board_bids", "import_result_notes"),
            parts.args.map { it.nested!!.key },
        )
        presenter.close()
    }

    @Test
    fun `the import summary reports duplicates and says no data when nothing was new`() = runBlocking {
        val importer = FakeImporter(result = CruxCoachBackup.ImportResult(skippedDuplicates = 3))
        val presenter = presenter(importer)
        previewed(presenter, file("mine.json", backup(me)))
        presenter.confirmImport()
        await(presenter) { it.importResult != null }

        val whole = screen(presenter).resultText.args.single().nested!!
        assertEquals(listOf("import_result_no_data", "", "import_result_duplicates_skipped"), whole.args.map { it.nested!!.key })
        assertEquals(3, whole.args[2].nested!!.pluralCount)
        presenter.close()
    }

    @Test
    fun `deselecting every category disables the import`() = runBlocking {
        val importer = FakeImporter()
        val presenter = presenter(importer)
        previewed(presenter, file("mine.json", backup(me)))
        presenter.toggleCategory(Category.BOARD_LOGBOOK)
        presenter.toggleCategory(Category.CLIMB_LISTS)
        assertFalse(screen(presenter).canImport)

        presenter.confirmImport()
        assertEquals(FileExchangeError.SELECT_CATEGORY, presenter.state.value.error)
        assertEquals(listOf("error_select_category"), keys(screen(presenter).errorText))
        assertTrue(importer.calls.isEmpty())
        presenter.close()
    }

    @Test
    fun `a file of another identity needs an explicit import anyway`() = runBlocking {
        val importer = FakeImporter()
        val presenter = presenter(importer)
        val done = previewed(presenter, file("theirs.json", backup(other)))
        assertTrue(done.importPubkeyMismatch)
        assertEquals(other, done.importSourcePubkey)

        val dialog = screen(presenter)
        assertTrue(dialog.pubkeyMismatch)
        assertTrue(dialog.sourceNpubDialog.startsWith("npub1"), dialog.sourceNpubDialog)
        assertEquals(12 + 3 + 6, dialog.sourceNpubDialog.length)
        assertTrue(dialog.sourceNpubDialog.contains("..."))

        // Nothing happens while the dialog is up.
        presenter.confirmImport()
        assertTrue(importer.calls.isEmpty())

        presenter.confirmMismatchImport()
        val banner = screen(presenter)
        assertFalse(banner.pubkeyMismatch)
        assertTrue(banner.mismatchAccepted)
        assertTrue(banner.sourceNpubBanner.contains("…"))
        // The dialog's own dismissal after "import anyway" must not drop the file.
        presenter.dismissPubkeyMismatch()
        assertNotNull(presenter.state.value.preview)

        presenter.confirmImport()
        val imported = await(presenter) { it.importResult != null || it.error != FileExchangeError.NONE }
        assertEquals(FileExchangeError.NONE, imported.error)
        val call = importer.calls.single()
        assertNull(call.expected, "the user accepted the other identity")
        assertEquals(me, call.adopt, "the active identity takes over the drafts")
        assertFalse(imported.importMismatchAccepted)
        presenter.close()
    }

    @Test
    fun `cancelling the mismatch dialog drops the file`() = runBlocking {
        val importer = FakeImporter()
        val presenter = presenter(importer)
        previewed(presenter, file("theirs.json", backup(other)))
        presenter.dismissPubkeyMismatch()
        val state = presenter.state.value
        assertNull(state.preview)
        assertFalse(state.importPubkeyMismatch)
        assertFalse(state.importMismatchAccepted)
        presenter.confirmImport()
        assertTrue(importer.calls.isEmpty(), "the dropped document cannot be imported any more")
        presenter.close()
    }

    @Test
    fun `a file without identity is no mismatch and cancel drops the preview`() = runBlocking {
        val presenter = presenter(FakeImporter())
        val done = previewed(presenter, file("anon.json", backup(null)))
        assertFalse(done.importPubkeyMismatch)
        presenter.cancelImport()
        assertNull(presenter.state.value.preview)
        presenter.close()
    }

    @Test
    fun `an export without data says so`() = runBlocking {
        val presenter = presenter(FakeImporter())
        previewed(presenter, file("empty.json", """{"version":3,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z"}"""))
        val ui = screen(presenter)
        assertTrue(ui.hasPreview)
        assertTrue(ui.previewEmpty)
        assertTrue(ui.categories.isEmpty())
        assertFalse(ui.canImport)
        presenter.close()
    }

    private suspend fun rejected(presenter: FileExchangePresenter, path: String): Pair<FileExchangeState, List<String>> {
        val done = previewed(presenter, path)
        assertEquals(FileExchangeError.FILE_NOT_READABLE, done.error)
        assertNull(done.preview)
        return done to keys(screen(presenter).errorText)
    }

    @Test
    fun `files that are not ours get Android's specific reasons`() = runBlocking {
        val importer = FakeImporter()
        val presenter = presenter(importer, maxBytes = 4096)

        val (notOurs, notOursKeys) = rejected(presenter, file("notes.txt", "hello"))
        assertEquals(ImportFailure.NOT_CRUXCOACH_EXPORT, notOurs.importFailure)
        assertEquals(listOf("error_file_not_readable", "error_not_cruxcoach_export"), notOursKeys)

        val (tooLarge, tooLargeKeys) = rejected(presenter, file("huge.json", "{\"app\":\"CruxCoach\"," + " ".repeat(5000) + "}"))
        assertEquals(ImportFailure.TOO_LARGE, tooLarge.importFailure)
        assertEquals(listOf("error_file_not_readable", "error_import_too_large"), tooLargeKeys)

        val (missing, missingKeys) = rejected(presenter, File(root, "gone.json").absolutePath)
        assertEquals(ImportFailure.CANNOT_READ, missing.importFailure)
        assertEquals(listOf("error_file_not_readable", "error_cannot_read_file"), missingKeys)

        val (broken, brokenKeys) = rejected(presenter, file("broken.json", """{"app":"CruxCoach","version":3,"boardAscents":[{"comment":"private"""))
        assertEquals(ImportFailure.INVALID_FORMAT, broken.importFailure)
        assertEquals(listOf("error_file_not_readable", "error_import_invalid_format"), brokenKeys)

        val (newer, newerKeys) = rejected(presenter, file("newer.json", """{"version":4,"app":"CruxCoach","exportedAt":"2026-09-20T08:00:00Z"}"""))
        assertEquals(ImportFailure.NEWER_VERSION, newer.importFailure)
        assertEquals(listOf("error_file_not_readable", "error_import_newer_version"), newerKeys)

        presenter.consumeError()
        assertEquals("none", screen(presenter).errorCode)
        assertTrue(importer.calls.isEmpty())
        presenter.close()
    }

    @Test
    fun `an excel workbook is refused at the preview, not imported`() = runBlocking {
        val workbook = CruxCoachExcelWorkbook.fromJson(
            """{"version":3,"app":"CruxCoach","boardAscents":[{"climbUuid":"c1","angle":40}],"boardBids":[]}""",
            CruxCoachCsvArchive.ALL_CATEGORIES,
        )
        val path = File(root, CruxCoachExcelWorkbook.FILE_NAME).apply { writeBytes(workbook) }.absolutePath
        val importer = FakeImporter()
        val presenter = presenter(importer)
        val (state, keys) = rejected(presenter, path)
        assertEquals(ImportFailure.OTHER, state.importFailure)
        assertTrue(state.importFailureDetail.endsWith("Exception"), state.importFailureDetail)
        assertEquals(listOf("error_file_not_readable"), keys, "the class name is plain text")
        assertTrue(importer.calls.isEmpty())
        presenter.close()
    }

    @Test
    fun `a csv zip is previewed like the json export`() = runBlocking {
        val archive = CruxCoachCsvArchive.fromJson(backup(me), CruxCoachCsvArchive.ALL_CATEGORIES)
        val path = File(root, CruxCoachCsvArchive.FILE_NAME).apply { writeBytes(archive) }.absolutePath
        val importer = FakeImporter()
        val presenter = presenter(importer)
        val done = previewed(presenter, path)
        assertEquals(FileExchangeError.NONE, done.error)
        assertEquals(1, done.preview!!.boardAscents)
        assertEquals(2, done.preview!!.boardBids)
        assertFalse(done.importPubkeyMismatch, "the archive keeps the owner")
        presenter.confirmImport()
        await(presenter) { it.importResult != null }
        assertEquals(CruxCoachCsvArchive.toJson(archive), importer.calls.single().json)
        presenter.close()
    }

    @Test
    fun `a failed import keeps the preview and gives the reason`() = runBlocking {
        val importer = FakeImporter(
            failure = IllegalArgumentException("invalid backup: nostrPubkey does not match active signer (payload 3bf0c63f…)"),
        )
        val presenter = presenter(importer)
        previewed(presenter, file("mine.json", backup(me)))
        presenter.confirmImport()
        val failed = await(presenter) { it.error != FileExchangeError.NONE }
        assertEquals(FileExchangeError.IMPORT_FAILED, failed.error)
        assertEquals(ImportFailure.INVALID_BACKUP, failed.importFailure)
        assertNotNull(failed.preview, "the user can retry or cancel")
        assertFalse(failed.isImporting)

        val error = screen(presenter).errorText
        assertEquals("error_import_failed", error.key)
        assertEquals("text", error.args.single().kind)
        assertTrue(error.args.single().text.startsWith("invalid backup: nostrPubkey does not match"))

        // An unknown failure shows only its class, never its message.
        importer.failure = IllegalStateException("user text from the file")
        presenter.consumeError()
        presenter.confirmImport()
        val other = await(presenter) { it.error != FileExchangeError.NONE }
        assertEquals("IllegalStateException", other.importFailureDetail)
        presenter.close()
    }

    @Test
    fun `document access is released after the preview and on cancellation`() = runBlocking {
        val presenter = presenter(FakeImporter())
        val path = file("scoped.json", backup(me))
        val finished = CompletableDeferred<Unit>()
        presenter.loadPreview(path) {
            assertNotNull(presenter.state.value.preview)
            finished.complete(Unit)
        }
        withTimeout(CI_WAIT_MS) { finished.await() }
        presenter.close()

        val other = presenter(FakeImporter())
        other.close()
        val cancelled = CompletableDeferred<Unit>()
        other.loadPreview(path) { cancelled.complete(Unit) }
        withTimeout(CI_WAIT_MS) { cancelled.await() }
    }

    @Test
    fun `the catalogue hint follows the running catalogue sync`() = runBlocking {
        val catalogue = MutableStateFlow(false)
        val presenter = presenter(FakeImporter(), catalogue = catalogue)
        catalogue.value = true
        await(presenter) { it.catalogueLoading }
        assertTrue(screen(presenter).catalogueLoading)
        catalogue.value = false
        await(presenter) { !it.catalogueLoading }
        presenter.close()
    }

    @Test
    fun `no identity means no import`() = runBlocking {
        val importer = FakeImporter()
        val presenter = presenter(importer, pubkey = "")
        var released = false
        presenter.loadPreview(file("mine.json", backup(me))) { released = true }
        assertEquals(FileExchangeError.NO_IDENTITY, presenter.state.value.error)
        assertTrue(released)
        assertTrue(screen(presenter).errorText.isEmpty)
        presenter.close()
    }
}
