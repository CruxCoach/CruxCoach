package com.cruxcoach.android.updater

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The source list must not hang on one host.
 *
 * Written after 2026-08-05, when cruxcoach.org answered 502 for a full day
 * (Codeberg moved custom-domain Pages to a new server and our DNS never
 * followed). The updater kept working on its embedded list, which is the
 * floor — but the runtime list is the ONE lever that can retire a release
 * host for installs already in the field, and it was unreachable for exactly
 * as long as the host it was published on. A multi-source updater whose
 * source list is single-source is the same mistake one layer up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateSourceRegistryFailoverTest {

    private lateinit var primary: MockWebServer
    private lateinit var mirror: MockWebServer

    private val client = OkHttpClient()
    private val preferences: UpdaterPreferences = mockk(relaxed = true)
    private var storedManifest: String? = null
    private var storedFetchedAt: Long? = null

    /** Past the 24 h cache TTL measured from epoch, so a null "last fetched"
     *  really does read as stale. A small number here silently disables every
     *  network assertion in this class. */
    private val nowMs = 1_800_000_000_000L

    private val manifest = """
        {"version":1,"sources":[
          {"id":"forge","kind":"forge","url":"https://example.org/api/v1",
           "owner":"CruxCoach","repo":"CruxCoach"}
        ]}
    """.trimIndent()

    @Before
    fun setUp() {
        primary = MockWebServer().apply { start() }
        mirror = MockWebServer().apply { start() }

        // In-memory stand-in for the DataStore: the registry writes the
        // fetched manifest and the attempt timestamp back, and later
        // assertions depend on what it decided to persist.
        coEvery { preferences.snapshot() } answers {
            UpdaterState(
                updateSourcesManifestJson = storedManifest,
                updateSourcesFetchedAtEpochMs = storedFetchedAt,
            )
        }
        val transform = slot<(UpdaterState) -> UpdaterState>()
        coEvery { preferences.update(capture(transform)) } answers {
            val next = transform.captured(
                UpdaterState(
                    updateSourcesManifestJson = storedManifest,
                    updateSourcesFetchedAtEpochMs = storedFetchedAt,
                )
            )
            storedManifest = next.updateSourcesManifestJson
            storedFetchedAt = next.updateSourcesFetchedAtEpochMs
        }
    }

    @After
    fun tearDown() {
        primary.shutdown()
        mirror.shutdown()
    }

    private fun registry(vararg urls: String) = UpdateSourceRegistry(
        okHttpClient = client,
        preferences = preferences,
        manifestUrls = urls.toList(),
        nowMsProvider = { nowMs },
    )

    @Test
    fun `a dead primary is answered by the mirror`() = runTest {
        primary.enqueue(MockResponse().setResponseCode(502))
        mirror.enqueue(MockResponse().setResponseCode(200).setBody(manifest))

        val sources = registry(
            primary.url("/update-sources.json").toString(),
            mirror.url("/update-sources.json").toString(),
        ).sources()

        assertEquals(listOf("forge"), sources.map { it.id })
        assertEquals("https://example.org/api/v1", sources.single().url)
        // Both were asked, in order, and the answer was persisted so the next
        // 24 hours cost no request at all.
        assertEquals(1, primary.requestCount)
        assertEquals(1, mirror.requestCount)
        assertEquals(manifest, storedManifest)
    }

    @Test
    fun `a healthy primary is not followed by a second request`() = runTest {
        primary.enqueue(MockResponse().setResponseCode(200).setBody(manifest))

        registry(
            primary.url("/update-sources.json").toString(),
            mirror.url("/update-sources.json").toString(),
        ).sources()

        // The ordering in the list is a preference, not a race to be won —
        // and paying a second daily request on every install to save nothing
        // is exactly the cost this asserts against.
        assertEquals(1, primary.requestCount)
        assertEquals(0, mirror.requestCount)
    }

    @Test
    fun `every host down falls back to the embedded list, not to nothing`() = runTest {
        primary.enqueue(MockResponse().setResponseCode(502))
        mirror.enqueue(MockResponse().setResponseCode(503))

        val sources = registry(
            primary.url("/update-sources.json").toString(),
            mirror.url("/update-sources.json").toString(),
        ).sources()

        assertEquals(UpdateSourceRegistry.EMBEDDED.map { it.id }, sources.map { it.id })
        // The attempt is still stamped, so a host that is down is retried
        // once a day rather than on every single update check.
        assertEquals(nowMs, storedFetchedAt)
    }

    @Test
    fun `the embedded list carries a manifest entry per host`() {
        // The runtime list is precisely what a device does NOT have during an
        // apex outage, so the compiled-in fallback has to be redundant too.
        val manifestSources = UpdateSourceRegistry.EMBEDDED
            .filter { it.kind == UpdateSource.Kind.MANIFEST }
        assertTrue(
            "embedded fallback needs more than one manifest host: $manifestSources",
            manifestSources.size >= 2,
        )
        assertEquals(
            "manifest hosts must be distinct origins",
            manifestSources.size,
            manifestSources.mapNotNull { it.webHost() }.distinct().size,
        )
        assertEquals(manifestSources.size, manifestSources.map { it.id }.distinct().size)
    }

    @Test
    fun `malformed entries are dropped without taking the chain down`() {
        // A fork overriding this in local.properties should lose the entry it
        // fat-fingered, not the whole fallback chain.
        assertEquals(
            listOf("https://a.example/x.json", "https://b.example/x.json"),
            UpdateSourceRegistry.parseUrlList(
                " https://a.example/x.json , http://insecure.example/x.json ," +
                    " https://b.example/x.json , , https://a.example/x.json "
            ),
        )
    }
}
