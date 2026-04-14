package com.cruxcoach.android.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.content.FileProvider
import com.cruxcoach.android.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object ApkShareHelper {

    private const val TAG = "ApkShareHelper"
    private const val SHARE_APK_NAME = "CruxCoach.apk"

    fun shareViaIntent(context: Context) {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val shareApk = File(context.cacheDir, SHARE_APK_NAME)
        sourceApk.copyTo(shareApk, overwrite = true)

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", shareApk
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_app_chooser)))
    }

    /**
     * Delete stale temp files from previous sharing/import sessions.
     * Call on app start to prevent cache bloat.
     */
    fun cleanupCache(context: Context) {
        val staleFiles = listOf(
            SHARE_APK_NAME,
            "aurora_apk_download.zip",
            "aurora_apk_db.sqlite3",
            "kilter_board_import.sqlite3"
        )
        for (name in staleFiles) {
            val file = File(context.cacheDir, name)
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Cache cleanup: $name ${if (deleted) "deleted" else "failed"}")
            }
        }
    }

    fun getDeviceIpAddress(): String? {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                iface.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP", e)
        }
        return null
    }

    /**
     * Generate a QR code bitmap for arbitrary content (URL, text, etc.).
     */
    fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /**
     * Generate a WiFi QR code bitmap.
     * Format: WIFI:T:WPA;S:<ssid>;P:<password>;;
     */
    fun generateWifiQrBitmap(ssid: String, password: String, size: Int = 512): Bitmap {
        val escapedSsid = ssid.replace("\\", "\\\\").replace(";", "\\;")
        val escapedPass = password.replace("\\", "\\\\").replace(";", "\\;")
        return generateQrBitmap("WIFI:T:WPA;S:$escapedSsid;P:$escapedPass;;", size)
    }
}

/**
 * HTTP server that serves an HTML landing page, the APK, and optionally the
 * public board database (for offline sharing via WiFi Direct).
 *
 * **Security**: Only the public board DB (cruxcoach.db) is served — NEVER
 * the encrypted user DB (cruxcoach_secure.db) or any other user data.
 * The board DB contains only community climb data from Blossom/Kilter.
 */
