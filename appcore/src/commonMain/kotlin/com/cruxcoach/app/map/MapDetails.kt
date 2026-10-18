package com.cruxcoach.app.map

import com.cruxcoach.data.repository.Adjustability

/**
 * Map-only facts from the Pages snapshot that the shared `BoardLocation` row
 * does not carry (port of Android `MapDetails.kt`). The synced rows have none.
 */
data class MapVenueInfo(
    /** The week the venue itself published, or null when there is none. */
    val hours: VenueHours? = null,
    /** The page the hours were read from (shown only when it differs from the website). */
    val hoursSource: String? = null,
    /** True when the city is the nearest town attached by distance, not the venue's address. */
    val cityIsNearest: Boolean = false,
    /** German name of that nearest town where the pipeline knows one. */
    val nearestCityDe: String? = null,
)

/** Per-board extras: the wall geometry Kilter rows carry and the published models. */
data class MapBoardInfo(
    val wallName: String? = null,
    val minAngle: Int? = null,
    val maxAngle: Int? = null,
    val angleStep: Int? = null,
    /** The angle the board is set to (adjustable) or fixed at. */
    val angle: Int? = null,
    val models: List<String> = emptyList(),
)

/**
 * A venue's published opening week, Monday first, where `""` is a day the
 * venue states as closed. Wrong hours are worse than none: anything not
 * spelled canonically rejects the whole week, and nothing here works out
 * whether the venue is open *now* (its timezone and holidays are not in the data).
 */
class VenueHours private constructor(val week: List<String>) {

    /** One displayed row: a run of identical days ([firstDay]..[lastDay], 0 = Monday). */
    data class Group(val firstDay: Int, val lastDay: Int, val ranges: List<IntRange>)

    fun groups(): List<Group> {
        val out = mutableListOf<Group>()
        var start = 0
        for (i in 1..week.size) {
            if (i < week.size && week[i] == week[start]) continue
            out += Group(start, i - 1, parseDay(week[start]).orEmpty())
            start = i
        }
        return out
    }

    /** The ranges for one day (0 = Monday); empty when the venue is closed that day. */
    fun day(index: Int): List<IntRange> = parseDay(week[index]).orEmpty()

    override fun equals(other: Any?) = other is VenueHours && other.week == week
    override fun hashCode() = week.hashCode()
    override fun toString() = "VenueHours($week)"

    companion object {
        /** The week only when all seven days are canonical and at least one is open. */
        fun parse(raw: List<String>?): VenueHours? {
            if (raw == null || raw.size != 7) return null
            var stated = 0
            for (spec in raw) {
                if (spec.isEmpty()) continue
                parseDay(spec) ?: return null
                stated++
            }
            return if (stated > 0) VenueHours(raw) else null
        }

        private val RANGE = Regex("^([0-2][0-9]):([0-5][0-9])-([0-2][0-9]):([0-5][0-9])$")

        /** Minutes since midnight per range; an end past 24:00 (up to 28:00) is the next day. */
        internal fun parseDay(spec: String): List<IntRange>? {
            if (spec.isEmpty()) return emptyList()
            val out = mutableListOf<IntRange>()
            for (part in spec.split(',')) {
                val m = RANGE.matchEntire(part) ?: return null
                val (h1, m1, h2, m2) = m.destructured
                val start = h1.toInt() * 60 + m1.toInt()
                val end = h2.toInt() * 60 + m2.toInt()
                if (start > 1439 || end > 1680 || end <= start) return null
                if (out.isNotEmpty() && start <= out.last().last) return null
                out += start..end
            }
            return out
        }
    }
}

/** "10:00–22:00", "24 hours", "Closed" or "18:00–02:00 (next day)"; the words come from the caller. */
fun formatHoursRanges(ranges: List<IntRange>, closed: String, allDay: String, nextDay: String): String {
    if (ranges.isEmpty()) return closed
    if (ranges.size == 1 && ranges[0].first == 0 && ranges[0].last == 1440) return allDay
    fun hm(minutes: Int) = (minutes / 60).toString().padStart(2, '0') + ":" + (minutes % 60).toString().padStart(2, '0')
    return ranges.joinToString(", ") { r ->
        if (r.last > 1440) "${hm(r.first)}–${hm(r.last - 1440)} ($nextDay)"
        else "${hm(r.first)}–${hm(r.last)}"
    }
}

/** "Mon", "Mon–Fri" for a group, from the seven localized short day names. */
fun formatHoursDays(group: VenueHours.Group, dayNames: List<String>): String =
    if (group.firstDay == group.lastDay) dayNames[group.firstDay]
    else "${dayNames[group.firstDay]}–${dayNames[group.lastDay]}"

/** How a board's angle reads, mirroring the cruxcoach.org board map. */
sealed interface AngleFact {
    data class Range(val min: Int, val max: Int, val step: Int?) : AngleFact
    data class SetTo(val angle: Int) : AngleFact
    data object Adjustable : AngleFact
    data class FixedAt(val angle: Int) : AngleFact
    data object Fixed : AngleFact
}

fun angleFact(adjustability: Adjustability, info: MapBoardInfo?, fixedAngle: Int?): AngleFact? = when (adjustability) {
    Adjustability.ADJUSTABLE, Adjustability.FULL, Adjustability.LIMITED -> {
        val min = info?.minAngle
        val max = info?.maxAngle
        when {
            min != null && max != null && max > min -> AngleFact.Range(min, max, info.angleStep)
            info?.angle != null -> AngleFact.SetTo(info.angle)
            else -> AngleFact.Adjustable
        }
    }
    Adjustability.FIXED -> (fixedAngle ?: info?.angle)?.let { AngleFact.FixedAt(it) } ?: AngleFact.Fixed
    Adjustability.UNKNOWN -> null
}
