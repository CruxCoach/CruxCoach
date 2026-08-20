package com.cruxcoach.android.boardcell

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where an entry goes.
 *
 * Positions are expressed relative to another *occurrence*, never as an index:
 * an index means something different the moment anybody else adds, removes or
 * moves anything, and two people editing at once is the normal case rather
 * than the exception.
 */
@Serializable
sealed interface BoardPlaylistAnchor {
    @Serializable @SerialName("head") data object Head : BoardPlaylistAnchor
    @Serializable @SerialName("tail") data object Tail : BoardPlaylistAnchor
    @Serializable @SerialName("after") data class After(val entryId: String) : BoardPlaylistAnchor
}

/**
 * One bounded, typed change to the shared playlist.
 *
 * These are what actually travels between devices. A normal edit used to be
 * broadcast as the whole playlist, which meant a 500-entry list crossed a BLE
 * mesh every time somebody moved one climb, and which made "who won" depend on
 * whose full copy arrived last. An operation is small, says exactly what was
 * meant, and — because every occurrence has a stable id — stays meaningful
 * even when the list moved underneath it. Full snapshots remain the repair
 * path: join, restart, a detected gap, anti-entropy, controller recovery and
 * handover all still carry complete canonical state.
 *
 * Fields the controller stamps (rest instants, the clear generation) are
 * defaulted to zero in a command and filled in when it is committed, so a
 * member can never dictate a canonical deadline.
 */
@Serializable
sealed interface BoardPlaylistOp {

    /**
     * Append or insert one occurrence.
     *
     * [entryId] is minted by the sender and is what makes a retry idempotent:
     * the same add applied twice is one entry, on any controller, in any
     * order, without a dedup window having to remember it.
     */
    @Serializable
    @SerialName("add")
    data class Add(
        val entryId: String,
        val climbUuid: String,
        val angle: Int,
        val restAfterSeconds: Int = 0,
        val anchor: BoardPlaylistAnchor = BoardPlaylistAnchor.Tail,
    ) : BoardPlaylistOp

    /** Remove one occurrence. Removing something already gone is a no-op. */
    @Serializable
    @SerialName("remove")
    data class Remove(val entryId: String) : BoardPlaylistOp

    /** Reposition one occurrence, keeping its identity and its rest plan. */
    @Serializable
    @SerialName("move")
    data class Move(val entryId: String, val anchor: BoardPlaylistAnchor) : BoardPlaylistOp

    /** Point the group at one occurrence. Cancels a running rest. */
    @Serializable
    @SerialName("current")
    data class SetCurrent(val entryId: String) : BoardPlaylistOp

    /** Change the planned rest that follows one occurrence. */
    @Serializable
    @SerialName("rest")
    data class SetRest(val entryId: String, val seconds: Int) : BoardPlaylistOp

    /**
     * Begin a planned rest in front of [nextEntryId].
     *
     * The three stamped fields are the controller's, not the sender's: only
     * the device that serializes the commit reads a clock, so every replica
     * derives the same bytes and the same state hash from it.
     */
    @Serializable
    @SerialName("rest_start")
    data class StartRest(
        val nextEntryId: String,
        val totalSeconds: Int,
        val generation: Long = 0,
        val startedAtEpochMs: Long = 0,
        val endsAtEpochMs: Long = 0,
    ) : BoardPlaylistOp

    /** The rest ran out or somebody skipped it. */
    @Serializable
    @SerialName("rest_end")
    data object EndRest : BoardPlaylistOp

    /**
     * Empty the shared playlist.
     *
     * [generation] is stamped by the controller. Every command carries the
     * generation it was composed against, so an edit that was in flight while
     * somebody else emptied the list is dropped rather than resurrecting one
     * entry of a playlist that no longer exists, and a retried clear names a
     * generation that has already been reached and changes nothing.
     */
    @Serializable
    @SerialName("clear")
    data class Clear(val generation: Long = 0) : BoardPlaylistOp

    /**
     * Record that the current entry is (not) on the wall.
     *
     * A physical fact about the controller's own board write, so only the
     * controller may assert it; it is rejected outright when it arrives from
     * the wire.
     */
    @Serializable
    @SerialName("pending")
    data class SetPendingProjection(val pending: BoardPlaylistPendingProjection?) : BoardPlaylistOp
}

/**
 * One atomic, idempotent playlist edit.
 *
 * The whole list of [ops] commits together or not at all, which is what lets
 * "advance and start the planned rest" be one canonical step rather than two
 * that a reconnect or a controller handover could split apart.
 */
