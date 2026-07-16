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
import com.cruxcoach.data.TransactionRunner
import com.cruxcoach.data.WaistlineExchange
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
import javax.inject.Inject

enum class ExportFormat { CRUXCOACH, WAISTLINE_JSON, WAISTLINE_CSV }

/**
 * Categories visible in the manual JSON export/import UI — gated to
 * what the user can actually access via 0.1.4's app surface.
 *
 * Hidden in 0.1.4 (no user-facing UI yet, so no point exporting):
 * - PROFILE (carries the fitness UserProfile only, age/weight/grade —
 *   that data has no UI in 0.1.4 to set or view; the label
 *   "Profil & Einstellungen" is misleading because app preferences
 *   are NOT in the wire format at all)
 * - CLIMB_LOGS (generic ClimbLog diary, part of the hidden bottom-bar
 *   training surface — no Add-form exposed in 0.1.4)
 * - ASSESSMENTS, BODY_STATS, WORKOUT_LOGS, TRAINING_PLANS,
 *   BOARD_SESSIONS — same "no UI yet" reason.
 *
 * Re-add them here as their UI lands so the wire format stays in sync
 * with the visible feature surface.
 *
 * Note: the Nostr-backup path (BackupRepository) deliberately uses
 * `Category.entries.toSet()` instead — the cloud-backup is a true
 * snapshot of *everything* the wire format supports, so a future build
 * that re-enables one of the hidden categories doesn't lose the user's
 * old hidden-but-still-stored data on the next backup cycle.
 */
val VISIBLE_CATEGORIES: Set<Category> = setOf(
    Category.BOARD_LOGBOOK,
    Category.CLIMB_LISTS,
    Category.OWN_CLIMBS,
)

