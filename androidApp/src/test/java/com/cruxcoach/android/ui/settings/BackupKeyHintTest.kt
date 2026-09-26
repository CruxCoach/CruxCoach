package com.cruxcoach.android.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.android.data.DarkModeSetting
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupKeyHintTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `key risk is visible and optional detail never navigates or confirms storage`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var navigations = 0
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(darkModeSetting = DarkModeSetting.DARK) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    SettingsSectionCard {
                        BackupSettingsSection(BackupSettingsState(backupEnabled = true, hasNostrKey = true),
                            {}, {}, {}, {}, onNavigateToKeyManagement = { navigations++ })
                    }
                }
            }
        }
        System.getenv("CRUXCOACH_UI_REVIEW_DIR")?.let { directory ->
            compose.runOnIdle {
                val root = requireNotNull(view)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(directory).mkdirs()
                File(directory, "backup-compact.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        compose.onNodeWithText(app.getString(R.string.backup_key_short_local)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.backup_key_warning_body_local)).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.getString(R.string.action_show_info, app.getString(R.string.backup_key_warning_title))).performClick()
        compose.onNodeWithText(app.getString(R.string.backup_key_warning_body_local)).assertExists()
        compose.onNodeWithText(app.getString(R.string.action_close)).performClick()
        compose.runOnIdle { assertEquals(0, navigations) }
        compose.onNodeWithText(app.getString(R.string.backup_key_warning_view_account)).performClick()
        compose.runOnIdle { assertEquals(1, navigations) }
    }
}
