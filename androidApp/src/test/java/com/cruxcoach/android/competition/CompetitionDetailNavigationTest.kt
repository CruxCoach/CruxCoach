package com.cruxcoach.android.competition

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains

class CompetitionDetailNavigationTest {

    private fun detailSource(): String = listOf(
        File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionDetailScreen.kt"),
        File("androidApp/src/main/java/com/cruxcoach/android/ui/competition/CompetitionDetailScreen.kt"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("CompetitionDetailScreen.kt not found (cwd=${File(".").absolutePath})")

    @Test
    fun `host work is split into focused restorable destinations`() {
        val source = detailSource()

        assertContains(source, "private enum class HostRoute { SETUP, ENTRANTS, LIVE }")
        assertContains(source, "rememberSaveable(competitionKey)")
        assertContains(source, "competition_host_nav_${'$'}{target.name.lowercase()}")
        assertContains(source, "HostRoute.SETUP to R.string.comp_host_nav_setup")
        assertContains(source, "HostRoute.ENTRANTS to R.string.comp_host_nav_entrants")
        assertContains(source, "HostRoute.LIVE to R.string.comp_host_nav_live")
        assertContains(source, "BackHandler(effectiveRoute != HostRoute.SETUP)")
        assertContains(source, "if (route == HostRoute.SETUP)")
        assertContains(source, "if (route == HostRoute.ENTRANTS)")
        assertContains(source, "if (route == HostRoute.LIVE)")
        assertContains(source, "viewModel.hostDisqualify(pubkey, disqualifyReason)")
        assertContains(source, "comp_host_disqualify_hint")
        assertContains(source, "ui.preparedClimbId")
        assertContains(source, "PreparedChoice.Untrusted")
        assertContains(source, "viewModel::retryPreparedChoice")
        assertContains(source, "!ui.snapshot.intentHistoryComplete")
        assertContains(source, "competition_host_retry_intents")
        assertContains(source, "return@Column")
    }
}
