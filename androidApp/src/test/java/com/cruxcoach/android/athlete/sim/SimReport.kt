package com.cruxcoach.android.athlete.sim

import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.UnitSystem

/** Markdown summaries of simulation runs: one per persona, short enough for a coach to review, and an overview. */
object SimReport {

    private const val LB = 2.20462

    private fun mass(kg: Double, units: UnitSystem): String =
        if (units == UnitSystem.IMPERIAL) "%.1f lb".format(kg * LB) else "%.1f kg".format(kg)

    fun persona(r: SimRun, flags: List<Flag>): String = buildString {
        val p = r.persona
        appendLine("# ${p.title}")
        appendLine()
        appendLine(p.summary)
        appendLine()
        appendLine("Level ${p.level.name.lowercase()}, ${mass(p.bodyweightKg, p.units)} body weight, ${p.weeks} weeks, " +
            "training on ${weekdays(p.trainingDays)}" + (if (p.climbing.isNotEmpty()) ", climbing on ${weekdays(p.climbing.keys)}" else "") +
            (if (p.skipShare > 0) ", skips about ${(p.skipShare * 100).toInt()} %" else "") + ".")
        appendLine()
        appendLine("## Weeks at a glance")
        appendLine()
        appendLine("| Week | Block | Trained | Climbed | Suggestions | Work sets done | Flags |")
        appendLine("|---|---|---|---|---|---|---|")
        r.days.chunked(7).forEachIndexed { i, week ->
            val phases = week.mapNotNull { it.block?.phase?.name?.lowercase() }.distinct().joinToString("/").ifEmpty { "–" }
            val away = if (week.all { it.away }) " (away)" else ""
            val trained = week.count { it.trained }
            val planned = week.count { it.date.dayOfWeek.ordinal + 1 in p.trainingDays && !it.away }
            val climbed = week.count { it.climbed != null }
            val focus = week.mapNotNull { it.suggestion?.focus }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.joinToString(", ") { "${short(it.key)} ${it.value}" }
            val sets = week.sumOf { d -> d.sets.count { it.setType == SetType.WORK } }
            val weekFlags = flags.filter { f -> f.day in week.first().day..week.last().day }.size
            appendLine("| ${i + 1}$away | $phases | $trained/$planned | $climbed | $focus | $sets | ${if (weekFlags == 0) "–" else weekFlags} |")
        }
        appendLine()
        appendLine("## Key exercises")
        appendLine()
        val measured = r.convergence.map { it.slug }.toSet()
        val key = r.days.flatMap { d -> d.sets.filter { it.setType == SetType.WORK }.map { it.slug } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .map { it.key }.filter { it in measured }
            .take(4)
        if (key.isEmpty()) appendLine("No loaded exercise was trained.")
        key.forEach { slug ->
            val def = r.catalog[slug]!!
            appendLine("**${def.name("en")}** (${if (def.kind == ExerciseKind.LOAD_REPS) "est. 1RM" else "10-s max"} of the total load" +
                (if (def.load == LoadMode.BODYWEIGHT_PLUS) ", body weight included" else "") + ")")
            appendLine()
            appendLine("| Week | Planned first set | App's value | True value |")
            appendLine("|---|---|---|---|")
            r.convergence.filter { it.slug == slug }.groupBy { it.week }.forEach { (week, rows) ->
                val days = r.days.filter { it.day / 7 + 1 == week }
                val firstSet = days.flatMap { it.sets }.firstOrNull { it.slug == slug && it.setType == SetType.WORK }
                val planned = firstSet?.let { s ->
                    val load = s.targetLoadKg?.let { (if (def.load == LoadMode.BODYWEIGHT_PLUS && it >= 0) "+" else "") + mass(it, p.units) } ?: "–"
                    "$load × ${s.targetReps ?: s.targetDurationS?.toInt()?.let { "$it s" } ?: "?"}"
                } ?: "–"
                rows.forEach { c ->
                    val side = c.side?.let { " ${it.name.lowercase()}" } ?: ""
                    appendLine("| $week$side | $planned | ${c.appKg?.let { mass(it, p.units) } ?: "–"} | ${mass(c.trueKg, p.units)} |")
                }
            }
            appendLine()
        }
        r.days.mapNotNull { it.comeback }.takeIf { it.isNotEmpty() }?.let { appendLine(comebackWeeks(r)) }
        appendLine("## Flagged moments")
        appendLine()
        if (flags.isEmpty()) appendLine("None.")
        flags.groupBy { it.check }.forEach { (check, list) ->
            appendLine("**${check.group}: ${check.title}** (${list.size})")
            appendLine()
            list.take(12).forEach { f -> appendLine("- Week ${f.day / 7 + 1}, ${r.days[f.day].date} (${r.days[f.day].date.dayOfWeek.name.lowercase().take(3)}): ${f.what}") }
            if (list.size > 12) appendLine("- … and ${list.size - 12} more")
            appendLine()
        }
    }

    /** Every day in one line: what was climbed, what the coach suggested and why, what was done. */
    fun days(r: SimRun): String = buildString {
        val p = r.persona
        appendLine("# ${p.title}: day by day")
        appendLine()
        appendLine("| Day | Block | Climbed | Suggested (focus · first reasons) | Exercises × sets | Done |")
        appendLine("|---|---|---|---|---|---|")
        r.days.forEach { d ->
            val day = "${d.date} ${d.date.dayOfWeek.name.lowercase().take(3)}"
            if (d.away) { appendLine("| $day | – | – | away | – | – |"); return@forEach }
            val s = d.suggestion
            val phase = d.block?.let { "${it.phase.name.lowercase()} ${it.weekInBlock}" } ?: "–"
            val climb = d.climbed?.let { "${it.kind.name.lowercase()} ${it.minutes} min ${it.intensity.name.lowercase()}" } ?: "–"
            val why = s?.let { "${short(it.focus)} · " + it.reasons.take(3).joinToString(", ") { r -> r.name.lowercase() } +
                (if (it.levelShift != 0) " · level ${it.levelShift}" else "") } ?: "–"
            val items = s?.items?.filter { !it.warmup }?.joinToString(", ") { "${it.slug.substringAfter('.')}×${it.sets}" }.orEmpty().ifEmpty { "–" }
            val done = when {
                d.trained -> "${d.sets.count { it.setType == SetType.WORK }} sets"
                d.skipped -> "skipped"
                else -> "–"
            }
            val comeback = d.comeback?.let { c -> listOf("comeback stage ${c.stage}") + c.sessions.map { "${it.kind.name.lowercase()} pain ${it.painMax}" } + c.prompts }.orEmpty()
            val events = (d.events + comeback).takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") ?: ""
            appendLine("| $day$events | $phase | $climb | $why | $items | $done |")
        }
    }

    /** The comeback plan week by week: stages, sessions, pain and the injured hand's loads. */
    private fun comebackWeeks(r: SimRun): String = buildString {
        appendLine("## Comeback plan")
        appendLine()
        appendLine("The injured hand's tolerance is the simulated truth: the share of its maximum before the injury it takes without pain.")
        appendLine()
        appendLine("| Week | Stage | Climbing | Sessions | Highest pain | Injured hand, first set planned → done | Load changed by hand | Look back | Tolerance |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        r.days.chunked(7).forEachIndexed { i, week ->
            val cbs = week.mapNotNull { it.comeback }
            if (cbs.isEmpty()) return@forEachIndexed
            val stages = cbs.map { it.stage }.distinct().joinToString("→")
            val climbing = cbs.mapNotNull { it.climbing?.name?.lowercase() }.distinct().joinToString("/").ifEmpty { "–" }
            val sessions = cbs.flatMap { it.sessions }
            val kinds = sessions.groupingBy { it.kind.name.lowercase() }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "–" }
            val pain = sessions.maxOfOrNull { it.painMax }?.toString() ?: "–"
            val loads = sessions.flatMap { s -> s.sets.filter { it.injured } }.groupBy { it.slug.substringAfter('.') }.entries.joinToString("; ") { (slug, sets) ->
                fun kg(v: Double?) = v?.let { "%.0f".format(it) } ?: "–"
                "$slug ${kg(sets.first().plannedKg)}→${kg(sets.first().doneKg)} … ${kg(sets.last().plannedKg)}→${kg(sets.last().doneKg)} kg"
            }.ifEmpty { "–" }
            val prompts = cbs.flatMap { it.prompts }.joinToString("; ").ifEmpty { "–" }
            // Sessions in which the athlete had to change the injured hand's planned load.
            val loaded = sessions.filter { s -> s.sets.any { it.injured && it.plannedKg != null } }
            val changed = loaded.count { s -> s.sets.any { it.injured && it.plannedKg != null && it.doneKg != it.plannedKg } }
            appendLine("| ${i + 1} | $stages | $climbing | $kinds | $pain | $loads | ${if (loaded.isEmpty()) "–" else "$changed of ${loaded.size}"} | $prompts | ${(cbs.last().tolerance * 100).toInt()} % |")
        }
        r.days.firstOrNull { it.comeback?.ended == true }?.let { appendLine(); appendLine("The athlete ended the plan on ${it.date} (week ${it.day / 7 + 1}); the injury counts as over from then on.") }
    }

