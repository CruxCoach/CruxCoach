package com.cruxcoach.app.ui

import com.cruxcoach.data.repository.ClimbWithStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A climb's setter line: [pubkey] is empty unless the name opens the setter's profile. */
class ClimbSetterUi(val uuid: String, val display: String, val pubkey: String)

/** Android's setter line rules (ClimbCard, the climb detail and the playlist player). */
internal object SetterLink {
    /**
     * The setter name, else a short `npub:` stub for own and community rows whose profile is not
     * cached yet, so they always show someone (Android ClimbCard / `seedSetterProfile`).
     */
    fun display(climb: ClimbWithStats): String =
        climb.setterUsername?.takeIf { it.isNotBlank() }
            ?: if (climb.origin == "cruxcoach" || climb.source == "local") {
                climb.createdByPubkey?.takeIf { it.isNotBlank() }?.let { "npub:" + it.take(16) } ?: ""
            } else ""

    /** Only a CruxCoach climb with a known signer opens a profile; a catalogue setter is plain text. */
    fun pubkey(climb: ClimbWithStats): String =
        if (climb.origin == "cruxcoach") climb.createdByPubkey?.takeIf { it.isNotBlank() } ?: "" else ""

    fun of(climb: ClimbWithStats) = ClimbSetterUi(climb.uuid, display(climb), pubkey(climb))
}

/**
 * The playlist player's setter line (Android `PlaylistPlayerScreen`): the player's queue holds only
 * climb ids, so the setter is looked up per shown climb.
 */
class ClimbSetterLookup internal constructor(
    private val load: (uuid: String) -> ClimbWithStats?,
    main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    /** [onResult] runs on the main dispatcher; an unknown or unreadable climb has an empty setter. */
    fun lookup(uuid: String, onResult: (ClimbSetterUi) -> Unit) {
        scope.launch {
            val climb = try {
                withContext(io) { load(uuid) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            onResult(climb?.let { SetterLink.of(it) } ?: ClimbSetterUi(uuid, "", ""))
        }
    }

    fun close() = scope.cancel()
}
