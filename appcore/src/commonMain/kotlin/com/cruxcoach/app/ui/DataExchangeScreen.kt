package com.cruxcoach.app.ui

import com.cruxcoach.app.backup.FileExchangeError
import com.cruxcoach.app.backup.FileExchangePresenter
import com.cruxcoach.app.backup.FileExchangeState
import com.cruxcoach.app.backup.ImportFailure
import com.cruxcoach.app.backup.ManualImportCategories
import com.cruxcoach.app.nostr.Nip19
import com.cruxcoach.app.training.Tx
import com.cruxcoach.data.CruxCoachBackup
import com.cruxcoach.data.CruxCoachBackup.Category

/** One category row of the import preview: Android's checkbox row with its count line. */
class DataImportCategoryRow(
    /** Lowercase category code (`board_logbook`, `climb_lists`, …), handed back to [DataExchangeScreenModel.toggleCategory]. */
    val code: String,
    /** Android's `export_category_*` label. */
    val label: TrText,
    /** Android's `countLine`: the plural counts of this category in the file. */
    val countLine: TrText,
    val selected: Boolean,
)

class DataExchangeScreenState(
    /** An export is being written. */
    val isBusy: Boolean,
    /** Absolute path of the file to share; empty when there is none. */
    val exportPath: String,
    val exportBytes: Long,
    /** Android's opt-in export row "Verletzungen & Comeback-Tagebuch" (`cb_backup_category`, hint `cb_backup_category_hint`). */
    val exportHealth: Boolean,

    /** The picked file is being read and analysed (Android: "Analyzing file…"). */
    val isLoadingPreview: Boolean,
    /** A file was previewed: the category choice replaces the file picker. */
    val hasPreview: Boolean,
    /** Detected categories the screen offers, in Android's order. */
    val categories: List<DataImportCategoryRow>,
    /** Android's `import_no_data`: the file holds nothing at all. */
    val previewEmpty: Boolean,
    /** Android's `import_catalogue_loading_hint` is shown. */
    val catalogueLoading: Boolean,
    val isImporting: Boolean,
    /** The import button is enabled: not importing and at least one category chosen. */
    val canImport: Boolean,
    /** Android's identity-mismatch dialog is up. */
    val pubkeyMismatch: Boolean,
    /** The user chose "import anyway": Android's warning banner stays until the import ran. */
    val mismatchAccepted: Boolean,
    /** The file's identity for the dialog, shortened like Android's (`npub1abcdefg...uvwxyz`). */
    val sourceNpubDialog: String,
    /** The same for the banner, with Android's ellipsis character. */
    val sourceNpubBanner: String,

    /** Android's import summary ("Imported: …"); empty until an import finished. */
    val resultText: TrText,

    /** none | exportFailed | noIdentity | fileNotReadable | importFailed | selectCategory */
    val errorCode: String,
    /** Android's full error text for the import errors; empty for the export-side codes. */
    val errorText: TrText,
)

/** Swift-facing plain-file export, and Android's previewed import of the whole account. */
class DataExchangeScreenModel(private val presenter: FileExchangePresenter) {

    val currentState: DataExchangeScreenState get() = map(presenter.state.value)

