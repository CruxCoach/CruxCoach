package com.cruxcoach.android.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardBleScannerRetryPolicyTest {

    @Test
    fun registration_retries_reach_the_cap_instead_of_resetting() {
        assertEquals(1, nextRegistrationRetryAttempt(0, 3))
        assertEquals(2, nextRegistrationRetryAttempt(1, 3))
        assertEquals(3, nextRegistrationRetryAttempt(2, 3))
        assertNull(nextRegistrationRetryAttempt(3, 3))
    }
}
