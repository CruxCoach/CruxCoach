package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FEAT-062 §7 + §9: destroying a wrapping key has to outlive the process.
 *
 * The first implementation kept destroyed handles in a `Set` in RAM and derived
 * every wrapping key from one root with HKDF. After a restart the set was empty
 * and the very same key came back out of the very same root, so a crypto erase
 * lasted exactly as long as the process did. These tests pin the durable
 * behaviour: destruction removes the stored key, and what is minted afterwards
 * is a *different* key, so ciphertext wrapped under the old one stays
 * unreadable for good.
 */
class AliasedWrappingKeyStoreTest {

    private val videos = KeyHandle(KeyScope.CATEGORY, SharingCategory.VIDEOS.name)
    private val notes = KeyHandle(KeyScope.CATEGORY, SharingCategory.PRIVATE_NOTES.name)
    private val oneVideo = KeyHandle(KeyScope.OBJECT, "video-42")

    /** Stands in for the Keystore: survives the wrapper, not the test. */
    private val backend = InMemoryKeyAliasBackend()
    private fun store() = AliasedWrappingKeyStore(backend)

    // ------------------------------------------------------------- aliases

    @Test
    fun an_alias_is_deterministic_for_one_handle() {
        assertEquals(SharingKeyAlias.of(videos), SharingKeyAlias.of(videos))
    }

    @Test
    fun different_handles_get_different_aliases() {
        val aliases = setOf(
            SharingKeyAlias.of(videos),
            SharingKeyAlias.of(notes),
            SharingKeyAlias.of(oneVideo),
        )
        assertEquals(3, aliases.size)
    }

    @Test
    fun a_category_and_an_object_of_the_same_name_do_not_collide() {
        assertNotEquals(
            SharingKeyAlias.of(KeyHandle(KeyScope.CATEGORY, "VIDEOS")),
            SharingKeyAlias.of(KeyHandle(KeyScope.OBJECT, "VIDEOS")),
        )
    }

    @Test
    fun an_alias_is_safe_for_a_keystore_regardless_of_the_handle_id() {
        // Object ids come from user data, so they can contain anything.
        val nasty = KeyHandle(KeyScope.OBJECT, "../../etc/passwd \u0000 ✅ \n")
        val alias = SharingKeyAlias.of(nasty)
        assertTrue(alias.all { it.isDigit() || it in 'a'..'z' || it == '_' }, "unsafe alias: $alias")
        assertTrue(alias.isNotEmpty())
    }

    // ----------------------------------------------------------- lifecycle

    @Test
    fun getOrCreate_mints_a_key_and_get_returns_the_same_one() {
        val created = store().getOrCreate(videos)
        assertContentEquals(created, store().get(videos))
    }

    @Test
    fun get_returns_null_for_a_handle_that_was_never_created() {
        assertNull(store().get(videos), "get must not silently create a key")
    }

    @Test
    fun two_handles_get_independent_keys() {
        assertNotEquals(store().getOrCreate(videos).toList(), store().getOrCreate(notes).toList())
    }

    @Test
    fun destroying_removes_the_key() {
        store().getOrCreate(videos)
        store().destroy(videos)
        assertNull(store().get(videos))
    }

    @Test
    fun a_destroyed_key_stays_destroyed_across_a_restart() {
        store().getOrCreate(videos)
        store().destroy(videos)

        // A brand-new wrapper over the same durable backend — this is what a
        // process restart looks like.
        assertNull(store().get(videos), "the destruction must live in the store, not in the wrapper")
    }

    @Test
    fun a_key_minted_after_a_destruction_is_a_different_key() {
        val before = store().getOrCreate(oneVideo)
        store().destroy(oneVideo)
        val after = store().getOrCreate(oneVideo)

        assertNotEquals(
            before.toList(),
            after.toList(),
            "re-deriving the same key after a destroy would undo the crypto erase",
        )
    }

    @Test
    fun destroying_one_handle_leaves_the_others_intact() {
        val noteKey = store().getOrCreate(notes)
        store().getOrCreate(videos)

        store().destroy(videos)

        assertNull(store().get(videos))
        assertContentEquals(noteKey, store().get(notes))
    }

    @Test
    fun destroying_twice_is_not_an_error() {
        store().getOrCreate(videos)
        store().destroy(videos)
        store().destroy(videos)
        assertNull(store().get(videos))
    }

    @Test
    fun a_key_survives_a_restart_when_it_was_not_destroyed() {
        val created = store().getOrCreate(videos)
        assertContentEquals(created, store().get(videos))
    }

    // -------------------------------------------------- end-to-end erasure

    @Test
    fun ciphertext_wrapped_before_a_destroy_is_unreadable_after_a_restart() {
        val vault = AeadSharingKeyVault(store())
        val key = vault.createDataKey(oneVideo)
        val sealed = vault.seal(key, "Steilwand-Video".encodeToByteArray(), aad = oneVideo.aad())

        vault.destroy(oneVideo)

        // Restart: fresh vault, fresh wrapper, same durable backend.
        val afterRestart = AeadSharingKeyVault(store())
        assertFailsWith<SharingCryptoException> {
            afterRestart.open(key, sealed, aad = oneVideo.aad())
        }

        // And even after the handle is used again, the old ciphertext stays shut.
        afterRestart.createDataKey(oneVideo)
        assertFailsWith<SharingCryptoException> {
            afterRestart.open(key, sealed, aad = oneVideo.aad())
        }
    }

    @Test
    fun the_key_bytes_are_the_full_width_the_vault_expects() {
        assertEquals(32, store().getOrCreate(videos).size)
    }

    @Test
    fun a_backend_that_holds_no_alias_reports_none() {
        assertFalse(backend.contains(SharingKeyAlias.of(videos)))
        store().getOrCreate(videos)
        assertTrue(backend.contains(SharingKeyAlias.of(videos)))
        store().destroy(videos)
        assertFalse(backend.contains(SharingKeyAlias.of(videos)))
    }
}