@Serializable
data class BoardPlaylistCommand(
    val commandId: String,
    val basePlaylistRevision: Long,
    val baseClearGeneration: Long = 0,
    val ops: List<BoardPlaylistOp> = emptyList(),
)

/** Fresh, stable identity for one occurrence. */
object BoardPlaylistEntryId {
    fun random(): String = UUID.randomUUID().toString().replace("-", "")
}

/**
 * The product rules for the shared playlist, as one pure function.
 *
 * Keeping this free of transport, coroutines and Android lets the controller,
 * every replica and the tests run byte-identical logic. There is deliberately
 * nothing about hosts, membership or approval in here: being in the BoardCell
 * is taking part, and the technical controller only decides the *order* of
 * these operations, never whether somebody was allowed to want them.
 */
object BoardPlaylistPolicy {
    const val MAX_ENTRIES = 512
    const val MAX_OPS_PER_COMMAND = 256
    const val MAX_REST_SECONDS = 3_600
    const val MAX_ID_LENGTH = 256
    const val MAX_ENTRY_ID_LENGTH = 64

    sealed interface Outcome {
        /** Commit these canonical operations as the next playlist state. */
        data class Commit(
            val ops: List<BoardPlaylistOp>,
            val playlist: BoardPlaylistState,
        ) : Outcome

        /**
         * Legal and already satisfied. The command is acknowledged as
         * committed so a retry never reads as a failure, but nothing moves a
         * second time.
         */
        data object Accepted : Outcome

        data class Reject(val reason: String) : Outcome
    }

    /**
     * Normalizes a playlist into its canonical shape.
     *
     * Applied after every operation batch so the invariants hold no matter
     * which device produced the state — a peer cannot smuggle an inconsistent
     * playlist past the reducer, and every replica derives the same bytes for
     * the state hash.
     */
    fun normalize(playlist: BoardPlaylistState): BoardPlaylistState {
        val entries = ArrayList<BoardPlaylistEntry>(playlist.entries.size)
        val seen = HashSet<String>(playlist.entries.size * 2)
        for (entry in playlist.entries) {
            if (entries.size >= MAX_ENTRIES) break
            if (entry.entryId.isBlank() || entry.entryId.length > MAX_ENTRY_ID_LENGTH) continue
            if (entry.climbUuid.isBlank() || entry.climbUuid.length > MAX_ID_LENGTH) continue
            if (!seen.add(entry.entryId)) continue
            entries += entry.copy(
                restAfterSeconds = entry.restAfterSeconds.coerceIn(0, MAX_REST_SECONDS),
            )
        }
        // The group is always looking at something while there is anything to
        // look at. Falling back to the first entry rather than to "nothing"
        // keeps the player, the wall and every replica in agreement after a
        // remove that happened to take the current climb.
        val current = playlist.currentEntryId?.takeIf { id -> entries.any { it.entryId == id } }
            ?: entries.firstOrNull()?.entryId
        // The window must really be the duration it claims. Checking only the
        // far end let a "two minute" pause end in 2099, which every replica
        // would then have hashed and honoured, and which a process restart
        // would have started again at full length every time.
        val rest = playlist.activeRest?.takeIf { active ->
            active.totalSeconds in 1..MAX_REST_SECONDS &&
                entries.any { it.entryId == active.nextEntryId } &&
                BoardPlaylistInstant.isWindow(active.startedAtEpochMs, active.endsAtEpochMs,
                    active.totalSeconds * 1_000L)
        }
        // The pending-send state describes the entry the wall is supposed to
        // be showing. Keeping one that named some other queued entry let a
        // stale "send pending" survive a next/remove and misreport a climb
        // that had since been projected perfectly well.
        val pending = playlist.pendingProjection?.takeIf { candidate ->
            val entry = entries.firstOrNull { it.entryId == current }
            entry != null && entry.entryId == candidate.entryId &&
                entry.climbUuid == candidate.climbUuid && entry.angle == candidate.angle
        }
        return playlist.copy(
            entries = entries,
            currentEntryId = current,
            activeRest = rest,
            pendingProjection = pending,
            clearGeneration = playlist.clearGeneration.coerceAtLeast(0),
        )
    }

