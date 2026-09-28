import Foundation

public final class AcceleratorWorkspace {
    public let root: URL
    public let models: URL
    public let jobs: URL

    public init(fileManager: FileManager = .default) throws {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        root = base.appendingPathComponent("Rosalina Accelerator", isDirectory: true)
        models = root.appendingPathComponent("Models", isDirectory: true)
        jobs = root.appendingPathComponent("Jobs", isDirectory: true)
        try fileManager.createDirectory(at: models, withIntermediateDirectories: true)
        try fileManager.createDirectory(at: jobs, withIntermediateDirectories: true)
    }

    public func modelFileCount(fileManager: FileManager = .default) -> Int {
        guard let items = try? fileManager.contentsOfDirectory(
            at: models,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        return items.filter {
            (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
        }.count
    }

    public func cleanupExpiredJobs(olderThan seconds: TimeInterval = 24 * 60 * 60, fileManager: FileManager = .default) {
        guard let items = try? fileManager.contentsOfDirectory(
            at: jobs,
            includingPropertiesForKeys: [.contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return }
        let cutoff = Date().addingTimeInterval(-seconds)
        for item in items {
            if let date = try? item.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
               date < cutoff {
                try? fileManager.removeItem(at: item)
            }
        }
    }
}
