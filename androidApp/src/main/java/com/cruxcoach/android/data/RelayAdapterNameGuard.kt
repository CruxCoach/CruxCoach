package com.cruxcoach.android.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cruxcoach.domain.relay.RelayBoardName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How the phone's Bluetooth name stands, for the sharing settings. */
enum class RelayNameIssue {
    /** The phone has its own name. */
    NONE,

    /** Sharing runs and uses the name right now. */
    LENT,

    /** Sharing ended, but the phone may still carry the sharing name. CruxCoach keeps trying. */
    RESTORE_PENDING,

    /** As [RESTORE_PENDING], but Android refuses without the Nearby devices permission. */
    NEEDS_PERMISSION,

    /** The phone carries a CruxCoach sharing name and CruxCoach has no record of its own one. */
    ORIGINAL_UNKNOWN,
}

data class RelayNameStatus(
    val issue: RelayNameIssue = RelayNameIssue.NONE,
    /** The phone's own name as recorded before sharing. */
    val originalName: String? = null,
    /** The name Android reports right now, when it can be read. */
    val currentName: String? = null,
)

/** Outcome of [RelayAdapterNameGuard.lend]. Only [LENT] lets sharing advertise. */
enum class RelayNameLend {
    LENT,
    NO_ADAPTER,
    NO_PERMISSION,
    UNKNOWN_ORIGINAL,

    /** The phone already carries a CruxCoach sharing name that this app did not record. */
    ALREADY_SHARING_NAME,
    NOT_RECORDED,
    NOT_APPLIED,
}

/** Switches the receivers that wake CruxCoach to give a name back after the app ended. */
fun interface RelayNameWakeups {
    fun setArmed(armed: Boolean)
}

/**
 * The phone's Bluetooth name, lent to CruxRelay and always given back.
 *
 * Android has no name per advertisement, so sharing a board renames the whole
 * phone, and cars and headphones show that name too. This is the only place
 * that renames it. The phone's own name is recorded durably before the first
 * change, a CruxCoach sharing name is never recorded as the phone's own, and
 * the name goes back on every path the app can see: sharing stops, any process
 * start (also for background work), Bluetooth on, app foreground, boot, app
 * update, the next Bluetooth connection, and best effort before a crash.
 *
 * The record lives in `cruxrelay.xml`, unchanged since 0.2.2, so a record left
 * by an earlier version is honoured. It is excluded from backup and device
 * transfer, so one phone's record never renames another.
 */
