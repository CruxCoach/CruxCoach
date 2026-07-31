package com.cruxcoach.android.updater

import android.os.SystemClock
import android.util.Log
import com.cruxcoach.android.BuildConfig
import kotlinx.coroutines.CancellationException

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
 * Pure orchestration: network calls happen in the [ReleaseSource]
 * implementations, version picking in [VersionChecker], side effects
 * (notifications, download enqueues) in [UpdaterRepository].
 *
 * ### Source sweep (FEAT-050)
 *
 * **Every** discovery source is asked, every round, and the highest version
 * anyone reports wins. No source can veto or shadow another:
 *
 *  - `Success(release)` → a candidate; keep sweeping.
 *  - `Success(null)` / `NotModified` → this source has nothing newer, which
 *    says nothing about the others; keep sweeping.
 *  - `Error` → unreachable or broken; keep sweeping.
 *
 * An earlier design stopped at the first source that *answered*. That was
 * cheaper — one request in the healthy case — but it had a failure mode bad
 * enough to disqualify it: a source that is reachable but **frozen** (an
 * archived repo still serving its final release list) answers "nothing
 * newer" and thereby hides every source below it, silently freezing updates
 * for everyone. Note the asymmetry that made it so easy to miss — a host
 * that is fully *down* was harmless, because it failed and the walk
 * continued; only a *stale* one caused the freeze. Sweeping removes the
 * hazard entirely instead of relying on someone remembering to prune the
 * source list during a migration.
 *
 * The cost is one request per source per check rather than one per check.
 * The 2 h throttle still bounds it, ETags keep the forge and manifest
 * responses at 304, and the sweep runs at most once per throttle window.
 *
 * On a version tie the earlier (higher-priority) source keeps the slot, so
 * its URL stays the one tried first for the download.
 */
