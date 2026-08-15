package com.cruxcoach.android.fips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FipsRuntimeOwnersTest {
    @Test fun `acquiring one owner twice is idempotent`() {
        val owners = FipsRuntimeOwners()
        assertTrue(owners.acquire("board").becameActive)

        val duplicate = owners.acquire("board")

        assertFalse(duplicate.changed)
        assertEquals(1, duplicate.count)
    }

    @Test fun `only the last distinct owner makes runtime idle`() {
        val owners = FipsRuntimeOwners()
        owners.acquire("board")
        owners.acquire("session")

        assertFalse(owners.release("board").becameIdle)
        assertTrue(owners.isActive())
        assertTrue(owners.release("session").becameIdle)
        assertFalse(owners.isActive())
    }

    @Test fun `unknown release cannot steal another owner lease`() {
        val owners = FipsRuntimeOwners()
        owners.acquire("board")

        val release = owners.release("session")

        assertFalse(release.changed)
        assertEquals(1, release.count)
        assertTrue(owners.isActive())
    }
}
