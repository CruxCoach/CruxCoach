import CruxCoachCore
import SwiftUI

/// Ring colours: energy violet, carbohydrate the accent, protein green, fat gold, water blue.
enum NutritionColors {
    static let energy = Color(red: 0x9C / 255.0, green: 0x6A / 255.0, blue: 0xDE / 255.0)
    static let fat = Color(red: 0xD9 / 255.0, green: 0xA4 / 255.0, blue: 0x00 / 255.0)
    static let water = Color(red: 0x2F / 255.0, green: 0x80 / 255.0, blue: 0xED / 255.0)

    static func ring(_ code: String) -> Color {
        switch code {
        case "energy": return energy
        case "protein": return .green
        case "fat": return fat
        default: return .accentColor
        }
    }
}

/// Energy, carbohydrate, protein and fat side by side, in that order. Every
/// ring explains itself when tapped: energy its breakdown, the others how
/// their target comes about.
struct NutritionRingsRow: View {
    let rings: [NutritionRingUi]
    let onTap: (String) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            ForEach(rings, id: \.code) { ring in
                Button { onTap(ring.code) } label: {
                    NutritionRingView(ring: ring)
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .combine)
                .accessibilityHint(ring.code == "energy" ? L("trn_open_energy") : L("action_show_info", NutritionText.render(ring.label)))
            }
        }
        .padding(.vertical, 6)
    }
}

struct NutritionRingView: View {
    let ring: NutritionRingUi

    var body: some View {
        VStack(spacing: 4) {
            ZStack {
                Circle().stroke(Color.secondary.opacity(0.2), lineWidth: 8)
                Circle()
                    .trim(from: 0, to: CGFloat(min(max(ring.progress, 0), 1)))
                    .stroke(NutritionColors.ring(ring.code), style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                VStack(spacing: 0) {
                    // A reached target gets its tick – a small reward, never a "too much".
                    if ring.reached {
                        Image(systemName: "checkmark.circle.fill").font(.caption2)
                            .foregroundStyle(NutritionColors.ring(ring.code))
                            .accessibilityLabel(L("trf_reached"))
                    }
                    Text(NutritionText.render(ring.value))
                        .font(.headline.monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(NutritionText.render(ring.compactTargetText))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .padding(8)
            }
            .frame(width: 72, height: 72)
            Text(NutritionText.render(ring.label))
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            if ring.hasBelowText {
                Text(NutritionText.render(ring.belowText))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
        }
    }
}

/// Water as glasses: tap the next empty one for another glass, the last full one to take it back.
struct NutritionWaterCard: View {
    let water: NutritionWaterUi
    let onAdd: () -> Void
    let onRemoveLast: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Image(systemName: "drop.fill").foregroundStyle(NutritionColors.water)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("trf_water")).font(.headline)
                    Text(NutritionText.render(water.hint))
                        .font(.caption)
                        .foregroundStyle(water.reached ? Color.green : Color.secondary)
                }
                Spacer()
                Text(NutritionText.render(water.total)).font(.subheadline.monospacedDigit())
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(minimum: 24), spacing: 2), count: 10), spacing: 4) {
                ForEach(0..<Int(water.glasses), id: \.self) { index in
                    glass(index)
                }
            }
        }
    }

    @ViewBuilder
    private func glass(_ index: Int) -> some View {
        let full = Int(water.full)
        let filled = index < full
        let isLast = filled && index == full - 1
        let isNext = index == full
        Button {
            if isLast { onRemoveLast() } else if !filled { onAdd() }
        } label: {
            Image(systemName: filled ? "waterbottle.fill" : "waterbottle")
                .font(isNext ? .title3 : .body)
                .foregroundStyle(filled ? NutritionColors.water : Color.secondary.opacity(isNext ? 0.9 : 0.4))
                .frame(minWidth: 28, minHeight: 32)
        }
        .buttonStyle(.borderless)
        .disabled(filled && !isLast)
        .accessibilityLabel(isLast ? L("tru_water_remove") : (filled ? "" : NutritionText.render(water.addLabel)))
    }
}

/// How a macro ring's target comes about, and right under it the choice:
/// recommended or an own target.
struct NutritionRingSheet: View {
    let host: ScreenHost<NutritionScreenModel, NutritionDayUi>
    let code: String
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            if let ring = host.state.rings.first(where: { $0.code == code }) {
                Form {
                    Section {
                        ForEach(Array(ring.explanation.enumerated()), id: \.offset) { _, text in
                            Text(NutritionText.render(text)).font(.subheadline)
                        }
                    }
                    Section {
                        NutritionTargetEditor(target: ring.ownTarget, model: host.model)
                    }
                }
                .navigationTitle(NutritionText.render(ring.label))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button(L("action_close"), action: onDone) }
                }
            } else {
                ProgressView()
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// "Recommended (208 g)" or "Own target" with its field (Android `TargetChoice`).
/// Choosing "own" starts from the recommendation; a typed value saves itself
/// once it is plausible, and the field says why when it is not.
struct NutritionTargetEditor: View {
    let target: OwnTargetUi
    let model: NutritionScreenModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(NutritionText.render(target.label)).font(.subheadline.weight(.semibold))
            Picker(NutritionText.render(target.label), selection: Binding(
                get: { target.isOwn },
                set: { own in
                    if own { model.chooseOwnTarget(kindCode: target.kindCode) } else { model.followRecommendation(kindCode: target.kindCode) }
                })) {
                Text(NutritionText.render(target.recommendedChip)).tag(false)
                Text(L("trn_target_own")).tag(true)
            }
            .pickerStyle(.segmented)
            if target.isOwn {
                NutritionAutoSaveField(label: NutritionText.render(target.fieldLabel),
                                       unit: NutritionText.render(target.unit),
                                       stored: target.ownText,
                                       numbersOnly: true,
                                       invalidHint: NutritionText.render(target.invalidHint),
                                       isValid: { model.isOwnTargetValid(kindCode: target.kindCode, text: $0) },
                                       onSave: { _ = model.setOwnTarget(kindCode: target.kindCode, text: $0) })
                if target.hasOwnNote {
                    Text(NutritionText.render(target.ownNote)).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}
