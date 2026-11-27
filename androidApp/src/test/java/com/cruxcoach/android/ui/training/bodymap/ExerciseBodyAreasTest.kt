package com.cruxcoach.android.ui.training.bodymap

import com.cruxcoach.android.athlete.ExerciseCatalogStore
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.Side
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The body map is data too: every catalogue exercise must show what it trains, and taps must land where drawn. */
class ExerciseBodyAreasTest {

    private val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())

    @Test
    fun `every catalogue exercise has at least one main area`() {
        val missing = catalog.all.filter { def -> ExerciseBodyAreas.of(def).values.none { it >= ExerciseBodyAreas.PRIMARY } }
        assertTrue(missing.isEmpty(), "no main area: ${missing.map { it.slug }}")
    }

    @Test
    fun `every catalogue muscle name is known to the map`() {
        // A new muscle name in the catalogue must get a place on the body, not silently fall back.
        val unknown = catalog.all.flatMap { it.muscles.primary + it.muscles.secondary }.toSet()
            .filterNot { ExerciseBodyAreas.knows(it) }
        assertTrue(unknown.isEmpty(), "unknown muscles: $unknown")
    }

    @Test
    fun `spot checks match what the exercises train`() {
        val pickup = ExerciseBodyAreas.of(catalog["finger.one_arm_pickup"]!!)
        assertEquals(1f, pickup[BodyArea.FINGERS])
        assertEquals(1f, pickup[BodyArea.FOREARM_FLEXORS])
        val weighted = ExerciseBodyAreas.of(catalog["pull.weighted_pull_up"]!!)
        assertEquals(1f, weighted[BodyArea.LATS])
        assertEquals(1f, weighted[BodyArea.BICEPS])
        assertEquals(0.5f, weighted[BodyArea.ELBOW], "elbow is loaded, shown as involved")
        val rotation = ExerciseBodyAreas.of(catalog["antagonist.band_external_rotation"]!!)
        assertEquals(1f, rotation[BodyArea.ROTATOR_CUFF])
        assertEquals(0.5f, rotation[BodyArea.SHOULDER_REAR])
    }

    @Test
    fun `custom exercises without muscles fall back to slug and category`() {
        assertEquals(1f, ExerciseBodyAreas.of(def(slug = "pull.my_ring_rows", category = ExerciseCategoryV2.PULL))[BodyArea.UPPER_BACK])
        assertEquals(1f, ExerciseBodyAreas.of(def(slug = "legs.box_jumps", category = ExerciseCategoryV2.LEGS))[BodyArea.QUADS])
        assertEquals(1f, ExerciseBodyAreas.of(def(slug = "core.something", category = ExerciseCategoryV2.CORE))[BodyArea.ABS])
    }

    @Test
    fun `taps hit the drawn region and know the body side`() {
        assertEquals(BodyArea.ABS to null, BodyMapGeometry.regionAt(BodyMapSide.FRONT, 50f, 78f))
        // Front view: the viewer's left is the athlete's right.
        assertEquals(BodyArea.SHOULDER_FRONT to Side.RIGHT, BodyMapGeometry.regionAt(BodyMapSide.FRONT, 29f, 45f))
        assertEquals(BodyArea.SHOULDER_FRONT to Side.LEFT, BodyMapGeometry.regionAt(BodyMapSide.FRONT, 71f, 45f))
        // Back view: the viewer's left is the athlete's left.
        assertEquals(BodyArea.SHOULDER_REAR to Side.LEFT, BodyMapGeometry.regionAt(BodyMapSide.BACK, 29f, 45f))
        assertEquals(BodyArea.ROTATOR_CUFF to Side.LEFT, BodyMapGeometry.regionAt(BodyMapSide.BACK, 40f, 50f))
        assertEquals(BodyArea.FINGERS to Side.RIGHT, BodyMapGeometry.regionAt(BodyMapSide.FRONT, 16.5f, 118f))
        assertNull(BodyMapGeometry.regionAt(BodyMapSide.FRONT, 5f, 5f))
    }

    @Test
    fun `canvas points map through the layout of both figures`() {
        val layout = BodyMapGeometry.layout(420f, 460f, listOf(BodyMapSide.FRONT, BodyMapSide.BACK), focus = null, labels = true)
        val front = layout.placements[0]
        val back = layout.placements[1]
        assertEquals(BodyArea.QUADS, BodyMapGeometry.hit(layout, front.dx + 42.5f * layout.scale, front.dy + 128f * layout.scale)?.first)
        assertEquals(BodyArea.HAMSTRINGS, BodyMapGeometry.hit(layout, back.dx + 42.3f * layout.scale, back.dy + 130f * layout.scale)?.first)
    }

    @Test
    fun `every area can be drawn on some side and maps to an injury region`() {
        val drawn = BodyMapGeometry.areasOn(BodyMapSide.FRONT) + BodyMapGeometry.areasOn(BodyMapSide.BACK)
        assertEquals(BodyArea.entries.toSet(), drawn)
        BodyArea.entries.forEach { assertTrue(it.toInjuryRegion() != null, "$it") }
        InjuryRegion.entries.filter { it != InjuryRegion.OTHER }.forEach { r -> assertTrue(r.bodyAreas().isNotEmpty(), "$r") }
        // Choosing a region chip selects its first area; tapping that area must lead back to the same region.
        InjuryRegion.entries.filter { it != InjuryRegion.OTHER && it != InjuryRegion.SKIN }.forEach { r ->
            assertEquals(r, r.bodyAreas().first().toInjuryRegion(), "$r")
        }
    }

    @Test
    fun `thumbnails show the side with the trained areas`() {
        assertEquals(BodyMapSide.BACK, ExerciseThumbs.sideFor(ExerciseBodyAreas.of(catalog["pull.weighted_pull_up"]!!)))
        assertEquals(BodyMapSide.FRONT, ExerciseThumbs.sideFor(ExerciseBodyAreas.of(catalog["push.push_up"]!!)))
    }

    @Test
    fun `grips come from the logged grip or the exercise`() {
        assertEquals(GripType.HALF_CRIMP, gripFor(catalog["finger.one_arm_pickup"]!!, null))
        assertEquals(GripType.POCKET, gripFor(catalog["finger.one_arm_pickup"]!!, Grip.FRONT_TWO))
        assertEquals(GripType.BAR, gripFor(catalog["pull.weighted_pull_up"]!!, null))
        assertNull(gripFor(def(slug = "legs.squat", category = ExerciseCategoryV2.LEGS), null))
    }

    private fun def(
        slug: String = "core.test",
        category: ExerciseCategoryV2 = ExerciseCategoryV2.CORE,
        muscles: List<String> = emptyList(),
    ) = ExerciseDefinition(slug, category, ExerciseKind.REPS, LoadMode.BODYWEIGHT,
        muscles = com.cruxcoach.athlete.catalog.Muscles(primary = muscles))
}
