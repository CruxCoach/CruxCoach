package com.cruxcoach.android.data

import com.cruxcoach.android.ble.SessionCommand
import com.cruxcoach.android.boardcell.BoardPlaylistCommandContext
import com.cruxcoach.android.boardcell.BoardPlaylistCommandKind
import com.cruxcoach.android.boardcell.BoardPlaylistItemRef
import com.cruxcoach.android.boardcell.BoardPlaylistState

/**
 * Turns index-based UI commands into semantic operations. Stale commands are
 * accepted only when every item they refer to can still be identified and the
 * relevant ordering precondition is unchanged.
 */
object PlaylistCommandRebaser {
    sealed interface Result {
        data class Apply(val command: SessionCommand) : Result
        data class Conflict(val reason: String) : Result
    }

    fun context(command: SessionCommand, base: BoardPlaylistState): BoardPlaylistCommandContext? {
        fun ref(index: Int) = referenceAt(base.items, index)
        return when (command) {
            is SessionCommand.Add -> BoardPlaylistCommandContext(base.sessionId, BoardPlaylistCommandKind.ADD)
            is SessionCommand.Remove -> BoardPlaylistCommandContext(base.sessionId,
                BoardPlaylistCommandKind.REMOVE, subject = ref(command.index))
            is SessionCommand.SetCurrent -> BoardPlaylistCommandContext(base.sessionId,
                BoardPlaylistCommandKind.SET_CURRENT, subject = ref(command.index))
            SessionCommand.Next -> BoardPlaylistCommandContext(base.sessionId,
                BoardPlaylistCommandKind.NEXT, expectedCurrent = ref(base.currentIndex),
                expectedTarget = ref(base.currentIndex + 1))
            SessionCommand.Prev -> BoardPlaylistCommandContext(base.sessionId,
                BoardPlaylistCommandKind.PREV, expectedCurrent = ref(base.currentIndex),
                expectedTarget = ref(base.currentIndex - 1))
            is SessionCommand.Move -> {
                val subject = ref(command.from) ?: return null
                if (command.to !in base.items.indices) return null
                val without = base.items.toMutableList().apply { removeAt(command.from) }
                val insertion = command.to.coerceIn(0, without.size)
                BoardPlaylistCommandContext(base.sessionId, BoardPlaylistCommandKind.MOVE,
                    subject = subject,
                    before = referenceAt(without, insertion - 1),
                    after = referenceAt(without, insertion))
            }
            is SessionCommand.Join, SessionCommand.Leave -> null
        }
    }

    fun rebase(command: SessionCommand, context: BoardPlaylistCommandContext?,
        current: BoardPlaylistState, exactRevision: Boolean): Result {
        if (context == null) return Result.Conflict("missing semantic command context")
        if (context.sessionId != current.sessionId)
            return Result.Conflict("playlist session changed")
        if (kind(command) != context.kind)
            return Result.Conflict("command context does not match payload")
        if (exactRevision) return validateExact(command, current)

        fun resolve(ref: BoardPlaylistItemRef?) = ref?.let { resolve(current.items, it) }
        return when (command) {
            is SessionCommand.Add -> Result.Apply(command)
            is SessionCommand.Remove -> resolve(context.subject)?.let {
                Result.Apply(SessionCommand.Remove(it))
            } ?: Result.Conflict("item to remove changed or disappeared")
            is SessionCommand.SetCurrent -> resolve(context.subject)?.let {
                Result.Apply(SessionCommand.SetCurrent(it))
            } ?: Result.Conflict("requested current item changed or disappeared")
            SessionCommand.Next, SessionCommand.Prev -> {
                val from = resolve(context.expectedCurrent)
                    ?: return Result.Conflict("current climb changed")
                val target = resolve(context.expectedTarget)
                    ?: return Result.Conflict("next climb changed or disappeared")
                val delta = if (command == SessionCommand.Next) 1 else -1
                if (current.currentIndex != from || target != from + delta)
                    Result.Conflict("playlist advanced or relevant order changed")
                else Result.Apply(if (delta == 1) SessionCommand.Next else SessionCommand.Prev)
            }
            is SessionCommand.Move -> {
                val from = resolve(context.subject)
                    ?: return Result.Conflict("item to move changed or disappeared")
                val without = current.items.toMutableList().apply { removeAt(from) }
                val before = resolveIn(without, context.before)
                val after = resolveIn(without, context.after)
                val insertion = when {
                    context.before == null && context.after == null && without.isEmpty() -> 0
                    context.before == null && after == 0 -> 0
                    context.after == null && before == without.lastIndex -> without.size
                    before != null && after == before + 1 -> after
                    else -> return Result.Conflict("move destination changed")
                }
                Result.Apply(SessionCommand.Move(from, insertion.coerceAtMost(current.items.lastIndex)))
            }
            is SessionCommand.Join, SessionCommand.Leave -> Result.Conflict("not a playlist mutation")
        }
    }

