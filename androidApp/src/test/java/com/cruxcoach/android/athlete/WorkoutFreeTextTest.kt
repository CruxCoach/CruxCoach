package com.cruxcoach.android.athlete

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.WorkoutTextParser
import com.cruxcoach.athlete.model.SideMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Free text to a structured workout (owner 2026-10-19: "möglichst frei eingeben, ohne eine Übung pro Zeile, auch
 * was zu einem Zirkel gehört frei angeben, Rechtschreibfehler nicht so wichtig, zuverlässig und performant"),
 * against the real catalogue.
 */
class WorkoutFreeTextTest {

    private val catalog = ExerciseCatalog.parse(File("src/main/assets/${ExerciseCatalogStore.ASSET}").readText())

    private fun parse(text: String) = WorkoutTextParser.parse(text, catalog)

    private fun slugs(r: WorkoutTextParser.Result) = r.lines.map { it.item.slug.ifEmpty { "new:" + it.name } }

    @Test
    fun `several exercises on one line need no separators`() {
        val r = parse("Klimmzüge 5x5 +20kg Liegestütze 3x15 Plank 3x60s")
        assertEquals(listOf("pull.weighted_pull_up", "push.push_up", "core.front_plank"), slugs(r))
        assertEquals(20.0, r.lines[0].item.loadKg)
        assertEquals(15, r.lines[1].item.repsMax)
        assertEquals(60, r.lines[2].item.durationS)
    }

    @Test
    fun `numbers may come first`() {
        val r = parse("3x10 Klimmzüge 3x15 Liegestütze 4x30s Plank")
        assertEquals(listOf("pull.pull_up", "push.push_up", "core.front_plank"), slugs(r))
        assertEquals(10, r.lines[0].item.repsMax)
        assertEquals(30, r.lines[2].item.durationS)
        assertEquals(4, r.lines[2].item.sets)
    }

    @Test
    fun `prose with connectors and sentences`() {
        val r = parse("Heute erst Klimmzüge 4x6 mit 10 kg Zusatzgewicht, dann Liegestütze 3x15 und danach Dips 3x8. Zum Schluss Plank 3x45s.")
        assertEquals(listOf("pull.weighted_pull_up", "push.push_up", "push.dip", "core.front_plank"), slugs(r))
        assertEquals(10.0, r.lines[0].item.loadKg)
        assertEquals(6, r.lines[0].item.repsMax)
        assertEquals(45, r.lines[3].item.durationS)
        assertTrue(r.lines.none { it.isNew }, "${r.lines.map { it.name }}")
    }

    @Test
    fun `typos do not matter much`() {
        val r = parse("Klimzüge 5x5, Liegestüzte 3x15, Kniebäugen 3x10 40kg, Planck 3x60s, Hollow hlod 3x30s, Bankdrüken 3x8 30kg")
        assertEquals(listOf("pull.pull_up", "push.push_up", "legs.back_squat", "core.front_plank", "core.hollow_hold",
            "push.dumbbell_bench_press"), slugs(r))
    }

    @Test
    fun `the owner's exercises in one line`() {
        val r = parse("Situps mit Zusatzgewicht 3x15 10kg Kurzhantelschrägbankdrücken 3x10 20kg einarmiger Latzug links und rechts 3x12 25kg")
        assertEquals(listOf("core.weighted_sit_up", "push.dumbbell_incline_press", "pull.single_arm_lat_pulldown"), slugs(r))
        assertEquals(10.0, r.lines[0].item.loadKg)
        assertEquals(20.0, r.lines[1].item.loadKg)
        assertEquals(SideMode.BOTH, r.lines[2].item.sides)
        assertEquals(25.0, r.lines[2].item.loadKg)
    }

    @Test
    fun `and splits two exercises with the same numbers, je shares them along a list`() {
        val two = parse("Klimmzüge und Dips 3x8")
        assertEquals(listOf("pull.pull_up", "push.dip"), slugs(two))
        assertTrue(two.lines.all { it.item.sets == 3 && it.item.repsMax == 8 })
        val list = parse("Klimmzüge, Liegestütze und Dips je 3x8")
        assertEquals(listOf("pull.pull_up", "push.push_up", "push.dip"), slugs(list))
        assertTrue(list.lines.all { it.item.sets == 3 && it.item.repsMax == 8 }, "${list.lines.map { it.item }}")
    }

    @Test
    fun `names one after another split when each is an exercise`() {
        val r = parse("Plank Hollow Hold 3x30s")
        assertEquals(listOf("core.front_plank", "core.hollow_hold"), slugs(r))
        assertTrue(r.lines.all { it.item.durationS == 30 })
    }

    @Test
    fun `a hangboard line with grip and edge words stays one exercise`() {
        val r = parse("Hangboard 20mm Leiste 7/3 x6 halbaufgestellt")
        assertEquals(1, r.lines.size, "${r.lines.map { it.name }}")
        val item = r.lines.single().item
        assertEquals("finger.repeaters", item.slug)
        assertEquals(7, item.workS)
        assertEquals(6, item.repsPerSet)
        assertEquals(20, item.edgeMm)
    }

    @Test
    fun `a circuit header with the exercises on the lines below`() {
        val r = parse("Zirkel, 3 Runden:\nKlimmzüge 8\nLiegestütze 15\nSit-ups 20\n\nPlank 3x60s")
        assertEquals(listOf("pull.pull_up", "push.push_up", "core.sit_up", "core.front_plank"), slugs(r))
        val members = r.lines.take(3)
        assertTrue(members.all { it.circuit == 1 && it.item.sets == 3 }, "${members.map { it.item }}")
        assertEquals(listOf(8, 15, 20), members.map { it.item.repsMax })
        assertNull(r.lines[3].circuit)
    }

    @Test
    fun `an inline circuit with rounds and rests anywhere in the sentence`() {
        val r = parse("3 Runden Zirkel: Klimmzüge 8, Liegestütze 15, Sit-ups 20, 1 min Pause nach jeder Runde, 15 s zwischen den Übungen")
        assertEquals(listOf("pull.pull_up", "push.push_up", "core.sit_up"), slugs(r))
        r.lines.forEach { l ->
            assertEquals(1, l.circuit)
            assertEquals(3, l.item.sets)
            assertEquals(60, l.item.roundRestS)
            assertEquals(15, l.item.circuitRestS)
        }
    }

    @Test
    fun `a circuit named after its exercises`() {
        val r = parse("Klimmzüge 8, Liegestütze 15 und Sit-ups 20 als Zirkel, 4 Durchgänge")
        assertEquals(listOf("pull.pull_up", "push.push_up", "core.sit_up"), slugs(r))
        assertTrue(r.lines.all { it.circuit == 1 && it.item.sets == 4 }, "${r.lines.map { it.item }}")
    }

    @Test
    fun `a circuit made of a list, without rest, and what comes after it`() {
        val r = parse("Zirkel aus Klimmzügen, Dips und Kniebeugen, 4 Runden, ohne Pause zwischen den Übungen. Danach Plank 3x60s")
        assertEquals(listOf("pull.pull_up", "push.dip", "legs.air_squat", "core.front_plank"), slugs(r))
        r.lines.take(3).forEach { l ->
            assertEquals(1, l.circuit)
            assertEquals(4, l.item.sets)
            assertEquals(0, l.item.circuitRestS)
        }
        assertNull(r.lines[3].circuit)
        assertEquals(3, r.lines[3].item.sets)
    }

    @Test
    fun `alternating exercises are a superset`() {
        val r = parse("Bankdrücken 3x10 30kg im Wechsel mit Kurzhantelrudern 3x10 20kg")
        assertEquals(listOf("push.dumbbell_bench_press", "pull.dumbbell_row"), slugs(r))
        assertTrue(r.lines.all { it.circuit == 1 && it.item.sets == 3 })
        val heading = parse("Supersatz 4 Sätze: Klimmzüge 6 und Dips 10")
        assertEquals(listOf("pull.pull_up", "push.dip"), slugs(heading))
        assertTrue(heading.lines.all { it.circuit == 1 && it.item.sets == 4 }, "${heading.lines.map { it.item }}")
        assertEquals(listOf(6, 10), heading.lines.map { it.item.repsMax })
    }

    @Test
    fun `a warm-up heading ends with the next sentence that starts with dann`() {
        val r = parse("Aufwärmen: 5 min Seilspringen, Armkreisen 2x10. Dann Klimmzüge 5x5 und Dips 3x8")
        assertEquals(4, r.lines.size, "${r.lines.map { it.name }}")
        assertTrue(r.lines[0].item.warmup && r.lines[1].item.warmup)
        assertEquals("warmup.arm_circles", r.lines[1].item.slug)
        assertEquals(listOf("pull.pull_up", "push.dip"), slugs(r).drop(2))
        assertTrue(r.lines.drop(2).none { it.item.warmup })
    }

    @Test
    fun `a whole session written freely`() {
        val r = parse(
            """
            Oberkörper Montag
            Aufwärmen: 5 min Seilspringen, Armkreisen 2x10
            Hauptteil: Klimmzüge 5x5 +10kg, Schrägbankdrücken 4x8 22kg
            Zirkel 3 Runden: Face Pulls 15, Dips 10, Hollow Hold 30s, 90 s Pause nach jeder Runde
            Zum Schluss Klimmzüge 2x5
            """.trimIndent()
        )
        assertEquals("Oberkörper Montag", r.title)
        val s = slugs(r)
        assertEquals(listOf("warmup.arm_circles", "pull.weighted_pull_up", "push.dumbbell_incline_press", "antagonist.face_pull",
            "push.dip", "core.hollow_hold", "pull.pull_up"), s.drop(1), "$s")
        assertTrue(r.lines[0].item.warmup && r.lines[1].item.warmup)
        assertTrue(r.lines.subList(4, 7).all { it.circuit == 1 && it.item.sets == 3 && it.item.roundRestS == 90 }, "${r.lines.map { it.item }}")
        assertNull(r.lines[7].circuit)
        assertTrue(r.lines.subList(2, 4).none { it.item.warmup || it.circuit != null })
    }

    @Test
    fun `rounds over a list are a circuit without the word, a rest line under it is the round's rest`() {
        val list = parse("3 Runden:\n- 8 Klimmzüge\n- 12 Liegestütze\n- 30 Sek Hollow Hold\nPause 90 s")
        assertEquals(listOf("pull.pull_up", "push.push_up", "core.hollow_hold"), slugs(list))
        assertTrue(list.lines.all { it.circuit == 1 && it.item.sets == 3 && it.item.roundRestS == 90 }, "${list.lines.map { it.item }}")
        val rounds = parse("warm up 10 min rowing, then 5 rounds of 5 pull-ups, 10 push-ups and 15 squats, rest 1 min between rounds")
        assertTrue(rounds.lines[0].item.warmup && rounds.lines[0].isNew, "rowing is no catalogue exercise here")
        assertEquals(listOf("pull.pull_up", "push.push_up", "legs.air_squat"), slugs(rounds).drop(1))
        assertTrue(rounds.lines.drop(1).all { it.circuit == 1 && it.item.sets == 5 && it.item.roundRestS == 60 && !it.item.warmup })
        val paren = parse("Bouldern 45 min, danach Zirkel: 3x (10 Klimmzüge, 20 Liegestütze, 30 Sit-ups)")
        assertEquals(1, paren.lines[0].item.sets, "one long bout")
        assertTrue(paren.lines.drop(1).all { it.circuit == 1 && it.item.sets == 3 })
    }

    @Test
    fun `mixed styles, dumbbell pairs, labels and sentence warm-ups`() {
        assertEquals(listOf("pull.pull_up", "push.dip"), slugs(parse("Klimmzüge 5x5 und 3x10 Dips")))
        val r = parse("Aufwärmen mit Seilspringen 5 min und Armkreisen. Danach 4x4 Bouldern und am Ende Dehnen 10 min")
        assertEquals("endurance.four_by_four", r.lines[2].item.slug)
        assertTrue(r.lines[0].item.warmup && r.lines[1].item.warmup && !r.lines[2].item.warmup)
        assertEquals(1, r.lines[0].item.sets)
        assertEquals(600, r.lines[3].item.durationS)
        assertEquals(16.0, parse("Bulgarian Split Squats 3x8 pro Seite mit 2x16kg").lines.single().item.loadKg)
        val prose = parse("Heute Oberkörper. Erst 10 min warm machen auf dem Rad, dann Klimmzüge 5x5 mit 15 kg, " +
            "danach Dips 4x8 und zum Schluss Bauch: Hanging Leg Raises 3x10")
        assertTrue(prose.lines[0].item.warmup)
        assertTrue(prose.lines.drop(1).none { it.item.warmup }, "${prose.lines.map { it.name to it.item.warmup }}")
        assertEquals("Hanging Leg Raises", prose.lines.last().name)
    }

    @Test
    fun `a circuit after other exercises in the same sentence holds only its own`() {
        // Review shot 2026-10-19: "…, dann Zirkel 3 Runden: …" pulled the exercises before it into the circuit.
        val r = parse("Klimmzüge 5x5 +10kg Schrägbankdrücken 4x8 22kg, dann Zirkel 3 Runden: Face Pulls 15, Dips 10, Hollow Hold 30s, " +
            "90 s Pause nach jeder Runde")
        assertEquals(listOf("pull.weighted_pull_up", "push.dumbbell_incline_press", "antagonist.face_pull", "push.dip", "core.hollow_hold"), slugs(r))
        assertTrue(r.lines.take(2).all { it.circuit == null }, "${r.lines.map { it.item }}")
        assertTrue(r.lines.drop(2).all { it.circuit == 1 && it.item.sets == 3 && it.item.roundRestS == 90 })
        val inCircuit = parse("Klimmzüge 5x5, dann 5 Runden im Zirkel: Dips 10, Kniebeugen 15")
        assertNull(inCircuit.lines[0].circuit)
        assertTrue(inCircuit.lines.drop(1).all { it.circuit == 1 && it.item.sets == 5 })
        // "im Wechsel mit" pairs its two sides only.
        val pair = parse("Klimmzüge 5x5, Bankdrücken 3x10 30kg im Wechsel mit Kurzhantelrudern 3x10 20kg")
        assertNull(pair.lines[0].circuit)
        assertTrue(pair.lines.drop(1).all { it.circuit == 1 })
    }

    @Test
    fun `existing line-per-exercise text reads as before`() {
        val r = parse("Push Day\nLiegestütze 3x15\nDips 3x10 +10kg")
        assertEquals("Push Day", r.title)
        assertEquals(listOf("push.push_up", "push.dip"), slugs(r))
    }

    @Test
    fun `long texts parse fast`() {
        val line = "Klimzüge 5x5 +10kg Liegestüzte 3x15, Kniebäugen 3x10 40kg und Planck 3x60s. "
        val text = buildString { while (length < WorkoutTextParser.MAX_CHARS - line.length) append(line) }
        parse(text) // the catalogue index is built once
        val runs = 10
        val start = System.nanoTime()
        repeat(runs) { parse(text) }
        val perParse = (System.nanoTime() - start) / runs / 1_000_000
        // Generous bounds: they catch a runaway (quadratic splitting, no index), not a slow CI runner.
        assertTrue(perParse < 1500, "a ${text.length}-character text took $perParse ms")
        // A typical text, as typed: well below one frame of work per keystroke on a desktop JVM.
        val typical = "Klimmzüge 5x5 +20kg, Liegestütze 3x15, Zirkel 3 Runden: Dips 10, Sit-ups 20, Plank 30s"
        parse(typical)
        val t0 = System.nanoTime()
        repeat(50) { parse(typical) }
        val perTypical = (System.nanoTime() - t0) / 50 / 1_000_000
        assertTrue(perTypical < 150, "a typical text took $perTypical ms")
        println("free-text parse: ${text.length} chars $perParse ms, typical $perTypical ms")
        assertEquals(WorkoutTextParser.MAX_LINES, parse(text).lines.size)
    }
}
