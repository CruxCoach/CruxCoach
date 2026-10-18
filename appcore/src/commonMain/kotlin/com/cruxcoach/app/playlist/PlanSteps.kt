package com.cruxcoach.app.playlist

import com.cruxcoach.data.repository.ClimbWithStats

/** View-mode row of a training plan (Android `PlaylistRow`): attempts on one climb collapsed. */
sealed class PlanRow {
    abstract val key: String

    data class Climb(
        val step: PlanStep,
        val attemptCount: Int,
        /** Rest between the collapsed attempts (null when single). */
        val attemptRestSeconds: Long?,
        /** Uniform rest rows represented by this collapsed attempt card. */
        val attemptRestStepIds: List<Long>,
    ) : PlanRow() {
        override val key: String get() = "c${step.stepId}"
    }

    data class Rest(val step: PlanStep) : PlanRow() {
        override val key: String get() = "r${step.stepId}"
    }
}

/**
 * Collapses runs of [climb X, rest, climb X, rest, climb X] (same uuid and
 * angle) into one row with attemptCount=3 — the limit/projecting attempt
 * structure. Rests BETWEEN different climbs stay as rows. Port of Android
 * `groupAttempts`.
 */
internal fun groupAttempts(steps: List<PlanStep>): List<PlanRow> {
    val rows = mutableListOf<PlanRow>()
    var i = 0
    while (i < steps.size) {
        val e = steps[i]
        if (e.isRest) {
            rows.add(PlanRow.Rest(e))
            i++
            continue
        }
        var count = 1
        var attemptRest: Long? = null
        var separatorInitialized = false
        val attemptRestStepIds = mutableListOf<Long>()
        var j = i + 1
        while (j < steps.size) {
            val next = steps[j]
            val afterRest = steps.getOrNull(j + 1)
            when {
                !next.isRest && next.isSameAttemptTargetAs(e) -> {
                    // No-rest and timed-rest separators are different plans; keep
                    // the latter visible instead of folding both into one value.
                    if (separatorInitialized && attemptRest != null) break
                    separatorInitialized = true
                    attemptRest = null
                    count++
                    j++
                }
                next.isRest && afterRest != null && !afterRest.isRest &&
                    afterRest.isSameAttemptTargetAs(e) -> {
                    val restSeconds = next.restSeconds ?: 0L
                    // A collapsed row states ONE inter-attempt rest value.
                    if (separatorInitialized && attemptRest != restSeconds) break
                    separatorInitialized = true
                    attemptRest = restSeconds
                    attemptRestStepIds += next.stepId
                    count++
                    j += 2
                }
                else -> break
            }
        }
        rows.add(
            PlanRow.Climb(
                step = e,
                attemptCount = count,
                attemptRestSeconds = if (count > 1) attemptRest else null,
                attemptRestStepIds = if (count > 1) attemptRestStepIds else emptyList(),
            ),
        )
        i = j
    }
    return rows
}

private fun PlanStep.isSameAttemptTargetAs(other: PlanStep): Boolean {
    val thisUuid = climbUuid ?: return false
    val otherUuid = other.climbUuid ?: return false
    return normUuidKey(thisUuid) == normUuidKey(otherUuid) && (angle ?: 40L) == (other.angle ?: 40L)
}

/**
 * Per-angle statistics for pinned (uuid, angle) pairs, keyed by
 * [normUuidKey] + angle. One lookup per distinct angle, every plausible
 * spelling asked for.
 */
internal fun ListClimbLookup.resolvePinnedAngles(
    pairs: Collection<Pair<String, Int>>,
): Map<Pair<String, Int>, ClimbWithStats> {
    val out = HashMap<Pair<String, Int>, ClimbWithStats>()
    pairs.groupBy({ it.second }, { it.first }).forEach { (angle, uuids) ->
        val spellings = uuids.flatMap {
            val bare = it.replace("-", "")
            listOf(it, bare.lowercase(), bare.uppercase())
        }.distinct()
        spellings.chunked(LOOKUP_CHUNK).forEach { chunk ->
            climbsByUuids(chunk, angle).forEach { out[normUuidKey(it.uuid) to angle] = it }
        }
    }
    return out
}

/**
 * The step's climb with the grade of its pinned angle. Without statistics at
 * that angle no grade is shown, instead of a grade that belongs to another
 * angle (Android `gradeForPinnedAngle`).
 */
internal fun gradeForPinnedAngle(
    anyAngle: ClimbWithStats?,
    pinned: Map<Pair<String, Int>, ClimbWithStats>,
    uuid: String,
    angle: Long?,
): ClimbWithStats? {
    if (anyAngle == null || angle == null) return anyAngle
    return pinned[normUuidKey(uuid) to angle.toInt()] ?: anyAngle.copy(difficultyAverage = null)
}

/** A compiled playback queue and the distinct board configurations it spans. */
class PreparedPlayback(val items: List<PlaybackItem>, val boardCount: Int)

/**
 * Android `BoardListDetailViewModel.preparePlanPlayback`: rest rows collapse
 * onto the climb before them (consecutive rests summed), climbs the catalogue
 * cannot resolve are skipped, and a leading rest has no climb to own it.
 */
internal fun planPlayback(steps: List<PlanStep>, defaultAngle: Int): PreparedPlayback {
    val items = mutableListOf<PlaybackItem>()
    val boardKeys = linkedSetOf<Pair<String, Long>>()
    var pendingRest = 0
    for (step in steps) {
        if (step.isRest) {
            pendingRest += (step.restSeconds ?: 0L).toInt()
            continue
        }
        val uuid = step.climbUuid ?: continue
        val climb = step.climb ?: continue
        if (pendingRest > 0 && items.isNotEmpty()) {
            val last = items.removeAt(items.lastIndex)
            items.add(last.copy(restAfterSeconds = last.restAfterSeconds + pendingRest))
        }
        pendingRest = 0
        items.add(PlaybackItem(uuid, step.angle?.toInt() ?: defaultAngle))
        boardKeys.add(climb.boardBrand to climb.layoutId)
    }
    if (pendingRest > 0 && items.isNotEmpty()) {
        val last = items.removeAt(items.lastIndex)
        items.add(last.copy(restAfterSeconds = last.restAfterSeconds + pendingRest))
    }
    return PreparedPlayback(items, boardKeys.size)
}

/**
 * Android `prepareListPlayback`: without a plan the list plays once in its
 * saved order at the browser's angle, with the list's default rest between
 * climbs (none after the last). Only climbs this device can resolve.
 */
internal fun memberPlayback(members: List<ListMember>, angle: Int, restSeconds: Int): PreparedPlayback {
    val resolved = members.filter { it.climb != null }
    val items = resolved.mapIndexed { index, member ->
        PlaybackItem(
            climbUuid = member.climbUuid,
            angle = angle,
            restAfterSeconds = if (index < resolved.lastIndex) restSeconds else 0,
        )
    }
    val boards = resolved.mapNotNull { it.climb }.map { it.boardBrand to it.layoutId }.toSet()
    return PreparedPlayback(items, boards.size)
}
