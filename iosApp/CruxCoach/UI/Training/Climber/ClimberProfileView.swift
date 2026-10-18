import Charts
import CruxCoachCore
import SwiftUI

/// The climber profile (Android `ClimberProfileScreen`): headline tiles; strength by grade for
/// fingers and pulling against the orientation band with the own path over time; working grade and
/// finger strength on one timeline with their correlation; flash, working grade and hardest send;
/// one-arm pick-up per hand; load per structure; strengths and bottlenecks. Every number is the
/// Kotlin presenter's (`ClimberProfileScreenModel`). Push it inside a `NavigationStack`.
struct ClimberProfileView: View {
    let core: AppCore

    @State private var host: ScreenHost<ClimberProfileScreenModel, ClimberProfileScreenState>?
    @State private var loggingDay = false
    @State private var appeared = false

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Group {
            if let host {
                ClimberProfileContent(core: core, ui: host.state, onLogClimbing: { loggingDay = true })
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("trl_profile_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeClimberProfileScreen() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
        .onAppear {
            // Android reloads when the screen is entered again (after the values or a climbing day).
            if appeared { host?.model.reload() } else { appeared = true }
        }
        .sheet(isPresented: $loggingDay) {
            ClimbingDaySheetView(core: core, editId: "", onDone: {
                loggingDay = false
                host?.model.reload()
            })
        }
    }
}

private struct ClimberProfileContent: View {
    let core: AppCore
    let ui: ClimberProfileScreenState
    let onLogClimbing: () -> Void

    var body: some View {
        if ui.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if ui.failed {
            Text(LI("climber_profile_failed"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(16)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ClimberTiles(tiles: ui.tiles)
                    if ui.isEmpty {
                        ClimberEmptyCard(core: core, onLogClimbing: onLogClimbing)
                    }
                    upperSections
                    lowerSections
                    Text(L("trl_disclaimer")).font(.caption).foregroundStyle(.secondary).padding(.top, 8)
                    Spacer(minLength: 24)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
        }
    }

    @ViewBuilder
    private var upperSections: some View {
        ClimberSectionTitle(text: L("trl_position_title"))
        ClimberPositionSection(core: core, positions: ui.positions, onLogClimbing: onLogClimbing)
        ClimberSectionTitle(text: L("trl_timeline_title"))
        ClimberTimelineSection(core: core, ui: ui)
        if ui.hasGap {
            ClimberSectionTitle(text: L("trl_gap_title"))
            ClimberGapSection(ui: ui)
        }
    }

    @ViewBuilder
    private var lowerSections: some View {
        if ui.hasPickup {
            ClimberSectionTitle(text: L("trl_pickup_title"))
            ClimberPickupSection(ui: ui)
        }
        if ui.hasLoad {
            ClimberSectionTitle(text: L("trl_load_title"))
            ClimberLoadSection(loads: ui.loads, onLogClimbing: onLogClimbing)
        }
        ClimberSectionTitle(text: L("trl_bottleneck_title"))
        ClimberBottlenecks(items: ui.bottlenecks)
    }
}

// MARK: - Shared pieces

private enum ClimberColors {
    /// Android: the band in the primary colour, "you" in the brand accent.
    static let band = ChartPalette.blue
    static let you = ChartPalette.orange

    static func series(_ index: Int) -> Color {
        index == 0 ? ChartPalette.blue : ChartPalette.orange
    }

    static func gap(_ code: String) -> Color {
        switch code {
        case "flash": return TrainingStyle.positive
        case "working": return ChartPalette.orange
        default: return Color.purple
        }
    }
}

private enum ClimberDates {
    /// The shared date axis; a single day is widened to two so the chart has a width.
    static func domain(_ start: String, _ end: String) -> ClosedRange<Date> {
        let first: Date = ProgressDates.date(start) ?? Date()
        let last: Date = ProgressDates.date(end) ?? first
        let widened: Date = max(last, first.addingTimeInterval(86_400))
        return first...widened
    }
}

private struct ClimberSectionTitle: View {
    let text: String

    var body: some View {
        Text(text).font(.headline).padding(.top, 8)
    }
}

private struct ClimberLegend: View {
    let color: Color
    let label: String

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 10, height: 10)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct ClimberStatTile: View {
    let label: String
    let value: String
    let supporting: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(value).font(.title3.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.7)
            if let supporting {
                Text(supporting).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct ClimberValuesLink: View {
    let core: AppCore

    var body: some View {
        NavigationLink {
            ProgressBenchmarksView(core: core)
        } label: {
            Label(L("trl_action_values"), systemImage: "plus")
        }
        .font(.subheadline)
    }
}

private struct ClimberTiles: View {
    let tiles: [ClimberTileUi]

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(Array(tiles.enumerated()), id: \.offset) { _, tile in
                ClimberStatTile(label: TrRender.text(tile.label), value: TrRender.text(tile.value),
                                supporting: TrRender.optional(tile.supporting))
            }
        }
    }
}

private struct ClimberEmptyCard: View {
    let core: AppCore
    let onLogClimbing: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: "chart.dots.scatter").foregroundStyle(ClimberColors.you)
            Text(L("trl_empty_title")).font(.headline)
            Text(L("trl_empty_text")).font(.subheadline)
            HStack(spacing: 8) {
                NavigationLink {
                    ProgressBenchmarksView(core: core)
                } label: {
                    Text(L("trl_action_values"))
                }
                .buttonStyle(.borderedProminent)
                Button(L("trl_action_log_climbing"), action: onLogClimbing).buttonStyle(.bordered)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Strength by grade

private struct ClimberPositionSection: View {
    let core: AppCore
    let positions: [ClimberPositionUi]
    let onLogClimbing: () -> Void

    @State private var tab = "finger"

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                ForEach(positions, id: \.metricCode) { position in
                    TrainingChoiceChip(title: TrRender.text(position.tab), selected: tab == position.metricCode) {
                        tab = position.metricCode
                    }
                }
            }
            if let current = selected {
                ClimberPositionBody(core: core, position: current, onLogClimbing: onLogClimbing)
            }
        }
    }

    private var selected: ClimberPositionUi? {
        positions.first(where: { $0.metricCode == tab }) ?? positions.first
    }
}

private struct ClimberPositionBody: View {
    let core: AppCore
    let position: ClimberPositionUi
    let onLogClimbing: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tr: position.axis).font(.caption).foregroundStyle(.secondary)
            switch position.stateCode {
            case "noValues":
                Text(tr: position.emptyText).font(.subheadline)
                ClimberValuesLink(core: core)
            case "noGrade":
                Text(tr: position.emptyText).font(.subheadline)
                Button(L("trl_action_log_climbing"), action: onLogClimbing).buttonStyle(.borderless)
            default:
                chart
            }
        }
    }

    @ViewBuilder
    private var chart: some View {
        ClimberPositionChart(position: position)
        HStack(spacing: 16) {
            ClimberLegend(color: ClimberColors.band.opacity(0.4), label: L("trl_legend_band"))
            ClimberLegend(color: ClimberColors.you, label: L("trl_legend_you"))
        }
        if let reading = TrRender.optional(position.reading) {
            Text(reading).font(.subheadline)
        }
        if position.showEstimatedNote {
            Text(L("trl_position_estimated")).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Android `GradePositionChart`: x = grade climbed at the time, y = strength in % of body weight;
/// the shaded band is the orientation, the dots the athlete's path (older ones fainter, own
/// estimates hollow), the newest one with its callout.
private struct ClimberPositionChart: View {
    let position: ClimberPositionUi

    var body: some View {
        let gradeValues: [Double] = position.gradeTicks.map { $0.value }
        let strengthValues: [Double] = position.valueTicks.map { $0.value }
        Chart {
            bandMarks
            pathMarks
            pointMarks
            calloutMark
        }
        .chartXScale(domain: position.xMin...position.xMax)
        .chartYScale(domain: position.yMin...position.yMax)
        .chartXAxis {
            AxisMarks(values: gradeValues) { value in
                AxisGridLine()
                AxisValueLabel { Text(label(of: value, in: position.gradeTicks)) }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: strengthValues) { value in
                AxisGridLine()
                AxisValueLabel { Text(label(of: value, in: position.valueTicks)) }
            }
        }
        .frame(height: 240)
        .accessibilityLabel(TrRender.text(position.chartDescription))
    }

    private func label(of value: AxisValue, in ticks: [ClimberTickUi]) -> String {
        guard let number = value.as(Double.self) else { return "" }
        return ticks.first(where: { abs($0.value - number) < 0.01 })?.label ?? ""
    }

    @ChartContentBuilder
    private var bandMarks: some ChartContent {
        ForEach(Array(position.band.enumerated()), id: \.offset) { _, band in
            AreaMark(x: .value(LI("climber_axis_grade"), band.difficulty),
                     yStart: .value(LI("climber_axis_strength"), band.low),
                     yEnd: .value(LI("climber_axis_strength"), band.high))
                .foregroundStyle(ClimberColors.band.opacity(0.14))
            LineMark(x: .value(LI("climber_axis_grade"), band.difficulty),
                     y: .value(LI("climber_axis_strength"), band.center),
                     series: .value("line", "band"))
                .foregroundStyle(ClimberColors.band.opacity(0.45))
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
        }
    }

    @ChartContentBuilder
    private var pathMarks: some ChartContent {
        ForEach(Array(position.points.enumerated()), id: \.offset) { _, point in
            LineMark(x: .value(LI("climber_axis_grade"), point.difficulty),
                     y: .value(LI("climber_axis_strength"), point.pctBw),
                     series: .value("line", "you"))
                .foregroundStyle(ClimberColors.you.opacity(0.6))
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
        }
    }

    @ChartContentBuilder
    private var pointMarks: some ChartContent {
        ForEach(Array(position.points.enumerated()), id: \.offset) { _, point in
            dot(point)
        }
    }

    /// A filled dot; an own estimate is a ring (a background-coloured dot on top).
    @ChartContentBuilder
    private func dot(_ point: ClimberPositionPointUi) -> some ChartContent {
        let size: CGFloat = point.newest ? 110 : 55
        PointMark(x: .value(LI("climber_axis_grade"), point.difficulty),
                  y: .value(LI("climber_axis_strength"), point.pctBw))
            .symbolSize(size)
            .foregroundStyle(ClimberColors.you.opacity(point.opacity))
        if point.estimated {
            PointMark(x: .value(LI("climber_axis_grade"), point.difficulty),
                      y: .value(LI("climber_axis_strength"), point.pctBw))
                .symbolSize(size * 0.35)
                .foregroundStyle(Color(.systemBackground))
        }
    }

    @ChartContentBuilder
    private var calloutMark: some ChartContent {
        if let newest = position.points.last {
            PointMark(x: .value(LI("climber_axis_grade"), newest.difficulty),
                      y: .value(LI("climber_axis_strength"), newest.pctBw))
                .symbolSize(420)
                .foregroundStyle(ClimberColors.you.opacity(0.25))
                .annotation(position: .top, alignment: .center, spacing: 6) {
                    ClimberCallout(text: TrRender.text(position.youLabel))
                }
        }
    }
}

private struct ClimberCallout: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(Color(.systemBackground).opacity(0.92), in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(ClimberColors.you, lineWidth: 1))
    }
}