class LocalApkServer(
    private val apkFile: File,
    private val boardDbFile: File? = null
) {

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var shutdownTimer: java.util.Timer? = null
    var onAutoShutdown: (() -> Unit)? = null
    /** Set after start() — used to build deep link URLs in the landing page. */
    var baseUrl: String? = null
        private set

    fun start(port: Int = LOCAL_SHARE_PORT, hostIp: String? = null): Int {
        val ss = try {
            ServerSocket(port)
        } catch (_: Exception) {
            ServerSocket(0) // fallback to random port if fixed port is busy
        }
        serverSocket = ss
        running = true
        val ip = hostIp ?: ApkShareHelper.getDeviceIpAddress() ?: "localhost"
        baseUrl = "http://$ip:${ss.localPort}"
        thread(isDaemon = true, name = "apk-server") {
            while (running) {
                try {
                    val client = ss.accept()
                    handleClient(client)
                } catch (_: Exception) {
                    break
                }
            }
        }
        scheduleAutoShutdown()
        return ss.localPort
    }

    fun stop() {
        running = false
        shutdownTimer?.cancel()
        shutdownTimer = null
        try { serverSocket?.close() } catch (_: Exception) { }
    }

    private fun scheduleAutoShutdown() {
        shutdownTimer = java.util.Timer("apk-server-timeout", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (running) {
                        Log.d("LocalApkServer", "Auto-shutdown after ${AUTO_SHUTDOWN_MS / 1000}s")
                        stop()
                        onAutoShutdown?.invoke()
                    }
                }
            }, AUTO_SHUTDOWN_MS)
        }
    }

    private fun handleClient(socket: Socket) {
        thread(isDaemon = true, name = "apk-client") {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: ""
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    line = reader.readLine()
                }

                val out = socket.getOutputStream()
                val path = requestLine.split(" ").getOrNull(1) ?: "/"

                when {
                    path.endsWith(".apk") -> serveApk(out)
                    path == "/board.db" -> serveBoardDb(out)
                    path == "/favicon.ico" -> serve404(out)
                    else -> serveLandingPage(out)
                }

                out.flush()
                socket.close()
            } catch (e: Exception) {
                Log.e("LocalApkServer", "Client error", e)
            }
        }
    }

    private fun serveLandingPage(out: java.io.OutputStream) {
        val dbSection = if (boardDbFile != null && boardDbFile.exists() && baseUrl != null) {
            val sizeMb = "%.1f".format(boardDbFile.length() / 1_048_576.0)
            val dbUrl = "$baseUrl/board.db"
            val deepLink = "cruxcoach://import-board-db?url=${java.net.URLEncoder.encode(dbUrl, "UTF-8")}"
            """<a href="$deepLink" class="btn import">&#128640; In CruxCoach importieren ($sizeMb MB)</a>
<p class="note">Klicke nach der Installation auf den Button oben — die App öffnet sich und importiert die Boulder-Datenbank automatisch.<br>
After installing, tap the button above — the app opens and imports the boulder database automatically.</p>
<a href="/board.db" class="btn db">&#128202; Nur DB herunterladen</a>"""
        } else ""
        val html = LANDING_HTML.replace("<!-- DB_SECTION -->", dbSection)
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        out.write(html.toByteArray())
    }

    private fun serveApk(out: java.io.OutputStream) {
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/vnd.android.package-archive\r\n" +
            "Content-Length: ${apkFile.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"CruxCoach.apk\"\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        apkFile.inputStream().use { it.copyTo(out, bufferSize = 65536) }
    }

    /**
     * Serves the public board database (community climb data only).
     * This file contains NO user data — only public Kilter climb/stats/placement data.
     */
    private fun serveBoardDb(out: java.io.OutputStream) {
        val db = boardDbFile
        if (db == null || !db.exists()) {
            serve404(out)
            return
        }
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/x-sqlite3\r\n" +
            "Content-Length: ${db.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"cruxcoach-board.db\"\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        db.inputStream().use { it.copyTo(out, bufferSize = 65536) }
    }

    private fun serve404(out: java.io.OutputStream) {
        val body = "404 Not Found"
        val headers = "HTTP/1.1 404 Not Found\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        out.write(body.toByteArray())
    }

    companion object {
        /** Fixed port for auto-discovery by receivers (WiFi Direct group owner = 192.168.49.1). */
        const val LOCAL_SHARE_PORT = 4949
        private const val AUTO_SHUTDOWN_MS = 5 * 60 * 1000L  // 5 min

        private val LANDING_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CruxCoach</title>
<style>
body { background:#1a1a1a; color:#fff; font-family:Roboto,Arial,sans-serif;
       margin:0; padding:24px; text-align:center; }
h1 { font-size:24px; margin-bottom:8px; }
p { color:#aaa; font-size:14px; margin-bottom:24px; }
a.btn { display:block; background:#FF8C00; color:#000; font-weight:700;
        font-size:18px; text-decoration:none; padding:16px 32px;
        border-radius:12px; margin:0 auto 16px; max-width:300px; }
a.btn:active { background:#E07800; }
a.btn.import { background:#27AE60; color:#fff; }
a.btn.import:active { background:#1E8449; }
a.btn.db { background:#555; color:#fff; font-size:14px; padding:10px 20px; }
a.btn.db:active { background:#444; }
.note { margin-top:16px; font-size:12px; color:#888; }
</style>
</head>
<body>
<h1>CruxCoach</h1>
<p>Bouldering Training App</p>
<a href="/CruxCoach.apk" class="btn">&#11015; Download CruxCoach</a>
<p class="note">Open the APK after download and install it. You may need to allow "Install from unknown sources".<br>
Nach dem Download die APK-Datei öffnen und installieren. Eventuell muss "Aus unbekannten Quellen installieren" erlaubt werden.</p>
<!-- DB_SECTION -->
</body>
</html>
""".trimIndent()
    }
}
