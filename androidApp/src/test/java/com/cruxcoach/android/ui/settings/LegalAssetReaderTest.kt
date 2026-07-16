package com.cruxcoach.android.ui.settings

import kotlin.test.Test
import kotlin.test.assertContains
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LegalAssetReaderTest {

    @Test
    fun `project notice and full third-party texts are packaged`() {
        val assets = RuntimeEnvironment.getApplication().assets

        assertContains(
            LegalAssetReader.read(assets, listOf("licenses/NOTICE")),
            "SQLCipher for Android 4.14.0",
        )
        assertContains(
            LegalAssetReader.read(assets, listOf("licenses/SQLCipher-Community.txt")),
            "Copyright (c) 2008-2023, ZETETIC LLC",
        )
        assertContains(
            LegalAssetReader.read(assets, listOf("licenses/LazySodium-MPL-2.0.txt")),
            "Mozilla Public License Version 2.0",
        )
    }

    @Test
    fun `resolved dependency JSON is rendered with known and URL licences`() {
        val formatted = LegalAssetReader.formatLicenseeReport(
            """
            [
              {
                "groupId": "org.example",
                "artifactId": "known",
                "version": "1.0",
                "spdxLicenses": [
                  {"identifier": "MIT", "url": "https://example.test/mit"}
                ]
              },
              {
                "groupId": "org.example",
                "artifactId": "url-only",
                "version": "2.0",
                "unknownLicenses": [
                  {"name": null, "url": "https://example.test/license"}
                ]
              }
            ]
            """.trimIndent()
        )

        assertContains(formatted, "org.example:known:1.0")
        assertContains(formatted, "MIT — https://example.test/mit")
        assertContains(formatted, "Licence URL — https://example.test/license")
    }
}
