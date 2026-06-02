package com.cruxcoach.data.repository

import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.SupportedBoard

class BoardRepositoryImpl(
    private val database: BoardDatabase
) : BoardRepository {

    private val q = database.boardQueries

    // ── Row Mappers ────────────────────────────────────────────

    private fun mapClimb(
        uuid: String, layoutId: Long, setterUsername: String?,
        name: String, frames: String, framesCount: Long,
        difficultyAverage: Double?, qualityAverage: Double?,
        ascensionistCount: Long?, description: String,
        isNomatch: Long, framesPace: Long, hsm: Long,
        benchmarkDifficulty: Double = 0.0,
        faUsername: String? = null, faAt: String? = null,
        moveCount: Long = 0,
        origin: String = "kilter",
        kilterStatus: String? = null,
        createdByPubkey: String? = null,
        source: String = "kilter",
        syncStatus: String? = null,
        nostrEventId: String? = null,
        boardBrand: String = "kilter",
    ) = ClimbWithStats(
        uuid = uuid, layoutId = layoutId, setterUsername = setterUsername,
        name = name, frames = frames, framesCount = framesCount,
        difficultyAverage = difficultyAverage, qualityAverage = qualityAverage,
        ascensionistCount = ascensionistCount, description = description,
        isNomatch = isNomatch != 0L, framesPace = framesPace, hsm = hsm,
        benchmarkDifficulty = benchmarkDifficulty,
        faUsername = faUsername, faAt = faAt,
        storedMoveCount = moveCount,
        origin = origin,
        kilterStatus = kilterStatus,
        createdByPubkey = createdByPubkey,
        source = source,
        syncStatus = syncStatus,
        nostrEventId = nostrEventId,
        boardBrand = boardBrand,
    )

    // ── Climb Queries ──────────────────────────────────────────

    /** Maps a climb_browse VIEW row to ClimbWithStats (no frames in VIEW). */
    private fun mapBrowse(it: com.cruxcoach.db.board.Climb_browse) = mapClimb(
        it.uuid, it.layout_id, it.setter_username, it.name, "", it.frames_count,
        it.difficulty_average, it.quality_average, it.ascensionist_count,
        it.description, it.is_nomatch, it.frames_pace, it.hsm,
        benchmarkDifficulty = it.benchmark_difficulty ?: 0.0,
        moveCount = it.move_count,
        origin = it.origin,
        kilterStatus = it.kilter_status,
        createdByPubkey = it.created_by_pubkey,
        source = it.source,
        syncStatus = it.sync_status,
        boardBrand = it.board_brand,
    )

    override fun searchClimbsByName(query: String, angle: Int, layoutId: Int, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int, offset: Int, climbType: ClimbTypeFilter, selProductSizeId: Int): List<ClimbWithStats> {
        val lay = layoutId.toLong()
        val a = angle.toLong()
        val mn = climbType.minFrames()
        val mx = climbType.maxFrames()
        val l = limit.toLong()
        val o = offset.toLong()
        val sel = selProductSizeId.toLong()
        val desc = sortDirection == SortDirection.DESC

        return when (sortField) {
            ClimbSortField.QUALITY -> if (desc) q.searchByQualityDesc(lay, query, a, mn, mx, sel, l, o) else q.searchByQualityAsc(lay, query, a, mn, mx, sel, l, o)
            ClimbSortField.DIFFICULTY -> if (desc) q.searchByDifficultyDesc(lay, query, a, mn, mx, sel, l, o) else q.searchByDifficultyAsc(lay, query, a, mn, mx, sel, l, o)
            ClimbSortField.NAME -> if (desc) q.searchByNameDesc(lay, query, a, mn, mx, sel, l, o) else q.searchByNameAsc(lay, query, a, mn, mx, sel, l, o)
            ClimbSortField.QUALITY_SENDS -> if (desc) q.searchByQualitySendsDesc(lay, query, a, mn, mx, sel, l, o) else q.searchByQualitySendsAsc(lay, query, a, mn, mx, sel, l, o)
            ClimbSortField.RANDOM -> q.searchRandom(lay, query, a, mn, mx, sel, l, o)
            else -> if (desc) q.searchByAscensionistsDesc(lay, query, a, mn, mx, sel, l, o) else q.searchByAscensionistsAsc(lay, query, a, mn, mx, sel, l, o)
        }.executeAsList().map { mapBrowse(it) }
    }

    override fun getClimbByUuid(uuid: String, angle: Int): ClimbWithStats? {
        return q.getClimbByUuid(angle.toLong(), uuid).executeAsOneOrNull()?.let {
            mapClimb(
                it.uuid, it.layout_id, it.setter_username, it.name, it.frames, it.frames_count,
                it.difficulty_average, it.quality_average, it.ascensionist_count, it.description,
                it.is_nomatch, it.frames_pace, it.hsm,
                benchmarkDifficulty = it.benchmark_difficulty ?: 0.0,
                faUsername = it.fa_username, faAt = it.fa_at,
                moveCount = it.move_count,
                origin = it.origin,
                kilterStatus = it.kilter_status,
                createdByPubkey = it.created_by_pubkey,
                source = it.source,
                syncStatus = it.sync_status,
                nostrEventId = it.nostr_event_id,
                boardBrand = it.board_brand,
            )
        }
    }

    override fun searchClimbsSorted(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int,
        sortField: ClimbSortField, sortDirection: SortDirection, limit: Int, offset: Int,
        climbType: ClimbTypeFilter, selProductSizeId: Int
    ): List<ClimbWithStats> {
        val lay = layoutId.toLong()
        val a = angle.toLong()
        val mn = climbType.minFrames()
        val mx = climbType.maxFrames()
        val asc = minAscensionists.toLong()
        val l = limit.toLong()
        val o = offset.toLong()
        val sel = selProductSizeId.toLong()
        val desc = sortDirection == SortDirection.DESC

        return when (sortField) {
            ClimbSortField.QUALITY -> if (desc) q.browseByQualityDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o) else q.browseByQualityAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o)
            ClimbSortField.DIFFICULTY -> if (desc) q.browseByDifficultyDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o) else q.browseByDifficultyAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o)
            ClimbSortField.NAME -> if (desc) q.browseByNameDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o) else q.browseByNameAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o)
            ClimbSortField.QUALITY_SENDS -> if (desc) q.browseByQualitySendsDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o) else q.browseByQualitySendsAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o)
            ClimbSortField.RANDOM -> q.browseRandom(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o)
            else -> if (desc) q.browseByAscensionistsDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o) else q.browseByAscensionistsAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, sel, l, o)
        }.executeAsList().map { mapBrowse(it) }
    }

    override fun countFilteredClimbsFast(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, selProductSizeId: Int): Long {
        return q.countFilteredClimbsFast(layoutId.toLong(), angle.toLong(), minDifficulty, maxDifficulty, minAscensionists.toLong(), selProductSizeId.toLong()).executeAsOne()
    }

    override fun countFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int): Long {
        return q.countFilteredClimbs(layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(), minDifficulty, maxDifficulty, minAscensionists.toLong(), selProductSizeId.toLong()).executeAsOne()
    }

    override fun countBenchmarkFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int): Long {
        return q.countBenchmarkFilteredClimbs(layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(), minDifficulty, maxDifficulty, minAscensionists.toLong(), selProductSizeId.toLong()).executeAsOne()
    }

    override fun countSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter, selProductSizeId: Int): Long {
        return q.countSearchClimbs(layoutId.toLong(), query, query, angle.toLong(), climbType.minFrames(), climbType.maxFrames(), selProductSizeId.toLong()).executeAsOne()
    }

    override fun countBenchmarkSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter, selProductSizeId: Int): Long {
        return q.countBenchmarkSearchClimbs(layoutId.toLong(), query, query, angle.toLong(), climbType.minFrames(), climbType.maxFrames(), selProductSizeId.toLong()).executeAsOne()
    }

    override fun getClimbCount(): Long {
        return q.countClimbs().executeAsOne()
    }

    override fun hasAnyClimbs(): Boolean {
        return q.hasAnyClimbs().executeAsOne()
    }

    override fun getStatCount(): Long {
        return q.countStats().executeAsOne()
    }

    override fun countOrphanStats(): Long {
        return q.countOrphanStats().executeAsOne()
    }

    override fun countListedClimbsWithoutStats(): Long {
        return q.countListedClimbsWithoutStats().executeAsOne()
    }

    override fun hasPostV8ResyncMarker(): Boolean {
        return q.hasPostV8ResyncMarker().executeAsOneOrNull() != null
    }

    override fun clearPostV8ResyncMarker() {
        q.clearPostV8ResyncMarker()
    }

    override fun hasHomewallResyncMarker(): Boolean {
        return q.hasHomewallResyncMarker().executeAsOneOrNull() != null
    }

    override fun clearHomewallResyncMarker() {
        q.clearHomewallResyncMarker()
    }

    override fun deleteKilterCatalogData() {
        q.transaction {
            q.deleteKilterSourceClimbStats()
            q.deleteKilterSourceClimbs()
        }
    }

    override fun climbExistsByUuid(uuid: String): Boolean {
        return q.climbExistsByUuid(uuid).executeAsOne() > 0
    }

    override fun statExistsByUuid(uuid: String): Boolean {
        return q.statExistsByUuid(uuid).executeAsOne() > 0
    }

    override fun getClimbCountByAngle(layoutId: Int, climbType: ClimbTypeFilter): List<AngleClimbCount> {
        return q.getClimbCountByAngle(layoutId.toLong(), climbType.minFrames(), climbType.maxFrames()).executeAsList().map {
            AngleClimbCount(angle = it.angle, climbCount = it.climb_count)
        }
    }

    override fun getAnglesForClimb(climbUuid: String): List<AngleOption> {
        return q.getAnglesForClimb(climbUuid).executeAsList().map {
            AngleOption(
                angle = it.angle.toInt(),
                difficultyAverage = it.difficulty_average,
                qualityAverage = it.quality_average,
                ascensionistCount = it.ascensionist_count,
                benchmarkDifficulty = it.benchmark_difficulty ?: 0.0
            )
        }
    }

    override fun countNomatchClimbs(): Long {
        return q.countNomatchClimbs().executeAsOne()
    }

    override fun getClimbsByUuids(
        uuids: Collection<String>, angle: Int, layoutId: Int, minDifficulty: Double,
        maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter
    ): List<ClimbWithStats> {
        if (uuids.isEmpty()) return emptyList()
        return q.getClimbsByUuids(
            layoutId.toLong(), uuids, angle.toLong(), climbType.minFrames(), climbType.maxFrames(),
            minDifficulty, maxDifficulty, minAscensionists.toLong()
        ).executeAsList().map { mapBrowse(it) }
    }

    override fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<ClimbWithStats> {
        if (uuids.isEmpty()) return emptyList()
        return q.getClimbsByUuidsSimple(uuids, angle.toLong())
            .executeAsList().map { mapBrowse(it) }
    }
    override fun getClimbsByUuidsAnyAngle(uuids: Collection<String>): List<ClimbWithStats> {
        if (uuids.isEmpty()) return emptyList()
        return q.getClimbsByUuidsAnyAngle(uuids)
            .executeAsList().map { mapBrowse(it) }
    }

    override fun getAllBrowseMatchingUuids(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int
    ): List<String> {
        return q.browseAllMatchingUuids(
            layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(),
            minDifficulty, maxDifficulty, minAscensionists.toLong(), selProductSizeId.toLong()
        ).executeAsList()
    }

    override fun searchClimbUuidsByHold(
        holdPattern: String, angle: Int, layoutId: Int, minDifficulty: Double,
        maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter
    ): List<String> {
        return q.getAllFramesForFilter(
            layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(),
            minDifficulty, maxDifficulty, minAscensionists.toLong()
        ).executeAsList()
            .filter { it.frames.contains(holdPattern) }
            .map { it.uuid }
    }

    override fun searchClimbUuidsByAllHolds(
        holdPatterns: List<String>, angle: Int, layoutId: Int, minDifficulty: Double,
        maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter
    ): Set<String> {
        if (holdPatterns.isEmpty()) return emptySet()
        return q.getAllFramesForFilter(
            layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(),
            minDifficulty, maxDifficulty, minAscensionists.toLong()
        ).executeAsList()
            .filter { row -> holdPatterns.all { pattern -> row.frames.contains(pattern) } }
            .map { it.uuid }
            .toSet()
    }

    override fun getAllFramesForHeatmap(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter
    ): List<ClimbFrameRow> {
        return q.getAllFramesForFilter(
            layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(),
            minDifficulty, maxDifficulty, minAscensionists.toLong()
        ).executeAsList().map { ClimbFrameRow(it.uuid, it.frames) }
    }

    // ── Board Layout Queries ───────────────────────────────────

    override fun getAllPlacements(boardBrand: String): List<BoardPlacement> {
        return q.getAllPlacements(boardBrand).executeAsList().map {
            BoardPlacement(placementId = it.placement_id, holeId = it.hole_id, setId = it.set_id, x = it.x, y = it.y)
        }
    }

    override fun getPlacementsForLayout(productSizeId: Int, layoutId: Int, boardBrand: String): List<BoardPlacement> {
        val activeSetIds = q.getBoardImages(productSizeId.toLong(), layoutId.toLong(), boardBrand)
            .executeAsList()
            .map { it.set_id }
            .toSet()
        val all = getAllPlacements(boardBrand)
        return if (activeSetIds.isEmpty()) all else all.filter { it.setId in activeSetIds }
    }

    override fun getProductSize(id: Int, boardBrand: String): BoardSize? {
        return q.getProductSize(id.toLong(), boardBrand).executeAsOneOrNull()?.let {
            BoardSize(it.id, it.product_id, it.name, it.edge_left, it.edge_right, it.edge_bottom, it.edge_top, it.image_filename)
        }
    }

    override fun getAllProductSizes(productId: Long, boardBrand: String): List<BoardSize> {
        return q.getAllProductSizes(productId, boardBrand).executeAsList().map {
            BoardSize(it.id, it.product_id, it.name, it.edge_left, it.edge_right, it.edge_bottom, it.edge_top, it.image_filename)
        }
    }

    override fun getBoardImages(productSizeId: Int, layoutId: Int, boardBrand: String): List<BoardImage> {
        return q.getBoardImages(productSizeId.toLong(), layoutId.toLong(), boardBrand).executeAsList().map {
            BoardImage(id = it.id, productSizeId = it.product_size_id, layoutId = it.layout_id, setId = it.set_id, imageFilename = it.image_filename)
        }
    }

    override fun getProductSizesForLayout(layoutId: Int, boardBrand: String): List<Int> {
        return q.getProductSizesForLayout(layoutId.toLong(), boardBrand).executeAsList().map { it.toInt() }
    }

    override fun getDefaultLayoutForBrand(boardBrand: String): Int? =
        q.getMostCommonLayoutForBrand(boardBrand).executeAsOneOrNull()?.toInt()

    override fun getDefaultProductSizeForBrand(boardBrand: String): Pair<Int, String>? =
        q.getDefaultProductSizeForBrand(boardBrand).executeAsOneOrNull()?.let { it.id.toInt() to it.name }

    override fun getCruxCoachClimbs(
        layoutId: Int, angle: Int, minDifficulty: Double, maxDifficulty: Double,
        minAscensionists: Int, climbType: ClimbTypeFilter, selProductSizeId: Int,
    ): List<ClimbWithStats> {
        return q.browseCruxCoachOnly(
            layoutId.toLong(), angle.toLong(),
            climbType.minFrames(), climbType.maxFrames(),
            minDifficulty, maxDifficulty, minAscensionists.toLong(),
            selProductSizeId.toLong(),
        ).executeAsList().map { mapBrowse(it) }
    }

    override fun canRenderClimbOnSize(uuid: String, productSizeId: Int, boardBrand: String): Boolean {
        return q.canRenderClimbOnSize(productSizeId.toLong(), boardBrand, uuid).executeAsOneOrNull() != null
    }

    override fun getProductSizeForClimbRender(uuid: String, boardBrand: String): Int? {
        return q.getProductSizeForClimbRender(boardBrand, uuid).executeAsOneOrNull()?.toInt()
    }

    override fun getPlacementLedMap(productSizeId: Int, boardBrand: String): Map<Int, Int> {
        return q.getPlacementLedMap(productSizeId.toLong(), boardBrand).executeAsList().associate {
            it.placement_id.toInt() to it.led_position.toInt()
        }
    }

    override fun getMirrorPlacementMap(productSizeId: Int, boardBrand: String): Map<Int, Int> {
        return q.getMirrorPlacementMap(productSizeId.toLong(), boardBrand).executeAsList().associate {
            it.original_placement_id.toInt() to it.mirrored_placement_id.toInt()
        }
    }

    override fun countLeds(): Long {
        return q.countLeds().executeAsOne()
    }

    override fun getLedGrid(productSizeId: Int, boardBrand: String): List<LedGridPoint> {
        return q.getAllLedGrid(productSizeId.toLong(), boardBrand).executeAsList().map {
            LedGridPoint(placementId = it.placement_id, x = it.x, y = it.y, ledPosition = it.led_position)
        }
    }

    // ── Write Operations ───────────────────────────────────────

    override fun upsertClimb(
        uuid: String, layoutId: Long, setter: String?, name: String, frames: String,
        framesCount: Long, isListed: Long, edgeLeft: Long?, edgeRight: Long?,
        edgeBottom: Long?, edgeTop: Long?, createdAt: String?,
        description: String, isNomatch: Long, framesPace: Long, hsm: Long,
        moveCount: Long
    ) {
        // Two-step transaction so the Kilter-side blob refresh doesn't
        // wipe CruxCoach-side metadata (origin, nostr_event_id,
        // kilter_status, …). Existing row → UPDATE only the
        // Kilter-authoritative columns. New row → INSERT with column
        // defaults (source='kilter', origin='kilter', kilter_status NULL).
        q.transaction {
            val exists = q.existsClimb(uuid).executeAsOneOrNull() != null
            if (exists) {
                q.updateClimbBlobFields(
                    layout_id = layoutId,
                    setter_username = setter,
                    name = name,
                    frames_count = framesCount,
                    is_listed = isListed,
                    edge_left = edgeLeft,
                    edge_right = edgeRight,
                    edge_bottom = edgeBottom,
                    edge_top = edgeTop,
                    created_at = createdAt,
                    description = description,
                    is_nomatch = isNomatch,
                    frames_pace = framesPace,
                    hsm = hsm,
                    frames = frames,
                    move_count = moveCount,
                    uuid = uuid,
                )
            } else {
                q.insertClimbRow(
                    uuid = uuid,
                    layout_id = layoutId,
                    setter_username = setter,
                    name = name,
                    frames_count = framesCount,
                    is_listed = isListed,
                    edge_left = edgeLeft,
                    edge_right = edgeRight,
                    edge_bottom = edgeBottom,
                    edge_top = edgeTop,
                    created_at = createdAt,
                    description = description,
                    is_nomatch = isNomatch,
                    frames_pace = framesPace,
                    hsm = hsm,
                    frames = frames,
                    move_count = moveCount,
                )
            }
        }
    }

    override fun upsertClimbStat(
        climbUuid: String, angle: Long, displayDifficulty: Double?,
        difficultyAverage: Double?, qualityAverage: Double?,
        ascensionistCount: Long?, benchmarkDifficulty: Double?,
        faUsername: String?, faAt: String?,
        officialKilterDifficulty: Long?
    ) {
        q.upsertClimbStat(climbUuid, angle, displayDifficulty, difficultyAverage,
            qualityAverage, ascensionistCount, benchmarkDifficulty, faUsername, faAt,
            officialKilterDifficulty)
    }

    override fun upsertHoldPosition(holeId: Long, productSizeId: Long, x: Long, y: Long, ledPosition: Long, placementId: Long, boardBrand: String) {
        q.upsertHoldPosition(boardBrand, holeId, productSizeId, x, y, ledPosition, placementId)
    }

    override fun upsertLed(holeId: Long, productSizeId: Long, position: Long, boardBrand: String) {
        q.upsertLed(boardBrand, holeId, productSizeId, position)
    }

    override fun upsertHole(id: Long, productSizeId: Long, x: Long, y: Long, mirroredHoleId: Long?, boardBrand: String) {
        q.upsertHole(boardBrand, id, productSizeId, x, y, mirroredHoleId)
    }

    override fun upsertPlacement(placementId: Long, holeId: Long, setId: Long, x: Long, y: Long, boardBrand: String) {
        q.upsertPlacement(boardBrand, placementId, holeId, setId, x, y)
    }

    override fun upsertProductSize(id: Long, productId: Long, name: String, edgeLeft: Long, edgeRight: Long, edgeBottom: Long, edgeTop: Long, imageFilename: String?, boardBrand: String) {
        q.upsertProductSize(boardBrand, id, productId, name, edgeLeft, edgeRight, edgeBottom, edgeTop, imageFilename)
    }

    override fun upsertBoardImage(id: Long, productSizeId: Long, layoutId: Long, setId: Long, imageFilename: String, boardBrand: String) {
        q.upsertBoardImage(boardBrand, id, productSizeId, layoutId, setId, imageFilename)
    }

    // ── Sync State ─────────────────────────────────────────────

    override fun upsertSyncState(tableName: String, lastSynchronizedAt: String) {
        q.upsertSyncState(tableName, lastSynchronizedAt)
    }

    override fun getSyncState(tableName: String): String? {
        return q.getSyncState(tableName).executeAsOneOrNull()
    }

    override fun getAllClimbUuids(): Set<String> {
        return q.getAllClimbUuids().executeAsList().toSet()
    }

    override fun getAllStatKeys(): Map<Pair<String, Long>, Long?> {
        return q.getAllStatKeys().executeAsList().associate {
            (it.climb_uuid to it.angle) to it.ascensionist_count
        }
    }

    override fun runInTransaction(block: () -> Unit) {
        q.transaction { block() }
    }

    // ── Bulk delete ──────────────────────────────────────────────

    override fun deleteAllBoardData() {
        q.transaction {
            q.deleteAllClimbStats()
            q.deleteAllClimbs()
            q.deleteAllHoldPositions()
            q.deleteAllLeds()
            q.deleteAllPlacements()
            q.deleteAllBoardImages()
            q.deleteAllProductSizes()
            q.deleteAllHoles()
            q.deleteAllSyncState()
            q.deleteAllBetaLinks()
        }
    }

    // ── Community-climb support (FEAT-003) ──────────────────────

    override fun deleteLocalClimb(uuid: String) {
        q.deleteLocalClimb(uuid)
    }

    override fun markCommunityClimbDeleted(uuid: String, pubkey: String, tombstoneIso: String) {
        q.markCommunityClimbDeleted(uuid = uuid, pubkey = pubkey, tombstone_iso = tombstoneIso)
    }

    override fun isClimbTombstoned(uuid: String): Boolean =
        q.isClimbTombstoned(uuid).executeAsOneOrNull() != null

    override fun insertTombstoneShell(uuid: String, pubkey: String, dTag: String, tombstoneIso: String) {
        q.insertTombstoneShell(
            uuid = uuid,
            tombstone_iso = tombstoneIso,
            d_tag = dTag,
            pubkey = pubkey,
        )
    }

    override fun getCommunityClimbDeleteContext(uuid: String): CommunityClimbDeleteContext? {
        val row = q.getCommunityClimbDeleteContext(uuid).executeAsOneOrNull() ?: return null
        return CommunityClimbDeleteContext(
            nostrEventId = row.nostr_event_id,
            nostrDTag = row.nostr_d_tag,
            createdByPubkey = row.created_by_pubkey,
            kilterStatus = row.kilter_status,
            origin = row.origin,
        )
    }

    override fun getClimbCreatedAt(uuid: String): String? =
        q.getClimbCreatedAt(uuid).executeAsOneOrNull()?.created_at

    override fun getClimbAuthorPubkey(uuid: String): String? =
        q.getClimbAuthorPubkey(uuid).executeAsOneOrNull()?.created_by_pubkey

    override fun isLocallyAuthored(uuid: String): Boolean =
        q.isLocallyAuthored(uuid).executeAsOneOrNull() != null

    /**
     * Heatmap fast-path: parsed placements + roles per (layoutId, angle).
     *
     * `climbPlacements[i]` and `climbRoles[i]` are parallel IntArrays for
     * the i-th source climb (j-th placement at index j has role at the
     * same j in the role array). Layout/angle don't change while the user
     * edits, so we parse once and reuse across every recompute.
     *
     * Cache invalidates implicitly when the key changes; new climbs synced
     * mid-session won't appear until the editor reopens — acceptable.
     */
    private class HeatmapCache(
        val layoutId: Long,
        val angle: Long,
        val climbPlacements: Array<IntArray>,
        val climbRoles: Array<IntArray>,
    )

    @Volatile
    private var heatmapCache: HeatmapCache? = null

    override fun computeEditorHeatmap(
        layoutId: Long,
        angle: Long,
        seedHolds: Set<Int>,
        targetRole: Int?,
    ): Map<Int, Float> {
        val cache = ensureHeatmapCache(layoutId, angle) ?: return emptyMap()

        // Most calls have empty seedHolds (general popularity); only build
        // the IntArray when we'd actually filter.
        val seedAsArray: IntArray? = if (seedHolds.isEmpty()) null else seedHolds.toIntArray()

        val placementCounts = HashMap<Int, Int>(cache.climbPlacements.size.coerceAtMost(2048))
        var matchedClimbs = 0

        for (i in cache.climbPlacements.indices) {
            val placements = cache.climbPlacements[i]
            if (seedAsArray != null && !placementsContainAll(placements, seedAsArray)) continue
            matchedClimbs++
            val roles = cache.climbRoles[i]
            for (j in placements.indices) {
                // Role-aware mode: the heatmap suggests "where do role-X
                // holds usually go in climbs that already contain my seed?"
                // Skip placements whose source-climb role doesn't match.
                if (targetRole != null && roles[j] != targetRole) continue
                val pid = placements[j]
                placementCounts[pid] = (placementCounts[pid] ?: 0) + 1
            }
        }
        if (matchedClimbs == 0) return emptyMap()

        // Strip seed holds — the editor already renders them in their role
        // colour, so a heatmap halo on top is just noise.
        if (seedAsArray != null) {
            for (pid in seedAsArray) placementCounts.remove(pid)
        }

        val maxCount = placementCounts.values.maxOrNull() ?: return emptyMap()
        if (maxCount == 0) return emptyMap()
        val maxCountF = maxCount.toFloat()
        return placementCounts.mapValues { (_, count) -> count / maxCountF }
    }

    private fun ensureHeatmapCache(layoutId: Long, angle: Long): HeatmapCache? {
        val existing = heatmapCache
        if (existing != null && existing.layoutId == layoutId && existing.angle == angle) {
            return existing
        }
        // Lock-free; double rebuild on a race is harmless (idempotent),
        // and racing rebuilds for the same key are extremely unlikely
        // since the editor only fires one heatmap job at a time.
        val rows = q.getFramesForLayoutAndAngle(layoutId, angle).executeAsList()
        if (rows.isEmpty()) {
            heatmapCache = null
            return null
        }
        val placements = arrayOfNulls<IntArray>(rows.size)
        val roles = arrayOfNulls<IntArray>(rows.size)
        for (i in rows.indices) {
            val (p, r) = extractPlacementsAndRolesFast(rows[i].frames)
            placements[i] = p
            roles[i] = r
        }
        @Suppress("UNCHECKED_CAST")
        val cache = HeatmapCache(
            layoutId,
            angle,
            placements as Array<IntArray>,
            roles as Array<IntArray>,
        )
        heatmapCache = cache
        return cache
    }

    /**
     * Fast inline parser for (placementId, roleId) pairs in a frames string.
     *
     * Handles both Aurora delta (`p{id}r{role}…`) and Kilter range
     * (`h{id}p{ref}[s{n}][e{n}]…`). Single pass, no regex, no autoboxing.
     * Returns parallel IntArrays; index j in both is the same hold.
     * Used only on the heatmap hot path — BoardClimbParser stays the
     * canonical parser everywhere else.
     */
    private fun extractPlacementsAndRolesFast(frames: String): Pair<IntArray, IntArray> {
        if (frames.isEmpty()) return EMPTY_PAIR
        // First-frame-only — `frames_count = 1` is enforced by the SQL.
        val end = frames.indexOf(',').let { if (it < 0) frames.length else it }
        if (end == 0) return EMPTY_PAIR
        // Detect format from the first character.
        val isRange = frames[0] == 'h'
        val placementMarker = if (isRange) 'h' else 'p'
        val roleMarker = if (isRange) 'p' else 'r'

        var pBuf = IntArray(32)
        var rBuf = IntArray(32)
        var size = 0
        var i = 0

        while (i < end) {
            if (frames[i] != placementMarker) { i++; continue }
            i++
            var pid = 0
            var pidConsumed = false
            while (i < end) {
                val c = frames[i]
                if (c < '0' || c > '9') break
                pid = pid * 10 + (c.code - '0'.code)
                i++; pidConsumed = true
            }
            if (!pidConsumed) continue
            // Role marker must immediately follow the placement digits.
            if (i >= end || frames[i] != roleMarker) continue
            i++
            var role = 0
            var roleConsumed = false
            while (i < end) {
                val c = frames[i]
                if (c < '0' || c > '9') break
                role = role * 10 + (c.code - '0'.code)
                i++; roleConsumed = true
            }
            if (!roleConsumed) continue
            // Normalize route-specific roles 42-45 → 12-15.
            val normalized = when (role) {
                42 -> 12; 43 -> 13; 44 -> 14; 45 -> 15
                else -> role
            }
            if (size == pBuf.size) {
                pBuf = pBuf.copyOf(pBuf.size * 2)
                rBuf = rBuf.copyOf(rBuf.size * 2)
            }
            pBuf[size] = pid
            rBuf[size] = normalized
            size++
            // Optional Kilter `s{n}`/`e{n}` decorations are skipped naturally:
            // the outer loop only consumes characters until the next
            // placementMarker, so any non-marker chars just advance i.
        }

        if (size == 0) return EMPTY_PAIR
        val p = if (size == pBuf.size) pBuf else pBuf.copyOf(size)
        val r = if (size == rBuf.size) rBuf else rBuf.copyOf(size)
        return p to r
    }

    private fun placementsContainAll(placements: IntArray, seed: IntArray): Boolean {
        for (s in seed) {
            var found = false
            for (p in placements) {
                if (p == s) { found = true; break }
            }
            if (!found) return false
        }
        return true
    }

    private companion object {
        private val EMPTY_INT_ARRAY = IntArray(0)
        private val EMPTY_PAIR: Pair<IntArray, IntArray> = EMPTY_INT_ARRAY to EMPTY_INT_ARRAY
    }

    /** `board_brand` wire value for a layout, derived once so authored
     *  drafts + ingested community climbs persist the right family
     *  (MoonBoard variants → "moonboard", everything else → "kilter")
     *  without threading brand through every call site. */
    private fun brandForLayout(layoutId: Long): String =
        com.cruxcoach.domain.board.BoardBrand.fromLayoutId(layoutId).wireValue

    override fun insertLocalDraft(
        draft: LocalClimbDraft,
        layoutId: Long,
        angle: Long,
        setterGradeId: Int?,
        bounds: com.cruxcoach.domain.community.ClimbBounds?,
    ) {
        q.transaction {
            q.insertLocalDraft(
                uuid = draft.uuid,
                layout_id = layoutId,
                setter_username = draft.setterUsername,
                name = draft.name,
                frames = draft.framesText,
                edge_left = bounds?.left?.toLong(),
                edge_right = bounds?.right?.toLong(),
                edge_bottom = bounds?.bottom?.toLong(),
                edge_top = bounds?.top?.toLong(),
                created_at = draft.createdAt,
                description = draft.description,
                move_count = draft.moveCount,
                created_by_pubkey = draft.createdByPubkey,
                frames_hash = draft.framesHash,
                board_brand = brandForLayout(layoutId),
            )
            // Stub climb_stats so the climb appears in the browse VIEW.
            // Setter difficulty is the only known signal; community
            // vote-aggregation (FEAT-009) is backlogged, so quality_average
            // stays NULL until that lands in a later release.
            q.upsertLocalClimbStat(
                climb_uuid = draft.uuid,
                angle = angle,
                display_difficulty = setterGradeId?.toDouble(),
                difficulty_average = setterGradeId?.toDouble(),
            )
        }
    }

    override fun upsertCommunityClimb(
        uuid: String,
        layoutId: Long,
        setterUsername: String?,
        name: String,
        framesText: String,
        description: String,
        moveCount: Long,
        nostrEventId: String,
        nostrDTag: String,
        createdByPubkey: String,
        framesHash: String,
        createdAt: String,
        angle: Long,
        difficultyAverage: Double?,
        qualityAverage: Double?,
        bounds: com.cruxcoach.domain.community.ClimbBounds?,
    ) {
        q.transaction {
            q.upsertCommunityClimb(
                uuid = uuid,
                layout_id = layoutId,
                setter_username = setterUsername,
                name = name,
                frames = framesText,
                edge_left = bounds?.left?.toLong(),
                edge_right = bounds?.right?.toLong(),
                edge_bottom = bounds?.bottom?.toLong(),
                edge_top = bounds?.top?.toLong(),
                created_at = createdAt,
                description = description,
                move_count = moveCount,
                nostr_event_id = nostrEventId,
                nostr_d_tag = nostrDTag,
                created_by_pubkey = createdByPubkey,
                frames_hash = framesHash,
                board_brand = brandForLayout(layoutId),
            )
            q.upsertClimbStat(
                climb_uuid = uuid,
                angle = angle,
                display_difficulty = difficultyAverage,
                difficulty_average = difficultyAverage,
                quality_average = qualityAverage,
                ascensionist_count = 0,
                benchmark_difficulty = null,
                fa_username = null,
                fa_at = null,
                official_kilter_difficulty = null,
            )
        }
    }

    // ── Community-climb dead-letter queue (M16) ────────────────

    override fun recordCommunityClimbDeadLetter(
        uuid: String,
        eventId: String,
        eventCreatedAt: Long,
        rawEventJson: String,
        nowMs: Long,
        errorExcerpt: String?,
    ) {
        // Atomic insert-or-increment: try INSERT OR IGNORE first, and
        // fall through to UPDATE iff the row already exists (changes()
        // = 0 on conflict). SQLite 3.18 (Android API 26 baseline)
        // doesn't speak `ON CONFLICT … DO UPDATE`, so the two-step
        // form lives here in code instead of in the SQL file.
        q.transaction {
            q.insertDeadLetter(
                uuid = uuid,
                event_id = eventId,
                event_created_at = eventCreatedAt,
                raw_event_json = rawEventJson,
                first_failed_at_ms = nowMs,
                last_failed_at_ms = nowMs,
                last_error_excerpt = errorExcerpt,
            )
            val inserted = q.lastClimbsChangeCount().executeAsOne() > 0L
            if (!inserted) {
                q.incrementDeadLetterRetry(
                    last_failed_at_ms = nowMs,
                    last_error_excerpt = errorExcerpt,
                    uuid = uuid,
                )
            }
        }
    }

    override fun getRetriableCommunityClimbDeadLetters(
        maxRetries: Long,
        limit: Long,
    ): List<CommunityClimbDeadLetter> =
        q.getRetriableDeadLetters(maxRetries = maxRetries, limit = limit)
            .executeAsList()
            .map { row ->
                CommunityClimbDeadLetter(
                    uuid = row.uuid,
                    eventId = row.event_id,
                    eventCreatedAt = row.event_created_at,
                    rawEventJson = row.raw_event_json,
                    firstFailedAtMs = row.first_failed_at_ms,
                    lastFailedAtMs = row.last_failed_at_ms,
                    retryCount = row.retry_count,
                    lastErrorExcerpt = row.last_error_excerpt,
                )
            }

    override fun deleteCommunityClimbDeadLetter(uuid: String) {
        q.deleteDeadLetter(uuid)
    }

    override fun getCommunityClimbDeadLetterCounts(maxRetries: Long): DeadLetterCounts {
        val row = q.getDeadLetterCounts(maxRetries).executeAsOne()
        return DeadLetterCounts(
            total = row.total_count,
            abandoned = row.abandoned_count ?: 0L,
        )
    }

    override fun markClimbPublishedNostr(
        uuid: String,
        nostrEventId: String,
        nostrDTag: String,
        pubkey: String,
    ) {
        q.transaction {
            q.markClimbPublishedNostr(
                nostr_event_id = nostrEventId,
                nostr_d_tag = nostrDTag,
                pubkey = pubkey,
                uuid = uuid,
            )
            // SQL refuses to overwrite a foreign owner (see Board.sq).
            // If changes()=0 and the row exists, the caller passed a
            // pubkey that doesn't match the row's existing owner —
            // surface that as a loud error instead of a silent no-op.
            val changed = q.lastClimbsChangeCount().executeAsOne() > 0L
            if (!changed) {
                val existingOwner = q.getClimbAuthorPubkey(uuid).executeAsOneOrNull()?.created_by_pubkey
                if (existingOwner != null && existingOwner != pubkey) {
                    error("markClimbPublishedNostr: refusing to mark uuid=$uuid published — owner mismatch")
                }
            }
        }
    }

    override fun markClimbPublishFailed(uuid: String) {
        q.markClimbPublishFailed(uuid)
    }

    override fun markClimbPublishInFlight(uuid: String) {
        q.markClimbPublishInFlight(uuid)
    }

    override fun getKilterPublishState(uuid: String): KilterPublishState? {
        val row = q.getKilterPublishState(uuid).executeAsOneOrNull() ?: return null
        return KilterPublishState(
            status = row.kilter_status,
            syncedAtEpochSeconds = row.kilter_synced_at,
        )
    }

    override fun updateSetterUsernameForPubkey(pubkey: String, displayName: String) {
        q.updateSetterUsernameForPubkey(setter_username = displayName, created_by_pubkey = pubkey)
    }

    override fun getClimbsByPubkey(pubkey: String): List<SetterClimbEntry> {
        return q.getClimbsByPubkey(pubkey).executeAsList().map { row ->
            SetterClimbEntry(
                uuid = row.uuid,
                name = row.name,
                angle = row.angle.toInt(),
                difficultyAverage = row.difficulty_average,
                qualityAverage = row.quality_average,
                ascensionistCount = row.ascensionist_count ?: 0L,
            )
        }
    }

    override fun getOwnClimbsForBrowse(
        pubkey: String,
        layoutId: Int,
        preferredAngle: Int,
    ): List<ClimbWithStats> {
        // Reuse the existing setter-detail query: it pulls climb_browse rows
        // for this pubkey across every angle. We dedupe to one row per uuid,
        // preferring the row matching `preferredAngle` so the visible stats
        // line up with the browser's current angle when possible. Falls back
        // to any angle so a draft saved at 40° remains visible while the
        // user browses at 35° — the whole point of the My-climbs filter.
        val all = q.getClimbsByPubkey(pubkey).executeAsList()
            .filter { it.layout_id == layoutId.toLong() }
        val grouped = all.groupBy { it.uuid }
        val target = preferredAngle.toLong()
        return grouped.values.map { rows ->
            val pick = rows.firstOrNull { it.angle == target } ?: rows.first()
            mapBrowse(pick)
        }
    }

    override fun getCommunitySetterStats(): List<SetterStat> {
        return q.getCommunitySetterStats().executeAsList().mapNotNull { row ->
            val pubkey = row.created_by_pubkey ?: return@mapNotNull null
            SetterStat(
                pubkey = pubkey,
                displayName = row.setter_username,
                climbCount = row.climb_count,
            )
        }
    }

    override fun markKilterPublishPending(uuid: String) {
        q.markKilterPublishPending(uuid)
    }

    override fun markKilterPublishSynced(uuid: String, via: String, syncedAtEpochSeconds: Long) {
        q.markKilterPublishSynced(
            kilter_synced_at = syncedAtEpochSeconds,
            kilter_publish_via = via,
            uuid = uuid,
        )
    }

    override fun markKilterPublishFailed(uuid: String, error: String) {
        q.markKilterPublishFailed(kilter_error = error, uuid = uuid)
    }

    override fun markKilterPublishDiverged(uuid: String, error: String) {
        q.markKilterPublishDiverged(kilter_error = error, uuid = uuid)
    }

    override fun markKilterPublishRejected(uuid: String, error: String) {
        q.markKilterPublishRejected(kilter_error = error, uuid = uuid)
    }

    override fun sweepStuckKilterPending(olderThanMs: Long): Long =
        q.transactionWithResult {
            q.sweepStuckKilterPending(olderThanMs)
            q.lastClimbsChangeCount().executeAsOne()
        }

    override fun claimKilterPublishSlot(uuid: String): KilterClaim {
        // Read kilter_synced_at BEFORE the CAS so the caller can pick
        // CREATE vs UPDATE based on the pre-claim state. Both reads
        // happen inside the transaction so they're consistent with
        // the row state the CAS observes.
        return q.transactionWithResult {
            val priorSyncedAt = q.getKilterPublishState(uuid).executeAsOneOrNull()
                ?.kilter_synced_at
            q.claimKilterPublishSlot(uuid)
            val claimed = q.lastClimbsChangeCount().executeAsOne() > 0L
            if (claimed) KilterClaim.Won(priorSyncedAt) else KilterClaim.Lost
        }
    }

    override fun recordKilterPublishAttempt(
        climbUuid: String,
        attemptedAtMs: Long,
        op: KilterPublishOp,
        via: String,
        outcome: KilterPublishOutcomeKind,
        httpCode: Int?,
        errorExcerpt: String?,
    ) {
        q.recordKilterPublishAttempt(
            climb_uuid = climbUuid,
            attempted_at = attemptedAtMs,
            op = if (op == KilterPublishOp.UPDATE) "update" else "create",
            via = via,
            outcome = outcome.storageValue(),
            http_code = httpCode?.toLong(),
            error_excerpt = errorExcerpt,
        )
    }

    override fun getKilterPublishAttempts(climbUuid: String, limit: Int): List<KilterPublishAttempt> =
        q.getKilterPublishAttempts(climbUuid, limit.toLong()).executeAsList().map { row ->
            KilterPublishAttempt(
                id = row.id,
                climbUuid = row.climb_uuid,
                attemptedAtMs = row.attempted_at,
                op = row.op,
                via = row.via,
                outcome = row.outcome,
                httpCode = row.http_code?.toInt(),
                errorExcerpt = row.error_excerpt,
            )
        }

    override fun getKilterPublishQueueStats(): KilterPublishQueueStats {
        val row = q.getKilterPublishQueueStats().executeAsOne()
        return KilterPublishQueueStats(
            pendingCount = row.pending_count,
            failedCount = row.failed_count,
            lastAttemptAtMs = row.last_attempt_at,
        )
    }

    override fun getClimbsAwaitingNostrRetry(pubkey: String): List<CommunityClimbRow> =
        q.getClimbsAwaitingNostrRetry(pubkey).executeAsList().map { it.toCommunityRow() }

    override fun getClimbsAwaitingKilterRetry(pubkey: String): List<CommunityClimbRow> =
        q.getClimbsAwaitingKilterRetry(pubkey).executeAsList().map { it.toCommunityRow() }

    override fun getDraftClimbs(pubkey: String?): List<CommunityClimbRow> =
        q.getDraftClimbs(pubkey).executeAsList().map { it.toCommunityRow() }

    override fun getMyClimbs(pubkey: String): List<CommunityClimbRow> =
        q.getMyClimbs(pubkey).executeAsList().map { it.toCommunityRow() }

    override fun getCommunityClimbs(): List<CommunityClimbRow> =
        q.getCommunityClimbs().executeAsList().map { it.toCommunityRow() }

    override fun getClimbStatsForUuid(uuid: String): Pair<Int, Int?>? {
        val row = q.getClimbStatsForUuid(uuid).executeAsOneOrNull() ?: return null
        return row.angle.toInt() to row.display_difficulty?.toInt()
    }

    override fun findClimbByFramesHash(framesHash: String, layoutId: Long): CommunityClimbRow? {
        val row = q.findClimbByFramesHash(framesHash, layoutId).executeAsOneOrNull() ?: return null
        // Lightweight projection — we only need uuid + name + source + pubkey for dup-detection
        return CommunityClimbRow(
            uuid = row.uuid,
            name = row.name,
            setterUsername = null,
            description = "",
            framesText = "",
            source = row.source,
            syncStatus = "",
            createdByPubkey = row.created_by_pubkey,
            nostrEventId = null,
            nostrDTag = null,
            framesHash = framesHash,
            createdAt = null,
            moveCount = 0,
            kilterSyncedAt = null,
            layoutId = layoutId,
        )
    }

    override fun upsertSetterGrade(
        climbDTag: String,
        angle: Long,
        setterGradeId: Int,
        lastUpdatedEpochMs: Long,
    ) {
        q.upsertGradeCache(
            climb_d_tag = climbDTag,
            angle = angle,
            setter_grade_id = setterGradeId.toLong(),
            consensus_average = null,
            consensus_grade_id = null,
            vote_count = 0,
            confidence = "LOW",
            last_updated = lastUpdatedEpochMs,
        )
    }

    // ── Backup / restore for own climbs (FEAT-008 Phase B) ──────

    override fun getOwnClimbsForBackup(pubkey: String): List<OwnClimbBackupRow> =
        q.getOwnClimbsForBackup(pubkey).executeAsList().map { row ->
            OwnClimbBackupRow(
                uuid = row.uuid,
                layoutId = row.layout_id,
                setterUsername = row.setter_username,
                name = row.name,
                frames = row.frames,
                edgeLeft = row.edge_left,
                edgeRight = row.edge_right,
                edgeBottom = row.edge_bottom,
                edgeTop = row.edge_top,
                createdAt = row.created_at,
                description = row.description,
                moveCount = row.move_count,
                source = row.source,
                syncStatus = row.sync_status,
                createdByPubkey = row.created_by_pubkey,
                framesHash = row.frames_hash,
                nostrEventId = row.nostr_event_id,
                nostrDTag = row.nostr_d_tag,
                nostrPublishVia = row.nostr_publish_via,
                kilterStatus = row.kilter_status,
                kilterSyncedAt = row.kilter_synced_at,
                kilterPublishVia = row.kilter_publish_via,
                kilterError = row.kilter_error,
            )
        }

    override fun getOwnClimbStatsForBackup(pubkey: String): List<OwnClimbStatBackupRow> =
        q.getOwnClimbStatsForBackup(pubkey).executeAsList().map { row ->
            OwnClimbStatBackupRow(
                climbUuid = row.climb_uuid,
                angle = row.angle,
                displayDifficulty = row.display_difficulty,
                difficultyAverage = row.difficulty_average,
                qualityAverage = row.quality_average,
                ascensionistCount = row.ascensionist_count ?: 0L,
                benchmarkDifficulty = row.benchmark_difficulty,
            )
        }

    override fun getOwnClimbAngle(uuid: String): Long? =
        q.getOwnClimbAngle(uuid).executeAsOneOrNull()?.angle

    override fun restoreOwnClimb(row: OwnClimbBackupRow): Boolean =
        q.transactionWithResult {
            q.restoreOwnClimbInsert(
                uuid = row.uuid,
                layout_id = row.layoutId,
                setter_username = row.setterUsername,
                name = row.name,
                frames = row.frames,
                edge_left = row.edgeLeft,
                edge_right = row.edgeRight,
                edge_bottom = row.edgeBottom,
                edge_top = row.edgeTop,
                created_at = row.createdAt,
                description = row.description,
                move_count = row.moveCount,
                source = row.source,
                sync_status = row.syncStatus,
                created_by_pubkey = row.createdByPubkey,
                frames_hash = row.framesHash,
                nostr_event_id = row.nostrEventId,
                nostr_d_tag = row.nostrDTag,
                nostr_publish_via = row.nostrPublishVia,
                kilter_status = row.kilterStatus,
                kilter_synced_at = row.kilterSyncedAt,
                kilter_publish_via = row.kilterPublishVia,
                kilter_error = row.kilterError,
            )
            // Capture changes() BEFORE the COALESCE-fill UPDATE — that
            // UPDATE always reports 1 affected row for an existing
            // (and thus IGNORE-skipped) uuid, which would mask the
            // skip and falsely tick the "imported" counter. Reading
            // here pins changes() to the INSERT outcome (1 = fresh,
            // 0 = uuid already existed).
            val freshlyInserted = q.lastClimbsChangeCount().executeAsOne() > 0L
            q.restoreOwnClimbCoalesceFill(
                uuid = row.uuid,
                sync_status = row.syncStatus,
                created_by_pubkey = row.createdByPubkey,
                frames_hash = row.framesHash,
                nostr_event_id = row.nostrEventId,
                nostr_d_tag = row.nostrDTag,
                nostr_publish_via = row.nostrPublishVia,
                kilter_status = row.kilterStatus,
                kilter_synced_at = row.kilterSyncedAt,
                kilter_publish_via = row.kilterPublishVia,
                kilter_error = row.kilterError,
            )
            freshlyInserted
        }

    override fun restoreOwnClimbStat(row: OwnClimbStatBackupRow) {
        // Use the generic upsertClimbStat — it preserves every column the
        // backup carries. The local-only `upsertLocalClimbStat` hardcodes
        // quality_average / ascensionist_count / benchmark_difficulty to
        // sentinels and would silently drop those fields on restore.
        // fa_username / fa_at / official_kilter_difficulty are NULL by
        // construction for own-climb stats (only the Blossom catalog
        // populates them).
        q.upsertClimbStat(
            climb_uuid = row.climbUuid,
            angle = row.angle,
            display_difficulty = row.displayDifficulty,
            difficulty_average = row.difficultyAverage,
            quality_average = row.qualityAverage,
            ascensionist_count = row.ascensionistCount,
            benchmark_difficulty = row.benchmarkDifficulty,
            fa_username = null,
            fa_at = null,
            official_kilter_difficulty = null,
        )
    }

    private fun com.cruxcoach.db.board.Climbs.toCommunityRow(): CommunityClimbRow =
        CommunityClimbRow(
            uuid = uuid,
            name = name,
            setterUsername = setter_username,
            description = description,
            framesText = frames,
            source = source,
            syncStatus = sync_status,
            createdByPubkey = created_by_pubkey,
            nostrEventId = nostr_event_id,
            nostrDTag = nostr_d_tag,
            framesHash = frames_hash,
            createdAt = created_at,
            moveCount = move_count,
            kilterSyncedAt = kilter_synced_at,
            layoutId = layout_id,
        )
}
