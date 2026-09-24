package com.cruxcoach.android.sharing.p2p

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The two HTTP routes of the direct channel. Shared by the standalone
 * [ShareHttpServer] and the offline-share server (`LocalApkServer`), so a
 * hotspot share session carries the channel on its existing port as well.
 * Authentication is the channel itself (MACs and Olm), not HTTP.
 */
object ShareHttpRoutes {
    const val HELLO = "/share/v1/hello"
    const val EXCHANGE = "/share/v1/exchange"
    const val MAX_BODY = 360 * 1024

    class Reply(val status: Int, val reason: String, val type: String, val body: ByteArray)

    fun handles(path: String) = path == HELLO || path == EXCHANGE

    /** Read a POST body announced by Content-Length, never more than [MAX_BODY]. */
    fun body(input: InputStream, headers: Map<String, String>): ByteArray? {
        val length = headers["content-length"]?.toIntOrNull() ?: return null
        if (length !in 1..MAX_BODY) return null
        val body = input.readNBytes(length)
        return body.takeIf { it.size == length }
    }

    fun reply(method: String, path: String, body: ByteArray?, server: ShareExchangeServer?, maxBytes: Int): Reply {
        if (server == null) return Reply(503, "Service Unavailable", "text/plain", ByteArray(0))
        return when {
            method == "GET" && path == HELLO ->
                server.hello()?.let { Reply(200, "OK", "application/json", it.toJson().toByteArray()) }
                    ?: Reply(503, "Service Unavailable", "text/plain", ByteArray(0))
            method == "POST" && path == EXCHANGE && body != null ->
                server.exchange(body, maxBytes)?.let { Reply(200, "OK", "application/octet-stream", it) }
                    ?: Reply(400, "Bad Request", "text/plain", ByteArray(0))
            handles(path) -> Reply(405, "Method Not Allowed", "text/plain", ByteArray(0))
            else -> Reply(404, "Not Found", "text/plain", ByteArray(0))
        }
    }

    fun write(out: OutputStream, reply: Reply) {
        val head = "HTTP/1.1 ${reply.status} ${reply.reason}\r\n" +
            "Content-Type: ${reply.type}\r\n" +
            "Content-Length: ${reply.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(reply.body)
        out.flush()
    }
}

/**
 * A small listener for the direct channel while sharing is online and no
 * offline-share session runs. Bounded like `LocalApkServer`: request line,
 * header count and size, body size, concurrent clients and read timeouts.
 */
class ShareHttpServer(private val bind: InetAddress, private val port: Int = 0) {
    private var socket: ServerSocket? = null
    @Volatile private var running = false
    private val clients = AtomicInteger(0)

    val localPort: Int get() = socket?.localPort ?: 0

    fun start(server: ShareExchangeServer, maxBytes: Int): Int {
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bind, port))
        }
        socket = ss
        running = true
        thread(isDaemon = true, name = "share-http") {
            while (running) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                if (clients.incrementAndGet() > MAX_CLIENTS) {
                    clients.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                thread(isDaemon = true, name = "share-http-client") {
                    try {
                        serve(client, server, maxBytes)
                    } finally {
                        clients.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }
        }
        return ss.localPort
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(client: Socket, server: ShareExchangeServer, maxBytes: Int) = runCatching {
        client.soTimeout = READ_TIMEOUT_MS
        val input = client.getInputStream()
        var headBytes = 0
        fun line(): String? {
            val l = readLine(input) ?: return null
            headBytes += l.length + 2
            if (headBytes > MAX_HEAD_BYTES) throw IOException("head")
            return l
        }
        val parts = (line() ?: return@runCatching).split(" ")
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val headers = mutableMapOf<String, String>()
        var next = line()
        while (!next.isNullOrEmpty()) {
            if (headers.size >= MAX_HEADERS) throw IOException("headers")
            val colon = next.indexOf(':')
            if (colon > 0) headers[next.substring(0, colon).trim().lowercase()] = next.substring(colon + 1).trim()
            next = line()
        }
        val body = if (method == "POST") ShareHttpRoutes.body(input, headers) else null
        ShareHttpRoutes.write(client.getOutputStream(), ShareHttpRoutes.reply(method, path, body, server, maxBytes))
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            if (b == '\n'.code) return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            if (bytes.size() >= MAX_LINE_BYTES) throw IOException("line")
            bytes.write(b)
        }
    }

    companion object {
        const val MAX_CLIENTS = 8
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADERS = 32
        const val MAX_HEAD_BYTES = 16 * 1024
    }
}

/** Client side of the two routes. On Android [open] binds to the Wi-Fi network. */
class ShareHttpClient(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun hello(base: String): ShareHello? = runCatching {
        val connection = open(URL(base.trimEnd('/') + ShareHttpRoutes.HELLO))
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode != 200) return@runCatching null
            ShareHello.parse(connection.inputStream.use { it.readNBytes(1024) }.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun exchange(base: String, request: ByteArray): ByteArray? = runCatching {
        val connection = open(URL(base.trimEnd('/') + ShareHttpRoutes.EXCHANGE))
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(request) }
            if (connection.responseCode != 200) return@runCatching null
            val length = connection.contentLengthLong
            if (length !in 1..ShareHttpRoutes.MAX_BODY.toLong()) return@runCatching null
            connection.inputStream.use { it.readNBytes(length.toInt()) }.takeIf { it.size.toLong() == length }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

/** Where LAN endpoints come from: NSD on Android, a fixed list in tests. */
interface LanDiscovery {
    fun start(port: Int)
    fun stop()
    fun urls(): List<String>
}

/** A fixed or externally updated endpoint list (tests, hotspot share sessions). */
class StaticLanDiscovery(initial: List<String> = emptyList()) : LanDiscovery {
    @Volatile var endpoints: List<String> = initial
    override fun start(port: Int) = Unit
    override fun stop() = Unit
    override fun urls(): List<String> = endpoints
}

/**
 * LAN HTTP: same Wi-Fi, a hotspot or a Wi-Fi Direct group. Large frames
 * (initial full pages) travel here; BLE carries only small ones.
 */
class LanShareTransport(
    private val bindAddress: () -> InetAddress?,
    private val discovery: LanDiscovery,
    private val client: ShareHttpClient = ShareHttpClient(),
    override val maxContainer: Int = MAX_FRAME_BUDGET,
) : ShareTransport {
    override val name = "lan"
    private var server: ShareHttpServer? = null
    override val running: Boolean get() = server != null

    val port: Int get() = server?.localPort ?: 0

    @Synchronized override fun start(server: ShareExchangeServer) {
        if (this.server != null) return
        val bind = bindAddress() ?: return
        val http = ShareHttpServer(bind)
        val port = http.start(server, maxContainer)
        this.server = http
        discovery.start(port)
    }

    @Synchronized override fun stop() {
        discovery.stop()
        server?.stop()
        server = null
    }

    override fun endpoints(): List<ShareEndpoint> =
        discovery.urls().distinct().take(MAX_ENDPOINTS).map { url -> ShareEndpoint(name, url, client.hello(url)) }

    override fun exchange(endpoint: ShareEndpoint, request: ByteArray): ByteArray? = client.exchange(endpoint.address, request)

    companion object {
        /** Mirrors the native default frame budget of one step (192 KiB). */
        const val MAX_FRAME_BUDGET = 196_608
        const val MAX_ENDPOINTS = 16
    }
}
