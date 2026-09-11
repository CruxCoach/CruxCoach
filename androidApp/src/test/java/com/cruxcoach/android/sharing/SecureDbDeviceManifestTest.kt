package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.data.SecureDatabasePragmas
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.AeadSharingKeyVault
import com.cruxcoach.domain.sharing.AuthorityDeviceId
import com.cruxcoach.domain.sharing.DeviceCapability
import com.cruxcoach.domain.sharing.DeviceManifestBody
import com.cruxcoach.domain.sharing.DeviceManifestEntry
import com.cruxcoach.domain.sharing.DeviceManifestVerifier
import com.cruxcoach.domain.sharing.DeviceRole
import com.cruxcoach.domain.sharing.InMemoryWrappingKeyStore
import com.cruxcoach.domain.sharing.AuthorityAttestation
import com.cruxcoach.domain.sharing.AuthorityEffect
import com.cruxcoach.domain.sharing.LedgerEntryId
import com.cruxcoach.domain.sharing.ManifestContext
import com.cruxcoach.domain.sharing.OwnerPolicySigner
import com.cruxcoach.domain.sharing.SharingLedgerSigner
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
 * FEAT-062 §11: the manifest on disk.
 *
 * The reduced device authority is a *derived* view, exactly like the
 * relationship projection: the entries are the stored authority and the state
 * is recomputed from them. That is what makes it repairable — losing the cache
 * costs nothing, and a cache that disagreed with the entries would be a second
 * opinion about who may change permissions.
 */
class SecureDbDeviceManifestTest {

    private companion object {
        const val OWNER = "npub1owner"
        val LAPTOP = AuthorityDeviceId("aaaa1111")
        val PHONE = AuthorityDeviceId("bbbb2222")
        val TABLET = AuthorityDeviceId("cccc3333")
        val OTHER = AuthorityDeviceId("dddd4444")
    }

    private lateinit var dbFile: java.io.File
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: SecureDatabase
    private lateinit var repo: SecureDbSharingRepository

