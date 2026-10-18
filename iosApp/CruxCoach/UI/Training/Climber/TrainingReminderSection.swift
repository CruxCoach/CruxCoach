import CruxCoachCore
import SwiftUI

/// The training reminder (Android `TrainingReminderSection` in the training settings): on planned
/// training days at the chosen time, optionally on board days too; every change is scheduled at
/// once and switching it on asks for the notification permission. A `Section` for a `List`/`Form`
/// inside a `NavigationStack` ("Edit week plan" pushes the week plan).
struct TrainingReminderSection: View {
    let core: AppCore

    @State private var host: ScreenHost<TrainingReminderSettingsModel, TrainingReminderSettingsState>?
    @State private var info = false

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Section {
            toggle
                .task {
                    guard host == nil, let model = core.makeTrainingReminderSettings(notifications: LocalNotificationScheduler()) else { return }
                    host = ScreenHost(model: model, initial: model.currentState,
                                      subscribe: { model, onState in model.watch(onState: onState) },
                                      onClose: { model in model.close() })
                }
            if let host, host.state.enabled {
                TrainingReminderDetails(core: core, model: host.model, ui: host.state)
            }
        } header: {
            HStack {
                Text(L("trw_reminder_title"))
                Spacer()
                Button { info = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trw_reminder_title"))
            }
        }
    }

    private var toggle: some View {
        Toggle(L("trw_reminder_switch"), isOn: Binding(get: { host?.state.enabled ?? false },
                                                         set: { on in host?.model.setEnabled(on: on) }))
            .disabled(host == nil)
            .alert(L("trw_reminder_title"), isPresented: $info) {
                Button(L("action_close"), role: .cancel) {}
            } message: {
                Text(L("trw_reminder_info"))
            }
    }
}

private struct TrainingReminderDetails: View {
    let core: AppCore
    let model: TrainingReminderSettingsModel
    let ui: TrainingReminderSettingsState

    var body: some View {
        DatePicker(TrRender.text(ui.timeText), selection: time, displayedComponents: .hourAndMinute)
        Toggle(L("trw_reminder_board_days"), isOn: Binding(get: { ui.onBoardDays }, set: { on in model.setBoardDays(on: on) }))
        if !ui.hasWeekPlan {
            // A hint brings its action (design.md): plan the week.
            Text(L("trw_reminder_no_plan")).font(.caption).foregroundStyle(.secondary)
            NavigationLink {
                WeekPlanView(core: core)
            } label: {
                Text(L("trwo_week_plan_edit"))
            }
        }
        if ui.denied {
            Text(L("trr_permission_denied")).font(.caption).foregroundStyle(.red)
            Button(L("trr_open_notification_settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        }
    }

    private var time: Binding<Date> {
        Binding(get: { Self.date(minutes: Int(ui.minutes)) },
                set: { date in model.setMinutes(minutes: Int32(Self.minutes(of: date))) })
    }

    private static func date(minutes: Int) -> Date {
        let start = Calendar.current.startOfDay(for: Date())
        return Calendar.current.date(byAdding: .minute, value: max(0, min(minutes, 24 * 60 - 1)), to: start) ?? start
    }

    private static func minutes(of date: Date) -> Int {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }
}
