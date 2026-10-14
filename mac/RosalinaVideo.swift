import SwiftUI
import AppKit
import AVKit
import UniformTypeIdentifiers

private enum Config {
    static let model = "ltx-2.3-mlx-q4"
    static let baseURL = URL(string: "http://127.0.0.1:8000")!
    static let modelDownloadGB = "22.8 GB"
    static let applicationDirectory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Rosalina Video", isDirectory: true)
    }()
    static let engineBinary: URL = applicationDirectory
        .appendingPathComponent("venv/bin/rapid-mlx")
}

@main
struct RosalinaVideoApp: App {
    @StateObject private var model = VideoViewModel()

    var body: some Scene {
        WindowGroup {
            VideoScreen()
                .environmentObject(model)
                .frame(minWidth: 1000, minHeight: 700)
                .onAppear { model.checkConnection() }
        }
        .windowStyle(.titleBar)
        .commands {
            CommandGroup(replacing: .newItem) { }
        }
    }
}

@MainActor
final class VideoViewModel: ObservableObject {
    @Published var inputImage: NSImage?
    @Published var inputName = "No photo selected"
    @Published var instruction = ""
    @Published var duration = 2
    @Published var frameSize = "512x512"
    @Published var fidelity = 0.85
    @Published var preserveAppearance = true
    @Published var isConnected = false
    @Published var isStartingEngine = false
    @Published var isGenerating = false
    @Published var status = "Engine not checked"
    @Published var jobStatus = ""
    @Published var outputURL: URL?
    @Published var errorText: String?
    @Published var engineLogURL: URL?

    private var engineProcess: Process?
    private var engineLogHandle: FileHandle?
    private var generationTask: Task<Void, Never>?
    private var engineWatchTask: Task<Void, Never>?
    private var currentJobID: String?

    var canGenerate: Bool {
        inputImage != nil && !instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && isConnected && !isGenerating
    }

    var engineIsInstalled: Bool {
        FileManager.default.isExecutableFile(atPath: Config.engineBinary.path)
    }

    var engineIsOwned: Bool {
        engineProcess?.isRunning == true
    }

    func selectPhoto() {
        let panel = NSOpenPanel()
        panel.title = "Choose the photo to animate"
        panel.allowedContentTypes = [.png, .jpeg, .heic, .tiff, .bmp, .gif]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        if panel.runModal() != .OK { return }
        guard let url = panel.url, let image = NSImage(contentsOf: url),
              image.size.width > 0, image.size.height > 0 else {
            errorText = "This picture could not be opened. Try a PNG or JPG."
            return
        }
        inputImage = image
        inputName = url.lastPathComponent
        outputURL = nil
        errorText = nil
        let aspect = image.size.width / image.size.height
        frameSize = aspect > 1.18 ? "768x512" : (aspect < 0.85 ? "512x768" : "512x512")
    }

    func installEngine() {
        guard let script = Bundle.main.url(forResource: "InstallEngine", withExtension: "command") else {
            errorText = "The engine installer is missing from this app."
            return
        }
        NSWorkspace.shared.open(script)
        status = "Finish installation in Terminal, then select Start Local Engine."
    }

    func checkConnection() {
        Task { await refreshConnection() }
    }

