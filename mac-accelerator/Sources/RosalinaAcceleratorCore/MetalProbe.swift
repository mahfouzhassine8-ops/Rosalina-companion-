import Foundation
import Metal

public enum MetalProbe {
    public static func run(elements: Int = 65_536) throws -> MetalProbeResult {
        guard let device = MTLCreateSystemDefaultDevice() else {
            throw AcceleratorError.unavailable("No Metal device is available")
        }
        guard let queue = device.makeCommandQueue() else {
            throw AcceleratorError.unavailable("Metal command queue could not be created")
        }

        let source = """
        #include <metal_stdlib>
        using namespace metal;
        kernel void rosalina_add(
            device const float* a [[buffer(0)]],
            device const float* b [[buffer(1)]],
            device float* out [[buffer(2)]],
            uint id [[thread_position_in_grid]]
        ) {
            out[id] = a[id] + b[id];
        }
        """

        let library = try device.makeLibrary(source: source, options: nil)
        guard let function = library.makeFunction(name: "rosalina_add") else {
            throw AcceleratorError.internalError("Metal probe kernel was not found")
        }
        let pipeline = try device.makeComputePipelineState(function: function)

        let count = max(1_024, min(elements, 1_048_576))
        let a = (0..<count).map { Float($0 % 257) * 0.25 }
        let b = (0..<count).map { Float($0 % 113) * 0.5 }
        let length = count * MemoryLayout<Float>.stride

        guard let aBuffer = device.makeBuffer(bytes: a, length: length),
              let bBuffer = device.makeBuffer(bytes: b, length: length),
              let outBuffer = device.makeBuffer(length: length),
              let command = queue.makeCommandBuffer(),
              let encoder = command.makeComputeCommandEncoder() else {
            throw AcceleratorError.unavailable("Metal probe buffers could not be allocated")
        }

        encoder.setComputePipelineState(pipeline)
        encoder.setBuffer(aBuffer, offset: 0, index: 0)
        encoder.setBuffer(bBuffer, offset: 0, index: 1)
        encoder.setBuffer(outBuffer, offset: 0, index: 2)

        let width = pipeline.threadExecutionWidth
        encoder.dispatchThreads(
            MTLSize(width: count, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: width, height: 1, depth: 1)
        )
        encoder.endEncoding()

        let start = CFAbsoluteTimeGetCurrent()
        command.commit()
        command.waitUntilCompleted()
        let elapsed = (CFAbsoluteTimeGetCurrent() - start) * 1000.0

        if let error = command.error {
            throw AcceleratorError.internalError("Metal command failed: \(error.localizedDescription)")
        }

        let pointer = outBuffer.contents().bindMemory(to: Float.self, capacity: count)
        var maxError: Float = 0
        for index in 0..<count {
            maxError = max(maxError, abs(pointer[index] - (a[index] + b[index])))
        }
        return MetalProbeResult(
            passed: maxError < 0.0001,
            gpuName: device.name,
            elements: count,
            elapsedMs: elapsed,
            maxError: maxError
        )
    }
}
