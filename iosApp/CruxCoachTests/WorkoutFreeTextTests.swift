import CruxCoachCore
import XCTest
@testable import CruxCoach

/// Android's `WorkoutFreeTextTest`, run on Kotlin/Native against the exercise catalogue the app bundles: the free-text
/// workout parser is built from many regular expressions, and Kotlin/Native's regex engine differs from the JVM's in
/// corners (the negated `\p{…}` class bug of 2026-10-17). The JVM test alone would not see such a difference on iPhone.
final class WorkoutFreeTextTests: XCTestCase {

    private static var cached: ExerciseCatalog?

    private func catalog() throws -> ExerciseCatalog {
        if let c = Self.cached { return c }
        let resources = try XCTUnwrap(Bundle.main.resourceURL)
        let url = resources.appendingPathComponent("training/exercise_catalog.json")
        let text = try String(contentsOf: url, encoding: .utf8)
        let c = ExerciseCatalog.companion.parse(text: text, custom: [])
        Self.cached = c
        return c
    }

    private func parse(_ text: String) throws -> WorkoutTextParserResult {
        WorkoutTextParser.shared.parse(text: text, catalog: try catalog())
    }

    private func slugs(_ r: WorkoutTextParserResult) -> [String] {
        r.lines.map { $0.item.slug.isEmpty ? "new:" + $0.name : $0.item.slug }
    }

    private func items(_ r: WorkoutTextParserResult) -> String {
        r.lines.map { l in
            "\(l.item.slug) sets \(l.item.sets) reps \(l.item.repsMax?.intValue ?? -1) circuit \(l.circuit?.intValue ?? -1)"
        }.joined(separator: "; ")
    }

    func testSeveralExercisesOnOneLineNeedNoSeparators() throws {
        let r = try parse("Klimmzüge 5x5 +20kg Liegestütze 3x15 Plank 3x60s")
        XCTAssertEqual(slugs(r), ["pull.weighted_pull_up", "push.push_up", "core.front_plank"])
        XCTAssertEqual(r.lines[0].item.loadKg?.doubleValue, 20.0)
        XCTAssertEqual(r.lines[1].item.repsMax?.intValue, 15)
        XCTAssertEqual(r.lines[2].item.durationS?.intValue, 60)
    }

    func testNumbersMayComeFirstAndProseConnectorsSplit() throws {
        let first = try parse("3x10 Klimmzüge 3x15 Liegestütze 4x30s Plank")
        XCTAssertEqual(slugs(first), ["pull.pull_up", "push.push_up", "core.front_plank"])
        XCTAssertEqual(first.lines[2].item.sets, 4)
        let prose = try parse("Heute erst Klimmzüge 4x6 mit 10 kg Zusatzgewicht, dann Liegestütze 3x15 und danach Dips 3x8. Zum Schluss Plank 3x45s.")
        XCTAssertEqual(slugs(prose), ["pull.weighted_pull_up", "push.push_up", "push.dip", "core.front_plank"])
        XCTAssertEqual(prose.lines[0].item.loadKg?.doubleValue, 10.0)
        XCTAssertEqual(prose.lines[3].item.durationS?.intValue, 45)
        XCTAssertTrue(prose.lines.allSatisfy { !$0.isNew }, items(prose))
    }

    func testTyposAndTheOwnersExercises() throws {
        let typos = try parse("Klimzüge 5x5, Liegestüzte 3x15, Kniebäugen 3x10 40kg, Planck 3x60s, Hollow hlod 3x30s, Bankdrüken 3x8 30kg")
        XCTAssertEqual(slugs(typos), ["pull.pull_up", "push.push_up", "legs.back_squat", "core.front_plank", "core.hollow_hold",
                                      "push.dumbbell_bench_press"])
        let own = try parse("Situps mit Zusatzgewicht 3x15 10kg Kurzhantelschrägbankdrücken 3x10 20kg einarmiger Latzug links und rechts 3x12 25kg")
        XCTAssertEqual(slugs(own), ["core.weighted_sit_up", "push.dumbbell_incline_press", "pull.single_arm_lat_pulldown"])
        XCTAssertEqual(own.lines[2].item.sides, SideMode.both)
        XCTAssertEqual(own.lines[2].item.loadKg?.doubleValue, 25.0)
    }

    func testAndJeAndNamesOneAfterAnother() throws {
        let list = try parse("Klimmzüge, Liegestütze und Dips je 3x8")
        XCTAssertEqual(slugs(list), ["pull.pull_up", "push.push_up", "push.dip"])
        XCTAssertTrue(list.lines.allSatisfy { $0.item.sets == 3 && $0.item.repsMax?.intValue == 8 }, items(list))
        let names = try parse("Plank Hollow Hold 3x30s")
        XCTAssertEqual(slugs(names), ["core.front_plank", "core.hollow_hold"])
        let hang = try parse("Hangboard 20mm Leiste 7/3 x6 halbaufgestellt")
        XCTAssertEqual(hang.lines.count, 1, items(hang))
        XCTAssertEqual(hang.lines.first?.item.slug, "finger.repeaters")
        XCTAssertEqual(hang.lines.first?.item.edgeMm?.intValue, 20)
    }

