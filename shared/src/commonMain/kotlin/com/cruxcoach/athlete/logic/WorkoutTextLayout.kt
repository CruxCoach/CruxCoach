package com.cruxcoach.athlete.logic

import kotlin.math.roundToInt

/**
 * How a typed workout is laid out, read without format rules (owner 2026-10-19: "free text to a structured
 * workout – no one-exercise-per-line rule, circuits written freely, typos do not matter"):
 *
 * - blank lines part blocks, lines and sentences ("." "!" "?" ";") part what belongs together;
 * - a colon makes what stands before it a heading ("Aufwärmen:", "Zirkel 3 Runden:", "Push-Tag:");
 * - commas and spaced dashes part a sentence into pieces;
 * - inside one piece the pattern name + numbers ("Klimmzüge 5x5 Liegestütze 3x15") or numbers + name
 *   ("5x5 Klimmzüge 3x15 Liegestütze") parts the exercises ([units]);
 * - circuits are spotted by their words ("Zirkel", "Supersatz", "im Wechsel", "als Zirkel") with their rounds and
 *   rests ([circuit]).
 */
internal object WorkoutTextLayout {

    class Piece(
        val text: String,
        val block: Int,
        val line: Int,
        val sentence: Int,
        /** First piece of its sentence. */
        val sentenceStart: Boolean,
        /** The sentence before ended with a full stop ("." "!" "?"), not only with a line break. */
        val afterStop: Boolean,
        /** Before a colon, or a whole line ending with one. */
        val heading: Boolean,
    )

    private val BULLET = Regex("^(?:[-•*·▪►–—]+|\\d{1,2}[.)])\\s+")
    private val COMMA = Regex(",(?!\\d)|(?<!\\d),")
    private val DASH = Regex("(?<![\\d])\\s+[-–—]\\s+(?![\\d])")
    private val ABBREVIATIONS = setOf("wdh", "wh", "ca", "zb", "z", "b", "bzw", "usw", "etc", "inkl", "max", "mind", "ggf", "evtl",
        "nr", "vs", "approx", "rep", "reps", "u", "o", "a")
    private val REST_WORD = Regex("(?:pause|rest|ruhe|erholung|satzpause)$")

    /** The pieces of [text] in reading order, with what they belong to. */
    fun pieces(text: String, session: ExerciseMatcher.Session): List<Piece> {
        val out = mutableListOf<Piece>()
        var sentence = 0
        var lineNo = 0
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\\n[ \\t]*\\n"))
        for ((block, blockText) in blocks.withIndex()) {
            var afterStop = false
            for (rawLine in blockText.split('\n')) {
                val line = rawLine.trim().replace(BULLET, "").trim()
                lineNo++
                if (line.isEmpty()) continue
                for ((sentenceText, endsWithStop) in sentences(line)) {
                    var first = true
                    for ((partText, heading) in colonParts(sentenceText, session)) {
                        val parts = if (heading) listOf(partText) else splitPieces(partText)
                        for (p in parts) {
                            val clean = p.trim().replace(BULLET, "").trim().trimEnd(':').trim()
                            if (clean.isEmpty()) continue
                            out += Piece(clean, block, lineNo, sentence, first, afterStop && first, heading)
                            first = false
                        }
                    }
                    afterStop = endsWithStop
                    sentence++
                }
            }
        }
        return mergeNumbersOnly(out, session)
    }

    /** "Klimmzüge" and "5x5 +20 kg" on the next line, or "…, Pause 2 min": the numbers belong to the exercise before. */
    private fun mergeNumbersOnly(pieces: List<Piece>, session: ExerciseMatcher.Session): List<Piece> {
        val merged = mutableListOf<Piece>()
        for (p in pieces) {
            val previous = merged.lastOrNull()
            val numbers = WorkoutTextParser.Numbers.extract(p.text)
            val numbersOnly = numbers.any && (numbers.rest.isBlank() || REST_FIRST.containsMatchIn(p.text.lowercase())) &&
                session.wholeMatch(p.text) == null
            // A rest on a line of its own ("Pause 90 s" under a list) is read on its own: after a circuit it is the round's rest.
            val restOnly = numbers.restS != null && !numbers.complete && numbers.loadKg == null && numbers.edgeMm == null
            if (numbersOnly && previous != null && !previous.heading && !p.heading && previous.block == p.block &&
                !(restOnly && previous.line != p.line)) {
                merged[merged.lastIndex] = Piece("${previous.text} ${p.text}", previous.block, previous.line, previous.sentence,
                    previous.sentenceStart, previous.afterStop, false)
            } else merged += p
        }
        return merged
    }

