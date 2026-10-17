import XCTest

/// Walks the main screens on a simulator like a first-time user, asserts what each
/// screen must show, and keeps a screenshot of each one. CI exports the screenshots
/// into the build artifact and lists every test result as a public annotation.
///
/// The app runs in English and every label is looked up in the app's own English
/// string tables (copied into this bundle), so a reworded string cannot silently
/// turn a test into a no-op.
final class MainScreensUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
    }

    func test01FirstRunReachesTheBoardBrowser() throws {
        try reachBrowser()
        shot("01-browser")
    }

    func test02SettingsOpen() throws {
        try reachBrowser()
        try openFromMenu(Strings.l("settings_title"))
        XCTAssertTrue(app.navigationBars[Strings.l("settings_title")].waitForExistence(timeout: 15),
                      "Settings did not open:\n\(app.debugDescription)")
        shot("02-settings")
    }

    func test03LogbookOpens() throws {
        try reachBrowser()
        try openFromMenu(Strings.l("board_logbook_title"))
        XCTAssertTrue(app.navigationBars[Strings.l("board_logbook_title")].waitForExistence(timeout: 15),
                      "Logbook did not open:\n\(app.debugDescription)")
        shot("03-logbook")
    }

    func test04ListsOpen() throws {
        try reachBrowser()
        try openFromMenu(Strings.l("board_lists_title"))
        XCTAssertTrue(app.navigationBars[Strings.l("board_lists_title")].waitForExistence(timeout: 15),
                      "Lists did not open:\n\(app.debugDescription)")
        shot("04-lists")
    }

    func test05BoardMapOpens() throws {
        try reachBrowser()
        try openFromMenu(Strings.li("map_title"))
        XCTAssertTrue(app.maps.firstMatch.waitForExistence(timeout: 20),
                      "The board map shows no map:\n\(app.debugDescription)")
        shot("05-board-map")
    }

    func test06StatisticsOpen() throws {
        try reachBrowser()
        try openFromMenu(Strings.li("stats_title"))
        XCTAssertTrue(app.navigationBars[Strings.li("stats_title")].waitForExistence(timeout: 15),
                      "Statistics did not open:\n\(app.debugDescription)")
        shot("06-statistics")
    }

    // MARK: - Helpers

    /// First run shows the two-step setup; skipping it must land in the browser.
    private func reachBrowser() throws {
        let more = app.buttons[Strings.li("browser_more")]
        let skip = app.buttons[Strings.li("onboarding_skip")]
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline {
            if more.exists && more.isHittable { return }
            if skip.exists && skip.isHittable {
                shot("00-onboarding")
                skip.tap()
            }
            _ = more.waitForExistence(timeout: 2)
        }
        XCTFail("The board browser never appeared:\n\(app.debugDescription)")
        throw XCTSkip("browser not reachable")
    }

    private func openFromMenu(_ title: String) throws {
        app.buttons[Strings.li("browser_more")].tap()
        let item = app.buttons[title]
        XCTAssertTrue(item.waitForExistence(timeout: 10), "Menu has no \"\(title)\":\n\(app.debugDescription)")
        item.tap()
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

/// The app's English string tables, bundled into this test target.
enum Strings {
    private final class Marker {}
    private static var tables: [String: [String: String]] = [:]

    static func l(_ key: String) -> String { value(key, table: "Localizable") }
    static func li(_ key: String) -> String { value(key, table: "IOS") }

    private static func value(_ key: String, table: String) -> String {
        if tables[table] == nil {
            let url = Bundle(for: Marker.self).bundleURL.appendingPathComponent("en.lproj/\(table).strings")
            tables[table] = (NSDictionary(contentsOf: url) as? [String: String]) ?? [:]
        }
        guard let text = tables[table]?[key] else {
            XCTFail("No English string for \(key) in \(table).strings")
            return key
        }
        return text
    }
}