class UpdateChecker(
    private val preferences: UpdaterPreferences,
    private val sourceFactory: ReleaseSourceFactory,
    private val installSourceGate: InstallSourceGate,
    private val registry: UpdateSourceRegistry,
    private val deviceSupportGate: DeviceSupportGate = DeviceSupportGate(),
    /** Override only for tests — real calls use [SystemClock.elapsedRealtime]. */
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
) {

    suspend fun maybeCheck(trigger: Trigger): CheckOutcome {
        if (!installSourceGate.selfUpdateAllowed()) {
            Log.i(TAG, "event=check_skipped trigger=$trigger reason=install_source_gated")
            return CheckOutcome.Skipped(reason = "install_source_gated")
        }
        // Stop before touching the network. The next release raises minSdk
        // past this device, so anything found here could only be downloaded,
        // verified, handed to Android and then rejected for minSdk — a
        // recurring unexplained failure instead of a clear end of support.
        if (!deviceSupportGate.receivesFutureUpdates()) {
            Log.i(TAG, "event=check_skipped trigger=$trigger reason=device_end_of_support")
            return CheckOutcome.Skipped(reason = REASON_END_OF_SUPPORT)
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

        val sources = registry.discoverySources()
        if (sources.isEmpty()) {
            // Cannot happen with a non-empty EMBEDDED list, but a fork that
            // empties it should fail loudly rather than silently never check.
            Log.w(TAG, "event=check_error reason=no_discovery_sources")
            preferences.update {
                it.copy(lastCheckResult = CheckResult.ERROR, lastErrorAtEpochMs = nowMs())
            }
            return CheckOutcome.Error("no_discovery_sources")
        }

        var lastError: String? = null
        var answeredCount = 0
        var sawNotModified = false
        var best: Candidate? = null
        val freshEtags = mutableMapOf<String, String>()

        for (source in sources) {
            val client = sourceFactory.create(source) ?: continue
            val etag = snapshot.lastCheckEtags[source.id]
            // A source that throws must not take the whole sweep down with it —
            // but CancellationException is not a source failure, it means the
            // caller went away. Swallowing it into Result.Error would keep the
            // loop running and hammer the remaining sources after the scope is
            // already dead. Same rethrow convention as UpdaterRepository and
            // ZapstoreReleaseClient.
            val result = try {
                client.fetchNewerThan(installed, etag)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ReleaseSource.Result.Error(error.message ?: error.javaClass.simpleName)
            }
            when (result) {

                is ReleaseSource.Result.Error -> {
                    Log.i(TAG, "event=source_failed id=${source.id} reason=${result.message}")
                    lastError = result.message
                }

                is ReleaseSource.Result.NotModified -> {
                    // Same answer as last time from this source. Contributes no
                    // candidate, but says nothing about the others.
                    answeredCount++
                    sawNotModified = true
                }

                is ReleaseSource.Result.Success -> {
                    answeredCount++
                    result.etag?.takeIf { it.isNotBlank() }?.let { freshEtags[source.id] = it }
                    val release = result.release
                    val incumbent = best
                    if (release != null && (incumbent == null || release.version > incumbent.release.version)) {
                        // Strictly greater, so on a tie the earlier — that is,
                        // higher-priority — source keeps the slot and its URL
                        // stays the one tried first.
                        best = Candidate(source, release)
                    }
                }
            }
        }

        val winner = best
        if (winner != null) {
            val release = winner.release
            val info = UpdateInfo(
                tagName = release.tagName,
                versionName = release.version.toString(),
                version = release.version,
                downloadUrls = registry.downloadUrlsFor(
                    tagName = release.tagName,
                    apkSha256 = release.apkSha256,
                    primaryUrl = release.apkUrl.takeIf { it.isNotBlank() },
                ),
                apkSha256Url = release.apkSha256Url,
                apkSizeBytes = release.apkSizeBytes,
                apkSha256 = release.apkSha256,
                releaseNotesMarkdown = release.releaseNotesMarkdown,
                releasePageUrl = release.releasePageUrl,
                publishedAtEpochSeconds = release.publishedAtEpochSeconds,
            )
            if (info.downloadUrls.isNotEmpty()) {
                persistPendingUpdate(info, freshEtags)
                Log.i(
                    TAG,
                    "event=update_available trigger=$trigger source=${winner.source.id} " +
                        "tag=${info.tagName} apkSize=${info.apkSizeBytes} " +
                        "downloadSources=${info.downloadUrls.size} sourcesAnswered=$answeredCount",
                )
                return CheckOutcome.Update(info)
            }
            // Announced but unreachable: a newer version exists and no
            // configured source can serve its bytes. Reporting NO_UPDATE here
            // would be a lie that hides a broken release, so this is an error —
            // which also leaves the throttle open so the next trigger retries.
            Log.w(
                TAG,
                "event=release_no_download_urls id=${winner.source.id} tag=${release.tagName}",
            )
            preferences.update {
                it.copy(
                    lastCheckEtags = it.lastCheckEtags + freshEtags,
                    lastCheckResult = CheckResult.ERROR,
                    lastErrorAtEpochMs = nowMs(),
                )
            }
            return CheckOutcome.Error("no_download_urls")
        }

        if (answeredCount > 0) {
            // At least one source spoke and none of them had anything newer.
            val outcome = if (sawNotModified && freshEtags.isEmpty()) {
                CheckResult.NOT_MODIFIED
            } else {
                CheckResult.NO_UPDATE
            }
            preferences.update {
                it.copy(
                    lastCheckAtEpochMs = nowMs(),
                    lastCheckBootRealtime = elapsedRealtimeProvider(),
                    lastCheckEtags = it.lastCheckEtags + freshEtags,
                    lastCheckResult = outcome,
                )
            }
            Log.i(
                TAG,
                "event=check_no_update trigger=$trigger answered=$answeredCount " +
                    "failed=${sources.size - answeredCount}",
            )
            return if (outcome == CheckResult.NOT_MODIFIED) {
                CheckOutcome.NotModified
            } else {
                CheckOutcome.NoUpdate
            }
        }

        // Every source failed. Do NOT stamp lastCheckBootRealtime — the 2 h
        // throttle would otherwise block the NETWORK_AVAILABLE retry after
        // the user regains internet. Typical trigger: fresh install via local
        // share, device still on the offline hotspot when APP_FOREGROUND
        // fires.
        preferences.update {
            it.copy(lastCheckResult = CheckResult.ERROR, lastErrorAtEpochMs = nowMs())
        }
        val message = lastError ?: "all_sources_failed"
        Log.i(TAG, "Check failed on all ${sources.size} sources ($message) — will retry on next trigger")
        return CheckOutcome.Error(message)
    }

    /**
     * A newer version than the one currently pending is a FRESH surface:
     * clear the dismiss / re-arm state carried over from the prior pending
     * version. Otherwise a user who swiped away (say) the 0.1.4 notification
     * keeps notifDismissedAtEpochMs set, and if this 0.2.0 notification is
     * ever missed at post time (notifications briefly off / process death),
     * reNotifyPendingUpdateIfAny would bail on the stale flag and the
     * ETag-304 short-circuit would stop re-detection — so 0.2.0 would never
     * resurface. Dismiss is per-version, not sticky forever.
     */
    private suspend fun persistPendingUpdate(info: UpdateInfo, freshEtags: Map<String, String>) {
        preferences.update {
            // PackageInstaller owns the current session. Keep its release
            // metadata intact; after success the repository clears the
            // ETag/throttle and checks the new app version.
            if (it.pipelineStage == PipelineStage.INSTALLING) return@update it
            val isNewerThanPending = info.versionName != it.pendingVersionName
            it.copy(
                lastCheckAtEpochMs = nowMs(),
                lastCheckBootRealtime = elapsedRealtimeProvider(),
                lastCheckEtags = it.lastCheckEtags + freshEtags,
                lastCheckResult = CheckResult.SUCCESS,
                pendingTagName = info.tagName,
                pendingVersionName = info.versionName,
                pendingDownloadUrls = info.downloadUrls,
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
    }

    private data class Candidate(val source: UpdateSource, val release: DiscoveredRelease)

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
        /** [CheckOutcome.Skipped] reason when the device is past its last release. */
        const val REASON_END_OF_SUPPORT = "device_end_of_support"
        /** §6.12 — 2 h throttle between non-manual checks. */
        const val MIN_CHECK_INTERVAL_MS: Long = 2L * 60L * 60L * 1000L
        /** UI-side soft rate limit on the "Jetzt prüfen" button. */
        const val MANUAL_UI_COOLDOWN_MS: Long = 10L * 1000L
    }
}

/**
 * Builds the right [ReleaseSource] for a configured [UpdateSource].
 *
 * Kept as a seam so the checker stays free of transport concerns and tests
 * can substitute sources without touching DI or the network.
 */
class ReleaseSourceFactory(
    private val forgeClient: ForgeReleaseClient,
    private val zapstoreClient: ZapstoreReleaseClient,
    private val manifestHttpClient: okhttp3.OkHttpClient,
) {
    fun create(source: UpdateSource): ReleaseSource? = when (source.kind) {
        UpdateSource.Kind.FORGE -> ForgeDiscoverySource(source, forgeClient)
        UpdateSource.Kind.NOSTR -> NostrDiscoverySource(source, zapstoreClient)
        UpdateSource.Kind.MANIFEST -> ManifestDiscoverySource(source, manifestHttpClient)
        // Download-only; never handed out for discovery.
        UpdateSource.Kind.BLOSSOM -> null
    }
}
