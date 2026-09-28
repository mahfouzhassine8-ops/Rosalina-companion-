import Foundation

public enum AcceleratorProtocol {
    public static let version = 1
    public static let serviceType = "_rosalina-accel._tcp"
    public static let maxRequestBytes = 1_048_576
}

public struct HardwareProfile: Codable, Equatable {
    public let machineModel: String
    public let osVersion: String
    public let processorCount: Int
    public let activeProcessorCount: Int
    public let physicalMemoryBytes: UInt64
    public let gpuName: String
    public let gpuRecommendedWorkingSetBytes: UInt64
    public let gpuHasUnifiedMemory: Bool

    public init(
        machineModel: String,
        osVersion: String,
        processorCount: Int,
        activeProcessorCount: Int,
        physicalMemoryBytes: UInt64,
        gpuName: String,
        gpuRecommendedWorkingSetBytes: UInt64,
        gpuHasUnifiedMemory: Bool
    ) {
        self.machineModel = machineModel
        self.osVersion = osVersion
        self.processorCount = processorCount
        self.activeProcessorCount = activeProcessorCount
        self.physicalMemoryBytes = physicalMemoryBytes
        self.gpuName = gpuName
        self.gpuRecommendedWorkingSetBytes = gpuRecommendedWorkingSetBytes
        self.gpuHasUnifiedMemory = gpuHasUnifiedMemory
    }
}

public struct HelloResponse: Codable {
    public let protocolVersion: Int
    public let deviceID: String
    public let deviceName: String
    public let serverPublicKey: String
    public let pairingNonce: String
    public let pairingRequired: Bool
    public let capabilities: [String: Bool]
    public let hardware: HardwareProfile
}

public struct PairRequest: Codable {
    public let clientName: String
    public let clientPublicKey: String
    public let codeProof: String
}

public struct PairResponse: Codable {
    public let protocolVersion: Int
    public let clientID: String
    public let deviceID: String
    public let serverPublicKey: String
    public let serverProof: String
}

public struct EncryptedEnvelope: Codable {
    public let clientID: String
    public let sealedBox: String
}

public struct EncryptedEnvelopeResponse: Codable {
    public let sealedBox: String
}

public struct InnerRequest: Codable {
    public let requestID: String
    public let operation: String
    public let payload: [String: String]?
}

public struct InnerResponse: Codable {
    public let requestID: String
    public let ok: Bool
    public let payload: [String: String]?
    public let error: String?
}

public struct MetalProbeResult: Codable, Equatable {
    public let passed: Bool
    public let gpuName: String
    public let elements: Int
    public let elapsedMs: Double
    public let maxError: Float
}

public enum AcceleratorError: Error, LocalizedError {
    case invalidRequest(String)
    case unauthorized(String)
    case unavailable(String)
    case internalError(String)

    public var errorDescription: String? {
        switch self {
        case .invalidRequest(let value): return value
        case .unauthorized(let value): return value
        case .unavailable(let value): return value
        case .internalError(let value): return value
        }
    }
}

extension JSONEncoder {
    static let rosalina: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }()
}

extension JSONDecoder {
    static let rosalina = JSONDecoder()
}

extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
