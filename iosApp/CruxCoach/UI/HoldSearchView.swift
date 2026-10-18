import CruxCoachCore
import SwiftUI

/// Hold search: tap the holds a problem must use, or (Android's "Select area")
/// drag a box the problem must fit into. The result list narrows to climbs that
/// use every selected hold and lie fully inside the area, which is Android's rule.
struct HoldSearchView: View {
    let model: BrowserScreenModel
    let ui: BrowserScreenState
    @Environment(\.dismiss) private var dismiss
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
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(areaMode ? L("board_holdsearch_zone_hint") : LI("hold_search_hint"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                areaControls
                summary
                canvas
            }
            .padding()
        }
        // A drag on the board frames the area; it must not scroll the sheet.
        .scrollDisabled(areaMode)
        .navigationTitle(LI("hold_search_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("action_done")) { dismiss() } } }
    }

    private var areaControls: some View {
        HStack(spacing: 8) {
            Toggle(isOn: $areaMode) {
                Label(L("board_holdsearch_zone_mode"), systemImage: "square.grid.3x3")
            }
            .toggleStyle(.button)
            .accessibilityIdentifier("hold_search_area_mode")
            if ui.zoneActive {
                Button {
                    model.clearZone()
                } label: {
                    Label(L("board_holdsearch_zone_active"), systemImage: "xmark.circle.fill")
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(L("board_holdsearch_zone_clear"))
            }
            Spacer()
        }
    }

    @ViewBuilder private var summary: some View {
        let holdCount: Int = ui.holdFilter.count
        if holdCount > 0 || ui.zoneActive {
            HStack {
                Text(holdCount == 0 ? L("board_holdsearch_zone_only") : LI("hold_search_selected", holdCount))
                    .font(.subheadline.bold())
                Spacer()
                if ui.loadState == "loading" {
                    ProgressView()
                } else if ui.totalCount >= 0 {
                    Text(L("board_holdsearch_climbs_found", Int(ui.totalCount)))
                        .font(.subheadline)
                        .foregroundStyle(ui.totalCount > 0 ? Color.green : Color.red)
                }
                Button(L("board_holdsearch_reset")) { model.clearHoldFilter() }
                    .buttonStyle(.borderless)
            }
        }
    }

    private var canvas: some View {
        let loaded = image
        let selected = Set(ui.holdFilter.map { Int(truncating: $0) })
        let zone: CGRect? = ui.zoneActive
            ? CGRect(x: CGFloat(ui.zoneLeft), y: CGFloat(ui.zoneTop),
                     width: CGFloat(ui.zoneRight - ui.zoneLeft), height: CGFloat(ui.zoneBottom - ui.zoneTop))
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
            .gesture(areaDrag(size: geo.size), including: areaMode ? .all : .subviews)
            .onTapGesture { location in
                guard !areaMode else { return }
                let nx = Float(location.x / geo.size.width)
                let ny = Float(location.y / geo.size.height)
                if let nearest = nearestHold(nx: nx, ny: ny) {
                    model.toggleHoldFilter(placementId: nearest)
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
    private func areaDrag(size: CGSize) -> some Gesture {
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
                model.setZone(ax: Float(value.startLocation.x / size.width), ay: Float(value.startLocation.y / size.height),
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
