package com.cruxcoach.app.testing

import com.cruxcoach.app.platform.AeadCipher
import com.cruxcoach.app.platform.Gzip
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.WallClock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** JVM stand-ins for the platform contracts, so portable logic runs in unit tests on Linux. */
object JvmHashing : Hashing {
    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

    override fun sha256OfFile(path: String): ByteArray? {
        val file = File(path)
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }
}

object JvmAead : AeadCipher {
    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
            .doFinal(plaintext)
    } catch (e: Exception) {
        null
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
            .doFinal(ciphertextAndTag)
    } catch (e: Exception) {
        null
    }
}

object JvmGzip : Gzip {
    override fun compress(data: ByteArray): ByteArray? =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    override fun decompress(data: ByteArray, maxOutputBytes: Long): ByteArray? = try {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > maxOutputBytes) return null
                out.write(buffer, 0, read)
            }
        }
        out.toByteArray()
    } catch (e: Exception) {
        null
    }
}

class FixedClock(var seconds: Long) : WallClock {
    override fun epochSeconds(): Long = seconds
    override fun epochMillis(): Long = seconds * 1000
}

object Fixtures {
    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }
}
