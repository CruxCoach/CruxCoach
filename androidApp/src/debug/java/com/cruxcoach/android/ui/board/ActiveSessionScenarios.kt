package com.cruxcoach.android.ui.board

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.cruxcoach.android.ui.theme.CruxCoachSpacing
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.ActiveSessionClimb
import com.cruxcoach.domain.board.ActiveSessionPhase
import com.cruxcoach.domain.board.ActiveSessionState
import com.cruxcoach.domain.board.BoardConnectionState

internal data class ActiveSessionScenario(
    val id: String,
    val state: ActiveSessionState,
)

internal object ActiveSessionScenarios {
    private val base = ActiveSessionState(
        sessionId = "session-2026-08-30",
        startedAt = "2026-08-30T11:30:00Z",
        phase = ActiveSessionPhase.ACTIVE,
        elapsedSeconds = 1_800,
        pausedSeconds = 120,
        sendCount = 3,
        attemptCount = 7,
        currentClimb = ActiveSessionClimb("quiet-riot", "Quiet Riot", 40, false),
        connection = BoardConnectionState.CONNECTED,
    )

    val Active = ActiveSessionScenario("session/active", base)
    val Resting = ActiveSessionScenario(
        "session/resting",
        base.copy(phase = ActiveSessionPhase.RESTING, restSecondsRemaining = 75),
    )
    val Paused = ActiveSessionScenario(
        "session/paused",
        base.copy(
            phase = ActiveSessionPhase.PAUSED,
            restSecondsRemaining = null,
            connection = BoardConnectionState.DISCONNECTED,
        ),
    )
    val ActiveNoClimb = ActiveSessionScenario(
        "session/active-no-climb",
        base.copy(currentClimb = null),
    )

    val all = sequenceOf(Active, Resting, Paused, ActiveNoClimb)

    fun require(id: String): ActiveSessionScenario = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown DesignLab scenario: $id")
}

internal class ActiveSessionScenarioProvider : PreviewParameterProvider<ActiveSessionScenario> {
    override val values: Sequence<ActiveSessionScenario> = ActiveSessionScenarios.all
}

@Preview(name = "EN light compact", group = "Active session", locale = "en", widthDp = 360, heightDp = 360)
@Preview(name = "DE light compact", group = "Active session", locale = "de", widthDp = 360, heightDp = 360)
@Preview(name = "EN dark compact", group = "Active session", locale = "en", widthDp = 360, heightDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact", group = "Active session", locale = "de", widthDp = 360, heightDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded", group = "Active session", locale = "en", widthDp = 840, heightDp = 360)
@Preview(name = "DE light expanded", group = "Active session", locale = "de", widthDp = 840, heightDp = 360)
@Preview(name = "EN dark expanded", group = "Active session", locale = "en", widthDp = 840, heightDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded", group = "Active session", locale = "de", widthDp = 840, heightDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light compact large", group = "Active session large text", locale = "en", widthDp = 360, heightDp = 480, fontScale = 1.5f)
@Preview(name = "DE light compact large", group = "Active session large text", locale = "de", widthDp = 360, heightDp = 480, fontScale = 1.5f)
@Preview(name = "EN dark compact large", group = "Active session large text", locale = "en", widthDp = 360, heightDp = 480, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact large", group = "Active session large text", locale = "de", widthDp = 360, heightDp = 480, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded large", group = "Active session large text", locale = "en", widthDp = 840, heightDp = 480, fontScale = 1.5f)
@Preview(name = "DE light expanded large", group = "Active session large text", locale = "de", widthDp = 840, heightDp = 480, fontScale = 1.5f)
@Preview(name = "EN dark expanded large", group = "Active session large text", locale = "en", widthDp = 840, heightDp = 480, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded large", group = "Active session large text", locale = "de", widthDp = 840, heightDp = 480, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class ActiveSessionPreviewMatrix

@ActiveSessionPreviewMatrix
@Composable
private fun ActiveSessionPreview(
    @PreviewParameter(ActiveSessionScenarioProvider::class) scenario: ActiveSessionScenario,
) {
    CruxCoachTheme { ActiveSessionScenarioContent(scenario) }
}

@Composable
internal fun ActiveSessionScenarioContent(scenario: ActiveSessionScenario) {
    ActiveSessionContinueCard(
        state = scenario.state,
        onContinue = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(CruxCoachSpacing.large),
    )
}
