import SwiftUI
import RosalinaAcceleratorCore

struct ContentView: View {
    @ObservedObject var controller: AcceleratorController

    private let background = Color(red: 0.045, green: 0.035, blue: 0.07)
    private let panel = Color(red: 0.11, green: 0.085, blue: 0.15)
    private let accent = Color(red: 0.76, green: 0.65, blue: 1.0)

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [background, Color(red: 0.08, green: 0.05, blue: 0.11)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    statusCard
                    pairingCard
                    hardwareCard
                    acceleratorCard
                    privacyCard
                }
                .padding(24)
            }
        }
        .preferredColorScheme(.dark)
    }

    private var header: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(accent.opacity(0.18)).frame(width: 56, height: 56)
                Image(systemName: "sparkles")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(accent)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("ROSALINA ACCELERATOR")
                    .font(.system(size: 25, weight: .semibold, design: .rounded))
                Text("PRIVATE MAC COMPANION · v0.1")
                    .font(.system(size: 11, weight: .medium, design: .rounded))
                    .tracking(1.5)
                    .foregroundStyle(accent)
            }
            Spacer()
        }
    }

    private var statusCard: some View {
        card {
            HStack {
                Circle()
                    .fill(controller.running ? Color.green : Color.orange)
                    .frame(width: 10, height: 10)
                Text(controller.running ? "Ready on your local network" : "Accelerator stopped")
                    .font(.headline)
                Spacer()
                if controller.running {
                    Text("Port \(controller.port)")
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }
            Text(controller.lastEvent)
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button(controller.running ? "Stop" : "Start") {
                    controller.running ? controller.stop() : controller.start()
                }
                .buttonStyle(.borderedProminent)
                Spacer()
                Text("No internet exposure is required")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var pairingCard: some View {
        card {
            Label("Pair Rosalina on your phone", systemImage: "iphone.and.arrow.forward")
                .font(.headline)
            Text("The phone will use this one-time code to authenticate a cryptographic pairing. The code changes after a successful pair.")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack {
                Text(controller.pairingCode)
                    .font(.system(size: 34, weight: .semibold, design: .monospaced))
                    .tracking(3)
                    .textSelection(.enabled)
                Spacer()
                Button("Copy") { controller.copyPairingCode() }
                Button("New Code") { controller.regeneratePairing() }
            }

            Divider()
            if controller.pairedClients.isEmpty {
                Text("No Rosalina phone is paired yet.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(controller.pairedClients) { client in
                    HStack {
                        Image(systemName: "checkmark.shield.fill").foregroundStyle(.green)
                        VStack(alignment: .leading) {
                            Text(client.name)
                            Text(client.id)
                                .font(.caption2.monospaced())
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text(client.pairedAt, style: .date)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Button("Forget all paired phones", role: .destructive) {
                    controller.forgetClients()
                }
            }
        }
    }

    private var hardwareCard: some View {
        card {
            Label("This Mac", systemImage: "laptopcomputer")
                .font(.headline)
            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 8) {
                row("Mac", controller.hardware.machineModel)
                row("macOS", controller.hardware.osVersion)
                row("CPU", "\(controller.hardware.activeProcessorCount) active / \(controller.hardware.processorCount) logical cores")
                row("Memory", bytes(controller.hardware.physicalMemoryBytes))
                row("Metal GPU", controller.hardware.gpuName)
                row("GPU working set", bytes(controller.hardware.gpuRecommendedWorkingSetBytes))
                row("Unified memory", controller.hardware.gpuHasUnifiedMemory ? "Yes" : "No")
            }
            .font(.system(size: 13, design: .rounded))
        }
    }

    private var acceleratorCard: some View {
        card {
            Label("Accelerator readiness", systemImage: "gauge.with.dots.needle.50percent")
                .font(.headline)
            Text("v0.1 proves the private Mac transport and the actual Metal compute path before we let Rosalina send personal photos or heavy model jobs.")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack {
                Button("Run Metal Probe") { controller.runMetalProbe() }
                    .buttonStyle(.borderedProminent)
                Button("Open Models Folder") { controller.openModelsFolder() }
            }

            HStack(alignment: .top, spacing: 8) {
                Image(systemName: controller.probePassed == true ? "checkmark.circle.fill" : controller.probePassed == false ? "xmark.circle.fill" : "circle.dotted")
                    .foregroundStyle(controller.probePassed == true ? .green : controller.probePassed == false ? .red : accent)
                Text(controller.probeText)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
            }

            Divider()
            capability("Status / hardware", ready: true)
            capability("Authenticated encrypted requests", ready: true)
            capability("Metal compute probe", ready: true)
            capability("Create inference", ready: false)
            capability("Photo-faithful Edit inference", ready: false)
            capability("Animate / Wan inference", ready: false)

            Text("Create/Edit/Animate are intentionally disabled until we prove the right Mac-native model pipeline. The current poor phone Edit pipeline is not being copied to the Mac.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var privacyCard: some View {
        card {
            Label("Privacy design", systemImage: "lock.shield")
                .font(.headline)
            Text("Pairing uses Curve25519 key agreement. After pairing, request bodies are encrypted with ChaChaPoly and every request requires a paired client key. Bonjour is used only for local discovery. v0.1 accepts only status, capability, and Metal-probe operations; it will reject Create, Edit, and Animate payloads.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("Rosalina's chat history, microphone audio, companion memory, and phone models stay on the phone.")
                .font(.caption)
                .foregroundStyle(accent)
        }
    }

    @ViewBuilder
    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            content()
        }
        .padding(18)
        .background(panel.opacity(0.94), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(accent.opacity(0.16), lineWidth: 1)
        )
    }

    private func row(_ label: String, _ value: String) -> some View {
        GridRow {
            Text(label).foregroundStyle(.secondary)
            Text(value).textSelection(.enabled)
        }
    }

    private func capability(_ label: String, ready: Bool) -> some View {
        HStack {
            Image(systemName: ready ? "checkmark.circle.fill" : "clock.badge.exclamationmark")
                .foregroundStyle(ready ? .green : .orange)
            Text(label)
            Spacer()
            Text(ready ? "Ready" : "Pending backend")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func bytes(_ value: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(clamping: value), countStyle: .memory)
    }
}
