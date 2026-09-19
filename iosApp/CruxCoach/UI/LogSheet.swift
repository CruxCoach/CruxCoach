import CruxCoachCore
import SwiftUI

/// Log dialog: send/attempt, tries, quality, benchmark, comment — the Android form, no date or angle field.
struct LogSheet: View {
    let logger: LogAttemptPresenter
    let log: Observed<LogAttemptState>

    private var form: AscentFormState { log.value.ascent }

    var body: some View {
        Form {
            Picker(LI("log_outcome"), selection: Binding(get: { form.isSend }, set: { logger.updateIsSend(isSend: $0) })) {
                Text(LI("detail_sent")).tag(true)
                Text(LI("detail_attempt")).tag(false)
            }.pickerStyle(.segmented)
            Stepper(LI("log_tries", Int(form.bidCount)), value: Binding(get: { Int(form.bidCount) }, set: { logger.updateBidCount(count: Int32($0)) }), in: 1...999)
            if form.isSend {
                Picker(LI("log_quality"), selection: Binding(get: { Int(form.quality) }, set: { logger.updateQuality(quality: Int32($0)) })) {
                    ForEach(0...5, id: \.self) { Text($0 == 0 ? "–" : String(repeating: "★", count: $0)).tag($0) }
                }
                Toggle(LI("filter_benchmark_only"), isOn: Binding(get: { form.isBenchmark }, set: { logger.updateIsBenchmark(value: $0) }))
            }
            TextField(LI("log_comment"), text: Binding(get: { form.comment }, set: { logger.updateComment(comment: $0) }), axis: .vertical)
        }
        .navigationTitle(LI("detail_log"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { logger.dismissDialog() } }
            ToolbarItem(placement: .confirmationAction) { Button(L("action_save")) { logger.save() } }
        }
    }
}
