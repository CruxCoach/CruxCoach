package com.cruxcoach.android.util

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Wire contract for the peer-to-peer offline-share flow. */
object LocalShareProtocol {
    const val VERSION = 1
    const val VERSION_V2 = 2
    const val MANIFEST_PATH = "/v1/manifest"
    const val V2_MANIFEST_PATH = "/v2/manifest"
    const val COMPLETE_PATH = "/v1/complete"
    const val APK_PATH = "/CruxCoach.apk"
    const val BOARD_PATH = "/board.db.gz"
    const val V2_BOARD_PATH = "/v2/board.db.gz"
    /**
     * The Shiny Wall catalogue, offered next to the board snapshot in the v2
     * manifest's `families` list only. The board snapshot never carries Shiny
     * Wall: receivers released before the family read an unknown brand as
     * Kilter. Those receivers do not know `families` and never ask for it.
     */
    const val V2_SHINYWALL_PATH = "/v2/shinywall.sqlite3.gz"
    const val SHINYWALL_BRAND = "shinywall"
    const val SHINYWALL_FORMAT = "shinywall_snapshot_v1"
    private const val MAX_FAMILIES = 8
    const val PROTOCOL_HEADER = "X-CruxCoach-Share-Protocol"
    /** Optional continuity proof. Missing remains valid for legacy clients;
     *  once a receiver has seen a manifest, every later request binds to that
     *  exact bounded sender session. */
    const val SESSION_HEADER = "X-CruxCoach-Share-Session"


    data class Invitation(
        val baseUrl: String,
        val ssid: String,
        val password: String,
    )

    /**
     * Landing-page hand-off for a device that is already associated with the
     * sender's local-only Wi-Fi. Unlike [Invitation], this value never carries
     * a network credential: the receiver probes [baseUrl] through its existing
     * Wi-Fi [android.net.Network] and still asks for in-app consent before any
     * artifact transfer.
     */
    data class ConnectedInvitation(val baseUrl: String)

