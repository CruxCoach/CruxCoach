package com.cruxcoach.android.spraywall

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which walls are being withdrawn, and which of them are deleted afterwards: the screens name both steps for those. */
class SprayRunningWithdrawalsTest {
    private val running = SprayRunningWithdrawals()

    @Test
    fun `a withdrawal that deletes the wall shows as deleting from its start to its end`() {
        running.started("a", delete = true)
        assertEquals(mapOf("a" to true), running.walls.value)

        running.ended("a", delete = true)
        assertEquals(emptyMap(), running.walls.value)
    }

    @Test
    fun `a withdrawal that only makes the wall private does not show as deleting`() {
        running.started("a", delete = false)
        running.started("b", delete = true)
        assertEquals(mapOf("a" to false, "b" to true), running.walls.value)

        running.ended("b", delete = true)
        assertEquals(mapOf("a" to false), running.walls.value)
    }

    @Test
    fun `a wall stays deleting while one of its withdrawals deletes it, and withdrawing while one runs`() {
        running.started("a", delete = false)
        running.started("a", delete = true)
        assertEquals(mapOf("a" to true), running.walls.value)

        running.ended("a", delete = true)
        assertEquals(mapOf("a" to false), running.walls.value)
        running.ended("a", delete = false)
        assertEquals(emptyMap(), running.walls.value)
    }
}
