package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.cruxcoach.android.ui.training.basics.BasicsGateViewModel
import com.cruxcoach.android.ui.training.basics.BasicsScreen
import com.cruxcoach.android.ui.training.basics.BasicsViewModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The basics on the first visit to Training or Nutrition (owner 2026-10-09):
 * asked once while something essential is missing, all of it saved where the
 * targets, the energy need, the coach and the start loads read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BasicsTest : AthleteScreenTest() {

    @Test
    fun `the basics fill weight, height, age, sex, grade, experience and equipment`() {
        var done = false
        val vm = loaded(BasicsViewModel(service))
        render { BasicsScreen(onDone = { done = true }, viewModel = vm) }
        waitForTag("basics_page_0")
        compose.onNodeWithTag("basics_weight").performTextReplacement("68,5")
        compose.onNodeWithTag("basics_height").performTextReplacement("176")
        compose.onNodeWithTag("basics_birth_year").performTextReplacement("1990")
        click("basics_sex_female")
        click("basics_next")
        waitForTag("basics_page_1")
        click("basics_grade_plus")
        click("basics_experience_y3_5")
        click("preset_home")
        click("basics_done")
        compose.waitUntil(WAIT_MS) { repo.profile().basicsAsked }
        compose.waitUntil(WAIT_MS) { done }
        val p = repo.profile()
        assertEquals(68.5, repo.latest(BodyMetric.WEIGHT.key)!!.value, 1e-9)
        assertEquals(176.0, repo.latest(BodyMetric.HEIGHT.key)!!.value, 1e-9)
        assertEquals(1990, p.birthYear)
        assertEquals(Sex.FEMALE, p.sex)
        assertTrue(p.coach.currentGrade != null)
        assertEquals(ExperienceBand.Y3_5, p.coach.experience)
        // The coach's age guard rails follow the birth year.
        assertEquals(BasicsViewModel.ageBandFor(service.today().year - 1990), p.coach.ageBand)
        assertTrue(p.equipmentConfigured && EquipmentV2.HANGBOARD in p.equipment)
        assertTrue(p.basicsAsked)
    }

    @Test
    fun `the basics open once, only while something is missing`() {
        val first = loaded(BasicsGateViewModel(service))
        assertTrue(runBlocking { withTimeoutOrNull(WAIT_MS) { first.open.first() } } != null)
        assertTrue(repo.profile().basicsAsked)
        // Backing out does not bring them back on the next visit.
        val second = loaded(BasicsGateViewModel(service))
        assertNull(runBlocking { withTimeoutOrNull(1_000) { second.open.first() } })
    }
}
