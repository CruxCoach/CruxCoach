package com.cruxcoach.android.community

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunityPublishDrainGateTest {

    @Test
    fun `overlapping drain is rejected instead of replaying the same batch`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            CommunityPublishDrainGate.tryRun {
                entered.complete(Unit)
                release.await()
                "first"
            }
        }
        entered.await()

        val second = CommunityPublishDrainGate.tryRun { "second" }
        assertNull(second)

        release.complete(Unit)
        assertEquals("first", first.await())
    }
}
