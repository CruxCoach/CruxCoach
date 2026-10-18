import CruxCoachCore
import SwiftUI

/// The energy card (Android `EnergyCareCard`, RED-S guard) on its own, for Today, Body and the
/// weekly review: the same title, reasons and explanation as on the nutrition page; nothing is
/// locked. It loads its own state and shows nothing while there is no reason. Pass `onAdjustGoal`
/// where the screen can open the weight-loss goal (Today, Nutrition); without it there is no button,
/// as on Android's Body and weekly review.
struct EnergyCareCardView: View {
    let core: AppCore
    var onAdjustGoal: (() -> Void)?

    @State private var host: ScreenHost<EnergyCareCardModel, EnergyCareCardState>?
    @State private var appeared = false

    init(core: AppCore, onAdjustGoal: (() -> Void)? = nil) {
        self.core = core
        self.onAdjustGoal = onAdjustGoal
    }

    var body: some View {
        Group {
            if let host, host.state.card.visible {
                EnergyCareCardBody(card: host.state.card, onAdjustGoal: onAdjustGoal)
            } else {
                // A zero-size placeholder: modifiers on a bare EmptyView may never run `.task`.
                Color.clear.frame(width: 0, height: 0).accessibilityHidden(true)
            }
        }
        .task {
            guard host == nil, let model = core.makeEnergyCareCard() else { return }
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
        .onAppear {
            // Older food days and the training of the week change the reasons too: read again on return.
            if appeared { host?.model.reload() } else { appeared = true }
        }
    }
}

private struct EnergyCareCardBody: View {
    let card: NutritionEnergyCardUi
    let onAdjustGoal: (() -> Void)?

    @State private var info = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: "heart.fill")
                Text(L("trt_reds_title")).font(.headline)
                Spacer()
                Button { info = true } label: { Image(systemName: "info.circle") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("trt_reds_title"))
            }
            ForEach(card.warnings, id: \.code) { warning in
                Text("• " + NutritionText.render(warning.text)).font(.subheadline)
            }
            adjust
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(TrainingStyle.caution.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        .alert(L("trt_reds_title"), isPresented: $info) {
            Button(L("action_close"), role: .cancel) {}
        } message: {
            Text(L("trt_reds_info"))
        }
    }

    @ViewBuilder
    private var adjust: some View {
        if card.canAdjustGoal, let onAdjustGoal {
            HStack {
                Spacer()
                Button(L("trn_adjust_goal"), action: onAdjustGoal).buttonStyle(.borderless)
            }
        }
    }
}
