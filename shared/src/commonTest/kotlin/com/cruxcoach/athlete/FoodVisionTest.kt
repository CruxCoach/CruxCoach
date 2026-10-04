package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.BlsTable
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.DeviceFacts
import com.cruxcoach.athlete.logic.FoodMatcher
import com.cruxcoach.athlete.logic.FoodVisionParser
import com.cruxcoach.athlete.logic.VisionCapability
import com.cruxcoach.athlete.logic.VisionSupport
import com.cruxcoach.athlete.logic.VisionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoodVisionTest {

    // ── Parser ───────────────────────────────────────────────────────

    @Test
    fun parsesTheGrammarShape() {
        val text = """
            {
              "items": [
                { "name_de": "Reis gekocht", "name_en": "Boiled rice", "grams": 180 },
                { "name_de": "Brokkoli", "name_en": "Broccoli", "grams": 90 }
              ]
            }
        """.trimIndent()
        assertEquals(
            listOf(DetectedFood("Reis gekocht", "Boiled rice", 180.0), DetectedFood("Brokkoli", "Broccoli", 90.0)),
            FoodVisionParser.parse(text),
        )
    }

    @Test
    fun parserToleratesNoiseDuplicatesAndBrokenItems() {
        val text = "Sure: {\"items\":[{\"name_de\":\"Banane\",\"name_en\":\"Banana\",\"grams\":120}," +
            "{\"name_de\":\"banane\",\"name_en\":\"Banana\",\"grams\":120},{\"name_de\":\"\",\"name_en\":\"\",\"grams\":5}," +
            "{\"name_de\":\"Suppe\",\"name_en\":\"Soup\",\"grams\":0},{\"name_en\":\"Bread\",\"grams\":9000}]} thanks"
        assertEquals(
            listOf(DetectedFood("Banane", "Banana", 120.0), DetectedFood("Bread", "Bread", FoodVisionParser.MAX_GRAMS)),
            FoodVisionParser.parse(text),
        )
    }

    @Test
    fun parserReturnsNothingForTruncatedOrEmptyAnswers() {
        assertEquals(emptyList(), FoodVisionParser.parse(""))
        assertEquals(emptyList(), FoodVisionParser.parse("{\"items\":[{\"name_de\":\"Reis"))
        assertEquals(emptyList(), FoodVisionParser.parse("{\"items\":[]}"))
    }

    // ── Device gate ──────────────────────────────────────────────────

    private val modernCpu = setOf("fp", "asimd", "fphp", "asimdhp", "asimddp", "atomics")

    private fun facts(ramGb: Double, cpu: Set<String> = modernCpu, arm64: Boolean = true, lowRam: Boolean = false) =
        DeviceFacts((ramGb * 1_000_000_000).toLong(), lowRam, arm64, cpu)

    @Test
    fun ramDecidesTheTier() {
        // Nokia 6.1 (Snapdragon 630): 2.8 GB and no dot product.
        assertEquals(VisionSupport.Unsupported(VisionSupport.Reason.CPU_FEATURES),
            VisionCapability.assess(facts(2.86, cpu = setOf("fp", "asimd", "aes", "crc32"))))
        assertEquals(VisionSupport.Unsupported(VisionSupport.Reason.LOW_RAM), VisionCapability.assess(facts(3.7)))
        // Pixel 6a: 6 GB nominal, reported ≈ 5.6 GB.
        assertEquals(VisionSupport.Supported(VisionTier.SMALL), VisionCapability.assess(facts(5.6)))
        assertEquals(VisionSupport.Supported(VisionTier.LARGE), VisionCapability.assess(facts(7.5)))
        assertEquals(VisionSupport.Unsupported(VisionSupport.Reason.LOW_RAM), VisionCapability.assess(facts(11.0, lowRam = true)))
        assertEquals(VisionSupport.Unsupported(VisionSupport.Reason.NOT_ARM64), VisionCapability.assess(facts(8.0, arm64 = false)))
    }

    @Test
    fun cpuFeaturesAreTheIntersectionOfAllCores() {
        val cpuinfo = """
            processor	: 0
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            CPU part	: 0xd05

            processor	: 6
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            CPU part	: 0xd44
        """.trimIndent()
        assertTrue(VisionCapability.cpuFeatures(cpuinfo).containsAll(VisionCapability.REQUIRED_CPU_FEATURES))

        val mixed = cpuinfo + "\n\nprocessor\t: 7\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n"
        assertTrue("asimddp" !in VisionCapability.cpuFeatures(mixed))
        assertEquals(emptySet(), VisionCapability.cpuFeatures("Hardware\t: Qualcomm"))
    }

    @Test
    fun probeVerdictExtrapolatesTheShortRun() {
        // 12 s image + 32 tokens in 4 s → 12 s + 160 × 125 ms = 32 s.
        val estimate = VisionCapability.estimateFullRunMs(imageMs = 12_000, generateMs = 4_000, outputTokens = 32)
        assertEquals(32_000, estimate)
        assertEquals(VisionCapability.ProbeVerdict.FAST, VisionCapability.verdict(estimate))
        assertEquals(VisionCapability.ProbeVerdict.SLOW, VisionCapability.verdict(90_000))
        assertEquals(VisionCapability.ProbeVerdict.TOO_SLOW, VisionCapability.verdict(150_000))
    }

    // ── BLS table and matching ───────────────────────────────────────

    private val table = """
        # Max Rubner-Institut (2025): Bundeslebensmittelschlüssel (BLS), Version 4.0
        # code	name_de	name_en	kcal	protein_g	fat_g	carbs_g
        C352000	Reis poliert, roh	White rice raw	351	7.93	0.62	77.1
        C352032	Reis poliert, gekocht	White rice boiled	130	2.9	0.3	28.6
        C356032	Reis Grieß, gekocht	Rice grits, boiled	95	2	0.2	20
        G312100	Broccoli roh	Broccoli raw	35	3.8	0.2	2.7
        G312152	Broccoli gedünstet	Broccoli stewed	34	3.5	0.2	2.6
        G332152	Rosenkohl gedünstet	Brussels sprouts stewed	41	4	0.3	3.4
        V416100	Hähnchen Brustfilet, roh	Chicken breast fillet, raw	106	23.6	1.2	0
        V4A6182	Hähnchen Brust, ohne Haut, gebraten ohne Fett (Pfanne)	Chicken breast, without skin, fried without fat (pan)	143	30	2	0
        F503100	Banane roh	Banana raw	95	1.1	0.2	20
        F850100	Bananenchips frittiert, gesüßt	Banana crips/banana chips, deep-fried, sweetened	520	2.3	33.6	54
        F503400	Banane getrocknet	Banana dried	292	3.4	0.8	64
        C133000	Hafer Flocken	Oat flakes	348	13.22	6.65	53.3
        X711412	Eier-Frischteigwaren Spätzle mit Käse (Käsespätzle)	Fresh egg pasta spaetzle with cheese	186	8	9	18
        broken line without enough fields
    """.trimIndent()

    private val foods = BlsTable.parse(table)
    private val matcher = FoodMatcher(foods)

    private fun best(de: String, en: String) = matcher.match(DetectedFood(de, en, 100.0)).firstOrNull()?.food?.code

    @Test
    fun tableSkipsCommentsAndBrokenLines() {
        assertEquals(13, foods.size)
        assertEquals(BlsFood("C133000", "Hafer Flocken", "Oat flakes", 348.0, 13.22, 6.65, 53.3), foods.single { it.code == "C133000" })
    }

    @Test
    fun matcherPrefersPreparedFoodsAndCompounds() {
        assertEquals("C352032", best("Reis", "Rice"))
        assertEquals("C352032", best("Reis gekocht", "Boiled rice"))
        assertEquals("C352000", best("Reis roh", "Raw rice"))
        assertEquals("G312152", best("Brokkoli", "Broccoli"))
        assertEquals("V4A6182", best("Hähnchenbrust gebraten", "Fried chicken breast"))
        assertEquals("C133000", best("Haferflocken", "Oatmeal"))
        assertEquals("X711412", best("Käsespätzle", "Cheese spaetzle"))
    }

    @Test
    fun englishNameRescuesAWrongGermanName() {
        // Qwen3.5-2B wrote "Brühlchen" for Brussels sprouts in the Mensa benchmark.
        assertEquals("G332152", best("Brühlchen", "Brussels sprouts"))
    }

    @Test
    fun fruitStaysRawWhenNothingPreparedExists() {
        assertEquals("F503100", best("Banane", "Banana"))
    }

    @Test
    fun searchFindsBothLanguagesAndIgnoresStopWords() {
        assertEquals("C133000", matcher.search("oat flakes").first().food.code)
        assertEquals("G332152", matcher.search("rosenkohl").first().food.code)
        assertEquals(emptyList(), matcher.search("mit und"))
    }
}
