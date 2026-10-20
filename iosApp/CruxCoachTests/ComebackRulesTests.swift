import CruxCoachCore
import XCTest
@testable import CruxCoach

/// The comeback plan's shared content and rules (FEAT-072), run on Kotlin/Native from Swift: the stage ladders, the
/// warning-sign urgency, the training ids the player and the diary rely on, and the wording rule of the spec
/// (no healing or treatment words; the notice in both languages). `:shared`'s `ComebackTest` runs the full rules
/// natively too; these cases check what the iPhone app itself reads through the framework.
final class ComebackRulesTests: XCTestCase {

    private func allFindings() -> [InjuryFinding] {
        let values = InjuryFinding.values()
        return (0..<values.size).compactMap { values.get(index: $0) }
    }

    private func allSigns() -> [WarningSign] {
        let values = WarningSign.values()
        return (0..<values.size).compactMap { values.get(index: $0) }
    }

    func testTheNoticeSaysItIsNoSubstituteInBothLanguages() {
        let notice = ComebackContent.shared.NOT_A_SUBSTITUTE
        XCTAssertTrue(notice.de.contains("Kein Ersatz für den Arztbesuch"), notice.de)
        XCTAssertTrue(notice.de.contains("Physio"), notice.de)
        XCTAssertTrue(notice.en.lowercased().contains("not a substitute"), notice.en)
        XCTAssertEqual(notice.of(language: "de-DE"), notice.de)
        XCTAssertEqual(notice.of(language: "en"), notice.en)
        // The app's own string (every comeback surface shows it) says the same.
        XCTAssertFalse(L("cb_not_a_substitute").isEmpty)
        XCTAssertNotEqual(L("cb_not_a_substitute"), "cb_not_a_substitute")
    }

    func testTheFingerStagesFollowTheResearchOrder() {
        let stages = ComebackContent.shared.FINGER.stages
        XCTAssertEqual(stages.count, 6)
        XCTAssertEqual(stages.map { $0.climbing.name }, ["NONE", "NONE", "EASY", "MODERATE", "HARD", "FULL"])
        // Open hand first: no crimp item for the injured hand in stages 0 and 1.
        for stage in stages.prefix(2) {
            for session in stage.sessions {
                for item in session.items where item.side.name == "INJURED" {
                    let grip = item.grip?.name ?? ""
                    XCTAssertTrue(grip != "HALF_CRIMP" && grip != "FULL_CRIMP", "\(item.slug) \(grip)")
                }
            }
        }
        // Full crimp is optional and described, never planned.
        let planned = stages.flatMap { s in s.sessions.flatMap { $0.items } }
        XCTAssertFalse(planned.contains { $0.grip?.name == "FULL_CRIMP" })
    }

    func testEveryLadderHasSixStagesAndSources() {
        let ladders = ComebackContent.shared.all
        XCTAssertGreaterThanOrEqual(ladders.count, 11)
        for ladder in ladders where ladder.id.name != "OTHER" {
            XCTAssertEqual(ladder.stages.count, 6, ladder.id.name)
            XCTAssertFalse(ladder.sources.isEmpty, ladder.id.name)
        }
        XCTAssertEqual(ComebackContent.shared.ladderFor(region: .elbow, finding: .medialElbow).id.name, "ELBOW_MEDIAL")
        XCTAssertEqual(ComebackContent.shared.ladderFor(region: .knee, finding: .hamstring).id.name, "HAMSTRING")
        XCTAssertTrue(ComebackContent.shared.hasPlan(region: .finger))
        XCTAssertFalse(ComebackContent.shared.hasPlan(region: .skin))
    }

    func testWarningSignsFitTheRegionAndTheMostUrgentWins() {
        let finger = Comeback.shared.signsFor(region: .finger)
        XCTAssertTrue(finger.contains(.bowstringing))
        XCTAssertTrue(finger.contains(.deformity))
        XCTAssertFalse(finger.contains(.kneeLocksOrSwelled))
        XCTAssertEqual(Comeback.shared.urgency(signs: [.hadItBefore, .numbOrCold])?.name, "EMERGENCY")
        XCTAssertEqual(Comeback.shared.urgency(signs: [.hadItBefore, .popWithSwelling])?.name, "DAYS")
        XCTAssertNil(Comeback.shared.urgency(signs: []))
        for sign in allSigns() {
            let text = ComebackTexts.shared.sign(s: sign)
            XCTAssertFalse(text.de.isEmpty || text.en.isEmpty, sign.name)
        }
    }

    func testAComebackTrainingIdNamesItsInjuryAndSession() {
        let id = Comeback.shared.routineId(injuryId: "i1", stage: 2, session: 1)
        XCTAssertTrue(id.hasPrefix(Comeback.shared.ROUTINE_PREFIX), id)
        // Kotlin's Pair as text: (injury id, session index).
        XCTAssertEqual(Comeback.shared.parseRoutineId(id: id)?.description, "(i1, 1)")
        XCTAssertNil(Comeback.shared.parseRoutineId(id: "routine-1"))
        XCTAssertNil(Comeback.shared.parseRoutineId(id: nil))
    }

    /// research/safety-regulation.md §4.7: a training aid, so no treatment or healing words and no promises.
    func testNoTextPromisesHealingOrTreatment() {
        let forbiddenDe = ["Reha", "Therapie", "Behandlungsplan", " heilt", "geheilt", "Diagnose", "garantiert", "freigegeben", "erlaubt", "sicher ist"]
        let forbiddenEn = ["rehab", "therapy", "cures", "diagnosis", "guaranteed", "cleared", "allowed", "is safe"]
        var texts: [Txt] = []
        for ladder in ComebackContent.shared.all {
            texts.append(ladder.evidence)
            for s in ladder.stages {
                texts += [s.title, s.goal, s.climbingText]
                texts += s.doThis
                texts += s.nextWhen
                texts += s.avoid
            }
        }
        for f in allFindings() {
            texts += ComebackTexts.shared.findingNotes(f: f)
            if let course = ComebackTexts.shared.typicalCourse(f: f) { texts.append(course) }
        }
        for sign in allSigns() {
            texts.append(ComebackTexts.shared.sign(s: sign))
            if let why = ComebackTexts.shared.signWhy(s: sign) { texts.append(why) }
        }
        texts += [ComebackContent.shared.NOT_A_SUBSTITUTE, ComebackTexts.shared.GELATIN, ComebackTexts.shared.UNDER_18,
                  ComebackTexts.shared.NOTHING_TICKED]
        XCTAssertGreaterThan(texts.count, 100)
        for t in texts {
            for w in forbiddenDe { XCTAssertFalse(t.de.contains(w), "\"\(w)\" in: \(t.de)") }
            for w in forbiddenEn { XCTAssertFalse(t.en.lowercased().contains(w), "\"\(w)\" in: \(t.en)") }
            if t.de.contains("verheilt") { XCTAssertTrue(t.de.contains("nicht"), t.de) }
            if t.en.contains("healed") { XCTAssertTrue(t.en.contains("not"), t.en) }
        }
    }
}
