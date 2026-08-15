package com.cruxcoach.android.competition

import android.content.Context
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CompetitionCleanupStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences("competition_cleanup_store_test", Context.MODE_PRIVATE)
    }
    private val store by lazy { CompetitionCleanupStore(preferences) }

    @Before fun clearBefore() { preferences.edit().clear().commit() }
    @After fun clearAfter() { preferences.edit().clear().commit() }

    @Test fun `partial per-relay outcomes survive a new store instance`() {
        val job = fixture().copy(
            tombstoneOutcomes = mapOf("wss://relay.one" to true, "wss://relay.two" to false),
            deletionOutcomes = mapOf("wss://relay.one" to false, "wss://relay.two" to true),
        )
        store.save(job)

        val restored = CompetitionCleanupStore(preferences).get(job.ownerPubkey, job.compId)!!
        assertEquals(job, restored)
        assertEquals(listOf(job), CompetitionCleanupStore(preferences).list(job.ownerPubkey))
    }

    @Test fun `jobs are owner scoped and removed only explicitly`() {
        val mine = fixture()
        val other = fixture().copy(ownerPubkey = "b".repeat(64), compId = "fedcba9876543210")
        store.save(mine)
        store.save(other)
        assertEquals(listOf(mine), store.list(mine.ownerPubkey))
        assertTrue(store.list("c".repeat(64)).isEmpty())

        store.remove(mine)
        assertNull(store.get(mine.ownerPubkey, mine.compId))
        assertEquals(other, store.get(other.ownerPubkey, other.compId))
    }

    private fun fixture() = CompetitionCleanupJob(
        ownerPubkey = "a".repeat(64),
        compId = "0123456789abcdef",
        title = "Durable cleanup test",
        definitionEventId = "d".repeat(64),
        relays = listOf("wss://relay.one", "wss://relay.two"),
        tombstoneEventJson = """{"id":"${"1".repeat(64)}","pubkey":"${"a".repeat(64)}"}""",
        deletionEventJson = """{"id":"${"2".repeat(64)}","pubkey":"${"a".repeat(64)}"}""",
        updatedAt = 1234,
    )
}
