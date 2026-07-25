package com.cruxcoach.domain.playlist

import kotlin.random.Random

/** One candidate climb from the board catalogue (already board-fit- and
 *  ignore-filtered by the caller's query). */
data class PlaylistCandidate(
    val climbUuid: String,
    val difficulty: Double,
    val quality: Double? = null,
    val ascensionistCount: Long? = null,
    /** User has sent this climb (any angle counts — freshness bias only). */
    val sent: Boolean = false,
    /** User has attempted (bid) but not sent — an open project. */
    val attempted: Boolean = false,
    /** Any logbook contact within the last ~2 weeks — variety first:
     *  yesterday's problems are a weaker stimulus than fresh ones. */
    val recentlyTried: Boolean = false,
)

/** Supplies candidates inside a difficulty band. Implemented Android-side
 *  on top of BoardRepository.searchClimbsSorted (layout + angle + size-fit
 *  + min-ascents already applied). */
fun interface CandidateSource {
    fun candidates(minDifficulty: Double, maxDifficulty: Double): List<PlaylistCandidate>
}

/** A filled playlist element, ready to map into repository entries. */
sealed interface GeneratedEntry {
    data class Climb(
        val climbUuid: String,
        val difficulty: Double,
        val section: PlanSection,
    ) : GeneratedEntry

    data class Rest(val seconds: Int, val section: PlanSection) : GeneratedEntry
}

/**
 * Fill outcome. [droppedClimbs] counts slots that stayed empty even after
 * widening — the UI must surface this instead of silently shrinking the
 * session ("nur 3 statt 6 Limit-Probleme gefunden").
 */
data class GenerationResult(
    val entries: List<GeneratedEntry>,
    val droppedClimbs: Int,
    val widenedSlots: Int,
)

/**
 * Second phase of the generator: assign a concrete climb to every planned
 * slot.
 *
 *  - Never repeats a climb within the playlist (except repeatKey laps,
 *    which MUST repeat the same problem across sets).
 *  - Prefers unclimbed material, then quality; PROJECTING slots prefer the
 *    profile's open projects.
 *  - Widens the band stepwise (±0.5 V up to ±2 V) when a slot has no
 *    candidates; drops the slot (and its now-orphaned rest) if still dry.
 *  - Randomness is seeded by the caller: picks among the top candidates so
 *    two "Neu generieren" runs don't return the identical list.
 */
object PlaylistFiller {

    private const val WIDEN_STEP = 1.0
    private const val WIDEN_MAX = 4.0
    private const val PICK_POOL = 5

    fun fill(
        plan: PlaylistPlan,
        source: CandidateSource,
        openProjects: List<String> = emptyList(),
        selection: CandidateSelection = CandidateSelection.NEW,
        random: Random = Random.Default,
    ): GenerationResult {
        val used = mutableSetOf<String>()
        val byRepeatKey = mutableMapOf<Int, GeneratedEntry.Climb>()
        val entries = mutableListOf<GeneratedEntry>()
        // A plan contains many identical bands (for example every volume
        // problem in one tier). The Android source is a sorted catalogue SQL
        // query, so asking it again for every slot dominated generation time.
        // Keep one immutable catalogue snapshot per requested band for this
        // fill run; filtering already removes climbs selected by earlier slots.
        val candidateCache = mutableMapOf<Pair<Double, Double>, List<PlaylistCandidate>>()
        val cachedSource = CandidateSource { minDifficulty, maxDifficulty ->
            candidateCache.getOrPut(minDifficulty to maxDifficulty) {
                source.candidates(minDifficulty, maxDifficulty)
            }
        }
        var dropped = 0
        var widened = 0

        plan.slots.forEach { slot ->
            when (slot) {
                is PlanSlot.RestSlot -> entries.add(GeneratedEntry.Rest(slot.seconds, slot.section))
                is PlanSlot.ClimbSlot -> {
                    // Laps re-use their set's problem verbatim.
                    val repeated = slot.repeatKey?.let { byRepeatKey[it] }
                    if (repeated != null) {
                        entries.add(repeated.copy(section = slot.section))
                        return@forEach
                    }

                    val preferProjects = slot.section == PlanSection.PEAK &&
                        plan.effectiveType == GeneratorType.PROJECTING
                    val (pick, didWiden) =
                        pickClimb(slot, cachedSource, used, preferProjects, openProjects, selection, random)
                    if (didWiden) widened++
                    if (pick == null) {
                        dropped++
                        // Drop the rest that led into this now-empty slot.
                        if (entries.lastOrNull() is GeneratedEntry.Rest) {
                            entries.removeAt(entries.size - 1)
                        }
                        return@forEach
                    }
                    used.add(pick.climbUuid)
                    val entry = GeneratedEntry.Climb(pick.climbUuid, pick.difficulty, slot.section)
                    slot.repeatKey?.let { byRepeatKey[it] = entry }
                    entries.add(entry)
                }
            }
        }

        return GenerationResult(
            entries = entries.normalize(),
            droppedClimbs = dropped,
            widenedSlots = widened,
        )
    }

