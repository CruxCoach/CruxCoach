package com.cruxcoach.android.fips

/**
 * One aggregate transport counter reported by the native node.
 *
 * These replace the per-peer BLE attempt ring the previous FIPS revision
 * exposed, which upstream deleted. They are honestly aggregate: a counter says
 * *how many* dials timed out, never *which peer* they were for. Per-peer
 * attempt history stays where the information actually exists — the Kotlin
 * radio, which owns the dial and traces its address, direction and outcome.
 */
internal data class FipsTransportCounter(
    val instance: String,
    val name: String,
    val value: Long,
)

/** A counter that moved between two polls, with the size of the move. */
internal data class FipsCounterDelta(
    val counter: FipsTransportCounter,
    val increase: Long,
)

internal object FipsTransportCounterCodec {
    /**
     * Parse the native `instance\tcounter\tvalue` lines.
     *
     * Malformed lines are skipped rather than failing the batch: a diagnostic
     * that disappears entirely because one line was unexpected is worse than a
     * partial one. Skips are reported by [parseFailures] so the loss is
     * visible instead of silent.
     */
    fun parse(raw: String): List<FipsTransportCounter> = raw.lineSequence()
        .filter(String::isNotBlank)
        .mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 3) return@mapNotNull null
            val value = fields[2].toLongOrNull() ?: return@mapNotNull null
            if (fields[0].isEmpty() || fields[1].isEmpty()) return@mapNotNull null
            FipsTransportCounter(fields[0], fields[1], value)
        }
        .toList()

    /** How many lines [parse] had to reject. */
    fun parseFailures(raw: String): Int {
        val lines = raw.lineSequence().filter(String::isNotBlank).count()
        return lines - parse(raw).size
    }

    /**
     * Counters that grew since the previous poll.
     *
     * Only increases are reported. A counter that reset to zero means the
     * native node was rebuilt (a realm switch, a Bluetooth cycle) and its
     * totals started over; reporting that as a negative "outcome" would invent
     * events that never happened.
     */
    fun deltas(
        previous: List<FipsTransportCounter>,
        current: List<FipsTransportCounter>,
    ): List<FipsCounterDelta> {
        val before = previous.associateBy { it.instance to it.name }
        return current.mapNotNull { counter ->
            val was = before[counter.instance to counter.name]?.value ?: 0L
            val increase = counter.value - was
            if (increase > 0) FipsCounterDelta(counter, increase) else null
        }
    }
}
