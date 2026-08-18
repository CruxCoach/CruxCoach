package com.cruxcoach.android.competition

import com.cruxcoach.domain.competition.CompetitionProtocol
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The local-relay path, and the line it must not cross.
 *
 * Talking to the development relay needs cleartext to loopback, which the app
 * otherwise forbids everywhere. That exception exists in the debug build only,
 * and this test is what stops it being copied into the shipped one — a release
 * APK that permits cleartext to localhost is a downgrade that nothing else here
 * would notice.
 */
class CompetitionDevRelayPolicyTest {

    private fun config(sourceSet: String): String {
        val file = listOf(
            File("src/$sourceSet/res/xml/network_security_config.xml"),
            File("androidApp/src/$sourceSet/res/xml/network_security_config.xml"),
        ).firstOrNull { it.isFile } ?: error("no network security config for $sourceSet")
        return file.readText()
    }

    @Test
    fun `the shipped policy permits no cleartext to loopback`() {
        val release = config("main")
        assertFalse(release.contains("127.0.0.1"), "release must not allow cleartext to loopback")
        assertFalse(release.contains("localhost"), "release must not allow cleartext to loopback")
        assertTrue(release.contains("cleartextTrafficPermitted=\"false\""))
    }

    @Test
    fun `the debug policy permits exactly loopback, and keeps the rest strict`() {
        val debug = config("debug")
        assertTrue(debug.contains("127.0.0.1"), "the runbook depends on this")
        assertTrue(debug.contains("localhost"))
        assertTrue(
            debug.contains("<base-config cleartextTrafficPermitted=\"false\">"),
            "everything that is not named stays https",
        )
    }

    @Test
    fun `the relay URL the runbook uses is one both clients accept`() {
        // The runbook says `adb reverse tcp:7447 tcp:7447` and then a
        // competition whose relay is ws://127.0.0.1:7447. If the allowlist ever
        // stops accepting that, the runbook stops being executable.
        assertTrue(CompetitionProtocol.isAllowedRelayUrl("ws://127.0.0.1:7447"))
        assertTrue(CompetitionProtocol.isLoopbackRelay("ws://127.0.0.1:7447"))
        assertTrue(CompetitionProtocol.isAllowedRelayUrl("ws://localhost:7447"))
        assertTrue(CompetitionProtocol.isAllowedRelayUrl("ws://[::1]:7447"))
    }

    @Test
    fun `the emulator host alias is not accepted, which is why adb reverse is the instruction`() {
        // 10.0.2.2 is the emulator's route to the host, and it is not loopback:
        // both clients refuse a cleartext relay there. An older version of the
        // runbook told people to use it, and it could never have worked.
        assertFalse(CompetitionProtocol.isAllowedRelayUrl("ws://10.0.2.2:7447"))
        assertFalse(CompetitionProtocol.isLoopbackRelay("ws://10.0.2.2:7447"))
    }
}
