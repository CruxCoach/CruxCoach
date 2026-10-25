package com.cruxcoach.android.athlete

import android.content.Context
import com.cruxcoach.android.data.SqlCipherKeyManager
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.util.PerfLogger
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.data.AthleteDriverFactory
import com.cruxcoach.data.createAthleteDatabase
import com.cruxcoach.db.athlete.AthleteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether this identity already has an athlete database, without opening or
 * creating it: a backup of a board-only user must not create one, and a
 * missing file means there is nothing to back up.
 */
@Singleton
class AthleteDatabaseProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nostrSigner: NostrSigner,
) {
    /** True when in doubt, so callers behave as before and read the database. */
    fun exists(): Boolean = runCatching {
        context.getDatabasePath(fileName(nostrSigner.getPublicKeyHex())).exists()
    }.getOrDefault(true)

    companion object {
        fun fileName(pubkeyHex: String): String = "cruxcoach_athlete_${pubkeyHex.take(16)}.db"
    }
}

/**
 * The athlete database is opened on first use (the training screens or a
 * backup), never at app start — inject `dagger.Lazy<AthleteRepository>`
 * wherever the consumer itself is created early.
 */
@Module
@InstallIn(SingletonComponent::class)
object AthleteModule {

    @Provides
    @Singleton
    fun provideAthleteDatabase(
        @ApplicationContext context: Context,
        keyManager: SqlCipherKeyManager,
        nostrSigner: NostrSigner,
    ): AthleteDatabase = PerfLogger.trace("DI: AthleteDatabase") {
        val pubkeyHex = nostrSigner.getPublicKeyHex()
        val key = keyManager.getDerivedAthleteKeyForPubkey(pubkeyHex)
        try {
            createAthleteDatabase(AthleteDriverFactory(context, key), AthleteDatabaseProbe.fileName(pubkeyHex))
        } finally {
            key.fill(0)
        }
    }

    @Provides
    @Singleton
    fun provideAthleteRepository(db: AthleteDatabase): AthleteRepository =
        AthleteRepository(db, Dispatchers.IO) { System.currentTimeMillis() }
}