    private val REST_FIRST = Regex("^(?:pause|rest|ruhe|erholung)(?![\\p{L}])")

    /** Sentences of a line and whether each ended with a full stop; decimals and abbreviations ("Wdh.") stay. */
    private fun sentences(line: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val sb = StringBuilder()
        for (i in line.indices) {
            val c = line[i]
            val next = line.getOrNull(i + 1)
            val stop = when (c) {
                ';' -> true
                '!', '?' -> next == null || next.isWhitespace()
                '.' -> {
                    val prev = line.getOrNull(i - 1)
                    val decimal = prev?.isDigit() == true && next?.isDigit() == true
                    val word = Regex("\\p{L}+$").find(sb)?.value?.lowercase()
                    (next == null || next.isWhitespace()) && !decimal && (word == null || word !in ABBREVIATIONS)
                }
                else -> false
            }
            if (stop) {
                if (sb.isNotBlank()) out += sb.toString().trim() to (c != ';')
                sb.clear()
            } else sb.append(c)
        }
        if (sb.isNotBlank()) out += sb.toString().trim() to false
        return out
    }

    /**
     * A colon makes the part before it a heading – unless it is a rest ("Pause: 2 min"), a time ("1:30") or an
     * exercise with its numbers after it ("Klimmzüge: 5x5").
     */
    private fun colonParts(sentence: String, session: ExerciseMatcher.Session): List<Pair<String, Boolean>> {
        val at = sentence.indices.firstOrNull { i ->
            sentence[i] == ':' && !(sentence.getOrNull(i - 1)?.isDigit() == true && sentence.getOrNull(i + 1)?.isDigit() == true)
        } ?: return listOf(sentence to false)
        val left = sentence.substring(0, at).trim()
        val right = sentence.substring(at + 1).trim()
        if (left.isEmpty()) return listOf(right to false)
        if (REST_WORD.containsMatchIn(left.lowercase())) return listOf(sentence to false)
        if (left.split(Regex("\\s+")).size > 6) return listOf(sentence to false)
        val rightNumbers = WorkoutTextParser.Numbers.extract(right)
        if (right.isNotEmpty() && rightNumbers.any && rightNumbers.rest.isBlank() && !circuit(left).header &&
            session.scored(left).base >= 0.85) {
            return listOf("$left $right" to false)
        }
        return if (right.isEmpty()) listOf(left to true) else listOf(left to true, right to false)
    }

    private fun splitPieces(text: String): List<String> = text.split(COMMA).flatMap { it.split(DASH) }

    // ── Exercises inside one piece ───────────────────────────────────

    /** How an exercise is done; left out when the name is matched ("Klimmzüge langsam" are pull-ups). */
    val DESCRIPTORS = setOf(
        "langsam", "langsame", "langsamer", "schnell", "explosiv", "explosive", "kontrolliert", "controlled", "sauber", "saubere",
        "strikt", "strict", "locker", "leicht", "leichte", "easy", "light", "schwer", "schwere", "heavy", "hart", "hard", "maximal",
        "submaximal", "tempo", "slow", "fast", "clean", "ruhig", "versagen", "failure", "ausbelastung", "insgesamt", "total", "gesamt",
        "ungefähr", "ungefaehr", "etwa", "wieder", "nochmal", "again", "kurz", "kurze",
    )

    /** Words that never start an exercise of their own: fillers, connectors, prose, how it is done. */
    private val GLUE = setOf(
        "à", "a", "je", "mit", "with", "of", "at", "@", "für", "fuer", "for", "und", "and", "x", "ca", "circa", "about", "bei", "an", "on",
        "dann", "danach", "anschließend", "anschliessend", "then", "plus", "sowie", "außerdem", "ausserdem", "zudem", "noch", "also", "als",
        "heute", "erst", "zuerst", "zunächst", "zunaechst", "anfangs", "zum", "schluss", "abschluss", "abschließend", "abschliessend",
        "zuletzt", "am", "ende", "ich", "wir", "du", "mache", "machen", "mach", "mal", "ein", "eine", "einen", "paar", "einige", "today",
        "first", "finally", "i", "we", "do", "some", "few", "start", "starten", "beginnen", "beginne", "hinterher", "später", "spaeter",
        "die", "der", "das", "den", "dem", "the", "im", "in", "auf", "bis", "zur", "pro", "per", "jeweils", "each", "alle", "all",
    ) + DESCRIPTORS

