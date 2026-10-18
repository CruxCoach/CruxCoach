package com.cruxcoach.app.localshare

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire contract of Android's peer-to-peer offline share (`LocalShareProtocol`), receiving side.
 * Same constants, the same validation and the same limits: a sender is an unauthenticated peer
 * on the local network, so everything it says is bounded before it is used.
 */
object LocalShareProtocol {
    const val VERSION = 1
    const val VERSION_V2 = 2
    const val MANIFEST_PATH = "/v1/manifest"
    const val V2_MANIFEST_PATH = "/v2/manifest"
    const val COMPLETE_PATH = "/v1/complete"
    const val APK_PATH = "/CruxCoach.apk"
    const val BOARD_PATH = "/board.db.gz"
    const val V2_BOARD_PATH = "/v2/board.db.gz"
    const val PROTOCOL_HEADER = "X-CruxCoach-Share-Protocol"
    const val SESSION_HEADER = "X-CruxCoach-Share-Session"

    data class Invitation(val baseUrl: String, val ssid: String, val password: String)

    /** The credential-free hand-off of the sender's landing page: only the private origin. */
    data class ConnectedInvitation(val baseUrl: String)

    data class Artifact(val path: String, val sizeBytes: Long, val sha256: String)

    data class BoardArtifact(
        val artifact: Artifact,
        val compression: String,
        val uncompressedSizeBytes: Long,
        val uncompressedSha256: String,
        val schemaVersion: Int,
        val catalogues: List<BoardCatalogue>,
    )

    data class BoardCatalogue(val boardBrand: String, val climbCount: Long)

    data class Manifest(
        val protocolVersion: Int,
        val sessionId: String,
        val apkVersionCode: Long,
        val apkVersionName: String,
        val apk: Artifact,
        /** Null while the sender is still preparing the snapshot. */
        val board: BoardArtifact?,
        val boardStatus: String,
        /** A preparing current sender already names its catalogues; empty for an older one. */
        val declaredCatalogues: List<BoardCatalogue> = board?.catalogues.orEmpty(),
    )

    fun invitationUri(invitation: Invitation): String =
        "cruxcoach://offline-share?base=" + encode(invitation.baseUrl.trimEnd('/')) +
            "&ssid=" + encode(invitation.ssid) + "&password=" + encode(invitation.password)

    fun connectedInvitationUri(baseUrl: String): String {
        val origin = requireNotNull(normalizeHttpOrigin(baseUrl)) { "Invalid local-share origin" }
        require(isPrivateIpv4(SimpleUri.parse(origin)?.host)) { "Local-share origin must use private IPv4" }
        return "cruxcoach://offline-share?base=" + encode(origin)
    }

    fun parseInvitation(uri: String): Invitation? {
        val parsed = SimpleUri.parse(uri) ?: return null
        if (parsed.scheme != "cruxcoach" || parsed.host != "offline-share") return null
        val base = normalizeHttpOrigin(parsed.queryParameter("base") ?: return null) ?: return null
        val ssid = parsed.queryParameter("ssid") ?: return null
        val password = parsed.queryParameter("password") ?: return null
        if (ssid.isBlank() || ssid.length > 32) return null
        if (password.length !in 8..63) return null
        return Invitation(baseUrl = base, ssid = ssid, password = password)
    }

    /** Only the credential-free form a sender's landing page emits. */
    fun parseConnectedInvitation(uri: String): ConnectedInvitation? {
        val parsed = SimpleUri.parse(uri) ?: return null
        if (parsed.scheme != "cruxcoach" || parsed.host != "offline-share") return null
        if (parsed.queryParameterNames() != setOf("base")) return null
        val base = normalizeHttpOrigin(parsed.queryParameter("base") ?: return null) ?: return null
        if (!isPrivateIpv4(SimpleUri.parse(base)?.host)) return null
        return ConnectedInvitation(base)
    }

