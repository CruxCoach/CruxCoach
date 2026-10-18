import CruxCoachCore
import XCTest
@testable import CruxCoach

/// The crash hooks write Android's report file; these round-trip it without crashing the host.
final class CrashReportFileTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("crash-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        CrashHooks.companion.useDirectory(path: directory.path)
    }

    override func tearDownWithError() throws {
        CrashHooks.companion.clearReports()
        CrashHooks.companion.useDefaultDirectory()
        try? FileManager.default.removeItem(at: directory)
    }

    func testObjectiveCExceptionReportRoundTrips() throws {
        let written = CrashHooks.companion.recordObjCException(
            name: "NSInvalidArgumentException",
            reason: "unrecognized selector",
            callStack: ["0 CoreFoundation 0x0000000180123456 __exceptionPreprocess + 164",
                        "1 /var/mobile/Containers/Data/Application/1B2C3D4E-AAAA-BBBB-CCCC-DDDDEEEEFFFF/tmp/x"])
        XCTAssertTrue(written)
        let fileURL = directory.appendingPathComponent("crash_log.txt")
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))

        let report = try XCTUnwrap(CrashHooks.companion.readPendingReport())
        XCTAssertTrue(report.hasPrefix("--- CruxCoach Crash Report ---\nTime: "), report)
        XCTAssertTrue(report.contains("\niOS: \(UIDevice.current.systemVersion)\n"), report)
        XCTAssertTrue(report.contains("\nDevice: "), report)
        XCTAssertTrue(report.contains("\n--- Stack Trace ---\nNSInvalidArgumentException: unrecognized selector\n"), report)
        XCTAssertTrue(report.contains("\tat 0 CoreFoundation"), report)
        XCTAssertFalse(report.contains("1B2C3D4E"), "install paths are stripped")
        XCTAssertEqual(report, try String(contentsOf: fileURL, encoding: .utf8))
    }

    func testANewReportKeepsOnePreviousReport() throws {
        XCTAssertTrue(CrashHooks.companion.recordObjCException(name: "First", reason: "", callStack: []))
        XCTAssertTrue(CrashHooks.companion.recordObjCException(name: "Second", reason: "", callStack: []))
        let previous = try String(contentsOf: directory.appendingPathComponent("crash_log_prev.txt"), encoding: .utf8)
        XCTAssertTrue(previous.contains("\nFirst\n"), previous)
        let current = try XCTUnwrap(CrashHooks.companion.readPendingReport())
        XCTAssertTrue(current.contains("\nSecond\n"), current)
    }

    func testMetricKitDiagnosticOfTheSameCrashIsNotWrittenTwice() throws {
        XCTAssertTrue(CrashHooks.companion.recordObjCException(name: "Hooked", reason: "", callStack: []))
        let now = Int64(Date().timeIntervalSince1970)
        XCTAssertFalse(CrashHooks.companion.recordDiagnostic(
            text: "MetricKit crash diagnostic\nSignal: 6\n",
            windowStartEpochSeconds: now - 60, windowEndEpochSeconds: now + 60))
        XCTAssertTrue(try XCTUnwrap(CrashHooks.companion.readPendingReport()).contains("Hooked"))

        // A diagnostic for an older crash no hook saw is kept.
        XCTAssertTrue(CrashHooks.companion.recordDiagnostic(
            text: "MetricKit crash diagnostic\nSignal: 11\n",
            windowStartEpochSeconds: now - 100_000, windowEndEpochSeconds: now - 90_000))
        XCTAssertTrue(try XCTUnwrap(CrashHooks.companion.readPendingReport()).contains("Signal: 11"))
    }

    func testTheAppInstalledTheObjectiveCHandlerAtLaunch() {
        // The test host runs the app's didFinishLaunching, which installs the hooks.
        XCTAssertNotNil(NSGetUncaughtExceptionHandler())
    }
}
