// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "RosalinaAccelerator",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "RosalinaAcceleratorCore", targets: ["RosalinaAcceleratorCore"]),
        .executable(name: "RosalinaAccelerator", targets: ["RosalinaAccelerator"])
    ],
    targets: [
        .target(
            name: "RosalinaAcceleratorCore",
            path: "Sources/RosalinaAcceleratorCore",
            linkerSettings: [
                .linkedFramework("Security"),
                .linkedFramework("Network"),
                .linkedFramework("Metal")
            ]
        ),
        .executableTarget(
            name: "RosalinaAccelerator",
            dependencies: ["RosalinaAcceleratorCore"],
            path: "Sources/RosalinaAccelerator",
            exclude: ["AcceleratorController.swift", "ContentView.swift"]
        ),
        .testTarget(
            name: "RosalinaAcceleratorCoreTests",
            dependencies: ["RosalinaAcceleratorCore"],
            path: "Tests/RosalinaAcceleratorCoreTests"
        )
    ]
)
