package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §8: getting a data key out for a backup, and back in afterwards.
 *
 * The vault deliberately never handed out key material before. A backup that
 * carries only wrapped keys is useless — the wrapping key lives in the Android
 * Keystore of the phone that is gone — so there has to be one explicit,
 * narrow door, and it has to zeroize.
 */
class VaultBackupExportTest {

    private val backend = InMemoryKeyAliasBackend()
    private fun store() = AliasedWrappingKeyStore(backend)
    private val videos = KeyHandle(KeyScope.CATEGORY, SharingCategory.VIDEOS.name)

    @Test
    fun a_data_key_can_be_exported_and_re_imported_under_a_fresh_wrapping_key() {
        val vault = AeadSharingKeyVault(store())
        val key = vault.createDataKey(videos)
        val sealed = vault.seal(key, "Steilwand".encodeToByteArray(), aad = videos.aad())

        val exported = vault.exportForBackup(key)!!

        // A fresh install: new backend, so a new wrapping key.
        val restoredVault = AeadSharingKeyVault(AliasedWrappingKeyStore(InMemoryKeyAliasBackend()))
        val rewrapped = restoredVault.importFromBackup(videos, exported.copyOf())

        assertContentEquals(
            "Steilwand".encodeToByteArray(),
            restoredVault.open(rewrapped, sealed, aad = videos.aad()),
        )
    }

    @Test
    fun the_re_wrapped_key_is_wrapped_differently_even_though_it_opens_the_same_data() {
        val vault = AeadSharingKeyVault(store())
        val key = vault.createDataKey(videos)
        val exported = vault.exportForBackup(key)!!

        val other = AeadSharingKeyVault(AliasedWrappingKeyStore(InMemoryKeyAliasBackend()))
        val rewrapped = other.importFromBackup(videos, exported.copyOf())

        assertNotEquals(key.wrappedBytes.toList(), rewrapped.wrappedBytes.toList())
    }

    @Test
    fun importing_zeroizes_the_raw_key_it_was_given() {
        val vault = AeadSharingKeyVault(store())
        val exported = vault.exportForBackup(vault.createDataKey(videos))!!
        val copy = exported.copyOf()

        AeadSharingKeyVault(AliasedWrappingKeyStore(InMemoryKeyAliasBackend()))
            .importFromBackup(videos, exported)

        assertTrue(exported.all { it == 0.toByte() }, "the caller's copy must be wiped")
        assertFalse(copy.all { it == 0.toByte() }, "the test's own copy proves it was not already zero")
    }

    @Test
    fun exporting_a_key_whose_wrapping_key_was_destroyed_returns_nothing() {
        val vault = AeadSharingKeyVault(store())
        val key = vault.createDataKey(videos)
        vault.destroy(videos)

        assertNull(vault.exportForBackup(key))
    }

    @Test
    fun an_exported_key_is_the_full_width_the_vault_uses() {
        val vault = AeadSharingKeyVault(store())
        assertTrue(vault.exportForBackup(vault.createDataKey(videos))!!.size == 32)
    }

    @Test
    fun importing_a_key_of_the_wrong_width_is_refused() {
        val vault = AeadSharingKeyVault(store())
        assertFailsWith<SharingCryptoException> { vault.importFromBackup(videos, ByteArray(7)) }
    }
}
