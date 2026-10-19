package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.catalog.Prescription
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SideMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a typed workout ("Klimmzüge 5×5 +20 kg, Liegestütze 3×15, Plank 3×60 s")
 * into routine items without any model, the way [MealTextParser] does it for
 * meals (owner 2026-10-15: "create workouts and new exercises by typing, like
 * the food entry").
 *
 * One exercise per line, or separated by ";" or ",". Understood in German and
 * English, in any order: sets × reps ("5x5", "3 × 8-12", "3 Sätze à 8 Wdh."),
 * holds ("3x60s", "1 min"), loads ("+20 kg" added, "-15 kg" assisted, "60 kg"
 * lifted, lb converted), edges ("20 mm"), repeaters ("7/3", "x6"), rests
 * ("Pause 2 min"), grips, a side ("links", "pro Seite"), and headings: a first
 * line without numbers names the workout, "Aufwärmen:" marks the lines below
 * it as warm-up. Names match the catalogue in both languages, including
 * aliases, plurals, umlauts written as ae/oe/ue and small typos; a name that
 * matches nothing becomes a new exercise whose kind is guessed from the
 * numbers and the words ([ExerciseGuess]).
 */
object WorkoutTextParser {

    data class Line(
        /** The segment as typed. */
        val source: String,
        /** The exercise name as typed, numbers and units removed. */
        val name: String,
        /** The catalogue exercise it matched; null = a new exercise described by [guess]. */
        val match: ExerciseDefinition?,
        val guess: ExerciseGuess,
        /** Everything the text said; [RoutineItem.slug] is empty for a new exercise. */
        val item: RoutineItem,
    ) {
        val isNew: Boolean get() = match == null
        /** The circuit the exercise belongs to ([RoutineItem.circuit]); null outside one. */
        val circuit: Int? get() = item.circuit
    }

    data class Result(val title: String?, val lines: List<Line>)

    const val MAX_LINES = 30
    const val MAX_CHARS = 3000
    /** Below this a name counts as unknown and becomes a new exercise. */
    const val MATCH_THRESHOLD = 0.72
    /** A duration from here on is one bout unless sets are given. */
    private const val LONG_BOUT_S = 120
    private const val LB_IN_KG = 0.45359237

    fun parse(text: String, catalog: ExerciseCatalog): Result {
        val session = ExerciseMatcher.session(catalog)
        val pieces = WorkoutTextLayout.pieces(text.take(MAX_CHARS), session)
        var title: String? = null
        val lines = mutableListOf<Line>()
        val explicitSets = mutableListOf<Int?>()
        val lineSentence = mutableListOf<Int>()
        val hadNumbers = mutableListOf<Boolean>()
        val drafts = mutableListOf<CircuitDraft>()
        val inCircuit = mutableSetOf<Int>()
        var circuit: CircuitDraft? = null
        var warmupSection = false
        var warmupSentence = -1
        var block = -1
        fun close() {
            circuit?.let { c -> if (c.members.isNotEmpty()) { drafts += c; inCircuit += c.members } }
            circuit = null
        }
        for ((index, piece) in pieces.withIndex()) {
            if (lines.size >= MAX_LINES) break
            if (piece.block != block) { close(); warmupSection = false; block = piece.block }
            val (ended, afterEnd) = WorkoutTextLayout.sectionEnd(piece)
            if (ended) { close(); warmupSection = false }
            // How far an open circuit reaches: its sentence; with "Zirkel:" and exercises on the line, that line;
            // a header alone ("Zirkel, 3 Runden") reaches over the lines below until a blank line or a heading.
            circuit?.let { c ->
                val stillIn = piece.sentence == c.sentence || !c.membersInHeaderSentence || (c.heading && piece.line == c.line)
                if (!stillIn) close()
            }
            var t = afterEnd
            if (NEXT_STEP.containsMatchIn(t.lowercase())) warmupSentence = -1
            if (t.isBlank()) continue
            val words = WorkoutTextLayout.circuit(t, inCircuit = circuit != null, heading = piece.heading)
            val opensCircuit = words.header
            if (opensCircuit) {
                close()
                warmupSection = false
                val c = CircuitDraft(piece.sentence, piece.line, piece.heading, words.rounds, words.circuitRestS, words.roundRestS)
                if (words.retro) {
                    // "A, B und C als Zirkel": the exercises named before in the sentence belong to it.
                    var i = lines.lastIndex
                    while (i >= 0 && lineSentence[i] == piece.sentence && i !in inCircuit && !lines[i].item.warmup) { c.members.add(0, i); i-- }
                    if (c.members.isNotEmpty()) c.membersInHeaderSentence = true
                }
                circuit = c
                t = words.text
            } else circuit?.let { c ->
                if (words.settings) {
                    c.rounds = c.rounds ?: words.rounds
                    c.circuitRestS = c.circuitRestS ?: words.circuitRestS
                    c.roundRestS = c.roundRestS ?: words.roundRestS
                    t = words.text
                }
            }
            if (t.isBlank()) continue
            val pieceNumbers = Numbers.extract(t)
            // "Pause 90 s" on a line of its own: after a circuit list the rest after each round, else the exercise's rest.
            if (pieceNumbers.restS != null && !pieceNumbers.complete && pieceNumbers.loadKg == null && pieceNumbers.rest.isBlank()) {
                val c = circuit
                if (c != null) c.roundRestS = c.roundRestS ?: pieceNumbers.restS
                else if (lines.isNotEmpty()) lines[lines.lastIndex] = lines.last().let { it.copy(item = it.item.copy(restS = pieceNumbers.restS)) }
                continue
            }
            if (piece.heading && !pieceNumbers.any) {
                if (opensCircuit) {
                    // "Zirkel Oberkörper:": the rest of a circuit heading names it at most.
                    if (title == null && lines.isEmpty()) title = t
                    continue
                }
                if (WARMUP_WORDS.containsMatchIn(t.lowercase())) { close(); warmupSection = true }
                else {
                    close()
                    warmupSection = false
                    if (title == null && lines.isEmpty()) title = t
                }
                continue
            }
            // "Zum Aufwärmen 5 min Seilspringen und Armkreisen": the sentence warms up – until "dann", "then", "danach".
            if (WARMUP_WORDS.containsMatchIn(t.lowercase())) warmupSentence = piece.sentence
            for (unit in WorkoutTextLayout.units(t, session)) {
                if (lines.size >= MAX_LINES) break
                if (lines.isNotEmpty() && NEXT_STEP.containsMatchIn(unit.text.lowercase()) && !WARMUP_WORDS.containsMatchIn(unit.text.lowercase())) {
                    warmupSentence = -1
                }
                val numbers = Numbers.extract(unit.text)
                val whole = session.wholeMatch(WorkoutTextLayout.trimGlue(unit.text))
                val rest = afterLabel(WorkoutTextLayout.trimGlue(numbers.rest))
                val primary = unit.primaryName?.let { afterLabel(WorkoutTextLayout.trimGlue(stripWarmup(it))) }?.takeIf { it.isNotBlank() }
                val name = if (whole != null) WorkoutTextLayout.trimGlue(unit.text) else primary ?: rest
                if (name.isBlank()) continue
                val parts = if (whole != null) listOf(name) else splitNames(name, numbers, session)
                // A first line without numbers that names no exercise is the workout's name.
                if (index == 0 && lines.isEmpty() && title == null && !numbers.any && pieces.size > 1 && parts.size == 1 &&
                    !opensCircuit && whole == null && matchFor(name, rest, numbers, session) == null) {
                    title = name
                    continue
                }
                val warm = warmupSection || numbers.warmup || warmupSentence == piece.sentence
                for (part in parts) {
                    if (lines.size >= MAX_LINES) break
                    val match = whole ?: matchFor(part, if (parts.size == 1) rest else part, numbers, session)
                    val guess = ExerciseGuess.from(part, numbers)
                    val item = itemFor(match, guess, numbers, warmup = warm)
                    lines += Line(unit.text, capitalize(part), match, guess, item)
                    explicitSets += numbers.sets
                    lineSentence += piece.sentence
                    hadNumbers += numbers.complete
                    circuit?.let { c ->
                        if (!warm) {
                            c.members += lines.lastIndex
                            if (piece.sentence == c.sentence) c.membersInHeaderSentence = true
                        }
                    }
                }
                // "Klimmzüge, Liegestütze und Dips je 3x8": "je" gives the numbers to the names listed before as well.
                if (numbers.distributed && numbers.complete) {
                    var i = lines.lastIndex - parts.size
                    while (i >= 0 && lineSentence[i] == piece.sentence && !hadNumbers[i]) {
                        val l = lines[i]
                        val updated = itemFor(l.match, l.guess, numbers, warmup = l.item.warmup)
                        lines[i] = l.copy(item = updated.copy(slug = l.item.slug))
                        explicitSets[i] = numbers.sets
                        hadNumbers[i] = true
                        i--
                    }
                }
            }
        }
        close()
        return Result(title?.let(::capitalize), withCircuits(lines, explicitSets, drafts, catalog))
    }

    /** A circuit while it is read; [members] are line indices. */
    private class CircuitDraft(
        val sentence: Int, val line: Int, val heading: Boolean,
        var rounds: Int?, var circuitRestS: Int?, var roundRestS: Int?,
    ) {
        val members = mutableListOf<Int>()
        var membersInHeaderSentence = false
    }

    /** Rounds when the text names none and no exercise says how many sets. */
    const val DEFAULT_ROUNDS = 3

    /**
     * The circuits written into the items: every member gets the circuit key, the rests and, unless it said
     * its own sets, the rounds as sets; warm-ups and climbing stay out; a circuit needs two members.
     */
    private fun withCircuits(lines: List<Line>, explicitSets: List<Int?>, drafts: List<CircuitDraft>, catalog: ExerciseCatalog): List<Line> {
        if (drafts.isEmpty()) return lines
        val out = lines.toMutableList()
        var key = 0
        for (d in drafts) {
            val members = d.members.filter { i ->
                val l = out[i]
                !l.item.warmup && (l.match?.kind ?: l.guess.kind) != ExerciseKind.CLIMB &&
                    (l.match?.category ?: l.guess.category) != ExerciseCategoryV2.WARMUP
            }
            if (members.size < 2) {
                // "3 Runden: Klimmzüge 8": one exercise, the rounds are its sets.
                members.singleOrNull()?.let { i ->
                    d.rounds?.let { r -> if (explicitSets[i] == null) out[i] = out[i].copy(item = out[i].item.copy(sets = r.coerceIn(1, 20))) }
                }
                continue
            }
            key++
            val rounds = d.rounds ?: members.mapNotNull { explicitSets[it] }.maxOrNull() ?: DEFAULT_ROUNDS
            for (i in members) {
                out[i] = out[i].copy(item = out[i].item.copy(
                    sets = (explicitSets[i] ?: rounds).coerceIn(1, 20), circuit = key,
                    circuitRestS = d.circuitRestS, roundRestS = d.roundRestS,
                ))
            }
        }
        val normalized = Circuits.normalize(out.map { it.item }, catalog)
        return out.mapIndexed { i, l -> if (l.item == normalized[i]) l else l.copy(item = normalized[i]) }
    }

    /** The name as written first ([name]); when that names nothing, everything that was not numbers ([rest]). */
    private fun matchFor(name: String, rest: String, numbers: Numbers, session: ExerciseMatcher.Session): ExerciseDefinition? =
        session.best(WorkoutTextLayout.withoutDescriptors(name), numbers)
            ?: rest.takeIf { it.isNotBlank() && it != name }?.let { session.best(WorkoutTextLayout.withoutDescriptors(it), numbers) }

    /** "dann", "then", "danach" at the start: the next step, after the warm-up. */
    private val NEXT_STEP = Regex("^\\s*(?:und\\s+)?(?:dann|danach|anschließend|anschliessend|then|after\\s+that|afterwards|hauptteil|main)(?![\\p{L}])")

    private val CONNECTOR = Regex("\\s+(?:und|and|&|sowie|plus|\\+|dann|then|danach)\\s+", RegexOption.IGNORE_CASE)

    /**
     * One name or several: "Klimmzüge und Dips" are two (with the same numbers, as "je 3x8" says), "Plank Hollow
     * Hold" too when both parts are clearly exercises; a catalogue name stays whole.
     */
    private fun splitNames(name: String, numbers: Numbers, session: ExerciseMatcher.Session): List<String> {
        val words = name.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 2) return listOf(name)
        if (session.scored(WorkoutTextLayout.withoutDescriptors(name), numbers).base >= 0.9) return listOf(name)
        val byConnector = name.split(CONNECTOR).map { WorkoutTextLayout.trimGlue(it) }.filter { it.isNotBlank() }
        if (byConnector.size > 1) {
            val known = byConnector.count { session.best(WorkoutTextLayout.withoutDescriptors(it), numbers) != null }
            if (known >= 1 && byConnector.all { p -> p.count { it.isLetter() } >= 3 }) return byConnector
            return listOf(name)
        }
        if (words.size <= 8) lexicon(words, numbers, session)?.let { return it }
        return listOf(name)
    }

    /** Names written one after another without separators: the split where every part is clearly an exercise. */
    private fun lexicon(words: List<String>, numbers: Numbers, session: ExerciseMatcher.Session): List<String>? {
        val n = words.size
        val best = DoubleArray(n + 1) { -1.0 }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (i in 1..n) for (j in max(0, i - 5) until i) {
            if (best[j] < 0) continue
            val part = words.subList(j, i).joinToString(" ")
            val query = WorkoutTextLayout.withoutDescriptors(part)
            if (WorkoutTextLayout.trimGlue(part).isBlank()) continue
            val sc = session.scored(query, numbers).base
            if (sc < 0.85) continue
            // A little less for every part: one longer name beats two short ones of the same quality.
            val total = best[j] + sc - 0.05
            if (total > best[i]) { best[i] = total; from[i] = j }
        }
        if (best[n] < 0) return null
        val parts = mutableListOf<String>()
        var i = n
        while (i > 0) { val j = from[i]; parts.add(0, words.subList(j, i).joinToString(" ")); i = j }
        return parts.takeIf { it.size >= 2 }
    }

    /** "Bauch: Hanging Leg Raises" → "Hanging Leg Raises": a label before a colon is no part of the name. */
    private fun afterLabel(name: String): String = name.substringAfterLast(':').trim().ifEmpty { name }

    private val WARMUP_ANY_CASE by lazy { Regex(WARMUP_WORDS.pattern, RegexOption.IGNORE_CASE) }

    private fun stripWarmup(name: String): String = WARMUP_ANY_CASE.replace(name, " ").replace(Regex("\\s+"), " ").trim()

    private fun itemFor(match: ExerciseDefinition?, guess: ExerciseGuess, n: Numbers, warmup: Boolean): RoutineItem {
        val kind = match?.kind ?: guess.kind
        val loadMode = match?.load ?: guess.load
        val base = match?.let { WorkoutPlanner.itemFor(it) } ?: RoutineItem(
            slug = "", sets = guess.defaults.sets ?: 3, repsMin = guess.defaults.repsMin, repsMax = guess.defaults.repsMax,
            durationS = guess.defaults.durationS, restS = guess.defaults.restS, edgeMm = guess.defaults.edgeMm,
            workS = guess.defaults.workS, restBetweenS = guess.defaults.restBetweenS,
            repsPerSet = guess.defaults.repsPerSet ?: guess.defaults.rounds,
        )
        // A count without unit is reps for rep work and seconds for holds ("Plank 3x45").
        val seconds: Double? = n.seconds ?: n.reps?.toDouble()?.takeIf { (kind == ExerciseKind.TIME || kind == ExerciseKind.HANG) && n.repsMax == null }
        val reps = n.reps?.takeIf { n.seconds == null || kind == ExerciseKind.REPS || kind == ExerciseKind.LOAD_REPS }
        val loaded = loadMode == LoadMode.BODYWEIGHT_PLUS || loadMode == LoadMode.EXTERNAL
        val load = n.loadKg?.let { kg ->
            when {
                !loaded -> null
                // Lifted weight has no sign; "-10 kg" on a pick-up is not assistance.
                loadMode == LoadMode.EXTERNAL -> abs(kg)
                else -> kg
            }
        }
        return when (kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> base.copy(
                repsMin = reps ?: base.repsMin, repsMax = n.repsMax ?: reps ?: base.repsMax,
            )
            ExerciseKind.TIME, ExerciseKind.HANG -> base.copy(durationS = seconds?.roundToInt() ?: base.durationS)
            ExerciseKind.INTERVAL -> base.copy(
                workS = n.workS ?: base.workS, restBetweenS = n.restBetweenS ?: base.restBetweenS,
                repsPerSet = n.perSet ?: reps ?: base.repsPerSet,
            )
            ExerciseKind.CLIMB -> base.copy(
                repsPerSet = n.rounds ?: reps ?: base.repsPerSet,
                durationS = seconds?.roundToInt() ?: base.durationS,
            )
        }.copy(
            // "Seilspringen 5 min", "Bouldern 30 min": one long bout, not three.
            sets = (n.sets ?: if (n.seconds != null && n.seconds >= LONG_BOUT_S && n.reps == null && n.rounds == null) 1 else base.sets).coerceIn(1, 20),
            loadKg = load ?: base.loadKg,
            restS = n.restS ?: base.restS,
            edgeMm = n.edgeMm?.roundToInt()?.takeIf { kind == ExerciseKind.HANG || kind == ExerciseKind.INTERVAL } ?: base.edgeMm,
            grip = n.grip ?: base.grip,
            sides = n.sides ?: base.sides,
            warmup = warmup,
        )
    }

    private fun capitalize(s: String): String = s.trim().replaceFirstChar { it.uppercase() }

    // ── Numbers ──────────────────────────────────────────────────────

    /** Characters [start] until [end] (exclusive) of a segment were read as numbers or their words. */
    data class Span(val start: Int, val end: Int, val complete: Boolean)

    /** What a segment says besides the name; [rest] is the name part. */
    class Numbers(
        val sets: Int?,
        val reps: Int?,
        val repsMax: Int?,
        val seconds: Double?,
        val loadKg: Double?,
        /** "+20 kg"/"-10 kg"/"Zusatzgewicht": added or assisted, i.e. body-weight work. */
        val signedLoad: Boolean,
        val edgeMm: Double?,
        val restS: Int?,
        val workS: Int?,
        val restBetweenS: Int?,
        val perSet: Int?,
        val rounds: Int?,
        val grip: Grip?,
        val sides: SideMode?,
        val unilateral: Boolean,
        val warmup: Boolean,
        val rest: String,
        /** Where in the segment the numbers and their words stand; [Span.complete] for an amount (sets, reps, time). */
        val spans: List<Span> = emptyList(),
        /** "je", "jeweils", "each": the numbers hold for every exercise named with them. */
        val distributed: Boolean = false,
    ) {
        val any: Boolean get() = sets != null || reps != null || seconds != null || loadKg != null || edgeMm != null ||
            restS != null || workS != null || perSet != null || rounds != null

        /** Says how much to do (sets, reps, time, rounds or a rhythm), not only a load or a rest. */
        val complete: Boolean get() = sets != null || reps != null || seconds != null || rounds != null || workS != null

        companion object {
            private const val B = "(?<![\\p{L}\\p{N}])"
            private const val E = "(?![\\p{L}\\p{N}])"
            private const val NUM = "(\\d+(?:[.,]\\d+)?)"
            private const val SEC = "(?:s|sek|sec|secs|sekunde|sekunden|second|seconds)"
            private const val MIN = "(?:min|mins|minute|minuten|minutes)"

            private val REST1 = Regex("$B(?:pause|rest|ruhe|erholung)\\s*:?\\s*$NUM\\s*($SEC|$MIN)?$E")
            private val REST2 = Regex("$B$NUM\\s*($SEC|$MIN)\\s*(?:pause|rest|ruhe)$E")
            private val INTERVAL = Regex("$B(\\d{1,2})\\s*[/:]\\s*(\\d{1,2})$E")
            private val SETS_X = Regex("$B(\\d{1,2})\\s*x\\s*$NUM(?:\\s*-\\s*(\\d{1,3}))?\\s*($SEC|$MIN|wdh\\.?|wiederholungen|reps?)?$E")
            private val LEADING_X = Regex("$B(\\d{1,2})\\s*x$E")
            private val TRAILING_X = Regex("$B[x×]\\s*(\\d{1,3})$E")
            private val SETS_WORD = Regex("$B(\\d{1,2})\\s*(sätze|saetze|satze|satz|sets?|serien|serie|durchgänge|durchgaenge|durchgang|runden|runde|rounds?)$E")
            private val REPS_WORD = Regex("$B(\\d{1,3})(?:\\s*-\\s*(\\d{1,3}))?\\s*(?:wdh\\.?|wh\\.?|wiederholungen|wiederholung|reps?|repetitions|mal)(?![\\p{L}])")
            /** "2x16 kg": a pair of dumbbells, 16 kg each. */
            private val LOAD_PAIR = Regex("$B([1-4])\\s*x\\s*$NUM\\s*(kg|kilo|kilogramm|lbs?|pfund)$E")
            private val LOAD = Regex("$B(?:(plus|minus)\\s+)?([+-])?\\s*$NUM\\s*(kg|kilo|kilogramm|lbs?|pfund)$E")
            private val EDGE = Regex("$B$NUM\\s*mm$E")
            private val DURATION = Regex("$B$NUM\\s*($SEC|$MIN)$E")
            private val BARE = Regex("$B(\\d{1,3})$E")
            private val ADDED = Regex("$B(?:mit\\s+)?(?:zusatzgewicht|zusatz|extra|added|additional|weighted|gewichtet\\w*)$E")
            private val ASSISTED = Regex("$B(?:entlastet|entlastung|assisted|assistiert|unterstützt|unterstuetzt)$E")
            // "Pause 2 min zwischen den Sätzen", "nach jeder Runde": where the rest goes, not part of a name.
            private val REST_CONTEXT = Regex(
                "$B(?:(?:nach|after)\\s+(?:jedem|jeder|jede|each|every|dem|der|the)?\\s*(?:satz|set|runde|round|übung|uebung|exercise)|" +
                    "zwischen\\s+(?:den\\s+)?(?:sätzen|saetzen|satzen|sets|runden|rounds|übungen|uebungen|exercises)|between\\s+(?:sets|rounds|exercises))$E")
            private val DISTRIBUTED = Regex("$B(?:jeweils|je|each|alle|all|beide|both)$E(?!\\s+(?:seiten?|sides?|arme?|hände|haende|hands?|arms?|beine?|legs?)(?![\\p{L}]))")
            private val UNIT_FRAGMENTS = setOf("m", "mi", "mn", "s", "se", "sek", "k", "kg", "ki", "l", "lb", "w", "wd", "wdh", "r", "re", "rep", "mm")
            private val FILLER = Regex("(?:à|a|je|mit|with|of|at|@|für|fuer|for|und|and|x|ca\\.?|circa|about|bei|an|on|-|/)")
            private val SIDE_LEFT = Regex("$B(?:nur\\s+)?(?:links|left|linke|linker|linken)(?:\\s+(?:hand|arm|bein|seite|side|only))?$E")
            private val SIDE_RIGHT = Regex("$B(?:nur\\s+)?(?:rechts|right|rechte|rechter|rechten)(?:\\s+(?:hand|arm|bein|seite|side|only))?$E")
            private val SIDE_BOTH = Regex("$B(?:pro|je|each|per)\\s+(?:seite|side|arm|hand|bein|leg)$E")
            // "links und rechts", "rechts/links", "li+re", "beidseitig", "beide Seiten": each side in turn (owner 2026-10-19).
            private val SIDE_PAIR = Regex(
                "$B(?:(?:links|li|left)\\s*(?:und|and|&|/|\\+|,)?\\s*(?:rechts|re|right)|(?:rechts|re|right)\\s*(?:und|and|&|/|\\+|,)?\\s*(?:links|li|left)|" +
                    "beidseitig\\p{L}*|beide\\s+seiten|both\\s+sides|jede\\s+seite|each\\s+side)$E")
            private val GRIPS = listOf(
                Regex("$B(?:halb[\\s-]*(?:aufgestellt|crimp)|half[\\s-]*crimp|halfcrimp)$E") to Grip.HALF_CRIMP,
                Regex("$B(?:voll[\\s-]*aufgestellt|aufgestellt|full[\\s-]*crimp|fullcrimp)$E") to Grip.FULL_CRIMP,
                Regex("$B(?:3|drei|three)[\\s-]*finger(?:[\\s-]*drag)?|drag$E") to Grip.THREE_FINGER_DRAG,
                Regex("$B(?:offen|offener\\s+hand|offene\\s+hand|hängend|open[\\s-]*hand|open)$E") to Grip.OPEN_HAND,
            )

            fun extract(segment: String): Numbers {
                val lower = segment.lowercase()
                var t = " " + lower.replace('×', 'x').replace('–', '-').replace('—', '-').replace('−', '-') + " "
                // Positions map back to the segment only when lower-casing kept every character in place.
                val aligned = lower.length == segment.length
                val spans = mutableListOf<Span>()
                fun record(range: IntRange, complete: Boolean) {
                    if (!aligned) return
                    val start = (range.first - 1).coerceIn(0, segment.length)
                    val end = range.last.coerceIn(start, segment.length)
                    if (end > start) spans += Span(start, end, complete)
                }
                /** Reads every match of [regex]; the ones [onMatch] accepts leave the name. */
                fun take(regex: Regex, complete: Boolean = false, onMatch: (MatchResult) -> Boolean) {
                    val consumed = regex.findAll(t).toList().filter(onMatch)
                    if (consumed.isEmpty()) return
                    val chars = t.toCharArray()
                    consumed.forEach { m -> for (i in m.range) chars[i] = ' '; record(m.range, complete) }
                    t = chars.concatToString()
                }
                /** Blanks every match of [regex] (same length, so positions stay); true when there was one. */
                fun blank(regex: Regex): Boolean {
                    var found = false
                    take(regex) { found = true; true }
                    return found
                }
                fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
                fun secondsOf(value: Double, unit: String?) = if (unit != null && unit.startsWith("min")) value * 60 else value

                var sets: Int? = null; var reps: Int? = null; var repsMax: Int? = null; var seconds: Double? = null
                var load: Double? = null; var signed = false; var edge: Double? = null; var restS: Int? = null
                var workS: Int? = null; var restBetween: Int? = null; var perSet: Int? = null; var rounds: Int? = null
                var grip: Grip? = null; var sides: SideMode? = null; var unilateral = false

                take(REST1) { m ->
                    num(m.groupValues[1])?.let { v ->
                        val unit = m.groupValues[2].ifEmpty { null }
                        // "Pause 2" means minutes, "Pause 90" seconds.
                        restS = (if (unit == null) (if (v <= 10) v * 60 else v) else secondsOf(v, unit)).roundToInt()
                    }
                    true
                }
                take(REST2) { m -> num(m.groupValues[1])?.let { restS = secondsOf(it, m.groupValues[2]).roundToInt() }; true }
                take(LOAD_PAIR) { m ->
                    val v = num(m.groupValues[2]) ?: return@take false
                    if (load == null) load = if (m.groupValues[3].startsWith("lb") || m.groupValues[3] == "pfund") v * LB_IN_KG else v
                    true
                }
                take(LOAD) { m ->
                    val v = num(m.groupValues[3]) ?: return@take false
                    val kg = if (m.groupValues[4].startsWith("lb") || m.groupValues[4] == "pfund") v * LB_IN_KG else v
                    val negative = m.groupValues[2] == "-" || m.groupValues[1] == "minus"
                    signed = signed || m.groupValues[2].isNotEmpty() || m.groupValues[1].isNotEmpty()
                    if (load == null) load = if (negative) -kg else kg
                    true
                }
                if (blank(ADDED)) signed = true
                if (blank(ASSISTED)) { signed = true; load = load?.let { -abs(it) } }
                take(EDGE) { m -> if (edge == null) edge = num(m.groupValues[1]); true }
                take(INTERVAL, complete = true) { m ->
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toInt()
                    // "90/90" is a name, not a rhythm.
                    (a in 1..60 && b in 1..60 && workS == null).also { ok -> if (ok) { workS = a; restBetween = b } }
                }
                take(SETS_X, complete = true) { m ->
                    if (sets != null) return@take true
                    sets = m.groupValues[1].toInt()
                    val v = num(m.groupValues[2]) ?: return@take true
                    val unit = m.groupValues[4]
                    if (unit.isNotEmpty() && !unit.startsWith("w") && !unit.startsWith("r")) seconds = secondsOf(v, unit)
                    else {
                        reps = v.roundToInt()
                        repsMax = m.groupValues[3].toIntOrNull()?.takeIf { it > v }
                    }
                    true
                }
                take(SETS_WORD, complete = true) { m ->
                    val v = m.groupValues[1].toInt()
                    if (m.groupValues[2].startsWith("r")) { if (rounds == null) rounds = v } else if (sets == null) sets = v
                    true
                }
                take(REPS_WORD, complete = true) { m ->
                    if (reps == null) {
                        val r = m.groupValues[1].toInt()
                        reps = r
                        repsMax = m.groupValues[2].toIntOrNull()?.takeIf { it > r }
                    }
                    true
                }
                take(LEADING_X, complete = true) { m -> if (sets == null) sets = m.groupValues[1].toInt(); true }
                take(TRAILING_X, complete = true) { m ->
                    val v = m.groupValues[1].toInt()
                    // "7/3 x6": repeats per set; otherwise reps.
                    if (workS != null) { if (perSet == null) perSet = v } else if (reps == null) reps = v
                    true
                }
                take(DURATION, complete = true) { m -> if (seconds == null) num(m.groupValues[1])?.let { seconds = secondsOf(it, m.groupValues[2]) }; true }
                GRIPS.forEach { (regex, g) ->
                    regex.findAll(t).forEach { m -> record(m.range, false) }
                    if (grip == null && regex.containsMatchIn(t)) grip = g
                }
                if (blank(SIDE_PAIR)) { sides = SideMode.BOTH; unilateral = true }
                if (blank(SIDE_BOTH)) { sides = SideMode.BOTH; unilateral = true }
                if (blank(SIDE_LEFT)) { sides = SideMode.LEFT_ONLY; unilateral = true }
                else if (blank(SIDE_RIGHT)) { sides = SideMode.RIGHT_ONLY; unilateral = true }
                if (restS != null) blank(REST_CONTEXT)
                val distributed = blank(DISTRIBUTED)
                val warmup = WARMUP_WORDS.containsMatchIn(t)
                // A count left on its own: "Klimmzüge 8", "Plank 60".
                take(BARE, complete = true) { m -> if (reps == null && seconds == null) reps = m.groupValues[1].toInt(); true }
                var rest = com.cruxcoach.util.replaceNonWordRuns(t, "'/&()-", " ").replace(Regex("\\s+"), " ").trim()
                // Words left over around the numbers: "à", "mit", "x", "je" …
                var words = rest.split(' ').filter { it.isNotEmpty() }
                // ... and cut-off units ("2 mi", "20 k") when the line had numbers.
                fun leftover(w: String) = FILLER.matches(w) || (w.lowercase() in UNIT_FRAGMENTS && words.size > 1)
                while (words.isNotEmpty() && leftover(words.first())) words = words.drop(1)
                while (words.isNotEmpty() && leftover(words.last())) words = words.dropLast(1)
                rest = words.joinToString(" ").trim('-', '/', '(', ')').trim()
                if (warmup) {
                    val stripped = WARMUP_WORDS.replace(rest, " ").replace(Regex("\\s+"), " ").trim()
                    if (stripped.isNotEmpty()) rest = stripped
                }
                return Numbers(sets, reps, repsMax, seconds, load, signed, edge, restS, workS, restBetween, perSet, rounds,
                    grip, sides, unilateral, warmup, restoreCase(segment, rest), spans.sortedBy { it.start }, distributed)
            }

            /** The name as the athlete wrote it, not lower-cased. */
            private fun restoreCase(original: String, lower: String): String {
                if (lower.isEmpty()) return lower
                val start = original.lowercase().indexOf(lower)
                return if (start >= 0) original.substring(start, start + lower.length) else lower
            }
        }
    }

    private val REST_FIRST = Regex("^(?:pause|rest|ruhe|erholung)(?![\\p{L}])")

    internal val WARMUP_WORDS = Regex("(?<![\\p{L}])(?:aufw(?:ä|ae|a)rm\\p{L}*|einw(?:ä|ae|a)rm\\p{L}*|warm[\\s-]?ups?|warm[\\s-]?(?:machen|werden)|" +
        "warmmachen|warmlaufen|einlaufen|einfahren|einrudern)(?![\\p{L}])")
}

