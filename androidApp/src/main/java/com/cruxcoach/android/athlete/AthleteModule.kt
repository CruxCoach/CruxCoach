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
import javax.inject.Singleton

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
            createAthleteDatabase(AthleteDriverFactory(context, key), "cruxcoach_athlete_${pubkeyHex.take(16)}.db")
        } finally {
            key.fill(0)
        }
    }

    @Provides
    @Singleton
    fun provideAthleteRepository(db: AthleteDatabase): AthleteRepository =
        AthleteRepository(db, Dispatchers.IO) { System.currentTimeMillis() }
}
