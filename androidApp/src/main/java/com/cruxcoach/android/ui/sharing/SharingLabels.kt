package com.cruxcoach.android.ui.sharing

import androidx.annotation.StringRes
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.PeerIdParseError
import com.cruxcoach.android.sharing.SharingWriteError
import com.cruxcoach.domain.sharing.SharingBackupError
import com.cruxcoach.domain.sharing.DecisionSource
import com.cruxcoach.domain.sharing.NativeGateReason
import com.cruxcoach.domain.sharing.RelationshipStatus
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle

/**
 * Turns domain states into the strings the screens say.
 *
 * Every mapping is an exhaustive `when`, so a new circle, category, status,
 * decision source or gate reason cannot be added without a string to explain
 * it. That matters more here than in most screens: the whole point of this UI
 * is to say *why* something is or is not shared, and an unlabelled state is a
 * screen that shrugs.
 */
object SharingLabels {

    @StringRes
    fun circle(circle: SharingCircle): Int = when (circle) {
        SharingCircle.ALL_OTHER_USERS -> R.string.sharing_circle_all_other_users
        SharingCircle.ACQUAINTANCES -> R.string.sharing_circle_acquaintances
        SharingCircle.FRIENDS -> R.string.sharing_circle_friends
    }

    @StringRes
    fun category(category: SharingCategory): Int = when (category) {
        SharingCategory.PROFILE_AND_GOALS -> R.string.sharing_category_profile_and_goals
        SharingCategory.TRAINING_HISTORY -> R.string.sharing_category_training_history
        SharingCategory.VIDEOS -> R.string.sharing_category_videos
        SharingCategory.HEALTH_INFORMATION -> R.string.sharing_category_health_information
        SharingCategory.PRIVATE_NOTES -> R.string.sharing_category_private_notes
    }

    @StringRes
    fun status(status: RelationshipStatus): Int = when (status) {
        RelationshipStatus.PENDING -> R.string.sharing_status_pending
        RelationshipStatus.ACCEPTED -> R.string.sharing_status_accepted
        RelationshipStatus.DECLINED -> R.string.sharing_status_declined
        RelationshipStatus.DELIVERY_UNCLEAR -> R.string.sharing_status_delivery_unclear
        RelationshipStatus.REVOKED -> R.string.sharing_status_revoked
        RelationshipStatus.PURGE_PENDING -> R.string.sharing_status_purge_pending
        RelationshipStatus.PURGED -> R.string.sharing_status_purged
        RelationshipStatus.FAIL_CLOSED -> R.string.sharing_status_fail_closed
    }

    @StringRes
    fun source(source: DecisionSource): Int = when (source) {
        DecisionSource.OBJECT_DENY -> R.string.sharing_source_object_deny
        DecisionSource.OBJECT_ALLOW -> R.string.sharing_source_object_allow
        DecisionSource.PERSON_DENY -> R.string.sharing_source_person_deny
        DecisionSource.PERSON_ALLOW -> R.string.sharing_source_person_allow
        DecisionSource.CIRCLE_BASELINE -> R.string.sharing_source_circle_baseline
        DecisionSource.INHERITED_CIRCLE_BASELINE -> R.string.sharing_source_inherited
        DecisionSource.NO_BASELINE_DEFAULT_DENY -> R.string.sharing_source_default_deny
        DecisionSource.PEER_UNKNOWN_FAIL_CLOSED -> R.string.sharing_source_peer_unknown
        DecisionSource.RELATIONSHIP_UNKNOWN_FAIL_CLOSED -> R.string.sharing_source_relationship_unknown
        DecisionSource.LEDGER_FAIL_CLOSED -> R.string.sharing_source_ledger_fail_closed
        DecisionSource.RELATIONSHIP_PENDING_CONSENT -> R.string.sharing_source_pending_consent
        DecisionSource.RELATIONSHIP_DECLINED -> R.string.sharing_source_declined
        DecisionSource.RELATIONSHIP_REVOKED -> R.string.sharing_source_revoked
        DecisionSource.RELATIONSHIP_PURGE_PENDING -> R.string.sharing_source_purge_pending
        DecisionSource.DELIVERY_UNCLEAR -> R.string.sharing_source_delivery_unclear
        DecisionSource.DEVICE_NOT_AUTHORISED -> R.string.sharing_source_device_not_authorised
        DecisionSource.RESOURCE_EPOCH_STALE -> R.string.sharing_source_epoch_stale
        DecisionSource.CONSENT_EXPANSION_PENDING -> R.string.sharing_source_consent_expansion
        DecisionSource.AWAITING_REVOKE_SYNC -> R.string.sharing_source_awaiting_revoke_sync
    }