/**
 * Finds the catalogue exercise a typed name means: names and aliases in every
 * language, folded (umlauts, ß, ae/oe/ue, punctuation) and with plural
 * endings cut, then scored by shared words, containment and edit distance.
 * Typos count little: a loose spelling key (double letters, ck/k, tz/z, ie/i,
 * ph/f …) and swapped neighbours are tolerated. Numbers in the text break ties:
 * a load prefers loaded work, seconds prefer holds ("Klimmzüge +20 kg" is the
 * weighted pull-up, "Kniebeuge 60 kg" the barbell squat).
 *
 * Fast enough to run on every keystroke of a long text: the catalogue's names
 * are folded once per catalogue ([Index]), a query only scores the names that
 * share letter triples with it, and a [Session] remembers what it already looked up.
 */
object ExerciseMatcher {

    /** Generic words that mean one exercise, by whether a load was given (and a rhythm, for the hangboard). */
    private class Generic(val words: Set<String>, val loaded: String, val unloaded: String, val interval: String? = null)

    private val GENERICS = listOf(
        Generic(setOf("hangboard", "fingerboard", "griffbrett", "fingerbrett"), "finger.max_hang", "finger.max_hang", interval = "finger.repeaters"),
        Generic(setOf("squat", "squats", "kniebeuge", "kniebeugen"), "legs.back_squat", "legs.air_squat"),
        Generic(setOf("row", "rows", "rudern"), "pull.dumbbell_row", "pull.inverted_row"),
        Generic(setOf("pullup", "pullups", "pull up", "pull ups", "klimmzug", "klimmzuge", "klimmzuege"), "pull.weighted_pull_up", "pull.pull_up"),
        Generic(setOf("lunge", "lunges", "ausfallschritt", "ausfallschritte"), "legs.reverse_lunge", "legs.reverse_lunge"),
        Generic(setOf("plank", "planks"), "core.front_plank", "core.front_plank"),
        Generic(setOf("situp", "situps", "sit up", "sit ups"), "core.weighted_sit_up", "core.sit_up"),
        Generic(setOf("campus", "campusboard", "campus board"), "power.campus_ladders", "power.campus_ladders"),
    )

