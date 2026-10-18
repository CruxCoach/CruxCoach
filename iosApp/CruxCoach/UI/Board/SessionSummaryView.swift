import CruxCoachCore
import SwiftUI

/// Android `formatSessionTime`: "1:05:09" with hours, "5:09" without.
func sessionTimeText(_ totalSeconds: Int) -> String {
    let seconds: Int = max(totalSeconds, 0)
    let hours: Int = seconds / 3600
    let minutes: Int = (seconds % 3600) / 60
    let rest: Int = seconds % 60
    if hours > 0 {
        return String(format: "%ld:%02ld:%02ld", hours, minutes, rest)
    }
    return String(format: "%ld:%02ld", minutes, rest)
}

/// The running board session above the player (Android `BleStatusArea`
/// session row): total time and active time, re-read every second from the
/// recorder so a suspended app shows the real time.
struct SessionBar: View {
    let model: PlayerScreenModel

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            row
        }
    }

    private var row: some View {
        let total: Int = Int(model.sessionElapsedSeconds())
        let active: Int = Int(model.sessionActiveSeconds())
        return HStack(spacing: 12) {
            Label(L("board_session_label"), systemImage: "timer")
                .font(.subheadline.weight(.semibold))
            Spacer()
            Text(sessionTimeText(total)).font(.subheadline.monospacedDigit())
            Text("\(L("board_session_summary_active_time")) \(sessionTimeText(active))")
                .font(.footnote.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .combine)
    }
}

/// Android `SessionSummarySheet`: highlights, hardest send, time breakdown,
/// grade distribution and intensity zones of the session that just ended.
struct SessionSummarySheet: View {
    let summary: SessionSummaryUi
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    highlights
                    if summary.hasDetails && !summary.hardestGrade.isEmpty {
                        hardestSend
                    }
                    timeCard
                    if summary.hasDetails && !summary.gradeDistribution.isEmpty {
                        SummaryPyramid(bars: summary.gradeDistribution)
                    }
                    if summary.hasDetails && zoneTotal > 0 {
                        zonesCard
                    }
                    Button(action: onDone) {
                        Text(L("action_ok")).fontWeight(.bold).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding()
            }
            .navigationTitle(L("board_session_summary_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var zoneTotal: Int {
        Int(summary.warmupCount) + Int(summary.optimalCount) + Int(summary.limitCount)
    }

    private var highlights: some View {
        HStack(spacing: 8) {
            SummaryHighlight(value: Int(summary.sends), label: L("board_sends"), color: .green)
            SummaryHighlight(value: Int(summary.attempts), label: L("board_session_summary_attempts"), color: .orange)
            if summary.hasDetails && summary.flashes > 0 {
                SummaryHighlight(value: Int(summary.flashes), label: L("board_session_flashes"), color: .purple)
            }
            if summary.hasDetails && summary.uniqueClimbs > 0 {
                SummaryHighlight(value: Int(summary.uniqueClimbs), label: L("board_stats_unique"), color: .gray)
            }
        }
    }

    private var hardestSend: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("board_session_summary_hardest_send")).font(.caption).foregroundStyle(.secondary)
                if !summary.hardestName.isEmpty {
                    Text(summary.hardestName).font(.footnote).lineLimit(1)
                }
            }
            Spacer()
            Text(summary.hardestGrade).font(.title2.weight(.bold)).foregroundStyle(.orange)
        }
        .padding(12)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }

    private var timeCard: some View {
        VStack(spacing: 4) {
            SummaryRow(label: L("board_session_summary_total_time"), value: sessionTimeText(Int(summary.totalSeconds)))
            SummaryRow(label: L("board_session_summary_active_time"), value: sessionTimeText(Int(summary.activeSeconds)))
            SummaryRow(label: L("board_session_summary_pause_time"), value: sessionTimeText(Int(summary.pauseSeconds)))
        }
        .padding(12)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }

    private var zonesCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("board_session_summary_intensity_zones")).font(.subheadline.weight(.bold))
            SummaryZoneBar(label: L("board_zone_warmup"), count: Int(summary.warmupCount), total: zoneTotal, color: .green)
            SummaryZoneBar(label: L("board_zone_optimal"), count: Int(summary.optimalCount), total: zoneTotal, color: .yellow)
            SummaryZoneBar(label: L("board_zone_limit"), count: Int(summary.limitCount), total: zoneTotal, color: .red)
            SummaryRow(label: L("board_session_summary_session_type"), value: sessionTypeText(summary.sessionTypeCode))
        }
        .padding(12)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }

    private func sessionTypeText(_ code: String) -> String {
        switch code {
        case "warmup": return L("board_session_type_warmup")
        case "volume": return L("board_session_type_volume")
        case "limit": return L("board_session_type_limit")
        default: return L("board_session_type_pyramid")
        }
    }
}

private struct SummaryHighlight: View {
    let value: Int
    let label: String
    let color: Color

    var body: some View {
        VStack(spacing: 2) {
            Text("\(value)").font(.title2.weight(.bold).monospacedDigit()).foregroundStyle(color)
            Text(label).font(.caption2).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(color.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

private struct SummaryRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).fontWeight(.bold).monospacedDigit()
        }
        .font(.subheadline)
        .accessibilityElement(children: .combine)
    }
}

private struct SummaryZoneBar: View {
    let label: String
    let count: Int
    let total: Int
    let color: Color

    var body: some View {
        HStack {
            Text(label).font(.footnote).frame(width: 80, alignment: .leading)
            GeometryReader { geo in
                let fraction: CGFloat = total > 0 ? CGFloat(count) / CGFloat(total) : 0
                Capsule().fill(color).frame(width: geo.size.width * fraction, height: 10)
            }
            .frame(height: 10)
            Text("\(count)").font(.footnote.monospacedDigit()).frame(width: 30, alignment: .trailing)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct SummaryPyramid: View {
    let bars: [GradeBarUi]

    var body: some View {
        let maximum: Int = bars.map { Int($0.count) }.max() ?? 1
        return VStack(alignment: .leading, spacing: 6) {
            Text(L("board_session_summary_grade_distribution")).font(.subheadline.weight(.bold))
            ForEach(bars, id: \.difficulty) { bar in
                SummaryPyramidRow(grade: bar.grade, count: Int(bar.count), maximum: maximum)
            }
        }
        .padding(12)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SummaryPyramidRow: View {
    let grade: String
    let count: Int
    let maximum: Int

    var body: some View {
        HStack {
            Text(grade).font(.caption.monospaced()).frame(width: 44, alignment: .leading)
            GeometryReader { geo in
                let fraction: CGFloat = CGFloat(count) / CGFloat(max(maximum, 1))
                Capsule().fill(Color.accentColor).frame(width: geo.size.width * fraction, height: 10)
            }
            .frame(height: 10)
            Text("\(count)").font(.caption.monospacedDigit()).frame(width: 30, alignment: .trailing)
        }
        .accessibilityElement(children: .combine)
    }
}
