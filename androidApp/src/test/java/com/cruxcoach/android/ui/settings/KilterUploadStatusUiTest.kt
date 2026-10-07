package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.data.kilter.KilterNotUploadedEntry
import com.cruxcoach.android.data.kilter.KilterNotUploadedReason
import com.cruxcoach.android.data.kilter.KilterUploadReason
import com.cruxcoach.android.data.kilter.KilterUploadStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test fun held_imported_entries_are_explained_and_uploaded_only_after_confirmation() {
        var uploads = 0
        compose.setContent {
            MaterialTheme {
                KilterLogbookSyncStatus(KilterAccountState(pushEnabled = true,
                    uploadStatus = KilterUploadStatus(uploaded = 3, heldImported = 12, rejectedByKilter = 1)),
                    {}, {}, {}, { uploads++ })
            }
        }
        compose.onNodeWithText("12 Einträge aus einem Aurora-Export", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kilter kennt den Climb von 1 Eintrag unter keiner ID", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Importierte übertragen").performClick()
        compose.onNodeWithText("Importierte Einträge übertragen?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, uploads) }
        compose.onNodeWithText("Übertragen").performClick()
        compose.runOnIdle { assertEquals(1, uploads) }
    }

    @Test fun entries_kilter_did_not_take_are_listed_and_reported_with_their_climb_ids() {
        var report: String? = null
        var loads = 0
        val entries = listOf(
            KilterNotUploadedEntry(
                logUuid = "log-1", climbUuid = "1be3d36a0b854bb09ba21325ddce46b2", climbName = "A Bigger Squeeze",
                angle = 40, climbedAt = "2026-10-03T16:20:00", isAscent = true,
                reason = KilterNotUploadedReason.NOT_ON_KILTER, wireId = "1BE3D36A0B854BB09BA21325DDCE46B2", httpStatus = 500,
            ),
            KilterNotUploadedEntry(
                logUuid = "log-2", climbUuid = "877aff1306904ec69dfce1d24024dd39", climbName = null,
                angle = 35, climbedAt = "2026-10-02T10:00:00", isAscent = false, reason = KilterNotUploadedReason.RETRY_LATER,
            ),
        )
        compose.setContent {
            MaterialTheme {
                KilterLogbookSyncStatus(
                    KilterAccountState(pushEnabled = true, notUploaded = entries,
                        uploadStatus = KilterUploadStatus(uploaded = 5, pending = 1, rejectedByKilter = 1, unconfirmed = 1)),
                    {}, {}, { report = it }, {}, { loads++ },
                )
            }
        }
        compose.onNodeWithText("Kilter hat 1 Eintrag noch nicht angenommen", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Einträge anzeigen").performClick()
        compose.runOnIdle { assertEquals(1, loads) }
        compose.onNodeWithText("Nicht übertragen").assertIsDisplayed()
        compose.onNodeWithText("A Bigger Squeeze").assertIsDisplayed()
        compose.onNodeWithText("Kilter kennt diesen Climb nicht", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Unbekannter Climb").assertIsDisplayed()
        compose.onNodeWithText("Als Fehlerbericht senden").performClick()
        compose.runOnIdle {
            val text = assertNotNull(report)
            assertTrue("- A Bigger Squeeze · 40° · 03.10.2026 · Kilter kennt diesen Climb nicht · id 1BE3D36A0B854BB09BA21325DDCE46B2" in text, text)
            assertTrue("- Unbekannter Climb · 35° · 02.10.2026 · nur Versuche · Noch nicht angenommen, wird erneut versucht · id 877aff1306904ec69dfce1d24024dd39" in text, text)
            assertTrue(text.startsWith("Kilter hat diese Logbuch-Einträge nicht angenommen"), text)
        }
        compose.onNodeWithText("Nicht übertragen").assertDoesNotExist()
    }

    @Test fun a_slow_kilter_says_so_and_that_the_app_tries_again_by_itself() {
        compose.setContent {
            MaterialTheme {
                KilterLogbookSyncStatus(
                    KilterAccountState(pushEnabled = true, uploadStatus = KilterUploadStatus(
                        uploaded = 200, pending = 1510, reason = KilterUploadReason.TIMEOUT, nextRetry = 1,
                    )),
                    {}, {}, {},
                )
            }
        }
        compose.onNodeWithText("Kilter antwortet gerade zu langsam.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("CruxCoach versucht es automatisch erneut.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Bitte Internetverbindung prüfen", substring = true).assertDoesNotExist()
    }
}
