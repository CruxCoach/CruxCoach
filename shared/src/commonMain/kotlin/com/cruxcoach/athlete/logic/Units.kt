package com.cruxcoach.athlete.logic

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

    fun massUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "lb" else "kg"
    fun lengthUnit(system: UnitSystem): String = if (system == UnitSystem.IMPERIAL) "in" else "cm"

    /** Ape index: arm span minus height. Positive values help on the wall. */
    fun apeIndex(armSpanCm: Double?, heightCm: Double?): Double? =
        if (armSpanCm == null || heightCm == null || armSpanCm <= 0 || heightCm <= 0) null else armSpanCm - heightCm
}
