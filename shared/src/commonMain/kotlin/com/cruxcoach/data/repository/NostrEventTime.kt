package com.cruxcoach.data.repository

import kotlin.time.Instant

/** Strictly advances a replaceable event timestamp despite ties or clock rollback. */
fun monotonicCreatedAtSeconds(nowSeconds: Long, priorIso: String?): Long {
    val priorEpoch = priorIso?.let { runCatching { Instant.parse(it).epochSeconds }.getOrNull() }
    return if (priorEpoch != null) maxOf(nowSeconds, priorEpoch + 1L) else nowSeconds
}
