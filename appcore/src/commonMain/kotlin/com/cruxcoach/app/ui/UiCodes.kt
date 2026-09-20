package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.ble.BoardConnectFailure
import com.cruxcoach.app.ble.BoardSendResult
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.browse.BrowseLoadState
import com.cruxcoach.app.browse.ClimbStatusFilter
import com.cruxcoach.app.detail.ClimbDetailStatus
import com.cruxcoach.app.sync.CatalogueSyncFailure
import com.cruxcoach.app.sync.CatalogueSyncPhase
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.BoardBrand

/** Stable lowercase codes for the enums Swift needs to branch on. */
internal object UiCodes {
    fun phase(value: CatalogueSyncPhase): String = when (value) {
        CatalogueSyncPhase.IDLE -> "idle"
        CatalogueSyncPhase.CHECKING -> "checking"
        CatalogueSyncPhase.DOWNLOADING -> "downloading"
        CatalogueSyncPhase.VERIFYING -> "verifying"
        CatalogueSyncPhase.IMPORTING -> "importing"
        CatalogueSyncPhase.UP_TO_DATE -> "upToDate"
        CatalogueSyncPhase.DONE -> "done"
        CatalogueSyncPhase.FAILED -> "failed"
    }

    fun syncFailure(value: CatalogueSyncFailure): String = when (value) {
        CatalogueSyncFailure.NONE -> "none"
        CatalogueSyncFailure.MANIFEST_UNAVAILABLE -> "manifestUnavailable"
        CatalogueSyncFailure.MANIFEST_INVALID -> "manifestInvalid"
        CatalogueSyncFailure.OFFLINE -> "offline"
        CatalogueSyncFailure.DOWNLOAD_FAILED -> "downloadFailed"
        CatalogueSyncFailure.PARTIAL_DOWNLOAD -> "partialDownload"
        CatalogueSyncFailure.INSUFFICIENT_STORAGE -> "insufficientStorage"
        CatalogueSyncFailure.DECOMPRESSION_FAILED -> "decompressionFailed"
        CatalogueSyncFailure.SOURCE_REJECTED -> "sourceRejected"
        CatalogueSyncFailure.IMPORT_FAILED -> "importFailed"
        CatalogueSyncFailure.UNSUPPORTED_BRAND -> "unsupportedBrand"
        CatalogueSyncFailure.CANCELLED -> "cancelled"
    }

    fun loadState(value: BrowseLoadState): String = when (value) {
        BrowseLoadState.LOADING -> "loading"
        BrowseLoadState.LOADING_MORE -> "loadingMore"
        BrowseLoadState.READY -> "ready"
        BrowseLoadState.FAILED -> "failed"
    }

    fun detailStatus(value: ClimbDetailStatus): String = when (value) {
        ClimbDetailStatus.LOADING -> "loading"
        ClimbDetailStatus.READY -> "ready"
        ClimbDetailStatus.LOGBOOK_ONLY -> "logbookOnly"
        ClimbDetailStatus.NOT_FOUND -> "notFound"
        ClimbDetailStatus.FAILED -> "failed"
    }

    fun adapter(value: BleAdapterState): String = when (value) {
        BleAdapterState.UNKNOWN -> "unknown"
        BleAdapterState.RESETTING -> "resetting"
        BleAdapterState.UNSUPPORTED -> "unsupported"
        BleAdapterState.UNAUTHORIZED -> "unauthorized"
        BleAdapterState.POWERED_OFF -> "poweredOff"
        BleAdapterState.POWERED_ON -> "poweredOn"
    }

    fun connection(value: ConnectionState): String = when (value) {
        ConnectionState.DISCONNECTED -> "disconnected"
        ConnectionState.CONNECTING -> "connecting"
        ConnectionState.CONNECTED -> "connected"
        ConnectionState.SENDING -> "sending"
    }

    fun connectFailure(value: BoardConnectFailure?): String = when (value) {
        null -> "none"
        BoardConnectFailure.CONNECT_FAILED -> "connectFailed"
        BoardConnectFailure.MOONBOARD_GENERATION_UNSUPPORTED -> "moonBoardGenerationUnsupported"
        else -> "connectFailed"
    }

    fun sendResult(value: BoardSendResult?): String = when (value) {
        null -> "none"
        BoardSendResult.OK -> "ok"
        BoardSendResult.NOT_CONNECTED -> "notConnected"
        BoardSendResult.BOARD_MISMATCH -> "boardMismatch"
        else -> "sendFailed"
    }

    fun status(code: String): ClimbStatusFilter? = when (code) {
        "new" -> ClimbStatusFilter.NEW
        "attempted" -> ClimbStatusFilter.ATTEMPTED
        "sent" -> ClimbStatusFilter.SENT
        else -> null
    }

    /** Sort codes accepted from Swift; unknown codes keep the current sort. */
    fun sort(code: String): Pair<ClimbSortField, SortDirection>? = when (code) {
        "popular" -> ClimbSortField.ASCENSIONISTS to SortDirection.DESC
        "quality" -> ClimbSortField.QUALITY to SortDirection.DESC
        "hardest" -> ClimbSortField.DIFFICULTY to SortDirection.DESC
        "easiest" -> ClimbSortField.DIFFICULTY to SortDirection.ASC
        "name" -> ClimbSortField.NAME to SortDirection.ASC
        "newest" -> ClimbSortField.NEWEST to SortDirection.DESC
        "qualitySends" -> ClimbSortField.QUALITY_SENDS to SortDirection.DESC
        "random" -> ClimbSortField.RANDOM to SortDirection.DESC
        else -> null
    }

    fun sortCode(field: ClimbSortField, direction: SortDirection): String = when (field) {
        ClimbSortField.ASCENSIONISTS -> "popular"
        ClimbSortField.QUALITY -> "quality"
        ClimbSortField.DIFFICULTY -> if (direction == SortDirection.ASC) "easiest" else "hardest"
        ClimbSortField.NAME -> "name"
        ClimbSortField.NEWEST -> "newest"
        ClimbSortField.QUALITY_SENDS -> "qualitySends"
        ClimbSortField.RANDOM -> "random"
        else -> "popular"
    }

    /** Product names, not translatable UI text: the same labels Android shows. */
    fun brandTitle(brand: BoardBrand): String = when (brand) {
        BoardBrand.KILTER -> "Kilter Board"
        BoardBrand.MOONBOARD -> "MoonBoard"
        BoardBrand.TENSION -> "Tension Board"
        BoardBrand.GRASSHOPPER -> "Grasshopper"
        BoardBrand.DECOY -> "Decoy"
        BoardBrand.SOILL -> "So iLL"
        BoardBrand.TOUCHSTONE -> "Touchstone"
        BoardBrand.QUANTUM -> "Quantum"
        else -> brand.wireValue
    }
}
