package com.cruxcoach.android.ui.board

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class BetaThumbnailCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun fixture(): Pair<String, File> {
        val bitmap = Bitmap.createBitmap(80, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "verified_beta_thumbnails_v1/$hash.jpg")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        return hash to file
    }

    @Test fun `recreated request and alternate mirror use decoded memory without disk verification`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (hash, file) = fixture()
        val loader = ImageLoader.Builder(context).allowHardware(false)
            .memoryCache(MemoryCache.Builder(context).maxSizeBytes(1024 * 1024).build()).build()
        var loads = 0
        val load: suspend (File, String) -> File? = { root, url ->
            loads++
            VerifiedBetaThumbnail.load(root, url)
        }
        try {
            val first = betaThumbnailRequest(context, "https://nostr.download/$hash", load)!!
                .newBuilder().size(80, 96).build()
            val cold = loader.execute(first)
            assertTrue(cold.toString(), cold is SuccessResult)
            assertEquals(DataSource.DISK, (cold as SuccessResult).dataSource)
            assertEquals(1, loads)
            // A warmed image must be renderable even if Android removes disk cache.
            assertTrue(file.delete())
            val recreated = betaThumbnailRequest(context, "https://blossom.cruxcoach.org/$hash", load)!!
                .newBuilder().size(80, 96).build()
            assertEquals(first.memoryCacheKey, recreated.memoryCacheKey)
            assertEquals(recreated.memoryCacheKey, recreated.placeholderMemoryCacheKey)
            val warm = loader.execute(recreated)
            assertTrue(warm.toString(), warm is SuccessResult)
            assertEquals(DataSource.MEMORY_CACHE, (warm as SuccessResult).dataSource)
            assertEquals(1, loads)
        } finally {
            loader.shutdown()
            file.delete()
            Dispatchers.resetMain()
        }
    }

    @Test fun `memory eviction falls back to verified local bytes and missing bytes cannot bypass verifier`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (hash, file) = fixture()
        val loader = ImageLoader.Builder(context).allowHardware(false)
            .memoryCache(MemoryCache.Builder(context).maxSizeBytes(1024 * 1024).build())
            .callFactory(okhttp3.Call.Factory { error("Coil must never fetch unverified remote media") }).build()
        var loads = 0
        try {
            fun request() = betaThumbnailRequest(context, "https://nostr.download/$hash") { root, url ->
                loads++
                VerifiedBetaThumbnail.load(root, url)
            }!!.newBuilder().size(80, 96).build()
            assertTrue(loader.execute(request()) is SuccessResult)
            loader.memoryCache!!.clear()
            val reloaded = loader.execute(request())
            assertTrue(reloaded.toString(), reloaded is SuccessResult)
            assertEquals(DataSource.DISK, (reloaded as SuccessResult).dataSource)
            assertEquals(2, loads)
            loader.memoryCache!!.clear()
            val unavailable = betaThumbnailRequest(context, "https://nostr.download/$hash") { _, _ -> null }!!
            assertTrue(loader.execute(unavailable.newBuilder().size(80, 96).build()) is ErrorResult)
            assertNull(betaThumbnailRequest(context, "https://attacker.example/$hash"))
            assertNull(betaThumbnailRequest(context, "https://nostr.download/555f5ee1978ef15c15ca6bd780f1b205e56117f551eb4561ec10d1b9437c9a8e"))
        } finally {
            loader.shutdown()
            file.delete()
            Dispatchers.resetMain()
        }
    }
}
