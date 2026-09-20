package com.cruxcoach.app.ui

import com.cruxcoach.app.backup.BackupRepository
import com.cruxcoach.app.backup.BackupState
import com.cruxcoach.app.backup.BlossomClient
import com.cruxcoach.app.backup.CruxCoachBackupPayloadStore
import com.cruxcoach.app.backup.LocalEventSigner
import com.cruxcoach.app.backup.RelayBackupEventSource
import com.cruxcoach.app.nostr.RelayClient
import com.cruxcoach.app.platform.PlatformServices
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CoroutineScope

/**
 * Builds a working cloud-backup screen from the platform services, the
 * identity's databases and a provider for the private key.
 *
 * [secretKeyProvider] must hand out a FRESH copy on every call (the pipeline
 * zeroes what it receives) and must read from the Keychain rather than from a
 * retained field, so a key change takes effect without a restart — e.g.
 * `{ platform.secrets.read(LocalIdentity.NOSTR_KEY) }`.
 *
 * [scope] must be the caller's main-thread scope (`MainScope()` on iOS).
 */
fun createBackupScreenModel(
    platform: PlatformServices,
    secureDb: SecureDatabase,
    boardRepository: BoardRepository,
    personalBoardRepo: PersonalBoardRepository,
    scope: CoroutineScope,
    secretKeyProvider: () -> ByteArray?,
): BackupScreenModel {
    val signer = LocalEventSigner(platform.hashing, secretKeyProvider)
    val state = BackupState(platform.keyValues, platform.hashing)
    val repository = BackupRepository(
        hashing = platform.hashing,
        aead = platform.aead,
        gzip = platform.gzip,
        clock = platform.clock,
        events = RelayBackupEventSource(RelayClient(platform.webSockets, platform.hashing)),
        blossom = BlossomClient(platform.http, platform.hashing, platform.clock, signer),
        payloads = CruxCoachBackupPayloadStore(secureDb, boardRepository, personalBoardRepo),
        state = state,
        signer = signer,
        secretKeyProvider = secretKeyProvider,
    )
    return BackupScreenModel(repository, state, scope)
}
