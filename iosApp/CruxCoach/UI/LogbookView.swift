import CruxCoachCore
import SwiftUI

/// Board logbook: entries grouped by day plus the statistics for the selected period.
/// The denominators differ per statistic exactly as on Android (see docs/en/CORE_CONCEPTS.md).
struct LogbookView: View {
    let core: AppCore
    @State private var host: ScreenHost<LogbookScreenModel, LogbookScreenState>?

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("board_logbook_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeLogbookScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.refresh()
        }
    }

    @ViewBuilder
    private func screen(model: LogbookScreenModel, ui: LogbookScreenState) -> some View {
        List {
            if ui.isLoading && !ui.hasData {
                Section { ProgressView().frame(maxWidth: .infinity) }
            } else if !ui.hasData {
                Section {
                    ContentUnavailableView(LI("logbook_empty"), systemImage: "list.bullet.rectangle",
                                           description: Text(LI("logbook_empty_hint")))
                }
            } else {
                Section {
                    Picker(LI("logbook_period"), selection: Binding(get: { ui.intervalCode }, set: { model.setInterval(code: $0) })) {
                        Text(LI("period_all")).tag("all")
                        Text(LI("period_30")).tag("days30")
                        Text(LI("period_90")).tag("days90")
                        Text(LI("period_year")).tag("year1")
                    }
                    .pickerStyle(.segmented)
                    StatsSummary(stats: ui.stats)
                }
                Section {
                    Picker(LI("logbook_outcome_filter"), selection: Binding(get: { ui.outcomeFilterCode }, set: { model.setOutcomeFilter(code: $0) })) {
                        Text(LI("outcome_all")).tag("all")
                        Text(LI("outcome_sends")).tag("sends")
                        Text(LI("outcome_attempts")).tag("attempts")
                    }
                    .pickerStyle(.segmented)
                }
                ForEach(ui.days, id: \.date) { day in
                    Section {
                        ForEach(day.entries, id: \.uuid) { entry in
                            LogbookRow(entry: entry)
                                .swipeActions {
                                    Button(LI("logbook_delete"), role: .destructive) { model.requestDelete(uuid: entry.uuid) }
                                    Button(LI("logbook_edit")) { model.edit(uuid: entry.uuid) }
                                }
                        }
                    } header: {
                        Text("\(day.date) · \(LI("logbook_day_summary", Int(day.sendCount), Int(day.attemptCount)))")
                    }
                }
                if ui.canLoadMore {
                    ProgressView().frame(maxWidth: .infinity).onAppear { model.loadMore() }
                }
            }
        }
        .alert(LI("logbook_delete_confirm"), isPresented: Binding(
            get: { !ui.deleteConfirmUuid.isEmpty },
            set: { if !$0 { model.dismissDeleteConfirm() } })) {
            Button(L("action_cancel"), role: .cancel) { model.dismissDeleteConfirm() }
            Button(LI("logbook_delete"), role: .destructive) { model.confirmDelete() }
        }
        .sheet(isPresented: Binding(get: { ui.isEditing }, set: { if !$0 { model.dismissEdit() } })) {
            NavigationStack { LogbookEditSheet(model: model, ui: ui) }.presentationDetents([.medium])
        }
        .overlay(alignment: .bottom) {
            if ui.errorCode != "none" {
                Text(LI("logbook_error", ui.errorCode))
                    .font(.footnote).padding(8).background(.red.opacity(0.85)).foregroundStyle(.white)
                    .clipShape(Capsule())
                    .onTapGesture { model.consumeError() }
            }
        }
    }
}

