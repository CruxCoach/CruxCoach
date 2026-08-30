package com.cruxcoach.domain.board

/** Stable detail failure categories. Raw exception text never becomes renderer state. */
enum class ClimbDetailIssue {
    NOT_FOUND,
    LOAD_FAILED,
}

data class ClimbDetailIdentity(
    val uuid: String,
    val name: String,
    val setterName: String?,
    val boardBrand: BoardBrand,
    val layoutId: Long,
    val angle: Int,
    val difficultyAverage: Double?,
    val qualityAverage: Double?,
    val isBenchmark: Boolean,
    val isRoute: Boolean,
    val isMirrored: Boolean,
    val isMirrorable: Boolean,
)

data class ClimbDetailDeliveryState(
    val connection: BoardConnectionState,
    val decision: BoardDeliveryDecision,
    val isSending: Boolean = false,
    val isSent: Boolean = false,
    val hasWarning: Boolean = false,
    val connectedViaRelay: Boolean = false,
    val sessionOwned: Boolean = false,
)

sealed interface ClimbDetailScreenState {
    data object Loading : ClimbDetailScreenState

    data class Content(
        val identity: ClimbDetailIdentity,
        val holds: List<BoardHold>,
        val availableAngles: List<Int>,
        val delivery: ClimbDetailDeliveryState,
        val isFavorited: Boolean,
        val isIgnored: Boolean,
        val hasPersonalNote: Boolean,
        val loggedAscentCount: Int,
    ) : ClimbDetailScreenState

    data class LogbookOnly(
        val climbUuid: String,
        val loggedAscentCount: Int,
    ) : ClimbDetailScreenState

    data class Error(
        val issue: ClimbDetailIssue,
        val canReport: Boolean = true,
    ) : ClimbDetailScreenState
}

/** User intents only. Android and iOS own navigation, BLE, and sheets. */
sealed interface ClimbDetailAction {
    data class ChooseAngle(val angle: Int) : ClimbDetailAction
    data class OpenClimb(val uuid: String, val angle: Int) : ClimbDetailAction
    data object NavigateBack : ClimbDetailAction
    data object ConnectBoard : ClimbDetailAction
    data object DeliverToBoard : ClimbDetailAction
    data object LogSend : ClimbDetailAction
    data object LogAttempt : ClimbDetailAction
    data object ToggleMirror : ClimbDetailAction
    data object ToggleFavorite : ClimbDetailAction
    data object OpenLists : ClimbDetailAction
    data object OpenPersonalNote : ClimbDetailAction
    data object OpenHistory : ClimbDetailAction
    data object OpenMoreActions : ClimbDetailAction
    data object ReportIssue : ClimbDetailAction
    data object DismissIssue : ClimbDetailAction
}
