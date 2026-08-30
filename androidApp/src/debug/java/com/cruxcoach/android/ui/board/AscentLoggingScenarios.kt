package com.cruxcoach.android.ui.board

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.domain.board.AttemptLogSubmissionState

internal enum class AscentLoggingScenarioKind { FORM, SUCCESS }

/** Debug-only, side-effect-free input shared by previews and semantics tests. */
internal data class AscentLoggingScenario(
    val id: String,
    val isEditing: Boolean,
    val isSend: Boolean,
    val attemptCount: Int,
    val quality: Int,
    val comment: String,
    val isBenchmark: Boolean,
    val submissionState: AttemptLogSubmissionState = AttemptLogSubmissionState.EDITING,
    val kind: AscentLoggingScenarioKind = AscentLoggingScenarioKind.FORM,
)

internal object AscentLoggingScenarios {
    val NewSend = AscentLoggingScenario(
        id = "log/new-send",
        isEditing = false,
        isSend = true,
        attemptCount = 1,
        quality = 0,
        comment = "",
        isBenchmark = false,
    )

    val NewAttempt = AscentLoggingScenario(
        id = "log/new-attempt",
        isEditing = false,
        isSend = false,
        attemptCount = 3,
        quality = 0,
        comment = "",
        isBenchmark = false,
    )

    val EditSend = AscentLoggingScenario(
        id = "log/edit-send",
        isEditing = true,
        isSend = true,
        attemptCount = 2,
        quality = 4,
        comment = "Matched the heel on the second burn.",
        isBenchmark = true,
    )

    val Success = NewSend.copy(
        id = "log/success",
        kind = AscentLoggingScenarioKind.SUCCESS,
    )

    val Error = EditSend.copy(
        id = "log/error",
        submissionState = AttemptLogSubmissionState.FAILED,
    )

    val all = sequenceOf(NewSend, NewAttempt, EditSend, Success, Error)

    fun require(id: String): AscentLoggingScenario = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown DesignLab scenario: $id")
}

internal class AscentLoggingScenarioProvider : PreviewParameterProvider<AscentLoggingScenario> {
    override val values: Sequence<AscentLoggingScenario> = AscentLoggingScenarios.all
}

/** Full render-axis matrix required by docs/refactor/ui-scenario-matrix.json. */
@Preview(name = "EN light compact", group = "Log attempt", locale = "en", widthDp = 360, heightDp = 720)
@Preview(name = "DE light compact", group = "Log attempt", locale = "de", widthDp = 360, heightDp = 720)
@Preview(name = "EN dark compact", group = "Log attempt", locale = "en", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact", group = "Log attempt", locale = "de", widthDp = 360, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded", group = "Log attempt", locale = "en", widthDp = 840, heightDp = 720)
@Preview(name = "DE light expanded", group = "Log attempt", locale = "de", widthDp = 840, heightDp = 720)
@Preview(name = "EN dark expanded", group = "Log attempt", locale = "en", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded", group = "Log attempt", locale = "de", widthDp = 840, heightDp = 720, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light compact large", group = "Log attempt large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light compact large", group = "Log attempt large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark compact large", group = "Log attempt large text", locale = "en", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark compact large", group = "Log attempt large text", locale = "de", widthDp = 360, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "EN light expanded large", group = "Log attempt large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "DE light expanded large", group = "Log attempt large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f)
@Preview(name = "EN dark expanded large", group = "Log attempt large text", locale = "en", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "DE dark expanded large", group = "Log attempt large text", locale = "de", widthDp = 840, heightDp = 720, fontScale = 1.5f, uiMode = Configuration.UI_MODE_NIGHT_YES)
internal annotation class LogAttemptPreviewMatrix

@LogAttemptPreviewMatrix
@Composable
private fun AscentLoggingDialogPreview(
    @PreviewParameter(AscentLoggingScenarioProvider::class) scenario: AscentLoggingScenario,
) {
    CruxCoachTheme { AscentLoggingScenarioContent(scenario) }
}

@Composable
internal fun AscentLoggingScenarioContent(scenario: AscentLoggingScenario) {
    if (scenario.kind == AscentLoggingScenarioKind.SUCCESS) {
        AttemptLogConfirmation(
            climbName = "Quiet Riot",
            gradeLabel = "6c+",
            angle = 40,
            isSend = scenario.isSend,
            onViewLogbook = {},
        )
        return
    }
    var current by remember(scenario.id) { mutableStateOf(scenario) }
    AscentLoggingDialog(
        isEditing = current.isEditing,
        isSend = current.isSend,
        bidCount = current.attemptCount,
        quality = current.quality,
        comment = current.comment,
        isBenchmark = current.isBenchmark,
        onIsBenchmarkChanged = { current = current.copy(isBenchmark = it) },
        onIsSendChanged = { current = current.copy(isSend = it) },
        onBidCountChanged = { current = current.copy(attemptCount = it.coerceAtLeast(1)) },
        onQualityChanged = { current = current.copy(quality = it) },
        onCommentChanged = { current = current.copy(comment = it) },
        onSave = {},
        onDismiss = {},
        submissionState = current.submissionState,
    )
}
