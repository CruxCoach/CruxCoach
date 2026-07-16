package com.cruxcoach.android.updater

import android.content.Context
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class ApkDownloaderTransportTest {

    @Test
    fun `invalid persisted APK transports fail before touching Android services`() {
        listOf(
            "http://downloads.example/update.apk",
            "//downloads.example/update.apk",
            "content://downloads/update.apk",
        ).forEach { apkUrl ->
            val context = mockk<Context>()
            val result = ApkDownloader(context).start(update(apkUrl), allowMobile = false)

            assertEquals(ApkDownloader.StartResult.Error("apk_url_not_https"), result)
            verify { context wasNot Called }
        }
    }

    private fun update(apkUrl: String) = UpdateInfo(
        tagName = "v1.2.3",
        versionName = "1.2.3",
        version = SemVer(1, 2, 3),
        apkUrl = apkUrl,
        apkSha256Url = "https://downloads.example/update.apk.sha256",
        apkSizeBytes = 1,
        apkSha256 = "a".repeat(64),
        releaseNotesMarkdown = "",
        releasePageUrl = "https://downloads.example/releases/v1.2.3",
        publishedAtEpochSeconds = 0,
    )
}
