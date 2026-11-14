// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "RlcdCompanion",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "RlcdProtocol", targets: ["RlcdProtocol"]),
        .executable(name: "RlcdCompanion", targets: ["RlcdCompanion"]),
    ],
    targets: [
        .target(name: "RlcdProtocol"),
        .executableTarget(name: "RlcdCompanion", dependencies: ["RlcdProtocol"]),
        .testTarget(
            name: "RlcdProtocolTests",
            dependencies: ["RlcdProtocol"]
        ),
    ]
)
