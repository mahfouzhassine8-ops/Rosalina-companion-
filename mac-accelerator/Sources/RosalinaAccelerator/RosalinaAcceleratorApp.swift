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
        }
        .windowResizability(.contentMinSize)

        MenuBarExtra("Rosalina Accelerator", systemImage: "sparkles") {
            VStack(alignment: .leading, spacing: 10) {
                Text(controller.running ? "Rosalina Accelerator is ready" : "Rosalina Accelerator is stopped")
                Text(controller.hardware.gpuName)
                    .font(.caption)
                Divider()
                Button(controller.running ? "Stop Accelerator" : "Start Accelerator") {
                    controller.running ? controller.stop() : controller.start()
                }
                Button("Show Pairing Code") {
                    NSApp.activate(ignoringOtherApps: true)
                    NSApp.windows.first?.makeKeyAndOrderFront(nil)
                }
                Divider()
                Button("Quit") { NSApp.terminate(nil) }
            }
            .padding(8)
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}
