import CruxCoachCore
import SwiftUI

/// Training-list generator: plans a session from the logbook and fills it
/// from the catalogue. The plan preview is recomputed by the Kotlin presenter
/// on every change, so this view only mirrors it.
struct GeneratorView: View {
    let core: AppCore
    /// Called with the new list id once a session was written.
    var onCreated: (Int64) -> Void = { _ in }

    @State private var host: ScreenHost<GeneratorScreenModel, GeneratorScreenState>?
    @State private var naming = false
    @State private var name = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let host {
                screen(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("playlist_generator_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard host == nil else { return }
            let model = core.makeGeneratorScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }

    @ViewBuilder
    private func screen(model: GeneratorScreenModel, ui: GeneratorScreenState) -> some View {
        Form {
            profileSection(ui)
            typeSection(model: model, ui: ui)
            shapeSection(model: model, ui: ui)
            if ui.typeCode == "manual" {
                manualSection(model: model, ui: ui)
            } else {
                gradeSection(model: model, ui: ui)
            }
            previewSection(ui)
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                name = defaultName(ui.typeCode)
                naming = true
            } label: {
                if ui.isGenerating {
                    ProgressView().frame(maxWidth: .infinity)
                } else {
                    Text(L("playlist_generator_generate")).frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(ui.isGenerating || ui.climbCount == 0)
            .padding()
            .background(.bar)
        }
        .alert(L("playlist_generator_name_title"), isPresented: $naming) {
            TextField(L("playlist_generator_name_title"), text: $name)
            Button(L("action_cancel"), role: .cancel) {}
            Button(L("playlist_generator_generate")) {
                model.generate(name: name.isEmpty ? defaultName(ui.typeCode) : name)
            }
        }
        .alert(L("playlist_generator_dropped_title"),
               isPresented: Binding(get: { ui.createdListId != 0 }, set: { if !$0 { model.consumeCreatedList() } })) {
            Button(L("playlist_generator_dropped_confirm")) {
                let created = ui.createdListId
                model.consumeCreatedList()
                onCreated(created)
                dismiss()
            }
        } message: {
            Text(ui.droppedClimbs == 1
                 ? L("playlist_generator_dropped_body.one")
                 : L("playlist_generator_dropped_body.other", Int(ui.droppedClimbs)))
        }
        .alert(L(ui.typeCode == "manual" ? "playlist_generator_error_manual" : "playlist_generator_error"),
               isPresented: Binding(get: { ui.failed }, set: { _ in })) {
            Button(L("action_done")) { model.setTypeCode(code: ui.typeCode) }
        }
        .onChange(of: ui.createdListId) { _, new in
            // Nothing was dropped: there is no report to acknowledge.
            if new != 0 && ui.droppedClimbs == 0 {
                model.consumeCreatedList()
                onCreated(new)
                dismiss()
            }
        }
    }

    // MARK: sections

    @ViewBuilder
    private func profileSection(_ ui: GeneratorScreenState) -> some View {
        Section {
            if ui.profilePersonalized && !ui.maxGradeLabel.isEmpty {
                Text(L("playlist_generator_profile", ui.maxGradeLabel,
                       ui.flashGradeLabel.isEmpty ? ui.maxGradeLabel : ui.flashGradeLabel))
            } else {
                Text(L("playlist_generator_profile_empty")).font(.footnote).foregroundStyle(.secondary)
            }
            if !ui.downgradedFromTypeCode.isEmpty {
                Label(L("playlist_generator_downgraded"), systemImage: "exclamationmark.triangle")
                    .font(.footnote)
            }
        }
    }

    @ViewBuilder
    private func typeSection(model: GeneratorScreenModel, ui: GeneratorScreenState) -> some View {
        Section {
            Picker(L("playlist_generator_title"), selection: Binding(
                get: { ui.typeCode }, set: { model.setTypeCode(code: $0) })) {
                ForEach(Self.types, id: \.self) { code in
                    Text(L(Self.typeName[code] ?? code)).tag(code)
                }
            }
            .pickerStyle(.menu)
            Text(L(Self.typeDesc[ui.typeCode] ?? "")).font(.footnote).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func shapeSection(model: GeneratorScreenModel, ui: GeneratorScreenState) -> some View {
        Section {
            Stepper(value: Binding(get: { Int(ui.structureSize) },
                                   set: { model.setStructureSize(size: Int32($0)) }),
                    in: Int(ui.structureMin)...Int(ui.structureMax)) {
                Text(L(Self.sizeLabel[ui.structureUnitCode] ?? "playlist_generator_size_volume",
                       Int(ui.structureSize)))
            }
            if ui.typeCode == "pyramid" {
                Picker(L("playlist_generator_pyramid_shape"), selection: Binding(
                    get: { ui.pyramidShapeCode }, set: { model.setPyramidShapeCode(code: $0) })) {
                    Text(L("playlist_pyramid_ascending")).tag("ascending")
                    Text(L("playlist_pyramid_up_and_down")).tag("upAndDown")
                }
                Stepper(value: Binding(get: { Int(ui.pyramidClimbsPerTier) },
                                       set: { model.setPyramidClimbsPerTier(count: Int32($0)) }),
                        in: 1...6) {
                    Text(L("playlist_generator_pyramid_per_tier", Int(ui.pyramidClimbsPerTier)))
                }
            }
            if ui.typeCode == "powerEndurance" {
                Stepper(value: Binding(get: { Int(ui.problemsPerSet) },
                                       set: { model.setProblemsPerSet(count: Int32($0)) }),
                        in: 2...8) {
                    Text(L("playlist_generator_problems_per_set", Int(ui.problemsPerSet)))
                }
            }
            Picker(L("playlist_generator_position"), selection: Binding(
                get: { ui.positionCode }, set: { model.setPositionCode(code: $0) })) {
                Text(L("playlist_position_cold")).tag("startCold")
                Text(L("playlist_position_warm")).tag("warmedUp")
                Text(L("playlist_position_end")).tag("endOfSession")
            }
            Picker(L("playlist_generator_selection"), selection: Binding(
                get: { ui.selectionCode }, set: { model.setSelectionCode(code: $0) })) {
                Text(L("playlist_selection_new")).tag("new")
                Text(L("playlist_selection_projects")).tag("projects")
                Text(L("playlist_selection_all")).tag("all")
            }
            if ui.angleAdjustable {
                Stepper(value: Binding(get: { Int(ui.angle) },
                                       set: { model.setAngle(angle: Int32($0)) }),
                        in: 0...70, step: 5) {
                    Text(L("playlist_generator_angle", Int(ui.angle)))
                }
            }
        }
    }

    @ViewBuilder
    private func gradeSection(model: GeneratorScreenModel, ui: GeneratorScreenState) -> some View {
        Section {
            Text(L("playlist_generator_grade_range",
                   label(ui.targetMinDifficulty, ui),
                   label(ui.targetMaxDifficulty, ui)))
            gradeSliders(low: ui.targetMinDifficulty, high: ui.targetMaxDifficulty, ui: ui) { low, high in
                model.setTargetRange(low: low, high: high)
            }
            if ui.gradeRangeCustomized {
                Button(L("playlist_generator_recommended")) { model.useRecommendedRange() }
            }
            Text(L("playlist_generator_grade_range_hint")).font(.footnote).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func manualSection(model: GeneratorScreenModel, ui: GeneratorScreenState) -> some View {
        Section {
            Text(L("playlist_manual_grade_range",
                   label(ui.manualMinDifficulty, ui), label(ui.manualMaxDifficulty, ui)))
            gradeSliders(low: ui.manualMinDifficulty, high: ui.manualMaxDifficulty, ui: ui) { low, high in
                model.setManualRange(low: low, high: high)
            }
            Stepper(value: Binding(get: { Int(ui.manualRepeats) },
                                   set: { model.setManualRepeats(repeats: Int32($0)) }), in: 1...10) {
                Text(LI("generator_manual_repeats", Int(ui.manualRepeats)))
            }
            Stepper(value: Binding(get: { Int(ui.manualRestSeconds) },
                                   set: { model.setManualRest(seconds: Int32($0)) }),
                    in: 0...600, step: 30) {
                Text(LI("generator_manual_rest", Int(ui.manualRestSeconds)))
            }
            Stepper(value: Binding(get: { Int(ui.manualRepeatRestSeconds) },
                                   set: { model.setManualRepeatRest(seconds: Int32($0)) }),
                    in: 0...600, step: 30) {
                Text(LI("generator_manual_repeat_rest", Int(ui.manualRepeatRestSeconds)))
            }
        }
    }

    @ViewBuilder
    private func previewSection(_ ui: GeneratorScreenState) -> some View {
        Section(header: Text(L("playlist_generator_preview"))) {
            Text(L("playlist_generator_summary",
                   Int(ui.climbCount),
                   ui.plan.filter { $0.isRest }.count,
                   Int(ui.estimatedMinutes),
                   label(ui.targetMinDifficulty, ui),
                   label(ui.targetMaxDifficulty, ui)))
                .font(.footnote).foregroundStyle(.secondary)
            ForEach(Array(ui.plan.enumerated()), id: \.offset) { index, row in
                HStack {
                    Image(systemName: row.isRest ? "pause.circle" : icon(for: row.sectionCode))
                        .foregroundStyle(row.isRest ? Color.secondary : .accentColor)
                    if row.isRest {
                        Text(LI("generator_rest_step", Int(row.restSeconds) / 60,
                                Int(row.restSeconds) % 60))
                    } else {
                        Text(row.gradeLabel)
                        if row.repeatKey != 0 {
                            Text(LI("generator_same_climb")).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    Text(LI(Self.sectionName[row.sectionCode] ?? "generator_section_main")).font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: helpers

    /// Two sliders rather than a range control: SwiftUI has no two-thumb
    /// slider, and the pair keeps low ≤ high the way the presenter clamps it.
    @ViewBuilder
    private func gradeSliders(low: Double, high: Double, ui: GeneratorScreenState,
                              apply: @escaping (Double, Double) -> Void) -> some View {
        Slider(value: Binding(get: { low }, set: { apply($0, max($0, high)) }),
               in: ui.minDifficulty...ui.maxDifficulty, step: 1)
        Slider(value: Binding(get: { high }, set: { apply(min(low, $0), $0) }),
               in: ui.minDifficulty...ui.maxDifficulty, step: 1)
    }

    private func label(_ difficulty: Double, _ ui: GeneratorScreenState) -> String {
        let index = Int(difficulty.rounded()) - Int(ui.minDifficulty)
        guard index >= 0, index < ui.gradeLabels.count else { return "" }
        return ui.gradeLabels[index]
    }

    private func icon(for section: String) -> String {
        switch section {
        case "warmUp": return "flame"
        case "peak": return "arrow.up.circle"
        case "descent": return "arrow.down.circle"
        default: return "figure.climbing"
        }
    }

    private func defaultName(_ typeCode: String) -> String {
        L(Self.defaultName[typeCode] ?? "playlist_default_name_volume")
    }

    private static let types = ["pyramid", "powerEndurance", "volume", "limit", "projecting", "manual"]
    private static let typeName = [
        "pyramid": "playlist_type_pyramid", "powerEndurance": "playlist_type_power_endurance",
        "volume": "playlist_type_volume", "limit": "playlist_type_limit",
        "projecting": "playlist_type_projecting", "manual": "playlist_type_manual",
    ]
    private static let typeDesc = [
        "pyramid": "playlist_type_pyramid_desc", "powerEndurance": "playlist_type_power_endurance_desc",
        "volume": "playlist_type_volume_desc", "limit": "playlist_type_limit_desc",
        "projecting": "playlist_type_projecting_desc", "manual": "playlist_type_manual_desc",
    ]
    private static let sizeLabel = [
        "tiers": "playlist_generator_size_tiers", "sets": "playlist_generator_size_sets",
        "projects": "playlist_generator_size_projects", "problems": "playlist_generator_size_volume",
    ]
    private static let sectionName = [
        "warmUp": "generator_section_warmup", "main": "generator_section_main",
        "peak": "generator_section_peak", "descent": "generator_section_descent",
    ]
    private static let defaultName = [
        "pyramid": "playlist_default_name_pyramid",
        "powerEndurance": "playlist_default_name_power_endurance",
        "volume": "playlist_default_name_volume", "limit": "playlist_default_name_limit",
        "projecting": "playlist_default_name_projecting", "manual": "playlist_default_name_manual",
    ]
}
