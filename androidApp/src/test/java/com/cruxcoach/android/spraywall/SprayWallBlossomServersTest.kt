package com.cruxcoach.android.spraywall

import com.cruxcoach.android.nostr.backup.BlossomUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where spray walls read from Blossom since cdn.hzrd149.com stopped taking uploads (2026-11-10). */
class SprayWallBlossomServersTest {
    private val sha = "b".repeat(64)

    @Test fun `the hold model comes from the three mirrors that keep it, our own first`() {
        assertEquals(
            listOf("https://blossom.cruxcoach.org", "https://blossom-01.uid.ovh", "https://blossom.yakihonne.com"),
            HoldModel.MIRRORS,
        )
        assertEquals(HoldModel.MIRRORS.map { "$it/${HoldModel.SHA256}" }, HoldModel.urls)
        assertTrue(HoldModel.urls.none { "hzrd149" in it })
    }

    @Test fun `a wall photo is asked from its own server first, then from every server the app has uploaded to`() {
        assertEquals(
            listOf("https://photos.example.org") + BlossomUploader.DEFAULT_SERVERS + BlossomUploader.RETIRED_SERVERS,
            sprayWallPhotoServers("https://photos.example.org/$sha"),
        )
    }

    @Test fun `a wall photo that an earlier build put on the retired server is asked there first and only once`() {
        val servers = sprayWallPhotoServers("https://cdn.hzrd149.com/$sha")
        assertEquals("https://cdn.hzrd149.com", servers.first())
        assertEquals(servers.distinct(), servers)
        assertEquals(BlossomUploader.KNOWN_SERVERS.toSet(), servers.toSet())
    }
}
