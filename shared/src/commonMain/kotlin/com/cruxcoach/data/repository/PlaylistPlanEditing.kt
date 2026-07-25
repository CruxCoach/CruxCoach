package com.cruxcoach.data.repository

private const val DEFAULT_AUTO_PLAYBACK_REST_SECONDS = 180L

private const val MIN_PLAYBACK_REST_SECONDS = 10L
private const val MAX_PLAYBACK_REST_SECONDS = 3600L

/**
 * Estimate a useful rest for a newly appended playlist climb.
 *
 * The most recent three explicit rests best reflect the plan the user is
 * currently editing. With fewer than three, all available recent rests are
 * used. A configured list default is the first fallback; three minutes is the
 * final fallback for plans that have never contained a rest.
 */
fun inferAutoPlaybackRestSeconds(
    previousRestSeconds: Iterable<Long?>,
    configuredFallbackSeconds: Long = 0L,
): Long {
    val recent = previousRestSeconds
        .mapNotNull { it?.takeIf { seconds -> seconds in MIN_PLAYBACK_REST_SECONDS..MAX_PLAYBACK_REST_SECONDS } }
        .takeLast(3)
    if (recent.isNotEmpty()) {
        // Positive integer rounding keeps the stored value deterministic on
        // every platform and avoids introducing floating-point differences.
        return (recent.sum() + recent.size / 2L) / recent.size
    }
    return configuredFallbackSeconds
        .takeIf { it in MIN_PLAYBACK_REST_SECONDS..MAX_PLAYBACK_REST_SECONDS }
        ?: DEFAULT_AUTO_PLAYBACK_REST_SECONDS
}

/** Build a fresh explicit plan with a rest between every pair of climbs. */
fun playbackStepsWithAutoRests(
    climbUuids: List<String>,
    angle: Long?,
    restSeconds: Long,
): List<NewListPlaybackStep> {
    if (climbUuids.isEmpty()) return emptyList()
    val duration = restSeconds.coerceIn(MIN_PLAYBACK_REST_SECONDS, MAX_PLAYBACK_REST_SECONDS)
    return buildList(climbUuids.size * 2 - 1) {
        climbUuids.forEachIndexed { index, uuid ->
            if (index > 0) {
                add(NewListPlaybackStep(climbUuid = null, restSeconds = duration))
            }
            add(NewListPlaybackStep(climbUuid = uuid, angle = angle))
        }
    }
}
