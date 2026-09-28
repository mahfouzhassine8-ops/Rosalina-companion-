import Foundation
import CryptoKit
import Security

public struct PairedClient: Codable, Identifiable, Equatable {
    public let id: String
    public let name: String
    public let pairedAt: Date
}

public final class IdentityStore {
    private let service = "com.rosalina.accelerator"
    private let lock = NSLock()
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func serverPrivateKey() throws -> Curve25519.KeyAgreement.PrivateKey {
        lock.lock(); defer { lock.unlock() }
        if let data = readKeychain(account: "server.privateKey") {
            return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data)
        }
        let key = Curve25519.KeyAgreement.PrivateKey()
        try writeKeychain(account: "server.privateKey", data: key.rawRepresentation)
        return key
    }

    public func deviceID() throws -> String {
        lock.lock(); defer { lock.unlock() }
        if let data = readKeychain(account: "device.id"),
           let value = String(data: data, encoding: .utf8),
           !value.isEmpty {
            return value
        }
        let value = UUID().uuidString.lowercased()
        try writeKeychain(account: "device.id", data: Data(value.utf8))
        return value
    }

    public func saveClient(name: String, publicKey: Data, symmetricKey: SymmetricKey) throws -> PairedClient {
        lock.lock(); defer { lock.unlock() }
        let id = String(SHA256.hash(data: publicKey).compactMap { String(format: "%02x", $0) }.joined().prefix(24))
        let secret = symmetricKey.withUnsafeBytes { Data($0) }
        try writeKeychain(account: "client.\(id)", data: secret)

        var clients = loadClientsUnlocked()
        let client = PairedClient(id: id, name: name.isEmpty ? "Rosalina Phone" : String(name.prefix(80)), pairedAt: Date())
        clients.removeAll { $0.id == id }
        clients.append(client)
        persistClientsUnlocked(clients)
        return client
    }

    public func key(for clientID: String) -> SymmetricKey? {
        lock.lock(); defer { lock.unlock() }
        guard let data = readKeychain(account: "client.\(clientID)"), data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    public func clients() -> [PairedClient] {
        lock.lock(); defer { lock.unlock() }
        return loadClientsUnlocked().sorted { $0.pairedAt > $1.pairedAt }
    }

    public func forgetAllClients() {
        lock.lock(); defer { lock.unlock() }
        for client in loadClientsUnlocked() {
            deleteKeychain(account: "client.\(client.id)")
        }
        defaults.removeObject(forKey: "paired.clients")
    }

    private func loadClientsUnlocked() -> [PairedClient] {
        guard let data = defaults.data(forKey: "paired.clients") else { return [] }
        return (try? JSONDecoder.rosalina.decode([PairedClient].self, from: data)) ?? []
    }

    private func persistClientsUnlocked(_ clients: [PairedClient]) {
        if let data = try? JSONEncoder.rosalina.encode(clients) {
            defaults.set(data, forKey: "paired.clients")
        }
    }

    private func readKeychain(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else { return nil }
        return item as? Data
    }

    private func writeKeychain(account: String, data: Data) throws {
        deleteKeychain(account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueData as String: data
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw AcceleratorError.internalError("Keychain write failed: \(status)")
        }
    }

    private func deleteKeychain(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}
