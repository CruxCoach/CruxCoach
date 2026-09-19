import CruxCoachCore
import Foundation
import Security

/// Generic-password items, device-only and never synchronised to iCloud.
final class KeychainSecretStore: SecretStore {
    private let service: String

    init(service: String = "org.cruxcoach.ios.secrets") { self.service = service }

    private func baseQuery(_ name: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: name,
         kSecAttrSynchronizable as String: false]
    }

    func read(name: String) -> KotlinByteArray? {
        var query = baseQuery(name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return data.toKotlinByteArray()
    }

    func write(name: String, value: KotlinByteArray) -> Bool {
        let data = value.toData()
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(baseQuery(name) as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = baseQuery(name)
        add.merge(attributes) { _, new in new }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    func delete(name: String) -> Bool {
        let status = SecItemDelete(baseQuery(name) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