    func startEngine() {
        errorText = nil
        if engineProcess?.isRunning == true {
            status = "Rosalina started an engine already."
            checkConnection()
            return
        }
        guard engineIsInstalled else {
            errorText = "Install the local engine first using the Install Engine button."
            return
        }
        do {
            try FileManager.default.createDirectory(
                at: Config.applicationDirectory, withIntermediateDirectories: true)
            let log = Config.applicationDirectory.appendingPathComponent("engine.log")
            if !FileManager.default.fileExists(atPath: log.path) {
                FileManager.default.createFile(atPath: log.path, contents: nil)
            }
            let handle = try FileHandle(forWritingTo: log)
            try handle.truncate(atOffset: 0)
            let process = Process()
            process.executableURL = Config.engineBinary
            process.arguments = ["serve", Config.model]
            process.currentDirectoryURL = Config.applicationDirectory
            var env = ProcessInfo.processInfo.environment
            env["PATH"] = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
            env["HF_HUB_DISABLE_TELEMETRY"] = "1"
            process.environment = env
            process.standardOutput = handle
            process.standardError = handle
            try process.run()
            engineProcess = process
            engineLogHandle = handle
            engineLogURL = log
            status = "Starting LTX-2.3. First launch downloads the \(Config.modelDownloadGB) model; check the log."
            isStartingEngine = true
            engineWatchTask?.cancel()
            engineWatchTask = Task {
                while !Task.isCancelled {
                    await refreshConnection()
                    if isConnected { break }
                    if process.isRunning == false {
                        status = "Engine exited. Open Engine Log to see the error."
                        isStartingEngine = false
                        break
                    }
                    try? await Task.sleep(for: .seconds(5))
                }
            }
        } catch {
            errorText = "Could not start engine: \(error.localizedDescription)"
            isStartingEngine = false
        }
    }

    func stopEngine() {
        generationTask?.cancel()
        generationTask = nil
        engineWatchTask?.cancel()
        engineWatchTask = nil
        if let process = engineProcess, process.isRunning { process.terminate() }
        engineProcess = nil
        try? engineLogHandle?.close()
        engineLogHandle = nil
        isConnected = false
        isStartingEngine = false
        isGenerating = false
        currentJobID = nil
        status = "Local engine stopped"
        jobStatus = ""
    }

    func openLog() {
        let log = engineLogURL ??
            Config.applicationDirectory.appendingPathComponent("engine.log")
        if FileManager.default.fileExists(atPath: log.path) {
            NSWorkspace.shared.open(log)
        } else {
            errorText = "No engine log yet. Start the engine first."
        }
    }

