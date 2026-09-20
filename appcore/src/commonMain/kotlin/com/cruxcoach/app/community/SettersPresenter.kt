package com.cruxcoach.app.community

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import com.cruxcoach.app.profile.NostrProfileData
import com.cruxcoach.app.profile.ProfileLookup
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.SetterClimbEntry
import com.cruxcoach.data.repository.SetterStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettersListState(
    val isLoading: Boolean = true,
    val setters: List<SetterStat> = emptyList(),
    /** True after a failed read — "no setters yet" and "the read threw" are
     *  different answers and the screen shows a retry for the second. */
    val failed: Boolean = false,
)

/**
 * Every community setter the local catalogue knows about, most problems
 * first. Port of Android's `SettersListViewModel`; the counts are scoped to
 * the active board so they match what the setter's own page will show.
 */
class SettersListPresenter(
    private val boardRepository: BoardRepository,
    private val preferences: BrowsePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(SettersListState())
    val state: StateFlow<SettersListState> = _state.asStateFlow()

    init {
        load()
    }

    fun watch(onState: (SettersListState) -> Unit): StateWatch = scope.watchState(state, onState)

    /** Also the retry: the active board may have changed meanwhile. */
    fun refresh() = load()

    private fun load() {
        scope.launch {
            _state.update { it.copy(isLoading = true, failed = false) }
            try {
                val rows = withContext(ioDispatcher) {
                    val board = preferences.boardSelection()
                    boardRepository.getCommunitySetterStats(
                        board.boardBrand, board.layoutId, board.productSizeId,
                    )
                }
                _state.update { SettersListState(isLoading = false, setters = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { SettersListState(isLoading = false, failed = true) }
            }
        }
    }

    fun close() {
        scope.cancel()
    }
}

data class SetterDetailState(
    val pubkey: String = "",
    /** Resolved display name, or an `npub:` stub until the profile arrives. */
    val displayName: String = "",
    val about: String = "",
    val pictureUrl: String = "",
    val lightningAddress: String = "",
    val climbs: List<SetterClimbEntry> = emptyList(),
    val isLoading: Boolean = true,
    val failed: Boolean = false,
)

/**
 * One setter: their profile header and every problem of theirs this device
 * has, scoped to the active board like Android's `SetterDetailViewModel`.
 *
 * The climbs and the profile load independently — a relay that never answers
 * must not hide problems that are already in the local catalogue.
 */
class SetterDetailPresenter(
    private val boardRepository: BoardRepository,
    private val preferences: BrowsePreferences,
    private val profiles: ProfileLookup,
    private val pubkey: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(
        SetterDetailState(pubkey = pubkey, displayName = stub(pubkey)),
    )
    val state: StateFlow<SetterDetailState> = _state.asStateFlow()

    init {
        if (pubkey.isNotBlank()) {
            loadClimbs()
            loadProfile()
        } else {
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun watch(onState: (SetterDetailState) -> Unit): StateWatch = scope.watchState(state, onState)

    fun refresh() {
        if (pubkey.isBlank()) return
        loadClimbs()
        loadProfile(force = true)
    }

    private fun loadClimbs() {
        scope.launch {
            _state.update { it.copy(isLoading = true, failed = false) }
            try {
                val rows = withContext(ioDispatcher) {
                    val board = preferences.boardSelection()
                    boardRepository.getClimbsByPubkeyForBoard(
                        pubkey, board.angle, board.boardBrand, board.layoutId, board.productSizeId,
                    )
                }
                _state.update { it.copy(climbs = rows, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(climbs = emptyList(), isLoading = false, failed = true) }
            }
        }
    }

    private fun loadProfile(force: Boolean = false) {
        scope.launch {
            // Paint whatever is cached first; the relay round-trip follows.
            val cached = withContext(ioDispatcher) { profiles.cached(pubkey) }
            if (cached != null) _state.update { it.apply(cached) }
            val resolved = try {
                profiles.resolve(pubkey, force)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            _state.update { it.apply(resolved) }
        }
    }

    private fun SetterDetailState.apply(profile: NostrProfileData) = copy(
        displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: stub(pubkey),
        about = profile.about ?: "",
        pictureUrl = profile.pictureUrl ?: "",
        lightningAddress = profile.lightningAddress ?: "",
    )

    fun close() {
        scope.cancel()
    }
}

/** What a setter is called before their profile has been resolved. */
private fun stub(pubkey: String): String =
    if (pubkey.isBlank()) "" else "npub:${pubkey.take(16)}"