// MARK: - Grade and finger strength over time

private struct ClimberTimelineSection: View {
    let core: AppCore
    let ui: ClimberProfileScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if ui.timelineEmpty {
                Text(L("trl_timeline_empty")).font(.subheadline)
                ClimberValuesLink(core: core)
            } else {
                ClimberAlignedRows(rows: ui.timeline, start: ui.timelineStart, end: ui.timelineEnd, rowHeight: 96)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(TrRender.text(ui.timelineDescription))
                ClimberCorrelationCard(ui: ui)
            }
        }
    }
}

private struct ClimberCorrelationCard: View {
    let ui: ClimberProfileScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if ui.hasCorrelation {
                Text(tr: ui.correlationValue).font(.subheadline.weight(.semibold))
            }
            Text(tr: ui.correlationText).font(.subheadline)
            Text(L("trl_corr_note")).font(.caption).foregroundStyle(.secondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Android `AlignedTimeline`: small line charts stacked on one shared date axis.
private struct ClimberAlignedRows: View {
    let rows: [ClimberTimelineRowUi]
    let start: String
    let end: String
    let rowHeight: CGFloat

    var body: some View {
        let domain: ClosedRange<Date> = ClimberDates.domain(start, end)
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                ClimberTimelineRow(row: row, color: ClimberColors.series(index), domain: domain, height: rowHeight)
            }
            HStack {
                Text(TrRender.date(start))
                Spacer()
                if end != start {
                    Text(TrRender.date(end))
                }
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.leading, 48)
        }
    }
}

