package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.ClimbStatusFilter
import com.cruxcoach.app.browse.OriginFilter
import com.cruxcoach.app.logbook.BoardStatsComputer
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.app.playlist.GeneratorPresenter
import com.cruxcoach.app.playlist.GeneratorState
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.domain.playlist.CandidateSelection
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.domain.playlist.PlanSection
import com.cruxcoach.domain.playlist.PlanSlot
import com.cruxcoach.domain.playlist.PyramidShape
import com.cruxcoach.domain.playlist.SessionPosition
import com.cruxcoach.domain.playlist.TrainingRanges
import com.cruxcoach.domain.playlist.structureRange

/** One row of the live plan preview: a climb slot or an explicit rest. */
class PlanPreviewRowUi(
    val isRest: Boolean,
    val restSeconds: Int,
    /** warmUp | main | peak | descent */
    val sectionCode: String,
    /** "6b–6c" for a climb slot, empty for a rest. */
    val gradeLabel: String,
    /** Slots sharing a non-zero key are the same climb repeated. */
    val repeatKey: Int,
)

class GeneratorScreenState(
    /** pyramid | powerEndurance | volume | limit | projecting | manual */
    val typeCode: String,
    val durationMinutes: Int,
    /** new | projects | all */
    val selectionCode: String,
    /** ascending | upAndDown */
    val pyramidShapeCode: String,
    val pyramidClimbsPerTier: Int,
    /** startCold | warmedUp | endOfSession */
    val positionCode: String,
    /** What the size control counts, and its bounds for this type. */
    val structureSize: Int,
    val structureMin: Int,
    val structureMax: Int,
    /** problems | projects | sets | tiers */
    val structureUnitCode: String,
    val targetMinDifficulty: Double,
    val targetMaxDifficulty: Double,
    val targetRangeLabel: String,
    val gradeRangeCustomized: Boolean,
    val manualMinDifficulty: Double,
    val manualMaxDifficulty: Double,
    val manualRangeLabel: String,
    val manualRepeats: Int,
    val manualRestSeconds: Int,
    val manualRepeatRestSeconds: Int,
    val problemsPerSet: Int,
    val angle: Int,
    val angleAdjustable: Boolean,
    val boardWire: String,
    val estimatedMinutes: Int,
    val plan: List<PlanPreviewRowUi>,
    val climbCount: Int,
    /** Empty when the logbook is too thin to name a grade. */
    val maxGradeLabel: String,
    val flashGradeLabel: String,
    /** False = the ~V5 default drove the plan, and the screen must say so. */
    val profilePersonalized: Boolean,
    /** Non-null on the plan when a max-effort type was downgraded. */
    val downgradedFromTypeCode: String,
    val isGenerating: Boolean,
    /** 0 until a list was created. */
    val createdListId: Long,
    val droppedClimbs: Int,
    val failed: Boolean,
    /** Difficulty bounds the grade controls may move in. */
    val minDifficulty: Double,
    val maxDifficulty: Double,
    /** Label per whole difficulty point from [minDifficulty] upwards. */
    val gradeLabels: List<String>,
    /** Android "Minimum ascents": 0 = all. */
    val minAscensionists: Int,
    /** Android's thresholds plus whatever value came in from the board browser. */
    val minAscentOptions: List<Int>,
    /** "More filters" (Android's sheet): benchmarks only, status, origin, type. */
    val benchmarkOnly: Boolean,
    /** all | new | unsent | sent; empty for a browser status mix the sheet has no chip for. */
    val statusCode: String,
    /** all | cruxcoach | kilter | boardsesh */
    val originCode: String,
    /** boulder | route | all */
    val climbTypeCode: String,
    /** The restrictions narrowing the candidates now, in Android's order: benchmark, then status/origin/type codes. */
    val activeFilterCodes: List<String>,
)

internal object GeneratorCodes {
    fun type(value: GeneratorType): String = when (value) {
        GeneratorType.PYRAMID -> "pyramid"
        GeneratorType.POWER_ENDURANCE -> "powerEndurance"
        GeneratorType.VOLUME -> "volume"
        GeneratorType.LIMIT -> "limit"
        GeneratorType.PROJECTING -> "projecting"
        GeneratorType.MANUAL -> "manual"
    }