    data class Artifact(
        val path: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    data class BoardArtifact(
        val artifact: Artifact,
        val compression: String,
        val uncompressedSizeBytes: Long,
        val uncompressedSha256: String,
        val schemaVersion: Int,
        /** Every interactive catalogue contained in this one DB snapshot. */
        val catalogues: List<BoardCatalogue>,
    )

    data class BoardCatalogue(
        val boardBrand: String,
        val climbCount: Long,
    )

    /** A board family's catalogue served as its own artifact (see [V2_SHINYWALL_PATH]). */
    data class FamilyArtifact(
        val boardBrand: String,
        val format: String,
        val artifact: Artifact,
        val compression: String,
        val uncompressedSizeBytes: Long,
        val uncompressedSha256: String,
        val climbCount: Long,
    )

    data class Manifest(
        val protocolVersion: Int,
        val sessionId: String,
        val apkVersionCode: Long,
        val apkVersionName: String,
        val apk: Artifact,
        /** null while the sender is still preparing the snapshot. */
        val board: BoardArtifact?,
        val boardStatus: String,
        /**
         * The catalogues the sender says it has. From a ready snapshot these are its exact
         * contents; while the snapshot is still being prepared a current sender already names
         * them from its live database, so a receiver can choose before anything is built.
         * Empty for an older sender that is still preparing.
         */
        val declaredCatalogues: List<BoardCatalogue> = board?.catalogues.orEmpty(),
        /** Catalogues served as their own artifact; empty from senders before 0.2.5. */
        val families: List<FamilyArtifact> = emptyList(),
    )

    fun invitationUri(invitation: Invitation): String = Uri.Builder()
        .scheme("cruxcoach")
        .authority("offline-share")
        .appendQueryParameter("base", invitation.baseUrl.trimEnd('/'))
        .appendQueryParameter("ssid", invitation.ssid)
        .appendQueryParameter("password", invitation.password)
        .build()
        .toString()

    fun connectedInvitationUri(baseUrl: String): String {
        val origin = requireNotNull(normalizeHttpOrigin(baseUrl)) {
            "Invalid local-share origin"
        }
        require(isPrivateIpv4(Uri.parse(origin).host)) {
            "Local-share origin must use private IPv4"
        }
        return Uri.Builder()
            .scheme("cruxcoach")
            .authority("offline-share")
            .appendQueryParameter("base", origin)
            .build()
            .toString()
    }

    fun parseInvitation(uri: Uri): Invitation? {
        if (uri.scheme != "cruxcoach" || uri.host != "offline-share") return null
        val base = normalizeHttpOrigin(uri.getQueryParameter("base") ?: return null) ?: return null
        val ssid = uri.getQueryParameter("ssid") ?: return null
        val password = uri.getQueryParameter("password") ?: return null
        if (ssid.isBlank() || ssid.length > 32) return null
        if (password.length !in 8..63) return null
        return Invitation(baseUrl = base, ssid = ssid, password = password)
    }

    /** Parse only the credential-free installed-app form emitted by a sender. */
    fun parseConnectedInvitation(uri: Uri): ConnectedInvitation? {
        if (uri.scheme != "cruxcoach" || uri.host != "offline-share") return null
        if (uri.queryParameterNames != setOf("base")) return null
        val base = normalizeHttpOrigin(uri.getQueryParameter("base") ?: return null) ?: return null
        if (!isPrivateIpv4(Uri.parse(base).host)) return null
        return ConnectedInvitation(base)
    }

    fun parseManifest(json: String): Manifest {
        val root = JSONObject(json)
        val protocolVersion = root.getInt("protocolVersion")
        require(protocolVersion == VERSION || protocolVersion == VERSION_V2) {
            "Unsupported share protocol"
        }
        val apkJson = root.getJSONObject("apk")
        val boardJson = root.getJSONObject("board")
        val boardStatus = boardJson.getString("status")
        val board = if (boardStatus == "ready") {
            BoardArtifact(
                artifact = Artifact(
                    path = requireLocalPath(boardJson.getString("path")),
                    sizeBytes = boardJson.getLong("sizeBytes"),
                    sha256 = requireSha256(boardJson.getString("sha256")),
                ),
                compression = boardJson.getString("compression").also {
                    require(it == "gzip") { "Unsupported board compression" }
                },
                uncompressedSizeBytes = boardJson.getLong("uncompressedSizeBytes"),
                uncompressedSha256 = requireSha256(boardJson.getString("uncompressedSha256")),
                schemaVersion = boardJson.getInt("schemaVersion"),
                catalogues = parseCatalogues(boardJson.optJSONArray("catalogues") ?: JSONArray()),
            )
        } else {
            require(boardStatus == "preparing" || boardStatus == "unavailable") {
                "Unknown board status"
            }
            null
        }
        val families = if (protocolVersion == VERSION_V2) {
            parseFamilies(root.optJSONArray("families") ?: JSONArray())
        } else emptyList()
        // The board snapshot cannot carry a family that travels on its own.
        val declared = (board?.catalogues
            ?: parseCatalogues(boardJson.optJSONArray("catalogues") ?: JSONArray()))
            .filter { catalogue -> catalogue.boardBrand != SHINYWALL_BRAND } +
            families.map { BoardCatalogue(it.boardBrand, it.climbCount) }
        return Manifest(
            protocolVersion = protocolVersion,
            sessionId = root.getString("sessionId").also {
                require(it.matches(Regex("[0-9a-fA-F-]{16,64}"))) { "Invalid session id" }
            },
            apkVersionCode = apkJson.getLong("versionCode"),
            apkVersionName = apkJson.getString("versionName").take(64),
            apk = Artifact(
                path = requireLocalPath(apkJson.getString("path")),
                sizeBytes = apkJson.getLong("sizeBytes"),
                sha256 = requireSha256(apkJson.getString("sha256")),
            ),
            board = board,
            boardStatus = boardStatus,
            declaredCatalogues = declared,
            families = families,
        ).also { manifest ->
            require(manifest.apkVersionCode > 0L)
            LocalTransferLimits.requireSize(manifest.apk.sizeBytes, LocalTransferLimits.MAX_APK_BYTES)
            manifest.board?.let {
                val expectedBoardPath = when (manifest.protocolVersion) {
                    VERSION -> BOARD_PATH
                    VERSION_V2 -> V2_BOARD_PATH
                    else -> error("Unsupported share protocol")
                }
                require(it.artifact.path == expectedBoardPath) {
                    "Board artifact does not match share protocol"
                }
                LocalTransferLimits.requireSize(it.artifact.sizeBytes, LocalTransferLimits.MAX_COMPRESSED_DB_BYTES)
                LocalTransferLimits.requireSize(it.uncompressedSizeBytes, LocalTransferLimits.MAX_DB_BYTES)
                require(it.schemaVersion >= 0)
            }
        }
    }

    fun artifactUrl(baseUrl: String, path: String): String =
        checkNotNull(normalizeHttpOrigin(baseUrl)) { "Invalid local-share origin" } +
            requireLocalPath(path)

    /**
     * Accept only a plain HTTP origin, never credentials, a path, query or
     * fragment. The caller separately constrains the host to private IPv4.
     */
    fun normalizeHttpOrigin(value: String): String? {
        val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "http") return null
        val host = uri.host ?: return null
        val port = uri.port
        if (port !in 1..65535) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
        return "http://$host:$port"
    }

