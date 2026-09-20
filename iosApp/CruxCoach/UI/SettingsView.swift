import CruxCoachCore
import SwiftUI

/// The settings that already take effect on iOS. Uses Android's preference keys and values.
struct SettingsView: View {
    let core: AppCore
    @State private var gradeScale: String
    @State private var ledMode: String

    init(core: AppCore) {
        self.core = core
        _gradeScale = State(initialValue: core.settings.gradeScale)
        _ledMode = State(initialValue: core.settings.moonBoardLedMode)
    }

    var body: some View {
        Form {
            Section(LI("settings_display")) {
                Picker(LI("settings_grade_scale"), selection: $gradeScale) {
                    Text("Fontainebleau").tag("FRENCH")
                    Text("V-Scale").tag("V_SCALE")
                }
                Text(LI("settings_grade_restart")).font(.footnote).foregroundStyle(.secondary)
            }
            Section("MoonBoard") {
                Picker(LI("settings_led_position"), selection: $ledMode) {
                    Text(LI("settings_led_below")).tag("BELOW")
                    Text(LI("settings_led_above")).tag("ABOVE")
                    Text(LI("settings_led_both")).tag("BOTH")
                }
            }
            Section(LI("settings_account")) {
                LabeledContent(LI("settings_pubkey")) {
                    Text(core.npub.isEmpty ? core.pubkeyHex : core.npub)
                        .font(.footnote.monospaced())
                        .textSelection(.enabled)
                        .lineLimit(3)
                }
                Text(LI("settings_account_hint")).font(.footnote).foregroundStyle(.secondary)
            }
            Section(LI("settings_about")) {
                LabeledContent("CruxCoach iOS", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                LabeledContent(LI("settings_storage"), value: "SQLCipher \(core.cipherVersion)")
                Text(LI("settings_preview_notice")).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle(L("settings_title"))
        .onChange(of: gradeScale) { _, value in core.settings.gradeScale = value }
        .onChange(of: ledMode) { _, value in core.settings.moonBoardLedMode = value }
    }
}
