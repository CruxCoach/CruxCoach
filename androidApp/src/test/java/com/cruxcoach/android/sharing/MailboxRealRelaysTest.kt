package com.cruxcoach.android.sharing

import com.cruxcoach.android.sharing.mailbox.FakeMailboxRelay
import com.cruxcoach.android.sharing.mailbox.Mailbox
import com.cruxcoach.android.sharing.mailbox.MailboxTier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T2 (PLAN-0.2.6 §10.2): two real installations share through real strfry
 * mailboxes on 127.0.0.1 with the limits of the relays they stand for, one
 * of them behind a hostile proxy, and a hostile reserve that keeps
 * everything and sends junk. Nothing leaves the machine.
 *
 * Opt-in (not in CI): `CC_T2_STRFRY` names the strfry source folder (binary
 * and `strfry.conf`), `CC_T2_LIB` the library path it needs.
 */
class MailboxRealRelaysTest {
    private val strfry = System.getenv("CC_T2_STRFRY")?.let(::File)
    private val lib = System.getenv("CC_T2_LIB").orEmpty()
    private val scripts = File("../scripts/mailbox_t2").canonicalFile
    private val closeables = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> keep(value: T) = value.also { closeables += it }
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val sizeClasses = setOf(1024, 2048, 4096, 8192, 16_384, 32_768, 48_128)

