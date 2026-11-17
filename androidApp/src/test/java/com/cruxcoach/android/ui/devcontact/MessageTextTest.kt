package com.cruxcoach.android.ui.devcontact

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.widget.Magnifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.awaitCancellation
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Message texts in announcements, the developer chat, the threads and the report lists:
 * a long press selects them for Copy and Select all, a tap still reaches the card,
 * and http/https links open in the browser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [MessageTextTest.NoMagnifier::class])
// Real text measuring, so a tap can land on a link.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageTextTest {

    /** The selection's magnifier needs a window surface Robolectric does not have; the test does not look at it. */
    @Implements(Magnifier::class)
    class NoMagnifier {
        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

        @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

        @Implementation fun update() = Unit

        @Implementation fun dismiss() = Unit
    }

    @get:Rule val compose = createComposeRule()

    /** The system's text toolbar: what it would offer, and a tap on one of its entries. */
    private class Menu : TextContextMenuProvider {
        private var shown: TextContextMenuDataProvider? = null
        val isShown get() = shown != null

        fun keys() = items().map { it.key }

        fun tap(key: Any) = items().single { it.key == key }.onClick(object : TextContextMenuSession {
            override fun close() = Unit
        })

        private fun items() = shown!!.data().components.filterIsInstance<TextContextMenuItem>()

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                awaitCancellation()
            } finally {
                shown = null
            }
        }
    }

    private class Browser : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private val menu = Menu()
    private val browser = Browser()
    private var cardTaps = 0
    private var linksOpened = 0

    private fun showInCard(text: String) {
        compose.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menu, LocalUriHandler provides browser) {
                MaterialTheme {
                    Card(Modifier.clickable { cardTaps++ }) {
                        Box { MessageText(text, onLinkOpened = { linksOpened++ }) }
                    }
                }
            }
        }
    }

    @Test
    fun `a long press offers Copy and Select all, and Copy takes the whole message`() {
        val message = "Version 0.2.5 is out.\nThanks for testing!"
        showInCard(message)

        compose.onNodeWithText(message).performTouchInput { longClick(centerLeft.copy(x = 20f)) }
        // The menu can come before the selection settles (smart selection runs on its own thread).
        compose.waitUntil(5_000) { menu.isShown && TextContextMenuKeys.CopyKey in menu.keys() }

        // Select all is offered unless the long press already took everything.
        if (TextContextMenuKeys.SelectAllKey in menu.keys()) {
            menu.tap(TextContextMenuKeys.SelectAllKey)
            compose.waitUntil(5_000) { menu.isShown && TextContextMenuKeys.SelectAllKey !in menu.keys() }
        }
        assertTrue(menu.keys().toString(), TextContextMenuKeys.CopyKey in menu.keys())
        menu.tap(TextContextMenuKeys.CopyKey)
        compose.waitForIdle()

        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(message, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("a long press is no tap on the card", 0, cardTaps)
    }

    @Test
    fun `a tap on the text still reaches the card`() {
        showInCard("A tap marks this announcement read.")

        compose.onNodeWithText("A tap marks this announcement read.").performTouchInput { click(center) }

        assertEquals(1, cardTaps)
        assertTrue(browser.opened.isEmpty())
    }

    @Test
    fun `a tap on a link opens it in the browser`() {
        showInCard("https://cruxcoach.org/download")

        compose.onNodeWithText("https://cruxcoach.org/download").performTouchInput { click(center) }

        assertEquals(listOf("https://cruxcoach.org/download"), browser.opened)
        assertEquals(1, linksOpened)
        assertEquals("the link took the tap", 0, cardTaps)
    }

    @Test
    fun `a long press on a link selects it without opening it`() {
        showInCard("https://cruxcoach.org/download")

        compose.onNodeWithText("https://cruxcoach.org/download").performTouchInput { longClick(center) }
        compose.waitUntil(5_000) { menu.isShown }

        assertTrue(browser.opened.toString(), browser.opened.isEmpty())
        assertEquals(0, linksOpened)
        assertEquals(0, cardTaps)
    }

    @Test
    fun `a screen reader opens a link with an action`() {
        showInCard("Download: https://cruxcoach.org/download")

        val action = compose.onNodeWithText("Download: https://cruxcoach.org/download", useUnmergedTree = true)
            .fetchSemanticsNode().let { node ->
                generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.CustomActions) }
            }.single()
        assertEquals("Open link https://cruxcoach.org/download", action.label)
        compose.runOnUiThread { action.action() }

        assertEquals(listOf("https://cruxcoach.org/download"), browser.opened)
    }

    @Test
    fun `other links stay plain text`() {
        showInCard("intent://x#Intent;end")

        compose.onNodeWithText("intent://x#Intent;end").performTouchInput { click(center) }

        assertTrue(browser.opened.isEmpty())
        assertEquals(1, cardTaps)
    }
}
