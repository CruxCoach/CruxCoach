package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TodayPresenter
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.AthleteSettingsScreenModel
import com.cruxcoach.app.ui.TodayScreenModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.PlaceKind
import com.cruxcoach.athlete.model.TrainingPlace
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port of Android `PlacesTest` (owner 2026-10-10, 2026-10-15) through the iOS facades: the
 * settings keep every place with its own equipment, Today trains at the default and can pick
 * another one — or no equipment — for the day only.
 */
class PlacesPortTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    private val base = setOf(EquipmentV2.NONE, EquipmentV2.MAT)
    private val home = base + EquipmentV2.PICKUP_BLOCK + EquipmentV2.PLATES

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun day() = kit.db { service.today().toString() }

    @Test
    fun `the settings add a gym next to home and home stays the default`() {
        val today = day()
        kit.db { repo.updateProfile { TrainingPlaces.withEquipment(it, null, home, today) } }
        val p = AthleteSettingsPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { p.state.value.loaded }
        val model = AthleteSettingsScreenModel(p, "en")
        val homeId = kit.db { TrainingPlaces.of(repo.profile()).single().id }
        model.addPlace("GYM")
        kit.until("two places") { repo.profile().places.size == 2 }
        val gym = kit.db { repo.profile().places.last() }
        assertEquals(PlaceKind.GYM, gym.kind)
        assertTrue(EquipmentV2.HANGBOARD in gym.equipment)
        assertEquals(home, kit.db { repo.profile().equipment }, "training still happens at home")
        assertEquals(homeId, kit.db { TrainingPlaces.default(repo.profile())?.id })
        // A change to the new place changes that place only.
        kit.until("gym in the state") { model.currentState.places.any { it.id == gym.id } }
        val codes = model.currentState.places.single { it.id == gym.id }.equipmentCodes
        model.savePlaceEquipment(gym.id, codes + "PULLEY")
        kit.until("pulley at the gym") { EquipmentV2.PULLEY in repo.profile().places.last().equipment }
        assertTrue(EquipmentV2.PULLEY !in kit.db { repo.profile().equipment })
        model.makeDefaultPlace(gym.id)
        kit.until("gym is the default") { repo.profile().defaultPlaceId == gym.id }
        kit.until("trains at the gym") { EquipmentV2.HANGBOARD in repo.profile().equipment }
    }

    @Test
    fun `today trains at another place and only for today`() {
        val today = day()
        val gymId = kit.db {
            val (two, gymId) = TrainingPlaces.add(TrainingPlaces.withEquipment(repo.profile(), null, home, today), PlaceKind.GYM,
                base + EquipmentV2.HANGBOARD + EquipmentV2.PULL_UP_BAR, today)
            repo.saveProfile(two)
            gymId
        }
        val vm = TodayPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !vm.state.value.loading }
        TodayScreenModel(vm, "en").pickPlace(gymId)
        kit.until("gym equipment") { EquipmentV2.HANGBOARD in repo.profile().equipment }
        assertEquals(gymId, kit.db { TrainingPlaces.current(repo.profile(), today)?.id })
        // The next day starts at the default again.
        val tomorrow = kit.db { service.today().plus(DatePeriod(days = 1)).toString() }
        assertEquals(home, kit.db { TrainingPlaces.inUse(repo.profile(), tomorrow).equipment })
    }

    @Test
    fun `one place offers a day without equipment and two offer the pick`() {
        val today = day()
        val vm = TodayPresenter(service, kit.scope(), kit.dispatcher)
        val model = TodayScreenModel(vm, "en")
        kit.until("loaded") { !vm.state.value.loading }
        kit.db { repo.updateProfile { it.copy(places = emptyList(), defaultPlaceId = null, equipmentConfigured = false) } }
        kit.until("no places") { TrainingPlaces.of(vm.state.value.profile).isEmpty() }
        assertTrue(model.currentState.places.isEmpty(), "nothing to pick without a place")
        // Owner 2026-10-15: travelling or outside, "no equipment" is a choice even with one place.
        kit.db { repo.saveProfile(repo.profile().copy(places = listOf(TrainingPlace("a", PlaceKind.HOME, null, home)), defaultPlaceId = "a")) }
        kit.until("one place") { model.currentState.places.size == 2 }
        assertEquals(listOf("a", TrainingPlaces.NO_GEAR_ID), model.currentState.places.map { it.id })
        assertEquals("none", model.currentState.places.last().kindCode)
        model.pickPlace(TrainingPlaces.NO_GEAR_ID)
        kit.until("no equipment today") { TrainingPlaces.current(repo.profile(), today)?.id == TrainingPlaces.NO_GEAR_ID }
        kit.db { repo.updateProfile { it.copy(places = it.places + TrainingPlace("b", PlaceKind.GYM, null, base + EquipmentV2.HANGBOARD)) } }
        kit.until("two places") { model.currentState.places.size == 3 }
        model.pickPlace("b")
        kit.until("gym today") { TrainingPlaces.current(repo.profile(), today)?.id == "b" }
        kit.until("current marked") { model.currentState.places.single { it.isCurrent }.id == "b" }
    }
}
