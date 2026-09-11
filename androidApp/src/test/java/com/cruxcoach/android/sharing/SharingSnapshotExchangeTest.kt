package com.cruxcoach.android.sharing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/** Protocol/persistence tests with a synthetic authenticated port; these do not execute MLS or BIP-340. */
class SharingSnapshotExchangeTest {
    private val owner = "11".repeat(32)
    private val recipient = "22".repeat(32)
    private val ownerLeaf = "33".repeat(32)
    private val recipientLeaf = "44".repeat(32)
    private var now = 1_000_000L
    private val parties = mutableListOf<Party>()

    private class TaggedCrypto(private val identity: String) : LedgerCrypto {
        override fun hash(canonical: ByteArray) = MessageDigest.getInstance("SHA-256").digest(canonical)
        override fun sign(hash: ByteArray) = identity.encodeToByteArray() + hash
        override fun verify(signature: ByteArray, hash: ByteArray, signerNpub: String) =
            signature.contentEquals(signerNpub.encodeToByteArray() + hash)
    }

    private inner class Port(val me: String, val leaf: String, val peer: String, val peerLeaf: String) : MarmotSnapshotPort {
        var binding = "synthetic-group-incarnation:epoch-1"
        var available = true
        var extraMember = false
        var failHandoff = false
        val incoming = mutableListOf<Pair<MarmotSnapshotSession, String>>()
        val sent = mutableListOf<String>()
        lateinit var other: Port
        fun session() = MarmotSnapshotSession(MarmotSnapshotLeaf(me, leaf), MarmotSnapshotLeaf(peer, peerLeaf), binding,
            setOf(MarmotSnapshotLeaf(me, leaf), MarmotSnapshotLeaf(peer, peerLeaf)) +
                if (extraMember) setOf(MarmotSnapshotLeaf("55".repeat(32), "66".repeat(32))) else emptySet())
        @Synchronized override fun <T> withSession(peer: String, block: (MarmotSnapshotSession) -> T): T? =
            if (available && peer == this.peer) block(session()) else null
        override fun handoff(session: MarmotSnapshotSession, kind: Int, tags: List<List<String>>, content: String) {
            assertEquals(SharingSnapshotExchange.KIND, kind)
            assertEquals(SharingSnapshotExchange.TAGS, tags)
            sent += content
            if (failHandoff) throw IllegalStateException("synthetic ambiguous transport failure")
            other.incoming += other.session() to content
        }
        @Synchronized override fun drain(kind: Int, tags: List<List<String>>, consume: (MarmotSnapshotSession, String) -> Unit) {
            val events = incoming.toList(); incoming.clear()
            events.forEach { (context, wire) -> consume(context, wire) }
        }
    }

