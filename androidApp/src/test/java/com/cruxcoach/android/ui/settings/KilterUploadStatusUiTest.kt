package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.data.kilter.KilterUploadReason
import com.cruxcoach.android.data.kilter.KilterUploadStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h480dp")
class KilterUploadStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partial_failure_displays_remaining_entries_and_exposes_retry_and_report() {
        var retries = 0
        var reports = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    KilterAccountSection(
                        state = KilterAccountState(isConnected = true, pushEnabled = true,
                            uploadStatus = KilterUploadStatus(uploaded = 200, pending = 1,
                                reason = KilterUploadReason.HTTP, httpStatus = 422)),
                        onShowLogin = {}, onDismissLogin = {}, onEmailChanged = {}, onPasswordChanged = {},
                        onLogin = {}, onImportOneTime = {}, onImportPersistent = {}, onDismissPreview = {},
                        onSyncNow = {}, onPushEnabledChanged = {}, onClimbPublishEnabledChanged = {},
                        onDisconnect = {}, onShowDisconnectConfirm = {}, onDismissDisconnectConfirm = {},
                        onDismissResult = {}, onRetryPublishQueueNow = {},
                        onRetryUpload = { retries++ }, onReportUpload = { reports++ },
                    )
                }
            }
        }
        compose.onNodeWithText("Übertragen: 200 · Noch ausstehend: 1", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HTTP 422", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Upload wiederholen").performScrollTo().performClick()
        compose.onNodeWithText("Bug melden").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, reports)
        }
    }
}
