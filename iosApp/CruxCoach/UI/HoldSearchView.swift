import CruxCoachCore
import SwiftUI

/// Hold search: tap the holds a problem must use. The result list narrows to
/// climbs that use every selected hold, which is Android's rule.
struct HoldSearchView: View {
    let model: BrowserScreenModel
    let ui: BrowserScreenState
    @Environment(\.dismiss) private var dismiss

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
        VStack(spacing: 12) {
            Text(LI("hold_search_hint")).font(.footnote).foregroundStyle(.secondary)
            canvas
            HStack {
                Text(LI("hold_search_selected", ui.holdFilter.count))
                Spacer()
                Button(LI("hold_search_clear")) { model.clearHoldFilter() }
                    .disabled(ui.holdFilter.isEmpty)
            }
            Spacer()
        }
        .padding()
        .navigationTitle(LI("hold_search_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button(L("action_done")) { dismiss() } } }
    }

    private var canvas: some View {
        let loaded = image
        let selected = Set(ui.holdFilter.map { Int(truncating: $0) })
        return GeometryReader { geo in
            Canvas { context, size in
                if let loaded {
                    context.draw(Image(uiImage: loaded), in: CGRect(origin: .zero, size: size))
                } else {
                    context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(.secondarySystemBackground)))
                }
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
            .contentShape(Rectangle())
            .onTapGesture { location in
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