    private val verifier = DeviceManifestVerifier { entry ->
        entry.signature == "${entry.signerNpub}:${entry.id.value}"
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
            deviceManifestVerifier = verifier,
            // Covers the bound context, as a real signature does: it is part
            // of the canonical bytes, so narrowing a frontier to hide a branch
            // is a forgery rather than a structurally valid earlier context.
            attestationVerifier = { act, publicKey ->
                act.signature == "$publicKey:${act.id.value}:${act.manifestContext.canonical}"
            },
        )
    }

    private class TaggedCrypto(private val identity: String) :
        com.cruxcoach.domain.sharing.LedgerCrypto {
        override fun sign(hash: ByteArray): ByteArray = (identity + ":").encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals((signerNpub + ":").encodeToByteArray() + hash)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-device-manifest-")
        dbFile = tmp.resolve("secure.db").toFile()
        open()
        SecureDatabase.Schema.create(driver)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    private fun entry(
        seq: Long,
        body: DeviceManifestBody,
        parent: String?,
        generation: Long = 1,
        id: String = "m-$seq",
        signer: String = OWNER,
    ) = DeviceManifestEntry(
        id = LedgerEntryId(id),
        manifestSequence = seq,
        authorityGeneration = generation,
        parent = parent?.let { LedgerEntryId(it) },
        signerNpub = signer,
        signature = "$signer:$id",
        body = body,
    )

    private val genesis = entry(1, DeviceManifestBody.DeviceEnrolled(LAPTOP, "pk", DeviceRole.PRIMARY), null)

    /**
     * Writes an ordinary manifest mutation with the act asking for it.
     *
     * Act first, naming the entry's *parent*: a change to the manifest cannot
     * be its own justification, so it is judged against the estate as it stood
     * before it. Only a genesis and a sovereign recovery go in without one.
     */
    private fun askedFor(
        entry: DeviceManifestEntry,
        device: AuthorityDeviceId = LAPTOP,
        /** The act this one was built on. Given explicitly to fork deliberately. */
        follows: LedgerEntryId? = null,
        /**
         * True when this sibling is being put on disk as though it had arrived
         * from another install — a restore, or one day a sync.
         *
         * There is deliberately no *live* door for it. An act binds the
         * frontier standing now, because a signature proves only which frontier
         * its author declared, not that the author had not seen more; a device
         * that really was offline re-signs on reconnect. These rows are written
         * straight in, which is what a root-authenticated restore does.
         */
        offline: Boolean = false,
    ) {
        val required = com.cruxcoach.domain.sharing.AuthorityPairing.requiredFor(entry.body)!!
        val context = if (offline) {
            ManifestContext.of(listOfNotNull(entry.parent))
        } else {
            // A local write binds the whole frontier standing now — after a
            // merge, both branches of it.
            ManifestContext.of(repo.loadDeviceAuthority().frontier)
        }
        val act = AuthorityAttestation(
            id = LedgerEntryId("act-${entry.id.value}"),
            scope = required.scope,
            subject = entry.id,
            device = device,
            // The act it was built on: the last one in this device's own scope,
            // so an enrolment and a later role change chain rather than looking
            // concurrent.
            parent = follows ?: repo.loadAttestations().lastOrNull { it.scope == required.scope }?.id,
            manifestContext = context,
            authorityGeneration = repo.loadDeviceAuthority().authorityGeneration,
            capability = required.capability,
            effect = required.effect,
            signature = "pk:act-${entry.id.value}:" + context.canonical,
        )
        if (offline) {
            // Put on disk as a restore would, not admitted live: the live door
            // requires the current frontier, and this act binds an older one.
            forceManifestEntry(entry)
            forceAttestation(act)
            return
        }
        // Through the batch door, so the act is judged against the estate at
        // the entry's parent rather than against whatever head happens to be
        // standing — which on a fork is the sibling, not the parent.
        check(repo.commitRecovery(listOf(entry), emptyList(), listOf(act))) {
            "the fixture's manifest entry was refused: " + repo.lastBatchRejection
        }
    }

    private fun forceManifestEntry(entry: DeviceManifestEntry) {
        val (kind, json) = com.cruxcoach.domain.sharing.DeviceManifestCodec.encodeBody(entry.body)
        driver.execute(
            null,
            """
            INSERT INTO sharing_device_manifest_entry
              (entry_id, manifest_sequence, authority_generation, parent_entry_id,
               signer_npub, signature, body_kind, body_json, received_at)
            VALUES ('${entry.id.value}', ${entry.manifestSequence}, ${entry.authorityGeneration},
                    ${entry.parent?.let { "'${it.value}'" } ?: "NULL"}, '${entry.signerNpub}',
                    '${entry.signature}', '$kind', '${json.replace("'", "''")}', 0)
            """.trimIndent(),
            0,
        ).value
    }

    private fun forceAttestation(act: AuthorityAttestation) = driver.execute(
        null,
        """
        INSERT INTO sharing_authority_attestation
          (attestation_id, scope, subject_entry_id, device_id, parent_attestation_id,
           manifest_context, authority_generation, capability, effect, signature, received_at)
        VALUES ('${act.id.value}', '${act.scope.value}', '${act.subject.value}', '${act.device.value}',
                ${act.parent?.let { "'${it.value}'" } ?: "NULL"}, '${act.manifestContext.canonical}',
                ${act.authorityGeneration}, '${act.capability.name}', '${act.effect.name}',
                '${act.signature}', 0)
        """.trimIndent(),
        0,
    ).value

    // -------------------------------------------------------- storing

    @Test
    fun `an appended entry is readable back`() {
        repo.appendDeviceManifestEntry(genesis)

        assertEquals(listOf(genesis), repo.loadDeviceManifest())
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(LAPTOP))
    }

    @Test
    fun `the next sequence follows the stored head`() {
        assertEquals(1L, repo.nextManifestSequence())
        repo.appendDeviceManifestEntry(genesis)
        assertEquals(2L, repo.nextManifestSequence())
        assertEquals(LedgerEntryId("m-1"), repo.loadDeviceAuthority().head)
    }

    @Test
    fun `the reduced authority survives closing and reopening the database`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1"))

        driver.close()
        open()

        val state = repo.loadDeviceAuthority()
        assertEquals(DeviceRole.PRIMARY, state.roleOf(LAPTOP))
        assertEquals(DeviceRole.TRUSTED, state.roleOf(PHONE))
        assertTrue(state.can(PHONE, DeviceCapability.MUTATE_PERMISSIONS))
        assertTrue(!state.can(PHONE, DeviceCapability.ADMINISTER_DEVICES))
    }

    @Test
    fun `every body kind survives a restart`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.READ_ONLY), "m-1"))
        askedFor(entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.TRUSTED), "m-2"))
        askedFor(entry(4, DeviceManifestBody.DeviceRevoked(PHONE), "m-3"))
        repo.appendDeviceManifestEntry(
            entry(5, DeviceManifestBody.AuthorityRotated(2, fenceExisting = true), "m-4"),
        )
        val before = repo.loadDeviceManifest()

        driver.close()
        open()

        assertEquals(before, repo.loadDeviceManifest(), "entries must round-trip byte for byte")
        assertEquals(2L, repo.loadDeviceAuthority().authorityGeneration)
    }

    // ------------------------------------------------------ refusing writes

    @Test
    fun `an inadmissible entry is refused and stores nothing`() {
        repo.appendDeviceManifestEntry(genesis)

        val forked = entry(2, DeviceManifestBody.DeviceRevoked(LAPTOP), "m-99")
        assertTrue(!repo.tryAppendDeviceManifestEntry(forked))

        assertEquals(1, repo.loadDeviceManifest().size, "a refused entry must leave no row")
        assertEquals(DeviceRole.PRIMARY, repo.loadDeviceAuthority().roleOf(LAPTOP))
    }

    @Test
    fun `a local append with a broken invariant fails hard rather than storing it`() {
        repo.appendDeviceManifestEntry(genesis)

        assertFailsWith<IllegalStateException> {
            repo.appendDeviceManifestEntry(entry(9, DeviceManifestBody.DeviceRevoked(LAPTOP), "m-1"))
        }
        assertEquals(1, repo.loadDeviceManifest().size)
    }

    @Test
    fun `a byte-identical duplicate is idempotent`() {
        repo.appendDeviceManifestEntry(genesis)
        assertTrue(repo.tryAppendDeviceManifestEntry(genesis))

        assertEquals(1, repo.loadDeviceManifest().size)
    }

    // ------------------------------------------------------------ capability

    @Test
    fun `a device with no manifest holds nothing`() {
        val state = repo.loadDeviceAuthority()

        assertNull(state.roleOf(LAPTOP))
        assertTrue(!state.can(LAPTOP, DeviceCapability.MUTATE_PERMISSIONS))
        assertEquals(0L, state.authorityGeneration)
    }

    @Test
    fun `a fenced device is still listed so the screen can explain it`() {
        repo.appendDeviceManifestEntry(genesis)
        repo.appendDeviceManifestEntry(
            entry(2, DeviceManifestBody.AuthorityRotated(2, fenceExisting = true), "m-1"),
        )

        val record = assertNotNull(repo.loadDeviceAuthority().devices[LAPTOP])
        assertTrue(record.fenced)
        assertNull(repo.loadDeviceAuthority().roleOf(LAPTOP))
    }

    // ------------------------------------------------------------- forks

    /**
     * FEAT-062 §12.1. The manifest forks, and the resolver settles it.
     *
     * Two of the owner's devices go offline and both build on the head they
     * last saw. The root key signs for both, because it is reachable from both.
     * Refusing the second put relay order in charge of who administers the
     * estate; merging them would let a revoked device keep its role. So both
     * are stored and exactly one branch of each *device's own* scope stands.
     */
    private fun forkOnPhone(first: DeviceManifestBody, second: DeviceManifestBody, order: Boolean) {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.READ_ONLY), "m-1"))
        val a = entry(3, first, "m-2", id = "m-3a")
        val b = entry(3, second, "m-2", id = "m-3b")
        if (order) { askedFor(a); askedFor(b, offline = true) } else { askedFor(b); askedFor(a, offline = true) }
    }

    @Test
    fun `a revocation beats a concurrent role change to the same device, either arrival order`() {
        listOf(true, false).forEach { order ->
            setUp()
            forkOnPhone(
                DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.PRIMARY),
                DeviceManifestBody.DeviceRevoked(PHONE),
                order,
            )

            assertNull(repo.loadDeviceAuthority().failClosedReason)
            assertNull(repo.loadDeviceAuthority().roleOf(PHONE), "order $order")
        }
    }

    /**
     * Administering devices is PRIMARY-only, so a TRUSTED device cannot ask
     * for a manifest change at all — the rank comparison never gets to run
     * here, and refusing at the capability check is the stronger answer.
     *
     * Rank between a PRIMARY and a TRUSTED device is settled on the permission
     * ledgers, where both may write; see OwnerPolicyBranchTest.
     */
    @Test
    fun `a trusted device cannot ask for a manifest change at all`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1"))

        val refused = assertFailsWith<IllegalStateException> {
            askedFor(entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.TRUSTED), "m-2"), PHONE)
        }

        assertTrue(refused.message.orEmpty().contains("ADMINISTER_DEVICES"), refused.message.orEmpty())
    }

    @Test
    fun `enrolling one device and revoking another are independent and both stand`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1"))
        askedFor(entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.TRUSTED), "m-2", id = "m-3a"))
        askedFor(entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3b"), offline = true)

        val state = repo.loadDeviceAuthority()
        assertNull(state.failClosedReason)
        assertEquals(DeviceRole.TRUSTED, state.roleOf(TABLET), "the enrolment stands")
        assertNull(state.roleOf(PHONE), "and so does the revocation")
    }

    @Test
    fun `enrolling two devices concurrently keeps both`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1", id = "m-2a"))
        askedFor(
            entry(2, DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.READ_ONLY), "m-1", id = "m-2b"),
            offline = true,
        )

        val state = repo.loadDeviceAuthority()
        assertNull(state.failClosedReason)
        assertEquals(DeviceRole.TRUSTED, state.roleOf(PHONE))
        assertEquals(DeviceRole.READ_ONLY, state.roleOf(TABLET))
    }

    /**
     * A later change builds on the tip of the *authorised* history — the branch
     * that won, not the one that lost.
     *
     * An entry's own ancestors are what its snapshot is folded from, so
     * continuing from the losing side would be building on an estate nobody
     * chose.
     */
    @Test
    fun `the estate can be changed again after a fork`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.READ_ONLY), "m-1"))
        askedFor(entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.PRIMARY), "m-2", id = "m-3a"))
        askedFor(entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3b"), offline = true)
        val tip = assertNotNull(repo.loadDeviceAuthority().head)
        assertEquals(LedgerEntryId("m-3b"), tip, "the revocation is what stands")

        askedFor(
            entry(
                repo.loadDeviceManifest().first { it.id == tip }.manifestSequence + 1,
                DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.TRUSTED),
                tip.value,
                id = "m-after",
            ),
        )

        val state = repo.loadDeviceAuthority()
        assertNull(state.failClosedReason)
        assertEquals(DeviceRole.TRUSTED, state.roleOf(TABLET), "the estate keeps working after a fork")
        assertNull(state.roleOf(PHONE), "and the revocation is still in force")
        assertEquals(
            listOf(LedgerEntryId("m-3a"), LedgerEntryId("m-3b")),
            repo.loadAttestations().first { it.id == LedgerEntryId("act-m-after") }.manifestContext.frontier,
            "an act written after the merge binds both branches of it",
        )
    }

    @Test
    fun `a forked estate reduces the same way after a restart`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.READ_ONLY), "m-1"))
        askedFor(entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.PRIMARY), "m-2", id = "m-3a"))
        askedFor(entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3b"), offline = true)
        val before = repo.loadDeviceAuthority()

        driver.close()
        open()

        assertEquals(before, repo.loadDeviceAuthority())
        assertNull(repo.loadDeviceAuthority().roleOf(PHONE))
    }

    // --------------------------- numbering, and a malformed act DAG on disk

    /**
     * A new entry is numbered from the tip of the *authorised* history.
     *
     * Taken from the highest number on disk instead, a long abandoned branch
     * keeps pushing the numbering forward while the winner stays where it is —
     * so the next entry claims a sequence that does not follow its own parent
     * and is refused, or worse, follows a branch nobody chose.
     */
    @Test
    fun `the next sequence comes from the authorised tip, not the longest branch`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.READ_ONLY), "m-1"))
        // The losing branch is two entries long...
        askedFor(entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.PRIMARY), "m-2", id = "m-3b"))
        askedFor(entry(4, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.TRUSTED), "m-3b", id = "m-4b"))
        // ...and the winning one is a single restrictive step.
        // Concurrent with the branch above, not built on it.
        askedFor(
            entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3a"),
            follows = LedgerEntryId("act-m-2"),
            offline = true,
        )

        assertEquals(LedgerEntryId("m-3a"), repo.loadDeviceAuthority().head)
        assertEquals(4L, repo.nextManifestSequence(), "one past the tip that stands, not past the longest branch")
    }

    /** Writes an attestation row straight in, bypassing admission. */
    private fun forceAct(id: String, subject: String, scope: String, parent: String?) = driver.execute(
        null,
        """
        INSERT INTO sharing_authority_attestation
          (attestation_id, scope, subject_entry_id, device_id, parent_attestation_id,
           manifest_head_entry_id, authority_generation, capability, effect, signature, received_at)
        VALUES ('$id', '$scope', '$subject', '${LAPTOP.value}',
                ${parent?.let { "'$it'" } ?: "NULL"}, 'm-1', 1,
                'ADMINISTER_DEVICES', 'PERMISSIVE', 'pk:$id', 0)
        """.trimIndent(),
        0,
    ).value

    private fun assertManifestClosedAcrossRestart(what: String) {
        assertNotNull(repo.loadDeviceAuthority().failClosedReason, "$what must fail the manifest closed")
        driver.close()
        open()
        assertNotNull(
            repo.loadDeviceAuthority().failClosedReason,
            "$what must still fail the manifest closed after a restart",
        )
    }

    private fun seedOneMutation() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1"))
    }

    @Test
    fun `a manifest mutation whose act is gone fails the manifest closed`() {
        seedOneMutation()

        driver.execute(null, "DELETE FROM sharing_authority_attestation WHERE attestation_id = 'act-m-2'", 0).value

        assertManifestClosedAcrossRestart("a mutation with no act")
    }

    @Test
    fun `a manifest act following one in another scope fails the manifest closed`() {
        seedOneMutation()
        askedFor(entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.TRUSTED), "m-2"))

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET parent_attestation_id = 'act-m-2' " +
                "WHERE attestation_id = 'act-m-3'",
            0,
        ).value

        assertManifestClosedAcrossRestart("a cross-scope act parent")
    }

    @Test
    fun `a cycle among manifest acts fails the manifest closed`() {
        seedOneMutation()
        askedFor(entry(3, DeviceManifestBody.DeviceRoleChanged(PHONE, DeviceRole.PRIMARY), "m-2"))

        driver.execute(
            null,
            "UPDATE sharing_authority_attestation SET parent_attestation_id = 'act-m-3' " +
                "WHERE attestation_id = 'act-m-2'",
            0,
        ).value

        assertManifestClosedAcrossRestart("a cycle")
    }

    /**
     * Stronger than failing closed on read: the database refuses the second
     * act outright, so it never reaches disk.
     */
    @Test
    fun `a second act for one manifest entry cannot even be stored`() {
        seedOneMutation()

        assertFailsWith<Exception> {
            forceAct("act-second", "m-2", com.cruxcoach.domain.sharing.AuthorityScope.ownerDevice(PHONE).value, null)
        }
    }

    // ------------------------------- a context that is not what was signed

    /**
     * A merged estate, and an ordinary act bound to both branches of it.
     *
     * The three tests below tamper with that stored context. Every one of them
     * has to fail the estate closed: the context is inside the signature, so
     * narrowing it to hide a branch, widening it to claim more, or pointing it
     * somewhere that does not exist are all forgeries — and the reader must not
     * be able to fall back on "well, what does the frontier look like now".
     */
    private fun mergedEstateWithABoundAct() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.TRUSTED), "m-1"))
        askedFor(entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.TRUSTED), "m-2", id = "m-3a"))
        askedFor(entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3b"), offline = true)
        askedFor(
            entry(4, DeviceManifestBody.DeviceRoleChanged(TABLET, DeviceRole.PRIMARY), "m-3b", id = "m-4"),
        )
        assertNull(repo.loadDeviceAuthority().failClosedReason, "the fixture starts readable")
    }

    private fun forceContext(actId: String, canonical: String) = driver.execute(
        null,
        "UPDATE sharing_authority_attestation SET manifest_context = '$canonical' " +
            "WHERE attestation_id = '$actId'",
        0,
    ).value

    private fun assertManifestClosedNowAndAfterRestart(what: String) {
        assertNotNull(repo.loadDeviceAuthority().failClosedReason, "$what must fail the manifest closed")
        driver.close()
        open()
        assertNotNull(repo.loadDeviceAuthority().failClosedReason, "$what must still fail it after a restart")
    }

    /** Narrowed: one branch of the merge quietly dropped. */
    @Test
    fun `a context narrowed to hide a branch fails the manifest closed`() {
        mergedEstateWithABoundAct()

        forceContext("act-m-4", ManifestContext.of(listOf(LedgerEntryId("m-3b"))).canonical)

        assertManifestClosedNowAndAfterRestart("a narrowed context")
    }

    /** Widened: an ancestor added, so the frontier is no longer an antichain. */
    @Test
    fun `a context widened with an ancestor fails the manifest closed`() {
        mergedEstateWithABoundAct()

        forceContext(
            "act-m-4",
            ManifestContext.of(
                listOf(LedgerEntryId("m-2"), LedgerEntryId("m-3a"), LedgerEntryId("m-3b")),
            ).canonical,
        )

        assertManifestClosedNowAndAfterRestart("a widened context")
    }

    /** Pointed at an entry this install does not hold. */
    @Test
    fun `a context naming an entry nobody stored fails the manifest closed`() {
        mergedEstateWithABoundAct()

        forceContext("act-m-4", ManifestContext.of(listOf(LedgerEntryId("m-99"))).canonical)

        assertManifestClosedNowAndAfterRestart("a context nobody can place")
    }

    /** And a row with no context at all — a pre-migration act — decides nothing. */
    @Test
    fun `a context that does not parse fails the manifest closed`() {
        mergedEstateWithABoundAct()

        forceContext("act-m-4", "not-a-context")

        assertManifestClosedNowAndAfterRestart("an unreadable context")
    }

    // ------------------- a stale partial frontier is not a licence to write

    /**
     * FEAT-062 §12.1. The security case the review names.
     *
     * The estate forks: the tablet is enrolled on one branch, and on the other
     * the **phone is revoked**. An act the phone signed against the frontier
     * before that fork is perfectly well formed and correctly signed — but its
     * signature proves only which frontier it *declared*, not that its device
     * had not seen the revocation. Admitting it live would let a revoked device
     * keep writing simply by naming an older, partial view of the estate.
     *
     * So a live write binds the frontier standing **now**, and this one is
     * refused: nothing written, in either arrival order, and still nothing
     * after a restart.
     */
    private fun estateWherePhoneWasRevokedOnAnotherBranch(): AuthorityAttestation {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY), "m-1"))
        // What the phone signed while it could still see only m-2.
        val stale = AuthorityAttestation(
            id = LedgerEntryId("act-stale"),
            scope = com.cruxcoach.domain.sharing.AuthorityScope.ownerDevice(OTHER),
            subject = LedgerEntryId("m-late"),
            device = PHONE,
            parent = null,
            manifestContext = ManifestContext.of(listOf(LedgerEntryId("m-2"))),
            authorityGeneration = 1,
            capability = DeviceCapability.ADMINISTER_DEVICES,
            effect = AuthorityEffect.PERMISSIVE,
            signature = "pk2:act-stale:" + ManifestContext.of(listOf(LedgerEntryId("m-2"))).canonical,
        )
        // Then the estate moves on: a tablet on one branch, and on the other
        // the phone loses its authority entirely.
        askedFor(entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.TRUSTED), "m-2", id = "m-3a"))
        askedFor(entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3b"), offline = true)
        return stale
    }

    /**
     * A third sibling on `m-2` — inside the stale closure, so the reducer's
     * own parent-reachability rule has nothing to say about it. The only thing
     * standing between a revoked device and enrolling a device of its choosing
     * is the frontier its act had to bind.
     */
    private fun lateEntry() =
        entry(3, DeviceManifestBody.DeviceEnrolled(OTHER, "pk9", DeviceRole.PRIMARY), "m-2", id = "m-late")

    @Test
    fun `a revoked device cannot write live against the frontier it saw before the revocation`() {
        val stale = estateWherePhoneWasRevokedOnAnotherBranch()
        val storedActs = repo.loadAttestations().size

        assertTrue(
            !repo.commitRecovery(listOf(lateEntry()), emptyList(), listOf(stale)),
            "a stale partial frontier must not be a licence to write, whichever door it arrives at",
        )

        assertEquals(storedActs, repo.loadAttestations().size, "nothing was written")
        assertNull(repo.loadDeviceManifest().firstOrNull { it.id == LedgerEntryId("m-late") })
        assertNull(repo.loadDeviceAuthority().failClosedReason, "and the estate is still readable")

        driver.close()
        open()

        assertEquals(storedActs, repo.loadAttestations().size, "still nothing after a restart")
        assertNull(repo.loadDeviceAuthority().failClosedReason)
    }

    /** The other arrival order: the revocation lands first, then the stale act. */
    @Test
    fun `the same stale act is refused whichever branch arrived first`() {
        repo.appendDeviceManifestEntry(genesis)
        askedFor(entry(2, DeviceManifestBody.DeviceEnrolled(PHONE, "pk2", DeviceRole.PRIMARY), "m-1"))
        askedFor(entry(3, DeviceManifestBody.DeviceRevoked(PHONE), "m-2", id = "m-3b"))
        askedFor(
            entry(3, DeviceManifestBody.DeviceEnrolled(TABLET, "pk3", DeviceRole.TRUSTED), "m-2", id = "m-3a"),
            offline = true,
        )
        val stale = AuthorityAttestation(
            id = LedgerEntryId("act-stale"),
            scope = com.cruxcoach.domain.sharing.AuthorityScope.ownerDevice(OTHER),
            subject = LedgerEntryId("m-late"),
            device = PHONE,
            parent = null,
            manifestContext = ManifestContext.of(listOf(LedgerEntryId("m-2"))),
            authorityGeneration = 1,
            capability = DeviceCapability.ADMINISTER_DEVICES,
            effect = AuthorityEffect.PERMISSIVE,
            signature = "pk2:act-stale:" + ManifestContext.of(listOf(LedgerEntryId("m-2"))).canonical,
        )
        val storedActs = repo.loadAttestations().size

        assertTrue(!repo.commitRecovery(listOf(lateEntry()), emptyList(), listOf(stale)))

        assertEquals(storedActs, repo.loadAttestations().size)
    }
}