    /** One name or alias of an exercise, folded once. */
    private class Variant(val def: ExerciseDefinition, val tokens: List<String>, val loose: List<String>, val looseCompact: String, val grams: Int)

    /** The catalogue's names, folded and indexed by letter triples. */
    private class Index(val catalog: ExerciseCatalog) {
        val variants: List<Variant>
        /** A whole line that is a name, folded without spaces → the first exercise with it. */
        val byCompact: Map<String, ExerciseDefinition>
        val byGram: Map<String, IntArray>
        /** Generic words, folded loosely → the generic. */
        val generics: Map<String, Generic>

        init {
            val list = mutableListOf<Variant>()
            val compact = HashMap<String, ExerciseDefinition>()
            for (def in catalog.all) {
                for (text in texts(def)) {
                    val tokens = tokens(text)
                    if (tokens.isEmpty()) continue
                    compact.getOrPut(tokens.joinToString("")) { def }
                    val loose = tokens.map(::loosen)
                    val lc = loose.joinToString("")
                    list += Variant(def, tokens, loose, lc, grams(lc).size)
                }
            }
            variants = list
            byCompact = compact
            val postings = HashMap<String, MutableList<Int>>()
            list.forEachIndexed { i, v -> grams(v.looseCompact).forEach { g -> postings.getOrPut(g) { mutableListOf() } += i } }
            byGram = postings.mapValues { (_, ids) -> ids.distinct().toIntArray() }
            generics = GENERICS.flatMap { g -> g.words.map { w -> tokens(w).map(::loosen).joinToString(" ") to g } }.toMap()
        }
    }

