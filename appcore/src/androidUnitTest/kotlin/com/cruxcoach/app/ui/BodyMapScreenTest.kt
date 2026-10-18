package com.cruxcoach.app.ui

import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.app.bodymap.BodyArea
import com.cruxcoach.app.progress.InjuriesPresenter
import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Swift face of the body map: the shapes SwiftUI draws, Android's layout and hit test through
 * the facade, the exercise page's areas, tap text and grip (Android `VisualHeader`), and the
 * injury form's tap-to-pick (Android `AddInjuryDialog`'s `BodyMapPicker`).
 */
class BodyMapScreenTest {

    private val catalog = ExerciseCatalog.parse(AthleteTestKit.catalogJson)
    private val kit = BodyMapKit.KIT

    private fun visuals(slug: String) = ExerciseVisualsModel(MutableStateFlow(catalog), slug, {}).currentState

    @Test
    fun `both figures list every area with a label in both languages`() {
        assertEquals(listOf("front", "back"), kit.figures.map { it.sideCode })
        val codes = kit.figures.flatMap { f -> f.regions.map { it.areaCode } }.toSet()
        assertEquals(BodyArea.entries.map { it.code }.toSet(), codes)
        codes.forEach { TrTextCheck.check(com.cruxcoach.app.training.Tx.t(kit.areaLabelKey(it)), it) }
        kit.figures.forEach { f ->
            assertEquals(15, f.base.size, "${f.sideCode}: head, neck, torso and six mirrored limb parts")
            (f.base + f.regions.map { it.shape }).forEach { s ->
                when (s.kind) {
                    "ellipse" -> assertEquals(4, s.points.size)
                    "polygon", "smooth" -> assertTrue(s.points.size >= 6 && s.points.size % 2 == 0)
                    else -> error("unexpected kind ${s.kind}")
                }
            }
        }
    }

    @Test
    fun `taps through the facade land on Android's areas and sides`() {
        val w = 420.0
        val h = 460.0
        val layout = kit.layout(w, h, "both", true)
        assertEquals(listOf("front", "back"), layout.placements.map { it.sideCode })
        val front = layout.placements[0]
        val back = layout.placements[1]
        fun at(p: BodyPlacementUi, ux: Double, uy: Double) = kit.hit(w, h, "both", true, p.dx + ux * layout.scale, p.dy + uy * layout.scale)
        val quads = at(front, 42.5, 128.0)
        assertTrue(quads.found)
        assertEquals("quads", quads.areaCode)
        assertEquals("right", quads.bodySideCode, "front view: the viewer's left is the athlete's right")
        assertEquals("hamstrings", at(back, 42.3, 130.0).areaCode)
        assertEquals("left", at(back, 29.0, 45.0).bodySideCode, "back view: the viewer's left is the athlete's left")
        val abs = at(front, 50.0, 78.0)
        assertEquals("abs", abs.areaCode)
        assertEquals("", abs.bodySideCode)
        assertFalse(at(front, 5.0, 5.0).found)
        assertFalse(kit.hit(0.0, 0.0, "both", true, 1.0, 1.0).found)
        // One side alone fills the canvas with that figure.
        assertEquals(listOf("back"), kit.layout(200.0, 400.0, "back", false).placements.map { it.sideCode })
    }

    @Test
    fun `the exercise page shows what a pull-up trains, without a grip`() {
        val s = visuals("pull.weighted_pull_up")
        assertTrue(s.found)
        val byCode = s.areas.associate { it.areaCode to it.value }
        assertEquals(1.0, byCode["lats"])
        assertEquals(1.0, byCode["biceps"])
        assertEquals(0.5, byCode["elbow"])
        assertEquals(1.0, s.areas.first { it.areaCode == "lats" }.mix)
        assertEquals(0.35, s.areas.first { it.areaCode == "elbow" }.mix, 1e-6)
        // Android shows the grip for finger work and hangs only, not the bar of a pull-up.
        assertFalse(s.hasGrip)
        assertNull(s.grip)
        assertNotNull(s.pictogram)
        TrTextCheck.check(s.mapText, "map text")
        assertEquals("trb_cd_title", s.mapText.args.first().nested!!.key)
        val parts = s.mapText.args[1].nested!!
        assertEquals(listOf("trb_cd_main", "trb_cd_involved"), parts.args.map { it.nested!!.key })
    }

