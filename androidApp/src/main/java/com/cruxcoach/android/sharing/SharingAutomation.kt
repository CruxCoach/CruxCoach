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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The application's part of one exchange: apply received messages and hand
 * new outgoing messages to the host. Phase-independent seam for the sync
 * semantics; the transport itself belongs to the native host. */
fun interface SharingStep {
    suspend fun run(host: MarmotHost)
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
 */
@Singleton
class SharingAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val native: AndroidMarmotHost,
    private val step: SharingStep,
    private val database: com.cruxcoach.db.secure.SecureDatabase,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()
    @Volatile private var foreground = false
    private var loop: Job? = null
    private var started = false
    private var sourceChanges: SharingSourceChanges? = null
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { nudge() }
    }

    fun start() {
        if (started) return
        started = true
        sourceChanges = SharingSourceChanges(database) { nudge() }
        SharingWork.retireVersionOne(context)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
    }

    /** Something changed locally (a source row, a policy, a request). */
    fun nudge() {
        if (!native.exists()) return
        scope.launch { pass() }
        if (!foreground) SharingWork.wake(context)
    }

    suspend fun pass(): Boolean = serial.withLock { SharingPass(native.host, step).run(stayOnline = foreground) }

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
    private val native: AndroidMarmotHost,
    private val automation: SharingAutomation,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result =
        if (!native.exists() || automation.pass()) Result.success() else Result.retry()
}

object SharingWork {
    const val PERIODIC = "private-sharing-periodic-v2"
    const val CHANGE = "private-sharing-change-v2"
    private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** v1 scheduled a worker class that no longer exists; stop its jobs. */
    fun retireVersionOne(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("private-continuous-sharing-periodic-v1")
        manager.cancelUniqueWork("private-continuous-sharing-change-v1")
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
