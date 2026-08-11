package com.cruxcoach.android.util

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Best-effort captive-portal companion for a local share session.
 *
 * A regular app cannot add Android's captive-portal DHCP option to a Wi-Fi
 * Direct group. The portable fallback is the classic detection path: answer
 * DNS queries with the group owner's address and redirect the resulting HTTP
 * probe to [landingUrl]. Both listeners bind only to [hostIp], so they are not
 * exposed on the sender's mobile/home network.
 *
 * Android/OEM networking services may already own ports 53 or 80, or the OS
 * may prohibit an app from binding a low port. Such failures are deliberately
 * non-fatal: the main share server keeps running and the sender UI presents
 * the landing-page QR as a fallback.
 */
class LocalCaptivePortal(
    private val hostIp: String,
    landingUrl: String,
    private val httpPort: Int = HTTP_PORT,
    private val dnsPort: Int = DNS_PORT,
) {
    data class StartResult(
        val boundHttpPort: Int?,
        val boundDnsPort: Int?,
    ) {
        val automaticOpeningAvailable: Boolean
            get() = boundHttpPort != null && boundDnsPort != null
    }

    private val bindAddress: Inet4Address = parsePrivateIpv4(hostIp)
    private val redirectUrl: String = normalizeLandingUrl(landingUrl, hostIp)

    @Volatile
    private var running = false
    private var httpSocket: ServerSocket? = null
    private var dnsSocket: DatagramSocket? = null

    @Synchronized
    fun start(): StartResult {
        check(!running && httpSocket == null && dnsSocket == null) {
            "Captive portal is already running"
        }

        val http = try {
            openHttpSocket()
        } catch (error: Exception) {
            Log.w(TAG, "HTTP portal listener unavailable on $hostIp:$httpPort", error)
            return StartResult(boundHttpPort = null, boundDnsPort = null)
        }

        httpSocket = http
        running = true
        startHttpLoop(http)

        val dns = try {
            openDnsSocket()
        } catch (error: Exception) {
            Log.w(TAG, "DNS portal listener unavailable on $hostIp:$dnsPort", error)
            null
        }
        dnsSocket = dns
        if (dns != null) startDnsLoop(dns)

        return StartResult(
            boundHttpPort = http.localPort,
            boundDnsPort = dns?.localPort,
        )
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { dnsSocket?.close() }
        runCatching { httpSocket?.close() }
        dnsSocket = null
        httpSocket = null
    }

    private fun startHttpLoop(socket: ServerSocket) {
        thread(isDaemon = true, name = "local-share-portal-http") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (error: Exception) {
                    if (running) Log.w(TAG, "HTTP portal listener stopped unexpectedly", error)
                    break
                }
                handleHttpClient(client)
            }
        }
    }

    private fun openHttpSocket(): ServerSocket {
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bindAddress, httpPort))
            socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun openDnsSocket(): DatagramSocket {
        val socket = DatagramSocket(null)
        return try {
            socket.bind(InetSocketAddress(bindAddress, dnsPort))
            socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun handleHttpClient(client: Socket) {
        runCatching {
            client.use { socket ->
                socket.soTimeout = CLIENT_TIMEOUT_MS
                val request = readHttpHeaders(socket)
                val isHead = request
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.startsWith("HEAD ", ignoreCase = true) == true
                val body = PORTAL_BODY.toByteArray(StandardCharsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 302 Found\r\n")
                    append("Location: $redirectUrl\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
                    append("Pragma: no-cache\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                socket.getOutputStream().apply {
                    write(headers.toByteArray(StandardCharsets.US_ASCII))
                    if (!isHead) write(body)
                    flush()
                }
            }
        }.onFailure { error ->
            if (running) Log.d(TAG, "Captive-portal HTTP probe ended early", error)
        }
    }

    private fun readHttpHeaders(socket: Socket): String? {
        val input = socket.getInputStream().buffered()
        val bytes = ByteArrayOutputStream()
        var terminatorState = 0
        repeat(MAX_HTTP_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return bytes.takeIf { it.size() > 0 }
                ?.toString(StandardCharsets.US_ASCII.name())
            bytes.write(value)
            terminatorState = when {
                terminatorState == 0 && value == '\r'.code -> 1
                terminatorState == 1 && value == '\n'.code -> 2
                terminatorState == 2 && value == '\r'.code -> 3
                terminatorState == 3 && value == '\n'.code -> {
                    return bytes.toString(StandardCharsets.US_ASCII.name())
                }
                value == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun startDnsLoop(socket: DatagramSocket) {
        thread(isDaemon = true, name = "local-share-portal-dns") {
            while (running) {
                val packet = DatagramPacket(ByteArray(MAX_DNS_PACKET_BYTES), MAX_DNS_PACKET_BYTES)
                try {
                    socket.receive(packet)
                    val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val response = buildDnsResponse(query, bindAddress.address) ?: continue
                    socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                } catch (error: Exception) {
                    if (running) Log.w(TAG, "DNS portal probe failed", error)
                    if (socket.isClosed) break
                }
            }
        }
    }

    companion object {
        private const val TAG = "LocalCaptivePortal"
        private const val HTTP_PORT = 80
        private const val DNS_PORT = 53
        private const val CLIENT_TIMEOUT_MS = 3_000
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_DNS_PACKET_BYTES = 4 * 1024
        private const val DNS_HEADER_BYTES = 12
        private const val DNS_TYPE_A = 1
        private const val DNS_TYPE_ANY = 255
        private const val DNS_CLASS_IN = 1
        private const val DNS_TTL_SECONDS = 30
        private const val PORTAL_BODY = """<!doctype html><meta charset="utf-8"><title>CruxCoach</title><a href="/">Open CruxCoach setup</a>"""

        /** Build a minimal, bounded DNS response for the first question. */
        internal fun buildDnsResponse(query: ByteArray, ipv4: ByteArray): ByteArray? {
            if (query.size < DNS_HEADER_BYTES || ipv4.size != 4) return null
            val flags = readUnsignedShort(query, 2)
            val questionCount = readUnsignedShort(query, 4)
            if (flags and 0x8000 != 0 || flags and 0x7800 != 0 || questionCount < 1) return null

            var cursor = DNS_HEADER_BYTES
            var encodedNameLength = 0
            while (true) {
                if (cursor >= query.size) return null
                val labelLength = query[cursor].toInt() and 0xff
                cursor++
                if (labelLength == 0) break
                if (labelLength > 63 || labelLength and 0xc0 != 0) return null
                if (cursor + labelLength > query.size) return null
                encodedNameLength += labelLength + 1
                if (encodedNameLength > 255) return null
                cursor += labelLength
            }
            if (cursor + 4 > query.size) return null
            val questionType = readUnsignedShort(query, cursor)
            val questionClass = readUnsignedShort(query, cursor + 2)
            cursor += 4
            val answersWithIpv4 = questionClass == DNS_CLASS_IN &&
                (questionType == DNS_TYPE_A || questionType == DNS_TYPE_ANY)

            return ByteArrayOutputStream(cursor + if (answersWithIpv4) 16 else 0).use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write(query, 0, 2) // transaction id
                    // Response + authoritative + recursion-desired echo + recursion available.
                    output.writeShort(0x8480 or (flags and 0x0100))
                    output.writeShort(1)
                    output.writeShort(if (answersWithIpv4) 1 else 0)
                    output.writeShort(0)
                    output.writeShort(0)
                    output.write(query, DNS_HEADER_BYTES, cursor - DNS_HEADER_BYTES)
                    if (answersWithIpv4) {
                        output.writeShort(0xc00c) // pointer to QNAME at byte 12
                        output.writeShort(DNS_TYPE_A)
                        output.writeShort(DNS_CLASS_IN)
                        output.writeInt(DNS_TTL_SECONDS)
                        output.writeShort(ipv4.size)
                        output.write(ipv4)
                    }
                }
                bytes.toByteArray()
            }
        }

        private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

        private fun parsePrivateIpv4(host: String): Inet4Address {
            val octets = host.split('.').takeIf { it.size == 4 }
                ?.map { it.toIntOrNull() }
                ?: throw IllegalArgumentException("Captive portal requires a private IPv4 address")
            require(octets.none { it == null || it !in 0..255 }) {
                "Captive portal requires a private IPv4 address"
            }
            val numbers = octets.map { checkNotNull(it) }
            val (first, second) = numbers
            require(
                first == 10 || first == 127 ||
                    (first == 192 && second == 168) ||
                    (first == 172 && second in 16..31),
            ) { "Captive portal must bind to a private IPv4 address" }
            val address = InetAddress.getByAddress(host, numbers.map { it.toByte() }.toByteArray())
            return address as Inet4Address
        }

        private fun normalizeLandingUrl(value: String, hostIp: String): String {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid captive-portal landing URL")
            require(
                uri.scheme.equals("http", ignoreCase = true) &&
                    uri.host == hostIp &&
                    uri.port in 1..65535 &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null &&
                    (uri.path.isNullOrEmpty() || uri.path == "/"),
            ) { "Captive-portal landing URL must be the local HTTP origin" }
            return "http://$hostIp:${uri.port}/"
        }
    }
}
