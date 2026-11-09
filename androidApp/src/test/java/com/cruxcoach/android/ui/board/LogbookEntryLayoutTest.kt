package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The entries of one logbook list share one layout: on a 360 dp phone in
 * German a "Flash" fits beside the name, "Offen · 12 Versuche" does not, so a
 * list with both puts every result under its name. Text widths need native
 * graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogbookEntryLayoutTest {

    @get:Rule val compose = createComposeRule()

    /** A 360 dp screen less the list's padding. */
    private val cardWidth = 328.dp

    private fun entry(uuid: String, isSend: Boolean, tries: Long) = AscentWithClimb(
        uuid = uuid, climbUuid = "c-$uuid", angle = 40, isMirror = false, bidCount = tries, quality = null,
        difficulty = null, comment = null, climbedAt = "2026-11-01T18:00:00", climbName = "Crimp Theory",
        climbFrames = "", difficultyAverage = 20.0, isSend = isSend,
    )

    private val flashes = listOf(entry("f1", isSend = true, tries = 1), entry("f2", isSend = true, tries = 1))
    private val flashUuids = setOf("f1", "f2")
    private val project = entry("p1", isSend = false, tries = 12)

    private fun layoutOf(entries: List<AscentWithClimb>, width: Dp = cardWidth, fontScale: Float = 1f): EntryLayout {
        var result: EntryLayout? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme { result = logbookEntryLayout(entries, flashUuids, width) }
            }
        }
        compose.waitForIdle()
        return result!!
    }

    @Test
    fun flashesFitBesideTheName() = assertEquals(EntryLayout.RESULT_BESIDE, layoutOf(flashes))

    @Test
    fun oneWideResultMovesEveryResultBelowItsName() =
        assertEquals(EntryLayout.RESULT_BELOW, layoutOf(flashes + project))

    @Test
    fun aVeryNarrowCardStacks() = assertEquals(EntryLayout.STACKED, layoutOf(flashes, width = 260.dp))

    @Test
    fun largeFontsStackEveryList() = assertEquals(EntryLayout.STACKED, layoutOf(flashes, fontScale = 2f))

    @Test
    fun aListKeepsItsNarrowerLayoutWhenTheWideResultGoes() {
        var entries by mutableStateOf(flashes + project)
        var result: EntryLayout? = null
        compose.setContent {
            MaterialTheme { result = logbookEntryLayout(entries, flashUuids, cardWidth) }
        }
        compose.waitForIdle()
        assertEquals(EntryLayout.RESULT_BELOW, result)
        // A filter or a delete leaves only flashes: the cards on screen keep their layout.
        entries = flashes
        compose.waitForIdle()
        assertEquals(EntryLayout.RESULT_BELOW, result)
    }
}
