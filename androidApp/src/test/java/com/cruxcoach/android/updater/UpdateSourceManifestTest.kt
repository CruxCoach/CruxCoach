package com.cruxcoach.android.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest served at `cruxcoach.org/update-sources.json` has to parse
 * with the model shipped in the app.
 *
 * If the two drift, nothing fails loudly: the app quietly falls back to its
 * compiled-in defaults and the runtime source list — the one lever that can
 * retire a release host for installs already in the field — stops working
 * without anyone noticing. The fixture below is a verbatim copy of the
 * published file, comment keys included, so a format change breaks a test
 * instead of a migration.
 */
class UpdateSourceManifestTest {

    private val published = """
        {
          "version": 1,
          "updated": "2026-07-30",
          "_comment": "Authoritative release-source list …",
          "_retiring_a_host": "Removing a retired host is hygiene …",
          "sources": [
            {
              "id": "forge",
              "kind": "forge",
              "url": "https://codeberg.org/api/v1",
              "owner": "CruxCoach",
              "repo": "CruxCoach",
              "enabled": true,
              "_note": "Canonical forge."
            },
            {
              "id": "zapstore",
              "kind": "nostr",
              "url": "wss://relay.zapstore.dev",
              "cdn": "https://cdn.zapstore.dev",
              "enabled": true
            },
            {
              "id": "website",
              "kind": "manifest",
              "url": "https://cruxcoach.org/apk-target.json",
              "enabled": true
            },
            {
              "id": "blossom",
              "kind": "blossom",
              "url": "https://blossom.primal.net",
              "enabled": true
            },
            {
              "id": "blossom-1",
              "kind": "blossom",
              "url": "https://nostr.download",
              "enabled": true
            },
            {
              "id": "blossom-2",
              "kind": "blossom",
              "url": "https://cdn.hzrd149.com",
              "enabled": true
            }
          ]
        }
    """.trimIndent()

    private fun parse(raw: String): UpdateSourceManifest =
        UpdateSourceRegistry.JSON.decodeFromString(raw)

    @Test
    fun `the published manifest parses and every source is usable`() {
        val manifest = parse(published)
        assertEquals(1, manifest.version)
        assertEquals(6, manifest.sources.size)
        manifest.sources.forEach {
            assertTrue(it.isUsable(), "source ${it.id} must be usable")
        }
    }

    @Test
    fun `comment keys do not break parsing`() {
        // The file carries _comment, _retiring_a_host and per-source _note for
        // the humans editing it. Those must stay ignorable.
        assertEquals(6, parse(published).sources.size)
    }

    @Test
    fun `kinds map onto the enum by their lower-case wire names`() {
        val byId = parse(published).sources.associateBy { it.id }
        assertEquals(UpdateSource.Kind.FORGE, byId.getValue("forge").kind)
        assertEquals(UpdateSource.Kind.NOSTR, byId.getValue("zapstore").kind)
        assertEquals(UpdateSource.Kind.MANIFEST, byId.getValue("website").kind)
        assertEquals(UpdateSource.Kind.BLOSSOM, byId.getValue("blossom").kind)
    }

    @Test
    fun `discovery and download roles come out as intended`() {
        val byId = parse(published).sources.associateBy { it.id }
        assertTrue(byId.getValue("forge").supportsDiscovery)
        assertTrue(byId.getValue("zapstore").supportsDiscovery)
        assertTrue(byId.getValue("website").supportsDiscovery)
        assertTrue(!byId.getValue("blossom").supportsDiscovery, "Blossom is download-only")

        val sha = "a".repeat(64)
        // The manifest names no bytes of its own; everything else must.
        assertNull(byId.getValue("website").downloadUrlFor("v9.9.9", sha))
        assertEquals(
            "https://blossom.primal.net/$sha",
            byId.getValue("blossom").downloadUrlFor("v9.9.9", sha),
        )
        assertEquals(
            "https://cdn.zapstore.dev/$sha",
            byId.getValue("zapstore").downloadUrlFor("v9.9.9", sha),
        )
        assertEquals(
            "https://codeberg.org/CruxCoach/CruxCoach/releases/download/v9.9.9/CruxCoach-v9.9.9.apk",
            byId.getValue("forge").downloadUrlFor("v9.9.9", sha),
        )
    }

    @Test
    fun `a GitHub forge entry resolves to the right web host`() {
        // The migration target: same client, different api root.
        val github = UpdateSource(
            id = "github",
            kind = UpdateSource.Kind.FORGE,
            url = "https://api.github.com",
            owner = "CruxCoach",
            repo = "CruxCoach",
        )
        assertEquals("https://github.com", github.webHost())
        assertEquals(
            "https://github.com/CruxCoach/CruxCoach/releases/download/v1.0.0/CruxCoach-v1.0.0.apk",
            github.downloadUrlFor("v1.0.0", "a".repeat(64)),
        )
    }
}
