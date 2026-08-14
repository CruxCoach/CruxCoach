package com.cruxcoach.android.ui.competition

import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionClimb
import com.cruxcoach.domain.competition.CompetitionState
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.Participant

/**
 * DOM/Compose-free presentation policy for the participant live experience.
 * It derives every cue from shared reduced state so UI tests can cover the
 * lifecycle matrix without creating a second competition state machine.
 */
object CompetitionLivePolicy {
    enum class Cue { SPECTATOR, WAITING, NOT_QUEUED, CURRENT, NEXT, QUEUED, PAUSED, FINISHED, CANCELLED }

    data class PersonalCue(
        val kind: Cue,
        val ahead: Int? = null,
        val index: Int = -1,
        /** 0 is this round, 1 is the next pass through the order. */
        val roundOffset: Int = 0,
    )

    fun personalCue(state: CompetitionState?, pubkey: String, running: Boolean = state?.status == "running"): PersonalCue {
        if (state == null || pubkey.isBlank()) return PersonalCue(Cue.SPECTATOR)
        val index = state.order.indexOf(pubkey)
        return when (state.status) {
            "finished" -> PersonalCue(Cue.FINISHED, index = index)
            "cancelled" -> PersonalCue(Cue.CANCELLED, index = index)
            "paused" -> PersonalCue(Cue.PAUSED, index = index)
            else -> if (running) when {
                index < 0 -> PersonalCue(Cue.NOT_QUEUED)
                state.cursor == index -> PersonalCue(Cue.CURRENT, 0, index)
                else -> {
                    val cursor = if (state.cursor < 0) 0 else state.cursor
                    val passedThisRound = state.cursor >= 0 && index < cursor
                    val ahead = if (passedThisRound) state.order.size - cursor + index else index - cursor
                    PersonalCue(
                        if (ahead == 1) Cue.NEXT else Cue.QUEUED,
                        ahead.coerceAtLeast(0),
                        index,
                        if (passedThisRound) 1 else 0,
                    )
                }
            } else PersonalCue(Cue.WAITING, index = index)
        }
    }

    data class QueueEntry(
        val pubkey: String,
        val participant: Participant?,
        val queuePosition: Int,
        val current: Boolean,
        val next: Boolean,
        val roundOffset: Int,
    )

    data class QueuePreview(val entries: List<QueueEntry>, val hidden: Int)

    fun queue(state: CompetitionState?, limit: Int = 6): QueuePreview {
        if (state == null) return QueuePreview(emptyList(), 0)
        val start = (if (state.cursor < 0) 0 else state.cursor).coerceIn(0, state.order.size.coerceAtLeast(1) - 1)
        // Keep a complete rotation visible. Entries before the cursor have not
        // vanished: their next turn is in the next round.
        val rotation = if (state.cursor < 0) state.order else state.order.drop(start) + state.order.take(start)
        val entries = rotation.take(limit).mapIndexed { offset, pubkey ->
            QueueEntry(
                pubkey = pubkey,
                participant = state.participant(pubkey),
                queuePosition = offset,
                current = state.cursor >= 0 && start + offset == state.cursor,
                next = state.cursor >= 0 && offset == 1,
                roundOffset = if (state.cursor >= 0 && start + offset >= state.order.size) 1 else 0,
            )
        }
        return QueuePreview(entries, (rotation.size - entries.size).coerceAtLeast(0))
    }

    /**
     * A conservative queue estimate. We only show it while the entrant still
     * has a turn in the open round; crossing a round boundary is organizer
     * controlled and therefore cannot be estimated honestly.
     */
    fun etaSeconds(state: CompetitionState?, pubkey: String, nowSeconds: Long): Long? {
        if (state == null || state.status != "running" || state.cursor < 0 || state.turnDeadlineAt <= nowSeconds) return null
        val index = state.order.indexOf(pubkey)
        if (index < state.cursor) return null
        val ahead = index - state.cursor
        if (ahead == 0) return 0
        val turnLength = (state.turnDeadlineAt - state.turnOpenedAt).takeIf { it > 0 } ?: return null
        val currentLeft = state.turnDeadlineAt - nowSeconds
        return currentLeft + (ahead - 1L) * turnLength
    }

