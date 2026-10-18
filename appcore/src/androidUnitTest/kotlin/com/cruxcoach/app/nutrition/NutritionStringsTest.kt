package com.cruxcoach.app.nutrition

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every string key the nutrition facade and the nutrition SwiftUI screens use
 * exists in English and German. Swift is compiled only on CI and a missing
 * key there shows the raw key to the user, so the check runs here.
 */
class NutritionStringsTest {
    private val keyPattern = Regex("^(tr|trf|tru|trn|trt|tra|fvp|settings|action)_[a-z0-9_]+$")
    private val literal = Regex("\"([a-z0-9_]+)\"")

    private fun kotlinKeys(): Set<String> {
        val dir = File(repoRoot, "appcore/src/commonMain/kotlin/com/cruxcoach/app/ui")
        val files = listOf("NutritionText.kt", "NutritionScreen.kt", "EnergyScreen.kt", "FoodForms.kt", "FoodTextScreen.kt").map { File(dir, it) }
        return files.flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] }.filter { keyPattern.matches(it) } }.toSet()
    }

    private fun has(table: Map<String, String>, key: String) = key in table || ("$key.one" in table && "$key.other" in table)

    @Test
    fun `every key the facade emits exists in both languages`() {
        val keys = kotlinKeys()
        assertTrue(keys.size > 100, "found only ${keys.size} keys")
        val missing = keys.filter { !has(IosStrings.en, it) || !has(IosStrings.de, it) }
        assertTrue(missing.isEmpty(), "missing: $missing")
    }

    @Test
    fun `every key the nutrition screens use exists in both languages`() {
        val dir = File(repoRoot, "iosApp/CruxCoach/UI/Nutrition")
        val swift = (dir.listFiles().orEmpty().filter { it.extension == "swift" } +
            File(repoRoot, "iosApp/CruxCoach/Platform").listFiles().orEmpty().filter { it.name.startsWith("Barcode") })
        assertTrue(swift.isNotEmpty())
        val text = swift.joinToString("\n") { it.readText() }
        // L("…") calls plus key literals handed to L elsewhere (the guide's paragraphs).
        val android = Regex("\\bL\\(\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet() +
            literal.findAll(text).map { it.groupValues[1] }.filter { keyPattern.matches(it) }.toSet()
        val plurals = Regex("\\bLP\\(\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
        val ios = Regex("\\bLI\\(\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
        val missing = android.filter { it !in IosStrings.en || it !in IosStrings.de } +
            plurals.filter { "$it.one" !in IosStrings.en || "$it.other" !in IosStrings.de } +
            ios.filter { it !in IosStrings.iosEn || it !in IosStrings.iosDe }
        assertTrue(missing.isEmpty(), "missing: $missing")
        // The codes Swift renders keys from (e.g. a confirm button's key) are checked by the facade test above.
        assertTrue(android.size + ios.size > 20)
    }
}
