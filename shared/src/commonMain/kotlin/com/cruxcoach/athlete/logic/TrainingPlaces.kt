package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PlaceKind
import com.cruxcoach.athlete.model.TrainingPlace

/**
 * Places with their own equipment (owner 2026-10-10): a block at home, a
 * hangboard and plates in the gym. One place is the default; another can be
 * picked for a day, and the next day starts at the default again. The
 * profile's [AthleteProfile.equipment] always holds the equipment of the
 * place in use, so suggestions, filters and alternatives read one set.
 * Every function takes the day ("2026-10-10") it acts for.
 */
object TrainingPlaces {

    /** Id of the one place a profile from before places gets for its equipment. */
    const val FIRST_ID = "place-1"

    /** The places; an older profile with one configured equipment set has it as its only place. */
    fun of(p: AthleteProfile): List<TrainingPlace> = p.places.ifEmpty {
        if (p.equipmentConfigured) listOf(TrainingPlace(FIRST_ID, PlaceKind.HOME, null, p.equipment)) else emptyList()
    }

    fun default(p: AthleteProfile): TrainingPlace? = of(p).let { list -> list.firstOrNull { it.id == p.defaultPlaceId } ?: list.firstOrNull() }

    /** Where the athlete trains on [day]: the place picked for that day, else the default. */
    fun current(p: AthleteProfile, day: String): TrainingPlace? =
        of(p).firstOrNull { p.placeDay == day && it.id == p.placeToday } ?: default(p)

    /** Everything at any place: what the athlete can test or log at all. */
    fun allEquipment(p: AthleteProfile): Set<EquipmentV2> = of(p).flatMap { it.equipment }.toSet().ifEmpty { p.equipment }

    /** The profile with [day]'s place in use; an older profile gets its place written out. */
    fun inUse(p: AthleteProfile, day: String): AthleteProfile {
        val list = of(p)
        val place = current(p, day) ?: return p
        return p.copy(places = list, defaultPlaceId = default(p)?.id, equipment = place.equipment, equipmentConfigured = true)
    }

    /** Train at [placeId] on [day]; the default stays as it is. */
    fun pick(p: AthleteProfile, placeId: String, day: String): AthleteProfile =
        if (of(p).none { it.id == placeId }) p else inUse(p.copy(placeToday = placeId, placeDay = day), day)

    /**
     * New equipment for a place, the place in use when [placeId] is null. The
     * first edit of a profile without places creates its first place.
     */
    fun withEquipment(p: AthleteProfile, placeId: String?, equipment: Set<EquipmentV2>, day: String): AthleteProfile {
        val list = of(p)
        val id = placeId ?: current(p, day)?.id
        val places = if (id != null && list.any { it.id == id }) list.map { if (it.id == id) it.copy(equipment = equipment) else it }
            else list + TrainingPlace(id ?: nextId(list), PlaceKind.HOME, null, equipment)
        return inUse(p.copy(places = places, equipmentConfigured = true), day)
    }

    /** Adds a place and returns the profile with it and its id; the first place becomes the default. */
    fun add(p: AthleteProfile, kind: PlaceKind, equipment: Set<EquipmentV2>, day: String): Pair<AthleteProfile, String> {
        val list = of(p)
        val id = nextId(list)
        val places = list + TrainingPlace(id, kind, null, equipment)
        return inUse(p.copy(places = places, defaultPlaceId = default(p)?.id ?: id, equipmentConfigured = true), day) to id
    }

    /** The athlete's own name for a place; blank goes back to the kind's name. */
    fun rename(p: AthleteProfile, placeId: String, name: String?, day: String): AthleteProfile =
        inUse(p.copy(places = of(p).map { if (it.id == placeId) it.copy(name = name?.trim()?.takeIf(String::isNotEmpty)) else it }), day)

    fun makeDefault(p: AthleteProfile, placeId: String, day: String): AthleteProfile =
        if (of(p).none { it.id == placeId }) p else inUse(p.copy(places = of(p), defaultPlaceId = placeId), day)

    /** Removes a place; the last one stays. A removed default hands over to the first place left. */
    fun remove(p: AthleteProfile, placeId: String, day: String): AthleteProfile {
        val list = of(p)
        if (list.size <= 1 || list.none { it.id == placeId }) return p
        val rest = list.filter { it.id != placeId }
        val defaultId = default(p)?.id?.takeIf { it != placeId } ?: rest.first().id
        return inUse(p.copy(places = rest, defaultPlaceId = defaultId, placeToday = p.placeToday?.takeIf { it != placeId }), day)
    }

    private fun nextId(list: List<TrainingPlace>): String =
        generateSequence(list.size + 1) { it + 1 }.map { "place-$it" }.first { id -> list.none { it.id == id } }
}