    fun type(code: String): GeneratorType? = when (code) {
        "pyramid" -> GeneratorType.PYRAMID
        "powerEndurance" -> GeneratorType.POWER_ENDURANCE
        "volume" -> GeneratorType.VOLUME
        "limit" -> GeneratorType.LIMIT
        "projecting" -> GeneratorType.PROJECTING
        "manual" -> GeneratorType.MANUAL
        else -> null
    }

    /** What one step of the size control means for this type. */
    fun structureUnit(value: GeneratorType): String = when (value) {
        GeneratorType.PYRAMID -> "tiers"
        GeneratorType.POWER_ENDURANCE -> "sets"
        GeneratorType.PROJECTING -> "projects"
        GeneratorType.VOLUME, GeneratorType.LIMIT, GeneratorType.MANUAL -> "problems"
    }

    fun position(value: SessionPosition): String = when (value) {
        SessionPosition.START_COLD -> "startCold"
        SessionPosition.WARMED_UP -> "warmedUp"
        SessionPosition.END_OF_SESSION -> "endOfSession"
    }

    fun position(code: String): SessionPosition? = when (code) {
        "startCold" -> SessionPosition.START_COLD
        "warmedUp" -> SessionPosition.WARMED_UP
        "endOfSession" -> SessionPosition.END_OF_SESSION
        else -> null
    }

    fun selection(value: CandidateSelection): String = when (value) {
        CandidateSelection.NEW -> "new"
        CandidateSelection.PROJECTS -> "projects"
        CandidateSelection.ALL -> "all"
    }

    fun selection(code: String): CandidateSelection? = when (code) {
        "new" -> CandidateSelection.NEW
        "projects" -> CandidateSelection.PROJECTS
        "all" -> CandidateSelection.ALL
        else -> null
    }

    fun shape(value: PyramidShape): String = when (value) {
        PyramidShape.ASCENDING -> "ascending"
        PyramidShape.UP_AND_DOWN -> "upAndDown"
    }

    fun shape(code: String): PyramidShape? = when (code) {
        "ascending" -> PyramidShape.ASCENDING
        "upAndDown" -> PyramidShape.UP_AND_DOWN
        else -> null
    }

    fun section(value: PlanSection): String = when (value) {
        PlanSection.WARM_UP -> "warmUp"
        PlanSection.MAIN -> "main"
        PlanSection.PEAK -> "peak"
        PlanSection.DESCENT -> "descent"
    }
}

/**
 * Swift-facing playlist generator. The plan preview is recomputed by the
 * presenter on every change, so the screen only mirrors state and forwards
 * the controls.
 */
class GeneratorScreenModel(private val presenter: GeneratorPresenter) {

    val currentState: GeneratorScreenState get() = map(presenter.state.value)

