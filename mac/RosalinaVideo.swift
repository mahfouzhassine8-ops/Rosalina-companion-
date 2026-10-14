import SwiftUI
import AppKit
import UniformTypeIdentifiers

private enum Config {
    static let model = "ltx-2.3-mlx-q4"
    static let baseURL = URL(string: "http://127.0.0.1:8000")!
    static let modelDownloadGB = "22.8 GB"
    static let applicationDirectory = RosalinaStorage.appDirectory
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
                .onAppear { model.bootstrap() }
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
    @Published var duration = 1
    @Published var frameSize = "512x512"
    @Published var fidelity = 0.85
    @Published var preserveAppearance = true
    @Published var isConnected = false
    @Published var isStartingEngine = false
    @Published var isGenerating = false
    @Published var status = "Engine not checked"
    @Published var jobStatus = ""
    @Published var outputURL: URL?
    @Published var recoveredCount = 0
    @Published var qualityWarning: String?
    @Published var advancedAllowed = false
    @Published var errorText: String?
    @Published var engineLogURL: URL?

    private var engineProcess: Process?
    private var engineLogHandle: FileHandle?
    private var generationTask: Task<Void, Never>?
    private var engineWatchTask: Task<Void, Never>?
    private var currentJobID: String?

    var needsMemoryOverride: Bool {
        guard ProcessInfo.processInfo.physicalMemory < 32_000_000_000 else { return false }
        return duration > 1 || !["384x384", "512x320", "320x512"].contains(frameSize)
    }

