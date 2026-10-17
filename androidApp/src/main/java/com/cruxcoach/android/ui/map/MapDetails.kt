package com.cruxcoach.android.ui.map

/**
 * Map-only facts from the Pages snapshot that the shared
 * [com.cruxcoach.data.repository.BoardLocation] row does not carry. Kept out of
 * the shared model on purpose: the picker and the SQL locations chunk never
 * see them, and the dynamic Blossom rows simply have none.
 */
data class MapVenueInfo(
    /** The week the venue itself published, or null when there is none. */
    val hours: VenueHours? = null,
    /** The page the hours were read from (shown only when it differs from the website). */
    val hoursSource: String? = null,
    /** True when the city is the nearest town the pipeline attached by
     *  distance, not the venue's own address — the UI hedges it ("near X"). */
    val cityIsNearest: Boolean = false,
    /** German name of that nearest town where the pipeline knows one ("München" for "Munich"). */
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
 * venue states as closed. Ported from the cruxcoach.org board map, which owns
 * the curation policy: wrong hours are worse than none, so anything that is not
 * spelled canonically rejects the whole week, and nothing here works out
 * whether the venue is open *now* — the venue's timezone and public holidays
 * are not in the data.
 */
class VenueHours private constructor(val week: List<String>) {

    /** One displayed row: a run of identical days ([firstDay]..[lastDay], 0 = Monday). */
    data class Group(val firstDay: Int, val lastDay: Int, val ranges: List<IntRange>)

    /** Runs of identical days collapse into one row: a gym with the same
     *  weekday schedule reads as two lines rather than seven. */
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
        /** Returns the week only when all seven days are canonical and at
         *  least one is open; otherwise null (the section is not shown). */
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

        /** Minutes since midnight per range; an end past 24:00 (up to 28:00)
         *  means the next day. Null when the spec is not canonical. */
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

/** "10:00–22:00", "24 hours", "Closed" or "18:00–02:00 (next day)" for one row. */
internal fun formatHoursRanges(
    ranges: List<IntRange>,
    closed: String,
    allDay: String,
    nextDay: String,
): String {
    if (ranges.isEmpty()) return closed
    if (ranges.size == 1 && ranges[0].first == 0 && ranges[0].last == 1440) return allDay
    fun hm(minutes: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    return ranges.joinToString(", ") { r ->
        if (r.last > 1440) "${hm(r.first)}–${hm(r.last - 1440)} ($nextDay)"
        else "${hm(r.first)}–${hm(r.last)}"
    }
}

/** "Mon", "Mon–Fri" for a group, from the seven localized short day names. */
internal fun formatHoursDays(group: VenueHours.Group, dayNames: List<String>): String =
    if (group.firstDay == group.lastDay) dayNames[group.firstDay]
    else "${dayNames[group.firstDay]}–${dayNames[group.lastDay]}"
