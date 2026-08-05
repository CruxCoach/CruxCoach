package com.cruxcoach.android.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

/**
 * Two-gate integrity check that sits between download (§5.3) and install
 * (§5.5):
 *
 *  1. SHA-256 of the downloaded bytes must match the hash from the
 *     `.sha256` sidecar asset (§5.4.1).
 *  2. The TOFU pin held by [UpdaterPinStore] must appear somewhere in the
 *     APK's signing-certificate history (§5.4.2) — as its current signer,
 *     or as an ancestor in a v3 SigningCertificateLineage.
 *
 * Both comparisons use [MessageDigest.isEqual] for constant-time equality
 * so that a same-length-but-wrong hash cannot be inferred byte-for-byte
 * from timing. Any failure returns a typed [Result] — the caller decides
 * whether to retry (payload failures) or hand off to §5.4.3 (cert
 * mismatch) or escalate an unexpected error.
 */
class IntegrityVerifier(
    private val context: Context,
    private val pinStore: UpdaterPinStore,
) {

    /**
     * Verifies the APK at [apkFile] against [expectedSha256Hex] and the
     * pinned signing cert. Returns a typed [Result]. Never throws.
     */
    fun verify(apkFile: File, expectedSha256Hex: String): Result {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.PayloadMissing
        }
        val actualHash = try {
            sha256OfFile(apkFile)
        } catch (e: Exception) {
            Log.w(TAG, "SHA-256 of downloaded APK failed", e)
            return Result.PayloadError(e.message ?: e.javaClass.simpleName)
        }
        if (!hexEqualsConstantTime(actualHash, expectedSha256Hex)) {
            Log.w(TAG, "Payload SHA-256 mismatch — expected=${expectedSha256Hex.redactHash()} actual=${actualHash.redactHash()}")
            return Result.PayloadMismatch
        }

        // Logged separately from the signer gate below: when the certificate
        // check fails, this line is what says the bytes themselves were fine,
        // which is the difference between "wrong build served" and "corrupted
        // download".
        Log.i(TAG, "event=payload_sha256_ok bytes=${apkFile.length()}")

        val signerHashes = try {
            apkSignerCertHashes(apkFile)
        } catch (e: Exception) {
            Log.w(TAG, "Extracting APK signer failed", e)
            return Result.SignerUnavailable(e.message ?: e.javaClass.simpleName)
        }
        if (signerHashes.isEmpty()) {
            return Result.SignerUnavailable("No signing certificate in APK")
        }

        val pin = pinStore.getOrTofu()
        // Accept when the pinned certificate appears ANYWHERE in the APK's
        // signing history, not only as its current signer. That is what makes
        // a v3 key rotation installable: the rotated APK is signed by the new
        // key and carries a SigningCertificateLineage proving the old key
        // authorised it, so "the cert I trusted on first use is in this APK's
        // lineage" is precisely the property we want to require.
        //
        // This does not weaken the gate. The lineage is only ever read back
        // from PackageManager, which validates each link before exposing the
        // history — a forged lineage does not parse. The unverified ZIP
        // fallback still contributes exactly one certificate, so it cannot be
        // used to smuggle a trusted cert in alongside a hostile signer.
        val matched = pinMatchesSigningHistory(pin.certSha256Hex, signerHashes)
        if (!matched) {
            Log.w(
                TAG,
                "Cert pin mismatch — pinned=${pin.certSha256Hex.redactHash()} " +
                    "apkCerts=${signerHashes.joinToString(",") { it.redactHash() }}",
            )
            return Result.CertMismatch(
                expected = pin.certSha256Hex,
                actual = signerHashes.first(),
            )
        }
        Log.i(
            TAG,
            "event=signer_ok certsInApk=${signerHashes.size} " +
                "pin=${pin.certSha256Hex.redactHash()}",
        )
        return Result.Ok
    }

    private fun sha256OfFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHexLower()
    }

    /**
     * Every signing certificate the APK can prove it is authorised to use,
     * as lower-case SHA-256 hex.
     *
     * On API 28+ this is the whole [android.content.pm.SigningInfo]
     * certificate history, so a v3-rotated APK yields both the old and the
     * new certificate. The older paths yield exactly one certificate each —
     * they predate rotation and have no concept of it — which is why a
     * rotated APK must be verified through the modern path to be accepted.
     * Ordered, with the most authoritative source first.
     */
    private fun apkSignerCertHashes(apkFile: File): List<String> {
        val pm = context.packageManager
        val viaPm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extractSignerHistoryModern(pm, apkFile) ?: extractSignerLegacy(pm, apkFile)?.let(::listOf)
        } else {
            extractSignerLegacy(pm, apkFile)?.let(::listOf)
        }
        val certs = viaPm ?: extractSignerFromZip(apkFile)?.let(::listOf) ?: emptyList()
        return certs.map(pinStore::sha256OfApkSigner).distinct()
    }

    /** API 28+ path. Some OEM ROMs (observed on HTC Android 9) hand back a
     *  non-null [android.content.pm.PackageInfo] with a `null` `signingInfo`
     *  for APKs in app-scoped external dirs — caller falls back to the
     *  deprecated [extractSignerLegacy] when this returns null. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun extractSignerHistoryModern(pm: PackageManager, apkFile: File): List<ByteArray>? {
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info == null) {
            Log.w(TAG, "getPackageArchiveInfo(GET_SIGNING_CERTIFICATES) returned null — size=${apkFile.length()} sdk=${Build.VERSION.SDK_INT}")
            return null
        }
        val signing = info.signingInfo
        if (signing == null) {
            Log.w(TAG, "signingInfo is null — package=${info.packageName} sdk=${Build.VERSION.SDK_INT}")
            return null
        }
        // Multiple concurrent signers are mutually exclusive with rotation:
        // a multi-signer APK has no lineage, so apkContentsSigners is the
        // complete set. Otherwise signingCertificateHistory carries the
        // rotation chain (a single entry when nothing was ever rotated).
        val signers = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners
        } else {
            signing.signingCertificateHistory
        }
        val certs = signers?.mapNotNull { it?.toByteArray() }.orEmpty()
        if (certs.isEmpty()) {
            Log.w(TAG, "signingInfo has no signers (multi=${signing.hasMultipleSigners()})")
            return null
        }
        return certs
    }

    /** Legacy [PackageManager.GET_SIGNATURES] path. Works on every SDK;
     *  reads the v1 (JAR) signature, which for non-rotated keys carries
     *  the same X.509 certificate as v2/v3 schemes, so the SHA-256 of the
     *  signer certificate matches the TOFU pin either way. */
    @Suppress("DEPRECATION")
    private fun extractSignerLegacy(pm: PackageManager, apkFile: File): ByteArray? {
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        if (info == null) {
            Log.w(TAG, "getPackageArchiveInfo(GET_SIGNATURES) returned null — size=${apkFile.length()} sdk=${Build.VERSION.SDK_INT}")
            return null
        }
        val first = info.signatures?.firstOrNull()
        if (first == null) {
            Log.w(TAG, "signatures null/empty — package=${info.packageName}")
            return null
        }
        return first.toByteArray()
    }

    /** Last-resort path: parse the APK as a ZIP, read the PKCS#7 `.RSA`
     *  (or `.DSA` / `.EC`) entry in `META-INF/`, and let the JCA
     *  [CertificateFactory] extract the X.509 signer. This sidesteps
     *  PackageManager entirely, so it still works when a ROM refuses to
     *  scan APKs in the app-scoped external dir (observed: HTC Android 9).
     *
     *  Returns the same DER-encoded certificate bytes as
     *  `Signature.toByteArray()` for non-rotated signing keys, so the
     *  SHA-256 fed into the TOFU pin matches byte-for-byte. */
    private fun extractSignerFromZip(apkFile: File): ByteArray? {
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull { e ->
                        val n = e.name.uppercase()
                        n.startsWith("META-INF/") &&
                            (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
                    }
                if (entry == null) {
                    Log.w(TAG, "No META-INF/*.RSA|DSA|EC in APK ZIP")
                    return@use null
                }
                val cf = CertificateFactory.getInstance("X.509")
                zip.getInputStream(entry).use { input ->
                    val cert = cf.generateCertificates(input).firstOrNull()
                    if (cert == null) {
                        Log.w(TAG, "PKCS#7 entry ${entry.name} yielded no certificates")
                        null
                    } else {
                        cert.encoded
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ZIP-based signer extraction failed", e)
            null
        }
    }

    private fun hexEqualsConstantTime(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        val aBytes = a.lowercase().toByteArray(Charsets.US_ASCII)
        val bBytes = b.lowercase().toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(aBytes, bBytes)
    }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b))
        return sb.toString()
    }

    sealed interface Result {
        data object Ok : Result
        data object PayloadMissing : Result
        data object PayloadMismatch : Result
        data class CertMismatch(val expected: String, val actual: String) : Result {
            override fun toString(): String =
                "CertMismatch(expected=${expected.redactHash()}, actual=${actual.redactHash()})"
        }
        data class PayloadError(val message: String) : Result

        /**
         * The signing certificate could not be extracted from the APK at all
         * (every PackageManager path returned null AND the ZIP v1-signature
         * fallback found no cert). Unlike [PayloadMismatch]/[PayloadError]
         * this is NOT fixable by re-downloading — it's a ROM/signing-scheme
         * edge (e.g. a v2/v3-only APK on a ROM where getPackageArchiveInfo
         * returns null) — so the caller hands off to the release page instead
         * of looping on a corrupt-retry that can never succeed.
         */
        data class SignerUnavailable(val message: String) : Result
    }

    companion object {
        private const val TAG = "IntegrityVerifier"

        /**
         * The cert gate's whole decision, isolated so it can be tested
         * without a PackageManager: does the pinned certificate appear
         * anywhere in the certificate history the APK proved?
         *
         * Empty history is a hard no — "we could not read any certificate"
         * must never be mistaken for "the certificate matched". Comparison
         * is constant-time per entry and case-insensitive, because the three
         * extraction paths do not agree on hex casing.
         */
        internal fun pinMatchesSigningHistory(
            pinCertSha256Hex: String,
            apkCertSha256Hexes: List<String>,
        ): Boolean {
            if (pinCertSha256Hex.isBlank() || apkCertSha256Hexes.isEmpty()) return false
            // Deliberately not short-circuiting on the first match: the number
            // of comparisons should not depend on WHERE in the lineage the pin
            // sits, only on how long the lineage is (which is public).
            var matched = false
            for (candidate in apkCertSha256Hexes) {
                if (hexEqualsConstantTimeStatic(candidate, pinCertSha256Hex)) matched = true
            }
            return matched
        }

        private fun hexEqualsConstantTimeStatic(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            val aBytes = a.lowercase().toByteArray(Charsets.US_ASCII)
            val bBytes = b.lowercase().toByteArray(Charsets.US_ASCII)
            return MessageDigest.isEqual(aBytes, bBytes)
        }

        /** Trims a hex hash for logs: first 8 + last 8 chars, rest elided. */
        internal fun String.redactHash(): String =
            if (length >= 16) "${take(8)}…${takeLast(8)}" else "…"
    }
}
