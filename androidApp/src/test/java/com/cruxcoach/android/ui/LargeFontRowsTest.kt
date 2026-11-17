package com.cruxcoach.android.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.ui.bodystat.ExportSelectionButtons
import com.cruxcoach.android.ui.devcontact.DevContactSection
import com.cruxcoach.android.ui.settings.BoardSelectionDialogTitle
import com.cruxcoach.android.ui.settings.LedColorSection
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.android.ui.training.fuel.macrosText
import com.cruxcoach.android.ui.training.today.DoneHeroCard
import com.cruxcoach.android.ui.training.today.DurationHintCard
import com.cruxcoach.android.ui.training.today.TodayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rows from the 0.2.5 RC1 walk that broke at 320 dp and the largest font (2.0), in German: a text button beside
 * another one broke into single letters, a hint became a narrow column, a dialog title broke inside its words, and the
 * unread badge cut its number off. Native graphics, because the result depends on real text widths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeFontRowsTest {
    /** The font scale is set before the compose rule starts its activity, and reset after it. */
    @get:Rule(order = 0) val largestFont = object : ExternalResource() {
        override fun before() = RuntimeEnvironment.setFontScale(2f)
        override fun after() = RuntimeEnvironment.setFontScale(1f)
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        var fontScale = 0f
        compose.setContent {
            fontScale = LocalDensity.current.fontScale
            CruxCoachTheme { content() }
        }
        compose.waitForIdle()
        assertEquals("rendered at the largest font", 2f, fontScale, 0.01f)
    }

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun lines(layout: TextLayoutResult): List<String> {
        val text = layout.layoutInput.text.text
        return (0 until layout.lineCount).map { text.substring(layout.getLineStart(it), layout.getLineEnd(it)) }
    }

    /**
     * Where the painted lines leave the text's node, or null: each line inside its width, the last one inside its
     * height. Not didOverflowWidth: Text can keep a paragraph laid out wider than its node (see BrowserUxTest).
     */
    private fun cutOff(layout: TextLayoutResult): String? {
        val wide = (0 until layout.lineCount).firstOrNull {
            layout.getLineLeft(it) < -0.5f || layout.getLineRight(it) > layout.size.width + 0.5f
        }
        val bottom = layout.getLineBottom(layout.lineCount - 1)
        if (wide == null && bottom <= layout.size.height + 0.5f) return null
        return "node ${layout.size}, line ${wide ?: "-"} ${wide?.let { "${layout.getLineLeft(it)}..${layout.getLineRight(it)}" }}, " +
            "text bottom $bottom"
    }

    /** Every line ends at a space or after a hyphen, so no word is split into pieces or letters, and nothing is cut off. */
    private fun assertWholeWords(text: String) {
        val layout = layoutOf(compose.onNodeWithText(text, useUnmergedTree = true))
        val all = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val last = all[layout.getLineEnd(line) - 1]
            assertTrue("\"$text\" breaks inside a word: ${lines(layout)}", last.isWhitespace() || last == '-')
        }
        assertNull("\"$text\" is cut off: ${lines(layout)}", cutOff(layout))
    }

    @Test fun `data export - None stays a word next to or under All except health data`() {
        show {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ExportSelectionButtons(healthListed = true, onSelectAll = {}, onSelectNone = {})
            }
        }
        assertWholeWords("Alle außer Gesundheitsdaten")
        assertWholeWords("Keine")
    }

    @Test fun `board settings - Kilter Standard stays words next to or under CruxCoach Standard`() {
        show {
            // Settings page and card padding: 256 dp for the section.
            Column(Modifier.fillMaxWidth().padding(32.dp)) {
                LedColorSection(LedHoldColors(), onColorChange = { _, _ -> }, onResetColors = {}, onKilterColors = {})
            }
        }
        assertWholeWords("CruxCoach Standard")
        assertWholeWords("Kilter Standard")
    }

    @Test fun `today - History stays a word on the done card`() {
        show {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                DoneHeroCard(TodayState(loading = false), onMore = {}, onOpenHistory = {})
            }
        }
        assertWholeWords("Noch etwas trainieren")
        assertWholeWords("Verlauf")
    }

    @Test fun `today - the duration hint is no narrow column beside Uebernehmen`() {
        show {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { DurationHintCard(minutes = 60, onApply = {}) }
        }
        val hint = compose.onNodeWithText("Deine Trainings dauern", substring = true, useUnmergedTree = true)
        val text = hint.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        assertWholeWords(text)
        assertWholeWords("Übernehmen")
        val card = compose.onNodeWithTag("today_duration_hint").getBoundsInRoot()
        val width = hint.getBoundsInRoot().let { it.right - it.left }
        assertTrue("hint $width wide in a card of ${card.right - card.left}", width >= (card.right - card.left) / 2)
    }

    @Test fun `board picker - the title stays whole with Weiss nicht beside or under it`() {
        show {
            AlertDialog(
                onDismissRequest = {},
                title = { BoardSelectionDialogTitle(onFindViaGym = {}) },
                text = { Text("Kilter Board") },
                confirmButton = { TextButton(onClick = {}) { Text("OK") } },
            )
        }
        assertWholeWords("Board auswählen")
        assertWholeWords("Weiß nicht?")
    }

    @Test fun `support - the unread badge grows with its number`() {
        show {
            Column(Modifier.fillMaxWidth().padding(32.dp)) {
                DevContactSection(
                    unreadChat = 0, unreadBugs = 0, unreadFeatures = 0, unreadAnnouncements = 1,
                    crashReportOptIn = false, announcementsEnabled = false,
                    onNavigateToChat = {}, onNavigateToBugReports = {}, onNavigateToFeatureRequests = {},
                    onNavigateToCrashReports = {}, onDonateClick = {}, onCrashReportOptInChange = {},
                )
            }
        }
        val number = compose.onNode(hasText("1") and hasAnyAncestor(hasTestTag("devcontact_badge")), useUnmergedTree = true)
        assertNull("the number is cut off", cutOff(layoutOf(number)))
        val badge = compose.onNodeWithTag("devcontact_badge", useUnmergedTree = true).getBoundsInRoot()
        val text = number.getBoundsInRoot()
        assertTrue("number $text inside badge $badge", text.top >= badge.top - 0.5.dp && text.bottom <= badge.bottom + 0.5.dp)
    }

    @Test fun `meal lists - a macro letter never ends a line without its value`() {
        val widths = (120..320 step 8)
        show {
            Column {
                widths.forEach { w ->
                    Text(macrosText(15.0, 80.0, 10.0, null, false), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(w.dp).testTag("macros_$w"))
                }
            }
        }
        widths.forEach { w ->
            val layout = layoutOf(compose.onNodeWithTag("macros_$w", useUnmergedTree = true))
            // "P 15 · K 80 · F" on one line and "10 g" on the next: a line that ends in a lone letter.
            val split = lines(layout).dropLast(1).filter { line ->
                line.trimEnd().let { it.isNotEmpty() && it.last().isLetter() && (it.length == 1 || it[it.length - 2].isWhitespace()) }
            }
            assertTrue("at $w dp a line ends with a lone macro letter: ${lines(layout)}", split.isEmpty())
        }
    }
}
