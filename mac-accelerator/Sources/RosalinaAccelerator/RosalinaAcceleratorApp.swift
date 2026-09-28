import AppKit
import Foundation
import RosalinaAcceleratorCore

enum LaunchLog {
    static func write(_ message: String) {
        let fm = FileManager.default
        guard let base = try? fm.url(
            for: .libraryDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ) else { return }
        let dir = base.appendingPathComponent("Logs/Rosalina Accelerator", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("launch.log")
        let line = "\(ISO8601DateFormatter().string(from: Date())) \(message)\n"
        if fm.fileExists(atPath: file.path),
           let handle = try? FileHandle(forWritingTo: file) {
            defer { try? handle.close() }
            try? handle.seekToEnd()
            try? handle.write(contentsOf: Data(line.utf8))
        } else {
            try? Data(line.utf8).write(to: file, options: .atomic)
        }
    }
}

@main
struct RosalinaAcceleratorMain {
    static func main() {
        LaunchLog.write("main entered")
        let app = NSApplication.shared
        app.setActivationPolicy(.regular)
        let delegate = AppDelegate()
        app.delegate = delegate
        LaunchLog.write("NSApplication configured")
        app.run()
        LaunchLog.write("NSApplication run returned")
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow?
    private var statusLabel: NSTextField?
    private var hardwareLabel: NSTextField?
    private var probeLabel: NSTextField?
    private var pairingLabel: NSTextField?
    private var server: AcceleratorServer?
    private var workspace: AcceleratorWorkspace?

    func applicationWillFinishLaunching(_ notification: Notification) {
        LaunchLog.write("applicationWillFinishLaunching")
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        LaunchLog.write("applicationDidFinishLaunching")

        if CommandLine.arguments.contains("--launch-smoke") {
            LaunchLog.write("launch-smoke passed")
            NSApp.terminate(nil)
            return
        }

        buildWindow()
        LaunchLog.write("window built")
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        LaunchLog.write("window shown")

        DispatchQueue.global(qos: .utility).async { [weak self] in
            let profile = SystemProfiler.current()
            DispatchQueue.main.async {
                self?.hardwareLabel?.stringValue =
                    "\(profile.machineModel)  •  \(profile.gpuName)  •  " +
                    ByteCountFormatter.string(fromByteCount: Int64(clamping: profile.physicalMemoryBytes), countStyle: .memory)
                LaunchLog.write("hardware profile loaded")
            }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    private func buildWindow() {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 820, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Rosalina Accelerator"
        window.center()
        window.minSize = NSSize(width: 720, height: 520)

        let content = NSView()
        content.translatesAutoresizingMaskIntoConstraints = false
        window.contentView = content

        let title = label("ROSALINA ACCELERATOR", size: 26, weight: .semibold)
        let subtitle = label("PRIVATE MAC COMPANION · v0.1.3", size: 12, weight: .medium)
        subtitle.textColor = .secondaryLabelColor

        let status = label("Mac app launched successfully. Accelerator is stopped.", size: 15, weight: .medium)
        statusLabel = status

        let hardware = label("Reading this Mac…", size: 13, weight: .regular)
        hardware.textColor = .secondaryLabelColor
        hardwareLabel = hardware

        let pairing = label("Pairing code will appear after you start the local accelerator.", size: 20, weight: .semibold)
        pairingLabel = pairing

        let startButton = NSButton(title: "Start Local Accelerator", target: self, action: #selector(startAccelerator))
        startButton.bezelStyle = .rounded
        startButton.controlSize = .large

        let probeButton = NSButton(title: "Run Metal Probe", target: self, action: #selector(runMetalProbe))
        probeButton.bezelStyle = .rounded
        probeButton.controlSize = .large

        let modelsButton = NSButton(title: "Open Models Folder", target: self, action: #selector(openModelsFolder))
        modelsButton.bezelStyle = .rounded

        let probe = label("Metal probe has not been run yet.", size: 13, weight: .regular)
        probe.textColor = .secondaryLabelColor
        probeLabel = probe

        let privacy = label(
            "v0.1.3 intentionally does not accept personal photo jobs yet. Create/Edit/Animate stay disabled until the Mac-native image pipeline passes fidelity testing.",
            size: 12,
            weight: .regular
        )
        privacy.textColor = .secondaryLabelColor
        privacy.maximumNumberOfLines = 0

        let buttons = NSStackView(views: [startButton, probeButton, modelsButton])
        buttons.orientation = .horizontal
        buttons.spacing = 10
        buttons.alignment = .centerY

        let stack = NSStackView(views: [title, subtitle, divider(), status, hardware, pairing, buttons, probe, divider(), privacy])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false

        content.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 28),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -28),
            stack.topAnchor.constraint(equalTo: content.topAnchor, constant: 26),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: content.bottomAnchor, constant: -26),
            title.widthAnchor.constraint(lessThanOrEqualTo: stack.widthAnchor),
            status.widthAnchor.constraint(equalTo: stack.widthAnchor),
            hardware.widthAnchor.constraint(equalTo: stack.widthAnchor),
            pairing.widthAnchor.constraint(equalTo: stack.widthAnchor),
            probe.widthAnchor.constraint(equalTo: stack.widthAnchor),
            privacy.widthAnchor.constraint(equalTo: stack.widthAnchor)
        ])

        self.window = window
    }

    private func label(_ text: String, size: CGFloat, weight: NSFont.Weight) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = NSFont.systemFont(ofSize: size, weight: weight)
        field.lineBreakMode = .byWordWrapping
        field.maximumNumberOfLines = 0
        return field
    }