    fun watch(onState: (DataExchangeScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun export() = presenter.export()

    /** Ticks health data for the JSON export; off by default (FEAT-072). */
    fun setExportHealth(on: Boolean) = presenter.setExportHealth(on)

    /** Android's CSV ZIP (spreadsheet tables of the board data); shared like the JSON file. */
    fun exportCsvZip() = presenter.exportCsvZip()

    /** Android's readable XLSX copy; it cannot be imported back (Swift shows Android's notice). */
    fun exportExcel() = presenter.exportExcel()
    fun discardExport() = presenter.discardExport()

    /**
     * Reads and previews a JSON export or a CSV ZIP (detected from the content,
     * as on Android). Nothing is written until [confirmImport].
     */
    fun previewFile(path: String, onFinished: () -> Unit) = presenter.loadPreview(path, onFinished)

    /** Toggles a category row of the preview by its [DataImportCategoryRow.code]. */
    fun toggleCategory(code: String) {
        val category = Category.entries.firstOrNull { it.name.lowercase() == code } ?: return
        presenter.toggleCategory(category)
    }

    fun confirmImport() = presenter.confirmImport()
    fun cancelImport() = presenter.cancelImport()

    /** "Import anyway" in the identity-mismatch dialog. */
    fun confirmMismatch() = presenter.confirmMismatchImport()

    /** "Cancel" in the identity-mismatch dialog: the file is dropped. */
    fun dismissMismatch() = presenter.dismissPubkeyMismatch()

    fun consumeError() = presenter.consumeError()
    fun close() = presenter.close()

    private fun map(state: FileExchangeState): DataExchangeScreenState {
        val preview = state.preview
        val detected = preview?.detectedCategories().orEmpty()
        val rows = if (preview == null) emptyList() else ManualImportCategories.VISIBLE
            .filter { it in detected }
            .map { category ->
                DataImportCategoryRow(
                    code = category.name.lowercase(),
                    label = Tx.t(DataExchangeTexts.labelKey(category)),
                    countLine = DataExchangeTexts.countLine(preview, category),
                    selected = category in state.importCategories,
                )
            }
        val source = state.importSourcePubkey.orEmpty()
        return DataExchangeScreenState(
            isBusy = state.isBusy,
            exportPath = state.exportPath,
            exportBytes = state.exportBytes,
            exportHealth = state.exportHealth,
            isLoadingPreview = state.isLoadingPreview,
            hasPreview = preview != null,
            categories = rows,
            previewEmpty = preview != null && detected.isEmpty(),
            catalogueLoading = state.catalogueLoading,
            isImporting = state.isImporting,
            canImport = !state.isImporting && state.importCategories.isNotEmpty(),
            pubkeyMismatch = state.importPubkeyMismatch,
            mismatchAccepted = state.importMismatchAccepted,
            sourceNpubDialog = DataExchangeTexts.shortNpub(source, "..."),
            sourceNpubBanner = if (source.isBlank()) "?" else DataExchangeTexts.shortNpub(source, "…"),
            resultText = state.importResult?.let { DataExchangeTexts.result(it) } ?: Tx.EMPTY,
            errorCode = when (state.error) {
                FileExchangeError.NONE -> "none"
                FileExchangeError.EXPORT_FAILED -> "exportFailed"
                FileExchangeError.NO_IDENTITY -> "noIdentity"
                FileExchangeError.FILE_NOT_READABLE -> "fileNotReadable"
                FileExchangeError.IMPORT_FAILED -> "importFailed"
                FileExchangeError.SELECT_CATEGORY -> "selectCategory"
            },
            errorText = DataExchangeTexts.error(state.error, state.importFailure, state.importFailureDetail),
        )
    }
}

/** Android's texts of DataImportScreen and DataExchangeViewModel, as [TrText] recipes. */
internal object DataExchangeTexts {
    /** Android's `Category.localizedLabel()` (CategoryLabels.kt). */
    fun labelKey(category: Category): String = when (category) {
        Category.PROFILE -> "export_category_profile"
        Category.ASSESSMENTS -> "export_category_assessments"
        Category.BODY_STATS -> "export_category_body_stats"
        Category.WORKOUT_LOGS -> "export_category_workout_logs"
        Category.CLIMB_LOGS -> "export_category_climb_logs"
        Category.TRAINING_PLANS -> "export_category_training_plans"
        Category.BOARD_LOGBOOK -> "export_category_board_logbook"
        Category.BOARD_SESSIONS -> "export_category_board_sessions"
        Category.CLIMB_LISTS -> "export_category_climb_lists"
        Category.OWN_CLIMBS -> "export_category_own_climbs"
        Category.CLIMB_NOTES -> "export_category_climb_notes"
        Category.TRAINING -> "export_category_training"
        Category.FUEL -> "export_category_fuel"
        Category.HEALTH -> "cb_backup_category"
    }

    private fun plural(key: String, count: Int) = Tx.p(key, count, Tx.i(count))

    /** Android's `ImportPreview.countLine` (DataImportScreen.kt). */
    fun countLine(p: CruxCoachBackup.ImportPreview, category: Category): TrText = when (category) {
        Category.PROFILE -> Tx.t("import_result_profile")
        Category.ASSESSMENTS -> plural("import_result_assessments", p.assessments)
        Category.BODY_STATS -> plural("import_result_body_stats", p.bodyStats + p.athleteBodyRows)
        Category.WORKOUT_LOGS -> plural("import_result_workouts", p.workoutLogs)
        Category.CLIMB_LOGS -> plural("import_result_climbs", p.climbLogs)
        Category.TRAINING_PLANS -> plural("import_result_plans", p.trainingPlans)
        Category.BOARD_LOGBOOK -> Tx.join(
            listOfNotNull(
                p.boardAscents.takeIf { it > 0 }?.let { plural("import_result_board_sends", it) },
                p.boardBids.takeIf { it > 0 }?.let { plural("import_result_board_bids", it) },
            ),
            ", ",
        )
        Category.BOARD_SESSIONS -> plural("import_result_board_sessions", p.boardSessions)
        Category.CLIMB_LISTS -> plural("import_result_lists", p.climbLists)
        Category.OWN_CLIMBS -> plural("import_result_own_climbs", p.ownClimbs)
        Category.CLIMB_NOTES -> plural("import_result_notes", p.climbNotes)
        Category.TRAINING -> plural("import_result_training", p.trainingRows)
        Category.FUEL -> plural("import_result_fuel", p.fuelRows)
        Category.HEALTH -> plural("import_result_health", p.healthRows)
    }

    /**
     * Android's import message (`confirmImport`): the written rows per kind,
     * the skipped duplicates and the own climbs still to follow (held back
     * while a catalogue download runs); `ownClimbs` is what Android reports as restored.
     */
    fun result(r: CruxCoachBackup.ImportResult): TrText {
        val parts = buildList {
            if (r.profileImported) add(Tx.t("import_result_profile"))
            if (r.assessments > 0) add(plural("import_result_assessments", r.assessments))
            if (r.bodyStats > 0) add(plural("import_result_body_stats", r.bodyStats))
            if (r.workoutLogs > 0) add(plural("import_result_workouts", r.workoutLogs))
            if (r.climbLogs > 0) add(plural("import_result_climbs", r.climbLogs))
            if (r.trainingPlans > 0) add(plural("import_result_plans", r.trainingPlans))
            if (r.boardAscents > 0) add(plural("import_result_board_sends", r.boardAscents))
            if (r.boardBids > 0) add(plural("import_result_board_bids", r.boardBids))
            if (r.boardSessions > 0) add(plural("import_result_board_sessions", r.boardSessions))
            if (r.climbLists > 0) add(plural("import_result_lists", r.climbLists))
            if (r.ownClimbs > 0) add(plural("import_result_own_climbs", r.ownClimbs))
            if (r.climbNotes > 0) add(plural("import_result_notes", r.climbNotes))
            if (r.athleteRows > 0) add(plural("import_result_athlete", r.athleteRows))
        }
        val summary = if (parts.isNotEmpty()) Tx.join(parts, ", ") else Tx.t("import_result_no_data")
        val whole = buildList {
            add(summary)
            if (r.skippedDuplicates > 0) {
                add(Tx.plain(" "))
                add(plural("import_result_duplicates_skipped", r.skippedDuplicates))
            }
            if (r.pendingOwnClimbs > 0) {
                add(Tx.plain(". "))
                add(plural("import_result_own_climbs_pending", r.pendingOwnClimbs))
            }
        }
        return Tx.t("import_result_summary", Tx.x(Tx.join(whole, "")))
    }

    /**
     * Android's error texts: `error_file_not_readable` for a file that could not
     * be previewed, `error_import_failed` for a failed import, each with the
     * reason `safeImportErrorReason` gives.
     */
    fun error(error: FileExchangeError, failure: ImportFailure, detail: String): TrText = when (error) {
        FileExchangeError.FILE_NOT_READABLE -> Tx.t("error_file_not_readable", reason(failure, detail))
        FileExchangeError.IMPORT_FAILED -> Tx.t("error_import_failed", reason(failure, detail))
        FileExchangeError.SELECT_CATEGORY -> Tx.t("error_select_category")
        FileExchangeError.NONE, FileExchangeError.EXPORT_FAILED, FileExchangeError.NO_IDENTITY -> Tx.EMPTY
    }

    private fun reason(failure: ImportFailure, detail: String): TrArg = when (failure) {
        ImportFailure.CANNOT_READ -> Tx.k("error_cannot_read_file")
        ImportFailure.TOO_LARGE -> Tx.k("error_import_too_large")
        ImportFailure.NOT_CRUXCOACH_EXPORT -> Tx.k("error_not_cruxcoach_export")
        ImportFailure.NEWER_VERSION -> Tx.k("error_import_newer_version")
        ImportFailure.INVALID_BACKUP, ImportFailure.OTHER ->
            if (detail.isEmpty()) Tx.k("error_import_invalid_format") else Tx.s(detail)
        ImportFailure.INVALID_FORMAT, ImportFailure.NONE -> Tx.k("error_import_invalid_format")
    }

    /**
     * Android's shortened npub (`take(12)` + [ellipsis] + `takeLast(6)`), or the
     * first 12 hex characters when the file's identity is not a valid key.
     */
    fun shortNpub(hex: String, ellipsis: String): String {
        val npub = Nip19.encodeNpub(hex) ?: return hex.take(12) + ellipsis
        return if (npub.length > 20) npub.take(12) + ellipsis + npub.takeLast(6) else npub
    }
}
