import Foundation

// Portable local output handling. Never deletes or mutates Rapid-MLX's originals.
// All finished clips are copied to a visible, stable folder before any preview.
enum RosalinaStorage {
    static var appDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Rosalina Video", isDirectory: true)
    }

    static var exportDirectory: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Movies/Rosalina Videos", isDirectory: true)
    }

    static var journalURL: URL {
        appDirectory.appendingPathComponent("video-jobs.json")
    }

    struct Job: Codable, Equatable {
        var id: String
        var state: String
        var createdAt: Date
        var savedPath: String?
    }

    static func loadJobs(from file: URL = journalURL) -> [Job] {
        guard let data = try? Data(contentsOf: file),
              let jobs = try? JSONDecoder().decode([Job].self, from: data) else {
            return []
        }
        return jobs
    }

    static func updateJob(
        id: String, state: String, savedPath: String? = nil,
        at file: URL = journalURL
    ) throws {
        guard id.range(of: #"^video_[A-Za-z0-9_-]{1,120}$"#, options: .regularExpression) != nil else {
            throw OutputError.invalidJob
        }
        var jobs = loadJobs(from: file)
        if let i = jobs.firstIndex(where: { $0.id == id }) {
            jobs[i].state = state
            if let savedPath { jobs[i].savedPath = savedPath }
        } else {
            jobs.append(Job(id: id, state: state, createdAt: Date(), savedPath: savedPath))
        }
        // Bound journal growth without losing recent job history.
        if jobs.count > 200 { jobs.removeFirst(jobs.count - 200) }
        let manager = FileManager.default
        try manager.createDirectory(at: file.deletingLastPathComponent(),
                                    withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(jobs)
        try data.write(to: file, options: .atomic)
    }

    static func validMP4(_ file: URL) -> Bool {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: file.path),
              let bytes = attributes[.size] as? NSNumber, bytes.int64Value >= 2048,
              let handle = try? FileHandle(forReadingFrom: file) else {
            return false
        }
        defer { try? handle.close() }
        let header = (try? handle.read(upToCount: 16)) ?? nil
        guard let header, header.count >= 8 else { return false }
        return Data(header[4..<8]) == Data("ftyp".utf8)
    }

    @discardableResult
    static func archive(
        _ source: URL, id: String,
        into outputDirectory: URL = exportDirectory
    ) throws -> URL {
        guard id.range(of: #"^video_[A-Za-z0-9_-]{1,120}$"#, options: .regularExpression) != nil else {
            throw OutputError.invalidJob
        }
        guard validMP4(source) else { throw OutputError.invalidMP4 }
        let manager = FileManager.default
        try manager.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
        let target = outputDirectory.appendingPathComponent(id).appendingPathExtension("mp4")

        // An existing *valid* file is kept, even if a new export fails.
        if validMP4(target) { return target }
        if manager.fileExists(atPath: target.path) {
            throw OutputError.targetConflict
        }

        let temporary = outputDirectory.appendingPathComponent(".\(id)-\(UUID().uuidString).partial")
        defer { try? manager.removeItem(at: temporary) }
        try manager.copyItem(at: source, to: temporary)
        guard validMP4(temporary) else { throw OutputError.invalidMP4 }
        try manager.moveItem(at: temporary, to: target)
        return target
    }

    // Rapid-MLX writes the final MP4 in its own per-job temporary folder.
    // This is recovery-only: copy the original, never move or remove it.
    static func originalMP4(
        id: String, temporaryRoot: URL = URL(fileURLWithPath: NSTemporaryDirectory())
    ) -> URL? {
        guard id.range(of: #"^video_[A-Za-z0-9_-]{1,120}$"#, options: .regularExpression) != nil else {
            return nil
        }
        let manager = FileManager.default
        guard let roots = try? manager.contentsOfDirectory(at: temporaryRoot,
                                                           includingPropertiesForKeys: [.isDirectoryKey]) else {
            return nil
        }
        for root in roots where root.lastPathComponent.hasPrefix("rapid-mlx-videos-") {
            let file = root.appendingPathComponent(id, isDirectory: true)
                .appendingPathComponent("output.mp4")
            if validMP4(file) { return file }
        }
        return nil
    }

    static func recoverCachedVideos(
        temporaryRoot: URL = URL(fileURLWithPath: NSTemporaryDirectory()),
        into outputDirectory: URL = exportDirectory
    ) -> [URL] {
        let manager = FileManager.default
        guard let roots = try? manager.contentsOfDirectory(
            at: temporaryRoot, includingPropertiesForKeys: [.isDirectoryKey]
        ) else { return [] }
        var recovered: [URL] = []
        for root in roots where root.lastPathComponent.hasPrefix("rapid-mlx-videos-") {
            guard let jobs = try? manager.contentsOfDirectory(
                at: root, includingPropertiesForKeys: [.isDirectoryKey]
            ) else { continue }
            for job in jobs where job.lastPathComponent.hasPrefix("video_") {
                let source = job.appendingPathComponent("output.mp4")
                if !validMP4(source) { continue }
                if let saved = try? archive(source, id: job.lastPathComponent,
                                            into: outputDirectory) {
                    recovered.append(saved)
                }
            }
        }
        return recovered.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    // Decode a handful of actual video frames using the ffmpeg installed by
    // Rosalina's setup, *not* AVPlayer, avoiding a GPU preview crash at completion.
    // Returns true for uniformly near-black frames, false for visible image content,
    // nil if inspection is not possible. A result of nil is NEVER called a success.
    static func isLikelyBlack(
        _ file: URL, ffmpegExecutable: URL? = nil
    ) -> Bool? {
        let defaultBins = ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"]
        guard let binary = ffmpegExecutable ??
                defaultBins.first(where: { FileManager.default.isExecutableFile(atPath: $0) })
                    .map({ URL(fileURLWithPath: $0) }) else { return nil }
        guard validMP4(file) else { return nil }

        let process = Process()
        process.executableURL = binary
        process.arguments = [
            "-hide_banner", "-loglevel", "error", "-nostdin",
            "-i", file.path,
            "-an", "-vf", "fps=2,scale=32:32:flags=fast_bilinear,format=gray",
            "-frames:v", "12", "-f", "rawvideo", "pipe:1"
        ]
        let stdout = Pipe()
        process.standardOutput = stdout
        process.standardError = Pipe()
        do {
            try process.run()
            let raw = stdout.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            guard process.terminationStatus == 0, raw.count >= 1024 else { return nil }
            let samples = raw.count / 1024
            guard samples >= 1, raw.count % 1024 == 0 else { return nil }
            var black = 0
            for sample in 0..<samples {
                let start = sample * 1024
                let end = start + 1024
                let blackPixels = raw[start..<end].reduce(0) { $0 + ($1 < 30 ? 1 : 0) }
                if blackPixels >= 995 { black += 1 }
            }
            return Double(black) / Double(samples) >= 0.9
        } catch {
            return nil
        }
    }
}

enum OutputError: LocalizedError {
    case invalidJob
    case invalidMP4
    case targetConflict

    var errorDescription: String? {
        switch self {
        case .invalidJob: return "Unrecognized video job ID. No file was changed."
        case .invalidMP4: return "Video file was missing, incomplete or invalid. Original preserved."
        case .targetConflict: return "A file already exists at this name; it was not overwritten."
        }
    }
}
