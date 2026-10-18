import CruxCoachCore
import SwiftUI

/// The training area (Android `TrainingNavigation`): four tabs — Today, Training, Nutrition,
/// Progress — opened from the board browser's menu. When the athlete database cannot be
/// opened the area says so instead of failing the app.
struct TrainingHomeView: View {
    let core: AppCore
    let onClose: () -> Void

    enum Tab: Hashable { case today, training, nutrition, progress }

    @State private var tab: Tab = .today
    @State private var basics: BasicsScreenModel?
    @State private var showBasics = false

    var body: some View {
        if core.athleteAvailable {
            TabView(selection: $tab) {
                TodayView(core: core, onClose: onClose,
                          onOpenNutrition: { tab = .nutrition },
                          onOpenProgress: { tab = .progress })
                    .tabItem { Label(L("tr_tab_today"), systemImage: "calendar") }
                    .tag(Tab.today)
                TrainingWorkoutsPlaceholder(onClose: onClose)
                    .tabItem { Label(L("tr_tab_training"), systemImage: "dumbbell") }
                    .tag(Tab.training)
                NavigationStack {
                    NutritionView(core: core)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button(L("action_close"), action: onClose) }
                        }
                }
                    .tabItem { Label(L("tr_nav_fuel"), systemImage: "fork.knife") }
                    .tag(Tab.nutrition)
                TrainingProgressPlaceholder(onClose: onClose)
                    .tabItem { Label(L("tr_tab_progress"), systemImage: "chart.line.uptrend.xyaxis") }
                    .tag(Tab.progress)
            }
            .task {
                // The first visit asks the basics once (Android `BasicsGate`).
                guard basics == nil, let model = core.makeBasicsScreen() else { return }
                basics = model
                model.checkGate()
                for _ in 0..<50 {
                    if model.currentState.gateOpen {
                        model.consumeGate()
                        showBasics = true
                        break
                    }
                    try? await Task.sleep(nanoseconds: 100_000_000)
                }
                model.close()
            }
            .fullScreenCover(isPresented: $showBasics) {
                BasicsView(core: core) { showBasics = false }
            }
        } else {
            NavigationStack {
                ContentUnavailableView {
                    Label(LI("training_unavailable_title"), systemImage: "lock.trianglebadge.exclamationmark")
                } description: {
                    Text(LI("training_unavailable_text"))
                }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L("action_close"), action: onClose)
                    }
                }
            }
        }
    }
}
