import CruxCoachCore
import SwiftUI

/// Log dialog: send/attempt, tries, quality, benchmark, comment — the Android form, no date or angle field.
struct LogSheet: View {
    let model: DetailScreenModel
    let ui: DetailScreenState

    var body: some View {
        Form {
            Picker(LI("log_outcome"), selection: Binding(get: { ui.logIsSend }, set: { model.setLogIsSend(isSend: $0) })) {
                Text(LI("detail_sent")).tag(true)
                Text(LI("detail_attempt")).tag(false)
            }
            .pickerStyle(.segmented)
            Stepper(LI("log_tries", Int(ui.logTries)),
                    value: Binding(get: { Int(ui.logTries) }, set: { model.setLogTries(count: Int32($0)) }),
                    in: 1...999)
            if ui.logIsSend {
                Picker(LI("log_quality"), selection: Binding(get: { Int(ui.logQuality) }, set: { model.setLogQuality(quality: Int32($0)) })) {
                    ForEach(0...5, id: \.self) { value in
                        Text(value == 0 ? "–" : String(repeating: "★", count: value)).tag(value)
                    }
                }
                Toggle(LI("filter_benchmark_only"), isOn: Binding(get: { ui.logBenchmark }, set: { model.setLogBenchmark(value: $0) }))
            }
            TextField(LI("log_comment"), text: Binding(get: { ui.logComment }, set: { model.setLogComment(comment: $0) }), axis: .vertical)
        }
        .navigationTitle(LI("detail_log"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { model.dismissLogDialog() } }
            ToolbarItem(placement: .confirmationAction) { Button(L("action_save")) { model.saveLog() } }
        }
    }
}
