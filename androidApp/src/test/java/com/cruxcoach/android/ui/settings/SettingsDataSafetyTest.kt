package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class SettingsDataSafetyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `local deletion still requires board selection and explicit confirmation`() {
        val dialog = mutableStateOf(false)
        val boards = BoardBrand.entries.filter { it.isInteractive }.toSet()
        val selected = mutableStateOf(boards)
        var deletes = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                    DataDeletionSection(
                        showDeleteBoardDataDialog = false,
                        showDeleteUserDataDialog = dialog.value,
                        isDeletingBoardData = false,
                        selectedBrands = selected.value,
                        onShowDeleteBoardDataDialog = {},
                        onShowDeleteUserDataDialog = { dialog.value = true },
                        onToggleBrand = { selected.value = selected.value - it },
                        onToggleSelectAll = { selected.value = if (selected.value.isEmpty()) boards else emptySet() },
                        onDismissDeleteDialog = { dialog.value = false },
                        onDeleteBoardData = {},
                        onDeleteUserBoardData = { deletes++; dialog.value = false },
                    )
                }
            }
        }
        compose.onNodeWithText("Eigene Logbuch-Daten löschen").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("Alle Boards").performScrollTo().performClick()
        compose.onNodeWithText("Unwiderruflich löschen").assertIsNotEnabled()
        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle { assertEquals(0, deletes) }
        compose.onNodeWithText("Eigene Logbuch-Daten löschen").performScrollTo().performClick()
        compose.onNodeWithText("Alle Boards").performScrollTo().performClick()
        compose.onNodeWithText("Unwiderruflich löschen").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun `existing backup history and import lock remain visible while backup is disabled`() {
        var restores = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                    BackupSettingsSection(
                        state = BackupSettingsState(
                            hasNostrKey = true, lastBackupIso = "2026-09-01",
                            backupEnabled = false, boardImportInProgress = true,
                        ),
                        onSetBackupEnabled = {}, onSetInterval = {}, onRunBackupNow = {},
                        onTriggerRestore = { restores++ },
                    )
                }
            }
        }
        compose.onNodeWithText("2026-09-01", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Backup wiederherstellen").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Boards werden noch geladen", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, restores) }
    }
}
