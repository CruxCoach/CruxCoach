import CruxCoachCore
import Foundation
import MetricKit

/// Installs the crash sources that end in Android's report file and format (Kotlin `CrashHooks`):
/// uncaught Kotlin exceptions, uncaught Objective-C exceptions, and MetricKit crash diagnostics
/// for signals neither hook sees. Nothing is sent from here; the next start offers the report.
enum CrashReporting {
    /// Once, from `didFinishLaunching`, before the core starts.
    static func install() {
        CrashHooks.companion.install()
        // A C function pointer: the closure must not capture anything.
        NSSetUncaughtExceptionHandler { exception in
            _ = CrashHooks.companion.recordObjCException(
                name: exception.name.rawValue,
                reason: exception.reason ?? "",
                callStack: exception.callStackSymbols)
        }
        MXMetricManager.shared.add(CrashDiagnosticsSubscriber.shared)
    }
}

/// MetricKit hands over crash diagnostics on a later launch (signals, watchdog kills). They become
/// the same report; Kotlin skips one the hooks already wrote for the same crash.
final class CrashDiagnosticsSubscriber: NSObject, MXMetricManagerSubscriber {
    static let shared = CrashDiagnosticsSubscriber()

    /// The call stack tree is JSON of binary offsets; a deep one is cut, as the trace is.
    private static let maxTreeCharacters = 40_000

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            guard let crashes = payload.crashDiagnostics, !crashes.isEmpty else { continue }
            let start: Int64 = Int64(payload.timeStampBegin.timeIntervalSince1970)
            let end: Int64 = Int64(payload.timeStampEnd.timeIntervalSince1970)
            for crash in crashes {
                let text: String = Self.reportText(crash)
                _ = CrashHooks.companion.recordDiagnostic(
                    text: text, windowStartEpochSeconds: start, windowEndEpochSeconds: end)
            }
        }
    }

    private static func reportText(_ crash: MXCrashDiagnostic) -> String {
        var lines: [String] = ["MetricKit crash diagnostic"]
        if let type = crash.exceptionType { lines.append("Exception type: \(type.intValue)") }
        if let code = crash.exceptionCode { lines.append("Exception code: \(code.intValue)") }
        if let signal = crash.signal { lines.append("Signal: \(signal.intValue)") }
        if let reason = crash.terminationReason { lines.append("Termination reason: \(reason)") }
        let json: Data = crash.callStackTree.jsonRepresentation()
        let tree: String = String(data: json, encoding: .utf8) ?? ""
        lines.append("Call stack tree:")
        lines.append(String(tree.prefix(maxTreeCharacters)))
        return lines.joined(separator: "\n") + "\n"
    }
}
