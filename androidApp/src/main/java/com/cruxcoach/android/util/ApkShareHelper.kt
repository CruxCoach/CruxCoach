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
import java.net.InetAddress
import java.net.InetSocketAddress
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
            "kilter_board_import.sqlite3",
            LocalApkServer.SNAPSHOT_NAME
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
    private val boardDbFile: File? = null,
    /** Where the checkpointed board-DB snapshot is written (the app's
     *  cacheDir). null → the live file is served as-is. */
    private val snapshotDir: File? = null
) {

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var shutdownTimer: java.util.Timer? = null
    private val snapshotLock = Any()
    private var snapshotFile: File? = null
    var onAutoShutdown: (() -> Unit)? = null
    /** Set after start() — used to build deep link URLs in the landing page. */
    var baseUrl: String? = null
        private set

    fun start(port: Int = LOCAL_SHARE_PORT, hostIp: String? = null): Int {
        // Bind to a specific interface IP so the share server isn't reachable
        // on every network the device happens to be connected to (mobile data,
        // home WiFi, …). Default to loopback if the caller didn't pass an IP —
        // useless for LAN sharing, but prevents accidental exposure.
        val bindAddress: InetAddress = when {
            hostIp.isNullOrBlank() || hostIp == "0.0.0.0" || hostIp == "::" -> {
                InetAddress.getByName("127.0.0.1")
            }
            else -> InetAddress.getByName(hostIp)
        }
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, port))
            }
        } catch (_: Exception) {
            // Fallback: same bind address, random port if the fixed one is busy.
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, 0))
            }
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
        synchronized(snapshotLock) {
            snapshotFile?.delete()
            snapshotFile = null
        }
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
            """<section class="card">
  <span class="step">Step 2 · Schritt 2</span>
  <h2>Import the boulder database</h2>
  <p class="en">After installing, tap the button below — CruxCoach opens and imports the climbs automatically.</p>
  <p class="de">Nach der Installation auf den Button tippen — CruxCoach öffnet sich und importiert die Boulder automatisch.</p>
  <a href="$deepLink" class="btn success">&#128640; Open in CruxCoach &middot; In CruxCoach öffnen ($sizeMb MB)</a>
  <a href="/board.db" class="btn ghost">Download DB only &middot; Nur DB herunterladen</a>
</section>"""
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
        val live = boardDbFile
        if (live == null || !live.exists()) {
            serve404(out)
            return
        }
        val db = if (snapshotDir != null) boardDbSnapshot(live) else live
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/x-sqlite3\r\n" +
            "Content-Length: ${db.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"cruxcoach-board.db\"\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        db.inputStream().use { it.copyTo(out, bufferSize = 65536) }
    }

    /**
     * Snapshot the board DB before serving it. cruxcoach.db runs in WAL
     * mode: streaming the live file raw (a) silently drops whatever still
     * sits in the -wal file — the receiver misses the newest climbs — and
     * (b) races concurrent checkpoints: a page rewritten mid-transfer hands
     * the receiver a torn file that fails import with "database disk image
     * is malformed".
     *
     * wal_checkpoint(TRUNCATE) first folds the WAL into the main file;
     * BEGIN IMMEDIATE then holds the writer lock for the copy duration.
     * In WAL mode writers only append to the -wal and only checkpoints
     * touch the main file — with the writer lock held no transaction can
     * commit, so no checkpoint can start and the copied bytes are
     * guaranteed consistent.
     *
     * Best-effort: if the DB stays write-locked past the busy window
     * (e.g. a board sync is importing right now) we fall back to serving
     * the live file — the pre-0.2.1 behaviour, where a rare torn transfer
     * surfaces as a clear import error on the receiver and a retry fixes it.
     */
    private fun boardDbSnapshot(live: File): File = synchronized(snapshotLock) {
        snapshotFile?.takeIf { it.exists() }?.let { return it }
        val snap = File(snapshotDir, SNAPSHOT_NAME)
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                live.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                // PRAGMAs return a result row on Android — rawQuery, not execSQL.
                db.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.execSQL("BEGIN IMMEDIATE")
                try {
                    live.copyTo(snap, overwrite = true)
                } finally {
                    db.execSQL("ROLLBACK")
                }
            } finally {
                db.close()
            }
            snapshotFile = snap
            snap
        } catch (e: Exception) {
            Log.w("LocalApkServer", "board-DB snapshot failed — serving live file", e)
            snap.delete()
            live
        }
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
        /** Checkpointed board-DB copy in cacheDir; see [boardDbSnapshot]. */
        const val SNAPSHOT_NAME = "board_share_snapshot.db"

        private val LANDING_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>CruxCoach — Install</title>
