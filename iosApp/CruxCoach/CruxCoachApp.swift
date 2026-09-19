import SwiftUI

@main
struct CruxCoachApp: App {
    @State private var environment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(environment)
                .tint(Color("AccentOrange", bundle: nil))
        }
    }
}
