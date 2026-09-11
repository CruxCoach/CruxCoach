package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FEAT-062 §8: restoring a sharing backup needs control of the signer *and* a
 * separate recovery code. This covers the code itself — its shape, its
 * checksum, and that a wrong one is refused.
 */
class SharingRecoveryCodeTest {

    private val bytes = ByteArray(20) { (it * 7 + 3).toByte() }

    @Test
    fun a_code_is_grouped_for_reading_aloud() {
        val code = SharingRecoveryCode.fromEntropy(bytes)
        assertEquals(6, code.split("-").size)
        code.split("-").forEach { assertEquals(4, it.length) }
    }

    @Test
    fun a_code_avoids_characters_that_are_read_wrong() {
        val code = SharingRecoveryCode.fromEntropy(bytes)
        listOf('O', 'I', 'L', '0', '1', 'U').forEach {
            assertFalse(code.contains(it), "ambiguous character $it must not appear")
        }
    }

    @Test
    fun different_entropy_gives_a_different_code() {
        assertNotEquals(
            SharingRecoveryCode.fromEntropy(bytes),
            SharingRecoveryCode.fromEntropy(ByteArray(20) { (it * 11 + 5).toByte() }),
        )
    }

    @Test
    fun a_generated_code_validates() {
        assertTrue(SharingRecoveryCode.isValid(SharingRecoveryCode.fromEntropy(bytes)))
    }

    @Test
    fun a_code_with_one_wrong_character_is_refused() {
        val code = SharingRecoveryCode.fromEntropy(bytes)
        val broken = code.replaceFirst(code.first(), if (code.first() == 'A') 'B' else 'A')
        assertFalse(SharingRecoveryCode.isValid(broken))
    }

    @Test
    fun formatting_is_forgiving_but_verification_is_not() {
        val code = SharingRecoveryCode.fromEntropy(bytes)
        assertTrue(SharingRecoveryCode.isValid(code.lowercase().replace("-", " ")))
        assertFalse(SharingRecoveryCode.isValid("ABCD-EFGH-JKMN-PQRS-TVWX-YZ23"))
    }

    @Test
    fun an_empty_or_short_code_is_refused() {
        assertFalse(SharingRecoveryCode.isValid(""))
        assertFalse(SharingRecoveryCode.isValid("ABCD"))
    }
}
