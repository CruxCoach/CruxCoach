package com.cruxcoach.android.community

import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.data.repository.BoardRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityClimbSubscriberConcurrencyTest {

    @Test
    fun `dead-letter drain waits until board writer gate is quiescent`() = runTest {
        val state = MutableStateFlow(BoardSyncState(isSyncing = true))
        val syncManager = mockk<BoardSyncManager>()
        every { syncManager.state } returns state
        val repository = mockk<BoardRepository>(relaxed = true) {
            every { getRetriableCommunityClimbDeadLetters(any(), any()) } returns emptyList()
        }
        val subscriber = CommunityClimbSubscriber(
            pool = mockk<NostrRelayPool>(relaxed = true),
            boardRepository = repository,
            userPreferences = mockk<UserPreferences>(relaxed = true),
            nostrSigner = mockk<NostrSigner>(relaxed = true),
            nostrProfileManager = mockk<NostrProfileManager>(relaxed = true),
            boardSyncManager = syncManager,
        )

        val drain = async { subscriber.retryDeadLetters() }
        runCurrent()
        verify(exactly = 0) { repository.getRetriableCommunityClimbDeadLetters(any(), any()) }

        state.value = BoardSyncState(isSyncing = false)
        drain.await()
        verify(exactly = 1) { repository.getRetriableCommunityClimbDeadLetters(any(), any()) }
    }
}
