package com.cruxcoach.android.athlete

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's region ("US", "DE", …). Read from the system locales: the app's
 * own locale is only "de" or "en" once the language setting applied it, so it
 * carries no country (see resolveSystemLocaleTag).
 */
@Singleton
open class SystemRegion @Inject constructor(@ApplicationContext private val context: Context) {
    open fun country(): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(android.app.LocaleManager::class.java).systemLocales[0].country
        } else {
            android.content.res.Resources.getSystem().configuration.locales[0].country
        }
    }.getOrDefault("")
}
