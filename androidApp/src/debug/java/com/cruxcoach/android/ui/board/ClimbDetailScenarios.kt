package com.cruxcoach.android.ui.board

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardConnectionState
import com.cruxcoach.domain.board.BoardDeliveryDecision
import com.cruxcoach.domain.board.BoardDeliveryTarget
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.ClimbDetailDeliveryState
import com.cruxcoach.domain.board.ClimbDetailIdentity
import com.cruxcoach.domain.board.ClimbDetailScreenState
import com.cruxcoach.domain.board.HoldRole

internal data class ClimbDetailScenario(
    val id: String,
    val state: ClimbDetailScreenState.Content,
)

internal object ClimbDetailScenarios {
    private val identity = ClimbDetailIdentity(
        uuid = "quiet-riot",
        name = "Quiet Riot",
        setterName = "Alex",
        boardBrand = BoardBrand.KILTER,
        layoutId = 1,
        angle = 40,
        difficultyAverage = 21.0,
        qualityAverage = 4.4,
        isBenchmark = false,
        isRoute = false,
        isMirrored = false,
        isMirrorable = false,
    )
    private val holds = listOf(
        BoardHold(11, HoldRole.START),
        BoardHold(35, HoldRole.HAND),
        BoardHold(63, HoldRole.HAND),
        BoardHold(88, HoldRole.FINISH),
    )

    val Disconnected = ClimbDetailScenario(
        id = "detail/disconnected",
        state = content(
            connection = BoardConnectionState.DISCONNECTED,
            decision = BoardDeliveryDecision(BoardDeliveryTarget.NONE, false, false),
        ),
    )
    val Connected = ClimbDetailScenario(
        id = "detail/connected",
        state = content(
            connection = BoardConnectionState.CONNECTED,
            decision = BoardDeliveryDecision(BoardDeliveryTarget.DIRECT_BOARD, false, true),
        ),
    )

    val all = sequenceOf(Disconnected, Connected)

    fun require(id: String): ClimbDetailScenario = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown DesignLab scenario: $id")

    private fun content(
        connection: BoardConnectionState,
        decision: BoardDeliveryDecision,
    ) = ClimbDetailScreenState.Content(
        identity = identity,
        holds = holds,
        availableAngles = listOf(30, 35, 40, 45),
        delivery = ClimbDetailDeliveryState(connection, decision),
        isFavorited = false,
        isIgnored = false,
        hasPersonalNote = false,
        loggedAscentCount = 2,
    )
}

internal class ClimbDetailScenarioProvider : PreviewParameterProvider<ClimbDetailScenario> {
    override val values: Sequence<ClimbDetailScenario> = ClimbDetailScenarios.all
}

@Preview(name = "EN light compact", group = "Climb detail", locale = "en", widthDp = 360, heightDp = 720)
@Preview(name = "DE light compact", group = "Climb detail", locale = "de", widthDp = 360, heightDp = 720)
@Preview(name = "EN dark compact", group = "Climb detail", locale = "en", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact", group = "Climb detail", locale = "de", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded", group = "Climb detail", locale = "en", widthDp = 840, heightDp = 720)
@Preview(name = "DE light expanded", group = "Climb detail", locale = "de", widthDp = 840, heightDp = 720)
@Preview(name = "EN dark expanded", group = "Climb detail", locale = "en", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded", group = "Climb detail", locale = "de", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light compact large", group = "Climb detail large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light compact large", group = "Climb detail large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark compact large", group = "Climb detail large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact large", group = "Climb detail large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded large", group = "Climb detail large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light expanded large", group = "Climb detail large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark expanded large", group = "Climb detail large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded large", group = "Climb detail large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class ClimbDetailPreviewMatrix

@ClimbDetailPreviewMatrix
@Composable
private fun ClimbDetailPreview(
    @PreviewParameter(ClimbDetailScenarioProvider::class) scenario: ClimbDetailScenario,
) {
    CruxCoachTheme { ClimbDetailScenarioContent(scenario) }
}

@Composable
internal fun ClimbDetailScenarioContent(
    scenario: ClimbDetailScenario,
    onConnect: () -> Unit = {},
    onDeliver: () -> Unit = {},
    onLogAttempt: () -> Unit = {},
    onLogSend: () -> Unit = {},
) {
    ClimbDetailHero(
        state = scenario.state,
        gradeLabel = "6c+",
        boardLabel = "Kilter Original 12×12",
        onConnect = onConnect,
        onDeliver = onDeliver,
        onLogAttempt = onLogAttempt,
        onLogSend = onLogSend,
    ) {
        FixtureBoard(scenario.state.identity.name)
    }
}

@Composable
private fun FixtureBoard(climbName: String) {
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val start = CruxCoachDesign.colors.positive
    val hand = MaterialTheme.colorScheme.primary
    val finish = CruxCoachDesign.colors.brandAccent
    val description = stringResource(R.string.detail_board_semantics, climbName)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { contentDescription = description },
    ) {
        repeat(9) { index ->
            val fraction = (index + 1) / 10f
            drawLine(grid, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
            drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
        }
        drawCircle(start, radius = 12.dp.toPx(), center = Offset(size.width * 0.25f, size.height * 0.78f))
        drawCircle(hand, radius = 10.dp.toPx(), center = Offset(size.width * 0.42f, size.height * 0.56f))
        drawCircle(hand, radius = 10.dp.toPx(), center = Offset(size.width * 0.63f, size.height * 0.38f))
        drawCircle(finish, radius = 12.dp.toPx(), center = Offset(size.width * 0.76f, size.height * 0.18f))
    }
}
