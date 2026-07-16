package com.cruxcoach.android.payment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NostrProfileManagerLightningCacheTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: SecureDatabase
    private lateinit var manager: NostrProfileManager
    private var relayRequests = 0

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        database = SecureDatabase(driver)
        relayRequests = 0
        manager = NostrProfileManager(
            database = database,
            profileEventReader = ProfileEventReader {
                relayRequests++
                emptyFlow()
            },
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `stale lightning address takes authenticated profile refresh path`() = runTest {
        val pubkey = "a".repeat(64)
        cacheProfile(
            pubkey = pubkey,
            address = "old@example.test",
            updatedAt = System.currentTimeMillis() / 1000 -
                NostrProfileManager.PROFILE_CACHE_TTL_SECONDS - 1,
        )

        // Relay failure retains the authenticated stale row as a fallback, but
        // the subscribe verifies that payment lookup did attempt rotation.
        assertEquals("old@example.test", manager.getLightningAddress(pubkey))
        assertEquals(1, relayRequests)
    }

    @Test
    fun `fresh lightning address does not spend a relay request`() = runTest {
        val pubkey = "b".repeat(64)
        cacheProfile(
            pubkey = pubkey,
            address = "fresh@example.test",
            updatedAt = System.currentTimeMillis() / 1000,
        )

        assertEquals("fresh@example.test", manager.getLightningAddress(pubkey))
        assertEquals(0, relayRequests)
    }

    private fun cacheProfile(pubkey: String, address: String, updatedAt: Long) {
        database.nostrProfilesQueries.upsert(
            pubkey = pubkey,
            display_name = "Test",
            lightning_address = address,
            picture_url = null,
            updated_at = updatedAt,
            banner_url = null,
            nip05 = null,
            website = null,
            last_event_created_at = updatedAt,
        )
    }
}
