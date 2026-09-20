import CruxCoachCore
import SwiftUI

/// Draws the bundled board image with role-coloured rings. All hold positions are
/// normalized by the Kotlin geometry, so this view only scales them to the canvas.
struct BoardCanvasView: View {
    let imagePaths: [String]
    let holds: [DetailHoldUi]
    let aspect: CGFloat

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
        let loaded = image
        Canvas { context, size in
            if let loaded {
                context.draw(Image(uiImage: loaded), in: CGRect(origin: .zero, size: size))
            } else {
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(.secondarySystemBackground)))
            }
            let radius = max(size.width * 0.028, 6)
            for hold in holds {
                let point = CGPoint(x: CGFloat(hold.x) * size.width, y: CGFloat(hold.y) * size.height)
                let ring = Path(ellipseIn: CGRect(x: point.x - radius, y: point.y - radius,
                                                  width: radius * 2, height: radius * 2))
                // Dark outline under the role colour keeps the ring visible on any hold (0.2.3 contrast fix).
                context.stroke(ring, with: .color(.black.opacity(0.7)), lineWidth: radius * 0.55)
                context.stroke(ring, with: .color(Color(argb: hold.argb)), lineWidth: radius * 0.35)
            }
        }
        .aspectRatio(aspect > 0 ? aspect : 0.65, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel(LI("detail_board_a11y", holds.count))
    }
}

extension Color {
    init(argb: Int64) {
        self.init(.sRGB,
                  red: Double((argb >> 16) & 0xff) / 255,
                  green: Double((argb >> 8) & 0xff) / 255,
                  blue: Double(argb & 0xff) / 255,
                  opacity: ((argb >> 24) & 0xff) == 0 ? 1 : Double((argb >> 24) & 0xff) / 255)
    }
}
