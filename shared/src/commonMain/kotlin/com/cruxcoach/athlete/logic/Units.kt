package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.UnitSystem

/** Storage is always metric; these helpers convert only at the UI edge. */
object Units {
    const val LB_PER_KG = 2.2046226218
    const val CM_PER_INCH = 2.54

    fun massToDisplay(kg: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) kg * LB_PER_KG else kg

    fun massFromDisplay(value: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) value / LB_PER_KG else value

    fun lengthToDisplay(cm: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) cm / CM_PER_INCH else cm

    fun lengthFromDisplay(value: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) value * CM_PER_INCH else value

    /** US customary units where daily life uses them (United States, Liberia, Myanmar); metric elsewhere. */
    fun defaultFor(country: String): UnitSystem =
        if (country.uppercase() in setOf("US", "LR", "MM")) UnitSystem.IMPERIAL else UnitSystem.METRIC

    /** Switches the unit system together with the smallest weight step (1 kg, or 2.5 lb plates). */
    fun withUnits(profile: AthleteProfile, system: UnitSystem): AthleteProfile =
        if (profile.units == system) profile
        else profile.copy(units = system, smallestIncrementKg = if (system == UnitSystem.IMPERIAL) 2.5 / LB_PER_KG else 1.0)

    fun massUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "lb" else "kg"
    fun lengthUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "in" else "cm"

    /** Ape index: arm span minus height. Positive values help on the wall. */
    fun apeIndex(armSpanCm: Double?, heightCm: Double?): Double? =
        if (armSpanCm == null || heightCm == null || armSpanCm <= 0 || heightCm <= 0) null else armSpanCm - heightCm
}
