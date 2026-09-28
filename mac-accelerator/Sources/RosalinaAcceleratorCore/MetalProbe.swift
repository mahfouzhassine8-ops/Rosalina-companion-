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

        kernel void rosalina_constant(
            device float* out [[buffer(0)]],
            constant uint& count [[buffer(1)]],
            uint id [[thread_position_in_grid]]
        ) {
            if (id < count) {
                out[id] = 42.0f;
            }
        }

        kernel void rosalina_add(
            device const float* a [[buffer(0)]],
            device const float* b [[buffer(1)]],
            device float* out [[buffer(2)]],
            constant uint& count [[buffer(3)]],
            uint id [[thread_position_in_grid]]
        ) {
            if (id < count) {
                out[id] = a[id] + b[id];
            }
        }
        """

        let library = try device.makeLibrary(source: source, options: nil)
        guard let constantFunction = library.makeFunction(name: "rosalina_constant"),
              let addFunction = library.makeFunction(name: "rosalina_add") else {
            throw AcceleratorError.internalError("Metal probe kernels were not found")
        }

        let constantPipeline = try device.makeComputePipelineState(function: constantFunction)
        let addPipeline = try device.makeComputePipelineState(function: addFunction)

        let count = max(1_024, min(elements, 1_048_576))
        let length = count * MemoryLayout<Float>.stride
        let options: MTLResourceOptions = .storageModeShared

        guard let aBuffer = device.makeBuffer(length: length, options: options),
              let bBuffer = device.makeBuffer(length: length, options: options),
              let constantOut = device.makeBuffer(length: length, options: options),
              let addOut = device.makeBuffer(length: length, options: options),
              let countBuffer = device.makeBuffer(length: MemoryLayout<UInt32>.stride, options: options) else {
            throw AcceleratorError.unavailable("Metal probe buffers could not be allocated")
        }

        let aPointer = aBuffer.contents().bindMemory(to: Float.self, capacity: count)
        let bPointer = bBuffer.contents().bindMemory(to: Float.self, capacity: count)
        let constantPointer = constantOut.contents().bindMemory(to: Float.self, capacity: count)
        let addPointer = addOut.contents().bindMemory(to: Float.self, capacity: count)

        for index in 0..<count {
            aPointer[index] = Float(index % 257) * 0.25
            bPointer[index] = Float(index % 113) * 0.5
            constantPointer[index] = -9999.0
            addPointer[index] = -9999.0
        }
        countBuffer.contents().bindMemory(to: UInt32.self, capacity: 1).pointee = UInt32(count)

        guard let command = queue.makeCommandBuffer() else {
            throw AcceleratorError.unavailable("Metal command buffer could not be created")
        }

        try encode(
            command: command,
            pipeline: constantPipeline,
            count: count,
            buffers: [(constantOut, 0), (countBuffer, 1)]
        )
        try encode(
            command: command,
            pipeline: addPipeline,
            count: count,
            buffers: [(aBuffer, 0), (bBuffer, 1), (addOut, 2), (countBuffer, 3)]
        )

        let start = CFAbsoluteTimeGetCurrent()
        command.commit()
        command.waitUntilCompleted()
        let elapsed = (CFAbsoluteTimeGetCurrent() - start) * 1000.0

        if let error = command.error {
            throw AcceleratorError.internalError("Metal command failed: \(error.localizedDescription)")
        }
        guard command.status == .completed else {
            throw AcceleratorError.internalError("Metal command did not complete successfully")
        }

        var constantMaxError: Float = 0
        var vectorMaxError: Float = 0
        var firstMismatchIndex: Int?
        var firstMismatchExpected: Float?
        var firstMismatchActual: Float?

        for index in 0..<count {
            constantMaxError = max(constantMaxError, abs(constantPointer[index] - 42.0))

            let expected = aPointer[index] + bPointer[index]
            let actual = addPointer[index]
            let error = abs(actual - expected)
            vectorMaxError = max(vectorMaxError, error)

            if firstMismatchIndex == nil && error >= 0.0001 {
                firstMismatchIndex = index
                firstMismatchExpected = expected
                firstMismatchActual = actual
            }
        }

        let maxError = max(constantMaxError, vectorMaxError)
        let passed = maxError < 0.0001

        return MetalProbeResult(
            passed: passed,
            gpuName: device.name,
            elements: count,
            elapsedMs: elapsed,
            maxError: maxError,
            constantMaxError: constantMaxError,
            vectorMaxError: vectorMaxError,
            firstMismatchIndex: firstMismatchIndex,
            firstMismatchExpected: firstMismatchExpected,
            firstMismatchActual: firstMismatchActual,
            storageMode: "shared"
        )
    }

    private static func encode(
        command: MTLCommandBuffer,
        pipeline: MTLComputePipelineState,
        count: Int,
        buffers: [(MTLBuffer, Int)]
    ) throws {
        guard let encoder = command.makeComputeCommandEncoder() else {
            throw AcceleratorError.unavailable("Metal compute encoder could not be created")
        }
        encoder.setComputePipelineState(pipeline)
        for (buffer, index) in buffers {
            encoder.setBuffer(buffer, offset: 0, index: index)
        }

        let width = max(1, min(pipeline.threadExecutionWidth, pipeline.maxTotalThreadsPerThreadgroup))
        let groups = (count + width - 1) / width
        encoder.dispatchThreadgroups(
            MTLSize(width: groups, height: 1, depth: 1),
            threadsPerThreadgroup: MTLSize(width: width, height: 1, depth: 1)
        )
        encoder.endEncoding()
    }
}
