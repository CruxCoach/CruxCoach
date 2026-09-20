import CruxCoachCore
import XCTest
@testable import CruxCoach

/// End-to-end start-up on a simulator: identity in the Keychain, the encrypted
/// database open, repositories and every screen model reachable. This is the
/// closest thing to running the app that CI can do without a device.
final class AppCoreTests: XCTestCase {

    /// Starting the core opens the databases, and a process opens each database
    /// file once, so every test here shares one start — exactly as the app does.
    private func startCore() throws -> AppCore {
        let result = AppCore.companion.start(
            aead: CryptoKitAead(),
            secrets: KeychainSecretStore(service: "org.cruxcoach.ios.tests.core"),
            zstd: ZstdFileDecompressor(),
            deviceAuth: BiometricAuthenticator(),
            nip44: LibraryNip44Cipher()
        )
        XCTAssertEqual(result.failureCode, "", result.detail)
        return try XCTUnwrap(result.core)
    }

    func testAppStartsWithAnIdentityAndAnEncryptedDatabase() throws {
        let core = try startCore()
        XCTAssertEqual(core.pubkeyHex.count, 64, "a 32-byte x-only public key in hex")
        XCTAssertFalse(core.cipherVersion.isEmpty, "personal data must be SQLCipher-encrypted")
        XCTAssertTrue(core.pubkeyHex.allSatisfy { $0.isHexDigit && !$0.isUppercase })
    }

    func testTheSameIdentityComesBackOnTheNextStart() throws {
        // The key comes from the Keychain, so a second start must resolve to the
        // same account rather than silently creating a new one.
        let first = try startCore().pubkeyHex
        let second = try startCore().pubkeyHex
        XCTAssertEqual(first, second, "a restart must never mint a new identity")
        XCTAssertEqual(first.count, 64)
    }

    func testEveryScreenModelProducesAnInitialState() throws {
        let core = try startCore()

        let browser = core.makeBrowserScreen()
        let browserState = browser.currentState
        XCTAssertEqual(browserState.brandWire, "kilter")
        XCTAssertFalse(browserState.angle == 0, "the default angle is 40, as on Android")
        browser.close()

        let sync = core.syncScreen
        XCTAssertFalse(sync.brandWires.isEmpty)
        XCTAssertEqual(sync.currentState.rows.count, sync.brandWires.count)
        XCTAssertFalse(sync.currentState.running)

        let logbook = core.makeLogbookScreen()
        XCTAssertFalse(logbook.currentState.hasData, "a fresh install has an empty logbook")
        logbook.close()

        let history = core.makeHistoryScreen()
        XCTAssertTrue(history.currentState.entries.isEmpty)
        history.close()

        let detail = core.makeDetailScreen()
        XCTAssertEqual(detail.currentState.status, "loading")
        detail.close()

        // No catalogue is installed in a test run, so there is nothing to pick.
        XCTAssertTrue(core.boardOptions(brandWire: "kilter").isEmpty)
        XCTAssertFalse(core.boardOptions(brandWire: "moonboard").isEmpty, "MoonBoard variants need no catalogue")
        XCTAssertTrue(core.boardOptions(brandWire: "nonsense").isEmpty)
    }

    func testSettingsPersistThroughUserDefaults() throws {
        let core = try startCore()
        core.settings.gradeScale = "V_SCALE"
        XCTAssertEqual(core.settings.gradeScale, "V_SCALE")
        XCTAssertFalse(core.settings.usesFrenchGrades)
        core.settings.gradeScale = "FRENCH"
        XCTAssertTrue(core.settings.usesFrenchGrades)
    }

    func testBluetoothScreenReportsAnAdapterStateWithoutCrashing() throws {
        let core = try startCore()
        let ble = core.bleScreen
        let state = ble.currentState
        XCTAssertFalse(state.connected)
        XCTAssertEqual(state.connection, "disconnected")
        // Bluetooth is only powered up by activate(), so the state must be a
        // known code and must not be a connected one.
        XCTAssertTrue(["unknown", "resetting", "unsupported", "unauthorized", "poweredOff", "poweredOn"]
            .contains(state.adapter), state.adapter)
    }
}

/// The app draws board images and reads MoonBoard coordinate maps straight out
/// of the bundle, and shows German to German users. Both are packaging
/// decisions that only a bundled build can prove.
final class BundleContentsTests: XCTestCase {

