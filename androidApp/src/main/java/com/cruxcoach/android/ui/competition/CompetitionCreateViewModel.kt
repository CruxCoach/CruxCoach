package com.cruxcoach.android.ui.competition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.competition.CompetitionHostPublisher
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.competition.CompetitionProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@HiltViewModel
class CompetitionCreateViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val publisher: CompetitionHostPublisher,
    private val signer: NostrSigner,
    private val preferences: UserPreferences,
    private val boards: BoardRepository,
) : ViewModel() {
    @Serializable
    data class Draft(
        val title: String = "", val summary: String = "", val venue: String = "",
        val address: String = "", val organizer: String = "", val contact: String = "",
        val boardBrand: String = "kilter", val boardModel: String = "Kilter Board",
        val boardSize: String = "", val layoutId: String = "1", val angle: String = "40",
        val startsInHours: String = "24", val durationHours: String = "3",
        val timezone: String = "", val registrationOpensAt: String = "",
        val registrationClosesAt: String = "", val checkinOpensAt: String = "",
        val checkinClosesAt: String = "", val startsAt: String = "", val endsAt: String = "",
        val capacity: String = "20", val division: String = "Open",
        val divisions: String = "", val feeSats: String = "0", val feeLnurl: String = "",
        val prizes: String = "", val prizeClaimDays: String = "30",
        val attempts: String = "5", val turnSeconds: String = "180",
        val climbSource: String = "organizer_set", val climbCount: String = "1",
        val countedClimbCount: String = "1", val scoring: String = "tops_then_attempts",
        val progression: String = "asynchronous_turns", val selectionUniqueness: String = "none",
        val zonePoints: String = "10", val topPoints: String = "25", val flashPoints: String = "5",
        val climbs: String = "", val rulesText: String = "", val eligibility: String = "",
        val waiver: String = "", val participantInstructions: String = "",
        val spectatorInfo: String = "", val refundPolicy: String = "",
        val relays: String = "wss://relay.damus.io\nwss://nos.lol",
        val public: Boolean = true, val waiverRequired: Boolean = false,
        val waitlistEnabled: Boolean = true, val lateEntryAllowed: Boolean = false,
    )
    sealed interface State {
        @Serializable
        data class Editing(val draft: Draft = Draft(), val step: Int = 0, val error: String? = null) : State
        data object Publishing : State
        data class Created(val organizerPubkey: String, val compId: String) : State
    }
    private val hadSavedDraft = savedStateHandle.get<String>(DRAFT_KEY) != null
    private val _state = MutableStateFlow<State>(restoreEditing().let { editing ->
        editing.copy(draft = CompetitionCreateDraftPolicy.withDefaultSchedule(editing.draft))
    })
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        if (!hadSavedDraft) viewModelScope.launch {
            val prefs = preferences.getBoardFilterSnapshot()
            val size = preferences.boardProductSizeId.first()
            val sizeName = withContext(Dispatchers.IO) { boards.getProductSize(size, prefs.boardBrand)?.name }.orEmpty()
            edit { it.copy(
                boardBrand = prefs.boardBrand,
                boardModel = if (prefs.boardBrand == "moonboard") "MoonBoard" else "Kilter Board",
                boardSize = sizeName.ifBlank { if (prefs.boardBrand == "moonboard") "standard" else "12x12" },
                layoutId = prefs.layoutId.toString(), angle = prefs.angle.toString(),
            ) }
        }
    }

    fun edit(change: (Draft) -> Draft) = updateEditing {
        val editing = it as? State.Editing ?: return@updateEditing it
        editing.copy(draft = change(editing.draft), error = null)
    }
    fun next() = updateEditing {
        val editing = it as? State.Editing ?: return@updateEditing it
        editing.copy(step = (editing.step + 1).coerceAtMost(3), error = CompetitionCreateDraftPolicy.validateStep(editing.draft, editing.step))
            .let { next -> if (next.error == null) next else editing.copy(error = next.error) }
    }
    fun back() = updateEditing {
        val editing = it as? State.Editing ?: return@updateEditing it
        editing.copy(step = (editing.step - 1).coerceAtLeast(0), error = null)
    }

    fun discardDraft() {
        savedStateHandle.remove<String>(DRAFT_KEY)
        _state.value = State.Editing()
    }

    fun publish() {
        val editing = _state.value as? State.Editing ?: return
        val config = runCatching { buildConfig(editing.draft) }.getOrElse {
            _state.value = editing.copy(error = it.message); return
        }
        _state.value = State.Publishing
        viewModelScope.launch {
            when (val result = publisher.create(config.second)) {
                is CompetitionHostPublisher.Result.Published ->
                    _state.value = State.Created(signer.getPublicKeyHex(), config.first).also {
                        savedStateHandle.remove<String>(DRAFT_KEY)
                    }
                is CompetitionHostPublisher.Result.Failed ->
                    _state.value = editing.copy(error = result.reason)
            }
        }
    }

    private fun updateEditing(change: (State) -> State) {
        _state.update(change)
        (_state.value as? State.Editing)?.let { editing ->
            savedStateHandle[DRAFT_KEY] = Json.encodeToString(editing)
        }
    }

    private fun restoreEditing(): State.Editing = savedStateHandle.get<String>(DRAFT_KEY)
        ?.let { encoded -> runCatching { Json.decodeFromString<State.Editing>(encoded) }.getOrNull() }
        ?: State.Editing()

    private fun buildConfig(d: Draft): Pair<String, JsonObject> {
        for (step in 0..3) require(CompetitionCreateDraftPolicy.validateStep(d, step) == null) {
            CompetitionCreateDraftPolicy.validateStep(d, step).orEmpty()
        }
        val compId = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis() / 1000
        val schedule = requireNotNull(CompetitionCreateDraftPolicy.schedule(d)) { "invalid_schedule" }
        val climbs = CompetitionCreateDraftPolicy.parseClimbs(d)
        val divisions = requireNotNull(CompetitionCreateDraftPolicy.parseDivisions(d)) { "invalid_divisions" }
        val prizes = requireNotNull(CompetitionCreateDraftPolicy.parsePrizes(d, divisions)) { "invalid_prizes" }
        val feeMsat = (d.feeSats.toLongOrNull() ?: 0L) * 1_000L
        val climbCount = d.climbCount.toInt()
        val countedClimbCount = d.countedClimbCount.toInt()
        val authority = signer.getPublicKeyHex()
        val config = JsonObject(linkedMapOf(
            "comp_id" to JsonPrimitive(compId), "authority" to JsonPrimitive(authority), "authority_epoch" to JsonPrimitive(1),
            "title" to JsonPrimitive(d.title.trim()), "summary" to JsonPrimitive(d.summary.trim()), "description" to JsonPrimitive(d.rulesText.trim()),
            "organizer" to JsonObject(mapOf("name" to JsonPrimitive(d.organizer.trim()), "contact" to JsonPrimitive(d.contact.trim()))),
            "visibility" to JsonPrimitive(if (d.public) "public" else "unlisted"), "status" to JsonPrimitive("published"), "timezone" to JsonPrimitive(schedule.timezone),
            "registration_opens_at" to JsonPrimitive(schedule.registrationOpensAt), "registration_closes_at" to JsonPrimitive(schedule.registrationClosesAt),
            "checkin_opens_at" to JsonPrimitive(schedule.checkinOpensAt), "checkin_closes_at" to JsonPrimitive(schedule.checkinClosesAt),
            "starts_at" to JsonPrimitive(schedule.startsAt), "ends_at" to JsonPrimitive(schedule.endsAt),
            "capacity" to JsonPrimitive(d.capacity.toIntOrNull()?.coerceIn(0, 500) ?: 20), "waitlist_enabled" to JsonPrimitive(d.waitlistEnabled),
            "venue" to JsonObject(mapOf("kind" to JsonPrimitive("physical"), "name" to JsonPrimitive(d.venue.trim()), "address" to JsonPrimitive(d.address.trim()))),
            "board" to JsonObject(mapOf("brand" to JsonPrimitive(d.boardBrand), "model" to JsonPrimitive(d.boardModel.trim()), "size" to JsonPrimitive(d.boardSize.trim()), "angle" to JsonPrimitive(d.angle.toInt()), "layout_id" to JsonPrimitive(d.layoutId.toInt()))),
            "divisions" to JsonArray(divisions),
            "eligibility" to JsonPrimitive(d.eligibility.trim()), "waiver" to JsonPrimitive(d.waiver.trim()), "waiver_required" to JsonPrimitive(d.waiverRequired),
            "participant_instructions" to JsonPrimitive(d.participantInstructions.trim()), "spectator_info" to JsonPrimitive(d.spectatorInfo.trim()), "refund_policy" to JsonPrimitive(d.refundPolicy.trim()),
            "fee_msat" to JsonPrimitive(feeMsat), "prizes" to JsonArray(prizes),
            "prize_claim_days" to JsonPrimitive(d.prizeClaimDays.toInt()),
            "rules" to CompetitionCreateDraftPolicy.rules(d, climbCount, countedClimbCount),
            "climbs" to JsonArray(climbs), "relays" to JsonArray(d.relays.lineSequence().map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive).toList()),
            "created_at" to JsonPrimitive(now), "revision" to JsonPrimitive(1),
        ))
        val paid = if (feeMsat > 0) JsonObject(config + ("fee_lnurl" to JsonPrimitive(d.feeLnurl.trim()))) else config
        return compId to CompetitionCreateDraftPolicy.placeClimbs(paid, d.climbSource, climbs)
    }

    private companion object {
        const val DRAFT_KEY = "competition_create_draft_v1"
    }
}

