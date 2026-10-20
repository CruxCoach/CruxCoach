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
        // A first rest asks for notifications; answer system prompts so they cannot block a flow.
        addUIInterruptionMonitor(withDescription: "System alert") { alert in
            for title in ["Allow", "Don’t Allow", "Don't Allow", "OK"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
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
                      "Settings did not open:\n\(screenTail())")
        shot("02-settings")
    }

    func test03LogbookOpens() throws {
        try reachBrowser()
        try openFromMenu(Strings.l("board_logbook_title"))
        XCTAssertTrue(app.navigationBars[Strings.l("board_logbook_title")].waitForExistence(timeout: 15),
                      "Logbook did not open:\n\(screenTail())")
        shot("03-logbook")
    }

    func test04ListsOpen() throws {
        try reachBrowser()
        try openFromMenu(Strings.l("board_lists_title"))
        XCTAssertTrue(app.navigationBars[Strings.l("board_lists_title")].waitForExistence(timeout: 15),
                      "Lists did not open:\n\(screenTail())")
        shot("04-lists")
    }

    func test05BoardMapOpens() throws {
        try reachBrowser()
        try openFromMenu(Strings.li("map_title"))
        XCTAssertTrue(app.maps.firstMatch.waitForExistence(timeout: 20),
                      "The board map shows no map:\n\(screenTail())")
        shot("05-board-map")
    }

    func test06StatisticsOpen() throws {
        try reachBrowser()
        try openFromMenu(Strings.li("stats_title"))
        XCTAssertTrue(app.navigationBars[Strings.li("stats_title")].waitForExistence(timeout: 15),
                      "Statistics did not open:\n\(screenTail())")
        shot("06-statistics")
    }

    /// The 0.2.4 training area: open it, put off the quick start, start today's
    /// suggestion and log the first set as planned.
    func test07TrainingTodayStartsAndLogsASet() throws {
        try reachBrowser()
        app.buttons[Strings.li("browser_more")].tap()
        try tapTrainingEntry()
        let later = app.buttons[Strings.l("trq_later")]
        if later.waitForExistence(timeout: 10) {
            shot("07a-training-quick-start")
            later.tap()
        }
        // Today asks first how strong you feel and whether anything hurts (Android 0.2.4), then suggests.
        let quickShow = app.buttons["today_quick_show"]
        if quickShow.waitForExistence(timeout: 20) {
            app.buttons["4"].firstMatch.tap()
            app.buttons[Strings.l("trt_quick_no")].firstMatch.tap()
            shot("07a-training-quick-checkin")
            quickShow.tap()
        }
        let start = app.buttons["today_suggestion_start"]
        XCTAssertTrue(start.waitForExistence(timeout: 30), "Today shows no suggestion to start:\n\(screenTail())")
        shot("07b-training-today")
        start.tap()
        // A rep set logs with "done"; a timed set (warm-up hang, plank) offers "done without timer".
        let done = app.buttons["player_done"]
        let withoutTimer = app.buttons[Strings.l("trp_done_without_timer")]
        var target: XCUIElement?
        let deadline = Date().addingTimeInterval(30)
        while target == nil && Date() < deadline {
            if done.exists { target = done } else if withoutTimer.exists { target = withoutTimer }
            else { _ = done.waitForExistence(timeout: 1) }
        }
        shot("07c-training-player")
        let set = try XCTUnwrap(target, "The training player shows no set to log:\n\(screenTail())")
        set.tap()
        shot("07d-training-set-logged")
    }

    func test08TrainingTabsOpen() throws {
        try reachBrowser()
        app.buttons[Strings.li("browser_more")].tap()
        try tapTrainingEntry()
        let later = app.buttons[Strings.l("trq_later")]
        if later.waitForExistence(timeout: 5) { later.tap() }
        for (index, key) in ["tr_tab_training", "tr_nav_fuel", "tr_tab_progress"].enumerated() {
            let tab = app.tabBars.buttons[Strings.l(key)]
            XCTAssertTrue(tab.waitForExistence(timeout: 10), "No tab \(key):\n\(screenTail())")
            tab.tap()
            shot("08\(index)-training-tab-\(key)")
        }
    }

    /// "Plan days" (Android 570820eee): tomorrow as a rest day and back with Undo, then a climbing day.
    func test09TrainingPlansDays() throws {
        try openTraining()
        let plan = app.buttons["today_plan_days"]
        XCTAssertTrue(plan.waitForExistence(timeout: 30), "Today has no plan-days button:\n\(screenTail())")
        plan.tap()
        XCTAssertTrue(app.navigationBars[Strings.l("trdp_title")].waitForExistence(timeout: 15),
                      "The planner did not open:\n\(screenTail())")
        let tomorrow = app.buttons["day_planner_day_" + Self.isoDay(offset: 1)]
        XCTAssertTrue(tomorrow.waitForExistence(timeout: 15), "The planner has no row for tomorrow:\n\(screenTail())")
        shot("09a-plan-days")

        tomorrow.tap()
        let rest = app.buttons["week_plan_option_rest"]
        XCTAssertTrue(rest.waitForExistence(timeout: 10), "The day chooser has no rest option:\n\(screenTail())")
        shot("09b-plan-days-chooser")
        rest.tap()
        let restLabel = Strings.l("trwo_plan_rest")
        XCTAssertTrue(eventually { tomorrow.label.contains(restLabel) }, "Tomorrow is not a rest day: \(tomorrow.label)")
        let undo = app.buttons["day_planner_undo"]
        XCTAssertTrue(undo.waitForExistence(timeout: 10), "No undo after a change:\n\(screenTail())")
        shot("09c-plan-days-rest")
        undo.tap()
        XCTAssertTrue(eventually { !tomorrow.label.contains(restLabel) }, "Undo left the rest day: \(tomorrow.label)")

        let later = app.buttons["day_planner_day_" + Self.isoDay(offset: 2)]
        XCTAssertTrue(later.waitForExistence(timeout: 10), "No row for the day after tomorrow:\n\(screenTail())")
        later.tap()
        let apply = app.buttons["week_plan_climb_apply"]
        XCTAssertTrue(apply.waitForExistence(timeout: 10), "The chooser offers no climbing day:\n\(screenTail())")
        if !apply.isHittable { app.swipeUp() }
        apply.tap()
        // A planned climbing day reads "<where> · <how hard>" (Android `climbPlanLabel`).
        XCTAssertTrue(eventually { later.label.contains(" · ") }, "No climbing day planned: \(later.label)")
        shot("09d-plan-days-climb")
    }

    /// A typed meal without separators splits into foods, and the review corrects amounts in one tap (Android
    /// e9e3069ce, b76427fba): counted foods get whole pieces, others shares of the amount.
    func test10TypedMealReviewCorrectsInOneTap() throws {
        try openTraining()
        let fuel = app.tabBars.buttons[Strings.l("tr_nav_fuel")]
        XCTAssertTrue(fuel.waitForExistence(timeout: 10), "No nutrition tab:\n\(screenTail())")
        fuel.tap()
        let add = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", Strings.l("tru_fuel_add"))).firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 20), "Nutrition has no add button:\n\(screenTail())")
        add.tap()
        // The tile's label may carry its symbol's name too.
        let describe = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", Strings.l("trf_describe"))).firstMatch
        XCTAssertTrue(describe.waitForExistence(timeout: 10), "No \"Describe\" tile:\n\(screenTail())")
        describe.tap()
        let field = app.descendants(matching: .any).matching(identifier: "meal_text_field").firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "No meal text field:\n\(screenTail())")
        field.tap()
        // English words: the simulator keyboard autocorrects in English (the same text is a JVM test case).
        field.typeText("2 eggs 1 banana 200ml milk")
        app.buttons[Strings.l("trf_describe_go")].firstMatch.tap()

        let amounts = app.textFields.matching(identifier: "meal_review_amount")
        XCTAssertTrue(eventually(seconds: 30) { amounts.count == 3 }, "Not three foods:\n\(screenTail())")
        let half = app.buttons[Strings.l("fvp_amount_scale_half")].firstMatch
        XCTAssertTrue(half.waitForExistence(timeout: 10), "No one-tap shares for the milk:\n\(screenTail())")
        XCTAssertTrue(app.buttons[Strings.l("fvp_piece_more")].firstMatch.exists, "No whole pieces for eggs or banana:\n\(screenTail())")
        // Amounts show in the device's units (the US simulator: 120 g ≈ 4.2 oz, 200 ml ≈ 6.8 fl oz), so the check is
        // the relation: every food has an amount, and one tap halves exactly one of them (the JVM test pins 200 → 100).
        let before = values(amounts)
        XCTAssertTrue(before.allSatisfy { (Self.number($0) ?? 0) > 0 }, "A food has no amount: \(before)")
        shot("10a-meal-review")
        half.tap()
        let halved: () -> Bool = {
            let now = self.values(amounts)
            guard now.count == before.count else { return false }
            let changed = zip(before, now).filter { $0.0 != $0.1 }
            guard changed.count == 1, let old = Self.number(changed[0].0), let new = Self.number(changed[0].1) else {
                return false
            }
            return abs(new - old / 2) <= max(0.06, old * 0.02)
        }
        XCTAssertTrue(eventually(halved), "One tap did not halve one amount: \(before) → \(values(amounts))")
        shot("10b-meal-review-half")
    }

    /// A workout typed as free text with a circuit (Android 8d98b494f): the preview groups the circuit under one line
    /// and "Add" puts both exercises into the new workout.
    func test11TypedWorkoutWithACircuit() throws {
        try openTraining()
        let training = app.tabBars.buttons[Strings.l("tr_tab_training")]
        XCTAssertTrue(training.waitForExistence(timeout: 10), "No training tab:\n\(screenTail())")
        training.tap()
        let new = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", Strings.l("trwo_new"))).firstMatch
        XCTAssertTrue(new.waitForExistence(timeout: 20), "No \"New workout\":\n\(screenTail())")
        new.tap()
        let open = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", Strings.l("trwo_text_open"))).firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 15), "The editor offers no typed entry:\n\(screenTail())")
        if !open.isHittable { app.swipeUp() }
        open.tap()
        let field = app.descendants(matching: .any).matching(identifier: "workout_text_field").firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "No workout text field:\n\(screenTail())")
        field.tap()
        // English words: the simulator keyboard autocorrects in English (the same text is a JVM and a K/N test case).
        field.typeText("Circuit 3 rounds: pull-ups 8, push-ups 15")

        // "Circuit · 3 rounds · 0:15 / 1:30": the start of Android's circuit line (`trcirc_group`, `trcirc_rounds`).
        let groupStart = (Strings.l("trcirc_group").components(separatedBy: "%1$@").first ?? "")
            + Strings.l("trcirc_rounds.other").replacingOccurrences(of: "%ld", with: "3")
        let header = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", groupStart)).firstMatch
        XCTAssertTrue(header.waitForExistence(timeout: 15), "No circuit line \"\(groupStart)\":\n\(screenTail())")
        let add = app.buttons[Strings.l("trwo_text_apply.other").replacingOccurrences(of: "%ld", with: "2")]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "Not two exercises to add:\n\(screenTail())")
        shot("11a-typed-workout-circuit")
        add.tap()

        XCTAssertTrue(eventually { !field.exists }, "The typed sheet stayed open:\n\(screenTail())")
        // The catalogue's English name of pull.pull_up (exercise_catalog.json).
        let pullUp = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Pull-Up")).firstMatch
        XCTAssertTrue(pullUp.waitForExistence(timeout: 10), "The new workout has no pull-ups:\n\(screenTail())")
        shot("11b-typed-workout-added")
    }

    // MARK: - Helpers

    /// Browser → menu → training area, past the quick start.
    private func openTraining() throws {
        try reachBrowser()
        app.buttons[Strings.li("browser_more")].tap()
        try tapTrainingEntry()
        let later = app.buttons[Strings.l("trq_later")]
        if later.waitForExistence(timeout: 10) { later.tap() }
    }

    /// Polls [condition] for up to [seconds]: labels and values follow the core a moment later.
    private func eventually(seconds: TimeInterval = 10, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return condition()
    }

    private func values(_ fields: XCUIElementQuery) -> [String] {
        fields.allElementsBoundByIndex.map { ($0.value as? String) ?? "" }
    }

    /// A shown amount as a number ("6.8", "6,8").
    private static func number(_ text: String) -> Double? {
        Double(text.replacingOccurrences(of: ",", with: "."))
    }

    /// The local date [offset] days from today as the app keys its days ("2026-10-20").
    private static func isoDay(offset: Int) -> String {
        let calendar = Calendar(identifier: .gregorian)
        let day = calendar.date(byAdding: .day, value: offset, to: Date()) ?? Date()
        let parts = calendar.dateComponents([.year, .month, .day], from: day)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    /// First run shows the two-step setup; skipping it must land in the browser.
    private func reachBrowser() throws {
        let more = app.buttons[Strings.li("browser_more")]
        let skip = app.buttons[Strings.li("onboarding_skip")]
        let deadline = Date().addingTimeInterval(60)
        var coveredSince: Date?
        while Date() < deadline {
            if more.exists && more.isHittable { return }
            // Present but covered: a sheet is on top. Record it, then swipe it away.
            if more.exists {
                if coveredSince == nil { coveredSince = Date() }
                if let since = coveredSince, Date().timeIntervalSince(since) > 5 {
                    shot("00-covered-browser")
                    print("Browser covered by:\n\(screenTail())")
                    app.swipeDown(velocity: .fast)
                    coveredSince = nil
                }
            }
            if skip.exists && skip.isHittable {
                shot("00-onboarding")
                skip.tap()
            }
            _ = more.waitForExistence(timeout: 2)
        }
        XCTFail("The board browser never appeared:\n\(screenTail())")
        throw XCTSkip("browser not reachable")
    }

    /// Menu items do not always carry their identifier into the UIMenu, so the label is the fallback.
    private func tapTrainingEntry() throws {
        let byId = app.buttons["browser_training"]
        let byLabel = app.buttons[Strings.l("tr_nav_training_nutrition")]
        if byId.waitForExistence(timeout: 5) { byId.tap(); return }
        XCTAssertTrue(byLabel.waitForExistence(timeout: 10), "No training entry in the menu:\n\(screenTail())")
        byLabel.tap()
    }

    private func openFromMenu(_ title: String) throws {
        app.buttons[Strings.li("browser_more")].tap()
        let item = app.buttons[title]
        XCTAssertTrue(item.waitForExistence(timeout: 10), "Menu has no \"\(title)\":\n\(screenTail())")
        item.tap()
    }

    /// The end of the element tree: a sheet or alert on top is listed last.
    private func screenTail() -> String {
        let tree = app.debugDescription
        return tree.count > 2800 ? "…" + String(tree.suffix(2800)) : tree
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