    @Volatile private var lastIndex: Index? = null

    private fun indexFor(catalog: ExerciseCatalog): Index =
        lastIndex?.takeIf { it.catalog === catalog } ?: Index(catalog).also { lastIndex = it }

    /**
     * Look-ups for one text: the same name with the same kind of numbers is
     * scored once, however often the parser asks while it splits the text.
     */
    class Session internal constructor(val catalog: ExerciseCatalog) {
        private val index = indexFor(catalog)
        private val memo = HashMap<String, Scored>()

        fun wholeMatch(segment: String): ExerciseDefinition? {
            val compact = tokens(segment).joinToString("")
            return if (compact.isEmpty()) null else index.byCompact[compact]
        }

        /** The match, if the name is close enough – the fit to the numbers breaks ties, it never makes a weak name a match. */
        fun best(name: String, numbers: WorkoutTextParser.Numbers? = null): ExerciseDefinition? {
            val s = scored(name, numbers)
            return s.def?.takeIf { s.score >= WorkoutTextParser.MATCH_THRESHOLD && s.base >= MIN_NAME_SCORE }
        }

        /** The best exercise for [name] and its score (shared words, typos, the fit to the numbers). */
        fun scored(name: String, numbers: WorkoutTextParser.Numbers? = null): Scored {
            val key = name.lowercase() + "|" + signature(numbers)
            memo[key]?.let { return it }
            return compute(name, numbers).also { memo[key] = it }
        }

