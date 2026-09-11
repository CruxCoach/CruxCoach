package com.cruxcoach.android.sharing

import com.cruxcoach.data.repository.PersonalBoardRepositoryImpl
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.domain.model.UserProfile
import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID
import kotlin.test.*

/** Deterministic protocol adversaries. Separate process tests additionally run
 * the actual MDK transport; these fixtures never supply production authority. */
class ContinuousSharingExchangeTest {
    private var time = System.currentTimeMillis()
    private val participants = mutableListOf<Party>()
    private val wireLog = mutableListOf<ContinuousWire>()
    private inner class Party(role: SnapshotEndpointRole = SnapshotEndpointRole.USER) : AutoCloseable {
        val local = SyntheticMarmotEndpoint(role, listOf("ws://127.0.0.1:9"), wall = { time }, elapsed = { time })
        val personal = PersonalBoardRepositoryImpl(local.database)
        val users = UserRepositoryImpl(local.database)
        val controller = SharingController(local.repository, local.signer, local.policySigner, local.account,
            authorityDevice = AuthorityDeviceId(local.device), authorityDevicePublicKey = local.device,
            attestationSigner = local.attestationSigner, manifestSigner = local.manifestSigner, nowEpochMillis = { time })
        val incoming = mutableListOf<Pair<MarmotSnapshotSession, String>>()
        val queued = linkedSetOf<Pair<MarmotSnapshotSession, String>>()
        var online = true
        var binding = "synthetic-fence"
        var retired = false
        val port = object : MarmotSnapshotPort {
            override val nativeAvailable = true
            override val durableIdempotentHandoff = true
            override fun bindingRetired(binding: String) = retired
            override fun <T> withSession(peer: String, block: (MarmotSnapshotSession) -> T): T? {
                if (retired) return null
                val other = participants.firstOrNull { it.local.account == peer } ?: return null
                val a = MarmotSnapshotLeaf(local.account, local.device); val b = MarmotSnapshotLeaf(peer, other.local.device)
                return block(MarmotSnapshotSession(a, b, binding, setOf(a,b)))
            }
            override fun handoff(session: MarmotSnapshotSession, kind: Int, tags: List<List<String>>, content: String) { queued += session to content }
            override fun flush(kind: Int, tags: List<List<String>>, authorize: (MarmotSnapshotSession, String, () -> Unit) -> Unit) {
                if (!online) return
                for ((session, raw) in queued.toList()) authorize(session, raw) {
                    val other = participants.first { it.local.account == session.peer.account }
                    other.incoming += session.copy(local = session.peer, peer = session.local) to raw
                    wireLog += ContinuousCodec.json.decodeFromString<ContinuousWire>(raw)
                }
            }
            override fun drain(kind: Int, tags: List<List<String>>, consume: (MarmotSnapshotSession, String) -> Unit) {
                // This fixture queue contains only friendship-v2 envelopes;
                // honor the exact filter just as the native port does.
                if (!online || kind != ContinuousSharingExchange.KIND || tags != ContinuousSharingExchange.TAGS) return
                for (entry in incoming.toList().reversed()) {
                    consume(entry.first, entry.second); consume(entry.first, entry.second)
                    incoming.remove(entry)
                }
            }
        }
        val exchange = ContinuousSharingExchange(local.database, local.repository, local.account, role, port,
            ContinuousSourceAdapter(local.database), local.exchange::pinnedRole, { time }, local.crypto(SigningDomain.FRIENDSHIP))
        init { participants += this }
        fun note(id: String, text: String) = personal.saveClimbNote(id, text)
        fun profile(name: String) {
            val old = users.getActiveProfile()
            val p = (old ?: UserProfile(name = name, age = 30, weightKg = 70.0, heightCm = 180.0, maxBoulderGrade = "6C")).copy(name = name)
            if (old == null) users.insertProfile(p) else users.updateProfile(p)
        }
        override fun close() = local.close()
    }
    @AfterTest fun close() = participants.forEach { it.close() }
    private fun scope(vararg categories: SharingCategory) = ContinuousScope(categories.toSet(), "2026-01-01")
    private fun tick(rounds: Int = 4) = repeat(rounds) { participants.forEach { it.exchange.synchronize() } }
    private fun permission(a: Party, b: Party, categories: Set<SharingCategory>) = runBlocking {
        assertTrue(a.local.exchange.pinPeer(b.local.account, b.local.role))
        assertTrue(b.local.exchange.pinPeer(a.local.account, a.local.role))
        for (c in categories) assertTrue(a.controller.setPeerRule(PeerId(b.local.account), c, AccessEffect.ALLOW).isSuccess)
        assertTrue(a.controller.offer(PeerId(b.local.account), SharingCircle.FRIENDS, categories).isSuccess)
    }
    private fun offer(a: Party, b: Party, scope: ContinuousScope): String {
        permission(a,b,scope.categories)
        return assertNotNull(a.exchange.offer(b.local.account, scope))
    }
    private fun accept(a: Party, b: Party, scope: ContinuousScope, reverse: ContinuousScope = scope()): String {
        val id = offer(a,b,scope); tick(1)
        permission(b,a,reverse.categories)
        assertTrue(b.exchange.accept(id, reverse), "accept: views=" + b.exchange.views().map { it.status } + "; persisted=" + b.local.database.continuousSharingQueries.grants(b.local.account).executeAsList().map { it.body.length } + "; ownerReady=" + b.local.repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) + "; policy=" + b.local.repository.loadProjection().relationships.values.map { it.status } + "; wireActions=" + wireLog.map { it.action }); tick(8)
        return id
    }
    private fun rows(b: Party, id: String) = b.exchange.views().single { it.offer.id == id }.records
    private fun reverse(b: Party, id: String) = b.exchange.views().single { it.outgoing && it.offer.friendship == id }.offer.id
    private fun persistedContains(b: Party, text: String) = b.local.database.continuousSharingQueries.grants(b.local.account).executeAsList().any { text in it.body }

    @Test fun friendship_once_then_three_real_repositories_and_independent_bidirectional_scopes() {
        val a=Party(); val b=Party(); val c=Party()
        a.note("n", "owner-note"); a.profile("owner-profile"); b.profile("friend-profile")
        val ab=accept(a,b,scope(SharingCategory.PRIVATE_NOTES),scope(SharingCategory.PROFILE_AND_GOALS))
        val ac=accept(a,c,scope(SharingCategory.PROFILE_AND_GOALS))
        assertEquals("owner-note",rows(b,ab).single().fields["note"])
        assertEquals("friend-profile",rows(a,reverse(b,ab)).single().fields["name"])
        assertEquals("owner-profile",rows(c,ac).single().fields["name"])
        a.note("n", "changed"); b.profile("changed-b"); tick(8)
        assertEquals("changed",rows(b,ab).single().fields["note"])
        assertEquals("changed-b",rows(a,reverse(b,ab)).single().fields["name"])
        assertTrue(b.personal.getClimbNotesForBackup().isEmpty()); assertNull(c.users.getActiveProfile())
    }
    @Test fun no_friendship_from_old_category_consent_or_an_unsigned_or_v1_request() {
        val a=Party();val b=Party();a.note("n","secret")
        val id=offer(a,b,scope(SharingCategory.PRIVATE_NOTES)); tick()
        assertEquals("INVITED", b.exchange.views().single().status)
        assertTrue(rows(b,id).isEmpty()); assertTrue(wireLog.none { it.action == "PAGE" })
        val raw=ContinuousCodec.json.encodeToString(wireLog.first())
        assertNull(ContinuousCodec.decode(raw.replace("cc.friendship.sync.v1","cc.continuous.sync.v1")))
        assertFalse(b.exchange.accept(id, scope(SharingCategory.HEALTH_INFORMATION)))
    }
    @Test fun owner_expands_without_recipient_acceptance_but_recipient_cannot_widen_owner_selection() {
        val a=Party();val b=Party();a.note("n","note");a.profile("profile")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        val old=wireLog.last { it.action=="PAGE" && it.offer.owner==a.local.account }
        val forged=old.copy(offer=old.offer.copy(scope=scope(SharingCategory.PRIVATE_NOTES,SharingCategory.PROFILE_AND_GOALS)))
        a.port.withSession(b.local.account) { b.incoming+=it.copy(local=it.peer,peer=it.local) to ContinuousCodec.json.encodeToString(forged) }
        tick();assertEquals(1,rows(b,id).size)
        assertEquals(id,offer(a,b,scope(SharingCategory.PRIVATE_NOTES,SharingCategory.PROFILE_AND_GOALS)))
        tick(8);assertEquals(2,rows(b,id).size);assertTrue(b.exchange.views().none { it.status=="INVITED" })
        assertEquals(1, wireLog.filter { it.action=="ACCEPT" }.map { it.counterOffer }.distinct().size)
    }
    @Test fun narrowing_deletes_actual_payload_and_staging_without_destroying_other_categories() {
        val a=Party();val b=Party();a.note("n","erase-note-marker");a.profile("keep-profile-marker")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES,SharingCategory.PROFILE_AND_GOALS))
        assertTrue(persistedContains(b,"erase-note-marker"))
        assertEquals(id,offer(a,b,scope(SharingCategory.PROFILE_AND_GOALS)));tick(8)
        assertFalse(persistedContains(b,"erase-note-marker"));assertTrue(persistedContains(b,"keep-profile-marker"))
        assertEquals("erase-note-marker",a.personal.getClimbNote("n"))
    }
    @Test fun either_side_end_deletes_both_directions_and_cannot_touch_c_or_owner_originals() {
        for (recipientEnds in listOf(false,true)) {
            val a=Party();val b=Party();val c=Party();a.note("n","a-original");b.note("n","b-original")
            val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES),scope(SharingCategory.PRIVATE_NOTES))
            val ac=accept(a,c,scope(SharingCategory.PRIVATE_NOTES)); val old=wireLog.last { it.action=="PAGE" && it.offer.id==id }
            val initiator=if(recipientEnds)b else a
            assertTrue(initiator.exchange.end(id));assertTrue(initiator.exchange.views().filter { it.offer.friendship==id }.all { it.records.isEmpty() })
            tick(8)
            assertFalse(persistedContains(a,"b-original"));assertFalse(persistedContains(b,"a-original"))
            assertEquals("a-original",rows(c,ac).single().fields["note"])
            assertEquals("a-original",a.personal.getClimbNote("n"));assertEquals("b-original",b.personal.getClimbNote("n"))
            assertTrue(initiator.exchange.views().filter { it.offer.friendship==id }.all { it.cleanupConfirmed })
            a.port.withSession(b.local.account) { b.incoming+=it.copy(local=it.peer,peer=it.local) to ContinuousCodec.json.encodeToString(old) }
            tick();assertFalse(persistedContains(b,"a-original"))
            a.note("n","after-ended-friendship")
            assertTrue(a.exchange.views().filter { it.offer.friendship==id }.none { it.pending })
            tick();assertFalse(persistedContains(b,"after-ended-friendship"))
        }
    }
    @Test fun offline_queue_revocation_then_reconnect_removes_old_and_never_publishes_waiting_change() {
        val a=Party();val b=Party();a.note("n","old");val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        a.online=false;b.online=false;a.note("n","do-not-send-marker");a.exchange.synchronize()
        assertTrue(a.exchange.end(id));assertTrue(persistedContains(b,"old"))
        a.online=true;b.online=true;wireLog.clear();tick(8)
        assertFalse(persistedContains(b,"old"));assertTrue(wireLog.none { "do-not-send-marker" in ContinuousCodec.json.encodeToString(it) })
    }
    @Test fun offline_coalesced_updates_and_tombstone_converge_under_duplicate_reverse_order() {
        val a=Party();val b=Party();a.note("a","v1");a.note("b","remove")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES));b.online=false
        repeat(10){a.note("a","v${it+2}")};a.note("b","");tick(2);b.online=true;tick(8)
        assertEquals(listOf("note:a"),rows(b,id).map { it.id });assertEquals("v11",rows(b,id).single().fields["note"])
        assertTrue(wireLog.any { it.action=="PAGE" && !it.full && it.changes.any { c->c.record==null } })
    }
    @Test fun new_generation_requires_new_friendship_old_accept_page_and_end_cannot_revive_or_kill_it() {
        val a=Party();val b=Party();a.note("n","note")
        val old=accept(a,b,scope(SharingCategory.PRIVATE_NOTES));a.exchange.end(old);tick(8)
        val replay=wireLog.toList();val fresh=offer(a,b,scope(SharingCategory.PRIVATE_NOTES));tick(4)
        assertNotEquals(old,fresh);assertTrue(rows(b,fresh).isEmpty())
        assertTrue(b.exchange.accept(fresh));tick(8)
        replay.filter { it.offer.owner==a.local.account }.forEach { w->a.port.withSession(b.local.account){ b.incoming+=it.copy(local=it.peer,peer=it.local) to ContinuousCodec.json.encodeToString(w) } }
        tick(8);assertEquals(1,rows(b,fresh).size);assertTrue(rows(b,old).isEmpty())
    }
    @Test fun authenticated_epoch_change_closes_and_physically_clears_replica_and_source_generation_stops_export() {
        val a=Party();val b=Party();a.note("n","epoch-marker");val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        b.binding="changed-epoch";assertTrue(rows(b,id).isEmpty());assertFalse(persistedContains(b,"epoch-marker"))
        b.binding="synthetic-fence";tick();assertTrue(rows(b,id).isEmpty())
    }
    @Test fun expired_replica_is_removed_then_resumes_with_full_current_state_without_consent_dialog() {
        val a=Party();val b=Party();a.note("n","lease-marker");val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        time+=8*ContinuousCodec.DAY;assertTrue(rows(b,id).isEmpty());assertFalse(persistedContains(b,"lease-marker"))
        tick(10);assertEquals(1, rows(b,id).size, "states=" + b.exchange.views().map { it.status to it.error } + "; actions=" + wireLog.takeLast(20).map { Triple(it.action,it.sequence,it.full) })
        assertEquals("lease-marker",rows(b,id).single().fields["note"])
        assertTrue(b.exchange.views().none { it.status=="INVITED" })
    }
    @Test fun explicit_server_friendship_has_no_special_or_implicit_reverse_data_rights() {
        val a=Party(SnapshotEndpointRole.SERVER);val b=Party();a.note("n","server-data");b.note("n","private-user-data")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES));assertEquals(1,rows(b,id).size)
        assertTrue(rows(a,reverse(b,id)).isEmpty());assertFalse(persistedContains(a,"private-user-data"))
    }
    @Test fun clock_recovery_ends_even_apparently_stale_state_without_payload_revival() {
        val a=Party();val b=Party();a.note("n","clock-marker");val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        b.exchange.endAll();assertFalse(persistedContains(b,"clock-marker"));tick();assertTrue(rows(b,id).isEmpty())
    }
    @Test fun recipient_role_is_selected_on_acceptance_not_inferred_from_a_message() {
        val a=Party(SnapshotEndpointRole.SERVER); val b=Party(); a.note("n","synthetic")
        val id=offer(a,b,scope(SharingCategory.PRIVATE_NOTES))
        b.local.driver.execute(null, "DELETE FROM sharing_snapshot_peer", 0)
        tick(2)
        assertEquals("INVITED", b.exchange.views().single().status)
        assertFalse(b.exchange.accept(id))
        assertTrue(b.local.exchange.pinPeer(a.local.account,SnapshotEndpointRole.SERVER))
        permission(b,a,emptySet()); assertTrue(b.exchange.accept(id));tick(8)
        assertEquals(1, rows(b,id).size)
    }
    @Test fun revoked_recipient_device_stops_pending_publication_and_remote_reads() {
        val a=Party(); val b=Party();a.note("n","initial")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        a.online=false;a.note("n","withheld");a.exchange.synchronize()
        runBlocking { assertTrue(a.controller.revokeDevice(PeerId(b.local.account),DeviceId(b.local.device)).isSuccess) }
        wireLog.clear();a.online=true;tick()
        assertTrue(rows(b,id).isEmpty())
        assertTrue(wireLog.none { it.changes.any { c -> c.record?.fields?.get("note")=="withheld" } })
    }
    @Test fun authenticated_wrong_owner_unknown_version_and_missing_grant_do_not_create_data() {
        val a=Party();val b=Party();val c=Party();a.note("n","initial")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        val good=wireLog.last { it.action=="PAGE" && it.offer.id==id }
        val before=rows(b,id)
        c.port.withSession(b.local.account) { session ->
            b.incoming += session.copy(local=session.peer,peer=session.local) to ContinuousCodec.json.encodeToString(good)
            b.incoming += session.copy(local=session.peer,peer=session.local) to ContinuousCodec.json.encodeToString(
                good.copy(action="OFFER", offer=good.offer.copy(owner=c.local.account,ownerDevice=c.local.device)))
        }
        a.port.withSession(b.local.account) { session ->
            val incoming=session.copy(local=session.peer,peer=session.local)
            b.incoming += incoming to ContinuousCodec.json.encodeToString(good.copy(version=999))
            b.incoming += incoming to ContinuousCodec.json.encodeToString(good.copy(offer=good.offer.copy(id=UUID.randomUUID().toString())))
        }
        tick();assertEquals(before,rows(b,id));assertEquals(2,b.exchange.views().size)
    }
    @Test fun paged_initial_state_commits_only_when_complete_and_missing_base_resyncs() {
        val a=Party();val b=Party(); repeat(40){a.note("n$it","synthetic $it")}
        val id=offer(a,b,scope(SharingCategory.PRIVATE_NOTES));tick(1);permission(b,a,emptySet());assertTrue(b.exchange.accept(id))
        b.exchange.synchronize();a.exchange.synchronize();b.exchange.synchronize()
        assertTrue(rows(b,id).isEmpty(),"two pages cannot expose a partial baseline")
        tick(6);assertEquals(40,rows(b,id).size)
        val full=wireLog.last { it.action=="PAGE" && it.offer.id==id }
        a.port.withSession(b.local.account) { session ->
            b.incoming += session.copy(local=session.peer,peer=session.local) to ContinuousCodec.json.encodeToString(
                full.copy(sequence=full.sequence+2, base=full.sequence+1, full=false))
        }
        wireLog.clear();tick(6)
        assertTrue(wireLog.any{it.action=="RESYNC"});assertTrue(wireLog.any{it.action=="PAGE" && it.full})
        assertEquals(40,rows(b,id).size)
    }

    @Test fun authenticated_end_before_request_leaves_a_tombstone_and_blocks_delayed_invitation() {
        val a=Party();val b=Party();b.online=false
        val id=offer(a,b,scope(SharingCategory.PRIVATE_NOTES));a.exchange.synchronize();a.exchange.end(id);a.exchange.synchronize()
        b.online=true;b.exchange.synchronize();tick(6)
        assertEquals("ENDED",b.exchange.views().single().status);assertFalse(b.exchange.accept(id))
    }
    @Test fun supported_backup_recovery_clears_payload_and_blocks_old_generations() {
        val a=Party();val b=Party();a.note("n","backup-foreign-marker");val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        b.local.database.continuousSharingQueries.withdrawForRecovery(b.local.account)
        assertFalse(persistedContains(b,"backup-foreign-marker"));assertFalse(b.exchange.accept(id));assertFalse(b.exchange.requestResync(id))
        tick(8);assertTrue(b.exchange.views().isEmpty());assertFalse(persistedContains(b,"backup-foreign-marker"))
        assertEquals("backup-foreign-marker",a.personal.getClimbNote("n"))
    }

    @Test fun recipient_data_before_acceptance_transports_the_same_two_authentic_friendship_certificates() {
        val a=Party();val b=Party();b.note("b","early-reverse-marker")
        val id=offer(a,b,scope());tick(1);permission(b,a,setOf(SharingCategory.PRIVATE_NOTES))
        assertTrue(b.exchange.accept(id,scope(SharingCategory.PRIVATE_NOTES)))
        b.exchange.synchronize()
        // Drop standalone ACCEPT, as an out-of-order/lost relay message. The
        // signed response carried by PAGE answers ONLY our persisted request.
        a.incoming.removeAll { ContinuousCodec.json.decodeFromString<ContinuousWire>(it.second).action=="ACCEPT" }
        a.exchange.synchronize();tick(6)
        assertEquals("early-reverse-marker",rows(a,reverse(b,id)).single().fields["note"])
    }

    @Test fun acceptance_cannot_overwrite_closed_direction_ids_or_exceed_generation_quota() {
        val a=Party();val b=Party()
        val id=offer(a,b,scope());tick(1);permission(b,a,emptySet());assertTrue(b.exchange.accept(id))
        b.exchange.synchronize()
        val response=wireLog.last { it.action=="ACCEPT" }.counterOffer!!
        val aq=a.local.database.continuousSharingQueries
        aq.putGrant(a.local.account,response.id,b.local.account,b.local.account,Long.MAX_VALUE,"")
        a.exchange.synchronize()
        assertEquals("",aq.grantById(a.local.account,response.id).executeAsOne().body)
        assertEquals("OFFERED",a.exchange.views().single().status)
        assertTrue(a.exchange.views().all { it.records.isEmpty() })
        // Independently exercise acceptance at the bounded retained-generation
        // ceiling. An extra reverse direction cannot bypass that storage limit.
        val c=Party();val d=Party();val pending=offer(c,d,scope());tick(1);permission(d,c,emptySet())
        val dq=d.local.database.continuousSharingQueries
        d.local.database.transaction {
            repeat(ContinuousCodec.MAX_GRANTS-1) {
                dq.putGrant(d.local.account,UUID.randomUUID().toString(),c.local.account,c.local.account,Long.MAX_VALUE,"")
            }
        }
        assertFalse(d.exchange.accept(pending))
        assertEquals(ContinuousCodec.MAX_GRANTS,dq.grants(d.local.account).executeAsList().size)
    }

    @Test fun late_selection_failure_rolls_back_policy_changes_and_cannot_reenable_queued_exports() {
        val a=Party();val b=Party();a.note("n","permitted-original")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        runBlocking { assertTrue(a.controller.setPeerRule(PeerId(b.local.account),SharingCategory.PRIVATE_NOTES,AccessEffect.DENY).isSuccess) }
        tick();assertTrue(rows(b,id).isEmpty())
        val before=a.local.repository.loadPolicy()
        val failed=a.exchange.selectionTransaction {
            runBlocking { assertTrue(a.controller.setPeerRule(PeerId(b.local.account),SharingCategory.PRIVATE_NOTES,AccessEffect.ALLOW).isSuccess) }
            SharingWriteResult.Failed(SharingWriteError.SIGNER_UNAVAILABLE)
        }
        assertIs<SharingWriteResult.Failed>(failed)
        assertEquals(before,a.local.repository.loadPolicy())
        wireLog.clear();a.note("n","must-remain-private");tick()
        assertTrue(rows(b,id).isEmpty())
        assertTrue(wireLog.none { w -> w.changes.any { it.record?.fields?.get("note")=="must-remain-private" } })
    }

    @Test fun owner_policy_can_restore_only_categories_still_in_the_owner_signed_selection() {
        val a=Party();val b=Party();a.note("n","owner-policy-resume")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        runBlocking { a.controller.setPeerRule(PeerId(b.local.account),SharingCategory.PRIVATE_NOTES,AccessEffect.DENY) };tick()
        assertTrue(rows(b,id).isEmpty())
        runBlocking { a.controller.setPeerRule(PeerId(b.local.account),SharingCategory.PRIVATE_NOTES,AccessEffect.ALLOW) };tick()
        assertEquals("owner-policy-resume",rows(b,id).single().fields["note"])
        assertEquals(id,a.exchange.offer(b.local.account,scope()))
        tick();assertTrue(rows(b,id).isEmpty())
        runBlocking { a.controller.setPeerRule(PeerId(b.local.account),SharingCategory.PRIVATE_NOTES,AccessEffect.ALLOW) };tick()
        assertTrue(rows(b,id).isEmpty(),"a rule cannot expand a deliberately emptied owner selection")
    }

    @Test fun clock_repair_keeps_signed_friendship_end_deliverable_after_native_access_unlocks() {
        val a=Party();val b=Party();a.note("n","clock-recovery-marker")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        val healthy=time
        time-=600_000;assertFalse(a.local.clock.healthy());time=healthy
        assertTrue(runBlocking { a.local.controller.recoverSharingClock() }.isSuccess)
        assertTrue(a.local.clock.healthy())
        assertFalse(persistedContains(a,"clock-recovery-marker"))
        assertEquals("ENDED",a.exchange.views().first { it.offer.id==id }.status)
        tick(8)
        assertEquals("ENDED",b.exchange.views().first { it.offer.id==id }.status)
        assertFalse(persistedContains(b,"clock-recovery-marker"))
        assertTrue(a.exchange.views().first { it.offer.id==id }.cleanupConfirmed)
        assertEquals("clock-recovery-marker",a.personal.getClimbNote("n"))
    }

    @Test fun definite_native_source_retirement_deletes_received_payload_even_without_a_live_session() {
        val a=Party();val b=Party();a.note("n","retired-native-source-marker")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        b.retired=true
        assertEquals("ENDED",b.exchange.views().first { it.offer.id==id }.status)
        assertFalse(persistedContains(b,"retired-native-source-marker"))
        assertFalse(b.exchange.requestResync(id))
        b.retired=false;tick()
        assertFalse(persistedContains(b,"retired-native-source-marker"))
        assertEquals("retired-native-source-marker",a.personal.getClimbNote("n"))
    }

    @Test fun retained_object_exception_survives_category_downgrade_but_never_widens_signed_selection() {
        val a=Party();val b=Party();a.note("keep","allowed-object-marker");a.note("gone","withdrawn-object-marker")
        val id=accept(a,b,scope(SharingCategory.PRIVATE_NOTES))
        runBlocking {
            assertTrue(a.controller.setObjectRule(PeerId(b.local.account),ObjectId("note:keep"),SharingCategory.PRIVATE_NOTES,AccessEffect.ALLOW).isSuccess)
            assertTrue(a.controller.setPeerRule(PeerId(b.local.account),SharingCategory.PRIVATE_NOTES,AccessEffect.DENY).isSuccess)
        }
        tick();assertEquals(listOf("note:keep"),rows(b,id).map { it.id })
        assertFalse(persistedContains(b,"withdrawn-object-marker"))
        assertEquals(id,a.exchange.offer(b.local.account,scope()))
        tick();assertTrue(rows(b,id).isEmpty());assertFalse(persistedContains(b,"allowed-object-marker"))
        assertEquals("allowed-object-marker",a.personal.getClimbNote("keep"))
    }

}
