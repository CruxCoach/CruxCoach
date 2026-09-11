package com.cruxcoach.android.sharing

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * FEAT-062 §11: the recovery core is not something a caller can reach.
 *
 * ## Why this is a test and not a comment
 *
 * `recover` was public and took `rootAlreadyVerified`, a payload, a device
 * public key, a backup generation and a signing cut-off — every one of them a
 * value the caller chose. Any screen, any future feature, any well-meaning
 * refactor could then run the restore and sovereign-reset path with the root
 * challenge simply switched off, enrolling a key this install does not hold
 * against a generation it made up. The KDoc said "set by importRecovery, which
 * already asked and got an answer", and a comment naming its intended caller is
 * not access control.
 *
 * So the surface is asserted here, mechanically. Java reflection is enough:
 * Kotlin's `public` compiles to a public method of the same name, `private`
 * does not compile to one at all, and `internal` compiles to a public method
 * with a mangled name — so a plain name-and-modifier check tells the three
 * apart.
 *
 * What stays public is the product's own doors: [SharingController.recoverThisDevice]
 * and [SharingController.importRecovery], which take a code and a file and
 * nothing else, read the device identity from this install, take the generation
 * from the file's own signed manifest, and verify the root challenge for real.
 */
class SharingRecoveryApiSurfaceTest {

    /**
     * Synthetic bridges are excluded, and deliberately.
     *
     * Kotlin emits `access$recover` and friends so that a lambda inside the
     * class can reach a private member. They are public in bytecode, marked
     * synthetic, and cannot be named from Kotlin at all — `private` is the real
     * gate and this is the compiler's plumbing behind it. What the check is
     * about is the surface a caller can actually write down.
     */
    private fun publicApi(type: Class<*>) =
        type.methods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun publicMethodsNamed(type: Class<*>, name: String) =
        publicApi(type).filter { it.name == name }

    @Test
    fun `the recovery core is not a public entry point`() {
        assertTrue(
            publicMethodsNamed(SharingController::class.java, "recover").isEmpty(),
            "recover must not be callable from outside: it is the path that mints an authority " +
                "generation and enrols a device, and every one of its terms came from the caller",
        )
    }

    /**
     * The two safe doors stay. Losing them silently would be the other failure:
     * a person who cannot recover their own estate is locked out of their data
     * just as thoroughly as one whose estate anybody can rewrite.
     */
    @Test
    fun `the product entry points are still public`() {
        assertTrue(publicMethodsNamed(SharingController::class.java, "recoverThisDevice").isNotEmpty())
        assertTrue(publicMethodsNamed(SharingController::class.java, "importRecovery").isNotEmpty())
    }

    /**
     * No public door anywhere on the controller may take a decrypted backup.
     *
     * A payload is somebody's whole permission history with its data keys in
     * it. A caller that could hand one in could restore a file this install
     * never authenticated — the recovery-code and root-challenge checks happen
     * *before* the payload is read, and passing one in skips straight past
     * them.
     */
    @Test
    fun `no public method takes a decrypted backup payload`() {
        val offenders = publicApi(SharingController::class.java)
            .filter { method ->
                method.parameterTypes.any {
                    it.name == "com.cruxcoach.domain.sharing.SharingBackupPayload"
                }
            }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "a decrypted payload must never be a caller's to supply, but $offenders take one",
        )
    }

    /**
     * And the repository's historical door is closed too.
     *
     * It writes a whole estate without checking a root challenge itself, on the
     * strength of its caller having done so. That is a reasonable division of
     * labour only while the door cannot be opened by anything else.
     */
    @Test
    fun `the repository historical commit is not public`() {
        assertTrue(
            publicMethodsNamed(SecureDbSharingRepository::class.java, "commitRecoveryFromBackup").isEmpty(),
            "commitRecoveryFromBackup verifies no challenge of its own, so it must not be reachable " +
                "from outside the flow that does",
        )
    }

    /**
     * Nothing may mint a recovery authorisation from a verifier it was handed.
     *
     * Three rounds tried to make an unforgeable *token*: a private constructor
     * (bypassed by an `internal` companion factory), a `verified: Boolean`
     * factory (the boolean again), and a factory that verified for itself —
     * still forgeable, because it took the verifier as a parameter and a caller
     * could pass one that always says yes.
     *
     * The token is gone. What remains is inert data, and the check happens at
     * the write, against a key the repository was constructed with. So the
     * invariant to assert is that no function anywhere hands back an
     * authorisation after being given a verifier: that shape is the bypass.
     */
    @Test
    fun `no function mints a recovery authorisation from a caller-supplied verifier`() {
        val offenders = listOf(SharingController::class.java, SecureDbSharingRepository::class.java)
            .flatMap { it.declaredMethods.asIterable() }
            .filter { !it.isSynthetic }
            .filter { method ->
                method.returnType.name.endsWith("RootRecoveryAuthorization") &&
                    method.parameterTypes.any {
                        it.name == "com.cruxcoach.domain.sharing.AsyncLedgerCrypto"
                    }
            }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "a verifier passed in per call is the forge this was meant to close, but $offenders take one",
        )
    }

    /**
     * And the door that writes must demand the authorisation, so there is no
     * unauthorised shape of the call left to reach for.
     */
    @Test
    fun `every root recovery door takes an authorisation, and no other door does`() {
        val doors = SecureDbSharingRepository::class.java.declaredMethods.filter { !it.isSynthetic }
        val rootDoors = doors.filter { it.name.startsWith("commitRootRecovery") }
        val ordinary = doors.filter { it.name.startsWith("commitRecovery") }

        assertTrue(rootDoors.isNotEmpty(), "there has to be a root recovery door at all")
        assertTrue(
            rootDoors.all { door ->
                door.parameterTypes.any { it.name.endsWith("RootRecoveryAuthorization") }
            },
            "a root door that takes no authorisation is one nothing has to authorise: " +
                rootDoors.map { it.name },
        )
        // And the ordinary batch has no nullable root parameter to reach for.
        assertTrue(
            ordinary.none { door ->
                door.parameterTypes.any { it.name.endsWith("RootRecoveryAuthorization") }
            },
            "the device-paired door must not accept a root authorisation: " + ordinary.map { it.name },
        )
    }

    /**
     * Nothing on the controller is called `restore` any more.
     *
     * This test's own KDoc has always said the product's doors are
     * `recoverThisDevice` and `importRecovery` — and for as long as it said so,
     * a third one called `restore` sat next to them doing none of what §11.8
     * requires, and the recovery screen called *that*. Naming was the whole
     * defect: the operation is a device-generation bump, and under the name
     * `restore` it read as the thing it is not.
     *
     * So the name is the assertion. A future `restore` on this class is either
     * a real recovery or the same mistake again, and neither should arrive
     * without somebody deciding which.
     */
    @Test
    fun `no controller entry point is called restore`() {
        val named = SharingController::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .map { it.name }
            .filter { it == "restore" || it.startsWith("restore\$") }

        assertTrue(
            named.isEmpty(),
            "a method called 'restore' is back on SharingController; the recovery doors are " +
                "recoverThisDevice and importRecovery, and the device-generation bump is " +
                "advanceDeviceGenerations: $named",
        )
    }

    /** And the operation that used to wear the name is still reachable, honestly. */
    @Test
    fun `the device generation bump is still there under its own name`() {
        assertTrue(
            SharingController::class.java.declaredMethods.any {
                it.name == "advanceDeviceGenerations"
            },
            "renaming it away entirely would drop an operation the tests still exercise",
        )
    }
}