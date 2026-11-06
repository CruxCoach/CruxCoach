package com.cruxcoach.android.communityclient

import com.cruxcoach.communityclient.CommunityEvent
import com.cruxcoach.communityclient.CommunitySigner
import com.cruxcoach.communityclient.EventVerifier
import com.cruxcoach.communityclient.UnsignedEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner as QuartzSigner

/**
 * Signs community events with the app's identity: the local key or Amber,
 * whichever the user chose in the key settings. With Amber, each signature may
 * need the user's approval unless they allowed it permanently in Amber.
 */
internal class QuartzCommunitySigner(
    private val signer: () -> QuartzSigner,
    private val publicKey: () -> String = { signer().pubKey },
) : CommunitySigner {
    override val pubkey: String get() = publicKey()

    override suspend fun sign(event: UnsignedEvent): CommunityEvent {
        val signed: Event = signer().sign(
            event.createdAt,
            event.kind,
            event.tags.map { it.toTypedArray() }.toTypedArray(),
            event.content,
        )
        return signed.toCommunityEvent()
    }
}

/** Checks id and Schnorr signature with Quartz, like the app's other relay reads. */
internal object QuartzEventVerifier : EventVerifier {
    override fun verify(event: CommunityEvent): Boolean {
        val quartz = Event(event.id, event.pubkey, event.createdAt, event.kind, event.tags.map { it.toTypedArray() }.toTypedArray(), event.content, event.sig)
        return runCatching { quartz.verifyId() && quartz.verifySignature() }.getOrDefault(false)
    }
}

internal fun Event.toCommunityEvent(): CommunityEvent =
    CommunityEvent(id, pubKey, createdAt, kind, tags.map { it.toList() }, content, sig)
