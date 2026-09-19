import CruxCoachCore
import SwiftUI

/// The settings that already have an effect on iOS. Uses Android's preference keys and values.
struct SettingsView: View {
    let core: AppCore
    @State private var gradeScale: String
    @State private var ledMode: String

    init(core: AppCore) {
        self.core = core
        _gradeScale = State(initialValue: core.platform.keyValues.getString(key: "grade_scale") ?? "FRENCH")
        _ledMode = State(initialValue: core.platform.keyValues.getString(key: "moonboard_led_mode") ?? "BELOW")
    }

    var body: some View {
        Form {
            Section(LI("settings_display")) {
                Picker(LI("settings_grade_scale"), selection: $gradeScale) {
                    Text("Fontainebleau").tag("FRENCH"); Text("V-Scale").tag("V_SCALE")
                }
            }
            Section("MoonBoard") {
                Picker(LI("settings_led_position"), selection: $ledMode) {
                    Text(LI("settings_led_below")).tag("BELOW"); Text(LI("settings_led_above")).tag("ABOVE"); Text(LI("settings_led_both")).tag("BOTH")
                }
            }
            Section(LI("settings_account")) {
                LabeledContent(LI("settings_pubkey")) { Text(core.pubkeyHex).font(.footnote.monospaced()).textSelection(.enabled) }
                Text(LI("settings_account_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section(LI("settings_about")) {
                LabeledContent("CruxCoach iOS", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                LabeledContent(LI("settings_storage"), value: "SQLCipher \(core.cipherVersion)")
                Text(LI("settings_preview_notice")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(L("settings_title"))
        .onChange(of: gradeScale) { _, value in core.platform.keyValues.putString(key: "grade_scale", value: value) }
        .onChange(of: ledMode) { _, value in core.platform.keyValues.putString(key: "moonboard_led_mode", value: value) }
    }
}
