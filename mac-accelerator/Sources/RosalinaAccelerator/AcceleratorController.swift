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

    private(set) var workspace: AcceleratorWorkspace?
    private var server: AcceleratorServer?
    private var didLaunch = false

    init() {
        LaunchLog.write("AcceleratorController init")
        do {
            let workspace = try AcceleratorWorkspace()
            self.workspace = workspace
            let server = try AcceleratorServer(workspace: workspace)
            self.server = server
            server.stateHandler = { [weak self] snapshot in
                DispatchQueue.main.async {
                    self?.apply(snapshot)
                }
            }
            lastEvent = "Ready to start"
            LaunchLog.write("Accelerator core initialized")
        } catch {
            lastEvent = "Startup repair needed: \(error.localizedDescription)"
            LaunchLog.write("Accelerator core init error: \(error)")
        }
    }

    func launchAfterUIReady() {
        guard !didLaunch else { return }
        didLaunch = true
        LaunchLog.write("SwiftUI content appeared")

        if CommandLine.arguments.contains("--launch-smoke") {
            lastEvent = "Launch smoke test"
            return
        }

        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 250_000_000)
            start()
        }
    }

    func start() {
        guard let server else {
            running = false
            lastEvent = "Accelerator core could not initialize. See ~/Library/Logs/Rosalina Accelerator/launch.log"
            LaunchLog.write("Start requested without server")
            return
        }
        do {
            try server.start()
            apply(server.currentSnapshot())
            LaunchLog.write("Local accelerator start requested")
        } catch {
            lastEvent = "Could not start local accelerator: \(error.localizedDescription)"
            LaunchLog.write("Server start error: \(error)")
        }
    }

    func stop() {
        guard let server else { return }
        server.stop()
        apply(server.currentSnapshot())
    }

    func regeneratePairing() {
        guard let server else { return }
        server.regeneratePairingCode()
        apply(server.currentSnapshot())
    }

    func forgetClients() {
        guard let server else { return }
        server.forgetAllClients()
        apply(server.currentSnapshot())
    }

    func copyPairingCode() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pairingCode, forType: .string)
        lastEvent = "Pairing code copied"
    }

    func openModelsFolder() {
        guard let workspace else {
            lastEvent = "Models folder is unavailable because startup did not finish"
            return
        }
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
