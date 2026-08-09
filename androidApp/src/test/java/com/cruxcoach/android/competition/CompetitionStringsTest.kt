package com.cruxcoach.android.competition

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * English and German string parity.
 *
 * A missing German string does not crash — it silently falls back to English,
 * which is exactly why it survives review. This makes it a build failure
 * instead.
 */
class CompetitionStringsTest {

    private fun strings(dir: String): Map<String, String> {
        val file = listOf(
            File("src/main/res/$dir/strings.xml"),
            File("androidApp/src/main/res/$dir/strings.xml"),
        ).firstOrNull { it.isFile } ?: error("strings.xml not found (cwd=${File(".").absolutePath})")

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }

    private val english by lazy { strings("values") }
    private val german by lazy { strings("values-de") }

    @Test
    fun `every string exists in both languages`() {
        assertEquals(
            emptyList(),
            (english.keys - german.keys).sorted(),
            "these strings have no German translation",
        )
        assertEquals(
            emptyList(),
            (german.keys - english.keys).sorted(),
            "these German strings have no English original",
        )
    }

    @Test
    fun `every competition string exists in both languages`() {
        val keys = english.keys.filter { it.startsWith("comp_") || it.startsWith("main_menu_") }
        assertTrue(keys.size >= 80, "expected the FEAT-058 strings, found ${keys.size}")
        for (key in keys) {
            assertTrue(german.containsKey(key), "$key is missing from values-de")
        }
    }

    @Test
    fun `no competition string was left as the English text`() {
        // Some are legitimately identical ("Tops", "sat"); a long sentence that
        // matches exactly is a translation nobody did.
        val untranslated = english.keys
            .filter { it.startsWith("comp_") || it.startsWith("main_menu_") }
            .filter { key ->
                val en = english.getValue(key)
                en == german[key] && en.length > 14 && en.contains(' ')
            }
            .sorted()
        assertEquals(emptyList(), untranslated, "these German strings are identical to the English ones")
    }

    @Test
    fun `format placeholders match between the two languages`() {
        val placeholder = Regex("%\\d\\\$[a-z]")
        for (key in english.keys.filter { it.startsWith("comp_") || it.startsWith("main_menu_") }) {
            val inEnglish = placeholder.findAll(english.getValue(key)).map { it.value }.toSet()
            val inGerman = placeholder.findAll(german.getValue(key)).map { it.value }.toSet()
            assertEquals(inEnglish, inGerman, "$key: format arguments differ, which crashes at format time")
        }
    }

    @Test
    fun `every lifecycle and participant state has a label in both languages`() {
        val required = buildList {
            addAll(
                listOf(
                    "draft", "published", "registration_open", "registration_closed",
                    "checkin_open", "running", "paused", "finished", "cancelled",
                ).map { "comp_status_$it" },
            )
            addAll(listOf("pending", "accepted", "waitlisted", "rejected", "withdrawn").map { "comp_reg_$it" })
            addAll(
                listOf("not_required", "pending", "settled", "failed", "expired", "refunded")
                    .map { "comp_pay_$it" },
            )
            addAll(listOf("none", "checked_in", "no_show").map { "comp_checkin_$it" })
        }
        for (key in required) {
            assertTrue(english.containsKey(key), "$key is missing from values")
            assertTrue(german.containsKey(key), "$key is missing from values-de")
        }
    }
}
