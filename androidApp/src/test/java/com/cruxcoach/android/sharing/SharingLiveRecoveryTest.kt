package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SharingLiveRecoveryTest {
    private var wall = System.currentTimeMillis()
    private var mono = 1_000L
    private val endpoint = SyntheticMarmotEndpoint(SnapshotEndpointRole.SERVER, listOf("ws://127.0.0.1:9"), true, {wall}, {mono})
    @AfterTest fun close() = endpoint.close()

    @Test fun clock_recovery_withdraws_grants_before_unlocking_and_requires_new_consent() = runBlocking {
        val peer=PeerId("b".repeat(64))
        assertTrue(endpoint.controller.offer(peer,SharingCircle.FRIENDS,setOf(SharingCategory.PRIVATE_NOTES)).isSuccess)
        assertTrue(endpoint.repository.loadProjection().relationships[peer]!!.offeredCategories.isNotEmpty())
        wall -= 600_000;assertFalse(endpoint.clock.healthy())
        assertFalse(endpoint.repository.canUseSnapshots(DeviceCapability.READ))
        wall += 600_000
        assertTrue(endpoint.controller.recoverSharingClock().isSuccess)
        assertTrue(endpoint.clock.healthy())
        val state=endpoint.repository.loadProjection().relationships[peer]!!
        assertTrue(state.offeredCategories.isEmpty());assertTrue(state.consentedCategories.isEmpty())
        endpoint.reopen();assertTrue(endpoint.clock.healthy())
        assertTrue(endpoint.repository.loadProjection().relationships[peer]!!.consentedCategories.isEmpty())
    }

    @Test fun expired_snapshot_quota_is_reusable_without_erasing_original_notes_or_reviving_tombstones() {
        endpoint.note()
        val json=Json{encodeDefaults=true}
        repeat(SharingSnapshotExchange.MAX_RECORDS){n ->
            val id=n.toString(16).padStart(64,'0')
            val offer=SnapshotOffer(id,endpoint.account,endpoint.role,"b".repeat(64),SnapshotEndpointRole.USER,
                endpoint.device,"c".repeat(64),"synthetic-binding",SharingCategory.PRIVATE_NOTES,1,"d".repeat(64),wall-1)
            endpoint.database.snapshotQueries.insertSnapshot(endpoint.account,id,json.encodeToString(offer),"synthetic-note",SnapshotState.REVOKED.name)
        }
        assertTrue(endpoint.exchange.atCapacity())
        assertEquals(SharingSnapshotExchange.MAX_RECORDS,endpoint.exchange.collectExpired())
        assertFalse(endpoint.exchange.atCapacity());assertTrue(endpoint.exchange.views().isEmpty())
        assertNotNull(endpoint.database.sharingQueries.selectSealedItem("synthetic-note").executeAsOneOrNull())
        assertNull(endpoint.exchange.read("0".repeat(64)))
    }
}
