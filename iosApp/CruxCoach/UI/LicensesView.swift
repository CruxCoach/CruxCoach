import SwiftUI

/// Android's "Open-source licenses": the libraries the iPhone app links, and the
/// repository's third-party notice for the bundled assets (board images, map data,
/// food tables …), shipped inside the app bundle.
struct LicensesView: View {
    private struct Component: Identifiable {
        let name: String
        let license: String
        let url: String
        var id: String { name }
    }

    /// What the iOS binary links besides Apple's frameworks (see iosApp/project.yml and :appcore).
    private let components = [
        Component(name: "SQLCipher (Zetetic)", license: "BSD-style", url: "https://github.com/sqlcipher/SQLCipher.swift"),
        Component(name: "zstd (Meta Platforms)", license: "BSD-3-Clause", url: "https://github.com/facebook/zstd"),
        Component(name: "nostr-sdk-swift (rust-nostr)", license: "MIT", url: "https://github.com/nostrdevkit/nostr-sdk-swift"),
        Component(name: "secp256k1-kmp (ACINQ) / libsecp256k1 (Bitcoin Core)", license: "Apache-2.0 / MIT", url: "https://github.com/ACINQ/secp256k1-kmp"),
        Component(name: "SQLDelight (Cash App)", license: "Apache-2.0", url: "https://github.com/cashapp/sqldelight"),
        Component(name: "Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime (JetBrains)", license: "Apache-2.0", url: "https://github.com/Kotlin"),
    ]

    @State private var notice = ""

    var body: some View {
        List {
            Section {
                Text(L("settings_licenses_desc")).font(.footnote).foregroundStyle(.secondary)
                ForEach(components) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name)
                        Text(item.license).font(.caption).foregroundStyle(.secondary)
                        if let link = URL(string: item.url) {
                            Link(item.url, destination: link).font(.caption)
                        }
                    }
                }
            }
            if !notice.isEmpty {
                Section("THIRD_PARTY_LICENSES.md") {
                    Text(notice).font(.caption.monospaced()).textSelection(.enabled)
                }
            }
        }
        .navigationTitle(L("settings_licenses_title"))
        .task {
            guard notice.isEmpty,
                  let url = Bundle.main.url(forResource: "THIRD_PARTY_LICENSES", withExtension: "md"),
                  let text = try? String(contentsOf: url, encoding: .utf8) else { return }
            notice = text
        }
    }
}
