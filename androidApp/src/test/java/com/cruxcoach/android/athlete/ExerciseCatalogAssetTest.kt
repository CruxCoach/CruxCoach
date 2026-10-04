package com.cruxcoach.android.athlete

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
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
