import Charts
import CruxCoachCore
import SwiftUI

/// Board statistics for the selected period.
///
/// Every chart names its own denominator, because they deliberately differ
/// (see `docs/en/CORE_CONCEPTS.md` and `BoardStatsComputer`):
///   * total sends / grade pyramid / progression / distinct-by-grade — send ROWS,
///     repeats included;
///   * total attempts — the SUM of recorded tries over every row, not a row count;
///   * angle, activity, weekly volume, entries-over-time — all log entries,
///     unsuccessful ones included;
///   * problem outcomes — ONE best outcome per board family and climb in the
///     period, collapsed across angles. It is not an attempt count;
///   * personal records and the period comparison — the FULL history.
struct StatsView: View {
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
        .navigationTitle(L("board_stats_title"))
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
        let stats = ui.stats
        List {
            if ui.isLoading && !ui.hasData {
                Section { ProgressView().frame(maxWidth: .infinity) }
            } else if !ui.hasData {
                Section {
                    ContentUnavailableView(L("stats_no_data"), systemImage: "chart.bar",
                                           description: Text(L("stats_no_data_hint")))
                }
            } else {
                // Grouped in threes: a ViewBuilder block takes at most ten children.
                Group {
                    periodSection(model: model, ui: ui)
                    summarySection(stats: stats)
                    recordsSection(records: stats.records)
                    gradePyramidSection(stats: stats)
                }
                Group {
                    outcomeByGradeSection(stats: stats)
                    outcomeDistributionSection(stats: stats)
                    distinctClimbsSection(stats: stats)
                    entriesOverTimeSection(stats: stats)
                }
                Group {
                    weeklyVolumeSection(stats: stats)
                    angleSection(stats: stats)
                    activitySection(stats: stats)
                    progressionSection(stats: stats)
                    comparisonSection(comparison: stats.periodComparison)
                }
            }
        }
    }

    // MARK: - Period and board scope

    @ViewBuilder
    private func periodSection(model: LogbookScreenModel, ui: LogbookScreenState) -> some View {
        Section {
            Picker(L("board_stats_select_period"),
                   selection: Binding(get: { ui.intervalCode }, set: { model.setInterval(code: $0) })) {
                Text(L("board_stats_interval_30d")).tag("days30")
                Text(L("board_stats_interval_90d")).tag("days90")
                Text(L("board_stats_interval_1y")).tag("year1")
                Text(L("board_stats_interval_all")).tag("all")
            }
            .pickerStyle(.segmented)

            if ui.availableBoardWires.count > 1 {
                Picker(L("board_stats_board_comparison_board"),
                       selection: Binding(get: { ui.statsBoardFilter },
                                          set: { model.setStatsBoardFilter(boardWire: $0) })) {
                    Text(L("map_filter_show_all")).tag("")
                    ForEach(Array(ui.availableBoardWires.enumerated()), id: \.offset) { index, wire in
                        Text(ui.availableBoardTitles[index]).tag(wire)
                    }
                }
            }
        } footer: {
            Text(L("ux_stats_help")).font(.footnote)
        }
    }

    // MARK: - Volume headline

    private func summarySection(stats: LogbookStatsUi) -> some View {
        Section {
            HStack(alignment: .top) {
                tile(L("board_sends"), "\(stats.totalSends)")
                tile(L("board_logbook_attempts"), "\(stats.totalAttempts)")
                tile(L("board_logbook_unique_climbs"), "\(stats.uniqueClimbs)")
            }
            HStack(alignment: .top) {
                tile(L("board_stats_hardest"), stats.hardestGrade.isEmpty ? "–" : stats.hardestGrade)
                tile(L("board_stats_flash_rate"), percent(stats.flashRate))
                tile(L("board_logbook_sessions"), "\(stats.sessionCount)")
            }
        } header: {
            Text(L("board_stats_total"))
        } footer: {
            Text(LI("stats_denom_volume")).font(.footnote)
        }
    }

    private func recordsSection(records: PersonalRecordsUi) -> some View {
        Section {
            LabeledContent(L("board_stats_hardest_flash"),
                           value: records.hardestFlashGrade.isEmpty ? "–" : records.hardestFlashGrade)
            LabeledContent(L("board_stats_most_sends_day")) {
                Text(records.mostSendsDate.isEmpty
                     ? "\(records.mostSendsInDay)"
                     : "\(records.mostSendsInDay) · \(records.mostSendsDate)")
            }
            LabeledContent(L("board_stats_avg_sessions_week"),
                           value: oneDecimal(records.avgSessionsPerWeek))
            LabeledContent(L("board_stats_week_streak"), value: "\(records.weekStreak)")
        } header: {
            Text(L("board_stats_title"))
        } footer: {
            Text(LI("stats_denom_records")).font(.footnote)
        }
    }

    // MARK: - Grade charts

    @ViewBuilder
    private func gradePyramidSection(stats: LogbookStatsUi) -> some View {
        let bars = stats.gradePyramid
        if !bars.isEmpty {
            Section {
                Chart(bars, id: \.difficulty) { bar in
                    BarMark(
                        x: .value(LI("stats_axis_sends"), Int(bar.count)),
                        y: .value(LI("stats_axis_grade"), bar.grade)
                    )
                    .cornerRadius(4)
                    .foregroundStyle(Color.accentColor)
                    .annotation(position: .trailing, alignment: .leading) {
                        Text("\(bar.count)").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                .chartYScale(domain: bars.map(\.grade))
                .chartXAxis(.hidden)
                .frame(height: chartHeight(rows: bars.count))
                .accessibilityLabel(L("board_stats_grade_pyramid"))
            } header: {
                Text(L("board_stats_grade_pyramid"))
            } footer: {
                Text(LI("stats_denom_sends")).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func outcomeByGradeSection(stats: LogbookStatsUi) -> some View {
        let entries = stats.gradeOutcomes
        if !entries.isEmpty {
            Section {
                Chart {
                    ForEach(entries, id: \.difficulty) { entry in
                        ForEach(Outcome.allCases, id: \.self) { outcome in
                            BarMark(
                                x: .value(LI("stats_axis_climbs"), outcome.count(in: entry)),
                                y: .value(LI("stats_axis_grade"), entry.grade)
                            )
                            .foregroundStyle(by: .value(LI("stats_series_outcome"), outcome.label))
                        }
                    }
                }
                .chartForegroundStyleScale(Outcome.styleScale)
                .chartYScale(domain: entries.map(\.grade))
                .chartLegend(position: .top, alignment: .leading)
                .frame(height: chartHeight(rows: entries.count))

                // The stacked segments are the relief for the sub-3:1 light-mode
                // fill: every value is also readable as text.
                ForEach(entries, id: \.difficulty) { entry in
                    LabeledContent(entry.grade) {
                        Text("\(entry.flashes) · \(entry.redpoints) · \(entry.attempts)")
                            .font(.caption.monospacedDigit())
                    }
                }
            } header: {
                Text(L("board_stats_flash_send_attempt"))
            } footer: {
                Text(L("board_stats_problem_outcomes_hint")).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func outcomeDistributionSection(stats: LogbookStatsUi) -> some View {
        let sent = Int(stats.outcomeFlashes) + Int(stats.outcomeRedpoints)
        if sent > 0 || stats.outcomeAttempts > 0 {
            Section {
                Chart {
                    SectorMark(angle: .value(Outcome.flash.label, Int(stats.outcomeFlashes)),
                               innerRadius: .ratio(0.62), angularInset: 2)
                        .cornerRadius(4)
                        .foregroundStyle(by: .value(LI("stats_series_outcome"), Outcome.flash.label))
                    SectorMark(angle: .value(Outcome.laterSend.label, Int(stats.outcomeRedpoints)),
                               innerRadius: .ratio(0.62), angularInset: 2)
                        .cornerRadius(4)
                        .foregroundStyle(by: .value(LI("stats_series_outcome"), Outcome.laterSend.label))
                }
                .chartForegroundStyleScale(Outcome.styleScale)
                .chartLegend(position: .bottom, alignment: .center)
                .frame(height: 200)
                .overlay {
                    VStack(spacing: 2) {
                        Text("\(sent)").font(.title2.monospacedDigit().weight(.semibold))
                        Text(L("ux_sent_climbs")).font(.caption).foregroundStyle(.secondary)
                    }
                }

                LabeledContent(Outcome.flash.label, value: "\(stats.outcomeFlashes)")
                LabeledContent(Outcome.laterSend.label, value: "\(stats.outcomeRedpoints)")
                LabeledContent(Outcome.attempted.label, value: "\(stats.outcomeAttempts)")
            } header: {
                Text(L("board_stats_outcome_distribution"))
            } footer: {
                Text(L("ux_outcome_donut_hint")).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func distinctClimbsSection(stats: LogbookStatsUi) -> some View {
        let entries = stats.uniqueClimbsByGrade
        if !entries.isEmpty {
            Section {
                Chart(entries, id: \.difficulty) { entry in
                    BarMark(
                        x: .value(L("board_sends"), Int(entry.sends)),
                        y: .value(LI("stats_axis_grade"), entry.grade)
                    )
                    .cornerRadius(4)
                    .foregroundStyle(Color.accentColor.opacity(0.35))
                    BarMark(
                        x: .value(L("ux_sent_climbs"), Int(entry.unique)),
                        y: .value(LI("stats_axis_grade"), entry.grade),
                        height: .fixed(8)
                    )
                    .cornerRadius(2)
                    .foregroundStyle(Color.accentColor)
                    .annotation(position: .trailing, alignment: .leading) {
                        Text("\(entry.unique)/\(entry.sends)")
                            .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                .chartYScale(domain: entries.map(\.grade))
                .chartXAxis(.hidden)
                .frame(height: chartHeight(rows: entries.count))
            } header: {
                Text(L("ux_sent_climbs_grade"))
            } footer: {
                Text(LI("stats_denom_unique")).font(.footnote)
            }
        }
    }

    // MARK: - Time charts

    @ViewBuilder
    private func entriesOverTimeSection(stats: LogbookStatsUi) -> some View {
        let buckets = stats.sendsOverTime
        if buckets.count >= 2 {
            Section {
                Chart(Array(buckets.enumerated()), id: \.offset) { item in
                    BarMark(
                        x: .value(LI("stats_axis_date"), bucketLabel(item.element)),
                        y: .value(LI("stats_axis_entries"), Int(item.element.count))
                    )
                    .cornerRadius(4)
                    .foregroundStyle(Color.accentColor)
                }
                .chartXScale(domain: buckets.map(bucketLabel))
                .chartXAxis { AxisMarks(values: .automatic(desiredCount: 6)) }
                .frame(height: 180)
            } header: {
                Text(L("board_stats_sends_over_time"))
            } footer: {
                Text(LI("stats_denom_entries")).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func weeklyVolumeSection(stats: LogbookStatsUi) -> some View {
        let weeks = stats.weeklyVolume
        if !weeks.isEmpty {
            Section {
                Chart {
                    ForEach(Array(weeks.enumerated()), id: \.offset) { item in
                        ForEach(GradeBand.allCases, id: \.self) { band in
                            BarMark(
                                x: .value(LI("stats_axis_week"), weekLabel(item.element)),
                                y: .value(LI("stats_axis_entries"), band.count(in: item.element))
                            )
                            .foregroundStyle(by: .value(LI("stats_series_band"), band.label))
                        }
                    }
                }
                .chartForegroundStyleScale(GradeBand.styleScale)
                .chartXScale(domain: weeks.map(weekLabel))
                .chartXAxis { AxisMarks(values: .automatic(desiredCount: 6)) }
                .chartLegend(position: .top, alignment: .leading)
                .frame(height: 200)
            } header: {
                Text(L("board_stats_weekly_volume"))
            } footer: {
                Text(LI("stats_denom_entries")).font(.footnote)
            }
        }
    }

    // MARK: - Distributions

    @ViewBuilder
    private func angleSection(stats: LogbookStatsUi) -> some View {
        let angles = stats.angleDistribution
        if !angles.isEmpty {
            Section {
                Chart(angles, id: \.angle) { entry in
                    BarMark(
                        x: .value(L("board_logbook_filter_angle"), "\(entry.angle)°"),
                        y: .value(LI("stats_axis_entries"), Int(entry.count))
                    )
                    .cornerRadius(4)
                    .foregroundStyle(Color.accentColor)
                    .annotation(position: .top) {
                        Text("\(entry.count)").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                .chartXScale(domain: angles.map { "\($0.angle)°" })
                .frame(height: 180)
            } header: {
                Text(L("board_stats_angle_distribution"))
            } footer: {
                Text(LI("stats_denom_entries")).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func activitySection(stats: LogbookStatsUi) -> some View {
        let days = ActivityDay.build(dates: stats.activityDates, counts: stats.activityCounts)
        if !days.isEmpty {
            Section {
                ActivityCalendar(days: days)
            } header: {
                Text(L("board_stats_activity"))
            } footer: {
                Text(LI("stats_denom_entries")).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func progressionSection(stats: LogbookStatsUi) -> some View {
        let points = stats.gradeProgression
        if points.count >= 2 {
            Section {
                Chart(points, id: \.weekStartDate) { point in
                    LineMark(
                        x: .value(LI("stats_axis_week"), Double(point.xFraction)),
                        y: .value(LI("stats_axis_level"), point.level)
                    )
                    .lineStyle(StrokeStyle(lineWidth: 2))
                    .foregroundStyle(Color.accentColor)
                    PointMark(
                        x: .value(LI("stats_axis_week"), Double(point.xFraction)),
                        y: .value(LI("stats_axis_level"), point.level)
                    )
                    .symbolSize(60)
                    .foregroundStyle(Color.accentColor)
                }
                .chartXAxis(.hidden)
                .chartYAxis {
                    AxisMarks { value in
                        AxisGridLine()
                        AxisValueLabel {
                            if let level = value.as(Double.self) {
                                Text(gradeLabel(level, in: points))
                            }
                        }
                    }
                }
                .frame(height: 180)

                if let first = points.first, let last = points.last {
                    LabeledContent(first.weekStartDate, value: first.levelGrade)
                    LabeledContent(last.weekStartDate, value: last.levelGrade)
                }
            } header: {
                Text(L("board_stats_grade_progression"))
            } footer: {
                Text(LI("stats_denom_progression")).font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func comparisonSection(comparison: PeriodComparisonUi) -> some View {
        Section {
            if comparison.hasData {
                delta(L("board_sends"), Double(comparison.sendsDelta), suffix: "")
                delta(L("board_stats_flash_rate"), Double(comparison.flashRateDelta), suffix: " pp")
                delta(L("board_stats_hardest"), Double(comparison.hardestGradeDelta), suffix: "")
                delta(L("board_logbook_unique_climbs"), Double(comparison.uniqueClimbsDelta), suffix: "")
            } else {
                Text(L("board_stats_select_period")).font(.footnote).foregroundStyle(.secondary)
            }
        } header: {
            Text(L("board_stats_period_comparison"))
        } footer: {
            Text(LI("stats_denom_period")).font(.footnote)
        }
    }

    // MARK: - Small pieces

    private func tile(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.title3.monospacedDigit())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// Status colour never carries the meaning alone: the arrow and the sign do.
    private func delta(_ title: String, _ value: Double, suffix: String) -> some View {
        let rising = value > 0
        let flat = value == 0
        return LabeledContent(title) {
            Label {
                Text("\(value > 0 ? "+" : "")\(oneDecimal(value))\(suffix)").monospacedDigit()
            } icon: {
                Image(systemName: flat ? "equal" : (rising ? "arrow.up.right" : "arrow.down.right"))
            }
            .foregroundStyle(flat ? Color.secondary : (rising ? ChartPalette.good : ChartPalette.critical))
        }
    }

    private func percent(_ value: Float) -> String {
        // BoardStatsComputer already reports a percentage, not a fraction.
        "\(Int(value.rounded()))%"
    }

    private func oneDecimal(_ value: Double) -> String {
        value == value.rounded() ? "\(Int(value))" : String(format: "%.1f", value)
    }

    private func chartHeight(rows: Int) -> CGFloat { CGFloat(max(rows, 1) * 30 + 24) }

    private func bucketLabel(_ bucket: TimeBucketUi) -> String {
        switch bucket.kind {
        case "day": return String(format: "%02d-%02d", Int(bucket.month), Int(bucket.day))
        case "isoWeek": return "\(LI("stats_week_short"))\(bucket.isoWeek)"
        default: return String(format: "%04d-%02d", Int(bucket.year), Int(bucket.month))
        }
    }

    private func weekLabel(_ week: WeeklyVolumeUi) -> String {
        "\(LI("stats_week_short"))\(week.isoWeek)"
    }

    /// The y axis is a difficulty number; show the user's own grade for it.
    private func gradeLabel(_ level: Double, in points: [ProgressionPointUi]) -> String {
        let nearest = points.min { abs($0.level - level) < abs($1.level - level) }
        guard let nearest, abs(nearest.level - level) < 1.0 else { return "" }
        return nearest.levelGrade
    }
}

// MARK: - Palette

/// Chart colours, stepped separately for the light and the dark surface.
///
/// The categorical trio is validated for colour-vision deficiency (worst
/// adjacent pair ΔE 9.2 light / 9.4 dark); the grade bands are an ordinal
/// single-hue ramp, because the bands are ordered, not independent identities.
enum ChartPalette {
    static let aqua = dynamic(light: 0x1BAF7A, dark: 0x199E70)
    static let orange = dynamic(light: 0xEB6834, dark: 0xD95926)
    static let blue = dynamic(light: 0x2A78D6, dark: 0x3987E5)

    static let band1 = dynamic(light: 0x86B6EF, dark: 0xB7D3F6)
    static let band2 = dynamic(light: 0x5598E7, dark: 0x86B6EF)
    static let band3 = dynamic(light: 0x2A78D6, dark: 0x3987E5)
    static let band4 = dynamic(light: 0x184F95, dark: 0x184F95)

    static let good = dynamic(light: 0x0CA30C, dark: 0x0CA30C)
    static let critical = dynamic(light: 0xD03B3B, dark: 0xD03B3B)

    /// Four activity steps, lightest = fewest entries.
    static let activity = [band1, band2, band3, band4]

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in
            UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255,
                  alpha: 1)
    }
}

// MARK: - Series

/// One best outcome per board family and climb in the period, collapsed across
/// angles. "Attempted" means "never sent", not "number of tries".
private enum Outcome: CaseIterable, Hashable {
    case flash, laterSend, attempted

    var label: String {
        switch self {
        case .flash: return L("board_logbook_flash")
        case .laterSend: return L("ux_later_send")
        case .attempted: return L("board_stats_attempt")
        }
    }

    var color: Color {
        switch self {
        case .flash: return ChartPalette.aqua
        case .laterSend: return ChartPalette.orange
        case .attempted: return ChartPalette.blue
        }
    }

    func count(in entry: GradeOutcomeUi) -> Int {
        switch self {
        case .flash: return Int(entry.flashes)
        case .laterSend: return Int(entry.redpoints)
        case .attempted: return Int(entry.attempts)
        }
    }

    static var styleScale: KeyValuePairs<String, Color> {
        [Outcome.flash.label: Outcome.flash.color,
         Outcome.laterSend.label: Outcome.laterSend.color,
         Outcome.attempted.label: Outcome.attempted.color]
    }
}

private enum GradeBand: CaseIterable, Hashable {
    case easy, medium, hard, elite

    var label: String {
        switch self {
        case .easy: return L("board_stats_grade_easy")
        case .medium: return L("board_stats_grade_medium")
        case .hard: return L("board_stats_grade_hard")
        case .elite: return L("board_stats_grade_elite")
        }
    }

    var color: Color {
        switch self {
        case .easy: return ChartPalette.band1
        case .medium: return ChartPalette.band2
        case .hard: return ChartPalette.band3
        case .elite: return ChartPalette.band4
        }
    }

    func count(in week: WeeklyVolumeUi) -> Int {
        switch self {
        case .easy: return Int(week.easy)
        case .medium: return Int(week.medium)
        case .hard: return Int(week.hard)
        case .elite: return Int(week.elite)
        }
    }

    static var styleScale: KeyValuePairs<String, Color> {
        [GradeBand.easy.label: GradeBand.easy.color,
         GradeBand.medium.label: GradeBand.medium.color,
         GradeBand.hard.label: GradeBand.hard.color,
         GradeBand.elite.label: GradeBand.elite.color]
    }
}

// MARK: - Activity calendar

private struct ActivityDay: Identifiable {
    let id: String
    let date: Date
    let count: Int

    /// Kotlin hands over two parallel lists sorted by date.
    static func build(dates: [String], counts: [KotlinInt]) -> [ActivityDay] {
        guard dates.count == counts.count else { return [] }
        return zip(dates, counts).compactMap { iso, count in
            guard let date = isoDayFormatter.date(from: iso) else { return nil }
            return ActivityDay(id: iso, date: date, count: Int(truncating: count))
        }
    }
}

private let isoDayFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.calendar = Calendar(identifier: .iso8601)
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyy-MM-dd"
    return formatter
}()

/// A week-column heatmap over the logged days, like Android's activity grid.
private struct ActivityCalendar: View {
    let days: [ActivityDay]

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
        return calendar
    }

    var body: some View {
        let byDay = Dictionary(days.map { (calendar.startOfDay(for: $0.date), $0.count) },
                               uniquingKeysWith: +)
        let maximum = max(byDay.values.max() ?? 1, 1)
        let columns = weekColumns(from: byDay)

        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 3) {
                    ForEach(Array(columns.enumerated()), id: \.offset) { _, week in
                        VStack(spacing: 3) {
                            ForEach(Array(week.enumerated()), id: \.offset) { _, day in
                                cell(count: day.map { byDay[$0] ?? 0 }, maximum: maximum)
                            }
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            HStack(spacing: 4) {
                Text(LI("stats_activity_fewer")).font(.caption2).foregroundStyle(.secondary)
                ForEach(Array(ChartPalette.activity.enumerated()), id: \.offset) { _, color in
                    RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 10, height: 10)
                }
                Text(LI("stats_activity_more")).font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func cell(count: Int?, maximum: Int) -> some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(fill(count: count, maximum: maximum))
            .frame(width: 11, height: 11)
    }

    private func fill(count: Int?, maximum: Int) -> Color {
        guard let count, count > 0 else { return Color.secondary.opacity(0.12) }
        let step = Int(ceil(Double(count) / Double(maximum) * Double(ChartPalette.activity.count)))
        return ChartPalette.activity[min(max(step, 1), ChartPalette.activity.count) - 1]
    }

    /// Whole ISO weeks from the first to the last logged day; nil = outside the range.
    private func weekColumns(from byDay: [Date: Int]) -> [[Date?]] {
        guard let first = byDay.keys.min(), let last = byDay.keys.max() else { return [] }
        guard let start = calendar.dateInterval(of: .weekOfYear, for: first)?.start,
              let end = calendar.dateInterval(of: .weekOfYear, for: last)?.end else { return [] }

        var columns: [[Date?]] = []
        var cursor = start
        // Bounded: a whole year of daily entries is 53 columns.
        while cursor < end && columns.count < 64 {
            var week: [Date?] = []
            for offset in 0..<7 {
                guard let day = calendar.date(byAdding: .day, value: offset, to: cursor) else { break }
                week.append(day <= last ? day : nil)
            }
            columns.append(week)
            guard let next = calendar.date(byAdding: .weekOfYear, value: 1, to: cursor) else { break }
            cursor = next
        }
        return columns
    }
}
