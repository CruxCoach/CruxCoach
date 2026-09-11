package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.Nip01SignedEvent
import com.cruxcoach.domain.sharing.Nip01SigningEnvelope
import com.cruxcoach.domain.sharing.SigningDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §10: signing a permission entry with an external NIP-55 signer.
 *
 * This is the class that turns a canonical hash into a real Nostr signature
 * without ever touching a private key. It is written against two injected
 * seams — "ask a signer to sign this event" and "check BIP-340" — because both
 * are native or newer-bytecode library calls that a unit-test JVM cannot load.
 * Everything *around* them is this app's responsibility and is tested here:
 * what we ask to be signed, and what we refuse to believe when it comes back.
 *
 * The rule the tests exist to enforce: a local key and Amber travel exactly the
 * same path and produce exactly the same stored bytes. Anything else would mean
 * an entry signed on one device could not be verified on another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Nip55LedgerCryptoTest {

    private companion object {
        const val OWNER = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
        const val OTHER = "1111111111111111111111111111111111111111111111111111111111111111"
    }

    private val sha256: (ByteArray) -> ByteArray = { MessageDigest.getInstance("SHA-256").digest(it) }
    private val canonical = "some canonical ledger bytes".encodeToByteArray()
    private val signatureBytes = ByteArray(64) { (it + 1).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { b ->
        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }

    /** What the class asked to have signed, so the request itself can be asserted. */
    private data class Request(
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
    )

    /**
     * A signer that behaves itself: it returns exactly the event it was asked
     * for, with a correctly computed id. Both a local key and Amber are
     * expected to do this.
     */
    private inner class WellBehavedSigner(
        private val pubKey: String = OWNER,
        private val sig: ByteArray = signatureBytes,
        private val approval: CompletableDeferred<Boolean>? = null,
    ) : Nip01EventSigner {
        var requests = mutableListOf<Request>()
        var mangle: (Nip01SignedEvent) -> Nip01SignedEvent = { it }

        override suspend fun sign(
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): Nip01SignedEvent? {
            requests += Request(createdAt, kind, tags, content)
            if (approval != null && !approval.await()) return null
            val preimage = buildString {
                append("[0,\"").append(pubKey).append("\",").append(createdAt).append(',').append(kind)
                append(",[")
                tags.forEachIndexed { i, tag ->
                    if (i > 0) append(',')
                    append('[').append(tag.joinToString(",") { "\"$it\"" }).append(']')
                }
                append("],\"").append(content).append("\"]")
            }
            return mangle(
                Nip01SignedEvent(
                    id = hex(sha256(preimage.encodeToByteArray())),
                    pubKey = pubKey,
                    createdAt = createdAt,
                    kind = kind,
                    tags = tags,
                    content = content,
                    sig = hex(sig),
                )
            )
        }
    }

    /** Accepts whatever it is given; the curve itself is upstream's. */
    private val alwaysValid = Bip340Verifier { _, _, _ -> true }
    private val neverValid = Bip340Verifier { _, _, _ -> false }

    private fun crypto(
        signer: Nip01EventSigner,
        bip340: Bip340Verifier = alwaysValid,
        pubKey: String? = OWNER,
        domain: SigningDomain = SigningDomain.RELATIONSHIP_LEDGER,
        timeoutMillis: Long = Nip55LedgerCrypto.DEFAULT_APPROVAL_TIMEOUT_MILLIS,
    ) = Nip55LedgerCrypto(domain, { pubKey }, signer, bip340, timeoutMillis)

    // ------------------------------------------------ what we ask to sign

    @Test
    fun `the request is the fixed envelope and nothing else`() = runTest {
        val signer = WellBehavedSigner()
        val hash = crypto(signer).hash(canonical)

        crypto(signer).signCanonical(hash)

        assertEquals(
            listOf(
                Request(
                    createdAt = Nip01SigningEnvelope.CREATED_AT,
                    kind = Nip01SigningEnvelope.KIND,
                    tags = listOf(listOf("cc-purpose", "cc.sharing.ledger.v2")),
                    content = hex(hash),
                )
            ),
            signer.requests,
        )
    }

    @Test
    fun `the owner policy ledger asks under its own purpose`() = runTest {
        val signer = WellBehavedSigner()
        val c = crypto(signer, domain = SigningDomain.OWNER_POLICY_LEDGER)

        c.signCanonical(c.hash(canonical))

        assertEquals(listOf(listOf("cc-purpose", "cc.sharing.ownerpolicy.v1")), signer.requests.single().tags)
    }

    @Test
    fun `the canonical bytes are hashed with SHA-256`() {
        assertEquals(
            hex(MessageDigest.getInstance("SHA-256").digest(canonical)),
            hex(crypto(WellBehavedSigner()).hash(canonical)),
        )
    }

    @Test
    fun `only the sixty-four signature bytes are kept`() = runTest {
        val c = crypto(WellBehavedSigner())

        val signature = assertNotNull(c.signCanonical(c.hash(canonical)))

        assertEquals(64, signature.size)
        assertEquals(signatureBytes.toList(), signature.toList())
    }

    /**
     * The whole point of routing a local key through the same call: two signers
     * that behave correctly are indistinguishable in what gets stored.
     */
    @Test
    fun `a local key and an external signer produce the same stored bytes`() = runTest {
        val local = WellBehavedSigner()
        val amber = WellBehavedSigner()
        val hash = crypto(local).hash(canonical)

        val fromLocal = assertNotNull(crypto(local).signCanonical(hash))
        val fromAmber = assertNotNull(crypto(amber).signCanonical(hash))

        assertEquals(fromLocal.toList(), fromAmber.toList())
        assertEquals(local.requests, amber.requests)
    }

    // --------------------------------------- what we refuse to believe

    @Test
    fun `an event signed by a different key is discarded`() = runTest {
        val c = crypto(WellBehavedSigner(pubKey = OTHER))
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `an event whose kind was changed is discarded`() = runTest {
        val signer = WellBehavedSigner().apply { mangle = { it.copy(kind = 1) } }
        val c = crypto(signer)
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `an event whose timestamp was moved is discarded`() = runTest {
        val signer = WellBehavedSigner().apply { mangle = { it.copy(createdAt = it.createdAt + 1) } }
        val c = crypto(signer)
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `an event whose purpose was changed is discarded`() = runTest {
        val signer = WellBehavedSigner().apply {
            mangle = { it.copy(tags = listOf(listOf("cc-purpose", "cc.sharing.ownerpolicy.v1"))) }
        }
        val c = crypto(signer)
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `an event over a different hash is discarded`() = runTest {
        val signer = WellBehavedSigner().apply { mangle = { it.copy(content = hex(ByteArray(32) { 9 })) } }
        val c = crypto(signer)
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    /**
     * The id is what the signature actually covers. A signer that computed it
     * differently has not signed what this app thinks it signed, so the entry
     * would never verify again — better to refuse it now.
     */
    @Test
    fun `an event whose id we cannot reproduce is discarded`() = runTest {
        val signer = WellBehavedSigner().apply { mangle = { it.copy(id = hex(ByteArray(32) { 4 })) } }
        val c = crypto(signer)
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `a signature that does not verify is discarded`() = runTest {
        val c = crypto(WellBehavedSigner(), bip340 = neverValid)
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `a malformed signature field is discarded`() = runTest {
        val signer = WellBehavedSigner().apply { mangle = { it.copy(sig = "nonsense") } }
        val c = crypto(signer)
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    /** The signature is checked against the bytes fixed before the request. */
    @Test
    fun `the signature is verified against the reconstructed event id`() = runTest {
        var checked: Pair<ByteArray, ByteArray>? = null
        val c = crypto(
            WellBehavedSigner(),
            bip340 = Bip340Verifier { _, message, pub -> checked = message to pub; true },
        )
        val hash = c.hash(canonical)

        c.signCanonical(hash)

        val (message, pub) = assertNotNull(checked)
        assertEquals(
            hex(Nip01SigningEnvelope.eventId(OWNER, SigningDomain.RELATIONSHIP_LEDGER, hash, sha256)),
            hex(message),
        )
        assertEquals(OWNER, hex(pub))
    }

    // -------------------------------------------------- unavailable signer

    @Test
    fun `a signer that refuses reports no signature rather than throwing`() = runTest {
        val c = crypto(Nip01EventSigner { _, _, _, _ -> null })
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `a signer error is a missing signature, not a crash`() = runTest {
        val c = crypto(Nip01EventSigner { _, _, _, _ -> error("Amber is not installed") })
        assertNull(c.signCanonical(c.hash(canonical)))
    }

    @Test
    fun `an identity with no usable public key cannot sign`() = runTest {
        val signer = WellBehavedSigner()
        val c = crypto(signer, pubKey = null)

        assertNull(c.signCanonical(c.hash(canonical)))
        assertTrue(signer.requests.isEmpty(), "nothing may be sent to a signer we cannot check the answer against")
    }

    @Test
    fun `a public key that is not a key at all cannot sign`() = runTest {
        val signer = WellBehavedSigner()
        val c = crypto(signer, pubKey = "npub1definitelynothex")

        assertNull(c.signCanonical(c.hash(canonical)))
        assertTrue(signer.requests.isEmpty())
    }

    /**
     * A screen rotation cancels the coroutine the signing runs in. That has to
     * stay a cancellation all the way up, so no caller mistakes it for "the
     * user said no" and no caller carries on to write something.
     */
    @Test
    fun `a cancelled request stays cancelled rather than looking like a refusal`() = runTest {
        val approval = CompletableDeferred<Boolean>()
        val c = crypto(WellBehavedSigner(approval = approval))
        val hash = c.hash(canonical)

        val signing = async { c.signCanonical(hash) }
        runCurrent()
        signing.cancel()

        assertTrue(signing.isCancelled)
    }

    @Test
    fun `a cancellation thrown by the signer is not swallowed`() = runTest {
        val c = crypto(Nip01EventSigner { _, _, _, _ -> throw CancellationException("rotated away") })
        val hash = c.hash(canonical)

        val signing = async { c.signCanonical(hash) }
        runCurrent()

        assertTrue(signing.isCancelled, "a cancellation must not be reported as a refusal")
    }

    // ------------------------------------------------------- verification

    @Test
    fun `verification rebuilds the event id from the stored signature alone`() = runTest {
        var checked: ByteArray? = null
        val c = crypto(WellBehavedSigner(), bip340 = Bip340Verifier { _, message, _ -> checked = message; true })
        val hash = c.hash(canonical)

        assertTrue(c.verify(signatureBytes, hash, OWNER))

        assertEquals(
            hex(Nip01SigningEnvelope.eventId(OWNER, SigningDomain.RELATIONSHIP_LEDGER, hash, sha256)),
            hex(assertNotNull(checked)),
        )
    }

    @Test
    fun `verification uses the signer named on the entry, not the local identity`() = runTest {
        var checkedPub: ByteArray? = null
        val c = crypto(WellBehavedSigner(), bip340 = Bip340Verifier { _, _, pub -> checkedPub = pub; true })

        c.verify(signatureBytes, c.hash(canonical), OTHER)

        assertEquals(OTHER, hex(assertNotNull(checkedPub)))
    }

    @Test
    fun `a signer that is not a public key never reaches the curve`() {
        var reached = false
        val c = crypto(WellBehavedSigner(), bip340 = Bip340Verifier { _, _, _ -> reached = true; true })

        assertFalse(c.verify(signatureBytes, c.hash(canonical), "npub1nothex"))
        assertFalse(c.verify(signatureBytes, c.hash(canonical), ""))
        assertFalse(reached)
    }

    @Test
    fun `a signature of the wrong width is refused before the curve`() {
        var reached = false
        val c = crypto(WellBehavedSigner(), bip340 = Bip340Verifier { _, _, _ -> reached = true; true })

        assertFalse(c.verify(ByteArray(63), c.hash(canonical), OWNER))
        assertFalse(reached)
    }

    @Test
    fun `a curve failure is a refusal`() {
        val c = crypto(WellBehavedSigner(), bip340 = neverValid)
        assertFalse(c.verify(signatureBytes, c.hash(canonical), OWNER))
    }

    @Test
    fun `a signature for one ledger does not verify on the other`() = runTest {
        val relationship = crypto(WellBehavedSigner(), domain = SigningDomain.RELATIONSHIP_LEDGER)
        val policy = crypto(WellBehavedSigner(), domain = SigningDomain.OWNER_POLICY_LEDGER)
        val hash = relationship.hash(canonical)

        var relationshipMessage: ByteArray? = null
        var policyMessage: ByteArray? = null
        crypto(
            WellBehavedSigner(),
            bip340 = Bip340Verifier { _, m, _ -> relationshipMessage = m; true },
            domain = SigningDomain.RELATIONSHIP_LEDGER,
        ).verify(signatureBytes, hash, OWNER)
        crypto(
            WellBehavedSigner(),
            bip340 = Bip340Verifier { _, m, _ -> policyMessage = m; true },
            domain = SigningDomain.OWNER_POLICY_LEDGER,
        ).verify(signatureBytes, hash, OWNER)

        assertFalse(
            assertNotNull(relationshipMessage).contentEquals(assertNotNull(policyMessage)),
            "the two ledgers must not present the same message to the curve",
        )
        assertNotNull(relationship)
        assertNotNull(policy)
    }

    /**
     * The scheme change is not backwards compatible, and must not silently be.
     *
     * Signing the bare canonical hash — what every build before this one did —
     * presents different bytes to the curve than signing the event id. Any
     * signer that still does the old thing produces entries this verifier
     * refuses, so every signer in the app has to go through the envelope. The
     * debug demo peers are signers too, and this is what catches them.
     */
    @Test
    fun `a signature over the bare canonical hash is not a valid ledger signature`() {
        var presented: ByteArray? = null
        val c = crypto(WellBehavedSigner(), bip340 = Bip340Verifier { _, m, _ -> presented = m; true })
        val hash = c.hash(canonical)

        c.verify(signatureBytes, hash, OWNER)

        assertFalse(
            assertNotNull(presented).contentEquals(hash),
            "the curve must see the event id, never the canonical hash itself",
        )
        assertEquals(
            hex(Nip01SigningEnvelope.eventId(OWNER, SigningDomain.RELATIONSHIP_LEDGER, hash, sha256)),
            hex(assertNotNull(presented)),
        )
    }

    // ------------------------------------------------------------- timeout

    /**
     * A signer that accepts the request and never answers.
     *
     * This is not hypothetical: a NIP-55 signer is another app, and it can be
     * killed, suspended by the system, or left sitting behind a lock screen
     * with its prompt unanswered. Nothing about the IPC guarantees a reply.
     */
    private class SilentSigner : Nip01EventSigner {
        var calls = 0
            private set

        override suspend fun sign(
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): Nip01SignedEvent? {
            calls++
            awaitCancellation()
        }
    }

    /**
     * Waiting for ever is not fail-closed, it is fail-stuck: the mutation holds
     * the controller's lock and the screen's signing state, so every later
     * permission change is blocked too. Giving up is what keeps the app usable.
     */
    @Test
    fun `a signer that never answers is given up on rather than waited for`() = runTest {
        val signer = SilentSigner()
        val c = crypto(signer, timeoutMillis = 30_000)
        val hash = c.hash(canonical)

        val started = currentTime
        assertNull(c.signCanonical(hash))

        assertEquals(30_000L, currentTime - started, "the wait must be bounded by the configured timeout")
        assertEquals(1, signer.calls)
    }

    @Test
    fun `an answer that arrives inside the timeout is still accepted`() = runTest {
        val approval = CompletableDeferred<Boolean>()
        val c = crypto(WellBehavedSigner(approval = approval), timeoutMillis = 30_000)
        val hash = c.hash(canonical)

        val signing = async { c.signCanonical(hash) }
        runCurrent()
        approval.complete(true)

        assertNotNull(signing.await())
    }

    /**
     * The production default is a judgement call, so it is pinned: long enough
     * to unlock a phone, switch apps, read a prompt and approve it; short
     * enough that a signer which is never coming back does not wedge the
     * screen. A value outside this range is a mistake worth failing over.
     */
    @Test
    fun `the production default leaves time to approve and is finite`() {
        assertTrue(
            Nip55LedgerCrypto.DEFAULT_APPROVAL_TIMEOUT_MILLIS in 30_000L..600_000L,
            "an approval timeout of ${Nip55LedgerCrypto.DEFAULT_APPROVAL_TIMEOUT_MILLIS}ms is not defensible",
        )
    }

    /**
     * A timeout and a lifecycle cancellation are different things and must stay
     * different. Our own timeout is a refusal to keep waiting — `null`, visible
     * as SIGNER_UNAVAILABLE. An outer cancellation is the caller going away,
     * and reinterpreting it as a timeout would report a failure to a screen
     * that no longer exists, and worse, would let a caller carry on.
     */
    @Test
    fun `an outer cancellation is not reinterpreted as a timeout`() = runTest {
        val c = crypto(SilentSigner(), timeoutMillis = 30_000)
        val hash = c.hash(canonical)

        val signing = async { c.signCanonical(hash) }
        runCurrent()
        signing.cancel()

        assertTrue(signing.isCancelled, "a cancelled request must stay cancelled, not complete with null")
    }

    @Test
    fun `the timeout does not fire while the request is progressing normally`() = runTest {
        val c = crypto(WellBehavedSigner(), timeoutMillis = 30_000)

        val started = currentTime
        assertNotNull(c.signCanonical(c.hash(canonical)))

        assertEquals(0L, currentTime - started, "a prompt answered at once must not wait at all")
    }
}
