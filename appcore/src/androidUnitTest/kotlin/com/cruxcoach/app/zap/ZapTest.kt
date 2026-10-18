package com.cruxcoach.app.zap

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.nostr.NostrEvent
import com.cruxcoach.app.nostr.NostrEvents
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.nostr.NostrRelays
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.db.secure.SecureDatabase
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Serves LNURL answers per URL prefix and records every request. Never pays anything. */
private class FakeLnurlHttp : HttpTransport {
    val requested = ArrayList<String>()
    val answers = LinkedHashMap<String, Pair<Int, String>>()
    var fail = false

    override suspend fun request(
        method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int,
    ): HttpResult {
        requested += url
        if (fail) return HttpResult.Failed(HttpFailure.OFFLINE, "offline")
        val (status, text) = answers.entries.firstOrNull { url.startsWith(it.key) }?.value
            ?: return HttpResult.Ok(HttpResponse(404, emptyMap(), ByteArray(0)))
        val bytes = text.encodeToByteArray()
        if (bytes.size > maxResponseBytes) return HttpResult.Failed(HttpFailure.TOO_LARGE, "cap")
        return HttpResult.Ok(HttpResponse(status, emptyMap(), bytes))
    }

    override suspend fun download(
        url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit,
    ): HttpResult = error("not used")
}

private const val ADDRESS = "alice@pay.example.org"
private const val PAY_URL = "https://pay.example.org/.well-known/lnurlp/alice"
private const val CALLBACK = "https://pay.example.org/lnurlp/alice/callback"

// Invoices shaped like the BOLT 11 spec examples (2500u = 250,000 sat; one without an amount).
// Only the human-readable part matters here: nothing in the app decodes the payload.
private const val INVOICE_2500U =
    "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
private const val INVOICE_NO_AMOUNT =
    "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"

class LnurlTest {
    @Test
    fun `lightning address maps to the LUD-16 well-known URL`() {
        assertEquals(PAY_URL, Lnurl.payInfoUrl(ADDRESS))
        assertEquals(
            "https://npub.cash/.well-known/lnurlp/npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s",
            Lnurl.payInfoUrl("npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s@npub.cash"),
        )
    }

    @Test
    fun `anything but one plain address is refused`() {
        for (bad in listOf("alice", "a@b@c.org", "@pay.example.org", "alice@", "alice@localhost",
            "alice@pay.example.org/evil", "al ice@pay.example.org", "alice@pay.example.org:8080", "../x@pay.example.org/")) {
            assertNull(Lnurl.payInfoUrl(bad), bad)
        }
    }

    @Test
    fun `pay info is read like Android and error answers give nothing`() {
        val info = assertNotNull(Lnurl.parsePayInfo(
            """{"callback":"$CALLBACK","minSendable":1000,"maxSendable":100000000000,"allowsNostr":true,"nostrPubkey":"ab","tag":"payRequest"}""",
        ))
        assertEquals(CALLBACK, info.callback)
        assertEquals(1000L, info.minSendable)
        assertEquals(100_000_000_000L, info.maxSendable)
        assertTrue(info.allowsNostr)
        assertEquals("ab", info.nostrPubkey)

        val plain = assertNotNull(Lnurl.parsePayInfo("""{"callback":"$CALLBACK","minSendable":1000,"maxSendable":2000}"""))
        assertEquals(false, plain.allowsNostr)
        assertNull(plain.nostrPubkey)

        assertNull(Lnurl.parsePayInfo("""{"status":"ERROR","reason":"no such user"}"""))
        assertNull(Lnurl.parsePayInfo("""{"callback":"http://insecure/cb","minSendable":1,"maxSendable":2}"""))
        assertNull(Lnurl.parsePayInfo("""{"callback":"$CALLBACK","minSendable":5000,"maxSendable":1000}"""))
        assertNull(Lnurl.parsePayInfo("""{"callback":"$CALLBACK","minSendable":"x","maxSendable":1000}"""))
        assertNull(Lnurl.parsePayInfo("not json"))
    }

    @Test
    fun `callback URLs append with the right separator`() {
        assertEquals("$CALLBACK?amount=5000000", Lnurl.privateCallbackUrl(CALLBACK, 5_000_000))
        assertEquals("$CALLBACK?k=1&amount=5000000", Lnurl.privateCallbackUrl("$CALLBACK?k=1", 5_000_000))
        val zap = Lnurl.zapCallbackUrl(CALLBACK, 21_000, """{"a":"b c"}""")
        assertEquals("$CALLBACK?amount=21000&nostr=%7B%22a%22%3A%22b%20c%22%7D", zap)
    }