    /** These two read "from the circle X", so they need the circle's name. */
    fun sourceTakesCircle(source: DecisionSource): Boolean =
        source == DecisionSource.CIRCLE_BASELINE || source == DecisionSource.INHERITED_CIRCLE_BASELINE

    @StringRes
    fun peerIdError(error: PeerIdParseError): Int = when (error) {
        PeerIdParseError.EMPTY -> R.string.sharing_invite_error_empty
        PeerIdParseError.NOT_A_PUBLIC_KEY -> R.string.sharing_invite_error_not_a_public_key
        PeerIdParseError.MALFORMED -> R.string.sharing_invite_error_malformed
    }

    @StringRes
    fun writeError(error: SharingWriteError): Int = when (error) {
        SharingWriteError.SIGNER_UNAVAILABLE -> R.string.sharing_write_error_signer
        SharingWriteError.REJECTED -> R.string.sharing_write_error_rejected
        SharingWriteError.NOT_AUTHORISED -> R.string.sharing_write_error_not_authorised
        SharingWriteError.RECOVERY_LOCKED -> R.string.sharing_write_error_recovery_locked
    }

    @StringRes
    fun backupError(error: SharingBackupError?): Int = when (error) {
        SharingBackupError.WRONG_RECOVERY_CODE -> R.string.sharing_backup_error_code
        SharingBackupError.WRONG_IDENTITY -> R.string.sharing_backup_error_identity
        SharingBackupError.BAD_SIGNATURE -> R.string.sharing_backup_error_signature
        SharingBackupError.NOT_A_BACKUP -> R.string.sharing_backup_error_not_a_backup
        SharingBackupError.UNKNOWN_VERSION -> R.string.sharing_backup_error_version
        SharingBackupError.TOO_LARGE, SharingBackupError.TRUNCATED -> R.string.sharing_backup_error_damaged
        SharingBackupError.INVALID_RECOVERY_CODE -> R.string.sharing_backup_error_no_code
        SharingBackupError.SIGNER_UNAVAILABLE -> R.string.sharing_write_error_signer
        SharingBackupError.MALFORMED_CONTENT, null -> R.string.sharing_backup_error_damaged
    }

    @StringRes
    fun gateReason(reason: NativeGateReason): Int = when (reason) {
        NativeGateReason.NO_EVIDENCE -> R.string.sharing_reason_no_evidence
        NativeGateReason.CROSS_SEAM_CONVERGENCE_DIVERGENT -> R.string.sharing_reason_cross_seam
        NativeGateReason.ARTIFACT_CHECKSUM_UNVERIFIED -> R.string.sharing_reason_checksum
        NativeGateReason.NOT_INTEGRATED_VIA_DEPENDENCY_STRATEGY -> R.string.sharing_reason_dependency
        NativeGateReason.NO_ON_DEVICE_INSTRUMENTATION_RUN -> R.string.sharing_reason_on_device
        NativeGateReason.DURABLE_PROJECTION_GATE_OPEN -> R.string.sharing_reason_projection
        NativeGateReason.OUTBOUND_RESUME_CORRELATION_UNFIXED -> R.string.sharing_reason_resume
    }
}
