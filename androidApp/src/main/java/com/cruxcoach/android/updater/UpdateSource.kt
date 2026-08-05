package com.cruxcoach.android.updater

import com.cruxcoach.android.BuildConfig
import kotlinx.serialization.Serializable

/**
 * One place the updater may look for a release, or fetch APK bytes from.
 *
 * The list of these is deliberately *data*, not code: it arrives either from
 * the embedded defaults ([UpdateSourceRegistry.EMBEDDED]) or from the runtime
 * manifest at [BuildConfig.UPDATE_SOURCES_URLS]. That is the whole point of
 * FEAT-050 — the set and order of sources must be changeable without
 * shipping a new APK, because an APK is exactly what we cannot deliver once
 * the current forge stops serving us.
 *
 * ### Why an untrusted source list is safe here
 *
 * `sw.js` on the website ships `DEFAULT_MIRRORS = []` with the rule "a mirror
 * host URL must never be guessed" — correct there, because the website has no
 * way to check what a mirror returns. The updater is the opposite case: every
 * downloaded APK must clear [IntegrityVerifier], which checks the SHA-256
 * against the release metadata *and* the signing certificate against the
 * TOFU pin in [UpdaterPinStore]. The transport is untrusted by construction,
 * so a hostile entry in this list cannot get code installed — it does not
 * hold our signing key.
 *
 * Two residual risks follow from that, and neither is fixed by shortening
 * the list:
 *
 *  - **The cert pin carries the entire security load.** A hostile source can
 *    serve a matching APK *and* sidecar, which defeats the hash check alone.
 *    Only the pin stops it. Do not weaken [IntegrityVerifier].
 *  - **Freeze.** A source that simply reports "no update" stalls that check
 *    round. Downgrade is already impossible ([VersionChecker.pickNewerStable]
 *    only accepts strictly greater versions), so withholding is all an
 *    attacker gets. Ordering the most trustworthy source first keeps the
 *    window small, and [UpdateSourceRegistry] logs a fully-silent round.
 */
@Serializable
data class UpdateSource(
    /**
     * Stable label. Also the value reported to the aggregate counter, so it
     * must stay in the server-side allowlist — see `SOURCE_IDS` below and
     * `anonymous_analytics.py` in cruxcoach-dlstats.
     */
    val id: String,
    val kind: Kind,
    /**
     * Forge API root, Nostr relay, content-addressed store root, or manifest
     * URL, depending on [kind]. Must be https (or wss for [Kind.NOSTR]).
     */
    val url: String,
    /** Content-addressed CDN paired with a [Kind.NOSTR] relay. */
    val cdn: String? = null,
    /** Forge sources only. */
    val owner: String? = null,
    /** Forge sources only. */
    val repo: String? = null,
    val enabled: Boolean = true,
) {

    @Serializable
    enum class Kind {
        /**
         * Forgejo/Gitea *and* GitHub. Their release JSON is field-for-field
         * compatible (`tag_name`, `prerelease`, `draft`, `body`, `html_url`,
         * `assets[].name`, `assets[].browser_download_url`, `assets[].size`)
         * and both expose `{url}/repos/{owner}/{repo}/releases`, so one
         * client covers both — only [url] differs
         * (`https://codeberg.org/api/v1` vs `https://api.github.com`).
         */
        @kotlinx.serialization.SerialName("forge")
        FORGE,

        /** Publisher-signed NIP-82 events on a relay, assets on a CDN. */
        @kotlinx.serialization.SerialName("nostr")
        NOSTR,

        /** Plain JSON release pointer, e.g. the website's `apk-target.json`. */
        @kotlinx.serialization.SerialName("manifest")
        MANIFEST,

        /** Content-addressed blob store (BUD-01 `GET /<sha256>`). Download only. */
        @kotlinx.serialization.SerialName("blossom")
        BLOSSOM,
    }

    /** Whether this source can answer "what is the newest version?". */
    val supportsDiscovery: Boolean
        get() = kind != Kind.BLOSSOM

    /**
     * Direct APK URL this source can serve for a release, or null if it
     * cannot. Content-addressed sources derive it from [apkSha256] alone and
     * therefore work for *any* release, including ones they never announced —
     * which is what makes them useful as pure fallbacks.
     */
    fun downloadUrlFor(tagName: String, apkSha256: String): String? = when (kind) {
        Kind.BLOSSOM -> if (apkSha256.isBlank()) null else "${url.trimEnd('/')}/$apkSha256"
        Kind.NOSTR -> cdn?.let { if (apkSha256.isBlank()) null else "${it.trimEnd('/')}/$apkSha256" }
        Kind.FORGE -> {
            val o = owner
            val r = repo
            if (o.isNullOrBlank() || r.isNullOrBlank() || tagName.isBlank()) {
                null
            } else {
                // Forgejo and GitHub share this release-download shape. The
                // asset file name follows our own release workflow's
                // convention (`<repo>-<tag>.apk`).
                "${webHost()}/$o/$r/releases/download/$tagName/$r-$tagName.apk"
            }
        }
        // The manifest names its own absolute URLs; nothing to derive.
        Kind.MANIFEST -> null
    }

    /**
     * Web host for a forge API root: `https://codeberg.org/api/v1` →
     * `https://codeberg.org`, `https://api.github.com` →
     * `https://github.com`.
     */
    fun webHost(): String = when {
        url.contains("/api/") -> url.substringBefore("/api/")
        url.startsWith("https://api.") -> "https://" + url.removePrefix("https://api.")
        else -> url.trimEnd('/')
    }

    /**
     * https-only, matching the rule `BlossomSyncManager.downloadAndVerifyChunk`
     * already enforces on manifest-supplied chunk URLs: a hostile or merely
     * careless source list must not be able to downgrade transport to
     * MITM-able cleartext. `wss://` is the equivalent for relays.
     */
    fun isTransportAcceptable(): Boolean {
        val schemeOk = when (kind) {
            Kind.NOSTR -> url.startsWith("wss://")
            else -> url.startsWith("https://")
        }
        val cdnOk = cdn == null || cdn.startsWith("https://")
        return schemeOk && cdnOk
    }

    fun isUsable(): Boolean {
        if (!enabled || id.isBlank() || !isTransportAcceptable()) return false
        return when (kind) {
            Kind.FORGE -> !owner.isNullOrBlank() && !repo.isNullOrBlank()
            Kind.NOSTR -> !cdn.isNullOrBlank()
            Kind.MANIFEST, Kind.BLOSSOM -> true
        }
    }
}

/** Wire shape of the runtime manifest at [BuildConfig.UPDATE_SOURCES_URLS]. */
@Serializable
data class UpdateSourceManifest(
    val version: Int = 1,
    val updated: String? = null,
    val sources: List<UpdateSource> = emptyList(),
)
