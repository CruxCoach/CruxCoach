package com.cruxcoach.android.sharing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The application's part of one exchange: apply received messages and hand
 * new outgoing messages to the host. Phase-independent seam for the sync
 * semantics; the transport itself belongs to the native host. */
fun interface SharingStep {
    suspend fun run(host: MarmotHost)

    /** Local obligations that must precede any network activity (withdrawals). */
    suspend fun beforeOnline(host: MarmotHost) = Unit
}

/** One bounded pass, shared by the foreground loop and the Worker: go online,
 * let the native host publish and catch up, run the app step, and wait a
 * little for outbound delivery. Never logs payloads or provider messages. */
internal class SharingPass(
    private val host: MarmotHost,
    private val step: SharingStep,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(stayOnline: Boolean): Boolean {
        return try {
            step.beforeOnline(host)
            host.setOnline(true)
            host.sync()
            step.run(host)
            for (attempt in 0 until 8) {
                if (host.status().local.outboundPending == 0L) break
                pause(2_000)
                host.sync()
                step.run(host)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            if (!stayOnline) runCatching { host.setOnline(false) }
        }
    }
}

/**
 * Application lifetime, independent of the sharing screen. Online only while the
 * app is in the foreground and during background passes; nothing starts for an
 * account that never used sharing on this installation. Android Doze,
 * force-stop and offline periods delay but never widen what is shared.
 *
 * Everything here waits for [SharingFeature]: while it is off (always in release
 * builds) no observer, network callback or worker exists and the native host is
 * never created.
 */
@Singleton
class SharingAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nativeHost: dagger.Lazy<AndroidMarmotHost>,
    private val sharingStep: dagger.Lazy<SharingStep>,
    private val database: com.cruxcoach.db.secure.SecureDatabase,
    private val feature: SharingFeature,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()
    @Volatile private var foreground = false
    @Volatile private var active = false
    private var loop: Job? = null
    private var started = false
    private var sourceChanges: SharingSourceChanges? = null
    private val native: AndroidMarmotHost get() = nativeHost.get()
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { nudge() }
    }

    /** Called once at app start, on the main thread. */
    fun start() {
        if (started) return
        started = true
        SharingWork.retireVersionOne(context)
        scope.launch {
            feature.enabled.distinctUntilChanged().collect { enabled ->
                withContext(Dispatchers.Main) { if (enabled) activate() else deactivate() }
            }
        }
    }

    private fun activate() {
        if (active) return
        active = true
        sourceChanges = SharingSourceChanges(database) { nudge() }
        // Dispatches onStart at once when the app is already in the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
    }

    private fun deactivate() {
        if (!active) {
            SharingWork.cancel(context)
            return
        }
        active = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        foreground = false
        loop?.cancel()
        loop = null
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        sourceChanges?.close()
        sourceChanges = null
        SharingWork.cancel(context)
        scope.launch { serial.withLock { if (native.exists()) runCatching { native.host.setOnline(false) } } }
    }

    /** Something changed locally (a source row, a policy, a request). */
    fun nudge() {
        if (!active || !native.exists()) return
        scope.launch { pass() }
        if (!foreground) SharingWork.wake(context)
    }

    suspend fun pass(): Boolean = serial.withLock {
        if (!feature.isEnabled()) return@withLock true
        SharingPass(native.host, sharingStep.get()).run(stayOnline = foreground).also { scheduleExpiry() }
    }

    private val store by lazy { SharingStore(database) }

    /** Local only: delete received records whose grant ended, without the native host. */
    fun purgeExpired() = runCatching { store.purgeExpiredRecords(System.currentTimeMillis()) }

    /** Wake when the next grant ends, even with no network (a received record must go then). */
    private fun scheduleExpiry() {
        val now = System.currentTimeMillis()
        val next = runCatching { store.nextExpiry(now) }.getOrNull()
        if (next == null) SharingWork.cancelExpiry(context) else SharingWork.expiry(context, next - now)
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        if (!native.exists()) return
        SharingWork.periodic(context)
        loop?.cancel()
        loop = scope.launch {
            pass()
            var after = 0L
            var generation = 0L
            while (isActive && foreground) {
                val batch = runCatching { native.host.next(after, generation, timeoutMs = 25_000) }.getOrNull()
                if (batch == null) {
                    delay(5_000)
                    continue
                }
                if (batch.items.isNotEmpty() || batch.generation != generation) pass()
                after = batch.items.lastOrNull()?.seq ?: after
                generation = batch.generation
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        loop?.cancel()
        loop = null
        if (native.exists()) scope.launch { pass() }
    }
}

@HiltWorker
class SharingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val native: dagger.Lazy<AndroidMarmotHost>,
    private val automation: SharingAutomation,
    private val feature: SharingFeature,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = when {
        !feature.isEnabled() -> Result.success().also { SharingWork.cancel(applicationContext) }
        !native.get().exists() -> Result.success()
        else -> {
            automation.purgeExpired()
            if (automation.pass()) Result.success() else Result.retry()
        }
    }
}

object SharingWork {
    const val PERIODIC = "private-sharing-periodic-v2"
    const val CHANGE = "private-sharing-change-v2"
    const val EXPIRY = "private-sharing-expiry-v3"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** v1 scheduled a worker class that no longer exists; stop its jobs. */
    fun retireVersionOne(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("private-continuous-sharing-periodic-v1")
        manager.cancelUniqueWork("private-continuous-sharing-change-v1")
    }

    /** The feature was turned off: no background pass may run any more. */
    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(PERIODIC)
        manager.cancelUniqueWork(CHANGE)
        manager.cancelUniqueWork(EXPIRY)
    }

    fun cancelExpiry(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(EXPIRY)

    /** One local wake at the next expiry; no network needed to delete received data. */
    fun expiry(context: Context, delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            EXPIRY, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SharingWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    fun periodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SharingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun wake(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHANGE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SharingWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }
}