        private fun compute(name: String, numbers: WorkoutTextParser.Numbers?): Scored {
            if (index.variants.isEmpty()) return Scored.NONE
            val query = tokens(name)
            if (query.isEmpty()) return Scored.NONE
            val loaded = numbers?.loadKg?.let { abs(it) > 0 } == true || numbers?.signedLoad == true
            val looseQuery = query.map(::loosen)
            genericFor(looseQuery.joinToString(" "))?.let { g ->
                val slug = if (numbers?.workS != null && g.interval != null) g.interval else if (loaded) g.loaded else g.unloaded
                catalog[slug]?.let { return Scored(it, 1.0, 1.0) }
            }
            // One short word ("Push", "Pull", "Legs") names a day, not the push-up: only an exact name counts.
            val shortWord = query.size == 1 && query.single().length <= 4
            val qGrams = grams(looseQuery.joinToString(""))
            val counts = HashMap<Int, Int>()
            for (g in qGrams) index.byGram[g]?.forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
            var bestDef: ExerciseDefinition? = null
            var bestScore = 0.0
            var bestBase = 0.0
            var bestId = Int.MAX_VALUE
            for ((id, shared) in counts) {
                val v = index.variants[id]
                // Too few shared letter triples to be the same name, even with a typo.
                if (shared * 10 < 3 * min(qGrams.size, v.grams).coerceAtLeast(1)) continue
                val base = score(query, v.tokens, looseQuery, v.loose)
                if (base < 0.55 || (shortWord && base < 1.0)) continue
                val total = base + fit(v.def, numbers, loaded)
                // Equal scores: the exercise earlier in the catalogue, as before the index.
                if (total > bestScore || (total == bestScore && id < bestId)) {
                    bestScore = total; bestDef = v.def; bestId = id; bestBase = base
                }
            }
            return if (bestDef == null) Scored.NONE else Scored(bestDef, bestScore, bestBase)
        }

