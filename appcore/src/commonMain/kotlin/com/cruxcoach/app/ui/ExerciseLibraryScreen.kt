package com.cruxcoach.app.ui

import com.cruxcoach.app.library.ExerciseLibraryPresenter
import com.cruxcoach.app.library.ExerciseLibraryState
import com.cruxcoach.app.library.LibraryKeys
import com.cruxcoach.app.library.LibraryPictograms
import com.cruxcoach.app.library.catalogLanguage
import com.cruxcoach.app.util.watch
import com.cruxcoach.athlete.catalog.CatalogFilter
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.InjuryAdvice
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Side
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** One stroke or fill of a pictogram on its 24-unit grid; see `LibraryPictoShape`. */
class PictoShapeUi(
    /** polyline | box | dot | curve */
    val kind: String,
    /** gear | figure | far */
    val role: String,
    val points: List<Double>,
    val width: Double,
    val radius: Double,
    val filled: Boolean,
)

/** An exercise's movement pictogram on a tile in its category's colour (Android `ExerciseThumb`). */
class PictogramUi(val code: String, val lightArgb: Long, val darkArgb: Long, val shapes: List<PictoShapeUi>)

class ExerciseRowUi(
    val slug: String,
    val name: String,
    val categoryKey: String,
    /** 1–5. */
    val difficulty: Int,
    val equipmentSummary: LibraryText,
    /** ok | avoid | caution | one_side_only */
    val verdictCode: String,
    /** "" when the injury filter has nothing to say. */
    val badgeKey: String,
    val favorite: Boolean,
    val excluded: Boolean,
    val custom: Boolean,
    val pictogram: PictogramUi,
)

class LibraryChoiceUi(val code: String, val labelKey: String, val selected: Boolean)

class ExerciseLibraryScreenState(
    val isLoading: Boolean,
    val failed: Boolean,
    val query: String,
    val categoryLabel: LibraryText,
    val anyCategory: Boolean,
    val categories: List<LibraryChoiceUi>,
    val mineSelected: Boolean,
    /** With several places, the filter names the place it uses (where the athlete trains today). */
    val mineLabel: LibraryText,
    /** Without equipment set up, the "my equipment" chip explains and offers the sheet. */
    val equipmentConfigured: Boolean,
    val withoutClimbing: Boolean,
    val fingerFree: Boolean,
    val favoritesOnly: Boolean,
    val injuryDefaultsApplied: Boolean,
    val count: Int,
    /** Favourites first: the exercises the athlete actually uses are one tap away. */
    val showSections: Boolean,
    val favoriteRows: List<ExerciseRowUi>,
    val rows: List<ExerciseRowUi>,
    /** Pick mode: one-shot "added" and "an injury rules it out". */
    val picked: Boolean,
    val pickRejected: Boolean,
    val equipmentSheetOpen: Boolean,
    val equipmentOptions: List<LibraryChoiceUi>,
)

internal object LibraryRows {
    private val pictograms: Map<String, PictogramUi> by lazy {
        com.cruxcoach.app.library.LibraryPictogram.entries.associate { p ->
            LibraryKeys.code(p.name) to PictogramUi(
                LibraryKeys.code(p.name), 0, 0,
                LibraryPictograms.shapes(p).map { PictoShapeUi(it.kind, it.role, it.points, it.width, it.radius, it.filled) },
            )
        }
    }

    fun pictogram(def: ExerciseDefinition): PictogramUi {
        val base = pictograms.getValue(LibraryKeys.code(LibraryPictograms.of(def).name))
        return PictogramUi(base.code, LibraryPictograms.color(def.category, dark = false),
            LibraryPictograms.color(def.category, dark = true), base.shapes)
    }

    /** Android `equipmentSummary`: what the exercise needs beyond the floor. */
    fun equipmentSummary(def: ExerciseDefinition): LibraryText {
        val needed = def.equipment.filterNot { it == EquipmentV2.NONE || it == EquipmentV2.MAT }
        return if (needed.isEmpty()) LibraryTexts.key(LibraryKeys.equipment(EquipmentV2.NONE))
        else LibraryTexts.join(needed.map { LibraryTexts.key(LibraryKeys.equipment(it)) }, ", ")
    }

    /** Android `InjuryBadge`'s text; "" for OK. */
    fun badgeKey(advice: InjuryAdvice): String = when (advice.verdict) {
        InjuryVerdict.OK -> ""
        InjuryVerdict.AVOID -> "trx_badge_avoid"
        InjuryVerdict.CAUTION -> "trx_badge_caution"
        InjuryVerdict.ONE_SIDE_ONLY -> if (advice.allowedSide == Side.LEFT) "trx_badge_only_left" else "trx_badge_only_right"
    }

    fun row(def: ExerciseDefinition, lang: String, advice: InjuryAdvice, favorite: Boolean, excluded: Boolean) = ExerciseRowUi(
        slug = def.slug,
        name = def.name(lang),
        categoryKey = LibraryKeys.category(def.category),
        difficulty = def.difficulty.coerceIn(1, 5),
        equipmentSummary = equipmentSummary(def),
        verdictCode = LibraryKeys.code(advice.verdict.name),
        badgeKey = badgeKey(advice),
        favorite = favorite,
        excluded = excluded,
        custom = def.custom,
        pictogram = pictogram(def),
    )
}

