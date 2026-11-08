package com.cruxcoach.android.ui.spraywall

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.android.data.DarkModeSetting
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.android.nostr.profile.ImageProcessor
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.android.payment.model.NostrProfileData
import com.cruxcoach.android.spraywall.HoldModelStore
import com.cruxcoach.android.spraywall.SprayWallFetcher
import com.cruxcoach.android.spraywall.SprayWallPhotoStore
import com.cruxcoach.android.spraywall.SprayWallPublisher
import com.cruxcoach.android.spraywall.SprayWallRepository
import com.cruxcoach.android.spraywall.SprayWithdrawal
import com.cruxcoach.android.ui.map.MapVenue
import com.cruxcoach.android.ui.settings.SprayModelStorageSection
import com.cruxcoach.android.ui.theme.CruxCoachTheme
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.spraywall.EditableHold
import com.cruxcoach.domain.spraywall.SprayGym
import com.cruxcoach.domain.spraywall.SprayHoldRole
import com.cruxcoach.domain.spraywall.SprayWallVisibility
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Design review of the spray-wall screens without a device: renders every
 * screen and state with a synthetic wall into `CRUXCOACH_UI_REVIEW_DIR`, each
 * in dark and light at font scale 1.0 and in dark at 2.0. The subclasses set
 * the language and the width (English and German at 411 dp, German at 320 dp).
 * Skipped unless that variable is set, so CI pays nothing.
 */
abstract class SprayWallUiReview(private val variant: String) {
    @get:Rule val compose = createComposeRule()

    private val dir: String? = System.getenv("CRUXCOACH_UI_REVIEW_DIR")
    /** Optional comma-separated shot names: only those are rendered. */
    private val only: Set<String>? = System.getenv("CRUXCOACH_UI_REVIEW_ONLY")?.split(',')?.map { it.trim() }?.toSet()

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(dir != null)

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: SecureDatabase
    private lateinit var repository: SprayWallRepository
    private lateinit var photos: SprayWallPhotoStore
    private val preferences by lazy { createTestUserPreferences(scope) }
    private val lastOutcome = MutableStateFlow<SprayWallPublisher.Outcome?>(null)
    private val publisher = mockk<SprayWallPublisher>(relaxed = true).also {
        every { it.last } returns lastOutcome
        every { it.hasIdentity() } returns true
    }
    private val fetcher = mockk<SprayWallFetcher>(relaxed = true)
    // Owners have account names, as most people who share a wall will.
    private val profiles = mockk<NostrProfileManager>().also {
        coEvery { it.getProfile(any()) } answers { NostrProfileData(pubkey = firstArg(), displayName = "Mara K.", lightningAddress = null, pictureUrl = null) }
    }
    private val venueSource by lazy { mockk<SprayVenueSource>().also { coEvery { it.venues() } returns venues } }

    /** View models made here: their work is stopped before the database closes. */
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private fun <T : androidx.lifecycle.ViewModel> T.tracked(): T = also { viewModels += it }

    @Before
    fun setUp() {
        // The camera button needs a content URI; the test app has no file provider.
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns Uri.parse("content://review/capture.jpg")
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SecureDatabase.Schema.create(driver)
        database = SecureDatabase(driver)
        photos = SprayWallPhotoStore(context, ImageProcessor(context))
        repository = SprayWallRepository(database, photos)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        unmockkStatic(FileProvider::class)
        if (::driver.isInitialized) driver.close()
        File(context.filesDir, "spray_walls").deleteRecursively()
    }

    // ── Shots ──

