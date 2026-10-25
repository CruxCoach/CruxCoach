package com.cruxcoach.android.sharing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PrivateApplicationSignerTest {
    private val account = "a".repeat(64)

    @Test fun unattended_refusal_has_no_interactive_fallback() = runBlocking {
        var foreground = 0
        var background = 0
        val signer = PrivateApplicationSigner(
            foreground = { _, _, _, _ -> foreground++; error("must not open foreground signer") },
            unattended = { true },
            provider = { _, _, _, _ -> background++; null },
            account = account, currentAccount = { account },
        )
        assertNull(signer.sign(1, 30443, emptyList(), "synthetic key package"))
        assertEquals(1, background)
        assertEquals(0, foreground)
    }

    @Test fun relay_authentication_never_opens_a_dialog_even_during_a_person_action() = runBlocking {
        var foreground = 0
        val signer = PrivateApplicationSigner(
            foreground = { _, _, _, _ -> foreground++; null },
            unattended = { false },
            provider = { _, _, _, _ -> null },
            account = account, currentAccount = { account },
        )
        assertNull(signer.sign(1, PrivateApplicationSigner.RELAY_AUTH_KIND, emptyList(), ""))
        assertEquals(0, foreground)
        assertNull(signer.sign(1, 10002, emptyList(), ""))
        assertEquals(1, foreground)
    }

    @Test fun a_provider_exception_never_opens_a_second_channel() = runBlocking {
        var foreground = 0
        val signer = PrivateApplicationSigner({ _, _, _, _ -> foreground++; null }, { true },
            { _, _, _, _ -> error("synthetic provider refusal") }, account, { account })
        assertFailsWith<IllegalStateException> { signer.sign(1, 10050, emptyList(), "synthetic") }
        assertEquals(0, foreground)
    }

    @Test fun wrong_or_changed_account_discards_the_result() = runBlocking {
        val forbidden = Nip01EventSigner { _, _, _, _ -> fail("unauthorized signer call") }
        assertFailsWith<IllegalStateException> {
            PrivateApplicationSigner(forbidden, { true }, forbidden, account, { "b".repeat(64) }).sign(1, 1, emptyList(), "")
        }
        var current = account
        val switching = Nip01EventSigner { time, kind, tags, content ->
            current = "b".repeat(64)
            SignedNostrEvent("0".repeat(64), account, time, kind, tags, content, "0".repeat(128))
        }
        assertFailsWith<IllegalStateException> {
            PrivateApplicationSigner(switching, { false }, switching, account, { current }).sign(1, 1, emptyList(), "")
        }
        Unit
    }

    @Test fun changed_fields_are_rejected_on_both_routes_without_retry() = runBlocking {
        val original = SignedNostrEvent("0".repeat(64), account, 1, 10002, listOf(listOf("r", "wss://relay.example")), "", "0".repeat(128))
        for (background in listOf(false, true)) for (changed in listOf(original.copy(pubKey = "b".repeat(64)),
            original.copy(createdAt = 2), original.copy(kind = 10050), original.copy(tags = emptyList()), original.copy(content = "changed"))) {
            var calls = 0
            val source = Nip01EventSigner { _, _, _, _ -> calls++; changed }
            val signer = PrivateApplicationSigner(source, { background }, source, account, { account })
            assertFailsWith<IllegalStateException> { signer.sign(original.createdAt, original.kind, original.tags, original.content) }
            assertEquals(1, calls)
        }
    }

    @Test fun a_natively_signed_event_survives_the_callback_encoding_exactly() = runBlocking {
        val identity = MarmotTestNative.value(MarmotTestNative.createIdentity()).jsonObject
        val handle = identity.getValue("handle").jsonPrimitive.long
        val public = identity.getValue("account").jsonPrimitive.content
        try {
            val source = Nip01EventSigner { time, kind, tags, content ->
                val request = buildJsonObject {
                    put("pubkey", public); put("created_at", time); put("kind", kind)
                    put("tags", Json.encodeToJsonElement(tags)); put("content", content)
                }
                Json.decodeFromJsonElement(SignedNostrEvent.serializer(), MarmotTestNative.value(MarmotTestNative.signEvent(handle, request.toString())))
            }
            val signer = PrivateApplicationSigner(source, { true }, source, public, { public })
            val tags = listOf(listOf("l", "synthetic \"tag\"\nβ"))
            val content = "synthetic callback \"quotes\" \\ newline\n日本語"
            val signed = assertNotNull(signer.sign(123, 1230, tags, content))
            val decoded = Json.decodeFromString<SignedNostrEvent>(Json.encodeToString(signed))
            assertEquals(signed, decoded)
            val hex = java.util.HexFormat.of()
            assertTrue(MarmotTestNative.verify(hex.parseHex(decoded.sig), hex.parseHex(decoded.id), hex.parseHex(decoded.pubKey)))
        } finally {
            MarmotTestNative.destroyIdentity(handle)
        }
    }
}
