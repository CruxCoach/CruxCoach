package com.cruxcoach.android.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostMigrationAttemptsTest {

    @Test
    fun migration_never_retries_a_host_and_stops_after_cap() {
        val attempts = HostMigrationAttempts(maxAttempts = 2)
        attempts.begin(previousHostSessionId = 10)

        assertFalse(attempts.canConsider(10))
        assertTrue(attempts.claim(20))
        assertFalse(attempts.canConsider(20))
        assertTrue(attempts.claim(30))
        assertFalse(attempts.canConsider(40))
        assertFalse(attempts.claim(40))
    }

    @Test
    fun successful_episode_reset_allows_future_hosts_again() {
        val attempts = HostMigrationAttempts(maxAttempts = 1)
        attempts.begin(previousHostSessionId = 10)
        assertTrue(attempts.claim(20))

        attempts.reset()
        attempts.begin(previousHostSessionId = 30)

        assertTrue(attempts.canConsider(20))
    }
}