    func testCircuitsInEveryForm() throws {
        let header = try parse("Zirkel, 3 Runden:\nKlimmzüge 8\nLiegestütze 15\nSit-ups 20\n\nPlank 3x60s")
        XCTAssertEqual(slugs(header), ["pull.pull_up", "push.push_up", "core.sit_up", "core.front_plank"])
        XCTAssertTrue(header.lines.prefix(3).allSatisfy { $0.circuit?.intValue == 1 && $0.item.sets == 3 }, items(header))
        XCTAssertNil(header.lines[3].circuit)

        let inline = try parse("3 Runden Zirkel: Klimmzüge 8, Liegestütze 15, Sit-ups 20, 1 min Pause nach jeder Runde, 15 s zwischen den Übungen")
        XCTAssertEqual(slugs(inline), ["pull.pull_up", "push.push_up", "core.sit_up"])
        XCTAssertTrue(inline.lines.allSatisfy {
            $0.circuit?.intValue == 1 && $0.item.sets == 3 && $0.item.roundRestS?.intValue == 60 && $0.item.circuitRestS?.intValue == 15
        }, items(inline))

        let named = try parse("Klimmzüge 8, Liegestütze 15 und Sit-ups 20 als Zirkel, 4 Durchgänge")
        XCTAssertTrue(named.lines.allSatisfy { $0.circuit?.intValue == 1 && $0.item.sets == 4 }, items(named))

        let superset = try parse("Bankdrücken 3x10 30kg im Wechsel mit Kurzhantelrudern 3x10 20kg")
        XCTAssertEqual(slugs(superset), ["push.dumbbell_bench_press", "pull.dumbbell_row"])
        XCTAssertTrue(superset.lines.allSatisfy { $0.circuit?.intValue == 1 }, items(superset))
    }

    func testCircuitRestsSurviveTypos() throws {
        let r = try parse("Zirkel 3 Runden: Klimmzüge 8, Liegestütze 15, 90 s Pause nach jeder Rande, 15 s zwichen den Übugen")
        XCTAssertEqual(slugs(r), ["pull.pull_up", "push.push_up"])
        XCTAssertTrue(r.lines.allSatisfy { $0.item.roundRestS?.intValue == 90 && $0.item.circuitRestS?.intValue == 15 }, items(r))
        let en = try parse("circuit 4 rounds: pull-ups 6, dips 10, rest 2 min betwen rouns")
        XCTAssertTrue(en.lines.allSatisfy { $0.circuit?.intValue == 1 && $0.item.roundRestS?.intValue == 120 }, items(en))
    }

    func testAWholeSessionWrittenFreely() throws {
        let r = try parse("""
            Oberkörper Montag
            Aufwärmen: 5 min Seilspringen, Armkreisen 2x10
            Hauptteil: Klimmzüge 5x5 +10kg, Schrägbankdrücken 4x8 22kg
            Zirkel 3 Runden: Face Pulls 15, Dips 10, Hollow Hold 30s, 90 s Pause nach jeder Runde
            Zum Schluss Klimmzüge 2x5
            """)
        XCTAssertEqual(r.title, "Oberkörper Montag")
        XCTAssertEqual(Array(slugs(r).dropFirst()), ["warmup.arm_circles", "pull.weighted_pull_up", "push.dumbbell_incline_press",
                                                     "antagonist.face_pull", "push.dip", "core.hollow_hold", "pull.pull_up"])
        XCTAssertTrue(r.lines[0].item.warmup && r.lines[1].item.warmup)
        XCTAssertTrue(r.lines[4..<7].allSatisfy { $0.circuit?.intValue == 1 && $0.item.roundRestS?.intValue == 90 }, items(r))
        XCTAssertNil(r.lines[7].circuit)
    }

    func testOneLinePerExerciseReadsAsBefore() throws {
        let r = try parse("Push Day\nLiegestütze 3x15\nDips 3x10 +10kg")
        XCTAssertEqual(r.title, "Push Day")
        XCTAssertEqual(slugs(r), ["push.push_up", "push.dip"])
    }

    /// Generous bounds on the simulator's debug framework: they catch a runaway (no index, quadratic splitting), not a
    /// slow runner. The sheet reads off the main thread anyway.
    func testTextsParseWithoutRunaway() throws {
        let line = "Klimzüge 5x5 +10kg Liegestüzte 3x15, Kniebäugen 3x10 40kg und Planck 3x60s. "
        var text = ""
        while text.count < Int(WorkoutTextParser.shared.MAX_CHARS) - line.count { text += line }
        _ = try parse(text)
        let start = Date()
        let long = try parse(text)
        let longMs = Date().timeIntervalSince(start) * 1000
        XCTAssertEqual(long.lines.count, Int(WorkoutTextParser.shared.MAX_LINES))
        XCTAssertLessThan(longMs, 15_000, "a \(text.count)-character text took \(Int(longMs)) ms")
        let typical = "Klimmzüge 5x5 +20kg, Liegestütze 3x15, Zirkel 3 Runden: Dips 10, Sit-ups 20, Plank 30s"
        _ = try parse(typical)
        let t0 = Date()
        for _ in 0..<10 { _ = try parse(typical) }
        let typicalMs = Date().timeIntervalSince(t0) * 100
        XCTAssertLessThan(typicalMs, 1_500, "a typical text took \(Int(typicalMs)) ms")
    }
}
