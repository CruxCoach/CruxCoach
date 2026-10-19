package com.cruxcoach.android.athlete

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.ExerciseGuess
import com.cruxcoach.athlete.logic.WorkoutTextParser
import com.cruxcoach.athlete.model.SideMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Typed workouts against the real catalogue (owner 2026-10-15: "create
 * workouts and new exercises by typing, like the food entry").
 */
class WorkoutTextParserTest {

    private val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())

    private fun one(text: String): WorkoutTextParser.Line = WorkoutTextParser.parse(text, catalog).lines.single()

    @Test
    fun `sets reps and an added load make the weighted pull-up`() {
        val line = one("Klimmzüge 5x5 +20 kg")
        assertEquals("pull.weighted_pull_up", line.item.slug)
        assertEquals(5, line.item.sets)
        assertEquals(5, line.item.repsMin)
        assertEquals(5, line.item.repsMax)
        assertEquals(20.0, line.item.loadKg)
        assertEquals("Klimmzüge", line.name)
    }

    @Test
    fun `without a load it is the plain pull-up, typos included`() {
        assertEquals("pull.pull_up", one("Klimmzüge 3x8").item.slug)
        assertEquals("pull.pull_up", one("Klimzüge 3x8").item.slug)
        assertEquals("pull.pull_up", one("Klimmzuege 3 x 8").item.slug)
        assertEquals(8, one("Klimmzüge 3x8").item.repsMax)
    }

    @Test
    fun `German sentence with sets words, a rep range, a load and a rest`() {
        val line = one("3 Sätze à 8-12 Wdh. Bankdrücken mit 60 kg, Pause 2 min")
        assertEquals("push.dumbbell_bench_press", line.item.slug)
        assertEquals(3, line.item.sets)
        assertEquals(8, line.item.repsMin)
        assertEquals(12, line.item.repsMax)
        assertEquals(60.0, line.item.loadKg)
        assertEquals(120, line.item.restS)
    }

    @Test
    fun `holds take seconds, also without a unit`() {
        assertEquals(60, one("Plank 3x60s").item.durationS)
        assertEquals("core.front_plank", one("Plank 3x60s").item.slug)
        assertEquals(45, one("Unterarmstütz 3x45").item.durationS)
        assertEquals(90, one("Plank 1,5 min").item.durationS)
    }

    @Test
    fun `hangboard work reads edge, time and load`() {
        val line = one("Max Hangs 20mm 5x10s +10kg")
        assertEquals("finger.max_hang", line.item.slug)
        assertEquals(5, line.item.sets)
        assertEquals(10, line.item.durationS)
        assertEquals(20, line.item.edgeMm)
        assertEquals(10.0, line.item.loadKg)
        assertEquals("finger.max_hang", one("Hangboard 18mm 6x7s +5 kg").item.slug)
    }

    @Test
    fun `repeaters read the rhythm and the repeats per set`() {
        val line = one("Repeaters 20mm 7/3 x6, 6 Sätze")
        assertEquals("finger.repeaters", line.item.slug)
        assertEquals(6, line.item.sets)
        assertEquals(7, line.item.workS)
        assertEquals(3, line.item.restBetweenS)
        assertEquals(6, line.item.repsPerSet)
        assertEquals(20, line.item.edgeMm)
    }

    @Test
    fun `a load picks the barbell squat, no load the air squat`() {
        assertEquals("legs.back_squat", one("Kniebeuge 5x5 60kg").item.slug)
        assertEquals("legs.air_squat", one("Kniebeugen 3x20").item.slug)
        assertEquals("legs.conventional_deadlift", one("Kreuzheben 5x3 100 kg").item.slug)
    }

    @Test
    fun `English names and pounds`() {
        assertEquals("pull.weighted_pull_up", one("Pull-ups 5x5 +20kg").item.slug)
        assertEquals("push.push_up", one("Push-ups 3x15").item.slug)
        val bench = one("Bench press 3x5 135 lb")
        assertEquals("push.dumbbell_bench_press", bench.item.slug)
        assertEquals(61.2, bench.item.loadKg!!, 0.1)
    }

    @Test
    fun `a first line without numbers names the workout`() {
        val r = WorkoutTextParser.parse("Push Day\nLiegestütze 3x15\nDips 3x10 +10kg", catalog)
        assertEquals("Push Day", r.title)
        assertEquals(listOf("push.push_up", "push.dip"), r.lines.map { it.item.slug })
        assertEquals(15, r.lines[0].item.repsMax)
        assertEquals(10.0, r.lines[1].item.loadKg)
    }

    @Test
    fun `a short day name is a title, not the push-up`() {
        val r = WorkoutTextParser.parse("Push\nLiegestütze 3x15\nDips 3x8", catalog)
        assertEquals("Push", r.title)
        assertEquals(listOf("push.push_up", "push.dip"), r.lines.map { it.item.slug })
        assertEquals("push.dip", one("Dips 3x8").item.slug)
    }

    @Test
    fun `a rest after a comma and cut-off units stay with the exercise`() {
        // Device test 2026-10-16: "Bankdruecken 3x8, Pause 2 mi" made a new exercise "Mi".
        val r = WorkoutTextParser.parse("Bankdruecken 3x8, Pause 2 mi\nPlank 3x60s", catalog)
        assertEquals(listOf("push.dumbbell_bench_press", "core.front_plank"), r.lines.map { it.item.slug })
        assertEquals(120, r.lines[0].item.restS)
        assertEquals("core.front_plank", one("Plank 3x60 se").item.slug)
        assertEquals(60, one("Plank 3x60 se").item.durationS)
    }

    @Test
    fun `a warm-up heading marks the lines below it`() {
        val r = WorkoutTextParser.parse("Aufwärmen:\nArmkreisen 2x10\nHauptteil:\nKlimmzüge 3x5", catalog)
        assertNull(r.title)
        assertEquals("warmup.arm_circles", r.lines[0].item.slug)
        assertTrue(r.lines[0].item.warmup)
        assertEquals("pull.pull_up", r.lines[1].item.slug)
        assertTrue(!r.lines[1].item.warmup)
    }

    @Test
    fun `numbers on the next line belong to the exercise above`() {
        val r = WorkoutTextParser.parse("Klimmzüge\n5x5 +17,5 kg\n- Liegestütze 3x15\n• Dips 3x10", catalog)
        assertEquals(listOf("pull.weighted_pull_up", "push.push_up", "push.dip"), r.lines.map { it.item.slug })
        assertEquals(17.5, r.lines[0].item.loadKg)
    }

    @Test
    fun `one side and the one-arm lift`() {
        val line = one("Blockheben 20mm 5x10s 25kg rechts")
        assertEquals("finger.one_arm_pickup", line.item.slug)
        assertEquals(SideMode.RIGHT_ONLY, line.item.sides)
        assertEquals(25.0, line.item.loadKg)
        assertEquals("finger.two_arm_pickup", one("Blockheben 20mm 5x10s 40kg").item.slug)
    }

    @Test
    fun `assisted pull-ups keep the minus`() {
        assertEquals(-15.0, one("Klimmzüge 3x8 -15kg").item.loadKg)
    }

    @Test
    fun `a catalogue name made of numbers stays a name`() {
        assertEquals("endurance.four_by_four", one("4x4").item.slug)
    }

    @Test
    fun `unknown names become new exercises with a guessed kind`() {
        val twists = one("Russian Twists 3x20 8kg")
        assertNull(twists.match)
        assertEquals("", twists.item.slug)
        assertEquals("Russian Twists", twists.name)
        assertEquals(ExerciseKind.LOAD_REPS, twists.guess.kind)
        assertEquals(LoadMode.EXTERNAL, twists.guess.load)
        assertEquals(ExerciseCategoryV2.CORE, twists.guess.category)
        assertEquals(20, twists.item.repsMax)
        assertEquals(8.0, twists.item.loadKg)

        val farmer = one("Farmer Walk 3x40s 24kg")
        assertNull(farmer.match)
        assertEquals(ExerciseKind.TIME, farmer.guess.kind)
        assertEquals(LoadMode.EXTERNAL, farmer.guess.load)
        assertEquals(40, farmer.item.durationS)
        assertEquals(24.0, farmer.item.loadKg)
    }

    @Test
    fun `a name alone guesses the custom exercise form`() {
        ExerciseGuess.fromText("Ringdips mit Zusatzgewicht").let {
            assertEquals(ExerciseCategoryV2.PUSH, it.category)
            assertEquals(ExerciseKind.LOAD_REPS, it.kind)
            assertEquals(LoadMode.BODYWEIGHT_PLUS, it.load)
            assertTrue(EquipmentV2.RINGS in it.equipment)
        }
        ExerciseGuess.fromText("Seitstütz").let {
            assertEquals(ExerciseCategoryV2.CORE, it.category)
            assertEquals(ExerciseKind.TIME, it.kind)
            assertEquals(LoadMode.NONE, it.load)
        }
        ExerciseGuess.fromText("Kettlebell Swings").let {
            assertEquals(ExerciseCategoryV2.LEGS, it.category)
            assertEquals(ExerciseKind.LOAD_REPS, it.kind)
            assertEquals(LoadMode.EXTERNAL, it.load)
        }
        ExerciseGuess.fromText("Einarmiges Blockheben").let {
            assertEquals(ExerciseCategoryV2.FINGER, it.category)
            assertEquals(ExerciseKind.HANG, it.kind)
            assertEquals(LoadMode.EXTERNAL, it.load)
            assertTrue(it.unilateral)
        }
    }

    @Test
    fun `the incline press is the incline press, not the I-Y-T on the incline bench`() {
        // Owner 2026-10-19: "Schrägbankdrücken" became "I-Y-T auf der Schrägbank".
        for (text in listOf("Kurzhantelschrägbankdrücken 3x10 20kg", "Schrägbankdrücken 3x10 20 kg", "Kurzhantel Schrägbankdrücken 3x10 20kg",
            "Incline Dumbbell Press 3x10 45 lbs")) {
            val line = one(text)
            assertEquals("push.dumbbell_incline_press", line.item.slug, text)
            assertTrue(line.item.loadKg!! > 19.0, text)
        }
        assertEquals("antagonist.prone_iyt_bench", one("I-Y-T auf der Schrägbank 3x10 2kg").item.slug)
        // A compound that only starts like a catalogue word is a new exercise, not that one.
        assertTrue(one("Schrägbankrudern 3x10 20kg").match?.slug != "antagonist.prone_iyt_bench")
    }

    @Test
    fun `sit-ups with added weight keep the weight`() {
        val weighted = one("Situps mit Zusatzgewicht 3x15 10kg")
        assertEquals("core.weighted_sit_up", weighted.item.slug)
        assertEquals(10.0, weighted.item.loadKg)
        assertEquals(15, weighted.item.repsMin)
        assertEquals("core.weighted_sit_up", one("Sit-ups 3x12 +5 kg").item.slug)
        assertEquals("core.sit_up", one("Sit-ups 3x20").item.slug)
    }

    @Test
    fun `left and right together mean each side in turn`() {
        // Owner 2026-10-19: "rechts" landed in the new exercise's name and "links" became its only side.
        val line = one("Einarmiger Latzug links und rechts 3x10 30kg")
        assertEquals("pull.single_arm_lat_pulldown", line.item.slug)
        assertEquals(SideMode.BOTH, line.item.sides)
        assertEquals(30.0, line.item.loadKg)
        for (text in listOf("Latzug einarmig rechts und links 3x10", "Einarmiger Latzug li/re 3x10", "Einarmiger Latzug beidseitig 3x10",
            "Einarmiger Latzug beide Seiten 3x10", "Single-arm lat pulldown left and right 3x10")) {
            val l = one(text)
            assertEquals("pull.single_arm_lat_pulldown", l.item.slug, text)
            assertEquals(SideMode.BOTH, l.item.sides, text)
        }
        // A new exercise: the sides go to the setting, not into its name.
        val kickbacks = one("Seilzug-Kickbacks links und rechts 3x12")
        assertNull(kickbacks.match)
        assertEquals("Seilzug-Kickbacks", kickbacks.name)
        assertEquals(SideMode.BOTH, kickbacks.item.sides)
        assertTrue(kickbacks.guess.unilateral)
        // One side alone stays that side.
        assertEquals(SideMode.LEFT_ONLY, one("Einarmiger Latzug links 3x10").item.sides)
    }

    @Test
    fun `empty and number-only text gives nothing`() {
        assertTrue(WorkoutTextParser.parse("", catalog).lines.isEmpty())
        assertTrue(WorkoutTextParser.parse("5x5", catalog).lines.isEmpty())
    }
}
