import CruxCoachCore
import SwiftUI

/// The training history (Android `TrainingHistoryScreen`): finished trainings by month, newest
/// first; each opens its summary. Push it inside a `NavigationStack`; it has none of its own.
struct TrainingHistoryView: View {
    let core: AppCore

    @State private var host: ScreenHost<TrainingHistoryScreenModel, TrainingHistoryScreenState>?

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Group {
            if let host {
                TrainingHistoryContent(core: core, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("trw_history_title"))
        .modifier(RestTimerBannerSlot(core: core))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeTrainingHistoryScreen() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct TrainingHistoryContent: View {
    let core: AppCore
    let ui: TrainingHistoryScreenState

    @State private var opened: TrainingHistoryTarget?

    var body: some View {
        content
            .sheet(item: $opened) { target in
                WorkoutSummaryView(core: core, workoutId: target.id, onClose: { opened = nil },
                                   onOpenNutrition: { opened = nil })
            }
    }

    @ViewBuilder
    private var content: some View {
        if ui.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if ui.isEmpty {
            Text(L("trw_history_empty"))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                ForEach(ui.months, id: \.month) { month in
                    TrainingHistoryMonthSection(month: month) { id in opened = TrainingHistoryTarget(id: id) }
                }
            }
        }
    }
}

private struct TrainingHistoryMonthSection: View {
    let month: TrainingHistoryMonthUi
    let onOpen: (String) -> Void

    var body: some View {
        Section(TrainingHistoryMonth.label(month.month)) {
            ForEach(month.rows, id: \.workoutId) { row in
                TrainingHistoryRow(row: row, onOpen: onOpen)
            }
        }
    }
}

private struct TrainingHistoryRow: View {
    let row: TrainingHistoryRowUi
    let onOpen: (String) -> Void

    var body: some View {
        Button { onOpen(row.workoutId) } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr: row.title).foregroundStyle(.primary)
                    Text(tr: row.details).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
            }
        }
    }
}

private struct TrainingHistoryTarget: Identifiable {
    let id: String
}

/// "2026-10" as "October 2026" in the device language (Android `monthLabel`, "LLLL yyyy").
enum TrainingHistoryMonth {
    static func label(_ month: String) -> String {
        let parser = DateFormatter()
        parser.calendar = Calendar(identifier: .gregorian)
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM"
        guard let date = parser.date(from: month) else { return month }
        let out = DateFormatter()
        out.setLocalizedDateFormatFromTemplate("LLLLyyyy")
        let text = out.string(from: date)
        return text.prefix(1).uppercased() + text.dropFirst()
    }
}