    private fun pickClimb(
        slot: PlanSlot.ClimbSlot,
        source: CandidateSource,
        used: Set<String>,
        preferProjects: Boolean,
        openProjects: List<String>,
        selection: CandidateSelection,
        random: Random,
    ): Pair<PlaylistCandidate?, Boolean> {
        var widening = 0.0
        while (widening <= WIDEN_MAX) {
            val pool = source
                .candidates(slot.minDifficulty - widening, slot.maxDifficulty + widening)
                .filter { it.climbUuid !in used }
            if (pool.isNotEmpty()) {
                return rank(pool, preferProjects, openProjects, selection, random) to (widening > 0.0)
            }
            widening += WIDEN_STEP
        }
        return null to true
    }

    private fun rank(
        pool: List<PlaylistCandidate>,
        preferProjects: Boolean,
        openProjects: List<String>,
        selection: CandidateSelection,
        random: Random,
    ): PlaylistCandidate {
        if (preferProjects) {
            // Open projects first, in the caller's (engagement) order.
            val projectSet = openProjects.toSet()
            val projects = pool.filter { it.climbUuid in projectSet || (it.attempted && !it.sent) }
            if (projects.isNotEmpty()) {
                val ordered = projects.sortedBy { openProjects.indexOf(it.climbUuid).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                return ordered.first()
            }
        }
        // User selection = primary tier; graceful fallback keeps slots
        // filled when the primary group runs dry.
        val primary = when (selection) {
            CandidateSelection.NEW -> pool.filter { !it.sent && !it.attempted }
            CandidateSelection.PROJECTS -> pool.filter { it.attempted && !it.sent }
            CandidateSelection.ALL -> pool
        }
        val effective = primary.ifEmpty { pool }
        // Fresh stimulus first (nothing from the last two weeks), then
        // unclimbed, then community quality; pick randomly among the top
        // few so regeneration varies.
        val ranked = effective.sortedWith(
            compareBy(
                { it.recentlyTried },           // variety: skip last-2-weeks repeats
                { it.sent },                    // unclimbed first
                { -(it.quality ?: 0.0) },       // then best quality
                { -(it.ascensionistCount ?: 0L) },
            )
        )
        // Random variety only WITHIN the best tier — the shuffle must not
        // leak a recently-tried or already-sent climb past a fresh one.
        val best = ranked.first()
        val top = ranked
            .takeWhile { it.recentlyTried == best.recentlyTried && it.sent == best.sent }
            .take(PICK_POOL)
        return top[random.nextInt(top.size)]
    }

    /** Strip rests orphaned by dropped climbs (leading/trailing/double). */
    private fun List<GeneratedEntry>.normalize(): List<GeneratedEntry> {
        val trimmed = dropWhile { it is GeneratedEntry.Rest }
            .dropLastWhile { it is GeneratedEntry.Rest }
        val out = mutableListOf<GeneratedEntry>()
        trimmed.forEach { e ->
            if (e is GeneratedEntry.Rest && out.lastOrNull() is GeneratedEntry.Rest) {
                val prev = out.removeAt(out.size - 1) as GeneratedEntry.Rest
                out.add(if (e.seconds > prev.seconds) e else prev)
            } else {
                out.add(e)
            }
        }
        return out
    }
}