    /** [name] without fillers, connectors and prose at its ends ("und dann Klimmzüge" → "Klimmzüge"). */
    fun trimGlue(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun glue(w: String) = w.lowercase().trim('.', ',', ':', ';', '!', '?', '(', ')') in GLUE
        return words.dropWhile(::glue).dropLastWhile(::glue).joinToString(" ")
    }

    /** [name] for matching: without how it is done ("Klimmzüge langsam" → "Klimmzüge"); the name itself if nothing else is left. */
    fun withoutDescriptors(name: String): String {
        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = words.filter { it.lowercase().trim('.', ',') !in DESCRIPTORS }
        return if (kept.isEmpty()) name else kept.joinToString(" ")
    }

    private val CONNECTORS = setOf("und", "and", "dann", "then", "danach", "sowie", "plus", "anschließend", "anschliessend",
        "außerdem", "ausserdem", "zudem")

    private val TOKEN = Regex("[\\p{L}\\p{N}@à][\\p{L}\\p{N}'’.,/+-]*")

    private enum class Kind { NAME, NUMBERS, GLUE }

    private class Tok(val start: Int, val end: Int, val text: String, val kind: Kind, val complete: Boolean)

    /** One exercise of a piece: its whole text, and the name as written before (or after) its numbers. */
    class Unit(val text: String, val primaryName: String?)

    /**
     * The exercises in one [piece]: a new one starts where a name follows a complete amount ("Klimmzüge 5x5
     * Liegestütze 3x15"), or, when the piece starts with numbers, where an amount follows a name ("5x5 Klimmzüge
     * 3x15 Liegestütze"). A word after the numbers that names no exercise ("langsam", "tief") stays with them.
     */
    fun units(piece: String, session: ExerciseMatcher.Session): List<Unit> {
        val numbers = WorkoutTextParser.Numbers.extract(piece)
        if (numbers.spans.isEmpty()) return listOf(Unit(piece, null))
        val toks = TOKEN.findAll(piece).map { m ->
            val start = m.range.first
            val end = m.range.last + 1
            val covering = numbers.spans.filter { it.start < end && it.end > start }
            val word = m.value.lowercase().trimEnd('.', ',')
            val kind = when {
                covering.isNotEmpty() -> Kind.NUMBERS
                word in GLUE || word.length < 2 || word.all { it.isDigit() } -> Kind.GLUE
                else -> Kind.NAME
            }
            Tok(start, end, m.value, kind, covering.any { it.complete })
        }.toList()
        if (toks.none { it.kind == Kind.NAME }) return listOf(Unit(piece, null))
        val firstSignificant = toks.first { it.kind != Kind.GLUE }
        val numbersFirst = firstSignificant.kind == Kind.NUMBERS && firstSignificant.complete
        val starts = mutableListOf(0)
        var hasName = false
        var hasAmount = false
        for ((i, t) in toks.withIndex()) {
            when (t.kind) {
                Kind.NAME -> {
                    if (!numbersFirst && hasAmount && startsAnExercise(toks, i, piece, session)) {
                        starts += i; hasAmount = false
                    }
                    hasName = true
                }
                Kind.NUMBERS -> {
                    if (numbersFirst && t.complete && hasName && hasAmount) {
                        starts += i; hasName = false
                    }
                    if (t.complete) hasAmount = true
                }
                Kind.GLUE -> Unit
            }
        }
        // Mixed styles joined by a connector: "4x4 Bouldern und Dehnen 10 min", "Klimmzüge 5x5 und 3x10 Dips" –
        // the next exercise starts right after "und", not at its numbers or its name.
        for (n in 1 until starts.size) {
            val prev = starts[n - 1]
            val cut = starts[n]
            val c = (cut - 1 downTo prev + 1).firstOrNull { toks[it].kind == Kind.GLUE && toks[it].text.lowercase() in CONNECTORS } ?: continue
            val before = toks.subList(prev, c)
            val between = toks.subList(c + 1, cut)
            if (before.any { it.kind == Kind.NAME } && before.any { it.kind == Kind.NUMBERS && it.complete } &&
                between.any { it.kind != Kind.GLUE }) starts[n] = c + 1
        }
        val bounds = starts.distinct().sorted()
        return bounds.mapIndexed { n, from ->
            val to = bounds.getOrNull(n + 1) ?: toks.size
            val part = toks.subList(from, to)
            val text = piece.substring(part.first().start, part.last().end)
            Unit(text.trim(), primaryName(part, numbersFirst, piece))
        }.filter { it.text.isNotBlank() }
    }

