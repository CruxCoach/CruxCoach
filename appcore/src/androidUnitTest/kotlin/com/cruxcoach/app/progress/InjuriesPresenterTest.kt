package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.InjuriesScreenModel
import com.cruxcoach.app.ui.InjuriesScreenState
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PauseReason
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Injuries and pauses on iOS run Android's InjuriesViewModel (ported) against a real database. */
class InjuriesPresenterTest {
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

    private fun screen(): Pair<InjuriesPresenter, InjuriesScreenModel> {
        val p = InjuriesPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        return p to InjuriesScreenModel(p)
    }

    private fun checkTexts(s: InjuriesScreenState) {
        (s.active + s.past).forEach { TrTextCheck.check(it.title, "title"); TrTextCheck.check(it.since, "since") }
        (s.pauses + listOfNotNull(s.openPause)).forEach { TrTextCheck.check(it.label, "pause"); TrTextCheck.check(it.text, "pause text") }
        s.regions.forEach { TrTextCheck.check(it.label, "region") }
        s.sides.forEach { TrTextCheck.check(it.label, "side") }
    }

    @Test
    fun `an injury with a streak pause, then healed, ends the pause`() {
        val (p, model) = screen()
        assertTrue(model.add("FINGER", "LEFT", "  ring finger A2  ", 4, pauseClimbing = true, pauseStreak = true))
        kit.until("injury") { p.state.value.active.size == 1 }
        kit.until("pause") { p.state.value.pauses.any { it.endDay == null } }
        val injury = kit.db { repo.activeInjuries().single() }
        assertEquals(InjuryRegion.FINGER, injury.region)
        assertEquals(InjurySide.LEFT, injury.side)
        assertEquals("ring finger A2", injury.detail)
        assertEquals(4, injury.severity)
        assertTrue(injury.climbingPaused)
        assertEquals(PauseReason.INJURY, kit.db { repo.openPause()!!.reason })
        val s = model.currentState
        assertTrue(s.hasOpenPause)
        assertEquals("tra_pause_open", s.openPause!!.text.key)
        assertEquals("ring finger A2", s.active.single().detail)
        checkTexts(s)

        model.setSeverity(injury.id, 12)
        kit.until("pain") { repo.activeInjuries().single().severity == 9 }
        model.setClimbingPaused(injury.id, false)
        kit.until("climbing") { !repo.activeInjuries().single().climbingPaused }

        model.resolve(injury.id)
        kit.until("healed") { repo.activeInjuries().isEmpty() }
        kit.until("pause ended") { repo.openPause() == null }
        kit.until("past") { p.state.value.past.size == 1 }
        val after = model.currentState
        assertFalse(after.hasOpenPause)
        assertNull(after.openPause)
        assertEquals(1, after.pauses.size)
        assertEquals(1, after.past.size)
        checkTexts(after)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `healing one of two injuries keeps the injury pause`() {
        val (p, model) = screen()
        model.add("FINGER", "", "", 3, pauseClimbing = true, pauseStreak = true)
        model.add("SHOULDER", "RIGHT", "", 2, pauseClimbing = true, pauseStreak = true)
        kit.until("two") { p.state.value.active.size == 2 }
        // One pause only, however many injuries.
        kit.until("one pause") { p.state.value.pauses.size == 1 }
        assertEquals(1, p.state.value.pauses.size)
        model.resolve(p.state.value.active.first().id)
        kit.until("one left") { p.state.value.active.size == 1 }
        assertTrue(kit.db { repo.openPause() } != null)
    }

    @Test
    fun `unknown codes are refused and the detail is bounded`() {
        val (p, model) = screen()
        assertFalse(model.add("NOSE", "", "", 3, true, false))
        assertFalse(model.add("FINGER", "UP", "", 3, true, false))
        assertTrue(model.add("KNEE", "", "x".repeat(500), -3, false, false))
        kit.until("knee") { p.state.value.active.size == 1 }
        val knee = kit.db { repo.activeInjuries().single() }
        assertEquals(120, knee.detail!!.length)
        assertEquals(0, knee.severity)
        assertFalse(knee.climbingPaused)
        // No streak pause asked for.
        assertNull(kit.db { repo.openPause() })
        model.delete(knee.id)
        kit.until("deleted") { repo.allInjuries().isEmpty() }
    }

    @Test
    fun `a holiday pause starts once and ends and can be deleted`() {
        val (p, model) = screen()
        model.startHoliday()
        kit.until("holiday") { repo.openPause()?.reason == PauseReason.HOLIDAY }
        model.startHoliday()
        kit.until("still one") { p.state.value.pauses.size == 1 }
        model.endPause()
        kit.until("ended") { repo.openPause() == null }
        kit.until("listed") { p.state.value.pauses.singleOrNull()?.endDay != null }
        val ended = model.currentState.pauses.single()
        assertEquals("tra_pause_holiday_label", ended.label.key)
        checkTexts(model.currentState)
        model.deletePause(ended.id)
        kit.until("deleted") { p.state.value.pauses.isEmpty() }
    }

    @Test
    fun `the region list says which regions pause climbing`() {
        val (_, model) = screen()
        val regions = model.currentState.regions
        assertEquals(InjuryRegion.entries.map { it.name }, regions.map { it.code })
        assertEquals(setOf("FINGER", "WRIST", "ELBOW", "SHOULDER", "SKIN"), regions.filter { it.pausesClimbing }.map { it.code }.toSet())
        assertEquals(listOf("LEFT", "RIGHT", "BOTH"), model.currentState.sides.map { it.code })
    }
}