    fun overview(runs: List<Pair<SimRun, List<Flag>>>): String = buildString {
        appendLine("# Training simulation: overview")
        appendLine()
        appendLine("| Persona | Weeks | Trainings | Climbing days | " + Check.entries.map { it.group }.distinct().joinToString(" | ") + " |")
        appendLine("|---|---|---|---|" + Check.entries.map { it.group }.distinct().joinToString("") { "---|" })
        runs.forEach { (r, flags) ->
            val groups = Check.entries.map { it.group }.distinct().map { g -> flags.count { it.check.group == g } }
            appendLine("| ${r.persona.title} | ${r.persona.weeks} | ${r.days.count { it.trained }} | ${r.days.count { it.climbed != null }} | " +
                groups.joinToString(" | ") { if (it == 0) "–" else it.toString() } + " |")
        }
        appendLine()
        appendLine("## Flags by check")
        appendLine()
        Check.entries.forEach { c ->
            val n = runs.sumOf { (_, f) -> f.count { it.check == c } }
            val who = runs.filter { (_, f) -> f.any { it.check == c } }.joinToString { it.first.persona.id }
            appendLine("- ${c.group}: ${c.title}: ${if (n == 0) "none" else "$n ($who)"}")
        }
    }

    private fun weekdays(days: Set<Int>) = days.sorted().joinToString("/") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
        .ifEmpty { "no day" }

    private fun short(f: SuggestionFocus) = when (f) {
        SuggestionFocus.PLANNED -> "planned"
        SuggestionFocus.BOARD_DAY -> "board"
        SuggestionFocus.FINGER_STRENGTH -> "finger"
        SuggestionFocus.PULL_PUSH -> "pull/push"
        SuggestionFocus.LEGS_CORE -> "legs/core"
        SuggestionFocus.MOBILITY_RECOVERY -> "mobility"
        SuggestionFocus.INJURY_SAFE -> "injury-safe"
        SuggestionFocus.REST -> "rest"
    }
}
