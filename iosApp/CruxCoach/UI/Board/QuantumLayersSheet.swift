import CruxCoachCore
import SwiftUI

/// Android's Quantum layer rack and lamp for one climb (BoardClimbDetailScreen's
/// rack): the wall's occupancy, the recommended layer, this phone's four numbered
/// layers and other apps' routes, which stay read-only. Never run on hardware.
struct QuantumLayersSheet: View {
    let core: AppCore
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    let climbUuid: String
    let angle: Int32

    @State private var host: ScreenHost<QuantumLayersScreenModel, QuantumLayersScreenState>?
    @State private var showBle = false
    @State private var removeSlot: Int32 = -1
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let host {
                content(model: host.model, ui: host.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("quantum_wall_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } } }
        .task {
            guard host == nil else { return }
            let model = core.makeQuantumLayersScreen()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
            model.setClimb(uuid: climbUuid, angle: angle)
        }
        .onDisappear { host?.close() }
        .sheet(isPresented: $showBle) { NavigationStack { BleView(ble: ble) } }
    }

    private func content(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        List {
            wallPictureSection(ui: ui)
            wallSection(ui: ui)
            lampSection(model: model, ui: ui)
            layersSection(model: model, ui: ui)
            pickerSection(model: model, ui: ui)
        }
        .confirmationDialog(L("quantum_layer_remove_live_title"),
                            isPresented: Binding(get: { removeSlot >= 0 }, set: { if !$0 { removeSlot = -1 } }),
                            titleVisibility: .visible) {
            Button(L("quantum_layer_remove_live_confirm"), role: .destructive) {
                model.removeLayer(slot: removeSlot)
                removeSlot = -1
            }
        } message: {
            Text(L("quantum_layer_remove_live_body", Int(removeSlot) + 1))
        }
    }

    // MARK: Wall

    @ViewBuilder
    private func wallPictureSection(ui: QuantumLayersScreenState) -> some View {
        if ui.hasClimb {
            Section {
                QuantumWallCanvas(imagePaths: ui.boardImagePaths, climbHolds: ui.climbHolds, layerHolds: ui.layerHolds)
                    .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
            }
        }
    }

    private func wallSection(ui: QuantumLayersScreenState) -> some View {
        Section {
            Text(wallText(ui: ui)).font(.subheadline)
            if ui.foreignCount > 0 {
                Text(foreignText(ui: ui)).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func wallText(ui: QuantumLayersScreenState) -> String {
        let active: Int = Int(ui.activeCount)
        let capacity: Int = Int(ui.capacity)
        switch ui.wallCode {
        case "live": return L("quantum_wall_occupancy", active, capacity)
        case "stale": return L("quantum_wall_occupancy_stale", active, capacity)
        case "syncing": return L("quantum_wall_syncing")
        default: return L("quantum_wall_unsynced")
        }
    }

    private func foreignText(ui: QuantumLayersScreenState) -> String {
        let foreign: Int = Int(ui.foreignCount)
        let unknown: Int = Int(ui.foreignUnknownCount)
        if unknown > 0 { return L("quantum_wall_foreign_unknown_short", foreign, unknown) }
        return L("quantum_wall_foreign_known_short", foreign)
    }

    // MARK: Lamp

    @ViewBuilder
    private func lampSection(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        Section {
            if !ui.managementAllowed {
                Text(L("board_layer_group_owns_board")).font(.footnote).foregroundStyle(.secondary)
            } else if !ui.connected {
                Button { showBle = true } label: { Label(L("board_ble_title"), systemImage: "antenna.radiowaves.left.and.right") }
            } else {
                suggestionRows(model: model, ui: ui)
                lampButton(model: model, ui: ui)
            }
            statusRow(model: model, ui: ui)
        }
    }

    @ViewBuilder
    private func suggestionRows(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        if ui.suggestedSlot >= 0 {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("quantum_layer_suggestion", Int(ui.suggestedSlot) + 1, colorName(ui.suggestedArgb)))
                    .font(.subheadline.bold())
                Text(L(ui.suggestionUsesExistingSlot ? "quantum_layer_suggestion_existing_reason" : "quantum_layer_suggestion_reason"))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if !ui.suggestionSelected {
                Button(L("quantum_layer_select_suggestion")) { model.selectSuggestion() }
                    .disabled(ui.sending)
                    .accessibilityIdentifier("quantum_layer_select_suggestion")
            }
        } else if !ui.suggestionBlockKey.isEmpty && ui.hasClimb {
            Text(L(ui.suggestionBlockKey)).font(.footnote).foregroundStyle(.orange)
        }
        if ui.sharedHoldCount > 0 {
            Text(L("board_layer_overlap_warning", Int(ui.sharedHoldCount))).font(.footnote).foregroundStyle(.orange)
        }
    }

    private func lampButton(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        Button { model.light() } label: {
            Label(LI("detail_light_up"), systemImage: "lightbulb.max").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .disabled(ui.sending || !ui.hasClimb || ui.suggestedSlot < 0)
        .accessibilityIdentifier("quantum_layer_light")
    }

    @ViewBuilder
    private func statusRow(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        if ui.sending {
            HStack { ProgressView(); Text(L("board_layer_sending")) }
        } else if !ui.errorKey.isEmpty {
            Text(L(ui.errorKey)).font(.footnote).foregroundStyle(.red)
                .onTapGesture { model.dismissResult() }
        } else if ui.success {
            Label(L("board_layer_confirmed"), systemImage: "checkmark.circle").foregroundStyle(.green)
        }
    }

    // MARK: Layers

    private func layersSection(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        let rows: [QuantumLayerRowUi] = ui.rows
        return Section {
            ForEach(rows, id: \.slot) { row in
                layerRow(model: model, ui: ui, row: row)
            }
            if ui.hasPlans && ui.managementAllowed && ui.connected {
                Button(L("board_layer_send_all")) { model.sendAll() }
                    .disabled(ui.sending || !ui.sendAllBlockKey.isEmpty)
                if !ui.sendAllBlockKey.isEmpty {
                    Text(L(ui.sendAllBlockKey)).font(.footnote).foregroundStyle(.secondary)
                }
            }
        } header: {
            Text(L("quantum_wall_title"))
        }
    }

    private func layerRow(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState, row: QuantumLayerRowUi) -> some View {
        let selected: Bool = row.slot == ui.selectedSlot
        let background: Color = selected ? Color.orange.opacity(0.16) : Color(.secondarySystemGroupedBackground)
        return HStack(spacing: 12) {
            selectableRowLabel(model: model, ui: ui, row: row, selected: selected)
            Spacer()
            rowActions(model: model, ui: ui, row: row)
        }
        .accessibilityElement(children: .contain)
        .listRowBackground(background)
    }

    /// Android's rack rows are radio buttons choosing the layer the picker acts on;
    /// another app's route is read-only, so its row cannot be chosen.
    @ViewBuilder
    private func selectableRowLabel(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState,
                                    row: QuantumLayerRowUi, selected: Bool) -> some View {
        if ui.managementAllowed && ui.hasClimb && row.stateCode != "foreign" {
            Button { model.selectSlot(slot: row.slot) } label: {
                rowLabel(row: row).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier("board_layer_slot_\(Int(row.number))")
        } else {
            rowLabel(row: row)
        }
    }

    private func rowLabel(row: QuantumLayerRowUi) -> some View {
        let number: Int = Int(row.number)
        return HStack(spacing: 12) {
            Circle()
                .fill(row.plannedArgb == 0 ? Color.secondary.opacity(0.25) : Color(argb: row.plannedArgb))
                .frame(width: 22, height: 22)
                .overlay { Text("\(number)").font(.caption2.bold()).foregroundStyle(.black) }
            VStack(alignment: .leading, spacing: 2) {
                Text(row.climbName.isEmpty ? L("board_layer_number", number) : row.climbName)
                    .fontWeight(row.isCurrentClimb ? .bold : .regular)
                Text(L(statusKey(row.stateCode))).font(.caption).foregroundStyle(.secondary)
                if !row.confirmedClimbName.isEmpty {
                    Text(L("quantum_layer_confirmed_previous", row.confirmedClimbName)).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: Colour picker (Android BoardLayerColorPicker + assign button)

    @ViewBuilder
    private func pickerSection(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        if ui.managementAllowed && ui.hasClimb {
            Section {
                if ui.selectedSlot < 0 {
                    Text(L("board_layer_select_hint")).font(.footnote).foregroundStyle(.secondary)
                } else {
                    colorPicker(model: model, ui: ui)
                    assignButton(model: model, ui: ui)
                    pickerWarnings(ui: ui)
                }
            } header: {
                Text(L("board_layer_color"))
            }
        }
    }

    private func colorPicker(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        let choices: [QuantumLayerColorUi] = ui.colorChoices
        return HStack(spacing: 10) {
            ForEach(Array(choices.enumerated()), id: \.offset) { index, choice in
                swatch(model: model, ui: ui, choice: choice, index: index)
            }
        }
        .padding(.vertical, 4)
    }

    private func swatch(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState,
                        choice: QuantumLayerColorUi, index: Int) -> some View {
        let name: String = colorName(choice.argb)
        let label: String = choice.isAvailable ? name : L("board_layer_color_unavailable", name)
        let opacity: Double = choice.isAvailable ? 1 : 0.22
        return Button { model.selectColor(argb: choice.argb) } label: {
            Circle()
                .fill(Color(argb: choice.argb).opacity(opacity))
                .frame(width: 44, height: 44)
                .overlay(Circle().stroke(Color.primary, lineWidth: choice.isChosen ? 3 : 0))
        }
        .buttonStyle(.plain)
        .disabled(!choice.isAvailable || ui.sending)
        .accessibilityLabel(label)
        .accessibilityAddTraits(choice.isChosen ? .isSelected : [])
        .accessibilityIdentifier("board_layer_color_\(index)")
    }

    private func assignButton(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState) -> some View {
        let number: Int = Int(ui.selectedSlot) + 1
        return Button { model.assignCurrent() } label: {
            Label(L(ui.assignKey, number), systemImage: "checkmark").frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(!ui.canAssign)
        .accessibilityIdentifier("board_layer_assign_current")
    }

    @ViewBuilder
    private func pickerWarnings(ui: QuantumLayersScreenState) -> some View {
        if ui.alreadyAssignedNumber > 0 {
            Text(L("quantum_layer_already_assigned", Int(ui.alreadyAssignedNumber)))
                .font(.footnote).foregroundStyle(.yellow)
        }
        if ui.selectedSharedHoldCount > 0 {
            Text(L("board_layer_preview_overlap_warning", Int(ui.selectedSharedHoldCount)))
                .font(.footnote).foregroundStyle(.yellow)
        }
        if ui.selectedColorTaken {
            Text(L("board_layer_error_color_taken")).font(.footnote).foregroundStyle(.red)
        }
    }

    @ViewBuilder
    private func rowActions(model: QuantumLayersScreenModel, ui: QuantumLayersScreenState, row: QuantumLayerRowUi) -> some View {
        if ui.managementAllowed {
            let number: Int = Int(row.number)
            Menu {
                if row.canSend && ui.connected {
                    Button(L("board_layer_send_one", number), systemImage: "lightbulb") { model.sendLayer(slot: row.slot) }
                }
                if row.canCancelReplacement {
                    Button(L("quantum_layer_cancel_replacement_one", number)) { model.cancelReplacement(slot: row.slot) }
                }
                if row.canRemove {
                    removeButton(model: model, row: row, number: number)
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .disabled(ui.sending || !(row.canSend || row.canRemove || row.canCancelReplacement))
        }
    }

    private func removeButton(model: QuantumLayersScreenModel, row: QuantumLayerRowUi, number: Int) -> some View {
        Button(row.removeIsLive ? L("quantum_layer_remove_live_one", number) : L("quantum_layer_remove_plan_one", number),
               systemImage: "trash", role: .destructive) {
            if row.removeIsLive { removeSlot = row.slot } else { model.removeLayer(slot: row.slot) }
        }
    }

    private func statusKey(_ code: String) -> String {
        switch code {
        case "planned": return "quantum_layer_status_planned"
        case "sending": return "quantum_layer_status_sending"
        case "onBoard": return "quantum_layer_status_on_board"
        case "replacing": return "quantum_layer_status_replacing"
        case "failed": return "quantum_layer_status_failed"
        case "unknown": return "quantum_layer_status_unknown"
        case "foreign": return "quantum_layer_status_foreign"
        default: return "quantum_layer_status_free"
        }
    }

    /// Android `boardLayerColorName`: the four controller colours have names.
    private func colorName(_ argb: Int64) -> String {
        switch argb {
        case 0xFF00FF00: return L("board_layer_color_green")
        case 0xFF00FFFF: return L("board_layer_color_cyan")
        case 0xFFFF00FF: return L("board_layer_color_magenta")
        case 0xFFFFFF00: return L("board_layer_color_yellow")
        default: return L("board_layer_custom_color")
        }
    }
}

/// The square model image with the climb's role rings and Android's layer overlay: a
/// diode shared by several layers is a ring split into one segment per layer colour.
/// Sizes follow Android's 20 dp diode with a 4 dp ring.
struct QuantumWallCanvas: View {
    let imagePaths: [String]
    let climbHolds: [QuantumWallHoldUi]
    let layerHolds: [QuantumWallHoldUi]

    private var image: UIImage? {
        for path in imagePaths {
            if let url = Bundle.main.resourceURL?.appendingPathComponent(path),
               let image = UIImage(contentsOfFile: url.path) {
                return image
            }
        }
        return nil
    }

    var body: some View {
        let loaded: UIImage? = image
        let climb: [QuantumWallHoldUi] = climbHolds
        let layers: [QuantumWallHoldUi] = layerHolds
        return Canvas { context, size in
            if let loaded {
                context.draw(Image(uiImage: loaded), in: CGRect(origin: .zero, size: size))
            } else {
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(.secondarySystemBackground)))
            }
            for hold in climb {
                QuantumWallCanvas.ring(context: context, size: size, hold: hold)
            }
            for hold in layers {
                QuantumWallCanvas.segments(context: context, size: size, hold: hold)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel(LI("detail_board_a11y", climb.count))
    }

    private static let radius: CGFloat = 10
    private static let stroke: CGFloat = 4

    private static func center(_ hold: QuantumWallHoldUi, _ size: CGSize) -> CGPoint {
        CGPoint(x: CGFloat(hold.x) * size.width, y: CGFloat(hold.y) * size.height)
    }

    private static func ring(context: GraphicsContext, size: CGSize, hold: QuantumWallHoldUi) {
        let c: CGPoint = center(hold, size)
        let rect = CGRect(x: c.x - radius, y: c.y - radius, width: radius * 2, height: radius * 2)
        let argb: Int64 = hold.argbs.first?.int64Value ?? 0xFFFFFFFF
        context.stroke(Path(ellipseIn: rect), with: .color(.black.opacity(0.7)), lineWidth: stroke + 2)
        context.stroke(Path(ellipseIn: rect), with: .color(Color(argb: argb)), lineWidth: stroke)
    }

    private static func segments(context: GraphicsContext, size: CGSize, hold: QuantumWallHoldUi) {
        let c: CGPoint = center(hold, size)
        let colors: [Int64] = hold.argbs.map { $0.int64Value }
        guard !colors.isEmpty else { return }
        let sweep: Double = 360.0 / Double(colors.count)
        let gap: Double = colors.count > 1 ? 3 : 0
        for (index, argb) in colors.enumerated() {
            var path = Path()
            let start: Double = -90 + Double(index) * sweep
            path.addArc(center: c, radius: radius, startAngle: .degrees(start),
                        endAngle: .degrees(start + sweep - gap), clockwise: false)
            context.stroke(path, with: .color(Color(argb: argb)), lineWidth: stroke)
        }
    }
}

/// The rack as a sheet over the climb detail; a modifier keeps the detail's chain short.
struct QuantumLayersSheetModifier: ViewModifier {
    let core: AppCore
    let ble: ScreenHost<BleScreenModel, BleScreenState>
    let climbUuid: String
    let angle: Int32
    @Binding var isPresented: Bool

    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPresented) {
            NavigationStack {
                QuantumLayersSheet(core: core, ble: ble, climbUuid: climbUuid, angle: angle)
            }
        }
    }
}