class RelayAdapterNameGuard(
    context: Context,
    private val adapterProvider: () -> BluetoothAdapter? = { defaultAdapter(context) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val wakeups: RelayNameWakeups = ComponentWakeups(context.applicationContext),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "CruxRelay/Name"
        internal const val PREFS = "cruxrelay"
        internal const val KEY_NAME_DIRTY = "adapter_name_dirty"
        internal const val KEY_ORIGINAL_NAME = "adapter_name_original"
        internal const val PROPAGATE_TIMEOUT_MS = 2_000L

        /** A rename sent just before giving back may still land after it; look again after this. */
        internal const val LATE_RENAME_SETTLE_MS = 1_500L

        /** Retries while Bluetooth is on but the name did not come back. */
        internal val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L, 300_000L)

        private fun defaultAdapter(context: Context): BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        private fun usableOriginal(name: String?): String? =
            name?.takeIf { it.isNotBlank() && !RelayBoardName.isOwnAdapterName(it) }

        /**
         * Last words of a dying process (uncaught exception): send the recorded
         * name once, without waiting. Android applies it even after the process
         * is gone. The record stays, so the next start still verifies it.
         */
        @SuppressLint("MissingPermission")
        fun giveBackBeforeProcessEnds(context: Context) {
            runCatching {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_NAME_DIRTY, false)) return
                val original = usableOriginal(prefs.getString(KEY_ORIGINAL_NAME, null)) ?: return
                defaultAdapter(context)?.setName(original)
            }
        }
    }

    private sealed interface NameRead {
        data class Known(val name: String) : NameRead
        data object Denied : NameRead
        data object Unreadable : NameRead
    }

    private enum class Sent { OK, DENIED, REFUSED }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** True while sharing in this process uses the name. Never persisted: a new process shares nothing. */
    @Volatile
    private var lent = false

    /** A rename to the sharing name was sent but not seen arriving. Giving back must outrun it. */
    private var renameInFlight = false
    private var retryJob: Job? = null

    private val _status = MutableStateFlow(RelayNameStatus())
    val status: StateFlow<RelayNameStatus> = _status.asStateFlow()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    // setName needs the adapter on. A give-back that failed while it
                    // was off is retried as soon as it is authoritative again.
                    if (state == BluetoothAdapter.STATE_ON) restoreIfStale()
                }
                // Renamed in the system settings, or by us: the settings card follows.
                BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED -> refreshStatus()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            bluetoothReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Records the phone's own name (once) and renames the phone to [desired].
     * Anything but [RelayNameLend.LENT] means sharing must not advertise; the
     * caller then gives the name back through [giveBack].
     */
    @SuppressLint("MissingPermission")
    suspend fun lend(desired: String): RelayNameLend = mutex.withLock {
        retryJob?.cancel()
        retryJob = null
        val adapter = adapterProvider() ?: return@withLock RelayNameLend.NO_ADAPTER
        val current = read(adapter)
        if (current is NameRead.Denied) {
            publish(RelayNameIssue.NEEDS_PERMISSION, recordedOriginal(), null)
            return@withLock RelayNameLend.NO_PERMISSION
        }
        val currentName = (current as? NameRead.Known)?.name
        var recorded = recordedOriginal()
        if (isDirty() && recorded == null) {
            // A sharing name recorded as the phone's own (possible before 0.2.5):
            // giving it "back" would make the loss permanent. Forget it.
            Log.w(TAG, "Discarding a recovery record that holds a sharing name")
            if (!clearRecord()) return@withLock RelayNameLend.NOT_RECORDED
        }
        if (recorded != null && currentName != null && currentName != recorded &&
            !RelayBoardName.isOwnAdapterName(currentName) && !renameInFlight
        ) {
            // Renamed by the user while a give-back was still pending: their name is the own one now.
            if (!clearRecord()) return@withLock RelayNameLend.NOT_RECORDED
            recorded = null
        }
        if (recorded == null) {
            val own = currentName ?: return@withLock RelayNameLend.UNKNOWN_ORIGINAL
            if (RelayBoardName.isOwnAdapterName(own)) {
                // Another CruxCoach install shares right now, or an earlier name never came back.
                publish(RelayNameIssue.ORIGINAL_UNKNOWN, null, own)
                return@withLock RelayNameLend.ALREADY_SHARING_NAME
            }
            // Never change a device-wide setting before its own value is durably known.
            val persisted = withContext(ioDispatcher) {
                prefs.edit()
                    .putString(KEY_ORIGINAL_NAME, own)
                    .putBoolean(KEY_NAME_DIRTY, true)
                    .commit()
            }
            if (!persisted) return@withLock RelayNameLend.NOT_RECORDED
            recorded = own
        }
        lent = true
        wakeups.setArmed(true)
        publish(RelayNameIssue.LENT, recorded, currentName)
        if (currentName == desired) {
            renameInFlight = false
            return@withLock RelayNameLend.LENT
        }
        when (send(adapter, desired)) {
            Sent.OK -> Unit
            Sent.DENIED -> return@withLock RelayNameLend.NO_PERMISSION
            Sent.REFUSED -> return@withLock RelayNameLend.NOT_APPLIED
        }
        renameInFlight = true
        // setName is asynchronous and the scan response reads the adapter name:
        // advertise only once the sharing name is really there.
        if (!awaitName(desired)) return@withLock RelayNameLend.NOT_APPLIED
        renameInFlight = false
        publish(RelayNameIssue.LENT, recorded, desired)
        RelayNameLend.LENT
    }

    /**
     * Sharing ended: give the phone its own name back. A failure keeps the
     * record and retries on its own; true once the own name is verified back.
     */
    suspend fun giveBack(): Boolean = mutex.withLock {
        lent = false
        val restored = restoreLocked()
        if (!restored) scheduleRetryLocked()
        restored
    }

    /**
     * Gives back a name a previous run (or a failed give-back) left behind.
     * Safe to call from anywhere and any number of times; a no-op while
     * sharing in this process uses the name.
     */
    fun restoreIfStale(): Job = scope.launch {
        mutex.withLock {
            if (lent) return@withLock
            if (!restoreLocked()) scheduleRetryLocked()
        }
    }

    /**
     * Watchdog for the rule "renamed only while sharing runs on a connected
     * board": unless [sharing] still holds once it is this call's turn, the
     * name goes back, even if an interrupted stop never returned it.
     */
    fun returnUnless(sharing: () -> Boolean): Job = scope.launch {
        mutex.withLock {
            if (sharing()) return@withLock
            lent = false
            if (!restoreLocked()) scheduleRetryLocked()
        }
    }

    /**
     * Gives the phone a name the user typed or confirmed in the sharing
     * settings, for when CruxCoach does not know its earlier one. Never while
     * sharing uses the name, never an unchecked name ([BluetoothNameInput]).
     */
    @SuppressLint("MissingPermission")
    suspend fun renameConfirmed(name: String): Boolean = mutex.withLock {
        val wanted = name.trim()
        if (lent || BluetoothNameInput.problem(wanted) != null) return@withLock false
        val adapter = adapterProvider() ?: return@withLock false
        if (send(adapter, wanted) != Sent.OK || !awaitName(wanted)) {
            publishCurrent()
            return@withLock false
        }
        // The confirmed name is the phone's own one now; nothing is left to give back.
        if (isDirty() && !clearRecord()) return@withLock false
        retryJob?.cancel()
        retryJob = null
        renameInFlight = false
        wakeups.setArmed(false)
        publishCurrent()
        true
    }

    /** Re-reads the phone's name for the settings card. */
    fun refreshStatus(): Job = scope.launch {
        mutex.withLock { publishCurrent() }
    }

    /**
     * Sends the recorded name right away, without waiting or verifying. For a
     * process that may be gone in a moment (task removed); the orderly
     * [giveBack] follows when the process survives.
     */
    @SuppressLint("MissingPermission")
    fun giveBackNow() {
        val original = recordedOriginal() ?: return
        val adapter = adapterProvider() ?: return
        send(adapter, original)
    }

    @SuppressLint("MissingPermission")
    private suspend fun restoreLocked(): Boolean {
        val adapter = adapterProvider()
        if (!isDirty()) {
            renameInFlight = false
            wakeups.setArmed(false)
            // Nothing of ours is out. Reading the name here would touch Bluetooth on
            // every app start; the settings card reads it when it is shown.
            if (_status.value.issue != RelayNameIssue.ORIGINAL_UNKNOWN) {
                publish(RelayNameIssue.NONE, null, null)
            }
            return true
        }
        val original = recordedOriginal()
        if (original == null) {
            // Nothing trustworthy to give back (blank, or a sharing name from an older
            // version). Forget it; the settings card explains how to rename the phone.
            Log.w(TAG, "Recovery record holds no usable name; dropping it")
            if (!clearRecord()) return false
            renameInFlight = false
            wakeups.setArmed(false)
            publishCurrent()
            return true
        }
        // Also for a record an earlier version or a dead process left: until the
        // name is back, boot and the next Bluetooth connection wake the app.
        wakeups.setArmed(true)
        if (adapter == null) {
            publish(RelayNameIssue.RESTORE_PENDING, original, null)
            return false
        }
        val before = read(adapter)
        if (before is NameRead.Denied) {
            publish(RelayNameIssue.NEEDS_PERMISSION, original, null)
            return false
        }
        val beforeName = (before as? NameRead.Known)?.name
        if (beforeName != null && beforeName != original &&
            !RelayBoardName.isOwnAdapterName(beforeName) && !renameInFlight
        ) {
            // Renamed by the user (or the system) meanwhile: their choice wins.
            Log.i(TAG, "Phone was renamed while shared; keeping that name")
            return finishRestore()
        }
        if (beforeName != original || renameInFlight) {
            // Sent even when the old name still shows while a rename is in flight:
            // the stack applies renames in order, so ours lands last.
            if (!sendOrPublish(adapter, original)) return false
            if (!awaitName(original)) {
                publish(RelayNameIssue.RESTORE_PENDING, original, nameOf())
                return false
            }
            if (renameInFlight) {
                delay(LATE_RENAME_SETTLE_MS)
                if (nameOf() != original) {
                    if (!sendOrPublish(adapter, original)) return false
                    if (!awaitName(original)) {
                        publish(RelayNameIssue.RESTORE_PENDING, original, nameOf())
                        return false
                    }
                }
            }
        }
        return finishRestore()
    }

    private fun finishRestore(): Boolean {
        if (!clearRecord()) {
            publish(RelayNameIssue.RESTORE_PENDING, recordedOriginal(), nameOf())
            return false
        }
        renameInFlight = false
        wakeups.setArmed(false)
        publishCurrent()
        Log.i(TAG, "Phone's own Bluetooth name is back")
        return true
    }

    private fun sendOrPublish(adapter: BluetoothAdapter, name: String): Boolean =
        when (send(adapter, name)) {
            Sent.OK -> true
            Sent.DENIED -> {
                publish(RelayNameIssue.NEEDS_PERMISSION, name, null)
                false
            }
            Sent.REFUSED -> {
                // Typically Bluetooth off: STATE_ON retries.
                publish(RelayNameIssue.RESTORE_PENDING, name, nameOf())
                false
            }
        }

    private fun scheduleRetryLocked() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            for (wait in RETRY_DELAYS_MS) {
                delay(wait)
                val done = mutex.withLock { lent || restoreLocked() }
                if (done) return@launch
            }
            Log.w(TAG, "Bluetooth name still not back; waiting for Bluetooth on or the next start")
        }
    }

    private fun publishCurrent() {
        val original = recordedOriginal()
        val current = adapterProvider()?.let { read(it) }
        val currentName = (current as? NameRead.Known)?.name
        val issue = when {
            lent -> RelayNameIssue.LENT
            isDirty() && current is NameRead.Denied -> RelayNameIssue.NEEDS_PERMISSION
            isDirty() -> RelayNameIssue.RESTORE_PENDING
            currentName != null && RelayBoardName.isOwnAdapterName(currentName) ->
                RelayNameIssue.ORIGINAL_UNKNOWN
            else -> RelayNameIssue.NONE
        }
        publish(issue, original, currentName)
    }

    private fun publish(issue: RelayNameIssue, original: String?, current: String?) {
        _status.value = RelayNameStatus(issue, original, current)
    }

    private fun isDirty(): Boolean = prefs.getBoolean(KEY_NAME_DIRTY, false)

    /** The recorded own name, or null when there is none worth giving back. */
    private fun recordedOriginal(): String? =
        if (isDirty()) usableOriginal(prefs.getString(KEY_ORIGINAL_NAME, null)) else null

    private fun clearRecord(): Boolean =
        prefs.edit().remove(KEY_NAME_DIRTY).remove(KEY_ORIGINAL_NAME).commit()

    @SuppressLint("MissingPermission")
    private fun read(adapter: BluetoothAdapter): NameRead = try {
        adapter.name?.takeIf { it.isNotBlank() }?.let { NameRead.Known(it) } ?: NameRead.Unreadable
    } catch (_: SecurityException) {
        NameRead.Denied
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the Bluetooth name", e)
        NameRead.Unreadable
    }

    private fun nameOf(): String? =
        adapterProvider()?.let { (read(it) as? NameRead.Known)?.name }

    @SuppressLint("MissingPermission")
    private fun send(adapter: BluetoothAdapter, name: String): Sent = try {
        if (adapter.setName(name)) Sent.OK else Sent.REFUSED
    } catch (_: SecurityException) {
        Sent.DENIED
    } catch (e: Exception) {
        Log.w(TAG, "setName failed", e)
        Sent.REFUSED
    }

    private suspend fun awaitName(target: String): Boolean {
        withTimeoutOrNull(PROPAGATE_TIMEOUT_MS) {
            while (nameOf() != target) delay(100)
        }
        return nameOf() == target
    }

    /**
     * Enables [RelayNameRecoveryReceiver] only while a name is on loan or not
     * yet back: a receiver for every Bluetooth connection must not start the
     * app for every pair of headphones.
     */
    private class ComponentWakeups(private val context: Context) : RelayNameWakeups {
        override fun setArmed(armed: Boolean) {
            runCatching {
                val pm = context.packageManager
                val component = ComponentName(context, RelayNameRecoveryReceiver::class.java)
                val enabled = pm.getComponentEnabledSetting(component) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                if (enabled == armed) return
                pm.setComponentEnabledSetting(
                    component,
                    if (armed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { Log.w(TAG, "Could not switch the name recovery receiver", it) }
        }
    }
}
