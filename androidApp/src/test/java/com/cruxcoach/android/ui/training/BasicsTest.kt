package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.cruxcoach.android.ui.training.basics.BasicsGateViewModel
import com.cruxcoach.android.ui.training.basics.BasicsScreen
import com.cruxcoach.android.ui.training.basics.BasicsViewModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.PlaceKind
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
 * The start on the first visit to Training (owner 2026-11-21: "onboarding focused on what matters most"): four
 * answers on one page, each one tap, saved where the coach reads them. Body data is not asked here any more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BasicsTest : AthleteScreenTest() {

    @Test
    fun `the start is one page with four answers and one button`() {
        var done = false
        val vm = loaded(BasicsViewModel(service))
        render { BasicsScreen(onDone = { done = true }, viewModel = vm) }
        waitForTag("basics_page")
        // No body data here: nutrition asks for it, a load asks for the body weight when it needs it.
        listOf("basics_weight", "basics_height", "basics_birth_year", "basics_sex_female", "basics_next").forEach { tag ->
            assertTrue(tag, compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty())
        }
        click("basics_grade_plus")
        click("basics_experience_y3_5")
        scrollTo("basics_page", "basics_age_y18_39")
        click("basics_age_y18_39")
        scrollTo("basics_page", "preset_home")
        click("preset_home")
        click("basics_done")
        compose.waitUntil(WAIT_MS) { repo.profile().basicsAsked }
        compose.waitUntil(WAIT_MS) { done }
        val p = repo.profile()
        assertTrue(p.coach.currentGrade != null)
        assertEquals(ExperienceBand.Y3_5, p.coach.experience)
        // The coach's guard rails for growing climbers and from 40 on read the age band.
        assertEquals(AgeBand.Y18_39, p.coach.ageBand)
        assertTrue(p.equipmentConfigured && EquipmentV2.HANGBOARD in p.equipment)
        assertNull(p.birthYear)
        assertNull(repo.latest(BodyMetric.WEIGHT.key))
    }

    @Test
    fun `the common equipment shows first and one chip opens the rest`() {
        val vm = loaded(BasicsViewModel(service))
        render { BasicsScreen(onDone = {}, viewModel = vm) }
        waitForTag("basics_page")
        scrollTo("basics_page", "equipment_more")
        // Five pieces most climbers have; rings and a barbell wait behind "+ n more".
        scrollTo("basics_page", "equipment_pull_up_bar")
        assertTrue(compose.onAllNodes(hasTestTag("equipment_barbell")).fetchSemanticsNodes().isEmpty())
        scrollTo("basics_page", "equipment_more")
        click("equipment_more")
        scrollTo("basics_page", "equipment_barbell")
        click("equipment_barbell")
        click("basics_done")
        compose.waitUntil(WAIT_MS) { repo.profile().basicsAsked }
        assertTrue(EquipmentV2.BARBELL in TrainingPlaces.default(repo.profile())!!.equipment)
    }

    @Test
    fun `mostly training in a gym makes the first place a gym`() {
        val vm = loaded(BasicsViewModel(service))
        render { BasicsScreen(onDone = {}, viewModel = vm) }
        waitForTag("basics_page")
        scrollTo("basics_page", "preset_gym")
        click("preset_gym")
        click("basics_done")
        compose.waitUntil(WAIT_MS) { repo.profile().basicsAsked }
        val place = TrainingPlaces.default(repo.profile())!!
        assertEquals(PlaceKind.GYM, place.kind)
        assertTrue(EquipmentV2.CAMPUS_BOARD in place.equipment)
    }

    @Test
    fun `a birth year from before stands for the age band`() {
        repo.updateProfile { it.copy(birthYear = service.today().year - 15) }
        val vm = loaded(BasicsViewModel(service))
        render { BasicsScreen(onDone = {}, viewModel = vm) }
        waitForTag("basics_page")
        scrollTo("basics_page", "basics_age_under_16")
        compose.onNodeWithTag("basics_age_under_16").assertIsSelected()
        compose.onNodeWithTag("basics_age_y18_39").assertIsNotSelected()
        click("basics_done")
        compose.waitUntil(WAIT_MS) { repo.profile().coach.ageBand == AgeBand.UNDER_16 }
    }

    @Test
    fun `the grade list shows that it goes on and reaches the harder grades`() {
        val vm = loaded(BasicsViewModel(service))
        render { BasicsScreen(onDone = {}, viewModel = vm) }
        waitForTag("basics_page")
        click("basics_grade")
        waitForTag("basics_grade_list")
        // A list that ended on a full row looked complete on the device (7a was out of sight). Here the dialog
        // leaves less than the list's maximum height, which is where the old list ended on a full row too.
        val list = compose.onNodeWithTag("basics_grade_list").fetchSemanticsNode().boundsInRoot
        val rows = compose.onAllNodes(SemanticsMatcher("grade row") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("basics_grade_item_") == true
            })
            // Unclipped position and size: boundsInRoot would be cut at the list edge.
            .fetchSemanticsNodes().map { it.positionInRoot.y to it.positionInRoot.y + it.size.height }
        // About half of the last row shows, so its text is cut and reads as "more below".
        val cut = rows.firstOrNull { (top, bottom) -> top < list.bottom && bottom > list.bottom }
        assertTrue("$list $rows", cut != null && (list.bottom - cut.first) / (cut.second - cut.first) in 0.35f..0.65f)
        compose.onNodeWithTag("basics_grade_list").performScrollToNode(hasTestTag("basics_grade_item_7a"))
        click("basics_grade_item_7a")
        click("basics_experience_y3_5")
        click("preset_home")
        click("basics_done")
        compose.waitUntil(WAIT_MS) { repo.profile().coach.currentGrade == "7a" }
    }

    @Test
    fun `the start opens once, only while one of its answers is missing`() {
        val first = loaded(BasicsGateViewModel(service))
        assertTrue(runBlocking { withTimeoutOrNull(WAIT_MS) { first.open.first() } } != null)
        assertTrue(repo.profile().basicsAsked)
        // Backing out does not bring it back on the next visit.
        val second = loaded(BasicsGateViewModel(service))
        assertNull(runBlocking { withTimeoutOrNull(1_000) { second.open.first() } })
    }

    @Test
    fun `one missing answer is enough to open the start`() {
        // Grade, years and equipment are there, the age band is not: the start opens (an "and" for the "or" would not).
        repo.updateProfile { p ->
            TrainingPlaces.withEquipment(p, null, setOf(EquipmentV2.NONE, EquipmentV2.MAT), service.today().toString(), PlaceKind.HOME)
                .let { it.copy(basicsAsked = false, coach = it.coach.copy(currentGrade = "6b", experience = ExperienceBand.Y1_2, ageBand = null)) }
        }
        val gate = loaded(BasicsGateViewModel(service))
        assertTrue(runBlocking { withTimeoutOrNull(WAIT_MS) { gate.open.first() } } != null)
    }

    @Test
    fun `body data is not what the start waits for`() {
        // Grade, years, age and equipment are there; weight, height and birth year are not: nothing opens.
        repo.updateProfile { p ->
            TrainingPlaces.withEquipment(p, null, setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.PULL_UP_BAR), service.today().toString(), PlaceKind.HOME)
                .let { it.copy(coach = it.coach.copy(currentGrade = "6b", experience = ExperienceBand.Y1_2, ageBand = AgeBand.Y18_39)) }
        }
        val gate = loaded(BasicsGateViewModel(service))
        assertNull(runBlocking { withTimeoutOrNull(1_000) { gate.open.first() } })
        assertTrue(!repo.profile().basicsAsked)
    }
}
