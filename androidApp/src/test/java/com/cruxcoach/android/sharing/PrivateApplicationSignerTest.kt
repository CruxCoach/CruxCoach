package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class PrivateApplicationSignerTest {
    private val account = "a".repeat(64)
    @Test fun unattended_root_friendship_refusal_has_no_interactive_fallback() = runBlocking {
        var foreground = 0
        var background = 0
        val signer = PrivateApplicationSigner(
            foreground = Nip01EventSigner { _, _, _, _ -> foreground++; error("must not open foreground signer") },
            unattended = { true },
            account = account, currentAccount = { account }, authorised = { true },
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
            Nip01EventSigner { _, _, _, _ -> error("synthetic provider refusal") }, account, { account }, { true })
        assertFailsWith<IllegalStateException> { signer.sign(1, 1223, emptyList(), "synthetic") }
        assertEquals(0, foreground)
        unattended = false
        assertNull(signer.sign(1, 1223, emptyList(), "synthetic"))
        assertEquals(1, foreground)
    }

    @Test fun wrong_account_or_revoked_authority_never_calls_either_signer() = runBlocking {
        for (background in listOf(false, true)) for (wrongAccount in listOf(false, true)) {
            val forbidden = Nip01EventSigner { _, _, _, _ -> fail("unauthorized signer call") }
            val signer = PrivateApplicationSigner(forbidden, { background }, forbidden, account,
                { if (wrongAccount) "b".repeat(64) else account }, { wrongAccount })
            assertFailsWith<IllegalStateException> { signer.sign(1, 1221, emptyList(), "synthetic") }
        }
    }

    @Test fun account_or_authority_change_during_signing_discards_the_result() = runBlocking {
        for (background in listOf(false, true)) for (changeAccount in listOf(false, true)) {
            var current = account
            var allowed = true
            var calls = 0
            val changed = Nip01EventSigner { time, kind, tags, content ->
                calls++
                if (changeAccount) current = "b".repeat(64) else allowed = false
                Nip01SignedEvent("0".repeat(64), account, time, kind, tags, content, "0".repeat(128))
            }
            val signer = PrivateApplicationSigner(changed, { background }, changed, account, { current }, { allowed })
            assertFailsWith<IllegalStateException> { signer.sign(1, 1221, emptyList(), "synthetic") }
            assertEquals(1, calls)
        }
    }

    @Test fun changed_fields_are_rejected_on_both_routes_without_retry() = runBlocking {
        val original = Nip01SignedEvent("0".repeat(64), account, 1, 1221, listOf(listOf("l", "synthetic")), "synthetic", "0".repeat(128))
        for (background in listOf(false, true)) for (changed in listOf(original.copy(pubKey = "b".repeat(64)),
            original.copy(createdAt = 2), original.copy(kind = 22242), original.copy(tags = emptyList()), original.copy(content = "changed"))) {
            var calls = 0
            val source = Nip01EventSigner { _, _, _, _ -> calls++; changed }
            val signer = PrivateApplicationSigner(source, { background }, source, account, { account }, { true })
            assertFailsWith<IllegalStateException> { signer.sign(original.createdAt, original.kind, original.tags, original.content) }
            assertEquals(1, calls)
        }
    }

    @Test fun native_signed_event_survives_shared_callback_encoding_exactly() = runBlocking {
        val identity = Json.parseToJsonElement(MarmotTestNative.createIdentity()).jsonObject
        val handle = identity.getValue("handle").jsonPrimitive.long
        val public = identity.getValue("account").jsonPrimitive.content
        try {
            val source = Nip01EventSigner { time, kind, tags, content ->
                val request = buildJsonObject {
                    put("pubkey", public); put("created_at", time); put("kind", kind)
                    put("tags", Json.encodeToJsonElement(tags)); put("content", content)
                }
                val event = Json.decodeFromString<BindingEvent>(MarmotTestNative.signEvent(handle, false, request.toString()))
                Nip01SignedEvent(event.id, event.pubkey, event.created_at, event.kind, event.tags, event.content, event.sig)
            }
            val signer = PrivateApplicationSigner(source, { true }, source, public, { public }, { true })
            val tags = listOf(listOf("l", "synthetic \"tag\"\nβ"))
            val content = "synthetic callback \"quotes\" \\ newline\n日本語"
            val signed = assertNotNull(signer.sign(123, 1221, tags, content))
            val decoded = Json.decodeFromString<BindingEvent>(Json.encodeToString(signed.toNativeEvent()))
            assertEquals(signed.toNativeEvent(), decoded)
            val hex = java.util.HexFormat.of()
            assertTrue(MarmotTestNative.verify(hex.parseHex(decoded.sig), hex.parseHex(decoded.id), hex.parseHex(decoded.pubkey)))
        } finally { MarmotTestNative.destroyIdentity(handle) }
    }
}
