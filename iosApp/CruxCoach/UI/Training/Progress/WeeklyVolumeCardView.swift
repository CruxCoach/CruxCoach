import CruxCoachCore
import SwiftUI

/// Android's `WeeklyVolumeCard` on the Progress tab: one compact bar per area — logged work in
/// full colour, what climbing counted in a lighter shade, the weekly range as markers. A tap on
/// an area opens its own target (Android `OwnVolumeTargetDialog`). Numbers are the Kotlin model's.
struct WeeklyVolumeCardView: View {
    let core: AppCore
    @State private var host: ScreenHost<WeeklyVolumeCardModel, WeeklyVolumeCardState>?
    @State private var entered = false

    var body: some View {
        Group {
            if let host {
                WeeklyVolumeCardContent(model: host.model, ui: host.state)
            }
        }
        .task {
            guard host == nil, let model = core.makeWeeklyVolumeCard() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
        .onAppear {
            // Logged sets do not touch the observed tables; a return to the tab recomputes.
            if entered { host?.model.refresh() } else { entered = true }
        }
    }
}

/// Which area's own target is open.
private struct WeeklyVolumeEditTarget: Identifiable {
    let areaCode: String
    var id: String { areaCode }
}

private struct WeeklyVolumeCardContent: View {
    let model: WeeklyVolumeCardModel
    let ui: WeeklyVolumeCardState
    @State private var editing: WeeklyVolumeEditTarget?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            stateBody
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .sheet(item: $editing) { target in
            WeeklyVolumeOwnSheet(model: model, ui: ui, areaCode: target.areaCode) { editing = nil }
                .presentationDetents([.medium])
        }
    }

    private var header: some View {
        HStack(spacing: 4) {
            Text(L("trv_title")).font(.headline)
            ProgressInfoButton(title: L("trv_title"), text: L("trv_info_text"))
            Spacer()
        }
    }

    @ViewBuilder
    private var stateBody: some View {
        if ui.loading {
            ProgressView().frame(maxWidth: .infinity)
        } else if ui.paused {
            // During a pause nothing is "behind": the targets wait for the return.
            Text(L("trv_paused")).font(.subheadline)
        } else if ui.empty {
            Text(L("trv_empty")).font(.caption).foregroundStyle(.secondary)
        } else {
            rowsList
        }
    }

    @ViewBuilder
    private var rowsList: some View {
        let rows: [WeeklyVolumeRowUi] = ui.rows
        ForEach(rows, id: \.areaCode) { row in
            Button {
                editing = WeeklyVolumeEditTarget(areaCode: row.areaCode)
            } label: {
                WeeklyVolumeRowView(row: row)
            }
            .buttonStyle(.plain)
            .accessibilityHint(L("trv_edit_target"))
        }
        if ui.showLegend {
            Text(L("trv_legend")).font(.caption2).foregroundStyle(.secondary).padding(.top, 4)
        }
    }
}

/// One area: status icon, name, bar with markers, "4 / 6–10 sets" (Android `VolumeRow`).
private struct WeeklyVolumeRowView: View {
    let row: WeeklyVolumeRowUi

    private var label: String { TrRender.text(row.label) }
    private var value: String { TrRender.text(row.value) }
    private var status: String { TrRender.text(row.status) }

    private var icon: String {
        switch row.statusCode {
        case "done": return "checkmark.circle.fill"
        case "over": return "exclamationmark.triangle.fill"
        default: return "circle"
        }
    }

    private var tint: Color {
        switch row.statusCode {
        case "done": return TrainingStyle.positive
        case "over": return TrainingStyle.caution
        default: return Color.secondary
        }
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon).foregroundStyle(tint).font(.subheadline)
            Text(label).font(.subheadline).lineLimit(1).frame(width: 104, alignment: .leading)
            WeeklyVolumeBar(logged: row.loggedFraction, credit: row.creditFraction, markers: markers)
                .frame(height: 10)
            Text(value).font(.caption2).frame(minWidth: 64, alignment: .trailing)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value), \(status)")
    }

    private var markers: [Double] {
        let values: [KotlinDouble] = row.markerFractions
        return values.map { (v: KotlinDouble) -> Double in v.doubleValue }
    }
}

/// The bar: track, logged part, climbing credit right after it, range markers.
private struct WeeklyVolumeBar: View {
    let logged: Double
    let credit: Double
    let markers: [Double]

    var body: some View {
        GeometryReader { geo in
            let w: CGFloat = geo.size.width
            let h: CGFloat = geo.size.height
            ZStack(alignment: .leading) {
                Capsule().fill(Color(.tertiarySystemFill))
                Capsule().fill(Color.accentColor.opacity(0.4))
                    .frame(width: CGFloat(logged + credit) * w)
                Capsule().fill(Color.accentColor)
                    .frame(width: CGFloat(logged) * w)
                ForEach(markers.indices, id: \.self) { i in
                    Rectangle().fill(Color.secondary)
                        .frame(width: 2, height: h + 4)
                        .offset(x: max(0, CGFloat(markers[i]) * w - 1))
                }
            }
        }
    }
}

/// The own target of one area: "Recommended (6–10 sets)" or an own number (0 switches the area
/// off), saved as typed once it is inside Android's range.
private struct WeeklyVolumeOwnSheet: View {
    let model: WeeklyVolumeCardModel
    let ui: WeeklyVolumeCardState
    let areaCode: String
    let onClose: () -> Void

    private var row: WeeklyVolumeRowUi? {
        let rows: [WeeklyVolumeRowUi] = ui.rows
        return rows.first { $0.areaCode == areaCode }
    }

    var body: some View {
        NavigationStack {
            Form {
                if let row {
                    choice(row: row)
                }
            }
            .navigationTitle(row.map { TrRender.text($0.label) } ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("action_close"), action: onClose)
                }
            }
        }
    }

    @ViewBuilder
    private func choice(row: WeeklyVolumeRowUi) -> some View {
        Section(L("trn_target_heading")) {
            Picker(L("trn_target_heading"), selection: Binding(
                get: { row.isOwn },
                set: { own in
                    if own { model.chooseOwn(areaCode: areaCode) } else { model.followRecommendation(areaCode: areaCode) }
                })) {
                Text(TrRender.text(row.recommendedChip)).tag(false)
                Text(L("trn_target_own")).tag(true)
            }
            .pickerStyle(.segmented)
            if row.isOwn {
                NutritionAutoSaveField(label: TrRender.text(row.fieldLabel),
                                       unit: TrRender.text(row.unit),
                                       stored: row.ownText,
                                       numbersOnly: true,
                                       isValid: { model.isOwnValid(areaCode: areaCode, text: $0) },
                                       onSave: { _ = model.setOwn(areaCode: areaCode, text: $0) })
            }
        }
    }
}
