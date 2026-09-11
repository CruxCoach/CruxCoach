package com.cruxcoach.android.sharing

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip55AndroidSigner.api.*
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal

/** Pinned Quartz 1.05.1's provider-only API. No foregroundQuery/Intent fallback.
 * Null, refusal and malformed results never trigger a second signing channel. */
internal object QuartzBackgroundSigner {
    private fun <T : IResult> result(value: SignerResult.RequestAddressed<T>?): T =
        (value as? SignerResult.RequestAddressed.Successful<T>)?.result ?: error("open_app_for_signer")

    fun sign(signer: NostrSignerExternal, time: Long, kind: Int, tags: List<List<String>>, content: String): Event {
        val array = tags.map { it.toTypedArray() }.toTypedArray()
        val unsigned = Event(EventHasher.hashId(signer.pubKey, time, kind, array, content), signer.pubKey, time, kind, array, content, "")
        val signed = result(signer.backgroundQuery.sign(unsigned)).event
        check(signed.id == unsigned.id && signed.pubKey == unsigned.pubKey && signed.createdAt == time &&
            signed.kind == kind && signed.tags.contentDeepEquals(array) && signed.content == content) { "background_signer_mismatch" }
        return signed // SDK verifies the signature; native/ledger boundaries verify again.
    }
    fun encrypt(signer: NostrSignerExternal, content: String, peer: String): String = result(signer.backgroundQuery.nip44Encrypt(content, peer)).ciphertext
    fun decrypt(signer: NostrSignerExternal, content: String, peer: String): String = result(signer.backgroundQuery.nip44Decrypt(content, peer)).plaintext
}
