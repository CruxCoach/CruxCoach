package com.cruxcoach.android.ble

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = Application::class)
class SessionGattServerCommandBacklogTest {
    @Test
    fun `stop discards commands buffered without a session collector`() {
        val server = SessionGattServer(ApplicationProvider.getApplicationContext())

        assertTrue(server.enqueueOrphanedCommandForTest("AA:BB:CC:DD:EE:FF", byteArrayOf(1, 2, 3)))
        server.stop()

        assertEquals(
            "a stopped singleton server must leave no command for its next session",
            0,
            server.discardPendingCommands(),
        )
        assertTrue(server.enqueueOrphanedCommandForTest("11:22:33:44:55:66", byteArrayOf(4)))
        assertEquals(1, server.discardPendingCommands())
    }
}
