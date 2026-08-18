package com.cruxcoach.android.competition

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionClimb
import com.cruxcoach.domain.competition.CompetitionProtocol
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A competition climb, resolved against the board this phone actually has.
 *
 * A competition names climbs by their real board uuid, which is what makes it
 * climbable rather than a list of names. That uuid still has to survive contact
 * with reality: the catalogue may not be downloaded, and the climb may not be
 * renderable on any board size we hold. Both are ordinary situations at a gym
 * with no signal, and both have to be *said* rather than turned into a blank
 * board screen.
 *
 * Nothing here is a guess. If it cannot be shown, the answer is which of the
 * two reasons applies, so the screen can offer the matching fix.
 */
@Singleton
class CompetitionClimbResolver @Inject constructor(
    private val boardRepository: BoardRepository,
) {

    sealed interface Result {
        /** Go: the board screen can load this uuid at this angle. */
        data class Ready(val climbUuid: String, val angle: Int) : Result

        /** The uuid is not one this competition could ever have meant. */
        data object Unusable : Result

        /** The climb is real, but this phone has not downloaded its board yet. */
        data class NotInCatalogue(val brand: String) : Result

        /** Downloaded, but not renderable on any board size we hold. */
        data class WrongBoard(val brand: String) : Result
    }

    /** The brand a competition runs on, defaulting to the one CruxCoach started with. */
    fun brandOf(competition: Competition): String =
        competition.raw["board"]?.let { board ->
            (board as? kotlinx.serialization.json.JsonObject)?.get("brand")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } ?: "kilter"

    fun resolve(competition: Competition, climb: CompetitionClimb): Result {
        val uuid = climb.climbUuid.lowercase()
        if (!CompetitionProtocol.isClimbUuid(uuid) || CompetitionProtocol.isPlaceholderUuid(uuid)) {
            return Result.Unusable
        }
        val brand = brandOf(competition)
        if (!boardRepository.climbExistsByUuid(uuid)) return Result.NotInCatalogue(brand)

        // Whether we can DRAW it, not merely whether a row exists. A climb whose
        // holds fall outside every size we hold would open a board screen with
        // nothing on it, which reads as a broken app rather than a missing board.
        val sizeId = boardRepository.getProductSizeForClimbRender(uuid, brand)
            ?: return Result.WrongBoard(brand)
        if (!boardRepository.canRenderClimbOnSize(uuid, sizeId, brand)) return Result.WrongBoard(brand)

        return Result.Ready(uuid, climb.angle)
    }
}
