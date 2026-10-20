package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.ui.LibraryText
import com.cruxcoach.app.ui.RoutineEditorScreenModel
import com.cruxcoach.athlete.logic.Circuits
import com.cruxcoach.athlete.logic.WorkoutTextParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Type a workout" in the editor (Android `WorkoutTextSheet` + `RoutineEditorViewModel.addFromText`, commit
 * 8d98b494f): the text is read off the main thread, "Add" takes what is in the field, typed circuits get keys
 * of their own, and the preview groups a circuit's members under one line. Real database and catalogue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypedWorkoutEditorTest {

    private lateinit var kit: AthleteTestKit
    private lateinit var service: AthleteService
    private val serial = SerialDispatcher().dispatcher
    private val closers = mutableListOf<() -> Unit>()

    @BeforeTest
    fun setUp() {
        kit = AthleteTestKit()
        service = kit.service
        db { service.ensureReady() }
    }

    @AfterTest
    fun tearDown() {
        closers.forEach { runCatching { it() } }
        db { }
        kit.close()
    }

    private fun <T> db(block: suspend () -> T): T = runBlocking { withContext(serial) { block() } }

    private fun <S> StateFlow<S>.await(predicate: (S) -> Boolean): S = runBlocking {
        try {
            withTimeout(CI_WAIT_MS) { first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("state never matched; last: $value", e)
        }
    }

    private fun editor(reader: CoroutineDispatcher = serial): RoutineEditorPresenter =
        RoutineEditorPresenter(service, serial, CoroutineScope(serial), reader).also { p ->
            closers += p::close
            p.load(null, null, null)
            p.state.await { !it.loading && it.catalog.all.isNotEmpty() }
        }

    /** Types [text] and waits until the field's text is read. */
    private fun RoutineEditorPresenter.type(text: String): RoutineEditorState {
        setTypedText(text)
        return state.await { it.typedRead == text && it.typedText == text }
    }

    private val strings: Set<String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "iosApp/CruxCoach/Resources").isDirectory }
        listOf("en", "de").map { lang ->
            File(root, "iosApp/CruxCoach/Resources/$lang.lproj/Localizable.strings").readLines()
                .mapNotNull { Regex("^\"([^\"]+)\"\\s*=").find(it)?.groupValues?.get(1) }.toSet()
        }.reduce { a, b -> a intersect b }
    }

    private fun assertKeysExist(t: LibraryText) {
        if (t.key.isNotEmpty()) {
            val keys = if (t.plural) listOf(t.key + ".one", t.key + ".other") else listOf(t.key)
            keys.forEach { assertTrue(it in strings, "missing string $it") }
        }
        t.parts.forEach { assertKeysExist(it) }
        t.args.forEach { a ->
            a.nested?.let { assertKeysExist(it) }
            if (a.kind == "key") assertTrue(a.text in strings, "missing string ${a.text}")
        }
    }

    @Test
    fun `the typed text is read off the field and cleared with the sheet`() {
        val p = editor()
        val first = p.type("Klimmzüge 5x5")
        assertEquals(listOf("pull.pull_up"), first.typed.lines.map { it.item.slug })
        val second = p.type("Klimmzüge 5x5, Liegestütze 3x15")
        assertEquals(listOf("pull.pull_up", "push.push_up"), second.typed.lines.map { it.item.slug })
        assertEquals(2, RoutineEditorScreenModel(p, "de").currentState.typedLines.size)
        p.clearTyped()
        val cleared = p.state.value
        assertEquals("", cleared.typedText)
        assertNull(cleared.typedRead)
        assertTrue(cleared.typed.lines.isEmpty())
    }

    @Test
    fun `Add takes what is in the field although the read is not done yet`() {
        // A reader that never gets to run: the field's text stays unread, as right after a keystroke.
        val busy = StandardTestDispatcher()
        val p = editor(reader = busy)
        // Through the facade, as the sheet's field and "Add" button call it.
        val model = RoutineEditorScreenModel(p, "de")
        model.setTypedText("Oberkörper\nKlimmzüge 5x5\nDips 3x8")
        assertTrue(model.currentState.typedLines.isEmpty(), "nothing read yet")
        assertNull(p.state.value.typedRead)
        model.applyTyped(" & ")
        val s = p.state.await { it.items.size == 2 }
        assertEquals(listOf("pull.pull_up", "push.dip"), s.items.map { it.item.slug })
        assertEquals("Oberkörper", s.name)
        assertEquals("", s.typedText)
    }

    @Test
    fun `a typed circuit added to an editor that has circuit 1 becomes circuit 2`() {
        val p = editor()
        p.addSlugs(listOf("pull.pull_up", "push.push_up"))
        p.link(p.state.value.items[0].key)
        p.setCircuitRests(1, 30, 90)
        val before = p.state.value.items
        assertEquals(listOf(1, 1), before.map { it.item.circuit })

        p.type("Zirkel 3 Runden: Dips 10, Sit-ups 20, Plank 30s")
        p.applyTyped("de", " & ")
        val s = p.state.await { it.items.size == 5 }
        // Without the offset the typed circuit (key 1) would have run on into the editor's circuit 1.
        assertEquals(listOf(1, 1, 2, 2, 2), s.items.map { it.item.circuit })
        assertEquals(before.map { it.key }, s.items.take(2).map { it.key }, "the existing rows keep their keys")
        assertTrue(s.items.take(2).all { it.item.circuitRestS == 30 && it.item.roundRestS == 90 })
        assertEquals(listOf("push.dip", "core.sit_up", "core.front_plank"), s.items.drop(2).map { it.item.slug })
        assertTrue(s.items.drop(2).all { it.item.sets == 3 })
        assertEquals(3, Circuits.rounds(s.items.map { it.item }, 2))
        // The editor shows two circuit cards.
        val ui = RoutineEditorScreenModel(p, "de").currentState
        assertEquals(listOf(true, false, true, false, false), ui.items.map { it.startsCircuit })

        // A second typed circuit is a third one, after the others.
        p.type("Supersatz 4 Sätze: Klimmzüge 6 und Dips 10")
        p.applyTyped("de", " & ")
        val more = p.state.await { it.items.size == 7 }
        assertEquals(listOf(1, 1, 2, 2, 2, 3, 3), more.items.map { it.item.circuit })
        assertTrue(more.items.drop(5).all { it.item.sets == 4 })
    }

    @Test
    fun `typed circuits in an editor without circuits keep their own keys`() {
        val p = editor()
        p.addSlugs(listOf("core.dead_bug"))
        p.type("Klimmzüge 5x5, dann 5 Runden im Zirkel: Dips 10, Kniebeugen 15")
        p.applyTyped("de", " & ")
        val s = p.state.await { it.items.size == 4 }
        assertEquals(listOf(null, null, 1, 1), s.items.map { it.item.circuit })
    }

    @Test
    fun `the preview groups a circuit under one line with rounds and rests`() {
        val p = editor()
        val read = p.type("Zirkel, 3 Runden:\nKlimmzüge 8\nLiegestütze 15\nSit-ups 20\n\nPlank 3x60s")
        val ui = RoutineEditorScreenModel(p, "de").currentState
        assertEquals(4, ui.typedLines.size)
        assertEquals(listOf(true, true, true, false), ui.typedLines.map { it.inCircuit })
        assertEquals(listOf(true, false, false, false), ui.typedLines.map { it.startsCircuit })
        val header = ui.typedLines[0].circuitHeader
        assertEquals("trcirc_group", header.key)
        val rounds = header.args[0].nested!!
        assertEquals("trcirc_rounds", rounds.key)
        assertTrue(rounds.plural)
        assertEquals(3L, rounds.count)
        assertEquals(3L, rounds.args.single().intValue)
        // No rests in the text: Android's defaults, 15 s between the exercises and the members' longest rest after a round.
        val members = read.typed.lines.take(3).map { it.item }
        assertTrue(members.all { it.circuitRestS == null && it.roundRestS == null }, "$members")
        assertEquals("0:15", header.args[1].text)
        val roundRest = Circuits.defaultRoundRest(members, read.catalog)
        assertTrue(roundRest in Circuits.MIN_ROUND_REST_S..Circuits.MAX_ROUND_REST_S)
        assertEquals("${roundRest / 60}:${(roundRest % 60).toString().padStart(2, '0')}", header.args[2].text)
        assertKeysExist(header)
        assertTrue(ui.typedLines.drop(1).all { it.circuitHeader.key.isEmpty() && it.circuitHeader.literal.isEmpty() })

        // Rests from the text.
        p.type("3 Runden Zirkel: Klimmzüge 8, Liegestütze 15, Sit-ups 20, 1 min Pause nach jeder Runde, 15 s zwischen den Übungen")
        val withRests = RoutineEditorScreenModel(p, "de").currentState.typedLines
        assertTrue(withRests.all { it.inCircuit })
        assertEquals(listOf("0:15", "1:00"), withRests[0].circuitHeader.args.drop(1).map { it.text })
        assertFalse(withRests[1].startsCircuit)
    }

    @Test
    fun `a text longer than the limit is cut like Android's`() {
        val p = editor()
        val long = "Klimmzüge 5x5 ".repeat(WorkoutTextParser.MAX_CHARS)
        p.setTypedText(long)
        val s = p.state.await { it.typedRead != null }
        assertEquals(WorkoutTextParser.MAX_CHARS, s.typedText.length)
        assertEquals(s.typedText, s.typedRead)
    }
}
