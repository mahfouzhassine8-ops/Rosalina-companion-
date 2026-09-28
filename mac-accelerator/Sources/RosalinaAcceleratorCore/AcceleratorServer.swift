import Foundation
import Network
import CryptoKit

public struct ServerSnapshot: Equatable {
    public let running: Bool
    public let port: UInt16
    public let pairingCode: String
    public let pairedClients: [PairedClient]
    public let lastEvent: String

    public init(running: Bool, port: UInt16, pairingCode: String, pairedClients: [PairedClient], lastEvent: String) {
        self.running = running
        self.port = port
        self.pairingCode = pairingCode
        self.pairedClients = pairedClients
        self.lastEvent = lastEvent
    }
}

public final class AcceleratorServer {
    public typealias StateHandler = (ServerSnapshot) -> Void

    private let identity: IdentityStore
    private let workspace: AcceleratorWorkspace
    private let queue = DispatchQueue(label: "com.rosalina.accelerator.network", qos: .userInitiated)
    private let lock = NSLock()
    private var listener: NWListener?
    private var pairing = PairingSession.fresh()
    private var port: UInt16 = 0
    private var lastEvent = "Not started"
    private var recentRequestIDs: [String] = []
    private var recentRequestSet = Set<String>()
    public var stateHandler: StateHandler?

    public init(identity: IdentityStore = IdentityStore(), workspace: AcceleratorWorkspace? = nil) throws {
        self.identity = identity
        self.workspace = try workspace ?? AcceleratorWorkspace()
        self.workspace.cleanupExpiredJobs()
    }

    public func start() throws {
        lock.lock()
        if listener != nil { lock.unlock(); return }
        lock.unlock()

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let newListener = try NWListener(using: parameters, on: .any)
        newListener.service = NWListener.Service(name: "Rosalina Accelerator", type: AcceleratorProtocol.serviceType)
        newListener.stateUpdateHandler = { [weak self, weak newListener] state in
            guard let self else { return }
            switch state {
            case .ready:
                let value = newListener?.port?.rawValue ?? 0
                self.updateState(running: true, port: value, event: "Ready on your local network")
            case .failed(let error):
                self.updateState(running: false, port: 0, event: "Server failed: \(error.localizedDescription)")
                newListener?.cancel()
                self.lock.lock(); self.listener = nil; self.lock.unlock()
            case .cancelled:
                self.updateState(running: false, port: 0, event: "Stopped")
            default:
                break
            }
        }
        newListener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }

