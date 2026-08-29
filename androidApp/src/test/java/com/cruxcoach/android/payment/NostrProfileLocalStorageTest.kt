package com.cruxcoach.android.payment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.db.secure.SecureDatabase
import io.mockk.mockk
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NostrProfileLocalStorageTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var relayPool: NostrRelayPool
    private lateinit var eventBuilder: NostrPublicEventBuilder
    private lateinit var manager: NostrProfileManager

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        relayPool = mockk(relaxed = true)
        eventBuilder = mockk(relaxed = true)
        manager = NostrProfileManager(eventBuilder, relayPool, SecureDatabase(driver))
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `local save persists full profile without signing or publishing`() {
        manager.saveLocalProfile(
            pubkey = "abc123",
            displayName = "Alex",
            lightningAddress = "alex@example.com",
            picture = "https://example.com/picture.jpg",
            about = "Local biography",
            banner = "https://example.com/banner.jpg",
            nip05 = "alex@example.com",
            website = "https://example.com",
        )

        val stored = manager.getProfileFromCache("abc123")!!
        assertEquals("Alex", stored.displayName)
        assertEquals("Local biography", stored.about)
        assertEquals("https://example.com", stored.website)
        assertEquals(
            1L,
            SecureDatabase(driver).nostrProfilesQueries
                .isLocalPrimary("abc123")
                .executeAsOne(),
        )
    }

    @Test
    fun `migration 12 to 13 adds local profile fields`() {
        val file = File.createTempFile("secure-profile-v12", ".db")
        val migrationDriver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        try {
            migrationDriver.execute(
                null,
                """
                CREATE TABLE nostr_profiles (
                    pubkey TEXT NOT NULL PRIMARY KEY,
                    display_name TEXT,
                    lightning_address TEXT,
                    picture_url TEXT,
                    updated_at INTEGER NOT NULL,
                    banner_url TEXT,
                    nip05 TEXT,
                    website TEXT,
                    last_event_created_at INTEGER
                )
                """.trimIndent(),
                0,
            )
            SecureDatabase.Schema.migrate(migrationDriver, 12, 13)

            val columns = mutableSetOf<String>()
            migrationDriver.executeQuery(
                null,
                "PRAGMA table_info(nostr_profiles)",
                { cursor ->
                    while (cursor.next().value) columns += cursor.getString(1)!!
                    app.cash.sqldelight.db.QueryResult.Value(Unit)
                },
                0,
            ).value
            assertTrue("about" in columns)
            assertTrue("local_primary" in columns)
        } finally {
            migrationDriver.close()
            file.delete()
        }
    }
}
