package com.cruxcoach.android.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * HMAC-sealed TOFU pin file (§6.5).
 *
 * Stores `pinCertSha256Hex` (64 ASCII bytes) plus the moment-of-pin
 * timestamp, sealed with an HMAC-SHA256 whose key lives in the Android
 * Keystore. The pin itself is **public** information — every app on the
 * device can read the installed signer's certificate via [PackageManager]
 * — so confidentiality is not the threat. **Integrity** is: an attacker
 * with filesystem-write access (e.g. ADB shell without root) must not be
 * able to silently swap the pin to one they control without forging the
 * MAC, which they cannot without access to the Keystore key.
 *
 * On every read, a missing or MAC-invalid file is indistinguishable from
 * "first launch": both trigger a re-TOFU against the currently installed
 * signing certificate and a fresh write. This is intentional — a tampered
 * pin file should not be honored, and forcing a one-time re-TOFU on
 * ROM-level state corruption is correct (§6.5.1).
 */
class UpdaterPinStore(private val context: Context) {

    private val pinFile: File by lazy { File(context.filesDir, "updater_pin.bin") }

    /** Never throws — returns the freshly TOFU'd pin on any read failure. */
    @Synchronized
    fun getOrTofu(): Pin {
        readSealed()?.let { return it }
        val freshHex = currentInstalledCertSha256Hex()
            ?: error("No signing certificate found for ${context.packageName} — refusing to TOFU empty pin")
        val pin = Pin(certSha256Hex = freshHex, pinnedAtEpochSec = System.currentTimeMillis() / 1000)
        writeSealed(pin)
        return pin
    }

    /** Reads the SHA-256 of the currently installed app's signing cert, or null. */
    fun currentInstalledCertSha256Hex(): String? {
        val pm = context.packageManager
        val pkg = context.packageName
        return try {
            val signers: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                val signing = info.signingInfo ?: return null
                if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                    ?: return null
            }
            if (signers.isEmpty()) return null
            sha256Hex(signers[0].toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "Could not read own signing certificate", e)
            null
        }
    }

    /** Same hash function applied to a downloaded APK's signer for comparison. */
    fun sha256OfApkSigner(apkSignerBytes: ByteArray): String = sha256Hex(apkSignerBytes)

    private fun readSealed(): Pin? {
        if (!pinFile.exists()) return null
        return try {
            val bytes = pinFile.readBytes()
            // Layout: 64 ascii cert hex | 8 byte BE epoch sec | 32 byte HMAC
            if (bytes.size != 64 + 8 + 32) return null
            val payload = bytes.copyOfRange(0, 64 + 8)
            val mac = bytes.copyOfRange(64 + 8, bytes.size)
            val expected = mac(payload)
            if (!MessageDigest.isEqual(mac, expected)) {
                Log.w(TAG, "Pin file MAC mismatch — treating as untrusted, will re-TOFU")
                return null
            }
            val hex = String(payload.copyOfRange(0, 64), Charsets.US_ASCII)
            val tsBytes = payload.copyOfRange(64, 64 + 8)
            val ts = bytesToLongBE(tsBytes)
            Pin(certSha256Hex = hex, pinnedAtEpochSec = ts)
        } catch (e: Exception) {
            Log.w(TAG, "Pin file read failed — re-TOFU", e)
            null
        }
    }

    private fun writeSealed(pin: Pin) {
        val tmp = File(pinFile.parentFile, pinFile.name + ".tmp")
        try {
            val payload = ByteArray(64 + 8)
            val hexBytes = pin.certSha256Hex.toByteArray(Charsets.US_ASCII)
            require(hexBytes.size == 64) { "cert sha256 hex must be 64 bytes ASCII" }
            System.arraycopy(hexBytes, 0, payload, 0, 64)
            longToBytesBE(pin.pinnedAtEpochSec, payload, 64)
            val mac = mac(payload)
            tmp.outputStream().use { out ->
                out.write(payload)
                out.write(mac)
                out.flush()
            }
            if (!tmp.renameTo(pinFile)) {
                // Some filesystems require the target to be deleted first.
                pinFile.delete()
                if (!tmp.renameTo(pinFile)) {
                    error("Atomic rename of updater pin file failed")
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun mac(payload: ByteArray): ByteArray {
        val key = getOrCreateMacKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(payload)
    }

    private fun getOrCreateMacKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build()
        gen.init(spec)
        return gen.generateKey()
    }

    data class Pin(
        val certSha256Hex: String,
        val pinnedAtEpochSec: Long,
    )

    companion object {
        private const val TAG = "UpdaterPinStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "cruxcoach.updater.pin.hmac.v1"

        internal fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append("%02x".format(b))
            return sb.toString()
        }

        private fun longToBytesBE(value: Long, dst: ByteArray, offset: Int) {
            for (i in 0..7) dst[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }

        private fun bytesToLongBE(src: ByteArray): Long {
            var v = 0L
            for (i in 0..7) v = (v shl 8) or (src[i].toLong() and 0xFF)
            return v
        }
    }
}
