package com.cruxcoach.domain.board

/**
 * Shiny Wall Standard variants (`st0`..`st5`).
 *
 * Each variant is a distinct wall with its own hold numbering `0 until
 * holdCount`; a catalogue problem carries a separate hold list per variant,
 * so CruxCoach stores one climb row per (problem, variant), like MoonBoard.
 * The Full variants extend their base wall (st1 → st4, st2 → st5) and often
 * reuse its numbers, but the catalogue lists them separately, so they stay
 * separate layouts here too.
 *
 * App-side ids sit in their own ranges (layouts 9301..9306, product sizes
 * 9401..9406), outside every Kilter, Aurora, MoonBoard and Quantum id. The
 * `placements` table is keyed by (brand, placement_id) without a layout, so a
 * hold's placement id carries its variant: `slot * 1_000_000 + index`.
 *
 * [holdCount] is the length of the controller's state vector, from the
 * derived hold table that also ships as the bundled geometry
 * (scripts/build_shinywall_geometry.py): it includes placeholder indices
 * without a hold (always sent as off). Standard, Full and Max Full are
 * certain; Max may be one longer than the catalogue proves (475 used); the
 * Mini's small-hold rows are inferred. The wall is cleared before every
 * route, so a length that differs slightly leaves holds dark rather than
 * lighting wrong ones.
 */
enum class ShinyWallVariant(
    val code: String,
    val displayName: String,
    val layoutId: Long,
    val productSizeId: Long,
    val columns: Int,
    val rows: Int,
    val holdCount: Int,
    /** Variant code in the controller's advertised name (`ShinyWall_STP_…`). */
    val advertisedCode: String?,
    /** False while the variant has no catalogue content or verified layout. */
    val supported: Boolean,
) {
    ST0("st0", "Standard Mini", 9301, 9401, 12, 29, 249, "ST0", supported = true),
    ST1("st1", "Standard", 9302, 9402, 12, 35, 311, "STP", supported = true),
    ST2("st2", "Standard Max", 9303, 9403, 18, 35, 476, "STM", supported = true),
    // No catalogue problem uses st3 and its layout is unconfirmed.
    ST3("st3", "Standard Mini Full", 9304, 9404, 12, 29, 0, null, supported = false),
    ST4("st4", "Standard Full", 9305, 9405, 12, 35, 401, "STF", supported = true),
    ST5("st5", "Standard Max Full", 9306, 9406, 18, 35, 611, "STMF", supported = true);

    /** Wall angles the catalogue sets problems at: 0..60 degrees in 5 degree steps. */
    val angles: List<Int> get() = ANGLES

    private val slot: Int get() = (layoutId - LAYOUT_BASE).toInt()

    /** Placement id stored in frames and `placements` for hold [index]. */
    fun placementId(index: Int): Int {
        require(index in 0 until holdCount) { "Hold $index is not on $code (holds 0..${holdCount - 1})" }
        return slot * PLACEMENT_STRIDE + index
    }

    /** Hold number on this variant, or null when [placementId] belongs elsewhere. */
    fun indexOf(placementId: Int): Int? {
        if (placementId / PLACEMENT_STRIDE != slot) return null
        val index = placementId % PLACEMENT_STRIDE
        return index.takeIf { it in 0 until holdCount }
    }

    companion object {
        const val LAYOUT_BASE = 9300L
        const val PLACEMENT_STRIDE = 1_000_000
        val ANGLES: List<Int> = (0..60 step 5).toList()

        val SUPPORTED: List<ShinyWallVariant> get() = entries.filter { it.supported }

        fun fromCode(code: String?): ShinyWallVariant? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }

        fun fromLayoutId(layoutId: Long): ShinyWallVariant? =
            entries.firstOrNull { it.layoutId == layoutId }

        fun fromProductSizeId(productSizeId: Long): ShinyWallVariant? =
            entries.firstOrNull { it.productSizeId == productSizeId }

        /** The variant a stored placement id belongs to. */
        fun fromPlacementId(placementId: Int): ShinyWallVariant? =
            entries.firstOrNull { it.indexOf(placementId) != null }

        fun fromBoardSelection(layoutId: Long, boardBrand: BoardBrand): ShinyWallVariant? =
            if (boardBrand == BoardBrand.SHINYWALL) fromLayoutId(layoutId) else null
    }
}

