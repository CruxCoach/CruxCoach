package com.cruxcoach.android.sharing

import com.cruxcoach.android.BuildConfig
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.LedgerCrypto
import com.cruxcoach.domain.sharing.Nip01SigningEnvelope
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SigningDomain
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01
import java.security.MessageDigest

/**
 * Example data so the sharing screens can be explored by hand.
 *
 * **Debug builds only.** [peerSimulator] returns `null` unless
 * `BuildConfig.DEBUG`, and nothing in the release security path consults this
 * object.
 *
 * The demo peers are *real* keypairs, derived from fixed seeds so they stay the
 * same between runs. That matters: the reducer requires an acceptance to be
 * signed by the peer, and the demo satisfies that requirement honestly rather
 * than by relaxing it. No verifier is weakened to make the demo work, and the
 * release path keeps exactly the same rule.
 */
object SharingDemoData {

    private val SEED_NAMES = listOf("anna", "ben", "carla")

    private data class DemoPeer(val name: String, val privKey: ByteArray, val peer: PeerId)

    /**
     * `null` when the native secp256k1 library is unavailable — a JVM unit test,
     * for instance. The demo then simply does nothing, which is the correct
     * fail-closed answer rather than a fake key.
     */
    private val demoPeers: List<DemoPeer>? by lazy {
        runCatching {
            SEED_NAMES.map { name ->
                val priv = MessageDigest.getInstance("SHA-256")
                    .digest("cruxcoach-feat062-demo-$name".encodeToByteArray())
                val pub = Nip01.pubKeyCreate(priv)
                DemoPeer(name, priv, PeerId(pub.toHex()))
            }
        }.getOrNull()
    }

    private fun ByteArray.toHex(): String = joinToString("") { b ->
        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }

    private fun displayNames(): Map<PeerId, String> =
        demoPeers.orEmpty().associate { it.peer to "${it.name.replaceFirstChar { c -> c.uppercase() }} (Demo)" }

    fun displayName(peer: PeerId): String =
        displayNames()[peer] ?: (peer.value.take(12) + "…")

    /** Debug-only. `null` in release, so an acceptance can never be simulated there. */
    fun peerSimulator(): PeerSimulator? {
        if (!BuildConfig.DEBUG) return null
        val peers = demoPeers ?: return null
        return PeerSimulator { peer ->
            peers.firstOrNull { it.peer == peer }?.let { demo ->
                DemoPeerCrypto(demo.privKey, demo.peer.value)
            }
        }
    }

    /**
     * The message a ledger signature covers: the NIP-01 event id, never the
     * canonical hash itself.
     *
     * A demo peer holds its key in this process and so never goes through
     * NIP-55 — which is precisely why it is easy to forget when the scheme
     * changes. It signs the same envelope everything else signs, or its
     * acceptances would be refused by the ordinary verifier.
     */
    internal fun ledgerMessageFor(peerPubKeyHex: String, canonicalHash: ByteArray): ByteArray =
        Nip01SigningEnvelope.eventId(
            peerPubKeyHex,
            SigningDomain.RELATIONSHIP_LEDGER,
            canonicalHash,
        ) { MessageDigest.getInstance("SHA-256").digest(it) }

    /** Signs as one demo peer with its own real key. */
    private class DemoPeerCrypto(
        private val privKey: ByteArray,
        private val pubKeyHex: String,
    ) : LedgerCrypto {
        override fun hash(canonical: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(canonical)

        @Suppress("TooGenericExceptionCaught")
        override fun sign(hash: ByteArray): ByteArray? =
            runCatching { Nip01.sign(ledgerMessageFor(pubKeyHex, hash), privKey) }.getOrNull()

        @Suppress("TooGenericExceptionCaught")
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String): Boolean =
            runCatching {
                val pub = ByteArray(32) { i -> signerNpub.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                Nip01.verify(signature, ledgerMessageFor(signerNpub, hash), pub)
            }.getOrDefault(false)
    }

    /**
     * Seeds serially, in the caller's coroutine.
     *
     * Each step is a signed append and the controller takes one mutation at a
     * time, so this is a sequence of awaits rather than a fan-out — a demo that
     * raced its own writes would produce a different ledger every run.
     */
    suspend fun seed(controller: SharingController) {
        // Gated here as well as at the caller. The caller's check is a UI
        // affordance; this one is the rule, and it is the one that survives
        // somebody wiring up a second caller.
        if (!BuildConfig.DEBUG) return
        val peers = demoPeers ?: return
        val (anna, ben, carla) = Triple(peers[0].peer, peers[1].peer, peers[2].peer)

        // Baselines: everyone sees the profile, acquaintances also the training
        // history, friends additionally videos.
        controller.setBaseline(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true)
        controller.setBaseline(SharingCircle.ACQUAINTANCES, SharingCategory.TRAINING_HISTORY, true)
        controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true)

        // Anna: a friend who accepted, with one video held back individually.
        val annaCategories = setOf(
            SharingCategory.PROFILE_AND_GOALS,
            SharingCategory.TRAINING_HISTORY,
            SharingCategory.VIDEOS,
        )
        controller.offer(anna, SharingCircle.FRIENDS, annaCategories)
        controller.simulateAccept(anna, annaCategories, DeviceId("anna-phone"))
        controller.setObjectRule(anna, ObjectId("video-hangboard-fail"), SharingCategory.VIDEOS, AccessEffect.DENY)

        // Ben: an acquaintance who has been invited but has not answered, so
        // the screen shows something released to nobody yet.
        controller.offer(ben, SharingCircle.ACQUAINTANCES, setOf(SharingCategory.TRAINING_HISTORY))

        // Carla: a friend whose grant was just widened — the new category waits
        // for a fresh consent instead of being released.
        controller.offer(carla, SharingCircle.FRIENDS, setOf(SharingCategory.PROFILE_AND_GOALS))
        controller.simulateAccept(carla, setOf(SharingCategory.PROFILE_AND_GOALS), DeviceId("carla-tablet"))
        controller.changeGrant(
            carla,
            setOf(SharingCategory.PROFILE_AND_GOALS, SharingCategory.HEALTH_INFORMATION),
        )
        controller.setPeerRule(carla, SharingCategory.HEALTH_INFORMATION, AccessEffect.ALLOW)
    }

    /**
     * Enrols a second device so the device screen has something to show.
     *
     * Debug only, and the key is one this build derived: a release build cannot
     * mint a device key it does not hold, so there is nothing here for it to
     * call. The enrolment itself still goes through the ordinary path and is
     * still signed by the owner's root identity — the demo does not get a door
     * of its own.
     */
    suspend fun enrolDemoDevice(controller: SharingController) {
        if (!BuildConfig.DEBUG) return
        val priv = runCatching {
            MessageDigest.getInstance("SHA-256").digest("cruxcoach-feat062-demo-device".encodeToByteArray())
        }.getOrNull() ?: return
        val pub = runCatching { Nip01.pubKeyCreate(priv).toHex() }.getOrNull() ?: return
        controller.enrolDevice(
            device = com.cruxcoach.domain.sharing.AuthorityDeviceId(pub),
            publicKey = pub,
            role = com.cruxcoach.domain.sharing.DeviceRole.TRUSTED,
        )
    }
}