    data class RotationEntry(val climb: CompetitionClimb, val current: Boolean, val next: Boolean)
    data class RotationPreview(val entries: List<RotationEntry>, val hidden: Int)

    fun rotation(
        competition: Competition?,
        state: CompetitionState?,
        participant: Participant?,
        limit: Int = 4,
    ): RotationPreview {
        if (competition == null || state == null) return RotationPreview(emptyList(), 0)
        val source = competition.climbsFor(participant?.selections.orEmpty())
        val complete = participant?.climbs.orEmpty()
            .filter { it.outcome == "top" || it.outcome == "dnf" }
            .mapTo(mutableSetOf()) { it.climbId }
        val ordered = if (competition.rules.climbSource == "organizer_set" &&
            competition.rules.progression != "asynchronous_turns"
        ) {
            val current = source.indexOfFirst { it.id == state.currentClimbId }.let { if (it < 0) 0 else it }
            source.drop(current) + source.take(current)
        } else {
            source.filterNot { it.id in complete }
        }
        val synchronous = competition.rules.climbSource == "organizer_set" &&
            competition.rules.progression != "asynchronous_turns"
        val entries = ordered.take(limit).mapIndexed { index, climb ->
            RotationEntry(
                climb,
                current = synchronous && climb.id == state.currentClimbId,
                next = index == if (synchronous) 1 else 0,
            )
        }
        return RotationPreview(entries, (ordered.size - entries.size).coerceAtLeast(0))
    }

    enum class DeferReason { NOT_ENTERED, PHASE, PAUSED, NOT_YOUR_TURN, BUDGET, CONSECUTIVE }
    data class DeferAvailability(val allowed: Boolean, val reason: DeferReason? = null, val left: Int = 0)

    fun defer(
        state: CompetitionState?,
        competition: Competition?,
        participant: Participant?,
        pubkey: String,
        at: Long? = null,
    ): DeferAvailability {
        if (state == null || competition == null || participant == null) {
            return DeferAvailability(false, DeferReason.NOT_ENTERED)
        }
        if (state.status == "paused") return DeferAvailability(false, DeferReason.PAUSED)
        val running = at?.let { CompetitionProtocol.competitionRunning(competition, state.status, it) }
            ?: (state.status == "running")
        if (!running) return DeferAvailability(false, DeferReason.PHASE)
        if (state.order.getOrNull(state.cursor) != pubkey) {
            return DeferAvailability(false, DeferReason.NOT_YOUR_TURN)
        }
        val left = (competition.rules.deferBudgetPerRound - participant.defersUsedThisRound).coerceAtLeast(0)
        if (left == 0) return DeferAvailability(false, DeferReason.BUDGET)
        if (participant.consecutiveDefers >= competition.rules.maxConsecutiveDefers) {
            return DeferAvailability(false, DeferReason.CONSECUTIVE)
        }
        return DeferAvailability(true, left = left)
    }

    enum class Sync { CONNECTING, LIVE, OFFLINE, STALE }
    data class SyncHealth(val kind: Sync, val ageSeconds: Long? = null, val connectedRelays: Int = 0)

    fun syncHealth(hasState: Boolean, connectedRelays: Int, lastSyncedAt: Long, nowSeconds: Long): SyncHealth {
        val age = lastSyncedAt.takeIf { it > 0 }?.let { (nowSeconds - it).coerceAtLeast(0) }
        return when {
            connectedRelays > 0 -> SyncHealth(Sync.LIVE, age, connectedRelays)
            hasState && age != null && age >= 60 -> SyncHealth(Sync.STALE, age)
            hasState -> SyncHealth(Sync.OFFLINE, age)
            else -> SyncHealth(Sync.CONNECTING)
        }
    }
}
