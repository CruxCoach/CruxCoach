package com.cruxcoach.android.nostr.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Decrypted content of the Kind 30078 backup pointer event.
 *
 * Versioning: [version] is the pointer envelope version (`1` at 0.1.3 ship),
 * independent of the payload's `Backup.version` (`2` — see FEAT-002 §9
 * "Schema version"). The two can evolve independently.
 *
 * [servers] lists the Blossom servers the blob was uploaded to, so the
 * restore path doesn't need to re-discover them if the user's Kind 10063
 * is unreachable.
 *
 * [previous_sha256] enables blob cleanup: after a successful pointer
 * publish, the old blob is DELETEd from each configured server (best
 * effort). Stored locally (not in the pointer) as
 * [BackupPreferences.getPreviousBlobSha256].
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
     * Defensive sanity-check before any accepted pointer drives network
     * I/O. Every field is attacker-controllable in principle (a
     * compromised nsec could sign a pointer with absurd values and
     * replay it through the A3 freshness gate if no prior pointer
     * exists locally). Refusing out-of-range values here keeps the
     * downstream code from spending I/O / memory / Amber approval
     * budgets on obviously-malicious payloads.
     */
    fun validateOrThrow() {
        require(version in 1..POINTER_VERSION) {
            "BackupPointer.version $version outside accepted range 1..$POINTER_VERSION"
        }
        require(schema_version in 1..PAYLOAD_SCHEMA_VERSION) {
            "BackupPointer.schema_version $schema_version outside accepted range 1..$PAYLOAD_SCHEMA_VERSION"
        }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "BackupPointer.sha256 not a 64-hex string"
        }
        require(size in 1..MAX_BLOB_SIZE_BYTES) {
            "BackupPointer.size $size outside accepted range 1..$MAX_BLOB_SIZE_BYTES"
        }
        require(servers.isNotEmpty() && servers.size <= MAX_SERVERS) {
            "BackupPointer.servers size ${servers.size} outside 1..$MAX_SERVERS"
        }
        require(updatedAt in 0L..(System.currentTimeMillis() / 1000 + MAX_CLOCK_SKEW_SEC)) {
            "BackupPointer.updatedAt $updatedAt outside plausible epoch range"
        }
        require(deviceId.length <= MAX_DEVICE_ID_LEN) {
            "BackupPointer.deviceId too long (${deviceId.length} > $MAX_DEVICE_ID_LEN)"
        }
        require(categories.size <= MAX_CATEGORIES) {
            "BackupPointer.categories size ${categories.size} exceeds $MAX_CATEGORIES"
        }
    }

    companion object {
        /** Pointer envelope version — bump when metadata fields change shape. */
        const val POINTER_VERSION = 1

        /**
         * Payload (`CruxCoachBackup.Backup.version`) the pointer is paired
         * with. Kept here so a single read of the pointer tells the restore
         * code which payload schema to expect.
         */
        const val PAYLOAD_SCHEMA_VERSION = 2

        // Ceiling on the ciphertext blob size we're willing to even look
        // at. 64 MB is ~5x the realistic plaintext max × typical gzip
        // ratio; A4's download cap kicks in separately at
        // pointer.size + 1 KB.
        private const val MAX_BLOB_SIZE_BYTES = 64L * 1024 * 1024
        // Number of Blossom servers a single pointer can fan out to.
        // Legitimate value is 1-3; more than a handful is either a
        // typo or a hostile attempt to drive parallel uploads.
        private const val MAX_SERVERS = 16
        // Up to 60s clock skew tolerance: future timestamps beyond that
        // indicate a clock-forged forgery.
        private const val MAX_CLOCK_SKEW_SEC = 60L
        private const val MAX_DEVICE_ID_LEN = 64
        private const val MAX_CATEGORIES = 32
    }
}
