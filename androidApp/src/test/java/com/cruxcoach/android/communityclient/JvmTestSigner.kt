package com.cruxcoach.android.communityclient

import com.cruxcoach.communityclient.CommunityEvent
import com.cruxcoach.communityclient.CommunitySigner
import com.cruxcoach.communityclient.EventVerifier
import com.cruxcoach.communityclient.UnsignedEvent
import com.cruxcoach.communityclient.sha256Hex
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/**
 * A NIP-01 signer for plain JVM tests. Quartz is built for Java 21 and cannot load in
 * the Java 17 unit-test runtime, so tests sign with the same secp256k1 library directly.
 * The app itself signs through [QuartzCommunitySigner] (local key or Amber).
 */
internal class JvmTestSigner(private val secret: ByteArray = randomSecret()) : CommunitySigner {
    override val pubkey: String = xOnlyPubkey(secret)

    override suspend fun sign(event: UnsignedEvent): CommunityEvent {
        val id = eventId(pubkey, event.createdAt, event.kind, event.tags, event.content)
        val aux = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val sig = Secp256k1.get().signSchnorr(id.hexToBytes(), secret, aux)
        return CommunityEvent(id, pubkey, event.createdAt, event.kind, event.tags, event.content, sig.toHex())
    }

    companion object {
        fun randomSecret(): ByteArray {
            val random = SecureRandom()
            while (true) {
                val candidate = ByteArray(32).also { random.nextBytes(it) }
                if (Secp256k1.get().secKeyVerify(candidate)) return candidate
            }
        }

        fun fromHex(secretHex: String) = JvmTestSigner(secretHex.hexToBytes())

        private fun xOnlyPubkey(secret: ByteArray): String =
            Secp256k1.get().pubkeyCreate(secret).copyOfRange(1, 33).toHex()
    }
}

/** Checks the NIP-01 id and the BIP-340 signature on the JVM. */
internal object JvmTestVerifier : EventVerifier {
    override fun verify(event: CommunityEvent): Boolean = runCatching {
        eventId(event.pubkey, event.createdAt, event.kind, event.tags, event.content) == event.id &&
            Secp256k1.get().verifySchnorr(event.sig.hexToBytes(), event.id.hexToBytes(), event.pubkey.hexToBytes())
    }.getOrDefault(false)
}

/** NIP-01: sha256 of `[0, pubkey, created_at, kind, tags, content]` as compact JSON. */
internal fun eventId(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
    val array = JsonArray(
        listOf(
            JsonPrimitive(0),
            JsonPrimitive(pubkey),
            JsonPrimitive(createdAt),
            JsonPrimitive(kind),
            JsonArray(tags.map { tag -> JsonArray(tag.map { JsonPrimitive(it) }) }),
            JsonPrimitive(content),
        ),
    )
    return sha256Hex(array.toString().toByteArray(Charsets.UTF_8))
}

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