/**
 * Advertised controller names.
 *
 * Standard controllers advertise `ShinyWall_<code>_<id>` (`STP` Standard,
 * `STF` Full, `STM` Max, `STMF` Max Full) and the Mini `Shiny Wall ST0 …`.
 * The code is only a hint: some walls advertise a code that differs from
 * their configured variant, so the user's variant choice wins. `CTM` (custom
 * walls) and names without a code are not Standard walls and are not offered.
 */
object ShinyWallName {
    data class Parsed(val serial: String, val variantHint: ShinyWallVariant?)

    private val UNDERSCORE = Regex("""^ShinyWall_([A-Z0-9]+)_(\S+)$""")
    private val SPACED = Regex("""^Shiny Wall (ST\d)\b.*$""")

    fun parse(name: String): Parsed? {
        UNDERSCORE.matchEntire(name)?.let { m ->
            val code = m.groupValues[1]
            if (!code.startsWith("ST")) return null
            return Parsed(serial = m.groupValues[2], variantHint = hint(code))
        }
        SPACED.matchEntire(name)?.let { m ->
            return Parsed(serial = "", variantHint = hint(m.groupValues[1]))
        }
        return null
    }

    private fun hint(code: String): ShinyWallVariant? =
        ShinyWallVariant.entries.firstOrNull { it.supported && it.advertisedCode == code }
}

/**
 * Shiny Wall controller messages (ASCII, `<command><payload>.`).
 *
 * - `-` clears the wall.
 * - A route is sent as `-` followed by `+<d0><d1>…<dN>.`: one digit per
 *   physical hold, no separators: 0 off, 2 start, 4 hand, 6 foot, 8 top
 *   (e.g. 313 bytes for a Standard wall with 311 holds).
 *
 * CruxCoach frames keep the shared role ids (12 start, 13 hand, 14 top,
 * 15 foot); they are translated to controller codes only here. Storing the
 * controller codes would collide with the Aurora role ids 2/4/6/8 that
 * [HoldRole.roleClass] folds onto hand/foot.
 */
object ShinyWallFrameEncoder {
    const val CLEAR: String = "-"

    const val CODE_OFF = 0
    const val CODE_START = 2
    const val CODE_HAND = 4
    const val CODE_FOOT = 6
    const val CODE_TOP = 8

    /** GATT write overhead the controller's own app leaves per chunk. */
    const val CHUNK_OVERHEAD = 5

    /** Default ATT MTU (23) minus the overhead. */
    const val MIN_CHUNK_BYTES = 18

    fun roleCode(roleId: Int): Int = when (HoldRole.roleClass(roleId)) {
        HoldRole.START -> CODE_START
        HoldRole.HAND -> CODE_HAND
        HoldRole.FOOT -> CODE_FOOT
        HoldRole.FINISH -> CODE_TOP
        else -> CODE_OFF
    }

    /**
     * One code per physical hold of [variant], or null when any hold does not
     * belong to the variant or has an unknown role. Failing closed keeps a
     * mismatched board choice from lighting a partial or shifted route.
     * A hold listed twice keeps its first role.
     */
    fun stateVector(holds: List<BoardHold>, variant: ShinyWallVariant): IntArray? {
        if (!variant.supported || variant.holdCount <= 0) return null
        val codes = IntArray(variant.holdCount)
        for (hold in holds) {
            val index = variant.indexOf(hold.placementId) ?: return null
            val code = roleCode(hold.roleId)
            if (code == CODE_OFF) return null
            if (codes[index] == CODE_OFF) codes[index] = code
        }
        return codes
    }

    const val ROUTE_PREFIX: String = "+"
    const val MESSAGE_END: String = "."

    /** The route message, or null when [stateVector] refuses the holds. */
    fun encodeRoute(holds: List<BoardHold>, variant: ShinyWallVariant): String? =
        stateVector(holds, variant)?.joinToString(separator = "", prefix = ROUTE_PREFIX, postfix = MESSAGE_END)

    /** Role digit of a single-hold change (`*<role><hold>.`): 0 off, 1 start,
     *  2 hand, 3 foot, 4 top — unlike the state vector's 0/2/4/6/8. */
    fun changeRole(code: Int): Int = when (code) {
        CODE_START -> 1
        CODE_HAND -> 2
        CODE_FOOT -> 3
        CODE_TOP -> 4
        else -> 0
    }

