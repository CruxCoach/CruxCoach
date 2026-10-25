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
 * Routes native signing requests. Unless the person is actively performing a
 * sharing action, an external signer (Amber) is asked only through its
 * background provider; a refusal never falls back to an interactive dialog.
 * Relay authentication (NIP-42, kind 22242) is always background-only.
 */
internal class PrivateApplicationSigner(
    private val foreground: Nip01EventSigner,
    private val unattended: () -> Boolean,
    private val provider: Nip01EventSigner,
    private val account: String,
    private val currentAccount: () -> String,
) : Nip01EventSigner {
    override suspend fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): SignedNostrEvent? {
        check(currentAccount() == account) { "native_account_changed" }
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
