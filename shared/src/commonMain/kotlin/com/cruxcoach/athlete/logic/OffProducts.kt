package com.cruxcoach.athlete.logic

/**
 * A packaged product from the bundled Open Food Facts extract (German- and
 * English-speaking countries); nutrients per 100 g. Either name may be empty.
 */
data class OffProduct(
    val code: String,
    val nameDe: String,
    val nameEn: String,
    val brand: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val servingG: Double?,
    /** Bit 1: sold in DE/AT/CH, bit 2: sold in GB/IE/US/CA/AU/NZ. */
    val regions: Int,
    /** The pack's own words for a serving: "1 cup (30 g)", "1 Riegel (40 g)", "330 ml". */
    val servingLabel: String? = null,
) {
    fun displayName(german: Boolean): String = if (german) nameDe.ifEmpty { nameEn } else nameEn.ifEmpty { nameDe }

    companion object {
        const val REGION_GERMAN = 1
        const val REGION_ENGLISH = 2
    }
}

/**
 * Format of `assets/fuel/off_products.tsv.zst` (scripts/build_off_asset.py):
 * `#` comment lines with source, licence and `# version: yyyy-mm-dd`, then
 * barcode, German name, English name, brand, kcal, protein, carbs, fat,
 * serving g, regions, serving label, tab separated.
 */
object OffTable {

    fun parseLine(line: String): OffProduct? {
        if (line.isEmpty() || line.startsWith("#")) return null
        val f = line.split('\t')
        if (f.size < 8 || f[0].isEmpty() || (f[1].isEmpty() && f[2].isEmpty())) return null
        return OffProduct(
            code = f[0], nameDe = f[1], nameEn = f[2], brand = f[3],
            kcal = f[4].toDoubleOrNull() ?: return null,
            protein = f[5].toDoubleOrNull() ?: return null,
            carbs = f[6].toDoubleOrNull() ?: return null,
            fat = f[7].toDoubleOrNull() ?: return null,
            servingG = f.getOrNull(8)?.toDoubleOrNull()?.takeIf { it > 0 },
            regions = f.getOrNull(9)?.toIntOrNull() ?: 0,
            servingLabel = f.getOrNull(10)?.trim()?.ifEmpty { null },
        )
    }

    /**
     * Whether a pack's serving words are fit to show to a German or English
     * reader: an amount only ("330 ml") or a known serving noun ("1 cup
     * (30 g)", "1 Riegel (40 g)"). Labels in other languages ("1 vasetto
     * (350 g)", seen on the test phone) are dropped; the grams stay.
     */
    fun readableServing(label: String): Boolean {
        val words = label.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        return words.all { it in SERVING_WORDS }
    }

    private val SERVING_WORDS = setOf(
        "g", "gr", "gram", "grams", "ml", "l", "cl", "kg", "oz", "fl", "lb",
        "cup", "cups", "tbsp", "tsp", "tablespoon", "tablespoons", "teaspoon", "teaspoons", "slice", "slices",
        "piece", "pieces", "pc", "pcs", "bar", "bars", "bottle", "can", "pack", "packet", "pouch", "bag", "box",
        "serving", "servings", "portion", "portions", "container", "cookie", "cookies", "biscuit", "biscuits",
        "egg", "eggs", "scoop", "scoops", "glass", "pot", "tub", "cake", "of", "the", "a", "an", "per", "about", "approx",
        "stück", "stk", "scheibe", "scheiben", "riegel", "becher", "flasche", "dose", "packung", "beutel", "glas",
        "tasse", "tassen", "portion", "portionen", "el", "tl", "esslöffel", "teelöffel", "messlöffel", "keks", "kekse",
        "eine", "ein", "einer", "pro", "ca", "von", "der", "packungsinhalt", "tafel", "riegeln", "brötchen", "teil",
    )

    /** The `# version:` value of a header line, or null. */
    fun version(line: String): String? =
        line.takeIf { it.startsWith("# version:") }?.substringAfter(':')?.trim()?.ifEmpty { null }

    /**
     * Spellings to look a scanned code up under: scanners report a UPC-A as
     * 12 digits, Open Food Facts often stores it as EAN-13 with a leading 0.
     */
    fun barcodeVariants(scanned: String): List<String> {
        val code = scanned.filter { it.isDigit() }
        if (code.isEmpty()) return emptyList()
        return buildList {
            add(code)
            if (code.length == 12) add("0$code")
            if (code.length == 13 && code.startsWith("0")) add(code.drop(1))
            val trimmed = code.trimStart('0')
            if (trimmed.isNotEmpty() && trimmed != code) add(trimmed)
        }.distinct()
    }

    /**
     * The barcode as the database's integer key: digits only, leading zeros
     * dropped (so a UPC-A and its EAN-13 form agree); null for anything that
     * is not 1–18 digits.
     */
    fun barcodeKey(code: String): Long? {
        val digits = code.trim()
        if (digits.isEmpty() || digits.length > 18 || !digits.all { it.isDigit() }) return null
        return digits.toLongOrNull()?.takeIf { it > 0 }
    }

    /**
     * An FTS4 MATCH expression for free text: every word as a prefix, all
     * words required. Characters with a meaning in the query syntax are
     * dropped, so user input can never form an operator. Null for no words.
     */
    fun ftsQuery(text: String): String? {
        val words = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(' ')
            .filter { it.length >= 2 }
            .take(6)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "$it*" }
    }
}
