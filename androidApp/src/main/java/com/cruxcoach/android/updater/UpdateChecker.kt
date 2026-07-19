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
    private val zapstoreClient: ZapstoreReleaseClient? = null,
    /** Override only for tests — real calls use [SystemClock.elapsedRealtime]. */
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {

    suspend fun maybeCheck(trigger: Trigger): CheckOutcome {
        if (!installSourceGate.selfUpdateAllowed()) {
            Log.i(TAG, "event=check_skipped trigger=$trigger reason=install_source_gated")
            return CheckOutcome.Skipped(reason = "install_source_gated")
        }
        val snapshot = preferences.snapshot()
        if (!snapshot.autoCheckEnabled && trigger != Trigger.MANUAL) {
            Log.i(TAG, "event=check_skipped trigger=$trigger reason=auto_check_disabled")
            return CheckOutcome.Skipped(reason = "auto_check_disabled")
        }
        if (trigger != Trigger.MANUAL) {
            val now = elapsedRealtimeProvider()
            val sinceBoot = now - snapshot.lastCheckBootRealtime
            // Reboot guard: elapsedRealtime() resets to 0 on every boot, but
            // lastCheckBootRealtime is persisted in DataStore and never reset (no
            // BOOT_COMPLETED, by design). So after a reboot the stored value is
            // LARGER than the current uptime → sinceBoot goes negative and the
            // old `negative < interval` test throttled every non-manual check for
            // hours/days (until uptime climbed past the stale value). A now <
            // lastCheckBootRealtime means we rebooted since the last check → the
            // throttle window is meaningless, so allow the check. Only throttle
            // within the SAME boot session.
            val rebootedSinceLastCheck = now < snapshot.lastCheckBootRealtime
            if (!rebootedSinceLastCheck &&
                snapshot.lastCheckBootRealtime > 0 &&
                sinceBoot < MIN_CHECK_INTERVAL_MS
            ) {
                val remaining = MIN_CHECK_INTERVAL_MS - sinceBoot
                Log.d(TAG, "event=check_throttled trigger=$trigger remainingMs=$remaining")
                return CheckOutcome.Throttled(remainingMs = remaining)
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
                Log.d(TAG, "event=check_not_modified trigger=$trigger")
                CheckOutcome.NotModified
            }
            is CodebergReleaseClient.Result.Error -> {
                val zapstoreOutcome = checkZapstoreFallback(
                    installed = installed,
                    trigger = trigger,
                )
                if (zapstoreOutcome != null) return zapstoreOutcome
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
                    Log.i(TAG, "event=check_no_update trigger=$trigger anyStable=$anyStable")
                    return CheckOutcome.NoUpdate
                }

                val info = buildUpdateInfo(chosen) ?: run {
                    val zapstoreOutcome = checkZapstoreFallback(
                        installed = installed,
                        trigger = trigger,
                        requireUpdate = true,
                    )
                    if (zapstoreOutcome != null) return zapstoreOutcome
                    preferences.update {
                        it.copy(
                            lastCheckBootRealtime = elapsedRealtimeProvider(),
                            lastCheckResult = CheckResult.ERROR,
                            lastErrorAtEpochMs = nowMs(),
                        )
                    }
                    Log.w(TAG, "event=check_error trigger=$trigger reason=release_malformed tag=${chosen.tagName}")
                    return CheckOutcome.Error("release_malformed")
                }

                val resolvedSha = client.fetchSha256(info.apkSha256Url) ?: run {
                    val zapstoreOutcome = checkZapstoreFallback(
                        installed = installed,
                        trigger = trigger,
                        requireUpdate = true,
                    )
                    if (zapstoreOutcome != null) return zapstoreOutcome
                    // Transient network error (same reasoning as Result.Error
                    // above) — don't stamp lastCheckBootRealtime.
                    preferences.update {
                        it.copy(
                            lastCheckResult = CheckResult.ERROR,
                            lastErrorAtEpochMs = nowMs(),
                        )
                    }
                    Log.w(TAG, "event=check_error trigger=$trigger reason=sha256_asset_fetch_failed")
                    return CheckOutcome.Error("sha256_asset_fetch_failed")
                }
                val infoWithSha = info.copy(
                    apkSha256 = resolvedSha,
                    apkFallbackUrl = BuildConfig.ZAPSTORE_CDN_BASE_URL.trimEnd('/') + "/" + resolvedSha,
                )

                // A newer version than the one currently pending is a FRESH
                // surface: clear the dismiss / re-arm state carried over from the
                // prior pending version. Otherwise a user who swiped away (say)
                // the 0.1.4 notification keeps notifDismissedAtEpochMs set, and if
                // this 0.2.0 notification is ever missed at post time (notifications
                // briefly off / process death), reNotifyPendingUpdateIfAny would
                // bail on the stale flag and the ETag-304 short-circuit would stop
                // re-detection — so 0.2.0 would never resurface. Dismiss is
                // per-version, not sticky forever.
                preferences.update {
                    // PackageInstaller owns the current session. Keep its
                    // release metadata intact; after success the repository
                    // clears the ETag/throttle and checks the new app version.
                    if (it.pipelineStage == PipelineStage.INSTALLING) return@update it
                    val isNewerThanPending = infoWithSha.versionName != it.pendingVersionName
                    it.copy(
                        lastCheckAtEpochMs = nowMs(),
                        lastCheckBootRealtime = elapsedRealtimeProvider(),
                        lastCheckEtag = response.etag ?: it.lastCheckEtag,
                        lastCheckResult = CheckResult.SUCCESS,
                        pendingTagName = infoWithSha.tagName,
                        pendingVersionName = infoWithSha.versionName,
                        pendingApkUrl = infoWithSha.apkUrl,
                        pendingApkFallbackUrl = infoWithSha.apkFallbackUrl,
                        pendingApkSha256 = infoWithSha.apkSha256,
                        pendingApkSizeBytes = infoWithSha.apkSizeBytes,
                        pendingApkSha256Url = infoWithSha.apkSha256Url,
                        pendingReleasePageUrl = infoWithSha.releasePageUrl,
                        pendingReleaseNotesMarkdown = infoWithSha.releaseNotesMarkdown,
                        pendingDownloadSourceIndex = if (isNewerThanPending) 0 else it.pendingDownloadSourceIndex,
                        pipelineStage = if (isNewerThanPending || it.pipelineStage == PipelineStage.NONE) {
                            PipelineStage.PENDING_DOWNLOAD
                        } else {
                            it.pipelineStage
                        },
                        notifDismissedAtEpochMs = if (isNewerThanPending) null else it.notifDismissedAtEpochMs,
                        notifReArmCount = if (isNewerThanPending) 0 else it.notifReArmCount,
                    )
                }
                Log.i(TAG, "event=update_available trigger=$trigger tag=${infoWithSha.tagName} apkSize=${infoWithSha.apkSizeBytes}")
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

    /**
     * Codeberg-independent discovery path. Zapstore assets are accepted only
     * after [ZapstoreReleaseClient] has verified their Nostr signature,
     * publisher, package id, content hash and installed signer certificate.
     */
    private suspend fun checkZapstoreFallback(
        installed: SemVer,
        trigger: Trigger,
        requireUpdate: Boolean = false,
    ): CheckOutcome? {
        val zapstore = zapstoreClient ?: return null
        return when (val response = zapstore.fetchReleases()) {
            is ZapstoreReleaseClient.Result.Error -> {
                Log.w(TAG, "event=zapstore_fallback_failed reason=${response.message}")
                null
            }
            is ZapstoreReleaseClient.Result.Success -> {
                val chosen = response.releases
                    .mapNotNull { release ->
                        val version = SemVer.parseOrNull(release.versionName) ?: return@mapNotNull null
                        if (version > installed) version to release else null
                    }
                    .maxByOrNull { it.first }
                    ?.second
                if (chosen == null) {
                    if (requireUpdate) return null
                    preferences.update {
                        it.copy(
                            lastCheckAtEpochMs = nowMs(),
                            lastCheckBootRealtime = elapsedRealtimeProvider(),
                            lastCheckResult = CheckResult.NO_UPDATE,
                        )
                    }
                    Log.i(TAG, "event=check_no_update source=zapstore trigger=$trigger")
                    CheckOutcome.NoUpdate
                } else {
                    val version = SemVer.parseOrNull(chosen.versionName) ?: return null
                    val tag = "v$version"
                    val info = UpdateInfo(
                        tagName = tag,
                        versionName = version.toString(),
                        version = version,
                        apkUrl = chosen.apkUrl,
                        apkFallbackUrl = codebergApkUrl(tag),
                        apkSha256Url = "",
                        apkSizeBytes = chosen.apkSizeBytes,
                        apkSha256 = chosen.apkSha256,
                        releaseNotesMarkdown = chosen.releaseNotesMarkdown,
                        releasePageUrl = BuildConfig.ZAPSTORE_APP_URL,
                        publishedAtEpochSeconds = chosen.publishedAtEpochSeconds,
                    )
                    preferences.update {
                        if (it.pipelineStage == PipelineStage.INSTALLING) return@update it
                        val isNewerThanPending = info.versionName != it.pendingVersionName
                        it.copy(
                            lastCheckAtEpochMs = nowMs(),
                            lastCheckBootRealtime = elapsedRealtimeProvider(),
                            lastCheckResult = CheckResult.SUCCESS,
                            pendingTagName = info.tagName,
                            pendingVersionName = info.versionName,
                            pendingApkUrl = info.apkUrl,
                            pendingApkFallbackUrl = info.apkFallbackUrl,
                            pendingApkSha256 = info.apkSha256,
                            pendingApkSizeBytes = info.apkSizeBytes,
                            pendingApkSha256Url = info.apkSha256Url,
                            pendingReleasePageUrl = info.releasePageUrl,
                            pendingReleaseNotesMarkdown = info.releaseNotesMarkdown,
                            pendingDownloadSourceIndex = if (isNewerThanPending) 0 else it.pendingDownloadSourceIndex,
                            pipelineStage = if (isNewerThanPending || it.pipelineStage == PipelineStage.NONE) {
                                PipelineStage.PENDING_DOWNLOAD
                            } else {
                                it.pipelineStage
                            },
                            notifDismissedAtEpochMs = if (isNewerThanPending) null else it.notifDismissedAtEpochMs,
                            notifReArmCount = if (isNewerThanPending) 0 else it.notifReArmCount,
                        )
                    }
                    Log.i(TAG, "event=update_available source=zapstore trigger=$trigger tag=$tag")
                    CheckOutcome.Update(info)
                }
            }
        }
    }

    private fun codebergApkUrl(tag: String): String {
        val webHost = BuildConfig.UPDATER_API_BASE.substringBefore("/api/", BuildConfig.UPDATER_API_BASE)
        return "$webHost/${BuildConfig.UPDATER_REPO_OWNER}/${BuildConfig.UPDATER_REPO_NAME}" +
            "/releases/download/$tag/${BuildConfig.UPDATER_REPO_NAME}-$tag.apk"
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
