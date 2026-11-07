package com.cruxcoach.android.communityclient

import com.cruxcoach.communityclient.CommunityEvent
import com.cruxcoach.communityclient.CommunityEventTemplates
import com.cruxcoach.communityclient.ThreadRef
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmTestSignerTest {
    private val signer = JvmTestSigner()

    /** NIP-01 id of a known event, computed independently (serialisation check). */
    @Test
    fun eventId_matchesNip01Serialisation() {
        val id = eventId(
            pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            createdAt = 1_700_000_000,
            kind = 1,
            tags = listOf(listOf("e", "x", "", "reply")),
            content = "line\n\"quoted\" \\ ü",
        )
        val expected = com.cruxcoach.communityclient.sha256Hex(
            ("[0,\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",1700000000,1," +
                "[[\"e\",\"x\",\"\",\"reply\"]],\"line\\n\\\"quoted\\\" \\\\ ü\"]").toByteArray(),
        )
        assertEquals(expected, id)
    }

    @Test
    fun signedEventsVerify_andTamperingFails() = runBlocking {
        val event = signer.sign(
            CommunityEventTemplates.streamMessage("9b353519-f4fe-4757-aef4-bec6cc0ae54c", "Grüße 🧗", 1_760_000_000, ThreadRef("ab".repeat(32), "cd".repeat(32))),
        )
        assertEquals(signer.pubkey, event.pubkey)
        assertTrue(JvmTestVerifier.verify(event))
        assertTrue(JvmTestVerifier.verify(CommunityEvent.fromJson(event.toJson())!!))
        assertFalse(JvmTestVerifier.verify(event.copy(content = "changed")))
        assertFalse(JvmTestVerifier.verify(event.copy(sig = "00".repeat(64))))
    }
}