        /** A generic word, also with a typo ("Kniebäugen" is still "Kniebeugen"). */
        private fun genericFor(looseQuery: String): Generic? {
            index.generics[looseQuery]?.let { return it }
            if (looseQuery.length < 5) return null
            return index.generics.entries.firstOrNull { (word, _) ->
                abs(word.length - looseQuery.length) <= 1 && damerau(word, looseQuery) <= (if (looseQuery.length >= 8) 2 else 1)
            }?.value
        }

        private fun signature(n: WorkoutTextParser.Numbers?): String {
            if (n == null) return "-"
            val loaded = n.loadKg?.let { abs(it) > 0 } == true || n.signedLoad
            return buildString {
                append(if (loaded) 'L' else 'l'); append(if (n.seconds != null && n.reps == null) 'S' else 's')
                append(if (n.reps != null && n.seconds == null) 'R' else 'r'); append(if (n.workS != null) 'W' else 'w')
                append(if (n.edgeMm != null) 'E' else 'e'); append(if (n.unilateral) 'U' else 'u')
            }
        }
    }

    /** [score] with the fit to the numbers; [base] is the name alone (1.0 = the same name). */
    /** Below this the name alone is too far from any exercise, whatever the numbers say ("rowing" is no forearm massage). */
    const val MIN_NAME_SCORE = 0.66

    data class Scored(val def: ExerciseDefinition?, val score: Double, val base: Double) {
        companion object { val NONE = Scored(null, 0.0, 0.0) }
    }

    fun session(catalog: ExerciseCatalog): Session = Session(catalog)

    /** A whole line that is a catalogue name ("4x4", "90/90 Hip Switches"), before numbers are read out of it. */
    fun wholeMatch(catalog: ExerciseCatalog, segment: String): ExerciseDefinition? = Session(catalog).wholeMatch(segment)

    fun best(catalog: ExerciseCatalog, name: String, numbers: WorkoutTextParser.Numbers? = null): ExerciseDefinition? =
        Session(catalog).best(name, numbers)

    private fun texts(def: ExerciseDefinition): List<String> = def.i18n.values.flatMap { listOf(it.name) + it.aliases }

    /** Small preference for the exercise whose kind fits the numbers. */
    private fun fit(def: ExerciseDefinition, n: WorkoutTextParser.Numbers?, loaded: Boolean): Double {
        if (n == null) return 0.0
        var bonus = 0.0
        val takesLoad = def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL
        if (loaded) bonus += if (takesLoad && def.kind != ExerciseKind.REPS) 0.08 else if (takesLoad) 0.02 else -0.05
        else if (def.kind == ExerciseKind.REPS || def.load == LoadMode.NONE || def.load == LoadMode.BODYWEIGHT) bonus += 0.03
        if (n.seconds != null && n.reps == null) bonus += if (def.kind == ExerciseKind.TIME || def.kind == ExerciseKind.HANG) 0.08 else 0.0
        if (n.reps != null && n.seconds == null) bonus += if (def.kind == ExerciseKind.REPS || def.kind == ExerciseKind.LOAD_REPS) 0.05 else 0.0
        if (n.workS != null) bonus += if (def.kind == ExerciseKind.INTERVAL) 0.1 else 0.0
        if (n.edgeMm != null) bonus += if (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) 0.05 else -0.05
        // "Blockheben rechts" is the one-arm lift, "Blockheben" the two-arm one.
        bonus += if (n.unilateral == def.unilateral) 0.03 else 0.0
        return bonus
    }

    /** 1.0 for the same words; shared words weigh coverage of the query over coverage of the name. */
    internal fun score(query: List<String>, candidate: List<String>): Double =
        score(query, candidate, query.map(::loosen), candidate.map(::loosen))

