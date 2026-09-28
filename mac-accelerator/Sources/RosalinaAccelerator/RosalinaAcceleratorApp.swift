import SwiftUI
import AppKit
import RosalinaAcceleratorCore

@main
struct RosalinaAcceleratorApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var controller = AcceleratorController()

    var body: some Scene {
        WindowGroup("Rosalina Accelerator") {
            ContentView(controller: controller)
                .frame(minWidth: 720, minHeight: 600)
                .task {
                    controller.launchAfterUIReady()
                }
        }
        .windowResizability(.contentMinSize)
    }
}

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

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillFinishLaunching(_ notification: Notification) {
        LaunchLog.write("applicationWillFinishLaunching")
        NSApp.setActivationPolicy(.regular)
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        LaunchLog.write("applicationDidFinishLaunching")
        NSApp.activate(ignoringOtherApps: true)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            NSApp.windows.first?.makeKeyAndOrderFront(nil)
        }

        if CommandLine.arguments.contains("--launch-smoke") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                LaunchLog.write("launch-smoke passed")
                NSApp.terminate(nil)
            }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}
