package com.cruxcoach.app.kilter

import com.cruxcoach.app.ui.Subscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The logbook's "Publish to the CruxCoach community" under an entry (Android
 * `BoardLogbookState.ownPublishableClimbUuids`, `ownPublishInProgressUuid`,
 * `ownPublishFeedback`). [feedback]: none | published | noNostrIdentity | notAuthor |
 * alreadyPublished | failed.
 */
class LogbookOwnPublishState internal constructor(
    private val publishable: Set<String>,
    /** The climb uuid being published; "" when none (Android allows one at a time). */
    val inProgressUuid: String,
    val feedback: String,
) {
    /** Android's gate per entry: the connected account authored the climb and it is not on the community yet. */
    fun isPublishable(climbUuid: String): Boolean =
        OwnKilterClimbPublisher.normalizeClimbUuid(climbUuid) in publishable

    internal fun with(
        publishable: Set<String> = this.publishable,
        inProgressUuid: String = this.inProgressUuid,
        feedback: String = this.feedback,
    ) = LogbookOwnPublishState(publishable, inProgressUuid, feedback)
}

class LogbookOwnPublishModel internal constructor(
    private val publisher: OwnKilterClimbPublisher,
    main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(LogbookOwnPublishState(emptySet(), "", FEEDBACK_NONE))

    val currentState: LogbookOwnPublishState get() = state.value

    fun watch(onState: (LogbookOwnPublishState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(it) } }
        return Subscription { job.cancel() }
    }

    /** Recomputes the gate: on open and on return from a climb (its detail may have published it). */
    fun load() {
        scope.launch { refresh() }
    }

    /** Android `publishOwnClimb`: one at a time; the gate is recomputed after a publish. */
    fun publish(climbUuid: String) {
        if (state.value.inProgressUuid.isNotEmpty() || climbUuid.isBlank()) return
        state.update { it.with(inProgressUuid = climbUuid, feedback = FEEDBACK_NONE) }
        scope.launch {
            val code = try {
                withContext(io) { publisher.publish(climbUuid) }.code
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "failed"
            }
            state.update { it.with(inProgressUuid = "", feedback = code) }
            if (code == "published") refresh()
        }
    }

    fun consumeFeedback() = state.update { it.with(feedback = FEEDBACK_NONE) }

    fun close() = scope.cancel()

    private suspend fun refresh() {
        val publishable = try {
            withContext(io) { publisher.publishableAsMineUuids() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        state.update { it.with(publishable = publishable) }
    }

    companion object {
        const val FEEDBACK_NONE = "none"
    }
}
