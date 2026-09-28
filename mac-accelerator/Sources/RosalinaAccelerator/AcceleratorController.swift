import Foundation
import SwiftUI
import AppKit
import RosalinaAcceleratorCore

@MainActor
final class AcceleratorController: ObservableObject {
    @Published var running = false
    @Published var port: UInt16 = 0
    @Published var pairingCode = "----- -----"
    @Published var pairedClients: [PairedClient] = []
    @Published var lastEvent = "Starting…"
    @Published var probeText = "Metal probe not run yet"
    @Published var probePassed: Bool? = nil
    let hardware = SystemProfiler.current()
    let workspace: AcceleratorWorkspace

    private let server: AcceleratorServer

    init() {
        do {
            let workspace = try AcceleratorWorkspace()
            self.workspace = workspace
            self.server = try AcceleratorServer(workspace: workspace)
            self.server.stateHandler = { [weak self] snapshot in
                DispatchQueue.main.async {
                    self?.apply(snapshot)
                }
            }
            start()
        } catch {
            fatalError("Rosalina Accelerator could not initialize: \(error)")
        }
    }

    func start() {
        do {
            try server.start()
            apply(server.currentSnapshot())
        } catch {
            lastEvent = "Could not start: \(error.localizedDescription)"
        }
    }

    func stop() {
        server.stop()
        apply(server.currentSnapshot())
    }

    func regeneratePairing() {
        server.regeneratePairingCode()
        apply(server.currentSnapshot())
    }

    func forgetClients() {
        server.forgetAllClients()
        apply(server.currentSnapshot())
    }

    func copyPairingCode() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pairingCode, forType: .string)
        lastEvent = "Pairing code copied"
    }

    func openModelsFolder() {
        NSWorkspace.shared.open(workspace.models)
    }

    func runMetalProbe() {
        probeText = "Testing the M5 Metal path…"
        probePassed = nil
        Task.detached {
            do {
                let result = try MetalProbe.run()
                await MainActor.run {
                    self.probePassed = result.passed
                    self.probeText = String(
                        format: "%@ · %,d elements · %.2f ms · max error %.6f",
                        result.gpuName,
                        result.elements,
                        result.elapsedMs,
                        result.maxError
                    )
                }
            } catch {
                await MainActor.run {
                    self.probePassed = false
                    self.probeText = error.localizedDescription
                }
            }
        }
    }

    private func apply(_ snapshot: ServerSnapshot) {
        running = snapshot.running
        port = snapshot.port
        pairingCode = snapshot.pairingCode
        pairedClients = snapshot.pairedClients
        lastEvent = snapshot.lastEvent
    }
}
