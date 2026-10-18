import CruxCoachCore
import SwiftUI

/// Builds SwiftUI paths from the Kotlin body-map shapes (`BodyShapeUi`), in the drawing's own
/// units. Kinds: ellipse, polygon, smooth (curve through the midpoints, Android's smoothed
/// outline), box, line, stroke and dot.
enum BodyMapPaths {
    static func values(_ shape: BodyShapeUi) -> [CGFloat] {
        let raw: [KotlinDouble] = shape.points
        return raw.map { CGFloat($0.doubleValue) }
    }

    static func path(_ shape: BodyShapeUi) -> Path {
        let v = values(shape)
        let radius = CGFloat(shape.radius)
        switch shape.kind {
        case "ellipse":
            guard v.count >= 4 else { return Path() }
            return Path(ellipseIn: CGRect(x: v[0] - v[2], y: v[1] - v[3], width: 2 * v[2], height: 2 * v[3]))
        case "polygon":
            return polygon(v)
        case "smooth":
            return smooth(v)
        case "box":
            guard v.count >= 4 else { return Path() }
            let rect = CGRect(x: v[0], y: v[1], width: v[2] - v[0], height: v[3] - v[1])
            return Path(roundedRect: rect, cornerRadius: radius)
        case "line", "stroke":
            return polyline(v)
        case "dot":
            guard v.count >= 2 else { return Path() }
            return Path(ellipseIn: CGRect(x: v[0] - radius, y: v[1] - radius, width: 2 * radius, height: 2 * radius))
        default:
            return Path()
        }
    }

    private static func polyline(_ v: [CGFloat]) -> Path {
        var path = Path()
        guard v.count >= 4 else { return path }
        path.move(to: CGPoint(x: v[0], y: v[1]))
        var i = 2
        while i + 1 < v.count {
            path.addLine(to: CGPoint(x: v[i], y: v[i + 1]))
            i += 2
        }
        return path
    }

    private static func polygon(_ v: [CGFloat]) -> Path {
        var path = polyline(v)
        path.closeSubpath()
        return path
    }

    /// Android `Outline.toPath`: a quadratic curve from midpoint to midpoint, each point the control.
    private static func smooth(_ v: [CGFloat]) -> Path {
        var path = Path()
        let n = v.count / 2
        guard n >= 3 else { return path }
        var points: [CGPoint] = []
        for i in 0..<n { points.append(CGPoint(x: v[2 * i], y: v[2 * i + 1])) }
        var mids: [CGPoint] = []
        for i in 0..<n {
            let a = points[i]
            let b = points[(i + 1) % n]
            mids.append(CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2))
        }
        path.move(to: mids[0])
        for i in 1...n {
            path.addQuadCurve(to: mids[i % n], control: points[i % n])
        }
        path.closeSubpath()
        return path
    }
}

/// The body map's colours (Android `BodyMapCanvas`): the figure, an idle area, the accent, and
/// the tint of an area with a given accent share — the swatches of the legend use the same.
enum BodyMapColors {
    static let base = Color(uiColor: .systemGray5)
    static let idle = Color(uiColor: .systemGray4)
    static let outline = Color(uiColor: .opaqueSeparator).opacity(0.35)

    /// The idle tint with [mix] of the accent on top (Android `lerp(idle, accent, areaMix(v))`).
    static func swatch(mix: Double) -> some View {
        ZStack {
            Circle().fill(idle)
            Circle().fill(Color.accentColor.opacity(mix))
        }
        .frame(width: 12, height: 12)
    }
}

/// Front and back of the figure with every area tinted by its accent share (Android `BodyMap`):
/// [mix] maps an area code to 0 (idle) … 1 (accent). With [onTap], a tap reports the area and
/// the body side under the finger. "Front"/"Back" are drawn under the figures.
struct BodyMapCanvas: View {
    let kit: BodyMapKit
    let mix: [String: Double]
    var sideCode: String = "both"
    var showLabels: Bool = true
    var onTap: ((BodyHitUi) -> Void)? = nil

    var body: some View {
        GeometryReader { geo in
            drawing
                .contentShape(Rectangle())
                .onTapGesture { location in
                    guard let onTap else { return }
                    let hit: BodyHitUi = kit.hit(width: Double(geo.size.width), height: Double(geo.size.height),
                                                 sideCode: sideCode, labels: showLabels,
                                                 x: Double(location.x), y: Double(location.y))
                    if hit.found { onTap(hit) }
                }
        }
    }

    private var drawing: some View {
        let figures: [BodyFigureUi] = kit.figures
        let tints = mix
        let side = sideCode
        let labels = showLabels
        let layoutKit = kit
        return Canvas { context, size in
            let layout: BodyLayoutUi = layoutKit.layout(width: Double(size.width), height: Double(size.height),
                                                        sideCode: side, labels: labels)
            for placement in layout.placements {
                guard let figure = figures.first(where: { $0.sideCode == placement.sideCode }) else { continue }
                BodyMapCanvas.draw(figure: figure, placement: placement, layout: layout, tints: tints, context: context)
                if labels {
                    BodyMapCanvas.label(placement: placement, layout: layout, context: context)
                }
            }
        }
    }

