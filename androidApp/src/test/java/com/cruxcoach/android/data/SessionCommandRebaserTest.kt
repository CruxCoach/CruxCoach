package com.cruxcoach.android.data

import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.ble.SessionCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCommandRebaserTest {
    private fun item(id: String, angle: Int = 40) = QueueItem(id.padEnd(32, '0'), angle)

    @Test
    fun `concurrent append does not invalidate independent remove`() {
        val base = listOf(item("a"), item("b"))
        val command = SessionCommand.Remove(0)
        val context = SessionCommandRebaser.context(command, 7, 0, base)!!

        val result = SessionCommandRebaser.rebase(command, context, 7, 0, base + item("c"))

        assertEquals(SessionCommandRebaser.Result.Apply(SessionCommand.Remove(0)), result)
    }

    @Test
    fun `remove follows its item after an independent insertion`() {
        val base = listOf(item("a"), item("b"))
        val command = SessionCommand.Remove(1)
        val context = SessionCommandRebaser.context(command, 7, 0, base)!!

        val result = SessionCommandRebaser.rebase(
            command, context, 7, 0, listOf(item("c")) + base,
        )

        assertEquals(SessionCommandRebaser.Result.Apply(SessionCommand.Remove(2)), result)
    }

    @Test
    fun `move follows stable destination anchors`() {
        val base = listOf(item("a"), item("b"), item("c"))
        val command = SessionCommand.Move(2, 1)
        val context = SessionCommandRebaser.context(command, 7, 0, base)!!

        val result = SessionCommandRebaser.rebase(
            command, context, 7, 0, listOf(item("x")) + base,
        )

        assertEquals(SessionCommandRebaser.Result.Apply(SessionCommand.Move(3, 2)), result)
    }

    @Test
    fun `next conflicts after another participant advanced`() {
        val base = listOf(item("a"), item("b"), item("c"))
        val context = SessionCommandRebaser.context(SessionCommand.Next, 7, 0, base)!!

        val result = SessionCommandRebaser.rebase(SessionCommand.Next, context, 7, 1, base)

        assertTrue(result is SessionCommandRebaser.Result.Conflict)
    }

    @Test
    fun `duplicate count change is rejected as ambiguous`() {
        val duplicate = item("a")
        val base = listOf(duplicate, duplicate, item("b"))
        val command = SessionCommand.Remove(1)
        val context = SessionCommandRebaser.context(command, 7, 0, base)!!

        val result = SessionCommandRebaser.rebase(
            command, context, 7, 0, listOf(duplicate, item("b")),
        )

        assertTrue(result is SessionCommandRebaser.Result.Conflict)
    }
}