    /**
     * The holds that differ between two state vectors of one wall, as one
     * change message `*<role><hold>/<role><hold>/….` (a hold going dark is
     * sent with role 0). The rest of the wall stays as it is, so nothing goes
     * dark in between — the controller's own app updates its editor and games
     * this way. Null when nothing changed or the vectors do not match.
     */
    fun encodeChanges(shown: IntArray, target: IntArray): String? {
        if (shown.size != target.size) return null
        val changes = target.indices.filter { shown[it] != target[it] }
        if (changes.isEmpty()) return null
        return changes.joinToString(separator = "/", prefix = "*", postfix = MESSAGE_END) {
            "${changeRole(target[it])}$it"
        }
    }

    /** Above this many changed holds a full route is shorter and resets any drift. */
    const val MAX_CHANGES_PER_MESSAGE = 48

    /** True when [chunk] starts a new message (clear, route, single hold, foot init),
     *  as opposed to continuing a route's digits. */
    fun startsMessage(chunk: ByteArray): Boolean =
        chunk.isNotEmpty() && chunk[0].toInt().toChar() in MESSAGE_STARTS

    private const val MESSAGE_STARTS = "-+*f"

    /** Splits [message] into GATT writes for the negotiated [mtu]. */
    fun chunks(message: String, mtu: Int): List<ByteArray> {
        val size = (mtu - CHUNK_OVERHEAD).coerceAtLeast(MIN_CHUNK_BYTES)
        return message.encodeToByteArray().toList().chunked(size).map { it.toByteArray() }
    }

    /** Clearing the wall: one write. */
    fun clearWrites(mtu: Int): List<ByteArray> = chunks(CLEAR, mtu)

    /**
     * All writes for showing a route: the clear, then the state vector, as the
     * controller's own app sends it. Null when [encodeRoute] refuses the holds.
     */
    fun routeWrites(holds: List<BoardHold>, variant: ShinyWallVariant, mtu: Int): List<ByteArray>? =
        encodeRoute(holds, variant)?.let { clearWrites(mtu) + chunks(it, mtu) }

    /**
     * Lights the holds at [placementIds] (all on one variant) in the hand
     * colour, e.g. for hold cleaning. Null for an empty set, mixed variants or
     * ids that belong to no variant.
     */
    fun highlightWrites(placementIds: Set<Int>, mtu: Int): List<ByteArray>? {
        val first = placementIds.firstOrNull() ?: return null
        val variant = ShinyWallVariant.fromPlacementId(first) ?: return null
        return routeWrites(placementIds.sorted().map { BoardHold(it, HoldRole.HAND) }, variant, mtu)
    }
}

/**
 * Maps Shiny Wall catalogue values onto CruxCoach's shared scales.
 *
 * Grades: Shiny publishes French/Font grades `4`, `4+`, `5`, `5+`, `6A` …
 * `8C+` plus `PROJECT` and `JOKER`. From `6A` they line up with the shared
 * difficulty scale (6A = 16 … 8C+ = 33, see [KilterGradeMapper]); the plain
 * lower grades take the middle of their Font band (4 → 4b, 4+ → 4c, 5 → 5b,
 * 5+ → 5c). `PROJECT`/`JOKER` have no grade.
 *
 * Statistics: `avgGrade` is the mean grade vote counted 0, 1, 2 … along
 * [GRADES]; `avgPunct` is the mean quality vote on 1..5 with 0 meaning
 * unrated, converted to CruxCoach's 1..3 stars.
 */
object ShinyWallCatalogue {
    val GRADES: List<String> = listOf(
        "4", "4+", "5", "5+",
        "6A", "6A+", "6B", "6B+", "6C", "6C+",
        "7A", "7A+", "7B", "7B+", "7C", "7C+",
        "8A", "8A+", "8B", "8B+", "8C", "8C+",
    )

    private val DIFFICULTIES: List<Int> = listOf(
        11, 12, 14, 15,
        16, 17, 18, 19, 20, 21,
        22, 23, 24, 25, 26, 27,
        28, 29, 30, 31, 32, 33,
    )

    /** Shared difficulty (10..34) for a published grade, or null when ungraded. */
    fun difficultyForGrade(grade: String?): Int? {
        val normalized = grade?.trim()?.uppercase()?.replace(" ", "") ?: return null
        val index = GRADES.indexOf(normalized)
        return if (index < 0) null else DIFFICULTIES[index]
    }

