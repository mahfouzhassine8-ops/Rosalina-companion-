import XCTest
import CryptoKit
@testable import RosalinaAcceleratorCore

final class RosalinaAcceleratorCoreTests: XCTestCase {
    func testPairingCodeIsTenDigits() {
        let session = PairingSession.fresh()
        XCTAssertEqual(session.code.count, 10)
        XCTAssertNotNil(UInt64(session.code))
        XCTAssertEqual(session.displayCode.count, 11)
        XCTAssertEqual(session.displayCode[session.displayCode.index(session.displayCode.startIndex, offsetBy: 5)], "-")
    }

    func testPairingProofAndEncryptedEnvelopeRoundTrip() throws {
        let server = Curve25519.KeyAgreement.PrivateKey()
        let client = Curve25519.KeyAgreement.PrivateKey()
        let session = PairingSession(code: "1234567890", nonce: Data(repeating: 7, count: 32))

        var transcript = Data()
        transcript.append(server.publicKey.rawRepresentation)
        transcript.append(client.publicKey.rawRepresentation)
        transcript.append(session.nonce)
        let codeKey = SymmetricKey(data: Data(SHA256.hash(data: Data(session.code.utf8))))
        let proof = Data(HMAC<SHA256>.authenticationCode(for: transcript, using: codeKey)).base64EncodedString()

        XCTAssertTrue(session.verifyProof(
            proof,
            serverPublicKey: server.publicKey.rawRepresentation,
            clientPublicKey: client.publicKey.rawRepresentation
        ))

        let material = Data(SHA256.hash(data: client.publicKey.rawRepresentation))
        let serverKey = try PairingCrypto.deriveSessionKey(
            serverPrivateKey: server,
            clientPublicKey: client.publicKey,
            pairingNonce: session.nonce,
            clientIDMaterial: material
        )
        let clientSecret = try client.sharedSecretFromKeyAgreement(with: server.publicKey)
        let clientKey = clientSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: session.nonce,
            sharedInfo: material,
            outputByteCount: 32
        )

        let request = InnerRequest(requestID: "test-1", operation: "status", payload: nil)
        let sealed = try PairingCrypto.seal(request, using: clientKey)
        let opened = try PairingCrypto.open(InnerRequest.self, sealedBox: sealed, using: serverKey)
        XCTAssertEqual(opened.requestID, "test-1")
        XCTAssertEqual(opened.operation, "status")
    }

    func testMetalProbeUsesFullProductionSizeOnAppleSilicon() throws {
        #if arch(arm64)
        let result = try MetalProbe.run(elements: 65_536)
        XCTAssertTrue(result.passed)
        XCTAssertEqual(result.storageMode, "shared")
        XCTAssertLessThan(result.constantMaxError, 0.0001)
        XCTAssertLessThan(result.vectorMaxError, 0.0001)
        XCTAssertNil(result.firstMismatchIndex)
        #endif
    }
}
