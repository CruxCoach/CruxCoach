package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.logic.PlayerQueue.Page
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals

/** The training view's pages: one per exercise, one per circuit. */
class PlayerPagesTest {

    private fun set(id: String, block: Int, index: Int, circuit: Int? = null, type: SetType = SetType.WORK, side: Side? = null) =
        ExerciseSet(id, "w", "pull.pull_up", block, index, type, side, restS = 15, circuit = circuit, roundRestS = circuit?.let { 120 })

    @Test
    fun onePagePerExercise() {
        val sets = listOf(set("C0", 2, 0), set("A0", 0, 0), set("A1", 0, 1), set("B0", 1, 0), set("Bw", 1, -1, type = SetType.WARMUP))
        assertEquals(listOf(Page(listOf(0), null), Page(listOf(1), null), Page(listOf(2), null)), PlayerQueue.pages(sets))
        assertEquals(1, PlayerQueue.pageOf(sets, sets.first { it.id == "Bw" }))
        assertEquals(2, PlayerQueue.pageOf(sets, sets.first { it.id == "C0" }))
        assertEquals(-1, PlayerQueue.pageOf(sets, set("Z0", 9, 0)))
        assertEquals(emptyList(), PlayerQueue.pages(emptyList()))
    }

    @Test
    fun aCircuitWithItsWarmUpsIsOnePage() {
        val sets = listOf(
            set("F0", 0, 0),
            set("Aw", 1, -1, circuit = 1, type = SetType.WARMUP), set("A0", 1, 0, circuit = 1), set("A1", 1, 1, circuit = 1),
            // A warm-up row without the key still belongs to its circuit's page.
            set("Bw", 2, -1, type = SetType.WARMUP), set("B0", 2, 0, circuit = 1), set("B1", 2, 1, circuit = 1),
            set("M0", 3, 0),
        )
        assertEquals(listOf(Page(listOf(0), null), Page(listOf(1, 2), 1), Page(listOf(3), null)), PlayerQueue.pages(sets.shuffled()))
        listOf("Aw", "A1", "Bw", "B0").forEach { assertEquals(1, PlayerQueue.pageOf(sets, sets.first { s -> s.id == it }), it) }
        assertEquals(2, PlayerQueue.pageOf(sets, sets.first { it.id == "M0" }))
    }

    @Test
    fun bothHandsOfAnExerciseAreOnePage() {
        val sets = (0..1).flatMap { i -> listOf(set("L$i", 0, i, side = Side.LEFT), set("R$i", 0, i, side = Side.RIGHT)) } + set("X0", 1, 0)
        assertEquals(listOf(Page(listOf(0), null), Page(listOf(1), null)), PlayerQueue.pages(sets))
        assertEquals(0, PlayerQueue.pageOf(sets, sets.first { it.id == "R1" }))
    }

    @Test
    fun anExerciseInsertedIntoACircuitJoinsItsPage() {
        // "It hurts" in block 1 of a circuit: the alternative comes in as block 2, the last member moves to block 3.
        val sets = listOf(
            set("S0", 0, 0),
            set("A0", 1, 0, circuit = 1).copy(completedAt = 1), set("Alt1", 2, 1, circuit = 1),
            set("B0", 3, 0, circuit = 1).copy(completedAt = 2), set("B1", 3, 1, circuit = 1),
            set("T0", 4, 0),
        )
        assertEquals(listOf(Page(listOf(0), null), Page(listOf(1, 2, 3), 1), Page(listOf(4), null)), PlayerQueue.pages(sets))
        assertEquals(1, PlayerQueue.pageOf(sets, sets.first { it.id == "Alt1" }))
        // Two circuits in a row stay two pages, in the order they are played.
        val two = listOf(set("P0", 0, 0, circuit = 2), set("Q0", 1, 0, circuit = 2), set("R0", 2, 0, circuit = 1), set("U0", 3, 0, circuit = 1))
        assertEquals(listOf(Page(listOf(0, 1), 2), Page(listOf(2, 3), 1)), PlayerQueue.pages(two))
    }
}