    /**
     * Applies canonical operations in order.
     *
     * Pure and total: the controller runs it to decide what to commit and
     * every replica runs it again on the identical base, so the resulting
     * state hash either matches or the replica repairs itself from a snapshot.
     * Every operation is idempotent by construction, which is what makes a
     * retry after a reconnect or a controller handover safe.
     */
    fun apply(current: BoardPlaylistState, ops: List<BoardPlaylistOp>): BoardPlaylistState =
        normalize(ops.fold(current, ::applyOne))

    private fun applyOne(state: BoardPlaylistState, op: BoardPlaylistOp): BoardPlaylistState =
        when (op) {
            is BoardPlaylistOp.Add -> add(state, op)
            is BoardPlaylistOp.Remove -> remove(state, op.entryId)
            is BoardPlaylistOp.Move -> move(state, op)
            is BoardPlaylistOp.SetCurrent ->
                if (state.entry(op.entryId) == null) state
                else state.copy(currentEntryId = op.entryId, activeRest = null)
            is BoardPlaylistOp.SetRest -> setRest(state, op)
            is BoardPlaylistOp.StartRest -> startRest(state, op)
            BoardPlaylistOp.EndRest -> state.copy(activeRest = null)
            is BoardPlaylistOp.Clear ->
                if (op.generation <= state.clearGeneration) state
                else state.copy(entries = emptyList(), currentEntryId = null, activeRest = null,
                    pendingProjection = null, clearGeneration = op.generation)
            is BoardPlaylistOp.SetPendingProjection -> state.copy(pendingProjection = op.pending)
        }

    private fun add(state: BoardPlaylistState, op: BoardPlaylistOp.Add): BoardPlaylistState {
        if (state.entry(op.entryId) != null) return state
        if (state.entries.size >= MAX_ENTRIES) return state
        val entry = BoardPlaylistEntry(op.entryId, op.climbUuid, op.angle, op.restAfterSeconds)
        val entries = state.entries.toMutableList()
        entries.add(insertionIndex(entries, op.anchor, forAdd = true) ?: entries.size, entry)
        return state.copy(entries = entries)
    }

    /**
     * Removing the entry a running rest is waiting on ends the rest.
     *
     * Otherwise the group would keep counting down towards a climb that is no
     * longer in the playlist. Removing the current entry moves the group to
     * whatever now occupies that position, which is what "the next one" means
     * to somebody looking at the list.
     */
    private fun remove(state: BoardPlaylistState, entryId: String): BoardPlaylistState {
        val index = state.indexOf(entryId)
        if (index < 0) return state
        val entries = state.entries.toMutableList().apply { removeAt(index) }
        val current = when {
            state.currentEntryId != entryId -> state.currentEntryId
            entries.isEmpty() -> null
            else -> entries[index.coerceAtMost(entries.lastIndex)].entryId
        }
        return state.copy(
            entries = entries,
            currentEntryId = current,
            activeRest = state.activeRest?.takeIf { it.nextEntryId != entryId },
        )
    }

    /**
     * A move keeps the entry's identity, so the current climb and a running
     * rest follow it rather than being reassigned to whatever slid into the
     * old position.
     *
     * An anchor that has since disappeared leaves the entry exactly where it
     * is. The alternative — dropping it at some default position — would
     * silently reorder the list on a conflict, which is worse than not acting
     * on an instruction whose reference point is gone.
     */
    private fun move(state: BoardPlaylistState, op: BoardPlaylistOp.Move): BoardPlaylistState {
        val from = state.indexOf(op.entryId)
        if (from < 0) return state
        if (op.anchor is BoardPlaylistAnchor.After && op.anchor.entryId == op.entryId) return state
        val entries = state.entries.toMutableList()
        val entry = entries.removeAt(from)
        val to = insertionIndex(entries, op.anchor, forAdd = false)
        if (to == null) return state
        entries.add(to, entry)
        return state.copy(entries = entries)
    }

    private fun setRest(state: BoardPlaylistState, op: BoardPlaylistOp.SetRest): BoardPlaylistState {
        val index = state.indexOf(op.entryId)
        if (index < 0) return state
        val entries = state.entries.toMutableList()
        entries[index] = entries[index].copy(
            restAfterSeconds = op.seconds.coerceIn(0, MAX_REST_SECONDS))
        return state.copy(entries = entries)
    }

    private fun startRest(state: BoardPlaylistState, op: BoardPlaylistOp.StartRest): BoardPlaylistState {
        if (state.entry(op.nextEntryId) == null) return state
        val rest = BoardPlaylistRest(
            totalSeconds = op.totalSeconds,
            generation = op.generation,
            nextEntryId = op.nextEntryId,
            startedAtEpochMs = op.startedAtEpochMs,
            endsAtEpochMs = op.endsAtEpochMs,
        )
        return state.copy(activeRest = rest)
    }

