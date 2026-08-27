package com.cruxcoach.android.data

import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.domain.relay.CompleteClimb
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BoardProjectionCoordinatorTest {

    @Test
    fun `identified relay write updates local and nearby projection`() = runTest {
        val queue = mockk<SessionQueueManager>(relaxed = true)
        every { queue.state } returns MutableStateFlow(
            SessionQueueState(role = SessionRole.HOST),
        )
        val boardState = mockk<BoardStateManager>(relaxed = true)
        val identifier = mockk<RelayClimbIdentifier>()
        coEvery { identifier.identify(any()) } returns
            RelayClimbIdentifier.Identified("external-climb", 35)
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
        every {
            advertiser.advertiseClimb("external-climb", 35, true, true)
        } returns "started"
        val preferences = mockk<UserPreferences>()
        every { preferences.nearbyClimbSharing } returns flowOf(true)
        val coordinator = BoardProjectionCoordinator(
            queue,
            boardState,
            identifier,
            advertiser,
            preferences,
        )
        val climb = CompleteClimb(
            rawBytes = byteArrayOf(1, 2, 3),
            chunks = listOf(byteArrayOf(1, 2, 3)),
            framesHash = 42L,
            holdCount = 1,
        )

        coordinator.onExternalBoardWrite(climb)

        verify(exactly = 1) { queue.markExternalBoardWrite(null, 0) }
        verify(exactly = 1) { queue.markExternalBoardWrite("external-climb", 35) }
        coVerify(exactly = 1) { boardState.setLastClimb("external-climb", 35, true) }
        verify(exactly = 1) {
            advertiser.advertiseClimb("external-climb", 35, true, true)
        }
    }

    @Test
    fun `nearby sharing off keeps identified external climb local`() = runTest {
        val queue = mockk<SessionQueueManager>(relaxed = true)
        every { queue.state } returns MutableStateFlow(
            SessionQueueState(role = SessionRole.HOST),
        )
        val boardState = mockk<BoardStateManager>(relaxed = true)
        val identifier = mockk<RelayClimbIdentifier>()
        coEvery { identifier.identify(any()) } returns
            RelayClimbIdentifier.Identified("private-climb", 40)
        val advertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
        val preferences = mockk<UserPreferences>()
        every { preferences.nearbyClimbSharing } returns flowOf(false)
        val coordinator = BoardProjectionCoordinator(
            queue,
            boardState,
            identifier,
            advertiser,
            preferences,
        )

        coordinator.onExternalBoardWrite(
            CompleteClimb(byteArrayOf(1), listOf(byteArrayOf(1)), 7L, 1),
        )

        verify(exactly = 1) { queue.markExternalBoardWrite(null, 0) }
        verify(exactly = 0) { queue.markExternalBoardWrite("private-climb", 40) }
        coVerify(exactly = 1) { boardState.setLastClimb("private-climb", 40, true) }
        verify(exactly = 1) {
            advertiser.advertiseClimb("private-climb", 40, false, true)
        }
    }
}
