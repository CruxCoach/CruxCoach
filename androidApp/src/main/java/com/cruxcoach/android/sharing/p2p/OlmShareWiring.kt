package com.cruxcoach.android.sharing.p2p

import android.content.Context
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.sharing.AndroidMarmotHost
import com.cruxcoach.android.sharing.Nip01EventSigner
import com.cruxcoach.android.util.ApkShareHelper
import java.net.InetAddress

/**
 * The Olm engine behind the unchanged [com.cruxcoach.android.sharing.MarmotHost]:
 * mailboxes while online, and LAN HTTP with NSD discovery and BLE GATT while
 * the app is in front (owner decision 2026-10-26: Olm, mailboxes, direct when
 * both are reachable; the MLS host stays in the code, not wired).
 */
object OlmShareWiring {
    private fun inFront() = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    fun host(
        context: Context,
        databaseKey: (String) -> ByteArray,
        nostrSigner: NostrSigner,
        foregroundSigner: Nip01EventSigner,
        /** Level "Maximal": no mailboxes at all, only direct exchange. */
        directOnly: () -> Boolean = { false },
    ): AndroidMarmotHost = AndroidMarmotHost(context, databaseKey, nostrSigner, foregroundSigner, engine = "olm") { native ->
        P2pMarmotBinding(
            native,
            listOf(
                LanShareTransport(
                    bindAddress = { ApkShareHelper.getDeviceIpAddress()?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } },
                    discovery = NsdLanDiscovery(context),
                ),
                BleShareTransport(context),
            ),
            mailboxes = com.cruxcoach.android.sharing.mailbox.MailboxDelivery(
                // Its own client: no interceptor, cookie or header of the app's other traffic.
                com.cruxcoach.android.sharing.mailbox.MailboxRelayClient(
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build(),
                ),
                com.cruxcoach.android.sharing.mailbox.MailboxChoice(com.cruxcoach.android.sharing.mailbox.MailboxPools.PREVIEW),
                foreground = ::inFront,
            ),
            direct = ::inFront,
            mailboxesOn = { !directOnly() },
        )
    }
}