    /** Throws [IllegalArgumentException] for a malformed manifest, [LimitExceeded] for one over the receiver's limits. */
    fun parseManifest(json: String): Manifest {
        val root = obj(runCatching { Json.parseToJsonElement(json) }.getOrElse { throw IllegalArgumentException("Malformed manifest JSON") })
        val protocolVersion = int(root, "protocolVersion")
        require(protocolVersion == VERSION || protocolVersion == VERSION_V2) { "Unsupported share protocol" }
        val apkJson = obj(root["apk"])
        val boardJson = obj(root["board"])
        val boardStatus = string(boardJson, "status")
        val board = if (boardStatus == "ready") {
            BoardArtifact(
                artifact = Artifact(
                    path = requireLocalPath(string(boardJson, "path")),
                    sizeBytes = long(boardJson, "sizeBytes"),
                    sha256 = requireSha256(string(boardJson, "sha256")),
                ),
                compression = string(boardJson, "compression").also { require(it == "gzip") { "Unsupported board compression" } },
                uncompressedSizeBytes = long(boardJson, "uncompressedSizeBytes"),
                uncompressedSha256 = requireSha256(string(boardJson, "uncompressedSha256")),
                schemaVersion = int(boardJson, "schemaVersion"),
                catalogues = parseCatalogues(boardJson["catalogues"]),
            )
        } else {
            require(boardStatus == "preparing" || boardStatus == "unavailable") { "Unknown board status" }
            null
        }
        val declared = board?.catalogues ?: parseCatalogues(boardJson["catalogues"])
        val manifest = Manifest(
            protocolVersion = protocolVersion,
            sessionId = string(root, "sessionId").also {
                require(SESSION_ID.matches(it)) { "Invalid session id" }
            },
            apkVersionCode = long(apkJson, "versionCode"),
            apkVersionName = string(apkJson, "versionName").take(64),
            apk = Artifact(
                path = requireLocalPath(string(apkJson, "path")),
                sizeBytes = long(apkJson, "sizeBytes"),
                sha256 = requireSha256(string(apkJson, "sha256")),
            ),
            board = board,
            boardStatus = boardStatus,
            declaredCatalogues = declared,
        )
        require(manifest.apkVersionCode > 0L)
        // An iPhone never takes the APK, but Android bounds it before anything else; so does this.
        LocalTransferLimits.requireSize(manifest.apk.sizeBytes, LocalTransferLimits.MAX_APK_BYTES)
        manifest.board?.let {
            val expectedBoardPath = if (manifest.protocolVersion == VERSION) BOARD_PATH else V2_BOARD_PATH
            require(it.artifact.path == expectedBoardPath) { "Board artifact does not match share protocol" }
            LocalTransferLimits.requireSize(it.artifact.sizeBytes, LocalTransferLimits.MAX_COMPRESSED_DB_BYTES)
            LocalTransferLimits.requireSize(it.uncompressedSizeBytes, LocalTransferLimits.MAX_DB_BYTES)
            require(it.schemaVersion >= 0)
        }
        return manifest
    }

    fun artifactUrl(baseUrl: String, path: String): String =
        checkNotNull(normalizeHttpOrigin(baseUrl)) { "Invalid local-share origin" } + requireLocalPath(path)

    /** A plain HTTP origin with an explicit port: no credentials, path, query or fragment. */
    fun normalizeHttpOrigin(value: String): String? {
        val uri = SimpleUri.parse(value.trim()) ?: return null
        if (uri.scheme?.lowercase() != "http") return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        if (uri.port !in 1..65535) return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        if (uri.path.isNotEmpty() && uri.path != "/") return null
        return "http://$host:${uri.port}"
    }

