package com.cruxcoach.android.updater

/**
 * Strict major.minor.patch tuple used by the in-app updater to compare
 * the installed [`BuildConfig.VERSION_NAME`] against the version announced
 * by any [ReleaseSource]. Decouples comparison from the
 * `versionCode` formula in `build.gradle.kts`, which has changed in the
 * past and may change again.
 *
 * Only strictly-greater versions are ever accepted
 * ([VersionChecker.pickNewerStable]), which is what makes a downgrade
 * attack from a hostile source impossible — see [UpdateSource].
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        // Strict: optional leading "v", three numeric segments, no suffix.
        // Suffixes like "-dev.abc" / "-rc.1" / "-beta.2" must not parse —
        // the prerelease filter in [VersionChecker] depends on this.
        private val STRICT = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

        fun parseOrNull(raw: String): SemVer? {
            val m = STRICT.matchEntire(raw.trim()) ?: return null
            return SemVer(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues[3].toInt(),
            )
        }
    }
}
