package com.cruxcoach.app.sync

import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.app.testing.JvmHashing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogueTrustTest {
    private fun verdict(event: JsonObject, dTag: String, now: Long = ManifestFixtures.NOW) =
        CatalogueTrust.acceptEvent(JvmHashing, event, dTag, now)

    private fun rejection(event: JsonObject, dTag: String, now: Long = ManifestFixtures.NOW) =
        assertIs<ManifestVerdict.Rejected>(verdict(event, dTag, now)).reason

    private fun JsonObject.with(key: String, value: String) = JsonObject(this + (key to JsonPrimitive(value)))

    @Test
    fun `real kilter manifest passes the whole pipeline`() {
        val manifest = assertIs<ManifestVerdict.Accepted>(
            verdict(ManifestFixtures.byDTag(CatalogueTrust.KILTER_D_TAG), CatalogueTrust.KILTER_D_TAG),
        ).manifest
        assertEquals(2, manifest.v)
        assertEquals("kilter", manifest.board)
        assertEquals(1, manifest.productId)
        assertEquals("zstd", manifest.compression)
        assertEquals(204, manifest.chunks.size)
        assertEquals(1_789_532_081L, manifest.eventCreatedAt)
        val meta = manifest.chunks.single { it.name == "meta" }
        assertEquals("meta", meta.type)
        assertEquals(64_538L, meta.size)
        assertEquals("0a09d0f0d9901837450bd15245e3ec43d9b7d5d7dbdd4baef0be5bb8976e8433", meta.sha256)
        assertEquals(101, manifest.chunks.count { CatalogueTrust.resolvedType(it) == "climbs" })
        assertEquals(101, manifest.chunks.count { CatalogueTrust.resolvedType(it) == "stats" })
        assertEquals(1, manifest.chunks.count { CatalogueTrust.resolvedType(it) == "locations" })
    }

    @Test
    fun `every real fixture manifest is accepted for its own d-tag`() {
        val dTags = listOf(
            CatalogueTrust.auroraDTag("tension"), CatalogueTrust.KILTER_D_TAG, CatalogueTrust.QUANTUM_D_TAG,
            CatalogueTrust.MOONBOARD_BETA_D_TAG, CatalogueTrust.MOONBOARD_D_TAG,
        )
        for (dTag in dTags) assertIs<ManifestVerdict.Accepted>(verdict(ManifestFixtures.byDTag(dTag), dTag), dTag)
    }

    @Test
    fun `sibling manifest with a valid signature is rejected for another d-tag`() {
        assertEquals(
            ManifestRejection.WRONG_D_TAG,
            rejection(ManifestFixtures.byDTag(CatalogueTrust.MOONBOARD_D_TAG), CatalogueTrust.KILTER_D_TAG),
        )
    }

    @Test
    fun `foreign pubkey is rejected`() {
        val event = ManifestFixtures.byDTag(CatalogueTrust.MOONBOARD_D_TAG).with("pubkey", "0".repeat(64))
        assertEquals(ManifestRejection.WRONG_PUBKEY, rejection(event, CatalogueTrust.MOONBOARD_D_TAG))
    }

    @Test
    fun `substituted content breaks the id binding`() {
        val original = ManifestFixtures.byDTag(CatalogueTrust.MOONBOARD_D_TAG)
        val tampered = original.with(
            "content",
            (original.getValue("content") as JsonPrimitive).content.replace("https://cdn.hzrd149.com", "https://evil.example"),
        )
        assertEquals(ManifestRejection.BAD_SIGNATURE, rejection(tampered, CatalogueTrust.MOONBOARD_D_TAG))
    }

    @Test
    fun `corrupted signature is rejected`() {
        val original = ManifestFixtures.byDTag(CatalogueTrust.MOONBOARD_D_TAG)
        val sig = (original.getValue("sig") as JsonPrimitive).content
        val flipped = (if (sig[0] == 'a') 'b' else 'a') + sig.substring(1)
        assertEquals(ManifestRejection.BAD_SIGNATURE, rejection(original.with("sig", flipped), CatalogueTrust.MOONBOARD_D_TAG))
    }

    @Test
    fun `manifest more than an hour in the future is rejected and exactly one hour is accepted`() {
        val event = ManifestFixtures.byDTag(CatalogueTrust.MOONBOARD_D_TAG)
        val createdAt = 1_788_514_154L
        assertEquals(
            ManifestRejection.FUTURE_TIMESTAMP,
            rejection(event, CatalogueTrust.MOONBOARD_D_TAG, now = createdAt - 3601),
        )
        assertIs<ManifestVerdict.Accepted>(verdict(event, CatalogueTrust.MOONBOARD_D_TAG, now = createdAt - 3600))
    }

    @Test
    fun `non-event json is rejected`() {
        assertEquals(ManifestRejection.MALFORMED_EVENT, rejection(JsonObject(emptyMap()), CatalogueTrust.KILTER_D_TAG))
    }

    @Test
    fun `chunk names that could escape the work directory are rejected`() {
        fun chunk(name: String, urls: List<String> = listOf("https://a.example/x")) =
            CatalogueChunk(name, "climbs", "00", 1, urls)
        assertTrue(CatalogueTrust.isValidChunk(chunk("climbs-2018_05")))
        for (bad in listOf("", "../x", "a/b", "a.b", "a b", "x".repeat(65), "climbs\n")) {
            assertFalse(CatalogueTrust.isValidChunk(chunk(bad)), bad)
        }
        assertFalse(CatalogueTrust.isValidChunk(chunk("ok", listOf("http://a.example/x", "ftp://b"))))
        assertFalse(CatalogueTrust.isValidChunk(chunk("ok", emptyList())))
        assertTrue(CatalogueTrust.isValidChunk(chunk("ok", listOf("http://a.example/x", "https://b.example/x"))))
    }

    @Test
    fun `content without required fields does not parse`() {
        assertNull(CatalogueTrust.parseContent("""{"v":2,"board":"kilter","compression":"zstd","chunks":[]}"""))
        assertNull(CatalogueTrust.parseContent("""{"v":2,"board":"kilter","created_at":1,"compression":"zstd","chunks":[{"name":"a"}]}"""))
        assertNull(CatalogueTrust.parseContent("not json"))
    }

    @Test
    fun `newest event wins and the lower id breaks ties`() {
        fun m(at: Long, id: String) = CatalogueManifest(2, "kilter", createdAt = 1, compression = "zstd", chunks = emptyList(), eventCreatedAt = at, eventId = id)
        assertEquals("new", CatalogueTrust.selectPreferred(listOf(m(10, "old"), m(11, "new"), m(9, "x")))?.eventId)
        assertEquals("aa", CatalogueTrust.selectPreferred(listOf(m(10, "bb"), m(10, "aa")))?.eventId)
        assertNull(CatalogueTrust.selectPreferred(emptyList()))
    }

    // ── rollback watermark and incremental markers ──

    private val manifest = CatalogueManifest(
        2, "kilter", createdAt = 1000, compression = "zstd",
        chunks = listOf(
            CatalogueChunk("climbs-1", "climbs", "h1", 1, listOf("https://a/x")),
            CatalogueChunk("beta", "beta", "h2", 1, listOf("https://a/y")),
        ),
        eventCreatedAt = 1000, eventId = "e1",
    )

    @Test
    fun `rollback below the watermark is refused while an equal timestamp can resume`() {
        val kv = FakeKeyValueStore()
        val store = ManifestTrackStore(kv, "blossom_sync", FixedClock(5000))
        assertTrue(store.canApplyManifest(manifest))
        store.saveCompletedManifest(manifest, manifest.chunks, CatalogueTrust.BETA_IMPORT_VERSION)
        assertEquals("1000", kv.map["blossom_sync/last_manifest_created_at"])
        assertEquals("h1", kv.map["blossom_sync/chunk_sha256_climbs-1"])
        assertEquals("h2", kv.map["blossom_sync/imported_beta_links_v1_sha256_beta"])

        val stale = manifest.copy(eventCreatedAt = 999, chunks = listOf(manifest.chunks[0].copy(sha256 = "rolled-back")))
        assertFalse(store.canApplyManifest(stale))
        assertTrue(store.getChangedChunks(stale).isEmpty())
        store.saveCompletedManifest(stale, stale.chunks)
        assertEquals("1000", kv.map["blossom_sync/last_manifest_created_at"])
        assertEquals("h1", kv.map["blossom_sync/chunk_sha256_climbs-1"])

        assertTrue(store.canApplyManifest(manifest))
        assertTrue(store.getChangedChunks(manifest, CatalogueTrust.BETA_IMPORT_VERSION, CatalogueTrust::isBetaChunk).isEmpty())
    }

    @Test
    fun `a download hash saved by an importer without beta support does not count as a beta import`() {
        val kv = FakeKeyValueStore()
        val store = ManifestTrackStore(kv, "blossom_sync", FixedClock(5000))
        store.saveChunkHash("climbs-1", "h1")
        store.saveChunkHash("beta", "h2")
        val changed = store.getChangedChunks(manifest, CatalogueTrust.BETA_IMPORT_VERSION, CatalogueTrust::isBetaChunk)
        assertEquals(listOf("beta"), changed.map { it.name })
    }

    @Test
    fun `future manifest never advances the watermark and a poisoned watermark is repaired`() {
        val kv = FakeKeyValueStore()
        val store = ManifestTrackStore(kv, "t", FixedClock(5000))
        val future = manifest.copy(eventCreatedAt = 5000 + 3601)
        assertFalse(store.canApplyManifest(future))
        store.saveCompletedManifest(future, future.chunks)
        assertTrue(kv.map.isEmpty())

        kv.map["t/last_manifest_created_at"] = "99999999"
        assertNull(store.lastAcceptedManifestTimestamp())
        assertFalse("t/last_manifest_created_at" in kv.map)
        assertTrue(store.canApplyManifest(manifest))
    }

    @Test
    fun `clearing one track leaves the others intact`() {
        val kv = FakeKeyValueStore()
        val kilter = ManifestTrackStore(kv, "blossom_sync", FixedClock(5000))
        val moon = ManifestTrackStore(kv, "blossom_sync_moonboard", FixedClock(5000))
        kilter.saveCompletedManifest(manifest, manifest.chunks)
        moon.saveCompletedManifest(manifest, manifest.chunks)
        kilter.clear()
        assertFalse(kilter.hasAnyChunkHash())
        assertTrue(moon.hasAnyChunkHash())
        assertEquals(1000L, moon.lastAcceptedManifestTimestamp())
    }
}
