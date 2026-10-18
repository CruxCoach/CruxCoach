import CruxCoachCore
import SwiftUI

/// A compact summary of the nutrition day for the Today screen: the four
/// rings small, and water. Give it the state of a `NutritionScreenModel`
/// (`core.makeNutritionScreen()`); tapping it is the caller's to handle.
struct NutritionDayTile: View {
    let ui: NutritionDayUi

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(L("tr_nav_fuel"), systemImage: "fork.knife").font(.headline)
            HStack(spacing: 10) {
                ForEach(ui.rings, id: \.code) { ring in
                    VStack(spacing: 2) {
                        ZStack {
                            Circle().stroke(Color.secondary.opacity(0.2), lineWidth: 4)
                            Circle()
                                .trim(from: 0, to: CGFloat(min(max(ring.progress, 0), 1)))
                                .stroke(NutritionColors.ring(ring.code), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            Text(NutritionText.render(ring.value)).font(.caption2.monospacedDigit()).lineLimit(1).minimumScaleFactor(0.5)
                                .padding(4)
                        }
                        .frame(width: 40, height: 40)
                        Text(NutritionText.render(ring.label)).font(.caption2).lineLimit(1).minimumScaleFactor(0.6)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            HStack(spacing: 6) {
                Image(systemName: "drop.fill").foregroundStyle(NutritionColors.water)
                Text(NutritionText.render(ui.water.total)).font(.caption.monospacedDigit())
            }
        }
        .accessibilityElement(children: .combine)
    }
}
