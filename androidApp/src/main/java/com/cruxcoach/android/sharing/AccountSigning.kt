package com.cruxcoach.android.sharing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A signed NIP-01 event exactly as the native layer and relays see it. */
@Serializable
data class SignedNostrEvent(
    val id: String,
    @SerialName("pubkey") val pubKey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
)

/**
 * Asks the account signer to sign one NIP-01 event. `null` means "did not
 * sign": refused, timed out, no signer installed, or read-only.
 */
fun interface Nip01EventSigner {
    suspend fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): SignedNostrEvent?
}

/** BIP-340 verification. Native, hence injected. */
fun interface Bip340Verifier {
    fun verify(signature: ByteArray, message32: ByteArray, pubKey: ByteArray): Boolean
}

/**
 * What the native side may ask the account to do, per engine. The native host
 * is ordinary code that could have a bug; this list is what keeps such a bug
 * from signing or decrypting anything else in the person's name.
 */
fun interface SigningPolicy {
    fun allows(kind: Int, tags: List<List<String>>, content: String): Boolean

    /** Whether the engine may use the account's NIP-44 encryption at all. */
    val nip44: Boolean get() = false

    companion object {
        const val BINDING_KIND = 27777
        const val BINDING_PURPOSE = "cc-share-bind-v1"
        private val key = Regex("[A-Za-z0-9+/]{43}")

        /**
         * Direct Olm channel: exactly the unpublished installation binding,
         * `purpose`, `curve25519` and `ed25519` tags in that order, no content.
         * No NIP-44: the channel never asks the account to encrypt or decrypt.
         */
        val OLM = SigningPolicy { kind, tags, content ->
            kind == BINDING_KIND && content.isEmpty() && tags.size == 3 &&
                tags[0] == listOf("purpose", BINDING_PURPOSE) &&
                tags[1].size == 2 && tags[1][0] == "curve25519" && key.matches(tags[1][1]) &&
                tags[2].size == 2 && tags[2][0] == "ed25519" && key.matches(tags[2][1])
        }

        /** MLS over relays: gift-wrap seals, relay authentication, KeyPackages. */
        val MLS = object : SigningPolicy {
            private val kinds = setOf(13, PrivateApplicationSigner.RELAY_AUTH_KIND, 30443)
            override fun allows(kind: Int, tags: List<List<String>>, content: String) = kind in kinds
            override val nip44 get() = true
        }

        fun forEngine(engine: String?): SigningPolicy = if (engine == "olm") OLM else MLS
    }
}

/**
 * Routes native signing requests. Only what [policy] allows is ever passed on;
 * anything else is refused and counted without asking the signer. Unless the
 * person is actively performing a sharing action, an external signer (Amber)
 * is asked only through its background provider; a refusal never falls back
 * to an interactive dialog. Relay authentication (NIP-42, kind 22242) is
 * always background-only.
 */
internal class PrivateApplicationSigner(
    private val foreground: Nip01EventSigner,
    private val unattended: () -> Boolean,
    private val provider: Nip01EventSigner,
    private val account: String,
    private val currentAccount: () -> String,
    private val policy: SigningPolicy,
) : Nip01EventSigner {
    /** Requests refused by [policy] since start; never reached a signer. */
    val refused = java.util.concurrent.atomic.AtomicInteger(0)

    override suspend fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): SignedNostrEvent? {
        check(currentAccount() == account) { "native_account_changed" }
        if (!policy.allows(kind, tags, content)) {
            refused.incrementAndGet()
            return null
        }
        val background = unattended() || kind == RELAY_AUTH_KIND
        val event = (if (background) provider else foreground).sign(createdAt, kind, tags, content)
        check(currentAccount() == account) { "native_account_changed" }
        if (event != null) {
            check(event.pubKey == account && event.createdAt == createdAt && event.kind == kind &&
                event.tags == tags && event.content == content) { "account_signer_mismatch" }
        }
        // Signature and id verification happen natively before anything is used.
        return event
    }

    companion object {
        const val RELAY_AUTH_KIND = 22242
    }
}
