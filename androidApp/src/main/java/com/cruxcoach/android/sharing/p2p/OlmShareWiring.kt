package com.cruxcoach.android.sharing.p2p

import android.content.Context
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.sharing.AndroidMarmotHost
import com.cruxcoach.android.sharing.Nip01EventSigner
import com.cruxcoach.android.util.ApkShareHelper
import java.net.InetAddress

/**
 * Spike M0 wiring of the direct Olm channel on Android: the Olm engine behind
 * the unchanged [com.cruxcoach.android.sharing.MarmotHost], LAN HTTP with NSD
 * discovery and BLE GATT. Not bound in Hilt: switching the app to it is a
 * one-line DI change once the owner decides (compiled, not run on a device).
 */
object OlmShareWiring {
    fun host(
        context: Context,
        databaseKey: (String) -> ByteArray,
        nostrSigner: NostrSigner,
        foregroundSigner: Nip01EventSigner,
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
        )
    }
}