    private inner class Party(val identity: String, val role: SnapshotEndpointRole, val port: Port) {
        val dir = Files.createTempDirectory("synthetic-snapshot-").toFile()
        val file = dir.resolve("test.db")
        val vault = AeadSharingKeyVault(InMemoryWrappingKeyStore())
        val crypto = TaggedCrypto(identity)
        var activeIdentity = identity
        var localPublicKey = TestDeviceAuthority.publicKeyOf()
        lateinit var driver: JdbcSqliteDriver
        lateinit var db: SecureDatabase
        lateinit var repo: SecureDbSharingRepository
        lateinit var controller: SharingController
        lateinit var exchange: SharingSnapshotExchange
        init { open(); SecureDatabase.Schema.create(driver); TestDeviceAuthority.enrol(repo, crypto, identity); parties += this }
        fun open() {
            driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}?foreign_keys=on")
            db = SecureDatabase(driver)
            val signer = AsyncSharingLedgerSigner(crypto.asAsync())
            val policySigner = AsyncOwnerPolicySigner(crypto.asAsync())
            repo = SecureDbSharingRepository(db, vault, signer.verifier(), policySigner.verifier(), identity,
                deviceManifestVerifier = DeviceManifestSigner(crypto).verifier(),
                attestationVerifier = TestDeviceAuthority.attestationSigner().verifier(),
                currentOwner = { activeIdentity }, nowEpochMillis = { now },
                localDeviceIdentity = { DeviceIdentity(TestDeviceAuthority.DEVICE, localPublicKey) })
            controller = SharingController(repo, signer, policySigner, identity,
                authorityDevice = TestDeviceAuthority.DEVICE, authorityDevicePublicKey = TestDeviceAuthority.publicKeyOf(),
                attestationSigner = TestDeviceAuthority.attestationSigner(),
                manifestSigner = AsyncDeviceManifestSigner(crypto.asAsync()), peerSimulator = { TaggedCrypto(it.value) },
                nowEpochMillis = { now })
            exchange = SharingSnapshotExchange(db, repo, identity, role, port, { now })
        }
        fun reopen() { driver.close(); open() }
        suspend fun permit(peer: String = recipient) {
            assertTrue(controller.setPeerRule(PeerId(peer), SharingCategory.PRIVATE_NOTES, AccessEffect.ALLOW).isSuccess)
            assertTrue(controller.offer(PeerId(peer), SharingCircle.FRIENDS, setOf(SharingCategory.PRIVATE_NOTES)).isSuccess)
            assertTrue(controller.simulateAccept(PeerId(peer), setOf(SharingCategory.PRIVATE_NOTES), DeviceId(recipientLeaf)))
        }
        fun note(id: String = "owner-local-id", text: String = "synthetic private note") {
            val handle = SharingKeyHandles.ownerObject(ObjectId(id))
            val key = repo.readWrappedKey(handle) ?: repo.createDataKey(handle)
            repo.storeSealedItem(id, SharingCategory.PRIVATE_NOTES, handle, key, text.encodeToByteArray())
        }
    }

    private fun setup(server: Boolean = false): Pair<Party, Party> {
        val a = Port(owner, ownerLeaf, recipient, recipientLeaf)
        val b = Port(recipient, recipientLeaf, owner, ownerLeaf)
        a.other = b; b.other = a
        val left = Party(owner, if (server) SnapshotEndpointRole.SERVER else SnapshotEndpointRole.USER, a)
        val right = Party(recipient, SnapshotEndpointRole.USER, b)
        assertTrue(left.exchange.pinPeer(recipient, SnapshotEndpointRole.USER))
        assertTrue(right.exchange.pinPeer(owner, left.role))
        return left to right
    }

    @AfterTest fun cleanup() { parties.forEach { it.driver.close(); it.dir.deleteRecursively() } }

    private suspend fun prepare(a: Party): String {
        a.permit(); a.note()
        return assertNotNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 60_000))
    }
    private fun deliver(a: Party, b: Party, id: String) {
        a.exchange.synchronize(); b.exchange.synchronize()
        assertNull(b.exchange.read(id), "opening/listing an offer is not consent")
        assertTrue(b.exchange.accept(id))
        b.exchange.synchronize(); a.exchange.synchronize(); b.exchange.synchronize(); a.exchange.synchronize()
    }

    @Test fun user_to_user_requires_two_consents_and_persists_receipt_and_content() = runTest {
        val (a,b) = setup(); val id = prepare(a)
        deliver(a,b,id)
        assertEquals("synthetic private note", b.exchange.read(id))
        assertEquals(SnapshotState.DELIVERED, a.exchange.views().single().state)
        assertFalse(a.port.sent.any { "owner-local-id" in it || "FRIENDS" in it })
        val sent = a.port.sent.size + b.port.sent.size
        a.reopen(); b.reopen(); a.exchange.synchronize(); b.exchange.synchronize()
        assertEquals("synthetic private note", b.exchange.read(id))
        assertEquals(sent, a.port.sent.size + b.port.sent.size)
    }

    @Test fun server_is_separately_pinned_and_has_no_user_policy_authority() = runTest {
        val (a,b) = setup(server = true); val id = prepare(a)
        assertTrue(b.exchange.pinPeer(owner, SnapshotEndpointRole.USER))
        a.exchange.synchronize(); b.exchange.synchronize()
        assertTrue(b.exchange.views().isEmpty(), "role mismatch cannot fall back to a user or developer key")
        assertTrue(b.exchange.pinPeer(owner, SnapshotEndpointRole.SERVER))
        b.port.incoming += b.port.session() to a.port.sent.single()
        b.exchange.synchronize(); assertTrue(b.exchange.accept(id))
        b.exchange.synchronize(); a.exchange.synchronize(); b.exchange.synchronize()
        assertEquals("synthetic private note", b.exchange.read(id))
        assertTrue(b.repo.loadProjection().relationships.isEmpty(), "server receipt grants no user data")
        assertNull(b.exchange.offer(owner, "snapshot:$id", 1, now + 10_000), "received copies cannot be forwarded")
        assertTrue(a.exchange.revoke(id)); a.exchange.synchronize(); b.exchange.synchronize()
        assertNull(b.exchange.read(id))
    }

    @Test fun membership_alone_wrong_identity_device_and_epoch_never_release_data() = runTest {
        val (a,b) = setup(); a.note()
        assertNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 10_000))
        a.permit()
        assertNull(a.exchange.offer(recipient, "owner-local-id", 2, now + 10_000))
        a.port.extraMember = true
        assertNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 10_000))
        a.port.extraMember = false
        val id = assertNotNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 10_000))
        a.exchange.synchronize()
        val original = b.port.incoming.removeAt(0)
        b.port.incoming += original.first.copy(peer = MarmotSnapshotLeaf("77".repeat(32), ownerLeaf)) to original.second
        b.exchange.synchronize(); assertTrue(b.exchange.views().isEmpty())
        b.port.incoming += original.first.copy(local = MarmotSnapshotLeaf(recipient, "88".repeat(32))) to original.second
        b.exchange.synchronize(); assertTrue(b.exchange.views().isEmpty())
        b.port.incoming += original
        b.exchange.synchronize(); assertEquals(id, b.exchange.views().single().id)
    }

    @Test fun reordered_revocation_tombstones_and_replays_survive_reopen() = runTest {
        val (a,b) = setup(); val id = prepare(a)
        a.exchange.synchronize(); val offer = b.port.incoming.removeAt(0)
        assertTrue(a.exchange.revoke(id)); a.exchange.synchronize(); b.exchange.synchronize()
        b.reopen(); b.port.incoming += offer; b.port.incoming += offer
        b.exchange.synchronize()
        assertEquals(SnapshotState.REVOKED, b.exchange.views().single().state)
        assertFalse(b.exchange.accept(id)); assertNull(b.exchange.read(id))
    }

    @Test fun current_policy_is_checked_after_consent_and_before_actual_handoff() = runTest {
        val (a,b) = setup(); val id = prepare(a)
        a.exchange.synchronize(); b.exchange.synchronize(); assertTrue(b.exchange.accept(id)); b.exchange.synchronize()
        assertTrue(a.controller.revoke(PeerId(recipient)).isSuccess)
        a.exchange.synchronize(); b.exchange.synchronize()
        assertNull(b.exchange.read(id))
        assertFalse(a.port.sent.any { "synthetic private note" in it })
        assertEquals(SnapshotState.REVOKED, a.exchange.views().single().state)
    }

    @Test fun expiry_clock_rollback_identity_switch_and_group_rotation_close_reads() = runTest {
        val (a,b) = setup(); val id = prepare(a); deliver(a,b,id)
        b.activeIdentity = "99".repeat(32); assertNull(b.exchange.read(id))
        assertFailsWith<IllegalStateException> { b.repo.collectBackupPayload() }
        assertEquals(SharingBackupWriteOutcome.Failed(SharingBackupError.WRONG_IDENTITY), b.controller.exportBackup(b.controller.newRecoveryCode()))
        b.activeIdentity = recipient
        b.port.binding = "replacement-group:epoch-2"
        assertNull(b.exchange.read(id)); b.port.binding = a.port.binding
        assertNull(b.exchange.read(id), "a replaced session cannot reactivate by switching back")
        a.reopen(); now -= 1
        assertTrue(a.exchange.views().isEmpty()); now += 2; a.reopen()
        assertTrue(a.exchange.views().isEmpty(), "detected rollback is sticky")
    }

    @Test fun expiry_and_recovery_lock_are_enforced_on_cached_payloads() = runTest {
        val (a,b) = setup(); val id = prepare(a); deliver(a,b,id)
        b.repo.setAdministrativeWritesLocked(true, "synthetic recovery", 1)
        assertNull(b.exchange.read(id))
        b.repo.setAdministrativeWritesLocked(false, null, null)
        now += 60_000; assertNull(b.exchange.read(id))
        assertEquals(SnapshotState.EXPIRED, b.exchange.views().single().state)
    }

    @Test fun ambiguous_handoff_is_not_retried_after_restart() = runTest {
        val (a,b) = setup(); val id = prepare(a)
        a.port.failHandoff = true; a.exchange.synchronize(); assertEquals(1, a.port.sent.size)
        a.reopen(); a.port.failHandoff = false; a.exchange.synchronize()
        assertEquals(1, a.port.sent.size); assertTrue(a.exchange.views().single().attempted)
        assertTrue(b.exchange.views().isEmpty()); assertNull(b.exchange.read(id))
    }

    @Test fun unknown_formats_and_modified_payloads_fail_without_poisoning_valid_offer() = runTest {
        val (a,b) = setup(); val id = prepare(a)
        a.exchange.synchronize(); val original = b.port.incoming.removeAt(0)
        for (wire in listOf(original.second.replace("\"version\":1", "\"version\":999"),
            original.second.replace("\"version\":1", "\"version\":999,\"version\":1"),
            original.second.dropLast(1) + ",\"grantEverything\":true}",
            original.second.replace(ownerLeaf, "99".repeat(32)))) {
            b.port.incoming += original.first to wire
        }
        b.exchange.synchronize(); assertTrue(b.exchange.views().isEmpty())
        b.port.incoming += original; b.exchange.synchronize(); assertTrue(b.exchange.accept(id))
        b.exchange.synchronize(); a.exchange.synchronize()
        val content = b.port.incoming.removeAt(0)
        b.port.incoming += content.first to content.second.replace("synthetic private note", "tampered private note")
        b.exchange.synchronize(); assertNull(b.exchange.read(id))
        b.port.incoming += content; b.port.incoming += content
        b.exchange.synchronize(); assertEquals("synthetic private note", b.exchange.read(id))
    }

    @Test fun sealed_metadata_substitution_and_legacy_rows_never_reach_a_peer() = runTest {
        val (a,_) = setup(); a.permit(); a.note()
        a.driver.execute(null, "UPDATE sharing_sealed_item SET item_id='forged-local-id'", 0)
        assertNull(a.exchange.offer(recipient, "forged-local-id", 1, now + 10_000))
        a.driver.execute(null, "UPDATE sharing_sealed_item SET item_id='owner-local-id', aad_version=1", 0)
        assertNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 10_000))
    }

    @Test fun rebuilding_a_repository_for_another_account_cannot_claim_legacy_keys() = runTest {
        val (a,_) = setup(); a.permit(); a.note()
        val handle = SharingKeyHandles.ownerObject(ObjectId("owner-local-id"))
        val key = assertNotNull(a.repo.readWrappedKey(handle))
        val legacy = a.vault.seal(key, "synthetic legacy note".encodeToByteArray(), handle.aad())
        a.db.sharingQueries.insertSealedItem("owner-local-id", SharingCategory.PRIVATE_NOTES.name,
            handle.scope.name, handle.id, 1, legacy.bytes, 0, 1)
        assertEquals("synthetic legacy note", a.repo.readOwnerSealedItem("owner-local-id", key)?.decodeToString())
        val otherCrypto = TaggedCrypto(recipient)
        val other = SecureDbSharingRepository(a.db, a.vault,
            AsyncSharingLedgerSigner(otherCrypto.asAsync()).verifier(),
            AsyncOwnerPolicySigner(otherCrypto.asAsync()).verifier(), recipient,
            deviceManifestVerifier = DeviceManifestSigner(otherCrypto).verifier(),
            attestationVerifier = TestDeviceAuthority.attestationSigner().verifier())
        assertNull(other.readOwnerSealedItem("owner-local-id", key))
        assertFailsWith<IllegalStateException> { other.collectBackupPayload() }
    }

    @Test fun mutable_circle_cache_cannot_widen_a_signed_policy() = runTest {
        val (a,_) = setup(); a.note()
        a.controller.setBaseline(SharingCircle.FRIENDS, SharingCategory.PRIVATE_NOTES, true)
        a.controller.offer(PeerId(recipient), SharingCircle.ACQUAINTANCES, setOf(SharingCategory.PRIVATE_NOTES))
        a.controller.simulateAccept(PeerId(recipient), setOf(SharingCategory.PRIVATE_NOTES), DeviceId(recipientLeaf))
        a.driver.execute(null, "UPDATE sharing_relationship SET circle='FRIENDS'", 0)
        assertNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 10_000))
    }

    @Test fun narrowing_and_extending_expiry_has_real_consent_and_read_effects() = runTest {
        val (a,_) = setup(); a.permit()
        assertTrue(a.controller.setExpiry(PeerId(recipient), now + 10_000).isSuccess)
        assertTrue(a.controller.peerDetail(PeerId(recipient))!!.categories.first { it.category == SharingCategory.PRIVATE_NOTES }.effectiveDecision.isAllowed)
        val oldAcceptance = a.repo.loadLedger(PeerId(recipient)).first { it.body is SharingLedgerBody.RecipientAccepted }
        assertTrue(a.controller.setExpiry(PeerId(recipient), now + 20_000).isSuccess)
        assertFalse(a.controller.peerDetail(PeerId(recipient))!!.categories.first { it.category == SharingCategory.PRIVATE_NOTES }.effectiveDecision.isAllowed)
        a.controller.ingestPeerSignedEntry(oldAcceptance)
        assertFalse(a.controller.peerDetail(PeerId(recipient))!!.categories.first { it.category == SharingCategory.PRIVATE_NOTES }.effectiveDecision.isAllowed)
        assertTrue(a.controller.simulateAccept(PeerId(recipient), setOf(SharingCategory.PRIVATE_NOTES), DeviceId(recipientLeaf)))
        now += 20_000
        assertEquals(DecisionSource.RELATIONSHIP_EXPIRED, a.controller.peerDetail(PeerId(recipient))!!.categories.first { it.category == SharingCategory.PRIVATE_NOTES }.effectiveDecision.source)
    }

    @Test fun a_snapshot_cannot_outlive_a_known_owner_permission_limit() = runTest {
        val (a,b) = setup(); a.permit(); a.note()
        assertTrue(a.controller.setExpiry(PeerId(recipient), now + 10_000).isSuccess)
        assertNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 60_000))
        val id = assertNotNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 10_000))
        deliver(a,b,id)
        assertTrue(a.controller.setExpiry(PeerId(recipient), now + 5_000).isSuccess)
        a.exchange.synchronize(); b.exchange.synchronize()
        assertEquals(SnapshotState.REVOKED, b.exchange.views().single().state)
        assertNull(b.exchange.read(id))
    }

    @Test fun a_device_without_current_authority_cannot_offer_or_read_received_snapshots() = runTest {
        val (a,b) = setup(); val id = prepare(a); deliver(a,b,id)
        b.localPublicKey = "another-device-key"
        assertNull(b.exchange.read(id), "an enrolled ID does not authorize a different device key")
        b.localPublicKey = TestDeviceAuthority.publicKeyOf()
        assertEquals("synthetic private note", b.exchange.read(id))
        val other = AuthorityDeviceId("second-primary")
        assertTrue(b.controller.enrolDevice(other, "second-device-key", DeviceRole.PRIMARY).isSuccess)
        assertTrue(b.controller.revokeOwnDevice(TestDeviceAuthority.DEVICE).isSuccess)
        assertNull(b.exchange.read(id))
        assertTrue(b.exchange.views().isEmpty())
    }

    @Test fun the_product_facade_cannot_send_while_the_native_gate_is_closed() = runTest {
        val (a,_) = setup(); a.permit()
        assertEquals(SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE),
            a.controller.shareNoteSnapshot(PeerId(recipient), "synthetic note", SnapshotEndpointRole.USER))
        assertTrue(a.port.sent.isEmpty())
        assertEquals(SharingWriteResult.Failed(SharingWriteError.NATIVE_UNAVAILABLE), a.controller.synchronizeSnapshots())
    }

    @Test fun blocked_production_port_has_no_publication_or_receive_side_effect() = runTest {
        val (a,_) = setup(); a.permit(); a.note()
        val blocked = SharingSnapshotExchange(a.db, a.repo, owner, SnapshotEndpointRole.USER, BlockedMarmotSnapshotPort(), { now })
        assertNull(blocked.offer(recipient, "owner-local-id", 1, now + 10_000))
        blocked.synchronize(); assertTrue(a.port.sent.isEmpty())
    }

    @Test fun two_instances_cannot_handoff_the_same_action_twice() = runTest {
        val (a,_) = setup(); prepare(a)
        val second = SharingSnapshotExchange(a.db, a.repo, owner, a.role, a.port, { now })
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(5) { attempt ->
                if (attempt > 0) assertNotNull(a.exchange.offer(recipient, "owner-local-id", 1, now + 60_000))
                val barrier = if (attempt == 0) start else java.util.concurrent.CountDownLatch(1)
                val futures = listOf(a.exchange, second).map { exchange ->
                    workers.submit { barrier.await(); exchange.synchronize() }
                }
                barrier.countDown()
                futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                assertEquals(attempt + 1, a.port.sent.size)
            }
            a.reopen(); a.exchange.synchronize()
            assertEquals(5, a.port.sent.size)
        } finally { workers.shutdownNow() }
    }

    @Test fun already_received_content_cannot_be_replayed_after_revocation() = runTest {
        val (a,b) = setup(); val id = prepare(a); deliver(a,b,id)
        val content = a.port.sent.single { "\"action\":\"CONTENT\"" in it }
        assertTrue(a.exchange.revoke(id)); a.exchange.synchronize(); b.exchange.synchronize()
        b.reopen(); b.port.incoming += b.port.session() to content; b.exchange.synchronize()
        assertEquals(SnapshotState.REVOKED, b.exchange.views().single().state)
        assertNull(b.exchange.read(id))
    }

    @Test fun a_signed_device_generation_change_invalidates_received_consent() = runTest {
        val (a,b) = setup(); val id = prepare(a); deliver(a,b,id)
        assertTrue(b.controller.offer(PeerId(owner), SharingCircle.FRIENDS, setOf(SharingCategory.PRIVATE_NOTES)).isSuccess)
        val code = b.controller.newRecoveryCode()
        assertTrue(b.controller.advanceDeviceGenerations(code, code).isSuccess)
        b.reopen()
        assertEquals(SnapshotState.INVALIDATED, b.exchange.views().single().state)
        assertNull(b.exchange.read(id))
    }
}
