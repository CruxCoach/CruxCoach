package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityAttestationVerifier
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.AuthorityEffect
import com.cruxcoach.domain.sharing.AuthorityScope
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestVerifier
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingLedgerSigner
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: administrative acts on disk, and the transaction that ties one
 * to the permission entry it authorises.
 *
 * The two have to land together or not at all. A permission entry without its
 * attestation is a change nobody signed for; an attestation without its entry
 * claims authority for something that never happened. Either one alone is a
 * lie the next reader has no way to detect.
 */
class SecureDbAuthorityAttestationTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val PHONE = AuthorityDeviceId("bbbb2222")
        val TABLET = AuthorityDeviceId("cccc3333")
        val FRIEND = PeerId("npub1friend")
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository

    private val manifestVerifier = DeviceManifestVerifier { entry ->
        entry.signature == "${entry.signerNpub}:${entry.id.value}"
    }
    private val attestationVerifier = AuthorityAttestationVerifier { attestation, publicKey ->
        attestation.signature == "$publicKey:${attestation.id.value}"
    }

    private class TaggedCrypto(private val identity: String) :
        com.cruxcoach.domain.sharing.LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    private fun open() {
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}?foreign_keys=on").also { d ->
            SecureDatabasePragmas.STATEMENTS.forEach { runCatching { d.execute(null, it, 0).value } }
        }
        db = SecureDatabase(driver)
        repo = SecureDbSharingRepository(
            database = db,
            vault = AeadSharingKeyVault(InMemoryWrappingKeyStore()),
            verifier = SharingLedgerSigner(TaggedCrypto(OWNER)).verifier(),
            ownerPolicyVerifier = OwnerPolicySigner(TaggedCrypto(OWNER)).verifier(),
            ownerNpub = OWNER,
            deviceManifestVerifier = manifestVerifier,
            attestationVerifier = attestationVerifier,
        )
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-attestation-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
        enrol()
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun manifestEntry(seq: Long, body: DeviceManifestBody, parent: String?) =
        DeviceManifestEntry(
            id = LedgerEntryId("m-$seq"),
            manifestSequence = seq,
            authorityGeneration = 1,
            parent = parent?.let { LedgerEntryId(it) },
            signerNpub = OWNER,
            signature = "$OWNER:m-$seq",
            body = body,
        )

    private fun enrol() {
        // The genesis carries only the root signature: there is no device yet
        // that could have asked for it.
        repo.appendDeviceManifestEntry(
            manifestEntry(1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk-${LAPTOP.value}", DeviceRole.PRIMARY), null),
        )
        askedFor(
            manifestEntry(
                2,
                DeviceManifestBody.DeviceEnrolled(PHONE, "pk-${PHONE.value}", DeviceRole.READ_ONLY),
                "m-1",
            ),
        )
    }

    /**
     * Writes a manifest entry with the act asking for it, act first.
     *
     * Every ordinary mutation needs one now, and it is judged against the
     * estate at the entry's *parent* — which is what the store still holds at
     * the moment the act goes in.
     */
    private fun askedFor(
        entry: DeviceManifestEntry,
        device: AuthorityDeviceId = LAPTOP,
        offline: Boolean = false,
    ) {
        val required = com.cruxcoach.domain.sharing.AuthorityPairing.requiredFor(entry.body)!!
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            // The act it was built on: the last one in this device's own
            // scope, so an enrolment and a later role change chain rather
            // than looking concurrent.
            parent = repo.loadAttestations().lastOrNull { it.scope == required.scope }?.id,
            // A local mutation binds the whole frontier standing now — after
                // a merge that is both branches. An offline sibling binds only
                // what its author had seen.
                manifestContext = if (offline) {
                    ManifestContext.of(listOfNotNull(entry.parent))
                } else {
                    ManifestContext.of(repo.loadDeviceAuthority().frontier)
                },
            authorityGeneration = repo.loadDeviceAuthority().authorityGeneration,
            capability = required.capability,
            effect = required.effect,
            signature = "pk-${device.value}:act-${entry.id.value}",
        )
        // Through the batch door, so the act is judged against the estate at
        // the entry's parent rather than against whatever head is standing.
        check(repo.commitRecovery(listOf(entry), emptyList(), listOf(act))) {
            "the fixture's manifest entry was refused: " + repo.lastBatchRejection
        }
    }

    /** The acts these fixtures wrote, without the estate's own enrolment act. */
    private fun storedActs() = repo.loadAttestations().filterNot { it.id.value.startsWith("act-m-") }

    private fun attest(
        id: String,
        device: AuthorityDeviceId = LAPTOP,
        effect: AuthorityEffect = AuthorityEffect.PERMISSIVE,
        parent: String? = null,
        capability: DeviceCapability = DeviceCapability.MUTATE_PERMISSIONS,
        scope: AuthorityScope = AuthorityScope.category(FRIEND, SharingCategory.VIDEOS),
    ) = AuthorityAttestation(
        id = LedgerEntryId(id),
        scope = scope,
        subject = LedgerEntryId("entry-$id"),
        device = device,
        parent = parent?.let { LedgerEntryId(it) },
        manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
        authorityGeneration = 1,
        capability = capability,
        effect = effect,
        signature = "pk-${device.value}:$id",
    )

    // -------------------------------------------------------------- storing

    @Test
    fun `an appended act is readable back`() {
        val act = attest("a")

        assertTrue(repo.tryAppendAttestation(act))

        assertEquals(listOf(act), storedActs())
    }

    @Test
    fun `every field survives closing and reopening the database`() {
        repo.appendAttestation(attest("a"))
        repo.appendAttestation(attest("b", effect = AuthorityEffect.RESTRICTIVE, parent = "a"))
        repo.appendAttestation(
            attest("c", capability = DeviceCapability.ADMINISTER_DEVICES, scope = AuthorityScope.estate()),
        )
        val before = storedActs()

        driver.close()
        open()

        assertEquals(before, storedActs(), "acts must round-trip byte for byte")
    }

    @Test
    fun `the winner is the same before and after a restart`() {
        repo.appendAttestation(attest("a"))
        repo.appendAttestation(attest("b", effect = AuthorityEffect.RESTRICTIVE, parent = "a"))
        // Resolved over the stored acts themselves. currentAuthority() also
        // drops acts whose subject is not stored, and these fixtures name
        // subjects that were never written — see the orphan test below.
        fun winners() = com.cruxcoach.domain.sharing.AuthorityLedger.winners(
            com.cruxcoach.domain.sharing.TrustedAttestations.of(
                storedActs(),
                repo.loadDeviceAuthority(),
                attestationVerifier,
            ),
        )
        val before = winners()

        driver.close()
        open()

        assertEquals(before, winners())
        assertEquals(
            LedgerEntryId("b"),
            winners()[AuthorityScope.category(FRIEND, SharingCategory.VIDEOS)]?.id,
        )
    }

    // ------------------------------------------------------- refusing writes

    @Test
    fun `a device without the capability writes nothing`() {
        assertTrue(!repo.tryAppendAttestation(attest("a", device = PHONE)))

        assertEquals(emptyList(), storedActs())
    }

    @Test
    fun `a local act with a broken invariant fails hard rather than storing it`() {
        assertFailsWith<IllegalStateException> { repo.appendAttestation(attest("a", device = PHONE)) }

        assertEquals(emptyList(), storedActs())
    }

    @Test
    fun `a forged signature writes nothing`() {
        val forged = attest("a").copy(signature = "pk-${PHONE.value}:a")

        assertTrue(!repo.tryAppendAttestation(forged))
        assertEquals(emptyList(), storedActs())
    }

    @Test
    fun `a byte-identical duplicate is idempotent`() {
        repo.appendAttestation(attest("a"))
        assertTrue(repo.tryAppendAttestation(attest("a")))

        assertEquals(1, storedActs().size)
    }

    // ---------------------------------------------------------- atomicity

    /**
     * The point of the whole slice. A refused act must take its permission
     * entry down with it, or the ledger records a change with no signed
     * authority behind it.
     */
    @Test
    fun `a refused act rolls the permission entry back with it`() {
        val entry = repo.let { _ ->
            com.cruxcoach.domain.sharing.SharingLedgerEntry(
                id = LedgerEntryId("entry-1"),
                peer = FRIEND,
                policySequence = 1,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = OWNER,
                signature = "",
                body = com.cruxcoach.domain.sharing.SharingLedgerBody.PeerCircleAssigned(
                    com.cruxcoach.domain.sharing.SharingCircle.FRIENDS,
                ),
            )
        }
        val signed = SharingLedgerSigner(TaggedCrypto(OWNER)).sign(entry)!!

        val stored = repo.commitAll(
            policyEntries = emptyList(),
            relationshipEntries = listOf(signed),
            attestations = listOf(attest("a", device = PHONE)),
        )

        assertTrue(!stored, "an unauthorised act must fail the whole batch")
        assertEquals(emptyList(), storedActs())
        assertEquals(emptyList(), repo.loadLedger(FRIEND), "the entry must not survive its own authority")
        assertNull(repo.loadProjection().relationships[FRIEND])
    }

    @Test
    fun `an authorised act lands together with its permission entry`() {
        val signed = SharingLedgerSigner(TaggedCrypto(OWNER)).sign(
            com.cruxcoach.domain.sharing.SharingLedgerEntry(
                id = LedgerEntryId("entry-1"),
                peer = FRIEND,
                policySequence = 1,
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = OWNER,
                signature = "",
                body = com.cruxcoach.domain.sharing.SharingLedgerBody.PeerCircleAssigned(
                    com.cruxcoach.domain.sharing.SharingCircle.FRIENDS,
                ),
            ),
        )!!

        // The act has to name *this* entry and match what its body requires;
        // the fixture used to point at an unrelated id and still claim to pair.
        assertTrue(
            repo.commitAll(
                policyEntries = emptyList(),
                relationshipEntries = listOf(signed),
                attestations = listOf(pairedAct("a", "entry-1")),
            ),
        )

        assertEquals(1, storedActs().size)
        assertEquals(1, repo.loadLedger(FRIEND).size)
    }

    // ----------------------------------------------------------- generation

    @Test
    fun `an act from before a rotation is refused after it`() {
        repo.appendAttestation(attest("a"))

        repo.appendDeviceManifestEntry(
            manifestEntry(3, DeviceManifestBody.AuthorityRotated(2, fenceExisting = false), "m-2"),
        )

        // Generation 1 is now stale, and the manifest says so.
        assertTrue(!repo.tryAppendAttestation(attest("b", parent = "a")))
        assertEquals(1, storedActs().size)
    }

    @Test
    fun `a fenced device loses its say without losing its history`() {
        repo.appendAttestation(attest("a"))

        repo.appendDeviceManifestEntry(
            manifestEntry(3, DeviceManifestBody.AuthorityRotated(2, fenceExisting = true), "m-2"),
        )

        assertEquals(1, storedActs().size, "the act stays on record")
        assertNull(
            repo.currentAuthority()[AuthorityScope.category(FRIEND, SharingCategory.VIDEOS)],
            "but it no longer decides anything",
        )
    }

    // ------------------------------------------------------------- pairing

    private fun signedEntry(
        id: String,
        body: com.cruxcoach.domain.sharing.SharingLedgerBody,
        seq: Long = 1,
        parent: String? = null,
    ) =
        SharingLedgerSigner(TaggedCrypto(OWNER)).sign(
            com.cruxcoach.domain.sharing.SharingLedgerEntry(
                id = LedgerEntryId(id),
                peer = FRIEND,
                policySequence = seq,
                parent = parent?.let { LedgerEntryId(it) },
                authorityGeneration = 1,
                resourceEpoch = 1,
                deviceGeneration = 1,
                signerNpub = OWNER,
                signature = "",
                body = body,
            ),
        )!!

    private fun pairedAct(
        id: String,
        subject: String,
        device: AuthorityDeviceId = LAPTOP,
        scope: AuthorityScope = AuthorityScope.peer(FRIEND),
        effect: AuthorityEffect = AuthorityEffect.PERMISSIVE,
        capability: DeviceCapability = DeviceCapability.MUTATE_PERMISSIONS,
    ) = AuthorityAttestation(
        id = LedgerEntryId(id),
        scope = scope,
        subject = LedgerEntryId(subject),
        device = device,
        parent = null,
        manifestContext = ManifestContext.of(repo.loadDeviceAuthority().frontier),
        authorityGeneration = 1,
        capability = capability,
        effect = effect,
        signature = "pk-${device.value}:$id",
    )

    /**
     * An owner-signed entry with no act behind it must not land. It would sit
     * in the ledger as a permission change nobody's device authorised, and
     * nothing later can tell it apart from one that was authorised.
     */
    @Test
    fun `an owner entry with no attestation is refused`() {
        val entry = signedEntry("e1", com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)))

        assertTrue(!repo.commitAll(emptyList(), listOf(entry), emptyList()))

        assertEquals(emptyList(), repo.loadLedger(FRIEND))
    }

    @Test
    fun `an owner entry with a matching attestation lands`() {
        val entry = signedEntry("e1", com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)))

        assertTrue(repo.commitAll(emptyList(), listOf(entry), listOf(pairedAct("a1", "e1"))))

        assertEquals(1, repo.loadLedger(FRIEND).size)
    }

    /**
     * The forged-capability hole. PHONE is READ_ONLY; READ is genuinely within
     * its role, so the role check alone passes. The body says the change needs
     * MUTATE_PERMISSIONS, and that is what decides.
     */
    @Test
    fun `a read-only device cannot authorise a grant by claiming READ`() {
        val entry = signedEntry("e1", com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)))
        val forged = pairedAct("a1", "e1", device = PHONE, capability = DeviceCapability.READ)

        assertTrue(!repo.commitAll(emptyList(), listOf(entry), listOf(forged)))

        assertEquals(emptyList(), repo.loadLedger(FRIEND))
        assertEquals(emptyList(), storedActs())
    }

    @Test
    fun `an act claiming the wrong effect cannot authorise the entry`() {
        val entry = signedEntry("e1", com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)))
        val wrong = pairedAct("a1", "e1", effect = AuthorityEffect.RESTRICTIVE)

        assertTrue(!repo.commitAll(emptyList(), listOf(entry), listOf(wrong)))
        assertEquals(emptyList(), repo.loadLedger(FRIEND))
    }

    @Test
    fun `an act naming another entry cannot authorise this one`() {
        val entry = signedEntry("e1", com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)))

        assertTrue(!repo.commitAll(emptyList(), listOf(entry), listOf(pairedAct("a1", "somewhere-else"))))
        assertEquals(emptyList(), repo.loadLedger(FRIEND))
    }

    /**
     * The inbound door. Anything that did not originate here goes through
     * tryAppendEntry, and an owner-signed entry arriving without its act is
     * exactly what an attacker replaying an old message looks like.
     */
    @Test
    fun `an inbound owner entry with no attestation is refused`() {
        val entry = signedEntry("e1", com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)))

        assertTrue(!repo.tryAppendEntry(entry))

        assertEquals(emptyList(), repo.loadLedger(FRIEND))
    }

    /**
     * An act naming an entry that is not stored vouches for nothing.
     *
     * Left in the heads it would be offered as the parent for the next act,
     * chaining real decisions onto a claim about an entry that does not exist —
     * and after a purge, an act about the deleted relationship would still be
     * sitting there.
     */
    @Test
    fun `an act whose entry is not stored is not a current head`() {
        repo.appendAttestation(attest("a"))

        assertEquals(
            emptyMap(),
            repo.currentAuthority(),
            "an orphan act must not head any scope",
        )
        assertEquals(1, storedActs().size, "but it stays on record")
    }

    // ------------------------------------------- concurrency and restart

    /**
     * Two admissions interleaving. Both read an empty ledger, both decide the
     * act is admissible, and both try to write — which is what happens when a
     * sync and a tap land together, or the same import runs twice. Only one
     * can win, and it has to be the database saying so.
     */
    @Test
    fun `two acts racing for one subject leave exactly one stored`() {
        val first = attest("a")
        val second = attest("b").copy(subject = first.subject)

        repo.appendAttestation(first)
        // The second passed its own preflight against the state it read.
        assertFailsWith<Exception> { repo.appendAttestation(second) }

        assertEquals(listOf(first), storedActs())
    }

    @Test
    fun `the constraint still holds after a restart`() {
        repo.appendAttestation(attest("a"))
        val stored = storedActs()

        driver.close()
        open()

        assertEquals(stored, storedActs())
        assertFailsWith<Exception> {
            repo.appendAttestation(attest("b").copy(subject = LedgerEntryId("entry-a")))
        }
        assertEquals(stored, storedActs(), "a refused race writes nothing")
    }

    /** A duplicate id must fail rather than silently do nothing. */
    @Test
    fun `re-inserting one id with different content is refused loudly`() {
        repo.appendAttestation(attest("a"))

        assertTrue(!repo.tryAppendAttestation(attest("a").copy(subject = LedgerEntryId("entry-z"))))
        assertEquals(1, storedActs().size)
    }

    // ------------------------- what is on disk is not what a device signed

    /**
     * Admission checked the signature and then the row was believed for ever.
     *
     * Anything that can write to this file — a restored backup, a rooted
     * device, a bug in our own code — could put an act on disk that no device
     * ever signed, and every read afterwards would take it as evidence that one
     * of the owner's devices authorised a permission change. So the signature
     * is checked again on every read, against the public key the *manifest*
     * binds to that device, and a row that fails takes the whole estate with
     * it rather than being quietly skipped.
     */
    private fun enrolTablet() {
        askedFor(
            manifestEntry(
                3,
                DeviceManifestBody.DeviceEnrolled(TABLET, "pk-${TABLET.value}", DeviceRole.TRUSTED),
                "m-2",
            ),
        )
    }

    private fun storeGrantWithItsAct() {
        val entry = signedEntry(
            "e1",
            com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)),
        )
        assertTrue(repo.commitAll(emptyList(), listOf(entry), listOf(pairedAct("a1", "e1"))))
        assertEquals(
            setOf(SharingCategory.VIDEOS),
            repo.loadProjection().relationships[FRIEND]?.offeredCategories,
            "the fixture has to release something, or the assertion below proves nothing",
        )
    }

    private fun forceActColumn(column: String, value: String) = driver.execute(
        null,
        "UPDATE sharing_authority_attestation SET $column = '$value' WHERE attestation_id = 'a1'",
        0,
    ).value

    private fun assertReleasesNothingAcrossARestart(what: String) {
        assertEquals(emptyMap(), repo.currentAuthority(), "$what must leave no current authority")
        assertEquals(
            RelationshipStatus.FAIL_CLOSED,
            repo.loadProjection().relationships[FRIEND]?.status,
            "$what must release nothing",
        )

        driver.close()
        open()

        assertEquals(emptyMap(), repo.currentAuthority(), "$what must still leave none after a restart")
        assertEquals(
            RelationshipStatus.FAIL_CLOSED,
            repo.loadProjection().relationships[FRIEND]?.status,
            "$what must still release nothing after a restart",
        )
    }

    @Test
    fun `an act whose signature does not verify fails the estate closed`() {
        storeGrantWithItsAct()

        forceActColumn("signature", "forged")

        assertReleasesNothingAcrossARestart("a signature nobody's key produced")
    }

    /**
     * The signature was made under LAPTOP's key and the row now claims TABLET,
     * which is enrolled and may change permissions. Only the key says no — so
     * only checking the key catches it.
     */
    @Test
    fun `an act moved onto a device that did not sign it fails the estate closed`() {
        enrolTablet()
        storeGrantWithItsAct()

        forceActColumn("device_id", TABLET.value)

        assertReleasesNothingAcrossARestart("an act attributed to a device that did not sign it")
    }

    @Test
    fun `an act from a device the manifest never named fails the estate closed`() {
        storeGrantWithItsAct()

        forceActColumn("device_id", "dddd4444")

        assertReleasesNothingAcrossARestart("a device the manifest never named")
    }

    /**
     * The fixture's signatures cover only the id and the key, so this row still
     * "verifies" — the generation rule is what has to catch it.
     */
    @Test
    fun `an act claiming a generation the manifest never established fails the estate closed`() {
        storeGrantWithItsAct()

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET authority_generation = 9 WHERE attestation_id = 'a1'",
            0,
        ).value

        assertReleasesNothingAcrossARestart("a generation nobody established")
    }

    // ------------------- a settled decision outlives its author's authority

    /**
     * FEAT-062 §11. LAPTOP denies, TABLET grants, concurrently. LAPTOP is
     * revoked afterwards.
     *
     * The denial has to stand, before and after a restart. Ranking the act by
     * the role its device holds *now* dropped it, and the grant underneath came
     * back — a withdrawal of access disappearing because of an unrelated later
     * change.
     */
    @Test
    fun `a denial outlives the revocation of the device that made it, across a restart`() {
        enrolTablet()
        val root = signedEntry("e0", com.cruxcoach.domain.sharing.SharingLedgerBody.PeerCircleAssigned(
            com.cruxcoach.domain.sharing.SharingCircle.FRIENDS,
        ))
        assertTrue(
            repo.commitAll(emptyList(), listOf(root), listOf(pairedAct("act-e0", "e0"))),
            "root refused: " + repo.lastBatchRejection,
        )

        val granted = signedEntry(
            "e-grant",
            com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)),
            seq = 2,
            parent = "e0",
        )
        val denied = signedEntry(
            "e-deny",
            com.cruxcoach.domain.sharing.SharingLedgerBody.RelationshipRevoked,
            seq = 2,
            parent = "e0",
        )
        assertTrue(
            repo.commitAll(
                emptyList(),
                listOf(granted),
                listOf(pairedAct("act-e-grant", "e-grant", device = TABLET).copy(parent = LedgerEntryId("act-e0"))),
            ),
        )
        assertTrue(
            repo.commitAll(
                emptyList(),
                listOf(denied),
                listOf(
                    pairedAct(
                        "act-e-deny",
                        "e-deny",
                        device = LAPTOP,
                        effect = AuthorityEffect.RESTRICTIVE,
                    ).copy(parent = LedgerEntryId("act-e0")),
                ),
            ),
        )
        assertEquals(
            RelationshipStatus.REVOKED,
            repo.loadProjection().relationships[FRIEND]?.status,
            "the denial stands while its author still holds authority",
        )

        // Now revoke the device that made the denial.
        askedFor(manifestEntry(4, DeviceManifestBody.DeviceRevoked(LAPTOP), "m-3"))

        assertEquals(
            RelationshipStatus.REVOKED,
            repo.loadProjection().relationships[FRIEND]?.status,
            "revoking the author does not un-say what it said",
        )

        driver.close()
        open()

        assertEquals(
            RelationshipStatus.REVOKED,
            repo.loadProjection().relationships[FRIEND]?.status,
            "and it still does not after a restart",
        )
    }

    /**
     * FEAT-062 §12.1: a row from before migration 22 grants nothing, visibly.
     *
     * Such an act named a single manifest head, and no frontier can be invented
     * for it — a head names one branch of a merged estate. So the column is
     * NULL, which parses to no context, and the read has to say so rather than
     * throw or quietly carry on.
     */
    @Test
    fun `an act with no bound context grants nothing and does not throw`() {
        val entry = signedEntry(
            "e0",
            com.cruxcoach.domain.sharing.SharingLedgerBody.GrantChanged(setOf(SharingCategory.VIDEOS)),
        )
        assertTrue(repo.commitAll(emptyList(), listOf(entry), listOf(pairedAct("act-e0", "e0"))))
        assertEquals(
            setOf(SharingCategory.VIDEOS),
            repo.loadProjection().relationships[FRIEND]?.offeredCategories,
            "the fixture has to release something first",
        )

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET manifest_context = NULL WHERE attestation_id = 'act-e0'",
            0,
        ).value

        // Reading is safe, and says why rather than granting.
        val state = repo.loadProjection().relationships[FRIEND]
        assertEquals(RelationshipStatus.FAIL_CLOSED, state?.status)
        assertTrue(state?.offeredCategories.isNullOrEmpty(), "nothing is released")
        assertTrue(repo.currentAuthority().isEmpty())

        driver.close()
        open()

        assertEquals(RelationshipStatus.FAIL_CLOSED, repo.loadProjection().relationships[FRIEND]?.status)
    }
}
