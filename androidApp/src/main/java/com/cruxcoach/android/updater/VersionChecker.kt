package com.cruxcoach.android.updater

/**
 * Filters and ranks the [ForgeRelease]s returned by the list endpoint
 * down to "the highest stable release strictly newer than the currently
 * installed version", or null if no such release exists. Pure / no I/O.
 *
 * Two independent filters must both agree before a release is considered
 * (§6.11): the forge `prerelease`/`draft` flags, AND the strict tag
 * shape (`v?MAJOR.MINOR.PATCH` with no suffix). Either filter alone
 * would be correct today; both together insulate us from CI bugs and
 * from human error on manual `-rc` releases.
 */
object VersionChecker {

    fun isStableRelease(release: ForgeRelease): Boolean {
        if (release.prerelease || release.draft) return false
        return SemVer.parseOrNull(release.tagName) != null
    }

    /**
     * Returns the highest stable release whose [SemVer] is strictly greater
     * than [installed], or null if no candidate qualifies. The list does
     * not need to be pre-sorted — forges return newest-first, but we
     * still scan all entries to tolerate out-of-order CI publishing.
     */
    fun pickNewerStable(
        candidates: List<ForgeRelease>,
        installed: SemVer,
    ): ForgeRelease? {
        return candidates
            .asSequence()
            .filter { isStableRelease(it) }
            .mapNotNull { release ->
                val version = SemVer.parseOrNull(release.tagName) ?: return@mapNotNull null
                release to version
            }
            .filter { (_, v) -> v > installed }
            .maxByOrNull { (_, v) -> v }
            ?.first
    }
}
