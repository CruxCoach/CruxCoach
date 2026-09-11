package com.cruxcoach.android.sharing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.cash.sqldelight.Query
import com.cruxcoach.db.secure.SecureDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton

/** SQLDelight emits this listener for source writes including the generated
 * trigger dependencies. Revisions persist even if a process dies before wakeup. */
class ContinuousSourceChanges(database: SecureDatabase, onChange: () -> Unit) : AutoCloseable {
    private val query = database.continuousSharingQueries.sourceVersion()
    private val listener = Query.Listener(onChange)
    init { query.addListener(listener) }
    override fun close() { query.removeListener(listener) }
}

/** Application lifetime, independent of the Sharing screen. No account/relay
 * discovery or consent is enabled by a wakeup. Background jobs remain subject
 * to Android network constraints, Doze and force-stop behavior. */
@Singleton
class ContinuousSharingAutomation @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: SecureDatabase,
    private val controller: SharingController,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var foreground = false
    private var ticker: Job? = null
    private var observer: ContinuousSourceChanges? = null
    private var started = false
    private val task = ContinuousSyncTask(controller::continuousHasWork, controller::synchronizeContinuousAutomatically)
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wakeups.trySend(Unit) }
    }
    fun start() {
        if (started) return
        started = true
        observer = ContinuousSourceChanges(database) { wakeups.trySend(Unit) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        runCatching { context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
        scope.launch {
            for (ignored in wakeups) {
                delay(2_000) // coalesce a burst; the persisted source is read once per run
                while (wakeups.tryReceive().isSuccess) Unit
                if (!controller.continuousHasWork()) continue
                ContinuousSharingWork.periodic(context)
                if (foreground) task.run() else ContinuousSharingWork.wake(context)
            }
        }
        wakeups.trySend(Unit)
    }
    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        ticker?.cancel()
        ticker = scope.launch { while (isActive) { wakeups.trySend(Unit); delay(30_000) } }
    }
    override fun onStop(owner: LifecycleOwner) { foreground = false; ticker?.cancel(); ticker = null }
}