    @Test
    fun `invoice must be a bech32 lightning invoice`() {
        assertEquals(INVOICE_2500U, Lnurl.parseInvoice("""{"pr":"$INVOICE_2500U","routes":[]}"""))
        assertEquals(INVOICE_2500U, Lnurl.parseInvoice("""{"pr":"${INVOICE_2500U.uppercase()}"}"""))
        assertNull(Lnurl.parseInvoice("""{"pr":""}"""))
        assertNull(Lnurl.parseInvoice("""{"status":"ERROR"}"""))
        assertNull(Lnurl.parseInvoice("""{"pr":"javascript:alert(1)"}"""))
        assertNull(Lnurl.parseInvoice("""{"pr":"lnbc1Mixed"}"""))
        assertNull(Lnurl.parseInvoice("""{"pr":"lnbc${"q".repeat(Lnurl.MAX_INVOICE_CHARS)}"}"""))
    }

    @Test
    fun `invoice amounts follow BOLT 11 multipliers`() {
        assertEquals(250_000_000L, Lnurl.invoiceAmountMilliSats(INVOICE_2500U))
        assertNull(Lnurl.invoiceAmountMilliSats(INVOICE_NO_AMOUNT))
        assertEquals(2_000_000_000L, Lnurl.invoiceAmountMilliSats("lnbc20m1qqqq"))
        assertEquals(5_000_000L, Lnurl.invoiceAmountMilliSats("lnbc50u1qqqq"))
        assertEquals(1_000L, Lnurl.invoiceAmountMilliSats("lnbc10n1qqqq"))
        assertEquals(1L, Lnurl.invoiceAmountMilliSats("lnbc10p1qqqq"))
        assertNull(Lnurl.invoiceAmountMilliSats("lnbc15p1qqqq"), "sub-millisatoshi amounts are invalid")
        assertEquals(100_000_000_000L, Lnurl.invoiceAmountMilliSats("lnbc11qqqq"))
        assertEquals(5_000L, Lnurl.invoiceAmountMilliSats("lnbcrt50n1qqqq"))
        assertNull(Lnurl.invoiceAmountMilliSats("lnbc012u1qqqq"))
        assertNull(Lnurl.invoiceAmountMilliSats("lnbc99999999999999999999m1qqqq"))
    }
}

class ZapClientTest {
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val pubkey = NostrKeys.publicKeyHex(secret)!!
    private val recipient = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
    private val http = FakeLnurlHttp()
    private val clock = FixedClock(1_790_000_000L)
    private var keyAvailable = true
    private val client = ZapClient(
        http = http,
        signer = LocalEventSigner(JvmHashing) { if (keyAvailable) secret.copyOf() else null },
        clock = clock,
        lightningAddressOf = { if (it == recipient) ADDRESS else null },
    )

    private fun payInfo(allowsNostr: Boolean = true, min: Long = 1_000, max: Long = 100_000_000_000) {
        http.answers[PAY_URL] = 200 to
            """{"callback":"$CALLBACK","minSendable":$min,"maxSendable":$max,"allowsNostr":$allowsNostr,"nostrPubkey":"$recipient","tag":"payRequest"}"""
    }

    private fun invoice(pr: String = INVOICE_2500U) {
        http.answers[CALLBACK] = 200 to """{"pr":"$pr","routes":[]}"""
    }

    @Test
    fun `private payment asks for an invoice with the amount only`() = runBlocking {
        payInfo(); invoice()
        val outcome = client.createPaymentRequest(recipient, 250_000_000, "thanks", privately = true)
        assertEquals(INVOICE_2500U, outcome.invoice)
        assertEquals(listOf(PAY_URL, "$CALLBACK?amount=250000000"), http.requested)
    }

