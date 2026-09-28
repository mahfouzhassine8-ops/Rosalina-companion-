import Foundation
import Metal
import Darwin

public enum SystemProfiler {
    public static func current() -> HardwareProfile {
        let process = ProcessInfo.processInfo
        let gpu = MTLCreateSystemDefaultDevice()
        return HardwareProfile(
            machineModel: machineModel(),
            osVersion: process.operatingSystemVersionString,
            processorCount: process.processorCount,
            activeProcessorCount: process.activeProcessorCount,
            physicalMemoryBytes: process.physicalMemory,
            gpuName: gpu?.name ?? "No Metal GPU",
            gpuRecommendedWorkingSetBytes: UInt64(gpu?.recommendedMaxWorkingSetSize ?? 0),
            gpuHasUnifiedMemory: gpu?.hasUnifiedMemory ?? false
        )
    }

    private static func machineModel() -> String {
        var size: size_t = 0
        guard sysctlbyname("hw.model", nil, &size, nil, 0) == 0, size > 1 else {
            return "Unknown Mac"
        }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname("hw.model", &buffer, &size, nil, 0) == 0 else {
            return "Unknown Mac"
        }
        return String(cString: buffer)
    }
}
