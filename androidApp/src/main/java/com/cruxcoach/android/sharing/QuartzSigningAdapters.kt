package com.cruxcoach.android.sharing

import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.domain.sharing.Nip01SignedEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two calls into the Nostr library that a unit-test JVM cannot make.
 *
 * Both classes are deliberately nothing but delegation. The library is compiled
 * to a newer bytecode level than the unit-test JVM runs, and BIP-340 is a native
 * secp256k1 library on top of that, so anything written here is untestable by
 * construction — which is exactly why there is as little of it as possible.
 * Every decision about *what* to sign and *what to believe* lives in
 * [Nip55LedgerCrypto], which is ordinary tested code.
 *
 * **NOT TESTED** on the JVM: these two bodies. They are exercised by hand on a
 * device, and the Android instrumentation that would cover them has no target
 * in this environment.
 */
@Singleton
class QuartzNip01EventSigner @Inject constructor(
    private val nostrSigner: NostrSigner,
) : Nip01EventSigner {

    /**
     * Signs through the signer abstraction, so a local key and an external
     * NIP-55 signer are the same call. The private key is never read: for a
     * local identity the library holds it, and for Amber it is in another app
     * that this one has no way to ask for it.
     */
    override suspend fun sign(
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): Nip01SignedEvent? {
        val event: Event = nostrSigner.signer.sign(
            createdAt,
            kind,
            tags.map { it.toTypedArray() }.toTypedArray(),
            content,
        )
        return Nip01SignedEvent(
            id = event.id,
            pubKey = event.pubKey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags.map { it.toList() },
            content = event.content,
            sig = event.sig,
        )
    }
}

/** BIP-340 as upstream implements it. No signature scheme of our own. */
@Singleton
class QuartzBip340Verifier @Inject constructor() : Bip340Verifier {
    override fun verify(signature: ByteArray, message32: ByteArray, pubKey: ByteArray): Boolean =
        Nip01.verify(signature, message32, pubKey)
}