    @Test
    fun `public zap attaches a signed kind-9734 request with Android's tags`() = runBlocking {
        payInfo(); invoice()
        val outcome = client.createPaymentRequest(recipient, 250_000_000, "Great app", privately = false)
        assertEquals(INVOICE_2500U, outcome.invoice)
        val callback = http.requested[1]
        assertTrue(callback.startsWith("$CALLBACK?amount=250000000&nostr="))
        val requestJson = URLDecoder.decode(callback.substringAfter("&nostr="), "UTF-8")
        val event = assertNotNull(NostrEvent.fromJson(Json.parseToJsonElement(requestJson).jsonObject))
        assertTrue(NostrEvents.verify(JvmHashing, event), "the zap request must carry a valid signature")
        assertEquals(9734, event.kind)
        assertEquals(pubkey, event.pubkey)
        assertEquals("Great app", event.content)
        assertEquals(clock.seconds, event.createdAt)
        assertEquals(listOf("p", recipient), event.tags[0])
        assertEquals(listOf("amount", "250000000"), event.tags[1])
        assertEquals(listOf("relays") + NostrRelays.DEFAULT_RELAYS, event.tags[2])
    }

    @Test
    fun `public zap is refused when the endpoint takes no Nostr zaps`() = runBlocking {
        payInfo(allowsNostr = false); invoice()
        val outcome = client.createPaymentRequest(recipient, 250_000_000, "", privately = false)
        assertEquals(ZapFailure.NO_NOSTR_SUPPORT, outcome.failure)
        assertEquals(1, http.requested.size, "no callback without zap support")
    }

    @Test
    fun `amount outside the endpoint limits reports the limits in sats`() = runBlocking {
        payInfo(min = 10_000, max = 1_000_000); invoice()
        val outcome = client.createPaymentRequest(recipient, 250_000_000, "", privately = true)
        assertEquals(ZapFailure.OUT_OF_RANGE, outcome.failure)
        assertEquals(10L, outcome.minSats)
        assertEquals(1_000L, outcome.maxSats)
        assertNull(outcome.invoice)
    }

    @Test
    fun `an invoice for another amount is refused`() = runBlocking {
        payInfo(); invoice()
        val outcome = client.createPaymentRequest(recipient, 5_000_000, "", privately = true)
        assertEquals(ZapFailure.INVOICE_MISMATCH, outcome.failure)
        invoice(INVOICE_NO_AMOUNT)
        assertEquals(ZapFailure.INVOICE_MISMATCH, client.createPaymentRequest(recipient, 5_000_000, "", privately = true).failure)
    }

    @Test
    fun `missing address unreachable server and missing invoice fail with their reason`() = runBlocking {
        assertEquals(ZapFailure.NO_ADDRESS, client.createPaymentRequest("ff".repeat(32), 1_000, "", privately = true).failure)
        http.fail = true
        assertEquals(ZapFailure.PAY_INFO_UNAVAILABLE, client.createPaymentRequest(recipient, 1_000, "", privately = true).failure)
        http.fail = false
        http.answers[PAY_URL] = 500 to "{}"
        assertEquals(ZapFailure.PAY_INFO_UNAVAILABLE, client.createPaymentRequest(recipient, 1_000, "", privately = true).failure)
        payInfo()
        http.answers[CALLBACK] = 200 to """{"status":"ERROR","reason":"later"}"""
        assertEquals(ZapFailure.INVOICE_UNAVAILABLE, client.createPaymentRequest(recipient, 1_000, "", privately = true).failure)
    }

    @Test
    fun `public zap without a key fails before any invoice is asked for`() = runBlocking {
        payInfo(); invoice()
        keyAvailable = false
        assertEquals(ZapFailure.SIGNING_FAILED, client.createPaymentRequest(recipient, 250_000_000, "", privately = false).failure)
        assertEquals(1, http.requested.size)
    }

    @Test
    fun `maintainer address is the fallback only for the maintainer`() = runBlocking {
        val resolver = LightningAddressResolver({ null }, recipient, "dev@npub.cash")
        assertEquals("dev@npub.cash", resolver.resolve(recipient))
        assertNull(resolver.resolve("ab".repeat(32)))
        val fromProfile = LightningAddressResolver({ " me@wallet.example " }, recipient, "dev@npub.cash")
        assertEquals("me@wallet.example", fromProfile.resolve(recipient), "the profile's lud16 wins, as on Android")
        val broken = LightningAddressResolver({ error("relay down") }, recipient, "dev@npub.cash")
        assertEquals("dev@npub.cash", broken.resolve(recipient))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ZapModelTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    private val database = SecureDatabase(driver)
    private val secret = NostrKeys.generateSecretKey(JvmHashing)!!
    private val sender = NostrKeys.publicKeyHex(secret)!!
    private val recipient = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
    private val http = FakeLnurlHttp()
    private val notes = ArrayList<Pair<String, String>>()

    @AfterTest
    fun tearDown() = driver.close()

    private fun TestScope.model(): ZapModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = ZapClient(http, LocalEventSigner(JvmHashing) { secret.copyOf() }, FixedClock(1_790_000_000L), { ADDRESS })
        return ZapModel(
            client = client,
            recipientPubkey = recipient,
            senderPubkey = sender,
            log = PaymentLog(database),
            donationMessage = { content, subject -> notes += content to subject },
            idFactory = { PaymentIds.random(JvmHashing) },
            millis = { 1_790_000_000_123L },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            appScope = CoroutineScope(SupervisorJob() + dispatcher),
            io = dispatcher,
        )
    }

