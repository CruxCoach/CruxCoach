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
    companion object {
        /** Pointer envelope version — bump when metadata fields change shape. */
        const val POINTER_VERSION = 1

        /**
         * Payload (`CruxCoachBackup.Backup.version`) the pointer is paired
         * with. Kept here so a single read of the pointer tells the restore
         * code which payload schema to expect.
         */
        const val PAYLOAD_SCHEMA_VERSION = 2
    }
}
