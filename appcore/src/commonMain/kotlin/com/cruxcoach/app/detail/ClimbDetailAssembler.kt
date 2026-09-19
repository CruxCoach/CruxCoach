package com.cruxcoach.app.detail

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.render.AuroraBoardGeometry
import com.cruxcoach.app.render.LedHoldColors
import com.cruxcoach.app.render.MirrorMapDeriver
import com.cruxcoach.app.render.MoonBoardRender
import com.cruxcoach.app.render.boardImageCandidatePaths
import com.cruxcoach.data.repository.AngleOption
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbBetaLink
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.MoonBoardVariant

/** Local setter display rule: setter_username, else `npub:<first 16 hex>`. Community climbs only. */
data class SetterProfile(
    /** Empty only when neither a username nor a pubkey exists; Swift then shows its "unknown" text. */
    val displayName: String,
    val pictureUrl: String?,
    val isCommunity: Boolean,
)

data class BetaLinkItem(
    val link: ClimbBetaLink,
    /** Admitted thumbnail URLs, primary first then mirrors; empty = do not load a thumbnail. */
    val thumbnailUrls: List<String>,
)

/**
 * A hold ready to draw. Aurora-family: [x]/[y] are normalized over the board view
 * (origin top-left) via the product-size edge box. MoonBoard: [x]/[y] are -1 and the
 * caller positions [placementId] (= MoonBoard holdId) with MoonBoardMappedGeometry /
 * MoonBoardGridGeometry. Quantum: [x]/[y] are -1; use quantumBoardPoint with
 * [boardX]/[boardY] because its mapping depends on the canvas and device class.
 */
data class RenderHold(
    val placementId: Int,
    val roleId: Int,
    val x: Float,
    val y: Float,
    val boardX: Long,
    val boardY: Long,
    val argb: Long,
)

data class ClimbDetailData(
    val climb: ClimbWithStats,
    val angle: Int,
    val frames: List<List<BoardHold>>,
    val placements: Map<Int, BoardPlacement>,
    val boardSize: BoardSize?,
    val boardImages: List<BoardImage>,
    /** Most specific first; empty for MoonBoard (see MoonBoardVariant.layoutJsonAssetPath). */
    val boardImagePaths: List<String>,
    val userAscents: List<AscentWithClimb>,
    val isFavorited: Boolean,
    val isIgnored: Boolean,
    val listIds: Set<Long>,
    val personalNote: String,
    val betaLinks: List<BetaLinkItem>,
    val availableAngles: List<AngleOption>,
    val isMirrorable: Boolean,
    val mirrorMap: Map<Int, Int>,
    val setterProfile: SetterProfile?,
)

sealed class ClimbDetailLoad {
    class Found(val data: ClimbDetailData) : ClimbDetailLoad()
    /** Absent from the board DB but present in the user's logbook. */
    class LogbookOnly(val uuid: String, val ascents: List<AscentWithClimb>) : ClimbDetailLoad()
    object NotFound : ClimbDetailLoad()
}

