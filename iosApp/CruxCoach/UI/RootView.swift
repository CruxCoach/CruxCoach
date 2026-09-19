import SwiftUI

struct RootView: View {
    @Environment(AppEnvironment.self) private var environment

    var body: some View {
        NavigationStack {
            List {
                Section("CruxCoach 0.2.3 (iOS)") {
                    switch environment.storage {
                    case .checking:
                        ProgressView()
                    case .ready(let version):
                        Label("SQLCipher \(version)", systemImage: "lock.shield")
                            .accessibilityLabel("Encrypted storage available, SQLCipher \(version)")
                    case .failed(let failure, let detail):
                        Label("\(String(describing: failure)): \(detail)", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("CruxCoach")
        }
        .task { environment.runStorageSelfCheck() }
    }
}
