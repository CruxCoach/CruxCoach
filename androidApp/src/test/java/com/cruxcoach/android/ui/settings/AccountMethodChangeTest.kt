package com.cruxcoach.android.ui.settings

import org.junit.Assert.*
import org.junit.Test

/** Signer public keys have already been decoded to hex at this comparison boundary. */
class AccountMethodChangeTest {
    private val publicHex = "ab".repeat(32)

    @Test fun `same decoded account keeps its context across hex casing`() {
        assertTrue(sameAccountIdentity(publicHex, publicHex))
        assertTrue(sameAccountIdentity(publicHex, publicHex.uppercase()))
        assertEquals(publicHex, canonicalAccountHex(publicHex.uppercase()))
    }

    @Test fun `different valid identities do not preserve the previous account context`() {
        assertFalse(sameAccountIdentity(publicHex, "cd".repeat(32)))
    }

    @Test fun `malformed or missing identities cannot be mistaken for the same account`() {
        for (invalid in listOf("", "00", "gg".repeat(32), "ab".repeat(33), "npub1invalid", "nsec1invalid")) {
            assertNull(canonicalAccountHex(invalid))
            assertFalse(sameAccountIdentity(invalid, invalid))
            assertFalse(sameAccountIdentity(publicHex, invalid))
        }
    }
}
