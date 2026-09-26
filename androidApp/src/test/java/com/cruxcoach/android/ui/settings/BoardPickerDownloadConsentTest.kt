package com.cruxcoach.android.ui.settings

import android.app.Application
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de")
class BoardPickerDownloadConsentTest {
    @get:Rule val compose = createComposeRule()
    private val vm = mockk<BoardPickerViewModel>(relaxed = true)
    private var applied = 0
    private var closed = 0

    private fun show(defer: Boolean = false, needsConsent: Boolean = true) {
        coEvery { vm.needsDownloadConsent(BoardBrand.MOONBOARD) } returns needsConsent
        compose.setContent {
            MaterialTheme {
                BoardDownloadRequestHost(vm, defer, onSelected = { closed++ }) { request ->
                    Button(onClick = { request(BoardBrand.MOONBOARD) { applied++ } }) { Text("MoonBoard wählen") }
                }
            }
        }
        compose.onNodeWithText("MoonBoard wählen").performClick()
        compose.waitForIdle()
    }

    @Test fun `cancel leaves active board and download preference unchanged`() {
        show()
        compose.onNodeWithText("MoonBoard herunterladen?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, applied); assertEquals(0, closed) }
        compose.onNodeWithText("Abbrechen").performClick()
        coVerify(exactly = 0) { vm.enableDownloads(any()) }
        verify(exactly = 0) { vm.downloadSelectedBoard(any()) }
        compose.runOnIdle { assertEquals(0, applied); assertEquals(0, closed) }
    }

    @Test fun `confirm enables future downloads and loads chosen board`() {
        show()
        compose.onNodeWithText("Herunterladen und Updates aktivieren").performClick()
        compose.waitForIdle()
        coVerifyOrder { vm.enableDownloads(BoardBrand.MOONBOARD); vm.downloadSelectedBoard(BoardBrand.MOONBOARD) }
        compose.runOnIdle { assertEquals(1, applied); assertEquals(1, closed) }
    }

    @Test fun `first onboarding step defers downloads to Continue without prompting`() {
        show(defer = true)
        compose.onNode(isDialog()).assertDoesNotExist()
        coVerify(exactly = 0) { vm.needsDownloadConsent(any()); vm.enableDownloads(any()) }
        verify(exactly = 0) { vm.downloadSelectedBoard(any()) }
        compose.runOnIdle { assertEquals(1, applied); assertEquals(1, closed) }
    }

    @Test fun `cached board can be selected without enabling updates`() {
        show(needsConsent = false)
        compose.onNode(isDialog()).assertDoesNotExist()
        coVerify(exactly = 0) { vm.enableDownloads(any()) }
        compose.runOnIdle { assertEquals(1, applied) }
    }
}
