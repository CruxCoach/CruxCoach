import CruxCoachCore
import SwiftUI

/// The weekly review (Android `WeeklyReviewScreen`): previous and next week, the weekly goal,
/// climbing and training days, work sets, new bests, load per structure, sets by area, the weight
/// trend, nutrition against the week's targets, the energy guard, micronutrients and check-ins.
/// Push it inside a `NavigationStack`; it has none of its own.
struct WeeklyReviewView: View {
    let core: AppCore

    @State private var host: ScreenHost<WeeklyReviewScreenModel, WeeklyReviewScreenState>?

    init(core: AppCore) {
        self.core = core
    }

    var body: some View {
        Group {
            if let host {
                WeeklyReviewContent(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("trt_weekly_review"))
        .modifier(RestTimerBannerSlot(core: core))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil, let model = core.makeWeeklyReviewScreen(language: TrainingStyle.catalogLanguage) else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

private struct WeeklyReviewContent: View {
    let model: WeeklyReviewScreenModel
    let ui: WeeklyReviewScreenState

    var body: some View {
        if ui.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            List {
                weekSection
                tilesSection
                recordsSection
                WeeklyReviewLoadSection(ui: ui)
                WeeklyReviewBodySection(ui: ui)
                WeeklyReviewFoodSection(ui: ui)
                WeeklyReviewCheckinSection(ui: ui)
            }
        }
    }

    private var weekSection: some View {
        Section {
            HStack {
                Button { model.previous() } label: { Image(systemName: "chevron.left") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("tra_review_prev"))
                Spacer()
                Text(tr: ui.weekTitle).font(.headline)
                Spacer()
                Button { model.next() } label: { Image(systemName: "chevron.right") }
                    .buttonStyle(.borderless)
                    .disabled(!ui.canGoNext)
                    .accessibilityLabel(L("tra_review_next"))
            }
            Text(tr: ui.goalText)
            if ui.hasPausedDays {
                Text(tr: ui.pausedText).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private var tilesSection: some View {
        Section {
            HStack(alignment: .top, spacing: 8) {
                WeeklyReviewTile(label: L("tra_review_climb_days"), value: String(ui.climbDays),
                                 supporting: TrRender.text(ui.climbMinutes))
                WeeklyReviewTile(label: L("tra_review_workout_days"), value: String(ui.workoutDays),
                                 supporting: TrRender.text(ui.workoutMinutes))
                WeeklyReviewTile(label: L("tra_review_sets"), value: String(ui.sets), supporting: "")
            }
        }
    }

    private var recordsSection: some View {
        Section(L("tra_review_records")) {
            if ui.records.isEmpty {
                Text(L("tra_review_no_records")).font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(ui.records, id: \.slug) { record in
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.name)
                    Text(tr: record.text).font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct WeeklyReviewTile: View {
    let label: String
    let value: String
    let supporting: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            Text(value).font(.title2.weight(.semibold))
            if !supporting.isEmpty {
                Text(supporting).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Load per structure and sets by area.
private struct WeeklyReviewLoadSection: View {
    let ui: WeeklyReviewScreenState

    var body: some View {
        if !ui.domainDays.isEmpty {
            Section(L("tra_review_load")) {
                ForEach(Array(ui.domainDays.enumerated()), id: \.offset) { item in
                    Text(tr: item.element)
                }
            }
        }
        if !ui.categories.isEmpty {
            Section(L("tra_review_categories")) {
                ForEach(ui.categories, id: \.code) { line in
                    WeeklyReviewLine(line: line)
                }
            }
        }
    }
}

private struct WeeklyReviewLine: View {
    let line: WeeklyReviewLineUi

    var body: some View {
        HStack {
            Text(tr: line.label)
            Spacer()
            Text(tr: line.value).foregroundStyle(.secondary)
        }
    }
}

private struct WeeklyReviewBodySection: View {
    let ui: WeeklyReviewScreenState

    var body: some View {
        if ui.hasWeight {
            Section(L("tr_nav_body")) {
                Text(tr: ui.weightText)
            }
        }
        if ui.energyCard.visible {
            Section {
                NutritionEnergyCard(card: ui.energyCard, onAdjust: {})
            }
        }
    }
}

/// Protein, carbohydrate, fat and energy on the logged days against that week's targets.
private struct WeeklyReviewFoodSection: View {
    let ui: WeeklyReviewScreenState

    var body: some View {
        if ui.hasFood {
            Section(L("tr_nav_fuel")) {
                Text(tr: ui.foodDaysText).font(.footnote).foregroundStyle(.secondary)
                Text(tr: ui.proteinText)
                if ui.hasCarbs { Text(tr: ui.carbsText) }
                if ui.hasFat { Text(tr: ui.fatText) }
                if ui.hasEnergy { Text(tr: ui.energyText) }
            }
            if ui.micros.visible {
                Section(L("trf_micro_title")) {
                    WeeklyReviewMicros(micros: ui.micros)
                }
            }
        }
    }
}

private struct WeeklyReviewMicros: View {
    let micros: MicroWeekUi

    var body: some View {
        ForEach(micros.items, id: \.code) { item in
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(NutritionText.render(item.label)).font(.subheadline)
                    Spacer()
                    Text(NutritionText.render(item.value)).font(.caption)
                }
                ProgressView(value: item.progress).tint(.green)
            }
        }
        Text(NutritionText.render(micros.coverage)).font(.caption).foregroundStyle(.secondary)
    }
}

private struct WeeklyReviewCheckinSection: View {
    let ui: WeeklyReviewScreenState

    var body: some View {
        if !ui.checkins.isEmpty {
            Section(L("tra_review_checkins")) {
                ForEach(ui.checkins, id: \.code) { line in
                    WeeklyReviewLine(line: line)
                }
            }
        }
    }
}