    var canGenerate: Bool {
        inputImage != nil && !instruction.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && isConnected && !isGenerating && (!needsMemoryOverride || advancedAllowed)
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
        frameSize = aspect > 1.18 ? "512x320" : (aspect < 0.85 ? "320x512" : "384x384")
        duration = 1
        advancedAllowed = false
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

    func bootstrap() {
        // Recovery is deliberately before connecting to the engine; no new
        // inference, download, or model startup is needed to restore MP4s.
        recoverPreviousVideos()
        checkConnection()
    }

    func showSavedVideos() {
        do {
            try FileManager.default.createDirectory(
                at: RosalinaStorage.exportDirectory, withIntermediateDirectories: true)
            NSWorkspace.shared.open(RosalinaStorage.exportDirectory)
        } catch {
            errorText = "Cannot open the saved-videos folder: \(error.localizedDescription)"
        }
    }

    func previewInExternalPlayer() {
        guard let outputURL else { return }
        // Deliberately avoid an embedded AVPlayer while 24 GB unified memory
        // may still be under extreme pressure after inference.
        NSWorkspace.shared.open(outputURL)
    }

    func recoverPreviousVideos() {
        Task {
            let saved = await Task.detached(priority: .utility) {
                RosalinaStorage.recoverCachedVideos()
            }.value
            let jobs = RosalinaStorage.loadJobs()
            recoveredCount = saved.count
            if let latest = saved.last {
                outputURL = latest
                status = "Recovered \(saved.count) engine video(s). They are in Movies → Rosalina Videos."
            } else if let existing = jobs.compactMap({ $0.savedPath }).reversed()
                .map({ URL(fileURLWithPath: $0) }).first(where: { RosalinaStorage.validMP4($0) }) {
                outputURL = existing
                status = "Previously saved video found in Movies → Rosalina Videos."
            }
            // Saved server job IDs also survive UI crashes. Query them only if
            // engine is actually running; lack of connectivity is not an error.
            guard await engineAvailable() else { return }
            for job in jobs where job.savedPath == nil && job.state != "failed" {
                do {
                    let statusURL = Config.baseURL.appendingPathComponent("/v1/videos/\(job.id)")
                    var request = URLRequest(url: statusURL)
                    request.timeoutInterval = 8
                    let (data, response) = try await URLSession.shared.data(for: request)
                    try Self.checkResponse(response, data)
                    let json = try Self.json(data)
                    if (json["status"] as? String)?.lowercased() == "completed" {
                        try await downloadVideo(id: job.id)
                        recoveredCount += 1
                        status = "Recovered completed video \(job.id) without regenerating."
                    }
                } catch {
                    // The local cache may have expired; keep the job ID in the
                    // persistent journal so it remains visible for diagnostics.
                    continue
                }
            }
        }
    }

    private func engineAvailable() async -> Bool {
        var request = URLRequest(url: Config.baseURL.appendingPathComponent("/v1/videos/capabilities"))
        request.timeoutInterval = 3
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch { return false }
    }

    func startEngine() {
        errorText = nil
        if isConnected {
            status = "Local engine is already connected. No second process started."
            return
        }
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
            _ = try handle.seekToEnd()
            try handle.write(contentsOf: Data("\n--- Rosalina engine session: \(Date()) ---\n".utf8))
            let process = Process()
            process.executableURL = Config.engineBinary
            // Restrict new GPU allocations and text-cache overhead. If the
            // 24 GB Mac cannot fit the render, fail cleanly instead of
            // attempting the prior ~29 GB peak. This is not a quality fix.
            process.arguments = [
                "serve", Config.model,
                "--gpu-memory-utilization", "0.75",
                "--cache-memory-percent", "0.05"
            ]
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
            status = "Starting safer-memory LTX-2.3 using your existing model cache. Open Engine Log if needed."
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
        qualityWarning = nil
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
                ("frames", String(duration * 24 + 1)),
                ("fps", "24"),
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
            try? RosalinaStorage.updateJob(id: jobID, state: "submitted")
            jobStatus = "Job \(jobID) queued · saved for recovery"
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
                jobStatus = "Video job: \(state)\(progress) · recovery ID: \(jobID)"
                if state == "completed" {
                    try await downloadVideo(id: safeID)
                    jobStatus = qualityWarning == nil ?
                        "Saved successfully in Movies → Rosalina Videos" :
                        "Saved, but output failed its visual check. See warning."
                    return
                }
                if state == "failed" || state == "cancelled" || state == "canceled" {
                    let details = Self.describeError(info)
                    try? RosalinaStorage.updateJob(id: jobID, state: state)
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
        // First, recover from Rapid-MLX's own finished job folder when
        // possible. This avoids transferring the MP4 in a memory-starved Mac.
        let original: URL
        if let cached = RosalinaStorage.originalMP4(id: id) {
            original = cached
        } else {
            let endpoint = Config.baseURL.appendingPathComponent("/v1/videos/\(id)/content")
            var request = URLRequest(url: endpoint)
            request.timeoutInterval = 180
            let (temporary, response) = try await URLSession.shared.download(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                throw VideoError.message("Completed job \(id) could not be downloaded. Recover its ID later.")
            }
            original = temporary
        }

        // The file is copied atomically BEFORE any UI preview or frame decode.
        // Never remove the source MP4 or a previously saved good export.
        let archived = try RosalinaStorage.archive(original, id: id)
        try? RosalinaStorage.updateJob(id: id, state: "saved", savedPath: archived.path)
        outputURL = archived

        jobStatus = "MP4 safely saved · checking frames"
        let isBlack = await Task.detached(priority: .utility) {
            RosalinaStorage.isLikelyBlack(archived)
        }.value
        if isBlack == true {
            qualityWarning = "WARNING: The saved video appears almost entirely black. " +
                "Do not spend time regenerating with the same settings. " +
                "Your MP4 is preserved for diagnosis."
            try? RosalinaStorage.updateJob(id: id, state: "black_output", savedPath: archived.path)
        } else if isBlack == nil {
            qualityWarning = "Frame validation could not run. MP4 was saved; inspect it before judging quality."
        } else {
            qualityWarning = nil
            try? RosalinaStorage.updateJob(id: id, state: "frames_visible", savedPath: archived.path)
        }
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
                Button("Recover Videos") { model.recoverPreviousVideos() }
                Button("Saved Videos Folder") { model.showSavedVideos() }
                Spacer()
            }
            Text(model.status).font(.caption).foregroundStyle(.secondary)
                .textSelection(.enabled)
            if ProcessInfo.processInfo.physicalMemory < 32_000_000_000 {
                Text("24 GB memory safety: starting engine capped to 75% Metal budget; low-memory render preset is selected. Models are NOT re-downloaded.")
                    .font(.caption).foregroundStyle(.orange)
            }
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
                        Text("1 sec · 25 frames").tag(1)
                        Text("2 sec · 49 frames").tag(2)
                        Text("4 sec · 97 frames").tag(4)
                    }.frame(maxWidth: 205)
                    Picker("Frame", selection: $model.frameSize) {
                        Text("Square · 384").tag("384x384")
                        Text("Wide · 512×320").tag("512x320")
                        Text("Tall · 320×512").tag("320x512")
                        Text("Wide HD · 768×512").tag("768x512")
                        Text("Tall HD · 512×768").tag("512x768")
                    }.frame(maxWidth: 230)
                }
                HStack {
                    Text("Reference strength")
                    Slider(value: $model.fidelity, in: 0.5...1.0, step: 0.05)
                    Text(String(format: "%.2f", model.fidelity)).monospacedDigit()
                }
                Text("Low-memory first test: 1 second, 25 frames, 320×512 portrait (or 512×320 wide). Even this is NOT guaranteed to fit a 24 GB Mac.")
                    .font(.caption).foregroundStyle(.secondary)
                if model.needsMemoryOverride {
                    Toggle("Allow higher-memory experiment (not recommended on 24 GB)", isOn: $model.advancedAllowed)
                        .foregroundStyle(.orange)
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
                if let warning = model.qualityWarning {
                    Text(warning).foregroundStyle(.orange).font(.caption)
                        .textSelection(.enabled)
                }
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
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color(nsColor: .controlBackgroundColor))
                        .overlay {
                            VStack(spacing: 10) {
                                Image(systemName: "checkmark.doc")
                                    .font(.system(size: 34))
                                Text("Video saved — open in external player")
                                    .font(.caption)
                            }
                            .foregroundStyle(.secondary)
                        }
                        .frame(width: 400, height: 180)
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
                        Button("Open MP4") { model.previewInExternalPlayer() }
                            .disabled(model.outputURL == nil)
                        Button("Save As…") { model.saveVideo() }
                            .disabled(model.outputURL == nil)
                        Button("Show in Finder") { model.revealVideo() }
                            .disabled(model.outputURL == nil)
                    }
                    Text("Automatic stable save: ~/Movies/Rosalina Videos/")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("Previously downloaded LTX weights are reused. Generated files are preserved even when visual validation fails.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(10)
        }
    }
}
