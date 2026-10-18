import CruxCoachCore
import SwiftUI

/// Family colours and letters from Kotlin (`MapMarkerStyles`, the cruxcoach.org hues).
struct MapMarkerStyleBook {
    private let byWire: [String: MapMarkerStyleUi]

    init(_ styles: [MapMarkerStyleUi]) {
        var map: [String: MapMarkerStyleUi] = [:]
        for style in styles { map[style.brandWire] = style }
        byWire = map
    }

    func color(_ wire: String) -> Color {
        guard let style = byWire[wire] else { return Color.secondary }
        return Color(argb: style.colorArgb)
    }

    func onColor(_ wire: String) -> Color {
        guard let style = byWire[wire] else { return Color.white }
        return Color(argb: style.onColorArgb)
    }

    func monogram(_ wire: String) -> String { byWire[wire]?.monogram ?? "" }
}

/// Android `MapMarkerIcons`: a round badge in the family colour with its
/// letter and a white ring; several families share the badge as pie slices,
/// 12 o'clock first.
struct MapVenueMarker: View {
    let brandWires: [String]
    let styles: MapMarkerStyleBook
    let selected: Bool
    var diameter: CGFloat = 26

    var body: some View {
        let size: CGFloat = selected ? diameter * 1.25 : diameter
        ZStack {
            if brandWires.count <= 1 {
                Circle().fill(styles.color(brandWires.first ?? ""))
            } else {
                Canvas { context, canvasSize in
                    let center = CGPoint(x: canvasSize.width / 2, y: canvasSize.height / 2)
                    let radius = min(canvasSize.width, canvasSize.height) / 2
                    let step = 360.0 / Double(brandWires.count)
                    for (index, wire) in brandWires.enumerated() {
                        var slice = Path()
                        slice.move(to: center)
                        slice.addArc(center: center, radius: radius,
                                     startAngle: .degrees(-90 + step * Double(index)),
                                     endAngle: .degrees(-90 + step * Double(index + 1)),
                                     clockwise: false)
                        slice.closeSubpath()
                        context.fill(slice, with: .color(styles.color(wire)))
                    }
                }
            }
            if brandWires.count == 1, let wire = brandWires.first {
                Text(styles.monogram(wire))
                    .font(.system(size: size * 0.42, weight: .bold))
                    .foregroundStyle(styles.onColor(wire))
                    .minimumScaleFactor(0.5)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(Circle().strokeBorder(.white, lineWidth: 2))
        .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
        .frame(width: diameter * 1.25 + 6, height: diameter * 1.25 + 6)
        .contentShape(Rectangle())
    }
}

/// "All boards": every family on the map with how many places hold it — the
/// legend and the brand filter in one (Android's family chips).
struct MapLegendSheet: View {
    let model: MapScreenModel
    let ui: MapScreenState
    let styles: MapMarkerStyleBook
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                Button {
                    model.selectAllBrands()
                } label: {
                    HStack {
                        Text(L("map_brand_all")).foregroundStyle(.primary)
                        Spacer()
                        if ui.filters.brands.isEmpty { Image(systemName: "checkmark") }
                    }
                }
            }
            Section {
                ForEach(ui.brandOptions, id: \.code) { option in
                    Button {
                        model.toggleBrand(brandWire: option.code)
                    } label: {
                        HStack(spacing: 12) {
                            MapVenueMarker(brandWires: [option.code], styles: styles, selected: false, diameter: 22)
                            Text(option.label).foregroundStyle(.primary)
                            Spacer()
                            Text("\(option.count)").monospacedDigit().foregroundStyle(.secondary)
                            if option.isSelected { Image(systemName: "checkmark") }
                        }
                    }
                    .accessibilityLabel("\(option.label), \(option.count)")
                }
            }
        }
        .navigationTitle(L("map_brand_all"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("action_close")) { dismiss() }
            }
        }
    }
}
