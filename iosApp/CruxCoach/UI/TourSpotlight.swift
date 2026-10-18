import CruxCoachCore
import SwiftUI

/// Where the controls a tour step can point at are, in window coordinates.
///
/// Frames rather than anchor preferences: the browser's Bluetooth and More
/// buttons live in the navigation bar, whose items are hosted outside the
/// view's preference tree, so a preference set there never reaches the screen.
@Observable
final class TourAnchors {
    var frames: [String: CGRect] = [:]
}

extension View {
    /// Marks a real control the tour can highlight (Android `Modifier.tourTarget`).
    /// The control keeps its own action; the spotlight only leaves it touchable.
    func tourAnchor(_ id: String, in anchors: TourAnchors?) -> some View {
        modifier(TourAnchorReporter(id: id, anchors: anchors))
    }

    /// Lays the spotlight of the current step over this view (Android `TourHost`).
    /// [target] nil shows nothing; an empty [target] shows the text without a cut-out.
    func tourSpotlight(anchors: TourAnchors, tour: BrowserTourModel?, step: String, target: String?,
                       message: String, visible: Bool, onMove: @escaping () -> Void) -> some View {
        modifier(TourSpotlightHost(anchors: anchors, tour: tour, step: step, target: target,
                                   message: message, visible: visible, onMove: onMove))
    }
}

private struct TourAnchorReporter: ViewModifier {
    let id: String
    let anchors: TourAnchors?

    func body(content: Content) -> some View {
        content.background {
            if let anchors {
                GeometryReader { proxy in
                    let frame: CGRect = proxy.frame(in: .global)
                    Color.clear
                        .onAppear { anchors.frames[id] = frame }
                        .onChange(of: frame) { _, next in anchors.frames[id] = next }
                        .onDisappear { anchors.frames[id] = nil }
                }
            }
        }
    }
}

/// Steps the Kotlin model (`BrowserTourModel`) still has to walk through.
enum TourSteps {
    static func isActive(_ step: String) -> Bool {
        !step.isEmpty && step != "inactive" && step != "done"
    }
}

private struct TourSpotlightHost: ViewModifier {
    let anchors: TourAnchors
    let tour: BrowserTourModel?
    let step: String
    let target: String?
    let message: String
    let visible: Bool
    let onMove: () -> Void

    func body(content: Content) -> some View {
        content.overlay { spotlight }
    }

    @ViewBuilder
    private var spotlight: some View {
        if let tour, let target, visible, TourSteps.isActive(step) {
            let current: String = step
            let move: () -> Void = onMove
            TourSpotlightOverlay(
                anchors: anchors, target: target, message: message,
                showsNoBoard: current == "connect",
                onNext: { tour.advance(from: current); move() },
                onSkip: { tour.skip(); move() },
                onNoBoard: { tour.deferBluetooth(); move() })
        }
    }
}

/// Android `TourSpotlight`: a dark scrim with a rounded cut-out around the real
/// control, an orange outline, the step's text beside it and the tour's buttons.
/// The scrim swallows touches; the cut-out lets them through to the control.
/// Without a measured target it shows the text alone, without blocking anything.
struct TourSpotlightOverlay: View {
    let anchors: TourAnchors
    let target: String
    let message: String
    let showsNoBoard: Bool
    let onNext: () -> Void
    let onSkip: () -> Void
    let onNoBoard: () -> Void

    var body: some View {
        GeometryReader { proxy in
            let origin: CGPoint = proxy.frame(in: .global).origin
            let size: CGSize = proxy.size
            if let hole = holeRect(origin: origin, size: size) {
                spotlit(hole: hole, size: size)
            } else {
                VStack {
                    Spacer(minLength: 0)
                    card.padding(.bottom, 48)
                }
            }
        }
        .ignoresSafeArea()
    }

    /// The target in this overlay's own coordinates, a little larger than the control.
    private func holeRect(origin: CGPoint, size: CGSize) -> CGRect? {
        guard !target.isEmpty, let frame = anchors.frames[target] else { return nil }
        let local: CGRect = frame.offsetBy(dx: -origin.x, dy: -origin.y).insetBy(dx: -6, dy: -6)
        let onScreen: CGRect = local.intersection(CGRect(origin: .zero, size: size))
        guard !onScreen.isNull, onScreen.width > 0, onScreen.height > 0 else { return nil }
        return onScreen
    }

    @ViewBuilder
    private func spotlit(hole: CGRect, size: CGSize) -> some View {
        // Android puts the text below a target in the upper half, above one in the lower half.
        let below: Bool = hole.midY < size.height / 2
        ZStack(alignment: .topLeading) {
            SpotlightCutout(hole: hole)
                .fill(Color.black.opacity(0.78), style: FillStyle(eoFill: true))
                .contentShape(SpotlightCutout(hole: hole), eoFill: true)
                .onTapGesture {}
                .accessibilityHidden(true)
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.orange, lineWidth: 2)
                .frame(width: hole.width, height: hole.height)
                .offset(x: hole.minX, y: hole.minY)
                .allowsHitTesting(false)
            placedCard(hole: hole, size: size, below: below)
        }
    }

    @ViewBuilder
    private func placedCard(hole: CGRect, size: CGSize, below: Bool) -> some View {
        if below {
            VStack(spacing: 0) {
                Color.clear.frame(height: hole.maxY + 24).allowsHitTesting(false)
                card
                Spacer(minLength: 0)
            }
        } else {
            VStack(spacing: 0) {
                Spacer(minLength: 0)
                card
                Color.clear.frame(height: max(size.height - hole.minY + 24, 0)).allowsHitTesting(false)
            }
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(message)
                .font(.body)
                .foregroundStyle(Color.white)
                .fixedSize(horizontal: false, vertical: true)
            buttons
        }
        .padding(12)
        .frame(maxWidth: 420, alignment: .leading)
        .background(Color(red: 0.043, green: 0.043, blue: 0.059), in: RoundedRectangle(cornerRadius: 12))
        .environment(\.colorScheme, .dark)
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity)
    }

    private var buttons: some View {
        HStack(spacing: 8) {
            Button(action: onSkip) {
                Label(L("tour_skip"), systemImage: "xmark")
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("tour_skip")
            Spacer(minLength: 4)
            if showsNoBoard {
                Button(LI("tour_no_board"), action: onNoBoard)
                    .accessibilityIdentifier("tour_no_board")
            }
            Button(L("action_next"), action: onNext)
                .buttonStyle(.borderedProminent)
                .tint(Color.orange)
                .accessibilityIdentifier("tour_next")
        }
        .lineLimit(2)
        .minimumScaleFactor(0.85)
    }
}

/// The whole rectangle minus one rounded hole, filled even-odd.
private struct SpotlightCutout: Shape {
    let hole: CGRect

    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.addRect(rect)
        path.addRoundedRect(in: hole, cornerSize: CGSize(width: 12, height: 12))
        return path
    }
}