data class DataExchangeState(
    // Export
    val exportFormat: ExportFormat = ExportFormat.CRUXCOACH,
    val exportCategories: Set<Category> = VISIBLE_CATEGORIES,
    val isExporting: Boolean = false,

    // Import — two-phase: preview → confirm
    val isLoadingPreview: Boolean = false,
    val importPreview: CruxCoachBackup.ImportPreview? = null,
    val importCategories: Set<Category> = emptySet(),
    val pendingImportJson: String? = null,
    val detectedWaistline: Boolean = false,
    val isImporting: Boolean = false,
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

    // ── Export format ───────────────────────────────────────────

    fun setExportFormat(format: ExportFormat) {
        _state.update { it.copy(exportFormat = format) }
    }

    // ── Export categories (only for CruxCoach format) ───────────

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
        if (s.exportFormat == ExportFormat.CRUXCOACH && s.exportCategories.isEmpty()) {
            _state.update { it.copy(error = context.getString(R.string.error_select_category)) }
            return
        }
        _state.update { it.copy(isExporting = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val content = when (s.exportFormat) {
                        ExportFormat.CRUXCOACH -> CruxCoachBackup.export(
                            categories = s.exportCategories,
                            userRepository = userRepository,
                            bodyStatRepository = bodyStatRepository,
                            workoutRepository = workoutRepository,
                            climbRepository = climbRepository,
                            planRepository = planRepository,
                            personalBoardRepo = personalBoardRepo,
                            boardRepository = boardRepository,
                            exportedAt = DateTimeUtil.nowIso(),
                            nostrPubkey = nostrSigner.getPublicKeyHex()
                        )
                        ExportFormat.WAISTLINE_JSON -> WaistlineExchange.exportToJson(bodyStatRepository)
                        ExportFormat.WAISTLINE_CSV -> WaistlineExchange.exportToCsv(bodyStatRepository)
                    }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                    } ?: throw Exception(context.getString(R.string.error_cannot_open_file))
                }
                val label = when (s.exportFormat) {
                    ExportFormat.CRUXCOACH -> context.resources.getQuantityString(
                        R.plurals.export_categories_exported,
                        s.exportCategories.size,
                        s.exportCategories.size,
                    )
                    ExportFormat.WAISTLINE_JSON -> context.getString(R.string.export_waistline_json_success)
                    ExportFormat.WAISTLINE_CSV -> context.getString(R.string.export_waistline_csv_success)
                }
                _state.update { it.copy(isExporting = false, message = label) }
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, error = context.getString(R.string.error_export_failed, e.message ?: "")) }
            }
        }
    }

    /** Suggested filename based on selected format. */
    fun exportFilename(): String = when (_state.value.exportFormat) {
        ExportFormat.CRUXCOACH -> "cruxcoach_backup.json"
        ExportFormat.WAISTLINE_JSON -> "waistline_export.json"
        ExportFormat.WAISTLINE_CSV -> "diary_export.csv"
    }

    /** MIME type for the file picker. */
    fun exportMimeType(): String = when (_state.value.exportFormat) {
        ExportFormat.CRUXCOACH, ExportFormat.WAISTLINE_JSON -> "application/json"
        ExportFormat.WAISTLINE_CSV -> "text/csv"
    }

    // ── Import: Phase 1 — Preview / detect format ───────────────

    fun loadImportPreview(uri: Uri) {
        _state.update { it.copy(isLoadingPreview = true, error = null, message = null, importPreview = null, detectedWaistline = false) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: throw Exception(context.getString(R.string.error_cannot_read_file))

                    val trimmed = raw.trimStart()
                    val isCruxCoach = trimmed.startsWith("{") && trimmed.contains("\"app\"")
                    val isWaistlineJson = !isCruxCoach && trimmed.startsWith("{") && raw.contains("\"diary\"")
                    val isWaistlineCsv = !trimmed.startsWith("{") && !trimmed.startsWith("[")

                    if (isWaistlineJson || isWaistlineCsv) {
                        // Waistline format → import directly
                        val count = if (isWaistlineJson) {
                            WaistlineExchange.importFromJson(raw, bodyStatRepository)
                        } else {
                            WaistlineExchange.importFromCsv(raw, bodyStatRepository)
                        }
                        _state.update { it.copy(
                            isLoadingPreview = false,
                            message = context.resources.getQuantityString(
                                R.plurals.import_waistline_success,
                                count,
                                count,
                            )
                        ) }
                    } else {
                        // CruxCoach format → show preview
                        val preview = CruxCoachBackup.preview(raw)
                        val detected = preview.detectedCategories().intersect(VISIBLE_CATEGORIES)
                        val currentPubkey = nostrSigner.getPublicKeyHex()
                        val mismatch = preview.nostrPubkey != null &&
                            preview.nostrPubkey != currentPubkey
                        _state.update { it.copy(
                            isLoadingPreview = false,
                            importPreview = preview,
                            importCategories = detected,
                            pendingImportJson = raw,
                            importPubkeyMismatch = mismatch,
                            importSourcePubkey = if (mismatch) preview.nostrPubkey else null
                        ) }
                    }
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
        val categories = s.importCategories
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
                    Log.i(TAG, "import: awaiting board-sync to finish before write")
                    boardSyncManager.state.first { !it.isSyncing }
                    Log.i(TAG, "import: board-sync done, proceeding")
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
                fun addQuantity(resource: Int, count: Int) {
                    if (count > 0) {
                        parts += context.resources.getQuantityString(resource, count, count)
                    }
                }
                addQuantity(R.plurals.import_result_assessments, result.assessments)
                addQuantity(R.plurals.import_result_body_stats, result.bodyStats)
                addQuantity(R.plurals.import_result_workouts, result.workoutLogs)
                addQuantity(R.plurals.import_result_climbs, result.climbLogs)
                addQuantity(R.plurals.import_result_plans, result.trainingPlans)
                addQuantity(R.plurals.import_result_board_sends, result.boardAscents)
                addQuantity(R.plurals.import_result_board_bids, result.boardBids)
                addQuantity(R.plurals.import_result_board_sessions, result.boardSessions)
                addQuantity(R.plurals.import_result_lists, result.climbLists)
                addQuantity(R.plurals.import_result_own_climbs, result.ownClimbs)

                val summary = if (parts.isNotEmpty()) parts.joinToString(", ") else context.getString(R.string.import_result_no_data)
                val dupNote = if (result.skippedDuplicates > 0) {
                    context.resources.getQuantityString(
                        R.plurals.import_result_duplicates_skipped,
                        result.skippedDuplicates,
                        result.skippedDuplicates,
                    )
                } else {
                    ""
                }

                _state.update { it.copy(
                    isImporting = false,
                    importPreview = null,
                    pendingImportJson = null,
                    importCategories = emptySet(),
                    importMismatchAccepted = false,
                    importSourcePubkey = null,
                    message = context.getString(R.string.import_result_summary, "$summary$dupNote")
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(isImporting = false, error = context.getString(R.string.error_import_failed, e.message ?: "")) }
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
