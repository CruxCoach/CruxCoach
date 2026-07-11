package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.util.DateTimeUtil

/**
 * Imports the connected Kilter account's own circuits (the official app's
 * "lists") into the local `climb_lists` tables, mirroring what the FEAT-005
 * Aurora-JSON path does — but sourced from Kilter's PowerSync sync stream
 * (via [KilterApiClient.fetchCircuits]) instead of an emailed export, and
 * resolving members by climb uuid rather than by name.
 *
 * Shape of both writes matches the Aurora circuit importer so a circuit
 * imported by either path is a first-class list:
 *  - Dedup/upsert by a namespaced, stable `external_id`
 *    (`kilter-api:circuit:<circuitUuid>`). Distinct from the Aurora
 *    `aurora-json:circuit:*` namespace on purpose — the two sources key
 *    differently (server uuid vs name+timestamp), and a user who happens to
 *    run both imports keeps one list per source rather than a fragile merge.
 *  - Re-import refreshes metadata + membership: the existing row's entries
 *    are cleared and rewritten, so a circuit edited on Kilter re-syncs
 *    cleanly instead of accreting stale members.
 *
 * Best-effort and NON-fatal: any fetch/write failure is logged and swallowed
 * so the surrounding log sync still completes. Members are inserted by uuid
 * regardless of whether the board DB currently mirrors that climb — an
 * unresolved uuid simply won't render until the catalogue catches up, the
 * same tolerance the Aurora path applies to unresolved names.
 */
internal class KilterCircuitImporter(
    private val apiClient: KilterApiClient,
    private val secureDb: SecureDatabase,
) {
    private companion object {
        const val TAG = "KilterCircuitImporter"
    }

    /**
     * Fetch + upsert the user's circuits. Returns how many circuits were
     * written (inserted or refreshed); 0 on empty/failure.
     */
    suspend fun importCircuits(): Int {
        val circuits = apiClient.fetchCircuits().getOrElse {
            Log.w(TAG, "Circuit import skipped (fetch failed): ${it.message}")
            return 0
        }
        val usable = circuits.filter { it.circuitUuid.isNotBlank() }
        if (usable.isEmpty()) return 0

        val q = secureDb.climbListsQueries
        var written = 0
        try {
            secureDb.transaction {
                for (circuit in usable) {
                    val externalId = "kilter-api:circuit:${circuit.circuitUuid}"
                    val createdAt = circuit.createdAt.ifBlank { DateTimeUtil.nowIso() }
                    val members = circuit.memberClimbUuids()

                    val existingId = q.findClimbListByExternalId(externalId).executeAsOneOrNull()
                    val listId = if (existingId != null) {
                        q.updateAuroraClimbListMeta(
                            name = circuit.name,
                            description = circuit.description,
                            color = circuit.color,
                            id = existingId,
                        )
                        q.deleteClimbListEntries(existingId)
                        existingId
                    } else {
                        q.insertAuroraClimbList(
                            name = circuit.name,
                            created_at = createdAt,
                            description = circuit.description,
                            color = circuit.color,
                            external_id = externalId,
                        )
                        q.getLastInsertedListId().executeAsOne()
                    }
                    for (climbUuid in members) {
                        q.insertClimbListEntry(
                            list_id = listId,
                            climb_uuid = climbUuid,
                            added_at = createdAt,
                        )
                    }
                    written++
                }
            }
            if (written > 0) Log.i(TAG, "Imported $written Kilter circuit(s) as lists")
        } catch (e: Exception) {
            // Circuit import is an enhancement, never a gate — keep the sync alive.
            Log.w(TAG, "Circuit import failed mid-write — continuing sync", e)
        }
        return written
    }
}