    /** Shared difficulty for a mean grade vote, interpolated between neighbouring grades. */
    fun difficultyForAverageVote(avgGrade: Double?): Double? {
        if (avgGrade == null || avgGrade.isNaN() || avgGrade.isInfinite() || avgGrade < 0) return null
        val clamped = avgGrade.coerceAtMost((GRADES.size - 1).toDouble())
        val low = clamped.toInt()
        val high = (low + 1).coerceAtMost(DIFFICULTIES.lastIndex)
        val fraction = clamped - low
        return DIFFICULTIES[low] + (DIFFICULTIES[high] - DIFFICULTIES[low]) * fraction
    }

    /** CruxCoach quality (1..3 stars) for a Shiny quality mean (1..5, 0 = unrated). */
    fun qualityForAverageVote(avgPunct: Double?): Double? {
        if (avgPunct == null || avgPunct.isNaN() || avgPunct.isInfinite() || avgPunct < 1.0) return null
        return 1.0 + (avgPunct.coerceAtMost(5.0) - 1.0) / 2.0
    }

    enum class Role(val wire: String, val roleId: Int) {
        START("start", HoldRole.START),
        HAND("hand", HoldRole.HAND),
        FOOT("foot", HoldRole.FOOT),
        TOP("top", HoldRole.FINISH);

        companion object {
            fun fromWire(value: String): Role? = entries.firstOrNull { it.wire == value }
        }
    }

    /**
     * CruxCoach frames for one route on [variant]: start, hand, foot, top in
     * that order, each by ascending hold number. Null when a hold number is
     * not on the variant or a role is unknown, so a corrupt row is skipped
     * instead of stored with wrong holds.
     */
    fun frames(holds: List<Pair<Int, String>>, variant: ShinyWallVariant): String? {
        val parsed = holds.map { (index, role) ->
            val r = Role.fromWire(role) ?: return null
            if (index !in 0 until variant.holdCount) return null
            r to index
        }
        return parsed.distinctBy { it.second }
            .sortedWith(compareBy({ it.first.ordinal }, { it.second }))
            .joinToString("") { (role, index) -> "p${variant.placementId(index)}r${role.roleId}" }
    }
}

/**
 * Categories and rules a Shiny Wall catalogue route carries: the five style
 * categories (in the catalogue's `style` order) and the two foot rules.
 *
 * Stored in `climbs.hsm` as the bits of the tags a route LACKS, so the
 * browser's existing `(hsm & mask) = 0` predicate keeps exactly the routes
 * that have every selected tag (Quantum stores its rules the same way). The
 * bits sit above Quantum's, so a filter mask carried over from the other
 * family can never exclude a route here. A catalogue route always lacks one
 * of the two contradictory foot rules, so an all-clear tag field means "no
 * tag information" (own and community climbs), not "every tag".
 */
enum class ShinyWallTag(val bit: Long) {
    TECHNICAL(1L shl 8),
    PHYSICAL(1L shl 9),
    CRIMPY(1L shl 10),
    ENDURANCE(1L shl 11),
    CRUXY(1L shl 12),

    /** Hand holds may not be used as footholds. */
    ONLY_HANDS(1L shl 13),

    /** Any hold on the wall may be used as a foothold. */
    OPEN_FEET(1L shl 14);

    companion object {
        /** Style categories in the catalogue's `style` flag order. */
        val STYLES: List<ShinyWallTag> = listOf(TECHNICAL, PHYSICAL, CRIMPY, ENDURANCE, CRUXY)

        val ALL_BITS: Long = entries.fold(0L) { acc, tag -> acc or tag.bit }

        fun fromStyleMask(styleMask: Int): Set<ShinyWallTag> =
            STYLES.filterIndexedTo(mutableSetOf()) { index, _ -> (styleMask shr index) and 1 == 1 }

        /** The `hsm` value for a catalogue route with [present] tags. */
        fun missingMask(present: Set<ShinyWallTag>): Long =
            entries.filter { it !in present }.fold(0L) { acc, tag -> acc or tag.bit }

        /** Tags of a stored climb; empty when it carries no tag information. */
        fun presentTags(hsm: Long): Set<ShinyWallTag> =
            if (hsm and ALL_BITS == 0L) emptySet()
            else entries.filterTo(mutableSetOf()) { hsm and it.bit == 0L }
    }
}
