package com.cruxcoach.android.updater

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * DataStore-backed bulk state for the updater (§3.3, §6.5).
 *
 * Kept separate from [com.cruxcoach.android.data.UserPreferences] to
 * isolate updater concerns and to keep that class — already large — from
 * growing further. The TOFU cert pin is NOT stored here; it lives in its
 * own HMAC-sealed file ([UpdaterPinStore]) because integrity (not
 * confidentiality) is the threat the pin defends against.
 */
class UpdaterPreferences(private val store: DataStore<Preferences>) {

    val state: Flow<UpdaterState> = store.data.map { it.toUpdaterState() }

    suspend fun snapshot(): UpdaterState = state.first()

    /**
     * Apply a pure transform to the persisted state inside one `edit`
     * block — avoids races when several call sites mutate concurrently.
     */
    suspend fun update(transform: (UpdaterState) -> UpdaterState) {
        store.edit { prefs ->
            val current = prefs.toUpdaterState()
            val next = transform(current)
            prefs.writeFrom(next)
        }
    }

    private fun Preferences.toUpdaterState(): UpdaterState = UpdaterState(
        lastCheckAtEpochMs = this[Keys.LAST_CHECK_AT_MS],
        lastCheckBootRealtime = this[Keys.LAST_CHECK_BOOT_REALTIME] ?: 0L,
        lastCheckEtags = readEtags(),
        lastCheckResult = this[Keys.LAST_CHECK_RESULT]?.let { runCatching { CheckResult.valueOf(it) }.getOrNull() }
            ?: CheckResult.NO_UPDATE,
        lastErrorAtEpochMs = this[Keys.LAST_ERROR_AT_MS],
        pendingDownloadId = this[Keys.PENDING_DOWNLOAD_ID]?.takeIf { it > 0 },
        pendingTagName = this[Keys.PENDING_TAG_NAME],
        pendingVersionName = this[Keys.PENDING_VERSION_NAME],
        pendingDownloadUrls = readDownloadUrls(),
        pendingApkSha256 = this[Keys.PENDING_APK_SHA256],
        pendingApkSizeBytes = this[Keys.PENDING_APK_SIZE_BYTES],
        pendingApkSha256Url = this[Keys.PENDING_APK_SHA256_URL],
        pendingReleasePageUrl = this[Keys.PENDING_RELEASE_PAGE_URL],
        pendingReleaseNotesMarkdown = this[Keys.PENDING_RELEASE_NOTES],
        pendingDownloadSourceIndex = this[Keys.PENDING_DOWNLOAD_SOURCE_INDEX] ?: 0,
        pendingAllowMobile = this[Keys.PENDING_ALLOW_MOBILE] ?: false,
        updateSourcesManifestJson = this[Keys.UPDATE_SOURCES_MANIFEST],
        updateSourcesFetchedAtEpochMs = this[Keys.UPDATE_SOURCES_FETCHED_AT_MS],
        endOfSupportNoticeShown = this[Keys.END_OF_SUPPORT_NOTICE_SHOWN] ?: false,
        pipelineStage = this[Keys.PIPELINE_STAGE]?.let { runCatching { PipelineStage.valueOf(it) }.getOrNull() }
            ?: PipelineStage.NONE,
        autoCheckEnabled = this[Keys.AUTO_CHECK_ENABLED] ?: true,
        automationMode = this[Keys.AUTOMATION_MODE]
            ?.let { runCatching { UpdateAutomationMode.valueOf(it) }.getOrNull() }
            ?: UpdateAutomationMode.NOTIFY,
        autoDownloadOnMobile = this[Keys.AUTO_DOWNLOAD_ON_MOBILE] ?: false,
        anonymousUpdateMetricsEnabled = this[Keys.ANONYMOUS_UPDATE_METRICS_ENABLED] ?: true,
        lastAnonymousMetricsAttemptVersion = this[Keys.LAST_ANONYMOUS_METRICS_ATTEMPT_VERSION],
        lastNotifiedTagName = this[Keys.LAST_NOTIFIED_TAG],
        notifDismissedAtEpochMs = this[Keys.NOTIF_DISMISSED_AT_MS],
        notifReArmCount = this[Keys.NOTIF_REARM_COUNT] ?: 0,
    )

