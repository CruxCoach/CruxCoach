package com.cruxcoach.android.ui.competition

import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionClimb
import com.cruxcoach.domain.competition.CompetitionState
import com.cruxcoach.domain.competition.Participant

/**
 * DOM/Compose-free presentation policy for the participant live experience.
 * It derives every cue from shared reduced state so UI tests can cover the
 * lifecycle matrix without creating a second competition state machine.
 */
object CompetitionLivePolicy {
    enum class Cue { SPECTATOR, WAITING, NOT_QUEUED, CURRENT, NEXT, QUEUED, PAUSED, FINISHED, CANCELLED }

    data class PersonalCue(val kind: Cue, val ahead: Int? = null, val index: Int = -1)

    fun personalCue(state: CompetitionState?, pubkey: String): PersonalCue {
        if (state == null || pubkey.isBlank()) return PersonalCue(Cue.SPECTATOR)
        val index = state.order.indexOf(pubkey)
        return when (state.status) {
            "finished" -> PersonalCue(Cue.FINISHED, index = index)
            "cancelled" -> PersonalCue(Cue.CANCELLED, index = index)
            "paused" -> PersonalCue(Cue.PAUSED, index = index)
            "running" -> when {
                index < 0 -> PersonalCue(Cue.NOT_QUEUED)
                state.cursor == index -> PersonalCue(Cue.CURRENT, 0, index)
                else -> {
                    val cursor = if (state.cursor < 0) 0 else state.cursor
                    val ahead = (index - cursor).coerceAtLeast(0)
                    PersonalCue(if (ahead == 1) Cue.NEXT else Cue.QUEUED, ahead, index)
                }
            }
            else -> PersonalCue(Cue.WAITING, index = index)
        }
    }

    data class QueueEntry(
        val pubkey: String,
        val participant: Participant?,
        val queuePosition: Int,
        val current: Boolean,
        val next: Boolean,
    )

    data class QueuePreview(val entries: List<QueueEntry>, val hidden: Int)

    fun queue(state: CompetitionState?, limit: Int = 6): QueuePreview {
        if (state == null) return QueuePreview(emptyList(), 0)
        val start = (if (state.cursor < 0) 0 else state.cursor).coerceAtLeast(0)
        val remaining = state.order.drop(start)
        val entries = remaining.take(limit).mapIndexed { offset, pubkey ->
            QueueEntry(
                pubkey = pubkey,
                participant = state.participant(pubkey),
                queuePosition = offset,
                current = state.cursor >= 0 && start + offset == state.cursor,
                next = state.cursor >= 0 && start + offset == state.cursor + 1,
            )
        }
        return QueuePreview(entries, (remaining.size - entries.size).coerceAtLeast(0))
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

    fun defer(state: CompetitionState?, competition: Competition?, participant: Participant?, pubkey: String): DeferAvailability {
        if (state == null || competition == null || participant == null) {
            return DeferAvailability(false, DeferReason.NOT_ENTERED)
        }
        if (state.status == "paused") return DeferAvailability(false, DeferReason.PAUSED)
        if (state.status != "running") return DeferAvailability(false, DeferReason.PHASE)
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
