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

    /**
     * Point the group at one occurrence. Cancels a running rest.
     *
     * The cursor, and only the cursor: what people are looking at, stepping
     * through and resting against. It says nothing about the wall, which is
     * why it is separate from [SetCurrent] and why anybody may send it.
     */
    @Serializable
    @SerialName("selection")
    data class SetSelection(val entryId: String) : BoardPlaylistOp

    /**
     * Record a relayed guest write's intention, so the whole cell shares it.
     *
     * A physical fact about a write this controller admitted, so only the
     * controller may assert it. Idempotent by `(fingerprint, guestKey)`: the
     * same intention recorded twice updates the one record rather than adding
     * a second.
     */
    @Serializable
    @SerialName("relay_op")
    data class RecordRelayOperation(val operation: BoardRelayOperation) : BoardPlaylistOp

    /**
     * Record that the board is confirmed to be showing one occurrence.
     *
     * A physical fact about the controller's own board write, so only the
     * controller may assert it and it is refused when it arrives from the
     * wire. Emitted by exactly one thing: a transport for that occurrence that
     * terminally succeeded.
     */
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
     *
     * The two instants are the controller's as well, and they are what turns
     * the clear into something the group can take back: they name the window
     * in which [RestoreClear] still works, so every replica counts the same
     * offer down without reading its own clock into the state hash. They are
     * defaulted to zero so an older committed clear — one that predates the
     * restore window — still decodes and simply offers nothing.
     */
    @Serializable
    @SerialName("clear")
    data class Clear(
        val generation: Long = 0,
        val clearedAtEpochMs: Long = 0,
        val restorableUntilEpochMs: Long = 0,
    ) : BoardPlaylistOp

    /**
     * Put the list a clear emptied back, in front of anything added since.
     *
     * Open to every member, not only to whoever pressed clear: the whole group
     * watches the list vanish, so the whole group must be able to bring it
     * back. [generation] names the clear being taken back, which is what makes
     * it idempotent — a retry names a generation whose record is already gone
     * and does nothing, and it can never resurrect an older list.
     */
    @Serializable
    @SerialName("clear_restore")
    data class RestoreClear(val generation: Long = 0) : BoardPlaylistOp

    /**
     * The restore offer ran out; drop what it was holding.
     *
     * A physical fact about the controller's clock rather than anybody's
     * intention, so only the controller may assert it and it is refused when
     * it arrives from the wire. Without it the emptied list would ride along
     * in every snapshot until the next clear replaced it.
     */
    @Serializable
    @SerialName("clear_expired")
    data class ExpireClearUndo(val generation: Long = 0) : BoardPlaylistOp

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

    /**
     * Relayed guest writes remembered at once.
     *
     * Enough for the handful of guests a single board can physically have,
     * and small enough that the record can never become a place to store
     * things in somebody else's canonical state.
     */
    const val MAX_RELAY_OPERATIONS = 8

    /**
     * How long a clear can be taken back.
     *
     * Long enough to notice the wall's list has gone and reach for the button,
     * short enough that the emptied list is not carried around in every
     * snapshot for the rest of the session. It is part of the canonical window
     * rather than a local UI timeout, because everybody sees the same offer.
     */
    const val RESTORE_WINDOW_MS = 30_000L

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
        // keeps the player and every replica in agreement after a remove that
        // happened to take the selected climb.
        //
        // A snapshot written before the selection existed carries its cursor in
        // `currentEntryId`, which is what that field used to be; adopting it
        // here is a pure function of the state, so every replica derives the
        // identical selection from the identical bytes.
        val selected = playlist.selectedEntryId?.takeIf { id -> entries.any { it.entryId == id } }
            ?: playlist.currentEntryId?.takeIf { id -> entries.any { it.entryId == id } }
            ?: entries.firstOrNull()?.entryId
        // The confirmed current gets no fallback of any kind. It names the
        // occurrence whose transport succeeded, so inventing one — which is
        // what the shared field used to do on every remove and every first add
        // — would be claiming a board write that never happened. Gone from the
        // list means gone from here; the wall's own climb lives in
        // `BoardCellSnapshot.projection` and is untouched by any of this.
        val current = playlist.currentEntryId?.takeIf { id -> entries.any { it.entryId == id } }
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
        // The pending-send state describes one occurrence that was asked for
        // and did not reach the wall. It is deliberately *not* tied to the
        // current: a failed send must leave the confirmed current alone, so the
        // marker usually names the occurrence sitting directly behind it.
        //
        // What it must still be is honest. Bound to an occurrence that exists,
        // and to that occurrence's own climb and angle — a marker whose entry
        // was removed, or whose entry now describes a different climb, is a
        // stale "not sent" that would misreport a wall nobody has touched.
        val pending = playlist.pendingProjection?.takeIf { candidate ->
            val entry = entries.firstOrNull { it.entryId == candidate.entryId }
            entry != null && entry.climbUuid == candidate.climbUuid &&
                entry.angle == candidate.angle
        }
        // The offer to undo a clear has to be an offer about *this* clear and
        // has to name a window it could really have been made in. Anything
        // else is a record that no replica could count down honestly, so it is
        // dropped rather than shown.
        val clearGeneration = playlist.clearGeneration.coerceAtLeast(0)
        val lastClear = playlist.lastClear?.takeIf { undo ->
            undo.generation == clearGeneration && clearGeneration > 0 &&
                undo.entries.size in 1..MAX_ENTRIES &&
                undo.entries.distinctBy { it.entryId }.size == undo.entries.size &&
                undo.entries.none { it.entryId.isBlank() || it.climbUuid.isBlank() } &&
                (undo.selectedEntryId == null ||
                    undo.entries.any { it.entryId == undo.selectedEntryId }) &&
                BoardPlaylistInstant.isWindow(undo.clearedAtEpochMs,
                    undo.restorableUntilEpochMs, RESTORE_WINDOW_MS)
        }?.let { undo ->
            // The clear buffer and post-clear additions share one snapshot
            // budget. Entries that no longer fit could not be restored anyway.
            val restorableEntries = undo.entries.take((MAX_ENTRIES - entries.size).coerceAtLeast(0))
            if (restorableEntries.isEmpty()) null else undo.copy(
                entries = restorableEntries,
                selectedEntryId = undo.selectedEntryId?.takeIf { id ->
                    restorableEntries.any { it.entryId == id }
                },
            )
        }
        // Bounded, and the oldest goes first. A relay's ingress history is a
        // convenience for matching retries, not a log — an unbounded one would
        // be an attacker-shaped queue in canonical state.
        val relayOperations = playlist.relayOperations
            .filter { record ->
                record.fingerprint.length in 1..MAX_ID_LENGTH &&
                    record.guestKey.length in 1..MAX_ID_LENGTH &&
                    record.operationId.length in 1..MAX_ID_LENGTH &&
                    record.entryId.length in 1..MAX_ENTRY_ID_LENGTH &&
                    // The same instant the wire insists on, so a record that
                    // survives validation also survives normalisation.
                    BoardPlaylistInstant.isValid(record.stampedAtEpochMs)
            }
            .distinctBy { it.fingerprint to it.guestKey }
            .takeLast(MAX_RELAY_OPERATIONS)
        return playlist.copy(
            entries = entries,
            selectedEntryId = selected,
            currentEntryId = current,
            activeRest = rest,
            pendingProjection = pending,
            relayOperations = relayOperations,
            clearGeneration = clearGeneration,
            lastClear = lastClear,
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
            is BoardPlaylistOp.SetSelection ->
                if (state.entry(op.entryId) == null) state
                else state.copy(selectedEntryId = op.entryId, activeRest = null)
            is BoardPlaylistOp.SetCurrent ->
                if (state.entry(op.entryId) == null) state
                else state.copy(currentEntryId = op.entryId)
            is BoardPlaylistOp.SetRest -> setRest(state, op)
            is BoardPlaylistOp.StartRest -> startRest(state, op)
            BoardPlaylistOp.EndRest -> state.copy(activeRest = null)
            is BoardPlaylistOp.Clear -> clear(state, op)
            is BoardPlaylistOp.RestoreClear -> restore(state, op.generation)
            is BoardPlaylistOp.ExpireClearUndo ->
                if (state.lastClear?.generation == op.generation) state.copy(lastClear = null)
                else state
            is BoardPlaylistOp.SetPendingProjection -> state.copy(pendingProjection = op.pending)
            is BoardPlaylistOp.RecordRelayOperation -> state.copy(
                relayOperations = state.relayOperations
                    .filterNot {
                        it.fingerprint == op.operation.fingerprint &&
                            it.guestKey == op.operation.guestKey
                    } + op.operation,
            )
        }

    /**
     * Empties the list and keeps what it held for the length of the window.
     *
     * The record is built here rather than travelling in the operation: every
     * replica applies this to the same predecessor, so all of them derive the
     * identical buffer without a 512-entry list crossing the mesh a second
     * time. A clear with no stamped window — an older committed delta, or a
     * clear of an already-empty list — simply offers nothing to restore.
     */
    private fun clear(state: BoardPlaylistState, op: BoardPlaylistOp.Clear): BoardPlaylistState {
        if (op.generation <= state.clearGeneration) return state
        val restorable = state.entries.isNotEmpty() &&
            BoardPlaylistInstant.isWindow(op.clearedAtEpochMs, op.restorableUntilEpochMs,
                RESTORE_WINDOW_MS)
        return state.copy(
            entries = emptyList(),
            // The cursor has nothing left to point at. The confirmed current
            // goes for a different reason: the occurrence it named no longer
            // exists, so it can no longer name it — the wall itself is
            // untouched and still says what it is showing.
            selectedEntryId = null,
            currentEntryId = null,
            activeRest = null,
            pendingProjection = null,
            clearGeneration = op.generation,
            lastClear = if (!restorable) null else BoardPlaylistClearUndo(
                generation = op.generation,
                entries = state.entries,
                selectedEntryId = state.selectedEntryId,
                clearedAtEpochMs = op.clearedAtEpochMs,
                restorableUntilEpochMs = op.restorableUntilEpochMs,
            ),
        )
    }

    /**
     * Puts the emptied list back in front of whatever has been added since.
     *
     * Restored entries keep their original occurrence ids, so replaying the
     * restore re-adds nothing that is already there, and a climb somebody
     * queued after the clear stays queued — it simply follows the list that
     * came back rather than being displaced by it. The group returns to the
     * entry it was on, if that entry is one of the ones restored.
     */
    private fun restore(state: BoardPlaylistState, generation: Long): BoardPlaylistState {
        val undo = state.lastClear?.takeIf { it.generation == generation } ?: return state
        val existing = state.entries.mapTo(HashSet()) { it.entryId }
        // If the two together would not fit, the climbs somebody queued after
        // the clear are the ones that stay: those are things the group asked
        // for since, and dropping them to make room for a list it had already
        // thrown away would be the wrong way round.
        val room = (MAX_ENTRIES - state.entries.size).coerceAtLeast(0)
        val restored = undo.entries.filterNot { it.entryId in existing }.take(room)
        if (restored.isEmpty()) return state.copy(lastClear = null)
        return state.copy(
            entries = restored + state.entries,
            // What comes back is where the group was looking. Nothing was
            // projected by taking a clear back, so the confirmed current is
            // not restored — it would be a board write nobody performed.
            selectedEntryId = undo.selectedEntryId?.takeIf { id -> restored.any { it.entryId == id } }
                ?: state.selectedEntryId,
            lastClear = null,
        )
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
        val selected = when {
            state.selectedEntryId != entryId -> state.selectedEntryId
            entries.isEmpty() -> null
            else -> entries[index.coerceAtMost(entries.lastIndex)].entryId
        }
        return state.copy(
            entries = entries,
            // The cursor moves to whatever now occupies that position, which is
            // what "the next one" means to somebody looking at the list. The
            // confirmed current does not move anywhere: removing the occurrence
            // the wall was showing means nothing on the list names it any more,
            // and normalisation drops it. Handing it to the neighbour — which
            // is what this did — was claiming a board write for a climb nobody
            // had sent.
            selectedEntryId = selected,
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
        // A restore is deliberately exempt from the clear-generation guard: it
        // is the one edit whose entire point is to name the generation the
        // list has already moved to. Only a command that is *nothing but*
        // restores, though — an ordinary edit riding along with one would be
        // exactly the stale write the guard exists to drop.
        if (command.baseClearGeneration < current.clearGeneration &&
            command.ops.any { it !is BoardPlaylistOp.RestoreClear })
            return Outcome.Reject("the shared playlist was cleared")
        // The other direction is not a race anybody can be in honestly: a
        // member claiming a generation the controller has not reached is
        // either ahead of canonical state or making one up.
        if (command.baseClearGeneration > current.clearGeneration)
            return Outcome.Reject("clear generation is ahead of the controller")
        if (command.ops.any { it is BoardPlaylistOp.SetPendingProjection } && !senderIsController)
            return Outcome.Reject("only the controller reports the physical send")
        // The confirmed current is a statement about the board, not an edit.
        // A member that could set it could tell the whole group a climb is on
        // the wall without anything ever having been written.
        if (command.ops.any { it is BoardPlaylistOp.SetCurrent } && !senderIsController)
            return Outcome.Reject("only the controller confirms what the board shows")
        if (command.ops.any { it is BoardPlaylistOp.RecordRelayOperation } && !senderIsController)
            return Outcome.Reject("only the controller admits a relayed write")
        if (command.ops.any { it is BoardPlaylistOp.ExpireClearUndo } && !senderIsController)
            return Outcome.Reject("only the controller retires the restore offer")
        // Somebody reached for the restore a moment too late. Saying so is the
        // honest answer; quietly acknowledging a command that did nothing
        // would leave them staring at an empty list they thought they had
        // just brought back.
        val expiredUndo = current.lastClear?.takeIf { it.hasExpired(nowEpochMs) }
        if (expiredUndo != null && command.ops.any {
                it is BoardPlaylistOp.RestoreClear && it.generation == expiredUndo.generation
            }) return Outcome.Reject("the restore window has passed")
        var clearGeneration = current.clearGeneration
        var restGeneration = current.activeRest?.generation ?: 0L
        val resolved = ArrayList<BoardPlaylistOp>(command.ops.size + 1)
        // An offer nobody took rides along in every snapshot until something
        // replaces it, so the first commit after it lapses is where it goes.
        if (expiredUndo != null && command.ops.size < MAX_OPS_PER_COMMAND) {
            resolved += BoardPlaylistOp.ExpireClearUndo(expiredUndo.generation)
        }
        for (op in command.ops) {
            when (op) {
                is BoardPlaylistOp.Clear -> {
                    if (clearGeneration == Long.MAX_VALUE)
                        return Outcome.Reject("clear generation exhausted")
                    clearGeneration += 1
                    // Both ends, so the window is checkable without a clock on
                    // the receiving side. A controller whose clock is outside
                    // the believable range clears without an offer rather than
                    // publishing one every replica would hash and count down
                    // wrongly — the list still goes, which is what was asked.
                    val until = nowEpochMs + RESTORE_WINDOW_MS
                    resolved += if (BoardPlaylistInstant.isWindow(nowEpochMs, until,
                            RESTORE_WINDOW_MS)) {
                        BoardPlaylistOp.Clear(clearGeneration, nowEpochMs, until)
                    } else BoardPlaylistOp.Clear(clearGeneration)
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

    /** The ops and the occurrence "put this on the board now" resolves to. */
    data class LightNow(val entryId: String, val ops: List<BoardPlaylistOp>)

    /**
     * Put one climb on the wall, as an occurrence the group can see.
     *
     * Opened from a playlist entry, that entry is what lights — its own stable
     * id, so a repeat of the same climb three rows down is not what moves and
     * the list does not reorder itself under anybody.
     *
     * Opened from anywhere else — the browser, a search, a deep link — the
     * climb is not on the list yet, so it becomes a new occurrence directly
     * after the current one. Never an existing occurrence of the same climb
     * found by searching: duplicates are legitimate (a 4x4 is four of them),
     * and quietly reusing or moving one is how somebody's later go disappears
     * from where they put it.
     */
    fun lightNow(
        state: BoardPlaylistState,
        climbUuid: String,
        angle: Int,
        fromEntryId: String? = null,
        newEntryId: () -> String = BoardPlaylistEntryId::random,
    ): LightNow {
        val existing = fromEntryId?.let(state::entry)
        if (existing != null) return LightNow(existing.entryId, emptyList())
        val entryId = newEntryId()
        val anchor = state.selectedEntryId
            ?.let { BoardPlaylistAnchor.After(it) }
            ?: BoardPlaylistAnchor.Tail
        // The occurrence only. Making it current is the *second* phase and
        // waits for the board — a current that moves before the write lands
        // says the group is on a climb that never reached the wall, and leaves
        // the previous confirmed one unrecoverable.
        return LightNow(entryId, listOf(BoardPlaylistOp.Add(entryId, climbUuid, angle, anchor = anchor)))
    }

    /**
     * The controller's half of somebody else's light-now.
     *
     * The occurrence may not have arrived yet — the member's add and its
     * projection request are two messages and the mesh does not promise an
     * order. Materialising it here is safe precisely because the member chose
     * the id: its own add merges into this one rather than producing a second
     * entry for the same tap.
     */
    fun completeLightNow(
        state: BoardPlaylistState,
        entryId: String,
        climbUuid: String,
        angle: Int,
        landed: Boolean,
    ): List<BoardPlaylistOp> {
        val ensure = if (state.entry(entryId) != null) emptyList() else listOf(
            BoardPlaylistOp.Add(
                entryId, climbUuid, angle,
                anchor = state.selectedEntryId?.let { BoardPlaylistAnchor.After(it) }
                    ?: BoardPlaylistAnchor.Tail,
            ),
        )
        val settled = BoardPlaylistPolicy.apply(state, ensure)
        return ensure + if (landed) confirmLit(settled, entryId)
        else recordLightFailure(settled, entryId)
    }

    /**
     * Phase two: the write landed, so this occurrence is what the group is on.
     *
     * A failure marker for the same occurrence is taken back here as well. It
     * was the record of an attempt that has now succeeded, and leaving it would
     * tell everybody the wall is dark while the climb is on it. (The projection
     * commit clears it too, for external writes that never went through a
     * light-now; this makes the retry path answer for itself.)
     */
    fun confirmLit(state: BoardPlaylistState, entryId: String): List<BoardPlaylistOp> {
        if (state.entry(entryId) == null) return emptyList()
        val clear = BoardPlaylistOp.SetPendingProjection(null)
            .takeIf { state.pendingProjection?.entryId == entryId }
        val current = BoardPlaylistOp.SetCurrent(entryId)
            .takeIf { state.currentEntryId != entryId }
        // The wall took it, so this is also where the group now is. The cursor
        // follows the confirmed write — never the other way round, which is
        // the whole point of them being two fields.
        val selection = BoardPlaylistOp.SetSelection(entryId)
            .takeIf { state.selectedEntryId != entryId }
        return listOfNotNull(selection, current, clear)
    }

    /**
     * Phase two, the other way: the write did not land, and everybody sees why.
     *
     * The current does not move. It names the occurrence whose transport
     * actually succeeded, and the wall is still showing that one — saying
     * otherwise would be the single lie this whole transaction exists to
     * prevent. The new occurrence stays where it was inserted, directly after
     * the current, carrying the reason it is not on the wall and a retry that
     * uses its own id.
     */
    fun recordLightFailure(state: BoardPlaylistState, entryId: String): List<BoardPlaylistOp> =
        state.entry(entryId)?.let { entry ->
            listOf(
                BoardPlaylistOp.SetPendingProjection(
                    BoardPlaylistPendingProjection(
                        entry.entryId, entry.climbUuid, entry.angle,
                        BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED,
                    ),
                ),
            )
        }.orEmpty()

    /** Record an intention, and optionally that it has landed. */
    fun recordRelayOperation(
        operation: BoardRelayOperation,
        landed: Boolean = operation.landed,
    ): List<BoardPlaylistOp> =
        listOf(BoardPlaylistOp.RecordRelayOperation(operation.copy(landed = landed)))

    fun removeAt(state: BoardPlaylistState, index: Int): List<BoardPlaylistOp> =
        state.entryIdAt(index)?.let { listOf(BoardPlaylistOp.Remove(it)) }.orEmpty()

    fun selectAt(state: BoardPlaylistState, index: Int): List<BoardPlaylistOp> =
        state.entryIdAt(index)?.let { listOf(BoardPlaylistOp.SetSelection(it)) }.orEmpty()

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
        val index = state.selectedIndex
        if (index < 0 || index >= state.entries.lastIndex) return emptyList()
        val leaving = state.entries[index]
        val target = state.entries[index + 1].entryId
        val ops = mutableListOf<BoardPlaylistOp>(BoardPlaylistOp.SetSelection(target))
        if (leaving.restAfterSeconds > 0) {
            ops += BoardPlaylistOp.StartRest(target, leaving.restAfterSeconds)
        }
        return ops
    }

    fun previous(state: BoardPlaylistState): List<BoardPlaylistOp> {
        val index = state.selectedIndex
        if (index <= 0) return emptyList()
        return listOf(BoardPlaylistOp.SetSelection(state.entries[index - 1].entryId))
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

    /**
     * Take back the clear the playlist is still offering, if it is offering
     * one.
     *
     * Composed against the state on screen, so it can only ever name the clear
     * this device is actually looking at — a stale generation reaches a
     * controller that has moved on and does nothing there.
     */
    fun restoreClear(state: BoardPlaylistState): List<BoardPlaylistOp> =
        state.lastClear?.let { listOf(BoardPlaylistOp.RestoreClear(it.generation)) }.orEmpty()
}
