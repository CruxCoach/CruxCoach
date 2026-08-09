package com.cruxcoach.android.competition

import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionBolt11
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * Paying an entry fee from the app, against a fake endpoint.
 *
 * No network leaves the machine — MockWebServer binds loopback — and nothing
 * here is payable: the invoice comes from the same deliberately unsigned
 * generator the fixtures use.
 *
 * What this pins is the sequence the app performs and the refusals it makes on
 * the way. The rules themselves live in `:shared` and are pinned separately
 * against the website's own fixtures, so a divergence shows up there; this is
 * about the app doing the steps in the right order and stopping at the right
 * points.
 */
class CompetitionPaymentFlowTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * https, not http.
     *
     * The flow refuses a plain-http endpoint outright, so a test served over
     * http would be exercising that refusal rather than the payment path. A
     * self-signed certificate for `localhost`, trusted only by this test's
     * client, is the smallest way to test the thing itself.
     */
    private val certificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .build()
    private val serverCerts = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
    private val clientCerts = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()

    private val server = MockWebServer().apply {
        useHttps(serverCerts.sslSocketFactory(), false)
    }
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        .build()

    private val organizer = "2014dc3b1e6ca37888d3b4620fd4f23f1d8e5440dfbe51121cf787ad63b15004"
    private val payer = "d4d41c056c407711c552ace7aaf4fa9b51b4ea80d06beac56a399c0034229130"
    private val provider = "8e9b6c1625647191d503917fa27a066d307188a5672fcde5e79780baf01f53b1"

    /** The same structurally-valid, unpayable invoice shape as the fixtures. */
    private val invoice2000Sats =
        CompetitionFixtureInvoice.bolt11(amountMsat = 2_000_000, timestamp = 1_789_000_255, expirySec = 900)
    private val invoice200Sats =
        CompetitionFixtureInvoice.bolt11(amountMsat = 200_000, timestamp = 1_789_000_255, expirySec = 900)

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun competition(
        feeMsat: Long = 2_000_000,
        lnurl: String? = null,
    ): Competition = Competition.from(
        json.parseToJsonElement(
            """
            {
              "comp_id": "bb00cc11dd22ee33",
              "authority": "$organizer",
              "authority_epoch": 1,
              "title": "Paid entry",
              "status": "registration_open",
              "capacity": 8,
              "fee_msat": $feeMsat,
              ${if (lnurl == null) "" else "\"fee_lnurl\": \"$lnurl\","}
              "divisions": [{"id": "open", "label": "Open"}],
              "climbs": [],
              "rules": {
                "climb_source": "organizer_set", "climb_count": 1,
                "selection_uniqueness": "none", "progression": "synchronous_rounds",
                "attempts_per_climb": 3, "turn_deadline_sec": 120, "attempt_deadline_sec": 0,
                "min_rest_sec": 0, "defer_budget_per_round": 1, "max_consecutive_defers": 1,
                "defer_slots": 2, "scoring": "tops_then_attempts",
                "tiebreaks": ["fewest_attempts"], "late_entry_allowed": false
              },
              "relays": ["wss://relay.example.invalid"]
            }
            """.trimIndent(),
        ).jsonObject,
    )

    private fun payResponse(
        callback: String,
        allowsNostr: Boolean = true,
        min: Long = 1000,
        max: Long = 100_000_000,
        tag: String = "payRequest",
    ) = """
        {
          "tag": "$tag",
          "callback": "$callback",
          "minSendable": $min,
          "maxSendable": $max,
          "metadata": "[[\"text/plain\",\"CruxCoach competition entry\"]]",
          "allowsNostr": $allowsNostr,
          "nostrPubkey": "$provider"
        }
    """.trimIndent()

    /**
     * A signer that produces a fixed, well-formed zap request.
     *
     * The request's *shape* is pinned by CompetitionLightningTest against the
     * shared fixtures; what this test is about is the flow's sequence.
     */
    private val fakeZapSigner = CompetitionPaymentFlow.ZapRequestSigner { _, _ ->
        CompetitionPaymentFlow.SignedZapRequest(
            id = "f".repeat(64),
            json = """{"kind":9734,"id":"${"f".repeat(64)}"}""",
        )
    }

    private val claims = mutableListOf<Pair<String, String>>()

    /** Records what the organizer would be told, without touching a relay. */
    private val fakeClaims = CompetitionPaymentFlow.ClaimPublisher { _, _, receiptId, bolt11 ->
        claims += (receiptId to bolt11)
    }

    /**
     * The flow with its two seams replaced.
     *
     * `NostrSigner` and `CompetitionIntentPublisher` are final classes that
     * reach a keystore and a relay, so they are supplied as relaxed mocks that
     * are never called: everything this test exercises goes through the seams.
     */
    private fun flow(): CompetitionPaymentFlow =
        CompetitionPaymentFlow(client, mockk(relaxed = true), mockk(relaxed = true))

    private suspend fun request(
        competition: Competition,
        nowSeconds: Long = 1_789_000_300,
    ): CompetitionPaymentFlow.Result = flow().requestInvoice(
        competition = competition,
        organizerPubkey = organizer,
        relays = listOf("wss://relay.example.invalid"),
        nowSeconds = nowSeconds,
        zapSigner = fakeZapSigner,
        claimPublisher = fakeClaims,
        registrationNonce = "3f9a2c17",
    )

    @Test
    fun `a good endpoint produces an invoice, and the claim tells the organizer about it`() = runBlocking {
        server.enqueue(MockResponse().setBody(payResponse(server.url("/cb").toString())))
        server.enqueue(MockResponse().setBody("""{"pr":"$invoice2000Sats"}"""))

        val result = request(competition(lnurl = server.url("/lnurlp").toString()), nowSeconds = 1_789_000_300)

        val ready = result as CompetitionPaymentFlow.Result.Ready
        assertEquals(invoice2000Sats, ready.invoice.bolt11)
        assertEquals(2_000_000L, ready.invoice.amountMsat)
        assertTrue(ready.invoice.verifiable, "the endpoint named a key, so a receipt can be checked")
        assertEquals("lightning:$invoice2000Sats", ready.invoice.walletUri)

        assertEquals(1, claims.size, "the organizer is told which receipt to look for")
        assertEquals(invoice2000Sats, claims.single().second)

        // The zap request went with the invoice request, which is what binds a
        // later receipt to this person and this registration.
        server.takeRequest()
        val callback = server.takeRequest()
        assertTrue(callback.path!!.contains("amount=2000000"), callback.path!!)
        assertTrue(callback.path!!.contains("nostr="), callback.path!!)
    }

    @Test
    fun `an invoice for the wrong amount is refused rather than displayed`() = runBlocking {
        server.enqueue(MockResponse().setBody(payResponse(server.url("/cb").toString())))
        server.enqueue(MockResponse().setBody("""{"pr":"$invoice200Sats"}"""))

        val result = request(competition(lnurl = server.url("/lnurlp").toString()), nowSeconds = 1_789_000_300)
        val failed = result as CompetitionPaymentFlow.Result.Failed
        assertEquals("wrong_amount", failed.code)
        assertEquals(200L, failed.amountSats, "and it says what the invoice was actually for")
    }

    @Test
    fun `an already-expired invoice is not offered as payable`() = runBlocking {
        server.enqueue(MockResponse().setBody(payResponse(server.url("/cb").toString())))
        server.enqueue(MockResponse().setBody("""{"pr":"$invoice2000Sats"}"""))

        // Well past the invoice's 900-second life.
        val result = request(competition(lnurl = server.url("/lnurlp").toString()), nowSeconds = 1_789_010_000)
        assertEquals("expired", (result as CompetitionPaymentFlow.Result.Failed).code)
    }

    @Test
    fun `an endpoint that cannot zap still produces an invoice, marked unverifiable`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(payResponse(server.url("/cb").toString(), allowsNostr = false)),
        )
        server.enqueue(MockResponse().setBody("""{"pr":"$invoice2000Sats"}"""))

        val result = request(competition(lnurl = server.url("/lnurlp").toString()), nowSeconds = 1_789_000_300)
        val ready = result as CompetitionPaymentFlow.Result.Ready
        assertFalse(ready.invoice.verifiable, "the organizer will have to confirm this by hand")
        assertEquals("", ready.invoice.zapReceiptId, "and there is no receipt to look for")
    }

    @Test
    fun `a hostile or broken endpoint stops the flow with a named reason`() = runBlocking {
        // Not a pay request at all.
        server.enqueue(
            MockResponse().setBody(payResponse(server.url("/cb").toString(), tag = "withdrawRequest")),
        )
        assertEquals(
            "not_a_pay_request",
            (request(competition(lnurl = server.url("/lnurlp").toString()))
                as CompetitionPaymentFlow.Result.Failed).code,
        )

        // A fee below what the provider will accept.
        server.enqueue(
            MockResponse().setBody(payResponse(server.url("/cb").toString(), min = 5_000_000)),
        )
        val small = request(competition(lnurl = server.url("/lnurlp").toString()))
            as CompetitionPaymentFlow.Result.Failed
        assertEquals("below_minimum", small.code)
        assertEquals(5000L, small.amountSats)

        // An endpoint that simply fails.
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(
            "unreachable",
            (request(competition(lnurl = server.url("/lnurlp").toString()))
                as CompetitionPaymentFlow.Result.Failed).code,
        )
    }

    @Test
    fun `a competition with no published way to pay says so instead of trying`() = runBlocking {
        assertEquals(
            "empty",
            (request(competition(lnurl = null))
                as CompetitionPaymentFlow.Result.Failed).code,
        )
        assertEquals(
            "no_fee",
            (request(competition(feeMsat = 0, lnurl = "gym@example.org"))
                as CompetitionPaymentFlow.Result.Failed).code,
        )
    }

    @Test
    fun `a plain http endpoint is refused before any request is made`() = runBlocking {
        val result = request(competition(lnurl = "http://example.org/pay"),)
        assertEquals("not_https", (result as CompetitionPaymentFlow.Result.Failed).code)
        assertEquals(0, server.requestCount, "nothing may be asked of an endpoint we refuse")
    }
}