private struct ClimberTimelineRow: View {
    let row: ClimberTimelineRowUi
    let color: Color
    let domain: ClosedRange<Date>
    let height: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                ClimberLegend(color: color, label: TrRender.text(row.label))
                Spacer()
                Text(row.latest).font(.subheadline.weight(.semibold))
            }
            if row.points.isEmpty {
                Spacer().frame(height: height / 3)
            } else {
                HStack(spacing: 4) {
                    VStack(alignment: .trailing) {
                        Text(row.maxLabel)
                        Spacer()
                        Text(row.minLabel)
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(width: 44)
                    ClimberRowChart(points: row.points, color: color, domain: domain, minY: row.minY, maxY: row.maxY,
                                    title: TrRender.text(row.label))
                }
                .frame(height: height)
            }
        }
    }
}

private struct ClimberRowChart: View {
    let points: [ProgressPointUi]
    let color: Color
    let domain: ClosedRange<Date>
    let minY: Double
    let maxY: Double
    let title: String

    var body: some View {
        Chart {
            ForEach(Array(points.enumerated()), id: \.offset) { _, point in
                marks(point)
            }
        }
        .chartXScale(domain: domain)
        .chartYScale(domain: minY...maxY)
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(values: .automatic(desiredCount: 3)) { _ in
                AxisGridLine()
            }
        }
    }

    @ChartContentBuilder
    private func marks(_ point: ProgressPointUi) -> some ChartContent {
        if let date = ProgressDates.date(point.isoDate) {
            LineMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(title, point.value))
                .foregroundStyle(color)
                .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            PointMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(title, point.value))
                .foregroundStyle(color)
                .symbolSize(24)
        }
    }
}

