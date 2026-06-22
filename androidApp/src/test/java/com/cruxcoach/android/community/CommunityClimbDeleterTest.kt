package com.cruxcoach.android.community

import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbDeleteContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for the early-return refusal Outcomes of [CommunityClimbDeleter.delete]
 * that are reachable through the mockable [BoardRepository] interface alone:
 *  - NotFound   — no local row for the uuid.
 *  - NotOurClimb — row exists but origin != 'cruxcoach' (Kilter rows are read-only).
 *
 * The NotOwner / Done / LocalTombstoneFailed paths depend on [NostrSigner]
 * (a concrete class whose final getPublicKeyHex() can't be mocked on the Java-17
 * test JVM — the inline mock agent can't self-attach, the same constraint that
 * keeps every Quartz/Nostr ingest path device-tested), so they are exercised on
 * device rather than here.
 */
class CommunityClimbDeleterTest {

    private val signer = mockk<NostrSigner>(relaxed = true)
    private val pool = mockk<NostrRelayPool>(relaxed = true)
    private val repo = mockk<BoardRepository>(relaxed = true)
    private val deleter = CommunityClimbDeleter(signer, pool, repo)

    private fun ctx(origin: String) = CommunityClimbDeleteContext(
        nostrEventId = null,
        nostrDTag = null,
        createdByPubkey = "pkOwner",
        kilterStatus = null,
        origin = origin,
    )

    @Test
    fun `NotFound when no local row exists for the uuid`() = runTest {
        every { repo.getCommunityClimbDeleteContext("u") } returns null
        assertEquals(CommunityClimbDeleter.Outcome.NotFound, deleter.delete("u"))
    }

    @Test
    fun `NotOurClimb when the row is not a cruxcoach climb`() = runTest {
        every { repo.getCommunityClimbDeleteContext("u") } returns ctx("kilter")
        assertEquals(CommunityClimbDeleter.Outcome.NotOurClimb, deleter.delete("u"))
    }
}