private struct StatsSummary: View {
    let stats: LogbookStatsUi

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                stat(LI("stat_sends"), "\(stats.totalSends)")
                stat(LI("stat_attempts"), "\(stats.totalAttempts)")
                stat(LI("stat_unique"), "\(stats.uniqueClimbs)")
            }
            HStack {
                stat(LI("stat_hardest"), stats.hardestGrade.isEmpty ? "–" : stats.hardestGrade)
                stat(LI("stat_flash_rate"), "\(Int((stats.flashRate * 100).rounded()))%")
                stat(LI("stat_sessions"), "\(stats.sessionCount)")
            }
            if !stats.gradePyramid.isEmpty {
                Text(LI("stat_pyramid")).font(.footnote).foregroundStyle(.secondary)
                ForEach(stats.gradePyramid, id: \.difficulty) { bar in
                    HStack {
                        Text(bar.grade).font(.caption.monospaced()).frame(width: 44, alignment: .leading)
                        GeometryReader { geo in
                            let maximum = stats.gradePyramid.map { Int($0.count) }.max() ?? 1
                            Capsule()
                                .frame(width: geo.size.width * CGFloat(Int(bar.count)) / CGFloat(max(maximum, 1)), height: 10)
                                .foregroundStyle(Color.accentColor)
                        }
                        .frame(height: 10)
                        Text("\(bar.count)").font(.caption.monospacedDigit()).frame(width: 34, alignment: .trailing)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.monospacedDigit())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private struct LogbookRow: View {
    let entry: LogbookEntryUi

    var body: some View {
        HStack {
            Image(systemName: entry.isSend ? (entry.isFlash ? "bolt.circle.fill" : "checkmark.circle.fill") : "circle.dotted")
                .foregroundStyle(entry.isSend ? Color.accentColor : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.climbName)
                HStack(spacing: 6) {
                    if !entry.grade.isEmpty { Text(entry.grade) }
                    Text("\(entry.angle)°")
                    if entry.isMirror { Image(systemName: "arrow.left.and.right") }
                    if !entry.comment.isEmpty { Image(systemName: "text.bubble") }
                }
                .font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            Text("×\(entry.tries)").font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct LogbookEditSheet: View {
    let model: LogbookScreenModel
    let ui: LogbookScreenState

    var body: some View {
        Form {
            Stepper(LI("log_tries", Int(ui.editTries)),
                    value: Binding(get: { Int(ui.editTries) }, set: { model.setEditTries(count: Int32($0)) }), in: 1...999)
            if ui.editIsSend {
                Picker(LI("log_quality"), selection: Binding(get: { Int(ui.editQuality) }, set: { model.setEditQuality(quality: Int32($0)) })) {
                    ForEach(0...5, id: \.self) { value in
                        Text(value == 0 ? "–" : String(repeating: "★", count: value)).tag(value)
                    }
                }
            }
            TextField(LI("log_comment"), text: Binding(get: { ui.editComment }, set: { model.setEditComment(comment: $0) }), axis: .vertical)
        }
        .navigationTitle(LI("logbook_edit"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { model.dismissEdit() } }
            ToolbarItem(placement: .confirmationAction) { Button(L("action_save")) { model.saveEdit() } }
        }
    }
}

/// Projection history ("Verlauf"): what was sent to a board, not what was climbed.
struct HistoryView: View {
    let core: AppCore
    @State private var host: ScreenHost<HistoryScreenModel, HistoryScreenState>?

    var body: some View {
        Group {
            if let host {
                let model = host.model
                let ui = host.state
                List {
                    Section {
                        Picker(LI("history_retention"), selection: Binding(get: { ui.retentionCode }, set: { model.setRetention(code: $0) })) {
                            Text(LI("retention_off")).tag("off")
                            Text(LI("retention_30")).tag("days30")
                            Text(LI("retention_90")).tag("days90")
                            Text(LI("retention_365")).tag("days365")
                        }
                    } footer: {
                        Text(LI("history_hint"))
                    }
                    ForEach(ui.entries, id: \.id) { entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.climbName)
                            Text("\(entry.recordedAt) · \(entry.angle)°")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if !ui.entries.isEmpty {
                        Button(LI("history_clear"), role: .destructive) { model.clearHistory() }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(LI("history_title"))
        .task {
            guard host == nil else { return }
            let model = core.makeHistoryScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}
