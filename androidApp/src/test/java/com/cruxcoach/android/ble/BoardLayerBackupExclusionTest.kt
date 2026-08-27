package com.cruxcoach.android.ble

import android.content.Context
import android.app.Application
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = Application::class)
class BoardLayerBackupExclusionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `installation scoped layer identities never cross backup or device transfer`() {
        val target = "sharedpref" to "board_layer_identity.xml"

        assertEquals(1, exclusions(R.xml.backup_rules).count { it == target })
        // Android 12+ rules contain one copy under cloud-backup and one under
        // device-transfer; both restore channels must reject the identity.
        assertEquals(2, exclusions(R.xml.data_extraction_rules).count { it == target })
    }

    @Test
    fun `relay Bluetooth name recovery never crosses backup or device transfer`() {
        val target = "sharedpref" to "cruxrelay.xml"

        assertEquals(1, exclusions(R.xml.backup_rules).count { it == target })
        assertEquals(2, exclusions(R.xml.data_extraction_rules).count { it == target })
    }

    @Test
    fun `crash reports never cross backup or device transfer`() {
        listOf("crash_log.txt", "crash_log_prev.txt").forEach { fileName ->
            val target = "file" to fileName
            assertEquals(1, exclusions(R.xml.backup_rules).count { it == target })
            assertEquals(2, exclusions(R.xml.data_extraction_rules).count { it == target })
        }
    }

    @Test
    fun `all databases including identity scoped database stay on device`() {
        val target = "database" to "."

        assertEquals(1, exclusions(R.xml.backup_rules).count { it == target })
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