    @AfterTest fun close() = closeables.asReversed().forEach { runCatching { it.close() } }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** One strfry relay with the default configuration and the given changes. */
    private inner class Strfry(name: String, edits: List<Pair<String, String>>, env: Map<String, String> = emptyMap()) : AutoCloseable {
        val dir: File = Files.createTempDirectory("cc-t2-$name-").toFile()
        val port = freePort()
        val url = "ws://127.0.0.1:$port"
        private val process: Process

        init {
            val db = File(dir, "db").apply { mkdirs() }
            var conf = File(strfry, "strfry.conf").readText()
                .replaceFirst(Regex("(?m)^db = .*$"), "db = \"${db.absolutePath}/\"")
                .replaceFirst("port = 7777", "port = $port")
                .replaceFirst(Regex("nofiles = \\d+"), "nofiles = 0")
            for ((old, new) in edits) {
                check(conf.contains(old)) { "strfry.conf has no '$old'" }
                conf = conf.replaceFirst(old, new)
            }
            val file = File(dir, "strfry.conf").apply { writeText(conf) }
            process = ProcessBuilder(File(strfry, "strfry").absolutePath, "--config=${file.absolutePath}", "relay")
                .directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "log"))
                .apply { environment()["LD_LIBRARY_PATH"] = lib; environment().putAll(env) }
                .start()
            var up = false
            repeat(100) { if (!up) { up = runCatching { info(port) }.isSuccess; if (!up) Thread.sleep(100) } }
            check(up) { "strfry $name did not start: ${File(dir, "log").readText().takeLast(500)}" }
        }

        fun log(): String = File(dir, "log").readText()

        override fun close() {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            dir.deleteRecursively()
        }
    }

    private fun info(port: Int): String =
        http.newCall(Request.Builder().url("http://127.0.0.1:$port/").header("Accept", "application/nostr+json").build())
            .execute().use { check(it.isSuccessful); it.body!!.string() }

    /**
     * A hostile WebSocket proxy in front of a relay: drops, doubles, delays and
     * so reorders messages both ways, closes subscriptions, drops connections,
     * and can be offline.
     */
    private inner class HostileProxy(private val upstream: Strfry, seed: Long) : AutoCloseable {
        private val server = MockWebServer()
        private val random = Random(seed)
        private val timer = Executors.newSingleThreadScheduledExecutor()
        @Volatile var offline = false
        val stats = ConcurrentHashMap<String, AtomicInteger>()
        private fun count(what: String) = stats.getOrPut(what) { AtomicInteger() }.incrementAndGet()
        private fun roll(p: Double) = random.nextDouble() < p

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    offline -> MockResponse().setResponseCode(503)
                    request.getHeader("Upgrade").equals("websocket", ignoreCase = true) -> MockResponse().withWebSocketUpgrade(bridge())
                    else -> MockResponse().setHeader("Content-Type", "application/nostr+json").setBody(info(upstream.port))
                }
            }
            server.start()
        }

        val url: String get() = "ws://127.0.0.1:${server.port}/"

        private fun later(block: () -> Unit) = timer.schedule(block, random.nextInt(300).toLong(), TimeUnit.MILLISECONDS)

        private fun bridge() = object : WebSocketListener() {
            @Volatile var up: WebSocket? = null

            override fun onOpen(webSocket: WebSocket, response: Response) {
                up = http.newWebSocket(Request.Builder().url(upstream.url).build(), object : WebSocketListener() {
                    override fun onMessage(ws: WebSocket, text: String) {
                        when {
                            text.startsWith("[\"EVENT\"") && roll(0.1) -> count("lost_in_answer")
                            roll(0.02) -> { count("dropped_connection"); webSocket.cancel(); ws.cancel() }
                            else -> {
                                later { webSocket.send(text) }
                                if (roll(0.1)) { count("doubled_to_client"); later { webSocket.send(text) } }
                            }
                        }
                    }
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { webSocket.cancel() }
                })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val upstreamSocket = up ?: return
                when {
                    text.startsWith("[\"EVENT\"") && roll(0.1) -> count("dropped_event")
                    text.startsWith("[\"REQ\"") && roll(0.05) -> {
                        count("closed_subscription")
                        val sub = Json.parseToJsonElement(text).jsonArray[1].jsonPrimitive.content
                        webSocket.send("""["CLOSED","$sub","error: shutting down idle subscription"]""")
                    }
                    else -> {
                        later { upstreamSocket.send(text) }
                        if (text.startsWith("[\"EVENT\"") && roll(0.1)) { count("doubled_event"); later { upstreamSocket.send(text) } }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { up?.close(1000, null); webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { up?.cancel() }
        }

        override fun close() {
            timer.shutdownNow()
            runCatching { server.shutdown() }
        }
    }

    /** Everything a relay holds that matches [filter] (raw REQ, for checks only). */
    private fun query(url: String, filter: String): List<JsonObject> {
        val out = CopyOnWriteArrayList<JsonObject>()
        val done = CountDownLatch(1)
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""["REQ","check",$filter]""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonArray
                when (message[0].jsonPrimitive.content) {
                    "EVENT" -> out += message[2].jsonObject
                    "EOSE", "CLOSED" -> done.countDown()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.countDown() }
        })
        done.await(10, TimeUnit.SECONDS)
        ws.close(1000, null)
        return out
    }

    /** Publish one event; null when accepted, else the relay's reason. */
    private fun publish(url: String, event: String): String? {
        var reason: String? = "no answer"
        val done = CountDownLatch(1)
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""["EVENT",$event]""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = Json.parseToJsonElement(text).jsonArray
                if (message[0].jsonPrimitive.content == "OK") {
                    reason = if (message[2].jsonPrimitive.content == "true") null else message[3].jsonPrimitive.content
                    done.countDown()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.countDown() }
        })
        done.await(10, TimeUnit.SECONDS)
        ws.close(1000, null)
        return reason
    }

    /** An event signed with a key nobody in the test owns. */
    private fun foreign(kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): String =
        Schnorr.event(Schnorr.secret(), createdAt, kind, tags, content)

    private fun waitFor(vararg endpoints: SyntheticMarmotEndpoint, seconds: Int, done: () -> Boolean): Boolean {
        repeat(seconds * 2) {
            endpoints.forEach { e -> runCatching { e.blocking { sync() } } }
            if (done()) return true
            Thread.sleep(500)
        }
        return false
    }

    private val received = ConcurrentHashMap<String, MutableList<String>>()

    private fun drain(endpoint: SyntheticMarmotEndpoint): List<String> = endpoint.blocking {
        val batch = next(0, 0, 0, 64)
        ack(batch.items.map { it.seq })
        batch.items.map { it.content }
    }.also { received.getOrPut(endpoint.account) { CopyOnWriteArrayList() } += it }

    @Test fun two_friends_share_over_real_mailboxes_with_real_limits_and_a_hostile_one() {
        assumeTrue("set CC_T2_STRFRY to run T2", strfry != null)
        val writeRule = File(scripts, "write_policy.py").absolutePath
        val throttle = File(scripts, "ratelimit_policy.py").absolutePath
        val cruxcoach = keep(Strfry("cruxcoach", listOf(
            "plugin = \"\"" to "plugin = \"$writeRule\"",
            "rejectEventsOlderThanSeconds = 94608000" to "rejectEventsOlderThanSeconds = 3600",
        )))
        val stats = File(cruxcoach.dir.parentFile, "cc-t2-throttle-${System.nanoTime()}.json")
        keep(AutoCloseable { stats.delete() })
        val damusLike = keep(Strfry("public-a", listOf("plugin = \"\"" to "plugin = \"$throttle\""), mapOf("STATS" to stats.absolutePath)))
        val nostrNetLike = keep(Strfry("public-b", listOf("maxFilterLimit = 500" to "maxFilterLimit = 100")))
        val seed = System.getenv("CC_T2_SEED")?.toLongOrNull() ?: 7
        println("T2 seed $seed")
        val proxy = keep(HostileProxy(damusLike, seed))
        val hostile = keep(FakeMailboxRelay()).apply { deletes = false }
        val pool = listOf(
            Mailbox("cruxcoach", cruxcoach.url, MailboxTier.CRUXCOACH, "cruxcoach", hetzner = true),
            Mailbox("a", proxy.url, MailboxTier.A, "a", cloudflare = true),
            Mailbox("b", nostrNetLike.url, MailboxTier.B, "b"),
            Mailbox("reserve", hostile.url, MailboxTier.RESERVE, "reserve"),
        )
        val alice = keep(SyntheticMarmotEndpoint(listOf("ws://127.0.0.1:1"), p2p = true, mailboxPool = pool))
        val bob = keep(SyntheticMarmotEndpoint(listOf("ws://127.0.0.1:1"), p2p = true, mailboxPool = pool))
        for (e in listOf(alice, bob)) e.blocking { setOnline(true) }
        runBlocking { alice.service.setAge(true); bob.service.setAge(true) }
        val template = com.cruxcoach.domain.sharing.ShareTemplates.TRAINING_PARTNER.id

        // A link, and nothing else in common.
        val payload = ShareLinks.payload(runBlocking { alice.service.createLink() })!!
        runBlocking { bob.service.openLink(payload, template, "Alice") }
        assertTrue(waitFor(alice, bob, seconds = 120) {
            runBlocking { alice.service.overview() }.invitations.any { it.peer == bob.account }
        }, "the request crossed real mailboxes")
        runBlocking { alice.service.accept(bob.account, template, outgoing = true) }
        assertTrue(waitFor(alice, bob, seconds = 120) {
            bob.blocking { status() }.peers.any { it.account == alice.account && it.state == "active" }
        }, "the acceptance came back")

        // VT8: a deletion signed by someone else deletes nothing on a real relay.
        alice.blocking { send(bob.account, "t0", "synthetic first") }
        assertTrue(waitFor(alice, seconds = 60) { query(cruxcoach.url, """{"kinds":[2437]}""").isNotEmpty() })
        val target = query(cruxcoach.url, """{"kinds":[2437]}""").first()["id"]!!.jsonPrimitive.content
        val expiry = (System.currentTimeMillis() / 1000 + 86_400).toString()
        assertEquals(null, publish(cruxcoach.url, foreign(5, listOf(listOf("e", target), listOf("k", "2437"), listOf("expiration", expiry)), "")),
            "the write rule takes a well-formed deletion")
        assertTrue(query(cruxcoach.url, """{"ids":["$target"]}""").isNotEmpty(), "a foreign deletion deletes nothing")

        // Messages both ways through the hostile proxy, in waves so they travel
        // as many events: everything arrives exactly once.
        val count = 30
        for (wave in 0 until 6) {
            for (n in wave * 5 + 1..wave * 5 + 5) {
                alice.blocking { send(bob.account, "a$n", "synthetic a$n") }
                bob.blocking { send(alice.account, "b$n", "synthetic b$n") }
            }
            repeat(3) { listOf(alice, bob).forEach { e -> runCatching { e.blocking { sync() } } }; drain(alice); drain(bob); Thread.sleep(300) }
        }
        val wantBob = (1..count).map { "synthetic a$it" } + "synthetic first"
        val wantAlice = (1..count).map { "synthetic b$it" }
        assertTrue(waitFor(alice, bob, seconds = 240) {
            drain(alice); drain(bob)
            received[bob.account].orEmpty().containsAll(wantBob) && received[alice.account].orEmpty().containsAll(wantAlice)
        }, "converged: bob ${received[bob.account]?.size}, alice ${received[alice.account]?.size}, proxy ${proxy.stats}")
        assertEquals(wantBob.size, received[bob.account].orEmpty().size, "nothing applied twice at bob")
        assertEquals(wantAlice.size, received[alice.account].orEmpty().size, "nothing applied twice at alice")

        // VT9: once delivered, the real mailboxes hold only deletions.
        assertTrue(waitFor(alice, bob, seconds = 180) {
            listOf(cruxcoach, nostrNetLike).all { query(it.url, """{"kinds":[2437]}""").isEmpty() }
        }, "fetched events are deleted: ${listOf(cruxcoach, nostrNetLike).map { query(it.url, """{"kinds":[2437]}""").size }}")
        assertTrue(query(cruxcoach.url, """{"kinds":[5]}""").isNotEmpty())

        // VT12: the CruxCoach write rule refuses what the app never sends.
        val now = System.currentTimeMillis() / 1000
        val content = Base64.getEncoder().encodeToString(ByteArray(1024))
        val tooLong = foreign(2437, listOf(listOf("z", "0123456789abcdef"), listOf("expiration", (now + 30 * 86_400).toString())), content)
        assertTrue(publish(cruxcoach.url, tooLong)!!.startsWith("invalid: expiration"))
        val oddSize = foreign(2437, listOf(listOf("z", "0123456789abcdef"), listOf("expiration", (now + 3_600).toString())),
            Base64.getEncoder().encodeToString(ByteArray(1000)))
        assertTrue(publish(cruxcoach.url, oddSize)!!.startsWith("invalid: size"))
        assertTrue(publish(cruxcoach.url, foreign(1, emptyList(), "hello"))!!.startsWith("blocked"))

        // VT13: what the relays hold names nobody and says little.
        // Refused events are not stored, so this is only what the two apps wrote (and the foreign deletion).
        val everything = listOf(cruxcoach, damusLike, nostrNetLike).flatMap { query(it.url, """{"limit":500}""") }
        for (event in everything) {
            val text = event.toString()
            assertTrue(alice.account !in text && bob.account !in text, "no npub on a relay")
            if (event["kind"]!!.jsonPrimitive.int != 2437) continue
            val address = event["tags"]!!.jsonArray.single { it.jsonArray[0].jsonPrimitive.content == "z" }.jsonArray[1].jsonPrimitive.content
            assertTrue(Regex("^[0-9a-f]{16}$").matches(address))
            assertTrue(Base64.getDecoder().decode(event["content"]!!.jsonPrimitive.content).size in sizeClasses, "size classes only")
            assertEquals(0L, event["created_at"]!!.jsonPrimitive.long % 60, "minutes only")
        }

        // The throttled relay never had to block anyone.
        val throttled = stats.takeIf { it.isFile }?.readText().orEmpty()
        assertTrue(!throttled.contains("\"bans\": [1-9]".toRegex()), "never blocked: $throttled")

        // VT7/VT8: the reserve keeps everything and adds junk; nothing is taken twice or from junk.
        val replay = query(nostrNetLike.url, """{"kinds":[5]}""").firstOrNull()?.toString()
        hostile.junk += listOfNotNull(
            """{"id":"00","kind":2437}""",
            foreign(2437, listOf(listOf("z", "0123456789abcdef"), listOf("expiration", (now + 3_600).toString())), content),
            foreign(2437, listOf(listOf("z", "0123456789abcdef"), listOf("expiration", (now - 7_200).toString())), content, now - 9_000),
            replay,
        )
        cruxcoach.close() // CruxCoach down: everything is read, the reserve too.
        repeat(6) { listOf(alice, bob).forEach { e -> runCatching { e.blocking { sync() } } }; Thread.sleep(500) }
        drain(alice); drain(bob)
        assertEquals(wantBob.size, received[bob.account].orEmpty().size, "junk and copies bring nothing new")
        assertEquals(wantAlice.size, received[alice.account].orEmpty().size)
        println("T2 proxy faults: ${proxy.stats}; throttle: $throttled")
    }
}

/**
 * BIP-340 signatures for foreign test events, in plain BigInteger arithmetic
 * (the app's signing library needs a newer JVM than the unit tests run on).
 * Slow and not constant-time: test use only; the relays verify the result.
 */
private object Schnorr {
    private val p = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val n = java.math.BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val g = java.math.BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16) to
        java.math.BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val two = java.math.BigInteger.TWO
    private val three = java.math.BigInteger.valueOf(3)

    private fun add(a: Pair<java.math.BigInteger, java.math.BigInteger>?, b: Pair<java.math.BigInteger, java.math.BigInteger>?) : Pair<java.math.BigInteger, java.math.BigInteger>? {
        if (a == null) return b
        if (b == null) return a
        if (a.first == b.first && a.second != b.second) return null
        val slope = if (a == b) three * a.first * a.first * (two * a.second).modInverse(p)
        else (b.second - a.second) * (b.first - a.first).modInverse(p)
        val x = (slope * slope - a.first - b.first).mod(p)
        return x to (slope * (a.first - x) - a.second).mod(p)
    }

    private fun times(k: java.math.BigInteger): Pair<java.math.BigInteger, java.math.BigInteger> {
        var result: Pair<java.math.BigInteger, java.math.BigInteger>? = null
        var addend: Pair<java.math.BigInteger, java.math.BigInteger>? = g
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result!!
    }

    private fun bytes(x: java.math.BigInteger): ByteArray {
        val raw = x.toByteArray().takeLast(32).toByteArray()
        return ByteArray(32 - raw.size) + raw
    }

    private fun sha(vararg parts: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").apply { parts.forEach(::update) }.digest()

    private fun tagged(tag: String, vararg parts: ByteArray): ByteArray {
        val t = sha(tag.toByteArray())
        return sha(t, t, *parts)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun secret(): java.math.BigInteger {
        while (true) {
            val d = java.math.BigInteger(1, java.security.SecureRandom().generateSeed(32))
            if (d.signum() > 0 && d < n) return d
        }
    }

    fun event(secret: java.math.BigInteger, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val point = times(secret)
        val d = if (point.second.testBit(0)) n - secret else secret
        val pubkey = hex(bytes(point.first))
        val jsonTags = kotlinx.serialization.json.buildJsonArray {
            tags.forEach { tag -> add(kotlinx.serialization.json.buildJsonArray { tag.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }
        }
        val serialized = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(0)); add(kotlinx.serialization.json.JsonPrimitive(pubkey))
            add(kotlinx.serialization.json.JsonPrimitive(createdAt)); add(kotlinx.serialization.json.JsonPrimitive(kind))
            add(jsonTags); add(kotlinx.serialization.json.JsonPrimitive(content))
        }.toString()
        val id = sha(serialized.toByteArray())
        val aux = java.security.SecureRandom().generateSeed(32)
        val masked = bytes(d).zip(tagged("BIP0340/aux", aux)) { a, b -> (a.toInt() xor b.toInt()).toByte() }.toByteArray()
        val k0 = java.math.BigInteger(1, tagged("BIP0340/nonce", masked, bytes(point.first), id)).mod(n)
        val r = times(k0)
        val k = if (r.second.testBit(0)) n - k0 else k0
        val e = java.math.BigInteger(1, tagged("BIP0340/challenge", bytes(r.first), bytes(point.first), id)).mod(n)
        val sig = hex(bytes(r.first)) + hex(bytes((k + e * d).mod(n)))
        return kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(hex(id)))
            put("pubkey", kotlinx.serialization.json.JsonPrimitive(pubkey))
            put("created_at", kotlinx.serialization.json.JsonPrimitive(createdAt))
            put("kind", kotlinx.serialization.json.JsonPrimitive(kind))
            put("tags", jsonTags)
            put("content", kotlinx.serialization.json.JsonPrimitive(content))
            put("sig", kotlinx.serialization.json.JsonPrimitive(sig))
        }.toString()
    }
}
