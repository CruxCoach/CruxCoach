package com.cruxcoach.domain.competition

/**
 * Standings — FEAT-058 §6.4.
 *
 * Derived, never stored: a pure function of the reduced state and the rules, so
 * a standing cannot drift from the log that produced it. Port of
 * `competitions/app/protocol/scoring.mjs`, pinned to the same fixtures.
 */
object CompetitionScoring {

    private data class Contribution(
        val climb: ClimbProgress,
        val top: Int,
        val zone: Int,
        val topAttempts: Int,
        val zoneAttempts: Int,
        val points: Int,
    )

    data class Standing(
        val rank: Int,
        val pubkey: String,
        val display: String,
        val division: String,
        val result: String,
        val tops: Int,
        val zones: Int,
        /** Attempts spent on climbs that were TOPPED — the sport's metric. */
        val attempts: Int,
        val zoneAttempts: Int,
        val totalAttempts: Int,
        val points: Int,
        val finishedAt: Long,
    )

    /**
     * Counting *every* attempt would rank a climber who never left the ground
     * above one who tried three times and fell. [attempts] is therefore
     * attempts-to-top; [totalAttempts] keeps the raw number for display.
     */
    private fun tally(participant: Participant, competition: Competition): Standing {
        val climbs = if (competition.rules.climbSource == "participant_choice" && participant.selections.isNotEmpty()) {
            participant.climbs.filter { it.climbId in participant.selections }
        } else {
            participant.climbs
        }
        val achievement = competition.rules.scorePoints ?: CompetitionScorePoints(0, 0, 0)
        val pointScoring = competition.rules.scoring != "tops_then_attempts"
        val contributions = climbs.map { climb ->
            val top = if (climb.outcome == "top") 1 else 0
            val zone = if (top == 1 || climb.outcome == "zone") 1 else 0
            val points = when {
                top == 1 && competition.rules.scoring == "achievement_points" ->
                    achievement.zone + achievement.top + if (climb.attemptsUsed == 1) achievement.flash else 0
                top == 1 -> competition.climb(climb.climbId)?.points ?: 0
                climb.outcome == "zone" && competition.rules.scoring == "achievement_points" -> achievement.zone
                else -> 0
            }
            Contribution(
                climb = climb,
                top = top,
                zone = zone,
                topAttempts = if (top == 1) climb.attemptsUsed else 0,
                zoneAttempts = if (zone == 1) climb.attemptsUsed else 0,
                points = points,
            )
        }.sortedWith(Comparator { a, b ->
            when {
                pointScoring && a.points != b.points -> b.points - a.points
                a.top != b.top -> b.top - a.top
                a.topAttempts != b.topAttempts -> a.topAttempts - b.topAttempts
                a.zone != b.zone -> b.zone - a.zone
                a.zoneAttempts != b.zoneAttempts -> a.zoneAttempts - b.zoneAttempts
                a.climb.at != b.climb.at -> a.climb.at.compareTo(b.climb.at)
                else -> a.climb.climbId.compareTo(b.climb.climbId)
            }
        }).take(competition.rules.countedClimbCount)

        var tops = 0
        var zones = 0
        var attempts = 0
        var zoneAttempts = 0
        var totalAttempts = 0
        var points = 0
        var finishedAt = 0L

        for (contribution in contributions) {
            val climb = contribution.climb
            totalAttempts += climb.attemptsUsed
            when {
                contribution.top == 1 -> {
                    tops += 1
                    zones += 1 // a top implies its zone
                    attempts += climb.attemptsUsed
                    zoneAttempts += climb.attemptsUsed
                    points += contribution.points
                    if (climb.at > finishedAt) finishedAt = climb.at
                }
                contribution.zone == 1 -> {
                    zones += 1
                    zoneAttempts += climb.attemptsUsed
                    points += contribution.points
                }
            }
        }

        return Standing(
            rank = 0,
            pubkey = participant.pubkey,
            display = participant.display,
            division = participant.division,
            result = participant.result,
            tops = tops,
            zones = zones,
            attempts = attempts,
            zoneAttempts = zoneAttempts,
            totalAttempts = totalAttempts,
            points = points,
            finishedAt = finishedAt,
        )
    }

    private fun comparators(competition: Competition, seedOrder: List<String>): List<Comparator<Standing>> {
        // The IFSC boulder ordering: tops, then attempts to top, then zones,
        // then attempts to zone. Point formats replace only the first key.
        val primary: Comparator<Standing> = if (competition.rules.scoring == "tops_then_attempts") {
            Comparator { a, b ->
                when {
                    a.tops != b.tops -> b.tops - a.tops
                    a.attempts != b.attempts -> a.attempts - b.attempts
                    a.zones != b.zones -> b.zones - a.zones
                    else -> a.zoneAttempts - b.zoneAttempts
                }
            }
        } else {
            Comparator { a, b -> b.points - a.points }
        }

        val byName: Map<String, Comparator<Standing>> = mapOf(
            "fewest_attempts" to Comparator { a, b -> a.attempts - b.attempts },
            "most_zones" to Comparator { a, b -> b.zones - a.zones },
            "fewest_zone_attempts" to Comparator { a, b -> a.zoneAttempts - b.zoneAttempts },
            // Someone who never finished (0) must sort last, not first.
            "earliest_finish" to Comparator { a, b ->
                val left = if (a.finishedAt == 0L) Long.MAX_VALUE else a.finishedAt
                val right = if (b.finishedAt == 0L) Long.MAX_VALUE else b.finishedAt
                left.compareTo(right)
            },
            "seed_order" to Comparator { a, b ->
                val left = seedOrder.indexOf(a.pubkey).let { if (it == -1) Int.MAX_VALUE else it }
                val right = seedOrder.indexOf(b.pubkey).let { if (it == -1) Int.MAX_VALUE else it }
                left.compareTo(right)
            },
        )

        return listOf(primary) + competition.rules.tiebreaks.mapNotNull { byName[it] }
    }

    /**
     * One row per ranked participant, ordered by division (ascending id) then
     * rank. Ties share a rank and the next rank skips, the way a results sheet
     * reads: 1, 1, 3.
     */
    fun standings(state: CompetitionState, competition: Competition): List<Standing> {
        val chain = comparators(competition, state.order)
        val rows = state.participants
            .filter { it.registration == "accepted" && it.checkin == "checked_in" }
            .map { tally(it, competition) }

        val out = mutableListOf<Standing>()
        for (division in rows.map { it.division }.distinct().sorted()) {
            val group = rows.filter { it.division == division }
            // Disqualified and no-show climbers are listed, but never above
            // someone who climbed — they carry no rank at all.
            val ranked = group.filter { it.result == "active" || it.result == "finished" }
                .sortedWith(
                    Comparator { a, b ->
                        for (comparator in chain) {
                            val verdict = comparator.compare(a, b)
                            if (verdict != 0) return@Comparator verdict
                        }
                        // Total order guarantee: without this, two genuinely
                        // tied climbers could come out in different positions
                        // on two clients.
                        a.pubkey.compareTo(b.pubkey)
                    },
                )
            var rank = 0
            ranked.forEachIndexed { index, row ->
                if (index == 0 || !tied(row, ranked[index - 1], chain)) rank = index + 1
                out += row.copy(rank = rank)
            }
            group.filterNot { it.result == "active" || it.result == "finished" }
                .sortedBy { it.pubkey }
                .forEach { out += it.copy(rank = 0) }
        }
        return out
    }

    private fun tied(a: Standing, b: Standing, chain: List<Comparator<Standing>>) =
        chain.all { it.compare(a, b) == 0 }
}
