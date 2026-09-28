import Foundation
import CryptoKit
import Security

public struct PairingSession {
    public let code: String
    public let nonce: Data

    public var displayCode: String {
        guard code.count == 10 else { return code }
        let split = code.index(code.startIndex, offsetBy: 5)
        return String(code[..<split]) + "-" + String(code[split...])
    }

    public init(code: String, nonce: Data) {
        self.code = code
        self.nonce = nonce
    }

    public static func fresh() -> PairingSession {
        var bytes = [UInt8](repeating: 0, count: 8)
        let result = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(result == errSecSuccess)
        let value = bytes.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } % 10_000_000_000
        let code = String(format: "%010llu", value)

        var nonce = [UInt8](repeating: 0, count: 32)
        let nonceResult = SecRandomCopyBytes(kSecRandomDefault, nonce.count, &nonce)
        precondition(nonceResult == errSecSuccess)
        return PairingSession(code: code, nonce: Data(nonce))
    }

    public func verifyProof(_ proofBase64: String, serverPublicKey: Data, clientPublicKey: Data) -> Bool {
        guard let supplied = Data(base64Encoded: proofBase64) else { return false }
        let codeKey = SymmetricKey(data: Data(SHA256.hash(data: Data(code.utf8))))
        var transcript = Data()
        transcript.append(serverPublicKey)
        transcript.append(clientPublicKey)
        transcript.append(nonce)
        let expected = Data(HMAC<SHA256>.authenticationCode(for: transcript, using: codeKey))
        return constantTimeEqual(supplied, expected)
    }

    private func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for index in a.indices {
            diff |= a[index] ^ b[index]
        }
        return diff == 0
    }
}

public enum PairingCrypto {
    public static func deriveSessionKey(
        serverPrivateKey: Curve25519.KeyAgreement.PrivateKey,
        clientPublicKey: Curve25519.KeyAgreement.PublicKey,
        pairingNonce: Data,
        clientIDMaterial: Data
    ) throws -> SymmetricKey {
        let secret = try serverPrivateKey.sharedSecretFromKeyAgreement(with: clientPublicKey)
        return secret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: pairingNonce,
            sharedInfo: clientIDMaterial,
            outputByteCount: 32
        )
    }

    public static func serverProof(key: SymmetricKey, deviceID: String, clientID: String) -> String {
        let data = Data("rosalina-pair-v1|\(deviceID)|\(clientID)".utf8)
        return Data(HMAC<SHA256>.authenticationCode(for: data, using: key)).base64EncodedString()
    }

    public static func seal<T: Encodable>(_ value: T, using key: SymmetricKey) throws -> String {
        let clear = try JSONEncoder.rosalina.encode(value)
        let box = try ChaChaPoly.seal(clear, using: key)
        guard let combined = box.combined else {
            throw AcceleratorError.internalError("Could not create encrypted envelope")
        }
        return combined.base64EncodedString()
    }

    public static func open<T: Decodable>(_ type: T.Type, sealedBox: String, using key: SymmetricKey) throws -> T {
        guard let data = Data(base64Encoded: sealedBox) else {
            throw AcceleratorError.invalidRequest("Encrypted envelope is not valid Base64")
        }
        let box = try ChaChaPoly.SealedBox(combined: data)
        let clear = try ChaChaPoly.open(box, using: key)
        return try JSONDecoder.rosalina.decode(type, from: clear)
    }
}