    /** A name after a complete amount starts the next exercise if more numbers follow or it names a known exercise. */
    private fun startsAnExercise(toks: List<Tok>, i: Int, piece: String, session: ExerciseMatcher.Session): Boolean {
        val end = (i until toks.size).firstOrNull { toks[it].kind == Kind.NUMBERS } ?: toks.size
        if (end < toks.size && (end until toks.size).any { toks[it].kind == Kind.NUMBERS && toks[it].complete }) return true
        val name = piece.substring(toks[i].start, toks[end - 1].end)
        return session.scored(name).base >= 0.8
    }

    /** Name-first: the words before the first numbers; numbers-first: the words after the first amount. */
    private fun primaryName(part: List<Tok>, numbersFirst: Boolean, piece: String): String? {
        val words = if (!numbersFirst) part.takeWhile { it.kind != Kind.NUMBERS }
        else part.dropWhile { it.kind != Kind.NAME }.takeWhile { it.kind != Kind.NUMBERS }
        val core = words.dropWhile { it.kind == Kind.GLUE }.dropLastWhile { it.kind == Kind.GLUE }
        if (core.none { it.kind == Kind.NAME }) return null
        return piece.substring(core.first().start, core.last().end).trim()
    }

    // ── Circuits ─────────────────────────────────────────────────────

    /** What a piece says about a circuit; [text] is what remains for its exercises. */
    class CircuitWords(
        val header: Boolean,
        /** "A, B und C als Zirkel", "A im Wechsel mit B": exercises before the words belong to it. */
        val retro: Boolean,
        val rounds: Int?,
        val circuitRestS: Int?,
        val roundRestS: Int?,
        val text: String,
    ) {
        val settings: Boolean get() = rounds != null || circuitRestS != null || roundRestS != null
    }

    private const val B = "(?<![\\p{L}\\p{N}])"
    private const val E = "(?![\\p{L}\\p{N}])"
    private const val UNIT = "(s|sek|sec|secs|sekunde|sekunden|second|seconds|min|mins|minute|minuten|minutes)"
    private const val NUM = "(\\d+(?:[.,]\\d+)?)"