/**
 * The same unpayable invoice the fixtures use, built here so the app's tests do
 * not depend on the website's tooling being present.
 *
 * Structurally valid — prefix, timestamp, tagged fields, checksum — with a
 * signature of all zeros. Nothing can settle it, which is the point.
 */
internal object CompetitionFixtureInvoice {

    fun bolt11(amountMsat: Long, timestamp: Long, expirySec: Int): String {
        val words = mutableListOf<Int>()
        for (i in 6 downTo 0) words += ((timestamp shr (5 * i)) and 31).toInt()

        fun field(type: Int, value: List<Int>) {
            words += type
            words += (value.size shr 5) and 31
            words += value.size and 31
            words += value
        }

        fun hexWords(hex: String): List<Int> {
            val bits = hex.chunked(2)
                .joinToString("") { it.toInt(16).toString(2).padStart(8, '0') }
            return bits.chunked(5).map { it.padEnd(5, '0').toInt(2) }
        }

        field(1, hexWords("a".repeat(64)))
        field(6, listOf((expirySec shr 5) and 31, expirySec and 31))
        repeat(104) { words += 0 }

        val micro = amountMsat / 100_000
        require(micro * 100_000 == amountMsat) { "amount must be a whole micro-bitcoin" }
        return encode("lnbc${micro}u", words)
    }

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in 0 until 5) if ((top shr i) and 1 == 1) checksum = checksum xor GENERATOR[i]
        }
        return checksum
    }

    private fun hrpExpand(hrp: String): List<Int> = buildList {
        for (char in hrp) add(char.code shr 5)
        add(0)
        for (char in hrp) add(char.code and 31)
    }

    private fun encode(hrp: String, words: List<Int>): String {
        val checksum = polymod(hrpExpand(hrp) + words + List(6) { 0 }) xor 1
        val combined = words + (0 until 6).map { (checksum shr (5 * (5 - it))) and 31 }
        return hrp + "1" + combined.joinToString("") { CHARSET[it].toString() }
    }
}
