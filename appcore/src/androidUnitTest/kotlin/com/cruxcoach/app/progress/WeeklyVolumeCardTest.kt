package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.WeeklyVolumeCardModel
import com.cruxcoach.app.ui.WeeklyVolumeCardState
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.PauseReason
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The weekly targets card of the Progress tab on iOS runs Android's WeeklyVolumeViewModel logic
 * (ported presenter) against a real database and the real exercise catalogue: the rows and their
 * bars, the own target per area (start value, range, switch off, back to the recommendation) and
 * the pause, as Android's WeeklyVolumeCard / OwnVolumeTargetDialog show them.
 */
class WeeklyVolumeCardTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun card(): Pair<WeeklyVolumePresenter, WeeklyVolumeCardModel> {
        val p = WeeklyVolumePresenter(service, kit.scope(), kit.dispatcher)
        kit.until("weekly volume loaded") { !p.state.value.loading }
        return p to WeeklyVolumeCardModel(p)
    }

    private fun checkTexts(s: WeeklyVolumeCardState) {
        s.rows.forEach { r ->
            listOf("label" to r.label, "value" to r.value, "status" to r.status, "chip" to r.recommendedChip,
                "unit" to r.unit, "field" to r.fieldLabel).forEach { (where, t) -> TrTextCheck.check(t, "${r.areaCode} $where") }
        }
    }

    @Test
    fun `an empty week lists the areas behind with their ranges as markers`() {
        val (p, model) = card()
        val s = model.currentState
        assertFalse(s.loading)
        assertFalse(s.paused)
        assertFalse(s.empty)
        val expected = p.state.value.progress.filter { !it.target.isOff }.map { it.area.name.lowercase() }
        assertEquals(expected, s.rows.map { it.areaCode })
        val pull = s.rows.first { it.areaCode == "pull" }
        val target = p.state.value.progress.first { it.area == VolumeArea.PULL }.target
        assertEquals("below", pull.statusCode)
        assertEquals("trv_status_below", pull.status.key)
        assertEquals(0.0, pull.loggedFraction)
        assertEquals(0.0, pull.creditFraction)
        // The range's ends as markers; the scale is the upper end, so it sits at the bar's end.
        assertTrue(target.maxSets > 0)
        assertEquals(1.0, pull.markerFractions.last(), 1e-9)
        assertEquals(if (target.minSets == target.maxSets) "trv_value_sets_exact" else "trv_value_sets", pull.value.key)
        assertEquals("trv_recommended_sets", pull.recommendedChip.key)
        assertFalse(pull.isOwn)
        assertEquals("", pull.ownText)
        s.rows.firstOrNull { it.areaCode == "finger" }?.let { finger ->
            assertEquals("trv_value_sessions", finger.value.key)
            assertEquals("trv_recommended_sessions", finger.recommendedChip.key)
            assertEquals(1, finger.markerFractions.size)
        }
        assertFalse(s.showLegend)
        checkTexts(s)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `an own target starts from the recommendation and can switch an area off`() {
        val (p, model) = card()
        val recommended = p.state.value.progress.first { it.area == VolumeArea.PULL }.target.maxSets
        model.chooseOwn("pull")
        kit.until("own pull target") { p.state.value.own["PULL"] != null }
        assertEquals(recommended, p.state.value.own["PULL"])
        assertEquals(recommended, kit.db { repo.profile().ownWeeklyTargets["PULL"] })
        var pull = model.currentState.rows.first { it.areaCode == "pull" }
        assertTrue(pull.isOwn)
        assertEquals(recommended.toString(), pull.ownText)
        // An own number is the week's exact target; the recommendation stays in the chip.
        assertEquals("trv_value_sets_exact", pull.value.key)
        assertEquals("trv_recommended_sets", pull.recommendedChip.key)

        assertTrue(model.setOwn("pull", "12"))
        kit.until("own 12") { p.state.value.own["PULL"] == 12 }
        assertEquals(12, p.state.value.progress.first { it.area == VolumeArea.PULL }.target.minSets)

        // 0 switches the area off; an area switched off by the athlete stays listed to switch it on again.
        assertTrue(model.setOwn("pull", "0"))
        kit.until("own 0") { p.state.value.own["PULL"] == 0 }
        assertTrue(p.state.value.progress.first { it.area == VolumeArea.PULL }.target.isOff)
        pull = model.currentState.rows.first { it.areaCode == "pull" }
        assertEquals("0", pull.ownText)

        model.followRecommendation("pull")
        kit.until("recommendation again") { p.state.value.own["PULL"] == null }
        assertFalse(model.currentState.rows.first { it.areaCode == "pull" }.isOwn)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `own values outside Android's ranges are refused`() {
        val (p, model) = card()
        assertTrue(model.isOwnValid("finger", "5"))
        assertFalse(model.isOwnValid("finger", "6"))
        assertTrue(model.isOwnValid("pull", "60"))
        assertFalse(model.isOwnValid("pull", "61"))
        assertFalse(model.isOwnValid("pull", "-1"))
        assertFalse(model.isOwnValid("pull", "abc"))
        assertFalse(model.isOwnValid("nonsense", "3"))
        assertFalse(model.setOwn("pull", "61"))
        assertFalse(model.setOwn("finger", "9"))
        assertTrue(kit.db { repo.profile().ownWeeklyTargets.isEmpty() })
        assertTrue(p.state.value.own.isEmpty())
    }

    @Test
    fun `logged pull sets fill the bar after a refresh`() {
        val (p, model) = card()
        kit.db {
            val id = service.startWorkout(Routine("r", "Pull", items = listOf(
                RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5, loadKg = 10.0))), null)
            repo.setsFor(id).filter { it.setType == SetType.WORK }.forEach { service.completeSet(it.copy(reps = 5), startRest = false) }
            service.finishWorkout(id, 6, null)
        }
        model.refresh()
        kit.until("three pull sets") { p.state.value.progress.first { it.area == VolumeArea.PULL }.doneSets == 3 }
        val pull = model.currentState.rows.first { it.areaCode == "pull" }
        assertTrue(pull.loggedFraction > 0.0, "${pull.loggedFraction}")
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `an open pause rests the targets`() {
        kit.db { repo.savePause(PausePeriod("p1", PauseReason.entries.first(), service.today().toString())) }
        val (_, model) = card()
        kit.until("paused") { model.currentState.paused }
        assertTrue(model.currentState.paused)
    }
}
