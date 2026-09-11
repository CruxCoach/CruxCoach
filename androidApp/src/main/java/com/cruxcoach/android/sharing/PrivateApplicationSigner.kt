package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.Nip01SignedEvent

/** A refused unattended request never falls back to an interactive signature.
 * Used by actual friendship root certificates as well as transport preparation. */
internal class PrivateApplicationSigner(
    private val foreground: Nip01EventSigner,
    private val unattended: () -> Boolean,
    private val provider: Nip01EventSigner,
    private val account: String,
    private val currentAccount: () -> String,
    private val authorised: () -> Boolean,
) : Nip01EventSigner {
    private fun checkAccount() {
        check(currentAccount() == account && authorised()) { "native_authority_unavailable" }
    }

    override suspend fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Nip01SignedEvent? {
        checkAccount()
        val event = (if (unattended()) provider else foreground).sign(createdAt, kind, tags, content)
        checkAccount() // Account/device authority can change while an external signer responds.
        if (event != null) check(event.pubKey == account && event.createdAt == createdAt &&
            event.kind == kind && event.tags == tags && event.content == content) { "account_signer_mismatch" }
        // Signature/hash verification still belongs to the existing native and
        // ledger boundaries. This check never turns metadata into crypto proof.
        return event
    }
}

internal fun Nip01SignedEvent.toNativeEvent() = BindingEvent(id, pubKey, createdAt, kind, tags, content, sig)
