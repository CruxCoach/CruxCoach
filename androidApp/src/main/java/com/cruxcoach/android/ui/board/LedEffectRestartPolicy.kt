package com.cruxcoach.android.ui.board

import com.cruxcoach.android.led.LedEffectStatus
import com.cruxcoach.domain.board.ClimbUuid

/** What "Restart" for an ended LED effect does on a climb's page. */
internal enum class LedEffectRestart {
    /** Not offered: the effect did not end on this climb, or the page can't put it back. */
    NONE,

    /** Light the climb again, like the lamp. */
    LIGHT,

    /** Put the shared session's current climb back on the wall, as its host. */
    SESSION_RESEND,
}

/**
 * Restart is offered only on the climb whose effect ended, and only where it
 * sends that same climb again: a new send is what starts an effect afresh. In a
 * shared session the queue owns the wall, so Restart resends the session's
 * current climb rather than adding a copy to everyone's queue — and is not
 * offered on any other climb. Only the host writes to the wall in a session; a
 * participant's own board link never shows that resend, so it gets no Restart.
 *
 * Climb ids are compared by [ClimbUuid.normKey]: ids that came over the session
 * link are upper case without dashes, the catalogue's are not.
 */
internal object LedEffectRestartPolicy {
    fun resolve(
        status: LedEffectStatus,
        litClimbUuid: String?,
        detailClimbUuid: String?,
        deliveryTarget: BoardDeliveryTarget,
        sessionHost: Boolean,
        sessionClimbUuid: String?,
    ): LedEffectRestart = when {
        status != LedEffectStatus.ENDED || !sameClimb(litClimbUuid, detailClimbUuid) -> LedEffectRestart.NONE
        deliveryTarget == BoardDeliveryTarget.DIRECT_BOARD -> LedEffectRestart.LIGHT
        deliveryTarget == BoardDeliveryTarget.SHARED_QUEUE && sessionHost &&
            sameClimb(sessionClimbUuid, detailClimbUuid) -> LedEffectRestart.SESSION_RESEND
        else -> LedEffectRestart.NONE
    }

    private fun sameClimb(a: String?, b: String?): Boolean =
        a != null && b != null && ClimbUuid.normKey(a) == ClimbUuid.normKey(b)
}
