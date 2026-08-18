package com.cruxcoach.android.fips

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FipsGattPresenceLeaseBookTest {
    @Test
    fun `duplicate channels keep one presence edge until the last channel closes`() {
        val leases = FipsGattPresenceLeaseBook()

        assertTrue(leases.acquire("AA:BB").connect)
        assertFalse(leases.acquire("AA:BB").connect)
        assertTrue(leases.isActive("AA:BB"))

        assertFalse(leases.release("AA:BB").disconnect)
        assertTrue(leases.isActive("AA:BB"))
        assertTrue(leases.release("AA:BB").disconnect)
        assertFalse(leases.isActive("AA:BB"))
    }

    @Test
    fun `addresses are isolated and an unknown close is harmless`() {
        val leases = FipsGattPresenceLeaseBook()

        leases.acquire("AA:BB")
        leases.acquire("CC:DD")

        assertFalse(leases.release("EE:FF").disconnect)
        assertTrue(leases.release("AA:BB").disconnect)
        assertTrue(leases.isActive("CC:DD"))
    }
}
