package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.ObjectRuleKey
import com.cruxcoach.domain.sharing.AccessEffect
import com.cruxcoach.domain.sharing.CryptoEraseStep
import com.cruxcoach.domain.sharing.DeviceId
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityPairing
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.AuthorityScope
import com.cruxcoach.domain.sharing.AuthorityEffect
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.KeyHandle
import com.cruxcoach.domain.sharing.KeyScope
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import com.cruxcoach.domain.sharing.ObjectId
import com.cruxcoach.domain.sharing.OwnerPolicyBody
import com.cruxcoach.domain.sharing.OwnerPolicyEntry
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle
import com.cruxcoach.domain.sharing.SharingLedgerBody
import com.cruxcoach.domain.sharing.SharingLedgerEntry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §B: the projection is written from the reduced ledger, survives a
 * restart, and a purge destroys keys before it deletes anything.
 */
class SecureDbSharingRepositoryTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository
    private val keyStore = InMemoryWrappingKeyStore()

    private val owner = "npub1owner"
    private val alice = PeerId("npub1alice")
    private var ownerSeq = 0L

    /** Appends one owner-policy entry, the only way policy can change now. */
    /**
     * Writes a policy entry with the act that authorises it.
     *
     * A policy entry no act vouches for grants nothing now, so a fixture that
     * wrote entries alone would be testing a state production never produces.
     */
    private fun policy(body: OwnerPolicyBody) {
        ownerSeq += 1
        val entry = OwnerPolicyEntry(
                id = LedgerEntryId("op-$ownerSeq"),
                policySequence = ownerSeq,
                // The policy is a DAG: every entry after the first names the
                // one it was built on.
                parent = if (ownerSeq > 1) LedgerEntryId("op-${ownerSeq - 1}") else null,
                authorityGeneration = 1,
                signerNpub = owner,
                signature = "sig-op-$ownerSeq",
                body = body,
        )
        val required = AuthorityPairing.requiredFor(entry.body)!!
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = DEVICE,
            parent = repo.currentAuthority()[required.scope]?.id,
            manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "act-sig",
        )
        check(repo.commitAll(listOf(entry), emptyList(), listOf(act))) {
            "the fixture's policy entry was refused: ${repo.lastBatchRejection}"
        }
    }
    private val phone = DeviceId("dev-phone")
    private val DEVICE = AuthorityDeviceId("repo-test-device")

    private fun open() {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        db = SecureDatabase(driver)
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(keyStore),
            verifier = { true },
            ownerPolicyVerifier = { true },
            ownerNpub = owner,
            deviceManifestVerifier = { true },
            attestationVerifier = { _, _ -> true },
        )
    }

    /**
     * Appends an entry together with the act that authorises it.
     *
     * Every owner-administrative write needs one now, so a test that wrote
     * entries alone would be testing a path production no longer has.
     */
    private fun append(entry: SharingLedgerEntry) {
        val required = AuthorityPairing.requiredFor(entry.peer, entry.body)
        if (required == null) {
            // The peer's own body. Carried by their signature, no owner act.
            repo.appendEntry(entry)
            return
        }
        // Already attested: re-appending is idempotent and must not mint a
        // second act, which would be two acts for one subject.
        if (repo.loadAttestations().any { it.subject == entry.id }) {
            repo.appendEntry(entry)
            return
        }
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = DEVICE,
            parent = repo.currentAuthority()[required.scope]?.id,
            manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "act-sig",
        )
        check(repo.commitAll(emptyList(), listOf(entry), listOf(act))) {
            "the fixture's entry was refused: ${repo.lastBatchRejection}"
        }
    }

    /** Enrols one PRIMARY device, so administrative writes have an author. */
    private fun enrolDevice() {
        repo.appendDeviceManifestEntry(
            DeviceManifestEntry(
                id = LedgerEntryId("m-1"),
                manifestSequence = 1,
                authorityGeneration = 1,
                parent = null,
                signerNpub = owner,
                signature = "m-sig",
                body = DeviceManifestBody.DeviceEnrolled(DEVICE, "pk", DeviceRole.PRIMARY),
            ),
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-sharing-repo-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        enrolDevice()
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun entry(
        id: String,
        seq: Long,
        body: SharingLedgerBody,
        // Owner by default; a recipient body must be signed by the peer, which
        // is what the reducer's role check enforces.
        signer: String = owner,
        // The ledger is a DAG: every entry after the first names the one it was
        // built on. Chaining on "e<seq-1>" keeps these fixtures a single
        // branch, which is what they were always testing.
        parent: String? = if (seq > 1) "e${seq - 1}" else null,
    ) = SharingLedgerEntry(
        id = LedgerEntryId(id),
        peer = alice,
        policySequence = seq,
        parent = parent?.let { LedgerEntryId(it) },
        authorityGeneration = 1,
        resourceEpoch = 1,
        deviceGeneration = 1,
        signerNpub = signer,
        signature = "sig-$id",
        body = body,
    )

    private val offer = entry(
        "e1", 1,
        SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)),
    )
    private val accept = entry(
        "e2", 2,
        SharingLedgerBody.RecipientAccepted(setOf(SharingCategory.VIDEOS), phone),
        signer = alice.value,
    )

    // ------------------------------------------------------------- ledger

    @Test
    fun `appending entries reduces and persists the projection`() {
        append(offer)
        append(accept)

        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertEquals(RelationshipStatus.ACCEPTED, state.status)
        assertEquals(setOf(SharingCategory.VIDEOS), state.consentedCategories)
        assertEquals(setOf(phone), state.authorisedDevices)
    }

    @Test
    fun `the projection survives a restart`() {
        append(offer)
        append(accept)
        driver.close()

        open()

        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertEquals(RelationshipStatus.ACCEPTED, state.status)
        assertEquals(setOf(SharingCategory.VIDEOS), state.consentedCategories)
    }

    @Test
    fun `appending the same entry twice is idempotent`() {
        append(offer)
        append(offer)
        assertEquals(1, repo.loadLedger(alice).size)
    }

    @Test
    fun `an entry that skips a sequence is refused rather than stored`() {
        // The reducer is order-independent, but an append-only store cannot
        // fill a gap later, so the write path requires exactly the next
        // sequence. Storing the gap first and rejecting it on every later read
        // would fail-close the relationship permanently.
        assertFailsWith<IllegalStateException> { append(accept) }
        assertTrue(repo.loadLedger(alice).isEmpty(), "a refused entry leaves no row")

        append(offer)
        append(accept)
        assertEquals(
            RelationshipStatus.ACCEPTED,
            assertNotNull(repo.loadProjection().relationships[alice]).status,
        )
    }

    @Test
    fun `rewriting the projection never deletes the signed history`() {
        // Regression: writing the projection row with INSERT OR REPLACE deleted
        // the row and the ledger's ON DELETE CASCADE took the whole signed
        // history with it, leaving a projection that looked right and a ledger
        // that was empty.
        append(offer)
        append(accept)

        assertEquals(2, repo.loadLedger(alice).size, "both entries must still be stored")
    }

    @Test
    fun `the next sequence follows the stored history`() {
        assertEquals(1L, repo.nextSequence(alice))
        append(offer)
        assertEquals(2L, repo.nextSequence(alice))
    }

    // ------------------------------------------------------------- policy

    @Test
    fun `baselines and rules load back into a policy`() {
        append(entry("e0", 1, SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS)))
        append(entry("e1b", 2, SharingLedgerBody.RelationshipOffered(setOf(SharingCategory.VIDEOS)), parent = "e0"))
        policy(OwnerPolicyBody.CircleBaselineSet(SharingCircle.ACQUAINTANCES, SharingCategory.PROFILE_AND_GOALS, true))
        policy(OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY))
        policy(OwnerPolicyBody.ObjectRuleSet(alice, ObjectId("v-1"), SharingCategory.VIDEOS, AccessEffect.ALLOW))

        val policy = repo.loadPolicy()

        assertEquals(
            setOf(SharingCategory.PROFILE_AND_GOALS),
            policy.baselines.explicitFor(SharingCircle.ACQUAINTANCES),
        )
        val peer = assertNotNull(policy.peers[alice])
        assertEquals(SharingCircle.FRIENDS, peer.circle)
        assertEquals(AccessEffect.DENY, peer.categoryRules[SharingCategory.VIDEOS])
        assertEquals(AccessEffect.ALLOW, peer.objectRules[ObjectRuleKey(ObjectId("v-1"), SharingCategory.VIDEOS)])
    }

    @Test
    fun `clearing a rule removes it from the policy`() {
        append(offer)
        policy(OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY))
        policy(OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, null))

        assertNull(repo.loadPolicy().peers[alice]?.categoryRules?.get(SharingCategory.VIDEOS))
    }

    @Test
    fun `removing a baseline takes it away again`() {
        policy(OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true))
        policy(OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false))
        assertTrue(repo.loadPolicy().baselines.explicitFor(SharingCircle.FRIENDS).isEmpty())
    }

    // -------------------------------------------------------------- purge

    /**
     * This used to assert the opposite — that the category key was gone — and
     * so encoded the bug rather than catching it. `CATEGORY:VIDEOS` is the key
     * *the owner's own* videos are sealed under; removing one person destroyed
     * it, taking the owner's data and every other relationship's access with
     * it. See [SharingPurgeScopeTest] for the full two-relationship case.
     *
     * The key-first ordering is still the contract and is still asserted.
     */
    @Test
    fun `a purge runs keys-first but spares the owner's own keys`() {
        append(offer)
        val handle = KeyHandle(KeyScope.CATEGORY, SharingCategory.VIDEOS.name)
        repo.createDataKey(handle)

        val order = mutableListOf<CryptoEraseStep>()
        val outcome = repo.purgeLocalRelationshipData(alice) { order += it }

        assertTrue(outcome.completed)
        assertEquals(CryptoEraseStep.DESTROY_KEYS, order.first())
        assertNotNull(keyStore.get(handle), "the owner's own wrapping key must survive")
        assertNotNull(repo.readWrappedKey(handle), "and so must its stored wrapped key")
    }

    @Test
    fun `a purge removes the relationship and everything cascading from it`() {
        append(offer)
        policy(OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY))

        repo.purgeLocalRelationshipData(alice)

        assertNull(repo.loadProjection().relationships[alice])
        assertTrue(repo.loadLedger(alice).isEmpty())
    }

    @Test
    fun `a purge leaves a sticky tombstone behind`() {
        append(offer)
        repo.purgeLocalRelationshipData(alice)
        assertEquals(1L, db.sharingQueries.countTombstones().executeAsOne())
    }

    @Test
    fun `a purged peer cannot be silently recreated by a replayed old entry`() {
        append(offer)
        repo.purgeLocalRelationshipData(alice)

        append(offer)

        val state = repo.loadProjection().relationships[alice]
        assertTrue(
            state == null || state.status != RelationshipStatus.ACCEPTED,
            "a replay after a purge must not restore an active relationship",
        )
        assertEquals(1L, db.sharingQueries.countTombstones().executeAsOne())
    }

    // ------------------------------------------------------- sealed items

    @Test
    fun `a sealed item is stored as ciphertext and reads back through the vault`() {
        val handle = KeyHandle(KeyScope.CATEGORY, SharingCategory.PRIVATE_NOTES.name)
        val key = repo.createDataKey(handle)
        val plaintext = "Schulter schont sich noch".encodeToByteArray()

        repo.storeSealedItem("n1", SharingCategory.PRIVATE_NOTES, handle, key, plaintext)

        val roundTripped = repo.readOwnerSealedItem("n1", key)
        assertEquals("Schulter schont sich noch", roundTripped?.decodeToString())
    }

    @Test
    fun `destroying the key makes a stored item unreadable`() {
        val handle = KeyHandle(KeyScope.CATEGORY, SharingCategory.PRIVATE_NOTES.name)
        val key = repo.createDataKey(handle)
        repo.storeSealedItem("n1", SharingCategory.PRIVATE_NOTES, handle, key, "geheim".encodeToByteArray())

        repo.destroyKey(handle)

        assertNull(repo.readOwnerSealedItem("n1", key), "no key, no plaintext")
    }

    // ------------------------- offline policy branches, through the database

    /**
     * Two of the owner's devices, offline, both changing the same thing.
     *
     * These run against the real database — schema, admission, projection — in
     * both arrival orders, because the whole point is that the answer must not
     * depend on which one the database saw first.
     */
    /**
     * [concurrentWith] names an act this one was made *alongside*, offline.
     *
     * Without it a sibling written second chains onto the first, because the
     * first is by then the scope's head — which makes it a causal successor
     * rather than a concurrent branch, and successors are supposed to win.
     */
    private fun storePolicyBranch(
        id: String,
        body: OwnerPolicyBody,
        parent: String?,
        sequence: Long,
        device: AuthorityDeviceId,
        concurrentWith: String? = null,
    ) {
        val entry = OwnerPolicyEntry(
            id = LedgerEntryId(id),
            policySequence = sequence,
            parent = parent?.let { LedgerEntryId(it) },
            authorityGeneration = 1,
            signerNpub = owner,
            signature = "sig-$id",
            body = body,
        )
        val required = AuthorityPairing.requiredFor(body)!!
        val actParent = if (concurrentWith != null) {
            repo.loadAttestations().first { it.id == LedgerEntryId("act-$concurrentWith") }.parent
        } else {
            repo.currentAuthority()[required.scope]?.id
        }
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-$id"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            parent = actParent,
            manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "act-sig",
        )
        check(repo.commitAll(listOf(entry), emptyList(), listOf(act))) {
            "branch $id was refused: ${repo.lastBatchRejection}"
        }
    }

    /**
     * Enrols another device, with the act asking for it.
     *
     * The act goes first and names the entry's parent — the estate as it stood
     * before the enrolment — because a change to the manifest cannot be its own
     * justification.
     */
    private fun enrolSecondDevice(device: AuthorityDeviceId, role: DeviceRole) {
        val entry = DeviceManifestEntry(
            id = LedgerEntryId("m-${device.value}"),
            manifestSequence = repo.nextManifestSequence(),
            authorityGeneration = 1,
            parent = repo.loadDeviceAuthority().head,
            signerNpub = owner,
            signature = "m-sig",
            body = DeviceManifestBody.DeviceEnrolled(device, "pk", role),
        )
        val required = AuthorityPairing.requiredFor(entry.body)!!
        repo.appendAttestation(
            AuthorityAttestation(
                id = LedgerEntryId("act-${entry.id.value}"),
                scope = required.scope,
                subject = entry.id,
                device = DEVICE,
                parent = null,
                manifestContext = ManifestContext.of(listOfNotNull(entry.parent)),
                authorityGeneration = 1,
                capability = required.capability,
                effect = required.effect,
                signature = "act-sig",
            ),
        )
        repo.appendDeviceManifestEntry(entry)
    }

    private val other = AuthorityDeviceId("other-repo-device")

    private fun baselineFork(first: AuthorityDeviceId, second: AuthorityDeviceId) {
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "grant",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "root", 2, first,
        )
        storePolicyBranch(
            "deny",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
            "root", 2, second,
            concurrentWith = "grant",
        )
    }

    @Test
    fun `a baseline denial beats a grant whichever the database saw first`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)

        baselineFork(first = DEVICE, second = other)

        assertTrue(
            SharingCategory.VIDEOS !in repo.loadOwnerPolicyState().baselines.explicitFor(SharingCircle.FRIENDS),
        )
    }

    @Test
    fun `the same is true when the denial is stored first`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "deny",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
            "root", 2, other,
        )
        storePolicyBranch(
            "grant",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
            concurrentWith = "deny",
        )

        assertTrue(
            SharingCategory.VIDEOS !in repo.loadOwnerPolicyState().baselines.explicitFor(SharingCircle.FRIENDS),
        )
    }

    @Test
    fun `a peer rule denial beats an allow through the database`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "allow",
            OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            "root", 2, DEVICE,
        )
        storePolicyBranch(
            "deny",
            OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY),
            "root", 2, other,
        )

        assertEquals(
            AccessEffect.DENY,
            repo.loadOwnerPolicyState().peerRules[alice]?.get(SharingCategory.VIDEOS),
        )
    }

    @Test
    fun `an object rule denial beats an allow through the database`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        val video = ObjectId("vid-1")
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "allow",
            OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            "root", 2, DEVICE,
        )
        storePolicyBranch(
            "deny",
            OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, AccessEffect.DENY),
            "root", 2, other,
        )

        assertEquals(AccessEffect.DENY, repo.loadOwnerPolicyState().objectRules[alice]?.get(ObjectRuleKey(video, SharingCategory.VIDEOS)))
    }

    /** Mandatory matrix: PRIMARY outranks TRUSTED on the same effect. */
    @Test
    fun `a primary device wins a policy tie through the database`() {
        // "other" sorts after the test device, so rank has to be what decides.
        enrolSecondDevice(other, DeviceRole.PRIMARY)
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "a",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
        )
        storePolicyBranch(
            "b",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.TRAINING_HISTORY, true),
            "root", 2, other,
        )

        val baselines = repo.loadOwnerPolicyState().baselines.explicitFor(SharingCircle.FRIENDS)
        assertTrue(SharingCategory.TRAINING_HISTORY in baselines, "the primary device's change stands")
    }

    /** The projection is the same after a restart. */
    @Test
    fun `the winning policy branch survives a restart`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        baselineFork(first = DEVICE, second = other)
        val before = repo.loadOwnerPolicyState()

        driver.close()
        open()

        assertEquals(before, repo.loadOwnerPolicyState())
    }

    // ------------------------------- independent scopes, through the database

    @Test
    fun `two different baselines both stand whichever arrived first`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "videos",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
        )
        storePolicyBranch(
            "history",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.TRAINING_HISTORY, true),
            "root", 2, other,
        )

        val baselines = repo.loadOwnerPolicyState().baselines.explicitFor(SharingCircle.FRIENDS)
        assertTrue(SharingCategory.VIDEOS in baselines, "an unrelated baseline must not be displaced")
        assertTrue(SharingCategory.TRAINING_HISTORY in baselines)
    }

    @Test
    fun `a peer rule and an object rule both stand`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        val video = ObjectId("vid-1")
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "peer",
            OwnerPolicyBody.PeerRuleSet(alice, SharingCategory.VIDEOS, AccessEffect.DENY),
            "root", 2, other,
        )
        storePolicyBranch(
            "object",
            OwnerPolicyBody.ObjectRuleSet(alice, video, SharingCategory.VIDEOS, AccessEffect.ALLOW),
            "root", 2, DEVICE,
        )

        val policy = repo.loadOwnerPolicyState()
        assertEquals(AccessEffect.DENY, policy.peerRules[alice]?.get(SharingCategory.VIDEOS))
        assertEquals(AccessEffect.ALLOW, policy.objectRules[alice]?.get(ObjectRuleKey(video, SharingCategory.VIDEOS)))
    }

    /**
     * The number a new entry claims follows the branch that stands.
     *
     * A long abandoned branch holds the higher numbers, and following those
     * left every later entry hanging off a branch nobody chose.
     */
    @Test
    fun `a long losing branch does not drag the numbering with it`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        // The branch that wins: one restrictive change to the videos baseline.
        storePolicyBranch(
            "winner",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
            "root", 2, other,
        )
        // The branch that loses, grown long.
        storePolicyBranch(
            "loser-1",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
            concurrentWith = "winner",
        )

        val head = assertNotNull(repo.authorisedPolicyHead())
        assertEquals(LedgerEntryId("winner"), head.id, "the tip is the branch that stands")
        assertEquals(3L, repo.nextOwnerPolicySequence(), "and the numbering follows it")
    }

    @Test
    fun `the winner can be continued after a losing branch`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "winner",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
            "root", 2, other,
        )
        storePolicyBranch(
            "loser",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
            concurrentWith = "winner",
        )

        // Continuing the winner has to be admissible, at the number that
        // follows it rather than the one the losing branch reached.
        storePolicyBranch(
            "next",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ACQUAINTANCES, SharingCategory.VIDEOS, true),
            "winner", 3, other,
        )

        assertEquals(LedgerEntryId("next"), assertNotNull(repo.authorisedPolicyHead()).id)
        assertTrue(
            SharingCategory.VIDEOS in
                repo.loadOwnerPolicyState().baselines.explicitFor(SharingCircle.ACQUAINTANCES),
        )
    }

    @Test
    fun `independent policy scopes survive a restart`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ALL_OTHER_USERS, SharingCategory.PROFILE_AND_GOALS, true),
            null, 1, DEVICE,
        )
        storePolicyBranch(
            "videos",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
        )
        storePolicyBranch(
            "history",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.TRAINING_HISTORY, true),
            "root", 2, other,
        )
        val before = repo.loadOwnerPolicyState()

        driver.close()
        open()

        assertEquals(before, repo.loadOwnerPolicyState())
    }

    // ------------------ independent relationship scopes, through the database

    private fun storeRelationshipBranch(
        id: String,
        body: SharingLedgerBody,
        parent: String?,
        sequence: Long,
        device: AuthorityDeviceId,
        concurrentWith: String? = null,
    ) {
        val e = entry(id, sequence, body, parent = parent)
        val required = AuthorityPairing.requiredFor(e.peer, e.body)!!
        val actParent = if (concurrentWith != null) {
            repo.loadAttestations().first { it.id == LedgerEntryId("act-$concurrentWith") }.parent
        } else {
            repo.currentAuthority()[required.scope]?.id
        }
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-$id"),
            scope = required.scope,
            subject = e.id,
            device = device,
            parent = actParent,
            manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
            authorityGeneration = 1,
            capability = required.capability,
            effect = required.effect,
            signature = "act-sig",
        )
        check(repo.commitAll(emptyList(), listOf(e), listOf(act))) {
            "branch $id was refused: ${repo.lastBatchRejection}"
        }
    }

    /**
     * A grant is about the peer; a device revocation is about one of their
     * devices. Neither displaces the other, whichever the database saw first.
     */
    @Test
    fun `a grant and a device revocation both stand`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storeRelationshipBranch(
            "r0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1, DEVICE,
        )
        storeRelationshipBranch(
            "r-grant", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "r0", 2, DEVICE,
        )
        storeRelationshipBranch(
            "r-revoke", SharingLedgerBody.DeviceRevoked(phone), "r0", 2, other,
        )

        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertTrue(phone in state.revokedDevices, "the revocation stands")
        assertEquals(SharingCircle.FRIENDS, state.circle, "and the grant branch was not discarded")
    }

    @Test
    fun `the same holds when the revocation is stored first`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storeRelationshipBranch(
            "r0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1, DEVICE,
        )
        storeRelationshipBranch(
            "r-revoke", SharingLedgerBody.DeviceRevoked(phone), "r0", 2, other,
        )
        storeRelationshipBranch(
            "r-grant", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "r0", 2, DEVICE,
        )

        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertTrue(phone in state.revokedDevices)
        assertEquals(SharingCircle.FRIENDS, state.circle)
    }

    @Test
    fun `two revocations of different devices both stand`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        val tablet = DeviceId("dev-tablet")
        storeRelationshipBranch(
            "r0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1, DEVICE,
        )
        storeRelationshipBranch("r-one", SharingLedgerBody.DeviceRevoked(phone), "r0", 2, DEVICE)
        storeRelationshipBranch("r-two", SharingLedgerBody.DeviceRevoked(tablet), "r0", 2, other)

        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertTrue(phone in state.revokedDevices)
        assertTrue(tablet in state.revokedDevices)
    }

    @Test
    fun `independent relationship scopes survive a restart`() {
        enrolSecondDevice(other, DeviceRole.TRUSTED)
        storeRelationshipBranch(
            "r0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1, DEVICE,
        )
        storeRelationshipBranch(
            "r-grant", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "r0", 2, DEVICE,
        )
        storeRelationshipBranch("r-revoke", SharingLedgerBody.DeviceRevoked(phone), "r0", 2, other)
        val before = repo.loadProjection()

        driver.close()
        open()

        assertEquals(before, repo.loadProjection())
    }

    // ------------------------- a malformed DAG on disk, across a restart

    /** Writes an attestation row straight in, bypassing admission. */
    private fun forceAct(
        id: String,
        subject: String,
        scope: AuthorityScope,
        parent: String?,
        effect: AuthorityEffect = AuthorityEffect.PERMISSIVE,
        capability: DeviceCapability = DeviceCapability.MUTATE_PERMISSIONS,
    ) = driver.execute(
        null,
        """
        INSERT INTO sharing_authority_attestation
          (attestation_id, scope, subject_entry_id, device_id, parent_attestation_id,
           authority_generation, capability, effect, signature, received_at)
        VALUES ('$id', '${scope.value}', '$subject', '${DEVICE.value}',
                ${parent?.let { "'$it'" } ?: "NULL"}, 1, '${capability.name}', '${effect.name}', 'sig', 0)
        """.trimIndent(),
        0,
    ).value

    private fun seedOnePolicyEntry() {
        storePolicyBranch(
            "root",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, true),
            null, 1, DEVICE,
        )
    }

    private fun assertProjectsNothingAcrossRestart(what: String) {
        assertTrue(repo.currentAuthority().isEmpty(), "$what must leave no current authority")
        driver.close()
        open()
        assertTrue(repo.currentAuthority().isEmpty(), "$what must still leave none after a restart")
    }

    @Test
    fun `an act naming a subject that is not stored fails the estate closed`() {
        seedOnePolicyEntry()

        forceAct("act-orphan", "nowhere", AuthorityScope.estate(), null)

        assertProjectsNothingAcrossRestart("an orphan act")
    }

    /**
     * The database refuses it outright, which is stronger than failing closed
     * on read: the second act never reaches disk at all.
     */
    @Test
    fun `a second act for one subject cannot even be stored`() {
        seedOnePolicyEntry()

        assertFailsWith<Exception> {
            forceAct(
                "act-second", "root",
                AuthorityScope.baseline(SharingCircle.FRIENDS, SharingCategory.VIDEOS), null,
            )
        }
    }

    @Test
    fun `an act from the superseded scope encoding fails the estate closed`() {
        seedOnePolicyEntry()
        storePolicyBranch(
            "next",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ACQUAINTANCES, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
        )

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET scope = 'peer|legacy' WHERE attestation_id = 'act-next'",
            0,
        ).value

        assertProjectsNothingAcrossRestart("a legacy scope")
    }

    @Test
    fun `an act following one in another scope fails the estate closed`() {
        seedOnePolicyEntry()
        storePolicyBranch(
            "next",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.ACQUAINTANCES, SharingCategory.VIDEOS, true),
            "root", 2, DEVICE,
        )

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET parent_attestation_id = 'act-root' " +
                "WHERE attestation_id = 'act-next'",
            0,
        ).value

        assertProjectsNothingAcrossRestart("a cross-scope act parent")
    }

    @Test
    fun `an act parent that is not stored fails the estate closed`() {
        seedOnePolicyEntry()

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET parent_attestation_id = 'act-nowhere' " +
                "WHERE attestation_id = 'act-root'",
            0,
        ).value

        assertProjectsNothingAcrossRestart("a dangling act parent")
    }

    @Test
    fun `a cycle among acts fails the estate closed`() {
        seedOnePolicyEntry()
        storePolicyBranch(
            "next",
            OwnerPolicyBody.CircleBaselineSet(SharingCircle.FRIENDS, SharingCategory.VIDEOS, false),
            "root", 2, DEVICE,
        )

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET parent_attestation_id = 'act-next' " +
                "WHERE attestation_id = 'act-root'",
            0,
        ).value

        assertProjectsNothingAcrossRestart("a cycle")
    }

    // ---------------------- an administrative change with no act at all

    /**
     * The act that authorised a revocation is deleted from under it.
     *
     * Nothing on disk then says the revocation happened with any authority, and
     * the grant it withdrew is still sitting there, older but intact. Reading
     * that as "the grant stands" is a withdrawal of access disappearing in
     * silence, so the whole estate closes instead.
     */
    @Test
    fun `a revocation whose act is gone releases nothing across a restart`() {
        storeRelationshipBranch(
            "r0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1, DEVICE,
        )
        storeRelationshipBranch(
            "r-grant", SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)), "r0", 2, DEVICE,
        )
        storeRelationshipBranch("r-revoke", SharingLedgerBody.RelationshipRevoked, "r-grant", 3, DEVICE)

        driver.execute(
            null,
            "DELETE FROM sharing_authority_attestation WHERE attestation_id = 'act-r-revoke'",
            0,
        ).value

        assertProjectsNothingAcrossRestart("a revocation with no act")
        val state = assertNotNull(repo.loadProjection().relationships[alice])
        assertEquals(RelationshipStatus.FAIL_CLOSED, state.status)
        assertTrue(
            state.offeredCategories.isEmpty(),
            "the grant the missing revocation withdrew must not stand in its place",
        )
    }

    /**
     * One broken ledger closes the other.
     *
     * The two are one estate. Letting the policy go on granting while the
     * relationship history cannot be read would release data on the strength of
     * exactly half the evidence.
     */
    @Test
    fun `a relationship with no act closes the owner policy too`() {
        seedOnePolicyEntry()
        storeRelationshipBranch(
            "r0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1, DEVICE,
        )

        driver.execute(
            null,
            "DELETE FROM sharing_authority_attestation WHERE attestation_id = 'act-r0'",
            0,
        ).value

        driver.close()
        open()

        assertTrue(
            SharingCategory.VIDEOS !in
                repo.loadOwnerPolicyState().baselines.explicitFor(SharingCircle.FRIENDS),
            "half a readable estate releases nothing",
        )
    }

    /** And the other way round. */
    @Test
    fun `a policy entry with no act closes the relationships too`() {
        seedOnePolicyEntry()
        storeRelationshipBranch(
            "r0", SharingLedgerBody.PeerCircleAssigned(SharingCircle.FRIENDS), null, 1, DEVICE,
        )

        driver.execute(
            null,
            "DELETE FROM sharing_authority_attestation WHERE attestation_id = 'act-root'",
            0,
        ).value

        driver.close()
        open()

        assertEquals(
            RelationshipStatus.FAIL_CLOSED,
            assertNotNull(repo.loadProjection().relationships[alice]).status,
        )
    }

    /**
     * And the projection goes with it: nothing released on the strength of a
     * history nobody can reconstruct.
     */
    @Test
    fun `a malformed estate releases no policy across a restart`() {
        seedOnePolicyEntry()
        forceAct("act-orphan", "nowhere", AuthorityScope.estate(), null)

        driver.close()
        open()

        assertTrue(
            SharingCategory.VIDEOS !in repo.loadOwnerPolicyState().baselines.explicitFor(SharingCircle.FRIENDS),
            "a truncated history must not release anything",
        )
    }
}