    private fun score(query: List<String>, candidate: List<String>, looseQ: List<String>, looseC: List<String>): Double {
        val digitsOnly = candidate.map { it.all(Char::isDigit) }
        val keep = if (digitsOnly.all { it }) candidate.indices.toList() else candidate.indices.filter { !digitsOnly[it] }
        if (keep.isEmpty()) return 0.0
        val cand = keep.map { candidate[it] }
        val candLoose = keep.map { looseC[it] }
        val q = query.joinToString("")
        val c = cand.joinToString("")
        if (q == c) return 1.0
        val lq = looseQ.joinToString("")
        val lc = candLoose.joinToString("")
        // The same name spelt a little differently: "Planck", "Klimzüge", "Liegestüze".
        if (lq == lc) return 0.97
        // A word matched only through a typo counts a little less than the same word ("rowing" is not "rolling").
        val matchedQ = query.indices.sumOf { i -> cand.indices.maxOfOrNull { j -> same(query[i], cand[j], looseQ[i], candLoose[j]) } ?: 0.0 }
        val matchedC = cand.indices.sumOf { j -> query.indices.maxOfOrNull { i -> same(query[i], cand[j], looseQ[i], candLoose[j]) } ?: 0.0 }
        var s = 0.65 * matchedQ / query.size + 0.35 * matchedC / cand.size
        if (q.length >= 4 && c.length >= 4 && (c.contains(q) || q.contains(c))) {
            s = max(s, 0.55 + 0.3 * min(q.length, c.length) / max(q.length, c.length))
        }
        if (lq.length >= 5 && abs(lq.length - lc.length) * 5 <= max(lq.length, lc.length)) {
            val sim = 1.0 - damerau(lq, lc).toDouble() / max(lq.length, lc.length)
            // Short words need to be closer: one wrong letter in six is another word as often as a typo.
            if (sim >= (if (max(lq.length, lc.length) < 8) 0.86 else 0.8)) s = max(s, 0.95 * sim)
        }
        return s
    }

    /** 1.0 for the same word (also spelt loosely or with another ending), 0.8 for a typo, 0.0 otherwise. */
    private fun same(a: String, b: String, la: String, lb: String): Double {
        if (a == b || la == lb) return 1.0
        // A word and its ending ("Liegestütz"/"Liegestützen"), but not a compound and its first part: "Schrägbank" is
        // not "Schrägbankdrücken" (owner 2026-10-19: the incline press became "I-Y-T on the incline bench").
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)) && abs(a.length - b.length) <= 2) return 1.0
        val longest = max(la.length, lb.length)
        if (longest >= 5 && abs(la.length - lb.length) <= 2 && damerau(la, lb) <= (if (longest >= 8) 2 else 1)) return 0.8
        return 0.0
    }

    private val STOP = setOf("der", "die", "das", "den", "dem", "mit", "an", "am", "auf", "im", "in", "the", "of", "with", "on", "a", "und", "and")

    internal fun tokens(text: String): List<String> =
        foldText(text).split(' ').filter { it.isNotEmpty() && it !in STOP }.map(::stem)

    /** Plural and inflection endings, so "Klimmzüge" meets "Klimmzug" and "Hangs" meets "Hang". */
    private fun stem(t: String): String = when {
        t.all(Char::isDigit) -> t
        t.length > 5 && (t.endsWith("en") || t.endsWith("er")) -> t.dropLast(2)
        t.length > 4 && (t.endsWith("e") || t.endsWith("n")) -> t.dropLast(1)
        t.length >= 3 && t.endsWith("s") && !t.endsWith("ss") -> t.dropLast(1)
        else -> t
    }

    /**
     * A spelling key that typos rarely change: double letters single, ck/k, tz/z, ph/f, th/t, dt/t, ie/i, y/i,
     * c/k, v/f – "Klimmzüge"/"Klimzüge", "Planck"/"Plank" meet.
     */
    internal fun loosen(t: String): String {
        if (t.all(Char::isDigit)) return t
        val s = t.replace("sch", "\u0001").replace("ck", "k").replace("tz", "z").replace("ph", "f").replace("th", "t")
            .replace("dt", "t").replace("ie", "i").replace('y', 'i').replace("ch", "\u0002").replace('c', 'k')
            .replace('v', 'f').replace("\u0001", "s").replace("\u0002", "h")
        val out = StringBuilder(s.length)
        for (ch in s) if (out.isEmpty() || out[out.length - 1] != ch) out.append(ch)
        return out.toString()
    }

    /** Letter triples of [s] with word boundary marks; a short word is its own triple. */
    private fun grams(s: String): Set<String> {
        if (s.isEmpty()) return emptySet()
        val padded = "^$s$"
        if (padded.length <= 3) return setOf(padded)
        return (0..padded.length - 3).mapTo(HashSet()) { padded.substring(it, it + 3) }
    }

    /** Edit distance where swapping two neighbouring letters counts once ("Liegestüzte"). */
    private fun damerau(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var twoBack = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, twoBack[j - 2] + 1)
                cur[j] = v
            }
            val t = twoBack; twoBack = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}

/** Lower case, umlauts and ß folded (also when written ae/oe/ue), punctuation to spaces. */
internal fun foldText(text: String): String = text.lowercase()
    .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
    .replace("ae", "a").replace("oe", "o").replace("ue", "u")
    .replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * What a new exercise probably is, from its name and the numbers typed with
 * it: "Ringdips +10 kg" are body-weight reps with added load for the push
 * muscles, "Farmer Walk 3x40s 24 kg" a loaded hold, "Seitstütz 3x30s" a hold
 * without load. Also fills in the custom-exercise form while a name is typed.
 */
