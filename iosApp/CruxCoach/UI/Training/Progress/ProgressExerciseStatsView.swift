import Charts
import CruxCoachCore
import SwiftUI

/// One exercise over time (Android `ExerciseStatsScreen`): the tiles, the estimated maximum per
/// session (a line per hand for one-sided work) with the current performance value, volume per
/// session, bests per edge, grip and side, and the sessions with their sets. Push it inside a
/// `NavigationStack`; it has none of its own.
struct ProgressExerciseStatsView: View {
    let core: AppCore
    let slug: String

    @State private var host: ScreenHost<ExerciseStatsScreenModel, ExerciseStatsScreenState>?

    init(core: AppCore, slug: String) {
        self.core = core
        self.slug = slug
    }

    var body: some View {
        Group {
            if let host {
                ExerciseStatsContent(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(host?.state.name ?? L("tr_tab_stats"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeExerciseStatsScreen(language: TrainingStyle.catalogLanguage) else { return }
            model.load(slug: slug)
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct ExerciseStatsContent: View {
    let model: ExerciseStatsScreenModel
    let ui: ExerciseStatsScreenState

    var body: some View {
        if ui.loading || !ui.found {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(tr: ui.meaning).font(.caption).foregroundStyle(.secondary)
                    TrainingFlow {
                        ForEach(model.rangeCodes, id: \.self) { code in
                            TrainingChoiceChip(title: TrRender.text(model.rangeLabel(code: code)), selected: ui.rangeCode == code) {
                                model.setRange(code: code)
                            }
                        }
                    }
                    if !ui.hasSessions {
                        Text(L("trs_exercise_empty")).font(.subheadline).foregroundStyle(.secondary)
                    } else {
                        tiles
                        capacity
                        volume
                        bests
                        sessions
                    }
                    Spacer(minLength: 24)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
        }
    }

    private var tiles: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                ExerciseStatTile(label: L("trs_tile_best"), value: TrRender.text(ui.best))
                ExerciseStatTile(label: L("trs_tile_last"), value: TrRender.text(ui.last))
            }
            HStack(spacing: 8) {
                ExerciseStatTile(label: L("trs_tile_change"), value: TrRender.text(ui.change))
                ExerciseStatTile(label: L("trs_tile_sessions"), value: "\(ui.sessionCount)")
                ExerciseStatTile(label: L("trs_tile_percent_bw"), value: TrRender.text(ui.percentBodyweight))
            }
        }
    }

    @ViewBuilder
    private var capacity: some View {
        Text(tr: ui.capacityTitle).font(.headline).padding(.top, 8)
        if ui.series.isEmpty {
            Text(L("trs_no_value")).font(.caption)
        } else {
            Chart {
                ForEach(ui.series, id: \.sideCode) { series in
                    ForEach(series.points, id: \.isoDate) { point in
                        if let date = ProgressDates.date(point.isoDate) {
                            LineMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(TrRender.text(ui.capacityTitle), point.value))
                                .foregroundStyle(by: .value(L("tr_tab_exercises"), TrRender.text(series.label)))
                            PointMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(TrRender.text(ui.capacityTitle), point.value))
                                .foregroundStyle(by: .value(L("tr_tab_exercises"), TrRender.text(series.label)))
                                .symbolSize(30)
                        }
                    }
                }
                if ui.hasLevel {
                    RuleMark(y: .value(L("trbm_card_title"), ui.level))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                        .foregroundStyle(Color.secondary)
                }
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartLegend(ui.series.count > 1 ? .visible : .hidden)
            .frame(height: 190)
            .accessibilityLabel(TrRender.text(ui.capacityDescription))
        }
        if let current = TrRender.optional(ui.currentValue) {
            Text(current).font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var volume: some View {
        Text(L("trs_volume_chart")).font(.headline).padding(.top, 8)
        if !ui.volume.isEmpty {
            Chart {
                ForEach(ui.volume, id: \.isoDate) { point in
                    if let date = ProgressDates.date(point.isoDate) {
                        BarMark(x: .value(LI("stats_axis_date"), date, unit: .day), y: .value(L("trs_volume_chart"), point.value))
                            .foregroundStyle(ChartPalette.aqua)
                    }
                }
            }
            .frame(height: 140)
            .accessibilityLabel(TrRender.text(ui.volumeDescription))
        }
        Text(tr: ui.volumeHint).font(.caption).foregroundStyle(.secondary)
    }

    @ViewBuilder
    private var bests: some View {
        if !ui.bests.isEmpty {
            Text(L("trs_bests")).font(.headline).padding(.top, 8)
            ForEach(Array(ui.bests.enumerated()), id: \.offset) { _, best in
                HStack {
                    Text(tr: best.label)
                    Spacer()
                    Text(tr: best.value).font(.subheadline.weight(.semibold))
                }
            }
        }
    }

    @ViewBuilder
    private var sessions: some View {
        Text(L("trs_sessions")).font(.headline).padding(.top, 8)
        ForEach(ui.sessions, id: \.workoutId) { session in
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(TrRender.date(session.isoDate)).font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(tr: session.best).font(.subheadline.weight(.semibold))
                }
                Text(tr: session.setsText).font(.caption).foregroundStyle(.secondary)
                ForEach(Array(session.sets.enumerated()), id: \.offset) { index, line in
                    Text(tr: line)
                        .font(.caption)
                        .fontWeight(index == Int(session.bestIndex) ? .semibold : .regular)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

private struct ExerciseStatTile: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            Text(value).font(.headline).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
    }
}
