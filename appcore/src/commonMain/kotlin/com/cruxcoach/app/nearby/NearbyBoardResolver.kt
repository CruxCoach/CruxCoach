// Port of the receive half of androidApp/.../data/BleShareManager.kt (0e1bad0c6):
// `resolveOnBoardClimb`, the suppression in `buildUiState`, the occupied count and
// `canRequestDisconnect`. It decides which one climb the board screens' nearby area names.
//
// This phone's own sends (LOCAL_ACTIVE while the link they went over is up, LOCAL_MANAGER after
// it) come from [LocalBoardClimbMemory]: an iPhone cannot advertise, so "active" means the climb
// this phone lit over the link that is still up, not an advertiser's state. Android's stale
// ClimbData check and session-overwrite detection clear that live state as on Android.
// What an iPhone does not have, and therefore never enters the resolution:
// - Android's bridge of other phones' climbs into `BoardStateManager`: a remote phone's last
//   climb is shown while the scan still holds it (REMOTE_LAST).
// - Hosting: the only own session is a joined Android host (participant).
// - `localPresenceToken`: the iPhone never advertises BoardConnected, so no loopback to drop.
// Android's "sharing enabled" switch gates only its own advertising and requests; reading
// nearby phones needs nothing but the scan, so that switch is not consulted here.
package com.cruxcoach.app.nearby

internal enum class OnBoardSource { REMOTE_ACTIVE, REMOTE_LAST, SESSION_REMOTE, LOCAL_ACTIVE, LOCAL_MANAGER }

/** This phone's last send (Android `BoardStateManager.lastClimb` + `ClimbBleAdvertiser.hasActiveClimb`). */
internal data class LocalBoard(
    val climbUuid: String,
    val angle: Int,
    val projectionSurvivesDisconnect: Boolean,
    /** Sent over the link that is still up. */
    val live: Boolean,
    /** Identifies one send: an overwrite clears exactly this one. */
    val sentAtMs: Long,
)

internal data class OnBoardClimb(
    val climbUuid: String,
    val angle: Int,
    val source: OnBoardSource,
    /** False when only resend metadata remains and the LEDs have gone out. */
    val isStillProjected: Boolean = true,
)

/** The shared session this phone takes part in, as far as the board state cares (Android `SessionQueueState`). */
internal data class OwnSession(
    /** Android role PARTICIPANT (joined, or waiting after the host left). */
    val member: Boolean,
    /** Android `isConnecting`: the GATT join runs and the role is not set yet. */
    val connecting: Boolean,
    /** The host's advertised session id; 0 when unknown. */
    val sessionId: Int,
    val currentClimbUuid: String?,
    val currentClimbAngle: Int,
)

internal data class NearbyBoard(
    /** The climb the area names, after the suppression; null when none or redundant. */
    val onBoard: OnBoardClimb?,
    /** What the wall shows while the joined queue expects another climb (Android `BoardMismatch`). */
    val boardShowsInstead: OnBoardClimb?,
    /** Phones connected to the board without an active climb. */
    val boardOccupiedCount: Int,
    /** Someone on the board would hand it over on request (Android `canRequestDisconnect` without its switch). */
    val disconnectRequestable: Boolean,
)

/**
 * Holds Android's overwrite memory ([overwriteSessionUuid]) between calls, so one instance must
 * see every update in order. Not thread-safe; the owner calls it from one thread.
 */
internal class NearbyBoardResolver {

    /**
     * The session's climb at the time someone else wrote the board (REMOTE_ACTIVE). While the
     * session has not advanced past it, the other phone's last climb is still what the wall shows.
     */
    private var overwriteSessionUuid: String? = null

    /** Android `lastSeenRemoteActiveUuid`: ClimbData that has not changed since our send is stale. */
    private var lastSeenRemoteActiveUuid: String? = null

    /** Android `lastSeenSessionClimbUuid`: a nearby session advancing overwrote our climb. */
    private var lastSeenSessionClimbUuid: String? = null

    /** The send whose live state another phone or a session overwrote (Android `clearActiveClimb`). */
    private var clearedSendAtMs: Long? = null

    fun resolve(
        climbs: List<NearbyClimb>,
        sessions: List<NearbySession>,
        own: OwnSession?,
        local: LocalBoard? = null,
    ): NearbyBoard {
        val member = own?.member == true
        val connecting = own?.connecting == true
        val raw = resolveOnBoard(climbs, sessions, own, local)
        val sessionClimbUuid = own?.currentClimbUuid
        // SESSION_REMOTE from the own queue repeats the session row; a remote writer never does.
        val nonSuppressible = raw != null && (
            raw.source == OnBoardSource.REMOTE_ACTIVE ||
                (raw.source == OnBoardSource.SESSION_REMOTE && !member) ||
                (raw.source == OnBoardSource.REMOTE_LAST && overwriteSessionUuid != null)
            )
        val suppressed = raw != null && sessionClimbUuid != null && member && !nonSuppressible && !connecting
        return NearbyBoard(
            onBoard = if (suppressed) null else raw,
            boardShowsInstead = raw?.takeIf { suppressed && !sameClimb(it.climbUuid, sessionClimbUuid) },
            boardOccupiedCount = climbs.count { it.connectedOnly },
            disconnectRequestable = climbs.any { !it.isLastClimb && it.acceptsDisconnectRequests },
        )
    }

