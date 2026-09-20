package com.cruxcoach.app.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Decrypted content of the kind-30078 backup pointer event. Field names are
 * the Android ones, so a pointer written by either platform parses on the
 * other.
 */
@Serializable
data class BackupPointer(
    val version: Int = POINTER_VERSION,
    val schema_version: Int = PAYLOAD_SCHEMA_VERSION,
    val sha256: String,
    val size: Long,
    val servers: List<String>,
    @SerialName("previous_sha256")
    val previousSha256: String? = null,
    @SerialName("updated_at")
    val updatedAt: Long,
    @SerialName("device_id")
    val deviceId: String,
    val categories: List<String>,
) {
    /**
     * Every field here is attacker-controllable in principle (a leaked nsec
     * could sign an absurd pointer and replay it). Refusing out-of-range
     * values keeps the download path from spending I/O and memory on it.
     */
    fun validate(nowSeconds: Long): Boolean =
        version in 1..POINTER_VERSION &&
            schema_version in 1..PAYLOAD_SCHEMA_VERSION &&
            sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' } &&
            size in 1..MAX_BLOB_SIZE_BYTES &&
            servers.isNotEmpty() && servers.size <= MAX_SERVERS &&
            updatedAt in 0L..(nowSeconds + MAX_CLOCK_SKEW_SEC) &&
            deviceId.length <= MAX_DEVICE_ID_LEN &&
            categories.size <= MAX_CATEGORIES

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val POINTER_VERSION = 1
        const val PAYLOAD_SCHEMA_VERSION = 2
        const val MAX_BLOB_SIZE_BYTES = 64L * 1024 * 1024
        private const val MAX_SERVERS = 16
        private const val MAX_CLOCK_SKEW_SEC = 60L
        private const val MAX_DEVICE_ID_LEN = 64
        private const val MAX_CATEGORIES = 32

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Null when the JSON is malformed — never throws at the parse boundary. */
        fun decode(json: String): BackupPointer? = try {
            JSON.decodeFromString(serializer(), json)
        } catch (e: Exception) {
            null
        }
    }
}
