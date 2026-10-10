package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PlaceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Places with their own equipment, a default and a place picked for one day (owner 2026-10-10). */
class TrainingPlacesTest {

    private val today = "2026-10-10"
    private val tomorrow = "2026-10-11"
    private val base = setOf(EquipmentV2.NONE, EquipmentV2.MAT)
    private val home = base + EquipmentV2.PICKUP_BLOCK + EquipmentV2.PLATES
    private val gym = base + EquipmentV2.HANGBOARD + EquipmentV2.PULL_UP_BAR + EquipmentV2.PLATES

    @Test
    fun anOlderProfileKeepsItsEquipmentAsItsOnlyPlace() {
        val old = AthleteProfile(equipment = home, equipmentConfigured = true)
        val places = TrainingPlaces.of(old)
        assertEquals(1, places.size)
        assertEquals(home, places.single().equipment)
        val written = TrainingPlaces.inUse(old, today)
        assertEquals(places, written.places)
        assertEquals(TrainingPlaces.FIRST_ID, written.defaultPlaceId)
        assertEquals(home, written.equipment)
        // Nothing configured yet: no place, nothing changes.
        assertEquals(AthleteProfile(), TrainingPlaces.inUse(AthleteProfile(), today))
    }

    @Test
    fun aPlacePickedForTodayIsBackToTheDefaultTomorrow() {
        val (withGym, gymId) = TrainingPlaces.add(TrainingPlaces.withEquipment(AthleteProfile(), null, home, today), PlaceKind.GYM, gym, today)
        assertEquals(home, withGym.equipment) // the first place stays the default
        val atGym = TrainingPlaces.pick(withGym, gymId, today)
        assertEquals(gym, atGym.equipment)
        assertEquals(gymId, TrainingPlaces.current(atGym, today)?.id)
        val nextDay = TrainingPlaces.inUse(atGym, tomorrow)
        assertEquals(home, nextDay.equipment)
        // Making the gym the default changes every day without a pick.
        assertEquals(gym, TrainingPlaces.inUse(TrainingPlaces.makeDefault(nextDay, gymId, tomorrow), "2026-10-12").equipment)
    }

    @Test
    fun editsGoToThePlaceInUseAndTheLastPlaceStays() {
        val (two, gymId) = TrainingPlaces.add(TrainingPlaces.withEquipment(AthleteProfile(), null, home, today), PlaceKind.GYM, gym, today)
        val atGym = TrainingPlaces.pick(two, gymId, today)
        val more = TrainingPlaces.withEquipment(atGym, null, gym + EquipmentV2.RINGS, today)
        assertTrue(EquipmentV2.RINGS in more.equipment)
        assertTrue(EquipmentV2.RINGS !in TrainingPlaces.default(more)!!.equipment)
        assertEquals(home + gym + EquipmentV2.RINGS, TrainingPlaces.allEquipment(more))
        // Removing the gym (picked today) falls back to home; home cannot be removed as the last place.
        val one = TrainingPlaces.remove(more, gymId, today)
        assertEquals(home, one.equipment)
        assertNull(one.placeToday)
        assertEquals(one, TrainingPlaces.remove(one, one.places.single().id, today))
        // A name of its own, and blank back to the kind's name.
        val named = TrainingPlaces.rename(one, one.places.single().id, "  Keller ", today)
        assertEquals("Keller", named.places.single().name)
        assertNull(TrainingPlaces.rename(named, named.places.single().id, " ", today).places.single().name)
    }
}