    /** Android `climbAdvertiser.hasActiveClimb()` for this phone's last send. */
    private fun hasActive(local: LocalBoard?): Boolean =
        local != null && local.live && local.sentAtMs != clearedSendAtMs

    private fun resolveOnBoard(
        climbs: List<NearbyClimb>,
        sessions: List<NearbySession>,
        own: OwnSession?,
        local: LocalBoard?,
    ): OnBoardClimb? {
        val member = own?.member == true
        val connecting = own?.connecting == true
        val memberSessionUuid = own?.currentClimbUuid
        val nearbySessionUuid = sessions.firstOrNull { it.currentClimbUuid != null }?.currentClimbUuid
        val anySessionClimbUuid = memberSessionUuid ?: nearbySessionUuid

        // Android (role NONE, joining included): a nearby session advancing (A→B, not its first
        // sighting) overwrote our LEDs.
        if (!member) {
            if (nearbySessionUuid != null) {
                val sessionOverwrites = lastSeenSessionClimbUuid != null && nearbySessionUuid != lastSeenSessionClimbUuid
                if (sessionOverwrites && hasActive(local)) clearedSendAtMs = local?.sentAtMs
                lastSeenSessionClimbUuid = nearbySessionUuid
            } else {
                lastSeenSessionClimbUuid = null
            }
        }

        // 1. Someone is connected and climbing right now. ClimbData unchanged since our send is
        //    stale (the board takes several links); a new uuid is a new send over ours (the bridge).
        val remoteActive = climbs.firstOrNull { !it.connectedOnly && it.climbUuid.isNotEmpty() && !it.isLastClimb }
        if (remoteActive != null) {
            val isNewRemoteSend = remoteActive.climbUuid != lastSeenRemoteActiveUuid
            lastSeenRemoteActiveUuid = remoteActive.climbUuid
            if (isNewRemoteSend && hasActive(local)) clearedSendAtMs = local?.sentAtMs
        }
        if (remoteActive != null && !hasActive(local)) {
            overwriteSessionUuid = anySessionClimbUuid
            return OnBoardClimb(remoteActive.climbUuid, remoteActive.angle, OnBoardSource.REMOTE_ACTIVE)
        }

        if (!member && !connecting) overwriteSessionUuid = null

        // 2. The other phone left, but the session has not sent a new climb since it wrote the wall.
        val remoteLast = climbs.firstOrNull { !it.connectedOnly && it.climbUuid.isNotEmpty() && it.isLastClimb }
        if (remoteLast != null && remoteLast.projectionSurvivesDisconnect && !hasActive(local) && overwriteSessionUuid != null) {
            if (anySessionClimbUuid == overwriteSessionUuid) {
                return OnBoardClimb(remoteLast.climbUuid, remoteLast.angle, OnBoardSource.REMOTE_LAST, isStillProjected = true)
            }
            overwriteSessionUuid = null
        }
        // Not cleared when both remote signals are briefly absent (the ClimbData → LastClimb gap).

        // 3. The joined queue's current climb.
        if (member && own != null && own.currentClimbUuid != null) {
            return OnBoardClimb(own.currentClimbUuid, own.currentClimbAngle, OnBoardSource.SESSION_REMOTE)
        }

        // 4. This phone's climb, lit over the link that is still up: what it sent is on the wall.
        if (local != null && hasActive(local)) {
            return OnBoardClimb(local.climbUuid, local.angle, OnBoardSource.LOCAL_ACTIVE)
        }

        // 5. A session advertisement's climb; once in a session only that session may speak here.
        val ownSessionId = own?.sessionId?.takeIf { it != 0 }
        val sessionClimb = sessions.firstOrNull {
            it.currentClimbUuid != null && (!member || ownSessionId == null || it.sessionId == ownSessionId)
        }
        if (sessionClimb != null) {
            return OnBoardClimb(sessionClimb.currentClimbUuid!!, sessionClimb.currentClimbAngle, OnBoardSource.SESSION_REMOTE)
        }

        // 6. A remote phone's last climb without overwrite tracking.
        if (remoteLast != null && !hasActive(local)) {
            return OnBoardClimb(remoteLast.climbUuid, remoteLast.angle, OnBoardSource.REMOTE_LAST,
                isStillProjected = remoteLast.projectionSurvivesDisconnect)
        }

        // 7. What this phone lit last, the link gone (Android LOCAL_MANAGER).
        if (local != null) {
            return OnBoardClimb(local.climbUuid, local.angle, OnBoardSource.LOCAL_MANAGER,
                isStillProjected = local.projectionSurvivesDisconnect)
        }
        return null
    }

    companion object {
        /** Advertisements carry ids hyphenated or as upper-case hex; the catalogue may spell them either way. */
        fun sameClimb(a: String?, b: String?): Boolean =
            a != null && b != null && normalize(a) == normalize(b)

        fun normalize(uuid: String): String = uuid.replace("-", "").uppercase()
    }
}