        lock.lock()
        listener = newListener
        lock.unlock()
        newListener.start(queue: queue)
        publish()
    }

    public func stop() {
        lock.lock()
        let current = listener
        listener = nil
        lock.unlock()
        current?.cancel()
        updateState(running: false, port: 0, event: "Stopped")
    }

    public func regeneratePairingCode() {
        lock.lock()
        pairing = .fresh()
        lastEvent = "New pairing code generated"
        lock.unlock()
        publish()
    }

    public func forgetAllClients() {
        identity.forgetAllClients()
        regeneratePairingCode()
        updateState(event: "All paired phones forgotten")
    }

    public func currentSnapshot() -> ServerSnapshot {
        lock.lock(); defer { lock.unlock() }
        return ServerSnapshot(
            running: listener != nil && port != 0,
            port: port,
            pairingCode: pairing.displayCode,
            pairedClients: identity.clients(),
            lastEvent: lastEvent
        )
    }

    private func handle(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            if case .ready = state, let connection {
                self?.receive(on: connection, buffer: Data())
            }
        }
        connection.start(queue: queue)
    }

    private func receive(on connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            var next = buffer
            if let data { next.append(data) }

            if next.count > AcceleratorProtocol.maxRequestBytes {
                self.send(status: 413, json: ["error": "Request too large"], on: connection)
                return
            }

            if let request = self.parseRequest(next) {
                self.route(request, connection: connection)
                return
            }

            if complete || error != nil {
                self.send(status: 400, json: ["error": "Incomplete HTTP request"], on: connection)
                return
            }
            self.receive(on: connection, buffer: next)
        }
    }

    private struct HTTPRequest {
        let method: String
        let path: String
        let body: Data
    }

    private func parseRequest(_ data: Data) -> HTTPRequest? {
        let marker = Data("\r\n\r\n".utf8)
        guard let headerRange = data.range(of: marker) else { return nil }
        let headerData = data[..<headerRange.lowerBound]
        guard let header = String(data: headerData, encoding: .utf8) else { return nil }
        let lines = header.components(separatedBy: "\r\n")
        guard let first = lines.first else { return nil }
        let firstParts = first.split(separator: " ")
        guard firstParts.count >= 2 else { return nil }
        let method = String(firstParts[0]).uppercased()
        let path = String(firstParts[1])

        var contentLength = 0
        for line in lines.dropFirst() {
            let pair = line.split(separator: ":", maxSplits: 1)
            if pair.count == 2 && pair[0].trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "content-length" {
                contentLength = Int(pair[1].trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
            }
        }

        let bodyStart = headerRange.upperBound
        guard data.count >= bodyStart + contentLength else { return nil }
        let body = data.subdata(in: bodyStart..<(bodyStart + contentLength))
        return HTTPRequest(method: method, path: path, body: body)
    }

    private func route(_ request: HTTPRequest, connection: NWConnection) {
        do {
            switch (request.method, request.path) {
            case ("GET", "/v1/hello"):
                let key = try identity.serverPrivateKey()
                let pair = lockedPairing()
                let response = HelloResponse(
                    protocolVersion: AcceleratorProtocol.version,
                    deviceID: try identity.deviceID(),
                    deviceName: Host.current().localizedName ?? "Rosalina Mac",
                    serverPublicKey: key.publicKey.rawRepresentation.base64EncodedString(),
                    pairingNonce: pair.nonce.base64EncodedString(),
                    pairingRequired: identity.clients().isEmpty,
                    capabilities: [
                        "status": true,
                        "metalProbe": true,
                        "create": false,
                        "edit": false,
                        "animate": false
                    ],
                    hardware: SystemProfiler.current()
                )
                try sendEncodable(status: 200, value: response, on: connection)

            case ("POST", "/v1/pair"):
                let pairRequest = try JSONDecoder.rosalina.decode(PairRequest.self, from: request.body)
                let response = try pair(pairRequest)
                try sendEncodable(status: 200, value: response, on: connection)

            case ("POST", "/v1/envelope"):
                let outer = try JSONDecoder.rosalina.decode(EncryptedEnvelope.self, from: request.body)
                let response = try handleEnvelope(outer)
                try sendEncodable(status: 200, value: response, on: connection)

            default:
                send(status: 404, json: ["error": "Unknown Rosalina Accelerator endpoint"], on: connection)
            }
        } catch {
            let status = (error as? AcceleratorError).map {
                switch $0 {
                case .unauthorized: return 401
                case .invalidRequest: return 400
                case .unavailable: return 503
                case .internalError: return 500
                }
            } ?? 400
            send(status: status, json: ["error": error.localizedDescription], on: connection)
        }
    }

    private func pair(_ request: PairRequest) throws -> PairResponse {
        guard let clientData = Data(base64Encoded: request.clientPublicKey),
              let clientKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: clientData) else {
            throw AcceleratorError.invalidRequest("Client public key is invalid")
        }
        let serverKey = try identity.serverPrivateKey()
        let pair = lockedPairing()

        guard pair.verifyProof(
            request.codeProof,
            serverPublicKey: serverKey.publicKey.rawRepresentation,
            clientPublicKey: clientData
        ) else {
            throw AcceleratorError.unauthorized("Pairing code proof did not match")
        }

        let material = Data(SHA256.hash(data: clientData))
        let key = try PairingCrypto.deriveSessionKey(
            serverPrivateKey: serverKey,
            clientPublicKey: clientKey,
            pairingNonce: pair.nonce,
            clientIDMaterial: material
        )
        let client = try identity.saveClient(name: request.clientName, publicKey: clientData, symmetricKey: key)
        let deviceID = try identity.deviceID()
        let response = PairResponse(
            protocolVersion: AcceleratorProtocol.version,
            clientID: client.id,
            deviceID: deviceID,
            serverPublicKey: serverKey.publicKey.rawRepresentation.base64EncodedString(),
            serverProof: PairingCrypto.serverProof(key: key, deviceID: deviceID, clientID: client.id)
        )

        lock.lock()
        pairing = .fresh()
        lastEvent = "Paired with \(client.name)"
        lock.unlock()
        publish()
        return response
    }

    private func handleEnvelope(_ envelope: EncryptedEnvelope) throws -> EncryptedEnvelopeResponse {
        guard let key = identity.key(for: envelope.clientID) else {
            throw AcceleratorError.unauthorized("This phone is not paired")
        }
        let request = try PairingCrypto.open(InnerRequest.self, sealedBox: envelope.sealedBox, using: key)
        guard !request.requestID.isEmpty && request.requestID.count <= 100 else {
            throw AcceleratorError.invalidRequest("Request ID is invalid")
        }
        guard remember(requestID: request.requestID) else {
            throw AcceleratorError.invalidRequest("Duplicate request was rejected")
        }

        let response: InnerResponse
        switch request.operation {
        case "status":
            let hardware = SystemProfiler.current()
            response = InnerResponse(
                requestID: request.requestID,
                ok: true,
                payload: [
                    "machineModel": hardware.machineModel,
                    "gpuName": hardware.gpuName,
                    "physicalMemoryBytes": String(hardware.physicalMemoryBytes),
                    "gpuRecommendedWorkingSetBytes": String(hardware.gpuRecommendedWorkingSetBytes),
                    "modelsDirectory": workspace.models.path,
                    "modelFileCount": String(workspace.modelFileCount())
                ],
                error: nil
            )
        case "metalProbe":
            let probe = try MetalProbe.run()
            response = InnerResponse(
                requestID: request.requestID,
                ok: probe.passed,
                payload: [
                    "gpuName": probe.gpuName,
                    "elements": String(probe.elements),
                    "elapsedMs": String(format: "%.3f", probe.elapsedMs),
                    "maxError": String(probe.maxError)
                ],
                error: probe.passed ? nil : "Metal verification did not pass"
            )
        case "capabilities":
            response = InnerResponse(
                requestID: request.requestID,
                ok: true,
                payload: [
                    "status": "true",
                    "metalProbe": "true",
                    "create": "false",
                    "edit": "false",
                    "animate": "false",
                    "note": "Mac transport is ready; inference backends are intentionally not enabled in v0.1."
                ],
                error: nil
            )
        case "create", "edit", "animate":
            response = InnerResponse(
                requestID: request.requestID,
                ok: false,
                payload: nil,
                error: "Inference backend is not installed yet. No photo or prompt was processed."
            )
        default:
            throw AcceleratorError.invalidRequest("Unknown encrypted operation")
        }

        updateState(event: "Handled \(request.operation) from paired Rosalina")
        return EncryptedEnvelopeResponse(sealedBox: try PairingCrypto.seal(response, using: key))
    }

    private func remember(requestID: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if recentRequestSet.contains(requestID) { return false }
        recentRequestIDs.append(requestID)
        recentRequestSet.insert(requestID)
        if recentRequestIDs.count > 256 {
            let removed = recentRequestIDs.removeFirst()
            recentRequestSet.remove(removed)
        }
        return true
    }

    private func lockedPairing() -> PairingSession {
        lock.lock(); defer { lock.unlock() }
        return pairing
    }

    private func updateState(running: Bool? = nil, port: UInt16? = nil, event: String? = nil) {
        lock.lock()
        if let port { self.port = port }
        if let event { self.lastEvent = event }
        if running == false { self.port = 0 }
        lock.unlock()
        publish()
    }

    private func publish() {
        guard let handler = stateHandler else { return }
        handler(currentSnapshot())
    }

    private func sendEncodable<T: Encodable>(status: Int, value: T, on connection: NWConnection) throws {
        let data = try JSONEncoder.rosalina.encode(value)
        send(status: status, body: data, on: connection)
    }

    private func send(status: Int, json: [String: String], on connection: NWConnection) {
        let data = (try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys])) ?? Data("{}".utf8)
        send(status: status, body: data, on: connection)
    }

    private func send(status: Int, body: Data, on connection: NWConnection) {
        let reason: String
        switch status {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 401: reason = "Unauthorized"
        case 404: reason = "Not Found"
        case 413: reason = "Payload Too Large"
        case 503: reason = "Service Unavailable"
        default: reason = "Error"
        }
        let header = "HTTP/1.1 \(status) \(reason)\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        var packet = Data(header.utf8)
        packet.append(body)
        connection.send(content: packet, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }
}