    func refreshConnection() async {
        var request = URLRequest(url: Config.baseURL.appendingPathComponent("/v1/videos/capabilities"))
        request.timeoutInterval = 3
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, http.statusCode == 200 {
                isConnected = true
                isStartingEngine = false
                status = "Local engine ready · \(Config.model)"
                return
            }
        } catch { }
        isConnected = false
        if !isStartingEngine {
            status = engineIsInstalled ? "Engine installed but not connected" : "Engine not installed"
        }
    }

    func generate() {
        guard canGenerate else { return }
        generationTask?.cancel()
        generationTask = Task { await generateImageVideo() }
    }

    func stopRender() {
        // Rapid-MLX does not document a per-job cancel route. Stopping the
        // engine we started is the honest, effective way to interrupt inference.
        if engineIsOwned {
            stopEngine()
            status = "Rendering interrupted; restart the local engine to try again."
        } else {
            generationTask?.cancel()
            generationTask = nil
            isGenerating = false
            jobStatus = "Stopped waiting. An externally started engine may still be rendering."
        }
    }

    private func generateImageVideo() async {
        isGenerating = true
        outputURL = nil
        errorText = nil
        jobStatus = "Preparing reference picture"
        defer {
            isGenerating = false
            generationTask = nil
            currentJobID = nil
        }
        do {
            guard let photo = inputImage, let png = Self.pngBytes(from: photo) else {
                throw VideoError.message("Cannot convert the photo to PNG.")
            }
            let wording = instruction.trimmingCharacters(in: .whitespacesAndNewlines)
            let fullPrompt = preserveAppearance
                ? "Animate the supplied reference photograph. Keep the same subject identity, face, hair, clothing, composition, lighting and background as closely as possible. Do not replace the character or redraw the scene. Requested motion: \(wording)"
                : wording

            let boundary = "Rosalina-\(UUID().uuidString)"
            var request = URLRequest(url: Config.baseURL.appendingPathComponent("/v1/videos"))
            request.httpMethod = "POST"
            request.timeoutInterval = 180
            request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
            request.httpBody = Self.multipart(boundary: boundary, fields: [
                ("model", Config.model),
                ("prompt", fullPrompt),
                ("seconds", String(duration)),
                ("size", frameSize),
                ("conditioning_strength", String(format: "%.2f", fidelity))
            ], imagePNG: png)

            jobStatus = "Submitting image-to-video job"
            let (data, response) = try await URLSession.shared.data(for: request)
            try Self.checkResponse(response, data)
            let object = try Self.json(data)
            guard let jobID = object["id"] as? String, !jobID.isEmpty else {
                throw VideoError.message("The engine returned no video job ID: \(String(decoding: data.prefix(400), as: UTF8.self))")
            }
            currentJobID = jobID
            jobStatus = "Queued"
            for _ in 0..<7200 {
                try Task.checkCancellation()
                let safeID = jobID.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? jobID
                let statusURL = Config.baseURL.appendingPathComponent("/v1/videos/\(safeID)")
                var poll = URLRequest(url: statusURL)
                poll.timeoutInterval = 30
                let (statusData, statusResponse) = try await URLSession.shared.data(for: poll)
                try Self.checkResponse(statusResponse, statusData)
                let info = try Self.json(statusData)
                let state = (info["status"] as? String ?? "in_progress").lowercased()
                let progress = info["progress"].map { " · \($0)" } ?? ""
                jobStatus = "Video job: \(state)\(progress)"
                if state == "completed" {
                    try await downloadVideo(id: safeID)
                    jobStatus = "Video ready · saved locally"
                    return
                }
                if state == "failed" || state == "cancelled" || state == "canceled" {
                    let details = Self.describeError(info)
                    throw VideoError.message("Generation \(state): \(details)")
                }
                try await Task.sleep(for: .seconds(3))
            }
            throw VideoError.message("Job polling limit reached. Open the engine log.")
        } catch is CancellationError {
            jobStatus = "Rendering interrupted"
        } catch {
            errorText = error.localizedDescription
            jobStatus = "Generation failed"
        }
    }

    private func downloadVideo(id: String) async throws {
        let url = Config.baseURL.appendingPathComponent("/v1/videos/\(id)/content")
        var request = URLRequest(url: url)
        request.timeoutInterval = 180
        let (temporary, response) = try await URLSession.shared.download(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw VideoError.message("Could not download the completed MP4.")
        }
        let exports = Config.applicationDirectory.appendingPathComponent("Exports", isDirectory: true)
        try FileManager.default.createDirectory(at: exports, withIntermediateDirectories: true)
        let destination = exports.appendingPathComponent("Rosalina-\(UUID().uuidString.prefix(8)).mp4")
        try FileManager.default.moveItem(at: temporary, to: destination)
        outputURL = destination
    }

    func saveVideo() {
        guard let outputURL else { return }
        let panel = NSSavePanel()
        panel.title = "Save Rosalina animation"
        panel.allowedContentTypes = [.mpeg4Movie]
        panel.nameFieldStringValue = "Rosalina-Animation.mp4"
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: outputURL, to: destination)
            status = "Saved \(destination.lastPathComponent)"
        } catch {
            errorText = "Could not save video: \(error.localizedDescription)"
        }
    }

    func revealVideo() {
        guard let outputURL else { return }
        NSWorkspace.shared.activateFileViewerSelecting([outputURL])
    }

    private static func pngBytes(from image: NSImage) -> Data? {
        let longestSide = max(image.size.width, image.size.height)
        guard longestSide > 0 else { return nil }
        let factor = min(1.0, 1024.0 / longestSide)
        let width = max(64, Int((image.size.width * factor).rounded()))
        let height = max(64, Int((image.size.height * factor).rounded()))
        guard let bitmap = NSBitmapImageRep(
            bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
            isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
        ), let context = NSGraphicsContext(bitmapImageRep: bitmap) else { return nil }

        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = context
        NSColor.black.setFill()
        NSRect(x: 0, y: 0, width: width, height: height).fill()
        image.draw(in: NSRect(x: 0, y: 0, width: width, height: height),
                   from: .zero, operation: .sourceOver, fraction: 1)
        context.flushGraphics()
        NSGraphicsContext.restoreGraphicsState()
        return bitmap.representation(using: .png, properties: [:])
    }

    private static func multipart(
        boundary: String, fields: [(String, String)], imagePNG: Data
    ) -> Data {
        var data = Data()
        for (key, value) in fields {
            data.append(Data("--\(boundary)\r\n".utf8))
            data.append(Data("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".utf8))
            data.append(Data("\(value)\r\n".utf8))
        }
        data.append(Data("--\(boundary)\r\n".utf8))
        data.append(Data("Content-Disposition: form-data; name=\"input_reference\"; filename=\"rosalina-reference.png\"\r\n".utf8))
        data.append(Data("Content-Type: image/png\r\n\r\n".utf8))
        data.append(imagePNG)
        data.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return data
    }

    private static func json(_ data: Data) throws -> [String: Any] {
        guard let dictionary = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw VideoError.message("Unexpected server response.")
        }
        return dictionary
    }

    private static func checkResponse(_ response: URLResponse, _ data: Data) throws {
        guard let http = response as? HTTPURLResponse else {
            throw VideoError.message("No HTTP response from local engine.")
        }
        guard (200...299).contains(http.statusCode) else {
            let info = (try? json(data)) ?? [:]
            throw VideoError.message("Engine HTTP \(http.statusCode): \(describeError(info))")
        }
    }

    private static func describeError(_ json: [String: Any]) -> String {
        if let message = json["error"] as? String { return message }
        if let detail = json["detail"] as? String { return detail }
        if let error = json["error"] as? [String: Any],
           let message = error["message"] as? String { return message }
        if let detail = json["detail"] as? [String: Any],
           let message = detail["message"] as? String { return message }
        return "See engine.log for details."
    }
}