/**
 * The exercise library (Android `ExerciseCatalogScreen`): search, one category
 * chip with a menu, the equipment and safety filters, favourites on top. In
 * pick mode it adds the tapped exercise to an open training.
 */
class ExerciseLibraryScreenModel internal constructor(
    private val presenter: ExerciseLibraryPresenter,
    language: String,
) {
    private val lang = catalogLanguage(language)

    val currentState: ExerciseLibraryScreenState get() = map(presenter.state.value)

    fun watch(onState: (ExerciseLibraryScreenState) -> Unit): Subscription {
        val watch = presenter.state.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun setQuery(query: String) = presenter.setQuery(query)
    /** "" = all categories (clears the choice); otherwise toggles one. */
    fun toggleCategory(code: String) {
        if (code.isEmpty()) presenter.toggleCategory(null)
        else LibraryKeys.categoryOf(code)?.let { presenter.toggleCategory(it) }
    }
    fun toggleMine() = presenter.toggleMine()
    fun toggleWithoutClimbing() = presenter.toggleWithoutClimbing()
    fun toggleFingerFree() = presenter.toggleFingerFree()
    fun toggleFavoritesOnly() = presenter.toggleFavorites()
    fun toggleFavorite(slug: String) = presenter.toggleFavorite(slug)
    fun toggleExcluded(slug: String) = presenter.toggleExcluded(slug)

    fun openEquipmentSheet() = presenter.openEquipmentSheet()
    fun toggleEquipment(code: String) { LibraryKeys.equipmentOf(code)?.let { presenter.toggleEquipmentDraft(it) } }
    fun closeEquipmentSheet() = presenter.closeEquipmentSheet()

    /** Pick mode: adds [slug] to the open training [workoutId] (Android `pickForWorkout`). */
    fun pick(workoutId: String, slug: String) = presenter.pick(workoutId, slug)
    fun consumeEvents() = presenter.consumeEvents()
    fun close() = presenter.close()

    private fun today(): String = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    /** Android `todaysPlaceName`: only with several places does the filter name one. */
    private fun placeLabel(profile: AthleteProfile): LibraryText? {
        if (TrainingPlaces.of(profile).size < 2) return null
        val place = TrainingPlaces.current(profile, today()) ?: return null
        return when {
            place.id == TrainingPlaces.NO_GEAR_ID -> LibraryTexts.key("tro_no_gear")
            place.name != null -> LibraryTexts.literal(place.name!!)
            else -> LibraryTexts.key("tro_kind_" + LibraryKeys.code(place.kind.name))
        }
    }

    private fun categoryLabel(selected: Set<ExerciseCategoryV2>): LibraryText = when {
        selected.isEmpty() -> LibraryTexts.key("trx_filter_categories_all")
        selected.size <= 2 -> LibraryTexts.join(ExerciseCategoryV2.entries.filter { it in selected }.map { LibraryTexts.key(LibraryKeys.category(it)) }, ", ")
        else -> LibraryTexts.plural("trx_filter_categories_count", selected.size, LibraryTexts.int(selected.size))
    }

    private fun map(s: ExerciseLibraryState): ExerciseLibraryScreenState {
        val results = s.catalog.search(s.query, lang, s.filter)
        // Safety and equipment filters do not hide the athlete's own picks; the row
        // still shows the injury advice. Category chips do apply.
        val favs = if (s.query.isBlank() && !s.favoritesOnly) {
            s.catalog.search("", lang, CatalogFilter(categories = s.categories)).filter { it.slug in s.favorites }
        } else emptyList()
        val showSections = favs.isNotEmpty()
        val rest = if (showSections) results.filterNot { it.slug in s.favorites } else results
        fun row(def: ExerciseDefinition) = LibraryRows.row(def, lang, InjuryAdvisor.assess(def, s.injuries),
            def.slug in s.favorites, def.slug in s.profile.excludedExercises)
        val draft = s.equipmentDraft
        return ExerciseLibraryScreenState(
            isLoading = s.loading,
            failed = s.failed,
            query = s.query,
            categoryLabel = categoryLabel(s.categories),
            anyCategory = s.categories.isNotEmpty(),
            categories = ExerciseCategoryV2.entries.map { LibraryChoiceUi(LibraryKeys.code(it.name), LibraryKeys.category(it), it in s.categories) },
            mineSelected = s.mineOnly && s.profile.equipmentConfigured,
            mineLabel = placeLabel(s.profile) ?: LibraryTexts.key("trx_filter_mine"),
            equipmentConfigured = s.profile.equipmentConfigured,
            withoutClimbing = s.withoutClimbing,
            fingerFree = s.fingerFree,
            favoritesOnly = s.favoritesOnly,
            injuryDefaultsApplied = s.injuryDefaultsApplied,
            count = results.size,
            showSections = showSections,
            favoriteRows = favs.map { row(it) },
            rows = rest.map { row(it) },
            picked = s.picked,
            pickRejected = s.pickRejected,
            equipmentSheetOpen = draft != null,
            equipmentOptions = EquipmentV2.entries.filter { it != EquipmentV2.NONE && it != EquipmentV2.MAT }
                .map { LibraryChoiceUi(LibraryKeys.code(it.name), LibraryKeys.equipment(it), draft != null && it in draft) },
        )
    }
}
