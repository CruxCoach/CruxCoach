package com.cruxcoach.android.ble

import android.os.Build

/** How the connection sheet gets from "user wants a board" to a GATT link. */
internal enum class BoardConnectFlow {
    /**
     * Scan first, always. Every board in range is listed, and an unambiguous
     * choice connects itself.
     */
    DISCOVER,

    /**
     * Try the remembered controller directly, and only fall back to discovery
     * if it does not answer.
     */
    DIRECT_THEN_DISCOVER,
}

/**
 * Decides how connecting starts, and it hinges on one thing: what scanning
 * costs the user.
 *
 * From Android 12 a BLE scan needs `BLUETOOTH_SCAN`, which we declare
 * `neverForLocation` — no location permission, no location toggle, nothing to
 * explain. There is no reason to withhold discovery, so the sheet always scans
 * and shows everything in range.
 *
 * Below Android 12 the platform routes scanning through location access, and a
 * climber who only wants to reconnect to the wall they used yesterday should
 * not have to hand over location for it. There the remembered controller is
 * tried directly first — a direct GATT connect by address needs no scan and no
 * location — and the location prompt appears only once that has failed.
 */
internal object BoardConnectFlowPolicy {

    /** True where a discovery scan drags the location permission in with it. */
    fun scanRequiresLocationAccess(apiLevel: Int = Build.VERSION.SDK_INT): Boolean =
        apiLevel < Build.VERSION_CODES.S

    fun initialFlow(
        hasRememberedController: Boolean,
        apiLevel: Int = Build.VERSION.SDK_INT,
    ): BoardConnectFlow = when {
        !scanRequiresLocationAccess(apiLevel) -> BoardConnectFlow.DISCOVER
        hasRememberedController -> BoardConnectFlow.DIRECT_THEN_DISCOVER
        else -> BoardConnectFlow.DISCOVER
    }

    /**
     * The board to connect to without asking, or null to show the list.
     *
     * One board in range is not a choice, so it connects — that is the 0.2.1
     * behaviour people are used to. With several in range the remembered
     * controller still wins, but only if exactly one of them is it: in a gym
     * with two identical walls, "the one you used last" has to be a single
     * board or it is not an answer.
     */
    fun <T> autoConnectTarget(
        candidates: List<T>,
        rememberedAddress: String?,
        addressOf: (T) -> String,
    ): T? {
        if (candidates.size == 1) return candidates.first()
        if (rememberedAddress == null) return null
        val remembered = candidates.filter { addressOf(it).equals(rememberedAddress, ignoreCase = true) }
        return remembered.singleOrNull()
    }
}
