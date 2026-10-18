package com.cruxcoach.app.ui

import com.cruxcoach.app.backup.FileExchangeError
import com.cruxcoach.app.backup.FileExchangePresenter
import com.cruxcoach.app.backup.FileExchangeState

class DataExchangeScreenState(
    val isBusy: Boolean,
    /** Absolute path of the file to share; empty when there is none. */
    val exportPath: String,
    val exportBytes: Long,
    /** -1 until an import finished. */
    val importedAscents: Int,
    val importedBids: Int,
    val importedLists: Int,
    /** none | exportFailed | importFailed | fileUnreadable | noIdentity */
    val errorCode: String,
)

/** Swift-facing plain-file export and import of the whole account. */
class DataExchangeScreenModel(private val presenter: FileExchangePresenter) {

    val currentState: DataExchangeScreenState get() = map(presenter.state.value)

    fun watch(onState: (DataExchangeScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun export() = presenter.export()

    /** Android's CSV ZIP (spreadsheet tables of the board data); shared like the JSON file. */
    fun exportCsvZip() = presenter.exportCsvZip()

    /** Android's readable XLSX copy; it cannot be imported back (Swift shows Android's notice). */
    fun exportExcel() = presenter.exportExcel()
    fun discardExport() = presenter.discardExport()

    /** Imports a JSON export or a CSV ZIP (detected from the content, as on Android). */
    fun importFile(path: String, onFinished: () -> Unit) = presenter.import(path, onFinished)
    fun consumeError() = presenter.consumeError()
    fun close() = presenter.close()

    private fun map(state: FileExchangeState) = DataExchangeScreenState(
        isBusy = state.isBusy,
        exportPath = state.exportPath,
        exportBytes = state.exportBytes,
        importedAscents = state.importedAscents,
        importedBids = state.importedBids,
        importedLists = state.importedLists,
        errorCode = when (state.error) {
            FileExchangeError.NONE -> "none"
            FileExchangeError.EXPORT_FAILED -> "exportFailed"
            FileExchangeError.IMPORT_FAILED -> "importFailed"
            FileExchangeError.FILE_UNREADABLE -> "fileUnreadable"
            FileExchangeError.NO_IDENTITY -> "noIdentity"
        },
    )
}