    private func divider() -> NSBox {
        let box = NSBox()
        box.boxType = .separator
        return box
    }

    @objc private func startAccelerator() {
        statusLabel?.stringValue = "Starting local accelerator…"
        LaunchLog.write("start accelerator clicked")
        do {
            if server == nil {
                let workspace = try AcceleratorWorkspace()
                self.workspace = workspace
                let server = try AcceleratorServer(workspace: workspace)
                server.stateHandler = { [weak self] snapshot in
                    DispatchQueue.main.async {
                        self?.statusLabel?.stringValue = snapshot.lastEvent
                        self?.pairingLabel?.stringValue = "Pairing code: \(snapshot.pairingCode)"
                    }
                }
                self.server = server
            }
            try server?.start()
            if let snapshot = server?.currentSnapshot() {
                statusLabel?.stringValue = snapshot.lastEvent
                pairingLabel?.stringValue = "Pairing code: \(snapshot.pairingCode)"
            }
        } catch {
            statusLabel?.stringValue = "Could not start accelerator: \(error.localizedDescription)"
            LaunchLog.write("accelerator start error: \(error)")
        }
    }

    @objc private func runMetalProbe() {
        probeLabel?.stringValue = "Testing Metal GPU with shared buffers…"
        LaunchLog.write("metal probe clicked")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let result = try MetalProbe.run()
                DispatchQueue.main.async {
                    let verdict = result.passed ? "PASS" : "FAIL"
                    var detail = String(
                        format: "%@ · %@ · %,d elements · %.2f ms · constant err %.6f · vector err %.6f",
                        verdict,
                        result.gpuName,
                        result.elements,
                        result.elapsedMs,
                        result.constantMaxError,
                        result.vectorMaxError
                    )
                    if let index = result.firstMismatchIndex,
                       let expected = result.firstMismatchExpected,
                       let actual = result.firstMismatchActual {
                        detail += String(
                            format: " · first mismatch #%d expected %.6f actual %.6f",
                            index,
                            expected,
                            actual
                        )
                    }
                    self?.probeLabel?.stringValue = detail
                    LaunchLog.write("metal probe \(verdict.lowercased()) · max error \(result.maxError)")
                }
            } catch {
                DispatchQueue.main.async {
                    self?.probeLabel?.stringValue = "Metal probe failed: \(error.localizedDescription)"
                    LaunchLog.write("metal probe failed: \(error)")
                }
            }
        }
    }

    @objc private func openModelsFolder() {
        do {
            if workspace == nil {
                workspace = try AcceleratorWorkspace()
            }
            if let models = workspace?.models {
                NSWorkspace.shared.open(models)
            }
        } catch {
            statusLabel?.stringValue = "Models folder unavailable: \(error.localizedDescription)"
            LaunchLog.write("models folder error: \(error)")
        }
    }
}
