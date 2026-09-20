import CruxCoachCore
import SwiftUI

/// The settings that have an effect on iOS, on Android's preference keys.
/// Anything Android offers that cannot work here is left out rather than shown
/// as a switch that does nothing.
struct SettingsDetailView: View {
    let core: AppCore
    @State private var host: ScreenHost<SettingsScreenModel, SettingsScreenState>?
    @State private var confirmCatalogueDelete = false
    @State private var confirmUserDelete = false
    @State private var deleteBrands: Set<String> = []

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("settings_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeSettingsScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: SettingsScreenModel, ui: SettingsScreenState) -> some View {
        Form {
            Section(LI("settings_display")) {
                Picker(LI("settings_dark_mode"), selection: Binding(get: { ui.darkMode }, set: { model.setDarkMode(code: $0) })) {
                    Text(LI("settings_dark_system")).tag("system")
                    Text(LI("settings_dark_light")).tag("light")
                    Text(LI("settings_dark_dark")).tag("dark")
                }
                Picker(LI("settings_grade_scale"), selection: Binding(get: { ui.gradeScale }, set: { model.setGradeScale(code: $0) })) {
                    Text("Fontainebleau").tag("french")
                    Text("V-Scale").tag("vScale")
                }
                Toggle(LI("settings_keep_screen_on"), isOn: Binding(get: { ui.keepScreenOn }, set: { model.setKeepScreenOn(enabled: $0) }))
            }

            Section {
                LabeledContent(LI("settings_active_board"), value: ui.boardTitle)
                if !ui.boardDetail.isEmpty {
                    Text(ui.boardDetail).font(.footnote).foregroundStyle(.secondary)
                }
                Stepper(bleText(Int(ui.bleAutoDisconnectSeconds)),
                        value: Binding(get: { Int(ui.bleAutoDisconnectSeconds) },
                                       set: { model.setBleAutoDisconnectSeconds(seconds: Int32($0)) }),
                        in: 0...3600, step: 30)
                Picker(LI("settings_led_position"), selection: Binding(get: { ui.moonBoardLedMode }, set: { model.setMoonBoardLedMode(code: $0) })) {
                    Text(LI("settings_led_below")).tag("below")
                    Text(LI("settings_led_above")).tag("above")
                    Text(LI("settings_led_both")).tag("both")
                }
            } header: {
                Text(L("board_ble_title"))
            }

            Section(LI("settings_led_colours")) {
                ForEach(ui.ledColors, id: \.roleCode) { role in
                    HStack {
                        Circle().fill(Color(argb: role.argb)).frame(width: 16, height: 16)
                        Text(roleName(role.roleCode))
                        Spacer()
                        if !role.isDefault { Text(LI("settings_led_custom")).font(.footnote).foregroundStyle(.secondary) }
                    }
                }
                Button(LI("settings_led_reset")) { model.resetLedColors() }
                Button(LI("settings_led_kilter")) { model.applyKilterLedColors() }
            }

            Section(LI("settings_timers")) {
                Stepper(LI("settings_rest_duration", Int(ui.restTimerDurationSeconds)),
                        value: Binding(get: { Int(ui.restTimerDurationSeconds) },
                                       set: { model.setRestTimerDurationSeconds(seconds: Int32($0)) }),
                        in: 5...1800, step: 15)
                Toggle(LI("settings_rest_auto"), isOn: Binding(get: { ui.restTimerAutoStart }, set: { model.setRestTimerAutoStart(enabled: $0) }))
            }

            Section {
                Picker(LI("settings_sync_interval"), selection: Binding(get: { ui.syncInterval }, set: { model.setSyncInterval(code: $0) })) {
                    Text(LI("settings_sync_manual")).tag("manual")
                    Text(LI("settings_sync_daily")).tag("daily")
                    Text(LI("settings_sync_weekly")).tag("weekly")
                }
            } header: {
                Text(L("board_sync_title"))
            } footer: {
                Text(LI("settings_sync_ios_note"))
            }

            Section(LI("settings_delete")) {
                ForEach(ui.brands, id: \.brandWire) { brand in
                    Toggle(brand.title, isOn: Binding(
                        get: { deleteBrands.contains(brand.brandWire) },
                        set: { on in
                            if on { deleteBrands.insert(brand.brandWire) } else { deleteBrands.remove(brand.brandWire) }
                        }))
                }
                Button(LI("settings_delete_catalogue"), role: .destructive) { confirmCatalogueDelete = true }
                    .disabled(deleteBrands.isEmpty || ui.deletingCatalogue)
                Button(LI("settings_delete_user"), role: .destructive) { confirmUserDelete = true }
                    .disabled(deleteBrands.isEmpty || ui.deletingUserData)
                if !ui.lastResult.isEmpty {
                    Text(LI("settings_delete_done", ui.lastResult)).font(.footnote).foregroundStyle(.secondary)
                        .onTapGesture { model.dismissResult() }
                }
            }
        }
        .alert(LI("settings_delete_catalogue_confirm"), isPresented: $confirmCatalogueDelete) {
            Button(L("action_cancel"), role: .cancel) { }
            Button(LI("logbook_delete"), role: .destructive) {
                model.deleteCatalogueData(brandWires: Array(deleteBrands))
                deleteBrands.removeAll()
            }
        }
        .alert(LI("settings_delete_user_confirm"), isPresented: $confirmUserDelete) {
            Button(L("action_cancel"), role: .cancel) { }
            Button(LI("logbook_delete"), role: .destructive) {
                model.deleteUserData(brandWires: Array(deleteBrands))
                deleteBrands.removeAll()
            }
        }
    }

    private func bleText(_ seconds: Int) -> String {
        seconds == 0 ? LI("settings_ble_never") : LI("settings_ble_after", seconds)
    }

    private func roleName(_ code: String) -> String {
        switch code {
        case "start": return LI("creator_role_start")
        case "hand": return LI("creator_role_hand")
        case "finish": return LI("creator_role_finish")
        case "foot": return LI("creator_role_foot")
        default: return code
        }
    }
}
