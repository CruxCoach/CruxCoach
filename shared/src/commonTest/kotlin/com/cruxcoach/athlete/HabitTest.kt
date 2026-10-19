package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.PreferenceLearning.HabitKind
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Always the same training, only the progression" (owner 2026-10-19): the coach notices the usual workout. */
class HabitTest {
    private val monday = LocalDate.parse("2026-10-19")
    private val routines = setOf("push", "pull", "builtin:finger_basics")
    private val isRoutine: (String) -> Boolean = { it in routines }
    private var n = 0L

    private fun w(daysAgo: Int, routineId: String?, open: Boolean = false): Workout {
        val day = monday.minus(DatePeriod(days = daysAgo))
        val start = 1_790_000_000_000L - daysAgo * 86_400_000L + (n++)
        return Workout("w$n", start, if (open) null else start + 3_600_000L, day.toString(), routineId = routineId, updatedAt = start)
    }

    @Test
    fun `two mondays with the same workout make it monday's usual one`() {
        val h = PreferenceLearning.habit(listOf(w(7, "push"), w(14, "push"), w(3, "pull")), monday, isRoutine)
        assertEquals("push", h?.entry)
        assertEquals(HabitKind.WEEKDAY, h?.kind)
        assertEquals(1, h?.isoDay)
        assertEquals(2, h?.times)
    }

    @Test
    fun `once is no habit, and the last monday must have been that workout`() {
        assertNull(PreferenceLearning.habit(listOf(w(7, "push"), w(3, "pull")), monday, isRoutine))
        assertNull(PreferenceLearning.habit(listOf(w(7, "suggestion:2026-10-12"), w(14, "push"), w(21, "push")), monday, isRoutine),
            "last Monday the athlete followed the coach")
        // Too long ago, open, deleted or not a routine.
        assertNull(PreferenceLearning.habit(listOf(w(49, "push"), w(56, "push")), monday, isRoutine))
        assertNull(PreferenceLearning.habit(listOf(w(7, "push", open = true), w(14, "push", open = true)), monday, isRoutine))
        assertNull(PreferenceLearning.habit(listOf(w(7, "gone"), w(14, "gone")), monday, isRoutine))
    }

    @Test
    fun `three of the last four trainings make it the usual one on any day`() {
        val list = listOf(w(1, "pull"), w(2, "pull"), w(4, "push"), w(5, "pull"), w(8, "push"))
        val h = PreferenceLearning.habit(list, monday, isRoutine)
        assertEquals(PreferenceLearning.Habit("pull", HabitKind.USUAL, null, 3), h)
    }

    @Test
    fun `a usual workout started from today's card still counts`() {
        val h = PreferenceLearning.habit(listOf(w(7, "suggestion:2026-10-12@push"), w(14, "push")), monday, isRoutine)
        assertEquals("push", h?.entry)
        assertEquals(PreferenceLearning.routineOf(w(1, "suggestion:2026-10-18@builtin:finger_basics"), isRoutine), "builtin:finger_basics")
    }

    @Test
    fun `forgetting the habit, the weekday or everything starts afresh`() {
        val list = listOf(w(7, "push"), w(14, "push"))
        val after = list.maxOf { it.startedAt } + 1
        assertNull(PreferenceLearning.habit(list, monday, isRoutine, mapOf(PreferenceLearning.habitKey("push") to after)))
        assertNull(PreferenceLearning.habit(list, monday, isRoutine, mapOf(PreferenceLearning.weekdayKey(1) to after)))
        assertNull(PreferenceLearning.habit(list, monday, isRoutine, mapOf(PreferenceLearning.RESET_ALL to after)))
        // Learned again from trainings after the reset.
        val again = list + w(0, "push").copy(day = "2026-10-12", startedAt = after + 10) + w(0, "push").copy(day = "2026-10-05", startedAt = after + 20)
        assertEquals("push", PreferenceLearning.habit(again, monday, isRoutine, mapOf(PreferenceLearning.habitKey("push") to after))?.entry)
    }

    @Test
    fun `the learned page sees every weekday's habit`() {
        val list = listOf(w(7, "push"), w(14, "push"), w(4, "pull"), w(11, "pull"))
        val all = PreferenceLearning.habits(list, monday, isRoutine)
        assertEquals(listOf(1 to "push", 4 to "pull"), all.filter { it.kind == HabitKind.WEEKDAY }.map { it.isoDay to it.entry })
    }
}
