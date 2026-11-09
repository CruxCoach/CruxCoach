package com.cruxcoach.android.spraywall

/**
 * The switch for spray walls (FEAT-078). Off, the app shows them nowhere: no entry in the main menu, no spray walls on a
 * place in the map, and the year in review does not mention them. Nothing of them then runs either, since only their
 * screens start the publisher and the fetcher.
 *
 * Data already on the phone stays as it is (its migrations run either way), and so does the hold model's entry under
 * Settings, so its storage can still be freed. Turned on again, everything is back.
 */
object SprayWallFeature {
    /** False hides spray walls in this build. */
    val enabled: Boolean = true
}
