package com.cruxcoach.android.updater

/**
 * Parsed, validated information about a remote release that has passed
 * the stable-release filter ([VersionChecker.isStableRelease]) and the
 * SemVer comparison against the currently installed app.
 *
 * Constructed by [CodebergReleaseClient]; consumed by every other
 * stage of the pipeline.
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val version: SemVer,
    val apkUrl: String,
    val apkSha256Url: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val releaseNotesMarkdown: String,
    /** Codeberg `html_url` — used by the cert-mismatch handoff (§5.4.3). */
    val releasePageUrl: String,
    val publishedAtEpochSeconds: Long,
)

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
    BLOCKED_CERT_MISMATCH,
}
