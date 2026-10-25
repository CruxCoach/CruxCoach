package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardCleaningStorage
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.LoggedHolds
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.ui.settings.BoardSendIdentity
import com.cruxcoach.android.ui.settings.resolveBoardConfigurationMismatch
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.data.repository.getClimbByUuidAnySpelling
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.ShinyWallVariant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The holds of the climbs logged on [day] — sends and attempts — that a send
 * to [board] would light, computed like that send: the climb's frames from the
 * catalogue (all frames of a route), mirrored when logged mirrored, refused
 * on a brand or layout mismatch, mapped through the active size's LED map
 * (none mapped = another size) or, on a MoonBoard, through its own encoder.
 * Nothing is stored; the logbook stays the only record.
 */
class BoardCleaningLog(
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
    private val io: CoroutineDispatcher,
) {
    @Inject constructor(
        boardRepository: BoardRepository,
        personalBoardRepo: PersonalBoardRepository,
        userPreferences: UserPreferences,
    ) : this(boardRepository, personalBoardRepo, userPreferences, Dispatchers.IO)

    /** A catalogue climb's frames, layout and brand; null frames = not in the catalogue. */
    private data class CatalogueClimb(val frames: String?, val layoutId: Long?, val brand: BoardBrand?)

    // The sheet re-reads every 30 s; a climb missing from the catalogue costs a
    // full-table scan per lookup, so each climb is resolved once per screen.
    private val catalogue = ConcurrentHashMap<String, CatalogueClimb>()

    private fun catalogueClimb(uuid: String, angle: Int): CatalogueClimb = catalogue.getOrPut(uuid) {
        boardRepository.getClimbByUuidAnySpelling(uuid, angle)
            ?.let { CatalogueClimb(it.frames, it.layoutId, it.brand) }
            ?: CatalogueClimb(null, null, null)
    }

    suspend fun load(board: DiscoveredBoard, day: String): List<LoggedHolds> = withContext(io) {
        read(board, day)
    }

    private suspend fun read(board: DiscoveredBoard, day: String): List<LoggedHolds> {
        if (BoardCleaningStorage.key(board) == null) return emptyList()
        val brand = board.boardBrand
        val entries = personalBoardRepo.getUserLogbookForDay(day)
            .filter { BoardBrand.fromWire(it.boardBrand) == brand }
        if (entries.isEmpty()) return emptyList()
        val activeBrand = userPreferences.boardBrand.first().let(BoardBrand::fromWire)
        val activeLayoutId = userPreferences.boardLayoutId.first().toLong()
        val activeSizeId = userPreferences.boardProductSizeId.first()
        val encoder = BoardPacketEncoder(board.apiLevel, BoardPacketEncoder.ledsPerHoldFor(brand))
        val ledMap by lazy { boardRepository.getPlacementLedMap(activeSizeId, brand.wireValue) }
        val mirrorMap by lazy {
            MirrorMapDeriver.forBoard(
                boardRepository.getAllPlacements(brand.wireValue),
                boardRepository.getProductSize(activeSizeId, brand.wireValue),
            )
        }
        return entries.mapNotNull { entry ->
            val loggedAt = loggedAt(entry.climbedAt) ?: return@mapNotNull null
            val climb = catalogueClimb(entry.climbUuid, entry.angle.toInt())
            // A logbook-only climb (missing from the catalogue) keeps the frames
            // its ascent row stored; an attempt stores none.
            val frames = climb.frames ?: entry.climbFrames
            val layoutId = if (climb.frames != null) climb.layoutId else entry.layoutId
            val climbBrand = climb.brand ?: BoardBrand.fromWire(entry.boardBrand)
            if (frames.isBlank()) return@mapNotNull null
            val identity = BoardSendIdentity(
                climbBrand = climbBrand,
                climbLayoutId = layoutId,
                activeBrand = activeBrand,
                activeLayoutId = activeLayoutId,
                activeProductSizeId = activeSizeId,
                connectedBrand = brand,
            )
            if (resolveBoardConfigurationMismatch(identity) != null) return@mapNotNull null
            val positions = if (brand == BoardBrand.MOONBOARD) {
                val variant = layoutId?.let(MoonBoardVariant::fromLayoutId) ?: MoonBoardVariant.MOONBOARD_2016
                MoonBoardFrameEncoder.cleaningPositions(frames, variant)
            } else if (brand == BoardBrand.SHINYWALL) {
                // Placement ids carry the variant, which the cleaning encoder
                // needs for the full state vector.
                val variant = layoutId?.let(ShinyWallVariant::fromLayoutId) ?: return@mapNotNull null
                BoardClimbParser.parseMultiFrames(frames).flatten()
                    .map { it.placementId }
                    .filterTo(mutableSetOf()) { variant.indexOf(it) != null }
            } else {
                val holds = BoardClimbParser.parseMultiFrames(frames).flatten().let { holds ->
                    if (entry.isMirror && layoutId != null &&
                        BoardConstants.isLayoutMirrorable(climbBrand, layoutId.toInt())
                    ) MirrorMapDeriver.mirror(holds, mirrorMap) else holds
                }
                // The send refuses a climb none of whose holds the size maps.
                if (holds.none { it.placementId in ledMap }) return@mapNotNull null
                encoder.ledPositions(holds, ledMap)
            }
            positions.takeIf { it.isNotEmpty() }?.let { LoggedHolds(loggedAt, it) }
        }.sortedBy { it.loggedAt }
    }

    companion object {
        /** `climbed_at` as the logbook writes it (local `LocalDateTime`), with
         * a space instead of the `T` as imports write it, or a bare date as an
         * import of a whole day writes it (counted from midnight). */
        fun loggedAt(climbedAt: String): LocalDateTime? {
            val text = climbedAt.trim().let {
                if (it.length > 10 && it[10] == ' ') it.replaceRange(10, 11, "T") else it
            }
            return runCatching { LocalDateTime.parse(text) }.getOrNull()
                ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay() }.getOrNull()
        }
    }
}