    /**
     * Renders [content] three times: dark and light at font scale 1, dark at 2.
     * The clock only moves when told to, so spinners and the scan animation do
     * not keep the test from ever being idle.
     */
    private fun shoot(name: String, ready: () -> Boolean = { true }, act: () -> Unit = {}, content: @Composable () -> Unit) {
        if (only != null && name !in only) return
        var dark by mutableStateOf(true)
        var fontScale by mutableStateOf(1f)
        var view: android.view.View? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CruxCoachTheme(if (dark) DarkModeSetting.DARK else DarkModeSetting.LIGHT) { Surface { content() } }
            }
        }
        settle(ready)
        act()
        settle({ true })
        for ((suffix, d, f) in listOf(Triple("dark", true, 1f), Triple("light", false, 1f), Triple("dark-2x", true, 2f))) {
            dark = d
            fontScale = f
            settle({ true })
            capture(requireNotNull(view), "$variant-$name-$suffix")
        }
    }

    /** Lets background work and frames run until [ready], then one more second for dialogs and fades. */
    private fun settle(ready: () -> Boolean, timeoutMs: Long = 120_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(100)
            if (ready()) break
            check(System.currentTimeMillis() < end) { "not ready after $timeoutMs ms" }
            Thread.sleep(20)
        }
        repeat(10) {
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun capture(root: android.view.View, file: String) = compose.runOnUiThread {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        // Sheets and dialogs live in windows of their own: draw them on top, in order.
        windows().filter { it !== root.rootView && it.width > 0 }.forEach { w ->
            val at = IntArray(2).also { w.getLocationOnScreen(it) }
            canvas.save(); canvas.translate(at[0].toFloat(), at[1].toFloat()); w.draw(canvas); canvas.restore()
        }
        File(dir!!).mkdirs()
        File(dir, "$file.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
    }.getOrDefault(emptyList())

    private fun tagShown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    // ── Data: a synthetic plywood wall ──

    private data class PaintedHold(val x: Float, val y: Float, val r: Float, val color: Int)

    private fun paintedHolds(seed: Int, count: Int): List<PaintedHold> {
        val random = Random(seed)
        val colours = listOf(0xFFE53935, 0xFF1E88E5, 0xFFFDD835, 0xFF43A047, 0xFF212121, 0xFFF5F5F5, 0xFF8E24AA, 0xFFFB8C00)
        return List(count) {
            PaintedHold(
                x = 0.06f + random.nextFloat() * 0.88f,
                y = 0.06f + random.nextFloat() * 0.88f,
                r = 0.010f + random.nextFloat() * 0.018f,
                color = colours[random.nextInt(colours.size)].toInt(),
            )
        }
    }

    /** A plywood wall photo with T-nut holes and [holds], stored like an imported photo. */
    private fun wallPhoto(holds: List<PaintedHold>, width: Int = 1600, height: Int = 1200, tint: Int = 0xFFD2B48C.toInt()): SprayWallPhotoStore.StoredPhoto {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tint)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Grain and panel seams.
        paint.color = 0x22000000
        for (i in 0 until 60) canvas.drawLine(0f, i * height / 60f + (i % 3) * 2f, width.toFloat(), i * height / 60f + (i % 5) * 3f, paint)
        paint.color = 0x55000000
        paint.strokeWidth = 4f
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
        // T-nuts every 8 % of the width.
        paint.color = 0xFF3E2C1C.toInt()
        val step = width * 0.08f
        var y = step / 2
        while (y < height) {
            var x = step / 2
            while (x < width) { canvas.drawCircle(x, y, 5f, paint); x += step }
            y += step
        }
        for (h in holds) {
            paint.color = h.color
            val r = h.r * width
            canvas.drawOval(h.x * width - r, h.y * height - r * 0.8f, h.x * width + r, h.y * height + r * 0.8f, paint)
            paint.color = 0x33000000
            canvas.drawCircle(h.x * width + r * 0.3f, h.y * height + r * 0.3f, r * 0.25f, paint)
        }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
        bitmap.recycle()
        return runBlocking { photos.storeDownloaded(bytes) }
    }

    private fun PaintedHold.editable() = EditableHold(-1, x, y, r)

    private val gym = SprayGym("475763,93431", "Boulderhalle Nord, Hamburg")
    private val venues = listOf(
        MapVenue("475763,93431", 47.5763, 9.3431, "Boulderhalle Nord", "Hamburg", "DE", emptyList()),
        MapVenue("481374,115755", 48.1374, 11.5755, "Kletterzentrum Süd", "München", "DE", emptyList()),
        MapVenue("525200,134050", 52.52, 13.405, "Spraywall Kollektiv", "Berlin", "DE", emptyList()),
    )

    private val names = listOf(
        "Crimp ladder", "Left arête", "The long move", "Kitchen traverse", "Pinch city",
        "Sloper sunday", "Heel hook problem", "Warm-up circuit", "Big span", "Project X",
    )

    /** My public wall with climbs, ascents, a hold edit that cost one climb two holds, published. */
    private fun mainWall(): String = runBlocking {
        val painted = paintedHolds(seed = 7, count = 46)
        val photo = wallPhoto(painted)
        val id = repository.createWall("Garage wall", 40, SprayWallVisibility.PUBLIC, gym, photo, painted.map { it.editable() })
        val holds = repository.holds(id, 1)
        names.forEachIndexed { i, name ->
            val picked = holds.shuffled(Random(i)).take(6 + i % 4)
            val roles = picked.mapIndexed { j, h ->
                h.id to when (j) { 0 -> SprayHoldRole.START; picked.lastIndex -> SprayHoldRole.FINISH; 1, 2 -> SprayHoldRole.FOOT; else -> SprayHoldRole.HAND }
            }.toMap()
            val uuid = repository.saveClimb(null, id, name, if (i == 0) "Small crimps, keep the hips in." else "", (10 + i * 2).takeIf { i != 9 }, roles)
            val climb = repository.climb(uuid)!!
            repeat(i % 3) { n -> repository.logAscent(climb, isSend = n == 0, tries = 2 + n, difficulty = null, comment = null, climbedAt = LocalDateTime.of(2026, 10, 20 + n, 18, 0)) }
        }
        // Two holds of the first climb come off.
        val first = repository.climbs(id).first { it.name == names[0] }
        val removed = first.holds.keys.take(2).toSet()
        repository.saveHolds(id, repository.holds(id, 1).filter { it.id !in removed }.map { EditableHold(it.id, it.x, it.y, it.radius) })
        repository.markPublished(id, repository.wall(id)!!.current.number)
        id
    }

    private fun privateWall(name: String = "Kitchen board", climbs: Boolean = false): String = runBlocking {
        val painted = paintedHolds(seed = 11, count = 24)
        val photo = wallPhoto(painted, width = 1200, height = 1600, tint = 0xFF9E9E9E.toInt())
        val id = repository.createWall(name, 25, SprayWallVisibility.PRIVATE, null, photo, painted.map { it.editable() })
        if (climbs) {
            val holds = repository.holds(id, 1)
            repository.saveClimb(null, id, "First line", "", 12, holds.take(5).associate { it.id to SprayHoldRole.HAND })
        }
        id
    }

    private fun newWall(): String = runBlocking {
        val photo = wallPhoto(paintedHolds(seed = 3, count = 40))
        repository.createWall("My spray wall", 40, SprayWallVisibility.PRIVATE, null, photo, emptyList())
    }

    /** Someone else's public wall, added here from the map. */
    private fun followedWall(): String = runBlocking {
        val painted = paintedHolds(seed = 5, count = 30)
        val photo = wallPhoto(painted, tint = 0xFF37474F.toInt())
        val id = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
        val q = database.sprayWallsQueries
        val t = 1_790_000_000L
        q.insertWall(id, "Gym spray wall", 45, "public", gym.id, gym.name, "b7".repeat(32), 1, 1, t, t)
        q.insertVersion(id, 1, "created", null, photo.fileName, photo.width.toLong(), photo.height.toLong(), null, null, painted.size.toLong(), t)
        painted.forEachIndexed { i, h -> q.insertHold(id, 1, i + 1L, h.x.toDouble(), h.y.toDouble(), h.r.toDouble(), null) }
        id
    }

    private fun saved(vararg args: Pair<String, Any?>) = SavedStateHandle(mapOf(*args))

    // ── Screens ──

    @Test
    fun list() {
        mainWall(); privateWall(); followedWall()
        val vm = SprayWallListViewModel(repository, photos).tracked()
        shoot("list", ready = { vm.state.value.thumbnails.size == 3 }) { SprayWallListScreen({}, {}, {}, viewModel = vm) }
    }

    @Test
    fun listEmpty() {
        val vm = SprayWallListViewModel(repository, photos).tracked()
        shoot("list-empty", ready = { !vm.state.value.loading }) { SprayWallListScreen({}, {}, {}, viewModel = vm) }
    }

    private fun wallVm(id: String) = SprayWallViewModel(saved(SprayWallRoutes.ARG_WALL to id), repository, photos, preferences, publisher, fetcher, profiles).tracked()

    private fun wallShot(name: String, id: String, act: () -> Unit = {}) {
        val vm = wallVm(id)
        shoot(name, ready = { vm.state.value.wall != null && vm.state.value.photo != null }, act = act) {
            SprayWallScreen({}, {}, {}, {}, {}, {}, {}, viewModel = vm)
        }
    }

    @Test fun wall() = wallShot("wall", mainWall())

    @Test fun wallMenu() = wallShot("wall-menu", mainWall(), act = { compose.onNodeWithTag("spray_wall_menu").performClick() })

    @Test fun wallSortMenu() = wallShot("wall-sort-menu", mainWall(), act = { compose.onNodeWithTag("spray_sort").performClick() })

    @Test fun wallLostHoldsFilter() {
        val id = mainWall()
        wallShot("wall-lost", id, act = { compose.onNodeWithTag("spray_filter_lost_holds").performClick() })
    }

    @Test fun wallNoClimbs() = wallShot("wall-no-climbs", privateWall())

    @Test fun wallNoHolds() = wallShot("wall-no-holds", newWall())

    @Test fun wallPublishFailed() {
        val id = runBlocking {
            val painted = paintedHolds(seed = 9, count = 20)
            repository.createWall("Garage wall", 40, SprayWallVisibility.PUBLIC, null, wallPhoto(painted), painted.map { it.editable() })
        }
        lastOutcome.value = SprayWallPublisher.Outcome.Failed("offline")
        wallShot("wall-publish-failed", id)
    }

    @Test fun wallWithdrawUnfinished() {
        val id = mainWall()
        runBlocking { repository.markWithdrawStarted(id) }
        wallShot("wall-withdraw-unfinished", id)
    }

    @Test fun wallUnfinishedReset() {
        val id = mainWall()
        runBlocking { repository.resetWall(id, wallPhoto(paintedHolds(seed = 21, count = 35), tint = 0xFFBCAAA4.toInt()), emptyList()) }
        wallShot("wall-reset-unfinished", id)
    }

    @Test fun wallAfterReset() {
        val id = mainWall()
        runBlocking {
            val painted = paintedHolds(seed = 21, count = 35)
            repository.resetWall(id, wallPhoto(painted, tint = 0xFFBCAAA4.toInt()), painted.map { it.editable() })
        }
        wallShot("wall-after-reset", id)
    }

    @Test fun wallFollowed() = wallShot("wall-followed", followedWall())

    private fun photoVm(vararg args: Pair<String, Any?>) = SprayWallPhotoViewModel(saved(*args), repository, photos).tracked()

    @Test fun photoNew() {
        val vm = photoVm()
        shoot("photo-new") { SprayWallPhotoScreen({}, {}, viewModel = vm) }
    }

    /** A picked photo: [adjusting] keeps the crop and rotate dialog open. */
    private fun photoPicked(name: String, adjusting: Boolean) {
        val vm = photoVm()
        val photo = wallPhoto(paintedHolds(seed = 13, count = 30))
        val uri = Uri.fromFile(photos.file(photo.fileName)!!)
        vm.onPhoto(uri)
        shoot(name, ready = { vm.state.value.preview != null }, act = { if (!adjusting) vm.closeAdjust() }) {
            SprayWallPhotoScreen({}, {}, viewModel = vm)
        }
    }

    @Test fun photoAdjust() = photoPicked("photo-adjust", adjusting = true)

    @Test fun photoChosen() = photoPicked("photo-chosen", adjusting = false)

    @Test fun photoReset() {
        val vm = photoVm(SprayWallRoutes.ARG_WALL to mainWall())
        shoot("photo-reset", ready = { vm.state.value.wallName.isNotEmpty() }) { SprayWallPhotoScreen({}, {}, viewModel = vm) }
    }

    private fun holdsVm(id: String, first: Boolean) = SprayHoldEditorViewModel(
        saved(SprayWallRoutes.ARG_WALL to id, SprayWallRoutes.ARG_FIRST to first), repository, photos, HoldModelStore(context, okhttp3.OkHttpClient()),
    ).tracked()

    @Test fun holdsFirst() {
        val vm = holdsVm(newWall(), first = true)
        shoot("holds-first", ready = { vm.state.value.detected && vm.state.value.photo != null }) { SprayHoldEditorScreen({}, {}, viewModel = vm) }
    }

    @Test fun holdsEdit() {
        val id = mainWall()
        val vm = holdsVm(id, first = false)
        shoot("holds-edit", ready = { vm.state.value.photo != null }) { SprayHoldEditorScreen({}, {}, viewModel = vm) }
    }

    @Test fun holdsSelected() {
        val id = mainWall()
        val vm = holdsVm(id, first = false)
        shoot(
            "holds-selected",
            ready = { vm.state.value.photo != null },
            act = { vm.state.value.editor.holds.first().let { vm.onTap(it.x, it.y, it.id) } },
        ) { SprayHoldEditorScreen({}, {}, viewModel = vm) }
    }

    private fun detailsVm(id: String, first: Boolean = false) =
        SprayWallDetailsViewModel(saved(SprayWallRoutes.ARG_WALL to id, SprayWallRoutes.ARG_FIRST to first), repository, publisher, venueSource).tracked()

    private fun detailsShot(name: String, vm: SprayWallDetailsViewModel, act: () -> Unit = {}) =
        shoot(name, ready = { !vm.state.value.loading }, act = act) { SprayWallDetailsScreen({}, {}, {}, viewModel = vm) }

    @Test fun detailsFirst() = detailsShot("details-first", detailsVm(newWall(), first = true))

    @Test fun detailsPublic() = detailsShot("details-public", detailsVm(mainWall()))

    @Test fun detailsConfirmPublic() {
        val vm = detailsVm(privateWall())
        detailsShot("details-confirm-public", vm, act = { vm.chooseVisibility(SprayWallVisibility.PUBLIC) })
    }

    @Test fun detailsConfirmWithdraw() {
        val vm = detailsVm(mainWall())
        detailsShot("details-confirm-withdraw", vm, act = { vm.chooseVisibility(SprayWallVisibility.PRIVATE) })
    }

    @Test fun detailsWithdrawFailed() {
        coEvery { publisher.withdrawWall(any()) } returns SprayWithdrawal.NOT_CONFIRMED
        val vm = detailsVm(mainWall())
        detailsShot("details-withdraw-failed", vm, act = {
            vm.chooseVisibility(SprayWallVisibility.PRIVATE)
            vm.confirmDialog()
            vm.setName("Garage wall (old)")
            vm.save()
            settle({ vm.state.value.withdrawFailed != null })
        })
    }

    @Test fun detailsWithdrawUnfinished() {
        val id = mainWall()
        runBlocking { repository.markWithdrawStarted(id) }
        detailsShot("details-withdraw-unfinished", detailsVm(id))
    }

    @Test fun detailsGymPicker() {
        val vm = detailsVm(privateWall())
        detailsShot("details-gym-picker", vm, act = {
            vm.openGymPicker()
            settle({ vm.state.value.venues != null })
        })
    }

    @Test fun detailsNoIdentity() {
        every { publisher.hasIdentity() } returns false
        val vm = detailsVm(privateWall())
        detailsShot("details-no-identity", vm, act = { vm.chooseVisibility(SprayWallVisibility.PUBLIC) })
    }

    private fun climbEditorVm(wallId: String, climbUuid: String? = null) = SprayClimbEditorViewModel(
        saved(SprayWallRoutes.ARG_WALL to wallId, SprayWallRoutes.ARG_CLIMB to climbUuid), repository, photos, preferences, publisher,
    ).tracked()

    @Test fun climbNew() {
        val id = mainWall()
        val vm = climbEditorVm(id)
        shoot("climb-new", ready = { tagShown("spray_climb_name") }) { SprayClimbEditorScreen({}, {}, {}, viewModel = vm) }
    }

    @Test fun climbEdit() {
        val id = mainWall()
        val uuid = runBlocking { repository.climbs(id).first { it.name == names[2] }.uuid }
        val vm = climbEditorVm(id, uuid)
        shoot("climb-edit", ready = { tagShown("spray_climb_name") }) { SprayClimbEditorScreen({}, {}, {}, viewModel = vm) }
    }

    private fun climbDetailVm(uuid: String) = SprayClimbDetailViewModel(saved(SprayWallRoutes.ARG_CLIMB to uuid), repository, photos, preferences).tracked()

    @Test fun climbDetail() {
        val id = mainWall()
        val uuid = runBlocking { repository.climbs(id).first { it.name == names[2] }.uuid }
        val vm = climbDetailVm(uuid)
        shoot("climb-detail", ready = { tagShown("spray_log_send") }) { SprayClimbDetailScreen({}, { _, _ -> }, viewModel = vm) }
    }

    @Test fun climbDetailLostHolds() {
        val id = mainWall()
        val uuid = runBlocking { repository.climbs(id).first { it.name == names[0] }.uuid }
        val vm = climbDetailVm(uuid)
        shoot("climb-detail-lost", ready = { tagShown("spray_log_send") }) { SprayClimbDetailScreen({}, { _, _ -> }, viewModel = vm) }
    }

    @Test fun climbLog() {
        val id = mainWall()
        val uuid = runBlocking { repository.climbs(id).first { it.name == names[4] }.uuid }
        val vm = climbDetailVm(uuid)
        shoot("climb-log", ready = { tagShown("spray_log_send") }, act = { compose.onNodeWithTag("spray_log_send").performClick() }) {
            SprayClimbDetailScreen({}, { _, _ -> }, viewModel = vm)
        }
    }

    @Test fun versions() {
        val id = mainWall()
        runBlocking {
            val painted = paintedHolds(seed = 21, count = 35)
            repository.resetWall(id, wallPhoto(painted, tint = 0xFFBCAAA4.toInt()), painted.map { it.editable() })
        }
        val vm = SprayWallVersionsViewModel(saved(SprayWallRoutes.ARG_WALL to id), repository, photos).tracked()
        shoot("versions", ready = { tagShown("spray_version_row") }) { SprayWallVersionsScreen({}, viewModel = vm) }
    }

    @Test fun versionsEarlierSelected() {
        val id = mainWall()
        val vm = SprayWallVersionsViewModel(saved(SprayWallRoutes.ARG_WALL to id), repository, photos).tracked()
        shoot("versions-earlier", ready = { tagShown("spray_version_row") }, act = { vm.select(1) }) { SprayWallVersionsScreen({}, viewModel = vm) }
    }

    private fun venueShot(name: String, walls: List<SprayWallFetcher.Listing>?, load: Boolean) {
        if (walls != null) coEvery { fetcher.wallsAt(any()) } returns walls
        val vm = SprayVenueWallsViewModel(fetcher, profiles).tracked()
        vm.select(venues[0].id)
        if (load) vm.load()
        shoot(name, ready = { !vm.state.value.loading }) {
            Column(Modifier.padding(16.dp)) { SprayWallsAtVenue(venues[0], {}, {}, viewModel = vm) }
        }
    }

    @Test fun venueAsk() = venueShot("venue-ask", null, load = false)

    @Test fun venueWalls() = venueShot(
        "venue-walls",
        listOf(
            SprayWallFetcher.Listing("b7".repeat(32), "w1", "Gym spray wall", 45, gym.name, alreadyHere = true),
            SprayWallFetcher.Listing("c8".repeat(32), "w2", "Moon-style 40°", 40, gym.name, alreadyHere = false),
        ),
        load = true,
    )

    @Test fun venueNone() = venueShot("venue-none", emptyList(), load = true)

    @Test fun settingsSection() = shoot("settings-model") {
        Box(Modifier.padding(16.dp)) { SprayModelStorageSection(onRemove = {}) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SprayWallUiReviewEnTest : SprayWallUiReview("en-411")

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SprayWallUiReviewDeTest : SprayWallUiReview("de-411")

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "de-w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SprayWallUiReviewNarrowTest : SprayWallUiReview("de-320")
