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
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
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
     *  cacheDir). null disables DB sharing; the live file is never served. */
    private val snapshotDir: File? = null,
    private val apkVersionCode: Long = 1L,
    private val apkVersionName: String = "unknown",
    /** Session-specific deep link attempted by a direct landing-page visit. */
    private val openAppUri: String? = null,
    /** Fixed lifetime of a share. Requests must never extend this deadline. */
    private val autoShutdownMs: Long = AUTO_SHUTDOWN_MS,
) {

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var shutdownTimer: java.util.Timer? = null

    /** Snapshot lifecycle. The build (copy + scrub + VACUUM of an ~85 MB DB)
     *  can take minutes on a phone, so /board.db must NEVER block on it —
     *  while BUILDING the request is answered 503 + Retry-After and the
     *  receiver polls. FAILED re-arms on the next request, so transient
     *  causes (a background sync holding the DB lock) heal themselves. */
    private enum class SnapState { IDLE, BUILDING, READY, FAILED }
    private val snapshotLock = Any()
    private var snapState = SnapState.IDLE
    private var snapshotFile: File? = null
    private var compressedSnapshotFile: File? = null
    private var snapshotMetadata: SnapshotMetadata? = null

    private data class SnapshotMetadata(
        val compressedSha256: String,
        val uncompressedSha256: String,
        val uncompressedSizeBytes: Long,
        val schemaVersion: Int,
        val catalogues: List<LocalShareProtocol.BoardCatalogue>,
    )
    private data class SourceFingerprint(
        val mainLength: Long,
        val mainModified: Long,
        val walLength: Long,
        val walModified: Long,
    )
    private data class SnapshotView(
        val state: SnapState,
        val compressed: File?,
        val raw: File?,
        val metadata: SnapshotMetadata?,
    )
    private data class BuiltSnapshot(
        val raw: File,
        val compressed: File,
        val metadata: SnapshotMetadata,
        val sourceFingerprint: SourceFingerprint,
    )

    private val sessionId = UUID.randomUUID().toString()
    private val apkSha256: String by lazy { LocalShareProtocol.sha256(apkFile) }
    private val activeTransfers = AtomicInteger(0)

    var onAutoShutdown: (() -> Unit)? = null
    var onReceiverComplete: (() -> Unit)? = null
    /** Fires once before this session starts snapshot work or a large file. */
    var onBulkTransferStarted: (() -> Unit)? = null
    private val bulkTransferAnnounced = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Set after start() — used to build deep link URLs in the landing page. */
    var baseUrl: String? = null
        private set

    fun start(port: Int = LOCAL_SHARE_PORT, hostIp: String? = null): Int {
        restoreCachedSnapshot()
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
        // The onboarding auto-discovery contract relies on port 4949. A
        // random-port fallback would make the sender UI look healthy while a
        // newly installed receiver can never find it, so fail visibly when
        // the fixed port is genuinely occupied. Tests may still request 0.
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port))
        }
        serverSocket = ss
        running = true
        val ip = hostIp ?: ApkShareHelper.getDeviceIpAddress() ?: "localhost"
        baseUrl = "http://$ip:${ss.localPort}"
        thread(isDaemon = true, name = "apk-server") {
            while (running) {
                try {
                    val client = ss.accept().apply {
                        // Keep the radio fed across short flash-read stalls.
                        sendBufferSize = SOCKET_BUFFER_SIZE
                        receiveBufferSize = SOCKET_BUFFER_SIZE
                        tcpNoDelay = true
                    }
                    handleClient(client)
                } catch (_: Exception) {
                    break
                }
            }
        }
        scheduleAutoShutdown(autoShutdownMs)

        // Do not start the potentially minutes-long copy/VACUUM/gzip pass yet.
        // A fresh-install receiver needs the APK first, and doing both against
        // the same phone storage made that download dramatically slower and
        // more likely to outlive an ordinary client's local-only Wi-Fi stay.
        // A board request arms the snapshot explicitly; a browser APK download
        // arms it after the last APK byte has been sent.
        return ss.localPort
    }

    fun stop() {
        running = false
        shutdownTimer?.cancel()
        shutdownTimer = null
        try { serverSocket?.close() } catch (_: Exception) { }
        synchronized(snapshotLock) {
            snapshotFile?.let { snap ->
                snap.delete()
                File(snap.path + "-wal").delete()
                File(snap.path + "-shm").delete()
            }
            snapshotFile = null
            compressedSnapshotFile = null
            snapshotMetadata = null
            snapState = SnapState.IDLE
        }
    }

    /**
     * Hard session deadline. Landing-page refreshes, browser probes and a
     * receiver polling the manifest must not leave the hotspot reachable
     * indefinitely. If an APK/DB file is actively streaming at the deadline,
     * let that transfer finish and recheck shortly; ordinary HTTP requests do
     * not extend the session.
     */
    @Synchronized
    private fun scheduleAutoShutdown(delayMs: Long) {
        shutdownTimer?.cancel()
        shutdownTimer = java.util.Timer("apk-server-timeout", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (!running) return
                    if (activeTransfers.get() > 0) {
                        scheduleAutoShutdown(ACTIVE_TRANSFER_RECHECK_MS)
                    } else {
                        Log.d("LocalApkServer", "Auto-shutdown at fixed session deadline")
                        stop()
                        onAutoShutdown?.invoke()
                    }
                }
            }, delayMs)
        }
    }

    private fun handleClient(socket: Socket) {
        thread(isDaemon = true, name = "apk-client") {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: ""
                val requestParts = requestLine.split(" ")
                val method = requestParts.getOrNull(0)?.uppercase().orEmpty()
                val rawPath = requestParts.getOrNull(1) ?: "/"
                val headers = mutableMapOf<String, String>()
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                    line = reader.readLine()
                }

                val out = BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE)
                val path = rawPath.substringBefore('?')
                val headOnly = method == "HEAD"
                var receiverCompleted = false

                when {
                    method == "POST" && path == LocalShareProtocol.COMPLETE_PATH -> {
                        serveNoContent(out)
                        receiverCompleted = true
                    }
                    method != "GET" && method != "HEAD" -> serveMethodNotAllowed(out)
                    path == LocalShareProtocol.MANIFEST_PATH ||
                        path == "/.well-known/cruxcoach-share" -> serveManifest(out, headOnly)
                    path == LocalShareProtocol.APK_PATH -> serveApk(out, headers["range"], headOnly)
                    path == LocalShareProtocol.BOARD_PATH ->
                        serveBoardDb(out, compressed = true, rangeHeader = headers["range"], headOnly = headOnly)
                    path == "/board.db" ->
                        serveBoardDb(out, compressed = false, rangeHeader = headers["range"], headOnly = headOnly)
                    path == "/favicon.ico" -> serve404(out)
                    else -> serveLandingPage(out)
                }

                out.flush()
                socket.close()
                if (receiverCompleted) onReceiverComplete?.invoke()
            } catch (e: Exception) {
                Log.e("LocalApkServer", "Client error", e)
            }
        }
    }

    private fun serveManifest(out: java.io.OutputStream, headOnly: Boolean) {
        // Reading the manifest must stay cheap: the receiver uses it to decide
        // whether an APK is needed. Snapshot work is armed explicitly by a
        // board request, or after the APK's final byte, so APK always wins.
        // Hashing the APK is intentionally lazy but deterministic. The first
        // manifest request pays this one-time read while the board snapshot is
        // normally still being prepared in parallel.
        val view = synchronized(snapshotLock) {
            SnapshotView(snapState, compressedSnapshotFile, snapshotFile, snapshotMetadata)
        }
        val boardJson = org.json.JSONObject().put(
            "status",
            when {
                boardDbFile == null || !boardDbFile.exists() -> "unavailable"
                else -> if (view.state == SnapState.READY) "ready" else "preparing"
            },
        )
        if (view.state == SnapState.READY &&
            view.compressed != null && view.metadata != null
        ) {
            boardJson
                .put("path", LocalShareProtocol.BOARD_PATH)
                .put("compression", "gzip")
                .put("sizeBytes", view.compressed.length())
                .put("sha256", view.metadata.compressedSha256)
                .put("uncompressedSizeBytes", view.metadata.uncompressedSizeBytes)
                .put("uncompressedSha256", view.metadata.uncompressedSha256)
                .put("schemaVersion", view.metadata.schemaVersion)
                .put(
                    "catalogues",
                    org.json.JSONArray().apply {
                        view.metadata.catalogues.forEach { catalogue ->
                            put(
                                org.json.JSONObject()
                                    .put("boardBrand", catalogue.boardBrand)
                                    .put("climbCount", catalogue.climbCount),
                            )
                        }
                    },
                )
        }
        val json = org.json.JSONObject()
            .put("protocolVersion", LocalShareProtocol.VERSION)
            .put("sessionId", sessionId)
            .put("idleTimeoutSeconds", AUTO_SHUTDOWN_MS / 1000L)
            .put(
                "apk",
                org.json.JSONObject()
                    .put("path", LocalShareProtocol.APK_PATH)
                    .put("versionCode", apkVersionCode)
                    .put("versionName", apkVersionName)
                    .put("sizeBytes", apkFile.length())
                    .put("sha256", apkSha256),
            )
            .put("board", boardJson)
            .toString()
        serveBytes(out, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8", headOnly)
    }

    private fun serveLandingPage(out: java.io.OutputStream) {
        val apkSizeMb = "%.1f".format(java.util.Locale.US, apkFile.length() / 1_048_576.0)
        val html = LANDING_HTML
            .replace("{{VERSION_NAME}}", escapeHtml(apkVersionName))
            .replace("{{APK_SIZE_MB}}", apkSizeMb)
            .replace("{{OPEN_APP_URI_HTML}}", escapeHtml(openAppUri.orEmpty()))
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${html.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        out.write(html.toByteArray())
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun serveApk(out: java.io.OutputStream, rangeHeader: String?, headOnly: Boolean) {
        serveFile(
            out = out,
            file = apkFile,
            contentType = "application/vnd.android.package-archive",
            downloadName = "CruxCoach.apk",
            rangeHeader = rangeHeader,
            headOnly = headOnly,
        )
        if (!headOnly) ensureSnapshotBuilding()
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
    private fun serveBoardDb(
        out: java.io.OutputStream,
        compressed: Boolean,
        rangeHeader: String?,
        headOnly: Boolean,
    ) {
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
            if (snapState == SnapState.READY) {
                (if (compressed) compressedSnapshotFile else snapshotFile)?.takeIf { it.exists() }
            } else {
                null
            }
        }
        if (db == null) {
            // Only the gzip endpoint is part of the advertised v1 protocol.
            // A restored cross-session cache deliberately retains the compact
            // gzip file, not another 500+ MB raw copy.
            if (!compressed && synchronized(snapshotLock) {
                    snapState == SnapState.READY && compressedSnapshotFile?.exists() == true
                }
            ) {
                serve404(out)
                return
            }
            ensureSnapshotBuilding()
            serve503(out)
            return
        }
        serveFile(
            out = out,
            file = db,
            contentType = if (compressed) "application/gzip" else "application/x-sqlite3",
            downloadName = if (compressed) "cruxcoach-board.db.gz" else "cruxcoach-board.db",
            rangeHeader = rangeHeader,
            headOnly = headOnly,
        )
    }

    /** Single-range HTTP serving for resumable APK and board downloads. */
    private fun serveFile(
        out: java.io.OutputStream,
        file: File,
        contentType: String,
        downloadName: String,
        rangeHeader: String?,
        headOnly: Boolean,
    ) {
        val total = file.length()
        val start = parseRangeStart(rangeHeader, total)
        if (start == null && rangeHeader != null) {
            val headers = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                "Content-Range: bytes */$total\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(headers.toByteArray())
            return
        }
        val offset = start ?: 0L
        val remaining = total - offset
        val partial = rangeHeader != null
        val headers = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $remaining\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) append("Content-Range: bytes $offset-${total - 1}/$total\r\n")
            append("Content-Disposition: attachment; filename=\"$downloadName\"\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())
        if (headOnly) return

        activeTransfers.incrementAndGet()
        announceBulkWork()
        try {
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                var left = remaining
                while (left > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    left -= read
                }
            }
            out.flush()
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    private fun parseRangeStart(header: String?, total: Long): Long? {
        if (header == null) return 0L
        val match = Regex("^bytes=(\\d+)-$").matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        return start.takeIf { it in 0 until total }
    }

    private fun serveBytes(
        out: java.io.OutputStream,
        bytes: ByteArray,
        contentType: String,
        headOnly: Boolean,
    ) {
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        if (!headOnly) out.write(bytes)
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
        // A receiver that already has this APK can arm /board.db directly;
        // don't wait for a file stream before giving SQLite exclusive CPU.
        announceBulkWork()
        thread(isDaemon = true, name = "apk-snapshot-build") {
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
            val startMs = System.currentTimeMillis()
            val built = buildBoardDbSnapshot(live)
            synchronized(snapshotLock) {
                // stop() may have raced us back to IDLE and cleaned up — don't
                // resurrect state (or leave a stray file) for a dead server.
                if (snapState != SnapState.BUILDING) {
                    built?.raw?.delete()
                    built?.compressed?.delete()
                    return@thread
                }
                if (built != null) {
                    snapshotFile = built.raw
                    compressedSnapshotFile = built.compressed
                    snapshotMetadata = built.metadata
                    snapState = SnapState.READY
                    persistSnapshotCache(built.metadata, built.sourceFingerprint)
                } else {
                    snapState = SnapState.FAILED
                }
            }
            val secs = (System.currentTimeMillis() - startMs) / 1000
            if (built != null) {
                Log.i(
                    "LocalApkServer",
                    "board-DB snapshot ready in ${secs}s " +
                        "(${built.raw.length() / 1024 / 1024} MB raw, " +
                        "${built.compressed.length() / 1024 / 1024} MB gzip)",
                )
            } else {
                Log.w("LocalApkServer", "board-DB snapshot build failed after ${secs}s — will retry on next request")
            }
        }
    }

    private fun announceBulkWork() {
        if (bulkTransferAnnounced.compareAndSet(false, true)) {
            onBulkTransferStarted?.invoke()
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
    private fun buildBoardDbSnapshot(live: File): BuiltSnapshot? {
        val snap = File(snapshotDir, SNAPSHOT_NAME)
        val compressed = File(snapshotDir, COMPRESSED_SNAPSHOT_NAME)
        val snapWal = File(snap.path + "-wal")
        val snapShm = File(snap.path + "-shm")
        return try {
            var copiedFingerprint: SourceFingerprint? = null
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
                    copiedFingerprint = sourceFingerprint(live)
                } finally {
                    db.execSQL("ROLLBACK")
                }
            } finally {
                db.close()
            }
            scrubAndCompactBoardDbSnapshot(snap)
            // Compression happens only after SQLite has folded the copied WAL,
            // scrubbed private rows and VACUUMed the result. Therefore both
            // hashes describe one immutable, internally consistent snapshot.
            ShareCompression.gzip(snap, compressed)
            val schemaVersion = android.database.sqlite.SQLiteDatabase.openDatabase(
                snap.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { snapshotDb ->
                snapshotDb.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
            }
            val catalogues = android.database.sqlite.SQLiteDatabase.openDatabase(
                snap.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { snapshotDb ->
                snapshotDb.rawQuery(
                    """
                    SELECT COALESCE(board_brand, 'kilter'), COUNT(*)
                    FROM climbs
                    WHERE is_listed = 1
                    GROUP BY COALESCE(board_brand, 'kilter')
                    ORDER BY COALESCE(board_brand, 'kilter')
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                LocalShareProtocol.BoardCatalogue(
                                    boardBrand = cursor.getString(0),
                                    climbCount = cursor.getLong(1),
                                ),
                            )
                        }
                    }
                }
            }
            BuiltSnapshot(
                raw = snap,
                compressed = compressed,
                metadata = SnapshotMetadata(
                    compressedSha256 = LocalShareProtocol.sha256(compressed),
                    uncompressedSha256 = LocalShareProtocol.sha256(snap),
                    uncompressedSizeBytes = snap.length(),
                    schemaVersion = schemaVersion,
                    catalogues = catalogues,
                ),
                sourceFingerprint = checkNotNull(copiedFingerprint),
            )
        } catch (e: Exception) {
            Log.w("LocalApkServer", "board-DB snapshot failed — refusing to serve the live file", e)
            snap.delete()
            snapWal.delete()
            snapShm.delete()
            compressed.delete()
            null
        }
    }

    /**
     * Reuses the expensive scrub/VACUUM/gzip result across share sessions.
     * The cache is accepted only when every live SQLite file attribute still
     * matches the point at which the consistent main/WAL pair was copied.
     */
    private fun restoreCachedSnapshot() {
        val live = boardDbFile ?: return
        val dir = snapshotDir ?: return
        if (!live.exists()) return
        val compressed = File(dir, COMPRESSED_SNAPSHOT_NAME)
        val metadataFile = File(dir, SNAPSHOT_METADATA_NAME)
        if (!compressed.exists()) return
        if (!metadataFile.exists()) {
            val raw = File(dir, SNAPSHOT_NAME)
            if (adoptLegacySnapshot(live, raw, compressed)) {
                restoreCachedSnapshot()
            }
            return
        }
        val restored = runCatching {
            val json = org.json.JSONObject(metadataFile.readText())
            val source = json.getJSONObject("source")
            val expected = SourceFingerprint(
                mainLength = source.getLong("mainLength"),
                mainModified = source.getLong("mainModified"),
                walLength = source.getLong("walLength"),
                walModified = source.getLong("walModified"),
            )
            check(sourceFingerprint(live) == expected) { "board database changed" }
            check(json.getLong("compressedSizeBytes") == compressed.length()) {
                "compressed snapshot size changed"
            }
            val cataloguesJson = json.getJSONArray("catalogues")
            val catalogues = buildList {
                for (index in 0 until cataloguesJson.length()) {
                    val item = cataloguesJson.getJSONObject(index)
                    add(
                        LocalShareProtocol.BoardCatalogue(
                            boardBrand = item.getString("boardBrand"),
                            climbCount = item.getLong("climbCount"),
                        ),
                    )
                }
            }
            SnapshotMetadata(
                compressedSha256 = json.getString("compressedSha256"),
                uncompressedSha256 = json.getString("uncompressedSha256"),
                uncompressedSizeBytes = json.getLong("uncompressedSizeBytes"),
                schemaVersion = json.getInt("schemaVersion"),
                catalogues = catalogues,
            )
        }.getOrElse { error ->
            Log.i("LocalApkServer", "Discarding stale snapshot cache: ${error.message}")
            compressed.delete()
            metadataFile.delete()
            return
        }
        synchronized(snapshotLock) {
            compressedSnapshotFile = compressed
            snapshotMetadata = restored
            snapState = SnapState.READY
        }
        Log.i(
            "LocalApkServer",
            "Reused board-DB snapshot cache (${compressed.length() / 1024 / 1024} MB gzip)",
        )
    }

    /** One-time migration for a snapshot produced by the previous build,
     * which already had safe scrub/VACUUM semantics but no cache sidecar. */
    private fun adoptLegacySnapshot(live: File, raw: File, compressed: File): Boolean {
        if (!raw.exists() || raw.lastModified() < live.lastModified()) return false
        return runCatching {
            val schemaAndCatalogues = android.database.sqlite.SQLiteDatabase.openDatabase(
                raw.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { snapshotDb ->
                val schema = snapshotDb.rawQuery("PRAGMA user_version", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val catalogues = snapshotDb.rawQuery(
                    """
                    SELECT COALESCE(board_brand, 'kilter'), COUNT(*)
                    FROM climbs
                    WHERE is_listed = 1
                    GROUP BY COALESCE(board_brand, 'kilter')
                    ORDER BY COALESCE(board_brand, 'kilter')
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(LocalShareProtocol.BoardCatalogue(cursor.getString(0), cursor.getLong(1)))
                        }
                    }
                }
                schema to catalogues
            }
            val metadata = SnapshotMetadata(
                compressedSha256 = LocalShareProtocol.sha256(compressed),
                uncompressedSha256 = LocalShareProtocol.sha256(raw),
                uncompressedSizeBytes = raw.length(),
                schemaVersion = schemaAndCatalogues.first,
                catalogues = schemaAndCatalogues.second,
            )
            compressedSnapshotFile = compressed
            persistSnapshotCache(metadata, sourceFingerprint(live))
            raw.delete()
            Log.i("LocalApkServer", "Adopted previous board-DB snapshot cache")
            true
        }.getOrElse { error ->
            Log.w("LocalApkServer", "Could not adopt previous snapshot cache", error)
            false
        }
    }

    private fun persistSnapshotCache(
        metadata: SnapshotMetadata,
        source: SourceFingerprint,
    ) {
        val dir = snapshotDir ?: return
        val target = File(dir, SNAPSHOT_METADATA_NAME)
        val temporary = File(dir, "$SNAPSHOT_METADATA_NAME.tmp")
        runCatching {
            val catalogues = org.json.JSONArray().apply {
                metadata.catalogues.forEach { catalogue ->
                    put(
                        org.json.JSONObject()
                            .put("boardBrand", catalogue.boardBrand)
                            .put("climbCount", catalogue.climbCount),
                    )
                }
            }
            temporary.writeText(
                org.json.JSONObject()
                    .put("source", org.json.JSONObject()
                        .put("mainLength", source.mainLength)
                        .put("mainModified", source.mainModified)
                        .put("walLength", source.walLength)
                        .put("walModified", source.walModified))
                    .put("compressedSizeBytes", compressedSnapshotFile?.length() ?: 0L)
                    .put("compressedSha256", metadata.compressedSha256)
                    .put("uncompressedSha256", metadata.uncompressedSha256)
                    .put("uncompressedSizeBytes", metadata.uncompressedSizeBytes)
                    .put("schemaVersion", metadata.schemaVersion)
                    .put("catalogues", catalogues)
                    .toString(),
            )
            check(temporary.renameTo(target)) { "metadata rename failed" }
        }.onFailure { error ->
            temporary.delete()
            Log.w("LocalApkServer", "Could not persist snapshot cache metadata", error)
        }
    }

    private fun sourceFingerprint(live: File): SourceFingerprint {
        val wal = File(live.path + "-wal")
        return SourceFingerprint(
            mainLength = live.length(),
            mainModified = live.lastModified(),
            walLength = if (wal.exists()) wal.length() else 0L,
            walModified = if (wal.exists()) wal.lastModified() else 0L,
        )
    }

    private fun serve404(out: java.io.OutputStream) {
        val body = "404 Not Found"
        val headers = "HTTP/1.1 404 Not Found\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        out.write(body.toByteArray())
    }

    private fun serveMethodNotAllowed(out: java.io.OutputStream) {
        val body = "405 Method Not Allowed"
        val headers = "HTTP/1.1 405 Method Not Allowed\r\n" +
            "Allow: GET, HEAD, POST\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        out.write(body.toByteArray())
    }

    private fun serve503(out: java.io.OutputStream) {
        val body = "503 Snapshot unavailable — retry in a moment"
        val headers = "HTTP/1.1 503 Service Unavailable\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Retry-After: 5\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        out.write(body.toByteArray())
    }

    private fun serveNoContent(out: java.io.OutputStream) {
        out.write(
            "HTTP/1.1 204 No Content\r\n".toByteArray() +
                "Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
        )
    }

    companion object {
        /** Fixed port for auto-discovery by receivers (WiFi Direct group owner = 192.168.49.1). */
        const val LOCAL_SHARE_PORT = 4949
        const val AUTO_SHUTDOWN_MS = 15 * 60 * 1000L
        private const val ACTIVE_TRANSFER_RECHECK_MS = 30_000L
        private const val STREAM_BUFFER_SIZE = 512 * 1024
        private const val SOCKET_BUFFER_SIZE = 1024 * 1024
        /** Checkpointed board-DB copy in cacheDir; see [buildBoardDbSnapshot]. */
        const val SNAPSHOT_NAME = "board_share_snapshot.db"
        const val COMPRESSED_SNAPSHOT_NAME = "board_share_snapshot.db.gz"
        internal const val SNAPSHOT_METADATA_NAME = "board_share_snapshot.meta.json"

        private val LANDING_HTML = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#0b0b0d">
  <title>Install CruxCoach</title>
  <style>
    :root {
      color-scheme: dark;
      --bg:#0b0b0d; --surface:#17171b; --surface2:#202027;
      --line:#303038; --text:#f7f7f8; --muted:#aaaab3;
      --orange:#ff9500; --orange2:#ffb13b; --green:#38d67a;
    }
    * { box-sizing:border-box; }
    body {
      margin:0; min-height:100vh; color:var(--text);
      font-family:Inter,Roboto,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
      background:
        radial-gradient(700px 420px at 50% -120px,rgba(255,149,0,.24),transparent 65%),
        var(--bg);
      -webkit-font-smoothing:antialiased;
    }
    main { width:min(100% - 32px,460px); margin:0 auto; padding:34px 0 max(30px,env(safe-area-inset-bottom)); }
    header { text-align:center; margin-bottom:24px; }
    .logo { width:82px; height:82px; margin:0 auto 15px; display:block; border-radius:22px; box-shadow:0 15px 42px rgba(255,149,0,.2); }
    h1 { margin:0; font-size:30px; line-height:1.15; letter-spacing:-.8px; }
    .subtitle { margin:8px auto 0; max-width:330px; color:var(--muted); font-size:15px; line-height:1.5; }
    .badge {
      display:inline-flex; gap:7px; align-items:center; margin-top:14px; padding:7px 11px;
      border:1px solid rgba(56,214,122,.28); border-radius:99px;
      color:#84e9ae; background:rgba(56,214,122,.08); font-size:12px; font-weight:700;
    }
    .dot { width:7px; height:7px; border-radius:50%; background:var(--green); box-shadow:0 0 0 4px rgba(56,214,122,.12); }
    .card {
      padding:22px; border:1px solid var(--line); border-radius:22px;
      background:linear-gradient(180deg,rgba(255,255,255,.025),transparent),var(--surface);
      box-shadow:0 18px 50px rgba(0,0,0,.25);
    }
    .version { display:flex; justify-content:space-between; gap:12px; color:var(--muted); font-size:13px; }
    .version strong { color:var(--text); font-weight:650; }
    .action {
      display:flex; align-items:center; justify-content:center; gap:10px; width:100%;
      margin:19px 0 0; padding:16px 18px; border-radius:15px; color:#171006;
      background:linear-gradient(135deg,var(--orange2),var(--orange));
      box-shadow:0 10px 26px rgba(255,149,0,.22); text-decoration:none;
      font-size:17px; font-weight:800; transition:transform .12s ease,filter .12s ease;
    }
    .action:active { transform:scale(.985); filter:brightness(.94); }
    .action svg { width:21px; height:21px; fill:currentColor; }
    .action.secondary {
      margin:18px 0 0; padding:10px 14px; border-radius:12px;
      color:var(--muted); background:transparent; border:1px solid var(--line);
      box-shadow:none; font-size:13px; font-weight:650;
    }
    .hint { margin:10px 0 0; color:var(--muted); text-align:center; font-size:12px; }
    .steps { margin:21px 0 0; padding:0; list-style:none; }
    .steps li { display:grid; grid-template-columns:31px 1fr; gap:12px; position:relative; padding:0 0 19px; }
    .steps li:last-child { padding-bottom:0; }
    .steps li:not(:last-child)::after { content:""; position:absolute; left:15px; top:31px; bottom:2px; width:1px; background:var(--line); }
    .num {
      width:31px; height:31px; display:grid; place-items:center; z-index:1;
      border-radius:50%; color:var(--orange2); background:var(--surface2);
      border:1px solid #3b352e; font-size:13px; font-weight:800;
    }
    .steps h2 { margin:2px 0 3px; font-size:15px; line-height:1.35; }
    .steps p { margin:0; color:var(--muted); font-size:13px; line-height:1.45; }
    .auto {
      display:flex; gap:10px; align-items:flex-start; margin-top:20px; padding:13px 14px;
      border-radius:14px; background:rgba(56,214,122,.07); border:1px solid rgba(56,214,122,.18);
      color:#b5f2ce; font-size:13px; line-height:1.45;
    }
    .auto b { color:#e8fff1; }
    .shield { flex:0 0 auto; margin-top:1px; }
    footer { margin-top:18px; text-align:center; color:#777781; font-size:11px; line-height:1.55; }
    [data-lang] { display:none; }
    html.de [data-lang="de"],html:not(.de) [data-lang="en"] { display:block; }
    @media (prefers-reduced-motion:no-preference) { .card { animation:rise .35s ease-out both; } @keyframes rise { from { opacity:0; transform:translateY(8px); } } }
  </style>
</head>
<body>
  <main>
    <header>
      <svg class="logo" viewBox="0 0 512 512" role="img" aria-label="CruxCoach">
        <defs>
          <radialGradient id="gL" cx="256" cy="256" r="210" gradientUnits="userSpaceOnUse"><stop offset="40%" stop-color="#FF8C2A"/><stop offset="100%" stop-color="#B84400"/></radialGradient>
          <radialGradient id="gR" cx="256" cy="256" r="210" gradientUnits="userSpaceOnUse"><stop offset="40%" stop-color="#E05800"/><stop offset="100%" stop-color="#8C3000"/></radialGradient>
          <radialGradient id="gX" cx="256" cy="256" r="80" gradientUnits="userSpaceOnUse"><stop offset="0%" stop-color="#FFD080"/><stop offset="100%" stop-color="#E08A00"/></radialGradient>
        </defs>
        <rect width="512" height="512" rx="96" fill="#000"/>
        <path d="M252 56A200 200 0 1 0 248 456L248 384A128 128 0 1 1 252 128Z" fill="url(#gL)"/>
        <path d="M260 456A200 200 0 1 0 264 56L264 128A128 128 0 1 1 260 384Z" fill="url(#gR)"/>
        <polygon points="192,214 214,192 256,234 298,192 320,214 274,256 320,298 298,320 256,278 214,320 192,298 238,256" fill="url(#gX)" stroke="#fff" stroke-width="1" stroke-opacity=".15"/>
      </svg>
      <h1>CruxCoach</h1>
      <p class="subtitle" data-lang="de">Direkt vom Gerät neben dir installieren — ohne Internet.</p>
      <p class="subtitle" data-lang="en">Install directly from the nearby device — no internet needed.</p>
      <div class="badge"><span class="dot"></span><span data-lang="de">Lokale Verbindung</span><span data-lang="en">Local connection</span></div>
    </header>

    <section class="card">
      <div class="version">
        <span><span data-lang="de">Version</span><span data-lang="en">Version</span> <strong>{{VERSION_NAME}}</strong></span>
        <span><strong>{{APK_SIZE_MB}} MB</strong></span>
      </div>
      <a class="action" href="/CruxCoach.apk" download>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M11 3h2v10.17l3.59-3.58L18 11l-6 6-6-6 1.41-1.41L11 13.17V3zm-5 16h12v2H6v-2z"/></svg>
        <span data-lang="de">CruxCoach installieren</span><span data-lang="en">Install CruxCoach</span>
      </a>
      <a class="action secondary" href="{{OPEN_APP_URI_HTML}}">
        <span data-lang="de">Schon installiert? CruxCoach öffnen</span><span data-lang="en">Already installed? Open CruxCoach</span>
      </a>
      <p class="hint" data-lang="de">APK · direkt über dieses WLAN</p>
      <p class="hint" data-lang="en">APK · transferred directly over this Wi-Fi</p>

      <ol class="steps">
        <li><span class="num">1</span><div><h2 data-lang="de">CruxCoach installieren</h2><h2 data-lang="en">Install CruxCoach</h2><p data-lang="de">Lade die APK herunter, bestätige die Installation und tippe danach auf Öffnen.</p><p data-lang="en">Download the APK, confirm installation, then tap Open.</p></div></li>
        <li><span class="num">2</span><div><h2 data-lang="de">Board-Daten übernehmen</h2><h2 data-lang="en">Transfer board data</h2><p data-lang="de">Bleib in diesem WLAN. CruxCoach erkennt die Freigabe und übernimmt die verfügbaren Board-Kataloge.</p><p data-lang="en">Stay on this Wi-Fi. CruxCoach detects the share and transfers the available board catalogues.</p></div></li>
      </ol>

      <div class="auto"><span class="shield">✓</span><span data-lang="de"><b>Kein Zurückkehren zu dieser Seite nötig.</b> Download, Import und Finalisierung laufen mit sichtbarem Fortschritt direkt in CruxCoach.</span><span data-lang="en"><b>No need to return to this page.</b> Download, import and finalization continue with visible progress inside CruxCoach.</span></div>
    </section>

    <footer data-lang="de">Direkte Übertragung im lokalen WLAN · Es werden keine Daten ins Internet gesendet.</footer>
    <footer data-lang="en">Direct transfer on the local Wi-Fi · Nothing is sent to the internet.</footer>
  </main>
  <script>if((navigator.language||"").toLowerCase().startsWith("de"))document.documentElement.classList.add("de");</script>
</body>
</html>
""".trimIndent()
    }
}
