package com.cruxcoach.app.ui

import com.cruxcoach.app.identity.KeyImport
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.platform.DeviceAuthenticator
import com.cruxcoach.app.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account screen's ncryptsec password flow and recovery-word import (Android KeyImportViewModel). */
class KeyScreenImportFormatsTest {
    private val ncryptsec =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
    private val ncryptsecKey = "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683"
    private val words = "leader monkey parrot ring guide accident before fence cannon height naive bean"
    private val wordsKey = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val wordsNpub = "npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu"

    private object NoAuth : DeviceAuthenticator {
        override fun authenticate(reason: String, onResult: (Boolean) -> Unit) = onResult(false)
    }

    @Test
    fun `an ncryptsec asks for its password, survives a wrong one and imports with the right one`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var stored: ByteArray? = null
        val model = KeyScreenModel(
            scope = scope,
            activePubkeyProvider = { stored?.let { NostrKeys.publicKeyHex(it) } },
            secretKeyProvider = { stored?.copyOf() },
            applyKey = { key -> stored = key.copyOf(); true },
            deviceAuth = NoAuth,
            worker = UnconfinedTestDispatcher(testScheduler),
            maxScryptLogN = 16,
        )
        model.updateInput(ncryptsec)
        assertEquals(KeyImport.Format.NCRYPTSEC.name, model.currentState.detectedFormat)
        model.preview()
        assertTrue(model.currentState.needsPassword, "Android's password dialog")
        assertNull(model.currentState.failureCode)
        assertNull(model.currentState.previewNpub)

        model.submitPassword("not nostr")
        advanceUntilIdle()
        assertFalse(model.currentState.isWorking)
        assertFalse(model.currentState.needsPassword)
        assertEquals(KeyImport.Failure.WRONG_PASSWORD.name, model.currentState.failureCode)
        model.confirmImport()
        assertNull(stored, "a wrong password never yields a key to store")

        model.preview()
        assertTrue(model.currentState.needsPassword)
        model.cancelPassword()
        assertFalse(model.currentState.needsPassword)
        // submitPassword without the prompt open does nothing.
        model.submitPassword("nostr")
        assertNull(model.currentState.previewNpub)

        model.preview()
        model.submitPassword("nostr")
        advanceUntilIdle()
        val npub = assertNotNull(model.currentState.previewNpub)
        assertNull(model.currentState.failureCode)
        assertNull(stored, "nothing is stored before the user confirms")
        model.confirmImport()
        assertEquals(ncryptsecKey, assertNotNull(stored).toHex())
        assertTrue(model.currentState.imported)
        assertEquals(npub, model.currentState.activeNpub)
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `an ncryptsec costlier than the phone allows is refused before asking for a password`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val model = KeyScreenModel(
            scope = scope,
            activePubkeyProvider = { null },
            secretKeyProvider = { null },
            applyKey = { true },
            deviceAuth = NoAuth,
            worker = UnconfinedTestDispatcher(testScheduler),
            maxScryptLogN = 15,
        )
        model.updateInput(ncryptsec)
        model.preview()
        assertFalse(model.currentState.needsPassword)
        assertEquals(KeyImport.Failure.NCRYPTSEC_TOO_EXPENSIVE.name, model.currentState.failureCode)
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `recovery words are derived in the background and previewed`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var stored: ByteArray? = null
        val model = KeyScreenModel(
            scope = scope,
            activePubkeyProvider = { null },
            secretKeyProvider = { null },
            applyKey = { key -> stored = key.copyOf(); true },
            deviceAuth = NoAuth,
            worker = StandardTestDispatcher(testScheduler),
            maxScryptLogN = 16,
        )
        model.updateInput(words)
        model.preview()
        assertTrue(model.currentState.isWorking, "Android's \"Checking key…\"")
        model.confirmImport()
        assertNull(stored, "confirm is ignored while the key is still being derived")
        advanceUntilIdle()
        assertFalse(model.currentState.isWorking)
        assertEquals(wordsNpub, model.currentState.previewNpub)
        model.confirmImport()
        assertEquals(wordsKey, assertNotNull(stored).toHex())

        model.updateInput(words.replace("bean", "beans"))
        model.preview()
        advanceUntilIdle()
        assertEquals(KeyImport.Failure.UNKNOWN_MNEMONIC_WORD.name, model.currentState.failureCode)
        scope.coroutineContext.cancelChildren()
    }

    @Test
    fun `a derivation that finishes after the input changed is dropped`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var stored: ByteArray? = null
        val model = KeyScreenModel(
            scope = scope,
            activePubkeyProvider = { null },
            secretKeyProvider = { null },
            applyKey = { key -> stored = key.copyOf(); true },
            deviceAuth = NoAuth,
            worker = StandardTestDispatcher(testScheduler),
            maxScryptLogN = 16,
        )
        model.updateInput(words)
        model.preview()
        assertTrue(model.currentState.isWorking)
        model.updateInput("$words ")
        assertFalse(model.currentState.isWorking)
        advanceUntilIdle()
        assertNull(model.currentState.previewNpub, "the stale result must not reach the screen")
        model.confirmImport()
        assertNull(stored)

        model.preview()
        model.cancelImport()
        advanceUntilIdle()
        assertNull(model.currentState.previewNpub)
        assertFalse(model.currentState.isWorking)
        scope.coroutineContext.cancelChildren()
    }
}
