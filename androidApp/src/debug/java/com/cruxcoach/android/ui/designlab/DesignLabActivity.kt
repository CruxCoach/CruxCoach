package com.cruxcoach.android.ui.designlab

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.ui.board.AscentLoggingScenarioContent
import com.cruxcoach.android.ui.board.AscentLoggingScenarios
import com.cruxcoach.android.ui.board.ActiveSessionScenarioContent
import com.cruxcoach.android.ui.board.ActiveSessionScenarios
import com.cruxcoach.android.ui.board.BoardBrowserScenarioContent
import com.cruxcoach.android.ui.board.BoardBrowserScenarios
import com.cruxcoach.android.ui.board.ProgressHistoryScenarioContent
import com.cruxcoach.android.ui.board.ProgressHistoryScenarios
import com.cruxcoach.android.ui.board.BoardLogbookScenarioContent
import com.cruxcoach.android.ui.board.BoardLogbookScenarios
import com.cruxcoach.android.ui.board.ClimbDetailScenarioContent
import com.cruxcoach.android.ui.board.ClimbDetailScenarios
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import java.util.Locale

/** ADB-addressable renderer compiled into debug variants only. */
class DesignLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val scenarioId = intent.getStringExtra(EXTRA_SCENARIO) ?: AscentLoggingScenarios.NewSend.id
        val darkMode = when (intent.getStringExtra(EXTRA_THEME) ?: THEME_LIGHT) {
            THEME_LIGHT -> DarkModeSetting.LIGHT
            THEME_DARK -> DarkModeSetting.DARK
            else -> throw IllegalArgumentException("DesignLab theme must be light or dark")
        }
        val localeTag = (intent.getStringExtra(EXTRA_LOCALE) ?: LOCALE_EN).also {
            require(it == LOCALE_EN || it == LOCALE_DE) { "DesignLab locale must be en or de" }
        }
        val fontScale = intent.getFloatExtra(EXTRA_FONT_SCALE, FONT_SCALE_NORMAL).also {
            require(it == FONT_SCALE_NORMAL || it == FONT_SCALE_LARGE) {
                "DesignLab font scale must be 1.0 or 1.5"
            }
        }

        setContent {
            DesignLabEnvironment(localeTag = localeTag, fontScale = fontScale) {
                CruxCoachTheme(darkModeSetting = darkMode) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (
                                    scenarioId.startsWith("browser/") ||
                                    scenarioId.startsWith("session/") ||
                                    scenarioId.startsWith("progress/") ||
                                    scenarioId.startsWith("logbook/")
                                ) {
                                    Modifier.safeDrawingPadding()
                                } else {
                                    Modifier
                                },
                            )
                            .testTag("design_lab_root"),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        when {
                            scenarioId.startsWith("log/") ->
                                AscentLoggingScenarioContent(AscentLoggingScenarios.require(scenarioId))
                            scenarioId.startsWith("browser/") ->
                                BoardBrowserScenarioContent(BoardBrowserScenarios.require(scenarioId))
                            scenarioId.startsWith("session/") ->
                                ActiveSessionScenarioContent(ActiveSessionScenarios.require(scenarioId))
                            scenarioId.startsWith("progress/") ->
                                ProgressHistoryScenarioContent(ProgressHistoryScenarios.require(scenarioId))
                            scenarioId.startsWith("logbook/") ->
                                BoardLogbookScenarioContent(BoardLogbookScenarios.require(scenarioId))
                            scenarioId.startsWith("detail/") ->
                                ClimbDetailScenarioContent(ClimbDetailScenarios.require(scenarioId))
                            else -> throw IllegalArgumentException(
                                "Unknown DesignLab scenario: $scenarioId",
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_THEME = "theme"
        const val EXTRA_LOCALE = "locale"
        const val EXTRA_FONT_SCALE = "font_scale"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val LOCALE_EN = "en"
        const val LOCALE_DE = "de"
        const val FONT_SCALE_NORMAL = 1.0f
        const val FONT_SCALE_LARGE = 1.5f
    }
}

@Composable
internal fun DesignLabEnvironment(
    localeTag: String,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val baseDensity = LocalDensity.current
    val configuration = remember(localeTag, fontScale) {
        Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(localeTag))
            this.fontScale = fontScale
        }
    }
    val localizedContext = remember(configuration) {
        baseContext.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localizedContext,
        LocalResources provides localizedContext.resources,
        LocalDensity provides Density(baseDensity.density, fontScale),
        content = content,
    )
}