    @Test
    fun `finger work carries the grip with its edge`() {
        val s = visuals("finger.one_arm_pickup")
        val grip = assertNotNull(s.grip)
        assertTrue(s.hasGrip)
        assertEquals("half_crimp", grip.gripCode)
        assertEquals("trb_grip_half_crimp", grip.labelKey)
        val edge = catalog["finger.one_arm_pickup"]!!.defaults.edgeMm
        assertEquals(edge ?: -1, grip.edgeMm)
        assertEquals(edge != null, grip.showsEdge)
        assertEquals("hand", grip.shapes.last { it.role == "hand" }.role)
        TrTextCheck.check(grip.accessibilityText, "grip")
        TrTextCheck.check(s.mapText, "map")
    }

    @Test
    fun `the tap line says how much the tapped area works`() {
        val model = ExerciseVisualsModel(MutableStateFlow(catalog), "pull.weighted_pull_up", {})
        assertEquals("trb_detail_tap", model.tapText("").key)
        assertEquals("trb_selected_main", model.tapText("lats").key)
        assertEquals("trb_area_lats", model.tapText("lats").args.single().text)
        assertEquals("trb_selected_involved", model.tapText("elbow").key)
        assertEquals("trb_selected_none", model.tapText("calves").key)
        listOf("", "lats", "elbow", "calves").forEach { TrTextCheck.check(model.tapText(it), it) }
    }

    @Test
    fun `the pain sheet's tap names Android's injury region`() {
        assertEquals("ELBOW", kit.injuryRegionCode("forearm_extensors"))
        assertEquals("KNEE", kit.injuryRegionCode("hamstrings"))
        assertEquals("FINGER", kit.injuryRegionCode("fingers"))
        assertEquals("OTHER", kit.injuryRegionCode("neck"))
        assertEquals("", kit.injuryRegionCode("tail"))
        BodyArea.entries.forEach { assertTrue(kit.injuryRegionCode(it.code).isNotEmpty(), it.code) }
    }

    @Test
    fun `an unknown exercise or an empty catalogue shows no map`() {
        assertFalse(visuals("nope.not_here").found)
        assertFalse(ExerciseVisualsModel(MutableStateFlow(ExerciseCatalog.EMPTY), "pull.weighted_pull_up", {}).currentState.found)
    }

    @Test
    fun `tapping the body picks the injury region, side and detail as Android's dialog does`() {
        val training = TrainingKit()
        try {
            training.standardAthlete()
            val model = InjuriesScreenModel(InjuriesPresenter(training.service, training.scope(), training.dispatcher))
            // A forearm tap is the elbow, on the athlete's side, with "forearm flexors" for the detail.
            val forearm = assertNotNull(model.pickArea("forearm_flexors", "left"))
            assertEquals("ELBOW", forearm.regionCode)
            assertEquals("LEFT", forearm.sideCode)
            assertEquals("trb_area_forearm_flexors", forearm.detailKey)
            assertTrue(forearm.pausesClimbing)
            // An area of the region itself leaves the detail alone.
            val fingers = assertNotNull(model.pickArea("fingers", "right"))
            assertEquals("FINGER", fingers.regionCode)
            assertEquals("RIGHT", fingers.sideCode)
            assertEquals("", fingers.detailKey)
            // The back is no limb: a side is never set from the tap.
            val lats = assertNotNull(model.pickArea("lats", "left"))
            assertEquals("BACK", lats.regionCode)
            assertEquals("", lats.sideCode)
            assertFalse(lats.pausesClimbing)
            assertEquals("trb_area_lats", lats.detailKey)
            // The midline keeps the side.
            assertEquals("", model.pickArea("abs", "")!!.sideCode)
            assertEquals("OTHER", model.pickArea("abs", "")!!.regionCode)
            assertNull(model.pickArea("tail", "left"))
            // A region chip selects its first area, which leads back to the same region.
            assertEquals("fingers", model.firstAreaOf("FINGER"))
            assertEquals("knee", model.firstAreaOf("KNEE"))
            assertEquals("", model.firstAreaOf("OTHER"))
            assertEquals("", model.firstAreaOf("NOPE"))
            listOf("WRIST", "ELBOW", "SHOULDER", "BACK", "HIP", "KNEE", "ANKLE").forEach { r ->
                assertEquals(r, model.pickArea(model.firstAreaOf(r), "")!!.regionCode, r)
            }
            model.close()
        } finally {
            training.close()
        }
    }
}
