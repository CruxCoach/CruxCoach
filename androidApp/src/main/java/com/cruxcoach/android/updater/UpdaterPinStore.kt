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

    /**
     * SHA-256 of the certificate the installed app is signed with **right
     * now**, or null.
     *
     * After a v3 rotation `signingCertificateHistory` holds the whole chain
     * ordered oldest-first, so the *last* entry is the certificate currently
     * in force. That is deliberately what we pin: once a device has
     * installed a rotated release, the new certificate is its trust anchor —
     * exactly the view Android itself takes — and a later rotation chains on
     * from there.
     *
     * Pinning the *oldest* entry instead would keep accepting APKs signed
     * with the superseded key, which is the one thing a rotation is supposed
     * to stop. Android would still refuse to install them, so it would not
     * be a compromise, but this gate would have stopped contributing.
     */
    fun currentInstalledCertSha256Hex(): String? = installedSignerChain()?.lastOrNull()

    /**
     * The installed app's certificate chain, **oldest first**, as SHA-256
     * hex. A single entry when the key was never rotated.
     *
     * The ordering is load-bearing — [currentInstalledCertSha256Hex] and
     * [advanceToCurrentSignerIfRotated] both take the last entry, and
     * getting it backwards would move the pin onto the superseded key. It
     * was confirmed on a rotated install (HTC U11, Android 9), where the
     * platform reported:
     *
     *   signatures:[<new>]  past signatures:[<old> flags:17, <new> flags:17]
     *
     * i.e. the history ends with the certificate currently in force.
     */
    fun installedSignerChain(): List<String>? {
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
            signers.map { sha256Hex(it.toByteArray()) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read own signing certificate", e)
            null
        }
    }

    /**
     * Move the pin forward onto the installed app's current signer after a
     * key rotation has actually taken effect on this device.
     *
     * Called at start-up rather than right after the install completes: a
     * self-update replaces this very package, so the running process still
     * reports the *old* signature — the rotation is only observable once the
     * app has been restarted with the new APK.
     *
     * The advance is conditional on the existing pin appearing in the
     * installed chain. That is the whole safety property: we only ever
     * follow a lineage we already trusted, verified by PackageManager, and
     * never adopt whatever happens to be installed. If the pin is absent
     * from the chain, something is wrong and the pin stays put so the
     * mismatch surfaces instead of being papered over.
     *
     * @return true when the pin was moved.
     */
    @Synchronized
    fun advanceToCurrentSignerIfRotated(): Boolean {
        val chain = installedSignerChain() ?: return false
        val pin = readSealed() ?: return false // no pin yet: getOrTofu will set it
        val next = nextPinAfterRotation(pin.certSha256Hex, chain) ?: return false
        writeSealed(Pin(certSha256Hex = next, pinnedAtEpochSec = System.currentTimeMillis() / 1000))
        Log.i(TAG, "event=pin_advanced_after_rotation chainLength=${chain.size}")
        return true
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

        /**
         * Where the pin should move to, given the certificate chain the
         * installed app currently proves — or null to leave it alone.
         *
         * Isolated from file and PackageManager access so the rule itself is
         * testable. Every "no" here is deliberate:
         *
         *  - a chain of one means nothing was rotated;
         *  - a pin already equal to the current signer needs no write;
         *  - **a pin that is absent from the chain must never advance.**
         *    That is the case where something is genuinely wrong, and
         *    silently adopting whatever is installed would turn the pin from
         *    a check into a rubber stamp.
         */
        internal fun nextPinAfterRotation(pinCertSha256Hex: String?, chain: List<String>): String? {
            if (pinCertSha256Hex.isNullOrBlank() || chain.size < 2) return null
            val current = chain.last()
            if (current.equals(pinCertSha256Hex, ignoreCase = true)) return null
            if (chain.none { it.equals(pinCertSha256Hex, ignoreCase = true) }) return null
            return current
        }

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
