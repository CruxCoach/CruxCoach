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
    }

    data class Result(val title: String?, val lines: List<Line>)

    const val MAX_LINES = 30
    const val MAX_CHARS = 3000
    /** Below this a name counts as unknown and becomes a new exercise. */
    const val MATCH_THRESHOLD = 0.72
    private const val LB_IN_KG = 0.45359237

    fun parse(text: String, catalog: ExerciseCatalog): Result {
        val segments = mergeNumbersOnly(split(text.take(MAX_CHARS)), catalog)
        var title: String? = null
        var warmupSection = false
        val lines = mutableListOf<Line>()
        for ((index, raw) in segments.withIndex()) {
            if (lines.size >= MAX_LINES) break
            val heading = raw.endsWith(":")
            val segment = raw.trimEnd(':').trim()
            if (segment.isEmpty()) continue
            val numbers = Numbers.extract(segment)
            if (heading && !numbers.any) {
                val isWarmup = WARMUP_WORDS.containsMatchIn(segment.lowercase())
                if (isWarmup) warmupSection = true
                else {
                    warmupSection = false
                    if (title == null && lines.isEmpty()) title = segment
                }
                continue
            }
            val whole = ExerciseMatcher.wholeMatch(catalog, segment)
            // Only numbers and nothing before them to belong to: nothing to add.
            if (numbers.rest.isBlank() && whole == null) continue
            val name = if (whole != null) segment else numbers.rest
            val match = whole ?: ExerciseMatcher.best(catalog, name, numbers)
            // A first line without numbers that names no exercise is the workout's name.
            if (match == null && !numbers.any && index == 0 && segments.size > 1 && title == null) {
                title = segment
                continue
            }
            val guess = ExerciseGuess.from(name, numbers)
            val item = itemFor(match, guess, numbers, warmup = warmupSection || numbers.warmup)
            lines += Line(segment, capitalize(name), match, guess, item)
        }
        return Result(title?.let(::capitalize), lines)
    }

    /** "Klimmzüge" and "5x5 +20 kg" on the next line are one exercise. */
    private fun mergeNumbersOnly(segments: List<String>, catalog: ExerciseCatalog): List<String> {
        val merged = mutableListOf<String>()
        for (segment in segments) {
            val previous = merged.lastOrNull()
            val numbers = Numbers.extract(segment.trimEnd(':'))
            // "…, Pause 2 min" and a line with nothing but numbers belong to the exercise before.
            val numbersOnly = numbers.any && (numbers.rest.isBlank() || REST_FIRST.containsMatchIn(segment.lowercase())) &&
                ExerciseMatcher.wholeMatch(catalog, segment) == null
            if (numbersOnly && previous != null && !previous.endsWith(":")) merged[merged.lastIndex] = "$previous $segment"
            else merged += segment
        }
        return merged
    }

    /** Lines, ";" and commas that are not decimal commas; list bullets and numbering go. */
    internal fun split(text: String): List<String> =
        text.replace('\r', '\n')
            .split('\n', ';')
            .flatMap { it.split(Regex(",(?!\\d)|(?<!\\d),")) }
            .map { it.trim().replace(Regex("^(?:[-•*·]+|\\d{1,2}[.)])\\s+"), "").trim() }
            .filter { it.isNotEmpty() }

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
            sets = (n.sets ?: base.sets).coerceIn(1, 20),
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
    ) {
        val any: Boolean get() = sets != null || reps != null || seconds != null || loadKg != null || edgeMm != null ||
            restS != null || workS != null || perSet != null || rounds != null

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
            private val LOAD = Regex("$B(?:(plus|minus)\\s+)?([+-])?\\s*$NUM\\s*(kg|kilo|kilogramm|lbs?|pfund)$E")
            private val EDGE = Regex("$B$NUM\\s*mm$E")
            private val DURATION = Regex("$B$NUM\\s*($SEC|$MIN)$E")
            private val BARE = Regex("$B(\\d{1,3})$E")
            private val ADDED = Regex("$B(?:mit\\s+)?(?:zusatzgewicht|zusatz|extra|added|additional|weighted|gewichtet\\w*)$E")
            private val ASSISTED = Regex("$B(?:entlastet|entlastung|assisted|assistiert|unterstützt|unterstuetzt)$E")
            private val UNIT_FRAGMENTS = setOf("m", "mi", "mn", "s", "se", "sek", "k", "kg", "ki", "l", "lb", "w", "wd", "wdh", "r", "re", "rep", "mm")
            private val FILLER = Regex("(?:à|a|je|mit|with|of|at|@|für|fuer|for|und|and|x|ca\\.?|circa|about|bei|an|on|-|/)")
            private val SIDE_LEFT = Regex("$B(?:nur\\s+)?(?:links|left|linke|linker|linken)(?:\\s+(?:hand|arm|bein|seite|side|only))?$E")
            private val SIDE_RIGHT = Regex("$B(?:nur\\s+)?(?:rechts|right|rechte|rechter|rechten)(?:\\s+(?:hand|arm|bein|seite|side|only))?$E")
            private val SIDE_BOTH = Regex("$B(?:pro|je|each|per)\\s+(?:seite|side|arm|hand|bein|leg)$E")
            private val GRIPS = listOf(
                Regex("$B(?:halb[\\s-]*(?:aufgestellt|crimp)|half[\\s-]*crimp|halfcrimp)$E") to Grip.HALF_CRIMP,
                Regex("$B(?:voll[\\s-]*aufgestellt|aufgestellt|full[\\s-]*crimp|fullcrimp)$E") to Grip.FULL_CRIMP,
                Regex("$B(?:3|drei|three)[\\s-]*finger(?:[\\s-]*drag)?|drag$E") to Grip.THREE_FINGER_DRAG,
                Regex("$B(?:offen|offener\\s+hand|offene\\s+hand|hängend|open[\\s-]*hand|open)$E") to Grip.OPEN_HAND,
            )

            fun extract(segment: String): Numbers {
                var t = " " + segment.lowercase().replace('×', 'x').replace('–', '-').replace('—', '-').replace('−', '-') + " "
                /** Reads every match of [regex]; the ones [onMatch] accepts leave the name. */
                fun take(regex: Regex, onMatch: (MatchResult) -> Boolean) {
                    val consumed = regex.findAll(t).toList().filter(onMatch)
                    if (consumed.isEmpty()) return
                    val chars = t.toCharArray()
                    consumed.forEach { m -> for (i in m.range) chars[i] = ' ' }
                    t = chars.concatToString()
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
                take(LOAD) { m ->
                    val v = num(m.groupValues[3]) ?: return@take false
                    val kg = if (m.groupValues[4].startsWith("lb") || m.groupValues[4] == "pfund") v * LB_IN_KG else v
                    val negative = m.groupValues[2] == "-" || m.groupValues[1] == "minus"
                    signed = signed || m.groupValues[2].isNotEmpty() || m.groupValues[1].isNotEmpty()
                    if (load == null) load = if (negative) -kg else kg
                    true
                }
                if (ADDED.containsMatchIn(t)) { signed = true; t = ADDED.replace(t, " ") }
                if (ASSISTED.containsMatchIn(t)) { signed = true; load = load?.let { -abs(it) }; t = ASSISTED.replace(t, " ") }
                take(EDGE) { m -> if (edge == null) edge = num(m.groupValues[1]); true }
                take(INTERVAL) { m ->
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[2].toInt()
                    // "90/90" is a name, not a rhythm.
                    (a in 1..60 && b in 1..60 && workS == null).also { ok -> if (ok) { workS = a; restBetween = b } }
                }
                take(SETS_X) { m ->
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
                take(SETS_WORD) { m ->
                    val v = m.groupValues[1].toInt()
                    if (m.groupValues[2].startsWith("r")) { if (rounds == null) rounds = v } else if (sets == null) sets = v
                    true
                }
                take(REPS_WORD) { m ->
                    if (reps == null) {
                        val r = m.groupValues[1].toInt()
                        reps = r
                        repsMax = m.groupValues[2].toIntOrNull()?.takeIf { it > r }
                    }
                    true
                }
                take(LEADING_X) { m -> if (sets == null) sets = m.groupValues[1].toInt(); true }
                take(TRAILING_X) { m ->
                    val v = m.groupValues[1].toInt()
                    // "7/3 x6": repeats per set; otherwise reps.
                    if (workS != null) { if (perSet == null) perSet = v } else if (reps == null) reps = v
                    true
                }
                take(DURATION) { m -> if (seconds == null) num(m.groupValues[1])?.let { seconds = secondsOf(it, m.groupValues[2]) }; true }
                GRIPS.forEach { (regex, g) -> if (grip == null && regex.containsMatchIn(t)) grip = g }
                if (SIDE_BOTH.containsMatchIn(t)) { sides = SideMode.BOTH; unilateral = true; t = SIDE_BOTH.replace(t, " ") }
                if (SIDE_LEFT.containsMatchIn(t)) { sides = SideMode.LEFT_ONLY; unilateral = true; t = SIDE_LEFT.replace(t, " ") }
                else if (SIDE_RIGHT.containsMatchIn(t)) { sides = SideMode.RIGHT_ONLY; unilateral = true; t = SIDE_RIGHT.replace(t, " ") }
                val warmup = WARMUP_WORDS.containsMatchIn(t)
                // A count left on its own: "Klimmzüge 8", "Plank 60".
                take(BARE) { m -> if (reps == null && seconds == null) reps = m.groupValues[1].toInt(); true }
                var rest = t.replace(Regex("[^\\p{L}\\p{N}'/&()-]+"), " ").replace(Regex("\\s+"), " ").trim()
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
                    grip, sides, unilateral, warmup, restoreCase(segment, rest))
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

    internal val WARMUP_WORDS = Regex("(?<![\\p{L}])(?:aufw(?:ä|ae|a)rm\\p{L}*|einw(?:ä|ae|a)rm\\p{L}*|warm[\\s-]?ups?)(?![\\p{L}])")
}

/**
 * Finds the catalogue exercise a typed name means: names and aliases in every
 * language, folded (umlauts, ß, ae/oe/ue, punctuation) and with plural
 * endings cut, then scored by shared words, containment and edit distance.
 * Numbers in the text break ties: a load prefers loaded work, seconds prefer
 * holds ("Klimmzüge +20 kg" is the weighted pull-up, "Kniebeuge 60 kg" the
 * barbell squat).
 */
object ExerciseMatcher {

    /** Generic words that mean one exercise, by whether a load was given. */
    private class Generic(val words: Set<String>, val loaded: String, val unloaded: String)

    private val GENERICS = listOf(
        Generic(setOf("hangboard", "fingerboard", "griffbrett", "fingerbrett"), "finger.max_hang", "finger.max_hang"),
        Generic(setOf("squat", "squats", "kniebeuge", "kniebeugen"), "legs.back_squat", "legs.air_squat"),
        Generic(setOf("row", "rows", "rudern"), "pull.dumbbell_row", "pull.inverted_row"),
        Generic(setOf("pullup", "pullups", "pull up", "pull ups", "klimmzug", "klimmzuge", "klimmzuege"), "pull.weighted_pull_up", "pull.pull_up"),
        Generic(setOf("lunge", "lunges", "ausfallschritt", "ausfallschritte"), "legs.reverse_lunge", "legs.reverse_lunge"),
        Generic(setOf("plank", "planks"), "core.front_plank", "core.front_plank"),
        Generic(setOf("campus", "campusboard", "campus board"), "power.campus_ladders", "power.campus_ladders"),
    )

    /** A whole line that is a catalogue name ("4x4", "90/90 Hip Switches"), before numbers are read out of it. */
    fun wholeMatch(catalog: ExerciseCatalog, segment: String): ExerciseDefinition? {
        val compact = tokens(segment).joinToString("")
        if (compact.isEmpty()) return null
        return catalog.all.firstOrNull { def -> texts(def).any { tokens(it).joinToString("") == compact } }
    }

    fun best(catalog: ExerciseCatalog, name: String, numbers: WorkoutTextParser.Numbers? = null): ExerciseDefinition? {
        if (catalog.all.isEmpty()) return null
        val query = tokens(name)
        if (query.isEmpty()) return null
        val loaded = numbers?.loadKg?.let { abs(it) > 0 } == true || numbers?.signedLoad == true
        val compactQuery = query.joinToString(" ")
        GENERICS.firstOrNull { g -> g.words.any { tokens(it).joinToString(" ") == compactQuery } }?.let { g ->
            catalog[if (loaded) g.loaded else g.unloaded]?.let { return it }
        }
        var bestDef: ExerciseDefinition? = null
        var bestScore = 0.0
        // One short word ("Push", "Pull", "Legs") names a day, not the push-up: only an exact name counts.
        val shortWord = query.size == 1 && query.single().length <= 4
        for (def in catalog.all) {
            val base = texts(def).maxOf { score(query, tokens(it)) }
            if (base < 0.55 || (shortWord && base < 1.0)) continue
            val total = base + fit(def, numbers, loaded)
            if (total > bestScore) { bestScore = total; bestDef = def }
        }
        return bestDef?.takeIf { bestScore >= WorkoutTextParser.MATCH_THRESHOLD }
    }

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
    internal fun score(query: List<String>, candidate: List<String>): Double {
        val cand = candidate.filterNot { it.all(Char::isDigit) }.ifEmpty { candidate }
        if (cand.isEmpty()) return 0.0
        val q = query.joinToString("")
        val c = cand.joinToString("")
        if (q == c) return 1.0
        val matchedQ = query.count { a -> cand.any { b -> same(a, b) } }
        val matchedC = cand.count { b -> query.any { a -> same(a, b) } }
        var s = 0.65 * matchedQ / query.size + 0.35 * matchedC / cand.size
        if (q.length >= 4 && c.length >= 4 && (c.contains(q) || q.contains(c))) {
            s = max(s, 0.55 + 0.3 * min(q.length, c.length) / max(q.length, c.length))
        }
        if (q.length >= 5) {
            val sim = 1.0 - levenshtein(q, c).toDouble() / max(q.length, c.length)
            if (sim >= 0.8) s = max(s, 0.95 * sim)
        }
        return s
    }

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        val longest = max(a.length, b.length)
        if (longest >= 5) return levenshtein(a, b) <= (if (longest >= 8) 2 else 1)
        return false
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

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
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
