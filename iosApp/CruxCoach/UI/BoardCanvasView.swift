import CruxCoachCore
import SwiftUI

/// Draws the bundled board image with role-coloured rings, using the geometry ported from Android.
struct BoardCanvasView: View {
    let data: ClimbDetailData
    let holds: [RenderHold]
    let brandWire: String

    private var brand: BoardBrand { BoardBrand.companion.fromWire(wire: brandWire) }

    private var image: UIImage? {
        if brand == .moonboard, let layout = moonLayout { return Self.bundled("board_images/\(layout.image)") }
        for path in data.boardImagePaths { if let image = Self.bundled(path) { return image } }
        return nil
    }

    private var moonLayout: MoonBoardLayoutJson? {
        guard brand == .moonboard,
              let variant = MoonBoardVariant.companion.fromLayoutId(layoutId: data.climb.layoutId),
              let url = Bundle.main.resourceURL?.appendingPathComponent(MoonBoardGeometryKt.layoutJsonAssetPath(variant)),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return MoonBoardGeometryKt.parseMoonBoardLayoutOrNull(jsonText: text)
    }

    private var aspect: CGFloat {
        if let image { return image.size.width / max(image.size.height, 1) }
        if let size = data.boardSize {
            let w = CGFloat(size.edgeRight - size.edgeLeft), h = CGFloat(size.edgeTop - size.edgeBottom)
            if w > 0, h > 0 { return w / h }
        }
        return 0.65
    }

    var body: some View {
        let layout = moonLayout
        let geometry = layout.map { MoonBoardMappedGeometry(layout: $0) }
        Canvas { context, size in
            if let image {
                context.draw(Image(uiImage: image), in: CGRect(origin: .zero, size: size))
            } else {
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(.secondarySystemBackground)))
            }
            let radius = max(size.width * 0.028, 6)
            for hold in holds {
                var point: CGPoint?
                if hold.x >= 0, hold.y >= 0 {
                    point = CGPoint(x: CGFloat(hold.x) * size.width, y: CGFloat(hold.y) * size.height)
                } else if let p = geometry?.point(holdId: hold.placementId, canvasWidth: Float(size.width), canvasHeight: Float(size.height)) {
                    point = CGPoint(x: CGFloat(p.x), y: CGFloat(p.y))
                }
                guard let point else { continue }
                let ring = Path(ellipseIn: CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2))
                // Light and dark outlines keep the ring visible on any hold colour (0.2.3 contrast fix).
                context.stroke(ring, with: .color(.black.opacity(0.7)), lineWidth: radius * 0.55)
                context.stroke(ring, with: .color(Color(argb: hold.argb)), lineWidth: radius * 0.35)
            }
        }
        .aspectRatio(aspect, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel(LI("detail_board_a11y", holds.count))
    }

    private static func bundled(_ relativePath: String) -> UIImage? {
        guard let url = Bundle.main.resourceURL?.appendingPathComponent(relativePath) else { return nil }
        return UIImage(contentsOfFile: url.path)
    }
}

extension Color {
    init(argb: Int64) {
        self.init(.sRGB, red: Double((argb >> 16) & 0xff) / 255, green: Double((argb >> 8) & 0xff) / 255,
                  blue: Double(argb & 0xff) / 255, opacity: Double((argb >> 24) & 0xff) / 255)
    }
}