    private fun serve() {
        http.answers[PAY_URL] = 200 to """{"callback":"$CALLBACK","minSendable":1000,"maxSendable":100000000000,"allowsNostr":true}"""
        http.answers[CALLBACK] = 200 to """{"pr":"$INVOICE_2500U"}"""
    }

    @Test
    fun `presets and default are Android's`() = runTest {
        val model = model()
        assertEquals(listOf(1_000L, 5_000L, 10_000L, 21_000L, 50_000L), model.presetAmounts)
        assertEquals(5_000L, model.defaultAmount)
        assertEquals("choose", model.currentState.stepCode)
        model.close()
    }

    @Test
    fun `private gift with a message shows the invoice, logs the request and sends the note`() = runTest {
        serve()
        val model = model()
        model.selectLightning(privately = true)
        assertEquals("amount", model.currentState.stepCode)
        model.requestInvoice(250_000, "  Danke!  ")
        assertTrue(model.currentState.isSending)
        advanceUntilIdle()
        val ui = model.currentState
        assertEquals("invoice", ui.stepCode)
        assertEquals(INVOICE_2500U, ui.invoice)
        assertEquals("none", ui.errorCode)
        val row = database.paymentEventsQueries.getAll().executeAsList().single()
        assertEquals("LIGHTNING_PRIVATE", row.type)
        assertEquals("sent", row.direction)
        assertEquals(sender, row.sender_pubkey)
        assertEquals(recipient, row.recipient_pubkey)
        assertEquals(250_000L, row.amount_sats)
        assertEquals("Danke!", row.message)
        assertEquals(1_790_000_000_123L, row.created_at)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(row.id))
        assertEquals(listOf("[DONATION] 250000 sats — Danke!" to "Spende"), notes)
        model.close()
    }

    @Test
    fun `public zap and private gift without message send no note`() = runTest {
        serve()
        val model = model()
        model.selectLightning(privately = false)
        model.requestInvoice(250_000, "public words")
        advanceUntilIdle()
        assertEquals("invoice", model.currentState.stepCode)
        assertEquals("LIGHTNING", database.paymentEventsQueries.getAll().executeAsList().single().type)
        val second = model()
        second.selectLightning(privately = true)
        second.requestInvoice(250_000, "   ")
        advanceUntilIdle()
        assertTrue(notes.isEmpty())
        model.close()
        second.close()
    }

    @Test
    fun `a failure stays on the amount step with its code and logs nothing`() = runTest {
        http.answers[PAY_URL] = 200 to """{"callback":"$CALLBACK","minSendable":10000000,"maxSendable":20000000}"""
        val model = model()
        model.selectLightning(privately = true)
        model.requestInvoice(1_000, "")
        advanceUntilIdle()
        val ui = model.currentState
        assertEquals("amount", ui.stepCode)
        assertEquals("outOfRange", ui.errorCode)
        assertEquals(10_000L, ui.minSats)
        assertEquals(20_000L, ui.maxSats)
        assertTrue(database.paymentEventsQueries.getAll().executeAsList().isEmpty())
        model.consumeError()
        assertEquals("none", model.currentState.errorCode)
        model.close()
    }

    @Test
    fun `amounts of zero and requests outside the amount step are ignored`() = runTest {
        serve()
        val model = model()
        model.requestInvoice(5_000, "")
        model.selectLightning(privately = true)
        model.requestInvoice(0, "")
        advanceUntilIdle()
        assertTrue(http.requested.isEmpty())
        assertEquals("amount", model.currentState.stepCode)
        model.backToChoice()
        assertEquals("choose", model.currentState.stepCode)
        model.close()
    }
}
