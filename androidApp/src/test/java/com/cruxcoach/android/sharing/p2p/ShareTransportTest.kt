package com.cruxcoach.android.sharing.p2p

import com.cruxcoach.android.sharing.MarmotBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetAddress
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareTransportTest {
    @Test fun ble_fragments_reassemble_for_every_mtu_and_size() {
        for (mtu in listOf(23, 185, 247, 512)) {
            for (size in listOf(1, 17, 100, 5_000, BleShareTransport.MAX_CONTAINER)) {
                val message = Random(size + mtu).nextBytes(size)
                val fragments = ShareBleFraming.fragments(message, mtu)
                assertTrue(fragments.all { it.size <= mtu - ShareBleFraming.ATT_OVERHEAD }, "mtu $mtu")
                val assembler = ShareBleFraming.Reassembler(BleShareTransport.MAX_CONTAINER)
                val results = fragments.map { assembler.add(it) }
                assertTrue(results.dropLast(1).all { it == null })
                assertContentEquals(message, results.last())
            }
        }
    }

    @Test fun ble_reassembly_refuses_gaps_repeats_and_oversize() {
        val fragments = ShareBleFraming.fragments(Random(1).nextBytes(2_000), 185)
        assertFailsWith<IllegalArgumentException> {
            ShareBleFraming.Reassembler(4_096).apply { add(fragments[0]); add(fragments[2]) }
        }
        assertFailsWith<IllegalArgumentException> {
            ShareBleFraming.Reassembler(4_096).apply { add(fragments[0]); add(fragments[0]) }
        }
        assertFailsWith<IllegalArgumentException> { ShareBleFraming.Reassembler(1_000).add(fragments[0]) }
        // A truncated message whose "last" fragment arrives early fails the length check.
        val short = fragments[1].copyOf().also { it[2] = 1 }
        assertFailsWith<IllegalArgumentException> { ShareBleFraming.Reassembler(4_096).apply { add(fragments[0]); add(short) } }
    }

    @Test fun ble_advertisement_carries_only_rotating_beacons() {
        val hello = ShareHello(listOf("0123456789abcdef", "fedcba9876543210"))
        val data = ShareBleFraming.advertisement(hello)
        assertEquals(4 + 1 + 16, data.size, "fits legacy advertising with flags and manufacturer header")
        assertEquals(hello, ShareBleFraming.parseAdvertisement(data))
        assertNull(ShareBleFraming.parseAdvertisement(byteArrayOf(0x43, 0x52, 0x55, 0x58, 0) + ByteArray(8)))
        assertNull(ShareBleFraming.parseAdvertisement(data + ByteArray(8)))
        assertNull(ShareHello.parse("""{"beacons":["not-hex"]}"""))
    }

    /** Native stand-in: a status with one friend in reach and one not. */
    private class FakeNative : MarmotBinding {
        val calls = mutableListOf<String>()
        override fun call(handle: Long, command: String): String {
            val op = Json.parseToJsonElement(command).jsonObject["op"]!!.jsonPrimitive.content
            calls += op
            return when (op) {
                "status" -> """{"ok":true,"value":{"account":"a","online":true,"discovery":{"enabled":true,"key_package_until":0},
                    "local":{"events":5,"inbox":0,"outbound_pending":5},"peers":[
                    {"account":"near","group":"g","state":"active","invited_by_me":true,"created_at":1,"last_seen":1,"cancelled_at":0,"ended_at":0,"reason":"","outbound_pending":2},
                    {"account":"far","group":"h","state":"active","invited_by_me":true,"created_at":1,"last_seen":1,"cancelled_at":0,"ended_at":0,"reason":"","outbound_pending":3}],
                    "relays":[],"local_relay":false,"generation":1}}"""
                "hello" -> """{"ok":true,"value":{"beacons":["00000000000000aa"]}}"""
                "identify" -> """{"ok":true,"value":{"peer":"near"}}"""
                "exchange_begin" -> """{"ok":true,"value":{"container":"AQID"}}"""
                "exchange_end" -> """{"ok":true,"value":{"more":false,"peers":["near"]}}"""
                else -> """{"ok":true,"value":true}"""
            }
        }
        override fun close(handle: Long) = Unit
    }

    private class FakeTransport : ShareTransport {
        override val name = "lan"
        override val maxContainer = 1_000
        override var running = false
        var exchanged = 0
        override fun start(server: ShareExchangeServer) { running = true }
        override fun stop() { running = false }
        override fun endpoints() = listOf(ShareEndpoint("lan", "http://x", ShareHello(listOf("00000000000000bb"))))
        override fun exchange(endpoint: ShareEndpoint, request: ByteArray): ByteArray? { exchanged++; return byteArrayOf(4, 5, 6) }
    }

    @Test fun binding_reports_transports_and_counts_only_friends_in_reach_as_pending() {
        val native = FakeNative()
        val transport = FakeTransport()
        val binding = P2pMarmotBinding(native, listOf(transport), pollMs = 0)
        binding.call(1, """{"op":"set_online","online":true}""")
        assertTrue(transport.running)
        fun status() = Json.parseToJsonElement(binding.call(1, """{"op":"status"}""")).jsonObject["value"]!!.jsonObject
        // Nobody reached yet: nothing is deliverable now, the pass does not wait.
        assertEquals(0, status()["local"]!!.jsonObject["outbound_pending"]!!.jsonPrimitive.long)
        assertEquals("p2p:lan", status()["relays"]!!.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content)
        binding.call(1, """{"op":"sync"}""")
        assertEquals(1, transport.exchanged)
        assertEquals(2, status()["local"]!!.jsonObject["outbound_pending"]!!.jsonPrimitive.long, "only the friend in reach")
        assertEquals("accepted", status()["relays"]!!.jsonArray.single().jsonObject["last_result"]!!.jsonPrimitive.content)
        binding.call(1, """{"op":"set_online","online":false}""")
        assertTrue(!transport.running)
        assertEquals(listOf("set_online", "status", "status", "sync", "hello", "identify", "exchange_begin", "exchange_end"),
            native.calls.take(8))
    }

    @Test fun direct_transports_run_only_while_the_app_is_in_front() {
        val transport = FakeTransport()
        var front = false
        val binding = P2pMarmotBinding(FakeNative(), listOf(transport), pollMs = 0, direct = { front })
        binding.call(1, """{"op":"set_online","online":true}""")
        assertTrue(!transport.running, "a background pass opens no server and advertises nothing")
        binding.call(1, """{"op":"sync"}""")
        assertEquals(0, transport.exchanged)
        front = true
        binding.call(1, """{"op":"set_online","online":true}""")
        assertTrue(transport.running)
        front = false
        binding.call(1, """{"op":"set_online","online":true}""")
        assertTrue(!transport.running, "leaving the app stops them")
    }

    @Test fun http_routes_serve_hello_and_bounded_exchange() {
        val server = object : ShareExchangeServer {
            override fun hello() = ShareHello(listOf("0123456789abcdef"))
            override fun exchange(request: ByteArray, maxBytes: Int) = request.reversedArray()
        }
        val http = ShareHttpServer(InetAddress.getLoopbackAddress())
        val port = http.start(server, 1_000)
        try {
            val client = ShareHttpClient()
            val base = "http://127.0.0.1:$port"
            assertEquals(server.hello(), client.hello(base))
            assertContentEquals(byteArrayOf(3, 2, 1), client.exchange(base, byteArrayOf(1, 2, 3)))
            assertNull(client.exchange(base, ByteArray(ShareHttpRoutes.MAX_BODY + 1)), "oversized body refused")
            assertNull(client.hello("http://127.0.0.1:1"), "unreachable endpoint")
        } finally {
            http.stop()
        }
    }

    /** A raw socket that answers [reply] to whatever it is asked, once. */
    private fun answering(reply: ByteArray): Pair<java.net.ServerSocket, Thread> {
        val socket = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = Thread {
            runCatching {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    while (ShareHttpRoutes.readLine(input)?.isNotEmpty() == true) Unit
                    client.getOutputStream().apply { write(reply); flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        return socket to thread
    }

    @Test fun the_wifi_client_is_a_plain_bounded_socket_client() {
        val client = ShareHttpClient()
        // No HTTP library in between: it reaches any address on the local network.
        val (ok, t1) = answering("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc".toByteArray())
        assertContentEquals("abc".toByteArray(), client.exchange("http://127.0.0.1:${ok.localPort}", byteArrayOf(1)))
        t1.join(2_000); ok.close()
        // A peer announcing more than a container may be is refused before reading it.
        val (big, t2) = answering("HTTP/1.1 200 OK\r\nContent-Length: ${ShareHttpRoutes.MAX_BODY + 1}\r\n\r\n".toByteArray())
        assertNull(client.exchange("http://127.0.0.1:${big.localPort}", byteArrayOf(1)))
        t2.join(2_000); big.close()
        // Endless headers and an endless line end the request, nothing else.
        val (headers, t3) = answering(("HTTP/1.1 200 OK\r\n" + "X: y\r\n".repeat(100) + "\r\n").toByteArray())
        assertNull(client.exchange("http://127.0.0.1:${headers.localPort}", byteArrayOf(1)))
        t3.join(2_000); headers.close()
        val (line, t4) = answering(("HTTP/1.1 200 " + "x".repeat(20_000)).toByteArray())
        assertNull(client.exchange("http://127.0.0.1:${line.localPort}", byteArrayOf(1)))
        t4.join(2_000); line.close()
        // Only addresses: a name is never looked up, other schemes never used.
        for (base in listOf("http://example.org:80", "http://cafe:80", "https://127.0.0.1:1", "http://user@127.0.0.1:1", "http://127.0.0.1")) {
            assertNull(client.hello(base), base)
        }
    }
}
