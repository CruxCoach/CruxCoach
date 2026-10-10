package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.android.ui.training.common.toggleEquipment
import com.cruxcoach.android.ui.training.player.perBlockText
import com.cruxcoach.athlete.catalog.CatalogFilter
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.ReadinessEvaluator
import com.cruxcoach.athlete.logic.SessionSuggester
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionInput
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Most lifting edges take one hand (owner 2026-10-10): one pick-up block
 * means one-arm lifts; two-arm lifts need a second block (or an edge for
 * both hands), and their total is shown per block.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PickupBlocksTest : AthleteScreenTest() {

    private val base = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.PLATES, EquipmentV2.PULL_UP_BAR)

    private fun fingerMains(equipment: Set<EquipmentV2>): List<String> {
        val profile = AthleteProfile(equipment = equipment, equipmentConfigured = true, sessionMinutes = 60)
        val input = SuggestionInput(service.catalog, profile, LocalDate.parse("2026-10-05"),
            ReadinessEvaluator.evaluate(null, emptyList()), emptyList(), emptyMap())
        val s = SessionSuggester.suggest(input)
        assertEquals(SuggestionFocus.FINGER_STRENGTH, s.focus)
        return s.routine.items.filter { !it.warmup && it.slug.startsWith("finger.") }.map { it.slug }
    }

    @Test
    fun `one block offers one-arm lifts only, a second block adds the two-arm lift`() {
        val one = CatalogFilter(ownedEquipment = base + EquipmentV2.PICKUP_BLOCK)
        val two = CatalogFilter(ownedEquipment = base + EquipmentV2.PICKUP_BLOCK_PAIR)
        val oneArm = service.catalog["finger.one_arm_pickup"]!!
        val twoArm = service.catalog["finger.two_arm_pickup"]!!
        assertTrue(one.matches(oneArm))
        assertFalse(one.matches(twoArm))
        assertTrue(two.matches(oneArm) && two.matches(twoArm))

        val single = fingerMains(base + EquipmentV2.PICKUP_BLOCK)
        assertTrue("$single", single.isNotEmpty() && single.none { it == twoArm.slug })
        assertTrue("$single", single.all { service.catalog[it]!!.unilateral })
    }

    @Test
    fun `the second block comes with the first and goes with it`() {
        val picked = toggleEquipment(base, EquipmentV2.PICKUP_BLOCK_PAIR)
        assertTrue(EquipmentV2.PICKUP_BLOCK in picked && EquipmentV2.PICKUP_BLOCK_PAIR in picked)
        val dropped = toggleEquipment(picked, EquipmentV2.PICKUP_BLOCK)
        assertFalse(EquipmentV2.PICKUP_BLOCK in dropped || EquipmentV2.PICKUP_BLOCK_PAIR in dropped)
        assertEquals(setOf(EquipmentV2.PICKUP_BLOCK) + base, toggleEquipment(picked, EquipmentV2.PICKUP_BLOCK_PAIR))
    }

    @Test
    fun `a two-arm lift shows what goes on each block`() {
        val twoArm = service.catalog["finger.two_arm_pickup"]!!
        val oneArm = service.catalog["finger.one_arm_pickup"]!!
        fun set(slug: String) = ExerciseSet(id = slug, workoutId = "w", exerciseSlug = slug, blockIndex = 0, setIndex = 0,
            setType = SetType.WORK, loadKg = 38.0)
        compose.setContent {
            Text("two: " + perBlockText(set(twoArm.slug), twoArm, UnitSystem.METRIC))
            Text("one: " + perBlockText(set(oneArm.slug), oneArm, UnitSystem.METRIC))
        }
        compose.onNodeWithText("two: 19 kg per block").assertExists()
        compose.onNodeWithText("one: null").assertExists()
    }
}
