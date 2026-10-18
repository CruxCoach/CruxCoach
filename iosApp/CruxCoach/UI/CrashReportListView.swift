import CruxCoachCore
import SwiftUI

/// Android's crash report list (settings → developer contact): each crash report sent from the
/// start-up prompt with its delivery status; an undelivered one can be sent again or deleted.
/// Android opens a delivered report's thread page; here the row unfolds with the full report and
/// the maintainer's replies.
struct CrashReportListView: View {
    let core: AppCore
    @State private var host: ScreenHost<CrashReportListModel, CrashReportListState>?
    @State private var expanded: Set<String> = []

    var body: some View {
        Group {
            if let host {
                content(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("devcontact_crash_reports"))
        .task {
            guard host == nil else { return }
            let model = core.makeCrashReportList()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.start()
        }
    }

    @ViewBuilder
    private func content(model: CrashReportListModel, ui: CrashReportListState) -> some View {
        if ui.isLoading && ui.reports.isEmpty {
            ProgressView()
        } else if ui.reports.isEmpty {
            ContentUnavailableView(L("devcontact_crashes_empty"), systemImage: "exclamationmark.octagon")
        } else {
            List(ui.reports, id: \.id) { report in
                row(model: model, ui: ui, report: report)
            }
            .alert(L("devcontact_send_failed"),
                   isPresented: Binding(get: { ui.noticeCode != "none" },
                                        set: { if !$0 { model.consumeNotice() } })) {
                Button(L("action_close")) { model.consumeNotice() }
            }
        }
    }

    @ViewBuilder
    private func row(model: CrashReportListModel, ui: CrashReportListState, report: CrashReportRowUi) -> some View {
        let open: Bool = expanded.contains(report.id)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text(status(report))
                    .font(.caption)
                    .foregroundStyle(statusColor(report))
                if report.unreadReplies > 0 {
                    Image(systemName: "envelope.badge").foregroundStyle(.tint)
                }
                Spacer()
                actions(model: model, ui: ui, report: report)
            }
            Text(report.firstLine)
                .font(.subheadline.weight(.medium))
                .lineLimit(open ? nil : 1)
            if open {
                details(report)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { toggle(model: model, report: report) }
    }

    /// Android's retry and delete buttons, on undelivered reports only.
    @ViewBuilder
    private func actions(model: CrashReportListModel, ui: CrashReportListState, report: CrashReportRowUi) -> some View {
        if ui.busyId == report.id {
            ProgressView()
        } else {
            if report.canRetry {
                Button { model.retry(id: report.id) } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.orange)
                .accessibilityLabel(L("cd_retry_send"))
                .disabled(!ui.busyId.isEmpty)
            }
            if report.canDelete {
                Button(role: .destructive) { model.delete(id: report.id) } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("action_delete"))
                .disabled(!ui.busyId.isEmpty)
            }
        }
    }

    @ViewBuilder
    private func details(_ report: CrashReportRowUi) -> some View {
        Divider()
        Text(report.content)
            .font(.caption.monospaced())
            .foregroundStyle(.secondary)
            .textSelection(.enabled)
        ForEach(report.replies, id: \.id) { reply in
            VStack(alignment: .leading, spacing: 2) {
                Text(L("devcontact_developer") + " · " + Self.timestamp(reply.createdAtEpochSeconds))
                    .font(.caption.bold())
                Text(reply.content)
            }
            .padding(.top, 4)
        }
    }

    private func toggle(model: CrashReportListModel, report: CrashReportRowUi) {
        if expanded.contains(report.id) {
            expanded.remove(report.id)
        } else {
            expanded.insert(report.id)
            model.open(id: report.id)
        }
    }

    private func status(_ report: CrashReportRowUi) -> String {
        let time: String = Self.timestamp(report.createdAtEpochSeconds)
        switch report.statusCode {
        case "delivered": return L("devcontact_status_delivered", time)
        case "notDelivered": return L("devcontact_status_not_delivered")
        default: return L("devcontact_developer") + " · " + time
        }
    }

    private func statusColor(_ report: CrashReportRowUi) -> Color {
        switch report.statusCode {
        case "delivered": return .green
        case "notDelivered": return .red
        default: return .secondary
        }
    }

    /// Android's `formatTimestamp` pattern.
    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd.MM.yyyy HH:mm"
        return formatter
    }()

    private static func timestamp(_ epochSeconds: Int64) -> String {
        formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochSeconds)))
    }
}