    fun isPrivateIpv4(host: String?): Boolean {
        val parts = host?.split('.') ?: return false
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    /** `host:port` of a validated origin, for the consent text. */
    fun hostOf(baseUrl: String): String = baseUrl.removePrefix("http://")

    private fun requireLocalPath(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//")) { "Invalid artifact path" }
        require(path.length <= 128 && LOCAL_PATH.matches(path)) { "Invalid artifact path" }
        require(!path.contains("..") && !path.contains('?') && !path.contains('#')) { "Invalid artifact path" }
        return path
    }

    private fun requireSha256(value: String): String = value.lowercase().also {
        require(SHA256.matches(it)) { "Invalid SHA-256" }
    }

    private fun parseCatalogues(element: JsonElement?): List<BoardCatalogue> {
        if (element == null || element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: throw IllegalArgumentException("Invalid board catalogues")
        require(array.size <= 32) { "Too many board catalogues" }
        val result = array.map { item ->
            val entry = obj(item)
            val brand = string(entry, "boardBrand")
            require(BRAND.matches(brand)) { "Invalid board brand" }
            val count = long(entry, "climbCount")
            require(count >= 0L) { "Invalid board climb count" }
            BoardCatalogue(brand, count)
        }
        require(result.map { it.boardBrand }.distinct().size == result.size) { "Duplicate board catalogue" }
        return result
    }

    private fun obj(element: JsonElement?): JsonObject =
        element as? JsonObject ?: throw IllegalArgumentException("Expected a JSON object")

    private fun primitive(o: JsonObject, key: String): JsonPrimitive {
        val value = o[key]
        if (value == null || value is JsonNull) throw IllegalArgumentException("Missing $key")
        return value as? JsonPrimitive ?: throw IllegalArgumentException("Invalid $key")
    }

    private fun string(o: JsonObject, key: String): String {
        val value = primitive(o, key)
        require(value.isString) { "Invalid $key" }
        return value.content
    }

    private fun long(o: JsonObject, key: String): Long {
        val content = primitive(o, key).content
        return content.toLongOrNull()
            ?: content.toDoubleOrNull()?.takeIf { it == kotlin.math.floor(it) && kotlin.math.abs(it) < 9.0E18 }?.toLong()
            ?: throw IllegalArgumentException("Invalid $key")
    }

    private fun int(o: JsonObject, key: String): Int {
        val value = long(o, key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid $key" }
        return value.toInt()
    }

    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") append(ch)
            else {
                append('%')
                append(HEX[c shr 4])
                append(HEX[c and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
    private val SESSION_ID = Regex("[0-9a-fA-F-]{16,64}")
    private val LOCAL_PATH = Regex("/[A-Za-z0-9._/-]+")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val BRAND = Regex("[a-z0-9_-]{1,32}")
}

/**
 * The parts of `android.net.Uri` the share needs, with its semantics: an absent port is -1,
 * query values are percent-decoded and `+` reads as a space. Anything it cannot split cleanly
 * is null, which every caller treats as "not an invitation".
 */
internal class SimpleUri(
    val scheme: String?,
    val userInfo: String?,
    val host: String?,
    val port: Int,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    fun queryParameter(name: String): String? = parameters().firstOrNull { it.first == name }?.second

    fun queryParameterNames(): Set<String> = parameters().map { it.first }.toSet()

    private fun parameters(): List<Pair<String, String>> {
        val raw = query ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split('&').mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val separator = part.indexOf('=')
            val name = if (separator < 0) part else part.substring(0, separator)
            val value = if (separator < 0) "" else part.substring(separator + 1)
            val decodedName = decode(name) ?: return@mapNotNull null
            val decodedValue = decode(value) ?: return@mapNotNull null
            decodedName to decodedValue
        }
    }

    companion object {
        fun parse(value: String): SimpleUri? {
            if (value.any { it.code < 0x21 || it.code == 0x7F }) return null
            val schemeEnd = value.indexOf(':')
            if (schemeEnd <= 0) return null
            val scheme = value.substring(0, schemeEnd)
            if (!scheme[0].isLetter() || scheme.any { !(it.isLetterOrDigit() || it in "+-.") }) return null
            var rest = value.substring(schemeEnd + 1)
            var fragment: String? = null
            rest.indexOf('#').takeIf { it >= 0 }?.let { fragment = rest.substring(it + 1); rest = rest.substring(0, it) }
            var query: String? = null
            rest.indexOf('?').takeIf { it >= 0 }?.let { query = rest.substring(it + 1); rest = rest.substring(0, it) }
            if (!rest.startsWith("//")) return null
            rest = rest.substring(2)
            val pathStart = rest.indexOf('/')
            val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
            val path = if (pathStart < 0) "" else rest.substring(pathStart)
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            if (hostPort.startsWith("[")) return null
            val colon = hostPort.lastIndexOf(':')
            val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            val port = if (colon >= 0) {
                val digits = hostPort.substring(colon + 1)
                if (digits.isEmpty() || digits.length > 5 || digits.any { it !in '0'..'9' }) return null
                digits.toInt()
            } else -1
            return SimpleUri(scheme, userInfo, host, port, path, query, fragment)
        }

        private fun decode(value: String): String? {
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '+' -> { bytes.add(' '.code.toByte()); i++ }
                    c == '%' -> {
                        if (i + 2 >= value.length) return null
                        val hi = value[i + 1].digitToIntOrNull(16) ?: return null
                        val lo = value[i + 2].digitToIntOrNull(16) ?: return null
                        bytes.add(((hi shl 4) or lo).toByte())
                        i += 3
                    }
                    else -> {
                        c.toString().encodeToByteArray().forEach { bytes.add(it) }
                        i++
                    }
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
