package com.cruxcoach.android.nostr.backup

/**
 * Structured error reasons for [BackupException]. The pipeline raises a
 * specific [BackupErrorReason] subclass; the UI layer (which has access
 * to a Context) maps it to a localized `stringResource(...)`. Pre-fix
 * the exception just carried an English `message` string that surfaced
 * verbatim to German users.
 *
 * Keep the dev-facing log message in [toLogMessage] for `logcat` /
 * crash reports — that string never reaches the UI.
 */
sealed class BackupErrorReason {
    /** No Blossom server accepted the encrypted blob. */
    data class BlobUploadFailed(val total: Int, val authDetail: String?) : BackupErrorReason()

    /**
     * Every server returned an Amber "No activity to launch from" error.
     * The only self-service fix is the user enabling "always approve" in
     * Amber — surface that explicitly so the user doesn't read it as a
     * generic Blossom outage.
     */
    data object AmberNeedsAutoApprove : BackupErrorReason()

    /** HEAD-verify after upload returned absent on every server. */
    data class BlobNotVisibleAfterUpload(val total: Int) : BackupErrorReason()

    /**
     * Pointer event rejected by every write relay — the blob is durable
     * on Blossom but no client can find it. Retry once relays come back.
     */
    data class PointerEventNotDurable(val attempted: Int) : BackupErrorReason()

    /**
     * Key event rejected by every write relay — without it, restore on
     * another device is impossible (the wrapped data key can't be fetched).
     */
    data class KeyEventNotDurable(val attempted: Int) : BackupErrorReason()

    /** dataKey could not be NIP-44-decrypted on restore. */
    data object DataKeyUnwrapFailed : BackupErrorReason()

    /** Restored pointer references no servers that pass the URL gate. */
    data object PointerListsNoUsableServers : BackupErrorReason()

    /** Decompressed plaintext exceeded the safety cap (gzip-bomb defense). */
    data class PlaintextSizeCap(val maxBytes: Int) : BackupErrorReason()

    /**
     * Local wrapped-key cache is empty AND the relay query for the
     * Kind-30078 key event returned no result. We can't tell whether
     * the relays genuinely don't have a key event (first-time setup,
     * post-opt-out) or whether the query timed out / network glitched
     * (transient — relays still have the key). Refuse to auto-
     * regenerate in this ambiguous case if there is any prior backup
     * history on this device, because regenerating would publish a
     * NEW key event, replacing the old one on relays — making the
     * existing blob undecryptable forever.
     */
    data object KeyFetchAmbiguous : BackupErrorReason()

    /** Fall-through for unstructured errors. */
    data class Other(val message: String) : BackupErrorReason()
}

/** Human-readable representation for `logcat` / crash reports — never user-facing. */
fun BackupErrorReason.toLogMessage(): String = when (this) {
    is BackupErrorReason.BlobUploadFailed ->
        "Blob upload failed on all $total servers" +
            (authDetail?.let { " ($it)" } ?: "")
    BackupErrorReason.AmberNeedsAutoApprove ->
        "Amber needs to be set to 'always approve' for CruxCoach signing operations"
    is BackupErrorReason.BlobNotVisibleAfterUpload ->
        "Blob not visible on any server after upload (servers=$total)"
    is BackupErrorReason.PointerEventNotDurable ->
        "Pointer event rejected by every relay ($attempted attempted) — backup not durable"
    is BackupErrorReason.KeyEventNotDurable ->
        "Key event rejected by every relay ($attempted attempted) — restore not recoverable"
    BackupErrorReason.DataKeyUnwrapFailed ->
        "dataKey unwrap failed"
    BackupErrorReason.PointerListsNoUsableServers ->
        "Backup pointer lists no usable https Blossom servers"
    is BackupErrorReason.PlaintextSizeCap ->
        "Gzip output exceeded $maxBytes bytes (decompression bomb?)"
    BackupErrorReason.KeyFetchAmbiguous ->
        "Wrapped-key cache empty and relay key event query returned no result; " +
            "prior backup history present on device → refusing to regenerate"
    is BackupErrorReason.Other -> message
}

/**
 * Per-leg notes returned by [BackupRepository.deleteRemoteBackups]. The
 * UI maps each note to a localized `stringResource(...)`. Pre-fix the
 * notes were a `List<String>` populated with English diagnostic text
 * that surfaced verbatim in the German delete-remote dialog.
 */
sealed class DeleteRemoteNote {
    /** D-tag could not be derived (signer unavailable, key rotated, etc.). */
    data object DTagDerivationFailed : DeleteRemoteNote()

    /** User has no Kind 10002 write relays configured — nothing to publish to. */
    data object NoWriteRelays : DeleteRemoteNote()

    /** Deletion event reached relays but every one rejected it. */
    data object NoRelayAcceptedDeletion : DeleteRemoteNote()

    /** Partial relay accept — at least one but not all. */
    data class PartialRelayAccept(val accepted: Int, val attempted: Int) : DeleteRemoteNote()

    /** sendEventWithStats (or its predecessor) threw before reaching relays. */
    data object RelayPublishThrew : DeleteRemoteNote()

    /** Blossom DELETE rejected because the auth signature failed. */
    data object BlossomAuthFailed : DeleteRemoteNote()

    /** Every Blossom server returned a non-success on DELETE. */
    data object BlossomFullyRejected : DeleteRemoteNote()

    /** Partial Blossom success — at least one but not all. */
    data class BlossomPartial(val accepted: Int, val attempted: Int) : DeleteRemoteNote()

    /**
     * Tombstone Kind-30078 (the replaceable-event replacement that hides
     * the live pointer / key event from any future restore_check) failed
     * to land on at least one relay for at least one of the two d-tags.
     * Without a tombstone on every relay, a cross-device restore could
     * still find the original ciphertext-bearing pointer there. Surfaced
     * as a hard "retry" hint because — unlike NIP-09, which third-party
     * relays may silently drop — replaceable-event replacement is core
     * NIP-01 and a 0-accept means the publish itself didn't reach anyone.
     */
    data class TombstonePublishFailed(
        val backupAccepted: Int,
        val keyAccepted: Int,
        val attempted: Int,
    ) : DeleteRemoteNote()

    /** Catch-all for an unexpected throwable (carries `e.javaClass.simpleName`). */
    data class UnexpectedError(val type: String) : DeleteRemoteNote()
}
