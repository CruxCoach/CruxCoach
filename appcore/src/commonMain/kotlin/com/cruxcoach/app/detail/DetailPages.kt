package com.cruxcoach.app.detail

/**
 * The climbs the detail screen can swipe through: Android's
 * `BoardClimbDetailScreen` pager over the list it was opened from.
 *
 * Rules kept from Android: the list is de-duplicated (the pager keys pages by
 * uuid), and a climb that is not in the list gets a single page of its own —
 * a stale list must never open the detail on a different climb than the one
 * tapped.
 */
class DetailPages private constructor(val uuids: List<String>, val index: Int) {

    val count: Int get() = uuids.size
    val current: String get() = uuids.getOrElse(index) { "" }
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < uuids.size - 1

    /** Null when [target] is outside the list or already shown. */
    fun moveTo(target: Int): DetailPages? =
        if (target !in uuids.indices || target == index) null else DetailPages(uuids, target)

    companion object {
        fun of(listUuids: List<String>, initialUuid: String): DetailPages {
            val unique = listUuids.filter { it.isNotEmpty() }.distinct()
            val at = unique.indexOf(initialUuid)
            return if (at >= 0) DetailPages(unique, at) else single(initialUuid)
        }

        fun single(uuid: String): DetailPages = DetailPages(listOf(uuid), 0)
    }
}
