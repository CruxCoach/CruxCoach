package com.cruxcoach.android.athlete

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.Familiarity
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.BuiltinRoutines
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The packaged catalogue is data, so it gets the checks a schema would give:
 * every exercise parses, is bilingual, links only to existing variants in a
 * consistent chain, and every starter routine resolves.
 */
class ExerciseCatalogAssetTest {

    private val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())

    @Test
    fun `catalogue is large and covers every category`() {
        assertTrue(catalog.all.size >= 180, "only ${catalog.all.size} exercises")
        assertEquals(ExerciseCategoryV2.entries.toSet(), catalog.all.map { it.category }.toSet())
    }

    @Test
    fun `every exercise has a level of familiarity`() {
        // Issue #68: written out for every exercise, so a missing one is not silently Basic.
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())
        val exercises = (raw as kotlinx.serialization.json.JsonObject)["exercises"] as kotlinx.serialization.json.JsonArray
        exercises.forEach { e ->
            val o = e as kotlinx.serialization.json.JsonObject
            val level = (o["familiarity"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            assertTrue(level in Familiarity.entries.map { it.name }, "${o["slug"]}: $level")
        }
        // Every kind of training has well-known exercises to start with (warm-ups for every session among them).
        val basics = catalog.all.filter { it.familiarity == Familiarity.BASIC }.map { it.category }.toSet()
        listOf(ExerciseCategoryV2.WARMUP, ExerciseCategoryV2.PULL, ExerciseCategoryV2.PUSH, ExerciseCategoryV2.CORE,
            ExerciseCategoryV2.LEGS, ExerciseCategoryV2.MOBILITY).forEach { assertTrue(it in basics, "no Basic ${it.name}") }
    }

    @Test
    fun `search finds every exercise that has the word in its name`() {
        // Device test 2026-10-17: "Handgelenk" listed five exercises, not "Handgelenkbeugen mit Kurzhantel".
        val found = catalog.search("Handgelenk", "de").map { it.slug }
        assertTrue("antagonist.wrist_curl" in found, "$found")
        assertTrue("antagonist.reverse_wrist_curl" in found, "$found")
    }

    @Test
    fun `slugs are unique and prefixed by their category`() {
        val slugs = catalog.all.map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        catalog.all.forEach { ex ->
            assertTrue(ex.slug.startsWith(ex.category.name.lowercase() + "."), ex.slug)
            assertTrue(ex.slug.matches(Regex("[a-z]+\\.[a-z0-9_]+")), ex.slug)
        }
    }

    @Test
    fun `every exercise is bilingual with instructions`() {
        catalog.all.forEach { ex ->
            for (lang in listOf("de", "en")) {
                val t = assertNotNull(ex.i18n[lang], "${ex.slug} misses $lang")
                assertTrue(t.name.isNotBlank() && t.name.length <= 40, "${ex.slug} $lang name")
                assertTrue(t.steps.size in 2..4, "${ex.slug} $lang steps")
                assertTrue(t.why.isNotBlank(), "${ex.slug} $lang why")
            }
            assertTrue(ex.difficulty in 1..5, ex.slug)
            assertTrue(ex.equipment.isNotEmpty(), ex.slug)
        }
    }

    @Test
    fun `progression chains are symmetric`() {
        catalog.all.forEach { ex ->
            ex.harder?.let { h -> assertEquals(ex.slug, assertNotNull(catalog[h], "${ex.slug} → $h").easier, "${ex.slug} → $h") }
            ex.easier?.let { e -> assertEquals(ex.slug, assertNotNull(catalog[e], "${ex.slug} ← $e").harder, "${ex.slug} ← $e") }
        }
    }

    @Test
    fun `load fields match the kind`() {
        catalog.all.forEach { ex ->
            if (ex.kind == ExerciseKind.CLIMB) assertEquals(LoadMode.NONE, ex.load, ex.slug)
            if (ex.kind == ExerciseKind.INTERVAL) assertNotNull(ex.defaults.workS, ex.slug)
        }
    }

    @Test
    fun `injured climbers keep one-arm pick-ups and bar pull-ups`() {
        val pickup = assertNotNull(catalog["finger.one_arm_pickup"])
        assertTrue(pickup.unilateral)
        assertEquals(LoadMode.EXTERNAL, pickup.load)
        val pullUp = assertNotNull(catalog["pull.pull_up"])
        assertTrue(pullUp.fingerFree)
        assertTrue(!pullUp.needsClimbingWall)
    }

    @Test
    fun `starter routines only reference packaged exercises`() {
        BuiltinRoutines.all.forEach { r ->
            r.items.forEach { item -> assertNotNull(catalog[item.slug], "${r.builtinKey}: ${item.slug}") }
        }
    }
}