/** Data side of Android's BoardClimbDetailViewModel.loadClimb. Blocking; run off the main thread. */
class ClimbDetailAssembler(
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val prefs: BrowsePreferences,
) {
    companion object {
        const val NOTE_MAX_CHARS = 1000

        /** Aurora `layouts.is_mirrored`: Tension Board (9) and Tension Board 2 Mirror (10). */
        private val TENSION_MIRRORED_LAYOUTS = setOf(9, 10)

        fun isLayoutMirrorable(brand: BoardBrand, layoutId: Int): Boolean = when (brand) {
            BoardBrand.TENSION -> layoutId in TENSION_MIRRORED_LAYOUTS
            BoardBrand.GRASSHOPPER, BoardBrand.DECOY, BoardBrand.SOILL, BoardBrand.KILTER -> true
            else -> false
        }

        fun seedSetterProfile(climb: ClimbWithStats): SetterProfile? {
            if (climb.origin != "cruxcoach") return null
            val name = climb.setterUsername?.takeIf { it.isNotBlank() }
                ?: climb.createdByPubkey?.let { "npub:${it.take(16)}" }
                ?: ""
            return SetterProfile(displayName = name, pictureUrl = null, isCommunity = true)
        }

        fun mirrorHolds(holds: List<BoardHold>, mirrorMap: Map<Int, Int>): List<BoardHold> =
            holds.map { h -> mirrorMap[h.placementId]?.let { h.copy(placementId = it) } ?: h }

        fun renderHolds(data: ClimbDetailData, holds: List<BoardHold>, colors: LedHoldColors): List<RenderHold> {
            val brand = data.climb.brand
            if (brand == BoardBrand.MOONBOARD) {
                return holds.mapNotNull { h ->
                    MoonBoardRender.roleArgb(h.roleId)?.let { RenderHold(h.placementId, h.roleId, -1f, -1f, 0, 0, it) }
                }
            }
            val geometry = AuroraBoardGeometry.of(data.boardSize)
            return holds.mapNotNull { h ->
                val p = data.placements[h.placementId] ?: return@mapNotNull null
                val argb = colors.argbForRole(h.roleId)
                if (brand == BoardBrand.QUANTUM) RenderHold(h.placementId, h.roleId, -1f, -1f, p.x, p.y, argb)
                else geometry.normalized(p.x, p.y).let { n ->
                    if (n.x !in 0f..1f || n.y !in 0f..1f) null
                    else RenderHold(h.placementId, h.roleId, n.x, n.y, p.x, p.y, argb)
                }
            }
        }
    }

    private val supportedAnglesCache = HashMap<Pair<Int, String>, Set<Int>>()

    fun load(uuid: String, angle: Int): ClimbDetailLoad {
        // Indexed exact/case lookups first; the normalized full scan only on a miss.
        val climb = boardRepository.getClimbByUuid(uuid, angle)
            ?: boardRepository.getClimbByUuid(uuid.lowercase(), angle)
            ?: boardRepository.getClimbByUuid(uuid.uppercase(), angle)
            ?: boardRepository.getClimbByUuidNormalized(uuid, angle)
        if (climb == null) {
            val history = personalBoardRepo.getUserHistoryForClimb(uuid)
            return if (history.isNotEmpty()) ClimbDetailLoad.LogbookOnly(uuid, history) else ClimbDetailLoad.NotFound
        }
        val brandWire = climb.brand.wireValue
        val isMoonBoard = !climb.brand.usesAuroraPlacements
        val frames = BoardClimbParser.parseMultiFrames(climb.frames)
        val placements = if (isMoonBoard) emptyMap()
        else boardRepository.getAllPlacements(brandWire).associateBy { it.placementId.toInt() }
        val sel = prefs.boardSelection()
        val effective = if (isMoonBoard) null
        else pickEffectiveBoard(uuid, climb.layoutId.toInt(), sel.productSizeId, sel.layoutId, brandWire)
        val boardSize = effective?.let { boardRepository.getProductSize(it.first, brandWire) }
        val boardImages = effective?.let { boardRepository.getBoardImages(it.first, it.second, brandWire) }.orEmpty()
        val identities = boardRepository.equivalentClimbUuids(climb.uuid)
        val ascents = identities.flatMap(personalBoardRepo::getUserHistoryForClimb)
            .distinctBy { it.uuid }.sortedByDescending { it.climbedAt }
        val betaLinks = boardRepository.getClimbBetaLinks(brandWire, climb.uuid, angle).map {
            BetaLinkItem(it, it.thumbnail?.let(::betaThumbnailUrls).orEmpty())
        }
        val mirrorMap = if (effective == null || boardSize == null || placements.isEmpty()) emptyMap() else {
            // Geometric derivation: the `holes` mirror table is never populated for any board.
            MirrorMapDeriver.derive(
                placements.values.map { MirrorMapDeriver.Hold(it.placementId.toInt(), it.x.toInt(), it.y.toInt(), it.setId.toInt()) },
                (boardSize.edgeLeft + boardSize.edgeRight).toInt(),
            )
        }
        return ClimbDetailLoad.Found(
            ClimbDetailData(
                climb = climb, angle = angle, frames = frames, placements = placements,
                boardSize = boardSize, boardImages = boardImages,
                boardImagePaths = if (isMoonBoard) emptyList() else boardImageCandidatePaths(
                    boardSize?.boardBrand ?: climb.brand, boardSize?.id ?: 10L, boardImages.firstOrNull()?.layoutId,
                ),
                userAscents = ascents,
                isFavorited = identities.any(personalBoardRepo::isClimbFavorited),
                isIgnored = identities.any(personalBoardRepo::isClimbIgnored),
                listIds = identities.flatMapTo(HashSet()) { personalBoardRepo.getListIdsForClimb(it) },
                personalNote = identities.asSequence().mapNotNull(personalBoardRepo::getClimbNote).firstOrNull().orEmpty(),
                betaLinks = betaLinks,
                // Resolved uuid, not the navigation spelling (Android passes the latter and loses the stats on a case mismatch).
                availableAngles = buildAngleOptions(climb, boardRepository.getAnglesForClimb(climb.uuid)),
                isMirrorable = isLayoutMirrorable(climb.brand, climb.layoutId.toInt()),
                mirrorMap = mirrorMap,
                setterProfile = seedSetterProfile(climb),
            ),
        )
    }

    /** Statted angles merged with every angle the board supports; extra angles carry no grade. */
    fun buildAngleOptions(climb: ClimbWithStats, statted: List<AngleOption>): List<AngleOption> {
        val brand = climb.brand
        val setterAngle = if (climb.origin == "cruxcoach") statted.firstOrNull()?.angle else null
        val supported: Set<Int> = when {
            brand.usesAuroraProtocol -> {
                val key = climb.layoutId.toInt() to climb.boardBrand
                supportedAnglesCache[key] ?: boardRepository
                    .getSupportedAnglesForLayout(climb.layoutId.toInt(), climb.boardBrand).toSet()
                    // An empty result may race a running import; do not retain it.
                    .also { if (it.isNotEmpty()) supportedAnglesCache[key] = it }
            }
            brand == BoardBrand.MOONBOARD -> MoonBoardVariant.fromLayoutId(climb.layoutId)?.angles?.toSet() ?: emptySet()
            else -> emptySet()
        }
        val byAngle = statted.associateBy { it.angle }
        return (byAngle.keys + supported).sorted().map { a ->
            (byAngle[a] ?: AngleOption(a, null, null, null, 0.0)).copy(isSetterAngle = a == setterAngle)
        }
    }

    // User's configured board when it can host the climb, else the smallest containing
    // size, else any size with images for the climb's layout, else the user's pair.
    private fun pickEffectiveBoard(
        climbUuid: String, climbLayoutId: Int, preferredSizeId: Int, preferredLayoutId: Int, boardBrand: String,
    ): Pair<Int, Int> {
        if (boardRepository.canRenderClimbOnSize(climbUuid, preferredSizeId, boardBrand)) return preferredSizeId to climbLayoutId
        boardRepository.getProductSizeForClimbRender(climbUuid, boardBrand)?.let { return it to climbLayoutId }
        val candidates = boardRepository.getProductSizesForLayout(climbLayoutId, boardBrand)
        return when {
            preferredSizeId in candidates -> preferredSizeId to climbLayoutId
            candidates.isNotEmpty() -> candidates.first() to climbLayoutId
            else -> preferredSizeId to preferredLayoutId
        }
    }

    /** Toggles across every exact identity (legacy MoonBoard aliases). Returns the new state. */
    fun toggleFavourite(uuid: String): Boolean {
        val identities = boardRepository.equivalentClimbUuids(uuid)
        val existing = identities.filter(personalBoardRepo::isClimbFavorited)
        return if (existing.isEmpty()) { personalBoardRepo.toggleFavorite(identities.first()); true }
        else { existing.forEach { personalBoardRepo.toggleFavorite(it) }; false }
    }

    fun setIgnored(uuid: String, ignored: Boolean): Boolean {
        val identities = boardRepository.equivalentClimbUuids(uuid)
        val existing = identities.filter(personalBoardRepo::isClimbIgnored)
        if (ignored && existing.isEmpty()) personalBoardRepo.toggleIgnored(identities.first())
        if (!ignored) existing.forEach { personalBoardRepo.toggleIgnored(it) }
        return ignored
    }

    /** Returns the stored (trimmed, max 1000 chars) note. */
    fun saveNote(uuid: String, note: String): String {
        val normalized = note.trim().take(NOTE_MAX_CHARS)
        boardRepository.equivalentClimbUuids(uuid).forEach { personalBoardRepo.saveClimbNote(it, normalized) }
        return normalized
    }
}
