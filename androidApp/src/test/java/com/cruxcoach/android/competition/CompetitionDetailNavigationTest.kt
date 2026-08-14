package com.cruxcoach.android.competition

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CompetitionDetailNavigationTest {

    private fun detailSource(): String = listOf(
        File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionDetailScreen.kt"),
        File("androidApp/src/main/java/com/cruxcoach/android/ui/competition/CompetitionDetailScreen.kt"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("CompetitionDetailScreen.kt not found (cwd=${File(".").absolutePath})")

    private fun prizePanelSource(): String = listOf(
        File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionHostPrizePanel.kt"),
        File("androidApp/src/main/java/com/cruxcoach/android/ui/competition/CompetitionHostPrizePanel.kt"),
    ).firstOrNull(File::isFile)?.readText()
        ?: error("CompetitionHostPrizePanel.kt not found (cwd=${File(".").absolutePath})")

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
        assertContains(source, "it.op == \"climb_choice\" && it.pubkey == current")
        assertContains(source, "ui.secondsToDeadline(nowSeconds) == 0L")
        assertContains(source, "viewModel.hostAttempt(current, activeClimbId, \"timeout\", currentChoice?.eventId)")
        assertContains(source, "if (confirmFinish) AlertDialog")
        assertContains(source, "if (confirmSkip) AlertDialog")
        assertContains(source, "if (confirmCleanup) AlertDialog")
        assertContains(source, "viewModel.hostConfirmPayment(pubkey, paymentReason)")
        assertContains(source, "competition_host_payment_confirm")
        assertContains(source, "viewModel.hostVerifyPayment(paymentClaim)")
        assertContains(source, "competition_host_payment_verify_")
        assertContains(source, "viewModel.hostCorrectResult(selected!!.seq, chosenOutcome, correctionReason)")
        assertContains(source, "competition_host_correction_open")
        assertContains(source, "it.supersedesResults")
        assertContains(source, "latestCorrections.containsKey(entry.seq)")
        assertContains(source, "comp_host_correction_amended")
        assertFalse(source.contains("viewModel.hostQueue(\"close_turn\")"))

        val prizePanel = prizePanelSource()
        assertContains(source, "CompetitionHostPrizePanel(ui, viewModel, action)")
        assertContains(prizePanel, "viewModel.reviewPrizeClaim(claim)")
        assertContains(prizePanel, "review.resultsHash != currentHash")
        assertContains(prizePanel, "viewModel.hostPrizeDecision(prize.id, winner.pubkey, \"approved\")")
        assertContains(prizePanel, "competition_host_prize_paid_confirm")
        assertContains(prizePanel, "R.string.comp_host_prize_receipt_no")
    }
}
