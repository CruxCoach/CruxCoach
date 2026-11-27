package com.cruxcoach.android.ui.board

import com.cruxcoach.android.led.LedEffectStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Restart for an ended LED effect: only on that climb, and only where it sends that climb again. */
class LedEffectRestartPolicyTest {

    private val here = "1a2b3c4d-aaaa-bbbb-cccc-000000000001"
    private val other = "1a2b3c4d-aaaa-bbbb-cccc-000000000002"

    /** How an id comes back over the session link. */
    private fun linked(uuid: String) = uuid.replace("-", "").uppercase()

    private fun resolve(
        status: LedEffectStatus = LedEffectStatus.ENDED,
        lit: String? = here,
        target: BoardDeliveryTarget = BoardDeliveryTarget.DIRECT_BOARD,
        host: Boolean = target == BoardDeliveryTarget.SHARED_QUEUE,
        session: String? = null,
    ) = LedEffectRestartPolicy.resolve(
        status = status,
        litClimbUuid = lit,
        detailClimbUuid = here,
        deliveryTarget = target,
        sessionHost = host,
        sessionClimbUuid = session,
    )

    @Test
    fun aDirectBoardLightsTheClimbItEndedOnAgain() {
        assertEquals(LedEffectRestart.LIGHT, resolve())
    }

    @Test
    fun anotherClimbsPageOffersNoRestart() {
        assertEquals(LedEffectRestart.NONE, resolve(lit = other))
        assertEquals(LedEffectRestart.NONE, resolve(lit = null))
        assertEquals(
            LedEffectRestart.NONE,
            resolve(lit = other, target = BoardDeliveryTarget.SHARED_QUEUE, session = here),
        )
    }

    @Test
    fun onlyAnEndedEffectRestarts() {
        LedEffectStatus.entries.filter { it != LedEffectStatus.ENDED }.forEach {
            assertEquals(it.name, LedEffectRestart.NONE, resolve(status = it))
        }
    }

    @Test
    fun aSharedSessionResendsItsCurrentClimbOnlyOnThatClimb() {
        assertEquals(
            LedEffectRestart.SESSION_RESEND,
            resolve(target = BoardDeliveryTarget.SHARED_QUEUE, session = here),
        )
        assertEquals(
            LedEffectRestart.NONE,
            resolve(target = BoardDeliveryTarget.SHARED_QUEUE, session = other),
        )
        assertEquals(
            LedEffectRestart.NONE,
            resolve(target = BoardDeliveryTarget.SHARED_QUEUE, session = null),
        )
    }

    @Test
    fun idsMatchHoweverTheyAreSpelled() {
        assertEquals(LedEffectRestart.LIGHT, resolve(lit = linked(here)))
        assertEquals(
            LedEffectRestart.SESSION_RESEND,
            resolve(lit = linked(here), target = BoardDeliveryTarget.SHARED_QUEUE, session = linked(here)),
        )
        assertEquals(LedEffectRestart.NONE, resolve(lit = linked(other)))
    }

    @Test
    fun aParticipantGetsNoRestart() {
        assertEquals(
            LedEffectRestart.NONE,
            resolve(target = BoardDeliveryTarget.SHARED_QUEUE, host = false, session = here),
        )
    }

    @Test
    fun nothingToSendWithOffersNoRestart() {
        assertEquals(LedEffectRestart.NONE, resolve(target = BoardDeliveryTarget.NONE))
    }
}
