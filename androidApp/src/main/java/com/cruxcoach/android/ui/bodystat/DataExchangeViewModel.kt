package com.cruxcoach.android.ui.bodystat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category
import com.cruxcoach.data.CruxCoachCsv
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.repository.*
import com.cruxcoach.util.DateTimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class DataExchangeFormat { JSON, CSV }

/** Categories available in the manual JSON/CSV export/import UI.
 *
 * Keep this list aligned with data users can actually create or inspect in the
 * released app. The training surface is still intentionally hidden, so showing
 * profile, assessment, body-stat, workout, generic climb-log, or training-plan
 * rows here promises controls the rest of the app does not offer. Board-session
 * history is also internal-only for now; playlists themselves are included in
 * [Category.CLIMB_LISTS]. Private climb notes travel with
 * [Category.BOARD_LOGBOOK] instead of occupying a separate UI category.
 *
 * [com.cruxcoach.android.nostr.backup.BackupRepository] deliberately continues
 * to use every codec category for automatic full recovery. When a hidden
 * feature gets a real user-facing entry point, add its category here as part of
 * that feature.
 */
val VISIBLE_CATEGORIES: Set<Category> = setOf(
    Category.BOARD_LOGBOOK,
    Category.CLIMB_LISTS,
    Category.OWN_CLIMBS,
)

/** Translate the compact manual selection into the more granular wire format. */
internal fun Set<Category>.withBundledClimbNotes(): Set<Category> =
    if (Category.BOARD_LOGBOOK in this) this + Category.CLIMB_NOTES else this

/** Collapse wire-format notes back into the user-facing logbook category. */
internal fun Set<Category>.toManualCategories(): Set<Category> = buildSet {
    addAll(this@toManualCategories.intersect(VISIBLE_CATEGORIES))
    if (Category.CLIMB_NOTES in this@toManualCategories) add(Category.BOARD_LOGBOOK)
}

data class DataExchangeState(
    // Export
    val exportFormat: DataExchangeFormat = DataExchangeFormat.JSON,
    val exportCategories: Set<Category> = VISIBLE_CATEGORIES,
    val isExporting: Boolean = false,
    val pendingShare: ExportShare? = null,

    // Import — two-phase: preview → confirm
    val isLoadingPreview: Boolean = false,
    val importPreview: CruxCoachBackup.ImportPreview? = null,
    val importCategories: Set<Category> = emptySet(),
    val pendingImportJson: String? = null,
    val isImporting: Boolean = false,
    /** Board catalogue writes and backup imports share the board DB writer.
     *  Keep the reason for a disabled/waiting import visible instead of
     *  presenting an unexplained spinner while the initial sync completes. */
    val boardImportInProgress: Boolean = false,
    val waitingForBoardSync: Boolean = false,
    /** Set when the preview detected that the backup's `nostrPubkey`
     *  envelope field doesn't match the active signer. Drives a one-shot
     *  warning dialog in the UI; the user picks "import anyway" or
     *  "cancel". */
    val importPubkeyMismatch: Boolean = false,
    val importSourcePubkey: String? = null,
    /** Persists across the Mismatch dialog being dismissed: `true` once
     *  the user has explicitly accepted importing across an nsec
     *  boundary (lost-key recovery, deliberate identity migration). UI
     *  shows a persistent inline banner while this is `true` so the
     *  user can't forget the override before tapping "Import"; the
     *  ViewModel passes `expectedNostrPubkey = null` into the codec on
     *  this branch and emits an audit-log line. The default-path
     *  (`false`) wires `expectedNostrPubkey = currentPubkey` so the
     *  codec hard-refuses any mismatch — closes the historical bypass
     *  where the codec defense was dead-code on this UI flow. */
    val importMismatchAccepted: Boolean = false,

    // Feedback
    val message: String? = null,
    val error: String? = null
)

data class ExportShare(val uri: Uri, val mimeType: String)