    fun isPrivateIpv4(host: String?): Boolean {
        val parts = host?.split('.') ?: return false
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 || a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireLocalPath(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//")) { "Invalid artifact path" }
        require(path.length <= 128 && path.matches(Regex("/[A-Za-z0-9._/-]+"))) {
            "Invalid artifact path"
        }
        require(!path.contains("..") && !path.contains('?') && !path.contains('#')) {
            "Invalid artifact path"
        }
        return path
    }

    private fun requireSha256(value: String): String = value.lowercase().also {
        require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
    }

    /**
     * Known families only: an entry this version cannot import (another
     * brand, format, path or compression) is skipped, so a later sender can
     * add families without breaking this receiver. A malformed or oversized
     * entry is skipped too: the family then follows over the internet, and
     * the board share itself is never put at risk by it.
     */
    private fun parseFamilies(array: JSONArray): List<FamilyArtifact> {
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_FAMILIES)) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("boardBrand") != SHINYWALL_BRAND ||
                    item.optString("format") != SHINYWALL_FORMAT ||
                    item.optString("path") != V2_SHINYWALL_PATH ||
                    item.optString("compression") != "gzip"
                ) continue
                val family = runCatching { FamilyArtifact(
                    boardBrand = SHINYWALL_BRAND,
                    format = SHINYWALL_FORMAT,
                    artifact = Artifact(
                        path = V2_SHINYWALL_PATH,
                        sizeBytes = item.getLong("sizeBytes"),
                        sha256 = requireSha256(item.getString("sha256")),
                    ),
                    compression = "gzip",
                    uncompressedSizeBytes = item.getLong("uncompressedSizeBytes"),
                    uncompressedSha256 = requireSha256(item.getString("uncompressedSha256")),
                    climbCount = item.optLong("climbCount", 0L).coerceAtLeast(0L),
                ).also {
                    LocalTransferLimits.requireSize(it.artifact.sizeBytes, LocalTransferLimits.MAX_COMPRESSED_DB_BYTES)
                    LocalTransferLimits.requireSize(it.uncompressedSizeBytes, LocalTransferLimits.MAX_DB_BYTES)
                } }.getOrNull() ?: continue
                if (none { it.boardBrand == family.boardBrand }) add(family)
            }
        }
    }

    private fun parseCatalogues(array: JSONArray): List<BoardCatalogue> {
        require(array.length() <= 32) { "Too many board catalogues" }
        val result = buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val brand = item.getString("boardBrand")
                require(brand.matches(Regex("[a-z0-9_-]{1,32}"))) { "Invalid board brand" }
                val count = item.getLong("climbCount")
                require(count >= 0L) { "Invalid board climb count" }
                add(BoardCatalogue(brand, count))
            }
        }
        require(result.map { it.boardBrand }.distinct().size == result.size) {
            "Duplicate board catalogue"
        }
        return result
    }
}