    /**
     * Null means "the reference point is gone"; the caller decides whether
     * that is a tail insert (add) or nothing at all (move).
     */
    private fun insertionIndex(
        entries: List<BoardPlaylistEntry>,
        anchor: BoardPlaylistAnchor,
        forAdd: Boolean,
    ): Int? = when (anchor) {
        BoardPlaylistAnchor.Head -> 0
        BoardPlaylistAnchor.Tail -> entries.size
        is BoardPlaylistAnchor.After -> entries.indexOfFirst { it.entryId == anchor.entryId }
            .takeIf { it >= 0 }?.plus(1) ?: if (forAdd) entries.size else null
    }

    /**
     * Turns one member's command into the canonical operations to commit.
     *
     * This is where the controller's clock and the clear generation are
     * stamped, and where a command composed against a playlist that has since
     * been emptied is refused rather than half-applied. Everything else is
     * decided by [apply], which every replica also runs.
     */
    fun resolve(
        current: BoardPlaylistState,
        senderId: String,
        command: BoardPlaylistCommand,
        nowEpochMs: Long,
        senderIsController: Boolean,
    ): Outcome {
        if (senderId.isBlank() || senderId.length > MAX_ID_LENGTH)
            return Outcome.Reject("invalid sender")
        if (command.ops.isEmpty()) return Outcome.Accepted
        if (command.ops.size > MAX_OPS_PER_COMMAND) return Outcome.Reject("too many operations")
        if (command.baseClearGeneration < current.clearGeneration)
            return Outcome.Reject("the shared playlist was cleared")
        if (command.baseClearGeneration > current.clearGeneration)
            return Outcome.Reject("clear generation is ahead of the controller")
        if (command.ops.any { it is BoardPlaylistOp.SetPendingProjection } && !senderIsController)
            return Outcome.Reject("only the controller reports the physical send")
        var clearGeneration = current.clearGeneration
        var restGeneration = current.activeRest?.generation ?: 0L
        val resolved = ArrayList<BoardPlaylistOp>(command.ops.size)
        for (op in command.ops) {
            when (op) {
                is BoardPlaylistOp.Clear -> {
                    if (clearGeneration == Long.MAX_VALUE)
                        return Outcome.Reject("clear generation exhausted")
                    clearGeneration += 1
                    resolved += BoardPlaylistOp.Clear(clearGeneration)
                }
                is BoardPlaylistOp.StartRest -> {
                    val seconds = op.totalSeconds.coerceIn(1, MAX_REST_SECONDS)
                    val endsAt = nowEpochMs + seconds * 1_000L
                    // Both ends, so the duration is checkable. Not clamped: a
                    // clamped end would silently break the pair invariant. A
                    // controller whose clock is outside the believable window
                    // publishes no rest at all rather than a subtly wrong one
                    // that every replica would then hash and honour — and
                    // dropping it here also keeps the committed delta within
                    // the bounds every peer decodes it against.
                    if (BoardPlaylistInstant.isWindow(nowEpochMs, endsAt, seconds * 1_000L)) {
                        restGeneration += 1
                        resolved += op.copy(
                            totalSeconds = seconds,
                            generation = restGeneration,
                            startedAtEpochMs = nowEpochMs,
                            endsAtEpochMs = endsAt,
                        )
                    }
                }
                else -> resolved += op
            }
        }
        val next = apply(current, resolved)
        if (next == normalize(current)) return Outcome.Accepted
        return Outcome.Commit(resolved, next)
    }
}

/**
 * Builders that turn what the user did into canonical operations.
 *
 * The single place index-based UI intent becomes occurrence-addressed, so no
 * caller has to remember that "remove position 3" is only meaningful against
 * the list it was read from.
 */
object BoardPlaylistOps {
    fun add(
        climbUuid: String,
        angle: Int,
        restAfterSeconds: Int = 0,
        entryId: String = BoardPlaylistEntryId.random(),
        anchor: BoardPlaylistAnchor = BoardPlaylistAnchor.Tail,
    ): List<BoardPlaylistOp> =
        listOf(BoardPlaylistOp.Add(entryId, climbUuid, angle, restAfterSeconds, anchor))

