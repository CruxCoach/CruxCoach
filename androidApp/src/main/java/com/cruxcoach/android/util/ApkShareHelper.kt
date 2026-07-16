package com.cruxcoach.android.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.content.FileProvider
import com.cruxcoach.android.BuildConfig
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

    internal fun shareApkName(appDisplayName: String): String {
        val stem = appDisplayName
            .map {
                if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                    it == '.' || it == '_' || it == '-'
                ) {
                    it
                } else {
                    '_'
                }
            }
            .joinToString("")
            .trim('.', '_', '-')
            .take(48)
            .ifBlank { "app" }
        return "$stem.apk"
    }

    fun shareViaIntent(context: Context) {
        val sourceApk = File(context.applicationInfo.sourceDir)
        val shareApk = File(context.cacheDir, shareApkName(BuildConfig.APP_DISPLAY_NAME))
        sourceApk.copyTo(shareApk, overwrite = true)

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", shareApk
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                context.getString(
                    R.string.share_app_gpl_notice,
                    BuildConfig.VERSION_NAME,
                    versionSourceUrl(),
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_app_chooser)))
    }

    internal fun versionSourceUrl(
        apiBase: String = BuildConfig.UPDATER_API_BASE,
        owner: String = BuildConfig.UPDATER_REPO_OWNER,
        repository: String = BuildConfig.UPDATER_REPO_NAME,
        version: String = BuildConfig.VERSION_NAME,
    ): String {
        val forgeBase = apiBase.trimEnd('/').removeSuffix("/api/v1")
        return "$forgeBase/$owner/$repository/src/tag/v$version"
    }

    /**
     * Delete stale temp files from previous sharing/import sessions.
     * Call on app start to prevent cache bloat.
     */
    fun cleanupCache(context: Context) {
        val staleFiles = listOf(
            shareApkName(BuildConfig.APP_DISPLAY_NAME),
            // Older upstream builds used this fixed cache name.
            "CruxCoach.apk",
            "aurora_apk_download.zip",
            "aurora_apk_db.sqlite3",
            "kilter_board_import.sqlite3",
            LocalApkServer.SNAPSHOT_NAME,
            // Pair-copy leftovers from an interrupted snapshot (see
            // LocalApkServer.boardDbSnapshot).
            "${LocalApkServer.SNAPSHOT_NAME}-wal",
            "${LocalApkServer.SNAPSHOT_NAME}-shm",
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
 * Folds and scrubs a PRIVATE board-DB snapshot copy in place. Ours is the
 * only connection to [snapshot], so:
 *  1. `journal_mode=DELETE` forces a COMPLETE checkpoint of the copied
 *     -wal pair into the main file (cannot stay partial, unlike on the
 *     live DB) and drops the snapshot back to a single file;
 *  2. [LocalShareSchema.SNAPSHOT_SCRUB] removes the sender's private rows;
 *  3. VACUUM rewrites the file so the scrubbed rows are not recoverable
 *     from free pages.
 *
 * File-level + internal so the Robolectric share test can exercise it
 * directly against a seeded DB file.
 */
internal fun scrubAndCompactBoardDbSnapshot(snapshot: File) {
    val db = android.database.sqlite.SQLiteDatabase.openDatabase(
        snapshot.absolutePath, null,
        android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
    )
    try {
        // This file is a throwaway private copy, rebuilt from scratch for
        // every share session — durability against a crash is worthless, so
        // skip the fsync storm. This is what turns the VACUUM of an ~85 MB
        // multi-catalogue DB from minutes into seconds on phone flash.
        db.rawQuery("PRAGMA synchronous=OFF", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        for (statement in com.cruxcoach.android.data.LocalShareSchema.SNAPSHOT_SCRUB) {
            db.execSQL(statement)
        }
        db.execSQL("VACUUM")
    } finally {
        db.close()
    }
    File(snapshot.path + "-wal").delete()
    File(snapshot.path + "-shm").delete()
}

/**
 * HTTP server that serves an HTML landing page, the APK, and optionally the
 * public board database (for offline sharing via WiFi Direct).
 *
 * **Security**: Only the board DB (cruxcoach.db) is ever touched — NEVER
 * the encrypted user DB (cruxcoach_secure.db). What actually goes on the
 * wire is a checkpointed snapshot with the sender's private rows scrubbed
 * out ([scrubAndCompactBoardDbSnapshot]); if that snapshot cannot be
 * produced the request fails 503 rather than exposing the live file.
 */
class LocalApkServer(
    private val apkFile: File,
    private val boardDbFile: File? = null,
    /** Where the checkpointed board-DB snapshot is written (the app's
     *  cacheDir). null → the live file is served as-is. */
    private val snapshotDir: File? = null,
    private val clientReadTimeoutMs: Int = CLIENT_READ_TIMEOUT_MS,
    private val versionName: String = BuildConfig.VERSION_NAME,
    private val appDisplayName: String = BuildConfig.APP_DISPLAY_NAME,
    private val sourceCodeUrl: String = ApkShareHelper.versionSourceUrl(),
    private val licenseText: ByteArray? = null,
) {

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var shutdownTimer: java.util.Timer? = null
    private val activeClients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    /** Snapshot lifecycle. The build (copy + scrub + VACUUM of an ~85 MB DB)
     *  can take minutes on a phone, so /board.db must NEVER block on it —
     *  while BUILDING the request is answered 503 + Retry-After and the
     *  receiver polls. FAILED re-arms on the next request, so transient
     *  causes (a background sync holding the DB lock) heal themselves. */
    private enum class SnapState { IDLE, BUILDING, READY, FAILED }
    private val snapshotLock = Any()
    private var snapState = SnapState.IDLE
    private var snapshotFile: File? = null

    /** Wall-clock of the last handled HTTP request — the auto-shutdown
     *  timer re-arms while a receiver is actively talking to us (it polls
     *  every few seconds while the snapshot builds, then streams). */
    @Volatile private var lastActivityMs = System.currentTimeMillis()
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

        // Kick the scrubbed-snapshot build the moment the server starts, so
        // its (potentially minutes-long) VACUUM overlaps with the receiver
        // installing the APK / connecting instead of blocking the /board.db
        // request. If the receiver still arrives first, it gets 503 +
        // Retry-After and polls — see serveBoardDb().
        ensureSnapshotBuilding()
        return ss.localPort
    }

    fun stop() {
        running = false
        shutdownTimer?.cancel()
        shutdownTimer = null
        try { serverSocket?.close() } catch (_: Exception) { }
        activeClients.toList().forEach { client ->
            try { client.close() } catch (_: Exception) { }
        }
        synchronized(snapshotLock) {
            snapshotFile?.let { snap ->
                snap.delete()
                File(snap.path + "-wal").delete()
                File(snap.path + "-shm").delete()
            }
            snapshotFile = null
            snapState = SnapState.IDLE
        }
    }

    /**
     * Idle-based auto-shutdown: fires only after [AUTO_SHUTDOWN_MS] with NO
     * incoming request. A receiver polling for the snapshot (503 loop) or
     * streaming the DB keeps [lastActivityMs] fresh, so an active transfer
     * can never be cut mid-flight — the fixed 5-minute fuse could previously
     * kill the server while the receiver was still waiting on the snapshot.
     */
    @Synchronized
    private fun scheduleAutoShutdown(delayMs: Long = AUTO_SHUTDOWN_MS) {
        shutdownTimer?.cancel()
        shutdownTimer = java.util.Timer("apk-server-timeout", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (!running) return
                    val idleMs = System.currentTimeMillis() - lastActivityMs
                    if (idleMs < AUTO_SHUTDOWN_MS) {
                        // Someone talked to us since the fuse was lit — re-arm
                        // for the remaining idle window.
                        scheduleAutoShutdown(AUTO_SHUTDOWN_MS - idleMs)
                    } else {
                        Log.d("LocalApkServer", "Auto-shutdown after ${idleMs / 1000}s idle")
                        stop()
                        onAutoShutdown?.invoke()
                    }
                }
            }, delayMs)
        }
    }

    private fun handleClient(socket: Socket) {
        lastActivityMs = System.currentTimeMillis()
        activeClients += socket
        thread(isDaemon = true, name = "apk-client") {
            try {
                socket.soTimeout = clientReadTimeoutMs
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: ""
                var line = reader.readLine()
                var headerLines = 0
                while (!line.isNullOrBlank() && headerLines < MAX_HEADER_LINES) {
                    headerLines++
                    line = reader.readLine()
                }
                if (!line.isNullOrBlank()) throw java.io.IOException("too many request headers")

                val out = socket.getOutputStream()
                val path = requestLine.split(" ").getOrNull(1) ?: "/"

                when {
                    path.endsWith(".apk") -> serveApk(out)
                    path == "/board.db" -> serveBoardDb(out)
                    path == "/LICENSE" -> serveLicense(out)
                    path == "/favicon.ico" -> serve404(out)
                    else -> serveLandingPage(out)
                }

                out.flush()
            } catch (e: Exception) {
                Log.e("LocalApkServer", "Client error", e)
            } finally {
                activeClients -= socket
                try { socket.close() } catch (_: Exception) { }
            }
        }
    }

    internal val activeClientCountForTesting: Int get() = activeClients.size

    private fun serveLandingPage(out: java.io.OutputStream) {
        val escapedAppName = appDisplayName.escapeHtml()
        val dbSection = if (boardDbFile != null && boardDbFile.exists() && baseUrl != null) {
            val sizeMb = "%.1f".format(boardDbFile.length() / 1_048_576.0)
            val dbUrl = "$baseUrl/board.db"
            val deepLink = "${BuildConfig.APP_SCHEME}://import-board-db?url=" +
                java.net.URLEncoder.encode(dbUrl, "UTF-8")
            """<section class="card">
  <span class="step">Step 2 · Schritt 2</span>
  <h2>Import the boulder database</h2>
  <p class="en">After installing, tap the button below — $escapedAppName opens and imports the climbs automatically.</p>
  <p class="de">Nach der Installation auf den Button tippen — $escapedAppName öffnet sich und importiert die Boulder automatisch.</p>
  <a href="$deepLink" class="btn success">&#128640; Open in $escapedAppName &middot; In $escapedAppName öffnen ($sizeMb MB)</a>
  <a href="/board.db" class="btn ghost">Download DB only &middot; Nur DB herunterladen</a>
</section>"""
        } else ""
        val html = LANDING_HTML
            .replace("<!-- DB_SECTION -->", dbSection)
            .replace("<!-- APP_NAME -->", escapedAppName)
            .replace("<!-- APP_INITIAL -->", appDisplayName.trim().take(1).escapeHtml())
            .replace("<!-- VERSION -->", versionName.escapeHtml())
            .replace("<!-- SOURCE_URL -->", sourceCodeUrl.escapeHtml())
        val body = html.toByteArray(Charsets.UTF_8)
        out.write(responseHeaders(
            status = "200 OK",
            contentLength = body.size.toLong(),
            contentType = "text/html; charset=utf-8",
        ))
        out.write(body)
    }

    private fun serveApk(out: java.io.OutputStream) {
        out.write(responseHeaders(
            status = "200 OK",
            contentLength = apkFile.length(),
            contentType = "application/vnd.android.package-archive",
            contentDisposition =
                "attachment; filename=\"${ApkShareHelper.shareApkName(appDisplayName)}\"",
        ))
        apkFile.inputStream().use { it.copyTo(out, bufferSize = 65536) }
    }

    private fun serveLicense(out: java.io.OutputStream) {
        val body = licenseText ?: return serve404(out)
        out.write(
            responseHeaders(
                status = "200 OK",
                contentLength = body.size.toLong(),
                contentType = "text/plain; charset=utf-8",
                contentDisposition = "inline; filename=\"LICENSE\"",
            )
        )
        out.write(body)
    }

    /**
     * Serves the shareable board-database snapshot: catalogue + community
     * climbs, stats, geometry and gym locations. The sender's PRIVATE rows
     * — unpublished drafts (`source='local'`) and the Kilter publish-attempt
     * log — are scrubbed from the snapshot before a single byte leaves the
     * device (see [LocalShareSchema.SNAPSHOT_SCRUB]); the receiver's import
     * filters drafts again as defence in depth.
     *
     * Privacy over availability: if the snapshot (copy + scrub) cannot be
     * produced, we answer 503 instead of falling back to the raw live file
     * — the live file still contains the drafts.
     */
    private fun serveBoardDb(out: java.io.OutputStream) {
        val live = boardDbFile
        if (live == null || !live.exists()) {
            serve404(out)
            return
        }
        if (snapshotDir == null) {
            serve503(out)
            return
        }
        // NEVER block this request thread on the snapshot build (it can take
        // minutes) — that silent wait is exactly what ran the receiver into
        // its socket read-timeout. Answer immediately: 200 + stream when
        // READY, else (re)arm the build and tell the receiver to poll.
        val db = synchronized(snapshotLock) {
            if (snapState == SnapState.READY) snapshotFile?.takeIf { it.exists() } else null
        }
        if (db == null) {
            ensureSnapshotBuilding()
            serve503(out)
            return
        }
        out.write(responseHeaders(
            status = "200 OK",
            contentLength = db.length(),
            contentType = "application/x-sqlite3",
            contentDisposition = "attachment; filename=\"cruxcoach-board.db\"",
        ))
        db.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                // Keep the idle fuse fresh for the whole (multi-minute over
                // WiFi-Direct) transfer, not just its first byte.
                lastActivityMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * Arms a background snapshot build unless one is already running or done.
     * FAILED re-arms (each receiver poll retries), so transient causes — a
     * catalogue sync briefly holding the board-DB write lock — heal without
     * user interaction.
     */
    private fun ensureSnapshotBuilding() {
        val live = boardDbFile ?: return
        if (snapshotDir == null || !live.exists()) return
        synchronized(snapshotLock) {
            if (snapState == SnapState.BUILDING || snapState == SnapState.READY) return
            snapState = SnapState.BUILDING
        }
        thread(isDaemon = true, name = "apk-snapshot-build") {
            val startMs = System.currentTimeMillis()
            val snap = buildBoardDbSnapshot(live)
            synchronized(snapshotLock) {
                // stop() may have raced us back to IDLE and cleaned up — don't
                // resurrect state (or leave a stray file) for a dead server.
                if (snapState != SnapState.BUILDING) {
                    snap?.delete()
                    return@thread
                }
                if (snap != null) {
                    snapshotFile = snap
                    snapState = SnapState.READY
                } else {
                    snapState = SnapState.FAILED
                }
            }
            val secs = (System.currentTimeMillis() - startMs) / 1000
            if (snap != null) {
                Log.i("LocalApkServer", "board-DB snapshot ready in ${secs}s (${snap.length() / 1024 / 1024} MB)")
            } else {
                Log.w("LocalApkServer", "board-DB snapshot build failed after ${secs}s — will retry on next request")
            }
        }
    }

    /**
     * Snapshot the board DB before serving it. cruxcoach.db runs in WAL
     * mode: streaming the live file raw (a) silently drops whatever still
     * sits in the -wal file — the receiver misses the newest climbs — and
     * (b) races concurrent checkpoints: a page rewritten mid-transfer hands
     * the receiver a torn file that fails import with "database disk image
     * is malformed".
     *
     * Consistency does NOT rely on the live checkpoint succeeding (the
     * app's own connections can keep `wal_checkpoint(TRUNCATE)` partial —
     * its result is best-effort): under BEGIN IMMEDIATE (no writer can
     * commit, no checkpoint can move pages) we copy the main file AND the
     * -wal as a pair, then fold the pair on the PRIVATE copy — where ours
     * is the only connection, so that checkpoint provably completes.
     *
     * The same private pass runs [LocalShareSchema.SNAPSHOT_SCRUB] and
     * VACUUMs, so the served file is a single, complete, draft-free
     * SQLite database.
     *
     * @return the snapshot, or null when it could not be produced —
     *   the caller must fail the request rather than serve the live file.
     *
     * Runs on the [ensureSnapshotBuilding] worker thread — NOT under
     * [snapshotLock] (the build takes minutes; holding the lock would make
     * /board.db block instead of answering 503).
     */
    private fun buildBoardDbSnapshot(live: File): File? {
        val snap = File(snapshotDir, SNAPSHOT_NAME)
        val snapWal = File(snap.path + "-wal")
        val snapShm = File(snap.path + "-shm")
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                live.absolutePath, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                // PRAGMAs return a result row on Android — rawQuery, not execSQL.
                // 30s busy timeout: a catalogue sync's write bursts can hold the
                // lock well past the previous 5s, failing the whole build for a
                // transient reason.
                db.rawQuery("PRAGMA busy_timeout = 30000", null).use { it.moveToFirst() }
                // Best-effort pre-fold to keep the copied -wal small; the
                // pair-copy below is correct even when this stays partial.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                db.execSQL("BEGIN IMMEDIATE")
                try {
                    live.copyTo(snap, overwrite = true)
                    val liveWal = File(live.path + "-wal")
                    if (liveWal.exists()) liveWal.copyTo(snapWal, overwrite = true)
                    else snapWal.delete()
                    // Never copy the -shm: it's a volatile index for the
                    // LIVE wal; SQLite rebuilds it for the copied pair.
                    snapShm.delete()
                } finally {
                    db.execSQL("ROLLBACK")
                }
            } finally {
                db.close()
            }
            scrubAndCompactBoardDbSnapshot(snap)
            snap
        } catch (e: Exception) {
            Log.w("LocalApkServer", "board-DB snapshot failed — refusing to serve the live file", e)
            snap.delete()
            snapWal.delete()
            snapShm.delete()
            null
        }
    }

    private fun serve404(out: java.io.OutputStream) {
        val body = "404 Not Found".toByteArray(Charsets.UTF_8)
        out.write(responseHeaders("404 Not Found", body.size.toLong(), "text/plain; charset=utf-8"))
        out.write(body)
    }

    private fun serve503(out: java.io.OutputStream) {
        val body = "503 Snapshot unavailable — retry in a moment".toByteArray(Charsets.UTF_8)
        out.write(responseHeaders(
            status = "503 Service Unavailable",
            contentLength = body.size.toLong(),
            contentType = "text/plain; charset=utf-8",
            extraHeaders = listOf("Retry-After" to "5"),
        ))
        out.write(body)
    }

    private fun String.escapeHtml(): String = this
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * Every response from the cleartext-by-necessity LAN endpoint gets the
     * same browser hardening. Centralising the framing keeps future response
     * paths from accidentally omitting `nosniff` or framing/CSP policy.
     */
    private fun responseHeaders(
        status: String,
        contentLength: Long,
        contentType: String? = null,
        contentDisposition: String? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): ByteArray = buildString {
        append("HTTP/1.1 ").append(status).append("\r\n")
        if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
        if (contentDisposition != null) {
            append("Content-Disposition: ").append(contentDisposition).append("\r\n")
        }
        append(SECURITY_HEADERS)
        for ((name, value) in extraHeaders) {
            append(name).append(": ").append(value).append("\r\n")
        }
        append("Content-Length: ").append(contentLength).append("\r\n")
        append("Connection: close\r\n\r\n")
    }.toByteArray(Charsets.US_ASCII)

    companion object {
        /** Fixed port for auto-discovery by receivers (WiFi Direct group owner = 192.168.49.1). */
        const val LOCAL_SHARE_PORT = 4949
        private const val AUTO_SHUTDOWN_MS = 5 * 60 * 1000L  // 5 min
        private const val CLIENT_READ_TIMEOUT_MS = 10_000
        private const val MAX_HEADER_LINES = 100
        /** Checkpointed board-DB copy in cacheDir; see [boardDbSnapshot]. */
        const val SNAPSHOT_NAME = "board_share_snapshot.db"
        private const val SECURITY_HEADERS =
            "X-Content-Type-Options: nosniff\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "X-Frame-Options: DENY\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; " +
                "img-src 'self' data:; base-uri 'none'; form-action 'none'; " +
                "frame-ancestors 'none'\r\n"

        private val LANDING_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title><!-- APP_NAME --> — Install</title>
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
    <div class="logo"><!-- APP_INITIAL --></div>
    <h1><!-- APP_NAME --></h1>
    <p class="tag">Bouldering training app &middot; Trainings-App</p>
  </header>

  <section class="card">
    <span class="step">Step 1 &middot; Schritt 1</span>
    <h2>Install the app</h2>
    <p class="en">Tap download, then open the APK to install. You may need to allow "Install from unknown sources".</p>
    <p class="de">Auf Herunterladen tippen, dann die APK öffnen und installieren. Eventuell muss "Aus unbekannten Quellen installieren" erlaubt werden.</p>
    <a href="/app.apk" class="btn primary">&#11015; Download APK &middot; APK herunterladen</a>
  </section>

  <!-- DB_SECTION -->

  <footer>Direct LAN transfer &middot; nothing leaves your network.<br>
  Direkte LAN-Übertragung &middot; verlässt dein Netzwerk nicht.<br><br>
  <!-- APP_NAME --> v<!-- VERSION --> &middot; free software under the
  <a href="/LICENSE">GNU GPL v3</a> &middot; NO WARRANTY.<br>
  Source code for this exact build &middot; Quellcode dieses Builds:<br>
  <a href="<!-- SOURCE_URL -->"><!-- SOURCE_URL --></a></footer>
</div>
</body>
</html>
""".trimIndent()
    }
}