internal object CompetitionCreateDraftPolicy {
    private val DIVISION_ID = Regex("^[a-z0-9_]{1,24}$")
    data class Schedule(
        val timezone: String,
        val registrationOpensAt: Long,
        val registrationClosesAt: Long,
        val checkinOpensAt: Long,
        val checkinClosesAt: Long,
        val startsAt: Long,
        val endsAt: Long,
    )

    private val LOCAL_MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        .withResolverStyle(ResolverStyle.STRICT)

    fun withDefaultSchedule(
        draft: CompetitionCreateViewModel.Draft,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CompetitionCreateViewModel.Draft {
        if (draft.startsAt.isNotBlank()) return draft
        val start = now.atZone(zone)
            .plusHours(draft.startsInHours.toLongOrNull()?.coerceIn(1, 24 * 365) ?: 24)
            .withSecond(0).withNano(0)
        val duration = draft.durationHours.toLongOrNull()?.coerceIn(1, 24 * 30) ?: 3
        return draft.copy(
            timezone = zone.id,
            registrationOpensAt = LOCAL_MINUTE.format(now.atZone(zone).withSecond(0).withNano(0)),
            registrationClosesAt = LOCAL_MINUTE.format(start),
            checkinOpensAt = LOCAL_MINUTE.format(start.minusHours(1)),
            checkinClosesAt = LOCAL_MINUTE.format(start),
            startsAt = LOCAL_MINUTE.format(start),
            endsAt = LOCAL_MINUTE.format(start.plusHours(duration)),
        )
    }

    fun schedule(d: CompetitionCreateViewModel.Draft): Schedule? = schedule(
        d.timezone, d.registrationOpensAt, d.registrationClosesAt,
        d.checkinOpensAt, d.checkinClosesAt, d.startsAt, d.endsAt,
    )

    fun schedule(
        timezone: String,
        registrationOpensAt: String,
        registrationClosesAt: String,
        checkinOpensAt: String,
        checkinClosesAt: String,
        startsAt: String,
        endsAt: String,
    ): Schedule? = runCatching {
        val zone = ZoneId.of(timezone.trim())
        fun epoch(value: String): Long = LocalDateTime.parse(value.trim(), LOCAL_MINUTE)
            .atZone(zone).toEpochSecond()
        Schedule(
            zone.id,
            epoch(registrationOpensAt), epoch(registrationClosesAt),
            epoch(checkinOpensAt), epoch(checkinClosesAt),
            epoch(startsAt), epoch(endsAt),
        )
    }.getOrNull()?.takeIf { s ->
        s.registrationOpensAt <= s.registrationClosesAt &&
            s.checkinOpensAt <= s.checkinClosesAt &&
            s.registrationOpensAt <= s.startsAt && s.checkinOpensAt <= s.startsAt &&
            s.startsAt <= s.endsAt && s.registrationClosesAt <= s.endsAt &&
            s.checkinClosesAt <= s.endsAt
    }

    fun formatLocalMinute(epochSeconds: Long, timezone: String): String? = runCatching {
        LOCAL_MINUTE.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.of(timezone)))
    }.getOrNull()

    fun parseDivisions(d: CompetitionCreateViewModel.Draft): List<JsonObject>? {
        val lines = d.divisions.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val source = if (lines.isEmpty()) listOf(d.division.trim().ifBlank { "Open" }) else lines
        if (source.size !in 1..8) return null
        val seen = mutableSetOf<String>()
        return source.mapIndexed { index, line ->
            val parts = line.split('|', limit = 2).map(String::trim)
            val label = (parts.getOrNull(1) ?: parts[0]).takeIf(String::isNotBlank) ?: return null
            val id = if (parts.size == 2) parts[0] else label.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
                .ifBlank { "division_${index + 1}" }
            if (!DIVISION_ID.matches(id) || !seen.add(id)) return null
            JsonObject(mapOf("id" to JsonPrimitive(id), "label" to JsonPrimitive(label)))
        }
    }

    fun parsePrizes(d: CompetitionCreateViewModel.Draft): List<JsonObject>? =
        parseDivisions(d)?.let { parsePrizes(d, it) }

    fun parsePrizes(
        d: CompetitionCreateViewModel.Draft,
        divisions: List<JsonObject>,
    ): List<JsonObject>? {
        val divisionIds = divisions.mapTo(mutableSetOf()) { (it["id"] as JsonPrimitive).content }
        val seen = mutableSetOf<String>()
        val lines = d.prizes.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.size > 10) return null
        return lines.map { line ->
            val parts = line.split('|').map(String::trim)
            val id = parts.getOrNull(0).orEmpty()
            val rank = parts.getOrNull(1)?.toIntOrNull()
            val kind = parts.getOrNull(2)
            val label = parts.getOrNull(3).orEmpty()
            val valueSats = parts.getOrNull(4)?.toLongOrNull() ?: 0L
            val division = parts.getOrNull(5)?.takeIf(String::isNotBlank)
            if (!DIVISION_ID.matches(id) || !seen.add(id) || rank !in 1..100 ||
                kind !in setOf("cash", "non_cash") || label.isBlank() || label.length > 80 ||
                valueSats !in 0L..100_000_000L || (division != null && division !in divisionIds)
            ) return null
            JsonObject(buildMap {
                put("id", JsonPrimitive(id)); put("rank", JsonPrimitive(rank!!))
                put("kind", JsonPrimitive(kind!!)); put("label", JsonPrimitive(label))
                if (kind == "cash") put("value_msat", JsonPrimitive(valueSats * 1_000L))
                division?.let { put("division", JsonPrimitive(it)) }
            })
        }
    }

    fun rules(d: CompetitionCreateViewModel.Draft, climbCount: Int, countedClimbCount: Int): JsonObject {
        val rules = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "climb_source" to JsonPrimitive(d.climbSource),
            "climb_count" to JsonPrimitive(climbCount),
            "counted_climb_count" to JsonPrimitive(countedClimbCount),
            "selection_uniqueness" to JsonPrimitive(if (d.climbSource == "participant_choice") d.selectionUniqueness else "none"),
            "progression" to JsonPrimitive(d.progression),
            "attempts_per_climb" to JsonPrimitive(d.attempts.toIntOrNull()?.coerceIn(1, 20) ?: 5),
            "turn_deadline_sec" to JsonPrimitive(d.turnSeconds.toIntOrNull()?.coerceIn(30, 1800) ?: 180),
            "attempt_deadline_sec" to JsonPrimitive(0),
            "min_rest_sec" to JsonPrimitive(0),
            "defer_budget_per_round" to JsonPrimitive(1),
            "max_consecutive_defers" to JsonPrimitive(1),
            "defer_slots" to JsonPrimitive(1),
            "scoring" to JsonPrimitive(d.scoring),
            "tiebreaks" to JsonArray(listOf(
                JsonPrimitive("fewest_attempts"), JsonPrimitive("most_zones"),
                JsonPrimitive("earliest_finish"), JsonPrimitive("seed_order"),
            )),
            "late_entry_allowed" to JsonPrimitive(d.lateEntryAllowed),
        )
        if (d.scoring == "achievement_points") rules["score_points"] = JsonObject(mapOf(
            "zone" to JsonPrimitive(d.zonePoints.toIntOrNull()?.coerceIn(0, 10_000) ?: 10),
            "top" to JsonPrimitive(d.topPoints.toIntOrNull()?.coerceIn(0, 10_000) ?: 25),
            "flash" to JsonPrimitive(d.flashPoints.toIntOrNull()?.coerceIn(0, 10_000) ?: 5),
        ))
        return JsonObject(rules)
    }

    fun placeClimbs(
        config: JsonObject,
        climbSource: String,
        climbs: List<JsonObject>,
    ): JsonObject = JsonObject(config.toMutableMap().apply {
        if (climbSource == "participant_choice") {
            remove("climbs")
            put("climb_pool", JsonObject(mapOf("options" to JsonArray(climbs))))
        }
    })

    fun validateStep(d: CompetitionCreateViewModel.Draft, step: Int): String? = when (step) {
        0 -> if (d.title.isBlank() || d.venue.isBlank()) "required_basics" else null
        1 -> when {
            d.layoutId.toIntOrNull() == null || d.angle.toIntOrNull() == null || d.boardSize.isBlank() -> "invalid_board"
            schedule(d) == null -> "invalid_schedule"
            else -> null
        }
        2 -> {
            val options = parseClimbs(d)
            val climbCount = d.climbCount.toIntOrNull()
            val counted = d.countedClimbCount.toIntOrNull()
            when {
                options.isEmpty() -> "required_climb"
                climbCount == null || climbCount !in 1..minOf(options.size, 40) -> "invalid_climb_count"
                counted == null || counted !in 1..climbCount -> "invalid_counted_climb_count"
                d.climbSource == "participant_choice" && d.scoring in setOf("points_sum", "hardest_n") -> "invalid_choice_scoring"
                else -> null
            }
        }
        3 -> when {
            parseDivisions(d) == null -> "invalid_divisions"
            parsePrizes(d) == null || d.prizeClaimDays.toIntOrNull() !in 1..365 -> "invalid_prizes"
            d.feeSats.toLongOrNull() !in 0L..1_000_000L -> "invalid_fee"
            d.feeSats.toLongOrNull()!! > 0 && d.feeLnurl.isBlank() -> "invalid_fee"
            d.waiverRequired && d.waiver.isBlank() -> "required_waiver"
            listOf(d.rulesText, d.eligibility, d.waiver, d.participantInstructions, d.spectatorInfo, d.refundPolicy)
                .any { it.length > 2_000 } -> "text_too_long"
            else -> null
        }
        else -> null
    }

    fun parseClimbs(d: CompetitionCreateViewModel.Draft): List<JsonObject> =
        d.climbs.lineSequence().mapNotNull { line ->
            val parts = line.split('|').map(String::trim)
            val raw = parts.getOrNull(0)?.lowercase().orEmpty()
            val uuid = (Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .find(raw)?.value ?: raw).takeIf(CompetitionProtocol::isClimbUuid) ?: return@mapNotNull null
            val label = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: uuid.take(8)
            JsonObject(buildMap {
                put("id", JsonPrimitive("b${size + 1}"))
                put("climb_uuid", JsonPrimitive(uuid))
                put("angle", JsonPrimitive(d.angle.toInt()))
                put("label", JsonPrimitive(label))
                put("points", JsonPrimitive(parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 100_000) ?: 0))
                parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
                    ?.let { put("zone_hold", JsonPrimitive(it)) }
            })
        }.toList().mapIndexed { index, climb -> JsonObject(climb.toMutableMap().apply {
            put("id", JsonPrimitive("b${index + 1}"))
        }) }
}
