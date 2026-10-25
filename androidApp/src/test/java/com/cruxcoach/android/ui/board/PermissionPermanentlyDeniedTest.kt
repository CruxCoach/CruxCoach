package com.cruxcoach.android.ui.board

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Allow board search" must turn into "Open app settings" once Android stops asking. */
class PermissionPermanentlyDeniedTest {
    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"

    @Test
    fun `a refusal Android would still ask about again is not permanent`() {
        assertFalse(permissionPermanentlyDenied(mapOf(scan to false, connect to false)) { true })
    }

    @Test
    fun `a refusal without rationale is permanent`() {
        assertTrue(permissionPermanentlyDenied(mapOf(scan to false, connect to true)) { false })
    }

    @Test
    fun `only the denied permissions decide`() {
        // CONNECT granted (no rationale either), SCAN denied but still askable.
        assertFalse(permissionPermanentlyDenied(mapOf(scan to false, connect to true)) { it == scan })
    }

    @Test
    fun `a full grant is never blocked`() {
        assertFalse(permissionPermanentlyDenied(mapOf(scan to true, connect to true)) { false })
    }
}