    /**
     * Per-source ETags, keyed by [UpdateSource.id].
     *
     * A single shared ETag was correct while there was exactly one polled
     * source; with several it would cross-contaminate — source B's
     * `If-None-Match` would carry source A's validator and could be answered
     * with a spurious 304, silently freezing discovery on B.
     *
     * Migration: an install upgrading from the single-key layout has its old
     * value adopted by the first embedded source, so the first check after
     * the upgrade still short-circuits on 304 instead of re-downloading the
     * release list.
     */
    private fun Preferences.readEtags(): Map<String, String> {
        this[Keys.LAST_CHECK_ETAGS]?.let { raw ->
            runCatching { JSON.decodeFromString<Map<String, String>>(raw) }
                .getOrNull()
                ?.let { return it }
        }
        val legacy = this[Keys.LAST_CHECK_ETAG] ?: return emptyMap()
        val primary = UpdateSourceRegistry.EMBEDDED.firstOrNull()?.id ?: return emptyMap()
        return mapOf(primary to legacy)
    }

    /**
     * Ordered download URLs.
     *
     * Migration: installs written by the two-field layout are read back as a
     * two-element list, so a download interrupted by the upgrade resumes on
     * the same source index it left off at instead of restarting.
     */
    private fun Preferences.readDownloadUrls(): List<String> {
        this[Keys.PENDING_DOWNLOAD_URLS]?.let { raw ->
            val parsed = raw.split('\n').filter { it.isNotBlank() }
            if (parsed.isNotEmpty()) return parsed
        }
        return listOfNotNull(this[Keys.PENDING_APK_URL], this[Keys.PENDING_APK_FALLBACK_URL])
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeFrom(s: UpdaterState) {
        s.lastCheckAtEpochMs?.let { set(Keys.LAST_CHECK_AT_MS, it) } ?: remove(Keys.LAST_CHECK_AT_MS)
        set(Keys.LAST_CHECK_BOOT_REALTIME, s.lastCheckBootRealtime)
        if (s.lastCheckEtags.isEmpty()) {
            remove(Keys.LAST_CHECK_ETAGS)
        } else {
            set(Keys.LAST_CHECK_ETAGS, JSON.encodeToString(s.lastCheckEtags))
        }
        // The legacy single-ETag key is never written again; drop it once the
        // map has taken over so the two cannot drift apart.
        remove(Keys.LAST_CHECK_ETAG)
        set(Keys.LAST_CHECK_RESULT, s.lastCheckResult.name)
        s.lastErrorAtEpochMs?.let { set(Keys.LAST_ERROR_AT_MS, it) } ?: remove(Keys.LAST_ERROR_AT_MS)
        s.pendingDownloadId?.let { set(Keys.PENDING_DOWNLOAD_ID, it) } ?: remove(Keys.PENDING_DOWNLOAD_ID)
        s.pendingTagName?.let { set(Keys.PENDING_TAG_NAME, it) } ?: remove(Keys.PENDING_TAG_NAME)
        s.pendingVersionName?.let { set(Keys.PENDING_VERSION_NAME, it) } ?: remove(Keys.PENDING_VERSION_NAME)
        if (s.pendingDownloadUrls.isEmpty()) {
            remove(Keys.PENDING_DOWNLOAD_URLS)
        } else {
            // Newline-joined: URLs cannot contain one, and unlike a string set
            // this preserves try-order, which is the whole contract here.
            set(Keys.PENDING_DOWNLOAD_URLS, s.pendingDownloadUrls.joinToString("\n"))
        }
        remove(Keys.PENDING_APK_URL)
        remove(Keys.PENDING_APK_FALLBACK_URL)
        s.pendingApkSha256?.let { set(Keys.PENDING_APK_SHA256, it) } ?: remove(Keys.PENDING_APK_SHA256)
        s.pendingApkSizeBytes?.let { set(Keys.PENDING_APK_SIZE_BYTES, it) } ?: remove(Keys.PENDING_APK_SIZE_BYTES)
        s.pendingApkSha256Url?.let { set(Keys.PENDING_APK_SHA256_URL, it) } ?: remove(Keys.PENDING_APK_SHA256_URL)
        s.pendingReleasePageUrl?.let { set(Keys.PENDING_RELEASE_PAGE_URL, it) } ?: remove(Keys.PENDING_RELEASE_PAGE_URL)
        s.pendingReleaseNotesMarkdown?.let { set(Keys.PENDING_RELEASE_NOTES, it) } ?: remove(Keys.PENDING_RELEASE_NOTES)
        set(Keys.PENDING_DOWNLOAD_SOURCE_INDEX, s.pendingDownloadSourceIndex)
        set(Keys.PENDING_ALLOW_MOBILE, s.pendingAllowMobile)
        s.updateSourcesManifestJson?.let { set(Keys.UPDATE_SOURCES_MANIFEST, it) }
            ?: remove(Keys.UPDATE_SOURCES_MANIFEST)
        s.updateSourcesFetchedAtEpochMs?.let { set(Keys.UPDATE_SOURCES_FETCHED_AT_MS, it) }
            ?: remove(Keys.UPDATE_SOURCES_FETCHED_AT_MS)
        set(Keys.END_OF_SUPPORT_NOTICE_SHOWN, s.endOfSupportNoticeShown)
        set(Keys.PIPELINE_STAGE, s.pipelineStage.name)
        set(Keys.AUTO_CHECK_ENABLED, s.autoCheckEnabled)
        set(Keys.AUTOMATION_MODE, s.automationMode.name)
        set(Keys.AUTO_DOWNLOAD_ON_MOBILE, s.autoDownloadOnMobile)
        set(Keys.ANONYMOUS_UPDATE_METRICS_ENABLED, s.anonymousUpdateMetricsEnabled)
        s.lastAnonymousMetricsAttemptVersion?.let {
            set(Keys.LAST_ANONYMOUS_METRICS_ATTEMPT_VERSION, it)
        } ?: remove(Keys.LAST_ANONYMOUS_METRICS_ATTEMPT_VERSION)
        s.lastNotifiedTagName?.let { set(Keys.LAST_NOTIFIED_TAG, it) } ?: remove(Keys.LAST_NOTIFIED_TAG)
        s.notifDismissedAtEpochMs?.let { set(Keys.NOTIF_DISMISSED_AT_MS, it) } ?: remove(Keys.NOTIF_DISMISSED_AT_MS)
        set(Keys.NOTIF_REARM_COUNT, s.notifReArmCount)
    }

    private object Keys {
        val LAST_CHECK_AT_MS = longPreferencesKey("updater_last_check_at_ms")
        val LAST_CHECK_BOOT_REALTIME = longPreferencesKey("updater_last_check_boot_realtime")
        /** Legacy single-source ETag. Read for migration, never written. */
        val LAST_CHECK_ETAG = stringPreferencesKey("updater_last_check_etag")
        val LAST_CHECK_ETAGS = stringPreferencesKey("updater_last_check_etags")
        val LAST_CHECK_RESULT = stringPreferencesKey("updater_last_check_result")
        val LAST_ERROR_AT_MS = longPreferencesKey("updater_last_error_at_ms")
        val PENDING_DOWNLOAD_ID = longPreferencesKey("updater_pending_download_id")
        val PENDING_TAG_NAME = stringPreferencesKey("updater_pending_tag")
        val PENDING_VERSION_NAME = stringPreferencesKey("updater_pending_version")
        /** Legacy two-slot download URLs. Read for migration, never written. */
        val PENDING_APK_URL = stringPreferencesKey("updater_pending_apk_url")
        val PENDING_APK_FALLBACK_URL = stringPreferencesKey("updater_pending_apk_fallback_url")
        val PENDING_DOWNLOAD_URLS = stringPreferencesKey("updater_pending_download_urls")
        val PENDING_APK_SHA256 = stringPreferencesKey("updater_pending_apk_sha256")
        val PENDING_APK_SIZE_BYTES = longPreferencesKey("updater_pending_apk_size")
        val PENDING_APK_SHA256_URL = stringPreferencesKey("updater_pending_apk_sha256_url")
        val PENDING_RELEASE_PAGE_URL = stringPreferencesKey("updater_pending_release_page_url")
        val PENDING_RELEASE_NOTES = stringPreferencesKey("updater_pending_release_notes")
        val PENDING_DOWNLOAD_SOURCE_INDEX = intPreferencesKey("updater_pending_download_source_index")
        val PENDING_ALLOW_MOBILE = booleanPreferencesKey("updater_pending_allow_mobile")
        val PIPELINE_STAGE = stringPreferencesKey("updater_pipeline_stage")
        val AUTO_CHECK_ENABLED = booleanPreferencesKey("updater_auto_check_enabled")
        val AUTOMATION_MODE = stringPreferencesKey("updater_automation_mode")
        val AUTO_DOWNLOAD_ON_MOBILE = booleanPreferencesKey("updater_auto_download_on_mobile")
        val ANONYMOUS_UPDATE_METRICS_ENABLED =
            booleanPreferencesKey("updater_anonymous_metrics_enabled")
        val LAST_ANONYMOUS_METRICS_ATTEMPT_VERSION =
            stringPreferencesKey("updater_anonymous_metrics_attempt_version")
        val LAST_NOTIFIED_TAG = stringPreferencesKey("updater_last_notified_tag")
        val NOTIF_DISMISSED_AT_MS = longPreferencesKey("updater_notif_dismissed_at_ms")
        val NOTIF_REARM_COUNT = intPreferencesKey("updater_notif_rearm_count")
        val UPDATE_SOURCES_MANIFEST = stringPreferencesKey("updater_sources_manifest_json")
        val UPDATE_SOURCES_FETCHED_AT_MS = longPreferencesKey("updater_sources_fetched_at_ms")
        val END_OF_SUPPORT_NOTICE_SHOWN = booleanPreferencesKey("updater_end_of_support_notice_shown")
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Pure-data view of all persisted updater state. Mutated via
 * [UpdaterPreferences.update] which serializes writes through DataStore.
 */
data class UpdaterState(
    val lastCheckAtEpochMs: Long? = null,
    val lastCheckBootRealtime: Long = 0L,
    /** ETag per [UpdateSource.id]; see `UpdaterPreferences.readEtags`. */
    val lastCheckEtags: Map<String, String> = emptyMap(),
    val lastCheckResult: CheckResult = CheckResult.NO_UPDATE,
    val lastErrorAtEpochMs: Long? = null,
    val pendingDownloadId: Long? = null,
    val pendingTagName: String? = null,
    val pendingVersionName: String? = null,
    val pendingDownloadUrls: List<String> = emptyList(),
    val pendingApkSha256: String? = null,
    val pendingApkSizeBytes: Long? = null,
    val pendingApkSha256Url: String? = null,
    val pendingReleasePageUrl: String? = null,
    val pendingReleaseNotesMarkdown: String? = null,
    val pendingDownloadSourceIndex: Int = 0,
    val pendingAllowMobile: Boolean = false,
    val pipelineStage: PipelineStage = PipelineStage.NONE,
    val autoCheckEnabled: Boolean = true,
    val automationMode: UpdateAutomationMode = UpdateAutomationMode.NOTIFY,
    val autoDownloadOnMobile: Boolean = false,
    /** User-visible opt-out. No request is attempted while false. */
    val anonymousUpdateMetricsEnabled: Boolean = true,
    /** Local at-most-once guard; contains only a public target version. */
    val lastAnonymousMetricsAttemptVersion: String? = null,
    val lastNotifiedTagName: String? = null,
    val notifDismissedAtEpochMs: Long? = null,
    val notifReArmCount: Int = 0,
    /** Raw body of the last successfully fetched runtime source list. */
    val updateSourcesManifestJson: String? = null,
    val updateSourcesFetchedAtEpochMs: Long? = null,
    /** At-most-once guard for the end-of-support notification. */
    val endOfSupportNoticeShown: Boolean = false,
) {
    /**
     * Every pending-update field back to its default, pipeline included.
     *
     * Nothing else in this class clears them: the pending block is written
     * when a check finds a release and was never unwritten, so the state
     * outlived the very install it described. Once the app is running the
     * version it was pointing at, the block is not merely stale — it is a
     * standing instruction to install something already installed.
     *
     * Deliberately exhaustive rather than "the fields pendingUpdate() reads":
     * a half-cleared block would leave pendingDownloadId naming a
     * DownloadManager job nobody owns any more.
     */
    fun withoutPendingUpdate(): UpdaterState = copy(
        pendingDownloadId = null,
        pendingTagName = null,
        pendingVersionName = null,
        pendingDownloadUrls = emptyList(),
        pendingApkSha256 = null,
        pendingApkSizeBytes = null,
        pendingApkSha256Url = null,
        pendingReleasePageUrl = null,
        pendingReleaseNotesMarkdown = null,
        pendingDownloadSourceIndex = 0,
        pendingAllowMobile = false,
        pipelineStage = PipelineStage.NONE,
    )

    /** Reconstitutes the [UpdateInfo] previously written to state, if all fields are present. */
    fun pendingUpdate(): UpdateInfo? {
        val tag = pendingTagName ?: return null
        val version = SemVer.parseOrNull(tag) ?: return null
        if (pendingDownloadUrls.isEmpty()) return null
        val sha = pendingApkSha256 ?: return null
        val size = pendingApkSizeBytes ?: return null
        val shaUrl = pendingApkSha256Url ?: return null
        val pageUrl = pendingReleasePageUrl ?: return null
        return UpdateInfo(
            tagName = tag,
            versionName = pendingVersionName ?: version.toString(),
            version = version,
            downloadUrls = pendingDownloadUrls,
            apkSha256Url = shaUrl,
            apkSizeBytes = size,
            apkSha256 = sha,
            releaseNotesMarkdown = pendingReleaseNotesMarkdown.orEmpty(),
            releasePageUrl = pageUrl,
            publishedAtEpochSeconds = 0L,
        )
    }
}

/** User-selected updater behavior. New installs and upgrades default to [NOTIFY]. */
enum class UpdateAutomationMode {
    NOTIFY,
    AUTO_DOWNLOAD,
    AUTO_INSTALL,
}
