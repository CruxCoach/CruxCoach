package com.cruxcoach.domain.board

import kotlinx.coroutines.CancellationException

/** Whether a board-climb log records a top or unfinished attempts. */
enum class AttemptOutcome {
    SEND,
    ATTEMPT,
}

/** Immutable climb metadata captured with a logbook entry. */
data class LoggedClimbSnapshot(
    val uuid: String,
    val name: String,
    val angle: Long,
    val isMirrored: Boolean,
    val difficultyAverage: Double?,
    val frames: String,
    val framesCount: Long,
    val boardBrand: String,
    val layoutId: Long?,
)

/**
 * Platform-neutral input for one explicit log action.
 *
 * IDs and timestamps are supplied by the platform boundary so this contract
 * does not depend on Android, Foundation, a global clock, or a UUID runtime.
 */
data class LogAttemptCommand(
    val entryUuid: String,
    val climb: LoggedClimbSnapshot,
    val outcome: AttemptOutcome,
    val attemptCount: Long,
    val quality: Long? = null,
    val isBenchmark: Boolean = false,
    val comment: String? = null,
    val climbedAt: String,
)

enum class LogAttemptIssue {
    MISSING_ENTRY_UUID,
    MISSING_CLIMB_UUID,
    MISSING_CLIMB_NAME,
    MISSING_BOARD_BRAND,
    MISSING_CLIMBED_AT,
    INVALID_ATTEMPT_COUNT,
    INVALID_QUALITY,
    QUALITY_REQUIRES_SEND,
    BENCHMARK_REQUIRES_SEND,
}

sealed interface LogAttemptResult {
    data class Logged(val entry: LoggedAttempt) : LogAttemptResult
    data class Rejected(val issues: Set<LogAttemptIssue>) : LogAttemptResult
    data object StorageFailure : LogAttemptResult
}

/** Portable submission state; platform UIs own presentation and localized copy. */
enum class AttemptLogSubmissionState {
    EDITING,
    SAVING,
    FAILED,
}

data class LoggedAttempt(
    val entryUuid: String,
    val climbUuid: String,
    val outcome: AttemptOutcome,
    val attemptCount: Long,
    val climbedAt: String,
)

/** Narrow persistence boundary shared by Android and the future iOS client. */
interface AttemptLogStore {
    fun insertSend(command: LogAttemptCommand)
    fun insertAttempt(command: LogAttemptCommand)
}

/** Validates and normalizes an explicit board-attempt log before persistence. */
class LogAttemptUseCase(
    private val store: AttemptLogStore,
) {
    operator fun invoke(command: LogAttemptCommand): LogAttemptResult {
        val issues = command.validationIssues()
        if (issues.isNotEmpty()) return LogAttemptResult.Rejected(issues)

        val normalized = command.copy(comment = command.comment?.trim()?.ifEmpty { null })
        return try {
            when (normalized.outcome) {
                AttemptOutcome.SEND -> store.insertSend(normalized)
                AttemptOutcome.ATTEMPT -> store.insertAttempt(normalized)
            }
            LogAttemptResult.Logged(
                LoggedAttempt(
                    entryUuid = normalized.entryUuid,
                    climbUuid = normalized.climb.uuid,
                    outcome = normalized.outcome,
                    attemptCount = normalized.attemptCount,
                    climbedAt = normalized.climbedAt,
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            LogAttemptResult.StorageFailure
        }
    }
}

private fun LogAttemptCommand.validationIssues(): Set<LogAttemptIssue> = buildSet {
    if (entryUuid.isBlank()) add(LogAttemptIssue.MISSING_ENTRY_UUID)
    if (climb.uuid.isBlank()) add(LogAttemptIssue.MISSING_CLIMB_UUID)
    if (climb.name.isBlank()) add(LogAttemptIssue.MISSING_CLIMB_NAME)
    if (climb.boardBrand.isBlank()) add(LogAttemptIssue.MISSING_BOARD_BRAND)
    if (climbedAt.isBlank()) add(LogAttemptIssue.MISSING_CLIMBED_AT)
    if (attemptCount < 1L) add(LogAttemptIssue.INVALID_ATTEMPT_COUNT)
    if (quality != null && quality !in 1L..5L) add(LogAttemptIssue.INVALID_QUALITY)
    if (outcome == AttemptOutcome.ATTEMPT && quality != null) {
        add(LogAttemptIssue.QUALITY_REQUIRES_SEND)
    }
    if (outcome == AttemptOutcome.ATTEMPT && isBenchmark) {
        add(LogAttemptIssue.BENCHMARK_REQUIRES_SEND)
    }
}