    private static func draw(figure: BodyFigureUi, placement: BodyPlacementUi, layout: BodyLayoutUi,
                             tints: [String: Double], context: GraphicsContext) {
        var ctx = context
        ctx.translateBy(x: CGFloat(placement.dx), y: CGFloat(placement.dy))
        ctx.scaleBy(x: CGFloat(layout.scale), y: CGFloat(layout.scale))
        for shape in figure.base {
            ctx.fill(BodyMapPaths.path(shape), with: .color(BodyMapColors.base))
        }
        for region in figure.regions {
            let path = BodyMapPaths.path(region.shape)
            ctx.fill(path, with: .color(BodyMapColors.idle))
            let share: Double = tints[region.areaCode] ?? 0
            if share > 0 {
                ctx.fill(path, with: .color(Color.accentColor.opacity(min(share, 1))))
            }
            ctx.stroke(path, with: .color(BodyMapColors.outline), lineWidth: 0.6)
        }
    }

    private static func label(placement: BodyPlacementUi, layout: BodyLayoutUi, context: GraphicsContext) {
        let scale = CGFloat(layout.scale)
        let x = CGFloat(placement.dx) + CGFloat(layout.figureWidth) * scale / 2
        let y = CGFloat(placement.dy) + CGFloat(layout.figureHeight) * scale + 2
        let key = placement.sideCode == "front" ? "trb_front" : "trb_back"
        let text = Text(L(key)).font(.caption2).foregroundColor(.secondary)
        context.draw(text, at: CGPoint(x: x, y: y), anchor: .top)
    }
}

/// Tap the body to choose an area (Android `BodyMapPicker`, injuries and pain): the chosen area
/// in the accent, its name under the map, or the hint while none is chosen.
struct BodyMapPicker: View {
    let kit: BodyMapKit
    /// Area code, "" for none.
    let selected: String
    let onPick: (BodyHitUi) -> Void

    var body: some View {
        let label: String = selected.isEmpty ? L("trb_tap_hint") : L(kit.areaLabelKey(areaCode: selected))
        let tints: [String: Double] = selected.isEmpty ? [:] : [selected: 1.0]
        VStack(spacing: 4) {
            BodyMapCanvas(kit: kit, mix: tints, sideCode: "both", showLabels: true, onTap: onPick)
                .frame(height: 230)
                .accessibilityElement()
                .accessibilityLabel(label)
                .accessibilityIdentifier("body_map_picker")
            Text(label)
                .font(.subheadline)
                .foregroundStyle(selected.isEmpty ? Color.secondary : Color.primary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("body_map_selected")
        }
    }
}

/// A side view of the hand on the hold (Android `GripPictogram`), the edge depth roughly to
/// scale and "20 mm" under the drawing, never on top of the edge.
struct GripPictureView: View {
    let grip: GripPictureUi

    var body: some View {
        VStack(spacing: 2) {
            GripCanvas(grip: grip)
            if grip.showsEdge {
                Text(L("trb_edge_mm", Int(grip.edgeMm))).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(TrRender.text(grip.accessibilityText))
    }
}

/// The grip drawing alone: hold first, then the hand, in the order Kotlin lists them.
struct GripCanvas: View {
    let grip: GripPictureUi

    var body: some View {
        let shapes: [BodyShapeUi] = grip.shapes
        let boxW = CGFloat(grip.boxWidth)
        let boxH = CGFloat(grip.boxHeight)
        return Canvas { context, size in
            guard boxW > 0, boxH > 0 else { return }
            let scale = min(size.width / boxW, size.height / boxH)
            var ctx = context
            ctx.translateBy(x: (size.width - boxW * scale) / 2, y: (size.height - boxH * scale) / 2)
            ctx.scaleBy(x: scale, y: scale)
            for shape in shapes {
                GripCanvas.draw(shape, context: ctx)
            }
        }
    }

    private static func tint(_ role: String) -> Color {
        switch role {
        case "wall": return Color(uiColor: .systemGray5)
        case "hole": return Color.primary.opacity(0.45)
        case "hand": return Color.accentColor
        case "thumb": return Color.accentColor.opacity(0.85)
        default: return Color(uiColor: .systemGray)
        }
    }

    private static func draw(_ shape: BodyShapeUi, context: GraphicsContext) {
        let path = BodyMapPaths.path(shape)
        let shading = GraphicsContext.Shading.color(tint(shape.role))
        switch shape.kind {
        case "line":
            context.stroke(path, with: shading, style: StrokeStyle(lineWidth: CGFloat(shape.width), lineCap: .butt))
        case "stroke":
            context.stroke(path, with: shading,
                           style: StrokeStyle(lineWidth: CGFloat(shape.width), lineCap: .round, lineJoin: .round))
        default:
            if shape.filled {
                context.fill(path, with: shading)
            } else {
                context.stroke(path, with: shading, lineWidth: CGFloat(shape.width))
            }
        }
    }
}
