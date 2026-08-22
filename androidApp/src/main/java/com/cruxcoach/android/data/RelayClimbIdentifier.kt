package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.relay.CompleteClimb
import com.cruxcoach.domain.relay.RelayClimbMatcher
import com.cruxcoach.domain.relay.RelayLedDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a relayed guest write could be established to be.
 *
 * Identification used to answer with a climb or with `null`, and `null` was
 * doing four jobs at once: an unlisted climb, a board-clear, a climb written
 * for a different wall, and bytes that are not a command at all. The relay
 * treated all four the same way — straight to the board, with no board check,
 * no pacing, no deduplication and a made-up identity — so the two that are
 * genuinely dangerous rode in on the back of the two that are ordinary.
 */
sealed interface RelayWriteIdentity {
    /** A catalogue climb, on the board this phone is connected to. */
    data class Named(val climbUuid: String, val angle: Int) : RelayWriteIdentity

    /**
     * Real bytes for this wall that cannot be given a name.
     *
     * An unlisted or mirrored climb, a hold set too small to identify, or a
     * board-clear. It may be projected — that is what the relay is for — but
     * it can never become a playlist occurrence, because there is nothing to
     * put on the list.
     */
    object Anonymous : RelayWriteIdentity

    /** LEDs the configured board does not have: written for another wall. */
    object ForeignBoard : RelayWriteIdentity

    /** Not a command, or the catalogue cannot answer. Nothing is known. */
    object Undecidable : RelayWriteIdentity
}

/**
 * Puts a climb identity back on a relayed board write (FEAT-044).
 *
 * A CruxCoach sender advertises the climb UUID over BLE, so nearby phones can
 * name what is on the wall. The official Aurora apps advertise nothing — the
 * relay only sees LED positions and colours. This recovers the identity from
 * exactly those: LED positions map back to placements (the inverse of the
 * per-product-size LED map used for sending), and the resulting hold set is
 * matched against the stored frame strings.
 *
 * Candidates are narrowed in SQL by frame-string length plus two placement
 * anchors, and confirmed here by comparing the full hold set — the frame order
 * is a property of the catalogue, not something to depend on. Roles only break
 * ties: the @2 wire format scales colours by a power budget, so a colour is a
 * hint, never a key.
 *
 * A miss is normal (mirrored climb, unlisted climb) — but "miss" was one
 * `null` standing for four different situations, and only two of them are
 * writes this board should ever be shown. [RelayWriteIdentity] separates them:
 * a climb whose LEDs are not even on this board is a write for somebody else's
 * wall, and bytes that decode into nothing are not a command at all. Both used
 * to reach the board exactly as a named climb did.
 */
