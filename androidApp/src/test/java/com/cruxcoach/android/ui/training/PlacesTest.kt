package com.cruxcoach.android.ui.training

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsScreen
import com.cruxcoach.android.ui.training.athlete.AthleteSettingsViewModel
import com.cruxcoach.android.ui.training.athlete.SettingsSection
import com.cruxcoach.android.ui.training.common.PlacePicker
import com.cruxcoach.android.ui.training.today.TodayViewModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.PlaceKind
import com.cruxcoach.athlete.model.TrainingPlace
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Several places with their own equipment (owner 2026-10-10): the settings
 * keep every place, Today trains at the default and can pick another one for
 * the day, and with a single place there is nothing to pick.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlacesTest : AthleteScreenTest() {

    private val base = setOf(EquipmentV2.NONE, EquipmentV2.MAT)
    private val home = base + EquipmentV2.PICKUP_BLOCK + EquipmentV2.PLATES

    private fun day() = service.today().toString()

    @Test
    fun `the settings add a gym next to home, home stays the default`() {
        repo.updateProfile { TrainingPlaces.withEquipment(it, null, home, day()) }
        val vm = loaded(AthleteSettingsViewModel(service))
        render { AthleteSettingsScreen({}, viewModel = vm, section = SettingsSection.EQUIPMENT) }
        waitForTag("place_${TrainingPlaces.FIRST_ID}")
        click("place_add_gym")
        compose.waitUntil(WAIT_MS) { repo.profile().places.size == 2 }
        val gym = repo.profile().places.last()
        assertEquals(PlaceKind.GYM, gym.kind)
        assertTrue(EquipmentV2.HANGBOARD in gym.equipment)
        assertEquals(home, repo.profile().equipment) // training still happens at home
        // The new place opens at once; a tap on a chip changes that place only.
        waitForTag("place_default_${gym.id}")
        click("equipment_pulley") // not in the gym template
        compose.waitUntil(WAIT_MS) { EquipmentV2.PULLEY in repo.profile().places.last().equipment }
        assertTrue(EquipmentV2.PULLEY !in repo.profile().equipment)
        click("place_default_${gym.id}")
        compose.waitUntil(WAIT_MS) { repo.profile().defaultPlaceId == gym.id }
        assertTrue(EquipmentV2.HANGBOARD in repo.profile().equipment)
    }

    @Test
    fun `today trains at another place and only for today`() {
        val (two, gymId) = TrainingPlaces.add(TrainingPlaces.withEquipment(repo.profile(), null, home, day()), PlaceKind.GYM,
            base + EquipmentV2.HANGBOARD + EquipmentV2.PULL_UP_BAR, day())
        repo.saveProfile(two)
        val vm = loaded(TodayViewModel(service))
        vm.pickPlace(gymId)
        compose.waitUntil(WAIT_MS) { EquipmentV2.HANGBOARD in repo.profile().equipment }
        assertEquals(gymId, TrainingPlaces.current(repo.profile(), day())?.id)
        // The next day starts at the default again.
        assertEquals(home, TrainingPlaces.inUse(repo.profile(), service.today().plus(kotlinx.datetime.DatePeriod(days = 1)).toString()).equipment)
    }

    @Test
    fun `one place is simply used, two offer the pick`() {
        val homePlace = TrainingPlace("a", PlaceKind.HOME, null, home)
        val gymPlace = TrainingPlace("b", PlaceKind.GYM, null, base + EquipmentV2.HANGBOARD)
        var places by androidx.compose.runtime.mutableStateOf(listOf(homePlace))
        var picked: String? = null
        render { PlacePicker(places, currentId = "a", defaultId = "a", onPick = { picked = it }) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("today_place").fetchSemanticsNodes().isEmpty())
        places = listOf(homePlace, gymPlace)
        waitForTag("today_place")
        click("today_place")
        waitForTag("today_place_b")
        click("today_place_b")
        compose.waitUntil(WAIT_MS) { picked == "b" }
        compose.onNodeWithTag("today_place").assertExists()
    }
}
