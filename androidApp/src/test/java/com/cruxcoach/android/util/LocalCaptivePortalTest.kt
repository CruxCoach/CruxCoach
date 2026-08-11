package com.cruxcoach.android.util

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class LocalCaptivePortalTest {
    private var portal: LocalCaptivePortal? = null

    @AfterTest
    fun tearDown() {
        portal?.stop()
    }

    @Test
    fun httpProbeRedirectsToTheShareLandingPage() {
        portal = LocalCaptivePortal(
            hostIp = "127.0.0.1",
            landingUrl = "http://127.0.0.1:4949",
            httpPort = 0,
            dnsPort = 0,
        )
        val started = portal!!.start()

        assertTrue(started.automaticOpeningAvailable)
        val connection = URL(
            "http://127.0.0.1:${assertNotNull(started.boundHttpPort)}/generate_204",
        ).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        try {
            assertEquals(HttpURLConnection.HTTP_MOVED_TEMP, connection.responseCode)
            assertEquals("http://127.0.0.1:4949/", connection.getHeaderField("Location"))
            assertTrue(connection.getHeaderField("Cache-Control").contains("no-store"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun wildcardDnsMapsAnHttpProbeHostToTheSender() {
        portal = LocalCaptivePortal(
            hostIp = "127.0.0.1",
            landingUrl = "http://127.0.0.1:4949",
            httpPort = 0,
            dnsPort = 0,
        )
        val started = portal!!.start()
        val query = dnsQuery("connectivitycheck.gstatic.com", type = 1)

        val response = DatagramSocket().use { socket ->
            socket.soTimeout = 2_000
            socket.send(
                DatagramPacket(
                    query,
                    query.size,
                    InetAddress.getByName("127.0.0.1"),
                    assertNotNull(started.boundDnsPort),
                ),
            )
            val packet = DatagramPacket(ByteArray(512), 512)
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        }

        assertContentEquals(query.copyOfRange(0, 2), response.copyOfRange(0, 2))
        assertEquals(1, unsignedShort(response, 4))
        assertEquals(1, unsignedShort(response, 6))
        assertContentEquals(
            byteArrayOf(127, 0, 0, 1),
            response.copyOfRange(response.size - 4, response.size),
        )
    }

    @Test
    fun ipv6QuestionGetsAValidEmptyReplySoIpv4CanWin() {
        val query = dnsQuery("www.google.com", type = 28)

        val response = assertNotNull(
            LocalCaptivePortal.buildDnsResponse(query, byteArrayOf(192.toByte(), 168.toByte(), 49, 1)),
        )

        assertEquals(1, unsignedShort(response, 4))
        assertEquals(0, unsignedShort(response, 6))
        assertTrue(response[2].toInt() and 0x80 != 0)
    }

    @Test
    fun malformedOrCompressedQuestionIsIgnored() {
        val compressedQuestion = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0xc0.toByte(), 0x0c,
            0x00, 0x01, 0x00, 0x01,
        )

        assertEquals(
            null,
            LocalCaptivePortal.buildDnsResponse(
                compressedQuestion,
                byteArrayOf(127, 0, 0, 1),
            ),
        )
        assertFalse(
            LocalCaptivePortal.StartResult(boundHttpPort = 80, boundDnsPort = null)
                .automaticOpeningAvailable,
        )
    }

    @Test
    fun occupiedHttpPortDisablesAutomaticOpeningWithoutStartingDns() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            portal = LocalCaptivePortal(
                hostIp = "127.0.0.1",
                landingUrl = "http://127.0.0.1:4949",
                httpPort = occupied.localPort,
                dnsPort = 0,
            )

            val started = portal!!.start()

            assertFalse(started.automaticOpeningAvailable)
            assertEquals(null, started.boundHttpPort)
            assertEquals(null, started.boundDnsPort)
        }
    }

    private fun dnsQuery(host: String, type: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeShort(0x4a31)
                output.writeShort(0x0100)
                output.writeShort(1)
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(0)
                host.split('.').forEach { label ->
                    output.writeByte(label.length)
                    output.write(label.toByteArray(Charsets.US_ASCII))
                }
                output.writeByte(0)
                output.writeShort(type)
                output.writeShort(1)
            }
            bytes.toByteArray()
        }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
