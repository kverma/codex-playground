// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RevealSwift",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "RevealSwiftCore", targets: ["RevealSwiftCore"]),
        .executable(name: "revealswift", targets: ["revealswift"])
    ],
    targets: [
        .target(name: "RevealSwiftCore"),
        .executableTarget(name: "revealswift", dependencies: ["RevealSwiftCore"]),
        .testTarget(name: "RevealSwiftCoreTests", dependencies: ["RevealSwiftCore"])
    ]
)
