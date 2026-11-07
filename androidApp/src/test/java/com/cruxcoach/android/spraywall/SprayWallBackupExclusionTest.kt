package com.cruxcoach.android.spraywall

import android.app.Application
import android.content.Context
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Wall photos are private and belong to the excluded encrypted database, so they never leave the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SprayWallBackupExclusionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `spray wall photos stay out of backups and device transfers`() {
        val target = "file" to SprayWallPhotoStore.DIRECTORY
        assertEquals(1, exclusions(R.xml.backup_rules).count { it == target })
        // Android 12+: one rule under cloud-backup, one under device-transfer.
        assertEquals(2, exclusions(R.xml.data_extraction_rules).count { it == target })
    }

    private fun exclusions(@XmlRes resourceId: Int): List<Pair<String, String>> {
        val parser = context.resources.getXml(resourceId)
        return try {
            buildList {
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        if (domain != null && path != null) add(domain to path)
                    }
                    parser.next()
                }
            }
        } finally {
            parser.close()
        }
    }
}
