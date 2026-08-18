package com.cruxcoach.android.data

import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.ble.SessionCommand
import com.cruxcoach.android.ble.SessionCommandContext
import com.cruxcoach.android.ble.SessionItemRef

/**
 * Converts fragile queue indices into semantic item references. This lets the
 * host accept independent concurrent edits while rejecting operations whose
 * meaning changed before they arrived.
 */
object SessionCommandRebaser {
    sealed interface Result {
        data class Apply(val command: SessionCommand) : Result
        data class Conflict(val reason: String) : Result
    }

    fun context(
        command: SessionCommand,
        sessionId: Int,
        currentIndex: Int,
        items: List<QueueItem>,
    ): SessionCommandContext? {
        fun ref(index: Int) = referenceAt(items, index)
        return when (command) {
            is SessionCommand.Add, SessionCommand.Resend -> SessionCommandContext(sessionId)
            is SessionCommand.Remove -> SessionCommandContext(sessionId, subject = ref(command.index))
            is SessionCommand.SetCurrent -> SessionCommandContext(sessionId, subject = ref(command.index))
            SessionCommand.Next -> SessionCommandContext(
                sessionId,
                expectedCurrent = ref(currentIndex),
                expectedTarget = ref(currentIndex + 1),
            )
            SessionCommand.Prev -> SessionCommandContext(
                sessionId,
                expectedCurrent = ref(currentIndex),
                expectedTarget = ref(currentIndex - 1),
            )
            is SessionCommand.Move -> {
                val subject = ref(command.from) ?: return null
                if (command.to !in items.indices) return null
                val without = items.toMutableList().apply { removeAt(command.from) }
                val insertion = command.to.coerceIn(0, without.size)
                SessionCommandContext(
                    sessionId,
                    subject = subject,
                    before = referenceAt(without, insertion - 1),
                    after = referenceAt(without, insertion),
                )
            }
            is SessionCommand.Join, SessionCommand.Leave -> null
        }
    }

    fun rebase(
        command: SessionCommand,
        context: SessionCommandContext?,
        sessionId: Int,
        currentIndex: Int,
        items: List<QueueItem>,
    ): Result {
        if (context == null) return Result.Conflict("missing semantic command context")
        if (context.sessionId != sessionId) return Result.Conflict("playlist session changed")
        fun resolve(ref: SessionItemRef?) = ref?.let { resolve(items, it) }

        return when (command) {
            is SessionCommand.Add, SessionCommand.Resend -> Result.Apply(command)
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
                    ?: return Result.Conflict("target climb changed or disappeared")
                val delta = if (command == SessionCommand.Next) 1 else -1
                if (currentIndex != from || target != from + delta) {
                    Result.Conflict("playlist advanced or relevant order changed")
                } else {
                    Result.Apply(if (delta == 1) SessionCommand.Next else SessionCommand.Prev)
                }
            }
            is SessionCommand.Move -> {
                val from = resolve(context.subject)
                    ?: return Result.Conflict("item to move changed or disappeared")
                val without = items.toMutableList().apply { removeAt(from) }
                val before = resolveIn(without, context.before)
                val after = resolveIn(without, context.after)
                val insertion = when {
                    context.before == null && context.after == null && without.isEmpty() -> 0
                    context.before == null && after == 0 -> 0
                    context.after == null && before == without.lastIndex -> without.size
                    before != null && after == before + 1 -> after
                    else -> return Result.Conflict("move destination changed")
                }
                Result.Apply(SessionCommand.Move(from, insertion.coerceAtMost(items.lastIndex)))
            }
            is SessionCommand.Join, SessionCommand.Leave ->
                Result.Conflict("not a playlist mutation")
        }
    }

    private fun referenceAt(items: List<QueueItem>, index: Int): SessionItemRef? {
        val item = items.getOrNull(index) ?: return null
        val matching = items.indices.filter { same(items[it], item) }
        return SessionItemRef(item.climbUuid, item.angle, matching.indexOf(index), matching.size)
    }

    private fun resolve(items: List<QueueItem>, ref: SessionItemRef): Int? {
        val matching = items.indices.filter {
            same(items[it], QueueItem(ref.climbUuid, ref.angle))
        }
        // When identical entries were concurrently added/removed, occurrence
        // numbers no longer identify the user's intended entry safely.
        if (matching.size != ref.totalAtBase) return null
        return matching.getOrNull(ref.occurrence)
    }

    private fun resolveIn(items: List<QueueItem>, ref: SessionItemRef?): Int? =
        ref?.let { resolve(items, it) }

    private fun same(a: QueueItem, b: QueueItem): Boolean =
        a.angle == b.angle &&
            a.climbUuid.replace("-", "").equals(b.climbUuid.replace("-", ""), ignoreCase = true)
}