    private val HEADER = Regex(
        "$B(?:(als|im|in|as)\\s+(?:(?:einem|einen|a)\\s+)?)?" +
            "(?:zirkel\\p{L}*|zierkel\\p{L}*|zyrkel\\p{L}*|zirkl\\p{L}*|cirkel\\p{L}*|circuit\\p{L}*|circut\\p{L}*|rundentraining|" +
            "supers(?:a|ä|ae)tz\\p{L}*|super[\\s-]?sets?|giant[\\s-]?sets?|tri[\\s-]?sets?|wechsel|abwechselnd\\p{L}*|alternierend\\p{L}*|" +
            "alternating|alternate|wechselweise)$E")
    private val AFTER_HEADER = Regex("^\\s*(?:bestehend\\s+aus|consisting\\s+of|aus|mit|von|of|with|für|fuer|for)$E")
    private val ROUNDS_1 = Regex("$B(\\d{1,2})\\s*(?:x\\s*)?(?:runden|runde|rounds?|durchg(?:ä|ae|a)nge|durchgang|durchl(?:ä|ae|a)ufe|durchlauf|laps?)$E")
    private val ROUNDS_2 = Regex("$B(?:runden|rounds?)\\s*:?\\s*(\\d{1,2})$E")
    private val ROUNDS_3 = Regex("$B(\\d{1,2})\\s*(?:x|mal)\\s*(?=(?:den\\s+|der\\s+|the\\s+|a\\s+|einen\\s+)?(?:zirkel|circuit|durch))")
    private val ROUNDS_HEADER = Regex("$B(?:zirkel|circuit|supersatz|superset)\\p{L}*\\s*(?:x\\s*(\\d{1,2})|\\(\\s*(\\d{1,2})\\s*x?\\s*\\))(?![\\p{L}\\d])")
    /** "5 Runden mit …", "5 rounds of …", "3x ( … )" at the start: a circuit without the word. */
    private const val LEAD = "^\\s*(?:(?:und|and)\\s+)?(?:(?:dann|then|danach|anschließend|anschliessend|afterwards)\\s+)?"
    private val ROUNDS_OPEN = Regex("$LEAD(\\d{1,2})\\s*(?:x\\s*)?(?:runden|rounds?|durchg(?:ä|ae|a)nge|durchgang)\\s*(?:of|mit|aus|à|von|je|:)?(?=\\s*\\S)")
    private val ROUNDS_PAREN = Regex("$LEAD(\\d{1,2})\\s*(?:x|mal|×)\\s*(?=\\()")
    private val SETS_HEADER = Regex("$B(\\d{1,2})\\s*(?:sätze|saetze|satze|sets?)$E")
    private const val ROUND_CTX = "(?:zwischen\\s+(?:den\\s+)?(?:runden|durchg(?:ä|ae)ngen)|nach\\s+(?:jeder|jede|der|einer)\\s+runde|between\\s+rounds|after\\s+(?:each|every|a)\\s+round)"
    private const val EX_CTX = "(?:zwischen\\s+(?:den\\s+)?(?:übungen|uebungen|stationen)|nach\\s+(?:jeder|jede)\\s+(?:übung|uebung|station)|between\\s+(?:exercises|stations)|after\\s+(?:each|every)\\s+exercise)"
    private val ROUND_REST = listOf(
        Regex("$B(?:(?:pause|rest|ruhe|erholung)\\s*:?\\s*)?$NUM\\s*$UNIT?\\s*(?:pause|rest|ruhe|erholung)?\\s*$ROUND_CTX$E"),
        Regex("$B(?:pause|rest|ruhe|erholung)\\s*$ROUND_CTX\\s*:?\\s*$NUM\\s*$UNIT?$E"),
        Regex("$B(?:runden[\\s-]?pause|rest\\s+between\\s+rounds)\\s*:?\\s*$NUM\\s*$UNIT?$E"),
    )
    private val CIRCUIT_REST = listOf(
        Regex("$B(?:(?:pause|rest|ruhe|erholung)\\s*:?\\s*)?$NUM\\s*$UNIT?\\s*(?:pause|rest|ruhe|erholung)?\\s*$EX_CTX$E"),
        Regex("$B(?:pause|rest|ruhe|erholung)\\s*$EX_CTX\\s*:?\\s*$NUM\\s*$UNIT?$E"),
    )
    private val NO_REST = Regex("$B(?:(?:ohne|keine|no)\\s+(?:pause|rest)(?:\\s+(?:dazwischen|zwischen\\s+den\\s+(?:übungen|uebungen)|between(?:\\s+exercises)?))?|direkt\\s+(?:weiter|hintereinander)|back[\\s-]?to[\\s-]?back)$E")