@Singleton
class RelayClimbIdentifier @Inject constructor(
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
) {

    private val mutex = Mutex()
    private var indexEnsured = false
    private var ledMapKey: Pair<String, Int>? = null
    private var ledToPlacement: Map<Int, Int> = emptyMap()
    /** Small LRU: the official app re-sends the same climb on every angle
     *  change and every re-light, and a repeat must not re-scan the DB. */
    private val resolved = object : LinkedHashMap<Long, RelayWriteIdentity>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RelayWriteIdentity>?) =
            size > 32
    }

    /**
     * Builds the lookup index ahead of the first climb.
     *
     * It takes ~12 s over the full catalogue, which is fine in the background
     * while sharing starts and not fine in front of the first write.
     */
    suspend fun warmUp() = mutex.withLock {
        if (indexEnsured) return@withLock
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val created = boardRepository.ensureRelayLookupIndex()
            indexEnsured = true
            Log.d(TAG, "lookup index ready=$created in ${System.currentTimeMillis() - started}ms")
        }
    }

    suspend fun identify(climb: CompleteClimb): RelayWriteIdentity = mutex.withLock {
        resolved[climb.framesHash]?.let { hit ->
            Log.d(TAG, "cache hit: $hit")
            return@withLock hit
        }
        val result = try {
            withContext(Dispatchers.IO) { resolve(climb) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The catalogue could not answer. That is not "no catalogue climb
            // matches" — it is "nothing is known", and a write nothing is
            // known about does not get to change a wall.
            Log.w(TAG, "relay climb identification failed", e)
            RelayWriteIdentity.Undecidable
        }
        // Deterministic answers are cached; "nothing is known" is not. It is
        // reached both by bytes that will never decode — cheap to re-derive —
        // and by a catalogue that could not answer this once, and caching the
        // second kind would turn one transient failure into a climb this relay
        // refuses for the rest of the cache's life. That mattered less when a
        // miss meant "forward it anyway"; it decides the write now.
        if (result != RelayWriteIdentity.Undecidable) resolved[climb.framesHash] = result
        result
    }

    private suspend fun resolve(climb: CompleteClimb): RelayWriteIdentity {
        val decoded = RelayLedDecoder.decode(climb.rawBytes) ?: run {
            Log.d(TAG, "no decodable packet in a ${climb.rawBytes.size}-byte write")
            return RelayWriteIdentity.Undecidable
        }
        if (decoded.leds.isEmpty()) {
            // A board-clear is a real command with nothing to name.
            Log.d(TAG, "board-clear write — nothing to identify")
            return RelayWriteIdentity.Anonymous
        }

        val brand = userPreferences.boardBrand.first()
        val productSizeId = userPreferences.boardProductSizeId.first()
        val layoutId = userPreferences.boardLayoutId.first()
        val angle = userPreferences.boardAngle.first()

        if (ledMapKey != brand to productSizeId) {
            // placement→LED is a bijection per product size, so it inverts.
            ledToPlacement = boardRepository.getPlacementLedMap(productSizeId, brand)
                .entries.associate { (placement, led) -> led to placement }
            ledMapKey = brand to productSizeId
        }
        if (ledToPlacement.isEmpty()) {
            // Without the map nothing about these LEDs can be checked, not
            // even whether they belong to this board.
            Log.w(TAG, "no LED map for size=$productSizeId brand=$brand")
            return RelayWriteIdentity.Undecidable
        }

        val placements = decoded.leds.map { ledToPlacement[it.position] }
        if (placements.any { it == null }) {
            // LEDs this board does not have. Whatever it is, it was written
            // for a different wall, and forwarding it here would light holds
            // that mean something else.
            Log.d(
                TAG,
                "${placements.count { it == null }}/${placements.size} LEDs outside the " +
                    "configured board (size=$productSizeId) — not this board's climb"
            )
            return RelayWriteIdentity.ForeignBoard
        }
        val placementSet = placements.filterNotNull().toSet()
        if (placementSet.size < MIN_HOLDS) {
            // Every LED is on this board; there are just too few of them to
            // name a climb. Nameless, not foreign.
            Log.d(TAG, "only ${placementSet.size} holds — too little to identify")
            return RelayWriteIdentity.Anonymous
        }

        // Frame entries read "p<placement>r<role>", so the string length is
        // fixed by the placements plus one unknown: how many digits the role
        // ids of this board use. Both bounds are cheap to state exactly.
        val roleDigits = roleDigitRange(brand)
        val lengths = RelayClimbMatcher.frameLengthRange(
            placements = placementSet,
            minRoleDigits = roleDigits.first,
            maxRoleDigits = roleDigits.second,
        )

        if (!indexEnsured) {
            val created = boardRepository.ensureRelayLookupIndex()
            indexEnsured = true
            Log.d(TAG, "relay lookup index ensured=$created")
        }

        val sorted = placementSet.sorted()
        val started = System.currentTimeMillis()
        val candidates = boardRepository.findClimbCandidatesByFrames(
            boardBrand = brand,
            layoutId = layoutId,
            minLength = lengths.first,
            maxLength = lengths.last,
            anchor1 = RelayClimbMatcher.anchorPattern(sorted.first()),
            anchor2 = sorted.last().takeIf { it != sorted.first() }
                ?.let { RelayClimbMatcher.anchorPattern(it) },
        )
        val matches = candidates.filter { RelayClimbMatcher.holdsMatch(it.frames, placementSet) }
        val elapsed = System.currentTimeMillis() - started
        Log.d(
            TAG,
            "holds=${placementSet.size} len=${lengths.first}..${lengths.last} " +
                "candidates=${candidates.size} matches=${matches.size} in ${elapsed}ms"
        )

        val hit = when {
            matches.isEmpty() -> return RelayWriteIdentity.Anonymous
            matches.size == 1 -> matches.first()
            // Same holds, different roles (a foot-only variant of the same
            // shape) — the colours decide, and only then. Beyond that the
            // catalogue simply carries the identical climb under several
            // names, and the query already put the most-climbed one first.
            else -> disambiguateByRoles(matches, decoded, brand) ?: matches.first().also {
                Log.d(TAG, "${matches.size} climbs share these holds — taking the most climbed")
            }
        }
        return RelayWriteIdentity.Named(climbUuid = hit.uuid, angle = angle)
    }

    private fun disambiguateByRoles(
        matches: List<com.cruxcoach.data.repository.RelayClimbCandidate>,
        decoded: com.cruxcoach.domain.relay.DecodedRelayFrame,
        brand: String,
    ): com.cruxcoach.data.repository.RelayClimbCandidate? {
        // @2 rescales colours to fit a power budget, so its bytes do not map
        // back onto catalogue role colours at all.
        if (decoded.apiLevel != 3) return null
        val colorToRoles = boardRepository.getRoleColorMapForBrand(brand)
            .entries.groupBy({ it.value }, { it.key })
        if (colorToRoles.isEmpty()) return null
        val wanted = decoded.leds.mapNotNull { led ->
            val placement = ledToPlacement[led.position] ?: return@mapNotNull null
            val roles = colorToRoles[led.colorByte] ?: return@mapNotNull null
            placement to roles.toSet()
        }.toMap()
        if (wanted.isEmpty()) return null
        return matches.firstOrNull { candidate ->
            BoardClimbParser.parseFrames(candidate.frames).all { hold ->
                wanted[hold.placementId]?.contains(hold.roleId) ?: true
            }
        }
    }

    /** Digit count of the board's role ids — 12-15 on Kilter, 1-4 on the
     *  Aurora family. Falls back to "either" when placement_roles is absent. */
    private fun roleDigitRange(brand: String): Pair<Int, Int> {
        val roles = boardRepository.getRoleColorMapForBrand(brand).keys
        if (roles.isEmpty()) return 1 to 2
        return roles.minOf { digitCount(it) } to roles.maxOf { digitCount(it) }
    }

    private fun digitCount(value: Int): Int {
        var v = value
        var digits = 1
        while (v >= 10) { v /= 10; digits++ }
        return digits
    }

    private companion object {
        const val TAG = "CruxRelay/Identify"
        /** Below this, a hold set is not distinctive enough to name a climb. */
        const val MIN_HOLDS = 3
    }
}