// MARK: - Flash and limit

private struct ClimberGapSection: View {
    let ui: ClimberProfileScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ClimberGapGauge(ui: ui)
            HStack(spacing: 16) {
                ForEach(ui.gapMarks, id: \.code) { mark in
                    ClimberLegend(color: ClimberColors.gap(mark.code), label: TrRender.text(mark.title))
                }
            }
            if let text = TrRender.optional(ui.gapText) {
                Text(text).font(.subheadline)
            }
            HStack(spacing: 8) {
                ClimberStatTile(label: L("trl_attempts_tile"), value: TrRender.text(ui.attempts), supporting: L("trl_attempts_sub"))
                ClimberStatTile(label: L("trl_angle_tile"), value: ui.topAngle, supporting: ui.brand.isEmpty ? nil : ui.brand)
            }
        }
    }
}

/// Android `GradeGapGauge`: flash level, working grade and hardest send on one grade scale, with
/// the flash–working stretch marked.
private struct ClimberGapGauge: View {
    let ui: ClimberProfileScreenState

    var body: some View {
        let tickValues: [Double] = ui.gapTicks.map { $0.value }
        Chart {
            RuleMark(xStart: .value(LI("climber_axis_grade"), ui.gapLo), xEnd: .value(LI("climber_axis_grade"), ui.gapHi),
                     y: .value("track", 0.0))
                .lineStyle(StrokeStyle(lineWidth: 8, lineCap: .round))
                .foregroundStyle(Color(.tertiarySystemFill))
            stretch
            ForEach(ui.gapMarks, id: \.code) { mark in
                PointMark(x: .value(LI("climber_axis_grade"), mark.difficulty), y: .value("track", 0.0))
                    .symbolSize(180)
                    .foregroundStyle(ClimberColors.gap(mark.code))
            }
        }
        .chartXScale(domain: ui.gapLo...ui.gapHi)
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks(values: tickValues) { value in
                AxisValueLabel { Text(label(of: value)) }
            }
        }
        .frame(height: 56)
        .accessibilityLabel(TrRender.text(ui.gapDescription))
    }

    @ChartContentBuilder
    private var stretch: some ChartContent {
        if ui.gapBand {
            RuleMark(xStart: .value(LI("climber_axis_grade"), ui.gapFlash), xEnd: .value(LI("climber_axis_grade"), ui.gapWorking),
                     y: .value("track", 0.0))
                .lineStyle(StrokeStyle(lineWidth: 8))
                .foregroundStyle(ChartPalette.orange.opacity(0.25))
        }
    }

    private func label(of value: AxisValue) -> String {
        guard let number = value.as(Double.self) else { return "" }
        return ui.gapTicks.first(where: { abs($0.value - number) < 0.01 })?.label ?? ""
    }
}

// MARK: - One-arm pick-up

private struct ClimberPickupSection: View {
    let ui: ClimberProfileScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ClimberAlignedRows(rows: ui.pickup, start: ui.pickupStart, end: ui.pickupEnd, rowHeight: 72)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(L("trl_pickup_cd"))
            if let balance = TrRender.optional(ui.pickupBalance) {
                Text(balance).font(.subheadline)
            }
        }
    }
}

