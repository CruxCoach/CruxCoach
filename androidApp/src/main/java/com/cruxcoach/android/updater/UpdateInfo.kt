package com.cruxcoach.android.updater

/**
 * Parsed, validated information about a remote release that has passed
 * the stable-release filter ([VersionChecker.isStableRelease]) and the
 * SemVer comparison against the currently installed app.
 *
 * Constructed from whichever [ReleaseSource] answered first; consumed by
 * every other stage of the pipeline.
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val version: SemVer,
    /**
     * Every URL that can serve these exact bytes, in try-order, announcing
     * source first. Built by [UpdateSourceRegistry.downloadUrlsFor], so it
     * grows and shrinks with the runtime source list rather than with the
     * app version — which is the whole point: retiring a release host must
     * not require shipping a new APK.
     *
     * All entries address the same [apkSha256] and each is gated identically
     * by [IntegrityVerifier], which is why walking the list is safe no
     * matter who is on it.
     */
    val downloadUrls: List<String>,
    val apkSha256Url: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val releaseNotesMarkdown: String,
    /** Shown by the cert-mismatch handoff (§5.4.3). */
    val releasePageUrl: String,
    val publishedAtEpochSeconds: Long,
) {
    /** The source tried first. */
    val apkUrl: String? get() = downloadUrls.firstOrNull()
}

/** Outcome of one check round. Persisted in [UpdaterPreferences]. */
enum class CheckResult {
    SUCCESS,
    NO_UPDATE,
    NO_UPDATE_STABLE,
    NOT_MODIFIED,
    ERROR,
    BLOCKED_CERT_MISMATCH,
}

/** Pipeline stage tracked across process restarts. */
enum class PipelineStage {
    NONE,
    PENDING_DOWNLOAD,
    DOWNLOADING,
    READY_TO_INSTALL,
    INSTALLING,
    BLOCKED_CERT_MISMATCH,
}