    func testBoardImagesAndCoordinateMapsAreBundled() throws {
        let resources = try XCTUnwrap(Bundle.main.resourceURL)
        let kilter = resources.appendingPathComponent("board_images/board_10.webp")
        XCTAssertTrue(FileManager.default.fileExists(atPath: kilter.path), "Kilter 12x12 background missing")
        let moon = resources.appendingPathComponent("board_images/moonboard_2016.json")
        XCTAssertTrue(FileManager.default.fileExists(atPath: moon.path), "MoonBoard 2016 coordinate map missing")
        // Parsed with Foundation on purpose: this asserts the bundled file, not the Kotlin parser.
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: moon)) as? [String: Any]
        let holds = try XCTUnwrap(json?["holds"] as? [[String: Any]])
        XCTAssertFalse(holds.isEmpty)
        XCTAssertGreaterThan(try XCTUnwrap(json?["imageAspect"] as? Double), 0)
        XCTAssertNotNil(json?["image"] as? String)
    }

    func testEnglishAndGermanStringsAreBundled() throws {
        XCTAssertNotEqual(NSLocalizedString("board_browser_title", comment: ""), "board_browser_title",
                          "the generated Android string table is missing")
        XCTAssertNotEqual(NSLocalizedString("detail_light_up", tableName: "IOS", comment: ""), "detail_light_up",
                          "the iOS-only string table is missing")
        for language in ["en", "de"] {
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"), "\(language).lproj missing")
            let bundle = try XCTUnwrap(Bundle(path: path))
            XCTAssertNotEqual(bundle.localizedString(forKey: "board_browser_title", value: nil, table: "Localizable"),
                              "board_browser_title", "\(language): Android strings not localized")
            XCTAssertNotEqual(bundle.localizedString(forKey: "detail_light_up", value: nil, table: "IOS"),
                              "detail_light_up", "\(language): iOS strings not localized")
        }
    }
}

/// Link handling has no universal-link entitlement on a free team, so the
/// custom scheme and pasted URLs are the whole story and must parse identically.
final class DeepLinkTests: XCTestCase {

    private func startCore() throws -> AppCore {
        let result = AppCore.companion.start(
            aead: CryptoKitAead(),
            secrets: KeychainSecretStore(service: "org.cruxcoach.ios.tests.links"),
            zstd: ZstdFileDecompressor(),
            deviceAuth: BiometricAuthenticator(),
            nip44: LibraryNip44Cipher()
        )
        return try XCTUnwrap(result.core)
    }

    func testCruxCoachClimbLinksResolveAndForeignLinksDoNot() throws {
        let core = try startCore()
        let uuid = "0123456789abcdef0123456789abcdef"
        XCTAssertEqual(core.climbUuidFromLink(url: "cruxcoach://c/\(uuid)"), uuid)
        XCTAssertEqual(core.climbUuidFromLink(url: "https://cruxcoach.org/c/\(uuid)"), uuid)
        XCTAssertEqual(core.climbUuidFromLink(url: "https://evil.example/c/\(uuid)"), "")
        XCTAssertEqual(core.climbUuidFromLink(url: "cruxcoach://l/AAAA"), "", "playlist links have no screen yet")
        XCTAssertEqual(core.climbUuidFromLink(url: "not a url"), "")
        XCTAssertEqual(core.climbUuidFromLink(url: ""), "")
    }
}

extension AppCoreTests {
    func testTheAccountIdIsShownAsAnNpub() throws {
        let core = try startCore()
        XCTAssertTrue(core.npub.hasPrefix("npub1"), core.npub)
        XCTAssertEqual(core.npub.count, 63, "a bech32 npub is 63 characters")
    }
}

/// The backup screen reaches the network, so CI only asserts that it composes
/// and reports a clean idle state. A restore against a real relay is the
/// owner's test on a device, not something a sandboxed runner should attempt.
final class BackupCompositionTests: XCTestCase {
    func testBackupScreenComposesWithoutTouchingTheNetwork() throws {
        let result = AppCore.companion.start(
            aead: CryptoKitAead(),
            secrets: KeychainSecretStore(service: "org.cruxcoach.ios.tests.backup"),
            zstd: ZstdFileDecompressor(),
            deviceAuth: BiometricAuthenticator(),
            nip44: LibraryNip44Cipher()
        )
        let core = try XCTUnwrap(result.core)
        let state = core.backupScreen.currentState
        XCTAssertEqual(state.phase, BackupScreenState.companion.PHASE_IDLE)
        XCTAssertFalse(state.busy)
        XCTAssertFalse(state.hasBackup)
        XCTAssertNil(state.failureCode)
    }
}
