package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.UnitSystem

/**
 * Food amounts in the athlete's units. Storage stays metric (grams for food,
 * millilitres for water); US users see and type oz, fl oz and cups, which are
 * converted only at the UI edge. Drinks are taken as 1 g per ml – within a
 * few percent for water, milk, juice and soft drinks.
 */
object FuelUnits {
    const val G_PER_OZ = 28.349523125
    const val ML_PER_FL_OZ = 29.5735295625
    /** US customary cup, 8 fl oz. */
    const val ML_PER_CUP = 236.5882365

    enum class Amount(val perUnit: Double) { G(1.0), ML(1.0), OZ(G_PER_OZ), FL_OZ(ML_PER_FL_OZ), CUP(ML_PER_CUP) }

    /** Grams (or millilitres) for [value] in [unit]. */
    fun toBase(value: Double, unit: Amount): Double = value * unit.perUnit

    /** [base] grams (or millilitres) in [unit]. */
    fun fromBase(base: Double, unit: Amount): Double = base / unit.perUnit

    /** Unit for a food's weight, or for a drink's volume. */
    fun unitFor(system: UnitSystem, drink: Boolean): Amount = when {
        system == UnitSystem.IMPERIAL && drink -> Amount.FL_OZ
        system == UnitSystem.IMPERIAL -> Amount.OZ
        drink -> Amount.ML
        else -> Amount.G
    }

    /** Quick water amounts in ml: a glass and a bottle, or a cup and a 16.9 fl oz bottle. */
    fun waterPresetsMl(system: UnitSystem): List<Int> =
        if (system == UnitSystem.IMPERIAL) listOf(ML_PER_CUP.toInt() + 1, 500) else listOf(250, 500)

    /**
     * Whether a food is drunk rather than eaten, so US amounts are fl oz and
     * cups make sense. From the BLS group (N non-alcoholic, P alcoholic
     * beverages), a serving given in ml, or the name in German or English.
     *
     * Only the head of the name counts – the part before a comma, a bracket or
     * "mit/aus/im/with …" – so what a food is made with does not make it a
     * drink ("Kaiserschmarren (mit Milch)", "Thunfisch im eigenen Saft",
     * "Soup, prepared with water"), and in a hyphenated compound the last part
     * names the food ("Buttermilch-Dressing").
     */
    fun isDrink(id: String?, name: String, servingLabel: String? = null): Boolean {
        if (id != null && id.startsWith("bls:") && id.length > 4 && id[4] in "NP") return true
        if (servingLabel != null && VOLUME.containsMatchIn(servingLabel)) return true
        val head = name.lowercase().split(HEAD, limit = 2).first()
        val words = head.split(Regex("\\s+"))
            .map { if ('-' in it.trimEnd('-')) it.substringAfterLast('-') else it }
            .flatMap { it.split(Regex("[^\\p{L}]+")) }
            .filter { it.isNotEmpty() }
        if (words.any { w -> w in NOT_A_DRINK || NOT_A_DRINK_SUFFIXES.any { w.endsWith(it) } }) return false
        return words.any { w -> w in DRINK_WORDS || DRINK_SUFFIXES.any { w.endsWith(it) && !w.startsWith(it) } }
    }

    private val VOLUME = Regex("""\d\s*(ml|cl|l|fl\.?\s*oz)\b""", RegexOption.IGNORE_CASE)

    /** Where the name of the food ends and its description begins. */
    private val HEAD = Regex("""[,(;]|\s(?:mit|aus|im|in|zu|auf|with|on)\s""")

    /** German compounds that end like a drink but are food: "Schwein" (not "Wein"), "Schafskäse", "Speiseeis". */
    private val NOT_A_DRINK_SUFFIXES = listOf(
        "schwein", "käse", "kuchen", "brot", "schokolade", "riegel", "pulver", "pudding", "eis", "sauce", "soße", "dressing", "brei",
    )

    private val DRINK_WORDS = setOf(
        "water", "wasser", "juice", "saft", "nectar", "nektar", "milk", "milch", "drink", "beverage", "getränk",
        "smoothie", "shake", "kefir", "buttermilk", "buttermilch", "soda", "cola", "lemonade", "limonade", "limo",
        "schorle", "tea", "tee", "coffee", "kaffee", "latte", "cappuccino", "espresso", "beer", "bier", "wine",
        "wein", "sekt", "cider", "kombucha", "spritzer", "beverages",
    )

    /** German compounds: "Orangensaft", "Hafermilch", "Pfefferminztee", "Weißbier" – but not "Saftschinken". */
    private val DRINK_SUFFIXES = listOf("saft", "milch", "getränk", "drink", "wasser", "tee", "kaffee", "bier", "wein", "schorle", "nektar", "limonade")

    private val NOT_A_DRINK = setOf(
        "chocolate", "schokolade", "milchschokolade", "powder", "pulver", "bar", "riegel", "pudding", "cake", "kuchen",
        "ice", "eis", "cheese", "käse", "bread", "brot", "rice", "reis", "milchreis", "sauce", "soße",
    )
}
