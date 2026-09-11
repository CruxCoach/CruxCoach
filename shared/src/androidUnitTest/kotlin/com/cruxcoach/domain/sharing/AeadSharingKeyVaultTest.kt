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
 * FEAT-062 §7: random per-category and per-object data keys, authenticated
 * encryption, wrapped-key handles and a crypto erase that destroys the key
 * before it touches the data.
 */
class AeadSharingKeyVaultTest {

    private val store = InMemoryWrappingKeyStore()
    private val vault = AeadSharingKeyVault(store)

    private val videos = KeyHandle(KeyScope.CATEGORY, SharingCategory.VIDEOS.name)
    private val notes = KeyHandle(KeyScope.CATEGORY, SharingCategory.PRIVATE_NOTES.name)
    private val oneVideo = KeyHandle(KeyScope.OBJECT, "video-42")

    private val plaintext = "Trainingsplan Woche 3".encodeToByteArray()

    @Test
    fun a_data_key_is_random_and_never_repeats_across_handles() {
        val a = vault.createDataKey(videos)
        val b = vault.createDataKey(notes)
        assertNotEquals(a.wrappedBytes.toList(), b.wrappedBytes.toList())
    }

    @Test
    fun two_data_keys_for_the_same_handle_are_still_distinct() {
        val a = vault.createDataKey(videos)
        val b = vault.createDataKey(videos)
        assertNotEquals(a.wrappedBytes.toList(), b.wrappedBytes.toList())
    }

    @Test
    fun sealing_and_opening_round_trips() {
        val key = vault.createDataKey(videos)
        val sealed = vault.seal(key, plaintext, aad = videos.aad())
        assertContentEquals(plaintext, vault.open(key, sealed, aad = videos.aad()))
    }

    @Test
    fun the_ciphertext_never_contains_the_plaintext() {
        val key = vault.createDataKey(videos)
        val sealed = vault.seal(key, plaintext, aad = videos.aad())
        assertFalse(sealed.bytes.asList().windowed(plaintext.size).any { it == plaintext.toList() })
    }

    @Test
    fun a_flipped_ciphertext_bit_is_rejected_not_returned() {
        val key = vault.createDataKey(videos)
        val sealed = vault.seal(key, plaintext, aad = videos.aad())
        val tampered = SealedPayload(sealed.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertFailsWith<SharingCryptoException> { vault.open(key, tampered, aad = videos.aad()) }
    }

    @Test
    fun ciphertext_cannot_be_moved_to_another_category() {
        val key = vault.createDataKey(videos)
        val sealed = vault.seal(key, plaintext, aad = videos.aad())
        assertFailsWith<SharingCryptoException> { vault.open(key, sealed, aad = notes.aad()) }
    }

    @Test
    fun another_handles_key_cannot_open_the_payload() {
        val videoKey = vault.createDataKey(videos)
        val noteKey = vault.createDataKey(notes)
        val sealed = vault.seal(videoKey, plaintext, aad = videos.aad())
        assertFailsWith<SharingCryptoException> { vault.open(noteKey, sealed, aad = videos.aad()) }
    }

    @Test
    fun destroying_the_wrapping_key_makes_existing_ciphertext_unopenable() {
        val key = vault.createDataKey(oneVideo)
        val sealed = vault.seal(key, plaintext, aad = oneVideo.aad())

        vault.destroy(oneVideo)

        assertNull(store.get(oneVideo))
        assertFailsWith<SharingCryptoException> { vault.open(key, sealed, aad = oneVideo.aad()) }
    }

    @Test
    fun destroying_one_handle_leaves_the_others_readable() {
        val videoKey = vault.createDataKey(videos)
        val noteKey = vault.createDataKey(notes)
        val sealedNote = vault.seal(noteKey, plaintext, aad = notes.aad())

        vault.destroy(videos)

        assertNull(store.get(videos))
        assertContentEquals(plaintext, vault.open(noteKey, sealedNote, aad = notes.aad()))
    }

    @Test
    fun destroying_a_handle_twice_is_not_an_error() {
        vault.createDataKey(videos)
        vault.destroy(videos)
        vault.destroy(videos)
        assertNull(store.get(videos))
    }

    // --------------------------------------------------------- no leakage

    @Test
    fun a_wrapped_key_never_prints_its_material() {
        val key = vault.createDataKey(videos)
        val text = key.toString()
        assertFalse(text.contains(key.wrappedBytes.joinToString("")))
        assertTrue(text.contains("redacted"))
    }

    @Test
    fun a_sealed_payload_never_prints_its_bytes() {
        val key = vault.createDataKey(videos)
        val sealed = vault.seal(key, plaintext, aad = videos.aad())
        assertTrue(sealed.toString().contains("redacted"))
        assertFalse(sealed.toString().contains("Trainingsplan"))
    }

    @Test
    fun a_key_handle_may_be_printed_because_it_is_not_secret() {
        assertEquals("KeyHandle(CATEGORY:VIDEOS)", videos.toString())
    }
}