@HiltViewModel
class DataExchangeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val bodyStatRepository: BodyStatRepository,
    private val workoutRepository: WorkoutRepository,
    private val climbRepository: ClimbRepository,
    private val planRepository: PlanRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    /** Board (unencrypted) repository — sourced for the v3 own-climb
     *  payload (FEAT-008 §4). See [CruxCoachBackup.export] for the
     *  cross-DB rationale. */
    private val boardRepository: com.cruxcoach.data.repository.BoardRepository,
    private val transactionRunner: TransactionRunner,
    /** Active-signer abstraction — same source of identity as
     *  [com.cruxcoach.android.nostr.backup.BackupRepository]. The previous
     *  direct [com.cruxcoach.android.nostr.NostrKeyStore] use resolved the
     *  LOCAL keypair, which for an Amber (external signer) user is a
     *  different identity: exports silently dropped their authored climbs
     *  and importing their own backup raised a bogus pubkey-mismatch (it
     *  also side-effect-created a fresh local key). */
    private val nostrSigner: NostrSigner,
    /** Same gate as in [com.cruxcoach.android.nostr.backup.BackupRepository.restore]:
     *  suspends a manual JSON-import until any in-flight board-sync has
     *  released the SQLite writer-lock on the unencrypted board DB,
     *  preventing SQLITE_BUSY when a user imports own-climbs from a
     *  file mid-onboarding. */
    private val boardSyncManager: com.cruxcoach.android.data.BoardSyncManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(DataExchangeState())
    val state: StateFlow<DataExchangeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            boardSyncManager.state.collect { sync ->
                _state.update {
                    it.copy(boardImportInProgress = sync.isSyncing || !sync.alreadyImported)
                }
            }
        }
    }

    // ── Export categories ───────────────────────────────────────

    fun setExportFormat(format: DataExchangeFormat) {
        _state.update { it.copy(exportFormat = format) }
    }

    fun toggleExportCategory(category: Category) {
        _state.update { s ->
            val current = s.exportCategories.toMutableSet()
            if (category in current) current.remove(category) else current.add(category)
            s.copy(exportCategories = current)
        }
    }

    fun selectAllExportCategories() {
        _state.update { it.copy(exportCategories = VISIBLE_CATEGORIES) }
    }

    fun deselectAllExportCategories() {
        _state.update { it.copy(exportCategories = emptySet()) }
    }

    // ── Export ───────────────────────────────────────────────────

    fun exportBackup(uri: Uri) {
        val s = _state.value
        if (s.exportCategories.isEmpty()) {
            _state.update { it.copy(error = context.getString(R.string.error_select_category)) }
            return
        }
        _state.update { it.copy(isExporting = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val content = buildExport(s)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                    } ?: throw Exception(context.getString(R.string.error_cannot_open_file))
                }
                val label = context.getString(R.string.export_categories_exported, s.exportCategories.size)
                _state.update { it.copy(isExporting = false, message = label) }
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, error = context.getString(R.string.error_export_failed, e.message ?: "")) }
            }
        }
    }

    fun shareExport() {
        val s = _state.value
        if (s.exportCategories.isEmpty()) {
            _state.update { it.copy(error = context.getString(R.string.error_select_category)) }
            return
        }
        _state.update { it.copy(isExporting = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val share = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, exportFilename(s.exportFormat))
                    file.writeText(buildExport(s), Charsets.UTF_8)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    ExportShare(uri, exportMimeType(s.exportFormat))
                }
                _state.update { it.copy(isExporting = false, pendingShare = share) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isExporting = false,
                        error = context.getString(R.string.error_export_failed, e.message ?: ""),
                    )
                }
            }
        }
    }

    fun shareHandled() {
        _state.update { it.copy(pendingShare = null) }
    }

    private fun buildExport(state: DataExchangeState): String {
        val json = CruxCoachBackup.export(
            categories = state.exportCategories.withBundledClimbNotes(),
            userRepository = userRepository,
            bodyStatRepository = bodyStatRepository,
            workoutRepository = workoutRepository,
            climbRepository = climbRepository,
            planRepository = planRepository,
            personalBoardRepo = personalBoardRepo,
            boardRepository = boardRepository,
            exportedAt = DateTimeUtil.nowIso(),
            nostrPubkey = nostrSigner.getPublicKeyHex(),
        )
        return when (state.exportFormat) {
            DataExchangeFormat.JSON -> json
            DataExchangeFormat.CSV -> CruxCoachCsv.fromJson(json)
        }
    }

    /** Suggested filename for the selected CruxCoach export format. */
    fun exportFilename(): String = exportFilename(_state.value.exportFormat)

    private fun exportFilename(format: DataExchangeFormat): String = when (format) {
        DataExchangeFormat.JSON -> "cruxcoach_export.json"
        DataExchangeFormat.CSV -> "cruxcoach_export.csv"
    }

    /** MIME type for the file picker. */
    fun exportMimeType(): String = exportMimeType(_state.value.exportFormat)

    private fun exportMimeType(format: DataExchangeFormat): String = when (format) {
        DataExchangeFormat.JSON -> "application/json"
        DataExchangeFormat.CSV -> CruxCoachCsv.MIME_TYPE
    }

    // ── Import: Phase 1 — Preview / detect format ───────────────

    fun loadImportPreview(uri: Uri) {
        _state.update { it.copy(isLoadingPreview = true, error = null, message = null, importPreview = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: throw Exception(context.getString(R.string.error_cannot_read_file))

                    val jsonString = if (CruxCoachCsv.looksLikeCsv(raw)) {
                        CruxCoachCsv.toJson(raw)
                    } else {
                        val trimmed = raw.trimStart()
                        require(trimmed.startsWith("{") && trimmed.contains("\"app\"")) {
                            context.getString(R.string.error_not_cruxcoach_export)
                        }
                        raw
                    }

                    val preview = CruxCoachBackup.preview(jsonString)
                    val detected = preview.detectedCategories().toManualCategories()
                    val currentPubkey = nostrSigner.getPublicKeyHex()
                    val mismatch = preview.nostrPubkey != null &&
                        preview.nostrPubkey != currentPubkey
                    _state.update { it.copy(
                        isLoadingPreview = false,
                        importPreview = preview,
                        importCategories = detected,
                        pendingImportJson = jsonString,
                        importPubkeyMismatch = mismatch,
                        importSourcePubkey = if (mismatch) preview.nostrPubkey else null
                    ) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(
                    isLoadingPreview = false,
                    error = context.getString(R.string.error_file_not_readable, e.message ?: "")
                ) }
            }
        }
    }

    // ── Import: Phase 2 — Category selection ────────────────────

    fun toggleImportCategory(category: Category) {
        _state.update { s ->
            val current = s.importCategories.toMutableSet()
            if (category in current) current.remove(category) else current.add(category)
            s.copy(importCategories = current)
        }
    }

    // ── Import: Phase 3 — Execute ───────────────────────────────

    fun confirmImport() {
        val s = _state.value
        val jsonString = s.pendingImportJson ?: return
        val categories = s.importCategories.withBundledClimbNotes()
        // UI disables the action while catalogue setup is active. Keep the
        // same invariant in the ViewModel so accessibility/rapid taps cannot
        // start a known-blocked operation behind the disabled surface.
        if (s.boardImportInProgress) return
        if (categories.isEmpty()) {
            _state.update { it.copy(error = context.getString(R.string.error_select_category)) }
            return
        }
        _state.update { it.copy(isImporting = true, error = null) }
        viewModelScope.launch {
            try {
                val currentPubkey = nostrSigner.getPublicKeyHex()
                // Default-path: pass the active pubkey so the codec
                // hard-refuses any mismatch. Override-path: user has
                // explicitly accepted the cross-pubkey import via the
                // mismatch dialog (lost-key recovery, deliberate
                // identity migration) — pass null to disable the codec
                // check, but emit an audit-log line so the override is
                // attributable in logcat when triaging "why are these
                // climbs in my account?".
                val expectedPubkey: String? = if (s.importMismatchAccepted) {
                    Log.w(
                        TAG,
                        "import-with-pubkey-override: source=${s.importSourcePubkey?.take(8) ?: "?"}…" +
                            " active=${currentPubkey.take(8)}…",
                    )
                    null
                } else {
                    currentPubkey
                }
                // Wait out any in-flight board-sync before writing — see
                // BackupRepository.restore for the same pattern + rationale.
                if (boardSyncManager.state.value.isSyncing) {
                    _state.update { it.copy(waitingForBoardSync = true) }
                    Log.i(TAG, "import: awaiting board-sync to finish before write")
                    boardSyncManager.state.first { !it.isSyncing }
                    Log.i(TAG, "import: board-sync done, proceeding")
                    _state.update { it.copy(waitingForBoardSync = false) }
                }
                val result = withContext(Dispatchers.IO) {
                    CruxCoachBackup.import(
                        jsonString = jsonString,
                        selectedCategories = categories,
                        userRepository = userRepository,
                        bodyStatRepository = bodyStatRepository,
                        workoutRepository = workoutRepository,
                        climbRepository = climbRepository,
                        planRepository = planRepository,
                        personalBoardRepo = personalBoardRepo,
                        boardRepository = boardRepository,
                        transactionRunner = transactionRunner,
                        expectedNostrPubkey = expectedPubkey,
                    )
                }

                val parts = mutableListOf<String>()
                if (result.profileImported) parts.add(context.getString(R.string.import_result_profile))
                if (result.assessments > 0) parts.add(context.getString(R.string.import_result_assessments, result.assessments))
                if (result.bodyStats > 0) parts.add(context.getString(R.string.import_result_body_stats, result.bodyStats))
                if (result.workoutLogs > 0) parts.add(context.getString(R.string.import_result_workouts, result.workoutLogs))
                if (result.climbLogs > 0) parts.add(context.getString(R.string.import_result_climbs, result.climbLogs))
                if (result.trainingPlans > 0) parts.add(context.getString(R.string.import_result_plans, result.trainingPlans))
                if (result.boardAscents > 0) parts.add(context.getString(R.string.import_result_board_sends, result.boardAscents))
                if (result.boardBids > 0) parts.add(context.getString(R.string.import_result_board_bids, result.boardBids))
                if (result.boardSessions > 0) parts.add(context.getString(R.string.import_result_board_sessions, result.boardSessions))
                if (result.climbLists > 0) parts.add(context.getString(R.string.import_result_lists, result.climbLists))
                if (result.ownClimbs > 0) parts.add(context.getString(R.string.import_result_own_climbs, result.ownClimbs))
                if (result.climbNotes > 0) parts.add(context.getString(R.string.import_result_notes, result.climbNotes))

                val summary = if (parts.isNotEmpty()) parts.joinToString(", ") else context.getString(R.string.import_result_no_data)
                val dupNote = if (result.skippedDuplicates > 0)
                    context.getString(R.string.import_result_duplicates_skipped, result.skippedDuplicates) else ""

                _state.update { it.copy(
                    isImporting = false,
                    waitingForBoardSync = false,
                    importPreview = null,
                    pendingImportJson = null,
                    importCategories = emptySet(),
                    importMismatchAccepted = false,
                    importSourcePubkey = null,
                    message = context.getString(R.string.import_result_summary, "$summary$dupNote")
                ) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isImporting = false,
                        waitingForBoardSync = false,
                        error = context.getString(R.string.error_import_failed, e.message ?: ""),
                    )
                }
            }
        }
    }

    fun dismissPubkeyMismatch() {
        // Cancel-branch from the mismatch dialog: discard the pending
        // import entirely. Resets every override-related field.
        _state.update { it.copy(
            importPubkeyMismatch = false,
            importMismatchAccepted = false,
            importSourcePubkey = null,
            importPreview = null,
            pendingImportJson = null,
            importCategories = emptySet()
        ) }
    }

    fun confirmMismatchImport() {
        // Override-branch from the mismatch dialog: user has explicitly
        // accepted importing across nsec boundaries (legitimate use:
        // lost-key recovery from a local file, deliberate migration).
        // The flag drives both the persistent inline warning in the UI
        // and the `expectedNostrPubkey = null` branch in [confirmImport].
        _state.update { it.copy(
            importPubkeyMismatch = false,
            importMismatchAccepted = true,
        ) }
    }

    fun cancelImport() {
        _state.update { it.copy(
            importPreview = null,
            pendingImportJson = null,
            importCategories = emptySet(),
            importPubkeyMismatch = false,
            importMismatchAccepted = false,
            importSourcePubkey = null
        ) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, error = null) }
    }

    private companion object {
        const val TAG = "DataExchangeVM"
    }
}