    fun watch(onState: (GeneratorScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun setTypeCode(code: String) {
        GeneratorCodes.type(code)?.let { presenter.setType(it) }
    }

    fun setPositionCode(code: String) {
        GeneratorCodes.position(code)?.let { presenter.setPosition(it) }
    }

    fun setSelectionCode(code: String) {
        GeneratorCodes.selection(code)?.let { presenter.setSelection(it) }
    }

    fun setPyramidShapeCode(code: String) {
        GeneratorCodes.shape(code)?.let { presenter.setPyramidShape(it) }
    }

    fun setStructureSize(size: Int) = presenter.setStructureSize(size)
    fun setPyramidClimbsPerTier(count: Int) = presenter.setPyramidClimbsPerTier(count)
    fun setDuration(minutes: Int) = presenter.setDuration(minutes)
    fun setTargetRange(low: Double, high: Double) = presenter.setTargetRange(low, high)
    fun useRecommendedRange() = presenter.useRecommendedRange()
    fun setManualRange(low: Double, high: Double) = presenter.setManualRange(low, high)
    fun setManualRepeats(repeats: Int) = presenter.setManualRepeats(repeats)
    fun setManualRest(seconds: Int) = presenter.setManualRest(seconds)
    fun setManualRepeatRest(seconds: Int) = presenter.setManualRepeatRest(seconds)
    fun setProblemsPerSet(count: Int) = presenter.setProblemsPerSet(count)
    fun setAngle(angle: Int) = presenter.setAngle(angle)
    fun generate(name: String) = presenter.generate(name)
    fun consumeCreatedList() = presenter.consumeCreatedList()
    fun close() = presenter.dispose()

    private fun map(state: GeneratorState): GeneratorScreenState {
        val scale = state.gradeScale
        val range = state.type.structureRange()
        val slots = state.plan?.slots.orEmpty()
        return GeneratorScreenState(
            typeCode = GeneratorCodes.type(state.type),
            durationMinutes = state.durationMinutes,
            selectionCode = GeneratorCodes.selection(state.selection),
            pyramidShapeCode = GeneratorCodes.shape(state.pyramidShape),
            pyramidClimbsPerTier = state.pyramidClimbsPerTier,
            positionCode = GeneratorCodes.position(state.position),
            structureSize = state.structureSize,
            structureMin = range.first,
            structureMax = range.last,
            structureUnitCode = GeneratorCodes.structureUnit(state.type),
            targetMinDifficulty = state.targetMinDifficulty ?: TrainingRanges.MIN_DIFFICULTY,
            targetMaxDifficulty = state.targetMaxDifficulty ?: TrainingRanges.MAX_DIFFICULTY,
            targetRangeLabel = band(
                state.targetMinDifficulty ?: TrainingRanges.MIN_DIFFICULTY,
                state.targetMaxDifficulty ?: TrainingRanges.MAX_DIFFICULTY,
                scale,
            ),
            gradeRangeCustomized = state.gradeRangeCustomized,
            manualMinDifficulty = state.manualMinDifficulty,
            manualMaxDifficulty = state.manualMaxDifficulty,
            manualRangeLabel = band(state.manualMinDifficulty, state.manualMaxDifficulty, scale),
            manualRepeats = state.manualRepeats,
            manualRestSeconds = state.manualRestSeconds,
            manualRepeatRestSeconds = state.manualRepeatRestSeconds,
            problemsPerSet = state.problemsPerSet,
            angle = state.angle,
            angleAdjustable = state.angleAdjustable,
            boardWire = state.boardBrand,
            estimatedMinutes = state.estimatedMinutes,
            plan = slots.map { slot ->
                when (slot) {
                    is PlanSlot.RestSlot -> PlanPreviewRowUi(
                        isRest = true,
                        restSeconds = slot.seconds,
                        sectionCode = GeneratorCodes.section(slot.section),
                        gradeLabel = "",
                        repeatKey = 0,
                    )
                    is PlanSlot.ClimbSlot -> PlanPreviewRowUi(
                        isRest = false,
                        restSeconds = 0,
                        sectionCode = GeneratorCodes.section(slot.section),
                        gradeLabel = band(slot.minDifficulty, slot.maxDifficulty, scale),
                        repeatKey = slot.repeatKey ?: 0,
                    )
                }
            },
            climbCount = slots.count { it is PlanSlot.ClimbSlot },
            maxGradeLabel = state.maxGradeLabel ?: "",
            flashGradeLabel = state.flashGradeLabel ?: "",
            profilePersonalized = state.profilePersonalized,
            downgradedFromTypeCode = state.plan?.downgradedFromType
                ?.let { GeneratorCodes.type(it) } ?: "",
            isGenerating = state.isGenerating,
            createdListId = state.createdListId ?: 0L,
            droppedClimbs = state.droppedClimbs,
            failed = state.error,
            minDifficulty = TrainingRanges.MIN_DIFFICULTY,
            maxDifficulty = TrainingRanges.MAX_DIFFICULTY,
            gradeLabels = gradeLabels(scale),
            minAscensionists = state.minAscensionists,
            minAscentOptions = (MIN_ASCENT_OPTIONS + state.minAscensionists).distinct().sorted(),
            benchmarkOnly = state.benchmarkOnly,
            statusCode = STATUS_OPTIONS.firstOrNull { it.second == state.statusFilter }?.first ?: "",
            originCode = ORIGIN_OPTIONS.firstOrNull { it.second == state.originFilter }?.first ?: "all",
            climbTypeCode = TYPE_OPTIONS.firstOrNull { it.second == state.climbType }?.first ?: "boulder",
            activeFilterCodes = activeFilters(state),
        )
    }

    /** Android `minAscentOptions` choice; clamped to 0…50 by the presenter. */
    fun setMinAscensionists(count: Int) = presenter.setMinAscensionists(count)
    fun setBenchmarkOnly(enabled: Boolean) = presenter.setBenchmarkOnly(enabled)

    /** all | new | unsent | sent; unknown codes are ignored. */
    fun setStatusCode(code: String) {
        STATUS_OPTIONS.firstOrNull { it.first == code }?.let { presenter.setStatusFilter(it.second) }
    }

    /** all | cruxcoach | kilter | boardsesh; unknown codes are ignored. */
    fun setOriginCode(code: String) {
        ORIGIN_OPTIONS.firstOrNull { it.first == code }?.let { presenter.setOriginFilter(it.second) }
    }

    /** boulder | route | all; unknown codes are ignored. */
    fun setClimbTypeCode(code: String) {
        TYPE_OPTIONS.firstOrNull { it.first == code }?.let { presenter.setClimbType(it.second) }
    }

    /** Android `activeFilterLabelRes`: what the "More filters" button names while it narrows the result. */
    private fun activeFilters(state: GeneratorState): List<String> = buildList {
        if (state.benchmarkOnly) add("benchmark")
        STATUS_OPTIONS.firstOrNull { it.second == state.statusFilter && it.second.isNotEmpty() }?.let { add(it.first) }
        ORIGIN_OPTIONS.firstOrNull { it.second == state.originFilter && it.second != OriginFilter.ALL }?.let { add(it.first) }
        TYPE_OPTIONS.firstOrNull { it.second == state.climbType && it.second != ClimbTypeFilter.BOULDER }?.let { add(it.first) }
    }

    /** Sheet option codes in Android's chip order. */
    val statusCodes: List<String> = STATUS_OPTIONS.map { it.first }
    val originCodes: List<String> = ORIGIN_OPTIONS.map { it.first }
    val climbTypeCodes: List<String> = TYPE_OPTIONS.map { it.first }

    private companion object {
        val MIN_ASCENT_OPTIONS = listOf(0, 5, 20, 100, 500)
        val STATUS_OPTIONS = listOf(
            "all" to emptySet<ClimbStatusFilter>(),
            "new" to setOf(ClimbStatusFilter.NEW),
            "unsent" to setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED),
            "sent" to setOf(ClimbStatusFilter.SENT),
        )
        val ORIGIN_OPTIONS = listOf(
            "all" to OriginFilter.ALL,
            "cruxcoach" to OriginFilter.CRUXCOACH,
            "kilter" to OriginFilter.KILTER,
            "boardsesh" to OriginFilter.BOARDSESH,
        )
        val TYPE_OPTIONS = listOf(
            "boulder" to ClimbTypeFilter.BOULDER,
            "route" to ClimbTypeFilter.ROUTE,
            "all" to ClimbTypeFilter.ALL,
        )
    }

    /** "6b" for a one-grade band, "6b–6c" otherwise. */
    private fun band(low: Double, high: Double, scale: GradeScale): String {
        val lowLabel = BoardStatsComputer.formatDifficulty(low, scale)
        val highLabel = BoardStatsComputer.formatDifficulty(high, scale)
        return if (lowLabel == highLabel) lowLabel else "$lowLabel–$highLabel"
    }

    private fun gradeLabels(scale: GradeScale): List<String> {
        val min = TrainingRanges.MIN_DIFFICULTY.toInt()
        val max = TrainingRanges.MAX_DIFFICULTY.toInt()
        return (min..max).map { BoardStatsComputer.formatDifficulty(it.toDouble(), scale) }
    }
}
