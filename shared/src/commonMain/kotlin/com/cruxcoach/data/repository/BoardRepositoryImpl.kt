package com.cruxcoach.data.repository

import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.AuroraBoard

class BoardRepositoryImpl(
    private val database: BoardDatabase
) : BoardRepository {

    private val q = database.auroraBoardQueries

    // ── Row Mappers ────────────────────────────────────────────

    private fun mapClimb(
        uuid: String, layoutId: Long, setterUsername: String?,
        name: String, frames: String, framesCount: Long,
        difficultyAverage: Double?, qualityAverage: Double?,
        ascensionistCount: Long?, description: String,
        isNomatch: Long, framesPace: Long, hsm: Long,
        benchmarkDifficulty: Double = 0.0,
        faUsername: String? = null, faAt: String? = null,
        moveCount: Long = 0
    ) = AuroraClimbWithStats(
        uuid = uuid, layoutId = layoutId, setterUsername = setterUsername,
        name = name, frames = frames, framesCount = framesCount,
        difficultyAverage = difficultyAverage, qualityAverage = qualityAverage,
        ascensionistCount = ascensionistCount, description = description,
        isNomatch = isNomatch != 0L, framesPace = framesPace, hsm = hsm,
        benchmarkDifficulty = benchmarkDifficulty,
        faUsername = faUsername, faAt = faAt,
        storedMoveCount = moveCount
    )

    // ── Climb Queries ──────────────────────────────────────────

    /** Maps a climb_browse VIEW row to AuroraClimbWithStats (no frames in VIEW). */
    private fun mapBrowse(it: com.cruxcoach.db.board.Climb_browse) = mapClimb(
        it.uuid, it.layout_id, it.setter_username, it.name, "", it.frames_count,
        it.difficulty_average, it.quality_average, it.ascensionist_count,
        it.description, it.is_nomatch, it.frames_pace, it.hsm,
        benchmarkDifficulty = it.benchmark_difficulty ?: 0.0,
        moveCount = it.move_count
    )

    override fun searchClimbsByName(query: String, angle: Int, layoutId: Int, sortField: ClimbSortField, sortDirection: SortDirection, limit: Int, offset: Int, climbType: ClimbTypeFilter): List<AuroraClimbWithStats> {
        val lay = layoutId.toLong()
        val a = angle.toLong()
        val mn = climbType.minFrames()
        val mx = climbType.maxFrames()
        val l = limit.toLong()
        val o = offset.toLong()
        val desc = sortDirection == SortDirection.DESC

        return when (sortField) {
            ClimbSortField.QUALITY -> if (desc) q.searchByQualityDesc(lay, query, a, mn, mx, l, o) else q.searchByQualityAsc(lay, query, a, mn, mx, l, o)
            ClimbSortField.DIFFICULTY -> if (desc) q.searchByDifficultyDesc(lay, query, a, mn, mx, l, o) else q.searchByDifficultyAsc(lay, query, a, mn, mx, l, o)
            ClimbSortField.NAME -> if (desc) q.searchByNameDesc(lay, query, a, mn, mx, l, o) else q.searchByNameAsc(lay, query, a, mn, mx, l, o)
            else -> if (desc) q.searchByAscensionistsDesc(lay, query, a, mn, mx, l, o) else q.searchByAscensionistsAsc(lay, query, a, mn, mx, l, o)
        }.executeAsList().map { mapBrowse(it) }
    }

    override fun getClimbByUuid(uuid: String, angle: Int): AuroraClimbWithStats? {
        return q.getClimbByUuid(angle.toLong(), uuid).executeAsOneOrNull()?.let {
            mapClimb(it.uuid, it.layout_id, it.setter_username, it.name, it.frames, it.frames_count, it.difficulty_average, it.quality_average, it.ascensionist_count, it.description, it.is_nomatch, it.frames_pace, it.hsm, benchmarkDifficulty = it.benchmark_difficulty ?: 0.0, faUsername = it.fa_username, faAt = it.fa_at, moveCount = it.move_count)
        }
    }

    override fun searchClimbsSorted(
        angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int,
        sortField: ClimbSortField, sortDirection: SortDirection, limit: Int, offset: Int,
        climbType: ClimbTypeFilter
    ): List<AuroraClimbWithStats> {
        val lay = layoutId.toLong()
        val a = angle.toLong()
        val mn = climbType.minFrames()
        val mx = climbType.maxFrames()
        val asc = minAscensionists.toLong()
        val l = limit.toLong()
        val o = offset.toLong()
        val desc = sortDirection == SortDirection.DESC

        return when (sortField) {
            ClimbSortField.QUALITY -> if (desc) q.browseByQualityDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o) else q.browseByQualityAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o)
            ClimbSortField.DIFFICULTY -> if (desc) q.browseByDifficultyDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o) else q.browseByDifficultyAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o)
            ClimbSortField.NAME -> if (desc) q.browseByNameDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o) else q.browseByNameAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o)
            else -> if (desc) q.browseByAscensionistsDesc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o) else q.browseByAscensionistsAsc(lay, a, mn, mx, minDifficulty, maxDifficulty, asc, l, o)
        }.executeAsList().map { mapBrowse(it) }
    }

    override fun countFilteredClimbsFast(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int): Long {
        return q.countFilteredClimbsFast(layoutId.toLong(), angle.toLong(), minDifficulty, maxDifficulty, minAscensionists.toLong()).executeAsOne()
    }

    override fun countFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): Long {
        return q.countFilteredClimbs(layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(), minDifficulty, maxDifficulty, minAscensionists.toLong()).executeAsOne()
    }

    override fun countBenchmarkFilteredClimbs(angle: Int, layoutId: Int, minDifficulty: Double, maxDifficulty: Double, minAscensionists: Int, climbType: ClimbTypeFilter): Long {
        return q.countBenchmarkFilteredClimbs(layoutId.toLong(), angle.toLong(), climbType.minFrames(), climbType.maxFrames(), minDifficulty, maxDifficulty, minAscensionists.toLong()).executeAsOne()
    }

    override fun countSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter): Long {
        return q.countSearchClimbs(layoutId.toLong(), query, query, angle.toLong(), climbType.minFrames(), climbType.maxFrames()).executeAsOne()
    }

    override fun countBenchmarkSearchClimbs(query: String, angle: Int, layoutId: Int, climbType: ClimbTypeFilter): Long {
        return q.countBenchmarkSearchClimbs(layoutId.toLong(), query, query, angle.toLong(), climbType.minFrames(), climbType.maxFrames()).executeAsOne()
    }

    override fun getClimbCount(): Long {
        return q.countClimbs().executeAsOne()
    }

    override fun getStatCount(): Long {
        return q.countStats().executeAsOne()
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
    ): List<AuroraClimbWithStats> {
        if (uuids.isEmpty()) return emptyList()
        return q.getClimbsByUuids(
            layoutId.toLong(), uuids, angle.toLong(), climbType.minFrames(), climbType.maxFrames(),
            minDifficulty, maxDifficulty, minAscensionists.toLong()
        ).executeAsList().map { mapBrowse(it) }
    }

    override fun getClimbsByUuids(uuids: Collection<String>, angle: Int): List<AuroraClimbWithStats> {
        if (uuids.isEmpty()) return emptyList()
        return q.getClimbsByUuidsSimple(uuids, angle.toLong())
            .executeAsList().map { mapBrowse(it) }
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

    override fun getAllPlacements(): List<AuroraPlacement> {
        return q.getAllPlacements().executeAsList().map {
            AuroraPlacement(placementId = it.placement_id, holeId = it.hole_id, setId = it.set_id, x = it.x, y = it.y)
        }
    }

    override fun getProductSize(id: Int): BoardSize? {
        return q.getProductSize(id.toLong()).executeAsOneOrNull()?.let {
            BoardSize(it.id, it.product_id, it.name, it.edge_left, it.edge_right, it.edge_bottom, it.edge_top, it.image_filename)
        }
    }

    override fun getAllProductSizes(): List<BoardSize> {
        return q.getAllProductSizes(AuroraBoard.KILTER.productId).executeAsList().map {
            BoardSize(it.id, it.product_id, it.name, it.edge_left, it.edge_right, it.edge_bottom, it.edge_top, it.image_filename)
        }
    }

    override fun getBoardImages(productSizeId: Int, layoutId: Int): List<BoardImage> {
        return q.getBoardImages(productSizeId.toLong(), layoutId.toLong()).executeAsList().map {
            BoardImage(id = it.id, productSizeId = it.product_size_id, layoutId = it.layout_id, setId = it.set_id, imageFilename = it.image_filename)
        }
    }

    override fun getPlacementLedMap(productSizeId: Int): Map<Int, Int> {
        return q.getPlacementLedMap(productSizeId.toLong()).executeAsList().associate {
            it.placement_id.toInt() to it.led_position.toInt()
        }
    }

    override fun getMirrorPlacementMap(productSizeId: Int): Map<Int, Int> {
        return q.getMirrorPlacementMap(productSizeId.toLong()).executeAsList().associate {
            it.original_placement_id.toInt() to it.mirrored_placement_id.toInt()
        }
    }

    override fun countLeds(): Long {
        return q.countLeds().executeAsOne()
    }

    override fun getLedGrid(productSizeId: Int): List<LedGridPoint> {
        return q.getAllLedGrid(productSizeId.toLong()).executeAsList().map {
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
        q.upsertClimb(uuid, layoutId, setter, name, framesCount,
            isListed, edgeLeft, edgeRight, edgeBottom, edgeTop, createdAt,
            description, isNomatch, framesPace, hsm, frames, moveCount)
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

    override fun upsertHoldPosition(holeId: Long, productSizeId: Long, x: Long, y: Long, ledPosition: Long, placementId: Long) {
        q.upsertHoldPosition(holeId, productSizeId, x, y, ledPosition, placementId)
    }

    override fun upsertLed(holeId: Long, productSizeId: Long, position: Long) {
        q.upsertLed(holeId, productSizeId, position)
    }

    override fun upsertHole(id: Long, productSizeId: Long, x: Long, y: Long, mirroredHoleId: Long?) {
        q.upsertHole(id, productSizeId, x, y, mirroredHoleId)
    }

    override fun upsertPlacement(placementId: Long, holeId: Long, setId: Long, x: Long, y: Long) {
        q.upsertPlacement(placementId, holeId, setId, x, y)
    }

    override fun upsertProductSize(id: Long, productId: Long, name: String, edgeLeft: Long, edgeRight: Long, edgeBottom: Long, edgeTop: Long, imageFilename: String?) {
        q.upsertProductSize(id, productId, name, edgeLeft, edgeRight, edgeBottom, edgeTop, imageFilename)
    }

    override fun upsertBoardImage(id: Long, productSizeId: Long, layoutId: Long, setId: Long, imageFilename: String) {
        q.upsertBoardImage(id, productSizeId, layoutId, setId, imageFilename)
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
}
