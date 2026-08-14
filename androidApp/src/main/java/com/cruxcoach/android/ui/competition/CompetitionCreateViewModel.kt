package com.cruxcoach.android.ui.competition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.competition.CompetitionHostPublisher
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.competition.CompetitionProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.SecureRandom
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

@HiltViewModel
class CompetitionCreateViewModel @Inject constructor(
    private val publisher: CompetitionHostPublisher,
    private val signer: NostrSigner,
    private val preferences: UserPreferences,
    private val boards: BoardRepository,
) : ViewModel() {
    data class Draft(
        val title: String = "", val summary: String = "", val venue: String = "",
        val address: String = "", val organizer: String = "", val contact: String = "",
        val boardBrand: String = "kilter", val boardModel: String = "Kilter Board",
        val boardSize: String = "", val layoutId: String = "1", val angle: String = "40",
        val startsInHours: String = "24", val durationHours: String = "3",
        val capacity: String = "20", val division: String = "Open",
        val attempts: String = "5", val turnSeconds: String = "180",
        val climbs: String = "", val rulesText: String = "",
        val relays: String = "wss://relay.damus.io\nwss://nos.lol",
        val public: Boolean = true, val waiverRequired: Boolean = false,
    )
    sealed interface State {
        data class Editing(val draft: Draft = Draft(), val step: Int = 0, val error: String? = null) : State
        data object Publishing : State
        data class Created(val organizerPubkey: String, val compId: String) : State
    }
    private val _state = MutableStateFlow<State>(State.Editing())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
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

    fun edit(change: (Draft) -> Draft) = _state.update {
        val editing = it as? State.Editing ?: return@update it
        editing.copy(draft = change(editing.draft), error = null)
    }
    fun next() = _state.update {
        val editing = it as? State.Editing ?: return@update it
        editing.copy(step = (editing.step + 1).coerceAtMost(3), error = validateStep(editing.draft, editing.step))
            .let { next -> if (next.error == null) next else editing.copy(error = next.error) }
    }
    fun back() = _state.update {
        val editing = it as? State.Editing ?: return@update it
        editing.copy(step = (editing.step - 1).coerceAtLeast(0), error = null)
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
                    _state.value = State.Created(signer.getPublicKeyHex(), config.first)
                is CompetitionHostPublisher.Result.Failed ->
                    _state.value = editing.copy(error = result.reason)
            }
        }
    }

    private fun validateStep(d: Draft, step: Int): String? = when (step) {
        0 -> if (d.title.isBlank() || d.venue.isBlank()) "Titel und Ort sind erforderlich." else null
        1 -> if (d.layoutId.toIntOrNull() == null || d.angle.toIntOrNull() == null || d.boardSize.isBlank()) "Board-Konfiguration vervollständigen." else null
        2 -> if (parseClimbs(d).isEmpty()) "Mindestens einen Boulder als UUID | Name eintragen." else null
        else -> null
    }

    private fun parseClimbs(d: Draft): List<JsonObject> = d.climbs.lineSequence().mapNotNull { line ->
        val parts = line.split('|').map(String::trim)
        val raw = parts.getOrNull(0)?.lowercase().orEmpty()
        val uuid = (Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .find(raw)?.value ?: raw).takeIf(CompetitionProtocol::isClimbUuid) ?: return@mapNotNull null
        val label = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: uuid.take(8)
        JsonObject(buildMap {
            put("id", JsonPrimitive("b${size + 1}")); put("climb_uuid", JsonPrimitive(uuid))
            put("angle", JsonPrimitive(d.angle.toInt())); put("label", JsonPrimitive(label)); put("points", JsonPrimitive(0))
            parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }?.let { put("zone_hold", JsonPrimitive(it)) }
        })
    }.toList().mapIndexed { index, climb -> JsonObject(climb.toMutableMap().apply {
        put("id", JsonPrimitive("b${index + 1}"))
    }) }

    private fun buildConfig(d: Draft): Pair<String, JsonObject> {
        for (step in 0..2) require(validateStep(d, step) == null) { validateStep(d, step).orEmpty() }
        val compId = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis() / 1000
        val starts = now + (d.startsInHours.toLongOrNull() ?: 24) * 3600
        val ends = starts + (d.durationHours.toLongOrNull() ?: 3) * 3600
        val climbs = parseClimbs(d)
        val authority = signer.getPublicKeyHex()
        val config = JsonObject(linkedMapOf(
            "comp_id" to JsonPrimitive(compId), "authority" to JsonPrimitive(authority), "authority_epoch" to JsonPrimitive(1),
            "title" to JsonPrimitive(d.title.trim()), "summary" to JsonPrimitive(d.summary.trim()), "description" to JsonPrimitive(d.rulesText.trim()),
            "organizer" to JsonObject(mapOf("name" to JsonPrimitive(d.organizer.trim()), "contact" to JsonPrimitive(d.contact.trim()))),
            "visibility" to JsonPrimitive(if (d.public) "public" else "unlisted"), "status" to JsonPrimitive("published"), "timezone" to JsonPrimitive("UTC"),
            "registration_opens_at" to JsonPrimitive(now), "registration_closes_at" to JsonPrimitive(starts),
            "checkin_opens_at" to JsonPrimitive((starts - 3600).coerceAtLeast(now)), "checkin_closes_at" to JsonPrimitive(starts),
            "starts_at" to JsonPrimitive(starts), "ends_at" to JsonPrimitive(ends),
            "capacity" to JsonPrimitive(d.capacity.toIntOrNull()?.coerceIn(0, 500) ?: 20), "waitlist_enabled" to JsonPrimitive(true),
            "venue" to JsonObject(mapOf("kind" to JsonPrimitive("physical"), "name" to JsonPrimitive(d.venue.trim()), "address" to JsonPrimitive(d.address.trim()))),
            "board" to JsonObject(mapOf("brand" to JsonPrimitive(d.boardBrand), "model" to JsonPrimitive(d.boardModel.trim()), "size" to JsonPrimitive(d.boardSize.trim()), "angle" to JsonPrimitive(d.angle.toInt()), "layout_id" to JsonPrimitive(d.layoutId.toInt()))),
            "divisions" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("open"), "label" to JsonPrimitive(d.division.trim().ifBlank { "Open" }))))),
            "eligibility" to JsonPrimitive(""), "waiver" to JsonPrimitive(if (d.waiverRequired) d.rulesText.trim() else ""), "waiver_required" to JsonPrimitive(d.waiverRequired),
            "participant_instructions" to JsonPrimitive(""), "spectator_info" to JsonPrimitive(""), "refund_policy" to JsonPrimitive(""),
            "fee_msat" to JsonPrimitive(0), "prizes" to JsonArray(emptyList()),
            "rules" to JsonObject(mapOf(
                "climb_source" to JsonPrimitive("organizer_set"), "climb_count" to JsonPrimitive(climbs.size), "counted_climb_count" to JsonPrimitive(climbs.size),
                "selection_uniqueness" to JsonPrimitive("none"), "progression" to JsonPrimitive("asynchronous_turns"),
                "attempts_per_climb" to JsonPrimitive(d.attempts.toIntOrNull()?.coerceIn(1, 20) ?: 5), "turn_deadline_sec" to JsonPrimitive(d.turnSeconds.toIntOrNull()?.coerceIn(30, 1800) ?: 180),
                "attempt_deadline_sec" to JsonPrimitive(0), "min_rest_sec" to JsonPrimitive(0), "defer_budget_per_round" to JsonPrimitive(1),
                "max_consecutive_defers" to JsonPrimitive(1), "defer_slots" to JsonPrimitive(1), "scoring" to JsonPrimitive("tops_then_attempts"),
                "tiebreaks" to JsonArray(listOf(JsonPrimitive("fewest_attempts"), JsonPrimitive("most_zones"), JsonPrimitive("earliest_finish"), JsonPrimitive("seed_order"))),
                "late_entry_allowed" to JsonPrimitive(false),
            )),
            "climbs" to JsonArray(climbs), "relays" to JsonArray(d.relays.lineSequence().map(String::trim).filter(String::isNotBlank).map(::JsonPrimitive).toList()),
            "created_at" to JsonPrimitive(now), "revision" to JsonPrimitive(1),
        ))
        return compId to config
    }
}
