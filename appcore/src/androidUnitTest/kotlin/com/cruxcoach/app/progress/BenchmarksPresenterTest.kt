package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.BenchmarksScreenModel
import com.cruxcoach.app.ui.BenchmarksScreenState
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Side
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Performance values on iOS run Android's BenchmarksViewModel and the entry dialog's rules
 * (ported): OverridesTest's "a performance value entered by mistake can be deleted" plus the
 * entry forms the owner named ("+20 kg × 5", "5×5 at +20 kg", "32 kg for 10 s on 20 mm, right
 * hand") and the dialog's limits.
 */
class BenchmarksPresenterTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun screen(): Pair<BenchmarksPresenter, BenchmarksScreenModel> {
        val p = BenchmarksPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        return p to BenchmarksScreenModel(p, "en")
    }

    private fun checkTexts(s: BenchmarksScreenState) {
        (s.key + s.others).forEach { row ->
            TrTextCheck.check(row.learning, "learning ${row.slug}")
            TrTextCheck.check(row.loadLabel, "load label ${row.slug}")
            TrTextCheck.check(row.hintSets, "hint sets ${row.slug}")
            TrTextCheck.check(row.hintMax, "hint max ${row.slug}")
            row.sides.forEach { side ->
                TrTextCheck.check(side.prefix, "prefix ${row.slug}")
                TrTextCheck.check(side.summary, "summary ${row.slug}")
                TrTextCheck.check(side.meta, "meta ${row.slug}")
                TrTextCheck.check(side.plausibility, "plausibility ${row.slug}")
            }
        }
        s.grips.forEach { TrTextCheck.check(it.label, "grip") }
    }

    @Test
    fun `a performance value entered by mistake can be deleted`() {
        kit.db {
            service.saveBenchmark(Benchmark("b", "pull.weighted_pull_up", loadKg = 20.0, reps = 5, bodyweightKg = 70.0,
                source = BenchmarkSource.MANUAL, measuredAt = System.currentTimeMillis(), sets = 5))
        }
        val (p, model) = screen()
        kit.until("value") { p.state.value.key.any { it.def.slug == "pull.weighted_pull_up" && it.values.isNotEmpty() } }
        val row = model.currentState.key.single { it.slug == "pull.weighted_pull_up" }
        val side = row.sides.single()
        assertEquals("", side.sideCode)
        assertTrue(side.hasValue)
        assertEquals("b", side.id)
        assertEquals("trbm_meta", side.meta.key)
        assertEquals("trc_badge_known", row.learning.key)
        checkTexts(model.currentState)
        model.deleteValue(side.id)
        kit.until("deleted") { repo.benchmarks("pull.weighted_pull_up").isEmpty() }
        kit.until("row empty") { p.state.value.key.single { it.def.slug == "pull.weighted_pull_up" }.values.isEmpty() }
        assertEquals("trbm_none", model.currentState.key.single { it.slug == "pull.weighted_pull_up" }.sides.single().summary.key)
    }

    @Test
    fun `the key exercises are listed in Android's order even without values`() {
        val (_, model) = screen()
        val s = model.currentState
        assertEquals(KEY_BENCHMARK_SLUGS, s.key.map { it.slug })
        assertTrue(s.others.isEmpty())
        assertFalse(s.needsWeight)
        val pickup = s.key.single { it.slug == "finger.one_arm_pickup" }
        assertTrue(pickup.unilateral)
        assertEquals(listOf("left", "right"), pickup.sides.map { it.sideCode })
        assertEquals("ten", pickup.kindCode)
        assertTrue(pickup.showEdge)
        assertEquals("20", pickup.defaultEdge)
        assertEquals("10", pickup.defaultSeconds)
        assertEquals("HALF_CRIMP", pickup.defaultGrip)
        assertFalse(pickup.loadIsAdded)
        assertTrue(s.key.single { it.slug == "pull.weighted_pull_up" }.loadIsAdded)
        assertEquals("reps", s.key.single { it.slug == "pull.pull_up" }.kindCode)
        checkTexts(s)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `plus 20 kg for 5 reps is a maximum and 5x5 at plus 20 kg are working sets`() {
        val (p, model) = screen()
        val preview = model.preview("pull.weighted_pull_up", false, "", "20", "5", "", "", "", "")
        assertTrue(preview.valid)
        TrTextCheck.check(preview.summary, "preview")
        TrTextCheck.check(preview.planned, "planned")
        TrTextCheck.check(preview.plausibility, "plausibility")
        assertEquals("trbm_planned", preview.planned.key)
        assertTrue(model.saveEntry("pull.weighted_pull_up", false, "", "20", "5", "", "", "", ""))
        kit.until("saved") { repo.benchmarks("pull.weighted_pull_up").size == 1 }
        val max = kit.db { repo.benchmarks("pull.weighted_pull_up").single() }
        assertEquals(20.0, max.loadKg)
        assertEquals(5, max.reps)
        assertNull(max.sets)
        assertEquals(BenchmarkSource.MANUAL, max.source)
        assertEquals(70.0, max.bodyweightKg)
        // Several sets: "5 × 5 at +20 kg".
        assertTrue(model.saveEntry("pull.weighted_pull_up", true, "5", "20", "5", "", "", "", ""))
        kit.until("sets saved") { repo.benchmarks("pull.weighted_pull_up").size == 2 }
        assertEquals(5, kit.db { repo.benchmarks("pull.weighted_pull_up") }.maxBy { it.measuredAt }.sets)
        kit.until("row shows it") { p.state.value.key.single { it.def.slug == "pull.weighted_pull_up" }.values.isNotEmpty() }
        checkTexts(model.currentState)
    }

    @Test
    fun `32 kg for 10 s on 20 mm with the right hand`() {
        val (_, model) = screen()
        assertTrue(model.saveEntry("finger.one_arm_pickup", false, "", "32", "", "10", "20", "HALF_CRIMP", "right"))
        kit.until("saved") { repo.benchmarks("finger.one_arm_pickup").isNotEmpty() }
        val b = kit.db { repo.benchmarks("finger.one_arm_pickup").single() }
        assertEquals(Side.RIGHT, b.side)
        assertEquals(32.0, b.loadKg)
        assertEquals(10.0, b.durationS)
        assertEquals(20.0, b.edgeMm)
        assertEquals(Grip.HALF_CRIMP, b.grip)
        assertNull(b.reps)
        val preview = model.preview("finger.one_arm_pickup", false, "", "32,5", "", "10", "20", "", "left")
        assertTrue(preview.valid, "comma decimals as on Android")
    }

    @Test
    fun `the dialog's limits refuse what Android refuses`() {
        val (_, model) = screen()
        // Reps 1–30 for an estimated one-rep max.
        assertFalse(model.saveEntry("pull.weighted_pull_up", false, "", "20", "0", "", "", "", ""))
        assertFalse(model.saveEntry("pull.weighted_pull_up", false, "", "20", "31", "", "", "", ""))
        // Sets 1–20 when several.
        assertFalse(model.saveEntry("pull.weighted_pull_up", true, "0", "20", "5", "", "", "", ""))
        assertFalse(model.saveEntry("pull.weighted_pull_up", true, "21", "20", "5", "", "", "", ""))
        // Hangs 3–60 s; an external load must be positive.
        assertFalse(model.saveEntry("finger.max_hang", false, "", "10", "", "2", "20", "", ""))
        assertFalse(model.saveEntry("finger.max_hang", false, "", "10", "", "61", "20", "", ""))
        assertFalse(model.saveEntry("finger.one_arm_pickup", false, "", "0", "", "10", "20", "", "right"))
        assertFalse(model.saveEntry("finger.one_arm_pickup", false, "", "", "", "10", "20", "", "right"))
        // Max reps 1–500, holds 1–3600 s.
        assertFalse(model.saveEntry("pull.pull_up", false, "", "", "501", "", "", "", ""))
        assertFalse(model.saveEntry("core.hollow_hold", false, "", "", "", "0.5", "", "", ""))
        assertFalse(model.saveEntry("no.such_exercise", false, "", "20", "5", "", "", "", ""))
        assertFalse(model.preview("pull.weighted_pull_up", false, "", "20", "abc", "", "", "", "").valid)
        // Body-weight-plus without a load is body weight: valid.
        assertTrue(model.preview("finger.max_hang", false, "", "", "", "10", "20", "", "").valid)
        assertTrue(kit.db { repo.allBenchmarks() }.isEmpty())
    }

    @Test
    fun `a one-sided finger injury allows only the healthy hand`() {
        kit.db {
            repo.saveInjury(Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 4, climbingPaused = false,
                startedOn = service.today().toString()))
        }
        val (p, model) = screen()
        kit.until("injury seen") { p.state.value.key.any { it.injury.allowedSide != null } }
        val pickup = model.currentState.key.single { it.slug == "finger.one_arm_pickup" }
        assertEquals("right", pickup.allowedSide)
        assertFalse(model.saveEntry("finger.one_arm_pickup", false, "", "30", "", "10", "20", "", "left"))
        assertTrue(model.saveEntry("finger.one_arm_pickup", false, "", "30", "", "10", "20", "", "right"))
        kit.until("saved") { repo.benchmarks("finger.one_arm_pickup").isNotEmpty() }
        assertEquals(Side.RIGHT, kit.db { repo.benchmarks("finger.one_arm_pickup").single().side })
    }

    @Test
    fun `a test starts a training and says so once`() {
        val (p, model) = screen()
        model.startTest("finger.max_hang")
        kit.until("test started") { p.state.value.testStarted }
        assertTrue(model.currentState.testStarted)
        assertTrue(kit.db { repo.openWorkout() } != null)
        model.consumeTestStarted()
        assertFalse(model.currentState.testStarted)
    }

    @Test
    fun `without a body weight the page asks for one`() {
        kit.db { repo.deleteMeasurement(service.today().toString(), "weight") }
        val (p, model) = screen()
        assertTrue(model.currentState.needsWeight)
        assertFalse(model.logWeight(5.0))
        assertTrue(model.logWeight(71.0))
        kit.until("weight") { p.state.value.bodyweight != null }
        assertFalse(model.currentState.needsWeight)
    }
}
