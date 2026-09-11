package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PrivateApplicationSignerTest {
    @Test fun unattended_root_friendship_refusal_has_no_interactive_fallback() = runBlocking {
        var foreground = 0
        var background = 0
        val signer = PrivateApplicationSigner(
            foreground = Nip01EventSigner { _, _, _, _ -> foreground++; error("must not open foreground signer") },
            unattended = { true },
            provider = Nip01EventSigner { time, kind, tags, content ->
                background++
                assertEquals(Nip01SigningEnvelope.CREATED_AT, time)
                assertEquals(Nip01SigningEnvelope.KIND, kind)
                assertEquals(Nip01SigningEnvelope.tags(SigningDomain.FRIENDSHIP), tags)
                assertEquals("synthetic request digest", content)
                null
            })
        assertNull(signer.sign(Nip01SigningEnvelope.CREATED_AT, Nip01SigningEnvelope.KIND,
            Nip01SigningEnvelope.tags(SigningDomain.FRIENDSHIP), "synthetic request digest"))
        assertEquals(1, background); assertEquals(0, foreground)
    }
    @Test fun provider_exception_never_opens_a_second_channel_and_foreground_remains_explicit() = runBlocking {
        var foreground = 0
        var unattended = true
        val signer = PrivateApplicationSigner(Nip01EventSigner { _, _, _, _ -> foreground++; null }, { unattended },
            Nip01EventSigner { _, _, _, _ -> error("synthetic provider refusal") })
        assertFailsWith<IllegalStateException> { signer.sign(1, 1223, emptyList(), "synthetic") }
        assertEquals(0, foreground)
        unattended = false
        assertNull(signer.sign(1, 1223, emptyList(), "synthetic"))
        assertEquals(1, foreground)
    }
}