private enum VideoError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let text): return text }
    }
}

struct VideoScreen: View {
    @EnvironmentObject private var model: VideoViewModel
    @State private var videoPlayer = AVPlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                header
                HStack(alignment: .top, spacing: 16) {
                    inputPane
                    settingsPane
                }
                outputPane
                Text("All rendering is local after model download. LTX-2.3 outputs silent MP4 video. It uses the uploaded photo as a reference; exact frame-to-frame identity cannot be guaranteed.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(22)
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .onChange(of: model.outputURL) { _, url in
            videoPlayer.pause()
            videoPlayer.replaceCurrentItem(with: url.map { AVPlayerItem(url: $0) })
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("ROSALINA").font(.caption).fontWeight(.bold).tracking(3)
                        .foregroundStyle(.teal)
                    Text("Photo → Video").font(.largeTitle.bold())
                    Text("Mac preview · local AI · reference-photo animation")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Label(model.isConnected ? "Engine ready" : "Engine offline",
                      systemImage: model.isConnected ? "checkmark.circle.fill" : "circle.dotted")
                    .foregroundStyle(model.isConnected ? .green : .secondary)
            }

            HStack(spacing: 10) {
                Button("1 · Install Engine") { model.installEngine() }
                    .disabled(model.isGenerating)
                Button("2 · Start Local Engine") { model.startEngine() }
                    .disabled(model.engineIsOwned || model.isStartingEngine)
                Button("Check Connection") { model.checkConnection() }
                if model.engineIsOwned {
                    Button("Stop Engine", role: .destructive) { model.stopEngine() }
                }
                Button("Engine Log") { model.openLog() }
                Spacer()
            }
            Text(model.status).font(.caption).foregroundStyle(.secondary)
                .textSelection(.enabled)
        }
    }

    private var inputPane: some View {
        GroupBox("Reference photograph") {
            VStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color(nsColor: .controlBackgroundColor))
                    if let image = model.inputImage {
                        Image(nsImage: image).resizable().scaledToFit().padding(8)
                    } else {
                        VStack(spacing: 10) {
                            Image(systemName: "photo.on.rectangle.angled")
                                .font(.system(size: 44))
                                .foregroundStyle(.secondary)
                            Text("Choose a picture to animate")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .frame(height: 275)
                Text(model.inputName).lineLimit(1).font(.caption).foregroundStyle(.secondary)
                Button("Choose Photo…") { model.selectPhoto() }
                    .buttonStyle(.borderedProminent)
            }
            .frame(maxWidth: .infinity)
            .padding(10)
        }
        .frame(maxWidth: .infinity)
    }

    private var settingsPane: some View {
        GroupBox("Animate your picture") {
            VStack(alignment: .leading, spacing: 14) {
                Text("Describe exactly what should move")
                    .font(.headline)
                TextEditor(text: $model.instruction)
                    .font(.body)
                    .scrollContentBackground(.hidden)
                    .frame(height: 102)
                    .padding(7)
                    .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 8))
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(.gray.opacity(0.25)))
                Text("Example: She smiles naturally, blinks, then turns her head slowly. The camera stays still.")
                    .font(.caption).foregroundStyle(.secondary)
                Toggle("Preserve the original appearance", isOn: $model.preserveAppearance)
                HStack(spacing: 18) {
                    Picker("Length", selection: $model.duration) {
                        Text("1 sec").tag(1)
                        Text("2 sec").tag(2)
                        Text("4 sec").tag(4)
                    }.frame(maxWidth: 165)
                    Picker("Frame", selection: $model.frameSize) {
                        Text("Square · 512").tag("512x512")
                        Text("Wide · 768×512").tag("768x512")
                        Text("Tall · 512×768").tag("512x768")
                    }.frame(maxWidth: 215)
                }
                HStack {
                    Text("Reference strength")
                    Slider(value: $model.fidelity, in: 0.5...1.0, step: 0.05)
                    Text(String(format: "%.2f", model.fidelity)).monospacedDigit()
                }
                Text("Higher strength asks the model to follow your reference more closely. It does not guarantee unchanged details.")
                    .font(.caption).foregroundStyle(.secondary)

                HStack {
                    Button {
                        model.generate()
                    } label: {
                        Label("Generate Video", systemImage: "sparkles.tv")
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!model.canGenerate)
                    if model.isGenerating {
                        Button("Stop Render") { model.stopRender() }
                        ProgressView().controlSize(.small)
                    }
                }
                Text(model.jobStatus)
                    .font(.caption).foregroundStyle(.secondary)
                    .textSelection(.enabled)
                if let error = model.errorText {
                    Text(error).foregroundStyle(.red).font(.caption)
                        .textSelection(.enabled)
                }
            }
            .padding(10)
        }
        .frame(maxWidth: .infinity)
    }

    private var outputPane: some View {
        GroupBox("Generated video") {
            HStack(alignment: .center, spacing: 16) {
                if model.outputURL != nil {
                    VideoPlayer(player: videoPlayer)
                        .frame(width: 400, height: 225)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color(nsColor: .controlBackgroundColor))
                        .overlay {
                            VStack(spacing: 8) {
                                Image(systemName: "play.rectangle")
                                    .font(.system(size: 35))
                                Text("Your generated MP4 appears here")
                            }
                            .foregroundStyle(.secondary)
                        }
                        .frame(width: 400, height: 180)
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text(model.outputURL == nil ? "Ready for your first test" : "Your video is ready")
                        .font(.headline)
                    Text("Compare the face, body, clothing and background with the original photo. Try gentle movements before complex ones.")
                        .foregroundStyle(.secondary)
                    HStack {
                        Button("Save MP4…") { model.saveVideo() }
                            .disabled(model.outputURL == nil)
                        Button("Show in Finder") { model.revealVideo() }
                            .disabled(model.outputURL == nil)
                    }
                    Text("First generation downloads ~\(Config.modelDownloadGB) of model weights. Processing can take several minutes per second of video.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(10)
        }
    }
}