    /**
     * Circuit words in [text]: whether it opens a circuit, the rounds, the rest from one exercise to the next and
     * the rest after each round. With [inCircuit] the rounds and rests are read even without the circuit word
     * ("…, 3 Runden", "2 min Pause nach jeder Runde").
     */
    fun circuit(text: String, inCircuit: Boolean = false, heading: Boolean = false): CircuitWords {
        val lower = text.lowercase()
        val aligned = lower.length == text.length
        val header = HEADER.find(lower)
        val opener = if (header == null) (ROUNDS_OPEN.find(lower) ?: ROUNDS_PAREN.find(lower)) else null
        if (header == null && opener == null && !inCircuit && !heading) return CircuitWords(false, false, null, null, null, text)
        val mask = BooleanArray(text.length)
        fun cut(range: IntRange) { if (aligned) for (i in range) if (i in mask.indices) mask[i] = true }
        var rounds: Int? = null
        var circuitRest: Int? = null
        var roundRest: Int? = null
        fun seconds(value: String, unit: String?): Int? {
            val v = value.replace(',', '.').toDoubleOrNull() ?: return null
            val s = when {
                unit.isNullOrEmpty() -> if (v <= 10) v * 60 else v
                unit.startsWith("m") -> v * 60
                else -> v
            }
            return s.roundToInt().coerceIn(0, Circuits.MAX_REST_S)
        }
        ROUND_REST.forEach { r -> r.find(lower)?.let { m -> if (roundRest == null) roundRest = seconds(m.groupValues[1], m.groupValues[2]); cut(m.range) } }
        CIRCUIT_REST.forEach { r -> r.find(lower)?.let { m -> if (circuitRest == null) circuitRest = seconds(m.groupValues[1], m.groupValues[2]); cut(m.range) } }
        NO_REST.find(lower)?.let { m -> if (circuitRest == null) circuitRest = 0; cut(m.range) }
        for (r in listOf(ROUNDS_1, ROUNDS_2, ROUNDS_3, ROUNDS_HEADER)) {
            r.find(lower)?.let { m ->
                val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                if (v != null && v in 1..20 && rounds == null) { rounds = v; cut(m.range) }
            }
        }
        if (opener != null && rounds == null) { rounds = opener.groupValues[1].toIntOrNull()?.takeIf { it in 1..20 }; cut(opener.range) }
        else if (opener != null) cut(opener.range)
        var retro = false
        if (header != null) {
            if (rounds == null) SETS_HEADER.find(lower)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 1..20 }?.let { rounds = it; cut(m.range) } }
            cut(header.range)
            AFTER_HEADER.find(lower.substring(header.range.last + 1))?.let { m ->
                cut((header.range.last + 1 + m.range.first)..(header.range.last + m.range.last + 1))
            }
            // "A, B und C als Zirkel" / "… im Zirkel": the exercises before it in the sentence belong to it. "A im Wechsel mit
            // B" pairs the two sides of this piece only, and "…, dann Zirkel: …" starts a circuit (device review 2026-10-19).
            val pairing = Regex("wechsel|altern").containsMatchIn(header.value)
            val before = trimGlue(text.substring(0, header.range.first))
            val after = lower.substring(header.range.last + 1)
            val nothingAfter = after.none { it.isLetter() } || ROUNDS_1.matches(after.trim()) || ROUNDS_2.matches(after.trim())
            retro = !pairing && nothingAfter && (header.groupValues[1].isNotEmpty() || before.any { it.isLetter() })
        }
        val rest = if (!aligned) text else text.indices.joinToString("") { i -> if (mask[i]) " " else text[i].toString() }
        val cleaned = rest.replace(Regex("\\s+"), " ").trim().trim(',', ':', '(', ')', '-').trim()
        // "3 Runden:" over a list, "5 rounds of …", "3x ( … )": a circuit without the word.
        if (header == null && !inCircuit) {
            return if ((heading && rounds != null && cleaned.isBlank()) || (opener != null && cleaned.any { it.isLetter() }))
                CircuitWords(true, false, rounds, circuitRest, roundRest, cleaned)
            else CircuitWords(false, false, null, null, null, text)
        }
        if (header == null && opener != null && cleaned.any { it.isLetter() }) return CircuitWords(true, false, rounds, circuitRest, roundRest, cleaned)
        return CircuitWords(header != null, retro, rounds, circuitRest, roundRest, cleaned)
    }

    // ── Where a section ends ─────────────────────────────────────────

    private val STRONG_END = Regex("^(?:danach|anschließend|anschliessend|im\\s+anschluss|zum\\s+(?:schluss|abschluss)|abschließend|abschliessend|" +
        "zuletzt|am\\s+ende|hinterher|nach\\s+dem\\s+zirkel|afterwards|after\\s+that|finally|ende(?:\\s+(?:zirkel|circuit|des\\s+zirkels))?|" +
        "end(?:\\s+of\\s+(?:the\\s+)?circuit)?)(?![\\p{L}])[\\s,:]*")
    private val SOFT_END = Regex("^(?:dann|then|jetzt|nun|now)(?![\\p{L}])[\\s,:]*")

    /** "Danach …", "Zum Schluss …", or "Dann …" after a full stop: the warm-up or the circuit before is over. */
    fun sectionEnd(piece: Piece): Pair<Boolean, String> {
        if (!piece.sentenceStart) return false to piece.text
        val lower = piece.text.lowercase()
        STRONG_END.find(lower)?.let { m -> if (lower.length == piece.text.length) return true to piece.text.substring(m.range.last + 1).trim() }
        if (piece.afterStop) SOFT_END.find(lower)?.let { m ->
            if (lower.length == piece.text.length) return true to piece.text.substring(m.range.last + 1).trim()
        }
        return false to piece.text
    }
}
