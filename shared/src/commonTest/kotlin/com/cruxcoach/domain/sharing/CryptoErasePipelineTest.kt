package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FEAT-062 §9: the local cleanup pipeline. Key destruction always runs first —
 * if the process dies half way, what is left behind must already be
 * undecryptable rather than merely unindexed.
 */
class CryptoErasePipelineTest {

    @Test
    fun key_destruction_is_the_first_step() {
        assertEquals(CryptoEraseStep.DESTROY_KEYS, CryptoErasePipeline.ORDER.first())
    }

    @Test
    fun every_local_surface_named_in_the_contract_is_covered() {
        assertEquals(
            listOf(
                CryptoEraseStep.DESTROY_KEYS,
                CryptoEraseStep.DATABASE_ROWS,
                CryptoEraseStep.FILES,
                CryptoEraseStep.CACHES,
                CryptoEraseStep.THUMBNAILS,
                CryptoEraseStep.SEARCH_INDEX,
                CryptoEraseStep.NOTIFICATIONS,
            ),
            CryptoErasePipeline.ORDER,
        )
    }

    @Test
    fun a_run_executes_the_steps_in_order() {
        val seen = mutableListOf<CryptoEraseStep>()
        val outcome = CryptoErasePipeline.run { step -> seen += step }
        assertEquals(CryptoErasePipeline.ORDER, seen)
        assertTrue(outcome.completed)
        assertTrue(outcome.failedStep == null)
    }

    @Test
    fun a_failure_after_key_destruction_still_leaves_the_data_unreadable() {
        val seen = mutableListOf<CryptoEraseStep>()
        val outcome = CryptoErasePipeline.run { step ->
            seen += step
            if (step == CryptoEraseStep.THUMBNAILS) error("no space left on device")
        }
        assertEquals(CryptoEraseStep.THUMBNAILS, outcome.failedStep)
        assertTrue(outcome.keysDestroyed)
        assertTrue(!outcome.completed)
    }

    @Test
    fun a_failure_during_key_destruction_is_reported_as_keys_not_destroyed() {
        val outcome = CryptoErasePipeline.run { step ->
            if (step == CryptoEraseStep.DESTROY_KEYS) error("keystore unavailable")
        }
        assertEquals(CryptoEraseStep.DESTROY_KEYS, outcome.failedStep)
        assertTrue(!outcome.keysDestroyed)
    }

    @Test
    fun the_pipeline_is_honest_that_external_copies_are_out_of_reach() {
        assertTrue(CryptoErasePipeline.EXTERNAL_COPIES_ARE_UNRECOVERABLE)
    }
}