// MARK: - Load

private struct ClimberLoadSection: View {
    let loads: [ClimberLoadUi]
    let onLogClimbing: () -> Void

    @State private var code = "finger"

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(loads, id: \.code) { load in
                        TrainingChoiceChip(title: TrRender.text(load.chip), selected: code == load.code) { code = load.code }
                    }
                }
            }
            if let load = selected {
                ClimberLoadChart(load: load)
                HStack(spacing: 16) {
                    ClimberLegend(color: ClimberColors.you, label: L("trl_legend_acute"))
                    ClimberLegend(color: Color.secondary, label: L("trl_legend_chronic"))
                }
                if let text = TrRender.optional(load.explanation) {
                    Text(text).font(.subheadline)
                }
            }
            Text(L("trl_load_note")).font(.caption).foregroundStyle(.secondary)
            Button(action: onLogClimbing) {
                Label(L("trl_action_log_climbing"), systemImage: "plus")
            }
            .buttonStyle(.borderless)
        }
    }

    private var selected: ClimberLoadUi? {
        loads.first(where: { $0.code == code }) ?? loads.first
    }
}

/// Android `LoadTrendChart`: daily load as faint bars, the 7-day mean solid and the 28-day mean
/// dashed on top; where the two lines part, load changes faster than the body is used to.
private struct ClimberLoadChart: View {
    let load: ClimberLoadUi

    var body: some View {
        Chart {
            ForEach(Array(load.days.enumerated()), id: \.offset) { _, day in
                marks(day)
            }
        }
        .chartYScale(domain: 0...load.maxY)
        .chartYAxis(.hidden)
        .chartLegend(.hidden)
        .frame(height: 140)
        .accessibilityLabel(TrRender.text(load.chartDescription))
    }

    @ChartContentBuilder
    private func marks(_ day: ClimberLoadDayUi) -> some ChartContent {
        if let date = ProgressDates.date(day.isoDate) {
            BarMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(L("trl_load_title"), day.value))
                .foregroundStyle(ClimberColors.band.opacity(0.25))
            LineMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(L("trl_load_title"), day.chronic),
                     series: .value("mean", "chronic"))
                .foregroundStyle(Color.secondary)
                .lineStyle(StrokeStyle(lineWidth: 2, dash: [5, 4]))
            LineMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(L("trl_load_title"), day.acute),
                     series: .value("mean", "acute"))
                .foregroundStyle(ClimberColors.you)
                .lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
        }
    }
}

// MARK: - Strengths and bottlenecks

/// Android `BottleneckBars`: diverging bars around "typical for your grade" — left (caution) is
/// weaker, right (positive) stronger; an area without a norm shows its reason instead of a bar.
private struct ClimberBottlenecks: View {
    let items: [ClimberBottleneckUi]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(items, id: \.code) { item in
                ClimberBottleneckRow(item: item)
            }
        }
    }
}

private struct ClimberBottleneckRow: View {
    let item: ClimberBottleneckUi

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(tr: item.label).font(.subheadline.weight(.medium)).frame(width: 120, alignment: .leading)
                GeometryReader { geo in
                    ClimberDivergingBar(hasScore: item.hasScore, score: item.score, width: geo.size.width)
                }
                .frame(height: 14)
            }
            Text(tr: item.reason).font(.caption).foregroundStyle(.secondary).padding(.leading, 128)
        }
    }
}

private struct ClimberDivergingBar: View {
    let hasScore: Bool
    let score: Double
    let width: CGFloat

    private var half: CGFloat { width / 2 }
    private var clamped: Double { min(max(score, -1), 1) }
    private var length: CGFloat { max(abs(CGFloat(clamped)) * half, 3) }
    private var left: CGFloat { clamped < 0 ? half - length : half }
    private var color: Color { clamped < 0 ? TrainingStyle.caution : TrainingStyle.positive }

    var body: some View {
        ZStack(alignment: .leading) {
            Capsule().fill(Color(.tertiarySystemFill).opacity(hasScore ? 1 : 0.5))
            if hasScore {
                Capsule().fill(color).frame(width: length).offset(x: left)
                Rectangle().fill(Color.secondary).frame(width: 2).offset(x: half - 1)
            }
        }
        .frame(width: width, height: 14)
    }
}
