import SwiftUI

// The three tabs of the training area that other parts of the iOS 0.2.4 port fill in.
// Each says plainly that this part is not on iPhone yet; nothing here pretends to work.
// Replace the whole struct when the tab is ported (keep the name and the `onClose` parameter).

/// Training tab: workouts, week plan and the exercise library (Android `WorkoutsScreen`, `ExerciseCatalogScreen`).
struct TrainingWorkoutsPlaceholder: View {
    let onClose: () -> Void

    var body: some View {
        TrainingNotYetTab(title: L("tr_tab_training"), systemImage: "dumbbell", onClose: onClose)
    }
}

/// Nutrition tab (Android `FuelScreen`).
struct TrainingNutritionPlaceholder: View {
    let onClose: () -> Void

    var body: some View {
        TrainingNotYetTab(title: L("tr_nav_fuel"), systemImage: "fork.knife", onClose: onClose)
    }
}

/// Progress tab: statistics and body (Android `StatsHubScreen`).
struct TrainingProgressPlaceholder: View {
    let onClose: () -> Void

    var body: some View {
        TrainingNotYetTab(title: L("tr_tab_progress"), systemImage: "chart.line.uptrend.xyaxis", onClose: onClose)
    }
}

private struct TrainingNotYetTab: View {
    let title: String
    let systemImage: String
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ContentUnavailableView {
                Label(title, systemImage: systemImage)
            } description: {
                Text(LI("training_tab_not_on_iphone"))
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_close"), action: onClose)
                }
            }
        }
    }
}