    private fun validateExact(command: SessionCommand, current: BoardPlaylistState): Result = when (command) {
        is SessionCommand.Add -> Result.Apply(command)
        is SessionCommand.Remove -> if (command.index in current.items.indices) Result.Apply(command)
            else Result.Conflict("remove index is no longer valid")
        is SessionCommand.SetCurrent -> if (command.index in current.items.indices) Result.Apply(command)
            else Result.Conflict("current index is no longer valid")
        SessionCommand.Next -> if (current.currentIndex in 0 until current.items.lastIndex) Result.Apply(command)
            else Result.Conflict("there is no next climb")
        SessionCommand.Prev -> if (current.currentIndex > 0) Result.Apply(command)
            else Result.Conflict("there is no previous climb")
        is SessionCommand.Move -> if (command.from in current.items.indices &&
            command.to in current.items.indices && command.from != command.to) Result.Apply(command)
            else Result.Conflict("move indices are no longer valid")
        is SessionCommand.Join, SessionCommand.Leave -> Result.Conflict("not a playlist mutation")
    }

    private fun kind(command: SessionCommand): BoardPlaylistCommandKind? = when (command) {
        is SessionCommand.Add -> BoardPlaylistCommandKind.ADD
        is SessionCommand.Remove -> BoardPlaylistCommandKind.REMOVE
        is SessionCommand.SetCurrent -> BoardPlaylistCommandKind.SET_CURRENT
        SessionCommand.Next -> BoardPlaylistCommandKind.NEXT
        SessionCommand.Prev -> BoardPlaylistCommandKind.PREV
        is SessionCommand.Move -> BoardPlaylistCommandKind.MOVE
        is SessionCommand.Join, SessionCommand.Leave -> null
    }

    private fun referenceAt(items: List<Pair<String, Int>>, index: Int): BoardPlaylistItemRef? {
        val item = items.getOrNull(index) ?: return null
        val matching = items.indices.filter { same(items[it], item) }
        return BoardPlaylistItemRef(item.first, item.second, matching.indexOf(index), matching.size)
    }

    private fun resolve(items: List<Pair<String, Int>>, ref: BoardPlaylistItemRef): Int? {
        val matching = items.indices.filter { same(items[it], ref.climbUuid to ref.angle) }
        // Duplicate additions/removals make occurrence-based identity ambiguous.
        if (matching.size != ref.totalAtBase) return null
        return matching.getOrNull(ref.occurrence)
    }

    private fun resolveIn(items: List<Pair<String, Int>>, ref: BoardPlaylistItemRef?): Int? =
        ref?.let { resolve(items, it) }

    private fun same(a: Pair<String, Int>, b: Pair<String, Int>): Boolean =
        a.second == b.second && a.first.replace("-", "").equals(b.first.replace("-", ""), ignoreCase = true)
}
