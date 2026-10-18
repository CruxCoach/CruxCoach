package com.cruxcoach.app.energycare

import com.cruxcoach.app.nutrition.text
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.EnergyCareCardModel
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stand-alone energy card (Android `EnergyCareCard`): the same reasons as on the nutrition page. */
class EnergyCareCardTest {
    private lateinit var kit: TrainingKit

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.db { kit.service.ensureReady() }
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun card(): Pair<EnergyCarePresenter, EnergyCareCardModel> {
        val p = EnergyCarePresenter(kit.service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        return p to EnergyCareCardModel(p)
    }

    private fun body(weightKg: Double, heightCm: Double) = kit.db {
        val today = kit.service.today().toString()
        kit.repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, weightKg, "kg", 1))
        kit.repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, heightCm, "cm", 1))
    }

    @Test
    fun `without a reason the card stays hidden`() {
        body(70.0, 178.0)
        val (_, model) = card()
        val s = model.currentState
        assertFalse(s.loading)
        assertFalse(s.card.visible)
        assertTrue(s.card.warnings.isEmpty())
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `a low BMI names its numbers and hides them in hide-numbers mode`() {
        body(55.0, 180.0)
        val (p, model) = card()
        val s = model.currentState
        assertTrue(s.card.visible)
        val w = s.card.warnings.single()
        assertEquals("lowBmi", w.code)
        assertEquals("trn_w_low_bmi", w.text.key)
        assertTrue(w.text.text.contains("17"), w.text.text)
        assertTrue(w.text.text.contains("18.5"), w.text.text)
        assertFalse(s.card.canAdjustGoal)
        // The card follows a changed profile without a reload.
        kit.db { kit.repo.updateProfile { it.copy(hideBodyNumbers = true) } }
        kit.until("hidden numbers") { p.state.value.profile.hideBodyNumbers }
        val hidden = model.currentState.card.warnings.single()
        assertEquals("trt_reds_low_bmi", hidden.text.key)
        assertFalse(hidden.text.text.contains("17"))
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `under 18 the youth note comes first`() {
        body(55.0, 180.0)
        kit.db { kit.repo.updateProfile { it.copy(birthYear = kit.service.today().year - 16) } }
        val (_, model) = card()
        val codes = model.currentState.card.warnings.map { it.code }
        assertEquals(listOf("youth", "lowBmi"), codes)
        assertEquals("trn_w_youth", model.currentState.card.warnings.first().text.key)
        // Without a running weight-loss goal there is nothing to adjust.
        assertFalse(model.currentState.card.canAdjustGoal)
        model.currentState.card.warnings.forEach { it.text.text }
    }
}
