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

    @Test fun partial_failure_shows_short_problem_with_retry_and_report() {
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
        compose.onNodeWithText("Kilter hat den Upload abgelehnt. Bitte erneut versuchen.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("HTTP 422", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("Jetzt synchronisieren")[1].performScrollTo().performClick()
        compose.onNodeWithText("Bug melden").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, reports)
        }
    }
    @Test fun success_is_a_single_line_without_technical_details_or_actions() {
        compose.setContent {
            MaterialTheme {
                KilterLogbookSyncStatus(KilterAccountState(pushEnabled = true,
                    uploadStatus = KilterUploadStatus(uploaded = 200)), {}, {}, {})
            }
        }
        compose.onNodeWithText("Logbuch synchronisiert").assertIsDisplayed()
        compose.onNodeWithText("Übertragen:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Letzter Übertragungsversuch", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Jetzt synchronisieren").assertDoesNotExist()
    }

    @Test fun disabled_upload_does_not_show_stale_success_or_retry() {
        compose.setContent {
            MaterialTheme {
                KilterLogbookSyncStatus(KilterAccountState(pushEnabled = false,
                    uploadStatus = KilterUploadStatus()), {}, {}, {})
            }
        }
        compose.onNodeWithText("Upload zu Kilter ausgeschaltet").assertIsDisplayed()
        compose.onNodeWithText("Logbuch synchronisiert").assertDoesNotExist()
        compose.onNodeWithText("Jetzt synchronisieren").assertDoesNotExist()
    }

    @Test fun missing_status_does_not_claim_success() {
        compose.setContent {
            MaterialTheme {
                KilterLogbookSyncStatus(KilterAccountState(pushEnabled = true), {}, {}, {})
            }
        }
        compose.onNodeWithText("Synchronisierung noch nicht geprüft").assertIsDisplayed()
        compose.onNodeWithText("Logbuch synchronisiert").assertDoesNotExist()
    }

    @Test fun syncing_hides_stale_success_and_retry_actions() {
        compose.setContent {
            MaterialTheme {
                KilterLogbookSyncStatus(KilterAccountState(pushEnabled = true, isSyncing = true,
                    uploadStatus = KilterUploadStatus()), {}, {}, {})
            }
        }
        compose.onNodeWithText("Logbuch wird synchronisiert…").assertIsDisplayed()
        compose.onNodeWithText("Logbuch synchronisiert").assertDoesNotExist()
        compose.onNodeWithText("Jetzt synchronisieren").assertDoesNotExist()
    }
}
