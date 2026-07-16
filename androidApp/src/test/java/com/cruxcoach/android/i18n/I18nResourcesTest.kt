package com.cruxcoach.android.i18n

import android.content.Context
import android.content.res.Configuration
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.bodystat.statLabelRes
import com.cruxcoach.domain.model.StatRegistry
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class I18nResourcesTest {
    private fun context(locale: Locale): Context {
        val configuration = Configuration(RuntimeEnvironment.getApplication().resources.configuration)
        configuration.setLocale(locale)
        return RuntimeEnvironment.getApplication()
            .createConfigurationContext(configuration)
    }

    @Test
    fun `representative English and German singulars use quantity resources`() {
        val en = context(Locale.US).resources
        val de = context(Locale.GERMANY).resources
        assertEquals("1 star", en.getQuantityString(R.plurals.cd_stars, 1, 1))
        assertEquals("2 stars", en.getQuantityString(R.plurals.cd_stars, 2, 2))
        assertEquals("1 Stern", de.getQuantityString(R.plurals.cd_stars, 1, 1))
        assertEquals("2 Sterne", de.getQuantityString(R.plurals.cd_stars, 2, 2))
        assertEquals("1 ascent", en.getQuantityString(R.plurals.settings_backup_restored_ascents, 1, 1))
        assertEquals("1 Begehung", de.getQuantityString(R.plurals.settings_backup_restored_ascents, 1, 1))
        assertEquals("1 message waiting to be sent", en.getQuantityString(R.plurals.queue_count_label, 1, 1))
        assertEquals("1 Nachricht wartet auf Versand", de.getQuantityString(R.plurals.queue_count_label, 1, 1))
    }

    @Test
    fun `every body stat key has an Android localization mapping`() {
        assertTrue(StatRegistry.ALL.all { statLabelRes(it.key) != 0 })
        val en = context(Locale.US)
        val de = context(Locale.GERMANY)
        assertEquals("Weight", en.getString(statLabelRes("weight")))
        assertEquals("Gewicht", de.getString(statLabelRes("weight")))
    }
}
