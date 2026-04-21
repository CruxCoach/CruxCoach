package com.cruxcoach.android.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Two-gate integrity check that sits between download (§5.3) and install
 * (§5.5):
 *
 *  1. SHA-256 of the downloaded bytes must match the hash from the
 *     `.sha256` sidecar asset (§5.4.1).
 *  2. SHA-256 of the APK's signing certificate must match the TOFU pin
 *     held by [UpdaterPinStore] (§5.4.2).
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
            Log.w(TAG, "Payload SHA-256 mismatch — expected=$expectedSha256Hex actual=$actualHash")
            return Result.PayloadMismatch
        }

        val signerHash = try {
            sha256OfApkSigner(apkFile)
        } catch (e: Exception) {
            Log.w(TAG, "Extracting APK signer failed", e)
            return Result.PayloadError(e.message ?: e.javaClass.simpleName)
        } ?: return Result.PayloadError("No signing certificate in APK")

        val pin = pinStore.getOrTofu()
        if (!hexEqualsConstantTime(signerHash, pin.certSha256Hex)) {
            Log.w(
                TAG,
                "Cert pin mismatch — pinned=${pin.certSha256Hex} apk=$signerHash",
            )
            return Result.CertMismatch(expected = pin.certSha256Hex, actual = signerHash)
        }
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

    private fun sha256OfApkSigner(apkFile: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        val firstSignerBytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
            signers?.firstOrNull()?.toByteArray() ?: return null
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: return null
        }
        return pinStore.sha256OfApkSigner(firstSignerBytes)
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
        data class CertMismatch(val expected: String, val actual: String) : Result
        data class PayloadError(val message: String) : Result
    }

    companion object {
        private const val TAG = "IntegrityVerifier"
    }
}
