package com.cruxcoach.android.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.DarkModeSetting
import java.io.File
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One page of the app's building blocks in the light and the dark theme: the theme's roles,
 * the orange and the status colours on the surfaces they sit on, and the overrides screens use
 * (orange buttons, selected chips). Written to `CRUXCOACH_UI_REVIEW_DIR`; skipped without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h1500dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
class ThemeUiReviewTest {
    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")

    @Before fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    @Test fun light() = shoot("theme-light", DarkModeSetting.LIGHT)

    @Test fun dark() = shoot("theme-dark", DarkModeSetting.DARK)

    private fun shoot(name: String, theme: DarkModeSetting) {
        var view: android.view.View? = null
        compose.setContent {
            view = LocalView.current
            CruxCoachTheme(theme) {
                Surface(color = MaterialTheme.colorScheme.background) { Gallery() }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            File(dir!!).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Composable
    private fun Gallery() {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Board size check", style = MaterialTheme.typography.titleLarge)
            Text("Supporting text in onSurfaceVariant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Orange text and links", color = OrangeAccent, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sent", color = SuccessGreen)
                Text("3.5★", color = WarningYellow)
                Text("Failed", color = ErrorRed)
                Text("Info", color = InfoBlue)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Card title", style = MaterialTheme.typography.titleMedium)
                    Text("Muted line on the card", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Orange", color = OrangeAccent)
                        Text("Sent", color = SuccessGreen)
                        Text("3.5★", color = WarningYellow)
                        Text("Failed", color = ErrorRed)
                        Text("Info", color = InfoBlue)
                    }
                }
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text("List item") },
                    supportingContent = { Text("Supporting line") },
                    leadingContent = { Icon(Icons.Default.Bluetooth, null, tint = OrangeAccent) },
                    trailingContent = { Badge { Text("3") } },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Button") }
                Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent)) { Text("Orange") }
                FilledTonalButton(onClick = {}) { Text("Tonal") }
                OutlinedButton(onClick = {}) { Text("Outlined") }
                TextButton(onClick = {}) { Text("Text") }
                Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)) { Text("Delete") }
                Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)) { Text("Sent") }
                Button(onClick = {}, enabled = false) { Text("Disabled") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Selected") })
                FilterChip(selected = false, onClick = {}, label = { Text("Not selected") })
                FilterChip(
                    selected = true, onClick = {}, label = { Text("Orange chip") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CruxCoachDesign.colors.brandAccentContainer,
                        selectedLabelColor = CruxCoachDesign.colors.onBrandAccentContainer,
                    ),
                )
                FilterChip(
                    selected = true, onClick = {}, label = { Text("Filled chip") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = OrangeAccent,
                        selectedLabelColor = CruxCoachDesign.colors.onBrandAccent,
                    ),
                )
                AssistChip(onClick = {}, label = { Text("Assist") }, leadingIcon = { Icon(Icons.Default.Star, null) })
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Day", "Week", "Month").forEachIndexed { i, label ->
                    SegmentedButton(selected = i == 1, onClick = {}, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = true, onCheckedChange = {})
                Switch(checked = false, onCheckedChange = {})
                Checkbox(checked = true, onCheckedChange = {})
                Checkbox(checked = false, onCheckedChange = {})
                RadioButton(selected = true, onClick = {})
                RadioButton(selected = false, onClick = {})
            }
            Slider(value = 0.4f, onValueChange = {})
            LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = "12x12 with kickboard", onValueChange = {}, label = { Text("Label") },
                supportingText = { Text("Supporting text") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = "", onValueChange = {}, label = { Text("Weight") }, isError = true,
                supportingText = { Text("Enter a number") }, modifier = Modifier.fillMaxWidth())
            TabRow(selectedTabIndex = 0) {
                Tab(selected = true, onClick = {}, text = { Text("Climbs") })
                Tab(selected = false, onClick = {}, text = { Text("Logbook") })
            }
            HorizontalDivider()
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profile") })
            }
            Snackbar { Text("Saved: your board is now 12x8") }
            ExtendedFloatingActionButton(
                onClick = {}, containerColor = OrangeAccent, contentColor = CruxCoachDesign.colors.onBrandAccent,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Play") },
            )
        }
    }
}
