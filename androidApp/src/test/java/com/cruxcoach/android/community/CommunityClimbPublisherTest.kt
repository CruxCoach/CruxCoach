package com.cruxcoach.android.community

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.kilter.KilterClimbPublisher
import com.cruxcoach.android.data.kilter.KilterTokenStore
import com.cruxcoach.android.nostr.CommunityEventSigner
import com.cruxcoach.android.nostr.NostrEventRelaySender
import com.cruxcoach.android.nostr.SignedCommunityEvent
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.community.ClimbEditorState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CommunityClimbPublisherTest {

    private val uuid = "11111111-aaaa-bbbb-cccc-000000000001"
    private val pubkey = "a".repeat(64)
    private val state = ClimbEditorState(
        selectedHolds = mapOf(1 to 42, 2 to 43, 3 to 44),
        boardBrand = "moonboard",
        name = "Moon test",
        description = "state machine",
        setterGradeId = 17,
        angle = 40,
    )

    private val signer = mockk<CommunityEventSigner>()
    private val relays = mockk<NostrEventRelaySender>()
    private val repository = mockk<BoardRepository>(relaxed = true)
    private val kilterPublisher = mockk<KilterClimbPublisher>(relaxed = true)
    private val tokenStore = mockk<KilterTokenStore>(relaxed = true)
    private val preferences = mockk<UserPreferences>(relaxed = true)
    private val event = object : SignedCommunityEvent {
        override val id: String = "event-1"
    }

    private fun publisher(): CommunityClimbPublisher = CommunityClimbPublisher(
        nostrSigner = signer,
        pool = relays,
        boardRepository = repository,
        kilterPublisher = kilterPublisher,
        kilterTokenStore = tokenStore,
        userPreferences = preferences,
    )

    private fun stubSignedEvent() {
        every { signer.getPublicKeyHex() } returns pubkey
        every { repository.reserveNextNostrCreatedAt(uuid, any()) } returns 1_750_000_000L
        coEvery {
            signer.signCommunityEvent(
                createdAt = 1_750_000_000L,
                kind = 30078,
                tags = any(),
                content = any(),
            )
        } returns event
        every { tokenStore.getAccessToken() } returns null
    }

    @Test
    fun `accepted relay marks publication after crash-safe in-flight state`() = runTest {
        stubSignedEvent()
        coEvery { relays.sendCommunityEventWithStats(event) } returns (3 to 2)

        val result = publisher().publish(
            uuid = uuid,
            layoutId = 2,
            boardBrand = BoardBrand.MOONBOARD,
            state = state,
            sizeLabel = "MoonBoard 2016",
        )

        assertEquals("event-1", result.nostrEventId)
        assertFalse(result.nudgeToConnectKilter)
        assertEquals(null, result.kilterOutcome)
        coVerifyOrder {
            repository.markClimbPublishInFlight(uuid)
            relays.sendCommunityEventWithStats(event)
            repository.markClimbPublishedNostr(
                uuid = uuid,
                nostrEventId = "event-1",
                nostrDTag = any(),
                pubkey = pubkey,
                createdAtIso = any(),
            )
        }
        coVerify(exactly = 0) { repository.markClimbPublishFailed(any()) }
        coVerify(exactly = 0) { kilterPublisher.publish(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { kilterPublisher.update(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `zero relay acceptance leaves row in retryable failed state`() = runTest {
        stubSignedEvent()
        coEvery { relays.sendCommunityEventWithStats(event) } returns (3 to 0)

        val error = assertFailsWith<IllegalStateException> {
            publisher().publish(
                uuid = uuid,
                layoutId = 2,
                boardBrand = BoardBrand.MOONBOARD,
                state = state,
                sizeLabel = "MoonBoard 2016",
            )
        }

        assertEquals(
            "No relay accepted the community-climb event (attempted=3)",
            error.message,
        )
        coVerifyOrder {
            repository.markClimbPublishInFlight(uuid)
            relays.sendCommunityEventWithStats(event)
            repository.markClimbPublishFailed(uuid)
        }
        coVerify(exactly = 0) {
            repository.markClimbPublishedNostr(any(), any(), any(), any(), any())
        }
    }
}
