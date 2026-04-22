package com.cruxcoach.android.updater

import android.os.SystemClock
import android.util.Log
import com.cruxcoach.android.BuildConfig

/**
 * Single coalescing check entry point (§5.1, §6.12).
 *
 * All triggers (app `ON_START`, `onAvailable`, periodic worker, manual
 * "Jetzt prüfen") funnel through [maybeCheck]. The clock-skew-immune
 * throttle uses [SystemClock.elapsedRealtime] — wall-clock can jump
 * because of NTP / timezone / battery-pulled reboots, but
 * elapsedRealtime only counts wall-forward-while-booted, so a check
 * either ran since last boot or it didn't.
 *
 * Pure orchestration: network calls happen in [CodebergReleaseClient],
 * version picking in [VersionChecker], side effects (notifications,
 * download enqueues) in [UpdaterRepository].
 */
class UpdateChecker(
    private val preferences: UpdaterPreferences,
    private val client: CodebergReleaseClient,
    private val installSourceGate: InstallSourceGate,
    /** Override only for tests — real calls use [SystemClock.elapsedRealtime]. */
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {

    suspend fun maybeCheck(trigger: Trigger): CheckOutcome {
        if (!installSourceGate.selfUpdateAllowed()) {
            return CheckOutcome.Skipped(reason = "install_source_gated")
        }
        val snapshot = preferences.snapshot()
        if (!snapshot.autoCheckEnabled && trigger != Trigger.MANUAL) {
            return CheckOutcome.Skipped(reason = "auto_check_disabled")
        }
        if (trigger != Trigger.MANUAL) {
            val sinceBoot = elapsedRealtimeProvider() - snapshot.lastCheckBootRealtime
            if (snapshot.lastCheckBootRealtime > 0 && sinceBoot < MIN_CHECK_INTERVAL_MS) {
                return CheckOutcome.Throttled(remainingMs = MIN_CHECK_INTERVAL_MS - sinceBoot)
            }
        }

        val installed = SemVer.parseOrNull(BuildConfig.VERSION_NAME)
        if (installed == null) {
            preferences.update { it.copy(lastCheckResult = CheckResult.ERROR, lastErrorAtEpochMs = nowMs()) }
            Log.w(TAG, "Installed version '${BuildConfig.VERSION_NAME}' is not strict SemVer")
            return CheckOutcome.Error("installed_version_unparseable")
        }

        return when (val response = client.fetchReleases(etag = snapshot.lastCheckEtag)) {
            is CodebergReleaseClient.Result.NotModified -> {
                preferences.update {
                    it.copy(
                        lastCheckAtEpochMs = nowMs(),
                        lastCheckBootRealtime = elapsedRealtimeProvider(),
                        lastCheckResult = CheckResult.NOT_MODIFIED,
                    )
                }
                CheckOutcome.NotModified
            }
            is CodebergReleaseClient.Result.Error -> {
                // Do NOT stamp lastCheckBootRealtime on network errors — the
                // 2 h throttle would otherwise block the NETWORK_AVAILABLE
                // retry after the user regains internet. Typical trigger:
                // fresh install via local share, device still on the
                // offline hotspot when APP_FOREGROUND fires.
                preferences.update {
                    it.copy(
                        lastCheckResult = CheckResult.ERROR,
                        lastErrorAtEpochMs = nowMs(),
                    )
                }
                Log.i(TAG, "Check failed (${response.message}) — will retry on next trigger")
                CheckOutcome.Error(response.message)
            }
            is CodebergReleaseClient.Result.Success -> {
                val chosen = VersionChecker.pickNewerStable(response.releases, installed)
                if (chosen == null) {
                    val anyStable = response.releases.any(VersionChecker::isStableRelease)
                    preferences.update {
                        it.copy(
                            lastCheckAtEpochMs = nowMs(),
                            lastCheckBootRealtime = elapsedRealtimeProvider(),
                            lastCheckEtag = response.etag ?: it.lastCheckEtag,
                            lastCheckResult = if (anyStable) CheckResult.NO_UPDATE else CheckResult.NO_UPDATE_STABLE,
                        )
                    }
                    return CheckOutcome.NoUpdate
                }

                val info = buildUpdateInfo(chosen) ?: run {
                    preferences.update {
                        it.copy(
                            lastCheckBootRealtime = elapsedRealtimeProvider(),
                            lastCheckResult = CheckResult.ERROR,
                            lastErrorAtEpochMs = nowMs(),
                        )
                    }
                    return CheckOutcome.Error("release_malformed")
                }

                val resolvedSha = client.fetchSha256(info.apkSha256Url) ?: run {
                    // Transient network error (same reasoning as Result.Error
                    // above) — don't stamp lastCheckBootRealtime.
                    preferences.update {
                        it.copy(
                            lastCheckResult = CheckResult.ERROR,
                            lastErrorAtEpochMs = nowMs(),
                        )
                    }
                    return CheckOutcome.Error("sha256_asset_fetch_failed")
                }
                val infoWithSha = info.copy(apkSha256 = resolvedSha)

                preferences.update {
                    it.copy(
                        lastCheckAtEpochMs = nowMs(),
                        lastCheckBootRealtime = elapsedRealtimeProvider(),
                        lastCheckEtag = response.etag ?: it.lastCheckEtag,
                        lastCheckResult = CheckResult.SUCCESS,
                        pendingTagName = infoWithSha.tagName,
                        pendingVersionName = infoWithSha.versionName,
                        pendingApkUrl = infoWithSha.apkUrl,
                        pendingApkSha256 = infoWithSha.apkSha256,
                        pendingApkSizeBytes = infoWithSha.apkSizeBytes,
                        pendingApkSha256Url = infoWithSha.apkSha256Url,
                        pendingReleasePageUrl = infoWithSha.releasePageUrl,
                        pendingReleaseNotesMarkdown = infoWithSha.releaseNotesMarkdown,
                        pipelineStage = PipelineStage.PENDING_DOWNLOAD,
                    )
                }
                CheckOutcome.Update(infoWithSha)
            }
        }
    }

    private fun buildUpdateInfo(release: CodebergRelease): UpdateInfo? {
        val tag = release.tagName
        val version = SemVer.parseOrNull(tag) ?: return null
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: return null
        val shaAsset = release.assets.firstOrNull { it.name.endsWith(".apk.sha256", ignoreCase = true) }
            ?: return null
        val pageUrl = release.htmlUrl ?: return null
        return UpdateInfo(
            tagName = tag,
            versionName = version.toString(),
            version = version,
            apkUrl = apkAsset.browserDownloadUrl,
            apkSha256Url = shaAsset.browserDownloadUrl,
            apkSizeBytes = apkAsset.size,
            apkSha256 = "",
            releaseNotesMarkdown = release.body.orEmpty(),
            releasePageUrl = pageUrl,
            publishedAtEpochSeconds = 0L,
        )
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    enum class Trigger { APP_FOREGROUND, NETWORK_AVAILABLE, PERIODIC, MANUAL }

    sealed interface CheckOutcome {
        data class Skipped(val reason: String) : CheckOutcome
        data class Throttled(val remainingMs: Long) : CheckOutcome
        data object NotModified : CheckOutcome
        data object NoUpdate : CheckOutcome
        data class Update(val info: UpdateInfo) : CheckOutcome
        data class Error(val message: String) : CheckOutcome
    }

    companion object {
        private const val TAG = "UpdateChecker"
        /** §6.12 — 2 h throttle between non-manual checks. */
        const val MIN_CHECK_INTERVAL_MS: Long = 2L * 60L * 60L * 1000L
        /** UI-side soft rate limit on the "Jetzt prüfen" button. */
        const val MANUAL_UI_COOLDOWN_MS: Long = 10L * 1000L
    }
}