    /**
     * Appends a whole list, preserving its order.
     *
     * Each entry after the first is anchored behind the one before it rather
     * than at the tail. That is what keeps a long import contiguous and in its
     * own order when it has to be split across commands and somebody else's
     * add lands between two of them — a chain of tail inserts would have let
     * their climb split the workout in half.
     */
    fun addAll(
        items: List<Triple<String, Int, Int>>,
        newEntryId: () -> String = BoardPlaylistEntryId::random,
    ): List<BoardPlaylistOp> {
        var previous: String? = null
        return items.map { (climbUuid, angle, rest) ->
            val entryId = newEntryId()
            val anchor = previous?.let { BoardPlaylistAnchor.After(it) } ?: BoardPlaylistAnchor.Tail
            previous = entryId
            BoardPlaylistOp.Add(entryId, climbUuid, angle, rest, anchor)
        }
    }

    fun removeAt(state: BoardPlaylistState, index: Int): List<BoardPlaylistOp> =
        state.entryIdAt(index)?.let { listOf(BoardPlaylistOp.Remove(it)) }.orEmpty()

    fun setCurrentAt(state: BoardPlaylistState, index: Int): List<BoardPlaylistOp> =
        state.entryIdAt(index)?.let { listOf(BoardPlaylistOp.SetCurrent(it)) }.orEmpty()

    fun setRestAt(state: BoardPlaylistState, index: Int, seconds: Int): List<BoardPlaylistOp> =
        state.entryIdAt(index)?.let { listOf(BoardPlaylistOp.SetRest(it, seconds)) }.orEmpty()

    /** Reordering is expressed relative to the neighbour it lands behind. */
    fun moveAt(state: BoardPlaylistState, from: Int, to: Int): List<BoardPlaylistOp> {
        val entryId = state.entryIdAt(from) ?: return emptyList()
        if (from == to) return emptyList()
        val remaining = state.entries.filterNot { it.entryId == entryId }
        val target = to.coerceIn(0, remaining.size)
        val anchor = if (target == 0) BoardPlaylistAnchor.Head
        else BoardPlaylistAnchor.After(remaining[target - 1].entryId)
        return listOf(BoardPlaylistOp.Move(entryId, anchor))
    }

    /**
     * Advance, arming the planned rest of the entry being left in the same
     * canonical step.
     *
     * One command rather than an advance followed by a separate rest: the pair
     * could otherwise be split by a reconnect or a controller handover, and a
     * peer would show the next climb ready to go while the rest of the group
     * was resting in front of the wall.
     */
    fun next(state: BoardPlaylistState): List<BoardPlaylistOp> {
        val index = state.currentIndex
        if (index < 0 || index >= state.entries.lastIndex) return emptyList()
        val leaving = state.entries[index]
        val target = state.entries[index + 1].entryId
        val ops = mutableListOf<BoardPlaylistOp>(BoardPlaylistOp.SetCurrent(target))
        if (leaving.restAfterSeconds > 0) {
            ops += BoardPlaylistOp.StartRest(target, leaving.restAfterSeconds)
        }
        return ops
    }

    fun previous(state: BoardPlaylistState): List<BoardPlaylistOp> {
        val index = state.currentIndex
        if (index <= 0) return emptyList()
        return listOf(BoardPlaylistOp.SetCurrent(state.entries[index - 1].entryId))
    }

    /**
     * Drop the queued repeats of the entry at [index] and carry over the rest
     * the dropped block ended on.
     *
     * A hard-bouldering or 4x4 block writes the same problem out several times
     * in a row, and topping it first go ends the work on it; the remaining
     * tries are a budget, not a quota. The rest that now follows is the one
     * that separated two attempts on the same problem — too short for what it
     * has become, which is the gap before a different problem.
     */
    fun dropRepeatsAfter(state: BoardPlaylistState, index: Int): List<BoardPlaylistOp> {
        val anchor = state.entries.getOrNull(index) ?: return emptyList()
        var end = index + 1
        while (end < state.entries.size && state.entries[end].climbUuid == anchor.climbUuid) end++
        if (end == index + 1) return emptyList()
        return (index + 1 until end).map { BoardPlaylistOp.Remove(state.entries[it].entryId) } +
            BoardPlaylistOp.SetRest(anchor.entryId, state.entries[end - 1].restAfterSeconds)
    }

    fun endRest(): List<BoardPlaylistOp> = listOf(BoardPlaylistOp.EndRest)

    fun clear(): List<BoardPlaylistOp> = listOf(BoardPlaylistOp.Clear())
}
