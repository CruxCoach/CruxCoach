import CruxCoachCore
import SwiftUI

/// Hold search: tap the holds a problem must use, or (Android's "Select area")
/// drag a box the problem must fit into. As on Android the selection is pending:
/// the sheet counts the climbs it matches on its own, and "Filter" narrows the
/// browser to climbs that use every selected hold and lie fully inside the area.
struct HoldSearchView: View {
    let model: BrowserScreenModel
    let ui: BrowserScreenState
    @Environment(\.dismiss) private var dismiss
    @State private var search: ScreenHost<HoldSearchScreenModel, HoldSearchScreenState>?
    /// Android's "Select area" chip: while on, a drag frames the area instead of tapping holds.
    @State private var areaMode = false
    /// The live drag, normalized over the board image, while framing an area.
    @State private var dragStart: CGPoint?
    @State private var dragEnd: CGPoint?

    private var image: UIImage? {
        for path in ui.boardImagePaths {
            if let url = Bundle.main.resourceURL?.appendingPathComponent(path),
               let image = UIImage(contentsOfFile: url.path) {
                return image
            }
        }
        return nil
    }

    var body: some View {
        Group {
            if let search {
                content(sheet: search.model, hs: search.state)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(L("board_holdsearch_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("action_close")) { dismiss() } } }
        .task {
            guard search == nil else { return }
            let sheet = model.makeHoldSearch()
            search = ScreenHost(model: sheet, initial: sheet.currentState,
                                subscribe: { model, onState in model.watch(onState: onState) },
                                onClose: { model in model.close() })
        }
        .onDisappear { search?.close() }
    }

    private func content(sheet: HoldSearchScreenModel, hs: HoldSearchScreenState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(areaMode ? L("board_holdsearch_zone_hint") : L("board_holdsearch_hint"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                areaControls(sheet: sheet, hs: hs)
                summary(sheet: sheet, hs: hs)
                canvas(sheet: sheet, hs: hs)
            }
            .padding()
        }
        // A drag on the board frames the area; it must not scroll the sheet.
        .scrollDisabled(areaMode)
    }

    private func areaControls(sheet: HoldSearchScreenModel, hs: HoldSearchScreenState) -> some View {
        HStack(spacing: 8) {
            Toggle(isOn: $areaMode) {
                Label(L("board_holdsearch_zone_mode"), systemImage: "square.grid.3x3")
            }
            .toggleStyle(.button)
            .accessibilityIdentifier("hold_search_area_mode")
            if hs.zoneActive {
                Button {
                    if sheet.clearZone() { dismiss() }
                } label: {
                    Label(L("board_holdsearch_zone_active"), systemImage: "xmark.circle.fill")
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(L("board_holdsearch_zone_clear"))
            }
            Spacer()
        }
    }

    /// Android: the selection with "Reset", then the count of climbs it matches and "Filter".
    @ViewBuilder
    private func summary(sheet: HoldSearchScreenModel, hs: HoldSearchScreenState) -> some View {
        let holdCount: Int = hs.selectedHolds.count
        if hs.hasSelection {
            HStack {
                Text(holdCount == 0 ? L("board_holdsearch_zone_only") : L("board_holdsearch_holds_selected", holdCount))
                    .font(.subheadline.bold())
                Spacer()
                Button(L("board_holdsearch_reset"), systemImage: "xmark") { sheet.reset() }
                    .buttonStyle(.borderless)
            }
            HStack {
                matchText(hs: hs)
                Spacer()
                Button {
                    if sheet.apply() { dismiss() }
                } label: {
                    Label(L("board_holdsearch_filter"), systemImage: "magnifyingglass").fontWeight(.bold)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.orange)
                .disabled(!hs.canApply)
                .accessibilityIdentifier("hold_search_filter")
            }
        }
    }

    @ViewBuilder
    private func matchText(hs: HoldSearchScreenState) -> some View {
        let count: Int = Int(hs.matchCount)
        if hs.searching {
            HStack(spacing: 8) {
                ProgressView()
                Text(L("board_holdsearch_searching")).font(.footnote)
            }
        } else if count >= 0 {
            Text(L("board_holdsearch_climbs_found", count))
                .font(.subheadline.bold())
                .foregroundStyle(count > 0 ? Color.green : Color.red)
        }
    }

    private func canvas(sheet: HoldSearchScreenModel, hs: HoldSearchScreenState) -> some View {
        let loaded = image
        let selected: Set<Int> = Set(hs.selectedHolds.map { Int(truncating: $0) })
        let zone: CGRect? = hs.zoneActive
            ? CGRect(x: CGFloat(hs.zoneLeft), y: CGFloat(hs.zoneTop),
                     width: CGFloat(hs.zoneRight - hs.zoneLeft), height: CGFloat(hs.zoneBottom - hs.zoneTop))
            : nil
        let preview: CGRect? = dragRect
        return GeometryReader { geo in
            Canvas { context, size in
                if let loaded {
                    context.draw(Image(uiImage: loaded), in: CGRect(origin: .zero, size: size))
                } else {
                    context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(.secondarySystemBackground)))
                }
                drawSelected(context: context, size: size, selected: selected)
                if let zone { drawArea(context: context, size: size, rect: zone, dashed: false) }
                if let preview { drawArea(context: context, size: size, rect: preview, dashed: true) }
            }
            .contentShape(Rectangle())
            .gesture(areaDrag(sheet: sheet, size: geo.size), including: areaMode ? .all : .subviews)
            .onTapGesture { location in
                guard !areaMode else { return }
                let nx = Float(location.x / geo.size.width)
                let ny = Float(location.y / geo.size.height)
                if let nearest = nearestHold(nx: nx, ny: ny) {
                    sheet.toggleHold(placementId: nearest)
                }
            }
        }
        .aspectRatio(ui.boardAspect > 0 ? CGFloat(ui.boardAspect) : 0.65, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func drawSelected(context: GraphicsContext, size: CGSize, selected: Set<Int>) {
        let radius = max(size.width * 0.022, 5)
        for hold in ui.boardHolds {
            guard selected.contains(Int(hold.placementId)) else { continue }
            let point = CGPoint(x: CGFloat(hold.x) * size.width, y: CGFloat(hold.y) * size.height)
            let ring = Path(ellipseIn: CGRect(x: point.x - radius, y: point.y - radius,
                                              width: radius * 2, height: radius * 2))
            context.stroke(ring, with: .color(.black.opacity(0.7)), lineWidth: radius * 0.6)
            context.stroke(ring, with: .color(.accentColor), lineWidth: radius * 0.4)
        }
    }

    /// The area box, in normalized image coordinates scaled to the canvas.
    private func drawArea(context: GraphicsContext, size: CGSize, rect: CGRect, dashed: Bool) {
        let scaled = CGRect(x: rect.minX * size.width, y: rect.minY * size.height,
                            width: rect.width * size.width, height: rect.height * size.height)
        let path = Path(roundedRect: scaled, cornerRadius: 4)
        context.fill(path, with: .color(Color.orange.opacity(0.15)))
        let style = dashed ? StrokeStyle(lineWidth: 2, dash: [6, 4]) : StrokeStyle(lineWidth: 2)
        context.stroke(path, with: .color(.orange), style: style)
    }

    private var dragRect: CGRect? {
        guard let dragStart, let dragEnd else { return nil }
        return CGRect(x: min(dragStart.x, dragEnd.x), y: min(dragStart.y, dragEnd.y),
                      width: abs(dragEnd.x - dragStart.x), height: abs(dragEnd.y - dragStart.y))
    }

    /// Android: a one-finger drag frames the area; it is committed on release
    /// unless the finger barely moved, and area mode then ends.
    private func areaDrag(sheet: HoldSearchScreenModel, size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard size.width > 0, size.height > 0 else { return }
                dragStart = CGPoint(x: value.startLocation.x / size.width, y: value.startLocation.y / size.height)
                dragEnd = CGPoint(x: value.location.x / size.width, y: value.location.y / size.height)
            }
            .onEnded { value in
                let dx: CGFloat = value.location.x - value.startLocation.x
                let dy: CGFloat = value.location.y - value.startLocation.y
                dragStart = nil
                dragEnd = nil
                guard size.width > 0, size.height > 0, (dx * dx + dy * dy).squareRoot() > 16 else { return }
                sheet.setZone(ax: Float(value.startLocation.x / size.width), ay: Float(value.startLocation.y / size.height),
                              bx: Float(value.location.x / size.width), by: Float(value.location.y / size.height))
                areaMode = false
            }
    }

    /// Nearest hold within a small radius, so a miss does not select the far side.
    private func nearestHold(nx: Float, ny: Float) -> Int32? {
        var best: (id: Int32, distance: Float)?
        for hold in ui.boardHolds {
            let dx = hold.x - nx, dy = hold.y - ny
            let distance = dx * dx + dy * dy
            if best == nil || distance < best!.distance { best = (hold.placementId, distance) }
        }
        guard let best, best.distance < 0.0016 else { return nil }
        return best.id
    }
}
