package com.cruxcoach.android.ui.training

import android.app.Application
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Issue #58: every weight field says what the number refers to, and a weight reads the way it was
 * entered – the same wording on every screen, in English and German, kg and lb.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WeightTextTest : AthleteScreenTest() {

    private fun def(slug: String) = service.catalog[slug] ?: error("missing $slug")

    private val german by lazy {
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.GERMAN) }).resources
    }

    @Test
    fun `every loaded catalogue exercise has a label`() {
        service.catalog.all.filter { it.load == LoadMode.BODYWEIGHT_PLUS || it.load == LoadMode.EXTERNAL }.forEach {
            assertNotNull(it.slug, weightLabelRes(it))
        }
    }

    @Test
    fun `labels name the case in both languages`() {
        val en = context.resources
        fun both(slug: String) = weightLabelRes(def(slug))!!.let { en.getString(it) to german.getString(it) }
        assertEquals("Weight per dumbbell" to "Gewicht pro Hantel", both("push.dumbbell_bench_press"))
        assertEquals("Weight per dumbbell" to "Gewicht pro Hantel", both("antagonist.wrist_curl"))
        assertEquals("Dumbbell weight" to "Gewicht der Hantel", both("legs.goblet_squat"))
        assertEquals("Dumbbell weight" to "Gewicht der Hantel", both("pull.dumbbell_row"))
        assertEquals("Kettlebell weight" to "Gewicht der Kettlebell", both("antagonist.turkish_get_up"))
        assertEquals("Total incl. bar" to "Gesamt inkl. Stange", both("legs.back_squat"))
        assertEquals("Total incl. block" to "Gesamt inkl. Block", both("finger.one_arm_pickup"))
        assertEquals("Total, both hands" to "Gesamt, beide Hände", both("finger.two_arm_pickup"))
        assertEquals("Weight on the stack" to "Gewicht am Kabelzug", both("pull.lat_pulldown"))
        assertEquals("Gauge reading" to "Wert am Kraftmesser", both("finger.isometric_edge_pull"))
        assertEquals("Added weight" to "Zusatzgewicht", both("pull.weighted_pull_up"))
    }

    @Test
    fun `values read the way they were entered`() {
        val en = context.resources
        val bench = def("push.dumbbell_bench_press")
        val pullUp = def("pull.weighted_pull_up")
        assertEquals("2 × 16 kg", weightText(en, bench, 16.0, UnitSystem.METRIC))
        assertEquals("2 × 35.3 lb", weightText(en, bench, 16.0, UnitSystem.IMPERIAL))
        assertEquals("2 × 16 kg", weightText(german, bench, 16.0, UnitSystem.METRIC))
        // A one-rep max of 40 kg on two dumbbells reads 2 × 20 kg.
        assertEquals("2 × 20 kg", weightTextOfTotal(en, bench, 40.0, UnitSystem.METRIC))
        assertEquals("16 kg", weightText(en, def("legs.goblet_squat"), 16.0, UnitSystem.METRIC))
        assertEquals("60 kg", weightText(en, def("legs.back_squat"), 60.0, UnitSystem.METRIC))
        assertEquals("+12 kg", weightText(en, pullUp, 12.0, UnitSystem.METRIC))
        assertEquals("15 kg assisted", weightText(en, pullUp, -15.0, UnitSystem.METRIC))
        assertEquals("15 kg entlastet", weightText(german, pullUp, -15.0, UnitSystem.METRIC))
        assertEquals("Bodyweight", weightText(en, pullUp, 0.0, UnitSystem.METRIC))
    }

    @Test
    fun `a change of a pair says it is per dumbbell`() {
        val bench = def("push.dumbbell_bench_press")
        compose.setContent {
            Text("pair: " + weightDeltaText(bench, 1.0, UnitSystem.METRIC))
            Text("bar: " + weightDeltaText(def("legs.back_squat"), 2.5, UnitSystem.METRIC))
            Text("title: " + weightInputTitle(bench, UnitSystem.METRIC))
        }
        compose.onNodeWithText("pair: +1 kg per dumbbell").assertExists()
        compose.onNodeWithText("bar: +2.5 kg").assertExists()
        compose.onNodeWithText("title: Weight per dumbbell in kg").assertExists()
    }
}
