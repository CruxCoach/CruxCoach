package com.cruxcoach.app.ui

import com.cruxcoach.app.playlist.LocalOnlyTransport
import com.cruxcoach.app.playlist.PlaylistPlayerPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/** The player's attempt/send buttons log the climb on the wall before the queue moves on (Android does). */
class PlayerLoggingTest {
    @Test
    fun `a send is logged for the current climb at its queued angle before the queue advances`() {
        val logged = mutableListOf<Triple<String, Int, Boolean>>()
        val presenter = PlaylistPlayerPresenter(
            transport = LocalOnlyTransport,
            scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher()),
        )
        val model = PlayerScreenModel(presenter, logClimb = { uuid, angle, isSend -> logged += Triple(uuid, angle, isSend) })
        model.play("List", listOf("a", "b"), listOf(35, 45), listOf(0, 0), "list", "afterSend", 0)
        model.climbLogged(isSend = false)
        model.climbLogged(isSend = true)
        assertEquals(listOf(Triple("a", 35, false), Triple("a", 35, true)), logged)
        assertEquals("b", model.currentState.currentClimbUuid)
    }
}
