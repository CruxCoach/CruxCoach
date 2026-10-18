import CruxCoachCore
import SwiftUI

/// "What it trains" on the exercise page (Android `ExerciseDetailScreen.VisualHeader`): the body
/// map front and back (tap an area for its name), the legend and, for finger work, the grip on
/// the edge. A `Section` for the page's `List`.
/// The page creates the model in its own `.task` (`makeHost`) and hands the host in: a view that
/// may show nothing cannot run a `.task` of its own.
struct ExerciseBodyMapSection: View {
    let host: ScreenHost<ExerciseVisualsModel, ExerciseVisualsState>?

    @State private var tapped = ""

    var body: some View {
        if let host, host.state.found {
            Section(L("trb_detail_title")) {
                BodyMapCard(model: host.model, ui: host.state, tapped: $tapped)
                if let grip = host.state.grip {
                    GripRow(grip: grip)
                }
            }
        }
    }

    /// The exercise page's visuals for [slug]; nil while the athlete database is unavailable.
    static func makeHost(core: AppCore, slug: String) -> ScreenHost<ExerciseVisualsModel, ExerciseVisualsState>? {
        guard let model = core.makeExerciseVisuals(slug: slug) else { return nil }
        return ScreenHost(model: model, initial: model.currentState,
                          subscribe: { model, onState in model.watch(onState: onState) })
    }
}

/// The map with its legend and the line about the tapped area.
private struct BodyMapCard: View {
    let model: ExerciseVisualsModel
    let ui: ExerciseVisualsState
    @Binding var tapped: String

    var body: some View {
        let tints: [String: Double] = BodyMapCard.tints(ui.areas)
        let line: String = TrRender.text(model.tapText(areaCode: tapped))
        VStack(alignment: .leading, spacing: 8) {
            BodyMapCanvas(kit: model.bodyMap, mix: tints, sideCode: "both", showLabels: true,
                          onTap: { hit in tapped = hit.areaCode })
            .frame(height: 220)
            .accessibilityElement()
            .accessibilityLabel(TrRender.text(ui.mapText))
            .accessibilityIdentifier("exercise_detail_bodymap")
            legend
            Text(line)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("exercise_detail_bodymap_tapped")
        }
        .padding(.vertical, 4)
    }

    private var legend: some View {
        HStack(spacing: 6) {
            BodyMapColors.swatch(mix: model.bodyMap.accentMix(value: 1.0))
            Text(L("trb_legend_main")).font(.caption)
            Spacer().frame(width: 10)
            BodyMapColors.swatch(mix: model.bodyMap.accentMix(value: 0.5))
            Text(L("trb_legend_involved")).font(.caption)
        }
    }

    static func tints(_ areas: [BodyAreaValueUi]) -> [String: Double] {
        var out: [String: Double] = [:]
        for area in areas { out[area.areaCode] = area.mix }
        return out
    }
}

/// The grip picture beside "Grip", its name and the edge depth.
private struct GripRow: View {
    let grip: GripPictureUi

    var body: some View {
        HStack(spacing: 12) {
            GripPictureView(grip: grip)
                .frame(width: 110, height: 76)
                .accessibilityIdentifier("exercise_detail_grip")
            VStack(alignment: .leading, spacing: 2) {
                Text(L("trb_grip_title")).font(.caption).foregroundStyle(.secondary)
                Text(L(grip.labelKey)).font(.subheadline.weight(.semibold))
                if grip.edgeMm >= 0 {
                    Text(L("trb_edge_mm", Int(grip.edgeMm))).font(.footnote)
                }
            }
        }
    }
}
