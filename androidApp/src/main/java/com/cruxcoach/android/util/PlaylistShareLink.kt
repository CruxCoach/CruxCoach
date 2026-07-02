package com.cruxcoach.android.util

import com.cruxcoach.android.BuildConfig
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Shareable App-Link for a playlist: `https://<APP_LINK_HOST>/l/<payload>`.
 *
 * The payload is base64url(no-pad) over a compact binary frame:
 *
 * ```
 * [version:1=1][nameLen:1][name: UTF-8 ≤ 60 bytes]
 * [count:1][ (angle:1)(uuid:16) × count ]
 * ```
 *
 * Same 17-byte climb tuple the session GATT protocol uses. Only climbs
 * travel — rest blocks are personal pacing and stay local (the receiver
 * can regenerate or add their own). ~50 climbs ≈ 1.2 kB link, well under
 * URL limits. Opening the link deep-links into the app (manifest `/l/`
 * App Link, parsed by MainActivity) or falls through to the cruxcoach.org
 * website when the app isn't installed.
 *
 * Climbs whose uuid doesn't parse as a UUID (defensive: imports from
 * exotic sources) are skipped by [build]; the caller surfaces the count.
 */
object PlaylistShareLink {

    private const val VERSION = 1
    private const val MAX_NAME_BYTES = 60
    private const val MAX_CLIMBS = 100

    data class SharedPlaylist(val name: String, val climbs: List<SharedClimb>)
    data class SharedClimb(val climbUuid: String, val angle: Int)

    /** Null when nothing is encodable (no valid uuids). */
    fun build(name: String, climbs: List<SharedClimb>): String? {
        val encodable = climbs.mapNotNull { c ->
            val uuid = parseUuid(c.climbUuid) ?: return@mapNotNull null
            uuid to c.angle.coerceIn(0, 255)
        }.take(MAX_CLIMBS)
        if (encodable.isEmpty()) return null

        val nameBytes = name.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_NAME_BYTES) it.copyOf(MAX_NAME_BYTES) else it
        }
        val buf = ByteBuffer.allocate(3 + nameBytes.size + encodable.size * 17)
        buf.put(VERSION.toByte())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(encodable.size.toByte())
        encodable.forEach { (uuid, angle) ->
            buf.put(angle.toByte())
            buf.putLong(uuid.mostSignificantBits)
            buf.putLong(uuid.leastSignificantBits)
        }
        // java.util.Base64 (API 26+ = our minSdk) so the codec stays plain-
        // JVM unit-testable, unlike android.util.Base64.
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array())
        return "https://${BuildConfig.APP_LINK_HOST}/l/$payload"
    }

    /** Null on any malformed payload — the caller falls through to the
     *  normal launcher path instead of showing a broken-link screen. */
    fun parse(payload: String): SharedPlaylist? {
        val bytes = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.size < 3) return null
        val buf = ByteBuffer.wrap(bytes)
        if (buf.get().toInt() != VERSION) return null
        val nameLen = buf.get().toInt() and 0xFF
        if (nameLen > MAX_NAME_BYTES || buf.remaining() < nameLen + 1) return null
        val nameBytes = ByteArray(nameLen).also { buf.get(it) }
        val count = buf.get().toInt() and 0xFF
        if (count == 0 || count > MAX_CLIMBS || buf.remaining() != count * 17) return null
        val climbs = (0 until count).map {
            val angle = buf.get().toInt() and 0xFF
            val uuid = UUID(buf.long, buf.long)
            SharedClimb(uuid.toString().lowercase(), angle)
        }
        return SharedPlaylist(String(nameBytes, Charsets.UTF_8), climbs)
    }

    private fun parseUuid(raw: String): UUID? {
        val bare = raw.replace("-", "")
        if (bare.length != 32 || !bare.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        val hyphenated = "${bare.substring(0, 8)}-${bare.substring(8, 12)}-" +
            "${bare.substring(12, 16)}-${bare.substring(16, 20)}-${bare.substring(20)}"
        return try {
            UUID.fromString(hyphenated)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