data class ExerciseGuess(
    val category: ExerciseCategoryV2,
    val kind: ExerciseKind,
    val load: LoadMode,
    val unilateral: Boolean,
    val equipment: List<EquipmentV2>,
    val domains: List<LoadDomain>,
) {
    val defaults: Prescription get() = defaultPrescription(kind)

    companion object {
        fun defaultPrescription(kind: ExerciseKind): Prescription = when (kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> Prescription(sets = 3, repsMin = 8, repsMax = 12, restS = 90)
            ExerciseKind.TIME -> Prescription(sets = 3, durationS = 30, restS = 60)
            ExerciseKind.HANG -> Prescription(sets = 5, durationS = 10, restS = 120, edgeMm = 20)
            ExerciseKind.INTERVAL -> Prescription(sets = 4, workS = 7, restBetweenS = 3, repsPerSet = 6, restS = 180)
            ExerciseKind.CLIMB -> Prescription(rounds = 4, restS = 120)
        }

        fun defaultLoad(kind: ExerciseKind): LoadMode = when (kind) {
            ExerciseKind.REPS -> LoadMode.BODYWEIGHT
            ExerciseKind.LOAD_REPS -> LoadMode.EXTERNAL
            ExerciseKind.TIME -> LoadMode.NONE
            ExerciseKind.HANG, ExerciseKind.INTERVAL -> LoadMode.BODYWEIGHT_PLUS
            ExerciseKind.CLIMB -> LoadMode.NONE
        }

        private fun has(text: String, vararg words: String) = words.any { text.contains(it) }

        private val CLIMB = arrayOf("boulder", "kletter", "climb", "traverse", "quergang", "route", "campus", "dyno", "arc ")
        private val FINGER = arrayOf("hang", "leiste", "edge", "crimp", "finger", "block", "pinch", "zange", "sloper", "aufleger", "griffbrett", "lift off", "pickup", "pick up")
        private val PULL = arrayOf("klimm", "pull", "zug", "zuge", "rudern", "row", "chin", "lat", "lever", "lock off", "bizeps", "biceps", "curl")
        private val PUSH = arrayOf("druck", "press", "push", "liegestutz", "dip", "stutz", "bench", "bank", "trizeps", "triceps", "handstand")
        private val LEGS = arrayOf("knie", "squat", "beuge", "lunge", "ausfall", "bein", "leg", "wade", "calf", "deadlift", "kreuzheb", "thrust", "huft", "glute", "step", "sprung", "jump", "pistol", "swing")
        private val CORE = arrayOf("plank", "bauch", "crunch", "sit up", "situp", "core", "hollow", "hohlkorper", "l sit", "rumpf", "twist", "seitstutz", "unterarmstutz", "rollout", "flag", "russian")
        private val MOBILITY = arrayOf("dehn", "stretch", "mobil", "yoga", "beweglich", "faszien", "foam")
        private val WARMUP = arrayOf("aufwarm", "warm up", "warmup", "einwarm", "jog", "seilspring", "hampel", "jumping jack")
        private val ANTAGONIST = arrayOf("rotation", "face pull", "pull apart", "ytw", "handgelenk", "wrist", "unterarm", "forearm", "schulterblatt", "scap")
        private val EXTERNAL = arrayOf("kurzhantel", "langhantel", "hantel", "dumbbell", "barbell", "kettlebell", "kabel", "cable", "maschine", "machine", "block", "pickup", "pick up", "farmer", "swing", "sandsack")
        private val BODYWEIGHT_PLUS = arrayOf("klimm", "pull up", "pullup", "chin", "dip", "liegestutz", "push up", "pushup", "ausfall", "lunge", "hang", "muscle up")
        private val UNILATERAL = arrayOf("einarm", "einbein", "one arm", "one leg", "single arm", "single leg", "einhand", "one hand", "pro seite", "je seite")

        /** From what the athlete types as a name, numbers and words like "mit Zusatzgewicht" included. */
        fun fromText(text: String): ExerciseGuess {
            val n = WorkoutTextParser.Numbers.extract(text)
            return from(n.rest.ifBlank { text }, n)
        }

        fun from(name: String, n: WorkoutTextParser.Numbers? = null): ExerciseGuess {
            val t = " " + foldText(name) + " "
            val climb = has(t, *CLIMB)
            val finger = has(t, *FINGER) || n?.edgeMm != null
            val category = when {
                has(t, *WARMUP) -> ExerciseCategoryV2.WARMUP
                has(t, *MOBILITY) -> ExerciseCategoryV2.MOBILITY
                climb && has(t, "campus", "dyno", "limit", "power") -> ExerciseCategoryV2.POWER
                climb && has(t, "arc ", "ausdauer", "endurance", "traverse", "quergang", "4x4") -> ExerciseCategoryV2.ENDURANCE
                climb && has(t, "technik", "technique", "drill") -> ExerciseCategoryV2.TECHNIQUE
                finger && !has(t, "bar ", "stange") -> ExerciseCategoryV2.FINGER
                has(t, *CORE) -> ExerciseCategoryV2.CORE
                has(t, *ANTAGONIST) -> ExerciseCategoryV2.ANTAGONIST
                has(t, *LEGS) -> ExerciseCategoryV2.LEGS
                has(t, *PUSH) -> ExerciseCategoryV2.PUSH
                has(t, *PULL) -> ExerciseCategoryV2.PULL
                climb -> ExerciseCategoryV2.POWER
                else -> ExerciseCategoryV2.CORE
            }
            val hasLoad = n?.loadKg?.let { abs(it) > 0 } == true || n?.signedLoad == true
            val timed = n?.seconds != null && n.reps == null
            val kind = when {
                climb -> ExerciseKind.CLIMB
                n?.workS != null -> ExerciseKind.INTERVAL
                (finger && (timed || n?.edgeMm != null)) || (finger && n?.reps == null && category == ExerciseCategoryV2.FINGER) -> ExerciseKind.HANG
                timed || ((category == ExerciseCategoryV2.MOBILITY || has(t, "halte", "hold", "plank", "stutz", "sitz ")) && n?.reps == null) -> ExerciseKind.TIME
                hasLoad || has(t, *EXTERNAL) -> ExerciseKind.LOAD_REPS
                else -> ExerciseKind.REPS
            }
            val external = has(t, *EXTERNAL) || (hasLoad && n?.signedLoad == false && !has(t, *BODYWEIGHT_PLUS))
            val load = when (kind) {
                ExerciseKind.CLIMB -> LoadMode.NONE
                ExerciseKind.INTERVAL -> LoadMode.BODYWEIGHT_PLUS
                ExerciseKind.HANG -> if (external) LoadMode.EXTERNAL else LoadMode.BODYWEIGHT_PLUS
                ExerciseKind.TIME -> if (hasLoad) (if (external || n?.signedLoad == false) LoadMode.EXTERNAL else LoadMode.BODYWEIGHT_PLUS) else LoadMode.NONE
                ExerciseKind.LOAD_REPS -> if (external) LoadMode.EXTERNAL else LoadMode.BODYWEIGHT_PLUS
                ExerciseKind.REPS -> if (has(t, "band", "gummi") || category == ExerciseCategoryV2.MOBILITY || category == ExerciseCategoryV2.WARMUP) LoadMode.NONE else LoadMode.BODYWEIGHT
            }
            val equipment = buildList {
                if (has(t, "hangboard", "fingerboard", "griffbrett", "leiste", "edge")) add(EquipmentV2.HANGBOARD)
                if (has(t, "block", "pickup", "pick up", "lift off")) add(EquipmentV2.PICKUP_BLOCK)
                if (has(t, "klimm", "pull up", "pullup", "chin", "stange", "bar ") && !has(t, "barbell", "langhantel")) add(EquipmentV2.PULL_UP_BAR)
                if (has(t, "ring")) add(EquipmentV2.RINGS)
                if (has(t, "kurzhantel", "dumbbell")) add(EquipmentV2.DUMBBELL)
                if (has(t, "kettlebell")) add(EquipmentV2.KETTLEBELL)
                if (has(t, "langhantel", "barbell")) add(EquipmentV2.BARBELL)
                if (has(t, "band", "gummi")) add(EquipmentV2.BANDS)
                if (has(t, "kabel", "cable")) add(EquipmentV2.CABLE)
                if (has(t, "barren", "dip") && !has(t, "ring", "bank", "bench")) add(EquipmentV2.DIP_BARS)
                if (has(t, " box")) add(EquipmentV2.BOX)
                if (has(t, "campus")) add(EquipmentV2.CAMPUS_BOARD)
                else if (kind == ExerciseKind.CLIMB) add(EquipmentV2.WALL)
            }.distinct()
            val domains = buildList {
                if (category == ExerciseCategoryV2.FINGER || kind == ExerciseKind.CLIMB) add(LoadDomain.FINGER)
                if (category == ExerciseCategoryV2.LEGS) add(LoadDomain.LOWER_BODY)
                if (category == ExerciseCategoryV2.PULL || category == ExerciseCategoryV2.PUSH) add(LoadDomain.SHOULDER)
                if (category == ExerciseCategoryV2.PULL) add(LoadDomain.ELBOW)
            }
            val unilateral = n?.unilateral == true || has(t, *UNILATERAL)
            return ExerciseGuess(category, kind, load, unilateral, equipment, domains)
        }
    }
}
