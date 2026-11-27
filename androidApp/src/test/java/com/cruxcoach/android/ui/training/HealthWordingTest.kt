package com.cruxcoach.android.ui.training

import com.cruxcoach.athlete.logic.ComebackContent
import com.cruxcoach.athlete.logic.ComebackTexts
import com.cruxcoach.athlete.logic.Txt
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.Urgency
import com.cruxcoach.athlete.model.WarningSign
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The comeback plan and the injury mode are a training aid, not a medical device
 * (docs/research/2026-10-20-comeback-plan/safety-regulation.md §4.7, owner rule):
 * no healing, treatment or clearance words and no promises in any text the
 * training area shows – Android strings of training and nutrition, the exercise
 * catalogue, the comeback texts of the shared module – nor in the changelog's
 * unreleased section, the release notes and the store text.
 *
 * A word is fine when its sentence negates it before it ("keine Diagnose",
 * "criteria don't show that it has healed") or when it names a profession
 * (physio, physiotherapist) or prevention ("prehab").
 */
class HealthWordingTest {

    private enum class Lang { DE, EN }

    private val forbiddenDe = listOf(
        """reha\w*""", """\w*therapie\w*""", """behandl\w*""", """\w*heil(?:t\w*|en|ung\w*|e|st)?""", """diagnos\w*""",
        """freigegeben""", """freigabe""", """garantier\w*""", """erlaubt""", """sicher ist""",
    ).map { Regex("""(?iU)\b$it\b""") }

    private val forbiddenEn = listOf(
        """\w*rehab\w*""", """\w*therap\w*""", """heal(?:s|ed|ing)?""", """cure[sd]?""", """diagnos\w*""",
        """treat(?:s|ed|ing|ment|ments)?""", """cleared""", """clearance""", """guarantee\w*""", """allowed""",
        """allows""", """is safe""",
    ).map { Regex("""(?iU)\b$it\b""") }

    private val allowedWords = listOf(Regex("""(?i)prehab"""), Regex("""(?i)physiotherap\w*"""))
    private val negationDe = Regex("""(?iU)\b(kein\w*|nicht|nie|ohne)\b""")
    private val negationEn = Regex("""(?iU)\b(not|no|never|without|\w+n['’]t)\b""")

    /** Sentences and dash-separated clauses: a negation only covers its own clause. */
    private val clauseBreak = Regex("""(?<=[.!?;:])\s+|\n+|\s[–—]\s""")

    private fun violations(text: String, lang: Lang): List<String> {
        val words = if (lang == Lang.DE) forbiddenDe else forbiddenEn
        val negation = if (lang == Lang.DE) negationDe else negationEn
        return text.split(clauseBreak).flatMap { clause ->
            words.flatMap { word -> word.findAll(clause).toList() }
                .filter { m -> allowedWords.none { it.matches(m.value) } }
                .filterNot { m -> negation.containsMatchIn(clause.substring(0, m.range.first)) }
                .map { m -> "\"${m.value}\" in: ${clause.trim().take(160)}" }
        }
    }

    private fun check(source: String, text: String, lang: Lang, out: MutableList<String>) {
        violations(text, lang).forEach { out += "$source: $it" }
    }

    // ── Android strings ─────────────────────────────────────────────────────

    private val res = File("src/main/res")

