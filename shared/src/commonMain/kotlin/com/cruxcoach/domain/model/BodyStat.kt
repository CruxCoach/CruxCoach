package com.cruxcoach.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BodyStat(
    val id: Long = 0,
    val date: String,
    val statName: String,
    val value: Double,
    val unit: String = "kg"
)

/**
 * Display names are NOT taken from here. `shared` cannot reach Android
 * resources, so these German strings used to be rendered directly and an
 * English user read them untranslated inside an otherwise localized screen.
 * The Android layer maps [StatDefinition.key] to a string resource
 * (`ui/bodystat/BodyStatLabels.kt`); what survives here is a fallback and a
 * developer-facing name.
 */
enum class StatCategory(val labelDe: String) {
    BODY_COMPOSITION("Körperzusammensetzung"),
    CLIMBING_SPECIFIC("Kletter-spezifisch"),
    MOBILITY("Beweglichkeit")
}

data class StatDefinition(
    val key: String,
    val labelDe: String,
    val unit: String,
    val category: StatCategory,
    val waistlineCompatible: Boolean,
    val placeholder: String,
    val higherIsBetter: Boolean = true
)

data class TrendEntry(
    val date: String,
    val value: Double
)

enum class BodyStatTimeRange(val labelDe: String, val months: Int?) {
    ONE_MONTH("1M", 1),
    THREE_MONTHS("3M", 3),
    SIX_MONTHS("6M", 6),
    ONE_YEAR("1J", 12),
    ALL("Alle", null)
}

object StatRegistry {

    val ALL: List<StatDefinition> = listOf(
        // ── Body Composition (Waistline-compatible) ──
        StatDefinition("weight", "Gewicht", "kg", StatCategory.BODY_COMPOSITION, waistlineCompatible = true, placeholder = "z.B. 72.5", higherIsBetter = false),
        StatDefinition("neck", "Halsumfang", "cm", StatCategory.BODY_COMPOSITION, waistlineCompatible = true, placeholder = "z.B. 38.0", higherIsBetter = false),
        StatDefinition("waist", "Taillenumfang", "cm", StatCategory.BODY_COMPOSITION, waistlineCompatible = true, placeholder = "z.B. 82.0", higherIsBetter = false),
        StatDefinition("hips", "Hüftumfang", "cm", StatCategory.BODY_COMPOSITION, waistlineCompatible = true, placeholder = "z.B. 95.0", higherIsBetter = false),
        StatDefinition("body fat", "Körperfett", "%", StatCategory.BODY_COMPOSITION, waistlineCompatible = true, placeholder = "z.B. 15.0", higherIsBetter = false),

        // ── Climbing-specific ──
        StatDefinition("arm_span", "Spannweite", "cm", StatCategory.CLIMBING_SPECIFIC, waistlineCompatible = false, placeholder = "z.B. 182.0", higherIsBetter = true),
        StatDefinition("forearm_circumference", "Unterarmumfang", "cm", StatCategory.CLIMBING_SPECIFIC, waistlineCompatible = false, placeholder = "z.B. 28.0", higherIsBetter = true),
        StatDefinition("max_hang_20mm", "Max Hang 20mm", "kg", StatCategory.CLIMBING_SPECIFIC, waistlineCompatible = false, placeholder = "z.B. 45.0", higherIsBetter = true),
        StatDefinition("finger_span", "Fingerspanne", "cm", StatCategory.CLIMBING_SPECIFIC, waistlineCompatible = false, placeholder = "z.B. 22.0", higherIsBetter = true),

        // ── Mobility ──
        StatDefinition("shoulder_mobility", "Schulterbeweglichkeit", "cm", StatCategory.MOBILITY, waistlineCompatible = false, placeholder = "z.B. 5.0", higherIsBetter = true),
        StatDefinition("hip_flexion", "Hüftbeugung", "°", StatCategory.MOBILITY, waistlineCompatible = false, placeholder = "z.B. 120", higherIsBetter = true),
    )

    private val byKey: Map<String, StatDefinition> = ALL.associateBy { it.key }

    val byCategory: Map<StatCategory, List<StatDefinition>> = ALL.groupBy { it.category }

    val waistlineKeys: Set<String> = ALL.filter { it.waistlineCompatible }.map { it.key }.toSet()

    fun get(key: String): StatDefinition? = byKey[key]

    fun labelDe(key: String): String = byKey[key]?.labelDe ?: key

    fun unit(key: String): String = byKey[key]?.unit ?: ""
}
