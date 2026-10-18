import Charts
import CruxCoachCore
import SwiftUI

/// The Progress tab (Android `StatsHubScreen`): the four hub tiles, training days and minutes per
/// week, the calendar, load per structure, body weight, strength to weight and the trained
/// exercises. Every number is the Kotlin presenter's (`ProgressScreenModel`); this view draws it.
struct TrainingProgressView: View {
    let core: AppCore
    var onClose: () -> Void = {}

    @State private var host: ScreenHost<ProgressScreenModel, ProgressScreenState>?

    init(core: AppCore, onClose: @escaping () -> Void = {}) {
        self.core = core
        self.onClose = onClose
    }

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    ProgressContent(core: core, model: host.model, ui: host.state)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(L("tr_tab_progress"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_close"), action: onClose)
                }
            }
            .navigationDestination(for: ProgressRoute.self) { route in
                switch route {
                case .body: ProgressBodyView(core: core)
                case .benchmarks: ProgressBenchmarksView(core: core)
                case .exercise(let slug): ProgressExerciseStatsView(core: core, slug: slug)
                }
            }
        }
        .task {
            guard host == nil, let model = core.makeProgressScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

enum ProgressRoute: Hashable { case body, benchmarks, exercise(String) }

/// ISO days (yyyy-mm-dd) as local midnight, so a chart's date axis shows the same day.
enum ProgressDates {
    private static let parser: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func date(_ iso: String) -> Date? { parser.date(from: iso) }

    static func iso(_ date: Date) -> String { parser.string(from: date) }
}

private struct ProgressContent: View {
    let core: AppCore
    let model: ProgressScreenModel
    let ui: ProgressScreenState

    @State private var entered = false
    @State private var showWeight = false
    @State private var notYet: String?

    private var climbLabel: String { L("trs_legend_climbing") }
    private var offBoardLabel: String { L("trs_legend_off_board") }
    private var seriesScale: KeyValuePairs<String, Color> {
        [L("trs_legend_climbing"): ChartPalette.blue, L("trs_legend_off_board"): ChartPalette.orange]
    }

    var body: some View {
        Group {
            if ui.loading && ui.weeks.isEmpty {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        hub
                        sectionTitle(L("tru_progress_training"))
                        TrainingFlow {
                            ForEach(model.rangeCodes, id: \.self) { code in
                                TrainingChoiceChip(title: TrRender.text(model.rangeLabel(code: code)),
                                                   selected: ui.rangeCode == code) { model.setRange(code: code) }
                            }
                        }
                        climberProfileCard
                        // Grouped so the stack stays within the classic ten-view builder limit.
                        Group {
                            if ui.hasLoad { loadChips }
                            if !ui.hasAnyData { hint(L("trs_empty")) }
                            if !ui.trained && ui.hasAnyData { hint(L("trs_no_training")) }
                            if ui.trained { trainingCharts }
                        }
                        bodySection
                        if !ui.strength.isEmpty { strengthSection }
                        exercisesSection
                        Spacer(minLength: 24)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
            }
        }
        .onAppear {
            // The tab keeps its presenter; a return recomputes so a just-finished training shows up.
            if entered { model.reload() } else { entered = true }
        }
        .sheet(isPresented: $showWeight) {
            ProgressWeightSheet(unitsCode: ui.unitsCode, model: model) { showWeight = false }
                .presentationDetents([.medium])
        }
        .alert(notYet ?? "", isPresented: Binding(get: { notYet != nil }, set: { if !$0 { notYet = nil } })) {
            Button(L("action_ok")) { notYet = nil }
        } message: {
            Text(LI("training_not_on_iphone"))
        }
    }

    // MARK: Hub

    private var hub: some View {
        Grid(horizontalSpacing: 12, verticalSpacing: 12) {
            GridRow {
                if ui.bodyIsAction {
                    ProgressTile(icon: "scalemass", tint: .purple, label: L("tr_nav_body"),
                                 value: TrRender.text(ui.bodyValue), valueIsAction: true,
                                 supporting: L("tru_hub_body")) { showWeight = true }
                } else {
                    NavigationLink(value: ProgressRoute.body) {
                        ProgressTileFace(icon: "scalemass", tint: .purple, label: L("tr_nav_body"),
                                         value: TrRender.text(ui.bodyValue), valueIsAction: false,
                                         supporting: L("tru_hub_body"))
                    }
                    .buttonStyle(.plain)
                }
                ProgressTile(icon: "calendar", tint: .accentColor, label: L("trt_weekly_review"),
                             value: ui.weeklyValue, valueIsAction: false,
                             supporting: L("trs_tile_training_days")) { notYet = L("trt_weekly_review") }
            }
            GridRow {
                NavigationLink(value: ProgressRoute.benchmarks) {
                    ProgressTileFace(icon: "speedometer", tint: TrainingStyle.positive, label: L("trbm_title"),
                                     value: TrRender.text(ui.benchmarksValue), valueIsAction: ui.benchmarksIsAction,
                                     supporting: L("tru_hub_benchmarks"))
                }
                .buttonStyle(.plain)
                ProgressTile(icon: "clock.arrow.circlepath", tint: .teal, label: L("trt_done_hero_history"),
                             value: "\(ui.historyValue)", valueIsAction: false,
                             supporting: TrRender.text(ui.historySupporting)) { notYet = L("trt_done_hero_history") }
            }
        }
    }

    private var climberProfileCard: some View {
        Button { notYet = L("trl_profile_title") } label: {
            HStack(spacing: 12) {
                Image(systemName: "chart.dots.scatter").font(.title2).foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("trl_profile_title")).font(.headline)
                    Text(tr: ui.profileLine).font(.subheadline).foregroundStyle(.secondary)
                    Text(L("trl_card_hint")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
            }
            .padding(16)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }

    private var loadChips: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("trl_load_title")).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
            TrainingFlow {
                ForEach(ui.loadChips, id: \.code) { chip in
                    Text(tr: chip.label)
                        .font(.subheadline)
                        .foregroundStyle(chip.trendCode == "rising" ? TrainingStyle.caution : Color.primary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(chip.trendCode == "spike" ? TrainingStyle.caution.opacity(0.25) : Color(.secondarySystemBackground),
                                    in: Capsule())
                }
            }
        }
    }

    // MARK: Training charts

    @ViewBuilder
    private var trainingCharts: some View {
        sectionTitle(L("trs_days_per_week"))
        Chart {
            ForEach(ui.weeks, id: \.weekStartIso) { week in
                if let date = ProgressDates.date(week.weekStartIso) {
                    BarMark(x: .value(LI("stats_axis_week"), date, unit: .weekOfYear),
                            y: .value(L("trs_days_per_week"), Int(week.climbingDays)))
                        .foregroundStyle(by: .value(LI("stats_series_outcome"), climbLabel))
                    BarMark(x: .value(LI("stats_axis_week"), date, unit: .weekOfYear),
                            y: .value(L("trs_days_per_week"), Int(week.offBoardDays)))
                        .foregroundStyle(by: .value(LI("stats_series_outcome"), offBoardLabel))
                }
            }
        }
        .chartForegroundStyleScale(seriesScale)
        .chartLegend(position: .bottom, alignment: .leading)
        .frame(height: 160)
        .accessibilityLabel(TrRender.text(ui.daysChartDescription))

        sectionTitle(L("trs_minutes_per_week"))
        Chart {
            ForEach(ui.weeks, id: \.weekStartIso) { week in
                if let date = ProgressDates.date(week.weekStartIso) {
                    BarMark(x: .value(LI("stats_axis_week"), date, unit: .weekOfYear),
                            y: .value(L("trs_minutes_per_week"), Int(week.climbingMinutes)))
                        .foregroundStyle(by: .value(LI("stats_series_outcome"), climbLabel))
                    BarMark(x: .value(LI("stats_axis_week"), date, unit: .weekOfYear),
                            y: .value(L("trs_minutes_per_week"), Int(week.workoutMinutes)))
                        .foregroundStyle(by: .value(LI("stats_series_outcome"), offBoardLabel))
                }
            }
        }
        .chartForegroundStyleScale(seriesScale)
        .chartLegend(.hidden)
        .frame(height: 140)
        .accessibilityLabel(TrRender.text(ui.minutesChartDescription))

        sectionTitle(L("trs_calendar"), info: L("trs_calendar_hint"))
        ProgressCalendarHeatmap(days: ui.calendar)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(TrRender.text(ui.calendarDescription))

        sectionTitle(L("trs_load_title"), info: L("trs_load_hint"))
        ForEach(ui.domains, id: \.code) { domain in
            HStack(spacing: 8) {
                Text(tr: domain.label).font(.subheadline).frame(width: 96, alignment: .leading)
                Chart {
                    ForEach(Array(domain.days.enumerated()), id: \.offset) { item in
                        BarMark(x: .value(LI("stats_axis_week"), item.offset),
                                y: .value(L("trs_load_title"), item.element.intValue))
                            .foregroundStyle(ChartPalette.blue)
                    }
                }
                .chartXAxis(.hidden)
                .chartYAxis(.hidden)
                .frame(height: 40)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(TrRender.text(domain.description))
        }
    }

    // MARK: Body and strength

    @ViewBuilder
    private var bodySection: some View {
        HStack {
            sectionTitle(L("tr_nav_body"))
            Spacer()
            NavigationLink(L("trs_open_body"), value: ProgressRoute.body).font(.subheadline)
        }
        if ui.weight.isEmpty {
            Text(L("trs_body_empty")).font(.caption).foregroundStyle(.secondary)
            Button(L("tru_enter_weight")) { showWeight = true }
                .buttonStyle(.bordered)
        } else {
            NavigationLink(value: ProgressRoute.body) {
                Chart {
                    ForEach(ui.weight, id: \.isoDate) { point in
                        if let date = ProgressDates.date(point.isoDate) {
                            LineMark(x: .value(LI("stats_axis_date"), date, unit: .day),
                                     y: .value(L("tr_metric_weight"), point.value))
                                .foregroundStyle(Color.purple)
                                .interpolationMethod(.monotone)
                        }
                    }
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .chartYAxis(ui.hideNumbers ? .hidden : .automatic)
                .frame(height: 150)
                .accessibilityLabel(L("trs_weight_cd"))
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private var strengthSection: some View {
        sectionTitle(L("trs_strength_title"), info: L("trs_strength_hint"))
        Chart {
            ForEach(ui.strength, id: \.slug) { series in
                ForEach(series.points, id: \.isoDate) { point in
                    if let date = ProgressDates.date(point.isoDate) {
                        LineMark(x: .value(LI("stats_axis_date"), date, unit: .day),
                                 y: .value(L("trs_tile_percent_bw"), point.value))
                            .foregroundStyle(by: .value(L("tr_tab_exercises"), series.name))
                        PointMark(x: .value(LI("stats_axis_date"), date, unit: .day),
                                  y: .value(L("trs_tile_percent_bw"), point.value))
                            .foregroundStyle(by: .value(L("tr_tab_exercises"), series.name))
                            .symbolSize(30)
                    }
                }
            }
        }
        .chartYScale(domain: .automatic(includesZero: false))
        .chartYAxis {
            AxisMarks { value in
                AxisGridLine()
                AxisValueLabel {
                    if let v = value.as(Double.self) { Text("\(Int(v.rounded())) %") }
                }
            }
        }
        .chartLegend(position: .bottom, alignment: .leading)
        .frame(height: 170)
        .accessibilityLabel(L("trs_strength_cd"))
    }

    // MARK: Exercises

    @ViewBuilder
    private var exercisesSection: some View {
        sectionTitle(L("tr_tab_exercises"))
        if ui.exercises.isEmpty {
            Text(L("trs_exercises_empty")).font(.caption).foregroundStyle(.secondary)
        }
        ForEach(ui.exercises, id: \.slug) { row in
            NavigationLink(value: ProgressRoute.exercise(row.slug)) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.name).font(.body)
                        Text(tr: row.supporting).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    ProgressSparkline(values: row.spark.map { $0.doubleValue })
                        .frame(width: 64, height: 24)
                    Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                }
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Divider()
        }
    }

    // MARK: Pieces

    private func sectionTitle(_ text: String, info: String? = nil) -> some View {
        HStack(spacing: 4) {
            Text(text).font(.headline)
            if let info { ProgressInfoButton(title: text, text: info) }
        }
        .padding(.top, 8)
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// The "how to read this chart" behind an info icon at a title (design.md), not a line under it.
struct ProgressInfoButton: View {
    let title: String
    let text: String
    @State private var shown = false

    var body: some View {
        Button { shown = true } label: {
            Image(systemName: "info.circle").font(.subheadline).foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .alert(title, isPresented: $shown) {
            Button(L("action_ok")) { shown = false }
        } message: {
            Text(text)
        }
    }
}

/// A hub tile with its current value (Android `ValueTile`).
struct ProgressTileFace: View {
    let icon: String
    let tint: Color
    let label: String
    let value: String
    let valueIsAction: Bool
    let supporting: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: icon).foregroundStyle(tint)
                Text(label).font(.caption.weight(.semibold)).foregroundStyle(.secondary).lineLimit(1)
            }
            Text(value)
                .font(valueIsAction ? .headline : .title3.weight(.semibold))
                .foregroundStyle(valueIsAction ? Color.accentColor : Color.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(supporting).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
    }
}

private struct ProgressTile: View {
    let icon: String
    let tint: Color
    let label: String
    let value: String
    let valueIsAction: Bool
    let supporting: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ProgressTileFace(icon: icon, tint: tint, label: label, value: value,
                             valueIsAction: valueIsAction, supporting: supporting)
        }
        .buttonStyle(.plain)
    }
}

/// A tiny trend line of the last sessions.
private struct ProgressSparkline: View {
    let values: [Double]

    var body: some View {
        if values.count >= 2 {
            Chart {
                ForEach(Array(values.enumerated()), id: \.offset) { item in
                    LineMark(x: .value("i", item.offset), y: .value("v", item.element))
                        .foregroundStyle(ChartPalette.blue)
                }
            }
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .chartYScale(domain: .automatic(includesZero: false))
        } else {
            Color.clear
        }
    }
}

/// 16 weeks, one column per week, Monday on top; a stronger colour is a harder day (Android `CalendarHeatmap`).
private struct ProgressCalendarHeatmap: View {
    let days: [ProgressCalendarDayUi]

    var body: some View {
        let columns = Self.columns(days)
        HStack(alignment: .top, spacing: 3) {
            ForEach(Array(columns.enumerated()), id: \.offset) { _, week in
                VStack(spacing: 3) {
                    ForEach(1...7, id: \.self) { weekday in
                        let level = week[weekday]
                        RoundedRectangle(cornerRadius: 2)
                            .fill(Self.fill(level))
                            .frame(width: 14, height: 14)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Weeks from the oldest day on; a weekday without a day in range is nil (drawn empty).
    private static func columns(_ days: [ProgressCalendarDayUi]) -> [[Int: Int?]] {
        var columns: [[Int: Int?]] = []
        var current: [Int: Int?] = [:]
        for day in days {
            let weekday = Int(day.isoWeekday)
            if weekday == 1 && !current.isEmpty {
                columns.append(current)
                current = [:]
            }
            current[weekday] = Int(day.level)
        }
        if !current.isEmpty { columns.append(current) }
        return columns
    }

    private static func fill(_ level: Int??) -> Color {
        guard let inner = level, let value = inner else { return Color.clear }
        if value <= 0 { return Color.secondary.opacity(0.12) }
        let palette = ChartPalette.activity
        return palette[min(max(value, 1), palette.count) - 1]
    }
}

/// A weigh-in from the hub when there is none yet (Android `WeightSheet`).
private struct ProgressWeightSheet: View {
    let unitsCode: String
    let model: ProgressScreenModel
    let onDone: () -> Void
    @State private var text = ""
    @State private var invalid = false

    var body: some View {
        NavigationStack {
            Form {
                HStack {
                    TextField(L("trq_weight"), text: $text).keyboardType(.decimalPad)
                    Text(unitsCode == "imperial" ? "lb" : "kg").foregroundStyle(.secondary)
                }
                if invalid { Text(LI("training_weight_invalid")).font(.caption).foregroundStyle(.red) }
            }
            .navigationTitle(L("tru_enter_weight"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel"), action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("tr_action_save")) {
                        if let v = TrRender.parse(text), model.logWeight(display: v) { onDone() } else { invalid = true }
                    }
                }
            }
        }
    }
}
