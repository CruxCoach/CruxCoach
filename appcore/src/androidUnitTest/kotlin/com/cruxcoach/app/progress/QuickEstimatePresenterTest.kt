package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.QuickEstimateScreenModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Side
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The quick estimate on iOS runs Android's QuickEstimateViewModel (ported) against a real database. */
class QuickEstimatePresenterTest {
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

    private fun sheet(): Pair<QuickEstimatePresenter, QuickEstimateScreenModel> {
        val p = QuickEstimatePresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        return p to QuickEstimateScreenModel(p)
    }

    @Test
    fun `two answers become estimated values`() {
        val (p, model) = sheet()
        val s = model.currentState
        assertFalse(s.needsWeight)
        assertTrue(s.hangAllowed)
        // The standard athlete has no pick-up block.
        assertFalse(s.showPickup)
        (s.pullChoices + s.hangChoices).forEach { TrTextCheck.check(it.label, "choice ${it.code}") }
        assertTrue(TrTextCheck.checkAll(s) > 0)
        assertFalse(model.canSave("NONE", "CANNOT", "", ""))
        assertFalse(model.save("NONE", "", "", "CANNOT", "", "", ""))
        val hint = model.pullHint("SOME", "", "")
        assertEquals("trc_estimate_pct", hint.args.first().nested!!.key)
        TrTextCheck.check(hint, "pull hint")
        TrTextCheck.check(model.hangHint("EASY", ""), "hang hint")
        assertTrue(model.save("SOME", "", "", "EASY", "", "", ""))
        kit.until("saved") { p.state.value.saved }
        val all = kit.db { repo.allBenchmarks() }
        assertTrue(all.all { it.source == BenchmarkSource.ESTIMATE })
        assertEquals(5, all.single { it.exerciseSlug == "pull.pull_up" }.reps)
        val hang = all.single { it.exerciseSlug == "finger.max_hang" }
        // "Easily" adds a tenth of body weight: 7 kg at 70 kg.
        assertEquals(7.0, hang.loadKg!!, 1e-9)
        assertEquals(10.0, hang.durationS)
        assertEquals(20.0, hang.edgeMm)
        assertTrue(model.currentState.saved)
    }

    @Test
    fun `weighted pull-ups and pick-ups per hand respect a one-sided injury`() {
        kit.db {
            repo.updateProfile { it.copy(equipment = it.equipment + EquipmentV2.PICKUP_BLOCK) }
            repo.saveInjury(Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 3, climbingPaused = false,
                startedOn = service.today().toString()))
        }
        val (p, model) = sheet()
        val s = model.currentState
        assertTrue(s.showPickup)
        assertEquals("right", s.pickupSide)
        assertTrue(model.save("WEIGHTED", "15", "5", "", "", "30", "32"))
        kit.until("saved") { p.state.value.saved }
        val all = kit.db { repo.allBenchmarks() }
        val pull = all.single { it.exerciseSlug == "pull.weighted_pull_up" }
        assertEquals(15.0, pull.loadKg)
        assertEquals(5, pull.reps)
        // Only the healthy hand.
        assertEquals(listOf(Side.RIGHT), all.filter { it.exerciseSlug == "finger.one_arm_pickup" }.map { it.side })
    }

    @Test
    fun `without a body weight the sheet asks for it`() {
        kit.db { repo.deleteMeasurement(service.today().toString(), "weight") }
        val (p, model) = sheet()
        assertTrue(model.currentState.needsWeight)
        assertTrue(model.pullHint("SOME", "", "").isEmpty)
        assertFalse(model.logWeight(500.0))
        assertTrue(model.logWeight(64.0))
        kit.until("weight") { p.state.value.bodyweight == 64.0 }
        assertFalse(model.currentState.needsWeight)
    }
}
