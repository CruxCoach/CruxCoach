package com.cruxcoach.android.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.os.LocaleListCompat
import com.cruxcoach.android.R

internal const val LOCALE_PREFS = "locale_prefs"
internal const val KEY_USER_CHOICE = "user_language_choice" // "system", "de", "en"

/**
 * Resolve the effective locale tag for "system" mode.
 * Only considers the primary system language — if it's German, use "de",
 * otherwise fall back to "en" (the app's default).
 *
 * Uses LocaleManager (API 33+) to get the TRUE system locales,
 * because Resources.getSystem().configuration.locales is contaminated
 * by AppCompat's per-app locale override.
 */
internal fun resolveSystemLocaleTag(context: android.content.Context): String {
    val primaryLang = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
        localeManager.systemLocales[0].language
    } else {
        // Pre-Android 13: no per-app locale override, so this is reliable
        android.content.res.Resources.getSystem().configuration.locales[0].language
    }
    return if (primaryLang == "de") "de" else "en"
}

/**
 * Apply the per-app locale based on user choice.
 * Call from Application.onCreate() and lifecycle onStart() to keep in sync.
 */
internal fun applyLocaleChoice(context: android.content.Context, choice: String) {
    val effectiveTag = if (choice == "system") resolveSystemLocaleTag(context) else choice
    AppCompatDelegate.setApplicationLocales(
        LocaleListCompat.forLanguageTags(effectiveTag)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSection() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(LOCALE_PREFS, android.content.Context.MODE_PRIVATE)
    var userChoice by remember {
        mutableStateOf(prefs.getString(KEY_USER_CHOICE, "system") ?: "system")
    }

    val options = listOf(
        "system" to "System",
        "de" to "Deutsch",
        "en" to "English"
    )

    Text(
        stringResource(R.string.settings_language_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (tag, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                onClick = {
                    if (tag != userChoice) {
                        prefs.edit().putString(KEY_USER_CHOICE, tag).apply()
                        userChoice = tag
                        applyLocaleChoice(context, tag)
                    }
                },
                selected = tag == userChoice,
                label = { Text(label) }
            )
        }
    }
}