<style>
  :root {
    --bg: #0f0f10;
    --card: #1c1c1e;
    --card-2: #26262a;
    --border: #2e2e33;
    --text: #f5f5f7;
    --muted: #9a9aa0;
    --muted-2: #6d6d73;
    --accent: #ff8c00;
    --accent-dark: #e07800;
    --success: #27ae60;
    --success-dark: #1e8449;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, Roboto, "Helvetica Neue", Arial, sans-serif;
    margin: 0;
    padding: 24px 16px 48px;
    line-height: 1.5;
    min-height: 100vh;
    -webkit-font-smoothing: antialiased;
  }
  .container { max-width: 440px; margin: 0 auto; }
  header { text-align: center; margin-bottom: 28px; }
  .logo {
    width: 64px; height: 64px;
    margin: 0 auto 12px;
    border-radius: 16px;
    background: linear-gradient(135deg, var(--accent) 0%, var(--accent-dark) 100%);
    display: flex; align-items: center; justify-content: center;
    font-size: 32px; font-weight: 800; color: #000;
    box-shadow: 0 8px 24px rgba(255, 140, 0, 0.25);
  }
  h1 { font-size: 28px; margin: 0 0 4px; letter-spacing: -0.5px; font-weight: 700; }
  header .tag { color: var(--muted); font-size: 14px; margin: 0; }
  .card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 20px;
    margin-bottom: 14px;
  }
  .step {
    display: inline-block;
    background: var(--card-2);
    color: var(--accent);
    font-weight: 700;
    font-size: 11px;
    padding: 4px 10px;
    border-radius: 999px;
    margin-bottom: 12px;
    letter-spacing: 0.8px;
    text-transform: uppercase;
  }
  .card h2 { font-size: 18px; margin: 0 0 8px; font-weight: 700; }
  .card p { color: var(--muted); font-size: 14px; margin: 0 0 6px; }
  .card p.de { color: var(--muted-2); font-size: 13px; font-style: italic; }
  .btn {
    display: block;
    text-align: center;
    text-decoration: none;
    font-weight: 700;
    font-size: 16px;
    padding: 14px 20px;
    border-radius: 12px;
    margin-top: 14px;
    transition: transform 0.05s ease, background 0.1s ease;
  }
  .btn:active { transform: scale(0.98); }
  .btn.primary { background: var(--accent); color: #000; }
  .btn.primary:active { background: var(--accent-dark); }
  .btn.success { background: var(--success); color: #fff; }
  .btn.success:active { background: var(--success-dark); }
  .btn.ghost {
    background: transparent;
    color: var(--muted);
    border: 1px solid var(--border);
    font-size: 14px;
    padding: 10px 16px;
    font-weight: 500;
    margin-top: 10px;
  }
  .btn.ghost:active { background: var(--card-2); color: var(--text); }
  footer {
    text-align: center;
    font-size: 12px;
    color: var(--muted-2);
    margin-top: 20px;
    line-height: 1.6;
  }
</style>
</head>
<body>
<div class="container">
  <header>
    <div class="logo">C</div>
    <h1>CruxCoach</h1>
    <p class="tag">Bouldering training app &middot; Trainings-App</p>
  </header>

  <section class="card">
    <span class="step">Step 1 &middot; Schritt 1</span>
    <h2>Install the app</h2>
    <p class="en">Tap download, then open the APK to install. You may need to allow "Install from unknown sources".</p>
    <p class="de">Auf Herunterladen tippen, dann die APK öffnen und installieren. Eventuell muss "Aus unbekannten Quellen installieren" erlaubt werden.</p>
    <a href="/CruxCoach.apk" class="btn primary">&#11015; Download APK &middot; APK herunterladen</a>
  </section>

  <!-- DB_SECTION -->

  <footer>Direct LAN transfer &middot; nothing leaves your network.<br>
  Direkte LAN-Übertragung &middot; verlässt dein Netzwerk nicht.</footer>
</div>
</body>
</html>
""".trimIndent()
    }
}
