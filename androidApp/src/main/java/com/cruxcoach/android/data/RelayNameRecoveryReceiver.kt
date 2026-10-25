package com.cruxcoach.android.data

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicBoolean

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RelayNameEntryPoint {
    fun relayAdapterNameGuard(): RelayAdapterNameGuard
}

/**
 * Wakes CruxCoach to give the phone its Bluetooth name back when the app ended
 * while sharing (killed, crashed, phone restarted). Starting the process
 * already restores the name (CruxCoachApp); this receiver only makes sure a
 * process starts, and waits for the result.
 *
 * Disabled in the manifest and switched on by [RelayAdapterNameGuard] only
 * while a name is lent or not back: it listens to every Bluetooth connection.
 * When a killed app's board link drops, Android reports the disconnection at
 * once; the next connection (typically the car) is when the name matters.
 */
open class RelayNameRecoveryReceiver : BroadcastReceiver() {
    companion object {
        /** Well inside the time Android gives a receiver. */
        private const val MAX_WAIT_MS = 8_000L

        internal val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val guard = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                RelayNameEntryPoint::class.java,
            ).relayAdapterNameGuard()
        }.getOrNull() ?: return
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
        guard.restoreIfStale().invokeOnCompletion { finish() }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, MAX_WAIT_MS)
    }
}

/**
 * Always enabled: after an app update Android starts nothing by itself, and a
 * name the previous version left behind (also 0.2.4's) should not wait for the
 * user to open the app.
 */
class RelayNameUpdateReceiver : RelayNameRecoveryReceiver()
