import Foundation

@main
struct RosalinaRecoveryTests {
    static func main() throws {
        let files = FileManager.default
        let root = files.temporaryDirectory
            .appendingPathComponent("rosalina-recovery-tests-\(UUID().uuidString)", isDirectory: true)
        defer { try? files.removeItem(at: root) }
        try files.createDirectory(at: root, withIntermediateDirectories: true)
        let output = root.appendingPathComponent("exports", isDirectory: true)
        let cache = root.appendingPathComponent("cache", isDirectory: true)
        let journal = root.appendingPathComponent("video-jobs.json")

        func require(_ ok: @autoclosure () -> Bool, _ detail: String) throws {
            if !ok() { throw NSError(domain: "RosalinaRecoveryTests", code: 1,
                                     userInfo: [NSLocalizedDescriptionKey: detail]) }
        }

        try RosalinaStorage.updateJob(id: "video_test001", state: "submitted", at: journal)
        try require(RosalinaStorage.loadJobs(from: journal).count == 1,
                    "submitted job must survive restart")
        try RosalinaStorage.updateJob(id: "video_test001", state: "rendering", at: journal)
        try require(RosalinaStorage.loadJobs(from: journal)[0].state == "rendering",
                    "journal must update existing job")
        do {
            try RosalinaStorage.updateJob(id: "../bad", state: "oops", at: journal)
            throw NSError(domain: "RosalinaRecoveryTests", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "unsafe ID accepted"])
        } catch OutputError.invalidJob { }

        let ffmpeg = ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"]
            .first(where: { files.isExecutableFile(atPath: $0) })
        guard let ffmpeg else {
            throw NSError(domain: "RosalinaRecoveryTests", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "ffmpeg missing in CI"])
        }

        func makeVideo(_ filter: String, dest: URL) throws {
            let ff = Process()
            ff.executableURL = URL(fileURLWithPath: ffmpeg)
            ff.arguments = ["-hide_banner", "-loglevel", "error", "-nostdin",
                            "-f", "lavfi", "-i", filter, "-frames:v", "25",
                            "-c:v", "mpeg4", "-q:v", "5", "-y", dest.path]
            try ff.run()
            ff.waitUntilExit()
            try require(ff.terminationStatus == 0, "ffmpeg fixture creation failed")
        }

        let first = cache.appendingPathComponent("rapid-mlx-videos-synthetic", isDirectory: true)
            .appendingPathComponent("video_test001", isDirectory: true)
        try files.createDirectory(at: first, withIntermediateDirectories: true)
        let black = first.appendingPathComponent("output.mp4")
        try makeVideo("color=c=black:s=320x512:r=24:d=1", dest: black)
        try require(RosalinaStorage.validMP4(black), "black MP4 should be recognized")
        try require(RosalinaStorage.isLikelyBlack(black) == true,
                    "black detection should identify uniformly black frames")

        let second = cache.appendingPathComponent("rapid-mlx-videos-synthetic", isDirectory: true)
            .appendingPathComponent("video_test002", isDirectory: true)
        try files.createDirectory(at: second, withIntermediateDirectories: true)
        let visible = second.appendingPathComponent("output.mp4")
        try makeVideo("testsrc2=size=320x512:rate=24:duration=1", dest: visible)
        try require(RosalinaStorage.validMP4(visible), "colored MP4 should be recognized")
        try require(RosalinaStorage.isLikelyBlack(visible) == false,
                    "black detection must not flag an actual visible clip")

        let recovered = RosalinaStorage.recoverCachedVideos(temporaryRoot: cache, into: output)
        try require(recovered.count == 2, "both synthetic videos should be recovered")
        try require(recovered.allSatisfy { RosalinaStorage.validMP4($0) },
                    "recovered videos should be valid")
        try require(files.fileExists(atPath: black.path) && files.fileExists(atPath: visible.path),
                    "recovery must not move or delete engine originals")

        let one = try RosalinaStorage.archive(black, id: "video_test001", into: output)
        let two = try RosalinaStorage.archive(black, id: "video_test001", into: output)
        try require(one == two, "archive retry must be idempotent")
        try RosalinaStorage.updateJob(id: "video_test001", state: "black_output",
                                      savedPath: one.path, at: journal)
        try require(RosalinaStorage.loadJobs(from: journal)[0].savedPath == one.path,
                    "saved video location must survive restart")

        let wrong = root.appendingPathComponent("not-mp4.mp4")
        try Data(repeating: 0, count: 4000).write(to: wrong)
        try require(!RosalinaStorage.validMP4(wrong), "invalid videos must be rejected")

        print("PASS: journal persistence, unsafe ID rejection, black/visible frame gates, " +
              "recover originals, stable atomic archive, idempotence, and invalid-MP4 rejection")
    }
}