    /** name → text of every string, plural item and array item, unescaped like Android does. */
    private fun strings(file: File): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Pair<String, String>>()
        for (tag in listOf("string", "plurals", "string-array")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                val name = e.getAttribute("name")
                if (tag == "string") out += name to unescape(e.textContent)
                else {
                    val items = e.getElementsByTagName("item")
                    for (j in 0 until items.length) out += "$name[$j]" to unescape(items.item(j).textContent)
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\'", "'").replace("\\\"", "\"").removeSurrounding("\"")

    private fun trainingStringFiles(dir: String): List<File> =
        File(res, dir).listFiles { f -> f.name.startsWith("strings_training") || f.name.startsWith("strings_food") }
            ?.sortedBy { it.name }.orEmpty()

    @Test
    fun trainingAndNutritionStringsMakeNoMedicalClaims() {
        val out = mutableListOf<String>()
        val en = trainingStringFiles("values")
        val de = trainingStringFiles("values-de")
        assertTrue(en.size >= 30 && de.size == en.size, "string files not found (cwd=${File(".").absolutePath})")
        en.forEach { f -> strings(f).forEach { (n, t) -> check("values/${f.name} $n", t, Lang.EN, out) } }
        de.forEach { f -> strings(f).forEach { (n, t) -> check("values-de/${f.name} $n", t, Lang.DE, out) } }
        // The backup description names what the injury switch keeps out.
        listOf("values" to Lang.EN, "values-de" to Lang.DE).forEach { (dir, lang) ->
            strings(File(res, "$dir/strings.xml")).filter { it.first == "settings_backup_description" }
                .forEach { (n, t) -> check("$dir/strings.xml $n", t, lang, out) }
        }
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
    }

    // ── Exercise catalogue ──────────────────────────────────────────────────

    @Test
    fun exerciseCatalogueMakesNoMedicalClaims() {
        val file = File("src/main/assets/training/exercise_catalog.json")
        val root = Json.parseToJsonElement(file.readText())
        val out = mutableListOf<String>()
        var texts = 0
        fun leaves(e: JsonElement, path: String, lang: Lang) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> leaves(v, "$path.$k", lang) }
                is JsonArray -> e.forEachIndexed { i, v -> leaves(v, "$path[$i]", lang) }
                is JsonPrimitive -> if (e.isString) { texts++; check(path, e.content, lang, out) }
            }
        }
        fun walk(e: JsonElement, path: String) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    if (k == "i18n" && v is JsonObject) {
                        v["de"]?.let { leaves(it, "$path.i18n.de", Lang.DE) }
                        v["en"]?.let { leaves(it, "$path.i18n.en", Lang.EN) }
                    } else walk(v, if (k == "slug") path else "$path.$k")
                }
                is JsonArray -> e.forEachIndexed { i, v ->
                    val slug = ((v as? JsonObject)?.get("slug") as? JsonPrimitive)?.content
                    walk(v, slug ?: "$path[$i]")
                }
                else -> Unit
            }
        }
        walk(root, "catalog")
        assertTrue(texts > 1000, "catalogue texts not found ($texts)")
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
    }

    // ── Comeback texts of the shared module ─────────────────────────────────

    @Test
    fun comebackTextsMakeNoMedicalClaims() {
        val texts = mutableListOf<Pair<String, Txt>>()
        ComebackContent.all.forEach { l ->
            texts += "${l.id} evidence" to l.evidence
            l.stages.forEachIndexed { i, s ->
                (listOf(s.title, s.goal, s.climbingText) + s.doThis + s.nextWhen + s.avoid).forEach { texts += "${l.id} stage $i" to it }
            }
        }
        InjuryFinding.entries.forEach { f ->
            texts += "finding $f" to ComebackTexts.finding(f)
            ComebackTexts.findingNotes(f).forEach { texts += "finding $f note" to it }
            ComebackTexts.typicalCourse(f)?.let { texts += "finding $f course" to it }
        }
        WarningSign.entries.forEach { s ->
            texts += "sign $s" to ComebackTexts.sign(s)
            ComebackTexts.signWhy(s)?.let { texts += "sign $s why" to it }
        }
        Urgency.entries.forEach { u ->
            texts += "alert $u title" to ComebackTexts.alertTitle(u)
            texts += "alert $u text" to ComebackTexts.alertText(u)
        }
        listOf(ComebackContent.NOT_A_SUBSTITUTE, ComebackTexts.GELATIN, ComebackTexts.UNDER_18, ComebackTexts.NOTHING_TICKED,
            ComebackTexts.OWN_PLAN_NOTE).forEach { texts += "notice" to it }
        val out = mutableListOf<String>()
        texts.forEach { (where, t) -> check("$where (de)", t.de, Lang.DE, out); check("$where (en)", t.en, Lang.EN, out) }
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
    }

    // ── Release texts ───────────────────────────────────────────────────────

    @Test
    fun releaseTextsMakeNoMedicalClaims() {
        val repo = File("..")
        val changelog = File(repo, "CHANGELOG.md").readText()
        val unreleased = changelog.substringAfter("## [Unreleased]", "").substringBefore("\n## [")
        val out = mutableListOf<String>()
        // Changelog lines wrap inside a sentence; join them first.
        check("CHANGELOG [Unreleased]", unreleased.replace(Regex("""\n\s+"""), " "), Lang.EN, out)
        check("RELEASE_NOTES.md", File(repo, "RELEASE_NOTES.md").readText().replace(Regex("""\n\s+"""), " "), Lang.EN, out)
        check("zapstore.yaml", File(repo, "zapstore.yaml").readText(), Lang.EN, out)
        if (out.isNotEmpty()) fail(out.joinToString("\n"))
    }

    // ── The rule itself ─────────────────────────────────────────────────────

    @Test
    fun negationsProfessionsAndPreventionPass() {
        assertTrue(violations("Das ist keine Diagnose.", Lang.DE).isEmpty())
        assertTrue(violations("Er kann nicht sagen, ob deine Verletzung verheilt ist.", Lang.DE).isEmpty())
        assertTrue(violations("Met criteria don't show that the injury has healed.", Lang.EN).isEmpty())
        assertTrue(violations("Ask your physiotherapist; antagonist & prehab work.", Lang.EN).isEmpty())
        assertTrue(violations("Your healthy hand and Health Connect.", Lang.EN).isEmpty())
        // A negation in another clause does not cover the word.
        assertTrue(violations("Kein Ersatz für den Arztbesuch – der Finger heilt so schneller.", Lang.DE).isNotEmpty())
        assertTrue(violations("Healed", Lang.EN).isNotEmpty())
        assertTrue(violations("Verheilt", Lang.DE).isNotEmpty())
        assertTrue(violations("Nach einer verheilten Verletzung", Lang.DE).isNotEmpty())
        assertTrue(violations("Criteria don’t show it has healed.", Lang.EN).isEmpty())
        assertTrue(violations("Physiotherapie", Lang.DE).isEmpty())
        assertTrue(violations("Therapie nach Plan", Lang.DE).isNotEmpty())
    }
}
